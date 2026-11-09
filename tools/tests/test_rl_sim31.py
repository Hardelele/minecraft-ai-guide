"""The simulator on contract 3.1 (rl/: docs/rl/CONTRACT-v2.md, 11): the cells round an agent as RlNear gives them
(a pit, a pillar, four blocks round, a roof), the primitives on them, the situations "not reachable this way" and
their ways round (a hanging tree: pillar up and dig at it; a ditch on the way home: a block a step down in front; lava
before the ore: a way dug round it), a shelter at night, the memory of failures (retry_fail, switch_ok) as
RlScore.Fails; and the start of 3.1's net from a v3 one (tools/rl/expand_policy.py): the same logits on v3's inputs."""
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
os.environ.pop("AIGUIDE_RL_SPEC", None)   # rl/: contract 3.1
sys.path.insert(0, str(ROOT / "tools" / "rl"))
import sim_near as NEAR  # noqa: E402
import spec  # noqa: E402
from sim_env import SimEnv, V31  # noqa: E402

bad = 0


def check(ok, what):
    global bad
    print(("ok   " if ok else "FAIL ") + what)
    if not ok:
        bad += 1


def facts(nr, night=False):
    r = {}
    nr.facts(r, night)
    return r


def near():
    n = NEAR.Near("surface")
    r = facts(n)
    check(r["nb_below_solid"] == 1 and r["nb_front_air"] == 1 and r["nb_below_dig"] == 1 and r["nb_front_dig"] == 0, "on the ground: dirt under, air in front")
    check(r["pillar_ok"] == 1 and r["nb_left_free"] == 1 and r["nb_above_free"] == 0, "it may pillar up, put a block at its side; nothing to put one over its head against")
    n.pillar()
    r = facts(n)
    check(n.y == 1 and r["pillar_ok"] == 1 and r["nb_left_free"] == 0, "on its pillar: up again yes, a block at the side has nothing to stand on")
    n = NEAR.Near("surface")
    n.dig("below")
    n.dig("below")
    r = facts(n)
    check(n.y == -2 and n.pit() == 2 and r["walls_around"] == 4 and r["enclosed"] == 0 and r["nb_above_free"] == 0,
          "two down: walls round, open over the head, nothing there to put a block against (it is the ground's level)")
    n.dig("below")
    r = facts(n)
    check(n.y == -3 and r["nb_above_free"] == 1 and r["enclosed"] == 0, "three down: a block over the head has the pit's walls to stand on")
    n.put("above")
    check(facts(n)["enclosed"] == 1 and facts(n, True)["shelter"] == 1 and facts(n)["shelter"] == 0, "a block over the head: shut in; at night a shelter")
    n = NEAR.Near("surface")
    for d in ("left", "right", "back"):
        n.put(d)
    n.set((0, 0, 1), NEAR.SOLID, "dirt")
    n.dig("below")
    n.dig("below")
    check(facts(n)["nb_above_free"] == 1, "blocks round, two down: the blocks hold a roof")
    n.put("above")
    check(n.enclosed(), "and it is shut in")
    n = NEAR.Near("surface")
    for d in ("left", "right", "back"):
        n.put(d)
    n.set((0, 0, 1), NEAR.SOLID, "dirt")
    r = facts(n)
    check(r["walls_around"] == 4 and r["enclosed"] == 0, "four blocks round: walled at the feet, not shut in")
    n.dig("below")
    check(n.y == -1 and n.walls() == (4, 4) and not n.enclosed(), "one down: the blocks are at the head now")
    n.dig("above") if n.can_dig("above") else None
    n = NEAR.Near("surface")
    n.set((0, -2, 0), NEAR.LAVA)
    check(facts(n)["nb_below_dig"] == 0, "not down over lava")
    n = NEAR.Near("surface")
    for y in (-2, -3, -4):
        n.set((0, y, 0), NEAR.AIR)
    check(facts(n)["nb_below_dig"] == 0, "not down over a drop of three")
    t = NEAR.Near("tunnel", 0)
    check(facts(t)["nb_left_dig"] == 0, "a tunnel's stone with no pickaxe: not dug")
    t = NEAR.Near("tunnel", 1)
    check(facts(t)["nb_left_dig"] == 1 and t.dig("left") == ["cobblestone", "cobblestone"], "with one: a side's column, two cobblestone")


def act(env, i, name):
    o = env.step({i: spec.IDX[name]})
    return o


def last(env, a):
    return a.outcome


def hang():
    env = SimEnv(5, force="hang")
    o = env.reset("wood", 5, crew=1)
    a = env.agents[0]
    a.dist = 1.0
    a.near = None
    hs = sorted(env.hang["cells"])
    r = env.raw(a)
    check(r["trees_free"] == 0 and r["trees_reachable"] == 0 and r["trees_seen"] >= 1, "a hanging tree: seen, not reachable (%s)" % hs)
    check(not spec.allowed(spec.IDX["chop_tree:near"], r), "chop_tree is masked")
    a.add("cobblestone", 8)
    logs = 0
    for _ in range(12):
        nr = env.near(a)
        if nr.can_dig("front") and any(nr.at(c) == NEAR.SOLID for c in nr.dig_cells("front")):
            was = a.n("log")
            env.ended(a, spec.IDX["dig@front"], "OK" if env.effect31(a, "dig@front") == "" else "X", 1)
            logs += a.n("log") - was
        elif nr.pillar():
            a.add("cobblestone", -1)
        else:
            break
    check(logs >= 2 and env.ob["hang_logs"] == logs, "pillar up and dig at it: %d logs of the hanging tree" % logs)


