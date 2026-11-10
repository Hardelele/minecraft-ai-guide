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
       "PEN_ORDER_N SUCCESS V4 GOAL_SENT MOB_NEXT N_FOUND B_SEEN ACTOR HURT_MOB N_OUT N_IN N_FEV N_FEVL NIGHT_ORD "
       "C4_PHASE C4_PREV_CALM C4_DAY_N C4_THR_N C4_POOL_N C4_MOVES_N C4_EP_FIRST C4_ERR C4_FORGOT C4_DAYFULL REC_NF REC_NE REC_NL").split()
_AF = ("T MINE_D TABLE_D FURNACE_D PHASE_UNTIL ENEMIES INSIDE MOB_S WAVE_MOB_S SUS_FR SUS_FE SUS_WR SUS_WU SUS_PAID0 SUS_PAID1 CT_WALL CT_MOAT "
       "CT_LIGHT CT_GATE CT_ETABLE CT_SOURCE CT_STOCK OB_SHELTER PROGRESS_OUT C4_TOTAL C4_T C4_CALM_END").split()
_GI = ("XP HELD DEPTH TUN0 TUN1 TUN2 POST ALIVE PEND PEND_ACT PEND_OK PEND_REASON HOLDING DID KIND LAST OUTCOME REPEATED TRIP_ORES TRIP_OUT TRIPS "
       "ENCHANTED DITCH LAVA_FRONT PIT_FAILS NEAR_VALID NEAR_GROUND NEAR_PICK NEAR_Y NEAR_PLACED NEAR_N MOB_ON MOB_X MOB_Y MOB_Z "
       "F_LAST_FAILED F_GROUP F_WIN_N F_WIN_HEAD HAS_RESPAWN BOX_N NIGHT_N NIGHT_ORD DANGER_N C4_CUR REC_N OPEN_N").split()
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
EV_CAP, LINK_CAP = 96, 512   # an arena's events and links in one step call (the default; new_state's ev_cap)
EV_REWARD, EV_NOVEL, EV_ACH, EV_MOVE = 0, 1, 2, 3
# ---- the reward v4's constants
for _j, _n in enumerate(T.EV4):
    globals()["E4_" + {"path_changed": "PATH", "attack_failed": "ATTACK_FAILED", "mob_gone": "GONE"}.get(_n, _n.upper())] = _j
P4_DAY, P4_NIGHT, P4_PREP, P4_WAVE, P4_BREAK = (T.PHASES4.index(k) for k in ("day", "night", "prep", "wave", "break"))
CALM4, NOCUR4 = T.CALM4, T.NOCUR4
NC4 = T.NC4
C4X = T.C4X
C4_NOOP, C4_GOAL, C4_DMG, C4_DEATH, C4_BREACH, C4_MOB_S, C4_SURRENDER, C4_AFK = (C4X[k] for k in (
    "noop", "goal", "crew.damage", "crew.death", "base.breach", "base.mob_seconds", "surrender", "afk"))
C4_SURVIVE, C4_SCOUT, C4_WASTED, C4_SCALE, C4_NOVEL = (C4X[k] for k in ("credit.survive", "credit.scout", "credit.wasted_day", "credit.scale",
                                                                    "curiosity.novel"))
R4_KILL, R4_BLOCK, R4_COVER = 0, 1, 2          # the contributions' reasons: credit.kill, credit.block, credit.cover
C4_CREDIT0 = C4X["credit.kill"]
assert (C4X["credit.block"], C4X["credit.cover"]) == (C4_CREDIT0 + 1, C4_CREDIT0 + 2)
KEY_SCALE4, SW4_C, SW4_HAS, FAC4 = T.KEY_SCALE4, T.SW4_C, T.SW4_HAS, T.FAC4
_p = T.P4F
O_NOOP, O_GOAL, O_DMG, O_DEATH, O_BREACH, O_MOB_S = _p["noop"], _p["goal"], _p["dmg"], _p["death"], _p["breach"], _p["mob_s"]
ACCIDENT_X, BASE_R, FAR_PER, FAR_CAP, FINDS_SOFTEN, SOFTEN_CAP = (_p[k] for k in ("accident_x", "base_r", "far_per", "far_cap", "finds_soften",
                                                                                  "soften_cap"))
AFK_RATE, AFK_GRACE, AFK_RAMP, AFK_ACTIVE, NEAR_R = _p["afk_rate"], _p["afk_grace"], _p["afk_ramp"], _p["afk_active"], _p["near_r"]
AGENT_HITS, TH_KILL, TH_DETER, DELAY_TAU, DODGE_S = _p["agent_hits"], _p["th_kill"], _p["th_deter"], _p["delay_tau"], _p["dodge_s"]
W_KILL4, W_BLOCKED, W_DETOUR, W_COVER = _p["w_kill"], _p["w_blocked"], _p["w_detour"], _p["w_cover"]
HP_LOW, HP_SAFE, SURV_SHARE, SCOUT_SHARE, WD_SHARE, SCALE_MOVE = (_p[k] for k in ("hp_low", "hp_safe", "surv_share", "scout_share", "wd_share",
                                                                                  "scale_move"))
ACCIDENT_IS = T.ACCIDENT_IS
ACT_BOX = T.ACT_BOX
TG_BASE4 = MA                                  # a threat's target: an agent, or the base
FK = ["coal", "iron", "gold", "redstone", "diamond", "obsidian", "trees"]       # found4's kinds
FK_COMP = np.array([C4X[T.FOUND4[k][0]] for k in FK], np.int64)
FK_AMOUNT = np.array([float(T.FOUND4[k][1]) for k in FK])
_FQ_KEYS = ["mine.coal", "mine.iron", "mine.gold", "mine.redstone", "mine.diamond", "mine.obsidian"]
FK_FQ = np.array([_FQ_KEYS.index(T.FOUND4[k][0]) if T.FOUND4[k][0] in _FQ_KEYS else -1 for k in FK], np.int64)
FQ_OF_KEY = np.array([_FQ_KEYS.index(k) if k in _FQ_KEYS else -1 for k in T.KEYS], np.int64)
FK_OF_ORE = np.array([FK.index(o) if o in FK else -1 for o in T.ORES], np.int64)
FK_TREES, FK_COAL, FK_IRON = FK.index("trees"), FK.index("coal"), FK.index("iron")
CA_X, CA_Y, CA_Z, CA_HP, CA_ALIVE, CA_BASE, CA_IDLE, CA_TRIP, CA_DANGER, CA_DANGER_AT, CA_EXISTS = range(11)
FEV_CAP, FEVL_CAP, FQ_CAP, NIGHT_CAP, BOX_CAP, DANGER_CAP, REC4_CAP = 256, 1024, 64, 32, 64, 64, 16
DEC_CAP, DAY_CAP, THR_CAP, POOL_CAP, FIND_CAP, MV_CAP = 1024, 512, 256, 128, 256, 64
V_CREDIT = T.VIA["credit"]
# a link: decision id, agent, tick, kind (an action, or KIND_REFLEX_WATER), via
FIELDS = ("ai af gi gf bag worn made_did made_t made_kind stuck ach fails_run win_act win_fail cells drops "
          "seen hidden pen_adults pen_order pen_wild pen_rest items_seen journal links_ep builders crops ripe saplings "
          "young renfood renwheat suswin lastren mt gnext comp facts ev_i ev_f links nev nlink flags sc_f sc_i kit w fac "
          "scale_on mt4 gnext4 fev_i fev_f fev_l fq fq_kind fq_n mobs_out mobs_in night box c4ag danger rec4 open4 open_ord ep4 dec_id "
          "dec_agent dec_t dec_kind dec_prod dec_end day4 thr_mob thr_dmg thr_until thr_inside thr_nt thr_tg thr_taken pool_i pool_w find_d "
          "find_a find_v mv_i mv_f rec_ff rec_fi rec_ei rec_ef rec_l").split()


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
FL_NOISE, FL_OBST, FL_REC, FL_V4, FL_MERGE = 0, 1, 2, 3, 4
NO_CHAIN = np.zeros((1, 5), np.int64)


