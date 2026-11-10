"""The batch simulator's kernels (numba): tools/rl/sim_env.py's world, skills, reward and observation over arrays,
one arena a row, all arenas in one call (in parallel over arenas when asked). Every function here is a port of the
sim_env function of the same name, line by line and draw for draw (tools/rl/simbatch_rng.py is Python's random
in numba), so a batch arena and a SimEnv with the same seed and the same actions give the same observations, masks,
rewards and ends (tools/tests/test_rl_simbatch.py). Where sim_env keeps dicts, lists and sets the kernel keeps arrays
with fixed capacities (overflows are counted in st.ai[:, A_OVERFLOW], never silent).

The reward is paid as sim_env pays it (rl/reward.json's weights and the scenario's factors, by key), and every
payment is also summed by key into st.facts (agent x key: the measured amounts, before the weights) and, with its
causes, written to the step's event buffer: the reward is a table over those (see simbatch.py's `reward_from_facts`).
"""
from __future__ import annotations

import math
import os
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import simbatch_tables as T  # noqa: E402

import numba as nb  # noqa: E402
from numba.experimental import structref  # noqa: E402

_cache = T.ROOT / "build" / "numba" / (T.source_hash() + "-o" + os.environ.get("NUMBA_OPT", "3"))
_cache.mkdir(parents=True, exist_ok=True)
nb.config.CACHE_DIR = str(_cache)

from simbatch_rng import below, choices1, gauss, rnd, randint, uniform  # noqa: E402
from simbatch_rng import seed_words as from_key  # noqa: E402

# ---- constants (numba freezes these globals) -------------------------------------------------------------------
MA = T.MA
A = T.A
D = T.D
R_W = T.R_W
NIT = T.NIT
NKEY = T.NKEY
NACH = T.NACH
NORE = T.NORE
RING, MOAT, POSTS = 64, 80, 4
STALL_S = float(T.S.STALL_S)
GROW = float(T.S.GROW)
WINDOW_S = float(T.S.WINDOW_S)
LOSSES_PER_WAVE = T.S.LOSSES_PER_WAVE
RESPAWN_S = float(T.S.RESPAWN_S)
LOW = float(T.spec.LOW)
W_KILL = float(T.S.WCAL["kill"])
W_HURT = float(T.S.WCAL["hurt"])
W_LEAK = float(T.S.WCAL["gate_leak"])
CAL = T.S.CAL
SKC = T.S.SK
LEVEL_S = float(CAL["mine"]["level_s"])
MINE_ACC = np.array(CAL["mine"]["accident"], np.float64)
CAVE_HITS = np.array(CAL["mine"]["cave_hits"], np.float64)
H_DRAIN = float(CAL["hunger"]["drain_s"])
H_STARVE = float(CAL["hunger"]["starve_dmg_s"])
H_REGEN = float(CAL["hunger"]["regen_min_food"])
CROP0, CROP1 = float(CAL["crops"]["grow_s"][0]), float(CAL["crops"]["grow_s"][1])
SEED0, SEED1 = int(CAL["crops"]["seeds_per_harvest"][0]), int(CAL["crops"]["seeds_per_harvest"][1])
SAP0, SAP1 = float(CAL["saplings"]["grow_s"][0]), float(CAL["saplings"]["grow_s"][1])
PEN_GROW = float(CAL["pasture"]["grow_s"])
PEN_REST = float(CAL["pasture"]["breed_cooldown_s"])
CHOP_EMPTY = float(SKC["chop_tree"]["empty_ok"])
STONE_EMPTY = float(SKC["mine_stone"]["empty_ok"])
STONE_YIELD = float(SKC["mine_stone"]["yield_mean"])
TABLE_BAD = float(SKC["craft_table"].get("table_bad", 0.0))
SMELT_PER = int(SKC["smelt"].get("per_call", 1))
BAD0, BAD1 = float(SKC["repair_wall"]["bad_cells"][0]), float(SKC["repair_wall"]["bad_cells"][1])
MOAT_YIELD = int(SKC["dig_moat"].get("yield_mean", 16))
CRAFT_SMALL_DUR = T.CRAFT_SMALL_DUR
SAPLING_ROOM = np.array([0, 0, 0, 1, 2, 4, 8], np.int64)
FAIL_WINDOW = T.NEAR.FAIL_WINDOW

IT = T.IT
I_LOG, I_PLANKS, I_STICK, I_COBBLE, I_DIRT = IT["log"], IT["planks"], IT["stick"], IT["cobblestone"], IT["dirt"]
I_COAL, I_IRON_ORE, I_DIAMOND, I_GRAVEL, I_FLINT = IT["coal"], IT["iron_ore"], IT["diamond"], IT["gravel"], IT["flint"]
I_FOOD_RAW, I_FOOD_COOKED, I_BREAD = IT["food_raw"], IT["food_cooked"], IT["bread"]
I_SAPLING, I_WOOL, I_FEATHER, I_LEATHER, I_STRING = IT["sapling"], IT["wool"], IT["feather"], IT["leather"], IT["string"]
I_WHEAT, I_SEEDS, I_CANE = IT["wheat"], IT["wheat_seeds"], IT["sugar_cane"]
I_BUCKET, I_WATER_B, I_LAVA_B = IT["bucket"], IT["water_bucket"], IT["lava_bucket"]
I_FURNACE, I_TABLE, I_CHEST, I_TORCH = IT["furnace"], IT["crafting_table"], IT["chest"], IT["torch"]
I_BOW, I_ARROW, I_FENCE, I_FENCE_GATE, I_DOOR = IT["bow"], IT["arrow"], IT["fence"], IT["fence_gate"], IT["wooden_door"]
I_ETABLE, I_SHELF, I_FLINT_STEEL = IT["enchanting_table"], IT["bookshelf"], IT["flint_and_steel"]
I_WOODEN_HOE, I_STONE_HOE, I_IRON_INGOT = IT["wooden_hoe"], IT["stone_hoe"], IT["iron_ingot"]
I_EQUIPPED, I_POST, I_TRIP = IT["equipped"], IT["post"], IT["trip"]
NIT_BAG = T.NIT_BAG
BAGLESS = np.zeros(NIT, np.bool_)
BAGLESS[NIT_BAG:] = True

IT_TIER, IT_PICK, IT_SWORD, IT_HOE, IT_TOOL = T.IT_TIER, T.IT_PICK, T.IT_SWORD, T.IT_HOE, T.IT_TOOL
IT_ARMOR, IS_ARMOR, IT_SLOT = T.IT_ARMOR, T.IS_ARMOR, T.IT_SLOT
FOODS = np.array(T.FOODS, np.int64)
FOOD_PTS = T.FOOD_PTS
EAT_ORDER, EAT_PTS, EAT_SAT, EAT_HEAL = T.EAT_ORDER, T.EAT_PTS, T.EAT_SAT, T.EAT_HEAL
MAT_PIECE, MAT_ITEM, PIECE_N, ARMOR_BY_PTS = T.MAT_PIECE, T.MAT_ITEM, T.PIECE_N, T.ARMOR_BY_PTS
REC_ITEM, REC_OUT, REC_SMALL, REC_NEED_IT, REC_NEED_N = T.REC_ITEM, T.REC_OUT, T.REC_SMALL, T.REC_NEED_IT, T.REC_NEED_N
REC_WOODY, REC_WOOD, NREC = T.REC_WOODY, T.REC_WOOD, T.NREC
REC_BREAD = T.RECS.index("bread")
ACH_PARENT, ACH_SIM, ACH_DEPTH = T.ACH_PARENT, T.ACH_SIM, T.ACH_DEPTH
AH = T.ACH_IX
H_OPEN_INV, H_MINE_WOOD, H_WORKBENCH, H_PICKAXE = AH["openInventory"], AH["mineWood"], AH["buildWorkBench"], AH["buildPickaxe"]
H_FURNACE, H_IRON, H_HOE, H_BREAD = AH["buildFurnace"], AH["acquireIron"], AH["buildHoe"], AH["makeBread"]
H_BETTER_PICK, H_SWORD, H_KILL_ENEMY, H_KILL_COW = AH["buildBetterPickaxe"], AH["buildSword"], AH["killEnemy"], AH["killCow"]
H_SNIPE, H_DIAMONDS, H_ENCHANT, H_OVERKILL = AH["snipeSkeleton"], AH["diamonds"], AH["enchantments"], AH["overkill"]
H_BOOKCASE, H_BREED_COW = AH["bookcase"], AH["breedCow"]
ACH_ALL_ORDER = np.arange(NACH, dtype=np.int64)

OR = T.OR
O_COAL, O_IRON, O_GRAVEL, O_GOLD, O_REDSTONE, O_DIAMOND, O_OBSIDIAN, O_LAVA = (OR[k] for k in ("coal", "iron", "gravel", "gold", "redstone",
                                                                                              "diamond", "obsidian", "lava"))
LEVEL_KEYS, LEVEL_LO, LEVEL_HI, DEEP_SEEN = T.LEVEL_KEYS, T.LEVEL_LO, T.LEVEL_HI, T.DEEP_SEEN
MINE_ITEM, MINE_N, MINE_XP, MINE_KEY = T.MINE_ITEM, T.MINE_N, T.MINE_XP, T.MINE_KEY
AN = T.AN
N_ANIMALS = len(T.ANIMALS)
AN_CHICKEN, AN_SHEEP, AN_COW = AN["chicken"], AN["sheep"], AN["cow"]
ANIMAL_CUM = T.ANIMAL_CUM
WILD = np.array(T.WILD, np.int64)

RS = T.RS
for _k, _v in T.F.items():
    globals()['FAM_' + _k] = _v
for _k, _v in T.DU.items():
    globals()['DUR_' + _k] = _v
for _k, _v in T.RS.items():
    globals()['RS_' + _k] = _v
I_WOODEN_PICK = IT['wooden_pickaxe']
T_REC_OF_ITEM = T.REC_OF_ITEM
EQ_KEYS = np.array([T.K['equipment.armor'], T.K['equipment.weapon'], T.K['equipment.pickaxe'], T.K['equipment.ranged']], np.int64)

REASON_OUT, REASON_GROUP = T.REASON_OUT, T.REASON_GROUP
R_OK, R_MASKED, R_DIED = RS_OK, RS_MASKED, RS_DIED
OUT_NONE, OUT_OK = T.OC["none"], T.OC["ok"]

K = T.K
KEY_COMP, KEY_PROGRESS = T.KEY_COMP, T.KEY_PROGRESS
NCOMP = len(T.COMPS)
(K_TIME, K_STALL, K_NOOP, K_SKILL_OK, K_SKILL_FAIL, K_RETRY_FAIL, K_SWITCH_OK, K_DAMAGE, K_DEATH, K_ACCIDENT, K_GOAL, K_NEW_ITEM,
 K_G_LOG, K_G_COBBLE, K_G_COAL, K_G_IRON, K_G_FOOD, K_IRON_FOUND, K_KILL, K_P_KILL, K_P_HURT_IN, K_P_BREACH, K_P_MOB_S, K_WAVE_CLEARED,
 K_P_WAVE_CLEARED, K_P_CLEAN, K_HOLES, K_P_HOLES, K_OUT_DIED, K_P_OUT_DIED, K_B_WALL, K_B_MOAT, K_B_TORCH, K_ATE, K_E_SHELF, K_E_ITEM,
 K_E_LEVELS, K_A_NEW, K_A_DEPTH, K_CUR_NOVEL, K_M_RETURN, K_M_DEPTH, K_EQ_ARMOR, K_EQ_WEAPON, K_EQ_PICK, K_EQ_RANGED, K_S_WALL,
 K_S_MOAT, K_S_LIGHT, K_S_GATE, K_E_TABLE, K_SU_SOURCE, K_ST_VALUE, K_SU_FOOD, K_SU_WOOD) = (K[k] for k in (
    "time", "stall", "noop", "skill_ok", "skill_fail", "retry_fail", "switch_ok", "damage", "death", "accident", "goal", "new_item",
    "gathered.log", "gathered.cobblestone", "gathered.coal", "gathered.iron_ore", "gathered.food", "iron_found", "kill", "perimeter.kill",
    "perimeter.hurt_inside", "perimeter.breach", "perimeter.mob_seconds", "wave_cleared", "perimeter.wave_cleared", "perimeter.clean_wave",
    "holes_at_wave", "perimeter.holes_at_wave", "out_died", "perimeter.out_died", "built.wall", "built.moat", "built.torch", "ate",
    "enchant.shelf", "enchant.item", "enchant.levels", "achievement.new", "achievement.depth", "curiosity.novel", "mine.return",
    "mine.depth", "equipment.armor", "equipment.weapon", "equipment.pickaxe", "equipment.ranged", "safety.wall", "safety.moat",
    "safety.light", "safety.gate", "enchant.table", "sustain.source", "stock.value", "sustain.food", "sustain.wood"))
GATHERED_OF = np.full(NIT, -1, np.int64)
GATHERED_OF[I_COAL], GATHERED_OF[I_IRON_ORE] = K_G_COAL, K_G_IRON

G = T.G
G_NONE, G_ITEMS, G_FOOD, G_FORTIFY, G_ARMOR, G_BOW = G["none"], G["items"], G["food"], G["fortify"], G["armor"], G["bow"]
G_TRIP, G_FARM, G_PASTURE, G_ENCHANT, G_ACH = G["mine_trip"], G["farm"], G["pasture"], G["enchant"], G["achievements"]

F = T.F
ACT_FAM, ACT_P, ACT_Q, ACT_KIND = T.ACT_FAM, T.ACT_P, T.ACT_Q, T.ACT_KIND
ACT_FAIL, ACT_STICKY, ACT_DUR_OK, ACT_DUR_FAIL = T.ACT_FAIL, T.ACT_STICKY, T.ACT_DUR_OK, T.ACT_DUR_FAIL
ACT_STICKY_REASON, ACT_FRESH_REASON = T.ACT_STICKY_REASON, T.ACT_FRESH_REASON
ACT_LOCAL, ACT_MOVE, ACT_PLACE, ACT_NO_RESULT = T.ACT_LOCAL, T.ACT_MOVE, T.ACT_PLACE, T.ACT_NO_RESULT
ACT_GOHOME, ACT_MINE_ORE, ACT_DUR = T.ACT_GOHOME, T.ACT_MINE_ORE, T.ACT_DUR
NSTUCK, STUCK_BUILD, STUCK_REPAIR = T.NSTUCK, T.STUCK_BUILD, T.STUCK_REPAIR
DU = T.DU
IDX = T.spec.IDX
ACT_FRONTIER, ACT_BASE, ACT_HOME, ACT_ASCEND = IDX["go_to:frontier"], IDX["go_to:base"], IDX["retreat:home"], IDX["ascend:surface"]
ACT_DESCEND, ACT_TUNNEL = IDX["descend:mine"], IDX["tunnel:branch"]
ACT_PUT_FRONT_DOWN = IDX["place:block@front_down"]
ACT_BUCKET_FRONT_DOWN = IDX["use:bucket@front_down"]
ACT_FIRE_MOB = IDX["use:fire@mob"]
VIA = T.VIA
V_HIT, V_LAVA, V_FIRE, V_WATER, V_COBBLE, V_WHEAT, V_STEP, V_ITEM0 = (VIA[k] for k in ("hit", "block:lava", "block:fire", "block:water",
                                                                                       "block:cobblestone", "item:wheat", "step",
                                                                                       "item:" + T.ITEMS[0]))
assert V_ITEM0 + IT["wheat"] == V_WHEAT
NVIA = len(T.VIAS)
EF = T.EF
EF_MOB_HURT, EF_MOB_KILLED, EF_ANIMAL_KILLED, EF_AGENT_SAVED = EF["mob_hurt"], EF["mob_killed"], EF["animal_killed"], EF["agent_saved"]
EF_CURIOUS = T.EF_CURIOUS
NEF = len(T.EFFECTS)
KIND_REFLEX_WATER, KIND_PLANT_WHEAT = T.KIND_REFLEX_WATER, T.KIND_PLANT_WHEAT

# the raw facts' columns
globals().update(T.named_cols())
TRASH = T.TRASH
ROW0 = T.ROW0
SCEN_COL, SCALE_COL, AGENT_COL, BAG_COL = T.SCEN_COL, T.SCALE_COL, T.AGENT_COL, T.BAG_COL
CRAFTABLE_COL, SMALL_COL, ARMOR_CRAFTABLE_COL = T.CRAFTABLE_COL, T.SMALL_COL, T.ARMOR_CRAFTABLE_COL
ACH_COL, ACH_TARGET_COL, LAST_OUT_COL, LAST_ACTION_COL = T.ACH_COL, T.ACH_TARGET_COL, T.LAST_OUT_COL, T.LAST_ACTION_COL
FAIL_RUN_COL, FAIL_RECENT_COL, LASTFAIL_COL = T.FAIL_RUN_COL, T.FAIL_RECENT_COL, T.LASTFAIL_COL
ORE_SEEN_COL, ORE_MINABLE_COL, ORE_DIST_COL = T.ORE_SEEN_COL, T.ORE_MINABLE_COL, T.ORE_DIST_COL
TGT_THERE_COL, TGT_DIST_COL, TGT_FREE_COL = T.TGT_THERE_COL, T.TGT_DIST_COL, T.TGT_FREE_COL
TGT_WATER_COL, TGT_LAVA_COL, TGT_WSRC_COL, TGT_LSRC_COL = T.TGT_WATER_COL, T.TGT_LAVA_COL, T.TGT_WSRC_COL, T.TGT_LSRC_COL
NB_SOLID_COL, NB_AIR_COL, NB_LIQ_COL, NB_MOB_COL = T.NB_SOLID_COL, T.NB_AIR_COL, T.NB_LIQ_COL, T.NB_MOB_COL
NB_DIG_COL, NB_FREE_COL = T.NB_DIG_COL, T.NB_FREE_COL
FEAT_KIND, FEAT_SCALE, FEAT_LOG1P = T.FEAT_KIND, T.FEAT_SCALE, T.FEAT_LOG1P
MP_CODE, MP_X, MP_Y, MP_Z, MP_VAL, MP_START, MP_LEN = T.mask_program()
STOCK_IT, STOCK_CAP, STOCK_VAL = T.STOCK_IT, T.STOCK_CAP, T.STOCK_VAL
NSTOCK = len(STOCK_IT)
TG_FRONT, TG_BELOW, TG_FRONT_DOWN, TG_HOLE, TG_MOAT, TG_MOB = range(6)

# ---- the near cells (sim_near.Near) -----------------------------------------------------------------------------
AIR, SOLID, WATER, LAVA, BEDROCK, HARD, LEAVES = 0, 1, 2, 3, 4, 5, 6
# directions: front, back, left, right, above, below, front_down (off() for the facing (0, 1))
DIR_FRONT, DIR_BACK, DIR_LEFT, DIR_RIGHT, DIR_ABOVE, DIR_BELOW, DIR_FRONT_DOWN = range(7)
OFF = np.array([[0, 0, 1], [0, 0, -1], [1, 0, 0], [-1, 0, 0], [0, 2, 0], [0, -1, 0], [0, -1, 1]], np.int64)
DIG_DIRS = np.array([DIR_BELOW, DIR_FRONT, DIR_FRONT_DOWN, DIR_LEFT, DIR_RIGHT, DIR_BACK, DIR_ABOVE], np.int64)   # NEAR.DIG
PUT_DIRS = np.array([DIR_LEFT, DIR_RIGHT, DIR_BACK, DIR_ABOVE], np.int64)                                     # NEAR.PUT
PUT31_DIRS = np.array([DIR_LEFT, DIR_RIGHT, DIR_BACK, DIR_ABOVE, DIR_FRONT, DIR_FRONT_DOWN, DIR_BELOW], np.int64)
SIDE_DIRS = np.array([DIR_FRONT, DIR_BACK, DIR_LEFT, DIR_RIGHT], np.int64)
NB6 = np.array([[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]], np.int64)
NCELL = 96   # changed cells a Near holds

