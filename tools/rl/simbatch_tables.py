"""The batch simulator's index spaces and tables, read from the reference simulator (tools/rl/sim_env.py) and the
contract's files through it (rl/actions.json, obs.json, reward.json, sim-calibration.json, achievements.json): the
items, the actions by family and parameter, the raw facts' columns, the mask's rules as a postfix program, the
scenarios' parameters, the reward's keys with their weights and factors by scenario.

Everything here is plain numpy; tools/rl/simbatch_kernel.py takes the ints as compile-time constants and the arrays as
frozen globals (its numba cache lives under build/numba/<hash of these inputs>, so a change of a contract file or of
this module compiles afresh).
"""
from __future__ import annotations

import hashlib
import math
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import achievements as ACHM  # noqa: E402
import sim_env as S  # noqa: E402
import sim_near as NEAR  # noqa: E402
import spec  # noqa: E402

ROOT = spec.ROOT
A = spec.A
D = spec.D
MA = spec.MAX_AGENTS
ACTIONS = spec.ACTIONS
assert S.V2 and S.V31, "the batch simulator speaks contract 3.1 (rl/ with pillar:up)"


def source_hash() -> str:
    h = hashlib.sha1()
    for p in sorted(spec.RL.glob("*.json")) + [Path(S.__file__), Path(NEAR.__file__), Path(spec.__file__), Path(__file__),
                                                  Path(ACHM.__file__), Path(__file__).with_name("mask_rules.py")]:
        h.update(p.name.encode())
        h.update(p.read_bytes())
    return h.hexdigest()[:16]


# ---- items ----------------------------------------------------------------------------------------------------
_items: list[str] = []


def _add(k):
    if k not in _items:
        _items.append(k)


for k in spec.BAG:
    _add(k)
for it, (need, _, _) in S.SIM_RECIPES.items():
    _add(it)
    for k in need:
        _add(k)
for k in S.ARMOR:
    _add(k)
for k in ("log", "planks", "stick", "cobblestone", "dirt", "coal", "iron_ore", "iron_ingot", "gold_ore", "gold_ingot", "redstone",
          "diamond", "obsidian", "gravel", "flint", "food_raw", "food_cooked", "bread", "sapling", "wool", "feather", "leather",
          "string", "wheat", "wheat_seeds", "sugar_cane", "bucket", "water_bucket", "lava_bucket", "furnace", "crafting_table", "chest",
          "torch", "bow", "arrow", "fence", "fence_gate", "wooden_door", "enchanting_table", "bookshelf", "book", "paper",
          "flint_and_steel", "wooden_hoe", "stone_hoe"):
    _add(k)
for sc in S.ALL_SCEN.values():
    for k in sc["kit"]:
        _add(k)
NIT_BAG = len(_items)           # items a bag may hold
PSEUDO = ("equipped", "post", "trip")   # made only (Agent.made's other things)
ITEMS = _items + list(PSEUDO)
NIT = len(ITEMS)
IT = {k: i for i, k in enumerate(ITEMS)}

IT_TIER = np.array([spec.tier(k) for k in ITEMS], np.int64)
IT_PICK = np.array([k.endswith("_pickaxe") for k in ITEMS], np.bool_)
IT_SWORD = np.array([k.endswith("_sword") for k in ITEMS], np.bool_)
IT_HOE = np.array([k.endswith("_hoe") for k in ITEMS], np.bool_)
IT_TOOL = np.array([k.endswith(S.TOOLS) for k in ITEMS], np.bool_)
IT_ARMOR = np.array([S.ARMOR.get(k, 0) for k in ITEMS], np.int64)
IS_ARMOR = np.array([k in S.ARMOR for k in ITEMS], np.bool_)
SLOTS = list(S._PIECES)            # helmet, chestplate, leggings, boots
IT_SLOT = np.array([SLOTS.index(k.rsplit("_", 1)[1]) if k in S.ARMOR else -1 for k in ITEMS], np.int64)
FOODS = [IT[f] for f in S.FOODS]   # food_raw, food_cooked, bread (FOOD_PTS' order)
FOOD_PTS = np.array([S.FOOD_PTS[f] for f in S.FOODS], np.float64)
FOOD_SAT = np.array([S.SAT[f] for f in S.FOODS], np.float64)
# eat:best's order and its health without hunger
EAT_ORDER = np.array([IT["food_cooked"], IT["bread"], IT["food_raw"]], np.int64)
EAT_PTS = np.array([S.FOOD_PTS["food_cooked"], S.FOOD_PTS["bread"], S.FOOD_PTS["food_raw"]], np.float64)
EAT_SAT = np.array([S.SAT["food_cooked"], S.SAT["bread"], S.SAT["food_raw"]], np.float64)
EAT_HEAL = np.array([6.0, 5.0, 3.0])
# armour: material -> its pieces' items in SLOTS order, the material's item
MATS = list(S.ARMOR_MAT)
MAT_PIECE = np.array([[IT[m + "_" + p] for p in SLOTS] for m in MATS], np.int64)
MAT_ITEM = np.array([IT[S.ARMOR_MAT[m]] for m in MATS], np.int64)
PIECE_N = np.array([S._PIECES[p] for p in SLOTS], np.int64)
# sorted(a.bag, key=-ARMOR): the armour pieces by points, ties as they came (the bag's order does not change the points worn)
ARMOR_BY_PTS = np.array(sorted([IT[k] for k in S.ARMOR], key=lambda i: -IT_ARMOR[i]), np.int64)