def new_state(n: int, scen_tables: dict, noise: bool = True, obstacles: float = 1.0, v4: bool = False, record: bool = False,
              ev_cap: int = EV_CAP, merge: bool = False):
    """-> (the kernels' StateRef, the same arrays by name for Python)"""
    from simbatch_rng import new_state as rng_state
    mt, gn = rng_state(n)
    sf = np.stack([scen_tables[k].astype(np.float64) for k in SC_F], 1)
    si = np.stack([scen_tables[k].astype(np.int64) for k in SC_I], 1)
    ai = np.zeros((n, NAI), np.int64)
    ai[:, A_DID_NEXT] = (np.arange(n, dtype=np.int64) << 32) + 1
    m4 = n if v4 else 1        # the reward v4's arrays: only when it is asked for
    mr = n if (v4 and record) else 1
    mt4, gn4 = rng_state(m4)
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
        ev_i=np.zeros((n, ev_cap, 8), np.int64), ev_f=np.zeros((n, ev_cap)), links=np.zeros((n, max(LINK_CAP, 4 * ev_cap), 5), np.int64),
        nev=np.zeros(n, np.int64), nlink=np.zeros(n, np.int64),
        flags=np.array([1 if noise else 0, int(round(obstacles * 1000)), 1 if (v4 and record) else 0, 1 if v4 else 0, 1 if merge else 0],
                       np.int64),
        sc_f=sf, sc_i=si, kit=scen_tables["kit"], w=scen_tables["w"], fac=scen_tables["fac"], scale_on=scen_tables["scale_on"],
        mt4=mt4, gnext4=gn4, fev_i=np.zeros((m4, FEV_CAP, 9), np.int64), fev_f=np.zeros((m4, FEV_CAP, 3)), fev_l=np.zeros((m4, FEVL_CAP), np.int64),
        fq=np.zeros((m4, 6, FQ_CAP), np.int64), fq_kind=np.zeros((m4, 6, FQ_CAP), np.int64), fq_n=np.zeros((m4, 6), np.int64),
        mobs_out=np.zeros((m4, 256), np.int64), mobs_in=np.zeros((m4, 256), np.int64), night=np.zeros((m4, MA, NIGHT_CAP), np.int64),
        box=np.zeros((m4, MA, BOX_CAP), np.int64), c4ag=np.zeros((m4, MA, 11)), danger=np.zeros((m4, MA, DANGER_CAP), np.int64),
        rec4=np.zeros((m4, MA, REC4_CAP, 2)), open4=np.zeros((m4, MA, NC4)), open_ord=np.zeros((m4, MA, NC4), np.int64), ep4=np.zeros((m4, NC4)),
        dec_id=np.full((m4, DEC_CAP), -1, np.int64), dec_agent=np.zeros((m4, DEC_CAP), np.int64), dec_t=np.zeros((m4, DEC_CAP)),
        dec_kind=np.zeros((m4, DEC_CAP), np.int64), dec_prod=np.zeros((m4, DEC_CAP), np.bool_), dec_end=np.zeros((m4, DEC_CAP)),
        day4=np.zeros((m4, DAY_CAP), np.int64), thr_mob=np.zeros((m4, THR_CAP), np.int64), thr_dmg=np.zeros((m4, THR_CAP)),
        thr_until=np.zeros((m4, THR_CAP)), thr_inside=np.zeros((m4, THR_CAP), np.bool_), thr_nt=np.zeros((m4, THR_CAP), np.int64),
        thr_tg=np.zeros((m4, THR_CAP, MA + 1), np.int64), thr_taken=np.zeros((m4, THR_CAP, MA)), pool_i=np.zeros((m4, POOL_CAP, 4), np.int64),
        pool_w=np.zeros((m4, POOL_CAP)), find_d=np.full((m4, FIND_CAP), -1, np.int64), find_a=np.zeros((m4, FIND_CAP), np.int64),
        find_v=np.zeros((m4, FIND_CAP)), mv_i=np.zeros((m4, MV_CAP, 6), np.int64), mv_f=np.zeros((m4, MV_CAP)),
        rec_ff=np.zeros((mr, 512, 2)), rec_fi=np.zeros((mr, 512, 3), np.int64), rec_ei=np.zeros((mr, 8192, 9), np.int64),
        rec_ef=np.zeros((mr, 8192, 3)), rec_l=np.zeros((mr, 16384), np.int64))
    arrays = {k: np.ascontiguousarray(arrays[k]) for k in FIELDS}
    return StateRef(*[arrays[k] for k in FIELDS]), arrays


SC_F = ("per seconds health day_sin day_cos dusk_in prep lava_risk night_m unarmed pass_kills pass_alive pass_diamonds waves_pass").split()
SC_I = ("goal item crew_goal night waves hunger xp has_chest chest iron_lo iron_hi rich moat_dug pond lava_pool sim_only hang_scen ditch_scen "
        "pasture many_animals long delay").split()
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
    if k >= st.ev_i.shape[1] or st.nlink[e] + nch > st.links.shape[1]:
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
    if st.ai[e, A_V4]:
        give4(st, e, i, key, n, ch, nch)
        return
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
    if st.ai[e, A_V4]:
        give4(st, e, i, key, n, NO_CHAIN, 0)
        return
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
def recent(st, e):
    """-> the window's food_renew, food_eaten, wood_renew, wood_used (no array: no allocation in the hot path)"""
    t = st.af[e, AF_T]
    while st.ai[e, A_N_SUSWIN] > 0 and st.suswin[e, st.ai[e, A_SUSWIN_HEAD], 0] < t - WINDOW_S:
        st.ai[e, A_SUSWIN_HEAD] = (st.ai[e, A_SUSWIN_HEAD] + 1) % CAP_SUSWIN
        st.ai[e, A_N_SUSWIN] -= 1
    o0, o1, o2, o3 = 0.0, 0.0, 0.0, 0.0
    h = st.ai[e, A_SUSWIN_HEAD]
    for j in range(st.ai[e, A_N_SUSWIN]):
        jj = (h + j) % CAP_SUSWIN
        k = int(st.suswin[e, jj, 1])
        v = st.suswin[e, jj, 2]
        if k == 0:
            o0 += v
        elif k == 1:
            o1 += v
        elif k == 2:
            o2 += v
        else:
            o3 += v
    return o0, o1, o2, o3


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
    r0, r1, r2, r3 = recent(st, e)
    r[C_food_renew] = r0
    r[C_food_eaten] = r1
    r[C_wood_renew] = r2
    r[C_wood_used] = r3
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
def allowed(r, act, stack):
    """stack: a scratch of 64 (the caller's: one for all the actions of a row)"""
    if act == 0:
        return True
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
    stack = np.empty(64, np.bool_)
    for a in range(A):
        out[a] = allowed(r, a, stack)


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
    if st.ai[e, A_V4]:
        j = fev_new(st, e, E4_DECISION)
        if j >= 0:
            st.fev_i[e, j, 1] = i
            st.fev_i[e, j, 2] = st.gi[e, i, GI_DID]
            st.fev_i[e, j, 3] = act
    raw(st, e, i, row)
    if not allowed(row, act, np.empty(64, np.bool_)):
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
        st.ai[e, A_ACTOR] = i
        res = effect(st, e, i, act)
        st.ai[e, A_ACTOR] = -1
        if res != R_OK:
            ok, reason = False, res
    if st.ai[e, A_V4] and ok and ACT_BOX[act]:
        box_add(st, e, i, st.gi[e, i, GI_DID])
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
    st.gi[e, i, GI_BOX_N] = 0
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
        if st.ai[e, A_V4]:
            found4(st, e, FK_TREES if attr == A_TREES else (FK_COAL if attr == A_COAL else (FK_IRON if attr == A_IRON else -1)), n)


