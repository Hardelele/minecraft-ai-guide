"""The open world's trainer (docs/rl/OPEN-WORLD.md section 5): DreamerV3-like, the teacher's record first (behaviour
cloning and the world model's first data), then the agent's own play on the engine's arenas with v4's outcomes and
curiosity in imagination.

    python tools/rl/dreamer/train.py --env fake --steps 3000 --out build/rl/open/runs/smoke           # no game
    python tools/rl/governor.py run -- python tools/rl/dreamer/train.py --env engine --ports 27300 \\
        --teacher build/rl/open/teacher/rec.jsonl.gz --bc-steps 2000 --steps 200000 --out build/rl/open/runs/proto1

Writes metrics.csv (the updates), episodes.jsonl (every episode: return, components, achievements, deaths, night
survived), ckpt.pt.
"""
from __future__ import annotations

import argparse
import csv
import gzip
import json
import os
import sys
import time
from pathlib import Path

import numpy as np
import torch

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE.parents[1]))
from dreamer import spaces as S  # noqa: E402
from dreamer.agent import DEFAULT, Dreamer, params  # noqa: E402
from dreamer.replay import Replay  # noqa: E402


def load_teacher(paths, rep: Replay, stream0=10_000_000):
    n = bc = 0
    streams = {}
    for path in paths:
        with gzip.open(path, "rt", encoding="utf-8") as f:
            for line in f:
                fr = json.loads(line)
                key = (path, fr["ep"], fr["agent"])
                first = key not in streams
                if first:
                    streams[key] = stream0 + len(streams)
                o = S.from_strings(fr["obs"])
                ok = not fr["unrep"]
                rep.add(streams[key], o, np.asarray(fr["act"]), 0.0, 1.0, first, np.asarray(fr["mask"], bool), rew_ok=False, bc=ok)
                n += 1
                bc += ok
    return n, bc


class Runner:
    """one environment (an arena or the fake): its agents' states, the reward, the streams into the replay"""

    def __init__(self, env, rep, agent, kind, seed, reset_kw):
        from open_reward import OpenReward
        self.env, self.rep, self.agent, self.kind, self.seed, self.kw = env, rep, agent, kind, seed, reset_kw
        self.OpenReward = OpenReward
        self.eps = 0
        self.new_episode()

    def new_episode(self):
        self.o = self.env.reset(seed=self.seed * 1000 + self.eps, **self.kw)
        self.reward = self.OpenReward()
        self.state, self.prev, self.stream, self.ret = {}, {}, {}, {}
        self.comps = {}
        self.ach = []
        self.t0 = time.time()
        self.steps = 0
        self.night_deaths = 0

    def stream_of(self, a):
        if a not in self.stream:
            self.stream[a] = hash((self.kind, self.seed, self.eps, a)) & 0x7FFFFFFFFFFF
        return self.stream[a]

    def step(self, greedy=False):
        o = self.o
        pts = self.reward.frame(o["frame"], o["agents"])
        for e in o["frame"]["events"]:
            if e["type"] == "achievement":
                self.ach.append(e["id"])
        ags = list(o["agents"])
        if ags:
            dev = self.agent.device
            obs = S.stack([o["obs"][a] for a in ags], dev)
            hs, zs, prev, first = [], [], [], []
            for a in ags:
                if a not in self.state:
                    h, z = self.agent.initial(1)
                    self.state[a] = (h[0], z[0])
                    self.prev[a] = np.zeros(5, np.int64)
                    first.append(True)
                else:
                    first.append(False)
                hs.append(self.state[a][0]); zs.append(self.state[a][1]); prev.append(self.prev[a])
            mask = torch.as_tensor(np.stack([o["mask"][a] for a in ags]), device=dev)
            acts, (h, z) = self.agent.act(obs, (torch.stack(hs), torch.stack(zs)), torch.as_tensor(np.stack(prev), device=dev),
                                          torch.as_tensor(first, device=dev), mask, greedy)
            acts = acts.cpu().numpy()
            for i, a in enumerate(ags):
                self.state[a] = (h[i], z[i])
                self.prev[a] = acts[i]
                r = pts.get(a, (0.0, {}))[0]
                self.ret[a] = self.ret.get(a, 0.0) + r
                self.rep.add(self.stream_of(a), {k: o["obs"][a][k] for k in ("vox", "seen", "vec", "ent", "hot")}, acts[i], r, 1.0, first[i], o["mask"][a])
            self.o = self.env.step({a: acts[i].tolist() for i, a in enumerate(ags)})
        else:
            self.o = self.env.step({})
        self.steps += 1
        if self.o["done"]:
            return self.finish()
        return None

    def finish(self):
        o = self.o
        pts = self.reward.frame(o["frame"], [a for a in self.stream])
        reason = o["info"].get("done_reason", "")
        for a, (p, _) in pts.items():
            self.ret[a] = self.ret.get(a, 0.0) + p
            self.rep.end(self.stream_of(a), p, 0.0 if reason == "lost" else 1.0)
        st = o["info"].get("stats", {})
        rec = {"episode": self.eps, "kind": self.kind, "steps": self.steps, "done_reason": reason, "return": {a: round(v, 3) for a, v in self.ret.items()},
               "components": {k: round(v, 3) for k, v in self.reward.episode().items()}, "achievements": sorted(set(self.ach)), "stats": st,
               "survived_night": reason == "days" and not st.get("deaths_night", 0), "info_logs": o["info"].get("logs"), "wall_s": round(time.time() - self.t0, 1)}
        self.eps += 1
        self.new_episode()
        return rec