# ---- recipes --------------------------------------------------------------------------------------------------
RECS = list(S.SIM_RECIPES)
NREC = len(RECS)
REC_ITEM = np.array([IT[r] for r in RECS], np.int64)
REC_OUT = np.array([S.SIM_RECIPES[r][1] for r in RECS], np.int64)
REC_SMALL = np.array([S.SIM_RECIPES[r][2] for r in RECS], np.bool_)
REC_NEED_IT = np.full((NREC, 4), -1, np.int64)
REC_NEED_N = np.zeros((NREC, 4), np.int64)
for _r, _k in enumerate(RECS):
    for _j, (_it, _n) in enumerate(S.SIM_RECIPES[_k][0].items()):
        REC_NEED_IT[_r, _j] = IT[_it]
        REC_NEED_N[_r, _j] = _n
REC_OF_ITEM = np.full(NIT, -1, np.int64)
for _r, _k in enumerate(RECS):
    REC_OF_ITEM[IT[_k]] = _r
# the wood a craft uses (RlScore.Sustain): need log + planks / 4, for the woody crafts
REC_WOODY = np.array([(r in ("planks", "stick") or r.startswith("wooden_") or r in ("crafting_table", "chest", "bookshelf", "fence", "fence_gate"))
                      for r in RECS], np.bool_)
REC_WOOD = np.array([S.SIM_RECIPES[r][0].get("log", 0) + S.SIM_RECIPES[r][0].get("planks", 0) / 4 for r in RECS], np.float64)

# ---- achievements ---------------------------------------------------------------------------------------------
ACH = ACHM.ALL
NACH = len(ACH)
ACH_IX = {k: i for i, k in enumerate(ACH)}
ACH_PARENT = np.array([ACH_IX[ACHM.ACH[k][0]] if ACHM.ACH[k][0] else -1 for k in ACH], np.int64)
ACH_SIM = np.array([ACHM.ACH[k][2] for k in ACH], np.bool_)
ACH_DEPTH = np.array([ACHM.depth(k) for k in ACH], np.float64)

# ---- ores by horizon --------------------------------------------------------------------------------------------
ORES = ["coal", "iron", "gravel", "gold", "redstone", "diamond", "obsidian", "lava"]
OR = {k: i for i, k in enumerate(ORES)}
NORE = len(ORES)
# self.hidden[lv]'s keys in their order (look_around, mine:stone, descend, tunnel see them in it)
LEVEL_KEYS = np.full((3, NORE), -1, np.int64)
LEVEL_LO = np.zeros((3, NORE), np.int64)
LEVEL_HI = np.zeros((3, NORE), np.int64)
for _lv, _ores in S.LEVEL_ORES.items():
    _ks = list(_ores) + (["lava"] if _lv == 2 else [])
    for _j, _k in enumerate(_ks):
        LEVEL_KEYS[_lv, _j] = OR[_k]
        if _k in _ores:
            LEVEL_LO[_lv, OR[_k]], LEVEL_HI[_lv, OR[_k]] = _ores[_k]
DEEP_SEEN = np.array([OR[k] for k in ("coal", "iron", "gold", "redstone", "diamond", "obsidian", "gravel")], np.int64)
# mine:<ore>: the ore's item, how many, xp a piece; its reward key
MINE_ITEM = np.full(NORE, -1, np.int64)
MINE_N = np.zeros(NORE, np.int64)
MINE_XP = np.zeros(NORE, np.float64)
for _k, (_it, _n, _xp) in {"coal": ("coal", 3, 1), "iron": ("iron_ore", 3, 0), "gold": ("gold_ore", 2, 0), "redstone": ("redstone", 4, 3),
                           "diamond": ("diamond", 1, 5), "obsidian": ("obsidian", 1, 0)}.items():
    MINE_ITEM[OR[_k]], MINE_N[OR[_k]], MINE_XP[OR[_k]] = IT[_it], _n, _xp

# ---- animals ----------------------------------------------------------------------------------------------------
ANIMALS = list(S.ANIMALS)          # chicken, sheep, cow, pig
AN = {k: i for i, k in enumerate(ANIMALS)}
ANIMAL_CUM = np.array(list(__import__("itertools").accumulate(S.ANIMALS.values())), np.float64)
WILD = [AN["cow"], AN["sheep"]]    # pen_wild's keys in their order

# ---- reasons and outcomes ---------------------------------------------------------------------------------------
REASONS = ["OK", "MASKED", "NO_PATH", "OUT_OF_REACH", "TIMEOUT", "BLOCKED", "NOT_VISIBLE", "NO_ITEMS", "NO_SPOT", "NO_TREE_THERE",
           "NOT_SEEN", "MISSING", "NO_TABLE", "NO_FURNACE", "NO_FUEL", "NO_HOLES", "NO_WEAPON", "ALL_TAKEN", "NO_FOOD", "NO_ANIMAL",
           "NOT_IN_CHEST", "DONE_ALREADY", "NO_SEEDS", "NOT_RIPE", "NO_MATE", "NO_XP", "DIED"]
RS = {k: i for i, k in enumerate(REASONS)}
OUTCOMES = ["none", "ok", "fail_path", "fail_items", "fail_target", "fail_danger", "fail_other"]
OC = {k: i for i, k in enumerate(OUTCOMES)}
REASON_OUT = np.array([OC[spec.outcome(r)] for r in REASONS], np.int64)
GROUPS = list(NEAR.FAIL_GROUPS)
REASON_GROUP = np.array([GROUPS.index(NEAR.fail_group(r)) if NEAR.fail_group(r) else -1 for r in REASONS], np.int64)

# ---- the reward's keys ------------------------------------------------------------------------------------------
_keys = list(spec.WEIGHTS)
for _sc in S.SIM_SCEN.values():
    for _k in _sc["weights"]:
        if _k not in _keys:
            _keys.append(_k)
