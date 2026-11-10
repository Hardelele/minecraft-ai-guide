"""A dedicated server and the engine side by side, at once (docs/rl/ENGINE.md): the same load round them (the PC is
shared), the same policy, the same seconds. Server ports from 26300 (one JVM each), the engine's arenas from 26200
(one JVM, the switches on); the bench drives all of them together.

    python tools/rl/engine_pair.py --n 1 --scenarios wood,waves:normal --seconds 120 --out build/rl/engine/bench-pair.jsonl
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import engine_env as E  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=1, help="servers and arenas each")
    ap.add_argument("--scenarios", default="wood,waves:normal")
    ap.add_argument("--seconds", type=float, default=120)
    ap.add_argument("--out", default=os.path.join(E.ROOT, "build", "rl", "engine", "bench-pair.jsonl"))
    a = ap.parse_args()
    if E.ours():
        sys.exit("ours already run: stop them first")
    eng = [26200 + i for i in range(a.n)]
    srv = [26300 + i for i in range(a.n)]
    E.start_servers(srv, "1536M", False, None)
    E.start_engine(eng, "%dM" % (1024 + 600 * a.n), True, None)
    ok = E.wait_ready(srv + eng)
    try:
        if ok:
            for sc in a.scenarios.split(","):
                bench = [sys.executable, os.path.join(E.HERE, "engine_bench.py"), "--scenario", sc, "--seconds", str(a.seconds), "--out", a.out]
                ps = [subprocess.Popen(bench + ["--ports", ",".join(map(str, srv)), "--label", "pair server x%d %s" % (a.n, sc)]),
                      subprocess.Popen(bench + ["--ports", ",".join(map(str, eng)), "--label", "pair engine-opt x%d %s" % (a.n, sc)])]
                for p in ps:
                    p.wait()
    finally:
        E.stop(srv + eng)


if __name__ == "__main__":
    main()
