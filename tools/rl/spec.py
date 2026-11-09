"""The environment's spaces in Python, read from rl/actions.json, rl/obs.json, rl/reward.json (rl/env's files,
generated from RlSpec.java: docs/rl/CONTRACT.md): the actions, the observation's scaling of the raw facts, the mask
of the actions from the raw facts (a port of RlSpec.allowed), the outcome groups, the scenarios.

The simulator (tools/rl/sim_env.py) builds its raw facts under the game's names and runs them through vector() and
mask() here, so its observations and masks are the real environment's in form. tools/tests/test_rl_policy.py writes
build/rl/obs-mask-cases.json and the Java test (PolicyTest) checks RlSpec.vector/mask give the same on them.

AIGUIDE_RL_SPEC=<dir> reads the three files from another directory (a draft of the spaces).
"""
from __future__ import annotations

import json
import math
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RL = Path(os.environ.get("AIGUIDE_RL_SPEC") or ROOT / "rl")

_actions = json.loads((RL / "actions.json").read_text(encoding="utf-8"))
_obs = json.loads((RL / "obs.json").read_text(encoding="utf-8"))
_reward = json.loads((RL / "reward.json").read_text(encoding="utf-8"))

ACTIONS = [a["id"] for a in _actions["actions"]]
ACTION_SPECS = _actions["actions"]
ACTIONS_HASH = _actions.get("hash", "")
VERSION = int(_actions.get("version", 1))   # the spaces' version: policy.bin's header carries it
A = len(ACTIONS)
IDX = {a: i for i, a in enumerate(ACTIONS)}
FEATURES = [(f["name"], f["kind"], float(f["scale"])) for f in _obs["features"]]
OBS_HASH = _obs.get("hash", "")
NAMES = [f[0] for f in FEATURES]
D = len(FEATURES)
WEIGHTS: dict[str, float] = {k: float(v) for k, v in _reward["weights"].items()}
AVAILABLE = list(_reward.get("scenarios", []))
SCENARIOS = ["wood", "pickaxe", "sword", "iron", "food", "fortify", "waves:easy", "waves:normal", "siege"]
BAG = [n[4:] for n in NAMES if n.startswith("bag_") and n not in ("bag_total",)]
OUTCOMES = [n[5:] for n in NAMES if n.startswith("last_") and not n.startswith("last_action_") and n != "last_seconds"]
MAX_AGENTS = sum(1 for n in NAMES if n.startswith("agent_"))
LOW = 8.0

# RlSpec.CRAFTS's items with what one craft takes (the game's recipes), what it gives, whether it fits 2x2
RECIPES: dict[str, tuple[dict[str, int], int, bool]] = {
    "planks": ({"log": 1}, 4, True),
    "stick": ({"planks": 2}, 4, True),
    "crafting_table": ({"planks": 4}, 1, True),
    "wooden_pickaxe": ({"planks": 3, "stick": 2}, 1, False),
    "stone_pickaxe": ({"cobblestone": 3, "stick": 2}, 1, False),
    "iron_pickaxe": ({"iron_ingot": 3, "stick": 2}, 1, False),
    "wooden_sword": ({"planks": 2, "stick": 1}, 1, False),
    "stone_sword": ({"cobblestone": 2, "stick": 1}, 1, False),
    "iron_sword": ({"iron_ingot": 2, "stick": 1}, 1, False),
    "furnace": ({"cobblestone": 8}, 1, False),
    "torch": ({"coal": 1, "stick": 1}, 4, True),
    "chest": ({"planks": 8}, 1, False),
}


def tier(item: str) -> int:
    """EpochScore.tier"""
    s = item.replace("minecraft:", "")
    if s.startswith("diamond_"):
        return 4
    if s.startswith("iron_") or s.startswith("chainmail_"):
        return 3
    if s.startswith("stone_") or s.startswith("golden_"):
        return 2
    if s.startswith("wooden_") or s.startswith("leather_"):
        return 1
    if s == "bow":
        return 2
    return 0


