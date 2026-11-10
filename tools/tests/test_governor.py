"""The resource governor's decisions (tools/rl/governor.py) on made-up measurements: League of Legends -> Idle, 1 thread
and 1 arena; the others' load jumping -> squeeze; the others' load hard or memory short -> pause; held until calm;
then BelowNormal with threads and arenas by the free cores. Also a pause applied for real: a child process suspended and
resumed (its CPU time stands still while suspended)."""
import os
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "rl"))
import governor  # noqa: E402

bad = 0


def check(ok, what):
    global bad
    print(("ok   " if ok else "FAIL ") + what)
    if not ok:
        bad += 1


def m(others, mem=20000, league=False, **kw):
    return dict({"cpu_total": others, "cpu_own": 0.0, "cpu_others": others, "mem_free_mb": mem, "league": league}, **kw)


def main():
    g = governor.Governor(max_threads=4, max_arenas=12, margin=2, calm=3)
    g.ncpu = 16
    d = g.decide(m(10))
    g._advice = d
    check(d["mode"] == "normal" and d["priority"] == "below_normal" and d["arenas"] + d["threads"] <= 16 * 0.9 - 2 and d["arenas"] >= 8
          and 1 <= d["threads"] <= 4, "quiet PC: below normal, threads and arenas within the free cores less the margin (%s)" % d["reason"])
    d = g.decide(m(10, league=True))
    g._advice = d
    check(d["mode"] == "league" and d["priority"] == "idle" and d["threads"] + d["arenas"] <= 2 and d["gpu_duty"] < 0.5,
          "League: idle, at most 2 threads of load, the GPU little")
    g = governor.Governor(max_threads=4, max_arenas=12, margin=2, calm=3)
    g.ncpu = 16
    g._advice = g.decide(m(10))
    d = g.decide(m(45))
    g._advice = d
    check(d["mode"] == "squeeze" and d["arenas"] < 12 and d["threads"] == 1, "a jump of 35 points: squeeze (%s)" % d["reason"])
    d = g.decide(m(95))
    g._advice = d
    check(d["mode"] == "pause" and d["pause"] and d["arenas"] == 0, "others at 95%%: pause (%s)" % d["reason"])
    seq = []
    for _ in range(5):
        d = g.decide(m(10))
        g._advice = d
        seq.append(d["mode"])
    check(seq[:2] == ["pause", "pause"] and seq[-1] == "normal", "released after the calm looks only: %s" % seq)
    d = g.decide(m(10, mem=1000))
    g._advice = d
    check(d["mode"] == "pause" and "memory" in d["reason"], "memory short: pause (%s)" % d["reason"])
    g = governor.Governor(max_threads=4, max_arenas=12, margin=2, calm=3)
    g.ncpu = 16
    g._advice = g.decide(m(10))
    d = g.decide(m(10, gpu_others=90))
    check(d["mode"] == "squeeze", "others on the GPU while we collect: squeeze (%s)" % d["reason"])
    d = g.decide(m(60))
    check(d["arenas"] <= 4, "others at 60%%: few arenas (%s)" % d["reason"])

    # a pause for real: the child's CPU time stands still while suspended
    p = subprocess.Popen([sys.executable, "-c", "while True: pass"])
    try:
        g = governor.Governor(root_pid=os.getpid(), max_threads=1, max_arenas=1)
        time.sleep(0.5)
        g.apply({"priority": "idle", "pause": True})
        import psutil
        c = psutil.Process(p.pid)
        t1 = sum(c.cpu_times()[:2])
        time.sleep(0.6)
        t2 = sum(c.cpu_times()[:2])
        check(t2 - t1 < 0.05 and c.nice() == governor.IDLE, "paused: the child suspended (%.2f s of CPU in 0.6 s) at idle" % (t2 - t1))
        g.apply({"priority": "below_normal", "pause": False})
        time.sleep(0.6)
        t3 = sum(c.cpu_times()[:2])
        check(t3 - t2 > 0.1 and c.nice() == governor.BELOW, "released: it runs again (%.2f s), below normal" % (t3 - t2))
    finally:
        p.kill()
    print("test_governor " + ("PASS" if not bad else "FAIL (%d)" % bad))
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
