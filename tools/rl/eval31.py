"""The held-out check of 3.1's nets on the simulator: other seeds than training's (play.py's --seed 777), the
scenarios as they are and with each situation "not reachable this way" forced (a hanging tree, a ditch on the way
home, lava before the ore), the night's shelter; a JSON line a (net, case) and a table.

    python tools/rl/eval31.py --weights a.bin,b.bin --n 40 --out build/rl/eval/v31/heldout.jsonl
"""
from __future__ import annotations

import argparse
import json
import multiprocessing as mp
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

CASES = [("wood", None), ("wood", "hang"), ("pickaxe", None), ("pickaxe", "hang"), ("iron", None), ("iron", "lava"),
         ("mine_trip", "lava"), ("farm", None), ("farm", "ditch"), ("waves:easy", None), ("waves:normal", None),
         ("night:shelter", None), ("achievements", None), ("diamond", None)]


def one(job):
    w, sc, force, n, seed, temp = job
    import torch
    torch.set_num_threads(1)
    from train import below_normal
    below_normal()
    import play
    pol = play.load(w)
    r = play.run(pol, sc, n, seed=seed, force=force, temperature=temp)
    r.update(weights=w, force=force, temperature=temp)
    return r


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--weights", required=True)
    ap.add_argument("--n", type=int, default=40)
    ap.add_argument("--seed", type=int, default=777)
    ap.add_argument("--temperature", type=float, default=0.0)
    ap.add_argument("--workers", type=int, default=3)
    ap.add_argument("--cases", default="")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    cases = CASES if not a.cases else [(c.split("+")[0], c.split("+")[1] if "+" in c else None) for c in a.cases.split(",")]
    jobs = [(w, sc, f, a.n, a.seed, a.temperature) for w in a.weights.split(",") for sc, f in cases]
    out = Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with mp.get_context("spawn").Pool(a.workers) as pool, open(out, "a", encoding="utf-8") as f:
        for r in pool.imap(one, jobs):
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
            f.flush()
            ob = r.get("obstacles", {})
            print(f"{Path(r['weights']).name:28s} {r['scenario']:14s} {str(r['force'] or ''):6s} succ {r['success']:.2f} noop {r['noop_end']:.2f} "
                  f"cleared {r['cleared']:.1f} hang_logs {ob.get('hang_logs', 0):.2f} ditch {ob.get('ditch_bridged', 0):.2f}/{ob.get('ditch_met', 0):.2f} "
                  f"lava {ob.get('lava_passed', 0):.2f}/{ob.get('lava_met', 0):.2f} shelter_s {ob.get('shelter_s', 0):.0f} switch {ob.get('switch_ok', 0):.1f} "
                  f"retry {ob.get('retry_fail', 0):.1f} new {sum(r.get('new_actions', {}).values())}", flush=True)