def ditch():
    env = SimEnv(6, force="ditch")
    env.reset("farm", 6, crew=1)
    a = env.agents[0]
    a.add("cobblestone", 4)
    env.moved31(a, "go_to:frontier", True)
    check(a.ditch, "back from the frontier: a ditch on the way home")
    check(env.blocked31(a, "go_to:base") == (False, "NO_PATH"), "going home: NO_PATH")
    r = env.raw(a)
    check(spec.allowed(spec.IDX["place:block@front_down"], r), "a block a step down in front is allowed (the ditch is air)")
    check(env.effect31(a, "place:block@front_down") == "" and not a.ditch and env.ob["ditch_bridged"] == 1, "the block over the ditch: a way home")
    check(env.blocked31(a, "go_to:base") == (True, ""), "going home now")


def lava():
    env = SimEnv(7, force="lava")
    env.reset("iron", 7, crew=1)
    a = env.agents[0]
    a.depth = 1
    env.moved31(a, "descend:mine", True)
    check(a.lava_front, "down at the horizon: lava before the ore")
    check(env.blocked31(a, "mine:iron_ore") == (False, "NO_PATH"), "the ore behind the lava: NO_PATH")
    r = env.raw(a)
    check(r["tgt_front_down_lava_src"] == 1 and r["nb_left_dig"] == 1, "lava in front a step down; the side is stone it digs")
    check(env.effect31(a, "dig@left") == "" and not a.lava_front and env.ob["lava_passed"] == 1, "a way dug round it")
    check(env.blocked31(a, "mine:iron_ore") == (True, ""), "the ore now")


def shelter():
    env = SimEnv(8)
    env.reset("night:shelter", 8, crew=1)
    a = env.agents[0]
    for name in ("dig@below", "dig@below", "dig@below", "place:block@above"):
        check(env.effect31(a, name) == "", name)
    check(env.sheltered(a) and env.raw(a)["shelter"] == 1, "shut in at night: a shelter")
    hp = a.health
    env.world(30.0)
    check(a.health >= hp and env.ob["shelter_s"] >= 30, "the night's mobs do not reach it (%.1f -> %.1f)" % (hp, a.health))
    check(env.blocked31(a, "go_to:frontier") == (False, "NO_PATH"), "a walk out of the pit two deep: NO_PATH")
    env2 = SimEnv(8)
    env2.reset("night:shelter", 8, crew=1)
    b = env2.agents[0]
    for _ in range(8):
        env2.world(30.0)
    check(not b.alive or b.health < 10, "out in the open all night: dead or hurt (%.1f)" % b.health)


def fails():
    f = NEAR.Fails()
    check(f.step(5, False, "NO_PATH") == (0, 0), "the first failure: no retry_fail")
    check(f.step(5, False, "NO_PATH") == (1, 0) and f.step(5, False, "NO_PATH") == (2, 0), "the 2nd, the 3rd: 1, 2 (times -0.03)")
    r = {}
    f.facts(r, spec.ACTIONS)
    check(r["fail_run_" + spec.ACTIONS[5]] == 3 and abs(r["fail_recent_" + spec.ACTIONS[5]] - 3 / 20) < 1e-9 and r["lastfail_no_path"] == 1,
          "fail_run 3, fail_recent 3/20, lastfail no_path")
    check(f.step(7, True, "OK") == (0, 1), "another action that works: switch_ok")
    check(f.step(7, True, "OK") == (0, 0), "not twice")
    env = SimEnv(9)
    env.reset("wood", 9, crew=1)
    a = env.agents[0]
    for _ in range(3):
        env.ended(a, 2, "NO_PATH", 1)
    env.ended(a, 3, "OK", 1)
    c = a.comp
    check(abs(c.get("retry_fail", 0) - (-0.03 * 3)) < 1e-9 and abs(c.get("switch_ok", 0) - 0.05) < 1e-9,
          "in the reward: retry_fail -0.03 x (1 + 2), switch_ok +0.05 (%s)" % {k: round(v, 3) for k, v in c.items()})


def expand():
    import numpy as np
    import torch
    import expand_policy as X
    from policy import Policy
    v3 = X.spec_of(ROOT / "rl" / "v3")
    torch.manual_seed(3)
    old = Policy(len(v3["features"]), len(v3["actions"]), 32)
    old.norm.update(torch.randn(64, old.d) * 2 + 1)
    new = X.expand(old, v3, X.spec_of(ROOT / "rl"))
    check(new.d == spec.D and new.a == spec.A, "3.1's net: %d features, %d actions" % (new.d, new.a))
    x3 = torch.randn(8, old.d) * 3
    x31 = torch.zeros(8, new.d)
    for i, n in enumerate(v3["features"]):
        x31[:, spec.NAMES.index(n)] = x3[:, i]
    for n in spec.NAMES:   # the new inputs at their own mean: no change
        if n not in v3["features"]:
            x31[:, spec.NAMES.index(n)] = new.norm.mean[spec.NAMES.index(n)].float()
    with torch.no_grad():
        l3, l31 = old.logits(x3), new.logits(x31)
        v3v, v31v = old.value(x3), new.value(x31)
    check(torch.allclose(l3, l31[:, :old.a], atol=1e-5) and torch.allclose(v3v, v31v, atol=1e-5), "v3's inputs: the same logits of its 124 actions and the same value")
    check(float(l31[:, old.a:].abs().max()) < 1e-6 or bool(np.isfinite(l31.numpy()).all()), "the new actions' logits start at their bias")


if __name__ == "__main__":
    if not V31:
        print("rl/ is not contract 3.1: skipped")
        sys.exit(0)
    near()
    hang()
    ditch()
    lava()
    shelter()
    fails()
    expand()
    print("test_rl_sim31", "PASS" if not bad else "FAIL (%d)" % bad)
    sys.exit(1 if bad else 0)