@jit
def see_ore(st, e, lv, o, p, n):
    h = st.hidden[e, lv, o]
    if h > 0 and rnd(st.mt, e) < p:
        n = min(h, n)
        st.hidden[e, lv, o] = h - n
        st.seen[e, lv, o] += n
        if st.ai[e, A_V4]:
            found4(st, e, FK_OF_ORE[o], n)


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
    if st.ai[e, A_V4]:
        kill4(st, e, i, k, inside, ch[0, 0] if nch > 0 else st.gi[e, i, GI_DID])
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
    if st.ai[e, A_V4]:
        j = fev_new(st, e, E4_DEATH)
        if j >= 0:
            st.fev_i[e, j, 1] = i
            st.fev_i[e, j, 2] = 1 if accident else 0
            st.fev_f[e, j, 0] = st.gf[e, i, GF_DIST]
        st.gi[e, i, GI_BOX_N] = 0
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
        if st.ai[e, A_V4]:   # the wave's mobs: threats to the base from their coming
            gone4(st, e)
            for _ in range(int(en)):
                m = mob4(st, e)
                no = st.ai[e, A_N_OUT]
                if no < 256:
                    st.mobs_out[e, no] = m
                    st.ai[e, A_N_OUT] = no + 1
                else:
                    st.ai[e, A_OVERFLOW] += 1
                threat4(st, e, m, -1, 240.0)
        if st.ai[e, A_RING]:
            h = float(RING - st.ai[e, A_WALL])
            give_all0(st, e, K_HOLES, h)
            give_all0(st, e, K_P_HOLES, h)
    elif ph == PH_WAVE:
        st.af[e, AF_ENEMIES] = 0
        st.af[e, AF_INSIDE] = 0
        if st.ai[e, A_V4]:
            gone4(st, e)
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
            nm4 = st.sc_f[s, SF_NIGHT_M]
            if st.ai[e, A_V4] and nm4:   # the mobs try at it and the blocks round it turn them
                nmine = night4(st, e, i, nm4 + st.af[e, AF_T] / 60)
                if rnd(st.mt4, e) < min(1.0, 0.035 * nmine * dt):
                    j = fev_new(st, e, E4_ATTACK_FAILED)
                    if j >= 0:
                        st.fev_i[e, j, 1] = st.night[e, i, below(st.mt4, e, nmine)]
                        st.fev_i[e, j, 2] = i
                        st.fev_i[e, j, 3] = 1   # cover
                        fev_list(st, e, j, 0, st.box[e, i], st.gi[e, i, GI_BOX_N])
                        seen = np.empty(1, np.int64)
                        seen[0] = i
                        fev_list(st, e, j, 1, seen, 1)
            continue
        nm = st.sc_f[s, SF_NIGHT_M]
        if nm and st.gi[e, i, GI_ALIVE]:
            m = nm + st.af[e, AF_T] / 60
            deep = st.gi[e, i, GI_NEAR_VALID] == 1 and depth == 0 and nr_pit(st, e, i) >= 2
            if st.ai[e, A_V4]:
                nmine = night4(st, e, i, m)
                st.ai[e, A_HURT_MOB] = st.night[e, i, below(st.mt4, e, nmine)]
            hurt(st, e, i, 0.035 * m * (0.5 if deep else 1.0), dt, 3.0, False)
            st.ai[e, A_HURT_MOB] = -1
            held = st.gi[e, i, GI_HELD]
            if held and poisson(st, e, 0.01 * held * dt):
                st.ai[e, A_KILLS] += 1
                if st.ai[e, A_V4]:
                    nmine = night4(st, e, i, m)
                    q = below(st.mt4, e, nmine)
                    x = st.night[e, i, q]
                    for z in range(q, nmine - 1):
                        st.night[e, i, z] = st.night[e, i, z + 1]
                    st.gi[e, i, GI_NIGHT_N] = nmine - 1
                    j = fev_new(st, e, E4_KILL)
                    if j >= 0:
                        st.fev_i[e, j, 1] = x
                        st.fev_i[e, j, 2] = i
                        st.fev_i[e, j, 3] = st.gi[e, i, GI_DID]
                        seen = np.empty(1, np.int64)
                        seen[0] = i
                        fev_list(st, e, j, 1, seen, 1)
            if st.gf[e, i, GF_HEALTH] <= 0:
                die(st, e, i, True, False)
            continue
        if not wave:
            continue
        en = max(0.0, st.af[e, AF_ENEMIES])
        if en <= 0 and st.af[e, AF_INSIDE] <= 0:
            continue
        dist = st.gf[e, i, GF_DIST]
        inside_a = dist < 8 and perim(st, e)
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
        if perim(st, e) and st.af[e, AF_ENEMIES] > 0:
            k = min(poisson(st, e, 0.02 * st.af[e, AF_ENEMIES] * leak * dt), int(st.af[e, AF_ENEMIES]))
            if k:
                st.af[e, AF_ENEMIES] -= k
                st.af[e, AF_INSIDE] += k
                st.ai[e, A_BREACHES] += k
                st.ai[e, A_WAVE_BREACHES] += k
                give_all0(st, e, K_P_BREACH, float(k))
                if st.ai[e, A_V4]:
                    for _ in range(k):
                        if st.ai[e, A_N_OUT] > 0:
                            m = pool_pop0(st.mobs_out[e], st.ai[e, A_N_OUT])
                            st.ai[e, A_N_OUT] -= 1
                            ni = st.ai[e, A_N_IN]
                            st.mobs_in[e, ni] = m
                            st.ai[e, A_N_IN] = ni + 1
                            j = fev_new(st, e, E4_BREACH)
                            if j >= 0:
                                st.fev_i[e, j, 1] = m
        ins = st.af[e, AF_INSIDE]
        if ins > 0:
            st.af[e, AF_MOB_S] += ins * dt
            st.af[e, AF_WAVE_MOB_S] += ins * dt
            give_all0(st, e, K_P_MOB_S, ins * dt)
            if st.ai[e, A_V4]:
                j = fev_new(st, e, E4_INSIDE)
                if j >= 0:
                    st.fev_f[e, j, 0] = ins * dt
        if st.af[e, AF_ENEMIES] <= 0.5 and st.af[e, AF_INSIDE] <= 0.5:
            st.af[e, AF_ENEMIES] = 0
            st.af[e, AF_INSIDE] = 0
            if st.ai[e, A_V4]:
                gone4(st, e)
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
            if st.ai[e, A_V4]:
                fall4(st, e, 1)


@jit
def crew_delta(st, e, col, key, v, first):
    was = st.af[e, col]
    st.af[e, col] = v
    if not first and v != was:
        give_all0(st, e, key, v - was)


@jit
def crew_delta_c(st, e, col, key, v, first, ch, nch):
    was = st.af[e, col]
    st.af[e, col] = v
    if not first and v != was:
        give_all(st, e, key, v - was, ch, nch)


@jit
def measure(st, e, first):
    s = st.ai[e, A_SCEN]
    if st.sc_i[s, SI_SIM_ONLY]:
        return
    ring = st.ai[e, A_RING]
    if st.ai[e, A_V4]:   # the wall's, the moat's, the gate's builders since the last measure: the causes of their change
        bc = np.zeros((8, 5), np.int64)
        nbc = 0
        nb_ = st.ai[e, A_N_BUILDERS]
        b0 = st.ai[e, A_B_SEEN]
        if nb_ - b0 > 8:
            st.ai[e, A_OVERFLOW] += 1
            b0 = nb_ - 8
        for jj in range(b0, nb_):
            bc[nbc, 0] = int(st.builders[e, jj % 8, 0])
            nbc += 1
        st.ai[e, A_B_SEEN] = nb_
        crew_delta_c(st, e, AF_CT_WALL, K_S_WALL, st.ai[e, A_WALL] / RING if ring else 0.0, first, bc, nbc)
        crew_delta_c(st, e, AF_CT_MOAT, K_S_MOAT, st.ai[e, A_MOAT] / MOAT, first, bc, nbc)
    else:
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
        if st.ai[e, A_V4]:
            flush(st, e, dt)
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
    if st.ai[e, A_V4]:
        if st.ai[e, A_DONE] and st.sc_f[st.ai[e, A_SCEN], SF_NIGHT_M]:   # the night is over: its mobs leave (sim_env's dict order)
            for r_ in range(st.ai[e, A_NIGHT_ORD]):
                for i in range(st.ai[e, A_NAG]):
                    if st.gi[e, i, GI_NIGHT_ORD] == r_ + 1:
                        for q in range(st.gi[e, i, GI_NIGHT_N]):
                            j = fev_new(st, e, E4_GONE)
                            if j >= 0:
                                st.fev_i[e, j, 1] = st.night[e, i, q]
                                st.fev_i[e, j, 2] = 1   # despawn
                        st.gi[e, i, GI_NIGHT_N] = 0
                        st.gi[e, i, GI_NIGHT_ORD] = 0
            st.ai[e, A_NIGHT_ORD] = 0
        flush(st, e, 0.0)
        for i in range(st.ai[e, A_NAG]):
            if ready[i]:
                st.gf[e, i, GF_ACC] = c4_take(st, e, i)
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
            if st.ai[e, A_V4]:
                fall4(st, e, 0)
            st.gi[e, i, GI_LAST] = 0
            st.gi[e, i, GI_OUTCOME] = OUT_NONE
            st.ai[e, A_DONE] = 1
            st.ai[e, A_REASON] = DONE_NOOP
            close(st, e, row, obs, mask, rew, facts_out, valid)
            return
        begin(st, e, i, act, row)
        assigned[e, i] = st.gi[e, i, GI_DID]
    if st.ai[e, A_V4]:
        flush(st, e, 0.0)
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
def reset_one(st, e, scen, seed_key, crew, target, force, row, obs, mask, rew, facts_out, valid, seed4_key):
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
    st.ai[e, A_ACTOR] = -1
    st.ai[e, A_HURT_MOB] = -1
    if st.flags[FL_V4] and not st.sc_i[s, SI_DELAY]:   # the reward v4 (not for delay:*): its events, its own draws, the core
        st.ai[e, A_V4] = 1
        from_key(st.mt4, st.gnext4, e, seed4_key)
        c4_reset(st, e)
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
        st.gi[e, i, GI_C4_CUR] = -1
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
        if st.flags[FL_REC]:
            st.ai[e, A_REC_NF] = 0
            st.ai[e, A_REC_NE] = 0
            st.ai[e, A_REC_NL] = 0
        row = np.empty(R_W)
        step_one(st, e, acts[e], row, obs, mask, rew, facts_out, valid, assigned)


@jit
def c4_reset(st, e):
    """a new RewardCore for the episode"""
    st.ai[e, A_C4_EP_FIRST] = st.ai[e, A_DID_NEXT]
    st.af[e, AF_C4_CALM_END] = np.nan
    st.ai[e, A_C4_PREV_CALM] = -1
    for i in range(MA):
        for c in range(11):
            st.c4ag[e, i, c] = 0.0
        st.c4ag[e, i, CA_DANGER_AT] = -1.0
    for c in range(NC4):
        st.ep4[e, c] = 0.0
    for q in range(FIND_CAP):
        st.find_d[e, q] = -1
    for q in range(6):
        st.fq_n[e, q] = 0