for _k in ("time", "stall", "noop", "skill_ok", "skill_fail", "retry_fail", "switch_ok", "damage", "death", "accident", "goal", "new_item",
           "gathered.log", "gathered.cobblestone", "gathered.coal", "gathered.iron_ore", "gathered.food", "iron_found", "kill",
           "perimeter.kill", "perimeter.hurt_inside", "perimeter.breach", "perimeter.mob_seconds", "wave_cleared", "perimeter.wave_cleared",
           "perimeter.clean_wave", "holes_at_wave", "perimeter.holes_at_wave", "out_died", "perimeter.out_died", "built.wall", "built.moat",
           "built.torch", "ate", "enchant.shelf", "enchant.item", "enchant.levels", "achievement.new", "achievement.depth", "curiosity.novel",
           "mine.coal", "mine.iron", "mine.gold", "mine.redstone", "mine.diamond", "mine.obsidian", "mine.return", "mine.depth",
           "equipment.armor", "equipment.weapon", "equipment.pickaxe", "equipment.ranged", "safety.wall", "safety.moat", "safety.light",
           "safety.gate", "enchant.table", "sustain.source", "stock.value", "sustain.food", "sustain.wood", "tier_up"):
    if _k not in _keys:
        _keys.append(_k)
KEYS = _keys
NKEY = len(KEYS)
K = {k: i for i, k in enumerate(KEYS)}
COMPS = sorted({k.split(".")[0] for k in KEYS})
KEY_COMP = np.array([COMPS.index(k.split(".")[0]) for k in KEYS], np.int64)
# give()'s stall clock: a positive part of a scale, the goal or skill_ok
KEY_PROGRESS = np.array([(k.split(".")[0] in spec.SCALES or k.split(".")[0] in ("goal", "skill_ok")) for k in KEYS], np.bool_)
MINE_KEY = np.full(NORE, -1, np.int64)
for _o in ("coal", "iron", "gold", "redstone", "diamond", "obsidian"):
    MINE_KEY[OR[_o]] = K["mine." + _o]

# ---- scenarios --------------------------------------------------------------------------------------------------
SCENS = list(S.ALL_SCEN)
NSC = len(SCENS)
SC = {k: i for i, k in enumerate(SCENS)}
GOALS = ["none", "items", "food", "fortify", "armor", "bow", "mine_trip", "farm", "pasture", "enchant", "achievements"]
G = {k: i for i, k in enumerate(GOALS)}


def scen_tables(curiosity: float = -1.0):
    """per scenario: its parameters (floats and ints), kit, reward weights and factors, scales on"""
    f = {}
    n = NSC
    f["goal"] = np.array([G[S.ALL_SCEN[s]["goal"]] for s in SCENS], np.int64)
    f["item"] = np.array([IT.get(S.ALL_SCEN[s]["item"], -1) for s in SCENS], np.int64)
    f["per"] = np.array([S.ALL_SCEN[s]["per"] for s in SCENS], np.float64)
    f["crew_goal"] = np.array([bool(S.ALL_SCEN[s].get("crew_goal")) for s in SCENS], np.bool_)
    f["seconds"] = np.array([S.ALL_SCEN[s]["seconds"] for s in SCENS], np.float64)
    f["health"] = np.array([float(S.ALL_SCEN[s]["health"]) for s in SCENS], np.float64)
    tod = [S.ALL_SCEN[s]["time"] for s in SCENS]
    f["day_sin"] = np.array([math.sin(2 * math.pi * t / 24000) for t in tod])
    f["day_cos"] = np.array([math.cos(2 * math.pi * t / 24000) for t in tod])
    f["night"] = np.array([1 if 13000 <= t < 23000 else 0 for t in tod], np.int64)
    f["dusk_in"] = np.array([(12500 - t) / 20.0 if t < 12500 else 0 for t in tod], np.float64)
    f["waves"] = np.array([{None: 0, "easy": 1, "normal": 2}[S.ALL_SCEN[s].get("waves")] for s in SCENS], np.int64)
    f["prep"] = np.array([float(S.ALL_SCEN[s].get("prep", 30.0)) for s in SCENS])
    f["hunger"] = np.array([bool(S.ALL_SCEN[s].get("hunger")) and S.V2 for s in SCENS], np.bool_)
    f["xp"] = np.array([int(S.ALL_SCEN[s].get("xp", 0)) for s in SCENS], np.int64)
    f["has_chest"] = np.array(["chest" in S.ALL_SCEN[s] for s in SCENS], np.bool_)
    f["chest"] = np.array([S.ALL_SCEN[s].get("chest", 0) for s in SCENS], np.int64)
    f["iron_lo"] = np.array([S.ALL_SCEN[s].get("iron", S.CAL["surface_iron"])[0] for s in SCENS], np.int64)
    f["iron_hi"] = np.array([S.ALL_SCEN[s].get("iron", S.CAL["surface_iron"])[1] for s in SCENS], np.int64)
    for k in ("rich", "moat_dug", "pond", "lava_pool"):
        f[k] = np.array([bool(S.ALL_SCEN[s].get(k)) for s in SCENS], np.bool_)
    f["lava_risk"] = np.array([float(S.ALL_SCEN[s].get("lava_risk", 0.0)) for s in SCENS])
    f["night_m"] = np.array([float(S.ALL_SCEN[s].get("night", 0)) for s in SCENS])
    f["unarmed"] = np.array([float(S.ALL_SCEN[s].get("unarmed", 1.0)) for s in SCENS])
    f["pass_kills"] = np.array([S.ALL_SCEN[s].get("pass_kills", -1) for s in SCENS], np.float64)
    f["pass_alive"] = np.array([S.ALL_SCEN[s].get("pass_alive", -1) for s in SCENS], np.float64)
    f["pass_diamonds"] = np.array([S.ALL_SCEN[s].get("pass_diamonds", -1) for s in SCENS], np.float64)
    f["waves_pass"] = np.array([spec.WAVES_PASS.get(s, 3) for s in SCENS], np.float64)
    f["sim_only"] = np.array([s in S.SIM_SCEN for s in SCENS], np.bool_)
    f["hang_scen"] = np.array([s in S.HANG_SCEN for s in SCENS], np.bool_)
    f["ditch_scen"] = np.array([s in S.DITCH_SCEN for s in SCENS], np.bool_)
    f["pasture"] = np.array([s == "pasture" for s in SCENS], np.bool_)
    f["many_animals"] = np.array([s in ("food", "pasture") for s in SCENS], np.bool_)
    f["long"] = np.array([S.ALL_SCEN[s]["seconds"] > 1200 for s in SCENS], np.bool_)
    kit = np.zeros((n, NIT), np.int64)
    w = np.zeros((n, NKEY))
    fac = np.ones((n, NKEY))
    scale_on = np.zeros((n, len(spec.SCALES)), np.int64)
    for i, s in enumerate(SCENS):
        sc = S.ALL_SCEN[s]
        for k, v in sc["kit"].items():
            kit[i, IT[k]] += v
        sim_only = s in S.SIM_SCEN
        ws = dict(sc["weights"]) if sim_only else dict(S.WEIGHTS)
        if curiosity >= 0:
            ws["curiosity.novel"] = curiosity
        scs = sc.get("scales", {})
        factors = dict(scs) if isinstance(scs, dict) else {k: 1.0 for k in scs}

        def factor(key):
            comp = key.split(".")[0]
            if comp not in spec.SCALES or sim_only:
                return 1.0
            if key in factors:
                return float(factors[key])
            return float(factors.get(comp, 0.0))
        for k, j in K.items():
            w[i, j] = ws.get(k, 0.0)       # absent: give() pays nothing, as a weight of 0
            fac[i, j] = factor(k)
        for j, s_ in enumerate(spec.SCALES):
            scale_on[i, j] = 1 if factor(s_) > 0 else 0
    f["kit"], f["w"], f["fac"], f["scale_on"] = kit, w, fac, scale_on
    return f


