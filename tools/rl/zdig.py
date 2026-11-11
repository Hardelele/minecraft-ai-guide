"""The digging zombies' rules for the simulators (docs/rl/ZOMBIES-DIG.md): a port of client/.../ZombieDig.java, read from
rl/zombie-dig.json: the tiers (a zombie's tool and its share by the wave), the time a block takes by the tool, how far an
agent is heard, and the digger's way down to an agent under the ground (the same planner as Java's, over a model of the
ground: soil, then stone), whose seconds go into rl/sim-calibration.json "dig" (this file's --write). Tested against the
Java side by tools/tests/test_rl_zdig.py (ZombieDigTest writes build/rl/zdig-java.json).

    python tools/rl/zdig.py            # the tables
    python tools/rl/zdig.py --write    # the "dig" section of rl/sim-calibration.json
"""
from __future__ import annotations

import json
import math
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CFG = json.loads((ROOT / "rl" / "zombie-dig.json").read_text(encoding="utf-8"))
P = CFG["params"]
TOOLS = CFG["tools"]
TIERS = CFG["tiers"]
PASS, LIQUID, NEVER = 0.0, -3.0, -1.0
BOSS_EVERY = 5

DEPTH_BLOCKS = (0, 24, 52)   # the simulators' depth levels 0, 1, 2 (y_rel 0, -24, -52)
SOIL = 5                     # blocks of soil under the surface, then stone
START = 10                   # the digger's blocks from above the agent when it begins
ON = os.environ.get("AIGUIDE_ZDIG", "1") != "0"


def boss(n: int) -> bool:
    return n > 0 and n % BOSS_EVERY == 0


def share(t: dict, wave: int) -> float:
    if wave < t["from"]:
        return 0.0
    f = min(t["max"], t["base"] + t["step"] * (wave - t["from"]))
    return f * P["boss_mult"] if boss(wave) else f


def tier(wave: int, k: int, total: int) -> int:
    """the tier of the k-th zombie (from 0) of a wave of total zombies, -1 plain: ZombieDig.tier"""
    s = [share(t, wave) for t in TIERS]
    tot = sum(s)
    if tot > P["tiers_max"]:
        s = [x * P["tiers_max"] / tot for x in s]
    u = ((k + 1) * 0.6180339887498949) % 1.0
    acc = 0.0
    for i in range(len(s) - 1, -1, -1):
        acc += s[i]
        if u < acc:
            return i
    return 1 if k == 0 and total >= 3 else -1


def tier_counts(wave: int, total: int) -> list[int]:
    c = [0] * len(TIERS)
    for k in range(int(total)):
        i = tier(wave, k, int(total))
        if i >= 0:
            c[i] += 1
    return c


def hear(t: float) -> float:
    return min(P["hear_max"], P["detect"] + min(P["hear_max"] - P["detect"], P["hear_grow"] * max(0.0, t - P["hear_grace"])))


def hear_time(d: float) -> float:
    """seconds underground in a wave after which an agent d blocks down (and ~8 off) is heard"""
    r = math.hypot(d, 8.0)
    if r <= P["detect"]:
        return 0.0
    if r > P["hear_max"]:
        return math.inf
    return P["hear_grace"] + (r - P["detect"]) / P["hear_grow"]


def ticks(tool: str, mat: str, hardness: float, need: bool, level: int) -> int:
    """ZombieDig.ticks: -1 never"""
    if hardness < 0:
        return -1
    tl = TOOLS[tool]
    kind = tl["kind"]
    right = kind == "shovel" and mat == "soil" or kind == "axe" and mat == "wood" or kind == "pickaxe" and mat == "rock"
    if need and not (right and tl["level"] >= level):
        return -1
    t = math.ceil(hardness * 30 / (tl["speed"] if right else 1) * P["slow"])
    if t > P["max_ticks"]:
        return -1
    return max(P["min_ticks"], int(t))


# ---- the way (ZombieDig.plan) ------------------------------------------------------------------------------------------

class Ground:
    """air from y = 64, SOIL blocks of soil under it, then stone; the digger's tool decides the ticks of a cell"""

    def __init__(self, tool: str, soil: int = SOIL, obsidian_y: int | None = None):
        self.tool, self.soil, self.obs_y = tool, soil, obsidian_y
        self.broken: set = set()

    def cell(self, x: int, y: int, z: int) -> float:
        if (x, y, z) in self.broken or y >= 64:
            return PASS
        if y < 1:
            return NEVER
        if self.obs_y is not None and y == self.obs_y:
            t = ticks(self.tool, "rock", 50.0, True, 3)
        elif y >= 64 - self.soil:
            t = ticks(self.tool, "soil", 0.5, False, 0)
        else:
            t = ticks(self.tool, "rock", 1.5, True, 0)
        return float(t) if t >= 0 else NEVER