# ---- the state's columns ------------------------------------------------------------------------------------------
_AI = ("SCEN NAG TARGET FORCE TREES TREES_HIDDEN STONE_SEEN COAL COAL_HIDDEN IRON IRON_HIDDEN GRAVEL ANIMALS ANIMALS_HIDDEN GRASS LEAVES "
       "SAPLING_ROOM CANE CANE_HIDDEN MINE_LEVELS TABLE FURNACE TABLE_BAD CHEST CHEST_FOOD RING WALL BAD MOAT TORCHES POSTS_TAKEN DOOR "
       "GATE_OPEN PLOTS PLANTED GROWN PEN BRED ENCH_TABLE SHELVES PHASE WAVE_NUM SPAWNED KILLS BREACHES WAVE_BREACHES CLEAN WAVE_LOSSES "
       "CLEARED NOVEL REPEAT_FAIL DISCOVERIES BAKED POND LAVA_POOL MOAT_LIQ ML_DID ML_AGENT ML_TICK ML_KIND ML_VIA HANG HANG_CELLS "
       "HANG_GOT OB_HANG_LOGS OB_DITCH_MET OB_DITCH_BRIDGED OB_LAVA_MET OB_LAVA_PASSED OB_SWITCH_OK OB_RETRY_FAIL OB_PIT_STUCK DONE REASON "
       "CHANGED N_BUILDERS N_CROPS N_RIPE N_SAPLINGS N_YOUNG N_RENFOOD N_RENWHEAT N_SUSWIN SUSWIN_HEAD N_LASTREN DID_NEXT OVERFLOW EPISODES "
       "PEN_ORDER_N SUCCESS").split()
_AF = ("T MINE_D TABLE_D FURNACE_D PHASE_UNTIL ENEMIES INSIDE MOB_S WAVE_MOB_S SUS_FR SUS_FE SUS_WR SUS_WU SUS_PAID0 SUS_PAID1 CT_WALL CT_MOAT "
       "CT_LIGHT CT_GATE CT_ETABLE CT_SOURCE CT_STOCK OB_SHELTER PROGRESS_OUT").split()
_GI = ("XP HELD DEPTH TUN0 TUN1 TUN2 POST ALIVE PEND PEND_ACT PEND_OK PEND_REASON HOLDING DID KIND LAST OUTCOME REPEATED TRIP_ORES TRIP_OUT TRIPS "
       "ENCHANTED DITCH LAVA_FRONT PIT_FAILS NEAR_VALID NEAR_GROUND NEAR_PICK NEAR_Y NEAR_PLACED NEAR_N MOB_ON MOB_X MOB_Y MOB_Z "
       "F_LAST_FAILED F_GROUP F_WIN_N F_WIN_HEAD HAS_RESPAWN").split()
_GF = ("HEALTH FOOD SAT XP_PTS DIST FREE_AT PEND_BEGAN ACC DID_T LAST_S TRK_ARMOR TRK_WEAPON TRK_PICK TRK_RANGED TRK_DEPTH PROGRESS_T "
       "RESPAWN_AT").split()
for _j, _n in enumerate(_AI):
    globals()["A_" + _n] = _j
for _j, _n in enumerate(_AF):
    globals()["AF_" + _n] = _j
for _j, _n in enumerate(_GI):
    globals()["GI_" + _n] = _j
for _j, _n in enumerate(_GF):
    globals()["GF_" + _n] = _j
NAI, NAF, NGI, NGF = len(_AI), len(_AF), len(_GI), len(_GF)
PH_OFF, PH_PREP, PH_WAVE, PH_BREAK = 0, 1, 2, 3
DONE_NO, DONE_TIME, DONE_NOOP, DONE_GOAL, DONE_LOST = 0, 1, 2, 3, 4
DONE_REASONS = ["", "time", "noop", "goal", "lost"]
FORCE_NONE, FORCE_HANG, FORCE_DITCH, FORCE_LAVA = 0, 1, 2, 3
CAP_CROPS, CAP_SAPLINGS, CAP_YOUNG, CAP_REN, CAP_SUSWIN, CAP_LASTREN = 64, 64, 64, 256, 512, 128
EV_CAP, LINK_CAP = 96, 512   # an arena's events and links in one step call
EV_REWARD, EV_NOVEL, EV_ACH = 0, 1, 2
# a link: decision id, agent, tick, kind (an action, or KIND_REFLEX_WATER), via
FIELDS = ("ai af gi gf bag worn made_did made_t made_kind stuck ach fails_run win_act win_fail cells drops "
          "seen hidden pen_adults pen_order pen_wild pen_rest items_seen journal links_ep builders crops ripe saplings "
          "young renfood renwheat suswin lastren mt gnext comp facts ev_i ev_f links nev nlink flags sc_f sc_i kit w fac "
          "scale_on").split()


@structref.register
class StateType(nb.types.StructRef):
    """the state of all arenas: arrays, one arena a row (a struct passed by reference: a tuple of 50 arrays passed by
    value made the kernels 7 times slower and 4 times slower to compile)"""

    def preprocess_fields(self, fields):
        return tuple((n, nb.types.unliteral(t)) for n, t in fields)


class StateRef(structref.StructRefProxy):
    pass


structref.define_proxy(StateRef, StateType, FIELDS)
# flags: noise, obstacles (x1000), parallel
FL_NOISE, FL_OBST = 0, 1


def new_state(n: int, scen_tables: dict, noise: bool = True, obstacles: float = 1.0):
    """-> (the kernels' StateRef, the same arrays by name for Python)"""
    from simbatch_rng import new_state as rng_state
    mt, gn = rng_state(n)
    sf = np.stack([scen_tables[k].astype(np.float64) for k in SC_F], 1)
    si = np.stack([scen_tables[k].astype(np.int64) for k in SC_I], 1)
    ai = np.zeros((n, NAI), np.int64)
    ai[:, A_DID_NEXT] = np.arange(n, dtype=np.int64) << 32
    arrays = dict(
        ai=ai, af=np.zeros((n, NAF)), gi=np.zeros((n, MA, NGI), np.int64), gf=np.zeros((n, MA, NGF)),
        bag=np.zeros((n, MA, NIT), np.int64), worn=np.full((n, MA, 4), -1, np.int64),
        made_did=np.full((n, MA, NIT), -1, np.int64), made_t=np.zeros((n, MA, NIT)), made_kind=np.zeros((n, MA, NIT), np.int64),
        stuck=np.zeros((n, MA, NSTUCK), np.bool_), ach=np.zeros((n, MA, NACH), np.bool_),
        fails_run=np.zeros((n, MA, A), np.int64), win_act=np.zeros((n, MA, FAIL_WINDOW), np.int64), win_fail=np.zeros((n, MA, FAIL_WINDOW), np.int64),
        cells=np.zeros((n, MA, NCELL, 4), np.int64), drops=np.zeros((n, MA, NCELL), np.int64),
        seen=np.zeros((n, 3, NORE), np.int64), hidden=np.zeros((n, 3, NORE), np.int64),
        pen_adults=np.zeros((n, N_ANIMALS), np.int64), pen_order=np.full((n, N_ANIMALS), -1, np.int64), pen_wild=np.zeros((n, 2), np.int64),
        pen_rest=np.zeros((n, N_ANIMALS)), items_seen=np.zeros((n, NIT), np.bool_),
        journal=np.zeros((n, NVIA, NEF), np.bool_), links_ep=np.zeros((n, NVIA, NEF), np.bool_),
        builders=np.zeros((n, 8, 4)), crops=np.zeros((n, CAP_CROPS, 3)), ripe=np.zeros((n, CAP_CROPS, 2), np.int64),
        saplings=np.zeros((n, CAP_SAPLINGS)), young=np.zeros((n, CAP_YOUNG, 2)), renfood=np.zeros((n, CAP_REN, 3)),
        renwheat=np.zeros((n, CAP_REN, 2), np.int64), suswin=np.zeros((n, CAP_SUSWIN, 3)), lastren=np.zeros((n, CAP_LASTREN, 2), np.int64),
        mt=mt, gnext=gn, comp=np.zeros((n, NCOMP)), facts=np.zeros((n, MA, NKEY)),
        ev_i=np.zeros((n, EV_CAP, 8), np.int64), ev_f=np.zeros((n, EV_CAP)), links=np.zeros((n, LINK_CAP, 5), np.int64),
        nev=np.zeros(n, np.int64), nlink=np.zeros(n, np.int64),
        flags=np.array([1 if noise else 0, int(round(obstacles * 1000))], np.int64),
        sc_f=sf, sc_i=si, kit=scen_tables["kit"], w=scen_tables["w"], fac=scen_tables["fac"], scale_on=scen_tables["scale_on"])
    arrays = {k: np.ascontiguousarray(arrays[k]) for k in FIELDS}
    return StateRef(*[arrays[k] for k in FIELDS]), arrays


SC_F = ("per seconds health day_sin day_cos dusk_in prep lava_risk night_m unarmed pass_kills pass_alive pass_diamonds waves_pass").split()
SC_I = ("goal item crew_goal night waves hunger xp has_chest chest iron_lo iron_hi rich moat_dug pond lava_pool sim_only hang_scen ditch_scen "
        "pasture many_animals long").split()
for _j, _n in enumerate(SC_F):
    globals()["SF_" + _n.upper()] = _j
for _j, _n in enumerate(SC_I):
    globals()["SI_" + _n.upper()] = _j

from numba.extending import register_jitable  # noqa: E402
jit = register_jitable   # the helpers: compiled into the batch calls, typed without literal specialisation


# ---- the agent's bag (sim_env.Agent) --------------------------------------------------------------------------------
@jit
def add(st, e, i, k, v):
    x = st.bag[e, i, k] + v
    st.bag[e, i, k] = x if x > 0 else 0


@jit
def tier_of(st, e, i, kind):
    """kind: IT_PICK, IT_SWORD or IT_HOE"""
    t = 0
    for k in range(NIT_BAG):
        if kind[k] and st.bag[e, i, k] > 0 and IT_TIER[k] > t:
            t = IT_TIER[k]
    return t


@jit
def best_item(st, e, i, kind):
    best, t = -1, 0
    for k in range(NIT_BAG):
        if kind[k] and st.bag[e, i, k] > 0 and IT_TIER[k] > t:
            best, t = k, IT_TIER[k]
    return best


@jit
def armor(st, e, i):
    s = 0
    for p in range(4):
        w = st.worn[e, i, p]
        if w >= 0:
            s += IT_ARMOR[w]
    return s


@jit
def worn_pts(st, e, i, slot):
    w = st.worn[e, i, slot]
    return IT_ARMOR[w] if w >= 0 else 0


@jit
def can_wear(st, e, i):
    for k in range(NIT_BAG):
        if IS_ARMOR[k] and st.bag[e, i, k] > 0 and IT_ARMOR[k] > worn_pts(st, e, i, IT_SLOT[k]):
            return True
    return False


@jit
def armor_piece(st, e, i, m):
    best, gain = -1, 0
    for p in range(4):
        k = MAT_PIECE[m, p]
        have = worn_pts(st, e, i, p)
        hb = 0
        for b in range(NIT_BAG):
            if IS_ARMOR[b] and IT_SLOT[b] == p and st.bag[e, i, b] > 0 and IT_ARMOR[b] > hb:
                hb = IT_ARMOR[b]
        if hb > have:
            have = hb
        g = IT_ARMOR[k] - have
        if g > gain and st.bag[e, i, MAT_ITEM[m]] >= PIECE_N[p]:
            best, gain = k, g
    return best


@jit
def foods(st, e, i):
    return st.bag[e, i, I_FOOD_RAW] + st.bag[e, i, I_FOOD_COOKED] + st.bag[e, i, I_BREAD]


# ---- the reward, the causes, the events --------------------------------------------------------------------------
@jit
def ticks(st, e):
    return int(st.af[e, AF_T] * 20)


@jit
def new_chain():
    return np.zeros((8, 5), np.int64)


@jit
def chain_add(ch, n, did, agent, tick, kind, via):
    """a link onto a chain of n (a missing link, did < 0, is not added: sim_env's None filtered)"""
    if did < 0 or n >= ch.shape[0]:
        return n
    ch[n, 0] = did
    ch[n, 1] = agent
    ch[n, 2] = tick
    ch[n, 3] = kind
    ch[n, 4] = via
    return n + 1


@jit
def add_hit(st, e, i, ch, n):
    """self.hit(a): the running decision as the cause of a blow"""
    return chain_add(ch, n, st.gi[e, i, GI_DID], i, int(st.gf[e, i, GF_DID_T] * 20), st.gi[e, i, GI_KIND], V_HIT)


@jit
def add_cause(st, e, i, ch, n, thing, via):
    """self.cause(a, thing, via): the decision that made the thing (via -1: item:<thing>); nothing when not made"""
    if thing < 0 or st.made_did[e, i, thing] < 0:
        return n
    return chain_add(ch, n, st.made_did[e, i, thing], i, int(st.made_t[e, i, thing] * 20), st.made_kind[e, i, thing],
                     V_ITEM0 + thing if via < 0 else via)


@jit
def emit(st, e, typ, agent, key, points, first, ch, nch):
    k = st.nev[e]
    if k >= EV_CAP or st.nlink[e] + nch > LINK_CAP:
        st.ai[e, A_OVERFLOW] += 1
        return
    s = st.nlink[e]
    for j in range(nch):
        for c in range(5):
            st.links[e, s + j, c] = ch[j, c]
    st.nlink[e] = s + nch
    st.ev_i[e, k, 0] = typ
    st.ev_i[e, k, 1] = agent
    st.ev_i[e, k, 2] = key
    st.ev_i[e, k, 3] = ticks(st, e)
    st.ev_i[e, k, 4] = first
    st.ev_i[e, k, 5] = s
    st.ev_i[e, k, 6] = nch
    st.ev_f[e, k] = points
    st.nev[e] = k + 1


@jit
def give(st, e, i, key, n, ch, nch):
    """sim_env.give: the measured amount by key (st.facts), the points w * n * factor, the event with its causes"""
    st.facts[e, i, key] += n
    s = st.ai[e, A_SCEN]
    p = st.w[s, key] * n * st.fac[s, key]
    if p == 0:
        return
    st.gf[e, i, GF_ACC] += p
    st.comp[e, KEY_COMP[key]] += p
    if nch > 0:
        emit(st, e, EV_REWARD, i, key, p, 0, ch, nch)
    if p > 0 and KEY_PROGRESS[key]:
        st.gf[e, i, GF_PROGRESS_T] = st.af[e, AF_T]


@jit
def give0(st, e, i, key, n):
    st.facts[e, i, key] += n
    s = st.ai[e, A_SCEN]
    p = st.w[s, key] * n * st.fac[s, key]
    if p == 0:
        return
    st.gf[e, i, GF_ACC] += p
    st.comp[e, KEY_COMP[key]] += p
    if p > 0 and KEY_PROGRESS[key]:
        st.gf[e, i, GF_PROGRESS_T] = st.af[e, AF_T]


@jit
def give_all(st, e, key, n, ch, nch):
    for i in range(st.ai[e, A_NAG]):
        give(st, e, i, key, n, ch, nch)


@jit
def give_all0(st, e, key, n):
    for i in range(st.ai[e, A_NAG]):
        give0(st, e, i, key, n)


@jit
def got(st, e, i, k, v, key):
    add(st, e, i, k, v)
    if key >= 0:
        give0(st, e, i, key, float(v))
    if not st.items_seen[e, k]:
        st.items_seen[e, k] = True
        give0(st, e, i, K_NEW_ITEM, 1.0)


@jit
def made(st, e, i, thing):
    st.made_did[e, i, thing] = st.gi[e, i, GI_DID]
    st.made_t[e, i, thing] = st.af[e, AF_T]
    st.made_kind[e, i, thing] = st.gi[e, i, GI_KIND]


@jit
def effect_of(st, e, i, ch, nch, effect):
    for j in range(nch):
        via = ch[j, 4]
        dup = False
        for q in range(j):
            if ch[q, 4] == via:
                dup = True
                break
        if dup or via == V_STEP:
            continue
        if st.links_ep[e, via, effect]:
            continue
        st.links_ep[e, via, effect] = True
        st.ai[e, A_NOVEL] += 1
        first = not st.journal[e, via, effect]
        if first:
            st.journal[e, via, effect] = True
            st.ai[e, A_DISCOVERIES] += 1
        emit(st, e, EV_NOVEL, i, via * NEF + effect, 0.0, 1 if first else 0, ch, nch)
        if EF_CURIOUS[effect]:
            give0(st, e, i, K_CUR_NOVEL, 1.0)


@jit
def achieve(st, e, i, k):
    if st.ach[e, i, k] or not ACH_SIM[k]:
        return
    par = ACH_PARENT[k]
    if par >= 0 and not st.ach[e, i, par]:
        return
    st.ach[e, i, k] = True
    emit(st, e, EV_ACH, i, k, 0.0, 0, new_chain(), 0)
    give0(st, e, i, K_A_NEW, 1.0)
    give0(st, e, i, K_A_DEPTH, ACH_DEPTH[k])


# ---- the near cells (sim_near.Near: the changed cells in a list) ------------------------------------------------------
@jit
def nr_find(st, e, i, x, y, z):
    n = st.gi[e, i, GI_NEAR_N]
    for j in range(n):
        if st.cells[e, i, j, 0] == x and st.cells[e, i, j, 1] == y and st.cells[e, i, j, 2] == z:
            return j
    return -1


@jit
def nr_base(st, e, i, x, y, z):
    if st.gi[e, i, GI_NEAR_GROUND] == 1:
        if x == 0 and (y == 0 or y == 1) and -4 <= z <= 4:
            return AIR
        return SOLID if st.gi[e, i, GI_NEAR_PICK] >= 1 else HARD
    return SOLID if y < 0 else AIR


@jit
def nr_at(st, e, i, x, y, z):
    j = nr_find(st, e, i, x, y, z)
    if j < 0:
        k = nr_base(st, e, i, x, y, z)
        hasdrop = False
    else:
        k = st.cells[e, i, j, 3]
        hasdrop = st.drops[e, i, j] >= 0
    if k == SOLID and st.gi[e, i, GI_NEAR_GROUND] == 1 and st.gi[e, i, GI_NEAR_PICK] < 1 and not hasdrop:
        return HARD
    return k


@jit
def nr_set(st, e, i, x, y, z, k, drop):
    """drop: an item, or -1 for none"""
    j = nr_find(st, e, i, x, y, z)
    if j < 0:
        j = st.gi[e, i, GI_NEAR_N]
        if j >= NCELL:
            st.ai[e, A_OVERFLOW] += 1
            return
        st.gi[e, i, GI_NEAR_N] = j + 1
        st.cells[e, i, j, 0] = x
        st.cells[e, i, j, 1] = y
        st.cells[e, i, j, 2] = z
        st.drops[e, i, j] = -1
    st.cells[e, i, j, 3] = k
    if drop >= 0:
        st.drops[e, i, j] = drop
    elif st.drops[e, i, j] >= 0 and k == AIR:
        st.drops[e, i, j] = -1


@jit
def nr_gives(st, e, i, x, y, z):
    j = nr_find(st, e, i, x, y, z)
    if j >= 0 and st.drops[e, i, j] >= 0:
        return st.drops[e, i, j]
    return I_COBBLE if st.gi[e, i, GI_NEAR_GROUND] == 1 else I_DIRT


@jit
def solid(k):
    return k == SOLID or k == HARD or k == BEDROCK or k == LEAVES


@jit
def nr_support(st, e, i, x, y, z):
    yy = st.gi[e, i, GI_NEAR_Y]
    for d in range(6):
        nx, ny, nz = x + NB6[d, 0], y + NB6[d, 1], z + NB6[d, 2]
        if nx == 0 and nz == 0 and (ny == yy or ny == yy + 1):
            continue
        k = nr_at(st, e, i, nx, ny, nz)
        if solid(k) and k != LEAVES:
            return True
    return False


@jit
def nr_is_mob(st, e, i, x, y, z):
    return st.gi[e, i, GI_MOB_ON] == 1 and st.gi[e, i, GI_MOB_X] == x and st.gi[e, i, GI_MOB_Y] == y and st.gi[e, i, GI_MOB_Z] == z


@jit
def nr_walls(st, e, i):
    y = st.gi[e, i, GI_NEAR_Y]
    walls, shut = 0, 0
    for s in range(4):
        d = SIDE_DIRS[s]
        feet = solid(nr_at(st, e, i, OFF[d, 0], y, OFF[d, 2]))
        head = solid(nr_at(st, e, i, OFF[d, 0], y + 1, OFF[d, 2]))
        walls += 1 if feet else 0
        shut += 1 if (feet and head) else 0
    return walls, shut


@jit
def nr_enclosed(st, e, i):
    w, s = nr_walls(st, e, i)
    return s == 4 and solid(nr_at(st, e, i, 0, st.gi[e, i, GI_NEAR_Y] + 2, 0))