# ---- actions by family --------------------------------------------------------------------------------------------
FAMS = ["NOOP", "LOOK", "FRONTIER", "CHOP", "MINE_STONE", "MINE_GRAVEL", "MINE_ORE", "DESCEND", "TUNNEL", "ASCEND", "CRAFT", "CRAFT_ARMOR",
        "SMELT", "PLACE_ITEM", "BUILD_WALL", "REPAIR_WALL", "DIG_MOAT", "LIGHT", "EQUIP", "TAKE_POST", "HOLD_LINE", "SHOOT", "HOME",
        "EAT", "HUNT", "PUT_CHEST", "TAKE_CHEST", "BUILD_GATE", "CLOSE_GATE", "GATHER_SEEDS", "GATHER_SAPLINGS", "GATHER_CANE", "TILL",
        "PLANT_WHEAT", "HARVEST", "PLANT_SAPLING", "BUILD_PEN", "LURE", "BREED", "SLAUGHTER", "ENCHANT", "USE_BUCKET", "USE_FIRE",
        "ATTACK_MOB", "ATTACK_ANIMAL", "OPEN", "WAIT", "DIG", "PUT31", "PILLAR", "NONE"]
F = {k: i for i, k in enumerate(FAMS)}
TARGETS = list(S.TARGETS)          # front, below, front_down, hole, moat, mob
TG = {k: i for i, k in enumerate(TARGETS)}
DIRS = list(NEAR.DIRS)             # front, back, left, right, above, below
DIGS = list(NEAR.DIG)              # below, front, front_down, left, right, back, above
SIDES = list(NEAR.SIDES)

