"""A fast, rough simulator of the crew's world in the shape of the real environment (rl/env; docs/rl/CONTRACT.md, v2:
docs/rl/CONTRACT-v2.md): the same actions, raw facts by the game's names (so the same obs vector and mask through
tools/rl/spec.py: contract 2's masks are rl/actions.json's own rules), the reward's weights of rl/reward.json with the
scenario's factors, the scenarios' kits, goals and limits, steps at the agents' decision points. For PPO on the GPU in
minutes; it is not the game.

Contract 2 (rl/*.json version 2) adds: hunger, armour of every tier, the bow and shooting from a post, the mine by
horizons (y~40: coal, iron; y~12: diamonds, gold, redstone, obsidian), branch tunnels and the way back by one's own
stairs, a gate in the wall, the farm (till, plant, harvest), saplings, bread, a pen (lure, breed, slaughter), the
enchanting table with shelves and enchanting for levels, Minecraft 1.7.10's achievements by agent with the parent rule
(tools/rl/achievements.py, rl/achievements.json), and the reward by scales as RlScore measures them: a scale's part is
paid by the change of a measured value (the wall standing, the armour worn, the deepest horizon, the stock up to its
caps, the renewable food that is eaten), only where the scenario turns the scale on; perimeter: mob-seconds inside the
ring, breaches, a clean wave; `skill_ok` only for an OK with a measured effect (never `wait`, `look_around`); `stall`
after STALL_S s with no measured progress. With v1's files (rl/v1 or the old rl/) the v1 rewards stand.

Contract 3 (docs/rl/CONTRACT-v2.md 2.1, 3.1): every decision has a decision_id (`info.assigned`: the ids of the
decisions taken on the actions just sent); a reward event carries the chain of its causes, the nearest first
(`info.events`: {type reward, agent, key, points, chain [{decision_id, agent, tick, kind, via}]}): a kill names the
hit and the craft of the weapon (via item:<id>), a mob burnt in the moat the pouring of the lava (via block:lava), an
ore the craft of the pickaxe, bread eaten the planting of its wheat, a clean wave the wall's building. The primitives
(place block/water/lava/torch at a target slot, a bucket filled at a source, fire, attack, open) act on the same
rough world. novel_effect: a kind of link x effect new in the episode (on a mob, an animal, an agent saved) pays the
curiosity scale; its first time ever (in this process) counts as a discovery. tools/rl/credit.py moves a share of an
event's points to its causes before PPO's update. Sim-only scenarios with a delayed reward and nothing in between
(SIM_SCEN: delay:sword, delay:pickaxe, delay:lava, delay:bucket) check that the credit reaches back.

Calibrated from the real environment's v1 logs (rl/sim-calibration.json, tools/rl/calibrate.py): a skill's game
seconds, its fresh failure rate and the stickiness of a failure (a tree out of reach stays out of reach until the
agent moves), chops that bring no log, hunts of sheep that bring no meat, tables put where 3x3 crafts cannot reach
them, wall cells out of reach, smelting one piece a call.

Protocol (rl/env's, in Python): reset(scenario, seed, crew, target) -> {agents, obs, mask, raw, reward, done, info,
t}; step({agent: action}) -> the same; `agents` are those at a decision now and their reward is
what they got since their last decision; at done every agent closes its step; info: scenario, done_reason,
components, cleared, ticks, assigned, events, achievements (the crew's; by id at the end), breaches, mob_seconds,
clean_waves, sustain (renewable food eaten, food eaten), novel (new links this episode), discoveries (new ever), chains
(at the end: what a made thing did later, for the film).
"""
from __future__ import annotations

import json
import math
import os
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import achievements as ACH  # noqa: E402
import reward_core as RCORE  # noqa: E402
import sim_near as NEAR  # noqa: E402
import spec  # noqa: E402
from spec import ACTIONS, IDX, RECIPES, SCEN, WEIGHTS  # noqa: E402

RING, MOAT, POSTS = 64, 80, 4      # a ring of radius 8, height 2 (cells), its moat, its posts
CAL = json.loads((spec.ROOT / "rl" / "sim-calibration.json").read_text(encoding="utf-8"))
# the waves' fight fitted to the engine (tools/rl/calibrate_waves.py): the kills' and the hits' multipliers, the leak of
# the gate's opening while no door shuts it (absent: 1, 1, 0 - the simulator as it was)
WCAL = {"kill": 1.0, "hurt": 1.0, "gate_leak": 0.0, **CAL.get("waves", {})}
LOSSES_PER_WAVE = 6   # the game's: WaveCycle.Config.lossesPerWave (-Daiguide.waves.losses)
OUTCOMES = os.environ.get("AIGUIDE_RL_OUTCOMES") == "1"   # the outcomes' rewards begun here: off (RlEnv.OUTCOMES; rl/reward-outcomes goes on with them)
_AFK = json.loads((spec.ROOT / "rl" / "reward.json").read_text(encoding="utf-8")).get("constants", {}).get("afk", {})


class Afk:
    """(3.1) idling, RlScore.Afk as it is: the time without a real deed (a measured gain, a new thing, a hit on a mob)
    and within move blocks of where it began; a second of it pays weights.afk x min(cap, (t / tau)^2)"""

    def __init__(self, tau: float = _AFK.get("tau_s", 15.0), cap: float = _AFK.get("cap", 20.0), move: float = _AFK.get("move_blocks", 4.0)):
        self.tau, self.cap, self.move = tau, cap, move
        self.since, self.ax, self.az, self.anchored = 0.0, 0.0, 0.0, False

    def second(self, t: float, x: float, z: float, real: bool) -> float:
        if not self.anchored or real or math.hypot(x - self.ax, z - self.az) > self.move:
            self.since, self.ax, self.az, self.anchored = t, x, z, True
            return 0.0
        s = (t - self.since) / self.tau
        return min(self.cap, s * s)
RESPAWN_S = 1.0       # the game's: Crew.respawn, a second after the death
SK = CAL["skills"]
V2 = spec.VERSION >= 2
V31 = "pillar:up" in IDX   # contract 3.1: digging and building round itself, the memory of failures (sim_near)
_C = json.loads((spec.RL / "reward.json").read_text(encoding="utf-8")).get("constants", {})
STALL_S = float(_C.get("stall_s", 60))
GROW = float(_C.get("grow", 1))             # the environment grows the crew's crops, saplings and young this much faster
WINDOW_S = float(_C.get("window_s", 120))
STOCK = {k: tuple(v) for k, v in _C.get("stock_cap_value", {}).items()}   # item -> (cap, value); food in points
NO_RESULT = ("look_around", "wait:5")       # never a skill_ok: nothing checkable comes of them
MOVES = ("go_to:frontier", "go_to:base", "retreat:home", "descend:mine", "ascend:surface", "tunnel:branch")
LEVEL_ORES = {1: {"coal": (4, 12), "iron": (3, 8), "gravel": (1, 4)},
              2: {"iron": (1, 4), "gold": (1, 4), "redstone": (2, 6), "diamond": (0, 3), "obsidian": (0, 2), "gravel": (0, 2)}}
FOOD_PTS = {"food_raw": 3, "food_cooked": 7, "bread": 5}   # hunger points a piece restores (raw/cooked: a mix of meats)
SAT = {"food_raw": 1.5, "food_cooked": 10.0, "bread": 6.0}
ARMOR_MAT = {"leather": "leather", "golden": "gold_ingot", "iron": "iron_ingot", "diamond": "diamond"}
_PIECES = {"helmet": 5, "chestplate": 8, "leggings": 7, "boots": 4}
_POINTS = {"leather": (1, 3, 2, 1), "golden": (2, 5, 3, 1), "iron": (2, 6, 5, 2), "diamond": (3, 8, 6, 3)}
ARMOR: dict[str, int] = {}
for _m, _pts in _POINTS.items():
    for (_piece, _n), _pt in zip(_PIECES.items(), _pts):
        ARMOR[_m + "_" + _piece] = _pt
# the game's recipes the simulator crafts (v1's RECIPES and contract 2's new items): what one craft takes, gives, 2x2
SIM_RECIPES: dict[str, tuple[dict[str, int], int, bool]] = dict(RECIPES)
SIM_RECIPES.update({
    "diamond_pickaxe": ({"diamond": 3, "stick": 2}, 1, False),
    "diamond_sword": ({"diamond": 2, "stick": 1}, 1, False),
    "bow": ({"stick": 3, "string": 3}, 1, False),
    "arrow": ({"flint": 1, "stick": 1, "feather": 1}, 4, False),
    "wooden_hoe": ({"planks": 2, "stick": 2}, 1, False),
    "stone_hoe": ({"cobblestone": 2, "stick": 2}, 1, False),
    "bread": ({"wheat": 3}, 1, False),
    "wooden_door": ({"planks": 6}, 1, False),
    "fence": ({"stick": 6}, 2, False),
    "fence_gate": ({"stick": 4, "planks": 2}, 1, False),
    "paper": ({"sugar_cane": 3}, 3, False),
    "book": ({"paper": 3, "leather": 1}, 1, True),
    "bookshelf": ({"planks": 6, "book": 3}, 1, False),
    "enchanting_table": ({"book": 1, "diamond": 2, "obsidian": 4}, 1, False),
    "bucket": ({"iron_ingot": 3}, 1, False),
    "flint_and_steel": ({"iron_ingot": 1, "flint": 1}, 1, True),
})
for _m, _mat in ARMOR_MAT.items():
    for _piece, _n in _PIECES.items():
        SIM_RECIPES[_m + "_" + _piece] = ({_mat: _n}, 1, False)
TOOLS = ("_pickaxe", "_sword", "_hoe", "bow", "bucket", "flint_and_steel") + tuple(ARMOR)
TARGETS = ("front", "below", "front_down", "hole", "moat", "mob")
CURIOUS = ("mob_hurt", "mob_killed", "animal_killed", "agent_saved")   # RlCause.curious
FOODS = tuple(FOOD_PTS)
ANIMALS = CAL["animals"]
DISTS = [f for f in spec.FEATURES if f[0].endswith("_dist")]

# the simulator's own scenarios with a delayed reward and nothing paid in between (not the contract's: for the credit
# checks); `weights` replaces the reward's, the scales are off
_ZERO = {"noop": -1.0, "time": 0.0, "death": -1.0, "accident": -1.5}
SIM_SCEN = {
    # a sword crafted in the long quiet before the waves pays only by the kills it makes later (bare hands kill
    # almost nothing); the craft is four steps from logs with nothing paid on the way
    "delay:sword": dict(goal="none", item="", per=1, seconds=700, kit={"log": 2}, health=20, time=14000, waves="easy", prep=300,
                        weights={**_ZERO, "kill": 1.0}, pass_kills=4, unarmed=0.05),
    # a stone pickaxe made early pays only by the iron ore it digs much later (hidden: to be found by going out)
    "delay:pickaxe": dict(goal="items", item="iron_ore", per=3, seconds=900, kit={"log": 3}, health=20, time=1000, waves=None,
                          weights={**_ZERO, "iron_found": 1.0, "goal": 2.0}, iron=(3, 5)),
    # a trap: lava fetched with the bucket from the pool by the base and poured into the moat in the quiet before the
    # waves burns the mobs that cross it; no weapon (the kills' chain: block:lava -> the pouring)
    "delay:lava": dict(goal="none", item="", per=1, seconds=700, kit={"bucket": 1, "cobblestone": 64}, health=20, time=14000,
                       waves="easy", prep=240, weights={**_ZERO, "kill": 1.0}, pass_kills=4, unarmed=0.05, lava_pool=True,
                       moat_dug=True),
    # a bucket of water carried down saves its agent from the lava at y~12 (the reflex); diamonds pay, a death costs;
    # no event names the bucket: only a learned credit (or curiosity's agent_saved) can link it
    "delay:bucket": dict(goal="none", item="", per=1, seconds=900, kit={"bucket": 1, "iron_pickaxe": 1, "torch": 16, "cobblestone": 32},
                         health=20, time=1000, waves=None, weights={**_ZERO, "mine.diamond": 1.0}, pass_diamonds=2, pond=True,
                         lava_risk=0.08, rich=True),
}
if V31:
    # a night out with no wall and no weapon, mobs about: hurt outside, safe shut in (walls at the feet and the head,
    # a roof: three down and a block over the head, or blocks round, two down and a roof); alive with health >= 10 at
    # the night's end passes and is paid the goal; noop costs more than a death out in the open (else it is the way out);
    # a pit two deep halves the hits (walls round, the mobs reach in from over it only): a step on the way
    SIM_SCEN["night:shelter"] = dict(goal="none", item="", per=1, seconds=240, kit={"cobblestone": 8}, health=20, time=14000,
                                     waves=None, weights={**_ZERO, "noop": -2.0, "damage": -0.005, "skill_fail": -0.01, "retry_fail": -0.03,
                                                          "switch_ok": 0.05, "goal": 2.0}, night=3, pass_alive=10)
for _s in SIM_SCEN.values():
    _s.setdefault("hunger", False)
    _s.setdefault("xp", 0)
    _s.setdefault("scales", {})
    _s.setdefault("crew_goal", False)
ALL_SCEN = {**SCEN, **SIM_SCEN}


SK31 = {"dig": {"fail_fresh": 0.05, "sticky": 0.0, "dur_ok": 1.5, "dur_fail": 1.0},
        "put": {"fail_fresh": 0.08, "sticky": 0.0, "dur_ok": 0.8, "dur_fail": 0.8},
        "pillar": {"fail_fresh": 0.1, "sticky": 0.0, "dur_ok": 1.2, "dur_fail": 1.0}}
PUT31 = ("place:block@left", "place:block@right", "place:block@back", "place:block@above")
# what keeps the agent where it is (its cells round it stay); any other skill takes it elsewhere
LOCAL = ("look_around", "wait:5", "pillar:up", "attack@mob", "equip:best", "eat:best") + PUT31
LOCAL_PRE = ("dig@", "craft:", "craft_armor:", "place:block@front", "place:block@below", "place:torch@front", "place:torch@below",
             "place:water@front", "place:water@below", "place:lava@front", "place:lava@below", "use:bucket@front", "use:bucket@below",
             "use:fire@front", "use:fire@below")
# the scenarios where a hanging tree may be the only wood near the start (3.1: "not reachable this way")
HANG_SCEN = ("wood", "pickaxe", "sword", "food", "armor", "bow", "fortify", "farm", "pasture", "achievements")
DITCH_SCEN = ("farm", "pasture", "mine_trip", "iron", "diamond", "fortify", "waves:easy", "waves:normal", "waves:long", "achievements", "food")


def local(name: str) -> bool:
    return name in LOCAL or name.startswith(LOCAL_PRE)


