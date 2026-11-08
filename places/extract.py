"""Cubes 32^3 from copies of worlds: channels, weak labels, a visibility mask.

    python places/extract.py                       # every world in build/places/worlds -> build/places/real/<world>.npz
    python places/extract.py --worlds server klodi --per-region 48
"""
from __future__ import annotations

import argparse
import sys
import time
from multiprocessing import Pool
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from channels import AIR, OTHER, lut  # noqa: E402
from label import BUILTLIKE as BUILTLIKE_CH, SPACE_CH, sky_of, weak_labels  # noqa: E402
from vis import random_view  # noqa: E402
from world import RegionVolume, registry, world_regions  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
WORLDS = ROOT / "build" / "places" / "worlds"
OUT = ROOT / "build" / "places" / "real"
S = 32
M = 12            # context margin around a cube for the rules
PAD = M


def region_cubes(args):
    path, table, per_region, seed = args
    rng = np.random.default_rng(seed)
    v = RegionVolume(path)
    if v.loaded.sum() < 4:
        return path.name, None
    ch = table[v.blocks]
    v.blocks = v.meta = None
    loaded = np.repeat(np.repeat(v.loaded, 16, 0), 16, 1)[:, None, :]       # [X, 1, Z]
    ch[~np.broadcast_to(loaded, ch.shape)] = AIR
    # out of the world: stone-like below, air above
    ch = np.concatenate([np.full((512, PAD, 512), OTHER, np.uint8), ch, np.full((512, PAD, 512), AIR, np.uint8)], 1)
    sky = sky_of(ch)
    H = ch.shape[1]

    picks = []
    tries = 0
    want = {"built": per_region // 3, "covered": per_region // 2, "surface": per_region // 6}
    got = {k: 0 for k in want}
    while sum(got.values()) < per_region and tries < 3000:
        tries += 1
        x0 = int(rng.integers(M, 512 - M - S))
        z0 = int(rng.integers(M, 512 - M - S))
        y0 = int(rng.integers(PAD, H - PAD - S))
        if not v.loaded[(x0 - M) // 16:(x0 + S + M - 1) // 16 + 1, (z0 - M) // 16:(z0 + S + M - 1) // 16 + 1].all():
            continue
        c = (slice(x0, x0 + S), slice(y0, y0 + S), slice(z0, z0 + S))
        cc, sk = ch[c], sky[c]
        sp = SPACE_CH[cc]
        nb, nc, ns = BUILTLIKE_CH[cc].sum(), (sp & ~sk).sum(), (sp & sk).sum()
        if nb >= 60 and got["built"] < want["built"]:
            kind = "built"
        elif nc >= 400 and got["covered"] < want["covered"]:
            kind = "covered"
        elif 2000 < ns < 30000 and got["surface"] < want["surface"] and rng.random() < 0.3:
            kind = "surface"
        elif tries > 1500 and nc >= 150 and got["covered"] < want["covered"]:
            kind = "covered"
        else:
            continue
        got[kind] += 1
        picks.append((x0, y0, z0, kind))

    chs, labs, viss, meta = [], [], [], []
    for x0, y0, z0, kind in picks:
        w = (slice(x0 - M, x0 + S + M), slice(y0 - M, y0 + S + M), slice(z0 - M, z0 + S + M))
        cw = ch[w]
        lab = weak_labels(cw, sky[w])
        inner = (slice(M, M + S),) * 3
        vis = random_view(cw, rng, inner)
        vis = np.ones((S, S, S), bool) if vis is None else vis[inner]
        chs.append(cw[inner].copy())
        labs.append(lab[inner].copy())
        viss.append(vis)
        meta.append((v.rx * 512 + x0, y0 - PAD, v.rz * 512 + z0, ["built", "covered", "surface"].index(kind)))
    if not chs:
        return path.name, None
    return path.name, (np.stack(chs), np.stack(labs), np.packbits(np.stack(viss), axis=-1), np.array(meta, np.int32))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--worlds", nargs="*")
    ap.add_argument("--per-region", type=int, default=48)
    ap.add_argument("--procs", type=int, default=6)
    a = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    worlds = a.worlds or sorted(p.name for p in WORLDS.iterdir() if p.is_dir())
    for wi, w in enumerate(worlds):
        t = time.time()
        reg = registry(WORLDS / w / "level.dat")
        if not reg:
            print(f"{w}: no FML registry in level.dat, skipped")
            continue
        table = lut(reg)
        jobs = [(p, table, a.per_region, 1000 * wi + i) for i, p in enumerate(world_regions(WORLDS / w))]
        with Pool(a.procs) as pool:
            res = [r for _, r in pool.imap_unordered(region_cubes, jobs) if r is not None]
        if not res:
            print(f"{w}: nothing")
            continue
        ch, lab, vis, meta = (np.concatenate(x) for x in zip(*res))
        np.savez_compressed(OUT / f"{w}.npz", ch=ch, lab=lab, vis=vis, meta=meta)
        dist = np.bincount(lab[lab != 255].ravel(), minlength=8) / max(1, (lab != 255).sum())
        print(f"{w}: {len(ch)} cubes from {len(jobs)} regions, {time.time() - t:.0f}s, labels "
              + " ".join(f"{x:.3f}" for x in dist), flush=True)


if __name__ == "__main__":
    main()
