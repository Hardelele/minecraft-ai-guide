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
    check(d["mode"] == "normal" and d["gpu_pause"] is False, "others on the GPU are not a reason to squeeze the CPU's work (%s)" % d["reason"])
    d = g.decide(m(60))
    check(d["arenas"] <= 4, "others at 60%%: few arenas (%s)" % d["reason"])

    # the CPU and the GPU apart (--gpu-root): the arenas pause, the updates go on, until the others take the GPU
    g = governor.Governor(max_threads=4, max_arenas=12, margin=2, calm=3, gpu_root=True)
    g.ncpu = 16
    g._advice = g.decide(m(10))
    d = g.decide(m(96, gpu_others=20))
    g._advice = d
    check(d["mode"] == "pause" and d["pause"] and d["arenas"] == 0 and d["gpu_pause"] is False and d["gpu_duty"] == 1.0,
          "others' CPU at 96%%, the GPU free: the arenas pause, the updates do not (%s)" % d["reason"])
    for _ in range(6):
        d = g.decide(m(96, gpu_others=75))
        g._advice = d
    check(d["pause"] and d["gpu_pause"] is False and d["gpu_duty"] == 0.5, "others on the GPU 75%%: the updates at half (%s)" % d["reason"])
    for _ in range(8):
        d = g.decide(m(96, gpu_others=99))
        g._advice = d
    check(d["pause"] and d["gpu_pause"] is True, "others on the GPU 99%%: the updates stop too (%s)" % d["reason"])
    for _ in range(40):
        d = g.decide(m(96, gpu_others=30))
        g._advice = d
    check(d["gpu_pause"] is False, "the GPU calm again: the updates go on (%s)" % d["reason"])
    d = g.decide(m(10, mem=1000))
    check(d["pause"] and d["gpu_pause"] is True, "memory short: both stop")
    g2 = governor.Governor(max_threads=4, max_arenas=12, margin=2, calm=3, gpu_root=True)
    g2.ncpu = 16
    d = g2.decide(m(10, league=True, gpu_others=50))
    check(d["mode"] == "league" and d["priority"] == "idle" and d["gpu_duty"] == 0.25 and d["gpu_pause"] is False and d["arenas"] <= 1,
          "League: Idle, one arena, the GPU a quarter, the updates go on (%s)" % d["reason"])
    gl = governor.Governor(max_threads=4, max_arenas=12, margin=2, calm=3)   # (no --gpu-root: one thing, it stops as a whole)
    gl.ncpu = 16
    d = gl.decide(m(96))
    check(d["pause"] and d["gpu_pause"] is True, "without --gpu-root the GPU stops with the CPU")

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
        # --gpu-root: the root keeps running in a CPU pause (here the child is the stand-in for the collector)
        root = subprocess.Popen([sys.executable, "-c", "while True: pass"])
        g = governor.Governor(root_pid=root.pid, max_threads=1, max_arenas=1, self_pause=True, gpu_root=True, extra_pids=lambda: [p.pid])
        time.sleep(0.4)
        r, c = psutil.Process(root.pid), psutil.Process(p.pid)
        g.apply({"priority": "idle", "pause": True, "gpu_pause": False, "mode": "pause"})
        a1, b1 = sum(r.cpu_times()[:2]), sum(c.cpu_times()[:2])
        time.sleep(0.6)
        a2, b2 = sum(r.cpu_times()[:2]), sum(c.cpu_times()[:2])
        check(a2 - a1 > 0.1 and b2 - b1 < 0.05 and r.nice() == governor.BELOW,
              "gpu_root, CPU pause: the root runs (%.2f s) below normal, the other suspended (%.2f s)" % (a2 - a1, b2 - b1))
        g.apply({"priority": "idle", "pause": True, "gpu_pause": True, "mode": "pause"})
        a3 = sum(r.cpu_times()[:2])
        time.sleep(0.6)
        a4 = sum(r.cpu_times()[:2])
        check(a4 - a3 < 0.05, "gpu_pause: the root stops too (%.2f s)" % (a4 - a3))
        g.apply({"priority": "below_normal", "pause": False, "gpu_pause": False, "mode": "normal"})
        time.sleep(0.6)
        check(sum(r.cpu_times()[:2]) - a4 > 0.1 and sum(c.cpu_times()[:2]) - b2 > 0.1, "released: both run again")
        g.resume_all()
        root.kill()
    finally:
        p.kill()
    print("test_governor " + ("PASS" if not bad else "FAIL (%d)" % bad))
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
