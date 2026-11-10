"""A net against the reward v4's loopholes (docs/rl/REWARD-v4.md) in the Python simulator: how its episodes end (noop:
the surrender), what share of its decisions are the box and wait (blocks round itself, wait:5, look_around) and the
walks out and back, its breaches, kills, deaths, and its return under v3 and under v4 (the same seeds).

    python tools/rl/eval_loopholes.py build/rl/runs/v4sim1/final.pt [--n 24] [--scen waves:easy,waves:normal,night:shelter]
"""
from __future__ import annotations

import argparse
import collections
import sys
from pathlib import Path

import numpy as np
import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import spec  # noqa: E402
from policy import Policy  # noqa: E402
from sim_env import SimEnv  # noqa: E402

BOX = ("place:block@left", "place:block@right", "place:block@back", "place:block@above", "pillar:up", "wait:5", "look_around")
WALK = ("go_to:frontier", "go_to:base", "retreat:home")


def run(pol, sc: str, n: int, reward: str, crew: int = 2) -> dict:
    st = collections.Counter()
    rets = []
    for s in range(n):
        env = SimEnv(10_000 + s, reward=reward)
        o = env.reset(sc, 10_000 + s, crew=crew)
        ret, k = 0.0, 0
        while not o["done"] and k < 5000:
            ret += sum(o["reward"])
            with torch.no_grad():
                act, _, _, _ = pol.act(torch.tensor(np.asarray(o["obs"], np.float32)), torch.tensor(np.asarray(o["mask"], bool)))
            acts = {}
            for i, a in zip(o["agents"], act.tolist()):
                acts[i] = int(a)
                name = spec.ACTIONS[int(a)]
                st["dec"] += 1
                st["box"] += name in BOX
                st["walk"] += name in WALK
                st["wave_dec"] += env.phase == "wave"
                st["wave_box"] += env.phase == "wave" and name in BOX
            o = env.step(acts)
            k += 1
        ret += sum(o["reward"])
        rets.append(ret / crew)
        inf = o["info"]
        st["end_" + inf["done_reason"]] += 1
        st["success"] += bool(inf["success"])
        st["breaches"] += inf["breaches"]
        st["kills"] += inf["kills"]
        st["cleared"] += inf["cleared"]
    out = {"return": round(float(np.mean(rets)), 2), "success": round(st["success"] / n, 3),
           "noop_end": round(st["end_noop"] / n, 3), "lost": round(st["end_lost"] / n, 3),
           "box_share": round(st["box"] / max(1, st["dec"]), 3), "box_in_wave": round(st["wave_box"] / max(1, st["wave_dec"]), 3),
           "walk_share": round(st["walk"] / max(1, st["dec"]), 3),
           "breaches": round(st["breaches"] / n, 2), "kills": round(st["kills"] / n, 2), "cleared": round(st["cleared"] / n, 2)}
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ckpt", nargs="+")
    ap.add_argument("--n", type=int, default=24)
    ap.add_argument("--scen", default="waves:easy,waves:normal,night:shelter")
    a = ap.parse_args()
    torch.set_num_threads(2)
    for path in a.ckpt:
        ck = torch.load(path, map_location="cpu", weights_only=False)
        pol = Policy(ck["d"], ck["a"], ck.get("hidden", 256))
        pol.load_state_dict(ck["model"])
        pol.eval()
        print(path)
        for sc in a.scen.split(","):
            torch.manual_seed(0)
            r4 = run(pol, sc, a.n, "v4")
            torch.manual_seed(0)
            r3 = run(pol, sc, a.n, "v3")
            print("  %-14s v4 %s | v3 return %s" % (sc, r4, r3["return"]))


if __name__ == "__main__":
    main()
