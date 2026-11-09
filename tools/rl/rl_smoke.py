"""A live check of the learning environment of contract 3 (docs/rl/CONTRACT-v2.md) on a running server: each scenario
played by a scripted preference (the first allowed of the scenario's list, else a random allowed action, never noop),
the episode's end, goal progress, reward by component, what the scales measured, the achievements, the events (their
kinds, the causes' chains, the discoveries); then each skill's results from the server's log.

    python tools/rl/rl_smoke.py --url 127.0.0.1:26110 --scenarios mine_trip,farm --steps 300 [--crew 1] [--seed 3]
    python tools/rl/rl_smoke.py --url ... --log build/rl/servers/env-26110/stdout.txt   # the skills' results too
    python tools/rl/rl_smoke.py --url ... --contract 3.1 --prefer "dig@below,pillar:up"  # a 3.1 episode, a script of its own
"""
import argparse
import collections
import json
import random
import re
import socket
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ACTIONS = [a["id"] for a in json.loads((ROOT / "rl" / "actions.json").read_text(encoding="utf-8"))["actions"]]
IDX = {a: i for i, a in enumerate(ACTIONS)}

# each scenario's script: the first allowed of these (a raw fact may gate it: "id?fact>value")
SCRIPT = {
    "wood": ["chop_tree:near", "go_to:frontier", "look_around"],
    "pickaxe": ["craft:stone_pickaxe", "mine:stone", "craft:wooden_pickaxe", "craft:stick", "craft:crafting_table", "craft:planks", "chop_tree:near", "go_to:frontier"],
    "sword": ["craft:stone_sword", "mine:stone", "craft:wooden_pickaxe", "craft:stick", "craft:crafting_table", "craft:planks", "chop_tree:near", "go_to:frontier"],
    "armor": ["equip:best", "craft_armor:iron", "craft_armor:leather", "place:crafting_table"],
    "bow": ["craft:bow", "craft:arrow", "place:crafting_table"],
    "iron": ["craft:iron_pickaxe", "smelt:iron_ingot", "craft:furnace", "mine:iron_ore", "mine:coal_ore", "tunnel:branch", "descend:mine", "mine:stone"],
    "mine_trip": ["ascend:surface?trip_ores>0", "mine:iron_ore", "mine:coal_ore", "tunnel:branch", "descend:mine", "eat:best"],
    "diamond": ["mine:diamond_ore", "descend:mine", "tunnel:branch", "ascend:surface?trip_ores>8", "eat:best", "mine:iron_ore"],
    "farm": ["craft:bread", "harvest:farm", "plant:wheat", "till:farm", "eat:best", "gather:seeds", "wait:5"],
    "pasture": ["breed:pen", "lure:pen", "build_pen:pasture", "eat:best", "go_to:frontier", "look_around"],
    "enchant": ["enchant:best", "place:bookshelf", "place:enchanting_table", "equip:best"],
    "fortify": ["build_gate:door", "build_wall:ring", "dig_moat:ring", "repair_wall:nearest"],
    "food": ["eat:best", "smelt:food", "hunt:near", "go_to:frontier"],
    "waves:easy": ["place:lava@mob", "use:bucket@mob", "hold_line:post", "take_post:auto", "repair_wall:nearest", "build_wall:ring", "equip:best", "eat:best", "wait:5"],
    "waves:normal": ["hold_line:post", "take_post:auto", "repair_wall:nearest", "build_wall:ring", "equip:best", "eat:best", "wait:5"],
    "waves:long": ["shoot:post", "hold_line:post", "build_gate:door", "take_post:auto", "repair_wall:nearest", "build_wall:ring", "harvest:farm", "plant:wheat", "till:farm", "eat:best", "wait:5"],
    "achievements": ["craft:stone_pickaxe", "craft:wooden_sword", "craft:wooden_hoe", "craft:wooden_pickaxe", "craft:furnace", "mine:stone", "craft:stick",
                     "craft:crafting_table", "craft:planks", "chop_tree:near", "go_to:frontier"],
}


class Env:
    def __init__(self, url):
        host, port = url.split(":")
        self.s = socket.create_connection((host, int(port)), timeout=600)
        self.f = self.s.makefile("r", encoding="utf-8")

    def ask(self, msg):
        self.s.sendall((json.dumps(msg) + "\n").encode("utf-8"))
        line = self.f.readline()
        o = json.loads(line)
        if "error" in o:
            raise RuntimeError(o["error"])
        return o

    def close(self):
        try:
            self.s.sendall(b'{"op":"close"}\n')
        except OSError:
            pass


def gate(rule, raw):
    if "?" not in rule:
        return rule, True
    aid, cond = rule.split("?")
    m = re.match(r"(\w+)([<>]=?)([\d.]+)", cond)
    v = float(raw.get(m.group(1), 0))
    t = float(m.group(3))
    ok = {">": v > t, ">=": v >= t, "<": v < t, "<=": v <= t}[m.group(2)]
    return aid, ok