def step(g: Ground, fx, fy, fz, ax, sy, az):
    nx, nz, ny = fx + ax, fz + az, fy + sy
    if sy < 0:
        cells = [(nx, fy, nz), (nx, fy + 1, nz), (nx, fy - 1, nz)]
    elif sy > 0:
        cells = [(fx, fy + 2, fz), (nx, fy + 1, nz), (nx, fy + 2, nz)]
    else:
        cells = [(nx, fy, nz), (nx, fy + 1, nz)]
    for c in cells:
        if g.cell(*c) < 0:
            return None
    if g.cell(nx, ny - 1, nz) == LIQUID:
        return None
    return (nx, ny, nz), cells


def plan(g: Ground, fx, fy, fz, tx, ty, tz):
    dx, dz, dy = tx - (fx + 0.5), tz - (fz + 0.5), ty - fy
    dh = math.hypot(dx, dz)
    if dh < 1.0 and abs(dy) < 2:
        return None
    sy = 0
    if dy <= -2 and -dy >= 0.5 * dh:
        sy = -1
    elif dy >= 2 and dy >= 0.5 * dh:
        sy = 1
    sx = 1 if dx >= 0 else -1
    sz = 1 if dz >= 0 else -1
    axes = [(sx, 0), (0, sz), (-sx, 0)] if abs(dx) >= abs(dz) else [(0, sz), (sx, 0), (0, -sz)]
    for s in ([sy, 0] if sy else [0]):
        for ax, az in axes:
            r = step(g, fx, fy, fz, ax, s, az)
            if r is not None:
                return r
    return None


def dig_seconds(tool: str, d: int, start: int = START, soil: int = SOIL, obsidian_y: int | None = None) -> float:
    """seconds from the surface to an agent d blocks under it (the breaking's ticks, 0.25 s a block of walking); inf when it cannot"""
    g = Ground(tool, soil, obsidian_y)
    fx, fy, fz, ticks_, moves = start, 64, 0, 0.0, 0
    tx, ty, tz = 0.5, 64.0 - d, 0.5
    for _ in range(600):
        r = plan(g, fx, fy, fz, tx, ty, tz)
        if r is None:
            return ticks_ / 20.0 + 0.25 * moves if math.hypot(fx - tx, fz - tz) < 1.5 and abs(fy - ty) < 2 else math.inf
        (nx, ny, nz), cells = r
        for c in cells:
            t = g.cell(*c)
            if t != PASS:
                ticks_ += t
                g.broken.add(c)
        fx, fy, fz = nx, ny, nz
        moves += 1
    return math.inf


def reach_table() -> dict:
    """seconds to an agent at the simulators' depth levels, by tier (None: never)"""
    out = {}
    for t in TIERS:
        row = []
        for d in DEPTH_BLOCKS[1:]:
            s = dig_seconds(t["tool"], d)
            row.append(None if math.isinf(s) else round(s, 1))
        out[t["name"]] = row
    return out


def main():
    print("seconds to an agent under the ground (soil %d, then stone; a zombie %d blocks off), by tool:" % (SOIL, START))
    tools = ["iron_shovel", "wooden_pickaxe", "stone_pickaxe", "iron_pickaxe", "diamond_pickaxe"]
    print("depth " + " ".join("%14s" % t for t in tools))
    for d in (12, 24, 48):
        row = []
        for t in tools:
            s = dig_seconds(t, d)
            row.append("%14s" % ("-" if math.isinf(s) else "%.1f" % s))
        print("%5d " % d + " ".join(row))
    print("with an obsidian floor at y=50 (14 blocks down), to 24 deep: iron %s, diamond %s" % (
        dig_seconds("iron_pickaxe", 24, obsidian_y=50), dig_seconds("diamond_pickaxe", 24, obsidian_y=50)))
    print("simulator depths", DEPTH_BLOCKS[1:], reach_table())
    print("heard after (s underground in the wave):", {d: round(hear_time(d), 1) for d in (12, 24, 48, 52)})
    if "--write" in sys.argv:
        p = ROOT / "rl" / "sim-calibration.json"
        cal = json.loads(p.read_text(encoding="utf-8"))
        cal["dig"] = {"note": "the digging zombies in the simulators (docs/rl/ZOMBIES-DIG.md, tools/rl/zdig.py --write): depth_blocks are the levels 1 and 2 under the surface; reach_s the seconds a digger of a tier needs to get from the surface to an agent there (the planner of ZombieDig.java over soil %d and stone, from %d blocks off; null: never), by the engine's rules of breaking; the hearing, the tiers and the numbers are rl/zombie-dig.json's" % (SOIL, START),
                      "depth_blocks": list(DEPTH_BLOCKS[1:]), "reach_s": reach_table(),
                      "hear_s": [round(hear_time(d), 1) if not math.isinf(hear_time(d)) else None for d in DEPTH_BLOCKS[1:]]}
        p.write_text(json.dumps(cal, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        print("written to", p)


if __name__ == "__main__":
    main()
