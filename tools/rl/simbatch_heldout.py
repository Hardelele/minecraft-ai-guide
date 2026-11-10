"""Held-out check of nets on the batch simulator: other seeds than training's (777, as eval31.py and play.py), each
scenario's episodes as arenas at once, the net's own sampling (or greedy); per (net, scenario) its success, how the
episodes end (noop: the surrender; lost), waves cleared, breaches, the return under the reward asked for.

    python tools/rl/simbatch_heldout.py --nets build/rl/init/v31eng1.pt,build/rl/runs/v4sb1/final.pt --n 200 --reward v4 \
        --out build/rl/eval/heldout-v4.jsonl
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

CASES = spec.SCENARIOS + ["night:shelter"]


def load(path: str, dev):
    if path.endswith(".bin"):
        from export_policy import read_policy
        return read_policy(Path(path)).to(dev).eval()
    ck = torch.load(path, map_location="cpu", weights_only=False)
    pol = Policy(ck.get("d", spec.D), ck.get("a", spec.A), ck.get("hidden", 256))
    pol.load_state_dict(ck["model"])
    return pol.to(dev).eval()


def crew_of(sc, ep):
    return 1 + ep % (4 if sc.startswith("waves") else 2)


def run(pol, sc, n, seed, reward, greedy, dev, force=None):
    from simbatch import MA, BatchSim
    sim = BatchSim(n, reward=reward)
    sim.reset(np.arange(n), [(sc, seed * 1000 + ep, crew_of(sc, ep), None, force) for ep in range(n)])
    live = np.ones(n, bool)
    ret = np.zeros(n)
    nag = sim.st.ai[:, 1].copy()
    res = {}
    while live.any():
        v = np.flatnonzero(sim.valid & np.repeat(live, MA))
        acts = np.full(n * MA, -1, np.int64)
        with torch.no_grad():
            a, _, _, _ = pol.act(torch.from_numpy(sim.obs[v]).to(dev), torch.from_numpy(sim.mask[v]).to(dev), greedy=greedy)
        acts[v] = a.cpu().numpy()
        sim.step(acts.reshape(n, MA), live)
        o = np.flatnonzero(sim.valid & np.repeat(live, MA))
        np.add.at(ret, o // MA, sim.rew[o])
        for e in np.flatnonzero(sim.done & live):
            res[int(e)] = sim.info(int(e))
            live[e] = False
    infs = [res[e] for e in range(n)]
    return {"scenario": sc, "n": n, "success": round(float(np.mean([x["success"] for x in infs])), 3),
            "noop_end": round(float(np.mean([x["done_reason"] == "noop" for x in infs])), 3),
            "lost": round(float(np.mean([x["done_reason"] == "lost" for x in infs])), 3),
            "cleared": round(float(np.mean([x["cleared"] for x in infs])), 2), "breaches": round(float(np.mean([x["breaches"] for x in infs])), 2),
            "return": round(float(np.mean(ret / np.maximum(1, nag))), 2), "game_s": round(float(np.mean([x["t"] for x in infs])), 1)}


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--nets", required=True)
    ap.add_argument("--n", type=int, default=200)
    ap.add_argument("--seed", type=int, default=777)
    ap.add_argument("--reward", default="v4", choices=["v3", "v4"])
    ap.add_argument("--greedy", action="store_true")
    ap.add_argument("--scenarios", default=",".join(CASES))
    ap.add_argument("--cpu", action="store_true")
    ap.add_argument("--out", default="")
    a = ap.parse_args()
    from train import below_normal
    below_normal()
    torch.set_num_threads(1)
    dev = torch.device("cuda" if torch.cuda.is_available() and not a.cpu else "cpu")
    for path in a.nets.split(","):
        pol = load(path, dev)
        rows = []
        for sc in a.scenarios.split(","):
            t0 = time.time()
            r = run(pol, sc, a.n, a.seed, a.reward, a.greedy, dev)
            r.update(net=path, reward=a.reward, greedy=a.greedy, s=round(time.time() - t0, 1))
            rows.append(r)
            print(json.dumps(r), flush=True)
            if a.out:
                Path(a.out).parent.mkdir(parents=True, exist_ok=True)
                with open(a.out, "a", encoding="utf-8") as f:
                    f.write(json.dumps(r) + "\n")
        print(json.dumps({"net": path, "mean_success": round(float(np.mean([r["success"] for r in rows])), 3),
                          "mean_noop_end": round(float(np.mean([r["noop_end"] for r in rows])), 3)}), flush=True)
