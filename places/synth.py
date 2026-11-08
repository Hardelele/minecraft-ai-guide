"""Synthetic scenes with known places: houses (any material, doors, windows, roofs, unfinished), mines (tunnels,
branches, stairs, shafts with ladders), wells, caves, and distractors (trees, heaps, pillars, walls, arches, fences).

A scene is built 48^3 and cropped to the 32^3 centre. Labels: the weak rules (label.py) for what the generator does
not know, overridden by what it does (room interiors, house parts, tunnels, junctions, shafts, halls).

    python places/synth.py --n 4000 --out build/places/synth/train.npz --seed 0
    python places/synth.py --n 300 --out build/places/synth/ood-houses.npz --seed 9 --kind houses_unusual
"""
from __future__ import annotations

import argparse
import sys
import time
from multiprocessing import Pool
from pathlib import Path

import numpy as np
from scipy import ndimage

sys.path.insert(0, str(Path(__file__).resolve().parent))
from channels import (AIR, BUILT, CLIMB, DOOR, FENCE, GROUND, LAVA, LIGHT, ORE, OTHER, PLANT, STONE,  # noqa: E402
                      STORAGE, WATER, WOOD)
from label import (CAVE, JUNCTION, OUTSIDE, PASSAGE, ROOM, SHAFT, SPACE_CH, STRUCT, sky_of,  # noqa: E402
                   weak_labels)
from vis import random_view  # noqa: E402

N = 48
M = 8
S = 32
UNK = 255


