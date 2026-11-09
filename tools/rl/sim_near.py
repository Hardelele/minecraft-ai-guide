"""Contract 3.1 in the simulator: the cells round an agent (RlNear.java in Python) and the memory of failures
(RlScore.Fails, RlSpec.failGroup).

A Near is a small grid round the agent: it stands with its feet at (0, y, 0), facing +z (front (0,0,1), left
(1,0,0) as RlNear.off gives for a facing of (0, 1)). The grid holds only what changed from its ground: "surface"
(solid under y 0, air over it), "tunnel" (stone all round but its own two cells and a corridor ahead and behind).
Cells carry what a dig gives (dirt, cobblestone, log). The simulator keeps a Near while the agent stays where it is
(the 3.1 primitives, crafts, a look round) and makes a new one when a skill takes it elsewhere.
"""
from __future__ import annotations

AIR, SOLID, WATER, LAVA, BEDROCK, HARD, LEAVES = 0, 1, 2, 3, 4, 5, 6
DIRS = ("front", "back", "left", "right", "above", "below")
DIG = ("below", "front", "front_down", "left", "right", "back", "above")
PUT = ("left", "right", "back", "above")
SIDES = ("front", "back", "left", "right")
FX, FZ = 0, 1   # the facing


def solid(k: int) -> bool:
    return k in (SOLID, HARD, BEDROCK, LEAVES)


def off(d: str) -> tuple[int, int, int]:
    """RlNear.off for the facing (FX, FZ)"""
    fx, fz = FX, FZ
    return {"front": (fx, 0, fz), "back": (-fx, 0, -fz), "left": (fz, 0, -fx), "right": (-fz, 0, fx), "above": (0, 2, 0),
            "below": (0, -1, 0), "front_down": (fx, -1, fz)}[d]


class Near:
    def __init__(self, ground: str = "surface", pick: int = 0):
        self.ground = ground
        self.pick = pick                       # the bag's best pickaxe: stone is HARD without one
        self.cells: dict[tuple, int] = {}      # changed cells: kind
        self.drop: dict[tuple, str] = {}       # what a dig of a cell gives (a cell not here: its ground's)
        self.mobs: set[tuple] = set()
        self.y = 0                             # the feet's y (0: on the ground; -2: in a pit two deep; 2: on a pillar)
        self.placed = 0                        # blocks put by the agent here (pillar too)

    # ---- the cells ------------------------------------------------------------------------------------
    def base(self, c: tuple) -> int:
        x, y, z = c
        if self.ground == "tunnel":
            if x == 0 and y in (0, 1) and -4 <= z <= 4:
                return AIR
            return SOLID if self.pick >= 1 else HARD
        return SOLID if y < 0 else AIR

    def at(self, c: tuple) -> int:
        k = self.cells.get(c)
        if k is None:
            k = self.base(c)
        if k == SOLID and self.ground == "tunnel" and self.pick < 1 and c not in self.drop:
            return HARD
        return k

    def set(self, c: tuple, k: int, drop: str | None = None):
        self.cells[c] = k
        if drop:
            self.drop[c] = drop
        elif c in self.drop and k == AIR:
            del self.drop[c]

    def gives(self, c: tuple) -> str:
        if c in self.drop:
            return self.drop[c]
        return "cobblestone" if self.ground == "tunnel" else "dirt"

    def cell(self, d: str) -> tuple:
        o = off(d)
        return (o[0], self.y + o[1], o[2])

    def dig_cells(self, d: str) -> list[tuple]:
        o = off(d)
        if o[1] == 0:
            return [(o[0], self.y + 1, o[2]), (o[0], self.y, o[2])]
        return [(o[0], self.y + o[1], o[2])]

    def support(self, c: tuple) -> bool:
        for d in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
            n = (c[0] + d[0], c[1] + d[1], c[2] + d[2])
            if n[0] == 0 and n[2] == 0 and n[1] in (self.y, self.y + 1):
                continue
            k = self.at(n)
            if solid(k) and k != LEAVES:
                return True
        return False

    # ---- RlNear.facts ---------------------------------------------------------------------------------
    def facts(self, r: dict, night_or_wave: bool):
        y = self.y
        for d in DIRS:
            o = off(d)
            c = (o[0], y + o[1], o[2])
            k = self.at(c)
            r["nb_%s_solid" % d] = 1 if solid(k) else 0
            r["nb_%s_air" % d] = 1 if k == AIR else 0
            r["nb_%s_liquid" % d] = 1 if k in (WATER, LAVA) else 0
            r["nb_%s_mob" % d] = 1 if c in self.mobs or o[1] == 0 and (c[0], c[1] + 1, c[2]) in self.mobs else 0
        for d in DIG:
            r["nb_%s_dig" % d] = 1 if self.can_dig(d) else 0
        for d in PUT:
            c = self.cell(d)
            r["nb_%s_free" % d] = 1 if self.at(c) in (AIR, WATER) and c not in self.mobs and self.support(c) else 0
        r["pillar_ok"] = 1 if self.at((0, y + 2, 0)) == AIR and solid(self.at((0, y - 1, 0))) else 0
        walls, shut = self.walls()
        r["walls_around"] = walls
        r["enclosed"] = 1 if self.enclosed() else 0
        r["shelter"] = 1 if self.enclosed() and night_or_wave else 0

    def walls(self) -> tuple[int, int]:
        walls = shut = 0
        for d in SIDES:
            o = off(d)
            feet, head = solid(self.at((o[0], self.y, o[2]))), solid(self.at((o[0], self.y + 1, o[2])))
            walls += feet
            shut += feet and head
        return walls, shut

    def enclosed(self) -> bool:
        return self.walls()[1] == 4 and solid(self.at((0, self.y + 2, 0)))

    def can_dig(self, d: str) -> bool:
        anyc, ok = False, True
        for c in self.dig_cells(d):
            k = self.at(c)
            if k in (SOLID, LEAVES):
                anyc = True
            if k in (HARD, BEDROCK):
                ok = False
        if d == "below" and anyc:
            y = self.y
            if self.at((0, y - 2, 0)) == LAVA:
                ok = False
            if self.at((0, y - 2, 0)) == AIR and self.at((0, y - 3, 0)) == AIR and self.at((0, y - 4, 0)) == AIR:
                ok = False
            for sx, sz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                if self.at((sx, y - 1, sz)) == LAVA:
                    ok = False
        return anyc and ok

    # ---- the primitives -------------------------------------------------------------------------------
    def dig(self, d: str) -> list[str] | None:
        """dig@d: what it gave (None: nothing to dig); under itself it falls into the hole"""
        if not self.can_dig(d):
            return None
        got = []
        for c in self.dig_cells(d):
            if self.at(c) in (SOLID, LEAVES):
                got.append(self.gives(c))
                self.set(c, AIR)
        if d == "below":
            self.fall()
        return got

    def fall(self):
        n = 0
        while not solid(self.at((0, self.y - 1, 0))) and self.at((0, self.y - 1, 0)) != LAVA and n < 8:
            self.y -= 1
            n += 1
        return n

    def put(self, d: str) -> bool:
        """place:block@d (left, right, back, above; v3's front and front_down too): a block of the bag into the cell"""
        c = self.cell(d)
        if self.at(c) not in (AIR, WATER) or c in self.mobs:
            return False
        if d in PUT and not self.support(c):
            return False
        self.set(c, SOLID, "dirt")
        self.placed += 1
        return True

    def pillar(self) -> bool:
        if not (self.at((0, self.y + 2, 0)) == AIR and solid(self.at((0, self.y - 1, 0)))):
            return False
        self.set((0, self.y, 0), SOLID, "dirt")
        self.y += 1
        self.placed += 1
        return True

    def pit(self) -> int:
        """how deep its feet are under the ground's level (0 on the ground or over it)"""
        return max(0, -self.y) if self.ground == "surface" else 0


