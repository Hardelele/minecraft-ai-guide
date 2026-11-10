"""A net on both simulators, the same episodes: per scenario its success, waves cleared, game seconds, on the reference
(tools/rl/play.py's loop: one SimEnv, an episode at a time) and on the batch simulator (all the scenario's episodes as
arenas at once), the same seeds and crews (play.py's: seed * 1000 + episode, crew 1 + episode % 2, waves % 4). The
worlds are the same draw for draw (tools/tests/test_rl_simbatch.py); what may differ is the net's arithmetic on a
batch of rows against one row (a greedy choice between two near-equal logits), so episodes are compared one by one.

    python tools/rl/simbatch_eval.py --weights build/rl/nets/v31eng1.bin --n 100 --greedy --out build/rl/eval/simbatch-v31eng1.jsonl
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np
import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import spec  # noqa: E402
from policy import Policy  # noqa: E402


def choose(pol, obs, mask, greedy, gen, temperature):
    with torch.no_grad():
        o = torch.as_tensor(np.asarray(obs, np.float32))
        m = torch.as_tensor(np.asarray(mask, bool))
        lg = Policy.masked(pol.logits(o), m)
        if greedy:
            return lg.argmax(-1).numpy()
        return torch.distributions.Categorical(logits=lg / temperature).sample(generator=gen).numpy() if False else \
            torch.multinomial(torch.softmax(lg / temperature, -1), 1, generator=gen).squeeze(-1).numpy()


def crew_of(sc, ep):
    return 1 + ep % (4 if sc.startswith("waves") else 2)


def run_old(pol, sc, n, seed, greedy, temperature, force=None):
    from sim_env import SimEnv
    env = SimEnv(seed, force=force)
    gen = torch.Generator().manual_seed(seed)
    out = []
    for ep in range(n):
        o = env.reset(sc, seed * 1000 + ep, crew=crew_of(sc, ep))
        dec = 0
        while not o["done"]:
            a = choose(pol, o["obs"], o["mask"], greedy, gen, temperature)
            o = env.step({i: int(k) for i, k in zip(o["agents"], a)})
            dec += len(a)
        inf = o["info"]
        out.append((bool(inf["success"]), inf["cleared"], round(inf["t"], 3), inf["done_reason"], dec))
    return out


def run_batch(pol, sc, n, seed, greedy, temperature, force=None):
    from simbatch import MA, BatchSim
    sim = BatchSim(n)
    gen = torch.Generator().manual_seed(seed + 1)
    sim.reset(np.arange(n), [(sc, seed * 1000 + ep, crew_of(sc, ep), None, force) for ep in range(n)])
    out = [None] * n
    decs = np.zeros(n, np.int64)
    live = np.ones(n, bool)
    while live.any():
        v = np.flatnonzero(sim.valid & np.repeat(live, MA))
        acts = np.full(n * MA, -1, np.int64)
        acts[v] = choose(pol, sim.obs[v], sim.mask[v], greedy, gen, temperature)
        np.add.at(decs, v // MA, 1)
        sim.step(acts.reshape(n, MA), live)
        for e in np.flatnonzero(sim.done & live):
            inf = sim.info(int(e))
            out[e] = (bool(inf["success"]), inf["cleared"], round(inf["t"], 3), inf["done_reason"], int(decs[e]))
            live[e] = False
    return out


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--weights", required=True)
    ap.add_argument("--n", type=int, default=100)
    ap.add_argument("--seed", type=int, default=777)
    ap.add_argument("--greedy", action="store_true")
    ap.add_argument("--temperature", type=float, default=1.0)
    ap.add_argument("--scenarios", default=",".join(spec.SCENARIOS + ["night:shelter"]))
    ap.add_argument("--out", default="")
    a = ap.parse_args()
    from train import below_normal
    below_normal()
    torch.set_num_threads(1)
    from play import load
    pol = load(a.weights).eval()
    rows = []
    for sc in a.scenarios.split(","):
        t0 = time.time()
        old = run_old(pol, sc, a.n, a.seed, a.greedy, a.temperature)
        t1 = time.time()
        new = run_batch(pol, sc, a.n, a.seed, a.greedy, a.temperature)
        t2 = time.time()
        same = sum(1 for x, y in zip(old, new) if x == y)
        so, sn = np.mean([x[0] for x in old]), np.mean([x[0] for x in new])
        se = float(np.sqrt(max(so * (1 - so), 1e-9) / a.n))
        r = {"scenario": sc, "n": a.n, "greedy": a.greedy, "succ_old": round(float(so), 3), "succ_new": round(float(sn), 3),
             "se": round(se, 3), "episodes_same": same, "cleared_old": round(float(np.mean([x[1] for x in old])), 2),
             "cleared_new": round(float(np.mean([x[1] for x in new])), 2), "game_s_old": round(float(np.mean([x[2] for x in old])), 1),
             "game_s_new": round(float(np.mean([x[2] for x in new])), 1), "s_old": round(t1 - t0, 1), "s_new": round(t2 - t1, 1)}
        rows.append(r)
        print(json.dumps(r), flush=True)
        if a.out:
            Path(a.out).parent.mkdir(parents=True, exist_ok=True)
            with open(a.out, "a", encoding="utf-8") as f:
                f.write(json.dumps(r) + "\n")