class Scene:
    def __init__(self, rng: np.random.Generator):
        self.rng = rng
        self.ch = np.zeros((N, N, N), np.uint8)
        self.ex = np.full((N, N, N), UNK, np.uint8)
        self.surface = np.zeros((N, N), np.int32)

    # --- primitives
    def put(self, sl, c, lab=UNK):
        self.ch[sl] = c
        self.ex[sl] = lab

    def inb(self, x, y, z):
        return 0 <= x < N and 0 <= y < N and 0 <= z < N

    # --- terrain
    def terrain(self, level: int, rough: float = 3.0):
        r = self.rng
        h = ndimage.gaussian_filter(r.normal(0, 1, (N, N)), r.uniform(4, 9))
        h = h / (h.std() + 1e-6) * rough * r.uniform(0.3, 1.0)
        self.surface = np.clip(np.round(level + h), 1, N - 2).astype(np.int32)
        y = np.arange(N)[None, :, None]
        s = self.surface[:, None, :]
        dirt = r.integers(2, 5)
        top = GROUND
        self.ch[:] = AIR
        self.ch[np.broadcast_to(y < s - dirt, self.ch.shape)] = STONE
        self.ch[np.broadcast_to((y >= s - dirt) & (y < s), self.ch.shape)] = GROUND
        self.ch[np.broadcast_to(y == s - 1, self.ch.shape)] = top
        # ore and dirt/gravel pockets in the stone
        st = self.ch == STONE
        ore = ndimage.binary_dilation(r.random(self.ch.shape) < 0.002, iterations=1) & (r.random(self.ch.shape) < 0.6)
        self.ch[st & ore] = ORE
        pocket = ndimage.gaussian_filter(r.random(self.ch.shape), 2) > 0.62
        self.ch[st & pocket] = GROUND
        # grass and flowers on top
        gx, gz = np.nonzero(r.random((N, N)) < r.uniform(0, 0.25))
        for x, z in zip(gx, gz):
            yy = self.surface[x, z]
            if yy < N and self.ch[x, yy, z] == AIR:
                self.ch[x, yy, z] = PLANT
        # water in the low places
        if r.random() < 0.25:
            sea = int(np.percentile(self.surface, r.uniform(5, 30)))
            w = (y < sea) & (self.ch == AIR)
            self.ch[w] = WATER

    def underground(self):
        self.terrain(N + 20, 1)

    def ground_at(self, x, z):
        return int(self.surface[np.clip(x, 0, N - 1), np.clip(z, 0, N - 1)])

    # --- caves
    def worm(self, start, n, rad, lab):
        r = self.rng
        p = np.array(start, float)
        d = r.normal(0, 1, 3)
        d[1] *= 0.3
        d /= np.linalg.norm(d) + 1e-6
        g = np.indices((N, N, N), dtype=np.float32)
        for _ in range(n):
            rr = max(0.8, rad * r.uniform(0.7, 1.3))
            lo = np.maximum(0, np.floor(p - rr - 1).astype(int))
            hi = np.minimum(N, np.ceil(p + rr + 2).astype(int))
            sl = tuple(slice(a, b) for a, b in zip(lo, hi))
            dd = sum((g[i][sl] + 0.5 - p[i]) ** 2 for i in range(3))
            m = dd <= rr * rr
            sub = self.ch[sl]
            carve = m & (sub != AIR) & (sub != WATER)
            sub[carve] = AIR
            ex = self.ex[sl]
            if lab == CAVE:
                ex[m] = CAVE
            else:
                ex[m & (ex != CAVE)] = lab
            d = d + r.normal(0, 0.35, 3)
            d[1] *= 0.5
            d /= np.linalg.norm(d) + 1e-6
            p = p + d * max(1.0, rr * 0.6)
            if not (0 <= p[0] < N and 0 <= p[1] < N and 0 <= p[2] < N):
                break

    def cave(self, big: bool):
        r = self.rng
        c = r.integers(10, N - 10, 3)
        c[1] = r.integers(6, max(8, min(N - 8, int(self.surface.min()) - 4)))
        if big:
            self.worm(c, int(r.integers(6, 16)), r.uniform(3.5, 6), CAVE)
            # floor clutter: gravel, a puddle of lava or water
            if r.random() < 0.3:
                fl = (self.ex == CAVE) & (self.ch == AIR) & (np.roll(self.ch, 1, 1) != AIR)
                self.ch[fl & (r.random(fl.shape) < 0.5)] = r.choice([LAVA, WATER, GROUND])
        for _ in range(r.integers(1, 4)):
            self.worm(c + r.integers(-3, 4, 3), int(r.integers(15, 40)), r.uniform(0.8, 1.6), PASSAGE)

    # --- mines
    def tunnel(self, a, b, w, h, torches, supports, lab=PASSAGE):
        """Axis-aligned straight tunnel from a to b on floor level a[1] (b may differ in y: stairs)."""
        r = self.rng
        a = np.array(a)
        b = np.array(b)
        d = np.sign(b - a)
        n = int(max(abs(b[0] - a[0]), abs(b[2] - a[2])))
        dy = (b[1] - a[1]) / max(n, 1)
        horiz = np.array([d[0], 0, d[2]])
        side = np.array([abs(d[2]), 0, abs(d[0])])   # across the tunnel
        cells = []
        for k in range(n + 1):
            p = a + horiz * k
            y = int(round(a[1] + dy * k))
            for s in range(w):
                for hh in range(h + (1 if dy != 0 else 0)):
                    q = p + side * (s - w // 2)
                    cells.append((int(q[0]), y + hh, int(q[2])))
        cells = [c for c in cells if self.inb(*c)]
        if not cells:
            return []
        idx = tuple(np.array(cells).T)
        self.ch[idx] = AIR
        self.ex[idx] = lab
        if torches:
            for k in range(0, n + 1, int(r.integers(5, 9))):
                p = a + horiz * k
                y = int(round(a[1] + dy * k)) + 1
                q = p + side * (-(w // 2))
                if self.inb(int(q[0]), y, int(q[2])):
                    self.ch[int(q[0]), y, int(q[2])] = LIGHT
        if supports and h >= 2:
            for k in range(2, n, 4):
                p = a + horiz * k
                y = int(round(a[1] + dy * k))
                for s in (-(w // 2), w - w // 2 - 1):
                    q = p + side * s
                    for hh in range(h - 1):
                        if self.inb(int(q[0]), y + hh, int(q[2])) and w >= 3:
                            self.ch[int(q[0]), y + hh, int(q[2])] = FENCE
                            self.ex[int(q[0]), y + hh, int(q[2])] = UNK
                for s in range(w):
                    q = p + side * (s - w // 2)
                    yy = y + h
                    if self.inb(int(q[0]), yy, int(q[2])):
                        self.ch[int(q[0]), yy, int(q[2])] = BUILT
        return cells

    def mine(self, torches=None, supports=None):
        r = self.rng
        w, h = [(1, 2), (2, 2), (3, 3), (2, 3), (1, 3), (3, 2)][r.integers(0, 6)]
        torches = r.random() < 0.5 if torches is None else torches
        supports = r.random() < 0.3 if supports is None else supports
        y = int(r.integers(4, max(6, min(N - 6, int(self.surface.min()) - h - 1))))
        nodes = [np.array([int(r.integers(12, N - 12)), y, int(r.integers(12, N - 12))])]
        deg = {}
        for _ in range(int(r.integers(3, 9))):
            a = nodes[int(r.integers(0, len(nodes)))]
            axis = [0, 2][r.integers(0, 2)]
            L = int(r.integers(5, 20)) * (1 if r.random() < 0.5 else -1)
            b = a.copy()
            b[axis] += L
            b[axis] = np.clip(b[axis], 2, N - 3)
            if r.random() < 0.2:   # stairs
                b[1] = np.clip(a[1] + int(np.sign(r.normal()) * min(abs(L), 8)), 2, N - 6)
            if abs(b[axis] - a[axis]) < 3:
                continue
            self.tunnel(a, b, w, h, torches, supports)
            deg[tuple(a)] = deg.get(tuple(a), 0) + 1
            deg[tuple(b)] = deg.get(tuple(b), 0) + 1
            # a branch node in the middle of a tunnel sometimes
            if r.random() < 0.4 and abs(b[axis] - a[axis]) > 4:
                t = int(r.integers(3, abs(b[axis] - a[axis]) - 1))
                m = a.copy()
                m[axis] += t * int(np.sign(b[axis] - a[axis]))
                m[1] = a[1] + int(round((b[1] - a[1]) * t / max(1, abs(b[axis] - a[axis]))))
                if m[1] == a[1] == b[1]:
                    nodes.append(m)
                    deg[tuple(m)] = deg.get(tuple(m), 0) + 2
            nodes.append(b)
        # junctions: nodes where three or more tunnels meet; a node is crossed by tunnels, so count the arms
        for nd in nodes:
            x, y0, z = nd
            arms = 0
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                xx, zz = x + dx * (w // 2 + 2), z + dz * (w // 2 + 2)
                if self.inb(xx, y0, zz) and self.ex[xx, y0, zz] == PASSAGE:
                    arms += 1
            if arms >= 3:
                r0 = w // 2 + 1
                sl = (slice(max(0, x - r0), x + r0 + 1), slice(y0, y0 + h), slice(max(0, z - r0), z + r0 + 1))
                sp = SPACE_CH[self.ch[sl]] & (self.ex[sl] == PASSAGE)
                self.ex[sl][sp] = JUNCTION
        # a shaft with a ladder, to the surface or between levels
        if r.random() < 0.6:
            nd = nodes[int(r.integers(0, len(nodes)))]
            top = self.ground_at(nd[0], nd[2]) + 1 if r.random() < 0.6 else min(N - 1, nd[1] + int(r.integers(6, 16)))
            self.shaft(nd[0], nd[2], nd[1], top, int(r.integers(1, 3)), ladder=r.random() < 0.7)

    def shaft(self, x, z, y0, y1, s, ladder=True, lining=None):
        x0, z0 = x - s // 2, z - s // 2
        sl = (slice(max(0, x0), x0 + s), slice(max(0, y0), y1), slice(max(0, z0), z0 + s))
        self.ch[sl] = AIR
        self.ex[sl] = SHAFT
        if lining is not None:
            for yy in range(max(0, y0), min(N, y1)):
                for xx in range(x0 - 1, x0 + s + 1):
                    for zz in range(z0 - 1, z0 + s + 1):
                        if self.inb(xx, yy, zz) and not (x0 <= xx < x0 + s and z0 <= zz < z0 + s):
                            self.ch[xx, yy, zz] = lining
                            self.ex[xx, yy, zz] = STRUCT
        if ladder:
            xx = x0
            for yy in range(max(0, y0), min(N, y1)):
                if self.inb(xx, yy, z0):
                    self.ch[xx, yy, z0] = CLIMB

    def well(self):
        r = self.rng
        x, z = (int(v) for v in r.integers(14, N - 14, 2))
        g = self.ground_at(x, z)
        depth = int(r.integers(5, 16))
        s = int(r.integers(1, 3))
        lining = [BUILT, BUILT, STONE][r.integers(0, 3)]
        self.shaft(x, z, max(1, g - depth), g + 1, s, ladder=r.random() < 0.4, lining=lining)
        if r.random() < 0.5:
            x0, z0 = x - s // 2, z - s // 2
            self.ch[x0:x0 + s, max(0, g - depth):max(0, g - depth) + 2, z0:z0 + s] = WATER
            self.ex[x0:x0 + s, max(0, g - depth):max(0, g - depth) + 2, z0:z0 + s] = UNK

    # --- houses
    def house(self, material=None, underground=False):
        r = self.rng
        if material is None:
            material = int(r.choice([BUILT] * 15 + [STONE, STONE, GROUND, GROUND]))
        roof_mat = material if r.random() < 0.6 else BUILT
        floor_mat = material if r.random() < 0.5 else int(r.choice([BUILT, GROUND, STONE]))
        w, d = int(r.integers(4, 13)), int(r.integers(4, 13))
        hgt = int(r.integers(3, 6))
        x0 = int(r.integers(max(2, 16 - w), min(N - w - 2, 32)))
        z0 = int(r.integers(max(2, 16 - d), min(N - d - 2, 32)))
        if underground:
            g = int(r.integers(6, max(8, int(self.surface.min()) - hgt - 3)))
        else:
            g = int(np.median(self.surface[x0:x0 + w, z0:z0 + d]))
        g = min(g, N - hgt - 4)
        x1, z1 = x0 + w, z0 + d
        unfinished = r.random() < 0.15
        # clear the box, fill below the floor
        self.ch[x0:x1, g:g + hgt + 2, z0:z1] = AIR
        self.ex[x0:x1, g:g + hgt + 2, z0:z1] = UNK
        if not underground:
            for xx in range(x0, x1):
                for zz in range(z0, z1):
                    if self.surface[xx, zz] < g:
                        self.ch[xx, self.surface[xx, zz]:g, zz] = GROUND
        self.put((slice(x0, x1), slice(g - 1, g), slice(z0, z1)), floor_mat, STRUCT)
        walls = np.zeros((N, N, N), bool)
        walls[x0:x1, g:g + hgt, z0:z1] = True
        walls[x0 + 1:x1 - 1, g:g + hgt, z0 + 1:z1 - 1] = False
        interior = np.zeros((N, N, N), bool)
        interior[x0 + 1:x1 - 1, g:g + hgt, z0 + 1:z1 - 1] = True
        self.ch[walls] = material
        self.ex[walls] = STRUCT
        self.ch[interior] = AIR
        self.ex[interior] = ROOM
        roofless = unfinished and r.random() < 0.6
        if not roofless:
            style = r.integers(0, 3)
            if style == 0 or w < 5 or d < 5:  # flat
                self.put((slice(x0, x1), slice(g + hgt, g + hgt + 1), slice(z0, z1)), roof_mat, STRUCT)
            else:  # gabled along x or z, with an attic that is part of the room
                along_x = style == 1
                span = d if along_x else w
                for k in range((span + 1) // 2 + 1):
                    yy = g + hgt + k
                    if yy >= N:
                        break
                    if along_x:
                        lo, hi = z0 + k, z1 - k
                        if lo >= hi:
                            self.put((slice(x0, x1), slice(yy, yy + 1), slice(lo - 1, lo + 1)), roof_mat, STRUCT)
                            break
                        self.put((slice(x0, x1), slice(yy, yy + 1), slice(lo, lo + 1)), roof_mat, STRUCT)
                        self.put((slice(x0, x1), slice(yy, yy + 1), slice(hi - 1, hi)), roof_mat, STRUCT)
                        self.put((slice(x0, x0 + 1), slice(yy, yy + 1), slice(lo, hi)), material, STRUCT)
                        self.put((slice(x1 - 1, x1), slice(yy, yy + 1), slice(lo, hi)), material, STRUCT)
                        if hi - lo > 2:
                            self.put((slice(x0 + 1, x1 - 1), slice(yy, yy + 1), slice(lo + 1, hi - 1)), AIR, ROOM)
                    else:
                        lo, hi = x0 + k, x1 - k
                        if lo >= hi:
                            self.put((slice(lo - 1, lo + 1), slice(yy, yy + 1), slice(z0, z1)), roof_mat, STRUCT)
                            break
                        self.put((slice(lo, lo + 1), slice(yy, yy + 1), slice(z0, z1)), roof_mat, STRUCT)
                        self.put((slice(hi - 1, hi), slice(yy, yy + 1), slice(z0, z1)), roof_mat, STRUCT)
                        self.put((slice(lo, hi), slice(yy, yy + 1), slice(z0, z0 + 1)), material, STRUCT)
                        self.put((slice(lo, hi), slice(yy, yy + 1), slice(z1 - 1, z1)), material, STRUCT)
                        if hi - lo > 2:
                            self.put((slice(lo + 1, hi - 1), slice(yy, yy + 1), slice(z0 + 1, z1 - 1)), AIR, ROOM)
        # doors and doorways
        sides = []
        for _ in range(int(r.integers(1, 3))):
            side = int(r.integers(0, 4))
            if side < 2:
                xx = x0 if side == 0 else x1 - 1
                zz = int(r.integers(z0 + 1, z1 - 1))
            else:
                zz = z0 if side == 2 else z1 - 1
                xx = int(r.integers(x0 + 1, x1 - 1))
            sides.append(side)
            if r.random() < 0.7:
                self.put((slice(xx, xx + 1), slice(g, g + 2), slice(zz, zz + 1)), DOOR, STRUCT)
            else:
                self.put((slice(xx, xx + 1), slice(g, g + 2), slice(zz, zz + 1)), AIR, ROOM)
        # windows: glass, fence or holes
        for _ in range(int(r.integers(0, 6))):
            side = int(r.integers(0, 4))
            yy = g + 1 + int(r.integers(0, max(1, hgt - 2)))
            if side < 2:
                xx = x0 if side == 0 else x1 - 1
                zz = int(r.integers(z0 + 1, z1 - 1))
            else:
                zz = z0 if side == 2 else z1 - 1
                xx = int(r.integers(x0 + 1, x1 - 1))
            kind = r.random()
            if kind < 0.6:
                self.put((xx, yy, zz), BUILT, STRUCT)
            elif kind < 0.8:
                self.put((xx, yy, zz), FENCE, STRUCT)
            else:
                self.put((xx, yy, zz), AIR, ROOM)
        if unfinished and not roofless:
            # a missing piece of a wall
            side = int(r.integers(0, 4))
            k = int(r.integers(2, 5))
            if side < 2:
                xx = x0 if side == 0 else x1 - 1
                zz = int(r.integers(z0 + 1, max(z0 + 2, z1 - k)))
                self.put((slice(xx, xx + 1), slice(g, g + hgt), slice(zz, zz + k)), AIR, ROOM)
            else:
                zz = z0 if side == 2 else z1 - 1
                xx = int(r.integers(x0 + 1, max(x0 + 2, x1 - k)))
                self.put((slice(xx, xx + k), slice(g, g + hgt), slice(zz, zz + 1)), AIR, ROOM)
        # an inner wall with a doorway
        if w >= 8 and r.random() < 0.4:
            xm = x0 + w // 2
            self.put((slice(xm, xm + 1), slice(g, g + hgt), slice(z0 + 1, z1 - 1)), material, STRUCT)
            zz = int(r.integers(z0 + 1, z1 - 1))
            self.put((slice(xm, xm + 1), slice(g, g + 2), slice(zz, zz + 1)), DOOR if r.random() < 0.5 else AIR,
                     STRUCT if r.random() < 0.5 else ROOM)
            if self.ch[xm, g, zz] == AIR:
                self.ex[xm, g:g + 2, zz] = ROOM
        # furniture and torches inside
        inside = np.argwhere(interior & (self.ch == AIR))
        floor_cells = inside[inside[:, 1] == g]
        for _ in range(int(r.integers(0, 6))):
            if len(floor_cells) == 0:
                break
            x, y, z = floor_cells[int(r.integers(0, len(floor_cells)))]
            self.put((x, y, z), int(r.choice([STORAGE, STORAGE, BUILT, LIGHT])), STRUCT)
            if self.ch[x, y, z] == LIGHT:
                self.ex[x, y, z] = ROOM
        for _ in range(int(r.integers(0, 4))):
            if len(inside) == 0:
                break
            x, y, z = inside[int(r.integers(0, len(inside)))]
            if self.ch[x, y, z] == AIR:
                self.put((x, y, z), LIGHT, ROOM)
        # a path of fences or a porch
        if not underground and r.random() < 0.3:
            zz = z0 - 1
            if zz > 0:
                for xx in range(x0, x1, 1):
                    yy = self.ground_at(xx, zz)
                    if self.inb(xx, yy, zz):
                        self.ch[xx, yy, zz] = FENCE
        return (x0, g, z0, x1, g + hgt, z1)

    # --- distractors
    def tree(self):
        r = self.rng
        x, z = (int(v) for v in r.integers(2, N - 2, 2))
        g = self.ground_at(x, z)
        h = int(r.integers(4, 8))
        if g + h + 3 >= N:
            return
        self.ch[x, g:g + h, z] = WOOD
        rad = r.uniform(1.5, 3)
        c = np.array([x + 0.5, g + h - 0.5, z + 0.5])
        gg = np.indices((N, N, N)).astype(np.float32) + 0.5
        m = ((gg - c[:, None, None, None]) ** 2).sum(0) <= rad * rad
        m &= self.ch == AIR
        self.ch[m & (r.random(m.shape) < 0.85)] = WOOD

    def heap(self):
        r = self.rng
        x, z = (int(v) for v in r.integers(4, N - 4, 2))
        g = self.ground_at(x, z)
        mat = int(r.choice([BUILT, STONE, GROUND, OTHER, STORAGE]))
        for _ in range(int(r.integers(4, 30))):
            xx = x + int(r.integers(-2, 3))
            zz = z + int(r.integers(-2, 3))
            if not self.inb(xx, 0, zz):
                continue
            col = self.ch[xx, :, zz]
            yy = int(np.max(np.nonzero(~SPACE_CH[col])[0], initial=-1)) + 1
            if self.inb(xx, yy, zz):
                self.ch[xx, yy, zz] = mat
                self.ex[xx, yy, zz] = UNK

    def decor(self):
        """Free-standing built things that enclose nothing: pillars, a wall, an arch, a fence row, a statue."""
        r = self.rng
        kind = int(r.integers(0, 5))
        mat = int(r.choice([BUILT, BUILT, STONE, OTHER]))
        x, z = (int(v) for v in r.integers(8, N - 8, 2))
        g = self.ground_at(x, z)
        if kind == 0:     # pillars
            for _ in range(int(r.integers(1, 5))):
                xx, zz = x + int(r.integers(-6, 7)), z + int(r.integers(-6, 7))
                gg = self.ground_at(xx, zz)
                self.ch[xx, gg:min(N, gg + int(r.integers(3, 8))), zz] = mat
        elif kind == 1:   # a straight wall
            L = int(r.integers(4, 14))
            h = int(r.integers(2, 5))
            if r.random() < 0.5:
                for k in range(L):
                    xx = min(N - 1, x + k)
                    gg = self.ground_at(xx, z)
                    self.ch[xx, gg:min(N, gg + h), z] = mat
            else:
                for k in range(L):
                    zz = min(N - 1, z + k)
                    gg = self.ground_at(x, zz)
                    self.ch[x, gg:min(N, gg + h), zz] = mat
        elif kind == 2:   # an arch over a path
            span = int(r.integers(2, 5))
            h = int(r.integers(3, 5))
            depth = int(r.integers(1, 3))
            for dz in range(depth):
                for xx in (x, x + span + 1):
                    gg = self.ground_at(xx, z + dz)
                    self.ch[min(N - 1, xx), gg:min(N, g + h), z + dz] = mat
                self.ch[x:min(N, x + span + 2), min(N - 1, g + h), z + dz] = mat
        elif kind == 3:   # a fence row
            L = int(r.integers(4, 16))
            for k in range(L):
                xx = min(N - 1, x + k)
                self.ch[xx, self.ground_at(xx, z), z] = FENCE
        else:             # a statue: a lump
            s = int(r.integers(2, 4))
            self.ch[x:x + s, g:min(N, g + s + 2), z:z + s] = mat
            self.ch[x + s // 2, min(N - 1, g + s + 2), z + s // 2] = mat

    def tube(self):
        """A built corridor on the surface: glass or brick, open at both ends - a passage."""
        r = self.rng
        L = int(r.integers(6, 16))
        x, z = int(r.integers(4, N - L - 4)), int(r.integers(8, N - 8))
        g = self.ground_at(x, z)
        mat = int(r.choice([BUILT, BUILT, STONE]))
        w = int(r.integers(1, 3))
        for k in range(L):
            xx = x + k
            self.put((xx, slice(g - 1, g + 4), slice(z - 1, z + w + 1)), mat, STRUCT)
            self.put((xx, slice(g, g + 2 + (w > 1)), slice(z, z + w)), AIR, PASSAGE)

    # --- final
    def labels(self) -> np.ndarray:
        sky = sky_of(self.ch)
        lab = weak_labels(self.ch, sky)
        ex = self.ex
        space = SPACE_CH[self.ch]
        known_space = space & (ex != UNK) & (ex != STRUCT)
        lab[known_space] = ex[known_space]
        known_solid = ~space & (ex == STRUCT)
        lab[known_solid] = STRUCT
        # what sees the sky is outside, unless it is a shaft or a roofless room's... no: a roofless room is outside
        outside = space & sky & (lab != SHAFT)
        lab[outside] = OUTSIDE
        return lab


KINDS = ["mixed", "house", "mine", "cave", "well", "houses_unusual", "tunnels_bare", "decor"]


def scene(rng: np.random.Generator, kind: str = "mixed") -> Scene:
    sc = Scene(rng)
    if kind == "mixed":
        kind = str(rng.choice(["house", "house", "mine", "mine", "cave", "well", "surface"]))
    under = kind in ("mine", "cave", "tunnels_bare") and rng.random() < 0.6
    if under:
        sc.underground()
    else:
        sc.terrain(int(rng.integers(14, 30)))
    if kind == "house":
        for _ in range(int(rng.integers(1, 3))):
            sc.house(underground=rng.random() < 0.15)
        if rng.random() < 0.3:
            sc.mine()
    elif kind == "houses_unusual":
        mat = int(rng.choice([WOOD, OTHER, ORE]))
        for _ in range(int(rng.integers(1, 3))):
            sc.house(material=mat)
    elif kind == "mine":
        for _ in range(int(rng.integers(1, 3))):
            sc.mine()
        if rng.random() < 0.4:
            sc.cave(rng.random() < 0.5)
    elif kind == "tunnels_bare":
        sc.mine(torches=False, supports=False)
    elif kind == "cave":
        sc.cave(True)
        if rng.random() < 0.5:
            sc.cave(rng.random() < 0.5)
        if rng.random() < 0.3:
            sc.mine()
    elif kind == "well":
        sc.well()
        if rng.random() < 0.5:
            sc.mine()
    if not under and kind != "tunnels_bare":
        for _ in range(int(rng.integers(0, 5))):
            sc.tree()
        for _ in range(int(rng.integers(0, 4) if kind != "decor" else rng.integers(1, 4))):
            sc.heap()
        if kind == "decor" or rng.random() < 0.3:
            for _ in range(int(rng.integers(1, 4))):
                sc.decor()
        if kind != "decor" and rng.random() < 0.1:
            sc.tube()
    return sc


def sample(args):
    seed, kind = args
    for attempt in range(5):
        rng = np.random.default_rng([seed, attempt])
        try:
            sc = scene(rng, kind)
            break
        except ValueError:   # a feature that did not fit the scene: draw it again
            continue
    lab = sc.labels()
    inner = (slice(M, M + S),) * 3
    vis = random_view(sc.ch, rng, inner)
    vis = np.ones((S, S, S), bool) if vis is None else vis[inner]
    return sc.ch[inner].copy(), lab[inner].copy(), vis


def sample_house_scene(rng):
    sc = scene(rng, "house")
    lab = sc.labels()
    inner = (slice(M, M + S),) * 3
    return sc.ch[inner].copy(), lab[inner].copy()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=1000)
    ap.add_argument("--out", required=True)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--kind", default="mixed", choices=KINDS)
    ap.add_argument("--procs", type=int, default=7)
    a = ap.parse_args()
    t = time.time()
    jobs = [(a.seed * 1_000_000 + i, a.kind) for i in range(a.n)]
    with Pool(a.procs) as p:
        res = p.map(sample, jobs, chunksize=8)
    ch, lab, vis = (np.stack(x) for x in zip(*res))
    out = Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(out, ch=ch, lab=lab, vis=np.packbits(vis, axis=-1))
    dist = np.bincount(lab.ravel(), minlength=8)[:8] / lab.size
    print(f"{out}: {len(ch)} scenes in {time.time() - t:.0f}s, labels " + " ".join(f"{x:.3f}" for x in dist))


if __name__ == "__main__":
    main()
