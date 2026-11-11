"""The update's speed alone (no environment): updates a second, the GPU's SM % by the process (nvidia-smi pmon) and the CPU a
process takes, on a replay of the recorded teacher; eager or as a CUDA graph.

    python tools/rl/dreamer/bench_update.py --b1 128 --b2 32 --seq 32 --iters 40 [--graph] [--bf16]
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
import threading
import time
from pathlib import Path

import numpy as np
import psutil
import torch

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from dreamer import spaces as S  # noqa: E402
from dreamer.agent import DEFAULT, Dreamer  # noqa: E402
from dreamer.replay import Replay  # noqa: E402
from dreamer.train import load_teacher  # noqa: E402


def sm_sampler(stop, out):
    pid = os.getpid()
    while not stop.is_set():
        try:
            o = subprocess.run(["nvidia-smi", "pmon", "-c", "1", "-s", "u"], capture_output=True, text=True, timeout=10, creationflags=0x08000000).stdout
            for line in o.splitlines():
                f = line.split()
                if len(f) > 3 and not line.startswith("#") and f[1].isdigit() and int(f[1]) == pid and f[3] != "-":
                    out.append(float(f[3]))
        except Exception:
            pass
        time.sleep(0.3)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--b1", type=int, default=128)
    ap.add_argument("--b2", type=int, default=32)
    ap.add_argument("--seq", type=int, default=32)
    ap.add_argument("--iters", type=int, default=40)
    ap.add_argument("--graph", action="store_true")
    ap.add_argument("--bf16", action="store_true")
    ap.add_argument("--teacher", default="build/rl/open/teacher/rec1.jsonl.gz")
    a = ap.parse_args()
    torch.set_num_threads(2)
    rep = Replay(60000)
    load_teacher([a.teacher], rep)
    teach = Replay(60000)
    load_teacher([a.teacher], teach)
    cfg = dict(DEFAULT)
    agent = Dreamer(cfg, "cuda")
    proc = psutil.Process()
    stop, sm = threading.Event(), []
    th = threading.Thread(target=sm_sampler, args=(stop, sm), daemon=True)
    th.start()
    if a.graph:
        from dreamer.fast import FastUpdate
        fast = FastUpdate(agent, a.b1, a.b2, a.seq, bf16=a.bf16)
        step = lambda: fast.update(rep.sample(a.b1, a.seq), teach.sample(a.b2, a.seq), 0.5)
    else:
        step = lambda: agent.train_step(rep.sample(a.b1, a.seq), bc_batch=teach.sample(a.b2, a.seq), bc_weight=0.5)
    for _ in range(4):
        step()
    torch.cuda.synchronize()
    sm.clear()
    c0, t0 = proc.cpu_times(), time.time()
    for _ in range(a.iters):
        step()
    torch.cuda.synchronize()
    dt = time.time() - t0
    c1 = proc.cpu_times()
    stop.set()
    cpu = (c1.user + c1.system - c0.user - c0.system) / dt * 100
    rows = (a.b1 + a.b2) * a.seq
    print(f"{'graph' if a.graph else 'eager'}{' bf16' if a.bf16 else ''} b1 {a.b1} b2 {a.b2} T {a.seq}: {a.iters / dt:.2f} updates/s, {a.iters * rows / dt:.0f} rows/s, "
          f"process CPU {cpu:.0f}% of one core, GPU SM {np.mean(sm) if sm else float('nan'):.0f}% (n={len(sm)}), peak mem {torch.cuda.max_memory_allocated() / 2**30:.1f} GB")


if __name__ == "__main__":
    main()