ACT_FAM = np.full(A, F["NONE"], np.int64)
ACT_P = np.full(A, -1, np.int64)    # the family's parameter: an ore, a recipe, a material, an item, a target, a direction
ACT_Q = np.full(A, -1, np.int64)    # a second one (smelt's product)
for _i, _n in enumerate(ACTIONS):
    fam, p, q = "NONE", -1, -1
    if _n == "noop":
        fam = "NOOP"
    elif _n.startswith("dig@"):
        fam, p = "DIG", DIGS.index(_n[4:])
    elif _n in S.PUT31 or _n in ("place:block@front", "place:block@front_down", "place:block@below"):
        fam, p = "PUT31", ["left", "right", "back", "above", "front", "front_down", "below"].index(_n[_n.index("@") + 1:])
    elif _n == "pillar:up":
        fam = "PILLAR"
    elif _n == "look_around":
        fam = "LOOK"
    elif _n == "go_to:frontier":
        fam = "FRONTIER"
    elif _n == "chop_tree:near":
        fam = "CHOP"
    elif _n == "mine:stone":
        fam = "MINE_STONE"
    elif _n == "mine:gravel":
        fam = "MINE_GRAVEL"
    elif _n.startswith("mine:"):
        fam, p = "MINE_ORE", OR[_n[5:].replace("_ore", "")]
    elif _n == "descend:mine":
        fam = "DESCEND"
    elif _n == "tunnel:branch":
        fam = "TUNNEL"
    elif _n == "ascend:surface":
        fam = "ASCEND"
    elif _n.startswith("craft:"):
        fam, p = "CRAFT", RECS.index(_n[6:])
    elif _n.startswith("craft_armor:"):
        fam, p = "CRAFT_ARMOR", MATS.index(_n[12:])
    elif _n.startswith("smelt:"):
        src, dst = {"smelt:iron_ingot": ("iron_ore", "iron_ingot"), "smelt:gold_ingot": ("gold_ore", "gold_ingot"),
                    "smelt:food": ("food_raw", "food_cooked")}[_n]
        fam, p, q = "SMELT", IT[src], IT[dst]
    elif _n.startswith("place:"):
        # sim_env.effect's "place:" branch comes before the primitives': place:<slot>@<target> asks the bag for an item
        # named "<slot>@<target>" and fails NO_ITEMS (the reference's behaviour, kept: see docs/rl/SIMBATCH.md)
        fam, p = "PLACE_ITEM", IT.get(_n[6:], -1)
    elif _n == "build_wall:ring":
        fam = "BUILD_WALL"
    elif _n == "repair_wall:nearest":
        fam = "REPAIR_WALL"
    elif _n == "dig_moat:ring":
        fam = "DIG_MOAT"
    elif _n == "light_area:base":
        fam = "LIGHT"
    elif _n == "equip:best":
        fam = "EQUIP"
    elif _n == "take_post:auto":
        fam = "TAKE_POST"
    elif _n == "hold_line:post":
        fam = "HOLD_LINE"
    elif _n == "shoot:post":
        fam = "SHOOT"
    elif _n in ("retreat:home", "go_to:base"):
        fam = "HOME"
    elif _n == "eat:best":
        fam = "EAT"
    elif _n == "hunt:near":
        fam = "HUNT"
    elif _n == "put:chest_all":
        fam = "PUT_CHEST"
    elif _n == "take:chest_food":
        fam = "TAKE_CHEST"
    elif _n == "build_gate:door":
        fam = "BUILD_GATE"
    elif _n == "close:gate":
        fam = "CLOSE_GATE"
    elif _n == "gather:seeds":
        fam = "GATHER_SEEDS"
    elif _n == "gather:saplings":
        fam = "GATHER_SAPLINGS"
    elif _n == "gather:sugar_cane":
        fam = "GATHER_CANE"
    elif _n == "till:farm":
        fam = "TILL"
    elif _n == "plant:wheat":
        fam = "PLANT_WHEAT"
    elif _n == "harvest:farm":
        fam = "HARVEST"
    elif _n == "plant:sapling":
        fam = "PLANT_SAPLING"
    elif _n == "build_pen:pasture":
        fam = "BUILD_PEN"
    elif _n == "lure:pen":
        fam = "LURE"
    elif _n == "breed:pen":
        fam = "BREED"
    elif _n == "slaughter:pen":
        fam = "SLAUGHTER"
    elif _n == "enchant:best":
        fam = "ENCHANT"
    elif _n.startswith("use:bucket@"):
        fam, p = "USE_BUCKET", TG[_n[11:]]
    elif _n.startswith("use:fire@"):
        fam, p = "USE_FIRE", TG[_n[9:]]
    elif _n == "attack@mob":
        fam = "ATTACK_MOB"
    elif _n == "attack@animal":
        fam = "ATTACK_ANIMAL"
    elif _n in ("open@gate", "open@front"):
        fam = "OPEN"
    elif _n == "wait:5":
        fam = "WAIT"
    ACT_FAM[_i], ACT_P[_i], ACT_Q[_i] = F[fam], p, q
assert (ACT_FAM != F["NONE"]).all(), [ACTIONS[i] for i in np.flatnonzero(ACT_FAM == F["NONE"])]

# begin(): the calibration's entry, the failure kind (a.stuck's key), the reasons of a sticky and a fresh failure
_cal = [S.sk(n) for n in ACTIONS]
ACT_FAIL = np.array([c.get("fail_fresh", 0.0) for c in _cal])
ACT_STICKY = np.array([c.get("sticky", 0.0) for c in _cal])
ACT_DUR_OK = np.array([float(c["dur_ok"]) for c in _cal])
ACT_DUR_FAIL = np.array([float(c.get("dur_fail", 2)) for c in _cal])
_kinds = []
for _n in ACTIONS:
    _kd = _n.split(":")[0] if not _n.startswith(("mine:", "craft:")) else _n
    if _kd not in _kinds:
        _kinds.append(_kd)
STUCK_KINDS = _kinds
NSTUCK = len(_kinds)
ACT_KIND = np.array([_kinds.index(_n.split(":")[0] if not _n.startswith(("mine:", "craft:")) else _n) for _n in ACTIONS], np.int64)
ACT_STICKY_REASON = np.array([RS["OUT_OF_REACH"] if _kinds[ACT_KIND[i]] in ("build_wall", "repair_wall", "light_area") else RS["NO_PATH"]
                              for i in range(A)], np.int64)
ACT_FRESH_REASON = np.array([{"chop_tree": RS["NO_PATH"], "hunt": RS["TIMEOUT"]}.get(_kinds[ACT_KIND[i]],
                             RS["BLOCKED"] if _kinds[ACT_KIND[i]].startswith("mine") else RS["NO_PATH"]) for i in range(A)], np.int64)