@jit
def nr_can_dig(st, e, i, d):
    y = st.gi[e, i, GI_NEAR_Y]
    anyc, ok = False, True
    ox, oy, oz = OFF[d, 0], OFF[d, 1], OFF[d, 2]
    nc = 2 if oy == 0 else 1
    for c in range(nc):
        if oy == 0:
            cy = y + 1 if c == 0 else y
        else:
            cy = y + oy
        k = nr_at(st, e, i, ox, cy, oz)
        if k == SOLID or k == LEAVES:
            anyc = True
        if k == HARD or k == BEDROCK:
            ok = False
    if d == DIR_BELOW and anyc:
        if nr_at(st, e, i, 0, y - 2, 0) == LAVA:
            ok = False
        if nr_at(st, e, i, 0, y - 2, 0) == AIR and nr_at(st, e, i, 0, y - 3, 0) == AIR and nr_at(st, e, i, 0, y - 4, 0) == AIR:
            ok = False
        if nr_at(st, e, i, 1, y - 1, 0) == LAVA or nr_at(st, e, i, -1, y - 1, 0) == LAVA or nr_at(st, e, i, 0, y - 1, 1) == LAVA \
                or nr_at(st, e, i, 0, y - 1, -1) == LAVA:
            ok = False
    if d == DIR_FRONT_DOWN and solid(nr_at(st, e, i, 0, y, 1)):
        ok = False
    return anyc and ok


@jit
def nr_fall(st, e, i):
    n = 0
    while True:
        y = st.gi[e, i, GI_NEAR_Y]
        k = nr_at(st, e, i, 0, y - 1, 0)
        if solid(k) or k == LAVA or n >= 8:
            break
        st.gi[e, i, GI_NEAR_Y] = y - 1
        n += 1
    return n


@jit
def nr_dig(st, e, i, d, out):
    """Near.dig: what it gave into out (items, at most 2) and how many; -1: nothing to dig (None)"""
    if not nr_can_dig(st, e, i, d):
        return -1
    y = st.gi[e, i, GI_NEAR_Y]
    ox, oy, oz = OFF[d, 0], OFF[d, 1], OFF[d, 2]
    nc = 2 if oy == 0 else 1
    n = 0
    for c in range(nc):
        if oy == 0:
            cy = y + 1 if c == 0 else y
        else:
            cy = y + oy
        k = nr_at(st, e, i, ox, cy, oz)
        if k == SOLID or k == LEAVES:
            out[n] = nr_gives(st, e, i, ox, cy, oz)
            n += 1
            nr_set(st, e, i, ox, cy, oz, AIR, -1)
    if d == DIR_BELOW:
        nr_fall(st, e, i)
    return n


@jit
def nr_put(st, e, i, d):
    y = st.gi[e, i, GI_NEAR_Y]
    x, cy, z = OFF[d, 0], y + OFF[d, 1], OFF[d, 2]
    k = nr_at(st, e, i, x, cy, z)
    if not (k == AIR or k == WATER) or nr_is_mob(st, e, i, x, cy, z):
        return False
    if (d == DIR_LEFT or d == DIR_RIGHT or d == DIR_BACK or d == DIR_ABOVE) and not nr_support(st, e, i, x, cy, z):
        return False
    nr_set(st, e, i, x, cy, z, SOLID, I_DIRT)
    st.gi[e, i, GI_NEAR_PLACED] += 1
    return True


@jit
def nr_pillar(st, e, i):
    y = st.gi[e, i, GI_NEAR_Y]
    if not (nr_at(st, e, i, 0, y + 2, 0) == AIR and solid(nr_at(st, e, i, 0, y - 1, 0))):
        return False
    nr_set(st, e, i, 0, y, 0, SOLID, I_DIRT)
    st.gi[e, i, GI_NEAR_Y] = y + 1
    st.gi[e, i, GI_NEAR_PLACED] += 1
    return True


@jit
def nr_pit(st, e, i):
    if st.gi[e, i, GI_NEAR_GROUND] == 0:
        y = st.gi[e, i, GI_NEAR_Y]
        return -y if y < 0 else 0
    return 0


@jit
def nr_new(st, e, i, ground):
    st.gi[e, i, GI_NEAR_VALID] = 1
    st.gi[e, i, GI_NEAR_GROUND] = ground
    st.gi[e, i, GI_NEAR_PICK] = tier_of(st, e, i, IT_PICK)
    st.gi[e, i, GI_NEAR_Y] = 0
    st.gi[e, i, GI_NEAR_PLACED] = 0
    st.gi[e, i, GI_NEAR_N] = 0
    st.gi[e, i, GI_MOB_ON] = 0


@jit
def nr_facts(st, e, i, r, night_or_wave):
    y = st.gi[e, i, GI_NEAR_Y]
    for d in range(6):
        x, cy, z = OFF[d, 0], y + OFF[d, 1], OFF[d, 2]
        k = nr_at(st, e, i, x, cy, z)
        r[NB_SOLID_COL[d]] = 1 if solid(k) else 0
        r[NB_AIR_COL[d]] = 1 if k == AIR else 0
        r[NB_LIQ_COL[d]] = 1 if (k == WATER or k == LAVA) else 0
        r[NB_MOB_COL[d]] = 1 if (nr_is_mob(st, e, i, x, cy, z) or OFF[d, 1] == 0 and nr_is_mob(st, e, i, x, cy + 1, z)) else 0
    for j in range(7):
        r[NB_DIG_COL[j]] = 1 if nr_can_dig(st, e, i, DIG_DIRS[j]) else 0
    for j in range(4):
        d = PUT_DIRS[j]
        x, cy, z = OFF[d, 0], y + OFF[d, 1], OFF[d, 2]
        k = nr_at(st, e, i, x, cy, z)
        r[NB_FREE_COL[j]] = 1 if ((k == AIR or k == WATER) and not nr_is_mob(st, e, i, x, cy, z) and nr_support(st, e, i, x, cy, z)) else 0
    r[C_pillar_ok] = 1 if (nr_at(st, e, i, 0, y + 2, 0) == AIR and solid(nr_at(st, e, i, 0, y - 1, 0))) else 0
    walls, shut = nr_walls(st, e, i)
    r[C_walls_around] = walls
    enc = nr_enclosed(st, e, i)
    r[C_enclosed] = 1 if enc else 0
    r[C_shelter] = 1 if (enc and night_or_wave) else 0


# ---- the memory of failures (sim_near.Fails) -----------------------------------------------------------------------
@jit
def fails_step(st, e, i, action, ok, reason):
    n = st.gi[e, i, GI_F_WIN_N]
    h = st.gi[e, i, GI_F_WIN_HEAD]   # the oldest entry
    if n < FAIL_WINDOW:
        j = (h + n) % FAIL_WINDOW
        st.gi[e, i, GI_F_WIN_N] = n + 1
    else:
        j = h
        st.gi[e, i, GI_F_WIN_HEAD] = (h + 1) % FAIL_WINDOW
    st.win_act[e, i, j] = action
    st.win_fail[e, i, j] = 0 if ok else 1
    if not ok:
        k = st.fails_run[e, i, action] + 1
        st.fails_run[e, i, action] = k
        st.gi[e, i, GI_F_LAST_FAILED] = action
        st.gi[e, i, GI_F_GROUP] = REASON_GROUP[reason]
        return (float(k - 1) if k >= 2 else 0.0), 0.0
    st.fails_run[e, i, action] = 0
    lf = st.gi[e, i, GI_F_LAST_FAILED]
    other = lf >= 0 and lf != action
    st.gi[e, i, GI_F_LAST_FAILED] = -1
    return 0.0, (1.0 if other else 0.0)


@jit
def fails_facts(st, e, i, r):
    for a in range(A):
        k = st.fails_run[e, i, a]
        if k > 0:
            r[FAIL_RUN_COL[a]] = k
    n = st.gi[e, i, GI_F_WIN_N]
    h = st.gi[e, i, GI_F_WIN_HEAD]
    for j in range(n):
        jj = (h + j) % FAIL_WINDOW
        if st.win_fail[e, i, jj]:
            a = st.win_act[e, i, jj]
            c = 0
            for q in range(n):
                qq = (h + q) % FAIL_WINDOW
                if st.win_act[e, i, qq] == a and st.win_fail[e, i, qq]:
                    c += 1
            r[FAIL_RECENT_COL[a]] = c / FAIL_WINDOW
    g = st.gi[e, i, GI_F_GROUP]
    if g >= 0 and st.gi[e, i, GI_F_LAST_FAILED] >= 0:
        r[LASTFAIL_COL[g]] = 1


# ---- the world's small questions ----------------------------------------------------------------------------------
@jit
def crew_n(st, e, k):
    s = 0
    for i in range(st.ai[e, A_NAG]):
        if st.gi[e, i, GI_ALIVE]:
            s += st.bag[e, i, k]
    return s


@jit
def crew_food(st, e):
    return crew_n(st, e, I_FOOD_RAW) + crew_n(st, e, I_FOOD_COOKED) + crew_n(st, e, I_BREAD)


@jit
def need(st, e):
    s = st.ai[e, A_SCEN]
    v = st.sc_f[s, SF_PER] * (1 if st.sc_i[s, SI_CREW_GOAL] else st.ai[e, A_NAG])
    return v if v > 1 else 1.0


@jit
def pairs(st, e, i):
    """the pen's pairs that may breed now (i < 0: without the bag's feed)"""
    n = 0
    t = st.af[e, AF_T]
    for k in range(N_ANIMALS):
        if st.pen_order[e, k] >= 0 and st.pen_adults[e, k] >= 2 and st.pen_rest[e, k] <= t:
            n += 1
    if i >= 0 and st.bag[e, i, I_WHEAT] + st.bag[e, i, I_SEEDS] < 2:
        return 0
    return n


@jit
def stock(st, e):
    v = 0.0
    for j in range(NSTOCK):
        if STOCK_IT[j] < 0:
            have = 0
            for f in range(3):
                have += int(FOOD_PTS[f]) * crew_n(st, e, FOODS[f])
        else:
            have = crew_n(st, e, STOCK_IT[j])
        cap = STOCK_CAP[j]
        v += min(have, cap) / cap * STOCK_VAL[j]
    return v


@jit
def sources(st, e):
    planted = st.ai[e, A_PLANTED]
    adults = 0
    for k in range(N_ANIMALS):
        adults += st.pen_adults[e, k]
    trees = st.ai[e, A_N_SAPLINGS] + st.ai[e, A_GROWN]
    return min(planted, 16) / 16 + min(adults, 2) / 2 + min(trees, 4) / 4


@jit
def ledger(st, e, kind, amount):
    """kind: 0 food_renew, 1 food_eaten, 2 wood_renew, 3 wood_used"""
    if amount > 0:
        st.af[e, AF_SUS_FR + kind] += amount
        n = st.ai[e, A_N_SUSWIN]
        if n >= CAP_SUSWIN:
            st.ai[e, A_OVERFLOW] += 1
            return
        j = (st.ai[e, A_SUSWIN_HEAD] + n) % CAP_SUSWIN
        st.suswin[e, j, 0] = st.af[e, AF_T]
        st.suswin[e, j, 1] = kind
        st.suswin[e, j, 2] = amount
        st.ai[e, A_N_SUSWIN] = n + 1


@jit
def recent(st, e, out):
    t = st.af[e, AF_T]
    while st.ai[e, A_N_SUSWIN] > 0 and st.suswin[e, st.ai[e, A_SUSWIN_HEAD], 0] < t - WINDOW_S:
        st.ai[e, A_SUSWIN_HEAD] = (st.ai[e, A_SUSWIN_HEAD] + 1) % CAP_SUSWIN
        st.ai[e, A_N_SUSWIN] -= 1
    out[0] = 0.0
    out[1] = 0.0
    out[2] = 0.0
    out[3] = 0.0
    h = st.ai[e, A_SUSWIN_HEAD]
    for j in range(st.ai[e, A_N_SUSWIN]):
        jj = (h + j) % CAP_SUSWIN
        out[int(st.suswin[e, jj, 1])] += st.suswin[e, jj, 2]


@jit
def progress(st, e):
    s = st.ai[e, A_SCEN]
    n = st.ai[e, A_NAG]
    g = st.sc_i[s, SI_GOAL]
    nd = need(st, e)
    per = st.sc_f[s, SF_PER]
    if g == G_ITEMS:
        return min(1.0, crew_n(st, e, st.sc_i[s, SI_ITEM]) / nd)
    if g == G_FOOD:
        return min(1.0, crew_food(st, e) / nd)
    if g == G_FORTIFY:
        return 0.5 * st.ai[e, A_WALL] / RING + 0.5 * min(1.0, st.ai[e, A_MOAT] / (0.9 * MOAT))
    if g == G_FARM:
        return min(1.0, st.ai[e, A_BAKED] / nd)
    if g == G_PASTURE:
        return min(1.0, st.ai[e, A_BRED] / nd)
    if g == G_NONE:
        return min(1.0, st.ai[e, A_CLEARED] / 10)
    tot = 0.0
    for i in range(n):
        if g == G_ARMOR:
            v = armor(st, e, i) / per
        elif g == G_BOW:
            v = 0.5 * (1 if st.bag[e, i, I_BOW] > 0 else 0) + 0.5 * min(1.0, st.bag[e, i, I_ARROW] / per)
        elif g == G_TRIP:
            v = st.gi[e, i, GI_TRIPS] / per
        elif g == G_ENCHANT:
            v = st.gi[e, i, GI_ENCHANTED] / per
        else:   # achievements: per agent besides the root
            c = 0
            for k in range(NACH):
                if st.ach[e, i, k]:
                    c += 1
            v = (c - 1) / per
        tot += min(1.0, v)
    return tot / n


@jit
def crew_ach(st, e):
    c = 0
    for k in range(NACH):
        if k == H_OPEN_INV:
            continue
        for i in range(st.ai[e, A_NAG]):
            if st.ach[e, i, k]:
                c += 1
                break
    return c


@jit
def goal_met(st, e):
    s = st.ai[e, A_SCEN]
    g = st.sc_i[s, SI_GOAL]
    if g == G_NONE:
        return False
    if g == G_FORTIFY:
        return st.ai[e, A_WALL] >= RING and st.ai[e, A_MOAT] >= 0.9 * MOAT
    return progress(st, e) >= 1.0


@jit
def success(st, e):
    s = st.ai[e, A_SCEN]
    n = st.ai[e, A_NAG]
    if st.sc_f[s, SF_PASS_KILLS] >= 0:
        return st.ai[e, A_KILLS] >= st.sc_f[s, SF_PASS_KILLS] * n
    if st.sc_f[s, SF_PASS_ALIVE] >= 0:
        if st.ai[e, A_REASON] == DONE_NOOP or not (st.af[e, AF_T] >= st.sc_f[s, SF_SECONDS] - 1):
            return False
        for i in range(n):
            if not (st.gi[e, i, GI_ALIVE] and st.gf[e, i, GF_HEALTH] >= st.sc_f[s, SF_PASS_ALIVE]):
                return False
        return True
    if st.sc_f[s, SF_PASS_DIAMONDS] >= 0:
        return crew_n(st, e, I_DIAMOND) >= st.sc_f[s, SF_PASS_DIAMONDS] * n
    if st.sc_i[s, SI_GOAL] == G_NONE:
        return st.ai[e, A_CLEARED] >= st.sc_f[s, SF_WAVES_PASS]
    return goal_met(st, e)


@jit
def night_or_wave(st, e):
    return st.sc_i[st.ai[e, A_SCEN], SI_NIGHT] == 1 or st.ai[e, A_PHASE] == PH_WAVE


@jit
def sheltered(st, e, i):
    return st.gi[e, i, GI_NEAR_VALID] == 1 and st.gi[e, i, GI_DEPTH] == 0 and nr_enclosed(st, e, i)


@jit
def near(st, e, i):
    """sim_env.near: the cells round it, made when first asked (a draw for a block in front on the plain ground)"""
    if st.gi[e, i, GI_NEAR_VALID]:
        st.gi[e, i, GI_NEAR_PICK] = tier_of(st, e, i, IT_PICK)
        return
    depth = st.gi[e, i, GI_DEPTH]
    nr_new(st, e, i, 1 if depth else 0)
    if depth:
        if st.gi[e, i, GI_LAVA_FRONT]:
            nr_set(st, e, i, 0, -1, 1, LAVA, -1)
            nr_set(st, e, i, 0, -1, 2, LAVA, -1)
    elif st.ai[e, A_HANG] and st.ai[e, A_HANG_CELLS] != 0 and st.gf[e, i, GF_DIST] < 6:
        for y in range(62):
            if (st.ai[e, A_HANG_CELLS] >> y) & 1:
                nr_set(st, e, i, 0, y, 1, SOLID, I_LOG)
    elif st.gi[e, i, GI_DITCH]:
        nr_set(st, e, i, 0, -1, 1, AIR, -1)
        nr_set(st, e, i, 0, -2, 1, AIR, -1)
    elif rnd(st.mt, e) < 0.3:
        nr_set(st, e, i, 0, 0, 1, SOLID, I_DIRT)


@jit
def dd(st, e, i, n, far):
    """raw's distance: the nearest of n seen (a draw), or far"""
    if n <= 0:
        return far
    v = st.gf[e, i, GF_DIST] * 0.3 + 2 + 12 / (1 + n) + uniform(st.mt, e, -1.0, 3.0)
    return v if v > 1.0 else 1.0


@jit
def ach_available(st, e, i):
    c = 0
    for k in range(NACH):
        if st.ach[e, i, k] or not ACH_SIM[k]:
            continue
        p = ACH_PARENT[k]
        if p < 0 or st.ach[e, i, p]:
            c += 1
    return c