def sk(name: str) -> dict:
    """the calibration's entry of a skill (rl/sim-calibration.json)"""
    if name.startswith("dig@"):
        return SK31["dig"]
    if name in PUT31:
        return SK31["put"]
    if name == "pillar:up":
        return SK31["pillar"]
    if "@" in name and not name.startswith("attack"):
        return SK["place_at"]
    if name.startswith("mine:"):
        return SK["mine_stone"] if name in ("mine:stone", "mine:gravel") else SK["mine_ore"]
    if name.startswith("craft:"):
        return SK["craft_small"] if SIM_RECIPES.get(name[6:], ({}, 1, True))[2] else SK["craft_table"]
    if name.startswith("craft_armor:"):
        return SK["craft_table"]
    if name.startswith("smelt:"):
        return SK["smelt"]
    if name.startswith("place:"):
        return SK["place"]
    if name in ("put:chest_all", "take:chest_food"):
        return SK["chest"]
    if name in ("go_to:frontier", "go_to:base"):
        return SK["go_to"]
    return SK.get(name.split(":")[0], SK["default"])


class Agent:
    def __init__(self, i: int):
        self.i = i
        self.bag: dict[str, int] = {}
        self.health = 20.0
        self.food = 20.0         # hunger, 0..20
        self.sat = 5.0           # saturation, eaten first
        self.xp = 0              # levels
        self.xp_pts = 0.0
        self.held = 0
        self.worn: dict[str, str] = {}   # slot -> piece
        self.dist = 0.0          # from the base (on the surface)
        self.depth = 0           # 0 the surface, 1 y~40, 2 y~12
        self.tunnels = {1: 0, 2: 0}
        self.post = False
        self.free_at = 0.0
        self.alive = True
        self.real = False        # (3.1) a real deed since the last second (Afk; OUTCOMES)
        self.afk = None
        self.pending = None      # (action, ok, reason, began)
        self.holding = False
        self.acc = 0.0
        self.comp: dict[str, float] = {}
        self.did = -1            # the decision now running (its id)
        self.kind = ""           # its action
        self.did_t = 0.0
        self.made: dict[str, tuple[int, float, str]] = {}   # a thing -> (the decision that made it, when, its action)
        self.last = -1
        self.outcome = "none"
        self.repeated = 0
        self.last_s = 0.0
        self.best = {"pick": 0, "weapon": 0, "armor": 0}
        self.stuck: dict[str, bool] = {}  # a skill whose target is out of reach until the agent moves
        self.ach: set[str] = set()
        self.trip_ores = 0
        self.trip_out = False
        self.trips = 0
        self.enchanted = 0
        self.phi = 0.0
        self.track: dict[str, float] = {}
        self.progress_t = 0.0
        self.near: NEAR.Near | None = None   # 3.1: the cells round it while it stays (None: made when asked)
        self.fails = NEAR.Fails()
        self.ditch = False       # 3.1: a ditch with no way over between it and the base (go home: NO_PATH)
        self.lava_front = False  # 3.1: lava in the tunnel's floor before the ore (mine an ore: NO_PATH)
        self.pit_fails = 0       # walks out of a pit two deep that failed (the escape reflex after three)
        self.box: list[int] = []  # v4: the decisions that put the blocks round it where it stands (its cover)

    def n(self, k):
        return self.bag.get(k, 0)

    def add(self, k, v):
        self.bag[k] = self.bag.get(k, 0) + v
        if self.bag[k] <= 0:
            del self.bag[k]

    def tier(self, kind):
        t = 0
        for k in self.bag:
            if k.endswith("_" + kind):
                t = max(t, spec.tier(k))
        return t

    def best_item(self, kind) -> str | None:
        best, t = None, 0
        for k in self.bag:
            if k.endswith("_" + kind) and spec.tier(k) > t:
                best, t = k, spec.tier(k)
        return best

    def armor(self) -> int:
        return sum(ARMOR.get(p, 0) for p in self.worn.values())

    def can_wear(self) -> bool:
        for k in self.bag:
            if k in ARMOR and ARMOR[k] > ARMOR.get(self.worn.get(k.rsplit("_", 1)[1], ""), 0):
                return True
        return False

    def armor_piece(self, mat: str) -> str | None:
        """the piece of this material craft_armor makes: the one adding the most points over what it has, affordable"""
        best, gain = None, 0
        for piece, n in _PIECES.items():
            k = mat + "_" + piece
            have = max(ARMOR.get(self.worn.get(piece, ""), 0), max((ARMOR[b] for b in self.bag if b in ARMOR and b.endswith(piece)), default=0))
            g = ARMOR[k] - have
            if g > gain and self.n(ARMOR_MAT[mat]) >= n:
                best, gain = k, g
        return best

    def foods(self) -> int:
        return sum(self.n(f) for f in FOODS)