STUCK_BUILD = _kinds.index("build_wall")
STUCK_REPAIR = _kinds.index("repair_wall")
ACT_LOCAL = np.array([S.local(n) for n in ACTIONS], np.bool_)
ACT_MOVE = np.array([n in S.MOVES for n in ACTIONS], np.bool_)
ACT_PLACE = np.array([n.startswith("place:") for n in ACTIONS], np.bool_)
ACT_NO_RESULT = np.array([n in S.NO_RESULT for n in ACTIONS], np.bool_)
ACT_CRAFT_TABLE = np.array([(n.startswith("craft:") and not S.SIM_RECIPES[n[6:]][2]) or n.startswith("craft_armor:") for n in ACTIONS], np.bool_)
ACT_GOHOME = np.array([n in ("go_to:base", "retreat:home", "take_post:auto", "hold_line:post") for n in ACTIONS], np.bool_)
ACT_MINE_ORE = np.array([n.startswith("mine:") and n not in ("mine:stone", "mine:gravel") for n in ACTIONS], np.bool_)
ACT_IS_MINE = np.array([n.startswith("mine:") for n in ACTIONS], np.bool_)
# begin()'s own times: a code for the skills whose time is not the calibration's
DURS = ["CAL", "FRONTIER", "HOME", "CHOP", "MINE", "CRAFT_TABLE", "WALL", "REPAIR", "HOLD", "WAIT", "DESCEND", "ASCEND", "TUNNEL", "SHOOT",
        "FARM", "GATHER", "LURE", "SHORT", "PEN"]
DU = {k: i for i, k in enumerate(DURS)}


def _dur(n):
    if n == "go_to:frontier":
        return "FRONTIER"
    if n in ("retreat:home", "go_to:base"):
        return "HOME"
    if n == "chop_tree:near":
        return "CHOP"
    if n.startswith("mine:"):
        return "MINE"
    if n.startswith("craft:") and not S.SIM_RECIPES[n[6:]][2] or n.startswith("craft_armor:"):
        return "CRAFT_TABLE"
    return {"build_wall:ring": "WALL", "repair_wall:nearest": "REPAIR", "hold_line:post": "HOLD", "wait:5": "WAIT", "descend:mine": "DESCEND",
            "ascend:surface": "ASCEND", "tunnel:branch": "TUNNEL", "shoot:post": "SHOOT", "till:farm": "FARM", "plant:wheat": "FARM",
            "harvest:farm": "FARM", "plant:sapling": "FARM", "gather:seeds": "GATHER", "gather:saplings": "GATHER",
            "gather:sugar_cane": "GATHER", "lure:pen": "LURE", "breed:pen": "SHORT", "slaughter:pen": "SHORT", "enchant:best": "SHORT",
            "close:gate": "SHORT", "build_pen:pasture": "PEN"}.get(n, "CAL")


ACT_DUR = np.array([DU[_dur(n)] for n in ACTIONS], np.int64)
CRAFT_SMALL_DUR = float(S.SK["craft_small"]["dur_ok"])

# ---- the links' vias and the effects (RlCause.novel's pairs) --------------------------------------------------------
VIAS = ["hit", "block:lava", "block:fire", "block:water", "block:cobblestone", "step"] + ["item:" + k for k in ITEMS]   # item:wheat among them
VIA = {k: i for i, k in enumerate(VIAS)}
VIA_ITEM0 = VIAS.index("item:" + ITEMS[0])
EFFECTS = ["mob_hurt", "mob_killed", "animal_killed", "agent_saved"]
EF = {k: i for i, k in enumerate(EFFECTS)}
EF_CURIOUS = np.array([e in S.CURIOUS for e in EFFECTS], np.bool_)
# the link kinds that are not actions
KIND_REFLEX_WATER = A          # "reflex:water"
KIND_PLANT_WHEAT = spec.IDX["plant:wheat"]

# ---- the raw facts' columns ---------------------------------------------------------------------------------------
_rule_facts: list[str] = []


def _walk(r):
    if isinstance(r, dict):
        for c in r.get("all", r.get("any", [])):
            _walk(c)
    else:
        if r[0] not in _rule_facts:
            _rule_facts.append(r[0])
        if isinstance(r[2], str) and r[2] not in _rule_facts:
            _rule_facts.append(r[2])


for _a in spec.ACTION_SPECS:
    _walk(_a["mask"])
COLS = list(spec.NAMES)
for _n in _rule_facts + ["tree_dist", "pick_tier", "wall_blocks"] + ["tgt_%s_%s_src" % (t, l) for t in TARGETS for l in ("water", "lava")]:
    if _n not in COLS:
        COLS.append(_n)
NCOL = len(COLS)
TRASH = NCOL                  # a fact neither the obs nor the mask reads: written here, never read
R_W = NCOL + 1
COL = {k: i for i, k in enumerate(COLS)}


def col(name: str) -> int:
    return COL.get(name, TRASH)


def cols(names) -> np.ndarray:
    return np.array([col(n) for n in names], np.int64)


# the contract: a distance not given is its scale (DISTS); the row starts so
ROW0 = np.zeros(R_W)
for _n, _k, _s in S.DISTS:
    ROW0[COL[_n]] = _s
FEAT_KIND = np.array([{"bool": 0, "log": 1}.get(k, 2) for _, k, _ in spec.FEATURES], np.int64)
FEAT_SCALE = np.array([s for _, _, s in spec.FEATURES], np.float64)
FEAT_LOG1P = np.array([math.log1p(s) for _, _, s in spec.FEATURES], np.float64)

