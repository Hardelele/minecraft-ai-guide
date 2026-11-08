"""Weak labels by rules, on the full geometry of a context window (before any visibility mask).

    labels = weak_labels(ch, sky)   # ch [X, Y, Z] channel indices, sky [X, Y, Z] bool: nothing blocks the sky above

The rules (places/README.md has them in words): sky -> open_outside; narrow tall columns -> shaft; covered space
enclosed by built blocks -> room; narrow covered space -> passage; wide natural covered space -> cave_hall;
passage cells where three or more branches meet -> junction; built blocks bounding a room -> built_structure.
"""
from __future__ import annotations

import numpy as np
from scipy import ndimage

from channels import AIR, BUILT, CLIMB, DOOR, FENCE, LIGHT, PLANT, STORAGE, WOOD

SOLID_, OUTSIDE, PASSAGE, ROOM, CAVE, SHAFT, JUNCTION, STRUCT = range(8)
IGNORE = 255

SPACE_CH = np.zeros(256, bool)
SPACE_CH[[AIR, PLANT, CLIMB, LIGHT]] = True
BUILTLIKE = np.zeros(256, bool)
BUILTLIKE[[BUILT, DOOR, FENCE, STORAGE]] = True
NO_HIT = 255
NARROW = 4.5     # natural tunnels wander: a width of 3-4 blocks is still a passage


def sky_of(ch: np.ndarray) -> np.ndarray:
    """[X, Y, Z]: True where no sky blocker lies above in the column (within the array). Leaves, plants, fences,
    torches, ladders do not block."""
    see = SPACE_CH.copy()
    see[[WOOD, FENCE]] = True
    blocker = ~see[ch]
    inc = np.flip(np.logical_or.accumulate(np.flip(blocker, 1), 1), 1)   # a blocker here or above
    above = np.zeros_like(inc)
    above[:, :-1] = inc[:, 1:]
    return ~above


def _shift(a: np.ndarray, d: tuple[int, int, int], fill) -> np.ndarray:
    """out[p] = a[p - d]."""
    out = np.full_like(a, fill)
    src = [slice(None)] * 3
    dst = [slice(None)] * 3
    for i, k in enumerate(d):
        if k > 0:
            src[i], dst[i] = slice(0, -k), slice(k, None)
        elif k < 0:
            src[i], dst[i] = slice(-k, None), slice(0, k)
    out[tuple(dst)] = a[tuple(src)]
    return out


def runs(s: np.ndarray, d: tuple[int, int, int], cap: int = 16) -> np.ndarray:
    """Length of the run of True through each cell along direction d (both ways), capped."""
    s8 = s.astype(np.int16)
    f = s8.copy()
    b = s8.copy()
    for _ in range(cap):
        f = s8 * (_shift(f, d, 0) + 1)
        b = s8 * (_shift(b, (-d[0], -d[1], -d[2]), 0) + 1)
    return np.minimum(f + b - s8, cap * 2)


def hits(ch: np.ndarray, space: np.ndarray, d: tuple[int, int, int], rng: int = 12) -> np.ndarray:
    """Channel of the first non-space cell from each cell along d (NO_HIT if none within rng)."""
    hit = np.full(ch.shape, NO_HIT, np.uint8)
    cur = np.where(space, NO_HIT, ch).astype(np.uint8)   # at distance 0: itself if solid
    # walk: hit[p] = first solid at p + k*d, k = 1..rng
    found = np.zeros(ch.shape, bool)
    look = ch
    sp = space
    for _ in range(rng):
        look = _shift(look, (-d[0], -d[1], -d[2]), NO_HIT)
        sp = _shift(sp, (-d[0], -d[1], -d[2]), True)
        new = ~found & ~sp & (look != NO_HIT)
        hit[new] = look[new]
        found |= new
    del cur
    return hit


def junction_centres(m: np.ndarray, cand: np.ndarray, r: int = 3) -> np.ndarray:
    """Cells of cand where the part of m connected to the cell inside a (2r+1)^3 box meets the box shell in >= 3
    separate pieces."""
    out = np.zeros(m.shape, bool)
    pad = np.pad(m, r)
    w = 2 * r + 1
    shell = np.ones((w, w, w), bool)
    shell[1:-1, 1:-1, 1:-1] = False
    s26 = np.ones((3, 3, 3), bool)
    for x, y, z in zip(*np.nonzero(cand)):
        box = pad[x:x + w, y:y + w, z:z + w]
        if box[shell].sum() < 6:
            continue
        lab, _ = ndimage.label(box)
        own = lab == lab[r, r, r]
        sh = own & shell
        _, n = ndimage.label(sh, s26)
        if n >= 3:
            out[x, y, z] = True
    return out


