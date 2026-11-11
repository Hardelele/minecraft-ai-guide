"""The batch simulator's speed against the reference's: decisions a second and game seconds a second, uniform
choices over the allowed actions (never noop; the same mix of scenarios and crews for both), the simulator alone
(no net) and with the net on the GPU (the act of train.py's loop: obs to the GPU, the actions back).

    python tools/rl/simbatch_bench.py --old 20 --sizes 1,64,256,1024,4096 --threads 1,8 [--net build/.../final.bin]

A JSON line per measurement (--out appends them to a file).
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import spec  # noqa: E402

SCEN = [s for s in spec.SCENARIOS]


def job(rng, k):
    sc = SCEN[k % len(SCEN)]
    crew = 1 + int(rng.integers(0, 4 if sc.startswith("waves") else 2))
    return sc, int(rng.integers(1 << 30)), crew


def bench_old(seconds: float) -> dict:
    import random
    from sim_env import SimEnv
    env = SimEnv(0)
    rng = np.random.default_rng(0)
    pick = random.Random(1)
    dec = 0
    gs = 0.0
    ep = 0
    t0 = time.perf_counter()
    while time.perf_counter() - t0 < seconds:
        o = env.reset(*job(rng, ep))
        ep += 1
        while not o["done"]:
            acts = {}
            for i, m in zip(o["agents"], o["mask"]):
                ok = [k for k, v in enumerate(m) if v and k != 0]
                acts[i] = pick.choice(ok)
                dec += 1
            o = env.step(acts)
        gs += o["t"]
    dt = time.perf_counter() - t0
    return {"sim": "sim_env", "arenas": 1, "threads": 1, "dec_s": dec / dt, "game_s_s": gs / dt, "episodes": ep}


def pick_actions(rng, mask: np.ndarray) -> np.ndarray:
    """uniform over the allowed actions but noop (noop when nothing else)"""
    m = mask.copy()
    m[:, 0] = False
    u = rng.random(m.shape) * m
    a = u.argmax(1)
    a[~m.any(1)] = 0
    return a


def bench_batch(n: int, threads: int, seconds: float, net=None, dev=None) -> dict:
    import numba
    from simbatch import BatchSim, MA
    numba.set_num_threads(threads)
    rng = np.random.default_rng(0)
    sim = BatchSim(n, threads=threads)
    jobs = [job(rng, k) for k in range(n)]
    sim.reset(np.arange(n), jobs)
    acts = np.full(n * MA, -1, np.int64)
    # warm (the compile is cached; the first calls still touch the pages)
    dec = 0
    gs = 0.0
    eps = 0
    sim_s = 0.0
    t0 = time.perf_counter()
    k = n
    while time.perf_counter() - t0 < seconds:
        v = np.flatnonzero(sim.valid)
        acts[:] = -1
        if net is None:
            acts[v] = pick_actions(rng, sim.mask[v])
        else:
            import torch
            with torch.no_grad():
                ot = torch.from_numpy(sim.obs[v]).to(dev, non_blocking=True)
                mt = torch.from_numpy(sim.mask[v]).to(dev, non_blocking=True)
                acts[v] = net.act(ot, mt)[0].cpu().numpy()
        dec += len(v)
        t_before = sim.t.copy()
        ts = time.perf_counter()
        sim.step(acts.reshape(n, MA))
        sim_s += time.perf_counter() - ts
        gs += float((sim.t - t_before).sum())
        d = np.flatnonzero(sim.done)
        if len(d):
            eps += len(d)
            ts = time.perf_counter()
            sim.reset(d, [job(rng, k + j) for j in range(len(d))])
            sim_s += time.perf_counter() - ts
            k += len(d)
    dt = time.perf_counter() - t0
    st_bytes = sum(x.nbytes for x in vars(sim.st).values() if isinstance(x, np.ndarray))
    return {"sim": "simbatch", "arenas": n, "threads": threads, "net": net is not None, "dec_s": dec / dt, "game_s_s": gs / dt,
            "sim_only_dec_s": dec / max(1e-9, sim_s), "episodes": eps, "state_mb": round(st_bytes / 2**20, 1),
            "bytes_per_arena": st_bytes // n}


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--old", type=float, default=20, help="seconds of the reference (0: skip)")
    ap.add_argument("--sizes", default="1,64,256,1024,4096")
    ap.add_argument("--threads", default="1")
    ap.add_argument("--seconds", type=float, default=15)
    ap.add_argument("--net", default="", help="a policy .bin: the net's choices on the GPU instead of uniform ones")
    ap.add_argument("--out", default="")
    a = ap.parse_args()
    from train import below_normal
    below_normal()
    net = dev = None
    if a.net:
        import torch
        from play import load
        dev = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        net = load(a.net).to(dev).eval()
    res = []
    if a.old:
        res.append(bench_old(a.old))
        print(json.dumps(res[-1]), flush=True)
    for th in [int(x) for x in a.threads.split(",")]:
        for n in [int(x) for x in a.sizes.split(",")]:
            res.append(bench_batch(n, th, a.seconds, net, dev))
            print(json.dumps(res[-1]), flush=True)
    if a.out:
        with open(a.out, "a", encoding="utf-8") as f:
            for r in res:
                f.write(json.dumps(r) + "\n")