# ---- the raw facts (sim_env.raw, raw31) -----------------------------------------------------------------------------
@jit
def raw(st, e, i, r):
    """r: a row of R_W (set from ROW0 here); the draws as sim_env.raw makes them, in its order"""
    mt = st.mt
    for c in range(R_W):
        r[c] = ROW0[c]
    s = st.ai[e, A_SCEN]
    nag = st.ai[e, A_NAG]
    t = st.af[e, AF_T]
    dist = st.gf[e, i, GF_DIST]
    depth = st.gi[e, i, GI_DEPTH]
    r[SCEN_COL[s]] = 1
    for j in range(SCALE_COL.shape[0]):
        r[SCALE_COL[j]] = st.scale_on[s, j]
    r[AGENT_COL[i if i < MA - 1 else MA - 1]] = 1
    r[C_n_agents] = nag
    r[C_health] = st.gf[e, i, GF_HEALTH]
    r[C_max_health] = 20
    r[C_low_health] = LOW
    r[C_armor] = armor(st, e, i)
    held = st.gi[e, i, GI_HELD]
    r[C_held_tier] = held
    r[C_pick_tier] = tier_of(st, e, i, IT_PICK)
    sw = tier_of(st, e, i, IT_SWORD)
    r[C_weapon_tier] = sw
    r[C_sword_tier] = sw
    r[C_hoe_tier] = tier_of(st, e, i, IT_HOE)
    r[C_hoes] = st.bag[e, i, I_WOODEN_HOE] + st.bag[e, i, I_STONE_HOE]
    deep = depth > 0
    r[C_at_home] = 1 if (dist < 4 and not deep) else 0
    hunger = st.sc_i[s, SI_HUNGER]
    r[C_hunger_on] = hunger
    r[C_food_level] = st.gf[e, i, GF_FOOD] if hunger else 20
    r[C_saturation] = st.gf[e, i, GF_SAT] if hunger else 5
    r[C_xp_level] = st.gi[e, i, GI_XP]
    r[C_xp_progress] = st.gf[e, i, GF_XP_PTS] / 17.0
    r[C_day_sin] = st.sc_f[s, SF_DAY_SIN]
    r[C_day_cos] = st.sc_f[s, SF_DAY_COS]
    r[C_night] = st.sc_i[s, SI_NIGHT]
    r[C_dusk_in] = st.sc_f[s, SF_DUSK_IN]
    tot, stack, arm = 0, 0, 0
    for k in range(NIT_BAG):
        v = st.bag[e, i, k]
        if v <= 0:
            continue
        if IS_ARMOR[k]:
            arm += v
        else:
            r[BAG_COL[k]] = v
        tot += v
        if not IT_TOOL[k]:
            stack += v
    r[C_bag_armor] = arm
    r[C_bag_total] = tot
    r[C_bag_stackables] = stack
    r[C_food_items] = foods(st, e, i)
    lure = st.bag[e, i, I_WHEAT] + st.bag[e, i, I_SEEDS]
    r[C_lure_food] = lure
    r[C_place_spot] = 1
    wild = st.pen_wild[e, 0] + st.pen_wild[e, 1]
    r[C_lure_targets] = (st.ai[e, A_ANIMALS] + wild) if (lure > 0 and not deep) else 0
    r[C_fuel] = st.bag[e, i, I_COAL] + st.bag[e, i, I_PLANKS] + st.bag[e, i, I_LOG]
    wb = max(st.bag[e, i, I_COBBLE], max(st.bag[e, i, I_PLANKS], st.bag[e, i, I_DIRT]))
    r[C_wall_blocks] = wb
    r[C_underground] = 1 if deep else 0
    r[C_depth_level] = depth
    r[C_at_horizon] = 1 if deep else 0
    r[C_y_rel] = 0 if depth == 0 else (-24 if depth == 1 else -52)
    ml = st.ai[e, A_MINE_LEVELS]
    r[C_mine_known] = 1 if ml > 0 else 0
    r[C_mine_level] = ml
    r[C_mine_dist] = (abs(dist - st.af[e, AF_MINE_D]) + 1) if (ml and not deep) else (2 if deep else 64)
    r[C_tunnels] = st.gi[e, i, GI_TUN1] if depth == 1 else (st.gi[e, i, GI_TUN2] if depth == 2 else st.gi[e, i, GI_TUN0])
    r[C_trip_ores] = st.gi[e, i, GI_TRIP_ORES]
    if not deep:
        trees = st.ai[e, A_TREES]
        r[C_trees_seen] = trees
        r[C_tree_dist] = dd(st, e, i, trees, 48.0)
        r[C_trees_free] = trees
        r[C_stone_seen] = st.ai[e, A_STONE_SEEN]
        r[C_stone_dist] = dd(st, e, i, st.ai[e, A_STONE_SEEN], 32.0)
        r[C_coal_seen] = st.ai[e, A_COAL]
        r[C_coal_dist] = dd(st, e, i, st.ai[e, A_COAL], 48.0)
        r[C_coal_minable] = st.ai[e, A_COAL]
        r[C_iron_seen] = st.ai[e, A_IRON]
        r[C_iron_dist] = dd(st, e, i, st.ai[e, A_IRON], 48.0)
        r[C_iron_minable] = st.ai[e, A_IRON]
        r[C_gravel_seen] = st.ai[e, A_GRAVEL]
        r[C_gravel_minable] = st.ai[e, A_GRAVEL]
        r[C_animals_seen] = st.ai[e, A_ANIMALS]
        r[C_animal_dist] = dd(st, e, i, st.ai[e, A_ANIMALS], 24.0)
        hid = st.ai[e, A_TREES_HIDDEN] + st.ai[e, A_ANIMALS_HIDDEN] + st.ai[e, A_COAL_HIDDEN] + st.ai[e, A_IRON_HIDDEN]
        r[C_frontier] = 1 if hid > 0 else 0
        r[C_grass_seen] = st.ai[e, A_GRASS]
        r[C_leaves_seen] = st.ai[e, A_LEAVES]
        r[C_cane_seen] = st.ai[e, A_CANE]
    else:
        r[C_tree_dist] = 48
        r[C_animal_dist] = 24
        r[C_stone_seen] = 32
        r[C_stone_dist] = 2
        for j in range(DEEP_SEEN.shape[0]):
            o = DEEP_SEEN[j]
            v = st.seen[e, depth, o]
            r[ORE_SEEN_COL[o]] = v
            r[ORE_MINABLE_COL[o]] = v
            r[ORE_DIST_COL[o]] = dd(st, e, i, v, 48.0)
    tb = st.ai[e, A_TABLE]
    r[C_table_known] = tb
    r[C_table_dist] = (abs(dist - st.af[e, AF_TABLE_D]) + 1) if (tb and not deep) else 24
    fu = st.ai[e, A_FURNACE]
    r[C_furnace_known] = fu
    r[C_furnace_dist] = (abs(dist - st.af[e, AF_FURNACE_D]) + 1) if (fu and not deep) else 24
    ch = st.ai[e, A_CHEST]
    r[C_chest_known] = ch
    r[C_chest_dist] = (dist + 1) if (ch and not deep) else 48
    r[C_chest_food] = st.ai[e, A_CHEST_FOOD]
    r[C_base_known] = 1
    r[C_base_dist] = dist + 30 * depth
    et = st.ai[e, A_ENCH_TABLE]
    r[C_ench_table_known] = et
    r[C_ench_table_dist] = (dist + 2) if (et and not deep) else 24
    sh = st.ai[e, A_SHELVES]
    r[C_bookshelves] = sh
    r[C_shelf_spots] = (15 - sh) if et else 0
    r[C_shelves_total] = sh + st.bag[e, i, I_SHELF]
    en = 0
    for k in range(NIT_BAG):
        if st.bag[e, i, k] > 0 and (IT_SWORD[k] or IT_PICK[k] or IS_ARMOR[k] or k == I_BOW):
            en += st.bag[e, i, k]
    nworn = 0
    for p in range(4):
        if st.worn[e, i, p] >= 0:
            nworn += 1
    v = en + nworn - st.gi[e, i, GI_ENCHANTED]
    r[C_enchantable] = v if v > 0 else 0
    plots, planted = st.ai[e, A_PLOTS], st.ai[e, A_PLANTED]
    r[C_farm_plots] = plots
    r[C_farm_planted] = planted
    r[C_farm_ripe] = st.ai[e, A_N_RIPE]
    r[C_farm_empty] = plots - planted
    r[C_till_spots] = 16 - plots
    nsap = st.ai[e, A_N_SAPLINGS]
    r[C_saplings_planted] = nsap
    r[C_trees_grown] = st.ai[e, A_GROWN]
    v = st.ai[e, A_SAPLING_ROOM] - nsap - st.ai[e, A_GROWN]
    r[C_sapling_spots] = v if v > 0 else 0
    adults = 0
    for k in range(N_ANIMALS):
        adults += st.pen_adults[e, k]
    pen = st.ai[e, A_PEN]
    r[C_pen_built] = pen
    r[C_pen_animals] = adults + st.ai[e, A_N_YOUNG]
    r[C_pen_adults] = adults
    r[C_pen_babies] = st.ai[e, A_N_YOUNG]
    r[C_pen_pairs] = pairs(st, e, i)
    r[C_pen_dist] = (abs(dist - 6) + 1) if pen else 32
    r[C_door_placed] = st.ai[e, A_DOOR]
    r[C_gate_open] = st.ai[e, A_GATE_OPEN]
    ph = st.ai[e, A_PHASE]
    r[C_waves_active] = 1 if ph != PH_OFF else 0
    r[C_wave_on] = 1 if ph == PH_WAVE else 0
    r[C_phase_prep] = 1 if ph == PH_PREP else 0
    r[C_phase_break] = 1 if ph == PH_BREAK else 0
    r[C_wave_num] = st.ai[e, A_WAVE_NUM]
    r[C_next_wave_in] = max(0.0, st.af[e, AF_PHASE_UNTIL] - t) if (ph == PH_PREP or ph == PH_BREAK) else 0
    en_ = st.af[e, AF_ENEMIES] if ph == PH_WAVE else 0.0
    inside = st.af[e, AF_INSIDE]
    wall = st.ai[e, A_WALL]
    ring = st.ai[e, A_RING]
    r[C_enemies] = en_
    r[C_enemies_visible] = (en_ + inside) if not deep else 0
    r[C_enemy_dist] = (8 + 10 * (wall / RING)) if en_ > 0 else 32
    if en_ > 0:
        r[C_enemy_dx] = uniform(mt, e, -12.0, 12.0)
        r[C_enemy_dz] = uniform(mt, e, -12.0, 12.0)
    else:
        r[C_enemy_dx] = 0
        r[C_enemy_dz] = 0
    r[C_enemies_near] = min(en_, en_ * ((1 - wall / RING) if ring else 1)) + inside
    r[C_ring_planned] = ring
    if ring:
        r[C_wall_frac] = wall / RING
        r[C_holes] = RING - wall
        r[C_inside_wall] = 1 if (dist < 8 and not deep) else 0
        v = POSTS - st.ai[e, A_POSTS_TAKEN]
        r[C_posts_free] = v if v > 0 else 0
    r[C_moat_frac] = st.ai[e, A_MOAT] / MOAT
    r[C_torches_placed] = st.ai[e, A_TORCHES]
    post = st.gi[e, i, GI_POST]
    r[C_has_post] = post
    r[C_post_dist] = abs(dist - 7) if post else 0
    if st.bag[e, i, I_TORCH]:
        v = 12 - st.ai[e, A_TORCHES]
        r[C_light_spots] = v if v > 0 else 0
    else:
        r[C_light_spots] = 0
    r[C_can_equip] = 1 if (sw > held or can_wear(st, e, i)) else 0
    r[C_mobs_inside] = inside
    r[C_breaches] = st.ai[e, A_WAVE_BREACHES]
    r[C_mob_seconds] = st.af[e, AF_WAVE_MOB_S]
    r[C_clean_waves] = st.ai[e, A_CLEAN]
    rec = np.zeros(4)
    recent(st, e, rec)
    r[C_food_renew] = rec[0]
    r[C_food_eaten] = rec[1]
    r[C_wood_renew] = rec[2]
    r[C_wood_used] = rec[3]
    r[C_sources] = sources(st, e)
    r[C_stock_value] = stock(st, e)
    r[C_stall] = min(STALL_S, t - st.gf[e, i, GF_PROGRESS_T])
    r[C_novel_count] = st.ai[e, A_NOVEL]
    r[C_buckets] = st.bag[e, i, I_BUCKET] + st.bag[e, i, I_WATER_B] + st.bag[e, i, I_LAVA_B]
    r[C_slot_block] = wb
    e_vis = en_ + inside
    liq = st.ai[e, A_MOAT_LIQ]
    for tg in range(6):
        if tg <= TG_FRONT_DOWN:
            there = 1
            dv = (1.1 if tg == TG_FRONT else (0.3 if tg == TG_BELOW else 1.5)) + uniform(mt, e, -0.4, 0.4)
        elif tg == TG_HOLE:
            there = 1 if (ring and wall < RING and not deep) else 0
            dv = abs(dist - 8) + 1
        elif tg == TG_MOAT:
            there = 1 if (st.ai[e, A_MOAT] > 0 and not deep) else 0
            dv = abs(dist - 10) + 1
        else:
            there = 1 if (e_vis > 0 and not deep) else 0
            dv = 8 + 10 * (wall / RING)
        r[TGT_THERE_COL[tg]] = there
        r[TGT_DIST_COL[tg]] = dv if there else 16
        lq = liq if (tg == TG_MOAT and liq > 0) else 0
        free = 0.65 if tg == TG_FRONT else (0.2 if tg == TG_FRONT_DOWN else 1.0)
        r[TGT_FREE_COL[tg]] = 1 if (there and lq == 0 and rnd(mt, e) < free) else 0
        r[TGT_WATER_COL[tg]] = 1 if lq == 1 else 0
        r[TGT_LAVA_COL[tg]] = 1 if lq == 2 else 0
    if not deep and dist < 12 and st.ai[e, A_POND]:
        r[C_tgt_front_water_src] = 1
    if (not deep and dist < 12 and st.ai[e, A_LAVA_POOL]) or (depth == 2 and st.seen[e, 2, O_LAVA] > 0):
        r[C_tgt_front_lava_src] = 1
    r[C_tgt_front_door] = 1 if (st.ai[e, A_DOOR] and 6.5 < dist < 9.5) else 0
    na = 0
    for k in range(NACH):
        if st.ach[e, i, k]:
            r[ACH_COL[k]] = 1
            na += 1
    r[C_ach_count] = na
    r[C_ach_available] = ach_available(st, e, i)
    tgt = st.ai[e, A_TARGET]
    if tgt >= 0:
        r[ACH_TARGET_COL[tgt]] = 1
    no, hsum, hmin, nlow, adist = 0, 0.0, 20.0, 0, 32.0
    first = True
    for b in range(nag):
        if b == i or not st.gi[e, b, GI_ALIVE]:
            continue
        no += 1
        hb = st.gf[e, b, GF_HEALTH]
        hsum += hb
        if first or hb < hmin:
            hmin = hb
        if hb <= LOW:
            nlow += 1
        dv = abs(dist - st.gf[e, b, GF_DIST]) + 2 + 30 * abs(depth - st.gi[e, b, GI_DEPTH])
        if first or dv < adist:
            adist = dv
        first = False
    r[C_allies_alive] = no
    r[C_allies_health_mean] = hsum / no if no else 20
    r[C_allies_health_min] = hmin if no else 20
    r[C_allies_low] = nlow
    r[C_ally_dist] = adist if no else 32
    r[LAST_OUT_COL[st.gi[e, i, GI_OUTCOME]]] = 1
    r[C_repeated] = st.gi[e, i, GI_REPEATED]
    r[C_last_seconds] = st.gf[e, i, GF_LAST_S]
    if st.gi[e, i, GI_LAST] >= 0:
        r[LAST_ACTION_COL[st.gi[e, i, GI_LAST]]] = 1
    r[C_time_frac] = t / st.sc_f[s, SF_SECONDS]
    r[C_goal_progress] = progress(st, e)
    for q in range(NREC):
        ok = True
        for j in range(4):
            it = REC_NEED_IT[q, j]
            if it >= 0 and st.bag[e, i, it] < REC_NEED_N[q, j]:
                ok = False
        r[CRAFTABLE_COL[q]] = 1 if ok else 0
        r[SMALL_COL[q]] = 1 if (ok and REC_SMALL[q]) else 0
    for m in range(4):
        r[ARMOR_CRAFTABLE_COL[m]] = 1 if armor_piece(st, e, i, m) >= 0 else 0
    raw31(st, e, i, r)


@jit
def raw31(st, e, i, r):
    mt = st.mt
    near(st, e, i)
    st.gi[e, i, GI_MOB_ON] = 0
    s = st.ai[e, A_SCEN]
    nm = st.sc_f[s, SF_NIGHT_M]
    en = (st.af[e, AF_ENEMIES] if st.ai[e, A_PHASE] == PH_WAVE else 0.0) + ((nm + st.af[e, AF_T] / 60) if nm else 0.0)
    depth = st.gi[e, i, GI_DEPTH]
    dist = st.gf[e, i, GF_DIST]
    out = not (st.ai[e, A_RING] and dist < 8) and depth == 0
    if en > 0 and out and rnd(mt, e) < min(0.6, 0.1 * en):
        d = SIDE_DIRS[below(mt, e, 4)]
        y = st.gi[e, i, GI_NEAR_Y]
        x, cy, z = OFF[d, 0], y + OFF[d, 1], OFF[d, 2]
        if nr_at(st, e, i, x, cy, z) == AIR:
            st.gi[e, i, GI_MOB_ON] = 1
            st.gi[e, i, GI_MOB_X] = x
            st.gi[e, i, GI_MOB_Y] = cy
            st.gi[e, i, GI_MOB_Z] = z
    nr_facts(st, e, i, r, night_or_wave(st, e))
    r[C_trees_reachable] = r[C_trees_free]
    if st.ai[e, A_HANG] and st.ai[e, A_HANG_CELLS] != 0 and dist < 12 and depth == 0:
        r[C_trees_seen] = r[C_trees_seen] + 1
        if r[C_trees_free] <= 0:
            r[C_tree_dist] = 1.5
    y = st.gi[e, i, GI_NEAR_Y]
    for tg in range(3):
        d = DIR_FRONT if tg == TG_FRONT else (DIR_BELOW if tg == TG_BELOW else DIR_FRONT_DOWN)
        k = nr_at(st, e, i, OFF[d, 0], y + OFF[d, 1], OFF[d, 2])
        r[TGT_FREE_COL[tg]] = 1 if k == AIR else 0
        r[TGT_LAVA_COL[tg]] = 1 if k == LAVA else 0
        if k == LAVA:
            r[TGT_LSRC_COL[tg]] = 1
    fails_facts(st, e, i, r)


# ---- the observation and the mask from the raw facts (spec.vector, mask_rules.holds) ----------------------------------
@jit
def obs_of(r, out):
    for f in range(D):
        x = r[f]
        k = FEAT_KIND[f]
        if k == 0:
            out[f] = 1.0 if x > 0.5 else 0.0
        elif k == 1:
            out[f] = math.log1p(x if x > 0.0 else 0.0) / FEAT_LOG1P[f]
        else:
            v = x / FEAT_SCALE[f]
            out[f] = min(2.0, max(-1.0, v))


@jit
def cmp(op, a, b):
    if op == 0:
        return a > b
    if op == 1:
        return a >= b
    if op == 2:
        return a < b
    if op == 3:
        return a <= b
    if op == 4:
        return a == b
    return a != b


@jit
def allowed(r, act):
    if act == 0:
        return True
    stack = np.empty(64, np.bool_)
    sp = 0
    s0 = MP_START[act]
    for j in range(s0, s0 + MP_LEN[act]):
        c = MP_CODE[j]
        if c == 0:
            b = r[MP_Z[j]] if MP_Z[j] >= 0 else MP_VAL[j]
            stack[sp] = cmp(MP_X[j], r[MP_Y[j]], b)
            sp += 1
        else:
            n = MP_X[j]
            v = c == 1
            for q in range(sp - n, sp):
                if c == 1:
                    v = v and stack[q]
                else:
                    v = v or stack[q]
            sp -= n
            stack[sp] = v
            sp += 1
    return stack[0]


@jit
def mask_of(r, out):
    for a in range(A):
        out[a] = allowed(r, a)


# ---- skills (sim_env.begin, blocked31, finish, moved31, ended) -------------------------------------------------------
@jit
def best_block(st, e, i):
    """max(("cobblestone", "planks", "dirt"), key=a.n): the first of the most"""
    m, n = I_COBBLE, st.bag[e, i, I_COBBLE]
    if st.bag[e, i, I_PLANKS] > n:
        m, n = I_PLANKS, st.bag[e, i, I_PLANKS]
    if st.bag[e, i, I_DIRT] > n:
        m = I_DIRT
    return m


@jit
def py_round(x):
    return int(np.rint(x))


@jit
def begin(st, e, i, act, row):
    mt = st.mt
    t = st.af[e, AF_T]
    st.gi[e, i, GI_DID] = st.ai[e, A_DID_NEXT]
    st.ai[e, A_DID_NEXT] += 1
    st.gi[e, i, GI_KIND] = act
    st.gf[e, i, GF_DID_T] = t
    raw(st, e, i, row)
    if not allowed(row, act):
        st.gi[e, i, GI_PEND] = 1
        st.gi[e, i, GI_PEND_ACT] = act
        st.gi[e, i, GI_PEND_OK] = 0
        st.gi[e, i, GI_PEND_REASON] = R_MASKED
        st.gf[e, i, GF_PEND_BEGAN] = t
        st.gf[e, i, GF_FREE_AT] = t
        return
    kind = ACT_KIND[act]
    ok, reason = True, R_OK
    if st.flags[FL_NOISE] == 0:
        pass
    elif st.stuck[e, i, kind] and rnd(mt, e) < ACT_STICKY[act]:
        ok, reason = False, ACT_STICKY_REASON[act]
    elif rnd(mt, e) < ACT_FAIL[act]:
        ok, reason = False, ACT_FRESH_REASON[act]
        if rnd(mt, e) < ACT_STICKY[act]:
            st.stuck[e, i, kind] = True
    if ok:
        ok, reason = blocked31(st, e, i, act)
    far = st.gf[e, i, GF_DIST]
    if ok:
        dur = ACT_DUR_OK[act] * uniform(mt, e, 0.7, 1.3)
    else:
        dur = ACT_DUR_FAIL[act] * uniform(mt, e, 0.6, 1.4)
    dur = dur if dur > 0.5 else 0.5
    if ok:
        dk = ACT_DUR[act]
        depth = st.gi[e, i, GI_DEPTH]
        if dk == DUR_FRONTIER:
            dur = 8 + uniform(mt, e, 0.0, 10.0)
        elif dk == DUR_HOME:
            dur = 2 + far * 0.4 + LEVEL_S * 0.3 * depth
        elif dk == DUR_CHOP:
            dur += row[C_tree_dist] * 0.2
        elif dk == DUR_MINE:
            pt = int(row[C_pick_tier])
            sp = 1.0 if pt == 1 else (1.5 if pt == 2 else (2.0 if pt == 3 else (2.5 if pt == 4 else 1.0)))
            dur = dur / sp
        elif dk == DUR_CRAFT_TABLE:
            dur = CRAFT_SMALL_DUR + (0 if (st.ai[e, A_TABLE] and abs(far - st.af[e, AF_TABLE_D]) < 6) else ACT_DUR_OK[act])
        elif dk == DUR_WALL:
            dur = 3 + min(24, min(RING - st.ai[e, A_WALL], int(row[C_wall_blocks]))) * 1.9 + far * 0.3
        elif dk == DUR_REPAIR:
            dur = 3 + min(min(RING - st.ai[e, A_WALL], int(row[C_wall_blocks])), 12) * 3.5
        elif dk == DUR_HOLD:
            dur = 60.0
            st.gi[e, i, GI_HOLDING] = 1
        elif dk == DUR_WAIT:
            dur = 5.0
        elif dk == DUR_DESCEND:
            dur = LEVEL_S * (1.0 if st.ai[e, A_MINE_LEVELS] <= depth else 0.3) + (abs(far - st.af[e, AF_MINE_D]) * 0.4 if depth == 0 else 0)
        elif dk == DUR_ASCEND:
            dur = LEVEL_S * 0.3 * depth
        elif dk == DUR_TUNNEL:
            dur = 25 + uniform(mt, e, 0.0, 15.0)
        elif dk == DUR_SHOOT:
            dur = 10.0
        elif dk == DUR_FARM:
            dur = 6 + uniform(mt, e, 0.0, 8.0)
        elif dk == DUR_GATHER:
            dur = 10 + uniform(mt, e, 0.0, 12.0)
        elif dk == DUR_LURE:
            dur = 20 + uniform(mt, e, 0.0, 20.0)
        elif dk == DUR_SHORT:
            dur = 3 + uniform(mt, e, 0.0, 3.0)
        elif dk == DUR_PEN:
            dur = 30 + uniform(mt, e, 0.0, 15.0)
    st.gi[e, i, GI_PEND] = 1
    st.gi[e, i, GI_PEND_ACT] = act
    st.gi[e, i, GI_PEND_OK] = 1 if ok else 0
    st.gi[e, i, GI_PEND_REASON] = reason
    st.gf[e, i, GF_PEND_BEGAN] = t
    st.gf[e, i, GF_FREE_AT] = t + dur


