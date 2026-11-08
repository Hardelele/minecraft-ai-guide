"""Read 1.7.10 Anvil worlds (copies) into dense block arrays. A small NBT reader of our own: only what we need.

Region volume: blocks[x, y, z] uint16 (x, z local to the region, 0..511; y 0..255), meta the same shape,
loaded[cx, cz] bool, and the tile entity positions.
"""
from __future__ import annotations

import gzip
import re
import struct
import zlib
from pathlib import Path

import numpy as np

SECTOR = 4096


class _Reader:
    __slots__ = ("b", "p")

    def __init__(self, b: bytes):
        self.b = b
        self.p = 0

    def u(self, fmt: str, n: int):
        v = struct.unpack_from(fmt, self.b, self.p)
        self.p += n
        return v[0]

    def string(self) -> str:
        n = self.u(">H", 2)
        s = self.b[self.p:self.p + n].decode("utf-8", "replace")
        self.p += n
        return s

    def payload(self, t: int, keep=None):
        if t == 1:
            return self.u(">b", 1)
        if t == 2:
            return self.u(">h", 2)
        if t == 3:
            return self.u(">i", 4)
        if t == 4:
            return self.u(">q", 8)
        if t == 5:
            return self.u(">f", 4)
        if t == 6:
            return self.u(">d", 8)
        if t == 7:
            n = self.u(">i", 4)
            v = np.frombuffer(self.b, np.uint8, n, self.p)
            self.p += n
            return v
        if t == 8:
            return self.string()
        if t == 9:
            et = self.u(">b", 1)
            n = self.u(">i", 4)
            return [self.payload(et, keep) for _ in range(n)]
        if t == 10:
            out = {}
            while True:
                tt = self.u(">b", 1)
                if tt == 0:
                    return out
                name = self.string()
                if keep is not None and tt in (9, 10) and name in ("Entities", "TileTicks"):
                    self.skip(tt)
                    continue
                out[name] = self.payload(tt, keep)
        if t == 11:
            n = self.u(">i", 4)
            v = np.frombuffer(self.b, ">i4", n, self.p)
            self.p += 4 * n
            return v
        raise ValueError(f"nbt tag {t}")

    def skip(self, t: int):
        self.payload(t)


def parse_nbt(raw: bytes, keep=None) -> dict:
    r = _Reader(raw)
    t = r.u(">b", 1)
    r.string()
    return r.payload(t, keep)


def _nibbles(a: np.ndarray) -> np.ndarray:
    out = np.empty(a.size * 2, np.uint8)
    out[0::2] = a & 15
    out[1::2] = a >> 4
    return out


def registry(level_dat: Path) -> dict[int, str]:
    """Block id -> name from the FML registry in level.dat; {} for a world without one."""
    try:
        root = parse_nbt(gzip.decompress(level_dat.read_bytes()))
    except Exception:
        return {}
    fml = root.get("FML", {})
    out = {}
    for e in fml.get("ItemData", []):
        k, v = e.get("K", ""), e.get("V", -1)
        if k.startswith("\x01"):
            out[int(v)] = k[1:]
    return out


class RegionVolume:
    def __init__(self, path: Path):
        m = re.match(r"r\.(-?\d+)\.(-?\d+)\.mca", path.name)
        self.rx, self.rz = int(m.group(1)), int(m.group(2))
        self.blocks = np.zeros((512, 256, 512), np.uint16)
        self.meta = np.zeros((512, 256, 512), np.uint8)
        self.loaded = np.zeros((32, 32), bool)
        self.tiles: list[tuple[int, int, int]] = []   # region-local x, y, z
        self.bad = 0
        data = path.read_bytes()
        if len(data) < 2 * SECTOR:
            return
        for i in range(1024):
            off = int.from_bytes(data[i * 4:i * 4 + 3], "big")
            if off == 0 or data[i * 4 + 3] == 0:
                continue
            try:
                self._chunk(data, off * SECTOR)
            except Exception:
                self.bad += 1

    def _chunk(self, data: bytes, start: int):
        (length,) = struct.unpack_from(">I", data, start)
        kind = data[start + 4]
        payload = data[start + 5:start + 4 + length]
        raw = zlib.decompress(payload) if kind == 2 else gzip.decompress(payload)
        level = parse_nbt(raw, keep=True)["Level"]
        cx, cz = level["xPos"] & 31, level["zPos"] & 31
        x0, z0 = cx * 16, cz * 16
        for s in level.get("Sections", []):
            y = s["Y"] & 255
            if y > 15:
                continue
            b = s["Blocks"].astype(np.uint16)
            if "Add" in s:
                b |= _nibbles(s["Add"]).astype(np.uint16) << 8
            d = _nibbles(s["Data"])
            # array index (y<<8)|(z<<4)|x -> [x, y, z]
            self.blocks[x0:x0 + 16, y * 16:y * 16 + 16, z0:z0 + 16] = b.reshape(16, 16, 16).transpose(2, 0, 1)
            self.meta[x0:x0 + 16, y * 16:y * 16 + 16, z0:z0 + 16] = d.reshape(16, 16, 16).transpose(2, 0, 1)
        for t in level.get("TileEntities", []):
            try:
                self.tiles.append((t["x"] & 511, t["y"], t["z"] & 511))
            except Exception:
                pass
        self.loaded[cx, cz] = True


def world_regions(world: Path) -> list[Path]:
    return sorted((world / "region").glob("r.*.mca"))
