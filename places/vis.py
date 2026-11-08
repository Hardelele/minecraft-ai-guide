"""What an agent standing somewhere would have seen: rays from eye points, radius ~20, through see-through blocks."""
from __future__ import annotations

import numpy as np

from channels import SEE_THROUGH, SOLID
from label import SPACE_CH


def _sphere(n: int) -> np.ndarray:
    i = np.arange(n) + 0.5
    phi = np.arccos(1 - 2 * i / n)
    th = np.pi * (1 + 5 ** 0.5) * i
    return np.stack([np.cos(th) * np.sin(phi), np.cos(phi), np.sin(th) * np.sin(phi)], 1).astype(np.float32)


DIRS = _sphere(4000)


def standable(ch: np.ndarray) -> np.ndarray:
    """Feet cells: space here and above, something solid below."""
    sp = SPACE_CH[ch]
    out = np.zeros(ch.shape, bool)
    out[:, 1:-1, :] = sp[:, 1:-1, :] & sp[:, 2:, :] & SOLID[ch[:, :-2, :]]
    return out


def visible(ch: np.ndarray, eyes: np.ndarray, radius: float = 20.0, step: float = 0.4) -> np.ndarray:
    """ch [X, Y, Z] channel indices; eyes [n, 3] float positions (cell units). Returns [X, Y, Z] bool."""
    vis = np.zeros(ch.shape, bool)
    shape = np.array(ch.shape)
    see = SEE_THROUGH[ch]
    t = np.arange(0, radius, step, dtype=np.float32)
    for e in eyes:
        pts = e[None, None, :] + DIRS[:, None, :] * t[None, :, None]       # [ndir, nstep, 3]
        idx = np.floor(pts).astype(np.int32)
        inside = np.all((idx >= 0) & (idx < shape), -1)
        ci = np.clip(idx, 0, shape - 1)
        opaque = ~see[ci[..., 0], ci[..., 1], ci[..., 2]] | ~inside
        before = np.cumsum(opaque, 1) - opaque                            # opaque cells strictly before
        ok = inside & (before == 0)
        p = ci[ok]
        vis[p[:, 0], p[:, 1], p[:, 2]] = True
    return vis


def random_view(ch: np.ndarray, rng: np.random.Generator, region: tuple[slice, slice, slice] | None = None,
                n_min: int = 1, n_max: int = 6) -> np.ndarray | None:
    """Visibility from a few random standing points (inside region, if given); None if nobody can stand there."""
    st = standable(ch)
    if region is not None:
        m = np.zeros_like(st)
        m[region] = True
        st &= m
    pts = np.argwhere(st)
    if len(pts) == 0:
        return None
    k = int(rng.integers(n_min, n_max + 1))
    sel = pts[rng.choice(len(pts), min(k, len(pts)), replace=False)]
    eyes = sel.astype(np.float32) + np.array([0.5, 1.6, 0.5], np.float32)
    return visible(ch, eyes)