class SimEnv:
    _did = 0   # decision ids: unique in a process

    def __init__(self, seed: int = 0, shaping: float = 0.0, weights: dict | None = None, noise: bool = True,
                 curiosity: float = -1.0, obstacles: float = 1.0, force: str | None = None, reward: str | None = None):
        """noise=False: the calibration's failures off (no fresh or sticky failures, no tables out of reach, no empty
        chops): the mechanics alone, for the tests; curiosity: curiosity.novel's weight (-1: the contract's)"""
        self.noise = noise
        self.rng = random.Random(seed)
        self.shaping = shaping
        self.base_w = dict(WEIGHTS if weights is None else weights)
        self.curiosity = curiosity
        self.journal: set[str] = set()   # the kinds of link x effect ever met (the discoveries' journal)
        self.scenario = "wood"
        self.changed = True
        self.obstacles = obstacles   # 3.1: the chances of the situations "not reachable this way" times this
        self.force = force           # 3.1: one of them always: "hang", "ditch", "lava" (play.py's checks)
        # the reward: "v3" (rl/reward.json's weights) or "v4" (docs/rl/REWARD-v4.md: the outcomes and the causal credit,
        # tools/rl/reward_core.py from the events this gives); the environment variable AIGUIDE_REWARD for train.py's workers
        self.reward_kind = reward or os.environ.get("AIGUIDE_REWARD", "v3")
        self.v4 = False

    # ---- the episode ------------------------------------------------------------------------------------
    def reset(self, scenario: str = "wood", seed: int | None = None, crew: int = 1, target: str | None = None):
        if seed is not None:
            self.rng.seed(seed)
        if scenario not in ALL_SCEN:
            raise ValueError("no scenario " + scenario)
        r = self.rng
        self.scenario = scenario
        self.sc = ALL_SCEN[scenario]
        self.sim_only = scenario in SIM_SCEN
        self.w = dict(self.sc["weights"]) if self.sim_only else self.base_w
        if self.curiosity >= 0:
            self.w = dict(self.w)
            self.w["curiosity.novel"] = self.curiosity
        self.target = target if target in ACH.ACH else None
        # v4: the delay:* scenarios keep their own weights (the credit's checks); every other one is paid by outcomes
        self.v4 = self.reward_kind == "v4" and not scenario.startswith("delay:")
        if self.v4:
            self.core = RCORE.RewardCore(None if self.sim_only else scenario)
            self.fev: list[dict] = []         # the events since the last frame
            self.rng4 = random.Random(7919 if seed is None else seed + 7919)   # the events' own draws: the world as in v3
            self.mobs_out: list[int] = []     # the wave's mobs outside the wall, inside it
            self.mobs_in: list[int] = []
            self.mob_next = 0
            self.night_mobs: dict[int, list[int]] = {}   # the night scenario: each agent's mobs about
            self.goal_sent = False
            self.found_q: dict[str, list[str]] = {}       # a kind of ore -> its finds not yet used
            self.n_found = 0
            self.b_seen = 0                   # the builders already named as the scales' causes
            self._actor: Agent | None = None
            self._hurt_mob: int | None = None
        sc_scales = self.sc.get("scales", {})
        self.factors = dict(sc_scales) if isinstance(sc_scales, dict) else {k: 1.0 for k in sc_scales}
        self.hunger = bool(self.sc.get("hunger")) and V2
        self.t = 0.0
        # the arena as contract 3's live runs see it: a forest (trees and leaves, grass), little stone in sight; the
        # crew's memory empty at the start (v1's files: as before)
        self.trees = 0 if V2 else r.randint(0, 6)   # seen and free
        self.trees_hidden = r.randint(14, 34) if V2 else r.randint(8, 30)   # there, to be seen
        self.stone_seen = r.randint(0, 2) if V2 else r.randint(0, 20)
        self.coal = 0 if V2 else r.choice([0, 0, 1, 2])
        self.coal_hidden = r.randint(2, 8)
        self.iron = 0
        self.iron_hidden = r.randint(*self.sc.get("iron", CAL["surface_iron"]))
        self.gravel = r.randint(0, 3)
        self.animals = 0 if V2 else r.randint(0, 2)
        self.animals_hidden = r.randint(2, 8) if scenario in ("food", "pasture") else r.randint(0, 4)
        self.grass = 0 if V2 else r.randint(2, 10)
        self.leaves = 0 if V2 else r.randint(2, 12)
        self.sapling_room = r.choice([0, 0, 0, 1, 2, 4, 8])   # the live runs: mostly none   # where the base's ground takes a sapling
        self.cane = r.randint(0, 3)
        self.cane_hidden = r.randint(0, 6)
        self.mine_levels = 0
        self.mine_d = r.uniform(4, 10)
        self.seen = {1: {}, 2: {}}
        self.hidden = {lv: {k: r.randint(*v) for k, v in ores.items()} for lv, ores in LEVEL_ORES.items()}
        self.hidden[2]["lava"] = r.randint(1, 3)
        if self.sc.get("rich"):
            self.hidden[2]["diamond"] = r.randint(3, 6)
        self.table = self.furnace = False
        self.table_bad = False
        self.table_d = self.furnace_d = 64.0
        self.chest = False
        self.chest_food = 0
        self.ring = bool(self.sc.get("moat_dug"))
        self.wall = RING if self.sc.get("moat_dug") else 0
        self.bad = 0                          # wall cells out of reach (of the ring's)
        self.moat = MOAT if self.sc.get("moat_dug") else 0
        self.torches = 0
        self.posts_taken = 0
        self.door = False
        self.gate_open = False
        self.builders: list[tuple[int, float, int]] = []   # (decision, time, agent) that built the wall, the moat, the gate
        self.plots = self.planted = 0
        self.crops: list[tuple[float, int, int]] = []     # planted plots: (ripe time, the planting decision, its agent)
        self.ripe: list[tuple[int, int]] = []
        self.saplings: list[float] = []
        self.grown = 0                        # trees grown from planted saplings, standing
        self.pen = False
        self.pen_adults: dict[str, int] = {}
        # pasture (contract 3): 3 cows and 3 sheep 10-20 blocks from the base, seen
        self.pen_wild = {"cow": 3, "sheep": 3} if scenario == "pasture" else {}
        self.pen_young: list[tuple[float, str]] = []
        self.pen_rest: dict[str, float] = {}
        self.bred = 0
        self.ench_table = False
        self.shelves = 0
        self.waves = self.sc.get("waves")
        self.phase = "prep" if self.waves else "off"
        self.phase_until = float(self.sc.get("prep", 30.0)) if self.waves else 1e18
        self.wave_num = 0
        self.enemies = 0.0
        self.spawned = 0
        self.kills = 0
        self.inside = 0.0                     # mobs inside the wall now
        self.breaches = 0                     # mobs that got inside, this episode
        self.wave_breaches = 0
        self.mob_s = 0.0
        self.wave_mob_s = 0.0
        self.clean = 0
        self.wave_losses = 0
        self.cleared = 0
        self.items_seen: set[str] = set()
        self.comp: dict[str, float] = {}
        self.novel = 0
        self.repeat_fail = 0
        self.discoveries = 0
        self.links_ep: set[str] = set()
        self.events: list[dict] = []
        self.assigned: dict[int, int] = {}
        self.baked = 0
        self.pond = bool(self.sc.get("pond")) or r.random() < 0.6     # a water source by the base
        self.lava_pool = bool(self.sc.get("lava_pool"))
        self.moat_liquid: tuple[str, dict] | None = None             # water or lava in the moat, and who poured it
        self.chains: dict[tuple, list] = {}   # (agent, cause decision, what it was) -> [time, effect counts]
        # 3.1: a hanging tree by the start (its lowest log 2-3 over the ground, no cell beside it to stand: chop_tree
        # does not take it): its logs' heights; the way to its wood is to pillar up and dig at it, or to go further
        self.hang: dict | None = None
        p_hang = 1.0 if self.force == "hang" else 0.35 * self.obstacles
        if V31 and self.noise and scenario in HANG_SCEN and r.random() < p_hang:
            h = r.choice([2, 3])
            self.hang = {"cells": set(range(h, h + r.randint(4, 6))), "got": 0}
            self.trees_hidden = r.randint(4, 10)   # the forest further off: from the frontier
        self.ob = {"hang_logs": 0, "ditch_met": 0, "ditch_bridged": 0, "lava_met": 0, "lava_passed": 0, "shelter_s": 0.0,
                   "switch_ok": 0, "retry_fail": 0, "pit_stuck": 0}
        self.done = False
        self.reason = ""
        # the renewable's ledger (RlScore.Sustain): food points and logs from the farm, the pen, planted trees
        self.ren_food: list[tuple[float, int, int]] = []   # renewable food points in bags: (points, cause decision, agent)
        self.ren_wheat: list[tuple[int, int]] = []
        self.last_ren_causes: list = []   # the plantings behind the renewable food eaten last
        self.sus = {"food_renew": 0.0, "food_eaten": 0.0, "wood_renew": 0.0, "wood_used": 0.0}
        self.sus_paid = [0.0, 0.0]
        self.sus_win: list[tuple[float, str, float]] = []
        self.agents = [Agent(i) for i in range(max(1, min(spec.MAX_AGENTS, crew)))]
        for a in self.agents:
            for k, v in self.sc["kit"].items():
                a.add(k, v)
            a.health = float(self.sc["health"])
            a.xp = int(self.sc.get("xp", 0))
            a.dist = r.uniform(0, 3)
            a.ach.add("openInventory")        # the root: at its first decision (no reward: it is the start)
            for kind in a.best:
                a.best[kind] = a.tier({"pick": "pickaxe", "weapon": "sword"}[kind]) if kind != "armor" else 0
        if "chest" in self.sc:
            self.chest = True
            self.chest_food = self.sc["chest"]
        for a in self.agents:
            self.items_seen.update(a.bag)
        self.crew_track: dict[str, float] = {}
        self.measure(first=True)
        for a in self.agents:
            a.phi = self.potential()
        return self.decide(list(self.agents), first=True)

    # ---- the raw facts (RlEnv.facts' names) -------------------------------------------------------------
    def raw(self, a: Agent) -> dict:
        x: dict[str, float] = {}
        x["scen_" + self.scenario.replace(":", "_")] = 1
        for s in spec.SCALES:   # the environment's: the scale's own factor (a part's factor alone does not turn it on)
            x["scale_" + s] = 1 if self.factor(s) > 0 else 0
        x["agent_%d" % min(a.i, spec.MAX_AGENTS - 1)] = 1
        x["n_agents"] = len(self.agents)
        x["health"], x["max_health"], x["low_health"] = a.health, 20, spec.LOW
        x["armor"] = a.armor()
        x["held_tier"] = a.held
        x["pick_tier"] = a.tier("pickaxe")
        x["weapon_tier"] = x["sword_tier"] = a.tier("sword")
        x["hoe_tier"] = a.tier("hoe")
        x["hoes"] = a.n("wooden_hoe") + a.n("stone_hoe")
        deep = a.depth > 0
        x["at_home"] = 1 if a.dist < 4 and not deep else 0
        x["hunger_on"] = int(self.hunger)
        x["food_level"] = a.food if self.hunger else 20
        x["saturation"] = a.sat if self.hunger else 5
        x["xp_level"], x["xp_progress"] = a.xp, a.xp_pts / 17.0
        tod = self.sc["time"]   # the arena's time stands
        x["day_sin"], x["day_cos"] = math.sin(2 * math.pi * tod / 24000), math.cos(2 * math.pi * tod / 24000)
        x["night"] = 1 if 13000 <= tod < 23000 else 0
        x["dusk_in"] = (12500 - tod) / 20.0 if tod < 12500 else 0
        tot = stack = arm = 0
        for k, v in a.bag.items():
            if k in ARMOR:
                arm += v
            else:
                x["bag_" + k] = v
            tot += v
            if not k.endswith(TOOLS):
                stack += v
        x["bag_armor"] = arm
        x["bag_total"], x["bag_stackables"] = tot, stack
        x["food_items"] = a.foods()
        x["lure_food"] = a.n("wheat") + a.n("wheat_seeds")
        x["place_spot"] = 1   # a free cell beside it (the arena's ground is open)
        x["lure_targets"] = (self.animals + sum(self.pen_wild.values())) if x["lure_food"] > 0 and not deep else 0
        x["fuel"] = a.n("coal") + a.n("planks") + a.n("log")
        x["wall_blocks"] = max(a.n("cobblestone"), a.n("planks"), a.n("dirt"))
        if V2:   # the live runs: the nearest of many seen trees some 2-12 blocks off
            d = lambda n, far: (far if n <= 0 else max(1.0, a.dist * 0.3 + 2 + 12 / (1 + n) + self.rng.uniform(-1, 3)))  # noqa: E731
        else:
            d = lambda n, far: (far if n <= 0 else max(2.0, a.dist * 0.5 + 6 + 20 / (1 + n)))  # noqa: E731
        x["underground"] = int(deep)
        x["depth_level"] = a.depth
        x["at_horizon"] = int(deep)
        x["y_rel"] = {0: 0, 1: -24, 2: -52}[a.depth]
        x["mine_known"] = int(self.mine_levels > 0)
        x["mine_level"] = self.mine_levels
        x["mine_dist"] = (abs(a.dist - self.mine_d) + 1) if self.mine_levels and not deep else (2 if deep else 64)
        x["tunnels"] = a.tunnels.get(a.depth, 0)
        x["trip_ores"] = a.trip_ores
        if not deep:
            x["trees_seen"], x["tree_dist"], x["trees_free"] = self.trees, d(self.trees, 48), self.trees
            x["stone_seen"], x["stone_dist"] = self.stone_seen, d(self.stone_seen, 32)
            x["coal_seen"], x["coal_dist"], x["coal_minable"] = self.coal, d(self.coal, 48), self.coal
            x["iron_seen"], x["iron_dist"], x["iron_minable"] = self.iron, d(self.iron, 48), self.iron
            x["gravel_seen"] = x["gravel_minable"] = self.gravel
            x["animals_seen"], x["animal_dist"] = self.animals, d(self.animals, 24)
            x["frontier"] = 1 if self.trees_hidden + self.animals_hidden + self.coal_hidden + self.iron_hidden > 0 else 0
            x["grass_seen"], x["leaves_seen"], x["cane_seen"] = self.grass, self.leaves, self.cane
        else:
            s = self.seen[a.depth]
            x["tree_dist"], x["animal_dist"] = 48, 24
            x["stone_seen"], x["stone_dist"] = 32, 2
            for k in ("coal", "iron", "gold", "redstone", "diamond", "obsidian", "gravel"):
                x[k + "_seen"] = x[k + "_minable"] = s.get(k, 0)
                x[k + "_dist"] = d(s.get(k, 0), 48)
        x["table_known"], x["table_dist"] = int(self.table), (abs(a.dist - self.table_d) + 1 if self.table and not deep else 24)
        x["furnace_known"], x["furnace_dist"] = int(self.furnace), (abs(a.dist - self.furnace_d) + 1 if self.furnace and not deep else 24)
        x["chest_known"], x["chest_dist"] = int(self.chest), (a.dist + 1 if self.chest and not deep else 48)
        x["chest_food"] = self.chest_food
        x["base_known"], x["base_dist"] = 1, a.dist + 30 * a.depth
        x["ench_table_known"], x["ench_table_dist"] = int(self.ench_table), (a.dist + 2 if self.ench_table and not deep else 24)
        x["bookshelves"], x["shelf_spots"] = self.shelves, (15 - self.shelves if self.ench_table else 0)
        x["shelves_total"] = self.shelves + a.n("bookshelf")
        x["enchantable"] = max(0, sum(v for k, v in a.bag.items() if k.endswith(("_sword", "_pickaxe")) or k in ARMOR or k == "bow")
                               + len(a.worn) - a.enchanted)
        x["farm_plots"], x["farm_planted"], x["farm_ripe"] = self.plots, self.planted, len(self.ripe)
        x["farm_empty"] = self.plots - self.planted
        x["till_spots"] = 16 - self.plots
        x["saplings_planted"], x["trees_grown"] = len(self.saplings), self.grown
        x["sapling_spots"] = max(0, self.sapling_room - len(self.saplings) - self.grown)
        adults = sum(self.pen_adults.values())
        x["pen_built"], x["pen_animals"] = int(self.pen), adults + len(self.pen_young)
        x["pen_adults"], x["pen_babies"] = adults, len(self.pen_young)
        x["pen_pairs"] = self.pairs(a)
        x["pen_dist"] = abs(a.dist - 6) + 1 if self.pen else 32
        x["door_placed"], x["gate_open"] = int(self.door), int(self.gate_open)
        w = self.phase in ("prep", "wave", "break")
        x["waves_active"] = int(w)
        x["wave_on"] = int(self.phase == "wave")
        x["phase_prep"], x["phase_break"] = int(self.phase == "prep"), int(self.phase == "break")
        x["wave_num"] = self.wave_num
        x["next_wave_in"] = max(0.0, self.phase_until - self.t) if self.phase in ("prep", "break") else 0
        e = self.enemies if self.phase == "wave" else 0
        x["enemies"] = e
        x["enemies_visible"] = (e + self.inside) if not deep else 0
        x["enemy_dist"] = (8 + 10 * (self.wall / RING) if e > 0 else 32)
        x["enemy_dx"], x["enemy_dz"] = (self.rng.uniform(-12, 12), self.rng.uniform(-12, 12)) if e > 0 else (0, 0)
        x["enemies_near"] = min(e, e * (1 - self.wall / RING if self.ring else 1)) + self.inside
        x["ring_planned"] = int(self.ring)
        if self.ring:
            x["wall_frac"], x["holes"] = self.wall / RING, RING - self.wall
            x["inside_wall"] = 1 if a.dist < 8 and not deep else 0
            x["posts_free"] = max(0, POSTS - self.posts_taken)
        x["moat_frac"] = self.moat / MOAT
        x["torches_placed"] = self.torches
        x["has_post"] = int(a.post)
        x["post_dist"] = abs(a.dist - 7) if a.post else 0
        x["light_spots"] = max(0, 12 - self.torches) if a.n("torch") else 0
        x["can_equip"] = 1 if a.tier("sword") > a.held or a.can_wear() else 0
        x["mobs_inside"], x["breaches"], x["mob_seconds"], x["clean_waves"] = self.inside, self.wave_breaches, self.wave_mob_s, self.clean
        for k, v in self.recent().items():
            x[k] = v
        x["sources"] = self.sources()
        x["stock_value"] = self.stock()
        x["stall"] = min(STALL_S, self.t - a.progress_t)
        x["novel_count"] = self.novel
        x["buckets"] = a.n("bucket") + a.n("water_bucket") + a.n("lava_bucket")
        x["slot_block"] = max(a.n("cobblestone"), a.n("planks"), a.n("dirt"))
        e_vis = (self.enemies if self.phase == "wave" else 0) + self.inside
        for tg in TARGETS:   # the primitives' target slots
            if tg in ("front", "below", "front_down"):   # the live runs: below 0.3, front 1.1, a step down 1.5 blocks
                there, dist = 1, {"front": 1.1, "below": 0.3, "front_down": 1.5}[tg] + self.rng.uniform(-0.4, 0.4)
            elif tg == "hole":
                there, dist = int(self.ring and self.wall < RING and not deep), abs(a.dist - 8) + 1
            elif tg == "moat":
                there, dist = int(self.moat > 0 and not deep), abs(a.dist - 10) + 1
            else:
                there, dist = int(e_vis > 0 and not deep), 8 + 10 * (self.wall / RING)
            x["tgt_" + tg] = there
            x["tgt_%s_dist" % tg] = dist if there else 16
            liquid = self.moat_liquid[0] if tg == "moat" and self.moat_liquid else None
            free = {"front": 0.65, "front_down": 0.2}.get(tg, 1.0)   # the live runs: often a block or a tree there
            x["tgt_%s_free" % tg] = int(there and liquid is None and self.rng.random() < free)
            x["tgt_%s_water" % tg] = int(liquid == "water")
            x["tgt_%s_lava" % tg] = int(liquid == "lava")
        if not deep and a.dist < 12 and self.pond:
            x["tgt_front_water_src"] = 1
        if not deep and a.dist < 12 and self.lava_pool or a.depth == 2 and self.seen[2].get("lava", 0) > 0:
            x["tgt_front_lava_src"] = 1
        x["tgt_front_door"] = int(self.door and 6.5 < a.dist < 9.5)
        for k in a.ach:
            x["ach_" + k] = 1
        x["ach_count"] = len(a.ach)
        x["ach_available"] = len([k for k in ACH.available(a.ach) if ACH.ACH[k][2]])
        if self.target:   # the curriculum's target (reset's), not the reward's
            x["ach_target_" + self.target] = 1
        others = [b for b in self.agents if b is not a and b.alive]
        x["allies_alive"] = len(others)
        x["allies_health_mean"] = sum(b.health for b in others) / len(others) if others else 20
        x["allies_health_min"] = min((b.health for b in others), default=20)
        x["allies_low"] = sum(1 for b in others if b.health <= spec.LOW)
        x["ally_dist"] = min((abs(a.dist - b.dist) + 2 + 30 * abs(a.depth - b.depth) for b in others), default=32)
        x["last_" + a.outcome] = 1
        x["repeated"] = a.repeated
        x["last_seconds"] = a.last_s
        if a.last >= 0:
            x["last_action_" + ACTIONS[a.last]] = 1
        x["time_frac"] = self.t / self.sc["seconds"]
        x["goal_progress"] = self.progress()
        for item, (need, _, small) in SIM_RECIPES.items():
            ok = all(a.n(k) >= v for k, v in need.items())
            x["craftable_" + item] = int(ok)
            x["small_" + item] = int(ok and small)
        for m in ARMOR_MAT:
            x["armor_craftable_" + m] = int(a.armor_piece(m) is not None)
        if V31:
            self.raw31(a, x)
        for n, _, sc in DISTS:   # the contract: the distance to what is not there is its scale
            if n not in x:
                x[n] = sc
        return x

    # ---- 3.1: the cells round the agent, the memory of failures ---------------------------------------------
    def near(self, a: Agent) -> NEAR.Near:
        """its cells round it (made when it came here): the ground or a tunnel, the hanging tree at the start, lava
        before the ore, a ditch on the way home"""
        if a.near is not None:
            a.near.pick = a.tier("pickaxe")
            return a.near
        r = self.rng
        nr = NEAR.Near("tunnel" if a.depth else "surface", a.tier("pickaxe"))
        if a.depth:
            if a.lava_front:
                nr.set((0, -1, 1), NEAR.LAVA)
                nr.set((0, -1, 2), NEAR.LAVA)
        elif self.hang and self.hang["cells"] and a.dist < 6:
            for y in self.hang["cells"]:
                nr.set((0, y, 1), NEAR.SOLID, "log")
        elif a.ditch:
            for y in (-1, -2):
                nr.set((0, y, 1), NEAR.AIR)
        elif r.random() < 0.3:   # a block in front (a hill's side, a stone)
            nr.set((0, 0, 1), NEAR.SOLID, "dirt")
        a.near = nr
        return nr

    def night_or_wave(self) -> bool:
        return 13000 <= self.sc["time"] < 23000 or self.phase == "wave"

    def sheltered(self, a: Agent) -> bool:
        return V31 and a.near is not None and a.depth == 0 and a.near.enclosed()

    def raw31(self, a: Agent, x: dict):
        nr = self.near(a)
        nr.mobs.clear()
        e = (self.enemies if self.phase == "wave" else 0) + (self.sc.get("night", 0) + self.t / 60 if self.sc.get("night") else 0)
        out = not (self.ring and a.dist < 8) and a.depth == 0
        if e > 0 and out and self.rng.random() < min(0.6, 0.1 * e):   # a mob at its side
            d = self.rng.choice(NEAR.SIDES)
            c = nr.cell(d)
            if nr.at(c) == NEAR.AIR:
                nr.mobs.add(c)
        nr.facts(x, self.night_or_wave())
        x["trees_reachable"] = x.get("trees_free", 0)
        if self.hang and self.hang["cells"] and a.dist < 12 and a.depth == 0:
            x["trees_seen"] = x.get("trees_seen", 0) + 1   # seen, not free: no cell beside it to stand
            if x.get("trees_free", 0) <= 0:
                x["tree_dist"] = 1.5
        # the primitives' front slots as the cells are
        for tg in ("front", "below", "front_down"):
            c = nr.cell(tg)
            k = nr.at(c)
            x["tgt_%s_free" % tg] = int(k == NEAR.AIR)
            x["tgt_%s_lava" % tg] = int(k == NEAR.LAVA)
            if k == NEAR.LAVA:
                x["tgt_%s_lava_src" % tg] = 1
        a.fails.facts(x, ACTIONS)

    def next_ach(self, a: Agent) -> str | None:
        """the achievement track's target: the shallowest available one the environment gives"""
        av = [k for k in ACH.available(a.ach) if ACH.ACH[k][2]]
        return min(av, key=lambda k: (ACH.depth(k), ACH.ALL.index(k))) if av else None

    def pairs(self, a: Agent | None = None) -> int:
        """the pen's pairs that may breed now (two adults of a kind, rested), with the feed in the bag for them"""
        n = sum(1 for k, v in self.pen_adults.items() if v >= 2 and self.pen_rest.get(k, 0) <= self.t)
        if a is not None and a.n("wheat") + a.n("wheat_seeds") < 2:
            return 0
        return n

    # ---- the goal ---------------------------------------------------------------------------------------
    def crew(self, k):
        return sum(a.n(k) for a in self.agents if a.alive)

    def food(self):
        return self.crew("food_raw") + self.crew("food_cooked") + self.crew("bread")

    def need(self) -> float:
        return max(1, self.sc["per"] * (1 if self.sc.get("crew_goal") else len(self.agents)))

    def progress(self) -> float:
        s, n = self.sc, len(self.agents)
        g = s["goal"]
        need = self.need()
        if g == "items":
            return min(1.0, self.crew(s["item"]) / need)
        if g == "food":
            return min(1.0, self.food() / need)
        if g == "fortify":
            return 0.5 * self.wall / RING + 0.5 * min(1.0, self.moat / (0.9 * MOAT))
        each = lambda f: sum(min(1.0, f(a)) for a in self.agents) / n  # noqa: E731
        if g == "armor":   # each agent wears per_agent armour points
            return each(lambda a: a.armor() / s["per"])
        if g == "bow":     # each: a bow and per_agent arrows
            return each(lambda a: 0.5 * (a.n("bow") > 0) + 0.5 * min(1.0, a.n("arrow") / s["per"]))
        if g == "mine_trip":   # each went down to a horizon, got ore and came back up
            return each(lambda a: a.trips / s["per"])
        if g == "farm":    # bread baked (of the farm's wheat or not) >= per_agent x crew
            return min(1.0, self.baked / need)
        if g == "pasture":   # young born in the pen
            return min(1.0, self.bred / need)
        if g == "enchant":
            return each(lambda a: a.enchanted / s["per"])
        if g == "achievements":   # each: per_agent achievements besides the root
            return each(lambda a: (len(a.ach) - 1) / s["per"])
        return min(1.0, self.cleared / 10)

    def crew_ach(self) -> int:
        got = set()
        for a in self.agents:
            got |= a.ach
        return len(got - {"openInventory"})

    def goal_met(self) -> bool:
        s = self.sc
        if s["goal"] == "none":
            return False
        if s["goal"] == "fortify":
            return self.wall >= RING and self.moat >= 0.9 * MOAT
        return self.progress() >= 1.0

    def success(self) -> bool:
        """the policy side's success: the goal, or for a waves run WAVES_PASS waves cleared"""
        if "pass_kills" in self.sc:
            return self.kills >= self.sc["pass_kills"] * len(self.agents)
        if "pass_alive" in self.sc:
            return self.reason != "noop" and self.t >= self.sc["seconds"] - 1 and all(a.alive and a.health >= self.sc["pass_alive"] for a in self.agents)
        if "pass_diamonds" in self.sc:
            return self.crew("diamond") >= self.sc["pass_diamonds"] * len(self.agents)
        if self.sc["goal"] == "none":
            return self.cleared >= spec.WAVES_PASS.get(self.scenario, 3)
        return self.goal_met()

    def potential(self) -> float:
        """the optional shaping's potential: the goal's progress plus the crafting chain's steps toward it"""
        if not self.shaping or self.sim_only:
            return 0.0
        c = self.crew
        chain_ = 0.0
        item = self.sc["item"]
        if item in ("stone_pickaxe", "stone_sword", "iron_pickaxe"):
            chain_ = 0.2 * ((c("log") + c("planks") + c("stick")) > 0) + 0.2 * (self.table or c("crafting_table") > 0) \
                + 0.2 * (c("wooden_pickaxe") + c("stone_pickaxe") + c("iron_pickaxe") > 0) + 0.2 * (c("cobblestone") >= 3)
            if item == "iron_pickaxe":
                chain_ = 0.25 * (c("iron_ore") + c("iron_ingot") > 0) + 0.25 * (self.furnace or c("furnace") > 0) + 0.3 * min(1, c("iron_ingot") / 3)
        return self.shaping * (self.progress() + chain_)

    # ---- the scales (contract 2): measured values, paid by their change (RlScore) ----------------------
    def factor(self, key: str) -> float:
        """RlReward.factor: a part named by the scenario its factor, else its scale's (0 when off); the others 1"""
        comp = key.split(".")[0]
        if comp not in spec.SCALES or self.sim_only:
            return 1.0
        if key in self.factors:
            return float(self.factors[key])
        return float(self.factors.get(comp, 0.0))

    def stock(self) -> float:
        v = 0.0
        for k, (cap, val) in STOCK.items():
            have = sum(FOOD_PTS[f] * self.crew(f) for f in FOODS) if k == "food" else self.crew(k)
            v += min(have, cap) / cap * val
        return v

    def sources(self) -> float:
        return min(self.planted, 16) / 16 + min(sum(self.pen_adults.values()), 2) / 2 + min(len(self.saplings) + self.grown, 4) / 4

    def ledger(self, kind: str, amount: float):
        if amount > 0:
            self.sus[kind] += amount
            self.sus_win.append((self.t, kind, amount))

    def recent(self) -> dict[str, float]:
        while self.sus_win and self.sus_win[0][0] < self.t - WINDOW_S:
            self.sus_win.pop(0)
        out = {"food_renew": 0.0, "food_eaten": 0.0, "wood_renew": 0.0, "wood_used": 0.0}
        for _, k, v in self.sus_win:
            out[k] += v
        return out

    def measure(self, first: bool = False):
        """every measured part: its change paid (the first value is the baseline: nothing for a kit)"""
        if not V2 or self.sim_only:
            return
        tr = self.crew_track

        def crew_delta(key, v, causes=None):
            was = tr.get(key)
            tr[key] = v
            if was is not None and not first and v != was:
                self.give_all(key, v - was, causes=causes)
        bc = None
        if self.v4:   # the wall's, the moat's, the gate's builders since the last measure: the causes of their change
            bc = [b[0] for b in self.builders[self.b_seen:]] or None
            self.b_seen = len(self.builders)
        crew_delta("safety.wall", self.wall / RING if self.ring else 0.0, bc)
        crew_delta("safety.moat", self.moat / MOAT, bc)
        crew_delta("safety.light", min(1.0, self.torches / 12))
        crew_delta("safety.gate", 1.0 if self.door and not self.gate_open else 0.0)
        crew_delta("enchant.table", 1.0 if self.ench_table else 0.0)
        crew_delta("sustain.source", self.sources())
        crew_delta("stock.value", self.stock())
        f, wd = min(self.sus["food_renew"], self.sus["food_eaten"]), min(self.sus["wood_renew"], self.sus["wood_used"])
        if not first:
            if f > self.sus_paid[0]:
                self.give_all("sustain.food", f - self.sus_paid[0], causes=self.last_ren_causes)
            if wd > self.sus_paid[1]:
                self.give_all("sustain.wood", wd - self.sus_paid[1])
        self.sus_paid = [f, wd]
        for a in self.agents:
            eq = (min(1.0, a.armor() / 20), min(1.0, max(a.held, a.tier("sword")) / 4), min(1.0, a.tier("pickaxe") / 4),
                  1.0 if a.n("bow") and a.n("arrow") >= 4 else 0.0)
            for key, v in zip(("equipment.armor", "equipment.weapon", "equipment.pickaxe", "equipment.ranged"), eq):
                was = a.track.get(key)
                a.track[key] = v
                if was is not None and not first and v != was and a.alive:
                    self.give(a, key, v - was)
            was = a.track.get("mine.depth", 0.0)
            if a.depth > was:
                a.track["mine.depth"] = a.depth
                if not first:
                    self.give(a, "mine.depth", a.depth - was)

    # ---- rewards ----------------------------------------------------------------------------------------
    def give(self, a: Agent, key: str, n: float = 1.0, causes: list | None = None):
        if self.v4:
            return self.give4(a, key, n, causes)
        w = self.w.get(key)
        if w is None:
            return
        p = w * n * (self.factor(key) if V2 else 1.0)
        if p == 0:
            return
        comp = key.split(".")[0]
        a.acc += p
        a.comp[comp] = a.comp.get(comp, 0.0) + p
        self.comp[comp] = self.comp.get(comp, 0.0) + p
        chain = [c for c in (causes or ()) if c]
        if chain:   # contract 3: the reward's event with the chain of its causes, the nearest first
            self.events.append({"type": "reward", "agent": a.i, "key": key, "points": round(p, 6), "chain": chain,
                                "ticks": int(self.t * 20)})
        if p > 0 and (comp in spec.SCALES or comp in ("goal", "skill_ok")):
            a.progress_t = self.t     # RlScore.progress: the stall's clock starts again
            if comp != "skill_ok":
                a.real = True         # a measured gain: no idling (RlEnv.real)

    # ---- the reward v4: the events (rl/events.json) for tools/rl/reward_core.py ----------------------------------
    def give4(self, a: Agent, key: str, n: float, causes: list | None):
        """v3's payments as v4's events: the outcomes and the scales; the rest (time, stall, skill_ok, kill, ...) is
        not paid (the kills, the wall's mobs, the covers are events of their own, where they happen)"""
        comp = key.split(".")[0]
        if key == "noop":
            self.fev.append({"type": "noop", "agent": a.i})
        elif key == "goal":
            if not self.goal_sent:
                self.goal_sent = True
                self.fev.append({"type": "goal"})
        elif key == "damage":
            e = {"type": "damage", "agent": a.i, "hp": float(n)}
            if self._hurt_mob is not None:
                e["mob"] = self._hurt_mob
            self.fev.append(e)
        elif key == "curiosity.novel":
            self.fev.append({"type": "novel", "agent": a.i, "decision": a.did})
        elif comp in spec.SCALES and comp not in ("perimeter", "curiosity") and n:
            e = {"type": "scale", "agent": a.i, "key": key, "delta": float(n)}
            ids = [c["decision_id"] if isinstance(c, dict) else int(c) for c in (causes or ()) if c]
            if ids:
                e["causes"] = ids
            if comp == "mine" and n > 0:   # an ore the crew found: its finder's
                q = self.found_q.get(key)
                if q:
                    e["res"] = q.pop(0)
            self.fev.append(e)

    def phase4(self) -> str:
        if self.phase in ("prep", "wave", "break"):
            return self.phase
        return "night" if self.night_or_wave() or self.sc.get("night") else "day"

    def flush(self, dt: float):
        """a frame for the core: the agents' state, the events since the last one; its moves into info.events"""
        evs = [{"type": "agent", "agent": a.i, "x": float(a.dist), "y": 64.0 - 24.0 * a.depth, "z": 0.0, "hp": float(max(0.0, a.health)),
                "alive": bool(a.alive), "base_dist": float(a.dist)} for a in self.agents]
        moves = self.core.frame({"t": self.t, "dt": dt, "phase": self.phase4(), "events": evs + self.fev})
        self.fev = []
        for m in moves:
            m["ticks"] = int(self.t * 20)
        self.events += moves

    def perim(self) -> bool:
        """the base's perimeter: v3's once the ring is built; v4's in a waves run from the start (the game's ring is
        planned at the reset: a mob in the base is in it whether its wall stands or not)"""
        return self.ring or (self.v4 and bool(self.waves))

    def fall4(self, why: str):
        """v4: the base given up (noop) or lost in a waves run: the threats still to come (the rest of this wave's, the
        waves' to the end) and the open ones count as done (reward_core's fall)"""
        if not self.waves:
            self.fev.append({"type": "fall", "why": why})
            return
        left = max(0, int(self.enemies)) - len(self.mobs_out) if self.phase == "wave" else 0
        t, ph, until, n = self.t, self.phase, self.phase_until, self.wave_num
        for _ in range(max(0, left)):
            self.mob_next += 1
            self.fev.append({"type": "threat", "mob": self.mob_next, "kind": "zombie", "target": "base", "dmg": 3.0, "stay_s": max(0.0, until - t)})
        end = float(self.sc["seconds"])
        while True:   # the waves still to come before the run's end
            if ph == "wave":
                ph, until = "break", until + (60 if self.sc["seconds"] > 1200 else 20)
                continue
            if until >= end:
                break
            n += 1
            mult = 0.7 if self.waves == "easy" else 1.0
            k = float(max(1, math.floor(mult * (2 + 1.6 * n + 0.12 * n * n))))
            k = round(k * (1 - min(0.3, self.torches / 40)))
            stay = min(240.0, end - until)
            for _ in range(int(k)):
                self.mob_next += 1
                self.fev.append({"type": "threat", "mob": self.mob_next, "kind": "zombie", "target": "base", "dmg": 3.0, "stay_s": stay})
            ph, until = "wave", until + 240
        self.fev.append({"type": "fall", "why": why})

    def mob4(self) -> int:
        self.mob_next += 1
        return self.mob_next

    def found4(self, a: Agent | None, kind: str, n: int):
        """v4: a resource seen for the first time by the agent at work: its find (an ore: what mining it may give)"""
        key, amount = {"coal": ("mine.coal", 3), "iron": ("mine.iron", 3), "gold": ("mine.gold", 2), "redstone": ("mine.redstone", 4),
                       "diamond": ("mine.diamond", 1), "obsidian": ("mine.obsidian", 1), "trees": ("stock.value", 0.125)}.get(kind, (None, 0))
        if key is None or a is None:
            return
        for _ in range(n):
            self.n_found += 1
            res = "%s%d" % (kind, self.n_found)
            self.fev.append({"type": "found", "agent": a.i, "decision": a.did, "res": res, "key": key, "amount": amount})
            if key.startswith("mine."):
                self.found_q.setdefault(key, []).append(res)

    def wall4(self, m: int):
        """v4: a wave's mob resolved outside: the wall (half or more standing) turned it on its way in, seen from the base"""
        if not self.ring or self.wall < RING // 2 or not self.builders:
            return
        by = list(dict.fromkeys(b[0] for b in self.builders[-8:]))
        seen = [a.i for a in self.agents if a.alive and a.dist < 10 and a.depth == 0]
        full = self.wall >= RING - 2
        self.fev.append({"type": "path_changed", "mob": m, "effect": "blocked" if full else "detour",
                         "delay_s": 0.0 if full else 20.0 * self.wall / RING, "on_route": True, "by": by, "seen": seen})

    def kill4(self, a: Agent, k: int, inside: bool, chain: list | None):
        pool = self.mobs_in if inside else self.mobs_out
        d = chain[0]["decision_id"] if chain and isinstance(chain[0], dict) else a.did
        for _ in range(k):
            if not pool:
                break
            m = pool.pop(0)
            if not inside:
                self.wall4(m)
            self.fev.append({"type": "kill", "mob": m, "agent": a.i, "decision": d, "seen": [a.i]})

    def gone4(self):
        for m in self.mobs_out:
            self.wall4(m)
            self.fev.append({"type": "mob_gone", "mob": m, "why": "wave_end"})
        for m in self.mobs_in:
            self.fev.append({"type": "mob_gone", "mob": m, "why": "wave_end"})
        self.mobs_out, self.mobs_in = [], []

    def night4(self, a: Agent, m: float) -> list[int]:
        """the night scenario's mobs after the agent: as many as there are about (they come with the time)"""
        mine = self.night_mobs.setdefault(a.i, [])
        while len(mine) < int(math.ceil(m)):
            x = self.mob4()
            mine.append(x)
            self.fev.append({"type": "threat", "mob": x, "kind": "zombie", "target": a.i, "dmg": 3.0})
        return mine

    def give_all(self, key, n=1.0, causes=None):
        for a in self.agents:
            self.give(a, key, n, causes)

    def got(self, a: Agent, k: str, v: int, key: str | None = None):
        a.add(k, v)
        if v > 0:
            a.real = True   # a new thing in the bag: no idling
        if key:
            self.give(a, key, v)
        if k not in self.items_seen:
            self.items_seen.add(k)
            self.give(a, "new_item")

    def made(self, a: Agent, thing: str):
        a.made[thing] = (a.did, self.t, a.kind)

    def link(self, a: Agent, did: int, t: float, kind: str, via: str) -> dict:
        return {"decision_id": did, "agent": a.i, "tick": int(t * 20), "kind": kind, "via": via}

    def cause(self, a: Agent, thing: str | None, via: str | None = None) -> dict | None:
        """the link of the decision that made a thing (via item:<thing>, or the via given)"""
        m = a.made.get(thing) if thing else None
        return self.link(a, m[0], m[1], m[2], via or "item:" + thing) if m else None

    def hit(self, a: Agent) -> dict:
        """the agent's running decision as the cause of a blow"""
        return self.link(a, a.did, a.did_t, a.kind, "hit")

    def effect_of(self, a: Agent, chain: list, effect: str):
        """RlCause.novel: each kind of link of the chain (its via) met with this effect for the first time in the
        episode is novel: curiosity pays it (effects on mobs, animals, an agent saved); the first time ever is a
        discovery"""
        for via in dict.fromkeys(c["via"] for c in chain if c):
            if via == "step":
                continue
            pair = via + " -> " + effect
            if pair in self.links_ep:
                continue
            self.links_ep.add(pair)
            self.novel += 1
            first = pair not in self.journal
            if first:
                self.journal.add(pair)
                self.discoveries += 1
            self.events.append({"type": "novel_effect", "agent": a.i, "cause": via, "effect": effect, "first_ever": first,
                                "chain": [c for c in chain if c], "ticks": int(self.t * 20)})
            if effect in CURIOUS:
                self.give(a, "curiosity.novel")

    def chain(self, a: Agent, thing: str | None, effect: str, k: int = 1):
        """for the film: the effects of a thing made by a decision ("stone_sword made 4.2 min ago -> 3 kills")"""
        m = a.made.get(thing) if thing else None
        if not m:
            return
        c = self.chains.setdefault((a.i, m[0], thing), [m[1], {}, self.t])
        c[1][effect] = c[1].get(effect, 0) + k
        c[2] = self.t

    def achieve(self, a: Agent, k: str):
        """the game's rule: an achievement needs its parent got by the same agent; once an agent"""
        if k in a.ach or k not in ACH.ACH or not ACH.ACH[k][2]:
            return
        par = ACH.ACH[k][0]
        if par is not None and par not in a.ach:
            return
        a.ach.add(k)
        self.events.append({"type": "achievement", "agent": a.i, "id": k, "name": ACH.ACH[k][1], "ticks": int(self.t * 20)})
        self.give(a, "achievement.new")
        self.give(a, "achievement.depth", ACH.depth(k))

    # ---- skills -----------------------------------------------------------------------------------------
    def dur(self, name: str, ok: bool) -> float:
        c = sk(name)
        if ok:
            return max(0.5, c["dur_ok"] * self.rng.uniform(0.7, 1.3))
        return max(0.5, c.get("dur_fail", 2) * self.rng.uniform(0.6, 1.4))

    def begin(self, a: Agent, act: int):
        name = ACTIONS[act]
        r = self.rng
        SimEnv._did += 1
        a.did, a.kind, a.did_t = SimEnv._did, name, self.t
        self.assigned[a.i] = a.did
        if self.v4:
            self.fev.append({"type": "decision", "agent": a.i, "id": a.did, "kind": name})
        raw = self.raw(a)
        if not spec.allowed(act, raw):   # RlEnv: a masked action is a failed step, nothing done
            a.pending = (act, False, "MASKED", self.t)
            a.free_at = self.t
            return
        c = sk(name)
        kind = name.split(":")[0] if not name.startswith(("mine:", "craft:")) else name
        ok, reason = True, ""
        if not self.noise:
            pass
        elif a.stuck.get(kind) and r.random() < c.get("sticky", 0.0):
            ok, reason = False, "OUT_OF_REACH" if kind in ("build_wall", "repair_wall", "light_area") else "NO_PATH"
        elif r.random() < c.get("fail_fresh", 0.0):
            ok, reason = False, {"chop_tree": "NO_PATH", "hunt": "TIMEOUT"}.get(kind, "BLOCKED" if kind.startswith("mine") else "NO_PATH")
            if r.random() < c.get("sticky", 0.0):
                a.stuck[kind] = True
        if V31 and ok:
            ok, reason = self.blocked31(a, name)
        far = a.dist
        dur = self.dur(name, ok)
        if ok:   # the skill's own time where the calibration's mean hides a dependence
            lv = CAL["mine"]["level_s"]
            if name == "go_to:frontier":
                dur = 8 + r.uniform(0, 10)
            elif name in ("retreat:home", "go_to:base"):
                dur = 2 + far * 0.4 + lv * 0.3 * a.depth
            elif name == "chop_tree:near":
                dur += raw["tree_dist"] * 0.2
            elif name.startswith("mine:"):
                sp = {1: 1.0, 2: 1.5, 3: 2.0, 4: 2.5}.get(int(raw["pick_tier"]), 1.0)
                dur = dur / sp
            elif name.startswith("craft:") and not SIM_RECIPES[name[6:]][2] or name.startswith("craft_armor:"):
                dur = SK["craft_small"]["dur_ok"] + (0 if self.table and abs(a.dist - self.table_d) < 6 else c["dur_ok"])
            elif name == "build_wall:ring":
                dur = 3 + min(24, RING - self.wall, int(raw["wall_blocks"])) * 1.9 + far * 0.3
            elif name == "repair_wall:nearest":
                dur = 3 + min(RING - self.wall, int(raw["wall_blocks"]), 12) * 3.5
            elif name == "hold_line:post":
                dur = 60.0
                a.holding = True
            elif name == "wait:5":
                dur = 5.0
            elif name == "descend:mine":
                dur = lv * (1.0 if self.mine_levels <= a.depth else 0.3) + (abs(far - self.mine_d) * 0.4 if a.depth == 0 else 0)
            elif name == "ascend:surface":
                dur = lv * 0.3 * a.depth
            elif name == "tunnel:branch":
                dur = 25 + r.uniform(0, 15)
            elif name == "shoot:post":
                dur = 10.0
            elif name in ("till:farm", "plant:wheat", "harvest:farm", "plant:sapling"):
                dur = 6 + r.uniform(0, 8)
            elif name in ("gather:seeds", "gather:saplings", "gather:sugar_cane"):
                dur = 10 + r.uniform(0, 12)
            elif name == "lure:pen":
                dur = 20 + r.uniform(0, 20)
            elif name in ("breed:pen", "slaughter:pen", "enchant:best", "close:gate"):
                dur = 3 + r.uniform(0, 3)
            elif name == "build_pen:pasture":
                dur = 30 + r.uniform(0, 15)
        a.pending = (act, ok, reason, self.t)
        a.free_at = self.t + dur

    def blocked31(self, a: Agent, name: str) -> tuple[bool, str]:
        """3.1: a skill that cannot get there this way: out of a pit two deep (the escape reflex frees it after three
        tries), home over a ditch with no way across, an ore behind lava in the tunnel's floor"""
        nr = a.near
        if nr is not None and not local(name) and nr.pit() >= 2:
            a.pit_fails += 1
            self.ob["pit_stuck"] += 1
            if a.pit_fails >= 3:   # Reflexes' escape: it digs steps out (its time)
                a.pit_fails = 0
                a.near = None
            return False, "NO_PATH"
        if a.ditch and name in ("go_to:base", "retreat:home", "take_post:auto", "hold_line:post"):
            return False, "NO_PATH"
        if a.lava_front and name.startswith("mine:") and name not in ("mine:stone", "mine:gravel"):
            return False, "NO_PATH"
        return True, ""

    def finish(self, a: Agent):
        act, ok, reason, began = a.pending
        a.pending = None
        a.holding = False
        name = ACTIONS[act]
        self.changed = False
        if ok:
            if self.v4:
                self._actor = a
            res = self.effect(a, name, self.rng)
            if self.v4:
                self._actor = None
            if res:
                ok, reason = False, res
        if self.v4 and ok and (name in PUT31 or name.startswith("place:block@") or name == "pillar:up"):
            a.box.append(a.did)
        if name in MOVES or name.startswith("place:"):
            a.stuck.clear()
        if V31 and not local(name) and (ok or name in MOVES):
            self.moved31(a, name, ok)
        result = "OK" if ok else reason
        self.ended(a, act, result, self.t - began, name)

    def moved31(self, a: Agent, name: str, ok: bool):
        """it went elsewhere: new cells round it (off a pillar: a fall); a ditch on the way home, lava in a tunnel"""
        r = self.rng
        nr = a.near
        if nr is not None and nr.y >= 4 and a.alive:   # down from a pillar of four or more: a fall
            hp = float(nr.y - 3)
            a.health -= hp
            self.give(a, "damage", hp)
        a.near = None
        a.pit_fails = 0
        a.box = []
        if not ok:
            return
        p_ditch = 1.0 if self.force == "ditch" else 0.2 * self.obstacles
        if name == "go_to:frontier" and self.noise and self.scenario in DITCH_SCEN and not a.ditch and r.random() < p_ditch:
            a.ditch = True
            self.ob["ditch_met"] += 1
        elif name in ("go_to:base", "retreat:home"):
            a.ditch = False
        p_lava = 1.0 if self.force == "lava" else 0.25 * self.obstacles
        if name in ("descend:mine", "tunnel:branch") and self.noise and not a.lava_front and r.random() < p_lava:
            a.lava_front = True
            self.ob["lava_met"] += 1
        elif name == "ascend:surface":
            a.lava_front = False

    def ended(self, a: Agent, act: int, result: str, secs: float, name: str = ""):
        if V31:   # the memory of failures; retry_fail for the k-th failure in a row, switch_ok for another way that worked
            rf, so = a.fails.step(act, result == "OK", result)
            if rf:
                self.give(a, "retry_fail", rf)
                self.ob["retry_fail"] += 1
            if so:
                self.give(a, "switch_ok", so)
                self.ob["switch_ok"] += 1
        out = spec.outcome(result)
        if out != "ok" and a.last == act and a.outcome == out:
            a.repeated += 1
            self.repeat_fail += 1   # the same action failed again the same way (a failure repeated, not a new way tried)
        else:
            a.repeated = 0 if out == "ok" else 1
        a.last, a.outcome, a.last_s = act, out, secs
        if result == "OK":
            if not V2 or (name not in NO_RESULT and self.changed):
                self.give(a, "skill_ok")
        else:
            self.give(a, "skill_fail")
        if not V2:
            for kind in ("pick", "weapon", "armor"):
                t = a.tier({"pick": "pickaxe", "weapon": "sword"}[kind]) if kind != "armor" else a.armor() // 5
                if t > a.best[kind]:
                    self.give(a, "tier_up", t - a.best[kind])
                    a.best[kind] = t

    def see(self, attr: str, hidden: str, p: float, k: int = 1):
        h = getattr(self, hidden)
        if h > 0 and self.rng.random() < p:
            n = min(h, k)
            setattr(self, hidden, h - n)
            setattr(self, attr, getattr(self, attr) + n)
            if self.v4:
                self.found4(self._actor, attr, n)

    def see_ore(self, lv: int, k: str, p: float, n: int = 1):
        h = self.hidden[lv].get(k, 0)
        if h > 0 and self.rng.random() < p:
            n = min(h, n)
            self.hidden[lv][k] = h - n
            self.seen[lv][k] = self.seen[lv].get(k, 0) + n
            if self.v4:
                self.found4(self._actor, k, n)

    def xp(self, a: Agent, pts: float):
        """experience points to levels, 1.7.10's first levels (17 points a level)"""
        a.xp_pts += pts
        while a.xp_pts >= 17:
            a.xp_pts -= 17
            a.xp += 1

    def leave(self, a: Agent):
        """going out of the ring (contract 2): through the gate opens it; with no gate the agent breaks a way through"""
        if not (V2 and self.ring and a.dist < 8 and a.depth == 0):
            return
        if self.door:
            self.gate_open = True
        elif self.wall >= RING - self.bad - 2 and self.wall > 0:
            self.wall -= 1

    def effect31(self, a: Agent, name: str) -> str | None:
        """3.1's primitives on the cells round it; None: not one of them (the v3 effect follows)"""
        nr = self.near(a)
        if name.startswith("dig@"):
            d = name[4:]
            lava_side = a.lava_front and d in ("left", "right")
            got = nr.dig(d)
            if got is None:
                return "NOT_VISIBLE"
            if not got:
                self.changed = False
            for it in got:
                if it == "log":
                    self.got(a, "log", 1, "gathered.log")
                    self.achieve(a, "mineWood")
                    self.ob["hang_logs"] += 1
                    if self.hang:
                        self.hang["got"] += 1
                elif it == "cobblestone":
                    self.got(a, "cobblestone", 1, "gathered.cobblestone")
                else:
                    self.got(a, it, 1)
            if self.hang and a.depth == 0 and a.dist < 6:   # the hanging tree's logs left
                self.hang["cells"] = {y for y in self.hang["cells"] if nr.at((0, y, 1)) == NEAR.SOLID}
            if lava_side and got:   # a way round the lava dug: the ore is reached from the side
                a.lava_front = False
                self.ob["lava_passed"] += 1
            return ""
        if name in PUT31 or name == "pillar:up" or name in ("place:block@front", "place:block@front_down", "place:block@below"):
            mat = max(("cobblestone", "planks", "dirt"), key=a.n)
            if a.n(mat) < 1:
                return "NO_ITEMS"
            if name == "pillar:up":
                ok = nr.pillar()
            else:
                ok = nr.put(name[name.index("@") + 1:])
            if not ok:
                return "NO_SPOT"
            a.add(mat, -1)
            if name == "place:block@front_down" and a.ditch:   # a way over the ditch
                a.ditch = False
                self.ob["ditch_bridged"] += 1
            return ""
        if name == "use:bucket@front_down" and a.lava_front and nr.at(nr.cell("front_down")) == NEAR.LAVA:
            if a.n("bucket") < 1:
                return "NO_ITEMS"
            nr.set(nr.cell("front_down"), NEAR.AIR)
            a.add("bucket", -1)
            self.got(a, "lava_bucket", 1)
            self.made(a, "lava_bucket")
            a.lava_front = False
            self.ob["lava_passed"] += 1
            return ""
        return None

    def effect(self, a: Agent, name: str, r: random.Random) -> str:
        """the skill's effects at its end; "" when it went well, else the reason it failed"""
        self.changed = True
        if V31:
            res = self.effect31(a, name)
            if res is not None:
                return res
        if name == "look_around":
            self.changed = False
            if a.depth:
                for k in self.hidden[a.depth]:
                    self.see_ore(a.depth, k, 0.2)
            else:
                self.see("trees", "trees_hidden", 0.9, r.randint(4, 12) if V2 else r.randint(1, 4))
                self.see("animals", "animals_hidden", 0.4, r.randint(1, 2))
                self.see("coal", "coal_hidden", 0.2)
                self.see("cane", "cane_hidden", 0.3)
                self.stone_seen += r.randint(0, 2) if V2 else r.randint(0, 6)
                if V2:
                    self.grass = min(40, self.grass + r.randint(5, 15))
                    self.leaves = min(80, self.leaves + 4 * self.trees)
        elif name == "go_to:frontier":
            self.leave(a)
            a.dist += r.uniform(8, 16)
            self.see("trees", "trees_hidden", 0.9, r.randint(2, 6))
            self.see("animals", "animals_hidden", 0.6, r.randint(1, 3))
            self.see("coal", "coal_hidden", 0.35)
            self.see("iron", "iron_hidden", 0.15 if not self.sim_only else 0.3)
            self.see("cane", "cane_hidden", 0.4, r.randint(1, 2))
            self.grass += r.randint(0, 4)
            self.leaves += r.randint(0, 6)
            self.gravel += r.choice([0, 0, 1])
            self.stone_seen += r.randint(0, 10)
        elif name == "chop_tree:near":
            if self.trees <= 0:
                return "NO_TREE_THERE"
            self.leave(a)
            self.trees -= 1
            planted = self.grown > 0 and r.random() < self.grown / max(1, self.trees + 1)
            if planted:
                self.grown -= 1
            if self.noise and r.random() < SK["chop_tree"]["empty_ok"]:   # chopped, the logs not picked up (real v1: half)
                self.changed = False
            else:
                n = r.randint(3, 6)
                self.got(a, "log", n, "gathered.log")
                self.achieve(a, "mineWood")
                if planted:   # a tree grown from a sapling: renewable wood
                    self.ledger("wood_renew", n)
                if r.random() < 0.5:
                    self.got(a, "sapling", r.randint(1, 2))
            a.dist = max(a.dist, r.uniform(6, 20))
        elif name == "mine:stone":
            if self.noise and r.random() < SK["mine_stone"]["empty_ok"] and not a.depth:
                self.changed = False
                return ""
            n = max(1, int(round(r.gauss(SK["mine_stone"]["yield_mean"], 1.5))))
            self.got(a, "cobblestone", n, "gathered.cobblestone")
            if a.depth:
                for k in self.hidden[a.depth]:
                    self.see_ore(a.depth, k, 0.15)
            else:
                self.stone_seen = max(self.stone_seen, 8)
                self.see("coal", "coal_hidden", 0.5, r.randint(1, 2))
                self.see("iron", "iron_hidden", 0.35)
                a.dist = max(a.dist, r.uniform(4, 16))
        elif name == "mine:gravel":
            where = self.seen[a.depth] if a.depth else None
            have = where.get("gravel", 0) if where is not None else self.gravel
            if have <= 0:
                return "NOT_SEEN"
            if where is not None:
                where["gravel"] = have - 1
            else:
                self.gravel -= 1
            n = r.randint(1, 4)
            fl = sum(1 for _ in range(n) if r.random() < 0.1)   # 1.7.10: gravel drops flint a tenth of the time
            if fl:
                self.got(a, "flint", fl)
            if n - fl:
                self.got(a, "gravel", n - fl)
        elif name.startswith("mine:"):
            ore = name[5:].replace("_ore", "")
            where = self.seen[a.depth] if a.depth else None
            have = where.get(ore, 0) if where is not None else getattr(self, ore, 0) if ore in ("coal", "iron") else 0
            if have <= 0:
                return "NOT_SEEN"
            if where is not None:
                where[ore] = have - 1
            else:
                setattr(self, ore, have - 1)
            item, n, xp = {"coal": ("coal", 3, 1), "iron": ("iron_ore", 3, 0), "gold": ("gold_ore", 2, 0),
                           "redstone": ("redstone", 4, 3), "diamond": ("diamond", 1, 5), "obsidian": ("obsidian", 1, 0)}[ore]
            pick = a.best_item("pickaxe")
            causes = [self.cause(a, pick)]   # the ore: the craft of the pickaxe that dug it
            self.got(a, item, n, "gathered." + item if item in ("coal", "iron_ore") else None)
            self.give(a, "mine." + ore, n, causes=causes)
            if ore == "iron":
                self.give(a, "iron_found", n, causes=causes)
            self.chain(a, pick, item, n)
            self.xp(a, xp * n)
            if a.depth:
                a.trip_ores += n
            if item == "diamond":
                self.achieve(a, "diamonds")
        elif name == "descend:mine":
            self.leave(a)
            if self.mine_levels <= a.depth:
                self.mine_levels = a.depth + 1
                self.got(a, "cobblestone", r.randint(8, 16))
            a.depth += 1
            a.trip_out = True
            a.post = False
            a.dist = self.mine_d
            if not a.trip_out or "trip" not in a.made:
                self.made(a, "trip")
            for k in self.hidden[a.depth]:
                self.see_ore(a.depth, k, 0.35)
        elif name == "tunnel:branch":
            a.tunnels[a.depth] = a.tunnels.get(a.depth, 0) + 1
            self.got(a, "cobblestone", r.randint(6, 12))
            for k in self.hidden[a.depth]:
                self.see_ore(a.depth, k, 0.55, r.randint(1, 2))
        elif name == "ascend:surface":
            a.depth = 0
            a.dist = self.mine_d
            self.end_trip(a)
        elif name.startswith("craft:") or name.startswith("craft_armor:"):
            item = name[6:] if name.startswith("craft:") else a.armor_piece(name[12:])
            if item is None:
                return "MISSING"
            need, out, small = SIM_RECIPES[item]
            if not all(a.n(k) >= v for k, v in need.items()):
                return "MISSING"
            if not small:
                if not (self.table and abs(a.dist - self.table_d) <= 24 and a.depth == 0):
                    if a.n("crafting_table") < 1:
                        return "NO_TABLE"
                    a.add("crafting_table", -1)     # its own table from the bag, put down beside it
                    self.put_table(a)
                if self.table_bad:                   # a table its crafts cannot reach (real v1: a quarter)
                    a.stuck[name] = True
                    return "NO_PATH"
            for k, v in need.items():
                a.add(k, -v)
            if item == "bread":
                self.baked += 1
            if item == "bread":   # the farm's wheat makes renewable bread: its planting is its cause
                k = 0
                while k < 3 and self.ren_wheat:
                    did, ag = self.ren_wheat.pop(0)
                    self.ren_food.append((FOOD_PTS["bread"] / 3, did, ag))
                    k += 1
            if item in ("planks", "stick") or item.startswith("wooden_") or item in ("crafting_table", "chest", "bookshelf", "fence", "fence_gate"):
                self.ledger("wood_used", need.get("log", 0) + need.get("planks", 0) / 4)
            self.got(a, item, out)
            self.made(a, item)
            self.on_craft(a, item)
        elif name.startswith("smelt:"):
            src, dst = {"smelt:iron_ingot": ("iron_ore", "iron_ingot"), "smelt:gold_ingot": ("gold_ore", "gold_ingot"),
                        "smelt:food": ("food_raw", "food_cooked")}[name]
            if not (self.furnace and abs(a.dist - self.furnace_d) <= 24):
                if a.n("furnace") < 1:
                    return "NO_FURNACE"
                a.add("furnace", -1)
                self.furnace, self.furnace_d = True, a.dist
            n = min(int(SK["smelt"].get("per_call", 1)), a.n(src))
            for fuel in ("coal", "planks", "log"):
                if a.n(fuel) >= 1:
                    a.add(fuel, -1)
                    break
            else:
                return "NO_FUEL"
            a.add(src, -n)
            self.got(a, dst, n)
            self.xp(a, 0.7 * n)
            if dst == "iron_ingot":
                self.achieve(a, "acquireIron")
        elif name.startswith("place:"):
            item = name[6:]
            if a.n(item) < 1:
                return "NO_ITEMS"
            a.add(item, -1)
            if item == "crafting_table":
                self.put_table(a)
            elif item == "furnace":
                self.furnace, self.furnace_d = True, a.dist
            elif item == "enchanting_table":
                self.ench_table = True
            elif item == "bookshelf":
                self.shelves += 1
                self.give(a, "enchant.shelf")
            else:
                self.chest = True
        elif name in ("build_wall:ring", "repair_wall:nearest"):
            if name.startswith("repair") and self.wall >= RING:
                return "NO_HOLES"
            if not self.ring:
                self.ring = True
                self.bad = int(RING * r.uniform(*SK["repair_wall"]["bad_cells"])) if self.noise else 0
            good_left = RING - self.wall - self.bad
            cells = min(24 if name.startswith("build") else 12, RING - self.wall)
            if good_left <= 0:   # only cells out of reach are left: from a new spot some of them are reached
                if self.bad <= 0 or r.random() < 0.5:
                    a.stuck[name.split(":")[0]] = True
                    return "OUT_OF_REACH"
                k = min(self.bad, r.randint(1, 4))
                self.bad -= k
                good_left = k
            cells = min(cells, good_left)
            mat = max(("cobblestone", "planks", "dirt"), key=a.n)
            k = min(a.n(mat), cells)
            if k <= 0:
                return "NO_ITEMS"
            a.add(mat, -k)
            self.wall += k
            self.builders.append((a.did, self.t, a.i, a.kind))
            self.give(a, "built.wall", k)
            a.dist = r.uniform(5, 9)
        elif name == "dig_moat:ring":
            n = min(int(SK["dig_moat"].get("yield_mean", 16)), MOAT - self.moat)
            self.moat += n
            self.builders.append((a.did, self.t, a.i, a.kind))
            self.got(a, "dirt", n)
            self.give(a, "built.moat", n)
            a.dist = r.uniform(8, 11)
        elif name == "light_area:base":
            n = min(a.n("torch"), max(0, 12 - self.torches), 4)
            if n <= 0:
                return "NO_SPOT"
            a.add("torch", -n)
            self.torches += n
            self.give(a, "built.torch", n)
        elif name == "equip:best":
            t = a.tier("sword")
            wore = False
            for k in sorted(a.bag, key=lambda k: -ARMOR.get(k, 0)):
                if k in ARMOR and a.n(k) > 0:
                    slot = k.rsplit("_", 1)[1]
                    if ARMOR[k] > ARMOR.get(a.worn.get(slot, ""), 0):
                        if slot in a.worn:
                            a.add(a.worn[slot], 1)
                        a.worn[slot] = k
                        a.add(k, -1)
                        wore = True
            if t <= a.held and not wore:
                return "NO_WEAPON"
            if t > a.held:
                self.made(a, "equipped")
            a.held = max(a.held, t)
        elif name == "take_post:auto":
            if not a.post:
                if self.posts_taken >= POSTS:
                    return "ALL_TAKEN"
                self.posts_taken += 1
            a.post, a.dist = True, 7.0
            self.made(a, "post")
        elif name == "hold_line:post":
            self.changed = False   # the fight is in world(): its kills are the result
        elif name == "shoot:post":
            e = self.enemies if self.phase == "wave" else 0
            if e <= 0:
                return "NOT_VISIBLE"
            shots = min(a.n("arrow"), r.randint(2, 5))
            a.add("arrow", -shots)
            p = 0.18 + (0.1 if a.post else 0.0)
            k = min(int(math.ceil(self.enemies)), sum(1 for _ in range(shots) if r.random() < p))
            if k:
                self.kill(a, k, weapon="bow")
                if r.random() < 0.02 * k:   # a skeleton shot from far off: the game's 50 blocks, now and then
                    self.achieve(a, "snipeSkeleton")
            else:
                self.changed = False
        elif name in ("retreat:home", "go_to:base"):
            if a.depth:
                self.end_trip(a)
            a.dist = r.uniform(0, 2)
            a.depth = 0
        elif name == "eat:best":
            ate = 0
            causes = []
            for food in ("food_cooked", "bread", "food_raw"):
                while a.n(food) > 0 and (a.food < 20 if self.hunger else a.health < 20):   # with hunger: as a player
                    a.add(food, -1)
                    pts = FOOD_PTS[food]
                    if self.hunger:
                        a.food = min(20.0, a.food + pts)
                        a.sat = min(a.food, a.sat + SAT[food])
                    else:
                        a.health = min(20.0, a.health + {"food_cooked": 6, "food_raw": 3, "bread": 5}[food])
                    left = pts
                    while left > 0 and self.ren_food:   # renewable food eaten first (its planting the cause)
                        p, did, ag = self.ren_food[0]
                        take = min(p, left)
                        left -= take
                        self.ledger("food_renew", take)
                        causes.append({"decision_id": did, "agent": ag, "tick": 0, "kind": "plant:wheat", "via": "item:wheat"})
                        if p - take <= 1e-9:
                            self.ren_food.pop(0)
                        else:
                            self.ren_food[0] = (p - take, did, ag)
                    self.ledger("food_eaten", pts)
                    ate += 1
                    if self.hunger and a.food >= 20 and a.health >= 20:
                        break
            if ate == 0:
                return "NO_FOOD"
            self.last_ren_causes = causes
            self.give(a, "ate", ate)
        elif name == "hunt:near":
            if self.animals <= 0:
                return "NO_ANIMAL"
            self.leave(a)
            self.animals -= 1
            kind = r.choices(list(ANIMALS), weights=list(ANIMALS.values()))[0]
            self.drops(a, kind)
            if kind == "sheep":   # 1.7.10's sheep give wool, no meat
                self.changed = False
            a.dist = max(a.dist, r.uniform(6, 20))
        elif name == "put:chest_all":
            for k in list(a.bag):
                if k.endswith(TOOLS) or k in ARMOR:
                    continue
                if k in FOODS:
                    self.chest_food += a.bag[k]
                a.add(k, -a.bag[k])
            a.dist = 1.0
        elif name == "take:chest_food":
            n = min(8, self.chest_food)
            if n <= 0:
                return "NOT_IN_CHEST"
            self.chest_food -= n
            self.got(a, "bread", n)
            a.dist = 1.0
        elif name == "build_gate:door":
            if a.n("wooden_door") < 1:
                return "NO_ITEMS"
            a.add("wooden_door", -1)
            self.door = True
            self.gate_open = False
            self.builders.append((a.did, self.t, a.i, a.kind))
        elif name == "close:gate":
            if not self.gate_open:
                return "DONE_ALREADY"
            self.gate_open = False
        elif name == "gather:seeds":
            if self.grass <= 0:
                return "NOT_SEEN"
            self.grass -= 1
            n = sum(1 for _ in range(6) if r.random() < 0.125)   # 1.7.10: tall grass drops seeds an eighth of the time
            if not n:
                self.changed = False
            else:
                self.got(a, "wheat_seeds", n)
        elif name == "gather:saplings":
            if self.leaves <= 0:
                return "NOT_SEEN"
            self.leaves -= 1
            n = sum(1 for _ in range(8) if r.random() < 0.05)   # leaves: a sapling a twentieth of the time
            if not n:
                self.changed = False
            else:
                self.got(a, "sapling", n)
        elif name == "gather:sugar_cane":
            if self.cane <= 0:
                return "NOT_SEEN"
            self.cane -= 1
            self.got(a, "sugar_cane", r.randint(2, 3))
        elif name == "till:farm":
            n = min(4, 16 - self.plots)
            if n <= 0:
                return "DONE_ALREADY"
            self.plots += n
            a.dist = r.uniform(2, 5)
        elif name == "plant:wheat":
            n = min(a.n("wheat_seeds"), self.plots - self.planted)
            if n <= 0:
                return "NO_SEEDS"
            a.add("wheat_seeds", -n)
            self.planted += n
            g0, g1 = CAL["crops"]["grow_s"]
            self.crops += [(self.t + r.uniform(g0, g1) / GROW, a.did, a.i) for _ in range(n)]
        elif name == "harvest:farm":
            if not self.ripe:
                return "NOT_RIPE"
            n = len(self.ripe)
            self.ren_wheat += self.ripe
            self.ripe = []
            self.planted -= n
            self.got(a, "wheat", n)
            s0, s1 = CAL["crops"]["seeds_per_harvest"]
            self.got(a, "wheat_seeds", sum(r.randint(s0, s1) for _ in range(n)))
            k = min(n, a.n("wheat_seeds"))   # contract 3: harvest replants with its own seeds
            if k:
                a.add("wheat_seeds", -k)
                self.planted += k
                g0, g1 = CAL["crops"]["grow_s"]
                self.crops += [(self.t + r.uniform(g0, g1) / GROW, a.did, a.i) for _ in range(k)]
        elif name == "plant:sapling":
            if a.n("sapling") < 1:
                return "NO_ITEMS"
            a.add("sapling", -1)
            g0, g1 = CAL["saplings"]["grow_s"]
            self.saplings.append(self.t + r.uniform(g0, g1) / GROW)
        elif name == "build_pen:pasture":
            if a.n("fence") < 15 or a.n("fence_gate") < 1:
                return "NO_ITEMS"
            a.add("fence", -15)
            a.add("fence_gate", -1)
            self.pen = True
        elif name == "lure:pen":
            if self.animals <= 0 and not any(self.pen_wild.values()):
                return "NO_ANIMAL"
            self.leave(a)
            wild = [k for k, v in self.pen_wild.items() if v > 0]
            if wild:   # the scenario's cows and sheep first
                k = wild[r.randrange(len(wild))]
                self.pen_wild[k] -= 1
                self.pen_adults[k] = self.pen_adults.get(k, 0) + 1
                return ""
            n = min(self.animals, r.randint(1, 2))
            self.animals -= n
            for _ in range(n):   # the kinds as the real arena has them (rl/sim-calibration.json: cows are rare)
                kind = r.choices(list(ANIMALS), weights=list(ANIMALS.values()))[0]
                self.pen_adults[kind] = self.pen_adults.get(kind, 0) + 1
        elif name == "breed:pen":
            kinds = [k for k, v in self.pen_adults.items() if v >= 2 and self.pen_rest.get(k, 0) <= self.t]
            if not kinds or a.n("wheat") + a.n("wheat_seeds") < 2:
                return "NO_MATE"
            k = kinds[0]
            for f in ("wheat", "wheat_seeds"):
                if a.n(f) >= 2:
                    a.add(f, -2)
                    break
            self.pen_young.append((self.t + CAL["pasture"]["grow_s"] / GROW, k))
            self.pen_rest[k] = self.t + CAL["pasture"]["breed_cooldown_s"]
            self.bred += 1
            self.xp(a, 3)
            if k == "cow":
                self.achieve(a, "breedCow")
        elif name == "slaughter:pen":
            k = max(self.pen_adults, key=lambda x: self.pen_adults[x], default=None)
            if k is None or self.pen_adults[k] < 1:
                return "NO_ANIMAL"
            self.pen_adults[k] -= 1
            got = self.drops(a, k)
            if got:   # pen meat: renewable while the pen breeds
                self.ren_food.append((FOOD_PTS["food_raw"] * got, a.did, a.i))
        elif name == "enchant:best":
            if not self.ench_table:
                return "NO_TABLE"
            if a.xp < 1:
                return "NO_XP"
            lvl = min(a.xp, 1 + 2 * self.shelves, 30)   # what the table offers with the shelves around
            spent = min(lvl if lvl <= 3 else max(1, lvl // 3), a.xp)
            a.xp -= spent
            a.enchanted += 1
            self.give(a, "enchant.item")
            self.give(a, "enchant.levels", spent)
        elif name.startswith(("place:block@", "place:water@", "place:lava@", "place:torch@")):
            return self.place_at(a, name[6:name.index("@")], name[name.index("@") + 1:], r)
        elif name.startswith("use:bucket@"):
            tg = name[11:]
            if a.n("bucket") < 1:
                return "NO_ITEMS"
            raw = self.raw(a)
            if raw.get("tgt_%s_lava_src" % tg, 0) > 0.5 or tg == "moat" and self.moat_liquid and self.moat_liquid[0] == "lava":
                liquid = "lava"
            elif raw.get("tgt_%s_water_src" % tg, 0) > 0.5 or tg == "moat" and self.moat_liquid and self.moat_liquid[0] == "water":
                liquid = "water"
            else:
                return "NOT_SEEN"
            a.add("bucket", -1)
            self.got(a, liquid + "_bucket", 1)
            self.made(a, liquid + "_bucket")
            if tg == "moat":
                self.moat_liquid = None
        elif name.startswith("use:fire@"):
            if a.n("flint_and_steel") < 1:
                return "NO_ITEMS"
            if name.endswith("@mob") and self.enemies > 0 and self.phase == "wave":
                k = min(int(self.enemies), r.randint(0, 2))
                link = self.link(a, a.did, a.did_t, a.kind, "block:fire")
                if k:
                    self.kill(a, k, chain=[link])
                else:
                    self.effect_of(a, [link], "mob_hurt")
            else:
                self.changed = False
        elif name == "attack@mob":
            e = self.enemies if self.phase == "wave" else 0
            if e + self.inside <= 0:
                return "NOT_VISIBLE"
            weapon = a.best_item("sword")
            p = (0.25 + 0.15 * a.held) * (1.0 if a.held else self.sc.get("unarmed", 1.0))
            k = min(int(math.ceil(e + self.inside)), self.poisson(p * 3))
            self.hurt(a, 0.3, 3.0)
            if k:
                self.kill(a, k, inside=self.inside >= k and e < k, weapon=weapon)
            else:
                self.effect_of(a, [self.hit(a), self.cause(a, weapon)], "mob_hurt")
                self.changed = False
        elif name == "attack@animal":
            if self.animals <= 0:
                return "NO_ANIMAL"
            self.leave(a)
            self.animals -= 1
            kind = r.choices(list(ANIMALS), weights=list(ANIMALS.values()))[0]
            self.drops(a, kind)
            self.effect_of(a, [self.hit(a), self.cause(a, a.best_item("sword"))], "animal_killed")
        elif name in ("open@gate", "open@front"):
            if not self.door:
                return "NOT_SEEN"
            self.gate_open = not self.gate_open
        return ""

    def place_at(self, a: Agent, slot: str, tg: str, r: random.Random) -> str:
        """place:<slot>@<target>: a building block, a bucket of water or lava, a torch into a target slot"""
        if slot == "block":
            mat = max(("cobblestone", "planks", "dirt"), key=a.n)
            if a.n(mat) < 1:
                return "NO_ITEMS"
            a.add(mat, -1)
            if tg == "hole" and self.ring and self.wall < RING:
                self.wall += 1
                self.bad = max(0, self.bad - (1 if self.wall > RING - self.bad else 0))
                self.builders.append((a.did, self.t, a.i, a.kind))
            elif tg == "moat" and self.moat > 0:
                self.moat -= 1
            return ""
        if slot == "torch":
            if a.n("torch") < 1:
                return "NO_ITEMS"
            a.add("torch", -1)
            if tg in ("front", "below", "front_down", "hole") and a.depth == 0 and a.dist < 12:
                self.torches = min(12, self.torches + 1)
            return ""
        item = slot + "_bucket"
        if a.n(item) < 1:
            return "NO_ITEMS"
        a.add(item, -1)
        a.add("bucket", 1)
        link = self.link(a, a.did, a.did_t, a.kind, "block:" + slot)
        if tg == "moat" and self.moat > 0:
            self.moat_liquid = (slot, link)
        elif tg == "mob" and slot == "lava" and self.enemies > 0 and self.phase == "wave":
            k = min(int(self.enemies), r.randint(1, 3))
            self.kill(a, k, chain=[link])
        elif slot == "lava" and tg in ("front", "below", "front_down"):   # lava at one's feet: a burn, at worst death
            self.hurt(a, 1.0, 4.0)
            if a.health <= 0:
                self.give(a, "accident")
                self.die(a, out=False, accident=True)
        return ""

    def drops(self, a: Agent, kind: str) -> int:
        """an animal killed: its drops (1.7.10's: sheep wool, chickens feathers, cows leather); its raw meat"""
        r = self.rng
        if kind == "sheep":
            self.got(a, "wool", 1)
            return 0
        n = r.randint(1, 3)
        self.got(a, "food_raw", n, "gathered.food")
        if kind == "chicken":
            f = r.randint(0, 2)
            if f:
                self.got(a, "feather", f)
        if kind == "cow":
            le = r.randint(0, 2)
            if le:
                self.got(a, "leather", le)
                self.achieve(a, "killCow")
        return n

    def end_trip(self, a: Agent):
        """RlScore.Trip: back on the surface with ore got below: a trip done (its descent the cause)"""
        if a.trip_out and a.trip_ores > 0:
            a.trips += 1
            self.give(a, "mine.return", causes=[self.cause(a, "trip")])
        a.trip_out = False
        a.trip_ores = 0

    def put_table(self, a: Agent):
        self.table, self.table_d = True, a.dist
        self.table_bad = self.noise and self.rng.random() < SK["craft_table"].get("table_bad", 0.0)

    def on_craft(self, a: Agent, item: str):
        if item == "crafting_table":
            self.achieve(a, "buildWorkBench")
        elif item.endswith("_pickaxe"):
            self.achieve(a, "buildPickaxe")
            if item != "wooden_pickaxe":
                self.achieve(a, "buildBetterPickaxe")
        elif item.endswith("_sword"):
            self.achieve(a, "buildSword")
        elif item == "furnace":
            self.achieve(a, "buildFurnace")
        elif item.endswith("_hoe"):
            self.achieve(a, "buildHoe")
        elif item == "bread":
            self.achieve(a, "makeBread")
        elif item == "enchanting_table":
            self.achieve(a, "enchantments")
        elif item == "bookshelf":
            self.achieve(a, "bookcase")

    # ---- the world's time -------------------------------------------------------------------------------
    def poisson(self, lam: float) -> int:
        if lam <= 0:
            return 0
        if lam < 0.3:
            return 1 if self.rng.random() < lam else 0
        L, k, p = math.exp(-lam), 0, 1.0
        while True:
            p *= self.rng.random()
            if p <= L:
                return k
            k += 1

    def hurt(self, a: Agent, rate: float, dt: float, dmg: float = 3.0, inside: bool = False):
        n = self.poisson(rate * dt)
        if n:
            hp = n * dmg * (1 - 0.04 * a.armor())
            a.health -= hp
            self.give(a, "damage", hp)
            if inside:
                self.give(a, "perimeter.hurt_inside", hp)
            return hp
        return 0.0

    def kill(self, a: Agent, k: int, inside: bool = False, weapon: str | None = None, chain: list | None = None):
        """k mobs killed by a: the chain is the blow (the running decision) and the craft of the weapon that did it,
        or the one given (lava, fire: the decision that put it)"""
        if inside:
            self.inside -= k
        else:
            self.enemies -= k
        self.kills += k
        if self.v4:
            self.kill4(a, k, inside, chain)
        weapon = weapon or a.best_item("sword")
        causes = chain if chain is not None else [self.hit(a), self.cause(a, weapon)]
        self.give(a, "kill", k, causes=causes)
        self.give(a, "perimeter.kill", k, causes=causes)
        self.effect_of(a, causes, "mob_killed")
        if chain is None:
            self.chain(a, weapon, "kill", k)
        self.xp(a, 5 * k)
        self.achieve(a, "killEnemy")
        if a.enchanted and a.tier("sword") >= 4 and self.rng.random() < 0.1 * k:   # 18 in a hit: an enchanted diamond sword
            self.achieve(a, "overkill")
        if self.rng.random() < 0.3 * k:   # spiders' string
            n = self.rng.randint(0, 2)
            if n:
                self.got(a, "string", n)

    def advance(self, until: float):
        while self.t < until - 1e-9 and not self.done:
            dt = min(2.0, until - self.t)
            self.t += dt
            for a in self.agents:
                if a.alive:
                    self.give(a, "time", dt)
            self.world(dt)
            self.measure()
            if V31 and OUTCOMES:   # (3.1) idling, dearer the longer it goes on (RlScore.Afk; the simulator's place is its distance from the base)
                for a in self.agents:
                    if a.alive:
                        a.afk = a.afk or Afk()
                        f = a.afk.second(self.t, a.dist, 0.0, a.real)
                        if f:
                            self.give(a, "afk", f * dt)
                    a.real = False
            if self.v4:
                self.flush(dt)
            if V2 and not self.sim_only:
                for a in self.agents:   # RlScore.Stall
                    if a.alive and self.t - a.progress_t >= STALL_S:
                        a.progress_t = self.t
                        self.give(a, "stall")
            if self.phase in ("prep", "break", "wave") and self.t >= self.phase_until:
                self.next_phase()
            if self.t >= self.sc["seconds"] and not self.done:
                self.done, self.reason = True, "time"

    def world(self, dt: float):
        self.respawn()
        wave = self.phase == "wave"
        gateway = self.gate_open or not self.door   # the gate's 1x2 opening stands open until a door is in it (as in the game)
        open_ = 2 if gateway else 0
        wallf = max(0.0, self.wall - open_) / RING if self.ring else 0.0
        leak = 1 - wallf
        if self.ring and gateway:   # the mobs find the opening: most of a wave comes in through it (WCAL: from the engine)
            leak = max(leak, WCAL["gate_leak"])
        leak *= (1 - 0.4 * self.moat / MOAT) * (0.7 if self.moat_liquid else 1.0)
        if self.crops:   # crops, saplings, young animals grow
            ripe = [c for c in self.crops if c[0] <= self.t]
            if ripe:
                self.ripe += [(c[1], c[2]) for c in ripe]
                self.crops = [c for c in self.crops if c[0] > self.t]
        if self.saplings:
            grown = [s for s in self.saplings if s <= self.t]
            if grown:
                self.trees += len(grown)
                self.grown += len(grown)
                self.saplings = [s for s in self.saplings if s > self.t]
        if self.pen_young:
            for t, k in [y for y in self.pen_young if y[0] <= self.t]:
                self.pen_adults[k] = self.pen_adults.get(k, 0) + 1
            self.pen_young = [y for y in self.pen_young if y[0] > self.t]
        for a in self.agents:
            if not a.alive:
                if self.t >= a.free_at:   # back at the base, the bag empty
                    a.alive, a.health, a.dist, a.post, a.depth, a.food, a.sat = True, 20.0, 0.5, False, 0, 20.0, 5.0
                continue
            if self.hunger:
                drain = dt / CAL["hunger"]["drain_s"] * (1.5 if a.depth or wave else 1.0)
                if a.sat > 0:
                    a.sat = max(0.0, a.sat - drain)
                else:
                    a.food = max(0.0, a.food - drain)
                if a.food >= CAL["hunger"]["regen_min_food"]:
                    a.health = min(20.0, a.health + 0.25 * dt)
                elif a.food <= 0:   # contract 2: hunger hurts to death (as "hard"); the death is an accident
                    hp = dt / CAL["hunger"]["starve_dmg_s"]
                    a.health -= hp
                    self.give(a, "damage", hp)
                    if a.health <= 0:
                        self.give(a, "accident")
                        self.die(a, out=False, accident=True)
                        continue
            else:
                a.health = min(20.0, a.health + 0.25 * dt)   # the agents count as fed: 1 a 4 s
            if a.depth:   # under the surface: caves' mobs, now and then lava or a fall
                lit = 0.5 if a.n("torch") else 1.0
                self.hurt(a, CAL["mine"]["cave_hits"][a.depth] * lit, dt, 4.0)
                risk = self.sc.get("lava_risk", 0.0) if a.depth == 2 else 0.0
                if (self.noise or risk) and self.rng.random() < (CAL["mine"]["accident"][a.depth] + risk) * dt / 10:
                    if a.n("water_bucket") and self.rng.random() < 0.85:   # the reflex: water under itself, saved
                        a.add("water_bucket", -1)
                        a.add("bucket", 1)
                        link = self.cause(a, "water_bucket", "block:water")
                        self.effect_of(a, [link] if link else [self.link(a, a.did, a.did_t, "reflex:water", "block:water")], "agent_saved")
                        continue
                    self.give(a, "accident")
                    self.die(a, out=False, accident=True)
                    continue
                if a.health <= 0:
                    self.die(a, out=False)
                continue
            if self.sheltered(a) and self.night_or_wave():   # 3.1: shut in, the mobs do not reach it
                self.ob["shelter_s"] += dt
                if self.v4 and self.sc.get("night"):   # the mobs try at it and the blocks round it turn them
                    mine = self.night4(a, self.sc["night"] + self.t / 60)
                    if self.rng4.random() < min(1.0, 0.035 * len(mine) * dt):
                        self.fev.append({"type": "attack_failed", "mob": self.rng4.choice(mine), "target": a.i, "reason": "cover",
                                         "by": list(a.box), "seen": [a.i]})
                continue
            if self.sc.get("night") and a.alive:   # the night scenario: mobs about, more with the time
                m = self.sc["night"] + self.t / 60
                deep = a.near is not None and a.depth == 0 and a.near.pit() >= 2
                if self.v4:
                    self._hurt_mob = self.rng4.choice(self.night4(a, m))
                self.hurt(a, 0.035 * m * (0.5 if deep else 1.0), dt)
                self._hurt_mob = None
                if a.held and self.poisson(0.01 * a.held * dt):
                    self.kills += 1
                    if self.v4:
                        mine = self.night4(a, m)
                        x = mine.pop(self.rng4.randrange(len(mine)))
                        self.fev.append({"type": "kill", "mob": x, "agent": a.i, "decision": a.did, "seen": [a.i]})
                if a.health <= 0:
                    self.die(a, out=True)
                continue
            if not wave:
                continue
            e = max(0.0, self.enemies)
            if e <= 0 and self.inside <= 0:
                continue
            inside = a.dist < 8 and self.perim()
            if a.dist > 10:   # out in the field during a wave
                self.hurt(a, WCAL["hurt"] * 0.08 * math.sqrt(e), dt)
            elif a.holding and a.post:
                self.hurt(a, WCAL["hurt"] * 0.025 * math.sqrt(e) * (0.4 + leak), dt)
                unarmed = 1.0 if a.held else self.sc.get("unarmed", 1.0)
                k = min(self.poisson(WCAL["kill"] * (0.05 + 0.04 * a.held) * unarmed * dt * min(1.0, e / 2)), int(math.ceil(self.enemies)))
                if k:
                    self.kill(a, k)
            else:
                self.hurt(a, WCAL["hurt"] * 0.025 * e * (leak if inside else 1.0), dt, inside=bool(inside))
                if leak > 0.3 or not self.ring:
                    unarmed = 1.0 if a.held else self.sc.get("unarmed", 1.0)
                    k = min(self.poisson(WCAL["kill"] * 0.012 * (1 + a.held) * unarmed * dt), int(math.ceil(self.enemies)))
                    if k:
                        self.kill(a, k)
            if inside and self.inside > 0:   # what got in hits the agents inside, they fight it
                self.hurt(a, WCAL["hurt"] * 0.05 * self.inside, dt, inside=True)
                k = min(self.poisson(WCAL["kill"] * 0.04 * (1 + a.held) * (1.0 if a.held else self.sc.get("unarmed", 1.0)) * dt), int(math.ceil(self.inside)))
                if k:
                    self.kill(a, k, inside=True)
            if a.health <= 0:
                self.die(a, out=not inside)
        if wave:
            if self.ring and self.wave_num >= 6 and self.wall > 0:   # creepers from the 6th wave
                self.wall = max(0, self.wall - self.poisson(0.01 * self.enemies * dt * (1 - 0.5 * self.moat / MOAT)))
            if self.moat_liquid and self.moat_liquid[0] == "lava" and self.enemies > 0 and self.moat > 0:
                # the trap: mobs crossing the lava moat burn; the kills are its pourer's (chain: block:lava)
                k = min(self.poisson(0.03 * self.enemies * dt * self.moat / MOAT), int(self.enemies))
                if k:
                    who = next((b for b in self.agents if b.i == self.moat_liquid[1]["agent"]), self.agents[0])
                    self.kill(who, k, chain=[self.moat_liquid[1]])
            if self.perim() and self.enemies > 0:   # the perimeter: mobs that get inside the wall
                k = min(self.poisson(0.02 * self.enemies * leak * dt), int(self.enemies))
                if k:
                    self.enemies -= k
                    self.inside += k
                    self.breaches += k
                    self.wave_breaches += k
                    self.give_all("perimeter.breach", k)
                    if self.v4:
                        for _ in range(k):
                            if self.mobs_out:
                                m = self.mobs_out.pop(0)
                                self.mobs_in.append(m)
                                self.fev.append({"type": "breach", "mob": m})
            if self.inside > 0:
                self.mob_s += self.inside * dt
                self.wave_mob_s += self.inside * dt
                self.give_all("perimeter.mob_seconds", self.inside * dt)
                if self.v4:
                    self.fev.append({"type": "inside", "mob_s": float(self.inside * dt)})
            if self.enemies <= 0.5 and self.inside <= 0.5:
                self.enemies = self.inside = 0
                if self.v4:
                    self.gone4()
                self.cleared += 1
                self.give_all("wave_cleared")
                self.give_all("perimeter.wave_cleared")
                if self.wave_breaches == 0 and self.ring:   # a clean wave: the wall's builders are its causes
                    self.clean += 1
                    for a in self.agents:
                        self.give(a, "perimeter.clean_wave", causes=[self.link(next(x for x in self.agents if x.i == b[2]), b[0], b[1], b[3],
                                                                               "block:cobblestone") for b in self.builders[-8:]])
                self.phase, self.phase_until = "break", self.t + (60 if self.sc["seconds"] > 1200 else 20)
            elif self.wave_losses >= LOSSES_PER_WAVE:   # the game's rule (WaveCycle.lossesPerWave): the crew's deaths in one wave
                self.done, self.reason = True, "lost"
                if self.v4:
                    self.fall4("lost")

    def die(self, a: Agent, out: bool, accident: bool = False):
        if self.v4:
            self.fev.append({"type": "death", "agent": a.i, "cause": "accident" if accident else "mob", "base_dist": float(a.dist)})
            a.box = []
        if not accident:
            self.give(a, "death")
        if out and self.phase == "wave":
            self.give(a, "out_died")
            self.give(a, "perimeter.out_died")
        if self.phase == "wave":
            self.wave_losses += 1
        a.alive = False
        a.bag = {}
        a.worn = {}
        a.made = {}
        a.held = 0
        a.health = 0
        a.depth = 0
        a.trip_out = False
        a.trip_ores = 0
        if a.post:
            self.posts_taken -= 1
        a.post = a.holding = False
        if a.pending:
            act, _, _, began = a.pending
            a.pending = None
            self.ended(a, act, "DIED", self.t - began)
        a.free_at = self.t + 10.0
        a.respawn_at = self.t + RESPAWN_S

    def respawn(self):
        """as the game's crew (Crew.respawn): a second after its death the agent is back at the base, full health, an empty bag"""
        for a in self.agents:
            at = getattr(a, "respawn_at", None)
            if not a.alive and at is not None and self.t >= at:
                a.alive, a.health, a.dist, a.free_at, a.respawn_at = True, 20.0, 0.0, self.t, None

    def next_phase(self):
        if self.phase in ("prep", "break"):
            self.wave_num += 1
            mult = 0.7 if self.waves == "easy" else 1.0
            n = self.wave_num
            self.enemies = float(max(1, math.floor(mult * (2 + 1.6 * n + 0.12 * n * n))))
            self.enemies *= 1 - min(0.3, self.torches / 40) if not self.sim_only else 1 - min(0.8, self.torches / 15)
            self.enemies = float(round(self.enemies))
            self.spawned += int(self.enemies)
            self.wave_losses = 0
            self.wave_breaches = 0
            self.wave_mob_s = 0.0
            self.phase, self.phase_until = "wave", self.t + 240
            if self.v4:   # the wave's mobs: threats to the base from their coming
                self.gone4()
                for _ in range(int(self.enemies)):
                    m = self.mob4()
                    self.mobs_out.append(m)
                    self.fev.append({"type": "threat", "mob": m, "kind": "zombie", "target": "base", "dmg": 3.0, "stay_s": 240.0})
            if self.ring:
                self.give_all("holes_at_wave", RING - self.wall)
                self.give_all("perimeter.holes_at_wave", RING - self.wall)
        elif self.phase == "wave":   # its time is up: the mobs go
            self.enemies = 0
            self.inside = 0
            if self.v4:
                self.gone4()
            self.phase, self.phase_until = "break", self.t + 20

    # ---- the protocol -----------------------------------------------------------------------------------
    def decide(self, ready: list[Agent], first: bool = False, final: bool = False):
        out = {"agents": [], "obs": [], "mask": [], "raw": [], "reward": [], "components": [], "done": bool(self.done),
               "t": self.t}
        if not first:
            self.measure()
        if self.v4:
            if self.done and self.sc.get("night"):   # the night is over: its mobs leave (what turned them is settled)
                for ms in self.night_mobs.values():
                    for m in ms:
                        self.fev.append({"type": "mob_gone", "mob": m, "why": "despawn"})
                self.night_mobs = {}
            self.flush(0.0)
            for a in ready:
                a.acc, a.comp = self.core.take(a.i)
        for a in ready:
            if self.shaping and not first:
                phi = 0.0 if final else self.potential()
                f = 0.99 * phi - a.phi
                a.acc += f
                a.comp["shaping"] = a.comp.get("shaping", 0.0) + f
                self.comp["shaping"] = self.comp.get("shaping", 0.0) + f
                a.phi = phi
            raw = self.raw(a)
            out["agents"].append(a.i)
            out["raw"].append(raw)
            out["obs"].append(spec.vector(raw))
            out["mask"].append(spec.mask(raw))
            out["reward"].append(a.acc)
            out["components"].append(a.comp)
            a.acc, a.comp = 0.0, {}
        out["info"] = {"t": self.t, "ticks": int(self.t * 20), "scenario": self.scenario, "done_reason": self.reason,
                       "success": self.success() if self.done else False, "cleared": self.cleared,
                       "goal_progress": self.progress(),
                       "episode_components": (dict(self.core.episode) if self.v4 else dict(self.comp)) if self.done else {},
                       "achievements": self.crew_ach(), "breaches": self.breaches, "mob_seconds": round(self.mob_s, 1),
                       "clean_waves": self.clean, "novel": self.novel, "repeat_fail": self.repeat_fail, "discoveries": self.discoveries, "kills": self.kills,
                       "spawned": self.spawned, "assigned": dict(self.assigned), "events": self.events,
                       "sustain": [round(self.sus["food_renew"], 2), round(self.sus["food_eaten"], 2)]}
        if V31:
            out["info"]["obstacles"] = {k: (round(v, 1) if isinstance(v, float) else v) for k, v in self.ob.items()}
            out["info"]["obstacles"]["hang"] = int(self.hang is not None)
        self.assigned, self.events = {}, []
        if self.done:
            got = set()
            for a in self.agents:
                got |= a.ach
            out["info"]["achievement_ids"] = sorted(got - {"openInventory"})
            out["info"]["journal"] = sorted(self.journal)
            out["info"]["chains"] = [{"agent": k[0], "cause": k[2], "made_s": round(v[0], 1), "last_s": round(v[2], 1), "effects": v[1]}
                                     for k, v in self.chains.items()]
        return out

    def step(self, actions: dict[int, int]):
        assert not self.done
        for i, act in actions.items():
            a = self.agents[i]
            if act == IDX["noop"]:
                self.give(a, "noop")
                if self.v4:
                    self.fall4("noop")
                a.last, a.outcome = act, "none"
                self.done, self.reason = True, "noop"
                return self.close()
            self.begin(a, act)
        if self.v4:
            self.flush(0.0)
        while not self.done:
            for a in self.agents:
                if a.alive and a.pending is not None and a.free_at <= self.t + 1e-9:
                    self.finish(a)
            if self.goal_met():
                self.done, self.reason = True, "goal"
                break
            ready = [a for a in self.agents if a.alive and a.pending is None and a.free_at <= self.t + 1e-9]
            if ready:
                return self.decide(ready)
            nxt = min([a.free_at for a in self.agents if a.free_at > self.t + 1e-9] + [self.phase_until, self.t + 2.0])
            self.advance(max(nxt, self.t + 1e-3))
        return self.close()

    def close(self):
        if self.reason == "goal" or "pass_alive" in self.sc and self.success():
            self.give_all("goal")
        for a in self.agents:
            a.pending = None
        return self.decide(list(self.agents), final=True)


if __name__ == "__main__":   # a random agent (uniform over the allowed actions, never noop)
    import collections
    import time
    env = SimEnv(0)
    t0 = time.time()
    steps = 0
    n_ep = int(sys.argv[1]) if len(sys.argv) > 1 else 200
    for sc in spec.SCENARIOS + list(SIM_SCEN):
        res = collections.Counter()
        for ep in range(n_ep):
            o = env.reset(sc, ep, crew=1 + ep % 3)
            while not o["done"]:
                acts = {}
                for i, m in zip(o["agents"], o["mask"]):
                    ok = [k for k, v in enumerate(m) if v and k != 0]
                    acts[i] = env.rng.choice(ok)
                o = env.step(acts)
                steps += 1
            for k in ("success", "cleared", "achievements", "breaches", "novel", "kills"):
                res[k] += o["info"][k]
            res[o["info"]["done_reason"]] += 1
        print(sc, dict(res))
    print(steps, "steps", round(time.time() - t0, 1), "s")
