"""Measures environments that speak the protocol (servers, the engine's arenas) or the Python simulator under a policy:
decisions a second, game seconds a second (over the wall time, and over the simulation's own time: info.sim_ms), the
resets, the episodes' outcomes. One thread an environment (the policy in each, torch on 1 thread).

    python tools/rl/engine_bench.py --ports 26200,26201 --scenario wood --seconds 120 --weights rl/snapshots/v31-sim-epoch-472.bin
    python tools/rl/engine_bench.py --sim 4 --scenario wood --seconds 60 --weights ...      # the simulator: 4 envs in 4 processes
    python tools/rl/engine_bench.py --ports 26200 --scenario wood --episodes 20 --seed 900 --trace out.jsonl   # scripted: the same seeds

`--trace` writes each episode's outcome and its actions (the equivalence check: tools/rl/engine_equiv.py).
"""
from __future__ import annotations

import argparse
import json
import multiprocessing as mp
import os
import sys
import threading
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))


def lower_priority():
    """Idle while League of Legends runs, else BelowNormal (the owner's machine comes first)"""
    if os.name != "nt":
        return
    import ctypes
    import subprocess
    lol = "League of Legends.exe" in subprocess.run(["tasklist", "/FI", "IMAGENAME eq League of Legends.exe", "/NH"], capture_output=True, text=True).stdout
    ctypes.windll.kernel32.SetPriorityClass(ctypes.windll.kernel32.GetCurrentProcess(), 0x40 if lol else 0x4000)


def make_policy(weights):
    import torch
    torch.set_num_threads(1)
    if not weights:
        return None
    from export_policy import read_policy
    return read_policy(Path(weights))


GREEDY = False   # --greedy: the policy's most likely action (a script: the same observations, the same choices)


def choose(pol, o, rng, script=None):
    """the policy's draw (its own sampling), or uniform over the allowed actions but noop, or a script's"""
    if script is not None:
        return {i: script(i, o) for i in o["agents"]}
    if pol is None:
        return {i: int(rng.choice(np.flatnonzero(np.asarray(m[1:], bool)) + 1)) for i, m in zip(o["agents"], o["mask"])}
    import torch
    with torch.no_grad():
        a = pol.act(torch.tensor(np.asarray(o["obs"], np.float32)), torch.tensor(np.asarray(o["mask"], bool)), greedy=GREEDY)[0].tolist()
    return dict(zip(o["agents"], a))


def run_env(env, name, scenario, seconds, episodes, seed, pol, crew, trace, out, torch_seed=0):
    import torch
    torch.manual_seed(torch_seed)
    rng = np.random.default_rng(seed)
    st = {"env": name, "decisions": 0, "steps": 0, "episodes": 0, "reasons": {}, "success": 0, "game_s": 0.0, "sim_ms": 0.0,
          "reset_ms": [], "returns": [], "policy_ms": 0.0}
    t0 = time.time()
    ep = 0
    while (episodes and ep < episodes) or (not episodes and time.time() - t0 < seconds):
        tr = time.time()
        o = env.reset(scenario, seed + ep, crew=crew)
        st["reset_ms"].append(o["info"].get("reset_ms") if o["info"].get("reset_ms") is not None else (time.time() - tr) * 1000)
        ret, acts_log, moves = 0.0, [], {}
        while not o["done"]:
            tp = time.time()
            acts = choose(pol, o, rng)
            st["policy_ms"] += (time.time() - tp) * 1000
            acts_log.append([o["info"].get("t", 0.0), {str(k): v for k, v in acts.items()}])
            st["decisions"] += len(acts)
            st["steps"] += 1
            o = env.step(acts)
            for ev in o["info"].get("events") or ():   # the credit moved to past decisions (v4: its components sum to 0)
                if ev.get("type") == "reward" and str(ev.get("key", "")).startswith("credit."):
                    moves[ev["key"]] = round(moves.get(ev["key"], 0.0) + abs(float(ev.get("points", 0.0))), 4)
            ret += sum(o["reward"]) / max(1, len(o["agents"]) or crew)
            if not episodes and time.time() - t0 > seconds * 3:
                break
        info = o["info"]
        st["episodes"] += 1
        why = info.get("done_reason", "?")
        st["reasons"][why] = st["reasons"].get(why, 0) + 1
        st["success"] += bool(info.get("success"))
        st["game_s"] += info.get("t", 0.0)
        st["sim_ms"] += info.get("sim_ms", 0.0) or 0.0
        st["returns"].append(round(ret, 3))
        if trace is not None:
            trace.append({"env": name, "scenario": scenario, "seed": seed + ep, "done_reason": why, "success": bool(info.get("success")),
                          "t": info.get("t"), "return": round(ret, 3), "achievements": info.get("achievement_ids"),
                          "measured": info.get("measured"), "episode_components": info.get("episode_components"), "events4": info.get("events4"), "moves": moves, "actions": acts_log})
        ep += 1
    st["wall_s"] = round(time.time() - t0, 2)
    if hasattr(env, "close_env"):
        env.close_env()
    out.append(st)


