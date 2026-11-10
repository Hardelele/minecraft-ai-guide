"""The open world's collector (a process of its own: the trainer's Python and its launches do not hold it up): several
arenas stepped at once (their answers awaited in threads), all their agents acted in one batch on the GPU with a copy
of the actor that the trainer refreshes, the steps sent back to the trainer through a queue. The stage (the current
goal) is read from a shared value at each reset.
"""
from __future__ import annotations

import queue
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np
import torch

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

STAGES = ["pickaxe", "night"]
STAGE_KW = {"pickaxe": dict(time=0, days=1, waves="off", max_steps=3000), "night": dict(time=0, days=1, waves="normal")}


class Arena:
    def __init__(self, env, kind, seed):
        from open_reward import OpenReward
        self.env, self.kind, self.seed = env, kind, seed
        self.OpenReward = OpenReward
        self.eps = 0
        self.o = None

    def reset(self, stage):
        self.stage = stage
        kw = {} if self.kind.startswith("fake") else STAGE_KW[stage]
        self.o = self.env.reset(seed=self.seed * 100000 + self.eps, **kw)
        self.reward = self.OpenReward()
        self.state, self.prev, self.stream, self.ret = {}, {}, {}, {}
        self.ach, self.steps, self.t0 = set(), 0, time.time()

    def stream_of(self, a):
        if a not in self.stream:
            self.stream[a] = hash((self.kind, self.seed, self.eps, a)) & 0x7FFFFFFFFFFF
        return self.stream[a]


def run(ports, kinds, cfg, q_out, q_w, stage_val, stop, seed, device, allowed=None):
    from dreamer import spaces as S
    from dreamer.agent import Dreamer
    torch.set_num_threads(1)
    agent = Dreamer(cfg, device)
    agent.eval()
    envs = []
    for i, (p, k) in enumerate(zip(ports, kinds)):
        if k == "fake":
            from dreamer.fake_env import FakeEnv
            env = FakeEnv(seed=seed + i)
        else:
            from open_env import OpenEnv
            env = OpenEnv(p)
        envs.append(Arena(env, k + str(p), seed + i))
    pool = ThreadPoolExecutor(max(1, len(envs)))
    list(pool.map(lambda a: a.reset(STAGES[stage_val.value]), envs))
    sent = 0
    while not stop.is_set():
        if allowed is not None and sent > allowed.value:   # the trainer is behind (its replay ratio kept): wait for it
            time.sleep(0.02)
            continue
        try:
            sd = q_w.get_nowait()
            agent.load_state_dict(sd, strict=False)
        except queue.Empty:
            pass
        # the rewards of what came, the agents' obs of every arena in one batch
        rows, out = [], []
        for ar in envs:
            o = ar.o
            pts = ar.reward.frame(o["frame"], o["agents"])
            for e in o["frame"]["events"]:
                if e["type"] == "achievement":
                    ar.ach.add(e["id"])
            for a in o["agents"]:
                first = a not in ar.state
                if first:
                    h, z = agent.initial(1)
                    ar.state[a], ar.prev[a] = (h[0], z[0]), np.zeros(5, np.int64)
                r = pts.get(a, (0.0, {}))[0]
                ar.ret[a] = ar.ret.get(a, 0.0) + r
                rows.append((ar, a, first, r))
        acts = {}
        if rows:
            obs = S.stack([ar.o["obs"][a] for ar, a, _, _ in rows], device)
            mask = torch.as_tensor(np.stack([ar.o["mask"][a] for ar, a, _, _ in rows]), device=device)
            hs = torch.stack([ar.state[a][0] for ar, a, _, _ in rows])
            zs = torch.stack([ar.state[a][1] for ar, a, _, _ in rows])
            prev = torch.as_tensor(np.stack([ar.prev[a] for ar, a, _, _ in rows]), device=device)
            first = torch.as_tensor([f for _, _, f, _ in rows], device=device)
            act, (h, z) = agent.act(obs, (hs, zs), prev, first, mask)
            act = act.cpu().numpy()
            for i, (ar, a, f, r) in enumerate(rows):
                ar.state[a], ar.prev[a] = (h[i], z[i]), act[i]
                acts.setdefault(id(ar), {})[a] = act[i].tolist()
                ob = ar.o["obs"][a]
                out.append(("step", ar.stream_of(a), {k: ob[k] for k in ("vox", "seen", "vec", "ent", "hot")}, act[i], r, f, ar.o["mask"][a]))
        q_out.put(("steps", out))
        sent += len(out)

        def advance(ar):
            ar.o = ar.env.step(acts.get(id(ar), {}))
            ar.steps += 1
            return ar
        list(pool.map(advance, envs))
        ends = []
        for ar in envs:
            if not ar.o["done"]:
                continue
            o = ar.o
            pts = ar.reward.frame(o["frame"], list(ar.stream))
            reason = o["info"].get("done_reason", "")
            for e in o["frame"]["events"]:
                if e["type"] == "achievement":
                    ar.ach.add(e["id"])
            for a, (p, _) in pts.items():
                ar.ret[a] = ar.ret.get(a, 0.0) + p
                q_out.put(("end", ar.stream_of(a), p, 0.0 if reason == "lost" else 1.0))
            st = o["info"].get("stats", {})
            ach = set(ar.ach) | set(x for v in (o["info"].get("achievements") or {}).values() for x in v)
            rec = {"episode": ar.eps, "kind": ar.kind, "stage": ar.stage, "steps": ar.steps, "done_reason": reason,
                   "return": {a: round(v, 3) for a, v in ar.ret.items()}, "components": {k: round(v, 3) for k, v in ar.reward.episode().items()},
                   "achievements": sorted(ach), "stats": st, "night_reached": o["info"].get("night", 0) >= 1,
                   "survived_night": reason == "days" and not st.get("deaths_night", 0) and not st.get("breaches", 0), "wall_s": round(time.time() - ar.t0, 1)}
            q_out.put(("episode", rec))
            ar.eps += 1
            ends.append(ar)
        if ends:
            stage = STAGES[stage_val.value]
            list(pool.map(lambda a: a.reset(stage), ends))
    for ar in envs:
        ar.env.close()