SCEN_COL = cols(["scen_" + s.replace(":", "_") for s in SCENS])
SCALE_COL = cols(["scale_" + s for s in spec.SCALES])
AGENT_COL = cols(["agent_%d" % i for i in range(MA)])
BAG_COL = cols(["bag_" + k if k not in S.ARMOR else "-" for k in ITEMS])
CRAFTABLE_COL = cols(["craftable_" + r for r in RECS])
SMALL_COL = cols(["small_" + r for r in RECS])
ARMOR_CRAFTABLE_COL = cols(["armor_craftable_" + m for m in MATS])
ACH_COL = cols(["ach_" + k for k in ACH])
ACH_TARGET_COL = cols(["ach_target_" + k for k in ACH])
LAST_OUT_COL = cols(["last_" + o for o in OUTCOMES])
LAST_ACTION_COL = cols(["last_action_" + n for n in ACTIONS])
FAIL_RUN_COL = cols(["fail_run_" + n for n in ACTIONS])
FAIL_RECENT_COL = cols(["fail_recent_" + n for n in ACTIONS])
LASTFAIL_COL = cols(["lastfail_" + g for g in GROUPS])
ORE_SEEN_COL = cols([k + "_seen" for k in ORES])
ORE_MINABLE_COL = cols([k + "_minable" for k in ORES])
ORE_DIST_COL = cols([k + "_dist" for k in ORES])
TGT_THERE_COL = cols(["tgt_" + t for t in TARGETS])
TGT_DIST_COL = cols(["tgt_%s_dist" % t for t in TARGETS])
TGT_FREE_COL = cols(["tgt_%s_free" % t for t in TARGETS])
TGT_WATER_COL = cols(["tgt_%s_water" % t for t in TARGETS])
TGT_LAVA_COL = cols(["tgt_%s_lava" % t for t in TARGETS])
TGT_WSRC_COL = cols(["tgt_%s_water_src" % t for t in TARGETS])
TGT_LSRC_COL = cols(["tgt_%s_lava_src" % t for t in TARGETS])
NB_SOLID_COL = cols(["nb_%s_solid" % d for d in DIRS])
NB_AIR_COL = cols(["nb_%s_air" % d for d in DIRS])
NB_LIQ_COL = cols(["nb_%s_liquid" % d for d in DIRS])
NB_MOB_COL = cols(["nb_%s_mob" % d for d in DIRS])
NB_DIG_COL = cols(["nb_%s_dig" % d for d in DIGS])
NB_FREE_COL = cols(["nb_%s_free" % d for d in NEAR.PUT])
_named = {}


def named_cols() -> dict[str, int]:
    """C_<name> for every single fact the kernel writes by name"""
    names = ["n_agents", "health", "max_health", "low_health", "armor", "held_tier", "pick_tier", "weapon_tier", "sword_tier", "hoe_tier",
             "hoes", "at_home", "hunger_on", "food_level", "saturation", "xp_level", "xp_progress", "day_sin", "day_cos", "night", "dusk_in",
             "bag_armor", "bag_total", "bag_stackables", "food_items", "lure_food", "place_spot", "lure_targets", "fuel", "wall_blocks",
             "underground", "depth_level", "at_horizon", "y_rel", "mine_known", "mine_level", "mine_dist", "tunnels", "trip_ores",
             "trees_seen", "tree_dist", "trees_free", "stone_seen", "stone_dist", "animals_seen", "animal_dist", "frontier", "grass_seen",
             "leaves_seen", "cane_seen", "table_known", "table_dist", "furnace_known", "furnace_dist", "chest_known", "chest_dist",
             "chest_food", "base_known", "base_dist", "ench_table_known", "ench_table_dist", "bookshelves", "shelf_spots", "shelves_total",
             "enchantable", "farm_plots", "farm_planted", "farm_ripe", "farm_empty", "till_spots", "saplings_planted", "trees_grown",
             "sapling_spots", "pen_built", "pen_animals", "pen_adults", "pen_babies", "pen_pairs", "pen_dist", "door_placed", "gate_open",
             "waves_active", "wave_on", "phase_prep", "phase_break", "wave_num", "next_wave_in", "enemies", "enemies_visible", "enemy_dist",
             "enemy_dx", "enemy_dz", "enemies_near", "ring_planned", "wall_frac", "holes", "inside_wall", "posts_free", "moat_frac",
             "torches_placed", "has_post", "post_dist", "light_spots", "can_equip", "mobs_inside", "breaches", "mob_seconds", "clean_waves",
             "food_renew", "food_eaten", "wood_renew", "wood_used", "sources", "stock_value", "stall", "novel_count", "buckets", "slot_block",
             "ach_count", "ach_available", "allies_alive", "allies_health_mean", "allies_health_min", "allies_low", "ally_dist", "repeated",
             "last_seconds", "time_frac", "goal_progress", "pillar_ok", "walls_around", "enclosed", "shelter", "trees_reachable",
             "coal_seen", "coal_dist", "coal_minable", "iron_seen", "iron_dist", "iron_minable", "gravel_seen", "gravel_minable",
             "tgt_front_water_src", "tgt_front_lava_src", "tgt_front_door"]
    return {"C_" + n: col(n) for n in names}


# ---- the mask's rules as a postfix program (mask_rules.holds over the columns) ---------------------------------------
OPS = [">", ">=", "<", "<=", "==", "!="]


def mask_program():
    """per action a run of instructions: (code, a, b, c): code 0 a comparison op a, column b, column c (or -1 and the
    number in VAL); 1 all of the last a; 2 any of the last a"""
    code, x, y, z, val, start, length = [], [], [], [], [], [], []

    def emit(r):
        if isinstance(r, dict):
            sub = r.get("all") if "all" in r else r.get("any")
            for c in sub:
                emit(c)
            code.append(1 if "all" in r else 2)
            x.append(len(sub))
            y.append(-1)
            z.append(-1)
            val.append(0.0)
            return
        fact, op, other = r
        code.append(0)
        x.append(OPS.index(op))
        y.append(COL[fact])
        if isinstance(other, str):
            z.append(COL[other])
            val.append(0.0)
        else:
            z.append(-1)
            val.append(float(other))
    for a in spec.ACTION_SPECS:
        start.append(len(code))
        emit(a["mask"])
        length.append(len(code) - start[-1])
    return (np.array(code, np.int64), np.array(x, np.int64), np.array(y, np.int64), np.array(z, np.int64), np.array(val, np.float64),
            np.array(start, np.int64), np.array(length, np.int64))


