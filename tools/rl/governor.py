"""The resource governor: a training run takes what the PC has spare and gives it back when the owner needs it.

Every few seconds it measures what the OTHER programs use (the CPU less our process tree's share, the free memory,
the GPU while our run is collecting, League of Legends running) and decides:

- League of Legends running: Idle priority, at most 2 threads of load (1 torch thread, 1 arena stepping), the GPU
  at a small duty (the update sleeps between its minibatches);
- otherwise: BelowNormal; threads and arenas by the free cores less a margin;
- the others' load jumps (CPU up by --jump points since the last look, or over --busy) or the free memory falls under
  --mem-low: squeeze (the arenas halved, 1 thread), and on --busy-hard / under --mem-pause: pause. A pause suspends
  the run's child processes (the engine's JVM, the workers: NtSuspendProcess, every thread) and the run itself waits
  between its steps; the wrapper (``run``) suspends the whole tree. Released after --calm looks below the bars.

Every change of decision (and a heartbeat a minute) is a line of the decision log (JSONL): when, what was measured,
what was decided and why.

Two ways in:
  * in the run: train.py starts ``Governor(...).start()`` (a thread) and reads ``advice()`` between its steps;
  * a wrapper for anything: ``python tools/rl/governor.py run [--log f] -- python tools/rl/train.py ...``: the
    advice also goes to the file named by AIGUIDE_GOVERNOR (the child reads it when it knows how: train.py does),
    priority and pauses on the whole tree;
  * ``python tools/rl/governor.py once``: one measurement and the decision (a look, nothing applied).
"""
import argparse
import json
import os
import subprocess
import sys
import threading
import time

import psutil

LEAGUE = "League of Legends.exe"
IS_WIN = os.name == "nt"
IDLE = psutil.IDLE_PRIORITY_CLASS if IS_WIN else 19
BELOW = psutil.BELOW_NORMAL_PRIORITY_CLASS if IS_WIN else 10
PRIO_NAME = {IDLE: "idle", BELOW: "below_normal"}


def gpu():
    """(utilization %, memory used MB, total MB) of the first GPU, or None"""
    try:
        out = subprocess.run(["nvidia-smi", "--query-gpu=utilization.gpu,memory.used,memory.total", "--format=csv,noheader,nounits"],
                             capture_output=True, text=True, timeout=5, creationflags=0x08000000 if IS_WIN else 0).stdout
        u, m, t = [float(x) for x in out.strip().splitlines()[0].split(",")]
        return u, m, t
    except Exception:
        return None


def league_running():
    for p in psutil.process_iter(["name"]):
        if p.info["name"] == LEAGUE:
            return True
    return False


