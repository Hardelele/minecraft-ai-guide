"""The mask against the skills on the engine (docs/rl/ENGINE.md, section 8): arenas of the engine run the scenarios
under a policy (or uniformly over the allowed actions), and each arena's "audit" tells what every allowed action came
to; summed here into the share of each action's allowed decisions that failed, by the failure's code ("@0": within a
game second, the skill did not even start).

    python tools/rl/engine_audit.py --n 2 --seconds 40 --policy random --out build/rl/engine/audit-random.json
    python tools/rl/engine_audit.py --n 2 --seconds 40 --policy rl/snapshots/v31-sim-epoch-472.bin --out ...
"""
from __future__ import annotations

import argparse
import collections
import json
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import engine_env as E  # noqa: E402

SCENARIOS = "wood,pickaxe,sword,armor,iron,bow,mine_trip,diamond,farm,pasture,enchant,fortify,food,waves:easy,waves:normal,waves:long,achievements"


def table(audit, top=40):
    rows = []
    for act, codes in audit.items():
        n = sum(codes.values())
        bad = {c: k for c, k in codes.items() if c != "OK"}
        for c, k in bad.items():
            rows.append((k, act, c, n))
    rows.sort(reverse=True)
    out = ["| действие | исход | раз | из разрешённых | доля |", "|---|---|---|---|---|"]
    for k, act, c, n in rows[:top]:
        out.append("| `%s` | %s | %d | %d | %.0f %% |" % (act, c, k, n, 100.0 * k / n))
    return "\n".join(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=2)
    ap.add_argument("--base-port", type=int, default=26400)
    ap.add_argument("--seconds", type=float, default=40)
    ap.add_argument("--scenarios", default=SCENARIOS)
    ap.add_argument("--policy", default="random", help="random: uniform over the allowed actions but noop; else a .bin")
    ap.add_argument("--crew", type=int, default=1)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    ports = [a.base_port + i for i in range(a.n)]
    E.start_engine(ports, "%dM" % (1024 + 600 * a.n), True, None)
    try:
        if not E.wait_ready(ports):
            sys.exit("the arenas did not start")
        for p in ports:
            E.ask(p, {"op": "audit", "reset": True})
        for sc in a.scenarios.split(","):
            cmd = [sys.executable, os.path.join(E.HERE, "engine_bench.py"), "--ports", ",".join(map(str, ports)), "--scenario", sc,
                   "--seconds", str(a.seconds), "--crew", str(a.crew), "--weights", "" if a.policy == "random" else a.policy,
                   "--label", "audit %s %s" % (a.policy, sc)]
            subprocess.run(cmd, stdout=subprocess.DEVNULL)
        total = collections.defaultdict(collections.Counter)
        for p in ports:
            for act, codes in (E.ask(p, {"op": "audit"}, 10) or {}).get("audit", {}).items():
                total[act].update(codes)
    finally:
        E.stop(ports)
    audit = {k: dict(v) for k, v in sorted(total.items())}
    allowed = sum(sum(v.values()) for v in audit.values())
    failed0 = sum(c for v in audit.values() for k, c in v.items() if k.endswith("@0"))
    failed = sum(c for v in audit.values() for k, c in v.items() if k != "OK")
    res = {"policy": a.policy, "scenarios": a.scenarios, "seconds_each": a.seconds, "arenas": a.n, "allowed_decisions": allowed,
           "failed": failed, "failed_at_start": failed0, "audit": audit}
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(res, f, ensure_ascii=False, indent=1)
    print("allowed %d, failed %d (%.1f %%), at start %d (%.1f %%)" % (allowed, failed, 100.0 * failed / max(1, allowed), failed0, 100.0 * failed0 / max(1, allowed)))
    print(table(audit))


if __name__ == "__main__":
    main()