# ---- the memory of failures (RlScore.Fails) ------------------------------------------------------------------

FAIL_WINDOW = 20
FAIL_GROUPS = ("no_path", "no_spot", "not_visible", "no_items", "no_tool", "occupied", "other")


def fail_group(r: str) -> str:
    """RlSpec.failGroup"""
    if not r or r == "OK":
        return ""
    if r in ("NO_PATH", "OUT_OF_REACH", "NO_WAY_BACK", "TIMEOUT"):
        return "no_path"
    if r in ("NO_SPOT", "BLOCKED", "NOT_AT_HORIZON", "AT_BOTTOM", "ON_SURFACE"):
        return "no_spot"
    if r in ("NOT_VISIBLE", "NOT_SEEN", "NO_TREE_SEEN", "NO_TREE_THERE", "GONE", "NO_ANIMAL", "NO_ENEMY", "NOT_RIPE"):
        return "not_visible"
    if r in ("NO_ITEMS", "MISSING", "NO_FOOD", "NO_SEEDS", "NO_BAIT", "NO_FENCES", "NO_DOOR", "NO_SAPLING", "NO_SHELF", "NO_ARROWS",
             "NO_TORCHES", "NO_FUEL", "NO_XP", "NOTHING_TO_ENCHANT"):
        return "no_items"
    if r in ("NO_TOOL", "NO_HOE", "NO_TABLE", "NO_FURNACE", "NO_WEAPON", "NO_BOW", "NO_ENCHANTER"):
        return "no_tool"
    if r in ("OCCUPIED", "BUSY", "POST_TAKEN", "ALL_TAKEN"):
        return "occupied"
    return "other"


class Fails:
    """by action: failures in a row since its last success, failures in the last FAIL_WINDOW decisions; the last
    failure's group. step() gives (retry_fail's k-1 for the k-th failure in a row, 1 for a success of another action
    right after a failure)"""

    def __init__(self):
        self.run: dict[int, int] = {}
        self.window: list[tuple[int, int]] = []
        self.last_failed = -1
        self.group = ""

    def step(self, action: int, ok: bool, reason: str) -> tuple[float, float]:
        self.window.append((action, 0 if ok else 1))
        if len(self.window) > FAIL_WINDOW:
            self.window.pop(0)
        if not ok:
            k = self.run.get(action, 0) + 1
            self.run[action] = k
            self.last_failed = action
            self.group = fail_group(reason)
            return (k - 1 if k >= 2 else 0, 0)
        self.run.pop(action, None)
        other = self.last_failed >= 0 and self.last_failed != action
        self.last_failed = -1
        return (0, 1 if other else 0)

    def recent(self, action: int) -> float:
        return sum(1 for a, f in self.window if a == action and f) / FAIL_WINDOW

    def facts(self, r: dict, names: list[str]):
        for i, k in self.run.items():
            if k > 0:
                r["fail_run_" + names[i]] = k
        seen = {a for a, f in self.window if f}
        for i in seen:
            r["fail_recent_" + names[i]] = self.recent(i)
        if self.group and self.last_failed >= 0:
            r["lastfail_" + self.group] = 1