def choose(scen, mask, raw, rng, eps):
    if rng.random() >= eps:
        for rule in SCRIPT.get(scen, []):
            aid, ok = gate(rule, raw)
            if ok and aid in IDX and mask[IDX[aid]]:
                return IDX[aid]
    allowed = [i for i, m in enumerate(mask) if m and i != 0]
    return rng.choice(allowed) if allowed else 0


def episode(env, scen, steps, crew, seed, eps, extra, contract=""):
    rng = random.Random(seed)
    msg = {"op": "reset", "scenario": scen, "seed": seed, "crew": crew, "raw": True}
    if extra:
        msg["extra_kit"] = extra
    if contract:
        msg["contract"] = contract
    t0 = time.time()
    o = env.ask(msg)
    chosen = collections.Counter()
    events = collections.Counter()
    novel, chains, ach = [], [], []
    rew = collections.Counter()
    n = 0
    while n < steps:
        acts = {}
        for a, m in o["mask"].items():
            k = choose(scen, m, o["info"].get("raw", {}).get(a, {}), rng, eps)
            acts[a] = k
            chosen[ACTIONS[k]] += 1
        o = env.ask({"op": "step", "actions": acts})
        n += 1
        for a, r in o["reward"].items():
            rew[a] += r
        for e in o["info"].get("events", []):
            events[e["type"]] += 1
            if e["type"] == "novel_effect":
                novel.append("%s -> %s%s" % (e["cause"], e["effect"], " (first ever)" if e["first_ever"] else ""))
            if e["type"] == "achievement":
                ach.append("%s:%s" % (e["agent"], e["id"]))
            if e["type"] == "reward" and len(e["chain"]) > 1 and len(chains) < 6:
                chains.append("%s %s %.3f <- %s" % (e["agent"], e["key"], e["points"], " <- ".join("%d %s via %s" % (c["decision_id"], c["kind"], c["via"]) for c in e["chain"])))
        if o["done"]:
            break
    info = o["info"]
    return {
        "scenario": scen, "steps": n, "done": o["done"], "reason": info.get("done_reason"), "game_s": info.get("ticks", 0) / 20, "wall_s": round(time.time() - t0, 1),
        "goal": info.get("goal_progress"), "reward": {a: round(v, 3) for a, v in rew.items()}, "components": info.get("episode_reward"),
        "measured": info.get("measured"), "achievements": info.get("achievements") or ach, "events": dict(events), "novel": novel, "chains": chains,
        "top_actions": chosen.most_common(8),
    }


def skills(log):
    """each tool's results from the server's log: mind: Agent N tool -> RESULT"""
    res = collections.defaultdict(collections.Counter)
    for line in Path(log).read_text(encoding="utf-8", errors="replace").splitlines():
        m = re.search(r"mind: \S+ \d+ (\w+) -> (\w+)", line)
        if m:
            res[m.group(1)][m.group(2)] += 1
    return {k: dict(v) for k, v in sorted(res.items())}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="127.0.0.1:26110")
    ap.add_argument("--scenarios", default="wood")
    ap.add_argument("--steps", type=int, default=200)
    ap.add_argument("--crew", type=int, default=1)
    ap.add_argument("--seed", type=int, default=3)
    ap.add_argument("--eps", type=float, default=0.1)
    ap.add_argument("--extra", default="")
    ap.add_argument("--log", default="")
    ap.add_argument("--contract", default="")
    ap.add_argument("--prefer", default="")
    ap.add_argument("--out", default="")
    a = ap.parse_args()
    env = Env(a.url)
    info = env.ask({"op": "info"})
    print("server: contract %s, %d actions (%s), %d features (%s); serves %s" % (info["contract"], info["actions"]["n"], info["actions"]["hash"], info["obs"]["dim"],
                                                                          info["obs"]["hash"], sorted(info.get("contracts", {}))))
    if a.contract == "3.1":
        global ACTIONS, IDX
        ACTIONS = [x["id"] for x in json.loads((ROOT / "rl" / "actions.json").read_text(encoding="utf-8"))["actions"]]
        IDX = {x: i for i, x in enumerate(ACTIONS)}
    if a.prefer:
        for s in a.scenarios.split(","):
            SCRIPT[s] = a.prefer.split(",")
    out = []
    for s in a.scenarios.split(","):
        r = episode(env, s, a.steps, a.crew, a.seed, a.eps, a.extra, a.contract)
        out.append(r)
        print(json.dumps(r, ensure_ascii=False))
        sys.stdout.flush()
    env.close()
    if a.log:
        print(json.dumps(skills(a.log), ensure_ascii=False))
    if a.out:
        Path(a.out).write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
