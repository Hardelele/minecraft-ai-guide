"""A fast, rough simulator of the crew's world in the shape of the real environment (rl/env, docs/rl/CONTRACT.md):
the same actions, raw facts by the game's names (so the same obs vector and mask through tools/rl/spec.py), the
reward's weights of rl/reward.json, the scenarios' kits, goals and limits, steps at the agents' decision points.
For debugging PPO and a first set of weights on the GPU in minutes; it is not the game.

The world, roughly: a crew's memory of what it has seen (trees, stone, coal, iron, animals; look_around and
go_to:frontier see more, mining shows ores), the game's recipes (2x2 in the hands, 3x3 at a table), a furnace and
fuel, a chest, a base with a ring wall (cells, posts), a moat, torches; the waves of WavePlan's budget (prep 30 s,
break 20 s, a wave at most 240 s; lost when a wave took the whole crew) with a crude fight: kills by weapon tier at a
post, hits by where one stands and how much wall stands, holes by creepers from wave 6. A skill takes game seconds;
its effects come at its end (an agent that dies loses them and its bag, and comes back at the base).

Protocol (rl/env's, in Python): reset(scenario, seed, crew) -> {agents, obs, mask, raw, reward, done, info};
step({agent: action}) -> the same; `agents` are those at a decision now and their reward is what they got since
their last decision; at done every agent closes its step; info: scenario, done_reason, components, cleared, ticks.
"""
from __future__ import annotations

import math
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import spec  # noqa: E402
from spec import ACTIONS, IDX, RECIPES, SCEN, WEIGHTS  # noqa: E402

RING, MOAT, POSTS = 64, 80, 4      # a ring of radius 8, height 2 (cells), its moat, its posts
TOOLS = ("_pickaxe", "_sword")


class Agent:
    def __init__(self, i: int):
        self.i = i
        self.bag: dict[str, int] = {}
        self.health = 20.0
        self.held = 0
        self.armor = 0
        self.dist = 0.0          # from the base
        self.post = False
        self.free_at = 0.0
        self.alive = True
        self.pending = None      # (action, ok, reason, began)
        self.holding = False
        self.acc = 0.0
        self.comp: dict[str, float] = {}
        self.last = -1
        self.outcome = "none"
        self.repeated = 0
        self.last_s = 0.0
        self.best = {"pick": 0, "weapon": 0, "armor": 0}

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