@nb.njit(cache=True, parallel=True)
def reset_batch(st, which, scen, keys, nkeys, crew, target, force, obs, mask, rew, facts_out, valid, keys4, nkeys4):
    """reset the arenas which[j] (scenario, the seed's 32-bit words keys[j, :nkeys[j]], crew, target, force)"""
    n = which.shape[0]
    for j in nb.prange(n):
        e = which[j]
        for i in range(MA):
            valid[e * MA + i] = False
        st.nev[e] = 0
        st.nlink[e] = 0
        if st.flags[FL_REC]:
            st.ai[e, A_REC_NF] = 0
            st.ai[e, A_REC_NE] = 0
            st.ai[e, A_REC_NL] = 0
        row = np.empty(R_W)
        reset_one(st, e, scen[j], keys[j, :nkeys[j]], crew[j], target[j], force[j], row, obs, mask, rew, facts_out, valid,
                  keys4[j, :nkeys4[j]])


# ==== the reward v4 (docs/rl/REWARD-v4.md) ===========================================================================
# The events sim_env gives under reward="v4" (its give4, flush, found4, wall4, kill4, gone4, night4, fall4: line by line,
# their own draws from rng4 = Random(seed + 7919)) into a frame buffer, and tools/rl/reward_core.py's RewardCore over
# each frame (c4_*: line by line too). A frame's events can be kept (st.flags[FL_REC]) for the Python core.

@jit
def fev_new(st, e, typ):
    j = st.ai[e, A_N_FEV]
    if j >= FEV_CAP:
        st.ai[e, A_OVERFLOW] += 1
        return -1
    st.ai[e, A_N_FEV] = j + 1
    for c in range(9):
        st.fev_i[e, j, c] = 0
    st.fev_i[e, j, 0] = typ
    for c in range(3):
        st.fev_f[e, j, c] = 0.0
    return j


@jit
def fev_list(st, e, j, slot, vals, n):
    """the event j's list slot (0: by/causes, 1: seen) := vals[:n]"""
    s = st.ai[e, A_N_FEVL]
    if s + n > FEVL_CAP:
        st.ai[e, A_OVERFLOW] += 1
        n = max(0, FEVL_CAP - s)
    for q in range(n):
        st.fev_l[e, s + q] = vals[q]
    st.ai[e, A_N_FEVL] = s + n
    st.fev_i[e, j, 5 + 2 * slot] = s
    st.fev_i[e, j, 6 + 2 * slot] = n


@jit
def give4(st, e, i, key, n, ch, nch):
    """sim_env.give4: v3's payments as v4's events (the outcomes and the scales; the rest is not paid)"""
    if key == K_NOOP:
        j = fev_new(st, e, E4_NOOP)
        if j >= 0:
            st.fev_i[e, j, 1] = i
    elif key == K_GOAL:
        if not st.ai[e, A_GOAL_SENT]:
            st.ai[e, A_GOAL_SENT] = 1
            fev_new(st, e, E4_GOAL)
    elif key == K_DAMAGE:
        j = fev_new(st, e, E4_DAMAGE)
        if j >= 0:
            st.fev_i[e, j, 1] = i
            st.fev_i[e, j, 2] = st.ai[e, A_HURT_MOB]
            st.fev_f[e, j, 0] = n
    elif key == K_CUR_NOVEL:
        j = fev_new(st, e, E4_NOVEL)
        if j >= 0:
            st.fev_i[e, j, 1] = i
            st.fev_i[e, j, 2] = st.gi[e, i, GI_DID]
    elif KEY_SCALE4[key] >= 0 and n != 0:
        j = fev_new(st, e, E4_SCALE)
        if j < 0:
            return
        st.fev_i[e, j, 1] = i
        st.fev_i[e, j, 2] = KEY_SCALE4[key]
        st.fev_i[e, j, 3] = -1
        st.fev_f[e, j, 0] = n
        if nch > 0:
            ids = np.empty(nch, np.int64)
            for q in range(nch):
                ids[q] = ch[q, 0]
            fev_list(st, e, j, 0, ids, nch)
        q = FQ_OF_KEY[key]
        if q >= 0 and n > 0 and st.fq_n[e, q] > 0:   # an ore the crew found: its finder's
            st.fev_i[e, j, 3] = st.fq[e, q, 0]
            st.fev_f[e, j, 1] = st.fq_kind[e, q, 0]
            m = st.fq_n[e, q]
            for z in range(m - 1):
                st.fq[e, q, z] = st.fq[e, q, z + 1]
                st.fq_kind[e, q, z] = st.fq_kind[e, q, z + 1]
            st.fq_n[e, q] = m - 1


@jit
def phase4(st, e):
    ph = st.ai[e, A_PHASE]
    if ph == PH_PREP:
        return P4_PREP
    if ph == PH_WAVE:
        return P4_WAVE
    if ph == PH_BREAK:
        return P4_BREAK
    return P4_NIGHT if (night_or_wave(st, e) or st.sc_f[st.ai[e, A_SCEN], SF_NIGHT_M]) else P4_DAY


@jit
def perim(st, e):
    return st.ai[e, A_RING] == 1 or (st.ai[e, A_V4] == 1 and st.sc_i[st.ai[e, A_SCEN], SI_WAVES] > 0)


@jit
def mob4(st, e):
    st.ai[e, A_MOB_NEXT] += 1
    return st.ai[e, A_MOB_NEXT]


@jit
def threat4(st, e, mob, target, stay):
    """a threat event: target -1 the base (stay_s given), else an agent (no stay_s)"""
    j = fev_new(st, e, E4_THREAT)
    if j >= 0:
        st.fev_i[e, j, 1] = mob
        st.fev_i[e, j, 2] = target
        st.fev_i[e, j, 3] = 1 if target < 0 else 0
        st.fev_f[e, j, 0] = 3.0
        st.fev_f[e, j, 1] = stay


@jit
def fall4(st, e, why):
    s = st.ai[e, A_SCEN]
    if not st.sc_i[s, SI_WAVES]:
        j = fev_new(st, e, E4_FALL)
        if j >= 0:
            st.fev_i[e, j, 1] = why
        return
    if st.ai[e, A_PHASE] == PH_WAVE:
        left = max(0, int(st.af[e, AF_ENEMIES])) - st.ai[e, A_N_OUT]
    else:
        left = 0
    t, ph, until, n = st.af[e, AF_T], st.ai[e, A_PHASE], st.af[e, AF_PHASE_UNTIL], st.ai[e, A_WAVE_NUM]
    for _ in range(max(0, left)):
        threat4(st, e, mob4(st, e), -1, max(0.0, until - t))
    end = st.sc_f[s, SF_SECONDS]
    while True:
        if ph == PH_WAVE:
            ph, until = PH_BREAK, until + (60 if st.sc_i[s, SI_LONG] else 20)
            continue
        if until >= end:
            break
        n += 1
        mult = 0.7 if st.sc_i[s, SI_WAVES] == 1 else 1.0
        k = float(max(1, math.floor(mult * (2 + 1.6 * n + 0.12 * n * n))))
        k = py_round(k * (1 - min(0.3, st.ai[e, A_TORCHES] / 40)))
        stay = min(240.0, end - until)
        for _ in range(int(k)):
            threat4(st, e, mob4(st, e), -1, stay)
        ph, until = PH_WAVE, until + 240
    j = fev_new(st, e, E4_FALL)
    if j >= 0:
        st.fev_i[e, j, 1] = why


@jit
def found4(st, e, kind, n):
    """kind: a FK index (-1: nothing the core values); the actor: the agent whose skill is ending"""
    a = st.ai[e, A_ACTOR]
    if kind < 0 or a < 0:
        return
    for _ in range(n):
        st.ai[e, A_N_FOUND] += 1
        res = st.ai[e, A_N_FOUND]
        j = fev_new(st, e, E4_FOUND)
        if j >= 0:
            st.fev_i[e, j, 1] = a
            st.fev_i[e, j, 2] = st.gi[e, a, GI_DID]
            st.fev_i[e, j, 3] = res
            st.fev_i[e, j, 4] = FK_COMP[kind]
            st.fev_f[e, j, 0] = FK_AMOUNT[kind]
            st.fev_f[e, j, 1] = kind
        q = FK_FQ[kind]
        if q >= 0:
            m = st.fq_n[e, q]
            if m < FQ_CAP:
                st.fq[e, q, m] = res
                st.fq_kind[e, q, m] = kind
                st.fq_n[e, q] = m + 1
            else:
                st.ai[e, A_OVERFLOW] += 1