def make_env(kind, port):
    if kind == "fake":
        from dreamer.fake_env import FakeEnv
        return FakeEnv()
    from open_env import OpenEnv
    return OpenEnv(port)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--env", choices=["fake", "engine"], default="fake")
    ap.add_argument("--ports", default="27300")
    ap.add_argument("--crew", type=int, default=1)
    ap.add_argument("--days", type=int, default=1)
    ap.add_argument("--time", type=int, default=0)
    ap.add_argument("--waves", default="normal")
    ap.add_argument("--steps", type=int, default=5000, help="env steps (all agents' steps of all arenas)")
    ap.add_argument("--teacher", default="", help="recorded teacher files, comma separated")
    ap.add_argument("--bc-steps", type=int, default=0, help="updates on the teacher's record alone before playing")
    ap.add_argument("--bc-weight", type=float, default=1.0)
    ap.add_argument("--bc-final", type=float, default=0.05)
    ap.add_argument("--bc-decay", type=int, default=20000, help="updates over which the BC weight goes to --bc-final")
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--seq", type=int, default=32)
    ap.add_argument("--train-every", type=int, default=32)
    ap.add_argument("--warmup", type=int, default=1000)
    ap.add_argument("--capacity", type=int, default=500_000)
    ap.add_argument("--small", action="store_true", help="a small model (the smoke test)")
    ap.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    ap.add_argument("--threads", type=int, default=2)
    ap.add_argument("--out", default="build/rl/runs/run")
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--init", default="")
    ap.add_argument("--resume", action="store_true", help="--init is this run's own checkpoint: the updates and the owner's minutes go on")
    ap.add_argument("--async", dest="asyn", action="store_true", help="the collector in its own process, all the arenas at once (run_async.py)")
    ap.add_argument("--stage", default="pickaxe", choices=["pickaxe", "night"])
    ap.add_argument("--ratio", type=float, default=3.0, help="--async: updates per train-every steps of the environment while collecting")
    ap.add_argument("--paused-ratio", type=float, default=16.0, help="--async: the same while the governor has the arenas paused (the replay again)")
    ap.add_argument("--fp32", action="store_true", help="--async: no bf16 in the encoder, decoder and heads")
    ap.add_argument("--lead", type=int, default=20, help="--async: updates the collector may run ahead of (train-every steps each)")
    ap.add_argument("--bc-batch", type=int, default=32, help="--async: the teacher's rows an update")
    a = ap.parse_args()
    torch.set_num_threads(a.threads)
    torch.manual_seed(a.seed)
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    cfg = dict(DEFAULT)
    if a.small:
        cfg.update({"embed": 128, "deter": 128, "stoch": 8, "classes": 8, "hidden": 128, "imag_starts": 256})
    agent = Dreamer(cfg, a.device)
    start_updates = 0
    if a.init:
        ck = torch.load(a.init, map_location=a.device, weights_only=False)
        agent.load_state_dict(ck["model"])
        if a.resume:   # the same run again from its checkpoint: its count of updates goes on (the BC weight's decay too)
            start_updates = int(ck.get("updates", 0))
    print(f"model: {params(agent) / 1e6:.1f} M parameters on {a.device}", flush=True)
    (out / "args.json").write_text(json.dumps({**vars(a), "cfg": cfg}, indent=1))
    rep = Replay(a.capacity, a.seed)
    teach = None
    if a.teacher:
        teach = Replay(400_000, a.seed + 1)
        n, bc = load_teacher([p for p in a.teacher.split(",") if p], teach)
        print(f"teacher: {n} steps, {bc} to imitate", flush=True)
    mf = open(out / "updates.csv", "a", newline="")
    mw = None
    ef = open(out / "episodes.jsonl", "a")
    updates, t0 = start_updates, time.time()

    def log(m, phase, steps=None, upd=None):
        nonlocal mw
        m = {"update": updates if upd is None else upd, "phase": phase, "env_steps": env_steps if steps is None else steps, "wall_s": round(time.time() - t0, 1), **{k: round(v, 5) for k, v in m.items()}}
        if mw is None:
            keys = list(m.keys()) + [k for k in ("bc/nll", "bc/acc_move", "bc/acc_jump", "bc/acc_yaw", "bc/acc_pitch", "bc/acc_act", "wm/loss_teacher", "wm/vox_teacher", "wm/vec_teacher") if k not in m]
            for k in ("ac/ret", "ac/r_ext", "ac/curiosity", "ac/cur_off", "ac/entropy", "ac/scale", "ac/actor", "ac/critic"):
                if k not in keys:
                    keys.append(k)
            mw = csv.DictWriter(mf, keys, extrasaction="ignore")
            mw.writeheader()
        mw.writerow(m)
        mf.flush()

    env_steps = 0
    # 1. the teacher's record alone: the world model and behaviour cloning
    for i in range(a.bc_steps):
        bb = teach.sample(a.batch, a.seq)
        m = agent.train_step(bb, bc_batch=bb, bc_weight=a.bc_weight, train_ac=False) if bb is not None else {}
        updates += 1
        if i % 50 == 0:
            log(m, "bc")
            print(f"bc {i}: wm {m.get('wm/loss', 0):.2f} vox {m.get('wm/vox', 0):.2f} bc nll {m.get('bc/nll', 0):.2f} acc act {m.get('bc/acc_act', 0):.2f} "
                  f"move {m.get('bc/acc_move', 0):.2f} yaw {m.get('bc/acc_yaw', 0):.2f}", flush=True)
    if a.bc_steps:
        torch.save({"model": agent.state_dict(), "cfg": cfg, "updates": updates}, out / "bc.pt")
    # 2. its own play
    if a.asyn:
        from dreamer.run_async import main_async
        env_steps, updates = main_async(a, agent, cfg, rep, teach, out, t0, updates, log)
        print(f"done: {env_steps} env steps, {updates} updates, {time.time() - t0:.0f} s -> {out}")
        return
    ports = [int(p) for p in a.ports.split(",")]
    kw = {} if a.env == "fake" else {"crew": a.crew, "days": a.days, "time": a.time, "waves": a.waves}
    runners = [Runner(make_env(a.env, p), rep, agent, a.env + str(p), a.seed + i, kw) for i, p in enumerate(ports)]
    last_log = 0
    while env_steps < a.steps:
        for r in runners:
            rec = r.step()
            env_steps += max(1, len(r.o.get("agents", [])))
            if rec:
                ef.write(json.dumps(rec) + "\n")
                ef.flush()
                print(f"episode {rec['episode']} ({rec['kind']}): {rec['done_reason']}, {rec['steps']} steps, return {rec['return']}, "
                      f"achievements {rec['achievements']}, stats {rec['stats']}, night {rec['survived_night']}", flush=True)
        if rep.size >= a.warmup and env_steps // a.train_every > updates - a.bc_steps:
            b = rep.sample(a.batch, a.seq)
            if b is None:
                continue
            k = updates - a.bc_steps
            w = a.bc_final + (a.bc_weight - a.bc_final) * max(0.0, 1 - k / max(1, a.bc_decay)) if teach is not None else 0.0
            bb = teach.sample(a.batch // 2, a.seq) if teach is not None else None
            m = agent.train_step(b, bc_batch=bb, bc_weight=w)
            m["bc/weight"] = w
            updates += 1
            if updates - last_log >= 25:
                last_log = updates
                log(m, "rl")
                print(f"update {updates} steps {env_steps}: wm {m['wm/loss']:.2f} kl {m['wm/kl']:.2f} rew {m['wm/rew']:.3f} ret {m.get('ac/ret', 0):.3f} "
                      f"cur {m.get('ac/curiosity', 0):.4f} ent {m.get('ac/entropy', 0):.2f} bc {m.get('bc/nll', 0):.2f} ({time.time() - t0:.0f} s)", flush=True)
            if updates % 1000 == 0:
                torch.save({"model": agent.state_dict(), "cfg": cfg, "updates": updates}, out / "ckpt.pt")
    torch.save({"model": agent.state_dict(), "cfg": cfg, "updates": updates}, out / "ckpt.pt")
    for r in runners:
        r.env.close()
    print(f"done: {env_steps} env steps, {updates} updates, {time.time() - t0:.0f} s -> {out}")


if __name__ == "__main__":
    main()