class SimEnv:
    def __init__(self, seed: int = 0, shaping: float = 0.0, weights: dict | None = None):
        self.rng = random.Random(seed)
        self.shaping = shaping
        self.w = dict(WEIGHTS if weights is None else weights)
        self.scenario = "wood"

    # ---- the episode ------------------------------------------------------------------------------------
    def reset(self, scenario: str = "wood", seed: int | None = None, crew: int = 1):
        if seed is not None:
            self.rng.seed(seed)
        if scenario not in SCEN:
            raise ValueError("no scenario " + scenario)
        r = self.rng
        self.scenario = scenario
        self.sc = SCEN[scenario]
        self.t = 0.0
        self.trees = r.randint(0, 6)          # seen and free
        self.trees_hidden = r.randint(8, 30)  # there, to be seen
        self.stone_seen = r.randint(0, 20)
        self.coal = r.choice([0, 0, 1, 2])
        self.coal_hidden = r.randint(2, 8)
        self.iron = 0
        self.iron_hidden = r.randint(1, 5)
        self.animals = r.randint(0, 2)
        self.animals_hidden = r.randint(2, 8) if scenario == "food" else r.randint(0, 4)
        self.table = self.furnace = False
        self.table_d = self.furnace_d = 64.0
        self.chest = False
        self.chest_food = 0
        self.ring = False
        self.wall = 0
        self.moat = 0
        self.torches = 0
        self.posts_taken = 0
        self.waves = self.sc["waves"]
        self.phase = "prep" if self.waves else "off"
        self.phase_until = 30.0 if self.waves else 1e18
        self.wave_num = 0
        self.enemies = 0.0
        self.wave_losses = 0
        self.cleared = 0
        self.items_seen: set[str] = set()
        self.comp: dict[str, float] = {}
        self.done = False
        self.reason = ""
        self.agents = [Agent(i) for i in range(max(1, min(spec.MAX_AGENTS, crew)))]
        for a in self.agents:
            for k, v in self.sc["kit"].items():
                a.add(k, v)
            a.health = float(self.sc["health"])
            a.dist = r.uniform(0, 3)
            for kind in a.best:
                a.best[kind] = a.tier(kind) if kind != "armor" else 0
        if "chest" in self.sc:
            self.chest = True
            self.chest_food = self.sc["chest"]
        for a in self.agents:
            self.items_seen.update(a.bag)
        for a in self.agents:
            a.phi = self.potential()
        return self.decide(list(self.agents), first=True)

    # ---- the raw facts (RlEnv.facts' names) -------------------------------------------------------------
    def raw(self, a: Agent) -> dict:
        x: dict[str, float] = {}
        x["scen_" + self.scenario.replace(":", "_")] = 1
        x["agent_%d" % min(a.i, spec.MAX_AGENTS - 1)] = 1
        x["n_agents"] = len(self.agents)
        x["health"], x["max_health"], x["low_health"] = a.health, 20, spec.LOW
        x["armor"] = a.armor
        x["held_tier"] = a.held
        x["pick_tier"] = a.tier("pickaxe")
        x["weapon_tier"] = a.tier("sword")
        x["at_home"] = 1 if a.dist < 4 else 0
        tod = self.sc["time"]   # the arena's time stands
        x["day_sin"], x["day_cos"] = math.sin(2 * math.pi * tod / 24000), math.cos(2 * math.pi * tod / 24000)
        x["night"] = 1 if 13000 <= tod < 23000 else 0
        x["dusk_in"] = (12500 - tod) / 20.0 if tod < 12500 else 0
        tot = stack = 0
        for k, v in a.bag.items():
            x["bag_" + k] = v
            tot += v
            if not k.endswith(TOOLS):
                stack += v
        x["bag_total"], x["bag_stackables"] = tot, stack
        x["fuel"] = a.n("coal") + a.n("planks") + a.n("log")
        x["wall_blocks"] = max(a.n("cobblestone"), a.n("planks"), a.n("dirt"))
        d = lambda n, far: (far if n <= 0 else max(2.0, a.dist * 0.5 + 6 + 20 / (1 + n)))  # noqa: E731
        x["trees_seen"], x["tree_dist"], x["trees_free"] = self.trees, d(self.trees, 48), self.trees
        x["stone_seen"], x["stone_dist"] = self.stone_seen, d(self.stone_seen, 32)
        x["coal_seen"], x["coal_dist"], x["coal_minable"] = self.coal, d(self.coal, 48), self.coal
        x["iron_seen"], x["iron_dist"], x["iron_minable"] = self.iron, d(self.iron, 48), self.iron
        x["animals_seen"], x["animal_dist"] = self.animals, d(self.animals, 24)
        x["frontier"] = 1 if self.trees_hidden + self.animals_hidden + self.coal_hidden > 0 else 0
        x["table_known"], x["table_dist"] = int(self.table), (abs(a.dist - self.table_d) + 1 if self.table else 24)
        x["furnace_known"], x["furnace_dist"] = int(self.furnace), (abs(a.dist - self.furnace_d) + 1 if self.furnace else 24)
        x["chest_known"], x["chest_dist"] = int(self.chest), (a.dist + 1 if self.chest else 48)
        x["chest_food"] = self.chest_food
        x["base_known"], x["base_dist"] = 1, a.dist
        w = self.phase in ("prep", "wave", "break")
        x["waves_active"] = int(w)
        x["wave_on"] = int(self.phase == "wave")
        x["phase_prep"], x["phase_break"] = int(self.phase == "prep"), int(self.phase == "break")
        x["wave_num"] = self.wave_num
        x["next_wave_in"] = max(0.0, self.phase_until - self.t) if self.phase in ("prep", "break") else 0
        e = self.enemies if self.phase == "wave" else 0
        x["enemies"] = e
        x["enemy_dist"] = (8 + 10 * (self.wall / RING) if e > 0 else 32)
        x["enemy_dx"], x["enemy_dz"] = (self.rng.uniform(-12, 12), self.rng.uniform(-12, 12)) if e > 0 else (0, 0)
        x["enemies_near"] = min(e, e * (1 - self.wall / RING if self.ring else 1))
        x["ring_planned"] = int(self.ring)
        if self.ring:
            x["wall_frac"], x["holes"] = self.wall / RING, RING - self.wall
            x["inside_wall"] = 1 if a.dist < 8 else 0
            x["posts_free"] = max(0, POSTS - self.posts_taken)
        x["moat_frac"] = self.moat / MOAT
        x["torches_placed"] = self.torches
        x["has_post"] = int(a.post)
        x["post_dist"] = abs(a.dist - 7) if a.post else 0
        x["light_spots"] = max(0, 12 - self.torches) if a.n("torch") else 0
        x["can_equip"] = 1 if a.tier("sword") > a.held else 0
        others = [b for b in self.agents if b is not a and b.alive]
        x["allies_alive"] = len(others)
        x["allies_health_mean"] = sum(b.health for b in others) / len(others) if others else 20
        x["allies_health_min"] = min((b.health for b in others), default=20)
        x["allies_low"] = sum(1 for b in others if b.health <= spec.LOW)
        x["ally_dist"] = min((abs(a.dist - b.dist) + 2 for b in others), default=32)
        x["last_" + a.outcome] = 1
        x["repeated"] = a.repeated
        x["last_seconds"] = a.last_s
        if a.last >= 0:
            x["last_action_" + ACTIONS[a.last]] = 1
        x["time_frac"] = self.t / self.sc["seconds"]
        x["goal_progress"] = self.progress()
        for item, (need, _, small) in RECIPES.items():
            ok = all(a.n(k) >= v for k, v in need.items())
            x["craftable_" + item] = int(ok)
            x["small_" + item] = int(ok and small)
        return x

    # ---- the goal ---------------------------------------------------------------------------------------
    def crew(self, k):
        return sum(a.n(k) for a in self.agents if a.alive)

    def food(self):
        return self.crew("food_raw") + self.crew("food_cooked")

    def progress(self) -> float:
        s, n = self.sc, len(self.agents)
        if s["goal"] == "items":
            return min(1.0, self.crew(s["item"]) / (s["per"] * n))
        if s["goal"] == "food":
            return min(1.0, self.food() / (s["per"] * n))
        if s["goal"] == "fortify":
            return 0.5 * self.wall / RING + 0.5 * min(1.0, self.moat / (0.9 * MOAT))
        return min(1.0, self.cleared / 10)

    def goal_met(self) -> bool:
        s = self.sc
        if s["goal"] == "none":
            return False
        if s["goal"] == "fortify":
            return self.wall >= RING and self.moat >= 0.9 * MOAT
        return self.progress() >= 1.0

    def success(self) -> bool:
        """the policy side's success: the goal, or for a waves run WAVES_PASS waves cleared"""
        if self.sc["goal"] == "none":
            return self.cleared >= spec.WAVES_PASS.get(self.scenario, 3)
        return self.goal_met()

    def potential(self) -> float:
        """the optional shaping's potential: the goal's progress plus the crafting chain's steps toward it"""
        if not self.shaping:
            return 0.0
        c = self.crew
        chain = 0.0
        item = self.sc["item"]
        if item in ("stone_pickaxe", "stone_sword", "iron_pickaxe"):
            chain = 0.2 * ((c("log") + c("planks") + c("stick")) > 0) + 0.2 * (self.table or c("crafting_table") > 0) \
                + 0.2 * (c("wooden_pickaxe") + c("stone_pickaxe") + c("iron_pickaxe") > 0) + 0.2 * (c("cobblestone") >= 3)
            if item == "iron_pickaxe":
                chain = 0.25 * (c("iron_ore") + c("iron_ingot") > 0) + 0.25 * (self.furnace or c("furnace") > 0) + 0.3 * min(1, c("iron_ingot") / 3)
        return self.shaping * (self.progress() + chain)

    # ---- rewards ----------------------------------------------------------------------------------------
    def give(self, a: Agent, key: str, n: float = 1.0):
        p = self.w.get(key, 0.0) * n
        if p == 0:
            return
        comp = key.split(".")[0]
        a.acc += p
        a.comp[comp] = a.comp.get(comp, 0.0) + p
        self.comp[comp] = self.comp.get(comp, 0.0) + p

    def give_all(self, key, n=1.0):
        for a in self.agents:
            self.give(a, key, n)

    def got(self, a: Agent, k: str, v: int, key: str | None = None):
        a.add(k, v)
        if key:
            self.give(a, key, v)
        if k not in self.items_seen:
            self.items_seen.add(k)
            self.give(a, "new_item")

    # ---- skills -----------------------------------------------------------------------------------------
    def begin(self, a: Agent, act: int):
        name = ACTIONS[act]
        r = self.rng
        raw = self.raw(a)
        if not spec.allowed(act, raw):   # RlEnv: a masked action is a failed step, nothing done
            a.pending = (act, False, "MASKED", self.t)
            a.free_at = self.t
            return
        ok, reason = True, ""
        far = a.dist
        if name == "look_around":
            dur = 3.0
        elif name == "go_to:frontier":
            dur = 12 + r.uniform(0, 10)
        elif name == "chop_tree:near":
            dur = r.uniform(12, 25) + raw["tree_dist"] * 0.3
            ok = r.random() > 0.07
            reason = "" if ok else "NO_PATH"
        elif name.startswith("mine:"):
            sp = {1: 1.0, 2: 1.5, 3: 2.0}.get(int(raw["pick_tier"]), 1.0)
            dur = {"mine:stone": 22, "mine:coal_ore": 12, "mine:iron_ore": 16}[name] / sp + r.uniform(0, 5)
            ok = r.random() > 0.07
            reason = "" if ok else "BLOCKED"
        elif name.startswith("craft:"):
            dur = 2.0 if RECIPES[name[6:]][2] else 2.0 + (0 if self.table and abs(a.dist - self.table_d) < 6 else 4)
        elif name.startswith("smelt:"):
            dur = 4 + 10 * min(3 if name.endswith("ingot") else 4, a.n("iron_ore") if name.endswith("ingot") else a.n("food_raw"))
        elif name.startswith("place:"):
            dur = 1.5
        elif name == "build_wall:ring":
            dur = 3 + min(24, RING - self.wall, int(raw["wall_blocks"])) * 0.8 + far * 0.3
        elif name == "repair_wall:nearest":
            dur = 3 + min(RING - self.wall, int(raw["wall_blocks"])) * 0.8
        elif name == "dig_moat:ring":
            dur = 3 + min(16, MOAT - self.moat) * 1.2
            ok = a.tier("pickaxe") >= 1 or r.random() < 0.5
            reason = "" if ok else "NO_TOOL"
        elif name == "light_area:base":
            dur = 3 + min(a.n("torch"), int(raw["light_spots"])) * 1.5
        elif name == "equip:best":
            dur = 1.0
        elif name == "take_post:auto":
            dur = 3 + abs(far - 7) * 0.4
        elif name == "hold_line:post":
            dur = 60.0
            a.holding = True
        elif name in ("retreat:home", "go_to:base"):
            dur = 2 + far * 0.4
        elif name == "eat:best":
            dur = 2.0
        elif name == "hunt:near":
            dur = r.uniform(10, 30)
            ok = r.random() > 0.2
            reason = "" if ok else "NO_PATH"
        elif name in ("put:chest_all", "take:chest_food"):
            dur = 3 + far * 0.3
        elif name == "wait:5":
            dur = 5.0
        else:
            dur = 2.0
        if not ok:
            dur = min(dur, r.uniform(2, 6))
        a.pending = (act, ok, reason, self.t)
        a.free_at = self.t + dur

    def finish(self, a: Agent):
        act, ok, reason, began = a.pending
        a.pending = None
        a.holding = False
        name = ACTIONS[act]
        if ok:
            res = self.effect(a, name, self.rng)
            if res:
                ok, reason = False, res
        result = "OK" if ok else reason
        self.ended(a, act, result, self.t - began)

    def ended(self, a: Agent, act: int, result: str, secs: float):
        out = spec.outcome(result)
        if out != "ok" and a.last == act and a.outcome == out:
            a.repeated += 1
        else:
            a.repeated = 0 if out == "ok" else 1
        a.last, a.outcome, a.last_s = act, out, secs
        self.give(a, "skill_ok" if result == "OK" else "skill_fail")
        for kind in ("pick", "weapon", "armor"):
            t = a.tier({"pick": "pickaxe", "weapon": "sword"}.get(kind, "armor_none")) if kind != "armor" else a.armor // 5
            if t > a.best[kind]:
                self.give(a, "tier_up", t - a.best[kind])
                a.best[kind] = t

    def see(self, attr: str, hidden: str, p: float, k: int = 1):
        h = getattr(self, hidden)
        if h > 0 and self.rng.random() < p:
            n = min(h, k)
            setattr(self, hidden, h - n)
            setattr(self, attr, getattr(self, attr) + n)

    def effect(self, a: Agent, name: str, r: random.Random) -> str:
        """the skill's effects at its end; "" when it went well, else the reason it failed"""
        if name == "look_around":
            self.see("trees", "trees_hidden", 0.7, r.randint(1, 4))
            self.see("animals", "animals_hidden", 0.4, r.randint(1, 2))
            self.see("coal", "coal_hidden", 0.2)
            self.stone_seen += r.randint(0, 6)
        elif name == "go_to:frontier":
            a.dist += r.uniform(8, 16)
            self.see("trees", "trees_hidden", 0.9, r.randint(2, 6))
            self.see("animals", "animals_hidden", 0.6, r.randint(1, 3))
            self.see("coal", "coal_hidden", 0.35)
            self.see("iron", "iron_hidden", 0.15)
            self.stone_seen += r.randint(0, 10)
        elif name == "chop_tree:near":
            if self.trees <= 0:
                return "NO_TREE_THERE"
            self.trees -= 1
            self.got(a, "log", r.randint(3, 6), "gathered.log")
            a.dist = max(a.dist, r.uniform(6, 20))
        elif name == "mine:stone":
            self.got(a, "cobblestone", 8, "gathered.cobblestone")
            self.stone_seen = max(self.stone_seen, 8)
            self.see("coal", "coal_hidden", 0.5, r.randint(1, 2))
            self.see("iron", "iron_hidden", 0.35)
            a.dist = max(a.dist, r.uniform(4, 16))
        elif name == "mine:coal_ore":
            if self.coal <= 0:
                return "NOT_SEEN"
            self.coal -= 1
            self.got(a, "coal", 3, "gathered.coal")
            self.got(a, "cobblestone", 2, "gathered.cobblestone")
        elif name == "mine:iron_ore":
            if self.iron <= 0:
                return "NOT_SEEN"
            self.iron -= 1
            self.got(a, "iron_ore", 3, "gathered.iron_ore")
        elif name.startswith("craft:"):
            item = name[6:]
            need, out, small = RECIPES[item]
            if not all(a.n(k) >= v for k, v in need.items()):
                return "MISSING"
            if not small and not (self.table and abs(a.dist - self.table_d) <= 24):
                if a.n("crafting_table") < 1:
                    return "NO_TABLE"
                a.add("crafting_table", -1)     # its own table from the bag, put down beside it
                self.table, self.table_d = True, a.dist
            for k, v in need.items():
                a.add(k, -v)
            self.got(a, item, out)
        elif name.startswith("smelt:"):
            ingot = name.endswith("ingot")
            src, dst, cap = ("iron_ore", "iron_ingot", 3) if ingot else ("food_raw", "food_cooked", 4)
            if not (self.furnace and abs(a.dist - self.furnace_d) <= 24):
                if a.n("furnace") < 1:
                    return "NO_FURNACE"
                a.add("furnace", -1)
                self.furnace, self.furnace_d = True, a.dist
            n = min(cap, a.n(src))
            for fuel in ("coal", "planks", "log"):
                if a.n(fuel) >= 1:
                    a.add(fuel, -1)
                    break
            else:
                return "NO_FUEL"
            a.add(src, -n)
            self.got(a, dst, n)
        elif name.startswith("place:"):
            item = name[6:]
            if a.n(item) < 1:
                return "NO_ITEMS"
            a.add(item, -1)
            if item == "crafting_table":
                self.table, self.table_d = True, a.dist
            elif item == "furnace":
                self.furnace, self.furnace_d = True, a.dist
            else:
                self.chest = True
        elif name in ("build_wall:ring", "repair_wall:nearest"):
            if name.startswith("repair") and self.wall >= RING:
                return "NO_HOLES"
            self.ring = True
            cells = min(24 if name.startswith("build") else RING, RING - self.wall)
            mat = max(("cobblestone", "planks", "dirt"), key=a.n)
            k = min(a.n(mat), cells)
            if k <= 0:
                return "NO_ITEMS"
            a.add(mat, -k)
            self.wall += k
            self.give(a, "built.wall", k)
            a.dist = r.uniform(5, 9)
        elif name == "dig_moat:ring":
            n = min(16, MOAT - self.moat)
            self.moat += n
            self.got(a, "dirt", n)
            self.give(a, "built.moat", n)
            a.dist = r.uniform(8, 11)
        elif name == "light_area:base":
            n = min(a.n("torch"), max(0, 12 - self.torches))
            if n <= 0:
                return "NO_SPOT"
            a.add("torch", -n)
            self.torches += n
            self.give(a, "built.torch", n)
        elif name == "equip:best":
            t = a.tier("sword")
            if t <= a.held:
                return "NO_WEAPON"
            a.held = t
        elif name == "take_post:auto":
            if not a.post:
                if self.posts_taken >= POSTS:
                    return "ALL_TAKEN"
                self.posts_taken += 1
            a.post, a.dist = True, 7.0
        elif name == "hold_line:post":
            pass   # the fight is in world()
        elif name in ("retreat:home", "go_to:base"):
            a.dist = r.uniform(0, 2)
        elif name == "eat:best":
            ate = 0
            for food, heal in (("food_cooked", 6), ("food_raw", 3)):
                while a.n(food) > 0 and a.health < 20:
                    a.add(food, -1)
                    a.health = min(20.0, a.health + heal)
                    ate += 1
            if ate == 0:
                return "NO_FOOD"
            self.give(a, "ate", ate)
        elif name == "hunt:near":
            if self.animals <= 0:
                return "NO_ANIMAL"
            self.animals -= 1
            self.got(a, "food_raw", r.randint(1, 3), "gathered.food")
            a.dist = max(a.dist, r.uniform(6, 20))
        elif name == "put:chest_all":
            for k in list(a.bag):
                if k.endswith(TOOLS):
                    continue
                if k.startswith("food"):
                    self.chest_food += a.bag[k]
                a.add(k, -a.bag[k])
            a.dist = 1.0
        elif name == "take:chest_food":
            n = min(8, self.chest_food)
            if n <= 0:
                return "NOT_IN_CHEST"
            self.chest_food -= n
            self.got(a, "food_cooked", n)
            a.dist = 1.0
        return ""

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

    def hurt(self, a: Agent, rate: float, dt: float, dmg: float = 3.0):
        n = self.poisson(rate * dt)
        if n:
            hp = n * dmg * (1 - 0.04 * a.armor)
            a.health -= hp
            self.give(a, "damage", hp)

    def advance(self, until: float):
        while self.t < until - 1e-9 and not self.done:
            dt = min(2.0, until - self.t)
            self.t += dt
            for a in self.agents:
                if a.alive:
                    self.give(a, "time", dt)
            self.world(dt)
            if self.phase in ("prep", "break", "wave") and self.t >= self.phase_until:
                self.next_phase()
            if self.t >= self.sc["seconds"] and not self.done:
                self.done, self.reason = True, "time"

    def world(self, dt: float):
        wave = self.phase == "wave"
        wallf = self.wall / RING if self.ring else 0.0
        leak = (1 - wallf) * (1 - 0.4 * self.moat / MOAT)
        for a in self.agents:
            if not a.alive:
                if self.t >= a.free_at:   # back at the base, the bag empty
                    a.alive, a.health, a.dist, a.post = True, 20.0, 0.5, False
                continue
            a.health = min(20.0, a.health + 0.25 * dt)   # the agents count as fed: 1 a 4 s
            if not wave:
                continue
            e = max(0.0, self.enemies)
            if e <= 0:
                continue
            inside = a.dist < 8 and self.ring
            if a.dist > 10:   # out in the field during a wave
                self.hurt(a, 0.08 * math.sqrt(e), dt)
            elif a.holding and a.post:
                self.hurt(a, 0.025 * math.sqrt(e) * (0.4 + leak), dt)
                k = min(self.poisson((0.05 + 0.04 * a.held) * dt * min(1.0, e / 2)), int(math.ceil(self.enemies)))
                if k:
                    self.enemies -= k
                    self.give(a, "kill", k)
            else:
                self.hurt(a, 0.025 * e * (leak if inside else 1.0), dt)
                if leak > 0.3:
                    k = min(self.poisson(0.012 * (1 + a.held) * dt), int(math.ceil(self.enemies)))
                    if k:
                        self.enemies -= k
                        self.give(a, "kill", k)
            if a.health <= 0:
                self.die(a, out=not inside)
        if wave:
            if self.ring and self.wave_num >= 6 and self.wall > 0:   # creepers from the 6th wave
                self.wall = max(0, self.wall - self.poisson(0.01 * self.enemies * dt * (1 - 0.5 * self.moat / MOAT)))
            if self.enemies <= 0.5:
                self.enemies = 0
                self.cleared += 1
                self.give_all("wave_cleared")
                self.phase, self.phase_until = "break", self.t + 20
            elif self.wave_losses >= len(self.agents):
                self.done, self.reason = True, "lost"

    def die(self, a: Agent, out: bool):
        self.give(a, "death")
        if out and self.phase == "wave":
            self.give(a, "out_died")
        if self.phase == "wave":
            self.wave_losses += 1
        a.alive = False
        a.bag = {}
        a.held = 0
        a.health = 0
        if a.post:
            self.posts_taken -= 1
        a.post = a.holding = False
        if a.pending:
            act, _, _, began = a.pending
            a.pending = None
            self.ended(a, act, "DIED", self.t - began)
        a.free_at = self.t + 10.0

    def next_phase(self):
        if self.phase in ("prep", "break"):
            self.wave_num += 1
            mult = 0.7 if self.waves == "easy" else 1.0
            n = self.wave_num
            self.enemies = float(max(1, math.floor(mult * (2 + 1.6 * n + 0.12 * n * n))))
            self.enemies *= 1 - min(0.3, self.torches / 40)
            self.wave_losses = 0
            self.phase, self.phase_until = "wave", self.t + 240
            if self.ring:
                self.give_all("holes_at_wave", RING - self.wall)
        elif self.phase == "wave":   # its time is up: the mobs go
            self.enemies = 0
            self.phase, self.phase_until = "break", self.t + 20

    # ---- the protocol -----------------------------------------------------------------------------------
    def decide(self, ready: list[Agent], first: bool = False, final: bool = False):
        out = {"agents": [], "obs": [], "mask": [], "raw": [], "reward": [], "components": [], "done": bool(self.done)}
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
                       "goal_progress": self.progress(), "episode_components": dict(self.comp) if self.done else {}}
        return out

    def step(self, actions: dict[int, int]):
        assert not self.done
        for i, act in actions.items():
            a = self.agents[i]
            if act == IDX["noop"]:
                self.give(a, "noop")
                a.last, a.outcome = act, "none"
                self.done, self.reason = True, "noop"
                return self.close()
            self.begin(a, act)
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
        if self.reason == "goal":
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
    for sc in spec.SCEN:
        res = collections.Counter()
        for ep in range(200):
            o = env.reset(sc, ep, crew=1 + ep % 3)
            while not o["done"]:
                acts = {}
                for i, m in zip(o["agents"], o["mask"]):
                    ok = [k for k, v in enumerate(m) if v and k != 0]
                    acts[i] = env.rng.choice(ok)
                o = env.step(acts)
                steps += 1
            res["success"] += o["info"]["success"]
            res[o["info"]["done_reason"]] += 1
            res["cleared"] += o["info"]["cleared"]
        print(sc, dict(res))
    print(steps, "steps", round(time.time() - t0, 1), "s")