@jit
def wall4(st, e, m):
    if not st.ai[e, A_RING] or st.ai[e, A_WALL] < RING // 2 or st.ai[e, A_N_BUILDERS] == 0:
        return
    nb_ = st.ai[e, A_N_BUILDERS]
    mb = min(8, nb_)
    by = np.empty(8, np.int64)
    nby = 0
    for jj in range(nb_ - mb, nb_):
        d = int(st.builders[e, jj % 8, 0])
        dup = False
        for q in range(nby):
            if by[q] == d:
                dup = True
                break
        if not dup:
            by[nby] = d
            nby += 1
    seen = np.empty(MA, np.int64)
    ns = 0
    for i in range(st.ai[e, A_NAG]):
        if st.gi[e, i, GI_ALIVE] and st.gf[e, i, GF_DIST] < 10 and st.gi[e, i, GI_DEPTH] == 0:
            seen[ns] = i
            ns += 1
    full = st.ai[e, A_WALL] >= RING - 2
    j = fev_new(st, e, E4_PATH)
    if j >= 0:
        st.fev_i[e, j, 1] = m
        st.fev_i[e, j, 2] = 0 if full else 1      # blocked, detour
        st.fev_i[e, j, 3] = 1                     # on_route
        st.fev_f[e, j, 0] = 0.0 if full else 20.0 * st.ai[e, A_WALL] / RING
        fev_list(st, e, j, 0, by, nby)
        fev_list(st, e, j, 1, seen, ns)


@jit
def pool_pop0(arr, n):
    v = arr[0]
    for q in range(n - 1):
        arr[q] = arr[q + 1]
    return v


@jit
def kill4(st, e, i, k, inside, d):
    for _ in range(k):
        if inside:
            if st.ai[e, A_N_IN] == 0:
                break
            m = pool_pop0(st.mobs_in[e], st.ai[e, A_N_IN])
            st.ai[e, A_N_IN] -= 1
        else:
            if st.ai[e, A_N_OUT] == 0:
                break
            m = pool_pop0(st.mobs_out[e], st.ai[e, A_N_OUT])
            st.ai[e, A_N_OUT] -= 1
            wall4(st, e, m)
        j = fev_new(st, e, E4_KILL)
        if j >= 0:
            st.fev_i[e, j, 1] = m
            st.fev_i[e, j, 2] = i
            st.fev_i[e, j, 3] = d
            seen = np.empty(1, np.int64)
            seen[0] = i
            fev_list(st, e, j, 1, seen, 1)


@jit
def gone4(st, e):
    for q in range(st.ai[e, A_N_OUT]):
        m = st.mobs_out[e, q]
        wall4(st, e, m)
        j = fev_new(st, e, E4_GONE)
        if j >= 0:
            st.fev_i[e, j, 1] = m
            st.fev_i[e, j, 2] = 0   # wave_end
    for q in range(st.ai[e, A_N_IN]):
        j = fev_new(st, e, E4_GONE)
        if j >= 0:
            st.fev_i[e, j, 1] = st.mobs_in[e, q]
            st.fev_i[e, j, 2] = 0
    st.ai[e, A_N_OUT] = 0
    st.ai[e, A_N_IN] = 0


@jit
def night4(st, e, i, m):
    if st.gi[e, i, GI_NIGHT_ORD] == 0:
        st.ai[e, A_NIGHT_ORD] += 1
        st.gi[e, i, GI_NIGHT_ORD] = st.ai[e, A_NIGHT_ORD]
    while st.gi[e, i, GI_NIGHT_N] < int(math.ceil(m)):
        x = mob4(st, e)
        nn = st.gi[e, i, GI_NIGHT_N]
        if nn >= NIGHT_CAP:
            st.ai[e, A_OVERFLOW] += 1
            break
        st.night[e, i, nn] = x
        st.gi[e, i, GI_NIGHT_N] = nn + 1
        threat4(st, e, x, i, 0.0)
    return st.gi[e, i, GI_NIGHT_N]


@jit
def box_add(st, e, i, d):
    n = st.gi[e, i, GI_BOX_N]
    if n < BOX_CAP:
        st.box[e, i, n] = d
        st.gi[e, i, GI_BOX_N] = n + 1
    else:
        st.ai[e, A_OVERFLOW] += 1


@jit
def flush(st, e, dt):
    """sim_env.flush: a frame (the agents' state, then the events since the last one) for the core"""
    if st.flags[FL_REC]:
        rec_frame(st, e, dt)
    c4_frame(st, e, dt)
    st.ai[e, A_N_FEV] = 0
    st.ai[e, A_N_FEVL] = 0


@jit
def rec_frame(st, e, dt):
    """the frame kept for Python (tools/rl/simbatch.py frames(): reward_core's input as sim_env makes it)"""
    nf = st.ai[e, A_REC_NF]
    if nf >= st.rec_ff.shape[1]:
        st.ai[e, A_OVERFLOW] += 1
        return
    st.rec_ff[e, nf, 0] = st.af[e, AF_T]
    st.rec_ff[e, nf, 1] = dt
    st.rec_fi[e, nf, 0] = phase4(st, e)
    s0 = st.ai[e, A_REC_NE]
    st.rec_fi[e, nf, 1] = s0
    n = st.ai[e, A_N_FEV]
    nag = st.ai[e, A_NAG]
    if s0 + nag + n > st.rec_ei.shape[1] or st.ai[e, A_REC_NL] + st.ai[e, A_N_FEVL] > st.rec_l.shape[1]:
        st.ai[e, A_OVERFLOW] += 1
        return
    for i in range(nag):
        q = s0 + i
        for c in range(9):
            st.rec_ei[e, q, c] = 0
        st.rec_ei[e, q, 0] = E4_AGENT
        st.rec_ei[e, q, 1] = i
        st.rec_ei[e, q, 2] = st.gi[e, i, GI_ALIVE]
        st.rec_ef[e, q, 0] = st.gf[e, i, GF_DIST]
        st.rec_ef[e, q, 1] = 64.0 - 24.0 * st.gi[e, i, GI_DEPTH]
        st.rec_ef[e, q, 2] = max(0.0, st.gf[e, i, GF_HEALTH])
    l0 = st.ai[e, A_REC_NL]
    for j in range(n):
        q = s0 + nag + j
        for c in range(9):
            st.rec_ei[e, q, c] = st.fev_i[e, j, c]
        for c in range(3):
            st.rec_ef[e, q, c] = st.fev_f[e, j, c]
        for sl in range(2):
            if st.fev_i[e, j, 6 + 2 * sl] > 0:
                st.rec_ei[e, q, 5 + 2 * sl] = st.fev_i[e, j, 5 + 2 * sl] + l0
    for q in range(st.ai[e, A_N_FEVL]):
        st.rec_l[e, l0 + q] = st.fev_l[e, q]
    st.ai[e, A_REC_NL] = l0 + st.ai[e, A_N_FEVL]
    st.rec_fi[e, nf, 2] = nag + n
    st.ai[e, A_REC_NE] = s0 + nag + n
    st.ai[e, A_REC_NF] = nf + 1


# ---- reward_core.RewardCore over an arena's frames ---------------------------------------------------------------------
@jit
def dec_slot(st, e, d):
    """the decision d's slot in the core's table, -1 when it is not there (not this episode's, or forgotten)"""
    if d < st.ai[e, A_C4_EP_FIRST]:
        return -1
    s = d % DEC_CAP
    if st.dec_id[e, s] != d:
        return -1
    return s


@jit
def c4_pay(st, e, a, comp, pts):
    if pts == 0:
        return
    n = st.gi[e, a, GI_OPEN_N]
    have = False
    for q in range(n):
        if st.open_ord[e, a, q] == comp:
            have = True
            break
    if not have:
        st.open_ord[e, a, n] = comp
        st.gi[e, a, GI_OPEN_N] = n + 1
        st.open4[e, a, comp] = 0.0
    st.open4[e, a, comp] += pts
    st.ep4[e, comp] += pts
    st.af[e, AF_C4_TOTAL] += pts


@jit
def c4_take(st, e, a):
    s = 0.0
    for q in range(st.gi[e, a, GI_OPEN_N]):
        s += st.open4[e, a, st.open_ord[e, a, q]]
    st.gi[e, a, GI_OPEN_N] = 0
    return s


@jit
def c4_progress(st, e, a):
    st.c4ag[e, a, CA_IDLE] = 0.0


@jit
def c4_productive(st, e, d):
    s = dec_slot(st, e, d)
    if s >= 0:
        st.dec_prod[e, s] = True


@jit
def c4_move(st, e, a, comp, pts, d):
    s = dec_slot(st, e, d)
    if s >= 0:
        ag, t0, kind = st.dec_agent[e, s], st.dec_t[e, s], st.dec_kind[e, s]
    else:
        ag, t0, kind = a, st.af[e, AF_C4_T], -1
    # one move a frame for each (agent, component, decision): summed into the first
    n = st.ai[e, A_C4_MOVES_N]
    for q in range(n):
        if st.mv_i[e, q, 0] == a and st.mv_i[e, q, 1] == comp and st.mv_i[e, q, 2] == d:
            st.mv_f[e, q] += pts
            return
    if n >= MV_CAP:
        st.ai[e, A_OVERFLOW] += 1
        return
    st.mv_i[e, n, 0] = a
    st.mv_i[e, n, 1] = comp
    st.mv_i[e, n, 2] = d
    st.mv_i[e, n, 3] = ag
    st.mv_i[e, n, 4] = int(np.rint(t0 * 20))
    st.mv_i[e, n, 5] = kind
    st.mv_f[e, n] = pts
    st.ai[e, A_C4_MOVES_N] = n + 1


