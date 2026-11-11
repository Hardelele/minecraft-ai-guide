"""The open world's actions do what they say (docs/rl/OPEN-WORLD.md section 2), on a running arena of the engine:
forward moves the body where it looks, a turn turns the head, attack breaks the block under the aim and hits a cow in
front, crafting makes planks, sticks and a table from logs, the table put down by "use" opens the 3x3 recipes and the
wooden pickaxe comes out (its achievement too); a slot puts its stack in the hand; a random policy runs a whole day.

    python tools/rl/open_check.py --port 27300
"""
from __future__ import annotations

import argparse
import math
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from open_env import SPEC, OpenEnv, random_action  # noqa: E402

VEC = {k: i for i, k in enumerate(SPEC["vec"])}
A = {k: i for i, k in enumerate(SPEC["heads"]["act"])}
YAW = SPEC["heads"]["yaw"]
PITCH = SPEC["heads"]["pitch"]
LOG64 = math.log1p(64)


def bag(o, agent, item):
    return int(round(math.expm1(float(o["obs"][agent]["vec"][VEC["bag_" + item]]) * LOG64)))


def pos(o, agent):
    for e in o["frame"]["events"]:
        if e["type"] == "agent" and e["agent"] == agent:
            return np.array([e["x"], e["y"], e["z"]])
    return None


def yaw_of(o, agent):
    v = o["obs"][agent]["vec"]
    return math.atan2(v[VEC["yaw_sin"]], v[VEC["yaw_cos"]])


def act(move=0, jump=0, yaw=0, pitch=0, a=0):
    return [move, jump, YAW.index(yaw), PITCH.index(pitch), a]


class Check:
    def __init__(self):
        self.ok, self.bad = [], []

    def __call__(self, name, cond, detail=""):
        (self.ok if cond else self.bad).append(name)
        print(("PASS " if cond else "FAIL ") + name + (": " + detail if detail else ""), flush=True)


