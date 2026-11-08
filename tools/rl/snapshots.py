"""The weights for a video and for the mod: the untrained net (epoch 0, the same init as train.py's with --seed),
a run's snapshots by epoch, the run's last weights as the mod's policy.bin and its policy-test.bin.

    python tools/rl/snapshots.py --run sim1 --epochs 1,10,50        # -> rl/snapshots/policy-epoch-{0,1,10,50}.bin
    python tools/rl/snapshots.py --run sim1 --ckpt ckpt.pt --mod    # and res/assets/aiguide/rl/policy.bin, rl/policy-test.bin
"""
from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from pathlib import Path

import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import spec  # noqa: E402
from export_policy import write_policy  # noqa: E402
from policy import Policy  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "rl" / "snapshots"

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", default="sim1")
    ap.add_argument("--epochs", default="1,10,50")
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--hidden", type=int, default=256)
    ap.add_argument("--ckpt", default="final.pt", help="the run's checkpoint for the mod's weights")
    ap.add_argument("--mod", action="store_true", help="also write the mod's policy.bin and rl/policy-test.bin")
    a = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    torch.manual_seed(a.seed)   # train.py: torch.manual_seed(seed), then Policy(...): the same untrained net
    write_policy(Policy(spec.D, spec.A, a.hidden), OUT / "policy-epoch-0.bin")
    print(OUT / "policy-epoch-0.bin")
    run = ROOT / "build" / "rl" / "runs" / a.run
    for e in [x for x in a.epochs.split(",") if x]:
        src = run / "snapshots" / f"policy-epoch-{e}.bin"
        if src.exists():
            shutil.copyfile(src, OUT / src.name)
            print(OUT / src.name)
        else:
            print("no", src)
    if a.mod:
        subprocess.check_call([sys.executable, str(ROOT / "tools" / "rl" / "export_policy.py"), "--ckpt", str(run / a.ckpt)])