@jit
def c4_transfer(st, e, frm, to, comp, pts, d):
    if pts == 0:
        return
    c4_pay(st, e, frm, comp, -pts)
    c4_pay(st, e, to, comp, pts)
    c4_move(st, e, to, comp, pts, d)


@jit
def c4_witness(st, e, a, seen_s, seen_n):
    if seen_n == 0:
        return False
    for q in range(seen_n):
        if st.fev_l[e, seen_s + q] == a:
            return True
    if not st.c4ag[e, a, CA_EXISTS]:
        return False
    r2 = NEAR_R * NEAR_R
    for q in range(seen_n):
        x = st.fev_l[e, seen_s + q]
        if 0 <= x < MA and st.c4ag[e, x, CA_EXISTS]:
            dx = st.c4ag[e, x, CA_X] - st.c4ag[e, a, CA_X]
            dy = st.c4ag[e, x, CA_Y] - st.c4ag[e, a, CA_Y]
            dz = st.c4ag[e, x, CA_Z] - st.c4ag[e, a, CA_Z]
            if dx ** 2 + dy ** 2 + dz ** 2 <= r2:
                return True
    return False


@jit
def c4_loss(st, e, a, comp, pts):
    c4_pay(st, e, a, comp, pts)
    if pts >= 0 or CALM4[st.ai[e, A_C4_PHASE]] or np.isnan(st.af[e, AF_C4_CALM_END]):
        return
    calm_end = st.af[e, AF_C4_CALM_END]
    nd = st.ai[e, A_C4_DAY_N]
    cd = np.empty(nd, np.int64)
    cdur = np.empty(nd)
    nc = 0
    for q in range(nd):
        d = st.day4[e, q]
        s = dec_slot(st, e, d)
        if s < 0:
            st.ai[e, A_C4_ERR] += 1   # sim_env's core would know it: the table forgot it
            continue
        if st.dec_prod[e, s]:
            continue
        end = st.dec_end[e, s] if not np.isnan(st.dec_end[e, s]) else calm_end
        dur = min(end, calm_end) - st.dec_t[e, s]
        if dur > 0:
            cd[nc] = d
            cdur[nc] = dur
            nc += 1
    tot = 0.0
    for q in range(nc):
        tot += cdur[q]
    if tot <= 0:
        return
    for q in range(nc):
        x = WD_SHARE * pts * cdur[q] / tot
        c4_transfer(st, e, a, st.dec_agent[e, dec_slot(st, e, cd[q])], C4_WASTED, x, cd[q])


@jit
def thr_find(st, e, mob):
    for q in range(st.ai[e, A_C4_THR_N]):
        if st.thr_mob[e, q] == mob:
            return q
    return -1


@jit
def thr_has_target(st, e, q, tg):
    for z in range(st.thr_nt[e, q]):
        if st.thr_tg[e, q, z] == tg:
            return True
    return False


@jit
def thr_remove(st, e, q):
    """the threat q out of the list (the order of the rest kept, as a dict's pop), its contributions out of the pool"""
    mob = st.thr_mob[e, q]
    n = st.ai[e, A_C4_THR_N]
    for z in range(q, n - 1):
        st.thr_mob[e, z] = st.thr_mob[e, z + 1]
        st.thr_dmg[e, z] = st.thr_dmg[e, z + 1]
        st.thr_until[e, z] = st.thr_until[e, z + 1]
        st.thr_inside[e, z] = st.thr_inside[e, z + 1]
        st.thr_nt[e, z] = st.thr_nt[e, z + 1]
        for c in range(MA + 1):
            st.thr_tg[e, z, c] = st.thr_tg[e, z + 1, c]
        for c in range(MA):
            st.thr_taken[e, z, c] = st.thr_taken[e, z + 1, c]
    st.ai[e, A_C4_THR_N] = n - 1
    pool_drop(st, e, mob)


@jit
def pool_drop(st, e, mob):
    n = st.ai[e, A_C4_POOL_N]
    k = 0
    for q in range(n):
        if st.pool_i[e, q, 0] != mob:
            if k != q:
                for c in range(4):
                    st.pool_i[e, k, c] = st.pool_i[e, q, c]
                st.pool_w[e, k] = st.pool_w[e, q]
            k += 1
    st.ai[e, A_C4_POOL_N] = k


@jit
def c4_contrib(st, e, mob, d, agent, reason, w):
    if w <= 0:
        return
    n = st.ai[e, A_C4_POOL_N]
    for q in range(n):
        if st.pool_i[e, q, 0] == mob and st.pool_i[e, q, 1] == d:
            if st.pool_w[e, q] < w:
                st.pool_i[e, q, 2] = agent
                st.pool_i[e, q, 3] = reason
                st.pool_w[e, q] = w
            return
    if n >= POOL_CAP:
        st.ai[e, A_OVERFLOW] += 1
        return
    st.pool_i[e, n, 0] = mob
    st.pool_i[e, n, 1] = d
    st.pool_i[e, n, 2] = agent
    st.pool_i[e, n, 3] = reason
    st.pool_w[e, n] = w
    st.ai[e, A_C4_POOL_N] = n + 1


@jit
def c4_value(st, e, q):
    wd = abs(O_DMG)
    v = 0.0
    t = st.af[e, AF_C4_T]
    for z in range(st.thr_nt[e, q]):
        tg = st.thr_tg[e, q, z]
        if tg == TG_BASE4:
            stay = max(0.0, st.thr_until[e, q] - t)
            v = max(v, (0.0 if st.thr_inside[e, q] else abs(O_BREACH)) + abs(O_MOB_S) * stay)
        else:
            v = max(v, AGENT_HITS * st.thr_dmg[e, q] * wd - st.thr_taken[e, q, tg] * wd)
    return max(0.0, v)


@jit
def c4_resolve(st, e, mob, share):
    q = thr_find(st, e, mob)
    if q < 0:
        return
    pie = share * c4_value(st, e, q)
    # the threat's contributions in their order (taken before the threat goes)
    n = st.ai[e, A_C4_POOL_N]
    cd = np.empty(n, np.int64)
    ca = np.empty(n, np.int64)
    cr = np.empty(n, np.int64)
    cw = np.empty(n)
    nc = 0
    for z in range(n):
        if st.pool_i[e, z, 0] == mob:
            cd[nc], ca[nc], cr[nc], cw[nc] = st.pool_i[e, z, 1], st.pool_i[e, z, 2], st.pool_i[e, z, 3], st.pool_w[e, z]
            nc += 1
    thr_remove(st, e, q)
    if nc == 0:
        return
    tot = 0.0
    for z in range(nc):
        tot += cw[z]
    if pie <= 0 or tot <= 0:
        return
    t = st.af[e, AF_C4_T]
    for z in range(nc):
        pts = pie * cw[z] / tot
        comp = C4_CREDIT0 + cr[z]
        c4_pay(st, e, ca[z], comp, pts)
        c4_move(st, e, ca[z], comp, pts, cd[z])
        c4_productive(st, e, cd[z])
        s = dec_slot(st, e, cd[z])
        if s >= 0 and t - st.dec_t[e, s] <= AFK_ACTIVE:
            c4_progress(st, e, ca[z])


@jit
def c4_state(st, e, a):
    if not st.c4ag[e, a, CA_EXISTS]:
        st.c4ag[e, a, CA_EXISTS] = 1.0
        st.c4ag[e, a, CA_X] = 0.0
        st.c4ag[e, a, CA_Y] = 0.0
        st.c4ag[e, a, CA_Z] = 0.0
        st.c4ag[e, a, CA_HP] = 20.0
        st.c4ag[e, a, CA_ALIVE] = 1.0
        st.c4ag[e, a, CA_BASE] = 0.0
        st.c4ag[e, a, CA_IDLE] = 0.0
        st.c4ag[e, a, CA_TRIP] = 0.0
        st.c4ag[e, a, CA_DANGER] = 0.0
        st.c4ag[e, a, CA_DANGER_AT] = -1.0
        st.gi[e, a, GI_DANGER_N] = 0


@jit
def c4_alive(st, e, out):
    n = 0
    for a in range(MA):
        if st.c4ag[e, a, CA_EXISTS] and st.c4ag[e, a, CA_ALIVE]:
            out[n] = a
            n += 1
    return n