def norm(kind: str, s: float, x: float) -> float:
    if kind == "bool":
        return 1.0 if x > 0.5 else 0.0
    if kind == "log":
        return math.log1p(max(x, 0.0)) / math.log1p(s)
    return min(2.0, max(-1.0, x / s))


def vector(raw: dict) -> list[float]:
    """RlSpec.vector (the server rounds to 1e-5 when it sends)"""
    return [norm(k, s, float(raw.get(n, 0.0))) for n, k, s in FEATURES]


def g(r: dict, k: str) -> float:
    return float(r.get(k, 0.0))


def allowed(i: int, r: dict) -> bool:
    """RlSpec.allowed, line by line"""
    aid = ACTIONS[i]
    table = g(r, "table_known") > 0.5 and g(r, "table_dist") <= 24 or g(r, "bag_crafting_table") > 0
    furnace = g(r, "furnace_known") > 0.5 and g(r, "furnace_dist") <= 24 or g(r, "bag_furnace") > 0
    base = g(r, "base_known") > 0.5
    if aid in ("noop", "look_around", "wait:5"):
        return True
    if aid == "go_to:frontier":
        return g(r, "frontier") > 0
    if aid == "chop_tree:near":
        return g(r, "trees_free") > 0
    if aid == "mine:stone":
        return g(r, "pick_tier") >= 1
    if aid == "mine:coal_ore":
        return g(r, "pick_tier") >= 1 and g(r, "coal_minable") > 0
    if aid == "mine:iron_ore":
        return g(r, "pick_tier") >= 2 and g(r, "iron_minable") > 0
    if aid.startswith("craft:"):
        it = aid[6:]
        if g(r, "craftable_" + it) <= 0:
            return False
        if g(r, "small_" + it) <= 0 and not table:
            return False
        if it == "planks":
            return g(r, "bag_planks") < 64
        if it == "stick":
            return g(r, "bag_stick") < 32
        if it == "torch":
            return g(r, "bag_torch") < 32
        if it == "crafting_table":
            return g(r, "bag_crafting_table") == 0
        if it == "furnace":
            return g(r, "bag_furnace") == 0
        if it == "chest":
            return g(r, "bag_chest") == 0 and g(r, "chest_known") < 0.5
        if it.endswith("_pickaxe"):
            return g(r, "pick_tier") < tier(it)
        if it.endswith("_sword"):
            return g(r, "weapon_tier") < tier(it)
        return True
    if aid == "smelt:iron_ingot":
        return g(r, "bag_iron_ore") > 0 and g(r, "fuel") > 0 and furnace
    if aid == "smelt:food":
        return g(r, "bag_food_raw") > 0 and g(r, "fuel") > 0 and furnace
    if aid == "place:crafting_table":
        return g(r, "bag_crafting_table") > 0 and not (g(r, "table_known") > 0.5 and g(r, "table_dist") <= 6)
    if aid == "place:furnace":
        return g(r, "bag_furnace") > 0 and not (g(r, "furnace_known") > 0.5 and g(r, "furnace_dist") <= 6)
    if aid == "place:chest":
        return g(r, "bag_chest") > 0 and g(r, "chest_known") < 0.5
    if aid == "build_wall:ring":
        return base and g(r, "wall_blocks") > 0 and not (g(r, "ring_planned") > 0.5 and g(r, "wall_frac") >= 1)
    if aid == "repair_wall:nearest":
        return g(r, "ring_planned") > 0.5 and g(r, "holes") > 0 and g(r, "wall_blocks") > 0
    if aid == "dig_moat:ring":
        return g(r, "ring_planned") > 0.5 and g(r, "moat_frac") < 1
    if aid == "light_area:base":
        return g(r, "bag_torch") > 0 and g(r, "light_spots") > 0
    if aid == "equip:best":
        return g(r, "can_equip") > 0
    if aid == "take_post:auto":
        return g(r, "ring_planned") > 0.5 and (g(r, "posts_free") > 0 if g(r, "has_post") < 0.5 else g(r, "post_dist") > 2)
    if aid == "hold_line:post":
        return g(r, "waves_active") > 0.5 and g(r, "health") >= (g(r, "low_health") if "low_health" in r else LOW)
    if aid == "retreat:home":
        return base and g(r, "base_dist") > 3
    if aid == "eat:best":
        return g(r, "bag_food_raw") + g(r, "bag_food_cooked") > 0 and g(r, "health") < g(r, "max_health")
    if aid == "hunt:near":
        return g(r, "animals_seen") > 0
    if aid == "put:chest_all":
        return g(r, "chest_known") > 0.5 and g(r, "bag_stackables") > 0
    if aid == "take:chest_food":
        return g(r, "chest_known") > 0.5 and g(r, "chest_food") > 0
    if aid == "go_to:base":
        return base and g(r, "base_dist") > 4
    return False


