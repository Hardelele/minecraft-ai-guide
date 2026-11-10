"""The same episodes on two environments, compared (docs/rl/ENGINE.md: the engine against the dedicated server). Each
side is a trace of tools/rl/engine_bench.py --trace (the same weights, --greedy, the same seeds): per scenario the
success, the ends, the game time and the return, the achievements; the differences with their noise (Minecraft is not
deterministic: the same seed gives the same start, not the same run).

    python tools/rl/engine_equiv.py build/rl/engine/equiv-server.jsonl build/rl/engine/equiv-engine.jsonl
"""
from __future__ import annotations

import argparse
import collections
import json
import math


def load(path):
    by = collections.defaultdict(list)
    for line in open(path, encoding="utf-8-sig"):
        if line.strip():
            e = json.loads(line)
            by[e["scenario"]].append(e)
    return by


def mean_sd(xs):
    if not xs:
        return float("nan"), float("nan")
    m = sum(xs) / len(xs)
    return m, math.sqrt(sum((x - m) ** 2 for x in xs) / max(1, len(xs) - 1))


def compare(a, b):
    out = {}
    for sc in sorted(set(a) | set(b)):
        x, y = a.get(sc, []), b.get(sc, [])
        sa, sb = sum(e["success"] for e in x) / max(1, len(x)), sum(e["success"] for e in y) / max(1, len(y))
        p = (sum(e["success"] for e in x) + sum(e["success"] for e in y)) / max(1, len(x) + len(y))
        se = math.sqrt(max(1e-12, p * (1 - p) * (1 / max(1, len(x)) + 1 / max(1, len(y)))))
        ta, tb = mean_sd([e["t"] for e in x if e["success"]]), mean_sd([e["t"] for e in y if e["success"]])
        ra, rb = mean_sd([e["return"] for e in x]), mean_sd([e["return"] for e in y])
        ach = collections.Counter(), collections.Counter()
        for side, es in enumerate((x, y)):
            for e in es:
                for k in e.get("achievements") or []:
                    ach[side][k] += 1
        same_actions = sum(1 for e, f in zip(sorted(x, key=lambda e: e["seed"]), sorted(y, key=lambda e: e["seed"]))
                           if [s[1] for s in e["actions"]] == [s[1] for s in f["actions"]])
        out[sc] = {"episodes": [len(x), len(y)], "success": [round(sa, 3), round(sb, 3)], "success_z": round((sa - sb) / se, 2),
                   "ends": [dict(collections.Counter(e["done_reason"] for e in x)), dict(collections.Counter(e["done_reason"] for e in y))],
                   "goal_time_s": [[round(ta[0], 1), round(ta[1], 1)], [round(tb[0], 1), round(tb[1], 1)]],
                   "return": [[round(ra[0], 3), round(ra[1], 3)], [round(rb[0], 3), round(rb[1], 3)]],
                   "achievements": {k: [ach[0][k], ach[1][k]] for k in sorted(set(ach[0]) | set(ach[1]))},
                   "same_action_sequences": same_actions}
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("a")
    ap.add_argument("b")
    a = ap.parse_args()
    print(json.dumps(compare(load(a.a), load(a.b)), ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