@jit
def blocked31(st, e, i, act):
    if st.gi[e, i, GI_NEAR_VALID] and not ACT_LOCAL[act] and nr_pit(st, e, i) >= 2:
        st.gi[e, i, GI_PIT_FAILS] += 1
        st.ai[e, A_OB_PIT_STUCK] += 1
        if st.gi[e, i, GI_PIT_FAILS] >= 3:
            st.gi[e, i, GI_PIT_FAILS] = 0
            st.gi[e, i, GI_NEAR_VALID] = 0
        return False, RS_NO_PATH
    if st.gi[e, i, GI_DITCH] and ACT_GOHOME[act]:
        return False, RS_NO_PATH
    if st.gi[e, i, GI_LAVA_FRONT] and ACT_MINE_ORE[act]:
        return False, RS_NO_PATH
    return True, R_OK


@jit
def finish(st, e, i):
    act = st.gi[e, i, GI_PEND_ACT]
    ok = st.gi[e, i, GI_PEND_OK] == 1
    reason = st.gi[e, i, GI_PEND_REASON]
    began = st.gf[e, i, GF_PEND_BEGAN]
    st.gi[e, i, GI_PEND] = 0
    st.gi[e, i, GI_HOLDING] = 0
    st.ai[e, A_CHANGED] = 0
    if ok:
        res = effect(st, e, i, act)
        if res != R_OK:
            ok, reason = False, res
    if ACT_MOVE[act] or ACT_PLACE[act]:
        for k in range(NSTUCK):
            st.stuck[e, i, k] = False
    if not ACT_LOCAL[act] and (ok or ACT_MOVE[act]):
        moved31(st, e, i, act, ok)
    ended(st, e, i, act, R_OK if ok else reason, st.af[e, AF_T] - began, act)


@jit
def moved31(st, e, i, act, ok):
    mt = st.mt
    if st.gi[e, i, GI_NEAR_VALID] and st.gi[e, i, GI_NEAR_Y] >= 4 and st.gi[e, i, GI_ALIVE]:
        hp = float(st.gi[e, i, GI_NEAR_Y] - 3)
        st.gf[e, i, GF_HEALTH] -= hp
        give0(st, e, i, K_DAMAGE, hp)
    st.gi[e, i, GI_NEAR_VALID] = 0
    st.gi[e, i, GI_PIT_FAILS] = 0
    if not ok:
        return
    noise = st.flags[FL_NOISE] == 1
    obst = st.flags[FL_OBST] / 1000.0
    force = st.ai[e, A_FORCE]
    s = st.ai[e, A_SCEN]
    p_ditch = 1.0 if force == FORCE_DITCH else 0.2 * obst
    if act == ACT_FRONTIER and noise and st.sc_i[s, SI_DITCH_SCEN] and not st.gi[e, i, GI_DITCH] and rnd(mt, e) < p_ditch:
        st.gi[e, i, GI_DITCH] = 1
        st.ai[e, A_OB_DITCH_MET] += 1
    elif act == ACT_BASE or act == ACT_HOME:
        st.gi[e, i, GI_DITCH] = 0
    p_lava = 1.0 if force == FORCE_LAVA else 0.25 * obst
    if (act == ACT_DESCEND or act == ACT_TUNNEL) and noise and not st.gi[e, i, GI_LAVA_FRONT] and rnd(mt, e) < p_lava:
        st.gi[e, i, GI_LAVA_FRONT] = 1
        st.ai[e, A_OB_LAVA_MET] += 1
    elif act == ACT_ASCEND:
        st.gi[e, i, GI_LAVA_FRONT] = 0


@jit
def ended(st, e, i, act, result, secs, name):
    """name: the action (its NO_RESULT), or -1 for die()'s (no name)"""
    rf, so = fails_step(st, e, i, act, result == R_OK, result)
    if rf:
        give0(st, e, i, K_RETRY_FAIL, rf)
        st.ai[e, A_OB_RETRY_FAIL] += 1
    if so:
        give0(st, e, i, K_SWITCH_OK, so)
        st.ai[e, A_OB_SWITCH_OK] += 1
    out = REASON_OUT[result]
    if out != OUT_OK and st.gi[e, i, GI_LAST] == act and st.gi[e, i, GI_OUTCOME] == out:
        st.gi[e, i, GI_REPEATED] += 1
        st.ai[e, A_REPEAT_FAIL] += 1
    else:
        st.gi[e, i, GI_REPEATED] = 0 if out == OUT_OK else 1
    st.gi[e, i, GI_LAST] = act
    st.gi[e, i, GI_OUTCOME] = out
    st.gf[e, i, GF_LAST_S] = secs
    if result == R_OK:
        if (name < 0 or not ACT_NO_RESULT[name]) and st.ai[e, A_CHANGED]:
            give0(st, e, i, K_SKILL_OK, 1.0)
    else:
        give0(st, e, i, K_SKILL_FAIL, 1.0)


@jit
def see(st, e, attr, hidden, p, k):
    h = st.ai[e, hidden]
    if h > 0 and rnd(st.mt, e) < p:
        n = min(h, k)
        st.ai[e, hidden] = h - n
        st.ai[e, attr] += n


@jit
def see_ore(st, e, lv, o, p, n):
    h = st.hidden[e, lv, o]
    if h > 0 and rnd(st.mt, e) < p:
        n = min(h, n)
        st.hidden[e, lv, o] = h - n
        st.seen[e, lv, o] += n


@jit
def xp(st, e, i, pts):
    st.gf[e, i, GF_XP_PTS] += pts
    while st.gf[e, i, GF_XP_PTS] >= 17:
        st.gf[e, i, GF_XP_PTS] -= 17
        st.gi[e, i, GI_XP] += 1


@jit
def leave(st, e, i):
    if not (st.ai[e, A_RING] and st.gf[e, i, GF_DIST] < 8 and st.gi[e, i, GI_DEPTH] == 0):
        return
    if st.ai[e, A_DOOR]:
        st.ai[e, A_GATE_OPEN] = 1
    elif st.ai[e, A_WALL] >= RING - st.ai[e, A_BAD] - 2 and st.ai[e, A_WALL] > 0:
        st.ai[e, A_WALL] -= 1


@jit
def put_table(st, e, i):
    st.ai[e, A_TABLE] = 1
    st.af[e, AF_TABLE_D] = st.gf[e, i, GF_DIST]
    st.ai[e, A_TABLE_BAD] = 1 if (st.flags[FL_NOISE] == 1 and rnd(st.mt, e) < TABLE_BAD) else 0


@jit
def on_craft(st, e, i, item):
    if item == I_TABLE:
        achieve(st, e, i, H_WORKBENCH)
    elif IT_PICK[item]:
        achieve(st, e, i, H_PICKAXE)
        if item != I_WOODEN_PICK:
            achieve(st, e, i, H_BETTER_PICK)
    elif IT_SWORD[item]:
        achieve(st, e, i, H_SWORD)
    elif item == I_FURNACE:
        achieve(st, e, i, H_FURNACE)
    elif IT_HOE[item]:
        achieve(st, e, i, H_HOE)
    elif item == I_BREAD:
        achieve(st, e, i, H_BREAD)
    elif item == I_ETABLE:
        achieve(st, e, i, H_ENCHANT)
    elif item == I_SHELF:
        achieve(st, e, i, H_BOOKCASE)


@jit
def drops(st, e, i, kind):
    mt = st.mt
    if kind == AN_SHEEP:
        got(st, e, i, I_WOOL, 1, -1)
        return 0
    n = randint(mt, e, 1, 3)
    got(st, e, i, I_FOOD_RAW, n, K_G_FOOD)
    if kind == AN_CHICKEN:
        f = randint(mt, e, 0, 2)
        if f:
            got(st, e, i, I_FEATHER, f, -1)
    if kind == AN_COW:
        le = randint(mt, e, 0, 2)
        if le:
            got(st, e, i, I_LEATHER, le, -1)
            achieve(st, e, i, H_KILL_COW)
    return n


@jit
def end_trip(st, e, i):
    if st.gi[e, i, GI_TRIP_OUT] and st.gi[e, i, GI_TRIP_ORES] > 0:
        st.gi[e, i, GI_TRIPS] += 1
        ch = new_chain()
        nch = add_cause(st, e, i, ch, 0, I_TRIP, -1)
        give(st, e, i, K_M_RETURN, 1.0, ch, nch)
    st.gi[e, i, GI_TRIP_OUT] = 0
    st.gi[e, i, GI_TRIP_ORES] = 0


@jit
def pen_add(st, e, k, v):
    if st.pen_order[e, k] < 0:
        st.pen_order[e, k] = st.ai[e, A_PEN_ORDER_N]
        st.ai[e, A_PEN_ORDER_N] += 1
    st.pen_adults[e, k] += v


@jit
def kill(st, e, i, k, inside, weapon, ch, nch):
    """k mobs killed by agent i; ch/nch the causes given (nch < 0: the blow and the weapon's craft)"""
    if inside:
        st.af[e, AF_INSIDE] -= k
    else:
        st.af[e, AF_ENEMIES] -= k
    st.ai[e, A_KILLS] += k
    if weapon < 0:
        weapon = best_item(st, e, i, IT_SWORD)
    if nch < 0:
        ch = new_chain()
        nch = add_hit(st, e, i, ch, 0)
        nch = add_cause(st, e, i, ch, nch, weapon, -1)
    give(st, e, i, K_KILL, float(k), ch, nch)
    give(st, e, i, K_P_KILL, float(k), ch, nch)
    effect_of(st, e, i, ch, nch, EF_MOB_KILLED)
    xp(st, e, i, 5 * k)
    achieve(st, e, i, H_KILL_ENEMY)
    mt = st.mt
    if st.gi[e, i, GI_ENCHANTED] and tier_of(st, e, i, IT_SWORD) >= 4 and rnd(mt, e) < 0.1 * k:
        achieve(st, e, i, H_OVERKILL)
    if rnd(mt, e) < 0.3 * k:
        n = randint(mt, e, 0, 2)
        if n:
            got(st, e, i, I_STRING, n, -1)


@jit
def poisson(st, e, lam):
    if lam <= 0:
        return 0
    if lam < 0.3:
        return 1 if rnd(st.mt, e) < lam else 0
    L = math.exp(-lam)
    k = 0
    p = 1.0
    while True:
        p *= rnd(st.mt, e)
        if p <= L:
            return k
        k += 1


@jit
def hurt(st, e, i, rate, dt, dmg, inside):
    n = poisson(st, e, rate * dt)
    if n:
        hp = n * dmg * (1 - 0.04 * armor(st, e, i))
        st.gf[e, i, GF_HEALTH] -= hp
        give0(st, e, i, K_DAMAGE, hp)
        if inside:
            give0(st, e, i, K_P_HURT_IN, hp)
        return hp
    return 0.0


# ---- the effects (sim_env.effect31, effect) ---------------------------------------------------------------------------
@jit
def effect31(st, e, i, act):
    """-1: not one of 3.1's primitives"""
    fam = ACT_FAM[act]
    near(st, e, i)
    if fam == FAM_DIG:
        d = DIG_DIRS[ACT_P[act]]
        lava_side = st.gi[e, i, GI_LAVA_FRONT] == 1 and (d == DIR_LEFT or d == DIR_RIGHT)
        out = np.empty(2, np.int64)
        n = nr_dig(st, e, i, d, out)
        if n < 0:
            return RS_NOT_VISIBLE
        if n == 0:
            st.ai[e, A_CHANGED] = 0
        for j in range(n):
            it = out[j]
            if it == I_LOG:
                got(st, e, i, I_LOG, 1, K_G_LOG)
                achieve(st, e, i, H_MINE_WOOD)
                st.ai[e, A_OB_HANG_LOGS] += 1
                if st.ai[e, A_HANG]:
                    st.ai[e, A_HANG_GOT] += 1
            elif it == I_COBBLE:
                got(st, e, i, I_COBBLE, 1, K_G_COBBLE)
            else:
                got(st, e, i, it, 1, -1)
        if st.ai[e, A_HANG] and st.gi[e, i, GI_DEPTH] == 0 and st.gf[e, i, GF_DIST] < 6:
            cells = st.ai[e, A_HANG_CELLS]
            keep = 0
            for y in range(62):
                if (cells >> y) & 1 and nr_at(st, e, i, 0, y, 1) == SOLID:
                    keep |= (1 << y)
            st.ai[e, A_HANG_CELLS] = keep
        if lava_side and n > 0:
            st.gi[e, i, GI_LAVA_FRONT] = 0
            st.ai[e, A_OB_LAVA_PASSED] += 1
        return R_OK
    if fam == FAM_PUT31 or fam == FAM_PILLAR:
        mat = best_block(st, e, i)
        if st.bag[e, i, mat] < 1:
            return RS_NO_ITEMS
        if fam == FAM_PILLAR:
            ok = nr_pillar(st, e, i)
        else:
            ok = nr_put(st, e, i, PUT31_DIRS[ACT_P[act]])
        if not ok:
            return RS_NO_SPOT
        add(st, e, i, mat, -1)
        if act == ACT_PUT_FRONT_DOWN and st.gi[e, i, GI_DITCH]:
            st.gi[e, i, GI_DITCH] = 0
            st.ai[e, A_OB_DITCH_BRIDGED] += 1
        return R_OK
    if act == ACT_BUCKET_FRONT_DOWN and st.gi[e, i, GI_LAVA_FRONT]:
        y = st.gi[e, i, GI_NEAR_Y]
        if nr_at(st, e, i, 0, y - 1, 1) == LAVA:
            if st.bag[e, i, I_BUCKET] < 1:
                return RS_NO_ITEMS
            nr_set(st, e, i, 0, y - 1, 1, AIR, -1)
            add(st, e, i, I_BUCKET, -1)
            got(st, e, i, I_LAVA_B, 1, -1)
            made(st, e, i, I_LAVA_B)
            st.gi[e, i, GI_LAVA_FRONT] = 0
            st.ai[e, A_OB_LAVA_PASSED] += 1
            return R_OK
    return -1