@jit
def c4_event(st, e, j):
    """reward_core's ev_<type> for the frame event j"""
    typ = st.fev_i[e, j, 0]
    t = st.af[e, AF_C4_T]
    if typ == E4_DECISION:
        k = st.fev_i[e, j, 1]
        d = st.fev_i[e, j, 2]
        c4_state(st, e, k)
        cur = st.gi[e, k, GI_C4_CUR]
        if cur >= 0:
            s = dec_slot(st, e, cur)
            if s >= 0:
                st.dec_end[e, s] = t
        s = d % DEC_CAP
        if st.dec_id[e, s] >= st.ai[e, A_C4_EP_FIRST] and st.dec_id[e, s] != d:
            st.ai[e, A_C4_FORGOT] += 1
        st.dec_id[e, s] = d
        st.dec_agent[e, s] = k
        st.dec_t[e, s] = t
        st.dec_kind[e, s] = st.fev_i[e, j, 3]
        st.dec_prod[e, s] = False
        st.dec_end[e, s] = np.nan
        st.gi[e, k, GI_C4_CUR] = d
        n = st.gi[e, k, GI_REC_N]
        if n >= REC4_CAP:
            for z in range(n - 1):
                st.rec4[e, k, z, 0] = st.rec4[e, k, z + 1, 0]
                st.rec4[e, k, z, 1] = st.rec4[e, k, z + 1, 1]
            n -= 1
            st.ai[e, A_OVERFLOW] += 1
        st.rec4[e, k, n, 0] = t
        st.rec4[e, k, n, 1] = d
        n += 1
        drop = 0
        while drop < n and st.rec4[e, k, drop, 0] < t - DODGE_S:
            drop += 1
        if drop:
            for z in range(n - drop):
                st.rec4[e, k, z, 0] = st.rec4[e, k, z + drop, 0]
                st.rec4[e, k, z, 1] = st.rec4[e, k, z + drop, 1]
            n -= drop
        st.gi[e, k, GI_REC_N] = n
        if CALM4[st.ai[e, A_C4_PHASE]]:
            nd = st.ai[e, A_C4_DAY_N]
            if nd < DAY_CAP:
                st.day4[e, nd] = d
                st.ai[e, A_C4_DAY_N] = nd + 1
            else:
                st.ai[e, A_C4_DAYFULL] += 1
        if st.c4ag[e, k, CA_DANGER]:
            n = st.gi[e, k, GI_DANGER_N]
            if n < DANGER_CAP:
                st.danger[e, k, n] = d
                st.gi[e, k, GI_DANGER_N] = n + 1
            else:
                st.ai[e, A_OVERFLOW] += 1
    elif typ == E4_NOOP:
        c4_pay(st, e, st.fev_i[e, j, 1], C4_NOOP, O_NOOP)
    elif typ == E4_FALL:
        alive = np.empty(MA, np.int64)
        while st.ai[e, A_C4_THR_N] > 0:
            q = 0
            if thr_has_target(st, e, q, TG_BASE4):
                stay = max(0.0, st.thr_until[e, q] - t)
                na = c4_alive(st, e, alive)
                for z in range(na):
                    c4_pay(st, e, alive[z], C4_SURRENDER, (0.0 if st.thr_inside[e, q] else O_BREACH) + O_MOB_S * stay)
            else:
                wd = abs(O_DMG)
                for z in range(st.thr_nt[e, q]):
                    tg = st.thr_tg[e, q, z]
                    if st.c4ag[e, tg, CA_EXISTS] and st.c4ag[e, tg, CA_ALIVE]:
                        v = max(0.0, AGENT_HITS * st.thr_dmg[e, q] * wd - st.thr_taken[e, q, tg] * wd)
                        c4_pay(st, e, tg, C4_SURRENDER, -v)
            thr_remove(st, e, q)
    elif typ == E4_GOAL:
        for k in range(MA):
            if st.c4ag[e, k, CA_EXISTS]:
                c4_pay(st, e, k, C4_GOAL, O_GOAL)
                c4_progress(st, e, k)
                if st.gi[e, k, GI_C4_CUR] >= 0:
                    c4_productive(st, e, st.gi[e, k, GI_C4_CUR])
    elif typ == E4_DAMAGE:
        k = st.fev_i[e, j, 1]
        hp = st.fev_f[e, j, 0]
        c4_loss(st, e, k, C4_DMG, O_DMG * hp)
        mob = st.fev_i[e, j, 2]
        if mob >= 0:
            q = thr_find(st, e, mob)
            if q >= 0:
                st.thr_taken[e, q, k] += hp
    elif typ == E4_DEATH:
        k = st.fev_i[e, j, 1]
        c4_state(st, e, k)
        wdeath = abs(O_DEATH)
        x = ACCIDENT_X if (st.fev_i[e, j, 2] == 1 and ACCIDENT_IS) else 1.0
        far = min(FAR_CAP, max(0.0, st.fev_f[e, j, 0] - BASE_R) * FAR_PER)
        soft = min(SOFTEN_CAP * wdeath, FINDS_SOFTEN * st.c4ag[e, k, CA_TRIP])
        c4_loss(st, e, k, C4_DEATH, -(wdeath * x * (1 + far)) + soft)
        st.c4ag[e, k, CA_ALIVE] = 0.0
        st.c4ag[e, k, CA_TRIP] = 0.0
        st.c4ag[e, k, CA_DANGER] = 0.0
    elif typ == E4_BREACH:
        alive = np.empty(MA, np.int64)
        na = c4_alive(st, e, alive)
        for z in range(na):
            c4_loss(st, e, alive[z], C4_BREACH, O_BREACH)
        q = thr_find(st, e, st.fev_i[e, j, 1])
        if q >= 0:
            st.thr_inside[e, q] = True
            pool_drop(st, e, st.fev_i[e, j, 1])
    elif typ == E4_INSIDE:
        ms = st.fev_f[e, j, 0]
        alive = np.empty(MA, np.int64)
        na = c4_alive(st, e, alive)
        for z in range(na):
            c4_loss(st, e, alive[z], C4_MOB_S, O_MOB_S * ms)
    elif typ == E4_SCALE:
        c4_scale(st, e, j)
    elif typ == E4_FOUND:
        res = st.fev_i[e, j, 3]
        if res < FIND_CAP and st.find_d[e, res] == -1:
            comp = st.fev_i[e, j, 4]
            v = abs(SW4_C[comp]) * st.fev_f[e, j, 0] if comp >= 0 else 0.0
            d = st.fev_i[e, j, 2]
            k = st.fev_i[e, j, 1]
            st.find_d[e, res] = d
            st.find_a[e, res] = k
            st.find_v[e, res] = v
            c4_state(st, e, k)
            st.c4ag[e, k, CA_TRIP] += v
            c4_productive(st, e, d)
            c4_progress(st, e, k)
        elif res >= FIND_CAP:
            st.ai[e, A_OVERFLOW] += 1
    elif typ == E4_THREAT:
        mob = st.fev_i[e, j, 1]
        q = thr_find(st, e, mob)
        if q < 0:
            q = st.ai[e, A_C4_THR_N]
            if q >= THR_CAP:
                st.ai[e, A_OVERFLOW] += 1
                return
            st.ai[e, A_C4_THR_N] = q + 1
            st.thr_mob[e, q] = mob
            st.thr_dmg[e, q] = 0.0
            st.thr_until[e, q] = t
            st.thr_inside[e, q] = False
            st.thr_nt[e, q] = 0
            for c in range(MA):
                st.thr_taken[e, q, c] = 0.0
        stay = st.fev_f[e, j, 1] if st.fev_i[e, j, 3] else 0.0
        st.thr_until[e, q] = max(st.thr_until[e, q], t + stay)
        tg = st.fev_i[e, j, 2]
        tg = TG_BASE4 if tg < 0 else tg
        if not thr_has_target(st, e, q, tg):
            st.thr_tg[e, q, st.thr_nt[e, q]] = tg
            st.thr_nt[e, q] += 1
        st.thr_dmg[e, q] = max(st.thr_dmg[e, q], st.fev_f[e, j, 0])
    elif typ == E4_PATH:
        mob = st.fev_i[e, j, 1]
        q = thr_find(st, e, mob)
        if q < 0 or not st.fev_i[e, j, 3]:
            return
        eff = st.fev_i[e, j, 2]
        w = W_BLOCKED if eff == 0 else W_DETOUR
        if eff == 1:
            dl = max(0.0, st.fev_f[e, j, 0])
            w *= dl / (dl + DELAY_TAU) if dl > 0 else 0.0
        bs, bn = st.fev_i[e, j, 5], st.fev_i[e, j, 6]
        ss, sn = st.fev_i[e, j, 7], st.fev_i[e, j, 8]
        by = np.empty(max(1, bn), np.int64)
        nby = 0
        for z in range(bn):
            d = st.fev_l[e, bs + z]
            if dec_slot(st, e, d) >= 0:
                by[nby] = d
                nby += 1
            elif d >= st.ai[e, A_C4_EP_FIRST] and d < st.ai[e, A_DID_NEXT]:
                st.ai[e, A_C4_ERR] += 1
        if nby == 0:
            return
        for z in range(nby):
            ag = st.dec_agent[e, dec_slot(st, e, by[z])]
            if c4_witness(st, e, ag, ss, sn):
                c4_contrib(st, e, mob, by[z], ag, R4_BLOCK, w / nby)
    elif typ == E4_ATTACK_FAILED:
        mob = st.fev_i[e, j, 1]
        q = thr_find(st, e, mob)
        if q < 0:
            return
        bs, bn = st.fev_i[e, j, 5], st.fev_i[e, j, 6]
        ss, sn = st.fev_i[e, j, 7], st.fev_i[e, j, 8]
        by = np.empty(max(1, bn), np.int64)
        nby = 0
        for z in range(bn):
            d = st.fev_l[e, bs + z]
            if dec_slot(st, e, d) >= 0:
                by[nby] = d
                nby += 1
            elif d >= st.ai[e, A_C4_EP_FIRST] and d < st.ai[e, A_DID_NEXT]:
                st.ai[e, A_C4_ERR] += 1
        for z in range(nby):
            ag = st.dec_agent[e, dec_slot(st, e, by[z])]
            if c4_witness(st, e, ag, ss, sn):
                c4_contrib(st, e, mob, by[z], ag, R4_COVER, W_COVER / nby)
    elif typ == E4_KILL:
        mob = st.fev_i[e, j, 1]
        if thr_find(st, e, mob) < 0:
            return
        d = st.fev_i[e, j, 3]
        if dec_slot(st, e, d) >= 0:
            c4_contrib(st, e, mob, d, st.fev_i[e, j, 2], R4_KILL, W_KILL4)
        c4_resolve(st, e, mob, TH_KILL)
    elif typ == E4_GONE:
        c4_resolve(st, e, st.fev_i[e, j, 1], TH_DETER)
    elif typ == E4_NOVEL:
        if NOCUR4[st.ai[e, A_C4_PHASE]]:
            return
        pts = SW4_C[C4_NOVEL] * FAC4[st.ai[e, A_SCEN], C4_NOVEL]
        c4_pay(st, e, st.fev_i[e, j, 1], C4_NOVEL, pts)


