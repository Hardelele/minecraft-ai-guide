"""The raw facts of a scenario's first decision on a running server (docs/rl/CONTRACT-v2.md), by a pattern of names:

    python tools/rl/rl_peek.py --url 127.0.0.1:26110 --scenario pasture --grep "bag_|lure|pen_"
"""
import argparse
import json
import re
import socket


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="127.0.0.1:26110")
    ap.add_argument("--scenario", default="wood")
    ap.add_argument("--grep", default=".")
    ap.add_argument("--seed", type=int, default=3)
    ap.add_argument("--extra", default="")
    a = ap.parse_args()
    host, port = a.url.split(":")
    s = socket.create_connection((host, int(port)), timeout=120)
    f = s.makefile("r", encoding="utf-8")
    msg = {"op": "reset", "scenario": a.scenario, "seed": a.seed, "raw": True}
    if a.extra:
        msg["extra_kit"] = a.extra
    s.sendall((json.dumps(msg) + "\n").encode())
    o = json.loads(f.readline())
    for agent, raw in o["info"]["raw"].items():
        print(agent, {k: v for k, v in sorted(raw.items()) if re.search(a.grep, k) and v})
    s.sendall(b'{"op":"close"}\n')


if __name__ == "__main__":
    main()