def sim_worker(args):
    weights, scenario, seconds, episodes, seed, crew, k = args
    lower_priority()
    from sim_env import SimEnv
    pol = make_policy(weights)
    out, trace = [], []
    env = SimEnv(seed * 1000 + k)
    run_env(env, "sim-%d" % k, scenario, seconds, episodes, seed + 100000 * k, pol, crew, trace, out, torch_seed=k)
    return out[0]


def summary(rows, label, scenario):
    for s in rows:
        s["decisions_per_s"] = round(s["decisions"] / s["wall_s"], 2)
        s["game_s_per_wall_s"] = round(s["game_s"] / s["wall_s"], 1)
        if s["sim_ms"]:
            s["game_s_per_sim_s"] = round(s["game_s"] / (s["sim_ms"] / 1000.0), 1)
        rm = sorted(s["reset_ms"])
        s["reset_ms"] = {"n": len(rm), "median": round(rm[len(rm) // 2], 1) if rm else None, "max": round(rm[-1], 1) if rm else None}
        s["mean_return"] = round(float(np.mean(s["returns"])), 3) if s["returns"] else None
        s["success_rate"] = round(s["success"] / max(1, s["episodes"]), 3)
        s.pop("returns")
    tot = {"label": label, "scenario": scenario, "envs": len(rows), "decisions_per_s": round(sum(s["decisions_per_s"] for s in rows), 2),
           "game_s_per_wall_s": round(sum(s["game_s_per_wall_s"] for s in rows), 1), "episodes": sum(s["episodes"] for s in rows),
           "success_rate": round(sum(s["success"] for s in rows) / max(1, sum(s["episodes"] for s in rows)), 3)}
    return {"total": tot, "per_env": rows}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ports", default="")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--sim", type=int, default=0, help="the Python simulator: this many envs, one process each")
    ap.add_argument("--scenario", default="wood")
    ap.add_argument("--seconds", type=float, default=120)
    ap.add_argument("--episodes", type=int, default=0, help="this many episodes an env instead of --seconds")
    ap.add_argument("--seed", type=int, default=900)
    ap.add_argument("--crew", type=int, default=1)
    ap.add_argument("--weights", default=os.path.join("rl", "snapshots", "v31-sim-epoch-472.bin"))
    ap.add_argument("--label", default="")
    ap.add_argument("--trace", default="", help="a .jsonl of the episodes (outcomes and actions)")
    ap.add_argument("--out", default="", help="append the summary to this .jsonl")
    ap.add_argument("--greedy", action="store_true", help="the most likely action (else drawn from the policy)")
    a = ap.parse_args()
    global GREEDY
    GREEDY = a.greedy
    lower_priority()
    trace = [] if a.trace else None
    if a.sim:
        ctx = mp.get_context("spawn")
        with ctx.Pool(a.sim) as p:
            rows = p.map(sim_worker, [(a.weights, a.scenario, a.seconds, a.episodes, a.seed, a.crew, k) for k in range(a.sim)])
        label = a.label or "sim x%d" % a.sim
    else:
        ports = [int(p) for p in a.ports.split(",") if p]
        rows, ts = [], []
        for i, p in enumerate(ports):
            pol = make_policy(a.weights)
            from real_env import RealEnv
            env = RealEnv("%s:%d" % (a.host, p))
            ts.append(threading.Thread(target=run_env, args=(env, str(p), a.scenario, a.seconds, a.episodes, a.seed + 100000 * i if not a.episodes else a.seed + 1000 * i,
                                                             pol, a.crew, trace, rows, i)))
        for t in ts:
            t.start()
        for t in ts:
            t.join()
        label = a.label or "envs %s" % a.ports
    res = summary(rows, label, a.scenario)
    print(json.dumps(res["total"], ensure_ascii=False), flush=True)
    for r in res["per_env"]:
        print("  ", json.dumps(r, ensure_ascii=False), flush=True)
    if a.out:
        with open(a.out, "a", encoding="utf-8") as f:
            f.write(json.dumps(res, ensure_ascii=False) + "\n")
    if a.trace:
        with open(a.trace, "w", encoding="utf-8") as f:
            for t in trace:
                f.write(json.dumps(t, ensure_ascii=False) + "\n")


if __name__ == "__main__":
    main()