@jit
def effect(st, e, i, act):
    """the skill's effects at its end: R_OK when it went well, else the reason it failed"""
    st.ai[e, A_CHANGED] = 1
    res = effect31(st, e, i, act)
    if res >= 0:
        return res
    mt = st.mt
    fam = ACT_FAM[act]
    s = st.ai[e, A_SCEN]
    depth = st.gi[e, i, GI_DEPTH]
    t = st.af[e, AF_T]
    if fam == FAM_LOOK:
        st.ai[e, A_CHANGED] = 0
        if depth:
            for j in range(NORE):
                o = LEVEL_KEYS[depth, j]
                if o >= 0:
                    see_ore(st, e, depth, o, 0.2, 1)
        else:
            k = randint(mt, e, 4, 12)
            see(st, e, A_TREES, A_TREES_HIDDEN, 0.9, k)
            k = randint(mt, e, 1, 2)
            see(st, e, A_ANIMALS, A_ANIMALS_HIDDEN, 0.4, k)
            see(st, e, A_COAL, A_COAL_HIDDEN, 0.2, 1)
            see(st, e, A_CANE, A_CANE_HIDDEN, 0.3, 1)
            st.ai[e, A_STONE_SEEN] += randint(mt, e, 0, 2)
            st.ai[e, A_GRASS] = min(40, st.ai[e, A_GRASS] + randint(mt, e, 5, 15))
            st.ai[e, A_LEAVES] = min(80, st.ai[e, A_LEAVES] + 4 * st.ai[e, A_TREES])
    elif fam == FAM_FRONTIER:
        leave(st, e, i)
        st.gf[e, i, GF_DIST] += uniform(mt, e, 8.0, 16.0)
        k = randint(mt, e, 2, 6)
        see(st, e, A_TREES, A_TREES_HIDDEN, 0.9, k)
        k = randint(mt, e, 1, 3)
        see(st, e, A_ANIMALS, A_ANIMALS_HIDDEN, 0.6, k)
        see(st, e, A_COAL, A_COAL_HIDDEN, 0.35, 1)
        see(st, e, A_IRON, A_IRON_HIDDEN, 0.15 if not st.sc_i[s, SI_SIM_ONLY] else 0.3, 1)
        k = randint(mt, e, 1, 2)
        see(st, e, A_CANE, A_CANE_HIDDEN, 0.4, k)
        st.ai[e, A_GRASS] += randint(mt, e, 0, 4)
        st.ai[e, A_LEAVES] += randint(mt, e, 0, 6)
        st.ai[e, A_GRAVEL] += 1 if below(mt, e, 3) == 2 else 0
        st.ai[e, A_STONE_SEEN] += randint(mt, e, 0, 10)
    elif fam == FAM_CHOP:
        if st.ai[e, A_TREES] <= 0:
            return RS_NO_TREE_THERE
        leave(st, e, i)
        st.ai[e, A_TREES] -= 1
        grown = st.ai[e, A_GROWN]
        planted = grown > 0 and rnd(mt, e) < grown / max(1, st.ai[e, A_TREES] + 1)
        if planted:
            st.ai[e, A_GROWN] -= 1
        if st.flags[FL_NOISE] == 1 and rnd(mt, e) < CHOP_EMPTY:
            st.ai[e, A_CHANGED] = 0
        else:
            n = randint(mt, e, 3, 6)
            got(st, e, i, I_LOG, n, K_G_LOG)
            achieve(st, e, i, H_MINE_WOOD)
            if planted:
                ledger(st, e, 2, float(n))
            if rnd(mt, e) < 0.5:
                got(st, e, i, I_SAPLING, randint(mt, e, 1, 2), -1)
        u = uniform(mt, e, 6.0, 20.0)
        st.gf[e, i, GF_DIST] = max(st.gf[e, i, GF_DIST], u)
    elif fam == FAM_MINE_STONE:
        if st.flags[FL_NOISE] == 1 and rnd(mt, e) < STONE_EMPTY and not depth:
            st.ai[e, A_CHANGED] = 0
            return R_OK
        n = py_round(gauss(mt, st.gnext, e, STONE_YIELD, 1.5))
        n = n if n > 1 else 1
        got(st, e, i, I_COBBLE, n, K_G_COBBLE)
        if depth:
            for j in range(NORE):
                o = LEVEL_KEYS[depth, j]
                if o >= 0:
                    see_ore(st, e, depth, o, 0.15, 1)
        else:
            st.ai[e, A_STONE_SEEN] = max(st.ai[e, A_STONE_SEEN], 8)
            k = randint(mt, e, 1, 2)
            see(st, e, A_COAL, A_COAL_HIDDEN, 0.5, k)
            see(st, e, A_IRON, A_IRON_HIDDEN, 0.35, 1)
            u = uniform(mt, e, 4.0, 16.0)
            st.gf[e, i, GF_DIST] = max(st.gf[e, i, GF_DIST], u)
    elif fam == FAM_MINE_GRAVEL:
        have = st.seen[e, depth, O_GRAVEL] if depth else st.ai[e, A_GRAVEL]
        if have <= 0:
            return RS_NOT_SEEN
        if depth:
            st.seen[e, depth, O_GRAVEL] = have - 1
        else:
            st.ai[e, A_GRAVEL] -= 1
        n = randint(mt, e, 1, 4)
        fl = 0
        for _ in range(n):
            if rnd(mt, e) < 0.1:
                fl += 1
        if fl:
            got(st, e, i, I_FLINT, fl, -1)
        if n - fl:
            got(st, e, i, I_GRAVEL, n - fl, -1)
    elif fam == FAM_MINE_ORE:
        o = ACT_P[act]
        if depth:
            have = st.seen[e, depth, o]
        elif o == O_COAL:
            have = st.ai[e, A_COAL]
        elif o == O_IRON:
            have = st.ai[e, A_IRON]
        else:
            have = 0
        if have <= 0:
            return RS_NOT_SEEN
        if depth:
            st.seen[e, depth, o] = have - 1
        elif o == O_COAL:
            st.ai[e, A_COAL] = have - 1
        else:
            st.ai[e, A_IRON] = have - 1
        item, n = MINE_ITEM[o], MINE_N[o]
        pick = best_item(st, e, i, IT_PICK)
        ch = new_chain()
        nch = add_cause(st, e, i, ch, 0, pick, -1)
        got(st, e, i, item, n, GATHERED_OF[item])
        give(st, e, i, MINE_KEY[o], float(n), ch, nch)
        if o == O_IRON:
            give(st, e, i, K_IRON_FOUND, float(n), ch, nch)
        xp(st, e, i, MINE_XP[o] * n)
        if depth:
            st.gi[e, i, GI_TRIP_ORES] += n
        if item == I_DIAMOND:
            achieve(st, e, i, H_DIAMONDS)
    elif fam == FAM_DESCEND:
        leave(st, e, i)
        if st.ai[e, A_MINE_LEVELS] <= depth:
            st.ai[e, A_MINE_LEVELS] = depth + 1
            got(st, e, i, I_COBBLE, randint(mt, e, 8, 16), -1)
        depth += 1
        st.gi[e, i, GI_DEPTH] = depth
        st.gi[e, i, GI_TRIP_OUT] = 1
        st.gi[e, i, GI_POST] = 0
        st.gf[e, i, GF_DIST] = st.af[e, AF_MINE_D]
        if st.made_did[e, i, I_TRIP] < 0:
            made(st, e, i, I_TRIP)
        if depth <= 2:
            for j in range(NORE):
                o = LEVEL_KEYS[depth, j]
                if o >= 0:
                    see_ore(st, e, depth, o, 0.35, 1)
        else:
            st.ai[e, A_OVERFLOW] += 1   # sim_env: KeyError (no horizon 3); the mask keeps it from here
    elif fam == FAM_TUNNEL:
        if depth == 1:
            st.gi[e, i, GI_TUN1] += 1
        elif depth == 2:
            st.gi[e, i, GI_TUN2] += 1
        else:
            st.gi[e, i, GI_TUN0] += 1
        got(st, e, i, I_COBBLE, randint(mt, e, 6, 12), -1)
        if 1 <= depth <= 2:
            for j in range(NORE):
                o = LEVEL_KEYS[depth, j]
                if o >= 0:
                    k = randint(mt, e, 1, 2)
                    see_ore(st, e, depth, o, 0.55, k)
        else:
            st.ai[e, A_OVERFLOW] += 1
    elif fam == FAM_ASCEND:
        st.gi[e, i, GI_DEPTH] = 0
        st.gf[e, i, GF_DIST] = st.af[e, AF_MINE_D]
        end_trip(st, e, i)
    elif fam == FAM_CRAFT or fam == FAM_CRAFT_ARMOR:
        if fam == FAM_CRAFT:
            rec = ACT_P[act]
        else:
            piece = armor_piece(st, e, i, ACT_P[act])
            if piece < 0:
                return RS_MISSING
            rec = T_REC_OF_ITEM[piece]
        for j in range(4):
            it = REC_NEED_IT[rec, j]
            if it >= 0 and st.bag[e, i, it] < REC_NEED_N[rec, j]:
                return RS_MISSING
        if not REC_SMALL[rec]:
            if not (st.ai[e, A_TABLE] and abs(st.gf[e, i, GF_DIST] - st.af[e, AF_TABLE_D]) <= 24 and depth == 0):
                if st.bag[e, i, I_TABLE] < 1:
                    return RS_NO_TABLE
                add(st, e, i, I_TABLE, -1)
                put_table(st, e, i)
            if st.ai[e, A_TABLE_BAD]:
                if fam == FAM_CRAFT:
                    st.stuck[e, i, ACT_KIND[act]] = True
                return RS_NO_PATH
        for j in range(4):
            it = REC_NEED_IT[rec, j]
            if it >= 0:
                add(st, e, i, it, -REC_NEED_N[rec, j])
        item = REC_ITEM[rec]
        if item == I_BREAD:
            st.ai[e, A_BAKED] += 1
            k = 0
            while k < 3 and st.ai[e, A_N_RENWHEAT] > 0:
                did, ag = st.renwheat[e, 0, 0], st.renwheat[e, 0, 1]
                nw = st.ai[e, A_N_RENWHEAT]
                for q in range(nw - 1):
                    st.renwheat[e, q, 0] = st.renwheat[e, q + 1, 0]
                    st.renwheat[e, q, 1] = st.renwheat[e, q + 1, 1]
                st.ai[e, A_N_RENWHEAT] = nw - 1
                renfood_push(st, e, FOOD_PTS[2] / 3, did, ag)
                k += 1
        if REC_WOODY[rec]:
            ledger(st, e, 3, REC_WOOD[rec])
        got(st, e, i, item, REC_OUT[rec], -1)
        made(st, e, i, item)
        on_craft(st, e, i, item)
    elif fam == FAM_SMELT:
        src, dst = ACT_P[act], ACT_Q[act]
        if not (st.ai[e, A_FURNACE] and abs(st.gf[e, i, GF_DIST] - st.af[e, AF_FURNACE_D]) <= 24):
            if st.bag[e, i, I_FURNACE] < 1:
                return RS_NO_FURNACE
            add(st, e, i, I_FURNACE, -1)
            st.ai[e, A_FURNACE] = 1
            st.af[e, AF_FURNACE_D] = st.gf[e, i, GF_DIST]
        n = min(SMELT_PER, st.bag[e, i, src])
        if st.bag[e, i, I_COAL] >= 1:
            add(st, e, i, I_COAL, -1)
        elif st.bag[e, i, I_PLANKS] >= 1:
            add(st, e, i, I_PLANKS, -1)
        elif st.bag[e, i, I_LOG] >= 1:
            add(st, e, i, I_LOG, -1)
        else:
            return RS_NO_FUEL
        add(st, e, i, src, -n)
        got(st, e, i, dst, n, -1)
        xp(st, e, i, 0.7 * n)
        if dst == I_IRON_INGOT:
            achieve(st, e, i, H_IRON)
    elif fam == FAM_PLACE_ITEM:
        item = ACT_P[act]
        if item < 0 or st.bag[e, i, item] < 1:
            return RS_NO_ITEMS
        add(st, e, i, item, -1)
        if item == I_TABLE:
            put_table(st, e, i)
        elif item == I_FURNACE:
            st.ai[e, A_FURNACE] = 1
            st.af[e, AF_FURNACE_D] = st.gf[e, i, GF_DIST]
        elif item == I_ETABLE:
            st.ai[e, A_ENCH_TABLE] = 1
        elif item == I_SHELF:
            st.ai[e, A_SHELVES] += 1
            give0(st, e, i, K_E_SHELF, 1.0)
        else:
            st.ai[e, A_CHEST] = 1
    elif fam == FAM_BUILD_WALL or fam == FAM_REPAIR_WALL:
        build = fam == FAM_BUILD_WALL
        if not build and st.ai[e, A_WALL] >= RING:
            return RS_NO_HOLES
        if not st.ai[e, A_RING]:
            st.ai[e, A_RING] = 1
            st.ai[e, A_BAD] = int(RING * uniform(mt, e, BAD0, BAD1)) if st.flags[FL_NOISE] == 1 else 0
        wall, bad = st.ai[e, A_WALL], st.ai[e, A_BAD]
        good_left = RING - wall - bad
        cells = min(24 if build else 12, RING - wall)
        if good_left <= 0:
            if bad <= 0 or rnd(mt, e) < 0.5:
                st.stuck[e, i, STUCK_BUILD if build else STUCK_REPAIR] = True
                return RS_OUT_OF_REACH
            k = min(bad, randint(mt, e, 1, 4))
            st.ai[e, A_BAD] = bad - k
            good_left = k
        cells = min(cells, good_left)
        mat = best_block(st, e, i)
        k = min(st.bag[e, i, mat], cells)
        if k <= 0:
            return RS_NO_ITEMS
        add(st, e, i, mat, -k)
        st.ai[e, A_WALL] += k
        builder(st, e, i)
        give0(st, e, i, K_B_WALL, float(k))
        st.gf[e, i, GF_DIST] = uniform(mt, e, 5.0, 9.0)
    elif fam == FAM_DIG_MOAT:
        n = min(MOAT_YIELD, MOAT - st.ai[e, A_MOAT])
        st.ai[e, A_MOAT] += n
        builder(st, e, i)
        got(st, e, i, I_DIRT, n, -1)
        give0(st, e, i, K_B_MOAT, float(n))
        st.gf[e, i, GF_DIST] = uniform(mt, e, 8.0, 11.0)
    elif fam == FAM_LIGHT:
        n = min(st.bag[e, i, I_TORCH], max(0, 12 - st.ai[e, A_TORCHES]), 4)
        if n <= 0:
            return RS_NO_SPOT
        add(st, e, i, I_TORCH, -n)
        st.ai[e, A_TORCHES] += n
        give0(st, e, i, K_B_TORCH, float(n))
    elif fam == FAM_EQUIP:
        tsw = tier_of(st, e, i, IT_SWORD)
        wore = False
        hv = np.zeros(ARMOR_BY_PTS.shape[0], np.bool_)
        for j in range(ARMOR_BY_PTS.shape[0]):
            hv[j] = st.bag[e, i, ARMOR_BY_PTS[j]] > 0
        for j in range(ARMOR_BY_PTS.shape[0]):
            k = ARMOR_BY_PTS[j]
            if hv[j] and st.bag[e, i, k] > 0:
                slot = IT_SLOT[k]
                if IT_ARMOR[k] > worn_pts(st, e, i, slot):
                    if st.worn[e, i, slot] >= 0:
                        add(st, e, i, st.worn[e, i, slot], 1)
                    st.worn[e, i, slot] = k
                    add(st, e, i, k, -1)
                    wore = True
        held = st.gi[e, i, GI_HELD]
        if tsw <= held and not wore:
            return RS_NO_WEAPON
        if tsw > held:
            made(st, e, i, I_EQUIPPED)
        st.gi[e, i, GI_HELD] = max(held, tsw)
    elif fam == FAM_TAKE_POST:
        if not st.gi[e, i, GI_POST]:
            if st.ai[e, A_POSTS_TAKEN] >= POSTS:
                return RS_ALL_TAKEN
            st.ai[e, A_POSTS_TAKEN] += 1
        st.gi[e, i, GI_POST] = 1
        st.gf[e, i, GF_DIST] = 7.0
        made(st, e, i, I_POST)
    elif fam == FAM_HOLD_LINE:
        st.ai[e, A_CHANGED] = 0
    elif fam == FAM_SHOOT:
        en = st.af[e, AF_ENEMIES] if st.ai[e, A_PHASE] == PH_WAVE else 0.0
        if en <= 0:
            return RS_NOT_VISIBLE
        shots = min(st.bag[e, i, I_ARROW], randint(mt, e, 2, 5))
        add(st, e, i, I_ARROW, -shots)
        p = 0.18 + (0.1 if st.gi[e, i, GI_POST] else 0.0)
        hits = 0
        for _ in range(shots):
            if rnd(mt, e) < p:
                hits += 1
        k = min(int(math.ceil(st.af[e, AF_ENEMIES])), hits)
        if k:
            kill(st, e, i, k, False, I_BOW, new_chain(), -1)
            if rnd(mt, e) < 0.02 * k:
                achieve(st, e, i, H_SNIPE)
        else:
            st.ai[e, A_CHANGED] = 0
    elif fam == FAM_HOME:
        if depth:
            end_trip(st, e, i)
        st.gf[e, i, GF_DIST] = uniform(mt, e, 0.0, 2.0)
        st.gi[e, i, GI_DEPTH] = 0
    elif fam == FAM_EAT:
        return eat(st, e, i)
    elif fam == FAM_HUNT:
        if st.ai[e, A_ANIMALS] <= 0:
            return RS_NO_ANIMAL
        leave(st, e, i)
        st.ai[e, A_ANIMALS] -= 1
        kind = choices1(mt, e, ANIMAL_CUM)
        drops(st, e, i, kind)
        if kind == AN_SHEEP:
            st.ai[e, A_CHANGED] = 0
        u = uniform(mt, e, 6.0, 20.0)
        st.gf[e, i, GF_DIST] = max(st.gf[e, i, GF_DIST], u)
    elif fam == FAM_PUT_CHEST:
        for k in range(NIT_BAG):
            v = st.bag[e, i, k]
            if v <= 0 or IT_TOOL[k] or IS_ARMOR[k]:
                continue
            if k == I_FOOD_RAW or k == I_FOOD_COOKED or k == I_BREAD:
                st.ai[e, A_CHEST_FOOD] += v
            st.bag[e, i, k] = 0
        st.gf[e, i, GF_DIST] = 1.0
    elif fam == FAM_TAKE_CHEST:
        n = min(8, st.ai[e, A_CHEST_FOOD])
        if n <= 0:
            return RS_NOT_IN_CHEST
        st.ai[e, A_CHEST_FOOD] -= n
        got(st, e, i, I_BREAD, n, -1)
        st.gf[e, i, GF_DIST] = 1.0
    elif fam == FAM_BUILD_GATE:
        if st.bag[e, i, I_DOOR] < 1:
            return RS_NO_ITEMS
        add(st, e, i, I_DOOR, -1)
        st.ai[e, A_DOOR] = 1
        st.ai[e, A_GATE_OPEN] = 0
        builder(st, e, i)
    elif fam == FAM_CLOSE_GATE:
        if not st.ai[e, A_GATE_OPEN]:
            return RS_DONE_ALREADY
        st.ai[e, A_GATE_OPEN] = 0
    elif fam == FAM_GATHER_SEEDS:
        if st.ai[e, A_GRASS] <= 0:
            return RS_NOT_SEEN
        st.ai[e, A_GRASS] -= 1
        n = 0
        for _ in range(6):
            if rnd(mt, e) < 0.125:
                n += 1
        if not n:
            st.ai[e, A_CHANGED] = 0
        else:
            got(st, e, i, I_SEEDS, n, -1)
    elif fam == FAM_GATHER_SAPLINGS:
        if st.ai[e, A_LEAVES] <= 0:
            return RS_NOT_SEEN
        st.ai[e, A_LEAVES] -= 1
        n = 0
        for _ in range(8):
            if rnd(mt, e) < 0.05:
                n += 1
        if not n:
            st.ai[e, A_CHANGED] = 0
        else:
            got(st, e, i, I_SAPLING, n, -1)
    elif fam == FAM_GATHER_CANE:
        if st.ai[e, A_CANE] <= 0:
            return RS_NOT_SEEN
        st.ai[e, A_CANE] -= 1
        got(st, e, i, I_CANE, randint(mt, e, 2, 3), -1)
    elif fam == FAM_TILL:
        n = min(4, 16 - st.ai[e, A_PLOTS])
        if n <= 0:
            return RS_DONE_ALREADY
        st.ai[e, A_PLOTS] += n
        st.gf[e, i, GF_DIST] = uniform(mt, e, 2.0, 5.0)
    elif fam == FAM_PLANT_WHEAT:
        n = min(st.bag[e, i, I_SEEDS], st.ai[e, A_PLOTS] - st.ai[e, A_PLANTED])
        if n <= 0:
            return RS_NO_SEEDS
        add(st, e, i, I_SEEDS, -n)
        st.ai[e, A_PLANTED] += n
        for _ in range(n):
            crop_push(st, e, t + uniform(mt, e, CROP0, CROP1) / GROW, st.gi[e, i, GI_DID], i)
    elif fam == FAM_HARVEST:
        nr = st.ai[e, A_N_RIPE]
        if nr == 0:
            return RS_NOT_RIPE
        for q in range(nr):
            renwheat_push(st, e, st.ripe[e, q, 0], st.ripe[e, q, 1])
        st.ai[e, A_N_RIPE] = 0
        st.ai[e, A_PLANTED] -= nr
        got(st, e, i, I_WHEAT, nr, -1)
        sd = 0
        for _ in range(nr):
            sd += randint(mt, e, SEED0, SEED1)
        got(st, e, i, I_SEEDS, sd, -1)
        k = min(nr, st.bag[e, i, I_SEEDS])
        if k:
            add(st, e, i, I_SEEDS, -k)
            st.ai[e, A_PLANTED] += k
            for _ in range(k):
                crop_push(st, e, t + uniform(mt, e, CROP0, CROP1) / GROW, st.gi[e, i, GI_DID], i)
    elif fam == FAM_PLANT_SAPLING:
        if st.bag[e, i, I_SAPLING] < 1:
            return RS_NO_ITEMS
        add(st, e, i, I_SAPLING, -1)
        ts = t + uniform(mt, e, SAP0, SAP1) / GROW
        n = st.ai[e, A_N_SAPLINGS]
        if n >= CAP_SAPLINGS:
            st.ai[e, A_OVERFLOW] += 1
        else:
            st.saplings[e, n] = ts
            st.ai[e, A_N_SAPLINGS] = n + 1
    elif fam == FAM_BUILD_PEN:
        if st.bag[e, i, I_FENCE] < 15 or st.bag[e, i, I_FENCE_GATE] < 1:
            return RS_NO_ITEMS
        add(st, e, i, I_FENCE, -15)
        add(st, e, i, I_FENCE_GATE, -1)
        st.ai[e, A_PEN] = 1
    elif fam == FAM_LURE:
        if st.ai[e, A_ANIMALS] <= 0 and st.pen_wild[e, 0] <= 0 and st.pen_wild[e, 1] <= 0:
            return RS_NO_ANIMAL
        leave(st, e, i)
        nw = (1 if st.pen_wild[e, 0] > 0 else 0) + (1 if st.pen_wild[e, 1] > 0 else 0)
        if nw:
            q = below(mt, e, nw)
            w = 0 if (st.pen_wild[e, 0] > 0 and q == 0) else 1
            st.pen_wild[e, w] -= 1
            pen_add(st, e, WILD[w], 1)
            return R_OK
        n = min(st.ai[e, A_ANIMALS], randint(mt, e, 1, 2))
        st.ai[e, A_ANIMALS] -= n
        for _ in range(n):
            pen_add(st, e, choices1(mt, e, ANIMAL_CUM), 1)
    elif fam == FAM_BREED:
        kk = -1
        best_o = 1 << 40
        for k in range(N_ANIMALS):   # the first in the pen's order (a dict's) that may breed
            if st.pen_order[e, k] >= 0 and st.pen_adults[e, k] >= 2 and st.pen_rest[e, k] <= t and st.pen_order[e, k] < best_o:
                kk, best_o = k, st.pen_order[e, k]
        if kk < 0 or st.bag[e, i, I_WHEAT] + st.bag[e, i, I_SEEDS] < 2:
            return RS_NO_MATE
        if st.bag[e, i, I_WHEAT] >= 2:
            add(st, e, i, I_WHEAT, -2)
        elif st.bag[e, i, I_SEEDS] >= 2:
            add(st, e, i, I_SEEDS, -2)
        n = st.ai[e, A_N_YOUNG]
        if n >= CAP_YOUNG:
            st.ai[e, A_OVERFLOW] += 1
        else:
            st.young[e, n, 0] = t + PEN_GROW / GROW
            st.young[e, n, 1] = kk
            st.ai[e, A_N_YOUNG] = n + 1
        st.pen_rest[e, kk] = t + PEN_REST
        st.ai[e, A_BRED] += 1
        xp(st, e, i, 3.0)
        if kk == AN_COW:
            achieve(st, e, i, H_BREED_COW)
    elif fam == FAM_SLAUGHTER:
        kk = -1
        bv, bo = -1, 1 << 40
        for k in range(N_ANIMALS):   # max(pen_adults, key=count): the first of the most in the dict's order
            o = st.pen_order[e, k]
            if o < 0:
                continue
            v = st.pen_adults[e, k]
            if v > bv or (v == bv and o < bo):
                kk, bv, bo = k, v, o
        if kk < 0 or st.pen_adults[e, kk] < 1:
            return RS_NO_ANIMAL
        st.pen_adults[e, kk] -= 1
        g = drops(st, e, i, kk)
        if g:
            renfood_push(st, e, FOOD_PTS[0] * g, st.gi[e, i, GI_DID], i)
    elif fam == FAM_ENCHANT:
        if not st.ai[e, A_ENCH_TABLE]:
            return RS_NO_TABLE
        x = st.gi[e, i, GI_XP]
        if x < 1:
            return RS_NO_XP
        lvl = min(x, 1 + 2 * st.ai[e, A_SHELVES], 30)
        spent = min(lvl if lvl <= 3 else max(1, lvl // 3), x)
        st.gi[e, i, GI_XP] = x - spent
        st.gi[e, i, GI_ENCHANTED] += 1
        give0(st, e, i, K_E_ITEM, 1.0)
        give0(st, e, i, K_E_LEVELS, float(spent))
    elif fam == FAM_USE_BUCKET:
        tg = ACT_P[act]
        if st.bag[e, i, I_BUCKET] < 1:
            return RS_NO_ITEMS
        row = np.empty(R_W)
        raw(st, e, i, row)
        liq = st.ai[e, A_MOAT_LIQ]
        if row[TGT_LSRC_COL[tg]] > 0.5 or tg == TG_MOAT and liq == 2:
            lb = I_LAVA_B
        elif row[TGT_WSRC_COL[tg]] > 0.5 or tg == TG_MOAT and liq == 1:
            lb = I_WATER_B
        else:
            return RS_NOT_SEEN
        add(st, e, i, I_BUCKET, -1)
        got(st, e, i, lb, 1, -1)
        made(st, e, i, lb)
        if tg == TG_MOAT:
            st.ai[e, A_MOAT_LIQ] = 0
    elif fam == FAM_USE_FIRE:
        if st.bag[e, i, I_FLINT_STEEL] < 1:
            return RS_NO_ITEMS
        if act == ACT_FIRE_MOB and st.af[e, AF_ENEMIES] > 0 and st.ai[e, A_PHASE] == PH_WAVE:
            k = min(int(st.af[e, AF_ENEMIES]), randint(mt, e, 0, 2))
            ch = new_chain()
            nch = chain_add(ch, 0, st.gi[e, i, GI_DID], i, int(st.gf[e, i, GF_DID_T] * 20), st.gi[e, i, GI_KIND], V_FIRE)
            if k:
                kill(st, e, i, k, False, -1, ch, nch)
            else:
                effect_of(st, e, i, ch, nch, EF_MOB_HURT)
        else:
            st.ai[e, A_CHANGED] = 0
    elif fam == FAM_ATTACK_MOB:
        en = st.af[e, AF_ENEMIES] if st.ai[e, A_PHASE] == PH_WAVE else 0.0
        inside = st.af[e, AF_INSIDE]
        if en + inside <= 0:
            return RS_NOT_VISIBLE
        weapon = best_item(st, e, i, IT_SWORD)
        held = st.gi[e, i, GI_HELD]
        p = (0.25 + 0.15 * held) * (1.0 if held else st.sc_f[s, SF_UNARMED])
        k = min(int(math.ceil(en + inside)), poisson(st, e, p * 3))
        hurt(st, e, i, 0.3, 3.0, 3.0, False)
        if k:
            kill(st, e, i, k, inside >= k and en < k, weapon, new_chain(), -1)
        else:
            ch = new_chain()
            nch = add_hit(st, e, i, ch, 0)
            nch = add_cause(st, e, i, ch, nch, weapon, -1)
            effect_of(st, e, i, ch, nch, EF_MOB_HURT)
            st.ai[e, A_CHANGED] = 0
    elif fam == FAM_ATTACK_ANIMAL:
        if st.ai[e, A_ANIMALS] <= 0:
            return RS_NO_ANIMAL
        leave(st, e, i)
        st.ai[e, A_ANIMALS] -= 1
        kind = choices1(mt, e, ANIMAL_CUM)
        drops(st, e, i, kind)
        ch = new_chain()
        nch = add_hit(st, e, i, ch, 0)
        nch = add_cause(st, e, i, ch, nch, best_item(st, e, i, IT_SWORD), -1)
        effect_of(st, e, i, ch, nch, EF_ANIMAL_KILLED)
    elif fam == FAM_OPEN:
        if not st.ai[e, A_DOOR]:
            return RS_NOT_SEEN
        st.ai[e, A_GATE_OPEN] = 0 if st.ai[e, A_GATE_OPEN] else 1
    return R_OK


@jit
def eat(st, e, i):
    hunger = st.sc_i[st.ai[e, A_SCEN], SI_HUNGER]
    ate = 0
    tmp = np.zeros((CAP_LASTREN, 2), np.int64)
    ntmp = 0
    for f in range(3):
        food = EAT_ORDER[f]
        while st.bag[e, i, food] > 0 and (st.gf[e, i, GF_FOOD] < 20 if hunger else st.gf[e, i, GF_HEALTH] < 20):
            add(st, e, i, food, -1)
            pts = EAT_PTS[f]
            if hunger:
                st.gf[e, i, GF_FOOD] = min(20.0, st.gf[e, i, GF_FOOD] + pts)
                st.gf[e, i, GF_SAT] = min(st.gf[e, i, GF_FOOD], st.gf[e, i, GF_SAT] + EAT_SAT[f])
            else:
                st.gf[e, i, GF_HEALTH] = min(20.0, st.gf[e, i, GF_HEALTH] + EAT_HEAL[f])
            left = pts
            while left > 0 and st.ai[e, A_N_RENFOOD] > 0:
                p, did, ag = st.renfood[e, 0, 0], int(st.renfood[e, 0, 1]), int(st.renfood[e, 0, 2])
                take = min(p, left)
                left -= take
                ledger(st, e, 0, take)
                if ntmp < CAP_LASTREN:
                    tmp[ntmp, 0] = did
                    tmp[ntmp, 1] = ag
                    ntmp += 1
                else:
                    st.ai[e, A_OVERFLOW] += 1
                if p - take <= 1e-9:
                    nf = st.ai[e, A_N_RENFOOD]
                    for q in range(nf - 1):
                        for c in range(3):
                            st.renfood[e, q, c] = st.renfood[e, q + 1, c]
                    st.ai[e, A_N_RENFOOD] = nf - 1
                else:
                    st.renfood[e, 0, 0] = p - take
            ledger(st, e, 1, pts)
            ate += 1
            if hunger and st.gf[e, i, GF_FOOD] >= 20 and st.gf[e, i, GF_HEALTH] >= 20:
                break
    if ate == 0:
        return RS_NO_FOOD
    for q in range(ntmp):
        st.lastren[e, q, 0] = tmp[q, 0]
        st.lastren[e, q, 1] = tmp[q, 1]
    st.ai[e, A_N_LASTREN] = ntmp
    give0(st, e, i, K_ATE, float(ate))
    return R_OK


@jit
def builder(st, e, i):
    """self.builders.append((did, t, agent, kind)): the last 8 kept (only they are read)"""
    nb_ = st.ai[e, A_N_BUILDERS]
    j = nb_ % 8
    st.builders[e, j, 0] = st.gi[e, i, GI_DID]
    st.builders[e, j, 1] = st.af[e, AF_T]
    st.builders[e, j, 2] = i
    st.builders[e, j, 3] = st.gi[e, i, GI_KIND]
    st.ai[e, A_N_BUILDERS] = nb_ + 1


@jit
def crop_push(st, e, ripe_t, did, ag):
    n = st.ai[e, A_N_CROPS]
    if n >= CAP_CROPS:
        st.ai[e, A_OVERFLOW] += 1
        return
    st.crops[e, n, 0] = ripe_t
    st.crops[e, n, 1] = did
    st.crops[e, n, 2] = ag
    st.ai[e, A_N_CROPS] = n + 1


@jit
def renwheat_push(st, e, did, ag):
    n = st.ai[e, A_N_RENWHEAT]
    if n >= CAP_REN:
        st.ai[e, A_OVERFLOW] += 1
        return
    st.renwheat[e, n, 0] = did
    st.renwheat[e, n, 1] = ag
    st.ai[e, A_N_RENWHEAT] = n + 1


@jit
def renfood_push(st, e, pts, did, ag):
    n = st.ai[e, A_N_RENFOOD]
    if n >= CAP_REN:
        st.ai[e, A_OVERFLOW] += 1
        return
    st.renfood[e, n, 0] = pts
    st.renfood[e, n, 1] = did
    st.renfood[e, n, 2] = ag
    st.ai[e, A_N_RENFOOD] = n + 1


# ---- the world's time (sim_env.advance, world, die, respawn, next_phase, measure) ---------------------------------------
@jit
def die(st, e, i, out, accident):
    wave = st.ai[e, A_PHASE] == PH_WAVE
    if not accident:
        give0(st, e, i, K_DEATH, 1.0)
    if out and wave:
        give0(st, e, i, K_OUT_DIED, 1.0)
        give0(st, e, i, K_P_OUT_DIED, 1.0)
    if wave:
        st.ai[e, A_WAVE_LOSSES] += 1
    st.gi[e, i, GI_ALIVE] = 0
    for k in range(NIT):
        st.bag[e, i, k] = 0
        st.made_did[e, i, k] = -1
    for p in range(4):
        st.worn[e, i, p] = -1
    st.gi[e, i, GI_HELD] = 0
    st.gf[e, i, GF_HEALTH] = 0
    st.gi[e, i, GI_DEPTH] = 0
    st.gi[e, i, GI_TRIP_OUT] = 0
    st.gi[e, i, GI_TRIP_ORES] = 0
    if st.gi[e, i, GI_POST]:
        st.ai[e, A_POSTS_TAKEN] -= 1
    st.gi[e, i, GI_POST] = 0
    st.gi[e, i, GI_HOLDING] = 0
    t = st.af[e, AF_T]
    if st.gi[e, i, GI_PEND]:
        act = st.gi[e, i, GI_PEND_ACT]
        began = st.gf[e, i, GF_PEND_BEGAN]
        st.gi[e, i, GI_PEND] = 0
        ended(st, e, i, act, R_DIED, t - began, -1)
    st.gf[e, i, GF_FREE_AT] = t + 10.0
    st.gf[e, i, GF_RESPAWN_AT] = t + RESPAWN_S
    st.gi[e, i, GI_HAS_RESPAWN] = 1


@jit
def respawn(st, e):
    t = st.af[e, AF_T]
    for i in range(st.ai[e, A_NAG]):
        if not st.gi[e, i, GI_ALIVE] and st.gi[e, i, GI_HAS_RESPAWN] and t >= st.gf[e, i, GF_RESPAWN_AT]:
            st.gi[e, i, GI_ALIVE] = 1
            st.gf[e, i, GF_HEALTH] = 20.0
            st.gf[e, i, GF_DIST] = 0.0
            st.gf[e, i, GF_FREE_AT] = t
            st.gi[e, i, GI_HAS_RESPAWN] = 0


@jit
def next_phase(st, e):
    s = st.ai[e, A_SCEN]
    ph = st.ai[e, A_PHASE]
    t = st.af[e, AF_T]
    if ph == PH_PREP or ph == PH_BREAK:
        st.ai[e, A_WAVE_NUM] += 1
        mult = 0.7 if st.sc_i[s, SI_WAVES] == 1 else 1.0
        n = st.ai[e, A_WAVE_NUM]
        en = float(max(1, math.floor(mult * (2 + 1.6 * n + 0.12 * n * n))))
        tor = st.ai[e, A_TORCHES]
        if not st.sc_i[s, SI_SIM_ONLY]:
            en *= 1 - min(0.3, tor / 40)
        else:
            en *= 1 - min(0.8, tor / 15)
        en = float(py_round(en))
        st.af[e, AF_ENEMIES] = en
        st.ai[e, A_SPAWNED] += int(en)
        st.ai[e, A_WAVE_LOSSES] = 0
        st.ai[e, A_WAVE_BREACHES] = 0
        st.af[e, AF_WAVE_MOB_S] = 0.0
        st.ai[e, A_PHASE] = PH_WAVE
        st.af[e, AF_PHASE_UNTIL] = t + 240
        if st.ai[e, A_RING]:
            h = float(RING - st.ai[e, A_WALL])
            give_all0(st, e, K_HOLES, h)
            give_all0(st, e, K_P_HOLES, h)
    elif ph == PH_WAVE:
        st.af[e, AF_ENEMIES] = 0
        st.af[e, AF_INSIDE] = 0
        st.ai[e, A_PHASE] = PH_BREAK
        st.af[e, AF_PHASE_UNTIL] = t + 20


@jit
def world(st, e, dt):
    mt = st.mt
    respawn(st, e)
    s = st.ai[e, A_SCEN]
    t = st.af[e, AF_T]
    wave = st.ai[e, A_PHASE] == PH_WAVE
    ring = st.ai[e, A_RING] == 1
    gateway = st.ai[e, A_GATE_OPEN] == 1 or st.ai[e, A_DOOR] == 0
    open_ = 2 if gateway else 0
    if ring:
        wallf = max(0.0, float(st.ai[e, A_WALL] - open_)) / RING
    else:
        wallf = 0.0
    leak = 1 - wallf
    if ring and gateway:
        leak = max(leak, W_LEAK)
    leak *= (1 - 0.4 * st.ai[e, A_MOAT] / MOAT) * (0.7 if st.ai[e, A_MOAT_LIQ] else 1.0)
    # crops, saplings, the young grow
    n = st.ai[e, A_N_CROPS]
    if n:
        k = 0
        for j in range(n):
            if st.crops[e, j, 0] <= t:
                nr = st.ai[e, A_N_RIPE]
                if nr < CAP_CROPS:
                    st.ripe[e, nr, 0] = int(st.crops[e, j, 1])
                    st.ripe[e, nr, 1] = int(st.crops[e, j, 2])
                    st.ai[e, A_N_RIPE] = nr + 1
                else:
                    st.ai[e, A_OVERFLOW] += 1
            else:
                for c in range(3):
                    st.crops[e, k, c] = st.crops[e, j, c]
                k += 1
        st.ai[e, A_N_CROPS] = k
    n = st.ai[e, A_N_SAPLINGS]
    if n:
        k = 0
        g = 0
        for j in range(n):
            if st.saplings[e, j] <= t:
                g += 1
            else:
                st.saplings[e, k] = st.saplings[e, j]
                k += 1
        if g:
            st.ai[e, A_TREES] += g
            st.ai[e, A_GROWN] += g
        st.ai[e, A_N_SAPLINGS] = k
    n = st.ai[e, A_N_YOUNG]
    if n:
        k = 0
        for j in range(n):
            if st.young[e, j, 0] <= t:
                pen_add(st, e, int(st.young[e, j, 1]), 1)
            else:
                st.young[e, k, 0] = st.young[e, j, 0]
                st.young[e, k, 1] = st.young[e, j, 1]
                k += 1
        st.ai[e, A_N_YOUNG] = k
    hunger = st.sc_i[s, SI_HUNGER]
    unarmed_s = st.sc_f[s, SF_UNARMED]
    for i in range(st.ai[e, A_NAG]):
        if not st.gi[e, i, GI_ALIVE]:
            if t >= st.gf[e, i, GF_FREE_AT]:
                st.gi[e, i, GI_ALIVE] = 1
                st.gf[e, i, GF_HEALTH] = 20.0
                st.gf[e, i, GF_DIST] = 0.5
                st.gi[e, i, GI_POST] = 0
                st.gi[e, i, GI_DEPTH] = 0
                st.gf[e, i, GF_FOOD] = 20.0
                st.gf[e, i, GF_SAT] = 5.0
            continue
        depth = st.gi[e, i, GI_DEPTH]
        if hunger:
            drain = dt / H_DRAIN * (1.5 if (depth or wave) else 1.0)
            if st.gf[e, i, GF_SAT] > 0:
                st.gf[e, i, GF_SAT] = max(0.0, st.gf[e, i, GF_SAT] - drain)
            else:
                st.gf[e, i, GF_FOOD] = max(0.0, st.gf[e, i, GF_FOOD] - drain)
            if st.gf[e, i, GF_FOOD] >= H_REGEN:
                st.gf[e, i, GF_HEALTH] = min(20.0, st.gf[e, i, GF_HEALTH] + 0.25 * dt)
            elif st.gf[e, i, GF_FOOD] <= 0:
                hp = dt / H_STARVE
                st.gf[e, i, GF_HEALTH] -= hp
                give0(st, e, i, K_DAMAGE, hp)
                if st.gf[e, i, GF_HEALTH] <= 0:
                    give0(st, e, i, K_ACCIDENT, 1.0)
                    die(st, e, i, False, True)
                    continue
        else:
            st.gf[e, i, GF_HEALTH] = min(20.0, st.gf[e, i, GF_HEALTH] + 0.25 * dt)
        if depth:
            lit = 0.5 if st.bag[e, i, I_TORCH] else 1.0
            hurt(st, e, i, CAVE_HITS[depth] * lit, dt, 4.0, False)
            risk = st.sc_f[s, SF_LAVA_RISK] if depth == 2 else 0.0
            if (st.flags[FL_NOISE] == 1 or risk) and rnd(mt, e) < (MINE_ACC[depth] + risk) * dt / 10:
                if st.bag[e, i, I_WATER_B] and rnd(mt, e) < 0.85:
                    add(st, e, i, I_WATER_B, -1)
                    add(st, e, i, I_BUCKET, 1)
                    ch = new_chain()
                    nch = add_cause(st, e, i, ch, 0, I_WATER_B, V_WATER)
                    if nch == 0:
                        nch = chain_add(ch, 0, st.gi[e, i, GI_DID], i, int(st.gf[e, i, GF_DID_T] * 20), KIND_REFLEX_WATER, V_WATER)
                    effect_of(st, e, i, ch, nch, EF_AGENT_SAVED)
                    continue
                give0(st, e, i, K_ACCIDENT, 1.0)
                die(st, e, i, False, True)
                continue
            if st.gf[e, i, GF_HEALTH] <= 0:
                die(st, e, i, False, False)
            continue
        if sheltered(st, e, i) and night_or_wave(st, e):
            st.af[e, AF_OB_SHELTER] += dt
            continue
        nm = st.sc_f[s, SF_NIGHT_M]
        if nm and st.gi[e, i, GI_ALIVE]:
            m = nm + st.af[e, AF_T] / 60
            deep = st.gi[e, i, GI_NEAR_VALID] == 1 and depth == 0 and nr_pit(st, e, i) >= 2
            hurt(st, e, i, 0.035 * m * (0.5 if deep else 1.0), dt, 3.0, False)
            held = st.gi[e, i, GI_HELD]
            if held and poisson(st, e, 0.01 * held * dt):
                st.ai[e, A_KILLS] += 1
            if st.gf[e, i, GF_HEALTH] <= 0:
                die(st, e, i, True, False)
            continue
        if not wave:
            continue
        en = max(0.0, st.af[e, AF_ENEMIES])
        if en <= 0 and st.af[e, AF_INSIDE] <= 0:
            continue
        dist = st.gf[e, i, GF_DIST]
        inside_a = dist < 8 and ring
        held = st.gi[e, i, GI_HELD]
        unarmed = 1.0 if held else unarmed_s
        if dist > 10:
            hurt(st, e, i, W_HURT * 0.08 * math.sqrt(en), dt, 3.0, False)
        elif st.gi[e, i, GI_HOLDING] and st.gi[e, i, GI_POST]:
            hurt(st, e, i, W_HURT * 0.025 * math.sqrt(en) * (0.4 + leak), dt, 3.0, False)
            k = min(poisson(st, e, W_KILL * (0.05 + 0.04 * held) * unarmed * dt * min(1.0, en / 2)), int(math.ceil(st.af[e, AF_ENEMIES])))
            if k:
                kill(st, e, i, k, False, -1, new_chain(), -1)
        else:
            hurt(st, e, i, W_HURT * 0.025 * en * (leak if inside_a else 1.0), dt, 3.0, inside_a)
            if leak > 0.3 or not ring:
                k = min(poisson(st, e, W_KILL * 0.012 * (1 + held) * unarmed * dt), int(math.ceil(st.af[e, AF_ENEMIES])))
                if k:
                    kill(st, e, i, k, False, -1, new_chain(), -1)
        if inside_a and st.af[e, AF_INSIDE] > 0:
            hurt(st, e, i, W_HURT * 0.05 * st.af[e, AF_INSIDE], dt, 3.0, True)
            k = min(poisson(st, e, W_KILL * 0.04 * (1 + held) * (1.0 if held else unarmed_s) * dt), int(math.ceil(st.af[e, AF_INSIDE])))
            if k:
                kill(st, e, i, k, True, -1, new_chain(), -1)
        if st.gf[e, i, GF_HEALTH] <= 0:
            die(st, e, i, not inside_a, False)
    if wave:
        moat = st.ai[e, A_MOAT]
        if ring and st.ai[e, A_WAVE_NUM] >= 6 and st.ai[e, A_WALL] > 0:
            st.ai[e, A_WALL] = max(0, st.ai[e, A_WALL] - poisson(st, e, 0.01 * st.af[e, AF_ENEMIES] * dt * (1 - 0.5 * moat / MOAT)))
        if st.ai[e, A_MOAT_LIQ] == 2 and st.af[e, AF_ENEMIES] > 0 and moat > 0:
            k = min(poisson(st, e, 0.03 * st.af[e, AF_ENEMIES] * dt * moat / MOAT), int(st.af[e, AF_ENEMIES]))
            if k:
                who = st.ai[e, A_ML_AGENT]
                if who < 0 or who >= st.ai[e, A_NAG]:
                    who = 0
                ch = new_chain()
                nch = chain_add(ch, 0, st.ai[e, A_ML_DID], st.ai[e, A_ML_AGENT], st.ai[e, A_ML_TICK], st.ai[e, A_ML_KIND], st.ai[e, A_ML_VIA])
                kill(st, e, who, k, False, -1, ch, nch)
        if ring and st.af[e, AF_ENEMIES] > 0:
            k = min(poisson(st, e, 0.02 * st.af[e, AF_ENEMIES] * leak * dt), int(st.af[e, AF_ENEMIES]))
            if k:
                st.af[e, AF_ENEMIES] -= k
                st.af[e, AF_INSIDE] += k
                st.ai[e, A_BREACHES] += k
                st.ai[e, A_WAVE_BREACHES] += k
                give_all0(st, e, K_P_BREACH, float(k))
        ins = st.af[e, AF_INSIDE]
        if ins > 0:
            st.af[e, AF_MOB_S] += ins * dt
            st.af[e, AF_WAVE_MOB_S] += ins * dt
            give_all0(st, e, K_P_MOB_S, ins * dt)
        if st.af[e, AF_ENEMIES] <= 0.5 and st.af[e, AF_INSIDE] <= 0.5:
            st.af[e, AF_ENEMIES] = 0
            st.af[e, AF_INSIDE] = 0
            st.ai[e, A_CLEARED] += 1
            give_all0(st, e, K_WAVE_CLEARED, 1.0)
            give_all0(st, e, K_P_WAVE_CLEARED, 1.0)
            if st.ai[e, A_WAVE_BREACHES] == 0 and ring:
                st.ai[e, A_CLEAN] += 1
                nb_ = st.ai[e, A_N_BUILDERS]
                mb = min(8, nb_)
                for i in range(st.ai[e, A_NAG]):
                    ch = new_chain()
                    nch = 0
                    for j in range(nb_ - mb, nb_):
                        q = j % 8
                        nch = chain_add(ch, nch, int(st.builders[e, q, 0]), int(st.builders[e, q, 2]), int(st.builders[e, q, 1] * 20),
                                        int(st.builders[e, q, 3]), V_COBBLE)
                    give(st, e, i, K_P_CLEAN, 1.0, ch, nch)
            st.ai[e, A_PHASE] = PH_BREAK
            st.af[e, AF_PHASE_UNTIL] = st.af[e, AF_T] + (60 if st.sc_i[s, SI_LONG] else 20)
        elif st.ai[e, A_WAVE_LOSSES] >= LOSSES_PER_WAVE:
            st.ai[e, A_DONE] = 1
            st.ai[e, A_REASON] = DONE_LOST


@jit
def crew_delta(st, e, col, key, v, first):
    was = st.af[e, col]
    st.af[e, col] = v
    if not first and v != was:
        give_all0(st, e, key, v - was)


@jit
def measure(st, e, first):
    s = st.ai[e, A_SCEN]
    if st.sc_i[s, SI_SIM_ONLY]:
        return
    ring = st.ai[e, A_RING]
    crew_delta(st, e, AF_CT_WALL, K_S_WALL, st.ai[e, A_WALL] / RING if ring else 0.0, first)
    crew_delta(st, e, AF_CT_MOAT, K_S_MOAT, st.ai[e, A_MOAT] / MOAT, first)
    crew_delta(st, e, AF_CT_LIGHT, K_S_LIGHT, min(1.0, st.ai[e, A_TORCHES] / 12), first)
    crew_delta(st, e, AF_CT_GATE, K_S_GATE, 1.0 if (st.ai[e, A_DOOR] and not st.ai[e, A_GATE_OPEN]) else 0.0, first)
    crew_delta(st, e, AF_CT_ETABLE, K_E_TABLE, 1.0 if st.ai[e, A_ENCH_TABLE] else 0.0, first)
    crew_delta(st, e, AF_CT_SOURCE, K_SU_SOURCE, sources(st, e), first)
    crew_delta(st, e, AF_CT_STOCK, K_ST_VALUE, stock(st, e), first)
    f = min(st.af[e, AF_SUS_FR], st.af[e, AF_SUS_FE])
    wd = min(st.af[e, AF_SUS_WR], st.af[e, AF_SUS_WU])
    if not first:
        if f > st.af[e, AF_SUS_PAID0]:
            nl = st.ai[e, A_N_LASTREN]
            ch = np.zeros((max(1, nl), 5), np.int64)
            for j in range(nl):
                ch[j, 0] = st.lastren[e, j, 0]
                ch[j, 1] = st.lastren[e, j, 1]
                ch[j, 2] = 0
                ch[j, 3] = KIND_PLANT_WHEAT
                ch[j, 4] = V_WHEAT
            give_all(st, e, K_SU_FOOD, f - st.af[e, AF_SUS_PAID0], ch, nl)
        if wd > st.af[e, AF_SUS_PAID1]:
            give_all0(st, e, K_SU_WOOD, wd - st.af[e, AF_SUS_PAID1])
    st.af[e, AF_SUS_PAID0] = f
    st.af[e, AF_SUS_PAID1] = wd
    for i in range(st.ai[e, A_NAG]):
        held = st.gi[e, i, GI_HELD]
        sw = tier_of(st, e, i, IT_SWORD)
        eq0 = min(1.0, armor(st, e, i) / 20)
        eq1 = min(1.0, max(held, sw) / 4)
        eq2 = min(1.0, tier_of(st, e, i, IT_PICK) / 4)
        eq3 = 1.0 if (st.bag[e, i, I_BOW] and st.bag[e, i, I_ARROW] >= 4) else 0.0
        alive = st.gi[e, i, GI_ALIVE] == 1
        for q in range(4):
            v = eq0 if q == 0 else (eq1 if q == 1 else (eq2 if q == 2 else eq3))
            was = st.gf[e, i, GF_TRK_ARMOR + q]
            st.gf[e, i, GF_TRK_ARMOR + q] = v
            if not first and v != was and alive:
                give0(st, e, i, EQ_KEYS[q], v - was)
        was = st.gf[e, i, GF_TRK_DEPTH]
        d = st.gi[e, i, GI_DEPTH]
        if d > was:
            st.gf[e, i, GF_TRK_DEPTH] = d
            if not first:
                give0(st, e, i, K_M_DEPTH, d - was)


@jit
def advance(st, e, until):
    s = st.ai[e, A_SCEN]
    sim_only = st.sc_i[s, SI_SIM_ONLY]
    while st.af[e, AF_T] < until - 1e-9 and not st.ai[e, A_DONE]:
        dt = min(2.0, until - st.af[e, AF_T])
        st.af[e, AF_T] += dt
        for i in range(st.ai[e, A_NAG]):
            if st.gi[e, i, GI_ALIVE]:
                give0(st, e, i, K_TIME, dt)
        world(st, e, dt)
        measure(st, e, False)
        t = st.af[e, AF_T]
        if not sim_only:
            for i in range(st.ai[e, A_NAG]):
                if st.gi[e, i, GI_ALIVE] and t - st.gf[e, i, GF_PROGRESS_T] >= STALL_S:
                    st.gf[e, i, GF_PROGRESS_T] = t
                    give0(st, e, i, K_STALL, 1.0)
        if st.ai[e, A_PHASE] != PH_OFF and t >= st.af[e, AF_PHASE_UNTIL]:
            next_phase(st, e)
        if t >= st.sc_f[s, SF_SECONDS] and not st.ai[e, A_DONE]:
            st.ai[e, A_DONE] = 1
            st.ai[e, A_REASON] = DONE_TIME


# ---- the protocol (sim_env.decide, step, close, reset) -----------------------------------------------------------------
@jit
def emit_row(st, e, i, row, obs, mask, rew, facts_out, valid):
    j = e * MA + i
    raw(st, e, i, row)
    obs_of(row, obs[j])
    mask_of(row, mask[j])
    rew[j] = st.gf[e, i, GF_ACC]
    st.gf[e, i, GF_ACC] = 0.0
    for k in range(NKEY):
        facts_out[j, k] = st.facts[e, i, k]
        st.facts[e, i, k] = 0.0
    valid[j] = True


@jit
def decide(st, e, ready, first, row, obs, mask, rew, facts_out, valid):
    if not first:
        measure(st, e, False)
    for i in range(st.ai[e, A_NAG]):
        if ready[i]:
            emit_row(st, e, i, row, obs, mask, rew, facts_out, valid)
    st.af[e, AF_PROGRESS_OUT] = progress(st, e)
    st.ai[e, A_SUCCESS] = 1 if (st.ai[e, A_DONE] and success(st, e)) else 0


@jit
def close(st, e, row, obs, mask, rew, facts_out, valid):
    s = st.ai[e, A_SCEN]
    if st.ai[e, A_REASON] == DONE_GOAL or (st.sc_f[s, SF_PASS_ALIVE] >= 0 and success(st, e)):
        give_all0(st, e, K_GOAL, 1.0)
    ready = np.ones(MA, np.bool_)
    for i in range(st.ai[e, A_NAG]):
        st.gi[e, i, GI_PEND] = 0
    decide(st, e, ready, False, row, obs, mask, rew, facts_out, valid)


@jit
def step_one(st, e, acts, row, obs, mask, rew, facts_out, valid, assigned):
    """sim_env.step for arena e: acts[i] the action of agent i (-1: none)"""
    nag = st.ai[e, A_NAG]
    for i in range(nag):
        act = acts[i]
        if act < 0:
            continue
        if act == 0:
            give0(st, e, i, K_NOOP, 1.0)
            st.gi[e, i, GI_LAST] = 0
            st.gi[e, i, GI_OUTCOME] = OUT_NONE
            st.ai[e, A_DONE] = 1
            st.ai[e, A_REASON] = DONE_NOOP
            close(st, e, row, obs, mask, rew, facts_out, valid)
            return
        begin(st, e, i, act, row)
        assigned[e, i] = st.gi[e, i, GI_DID]
    ready = np.zeros(MA, np.bool_)
    while not st.ai[e, A_DONE]:
        t = st.af[e, AF_T]
        for i in range(nag):
            if st.gi[e, i, GI_ALIVE] and st.gi[e, i, GI_PEND] and st.gf[e, i, GF_FREE_AT] <= t + 1e-9:
                finish(st, e, i)
        if goal_met(st, e):
            st.ai[e, A_DONE] = 1
            st.ai[e, A_REASON] = DONE_GOAL
            break
        t = st.af[e, AF_T]
        anyr = False
        for i in range(nag):
            ready[i] = st.gi[e, i, GI_ALIVE] == 1 and st.gi[e, i, GI_PEND] == 0 and st.gf[e, i, GF_FREE_AT] <= t + 1e-9
            anyr = anyr or ready[i]
        if anyr:
            decide(st, e, ready, False, row, obs, mask, rew, facts_out, valid)
            return
        nxt = t + 2.0
        if st.af[e, AF_PHASE_UNTIL] < nxt:
            nxt = st.af[e, AF_PHASE_UNTIL]
        for i in range(nag):
            fa = st.gf[e, i, GF_FREE_AT]
            if fa > t + 1e-9 and fa < nxt:
                nxt = fa
        advance(st, e, max(nxt, t + 1e-3))
    close(st, e, row, obs, mask, rew, facts_out, valid)


@jit
def reset_one(st, e, scen, seed_key, crew, target, force, row, obs, mask, rew, facts_out, valid):
    from_seed(st, e, seed_key)
    mt = st.mt
    s = scen
    noise = st.flags[FL_NOISE] == 1
    obst = st.flags[FL_OBST] / 1000.0
    for c in range(NAI):
        if c != A_DID_NEXT and c != A_EPISODES and c != A_OVERFLOW:
            st.ai[e, c] = 0
    for c in range(NAF):
        st.af[e, c] = 0.0
    st.ai[e, A_EPISODES] += 1
    st.ai[e, A_SCEN] = s
    st.ai[e, A_TARGET] = target
    st.ai[e, A_FORCE] = force
    st.ai[e, A_TREES] = 0
    st.ai[e, A_TREES_HIDDEN] = randint(mt, e, 14, 34)
    st.ai[e, A_STONE_SEEN] = randint(mt, e, 0, 2)
    st.ai[e, A_COAL] = 0
    st.ai[e, A_COAL_HIDDEN] = randint(mt, e, 2, 8)
    st.ai[e, A_IRON] = 0
    st.ai[e, A_IRON_HIDDEN] = randint(mt, e, st.sc_i[s, SI_IRON_LO], st.sc_i[s, SI_IRON_HI])
    st.ai[e, A_GRAVEL] = randint(mt, e, 0, 3)
    st.ai[e, A_ANIMALS] = 0
    st.ai[e, A_ANIMALS_HIDDEN] = randint(mt, e, 2, 8) if st.sc_i[s, SI_MANY_ANIMALS] else randint(mt, e, 0, 4)
    st.ai[e, A_GRASS] = 0
    st.ai[e, A_LEAVES] = 0
    st.ai[e, A_SAPLING_ROOM] = SAPLING_ROOM[below(mt, e, 7)]
    st.ai[e, A_CANE] = randint(mt, e, 0, 3)
    st.ai[e, A_CANE_HIDDEN] = randint(mt, e, 0, 6)
    st.ai[e, A_MINE_LEVELS] = 0
    st.af[e, AF_MINE_D] = uniform(mt, e, 4.0, 10.0)
    for lv in range(3):
        for o in range(NORE):
            st.seen[e, lv, o] = 0
            st.hidden[e, lv, o] = 0
    for lv in range(1, 3):
        for j in range(NORE):
            o = LEVEL_KEYS[lv, j]
            if o >= 0 and o != O_LAVA:
                st.hidden[e, lv, o] = randint(mt, e, LEVEL_LO[lv, o], LEVEL_HI[lv, o])
    st.hidden[e, 2, O_LAVA] = randint(mt, e, 1, 3)
    if st.sc_i[s, SI_RICH]:
        st.hidden[e, 2, O_DIAMOND] = randint(mt, e, 3, 6)
    st.af[e, AF_TABLE_D] = 64.0
    st.af[e, AF_FURNACE_D] = 64.0
    md = st.sc_i[s, SI_MOAT_DUG]
    st.ai[e, A_RING] = 1 if md else 0
    st.ai[e, A_WALL] = RING if md else 0
    st.ai[e, A_MOAT] = MOAT if md else 0
    for k in range(N_ANIMALS):
        st.pen_adults[e, k] = 0
        st.pen_order[e, k] = -1
        st.pen_rest[e, k] = 0.0
    if st.sc_i[s, SI_PASTURE]:
        st.pen_wild[e, 0] = 3
        st.pen_wild[e, 1] = 3
    else:
        st.pen_wild[e, 0] = 0
        st.pen_wild[e, 1] = 0
    st.ai[e, A_PHASE] = PH_PREP if st.sc_i[s, SI_WAVES] else PH_OFF
    st.af[e, AF_PHASE_UNTIL] = st.sc_f[s, SF_PREP] if st.sc_i[s, SI_WAVES] else 1e18
    for k in range(NIT):
        st.items_seen[e, k] = False
    for v in range(NVIA):
        for f in range(NEF):
            st.links_ep[e, v, f] = False
    for k in range(NCOMP):
        st.comp[e, k] = 0.0
    st.ai[e, A_POND] = 1 if (st.sc_i[s, SI_POND] or rnd(mt, e) < 0.6) else 0
    st.ai[e, A_LAVA_POOL] = st.sc_i[s, SI_LAVA_POOL]
    st.ai[e, A_ML_AGENT] = -1
    p_hang = 1.0 if force == FORCE_HANG else 0.35 * obst
    if noise and st.sc_i[s, SI_HANG_SCEN] and rnd(mt, e) < p_hang:
        h = 2 + below(mt, e, 2)
        k = randint(mt, e, 4, 6)
        cells = 0
        for y in range(h, h + k):
            cells |= (1 << y)
        st.ai[e, A_HANG] = 1
        st.ai[e, A_HANG_CELLS] = cells
        st.ai[e, A_TREES_HIDDEN] = randint(mt, e, 4, 10)
    nag = max(1, min(MA, crew))
    st.ai[e, A_NAG] = nag
    for i in range(MA):
        for c in range(NGI):
            st.gi[e, i, c] = 0
        for c in range(NGF):
            st.gf[e, i, c] = 0.0
        for k in range(NIT):
            st.bag[e, i, k] = 0
            st.made_did[e, i, k] = -1
        for p in range(4):
            st.worn[e, i, p] = -1
        for k in range(NSTUCK):
            st.stuck[e, i, k] = False
        for k in range(NACH):
            st.ach[e, i, k] = False
        for k in range(A):
            st.fails_run[e, i, k] = 0
        for k in range(NKEY):
            st.facts[e, i, k] = 0.0
        st.gi[e, i, GI_LAST] = -1
        st.gi[e, i, GI_DID] = -1
        st.gi[e, i, GI_F_LAST_FAILED] = -1
        st.gi[e, i, GI_F_GROUP] = -1
        st.gi[e, i, GI_OUTCOME] = OUT_NONE
    for i in range(nag):
        for k in range(NIT):
            if st.kit[s, k]:
                add(st, e, i, k, st.kit[s, k])
        st.gf[e, i, GF_HEALTH] = st.sc_f[s, SF_HEALTH]
        st.gf[e, i, GF_FOOD] = 20.0
        st.gf[e, i, GF_SAT] = 5.0
        st.gi[e, i, GI_XP] = st.sc_i[s, SI_XP]
        st.gi[e, i, GI_ALIVE] = 1
        st.gf[e, i, GF_DIST] = uniform(mt, e, 0.0, 3.0)
        st.ach[e, i, H_OPEN_INV] = True
    if st.sc_i[s, SI_HAS_CHEST]:
        st.ai[e, A_CHEST] = 1
        st.ai[e, A_CHEST_FOOD] = st.sc_i[s, SI_CHEST]
    for i in range(nag):
        for k in range(NIT_BAG):
            if st.bag[e, i, k] > 0:
                st.items_seen[e, k] = True
    measure(st, e, True)
    ready = np.zeros(MA, np.bool_)
    for i in range(nag):
        ready[i] = True
    decide(st, e, ready, True, row, obs, mask, rew, facts_out, valid)


@jit
def from_seed(st, e, key):
    from_key(st.mt, st.gnext, e, key)


# ---- the batch calls --------------------------------------------------------------------------------------------------
@nb.njit(cache=True, parallel=True)
def step_batch(st, acts, active, obs, mask, rew, facts_out, valid, assigned):
    """every arena e with active[e]: sim_env.step(acts[e]); the rows of the agents at a decision (or all, at the end)
    in obs/mask/rew/facts_out[e * MA + i] with valid set"""
    n = acts.shape[0]
    for e in nb.prange(n):
        for i in range(MA):
            valid[e * MA + i] = False
            assigned[e, i] = -1
        if not active[e]:
            continue
        st.nev[e] = 0
        st.nlink[e] = 0
        row = np.empty(R_W)
        step_one(st, e, acts[e], row, obs, mask, rew, facts_out, valid, assigned)


@nb.njit(cache=True, parallel=True)
def reset_batch(st, which, scen, keys, nkeys, crew, target, force, obs, mask, rew, facts_out, valid):
    """reset the arenas which[j] (scenario, the seed's 32-bit words keys[j, :nkeys[j]], crew, target, force)"""
    n = which.shape[0]
    for j in nb.prange(n):
        e = which[j]
        for i in range(MA):
            valid[e * MA + i] = False
        st.nev[e] = 0
        st.nlink[e] = 0
        row = np.empty(R_W)
        reset_one(st, e, scen[j], keys[j, :nkeys[j]], crew[j], target[j], force[j], row, obs, mask, rew, facts_out, valid)