def weak_labels(ch: np.ndarray, sky: np.ndarray, loaded: np.ndarray | None = None, junction_stride: int = 1) -> np.ndarray:
    space = SPACE_CH[ch]
    lab = np.zeros(ch.shape, np.uint8)

    rx = runs(space, (1, 0, 0))
    rz = runs(space, (0, 0, 1))
    ry = runs(space, (0, 1, 0))
    rd1 = runs(space, (1, 0, 1), 12)
    rd2 = runs(space, (1, 0, -1), 12)
    width = np.minimum(np.minimum(rx, rz), 1.414 * np.minimum(rd1, rd2))
    # a cell takes the widest width around it, so the rough edges of a hall stay in the hall
    width = np.where(space, ndimage.maximum_filter(np.where(space, width, 0), size=3), 0)

    hx = [hits(ch, space, d) for d in ((1, 0, 0), (-1, 0, 0), (0, 0, 1), (0, 0, -1))]
    up = hits(ch, space, (0, 1, 0))
    down = hits(ch, space, (0, -1, 0))
    nwood = sum((h == WOOD).astype(np.int8) for h in hx)
    nbuilt = sum(BUILTLIKE[h].astype(np.int8) for h in hx)

    covered = space & ~sky
    shaft = space & (rx <= 3) & (rz <= 3) & (ry >= 5) & (nwood == 0) & (sum((h != NO_HIT).astype(np.int8) for h in hx) == 4)
    # walls of a building around, and its roof or its floor (dungeons have a natural ceiling)
    room = covered & (BUILTLIKE[up] | BUILTLIKE[down]) & (nbuilt >= 3)
    # a long narrow space inside a building is a corridor
    corridor = room & (width <= NARROW) & (np.maximum(rx, rz) >= 6)
    room &= ~corridor

    # outside: sky cells and the covered cells within 2 steps of them (overhangs, under trees), never rooms
    outside = space & sky & ~shaft
    grow = outside.copy()
    six = ndimage.generate_binary_structure(3, 1)
    for _ in range(2):
        grow = ndimage.binary_dilation(grow, six) & covered & ~room & ~shaft
        grow |= outside
    outside = grow

    rest = covered & ~outside & ~shaft & ~room
    passage = rest & (width <= NARROW) | corridor & ~outside
    wide = rest & ~passage
    cl, n = ndimage.label(wide, six)
    if n:
        sizes = ndimage.sum(np.ones_like(cl), cl, np.arange(1, n + 1))
        big = np.zeros(n + 1, bool)
        big[1:] = sizes >= 120
        cave = big[cl]
    else:
        cave = np.zeros_like(wide)
    passage |= wide & ~cave

    # tiny closed pockets are nothing
    pk, n = ndimage.label(covered & ~outside, six)
    if n:
        sizes = ndimage.sum(np.ones_like(pk), pk, np.arange(1, n + 1))
        tiny = np.zeros(n + 1, bool)
        tiny[1:] = sizes < 6
        tiny = tiny[pk]
    else:
        tiny = np.zeros_like(covered)

    lab[outside] = OUTSIDE
    lab[passage] = PASSAGE
    lab[cave] = CAVE
    lab[room] = ROOM
    lab[shaft & ~outside] = SHAFT
    lab[shaft & sky] = SHAFT

    m = (lab == PASSAGE) | (lab == SHAFT)
    cand = lab == PASSAGE
    if junction_stride > 1:
        g = np.zeros_like(cand)
        g[::junction_stride, :, ::junction_stride] = True
        cand &= g
    jc = junction_centres(m, cand)
    if jc.any():
        jj = ndimage.binary_dilation(jc, np.ones((3, 3, 3), bool), iterations=1) & (lab == PASSAGE)
        lab[jj] = JUNCTION
    lab[tiny & (lab != OUTSIDE)] = SOLID_

    # built structure: built components touching a room, or big wall-like ones
    built = BUILTLIKE[ch]
    bl, n = ndimage.label(built, np.ones((3, 3, 3), bool))
    if n:
        near_room = ndimage.binary_dilation(lab == ROOM, np.ones((3, 3, 3), bool), iterations=2)
        touch = np.zeros(n + 1, bool)
        touch[np.unique(bl[near_room & built])] = True
        sizes = ndimage.sum(np.ones_like(bl), bl, np.arange(1, n + 1))
        objs = ndimage.find_objects(bl)
        for i, sl in enumerate(objs, 1):
            if sl is None:
                continue
            ext = [s.stop - s.start for s in sl]
            if sizes[i - 1] >= 40 and ext[1] >= 3 and max(ext[0], ext[2]) >= 4:
                touch[i] = True
        touch[0] = False
        lab[touch[bl]] = STRUCT

    if loaded is not None:
        lab[~loaded] = IGNORE
    return lab
