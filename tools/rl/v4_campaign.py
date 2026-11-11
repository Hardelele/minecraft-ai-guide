"""The reward v4's training on the batch simulator, watched: train.py --env simbatch --reward v4 (its resource governor
in it), and every --every minutes the newest checkpoint is checked apart (another process, the PC's spare share):

  * held-out seeds on the batch simulator (tools/rl/simbatch_heldout.py: success, the surrender = noop_end, by scenario),
  * tools/rl/eval_loopholes.py (the box and wait, the walks, the surrender under v3 and v4, in the Python simulator),
  * tools/tests/test_rl_exploits.py (the reward's own loopholes: it must stay all ok).

A run killed (the engine agent's watchdog stops train.py while League of Legends runs) is started again from its last
checkpoint once League is gone; the watch writes build/rl/runs/<run>/watch.jsonl.

    python tools/rl/v4_campaign.py --run v4sb1 --init build/rl/init/v31eng1.pt --hours 3 -- <train.py options>
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

import psutil

ROOT = Path(__file__).resolve().parents[2]
PY = sys.executable


def league() -> bool:
    return any(p.info["name"] == "League of Legends.exe" for p in psutil.process_iter(["name"]))


def run_json(cmd, timeout=3600) -> list[dict]:
    out = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, timeout=timeout)
    rows = []
    for ln in out.stdout.splitlines():
        try:
            rows.append(json.loads(ln))
        except ValueError:
            pass
    return rows, out


def check(run_dir: Path, tag: str, n: int) -> dict:
    ck = run_dir / ("check-%s.pt" % tag)
    shutil.copy(run_dir / "ckpt.pt", ck)
    rows, _ = run_json([PY, "tools/rl/simbatch_heldout.py", "--nets", str(ck), "--n", str(n), "--reward", "v4", "--cpu",
                        "--out", str(run_dir / "heldout.jsonl")])
    per = {r["scenario"]: r for r in rows if "scenario" in r}
    lo = subprocess.run([PY, "tools/rl/eval_loopholes.py", str(ck), "--n", "12"], cwd=ROOT, capture_output=True, text=True, timeout=3600)
    ex = subprocess.run([PY, "tools/tests/test_rl_exploits.py"], cwd=ROOT, capture_output=True, text=True, timeout=3600)
    rec = {"t": time.strftime("%Y-%m-%d %H:%M:%S"), "tag": tag,
           "mean_success": round(sum(r["success"] for r in per.values()) / max(1, len(per)), 3),
           "mean_noop_end": round(sum(r["noop_end"] for r in per.values()) / max(1, len(per)), 3),
           "waves": {k: (v["success"], v["noop_end"], v["cleared"]) for k, v in per.items() if k.startswith("waves") or k == "night:shelter"},
           "loopholes": lo.stdout.strip().splitlines()[-3:], "exploits_ok": ex.returncode == 0 and "all ok" in ex.stdout,
           "exploits_tail": ex.stdout.strip().splitlines()[-2:]}
    with open(run_dir / "watch.jsonl", "a", encoding="utf-8") as f:
        f.write(json.dumps(rec, ensure_ascii=False) + "\n")
    return rec


def main():
    argv = sys.argv[1:]
    rest = argv[argv.index("--") + 1:] if "--" in argv else []
    argv = argv[:argv.index("--")] if "--" in argv else argv
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", required=True)
    ap.add_argument("--init", required=True)
    ap.add_argument("--hours", type=float, default=3.0)
    ap.add_argument("--every", type=float, default=20.0, help="minutes between checks")
    ap.add_argument("--n", type=int, default=100, help="held-out episodes a scenario")
    a = ap.parse_args(argv)
    run_dir = ROOT / "build" / "rl" / "runs" / a.run
    end = time.time() + a.hours * 3600
    proc = None
    init = a.init
    part = 0
    last_check = time.time()
    while time.time() < end:
        if proc is None or proc.poll() is not None:
            if proc is not None and (run_dir / "ckpt.pt").exists():   # killed or ended: on from the last checkpoint
                part += 1
                keep = run_dir.parent / ("%s-part%d" % (a.run, part))
                shutil.copytree(run_dir, keep, dirs_exist_ok=True)
                init = str(keep / "ckpt.pt")
            if league():
                time.sleep(30)
                continue
            mins = max(1.0, (end - time.time()) / 60)
            cmd = [PY, "tools/rl/train.py", "--env", "simbatch", "--reward", "v4", "--run", a.run, "--init", init, "--minutes", "%.1f" % mins] + rest
            log = open(run_dir.parent / ("%s-train.log" % a.run), "a", encoding="utf-8")
            log.write("\n# %s %s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), " ".join(cmd)))
            log.flush()
            proc = subprocess.Popen(cmd, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
        time.sleep(20)
        if time.time() - last_check > a.every * 60 and (run_dir / "ckpt.pt").exists() and not league():
            last_check = time.time()
            rec = check(run_dir, time.strftime("%H%M"), a.n)
            print(json.dumps(rec, ensure_ascii=False), flush=True)
    if proc is not None and proc.poll() is None:
        proc.wait(timeout=600)
    if (run_dir / "ckpt.pt").exists():
        print(json.dumps(check(run_dir, "final", a.n), ensure_ascii=False), flush=True)


if __name__ == "__main__":
    os.environ.setdefault("PYTHONUNBUFFERED", "1")
    main()
