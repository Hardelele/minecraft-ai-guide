"""PNG slices of cubes: input channels | labels (| prediction). Horizontal slices (top view, x right, z down) and a
vertical one (x right, y up).

    python places/viz.py build/places/real/server.npz --n 12
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent))
from names import CHANNELS, CLASSES  # noqa: E402

CH_COL = np.array([
    (235, 240, 250), (40, 40, 40), (60, 110, 230), (240, 90, 0), (140, 100, 60), (130, 130, 130), (200, 60, 200),
    (40, 120, 30), (120, 220, 90), (210, 170, 110), (160, 60, 30), (110, 70, 40), (230, 230, 0), (255, 255, 120),
    (255, 0, 120), (70, 70, 110)], np.uint8)
CL_COL = np.array([
    (90, 90, 90), (170, 220, 255), (255, 200, 0), (240, 60, 60), (130, 0, 200), (0, 200, 200), (255, 120, 255),
    (150, 70, 20), (0, 0, 0)], np.uint8)


def _img(a: np.ndarray, col: np.ndarray, scale: int) -> Image.Image:
    a = np.where(a == 255, len(col) - 1, a)
    return Image.fromarray(col[a]).resize((a.shape[1] * scale, a.shape[0] * scale), Image.NEAREST)


def panel(ch, lab, pred=None, ys=None, scale=6, title=""):
    """ch, lab, pred [X, Y, Z]."""
    rows = []
    ys = ys or [8, 16, 24]
    for y in ys:
        rows.append([ch[:, y, :].T, lab[:, y, :].T] + ([pred[:, y, :].T] if pred is not None else []))
    zc = ch.shape[2] // 2
    rows.append([np.flip(ch[:, :, zc].T, 0), np.flip(lab[:, :, zc].T, 0)] + ([np.flip(pred[:, :, zc].T, 0)] if pred is not None else []))
    names = [f"y={y}" for y in ys] + [f"z={zc} (vertical)"]
    side = ch.shape[0] * scale
    cols = len(rows[0])
    W = cols * (side + 8) + 8
    H = len(rows) * (side + 22) + 30
    img = Image.new("RGB", (W + 190, H), (255, 255, 255))
    d = ImageDraw.Draw(img)
    d.text((8, 4), title, fill=(0, 0, 0))
    for r, (row, nm) in enumerate(zip(rows, names)):
        y0 = 26 + r * (side + 22)
        d.text((8, y0), nm + "   input | labels" + (" | prediction" if pred is not None else ""), fill=(0, 0, 0))
        for c, a in enumerate(row):
            img.paste(_img(a, CH_COL if c == 0 else CL_COL, scale), (8 + c * (side + 8), y0 + 14))
    lx = W
    for i, n in enumerate(CHANNELS):
        d.rectangle((lx, 10 + i * 14, lx + 10, 20 + i * 14), fill=tuple(int(v) for v in CH_COL[i]))
        d.text((lx + 14, 9 + i * 14), n, fill=(0, 0, 0))
    for i, n in enumerate(CLASSES + ["ignore"]):
        y = 250 + i * 14
        d.rectangle((lx, y, lx + 10, y + 10), fill=tuple(int(v) for v in CL_COL[i]))
        d.text((lx + 14, y - 1), n, fill=(0, 0, 0))
    return img


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("npz")
    ap.add_argument("--n", type=int, default=10)
    ap.add_argument("--out", default=str(Path(__file__).resolve().parent.parent / "build" / "places" / "viz"))
    ap.add_argument("--seed", type=int, default=0)
    a = ap.parse_args()
    d = np.load(a.npz)
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(a.seed)
    idx = rng.choice(len(d["ch"]), min(a.n, len(d["ch"])), replace=False)
    stem = Path(a.npz).stem
    for i in idx:
        m = d["meta"][i] if "meta" in d else None
        lab = d["lab"][i]
        # pick slices where there is the most non-solid
        ny = ((lab >= 2) & (lab != 255)).sum((0, 2)) * 100 + ((lab != 0) & (lab != 255)).sum((0, 2))
        ys = sorted(np.argsort(ny)[-3:].tolist())
        panel(d["ch"][i], lab, ys=ys, title=f"{stem} #{i} meta={None if m is None else m.tolist()}").save(out / f"{stem}-{i}.png")
    print(out)