@jit
def c4_scale(st, e, j):
    k = st.fev_i[e, j, 1]
    comp = st.fev_i[e, j, 2]
    if not SW4_HAS[comp]:
        st.ai[e, A_C4_ERR] += 1   # reward_core: KeyError (no scale weight)
        return
    raw_ = SW4_C[comp] * st.fev_f[e, j, 0]
    pts = raw_ * FAC4[st.ai[e, A_SCEN], comp]
    if raw_ == 0:
        return
    cs, cn = st.fev_i[e, j, 5], st.fev_i[e, j, 6]
    causes = np.empty(max(1, cn), np.int64)
    nc = 0
    for z in range(cn):
        d = st.fev_l[e, cs + z]
        if dec_slot(st, e, d) >= 0:
            causes[nc] = d
            nc += 1
        elif d >= st.ai[e, A_C4_EP_FIRST] and d < st.ai[e, A_DID_NEXT]:
            st.ai[e, A_C4_ERR] += 1
    res = st.fev_i[e, j, 3]
    fd = -1
    if res >= 0 and res < FIND_CAP and st.find_d[e, res] != -1:
        fd = res
    c4_pay(st, e, k, comp, pts)
    if raw_ > 0:
        c4_progress(st, e, k)   # the simulator names the agent ("*" never): progress, its running decision productive
        if st.gi[e, k, GI_C4_CUR] >= 0:
            c4_productive(st, e, st.gi[e, k, GI_C4_CUR])
        if pts > 0:
            if fd >= 0:
                c4_transfer(st, e, k, st.find_a[e, fd], C4_SCOUT, SCOUT_SHARE * pts, st.find_d[e, fd])
            elif nc > 0:
                m = SCALE_MOVE * pts / nc
                for z in range(nc):
                    c4_transfer(st, e, k, st.dec_agent[e, dec_slot(st, e, causes[z])], C4_SCALE, m, causes[z])
    if raw_ > 0:
        for z in range(nc):
            c4_productive(st, e, causes[z])
            c4_progress(st, e, st.dec_agent[e, dec_slot(st, e, causes[z])])


@jit
def afk_integral(x):
    if x <= AFK_GRACE:
        return 0.0
    if AFK_RAMP <= 0:
        return AFK_RATE * (x - AFK_GRACE)
    if x < AFK_GRACE + AFK_RAMP:
        return AFK_RATE * (x - AFK_GRACE) ** 2 / (2 * AFK_RAMP)
    return AFK_RATE * (AFK_RAMP / 2 + (x - AFK_GRACE - AFK_RAMP))


@jit
def c4_end_frame(st, e, dt):
    for k in range(MA):
        if not st.c4ag[e, k, CA_EXISTS]:
            continue
        if not st.c4ag[e, k, CA_ALIVE]:
            st.c4ag[e, k, CA_DANGER] = 0.0
            continue
        if not st.c4ag[e, k, CA_DANGER]:
            if st.c4ag[e, k, CA_HP] <= HP_LOW:
                anyt = False
                for q in range(st.ai[e, A_C4_THR_N]):
                    if thr_has_target(st, e, q, k):
                        anyt = True
                        break
                if anyt:
                    st.c4ag[e, k, CA_DANGER] = 1.0
                    st.gi[e, k, GI_DANGER_N] = 0
                    st.c4ag[e, k, CA_DANGER_AT] = float(st.gi[e, k, GI_C4_CUR])
        elif st.c4ag[e, k, CA_HP] >= HP_SAFE:
            n = st.gi[e, k, GI_DANGER_N]
            st.c4ag[e, k, CA_DANGER] = 0.0
            if n == 0 and st.c4ag[e, k, CA_DANGER_AT] >= 0:
                st.danger[e, k, 0] = int(st.c4ag[e, k, CA_DANGER_AT])
                n = 1
            if n:
                pts = SURV_SHARE * abs(O_DEATH) / n
                for z in range(n):
                    d = st.danger[e, k, z]
                    c4_pay(st, e, k, C4_SURVIVE, pts)
                    c4_move(st, e, k, C4_SURVIVE, pts, d)
                    c4_productive(st, e, d)
    if dt <= 0:
        return
    for k in range(MA):
        if not st.c4ag[e, k, CA_EXISTS] or not st.c4ag[e, k, CA_ALIVE]:
            continue
        i0 = st.c4ag[e, k, CA_IDLE]
        st.c4ag[e, k, CA_IDLE] = i0 + dt
        pen = afk_integral(i0 + dt) - afk_integral(i0)
        c4_pay(st, e, k, C4_AFK, -pen)


@jit
def c4_frame(st, e, dt):
    t = st.af[e, AF_T]
    st.af[e, AF_C4_T] = t
    ph = phase4(st, e)
    st.ai[e, A_C4_PHASE] = ph
    calm = CALM4[ph]
    prev = st.ai[e, A_C4_PREV_CALM]
    if calm and prev != 1:
        st.ai[e, A_C4_DAY_N] = 0
        st.af[e, AF_C4_CALM_END] = np.nan
    elif not calm and prev == 1:
        st.af[e, AF_C4_CALM_END] = t - dt
    st.ai[e, A_C4_PREV_CALM] = 1 if calm else 0
    st.ai[e, A_C4_MOVES_N] = 0
    for i in range(st.ai[e, A_NAG]):   # the agent events, first
        c4_state(st, e, i)
        st.c4ag[e, i, CA_X] = st.gf[e, i, GF_DIST]
        st.c4ag[e, i, CA_Y] = 64.0 - 24.0 * st.gi[e, i, GI_DEPTH]
        st.c4ag[e, i, CA_Z] = 0.0
        st.c4ag[e, i, CA_HP] = max(0.0, st.gf[e, i, GF_HEALTH])
        st.c4ag[e, i, CA_ALIVE] = 1.0 if st.gi[e, i, GI_ALIVE] else 0.0
        st.c4ag[e, i, CA_BASE] = st.gf[e, i, GF_DIST]
        if st.gf[e, i, GF_DIST] <= BASE_R:
            st.c4ag[e, i, CA_TRIP] = 0.0
    for j in range(st.ai[e, A_N_FEV]):
        c4_event(st, e, j)
    c4_end_frame(st, e, dt)
    for q in range(st.ai[e, A_C4_MOVES_N]):   # the moves into the step's events: reward events with move 1
        ch = np.empty((1, 5), np.int64)
        ch[0, 0] = st.mv_i[e, q, 2]
        ch[0, 1] = st.mv_i[e, q, 3]
        ch[0, 2] = st.mv_i[e, q, 4]
        ch[0, 3] = st.mv_i[e, q, 5]
        ch[0, 4] = V_CREDIT
        if st.flags[FL_MERGE]:   # for training: a step's moves to one (agent, component, decision) summed into one event
            hit = -1
            for z in range(st.nev[e]):
                if st.ev_i[e, z, 0] == EV_MOVE and st.ev_i[e, z, 1] == st.mv_i[e, q, 0] and st.ev_i[e, z, 2] == st.mv_i[e, q, 1] \
                        and st.links[e, st.ev_i[e, z, 5], 0] == st.mv_i[e, q, 2]:
                    hit = z
                    break
            if hit >= 0:
                st.ev_f[e, hit] += st.mv_f[e, q]
                continue
        emit(st, e, EV_MOVE, st.mv_i[e, q, 0], st.mv_i[e, q, 1], st.mv_f[e, q], 0, ch, 1)
