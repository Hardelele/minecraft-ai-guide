"""The exams of an open-world agent (docs/rl/OPEN-WORLD.md section 7), on an arena of the engine, in the same space of
a player's actions:

  pickaxe — the open world from the morning, 600 game seconds: a wooden pickaxe got (and a stone one, a bonus)?
  sword   — the same: a sword?
  night   — the open world from 11 000 (dusk soon), to the morning: alive all night, the base without a breach?

    python tools/rl/dreamer/exam.py --ckpt build/rl/open/runs/proto1/ckpt.pt --port 27300 --n 3
    python tools/rl/dreamer/exam.py --random --port 27300 --n 3      # the random player's marks, for comparison
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import torch

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from dreamer import spaces as S  # noqa: E402
from dreamer.agent import Dreamer  # noqa: E402
from open_env import OpenEnv, random_action  # noqa: E402

LOG64 = np.log1p(64)


def bag(vec, item):
    return int(round(np.expm1(vec[S.VI["bag_" + item]] * LOG64)))


def run(env, agent, exam, seed, rng, greedy):
    kw = {"wood": dict(time=0, max_steps=1500, waves="off"), "pickaxe": dict(time=0, max_steps=3000, waves="off"), "sword": dict(time=0, max_steps=3000, waves="off"),
          "night": dict(time=11000, days=1)}[exam]
    o = env.reset(seed=seed, **kw)
    state, prev, sticky, ach = {}, {}, {}, set()
    best = {"wooden_pickaxe": 0, "stone_pickaxe": 0, "wooden_sword": 0, "stone_sword": 0, "log": 0}
    while not o["done"]:
        ags = o["agents"]
        for e in o["frame"]["events"]:
            if e["type"] == "achievement":
                ach.add(e["id"])
        for a in ags:
            for k in best:
                best[k] = max(best[k], bag(o["obs"][a]["vec"], k))
        if not ags:
            o = env.step({})
            continue
        if agent is None:
            acts = {a: random_action(rng, o["mask"][a], sticky.get(a)) for a in ags}
            sticky.update(acts)
        else:
            obs = S.stack([o["obs"][a] for a in ags], agent.device)
            first = [a not in state for a in ags]
            for a in ags:
                if a not in state:
                    h, z = agent.initial(1)
                    state[a], prev[a] = (h[0], z[0]), np.zeros(5, np.int64)
            mask = torch.as_tensor(np.stack([o["mask"][a] for a in ags]), device=agent.device)
            act, (h, z) = agent.act(obs, (torch.stack([state[a][0] for a in ags]), torch.stack([state[a][1] for a in ags])),
                                    torch.as_tensor(np.stack([prev[a] for a in ags]), device=agent.device), torch.as_tensor(first, device=agent.device), mask, greedy)
            act = act.cpu().numpy()
            acts = {}
            for i, a in enumerate(ags):
                state[a], prev[a], acts[a] = (h[i], z[i]), act[i], act[i].tolist()
        o = env.step(acts)
    st = o["info"].get("stats", {})
    ok = {"wood": best["log"] > 0 or "mineWood" in ach, "pickaxe": best["wooden_pickaxe"] > 0, "sword": best["wooden_sword"] + best["stone_sword"] > 0,
          "night": o["info"].get("done_reason") == "days" and not st.get("deaths_night", 0) and not st.get("breaches", 0)}[exam]
    return {"exam": exam, "seed": seed, "pass": ok, "done_reason": o["info"].get("done_reason"), "best": best, "achievements": sorted(ach), "stats": st}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ckpt", default="")
    ap.add_argument("--random", action="store_true")
    ap.add_argument("--port", type=int, default=27300)
    ap.add_argument("--n", type=int, default=3)
    ap.add_argument("--exams", default="pickaxe,night")
    ap.add_argument("--greedy", action="store_true")
    ap.add_argument("--seed", type=int, default=9000)
    ap.add_argument("--out", default="")
    a = ap.parse_args()
    agent = None
    if not a.random:
        ck = torch.load(a.ckpt, map_location="cuda" if torch.cuda.is_available() else "cpu", weights_only=False)
        agent = Dreamer(ck["cfg"], "cuda" if torch.cuda.is_available() else "cpu")
        agent.load_state_dict(ck["model"])
        agent.eval()
    env = OpenEnv(a.port)
    rng = np.random.default_rng(a.seed)
    rows = []
    for exam in a.exams.split(","):
        for i in range(a.n):
            r = run(env, agent, exam, a.seed + i, rng, a.greedy)
            rows.append(r)
            print(json.dumps(r), flush=True)
    for exam in a.exams.split(","):
        rs = [r for r in rows if r["exam"] == exam]
        print(f"{exam}: {sum(r['pass'] for r in rs)}/{len(rs)}")
    if a.out:
        Path(a.out).write_text("\n".join(json.dumps(r) for r in rows) + "\n")
    env.close()


if __name__ == "__main__":
    main()