# ---- the stock's caps (RlScore) --------------------------------------------------------------------------------------
STOCK_KEYS = list(S.STOCK)
STOCK_IT = np.array([IT[k] if k != "food" else -1 for k in STOCK_KEYS], np.int64)
STOCK_CAP = np.array([S.STOCK[k][0] for k in STOCK_KEYS], np.float64)
STOCK_VAL = np.array([S.STOCK[k][1] for k in STOCK_KEYS], np.float64)


# ---- the reward v4 (docs/rl/REWARD-v4.md, tools/rl/reward_core.py): its parameters, components, scale weights ------------
import reward_core as RC  # noqa: E402

P4, (SW4, _SS4, _SCALES4) = RC.defaults()
C4 = ["noop", "goal", "human", "crew.damage", "crew.death", "base.breach", "base.mob_seconds", "surrender", "afk", "credit.kill",
      "credit.block", "credit.cover", "credit.dodge", "credit.survive", "credit.scout", "credit.wasted_day", "credit.scale", "curiosity.novel"]
for _k in SW4:
    if _k not in C4:
        C4.append(_k)
C4X = {k: i for i, k in enumerate(C4)}
NC4 = len(C4)
# a key of the simulator's -> the v4 component of its scale event (-1: not a scale v4 pays: perimeter and curiosity are events)
KEY_SCALE4 = np.array([C4X.get(k, -1) if (k.split(".")[0] in spec.SCALES and k.split(".")[0] not in ("perimeter", "curiosity")) else -1
                       for k in KEYS], np.int64)
SW4_C = np.array([SW4.get(k, 0.0) for k in C4], np.float64)          # a component's scale weight (rl/reward.json's)
SW4_HAS = np.array([k in SW4 for k in C4], np.bool_)
FAC4 = np.ones((NSC, NC4))                                            # the core's factor by scenario (None for the sim's own: 1)
for _i, _s in enumerate(SCENS):
    _f = None if _s in S.SIM_SCEN else dict(_SS4.get(_s, {}))
    for _j, _k in enumerate(C4):
        _comp = _k.split(".")[0]
        if _f is None or _comp not in _SCALES4:
            FAC4[_i, _j] = 1.0
        elif _k in _f:
            FAC4[_i, _j] = float(_f[_k])
        else:
            FAC4[_i, _j] = float(_f.get(_comp, 0.0))
_o = P4["outcomes"]
P4F = {"noop": _o["noop"], "goal": _o["goal"], "human": _o["human"], "dmg": _o["crew.damage"], "death": _o["crew.death"],
       "breach": _o["base.breach"], "mob_s": _o["base.mob_seconds"], "accident_x": P4["death"]["accident_x"], "base_r": P4["death"]["base_r"],
       "far_per": P4["death"]["far_per_block"], "far_cap": P4["death"]["far_cap"], "finds_soften": P4["death"]["finds_soften"],
       "soften_cap": P4["death"]["soften_cap"], "afk_rate": P4["afk"]["rate"], "afk_grace": P4["afk"]["grace_s"], "afk_ramp": P4["afk"]["ramp_s"],
       "afk_active": P4["afk"]["active_s"], "near_r": P4["sight"]["near_r"], "agent_hits": P4["threat"]["agent_hits"],
       "th_kill": P4["threat"]["kill"], "th_deter": P4["threat"]["deter"], "delay_tau": P4["threat"]["delay_tau_s"],
       "dodge_s": P4["threat"]["dodge_s"], "w_kill": P4["threat"]["w"]["kill"], "w_blocked": P4["threat"]["w"]["blocked"],
       "w_stuck": P4["threat"]["w"]["stuck"], "w_fell": P4["threat"]["w"]["fell"], "w_detour": P4["threat"]["w"]["detour"],
       "w_cover": P4["threat"]["w"]["cover"], "w_moved": P4["threat"]["w"]["moved"], "hp_low": P4["survive"]["hp_low"],
       "hp_safe": P4["survive"]["hp_safe"], "surv_share": P4["survive"]["share"], "scout_share": P4["scout"]["share"],
       "wd_share": P4["wasted_day"]["share"], "scale_move": P4["scale_move"]}
PHASES4 = ["day", "night", "prep", "wave", "break"]
CALM4 = np.array([p in P4.get("calm_phases", ("day", "prep", "break")) for p in PHASES4], np.bool_)
NOCUR4 = np.array([p in P4.get("no_curiosity_phases", ("wave", "night")) for p in PHASES4], np.bool_)
ACCIDENT_IS = "accident" in P4["death"]["accident_causes"]
# the events (rl/events.json) as the kernel keeps them
EV4 = ["agent", "decision", "noop", "goal", "human", "damage", "death", "breach", "inside", "scale", "found", "threat", "fall",
       "path_changed", "attack_failed", "kill", "mob_gone", "novel"]
ACT_BOX = np.array([n in S.PUT31 or n.startswith("place:block@") or n == "pillar:up" for n in ACTIONS], np.bool_)
# found4: a kind seen -> the scale it may give and how much (the simulator's ores and trees)
FOUND4 = {"coal": ("mine.coal", 3), "iron": ("mine.iron", 3), "gold": ("mine.gold", 2), "redstone": ("mine.redstone", 4),
          "diamond": ("mine.diamond", 1), "obsidian": ("mine.obsidian", 1), "trees": ("stock.value", 0.125)}