class Governor:
    def __init__(self, root_pid=None, log=None, max_threads=None, max_arenas=None, interval=2.0, margin=2.0, jump=25.0,
                 busy=70.0, busy_hard=90.0, mem_low=4096, mem_pause=2048, calm=5, self_pause=False, advice_file=None,
                 gpu_every=3, extra_pids=None):
        self.root = psutil.Process(root_pid or os.getpid())
        self.log_path = log
        self.ncpu = psutil.cpu_count() or 4
        self.max_threads = max_threads or self.ncpu
        self.max_arenas = max_arenas or self.ncpu
        self.interval, self.margin, self.jump, self.busy, self.busy_hard = interval, margin, jump, busy, busy_hard
        self.mem_low, self.mem_pause, self.calm = mem_low, mem_pause, calm
        self.self_pause = self_pause      # the wrapper: the root is a child and may be suspended too
        self.advice_file = advice_file
        self.gpu_every = gpu_every
        self.extra_pids = extra_pids or (lambda: ())
        self.status = {"phase": "collect", "gpu_mb": 0.0}   # what the run says of itself (train.py sets it)
        self._lock = threading.Lock()
        self._advice = {"mode": "start", "priority": "below_normal", "threads": 1, "arenas": self.max_arenas, "pause": False,
                        "gpu_duty": 1.0, "reason": "not measured yet"}
        self._last_others = None
        self._ema = None   # the others' CPU smoothed (the sizes follow it, not every 2 s wobble; jumps are seen raw)
        self._quiet = 0
        self._suspended: set = set()
        self._stop = threading.Event()
        self._last_log = 0.0
        self._n = 0
        self._gpu_last = None
        self._cpu_prev: dict = {}   # pid -> its CPU seconds at the last look
        self._t_prev = time.time()
        psutil.cpu_percent(None)

    # ---- measuring ------------------------------------------------------------------------------------------------
    def tree(self):
        out = []
        try:
            out = [self.root] + self.root.children(recursive=True)
        except psutil.Error:
            pass
        for pid in self.extra_pids():
            try:
                out.append(psutil.Process(pid))
            except psutil.Error:
                pass
        seen, res = set(), []
        for p in out:
            if p.pid in seen:
                continue
            seen.add(p.pid)
            res.append(p)
        return res

    def own_cpu(self):
        """our tree's share of all the cores since the last look (%): by CPU seconds, a process started since then
        counted from its start (cpu_percent's first call says 0: the JVM starting looked like another program's load)"""
        now = time.time()
        dt = max(1e-3, now - self._t_prev)
        used, cur = 0.0, {}
        for p in self.tree():
            try:
                t = p.cpu_times()
                c = t.user + t.system
                prev = self._cpu_prev.get(p.pid)
                if prev is None:
                    prev = 0.0 if p.create_time() >= self._t_prev - 0.5 else c
                used += max(0.0, c - prev)
                cur[p.pid] = c
            except psutil.Error:
                pass
        self._cpu_prev, self._t_prev = cur, now
        return 100.0 * used / (dt * self.ncpu)

    def measure(self):
        total = psutil.cpu_percent(None)   # of all the cores, since the last call
        own = self.own_cpu()
        vm = psutil.virtual_memory()
        self._n += 1
        g = None
        if self._n % self.gpu_every == 1 or self.gpu_every <= 1:
            g = gpu()
            self._gpu_last = g
        else:
            g = self._gpu_last
        m = {"cpu_total": round(total, 1), "cpu_own": round(min(own, total), 1), "cpu_others": round(max(0.0, total - own), 1),
             "mem_free_mb": round(vm.available / 2 ** 20), "league": league_running()}
        if g:
            m["gpu_util"], m["gpu_mem_mb"], m["gpu_total_mb"] = g
            m["gpu_mem_others_mb"] = round(max(0.0, g[1] - self.status.get("gpu_mb", 0.0) - (400 if self.status.get("gpu_mb") else 0)))
            if self.status.get("phase") == "collect":   # our run hardly touches the GPU while collecting: the use is the others'
                m["gpu_others"] = g[0]
        return m

    # ---- deciding -------------------------------------------------------------------------------------------------
    def decide(self, m):
        others = m["cpu_others"]
        rise = others - self._last_others if self._last_others is not None else 0.0
        self._last_others = others
        self._ema = others if self._ema is None else max(others, 0.7 * self._ema + 0.3 * others) if others > self._ema else 0.8 * self._ema + 0.2 * others
        free_cores = self.ncpu * (1 - self._ema / 100.0) - self.margin   # (down at once, up slowly)
        hard = others >= self.busy_hard or m["mem_free_mb"] < self.mem_pause
        soft = others >= self.busy or rise >= self.jump or m["mem_free_mb"] < self.mem_low or m.get("gpu_others", 0) >= 60
        if hard or soft:
            self._quiet = 0
        else:
            self._quiet += 1
        prev = self._advice["mode"]
        held = prev in ("pause", "squeeze") and self._quiet < self.calm   # released only after `calm` quiet looks
        if m["league"]:
            d = {"mode": "league", "priority": "idle", "threads": 1, "arenas": min(1, self.max_arenas), "pause": hard, "gpu_duty": 0.25,
                 "reason": "League of Legends runs: Idle, at most 2 threads of load, the GPU little" + ("; others' load hard: paused" if hard else "")}
        elif hard or (held and prev == "pause"):
            why = ("others' CPU %.0f%% >= %.0f" % (others, self.busy_hard) if others >= self.busy_hard else
                   "free memory %d MB < %d" % (m["mem_free_mb"], self.mem_pause) if m["mem_free_mb"] < self.mem_pause else
                   "held: %d of %d quiet looks" % (self._quiet, self.calm))
            d = {"mode": "pause", "priority": "idle", "threads": 1, "arenas": 0, "pause": True, "gpu_duty": 0.0, "reason": "pause: " + why}
        elif soft or held:
            why = ("others' CPU up %.0f points" % rise if rise >= self.jump else
                   "others' CPU %.0f%% >= %.0f" % (others, self.busy) if others >= self.busy else
                   "free memory %d MB < %d" % (m["mem_free_mb"], self.mem_low) if m["mem_free_mb"] < self.mem_low else
                   "others use the GPU %.0f%%" % m.get("gpu_others", 0) if m.get("gpu_others", 0) >= 60 else
                   "held: %d of %d quiet looks" % (self._quiet, self.calm))
            base = self._advice["arenas"] if prev not in ("start", "pause") else self.max_arenas
            d = {"mode": "squeeze", "priority": "idle", "threads": 1, "arenas": max(1, min(base, int(free_cores)) // 2 or 1), "pause": False,
                 "gpu_duty": 0.5, "reason": "squeeze: " + why}
        else:
            th = int(max(1, min(self.max_threads, free_cores // 4)))   # the update's threads: a share of what is free
            ar = int(max(1, min(self.max_arenas, free_cores - th)))
            d = {"mode": "normal", "priority": "below_normal", "threads": th, "arenas": ar, "pause": False, "gpu_duty": 1.0,
                 "reason": "free %.1f cores (others %.0f%%, smoothed %.0f%%, margin %.0f): %d threads, %d arenas" % (free_cores, others, self._ema, self.margin, th, ar)}
        return d

    # ---- applying -------------------------------------------------------------------------------------------------
    def apply(self, d):
        prio = IDLE if d["priority"] == "idle" else BELOW
        procs = self.tree()
        for p in procs:
            try:
                if p.nice() != prio:
                    p.nice(prio)
            except psutil.Error:
                pass
        if d["pause"]:
            for p in procs:
                if p.pid == os.getpid() or (p.pid == self.root.pid and not self.self_pause) or p.pid in self._suspended:
                    continue
                try:
                    p.suspend()
                    self._suspended.add(p.pid)
                except psutil.Error:
                    pass
        elif self._suspended:
            self.resume_all()

    def resume_all(self):
        for pid in list(self._suspended):
            try:
                psutil.Process(pid).resume()
            except psutil.Error:
                pass
            self._suspended.discard(pid)

    def write_log(self, m, d, why="change"):
        if not self.log_path:
            return
        rec = {"t": time.strftime("%Y-%m-%d %H:%M:%S"), "event": why, "measured": m, "decided": d, "suspended": sorted(self._suspended)}
        with open(self.log_path, "a", encoding="utf-8") as f:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
        self._last_log = time.time()

    def step(self):
        m = self.measure()
        d = self.decide(m)
        changed = any(d[k] != self._advice.get(k) for k in ("mode", "priority", "threads", "arenas", "pause", "gpu_duty"))
        with self._lock:
            self._advice = d
        self.apply(d)
        if changed:
            self.write_log(m, d)
        elif time.time() - self._last_log > 60:
            self.write_log(m, d, "heartbeat")
        if self.advice_file:
            tmp = self.advice_file + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(d, f)
            os.replace(tmp, self.advice_file)
        return m, d

    def advice(self):
        with self._lock:
            return dict(self._advice)

    def _loop(self):
        while not self._stop.wait(self.interval):
            try:
                self.step()
            except Exception as ex:   # the governor never takes the run down
                if self.log_path:
                    with open(self.log_path, "a", encoding="utf-8") as f:
                        f.write(json.dumps({"t": time.strftime("%Y-%m-%d %H:%M:%S"), "event": "error", "error": repr(ex)}) + "\n")

    def start(self):
        self.step()
        threading.Thread(target=self._loop, name="governor", daemon=True).start()
        return self

    def stop(self):
        self._stop.set()
        self.resume_all()


def read_advice(path, cache={}):
    """The wrapper's advice from its file (the last good one when it is being written)."""
    try:
        with open(path, encoding="utf-8") as f:
            cache[path] = json.load(f)
    except Exception:
        pass
    return cache.get(path)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("cmd", choices=["run", "once"])
    ap.add_argument("--log", default=None, help="the decision log (JSONL); run: default build/rl/governor-<time>.jsonl")
    ap.add_argument("--interval", type=float, default=2.0)
    ap.add_argument("--max-threads", type=int, default=0)
    ap.add_argument("--max-arenas", type=int, default=0)
    ap.add_argument("--margin", type=float, default=2.0, help="cores left free over the others' use")
    argv = sys.argv[1:]
    cut = argv.index("--") if "--" in argv else len(argv)   # (the command after --: its own options are not ours)
    a = ap.parse_args(argv[:cut])
    a.rest = argv[cut + 1:]
    if a.cmd == "once":
        g = Governor(max_threads=a.max_threads or None, max_arenas=a.max_arenas or None, margin=a.margin)
        time.sleep(1.0)
        m = g.measure()
        print(json.dumps({"measured": m, "decided": g.decide(m)}, indent=1, ensure_ascii=False))
        return
    cmd = a.rest
    if not cmd:
        sys.exit("governor run -- <command...>")
    root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
    log = a.log or os.path.join(root, "build", "rl", "governor-%s.jsonl" % time.strftime("%Y%m%d-%H%M%S"))
    os.makedirs(os.path.dirname(log), exist_ok=True)
    adv = log + ".advice.json"
    env = dict(os.environ, AIGUIDE_GOVERNOR=adv, AIGUIDE_GOVERNOR_LOG=log)
    import shutil
    cmd = [shutil.which(cmd[0]) or cmd[0]] + cmd[1:]   # (CreateProcess does not look along PATH as a shell does)
    child = subprocess.Popen(cmd, env=env, creationflags=BELOW if IS_WIN else 0)
    g = Governor(root_pid=child.pid, log=log, max_threads=a.max_threads or None, max_arenas=a.max_arenas or None, interval=a.interval,
                 margin=a.margin, self_pause=True, advice_file=adv)
    g.start()
    print("governor: log %s" % log, flush=True)
    try:
        rc = child.wait()
    except KeyboardInterrupt:
        g.resume_all()
        child.terminate()
        rc = child.wait()
    g.stop()
    sys.exit(rc)


if __name__ == "__main__":
    main()