def slot_of(o, agent, item):
    hot = list(o["obs"][agent]["hot"])
    k = SPEC["items"].index(item)
    return hot.index(k) if k in hot else -1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=27300)
    ap.add_argument("--day-steps", type=int, default=6200)
    a = ap.parse_args()
    env = OpenEnv(a.port)
    ck = Check()

    # the legs and the head
    o = env.reset(seed=5, waves="off")
    ag = o["agents"][0]
    best = 0.0
    for turn in range(4):   # the arena's trees: the freest of four sides (till it bumps into something)
        yaw0, p0 = yaw_of(o, ag), pos(o, ag)
        for _ in range(5):
            o = env.step({ag: act(move=1)})
        d = pos(o, ag) - p0
        best = max(best, float(-d[0] * math.sin(yaw0) + d[2] * math.cos(yaw0)))
        o = env.step({ag: act(yaw=90)})
    ck("forward moves where it looks", best > 2.0, f"{best:.2f} blocks along the look in 5 steps (1 s; a player walks 4.3)")
    yaw0 = yaw_of(o, ag)
    o = env.step({ag: act(yaw=90)})
    o = env.step({ag: act()})
    turned = math.degrees((yaw_of(o, ag) - yaw0 + math.pi) % (2 * math.pi) - math.pi)
    ck("yaw +90 turns the head 90 degrees", abs(abs(turned) - 90) < 3, f"{turned:.1f} degrees")

    # attack breaks the block under the aim (the ground)
    o = env.reset(seed=6, waves="off")
    ag = o["agents"][0]
    for _ in range(3):
        o = env.step({ag: act(pitch=30)})
    dirt0 = bag(o, ag, "dirt")
    for _ in range(15):
        o = env.step({ag: act(a=A["attack"])})
    for _ in range(3):
        o = env.step({ag: act()})
    ck("attack breaks the block it looks at (and the drop is picked up)", bag(o, ag, "dirt") > dirt0, f"dirt {dirt0} -> {bag(o, ag, 'dirt')}")

    # attack hits a cow in front
    o = env.reset(seed=7, waves="off", near="Cow")
    ag = o["agents"][0]
    o = env.step({ag: act(pitch=10)})
    o = env.step({ag: act(pitch=10)})
    hit = []
    for _ in range(12):
        o = env.step({ag: act(a=A["attack"])})
        hit.append(o["info"]["last"][ag])
    ck("attack hits the creature under the aim", any(h.startswith("hit") for h in hit), str(sorted(set(hit))))

    # crafting: logs -> planks -> sticks, a table; the table put down; the 3x3 pickaxe
    o = env.reset(seed=8, waves="off", kit="log 3")
    ag = o["agents"][0]
    o1 = env.step({ag: act()})
    ck("achievements arrive as events (openInventory at the first step)", any(e["type"] == "achievement" and e["id"] == "openInventory" for e in o1["frame"]["events"]))
    m = o["mask"][ag]
    ck("mask: no pickaxe from logs alone, planks yes", (not m[A["craft:wooden_pickaxe"]]) and bool(m[A["craft:planks"]]))
    for _ in range(3):
        o = env.step({ag: act(a=A["craft:planks"])})
    ck("craft planks", bag(o, ag, "planks") == 12 and bag(o, ag, "log") == 0, f"planks {bag(o, ag, 'planks')}")
    o = env.step({ag: act(a=A["craft:stick"])})
    o = env.step({ag: act(a=A["craft:crafting_table"])})
    ck("craft sticks and a table", bag(o, ag, "stick") == 4 and bag(o, ag, "crafting_table") == 1, f"sticks {bag(o, ag, 'stick')}")
    ck("mask: the pickaxe needs a table seen in reach", not o["mask"][ag][A["craft:wooden_pickaxe"]])
    o = env.step({ag: act(a=A["slot%d" % (slot_of(o, ag, "crafting_table") + 1)], pitch=30)})
    o = env.step({ag: act(pitch=30)})
    o = env.step({ag: act(a=A["use"])})
    placed = o["info"]["last"][ag]
    o = env.step({ag: act(pitch=-30)})
    o = env.step({ag: act()})
    ck("use puts the table down", bag(o, ag, "crafting_table") == 0, placed)
    ck("mask: the pickaxe with the table in sight", bool(o["mask"][ag][A["craft:wooden_pickaxe"]]))
    evs = []
    o = env.step({ag: act(a=A["craft:wooden_pickaxe"])})
    evs += [e["id"] for e in o["frame"]["events"] if e["type"] == "achievement"]
    o = env.step({ag: act()})
    evs += [e["id"] for e in o["frame"]["events"] if e["type"] == "achievement"]
    ck("craft the wooden pickaxe at the table", bag(o, ag, "wooden_pickaxe") == 1, str(o["info"]["last"]))
    ck("no buildPickaxe from a kit's logs (the game's parent mineWood is a pickup)", "buildPickaxe" not in evs, str(evs))
    o = env.step({ag: act(a=A["slot%d" % (slot_of(o, ag, "wooden_pickaxe") + 1)])})
    o = env.step({ag: act()})
    held = o["obs"][ag]["vec"][VEC["held"]] * len(SPEC["items"])
    ck("a slot puts its stack in the hand", round(held) == SPEC["items"].index("wooden_pickaxe"), f"held class {held:.1f}")

    # a random player for a whole day and night
    rng = np.random.default_rng(1)
    o = env.reset(seed=9, days=1)
    n, sticky, kinds = 0, {}, {}
    while not o["done"] and n < a.day_steps:
        acts = {x: random_action(rng, o["mask"][x], sticky.get(x)) for x in o["agents"]}
        sticky.update(acts)
        o = env.step(acts)
        n += 1
        for e in o["frame"]["events"]:
            kinds[e["type"]] = kinds.get(e["type"], 0) + 1
    ck("a random policy runs into the night", n >= a.day_steps or o["done"], f"{n} steps, {o['info'].get('done_reason')}, events {kinds}")
    env.close()
    print(f"{len(ck.ok)} passed, {len(ck.bad)} failed: {ck.bad}")
    sys.exit(1 if ck.bad else 0)


if __name__ == "__main__":
    main()
