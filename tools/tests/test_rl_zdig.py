"""The digging zombies (docs/rl/ZOMBIES-DIG.md): Java and Python are the same rules. ZombieDigTest (client/test) writes
build/rl/zdig-java.json: the tier of each zombie of the waves 1-20, the ticks of a block by tool, the seconds to an agent
down the planner's way, the hearing; tools/rl/zdig.py (what the simulators read) must give the same, and the
calibration's "dig" section must be what zdig.py computes from rl/zombie-dig.json.

    python tools/tests/test_rl_zdig.py     (run `python tools/test_client.py` or ZombieDigTest first for the Java file)
"""
import json
import math
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "rl"))
import zdig as ZD  # noqa: E402

bad = 0


def check(ok, what):
    global bad
    print(("ok   " if ok else "FAIL ") + what)
    bad += not ok


def main():
    f = ROOT / "build" / "rl" / "zdig-java.json"
    if not f.is_file():
        print("no", f, "- run ZombieDigTest first (tools/test_client.py)")
        sys.exit(1)
    j = json.loads(f.read_text(encoding="utf-8"))
    d = [k for k, v in j["tiers"].items() if [ZD.tier(int(k.split(":")[0]), q, int(k.split(":")[1])) for q in range(int(k.split(":")[1]))] != v]
    check(not d, "the tier of every zombie of waves 1-20 (%d cases) is Java's %s" % (len(j["tiers"]), d[:3]))
    d = []
    for k, v in j["ticks"].items():
        t, m, h, need, lv = k.split(":")
        py = ZD.ticks(t, m, float(h), need == "true", int(lv)) if t in ZD.TOOLS else None
        if py != v and not (m == "other" and py == v):
            d.append((k, py, v))
    check(not d, "the ticks of a block by tool (%d cases) are Java's %s" % (len(j["ticks"]), d[:3]))
    d = []
    for k, v in j["reach"].items():
        t, depth = k.split(":")
        py = ZD.dig_seconds(t, int(depth))
        py = -1.0 if math.isinf(py) else round(py, 1)
        if abs(py - v) > 0.11:
            d.append((k, py, v))
    check(not d, "the seconds to an agent (%d cases) are Java's %s" % (len(j["reach"]), d[:3]))
    check(all(abs(ZD.hear(t) - h) < 1e-9 for t, h in zip(range(0, 201, 5), j["hear"])), "the hearing radius is Java's")
    cal = json.loads((ROOT / "rl" / "sim-calibration.json").read_text(encoding="utf-8"))["dig"]
    check(cal["reach_s"] == ZD.reach_table(), "rl/sim-calibration.json dig.reach_s is zdig.py's (python tools/rl/zdig.py --write)")
    check(cal["depth_blocks"] == list(ZD.DEPTH_BLOCKS[1:]), "the depth levels")
    for tr in ZD.TIERS:
        check(ZD.TOOLS[tr["tool"]] is not None, "tier %s has a tool" % tr["name"])
    # the claims of the design: the deeper, the longer; a better pickaxe is faster; a shovel cannot do stone; hiding is heard
    r = ZD.reach_table()
    check(r["digger"][0] < r["digger"][1] and r["miner"][0] < r["digger"][0] and r["breaker"][1] < r["miner"][1], "deeper takes longer, a better pickaxe is faster")
    check(r["shoveler"] == [None, None], "a shovel does not get through stone")
    check(ZD.hear_time(24) < 60 and ZD.hear_time(52) < 120, "an agent at 24 and at 52 blocks is heard within 2 minutes of the wave")
    print("FAILED %d" % bad if bad else "all ok")
    sys.exit(1 if bad else 0)


main()