def mask(raw: dict) -> list[bool]:
    return [allowed(i, raw) for i in range(A)]


def outcome(reason: str | None) -> str:
    """RlSpec.outcome"""
    if not reason:
        return "none"
    r = reason
    if r == "OK":
        return "ok"
    if r in ("NO_PATH", "NO_SPOT", "OUT_OF_REACH", "NOT_VISIBLE", "BLOCKED", "OCCUPIED", "BUSY"):
        return "fail_path"
    if r in ("NO_TOOL", "NO_ITEMS", "MISSING", "NO_TABLE", "NO_FURNACE", "NO_FUEL", "NO_FOOD", "NO_TORCHES", "NO_WEAPON", "NO_ARMOR",
             "CHEST_FULL", "NOT_IN_CHEST"):
        return "fail_items"
    if r in ("NOT_SEEN", "NO_TREE_SEEN", "NO_TREE_THERE", "GONE", "NO_CHEST", "NO_PERIMETER", "NO_BASE", "NO_HOME", "NO_HOLES",
             "UNKNOWN_TARGET", "POST_TAKEN", "ALL_TAKEN", "DONE_ALREADY", "NO_ANIMAL", "NO_FRONTIER"):
        return "fail_target"
    if r in ("LOW_HEALTH", "DIED", "INTERRUPTED"):
        return "fail_danger"
    return "fail_other"


# RlSpec.scenario: start kit, chest, goal, its item, per agent, seconds, world time, start health, waves
SCEN = {
    "wood": dict(goal="items", item="log", per=10, seconds=300, kit={}, health=20, time=1000, waves=None),
    "pickaxe": dict(goal="items", item="stone_pickaxe", per=1, seconds=600, kit={}, health=20, time=1000, waves=None),
    "sword": dict(goal="items", item="stone_sword", per=1, seconds=600, kit={}, health=20, time=1000, waves=None),
    "iron": dict(goal="items", item="iron_pickaxe", per=1, seconds=900, kit={"stone_pickaxe": 1, "planks": 16, "stick": 4},
                 health=20, time=1000, waves=None),
    "food": dict(goal="food", item="", per=2, seconds=300, kit={"wooden_sword": 1}, health=10, time=1000, waves=None),
    "fortify": dict(goal="fortify", item="", per=1, seconds=600, kit={"cobblestone": 140, "stone_pickaxe": 1}, health=20, time=1000,
                    waves=None),
    "waves:easy": dict(goal="none", item="", per=1, seconds=900, kit={"stone_sword": 1, "cobblestone": 64, "food_cooked": 6, "torch": 8},
                       chest=16, health=20, time=14000, waves="easy"),
    "waves:normal": dict(goal="none", item="", per=1, seconds=900, kit={"stone_sword": 1, "cobblestone": 64, "food_cooked": 6, "torch": 8},
                         chest=16, health=20, time=14000, waves="normal"),
}
# a waves run has no goal of its own: for the curriculum's threshold (policy side) it counts as passed with this many cleared
WAVES_PASS = {"waves:easy": 5, "waves:normal": 5}
