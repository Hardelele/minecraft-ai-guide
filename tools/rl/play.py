"""A policy on the simulator: success, return, time, waves cleared, what it chose (a look; a video's captions).

    python tools/rl/play.py --weights build/rl/runs/sim1/final.bin --scenario pickaxe --n 200
    python tools/rl/play.py --weights ... --scenario waves:easy --trace      # one episode, step by step
    python tools/rl/play.py --random --scenario wood                         # uniform over the allowed actions
"""
from __future__ import annotations

import argparse
import collections
import json
import sys
from pathlib import Path

import numpy as np
import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import spec  # noqa: E402
from policy import Policy  # noqa: E402
from sim_env import SimEnv  # noqa: E402


def run(pol, scenario, n, seed=100, greedy=False, trace=False, rand=False, crew=0, url=None):
    if url:   # rl/env's real environment (one server)
        from real_env import RealEnv
        env = RealEnv(url)
    else:
        env = SimEnv(seed)
    rng = np.random.default_rng(seed)
    out = collections.Counter()
    t_succ, rets, chosen, reasons = [], [], collections.Counter(), collections.Counter()
    for ep in range(n):
        o = env.reset(scenario, seed * 1000 + ep, crew=crew or 1 + ep % (4 if scenario.startswith("waves") else 2))
        ret, nd = 0.0, 0
        crew_n = len(o["agents"])
        while not o["done"]:
            acts = {}
            obs = torch.tensor(np.asarray(o["obs"], np.float32))
            m = torch.tensor(np.asarray(o["mask"], bool))
            if rand:
                a = [int(rng.choice(np.flatnonzero(mm[1:]) + 1)) for mm in o["mask"]]
            else:
                with torch.no_grad():
                    a = pol.act(obs, m, greedy=greedy)[0].tolist()
            for i, k in zip(o["agents"], a):
                acts[i] = k
                chosen[spec.ACTIONS[k]] += 1
                if trace:
                    print(f"t={o['info'].get('t', 0):7.1f} agent {i} -> {spec.ACTIONS[k]}", flush=True)
            o = env.step(acts)
            ret += sum(o["reward"]) / max(1, crew_n)
            nd += 1
            if trace and not url:
                ag = env.agents
                print("        bag:", [dict(x.bag) for x in ag], "hp", [round(x.health) for x in ag], "r", [round(r, 3) for r in o["reward"]])
        out["success"] += o["info"]["success"]
        out["noop_end"] += o["info"]["done_reason"] == "noop"
        reasons[o["info"]["done_reason"]] += 1
        out["cleared"] += o["info"]["cleared"]
        if o["info"]["success"]:
            t_succ.append(o["info"]["t"])
        out["game_s"] += o["info"]["t"]
        rets.append(ret)
        if trace:
            print("done:", o["info"])
    if url:
        env.close_env()   # one connection a server: the next scenario connects again
    return {"scenario": scenario, "episodes": n, "success": out["success"] / n, "noop_end": out["noop_end"] / n,
            "return": float(np.mean(rets)), "cleared": out["cleared"] / n, "game_s": out["game_s"] / n,
            "done_reasons": dict(reasons), "game_s_to_success": float(np.mean(t_succ)) if t_succ else None,
            "top_actions": chosen.most_common(8)}


def load(path):
    from export_policy import read_policy
    return read_policy(Path(path))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--weights")
    ap.add_argument("--scenario", default="all")
    ap.add_argument("--n", type=int, default=200)
    ap.add_argument("--greedy", action="store_true")
    ap.add_argument("--trace", action="store_true")
    ap.add_argument("--random", action="store_true", help="uniform over the allowed actions but noop")
    ap.add_argument("--crew", type=int, default=0)
    ap.add_argument("--url", help="host:port of an rl/env server instead of the simulator")
    ap.add_argument("--seed", type=int, default=100)
    a = ap.parse_args()
    pol = None if a.random else load(a.weights)
    for sc in (list(spec.SCEN) if a.scenario == "all" else a.scenario.split(",")):
        print(json.dumps(run(pol, sc, 1 if a.trace else a.n, greedy=a.greedy, trace=a.trace, rand=a.random, crew=a.crew, url=a.url, seed=a.seed), ensure_ascii=False), flush=True)
