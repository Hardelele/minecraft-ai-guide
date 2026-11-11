"""The game's events of the reward v4 that look at the world (RlEvents4: path_changed, attack_failed, found, seen)
against the Python simulator's (sim_env, whose kernel the batch simulator runs line for line) on the same situations:

  block  a zombie comes for an agent and the agent's decision puts blocks across its way
         game: path_changed (blocked/detour/stuck) on its route, by that decision, seen by the agent;
         simulator: the wall the agent builds turns a wave's mob: path_changed by the builder's decisions;
  arrow  a skeleton's arrow at the agent stops in a block the agent's decision put
         game: attack_failed cover by that decision; simulator: the night's box turns the mobs: attack_failed cover by
         the box's decisions;
  dodge  the agent steps aside from an arrow aimed at it: game attack_failed moved (the simulator has no dodges: its
         fight is by rates; noted, not compared).

Each event's fields as rl/events.json says; the credit RlReward4's core gives for each stream (the mob gone after,
as when it leaves or the wave ends): credit.block, credit.cover, credit.dodge above 0 in the game, credit.block and
credit.cover in the simulator. The game's side runs on the engine (one arena, -Daiguide.rl.reward4=true); it waits
while League of Legends runs.

    python tools/tests/test_rl_events4.py [--sim-only]
"""
import json
import os
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "rl"))
import reward_core  # noqa: E402
import spec  # noqa: E402

EV = json.loads((ROOT / "rl" / "events.json").read_text(encoding="utf-8"))
bad = 0


def check(ok, what):
    global bad
    print(("ok   " if ok else "FAIL ") + what, flush=True)
    if not ok:
        bad += 1


def schema(e):
    """the event's fields are rl/events.json's: the required present, nothing unknown"""
    f = EV["events"].get(e["type"])
    if f is None:
        return "unknown type " + e["type"]
    need = {k for k in f if not k.endswith("?")}
    allowed = {k.rstrip("?") for k in f} | {"type"}
    miss, extra = need - set(e), set(e) - allowed
    return None if not miss and not extra else "%s: missing %s, unknown %s" % (e["type"], sorted(miss), sorted(extra))


def credit(events, scenario=None):
    """the core over a stream: one frame of the events, then the mobs gone (their pies shared)"""
    mobs = sorted({int(e["mob"]) for e in events if "mob" in e})
    agents = sorted({str(e["agent"]) for e in events if "agent" in e} | {str(e["target"]) for e in events if "target" in e})
    st = [{"type": "agent", "agent": a, "x": 0.0, "y": 64.0, "z": 0.0, "hp": 20.0, "alive": True, "base_dist": 0.0} for a in agents]
    frames = [{"t": 1.0, "dt": 1.0, "phase": "day", "events": st + events},
              {"t": 2.0, "dt": 1.0, "phase": "day", "events": st + [{"type": "mob_gone", "mob": m, "why": "other"} for m in mobs]}]
    return reward_core.run(frames, scenario)["episode"]


# ---- the game (the engine) --------------------------------------------------------------------------------------

def game():
    import engine_env as E
    while E.league():
        print("League of Legends runs: the engine's part waits", flush=True)
        time.sleep(120)
    port = 26250
    os.environ["AIGUIDE_REWARD"] = "v4"
    E.start_engine([port], "1600M", True, None)
    try:
        if not E.wait_ready([port]):
            check(False, "the engine's arena started")
            return {}
        out = {}
        wait = spec.IDX["wait:5"]
        from env_client import Conn
        conn = Conn("127.0.0.1", port)   # one connection: the episode is the connection's (a new one drops it)
        ask = lambda msg, _t=None: conn.ask(msg)
        for what in ("block", "arrow", "dodge"):
            r = ask({"op": "reset", "scenario": "wood", "seed": 4242, "crew": 1}, 120)
            st = ask({"op": "stage4", "what": what}, 30)
            check(bool(st.get("ok")), "%s: staged (%s)" % (what, st.get("error", "")))
            agent = str(st.get("agent"))
            for _ in range(4):   # 20 game seconds of waiting
                if r.get("done"):
                    break
                r = ask({"op": "step", "actions": {a: wait for a in r.get("obs", {})}}, 120)
            ev = ask({"op": "events4"}, 30)
            evs = [e for e in ev.get("events", []) if e["type"] != "agent"]
            out[what] = (evs, [int(d) for d in ev.get("decisions", [])], agent, st.get("mob"))
        conn.close()
        return out
    finally:
        E.stop([port])


# ---- the simulator --------------------------------------------------------------------------------------------

def sim(scenario, prefer, seed=3):
    """a scripted crew of 1: the first allowed of `prefer` (or prefer(env) -> such a list), else wait:5; every frame's
    events kept"""
    from sim_env import SimEnv
    env = SimEnv(seed, reward="v4")
    o = env.reset(scenario, seed, crew=1)
    frames = []
    real = env.core.frame
    env.core.frame = lambda fr: (frames.append(fr), real(fr))[1]
    wait = spec.IDX["wait:5"]
    k = 0
    while not o["done"] and k < 400:
        pick = [spec.IDX[p] for p in (prefer(env) if callable(prefer) else prefer)]
        acts = {}
        for i, m in zip(o["agents"], o["mask"]):
            acts[i] = next((p for p in pick if m[p]), wait if m[wait] else next(j for j in range(1, len(m)) if m[j]))
        o = env.step(acts)
        k += 1
    evs = [e for fr in frames for e in fr["events"] if e["type"] != "agent"]
    return evs, frames, env


def main():
    sim_only = "--sim-only" in sys.argv
    # the simulator: the wall against a wave, the box against the night
    s_block, f_block, _ = sim("waves:easy", ["build_wall:ring", "repair_wall:nearest"])
    # the night's box: down into the ground three blocks, a block over its head (the simulator's shortest way to be shut in)
    s_cover, f_cover, _ = sim("night:shelter", lambda env: ["place:block@above"] if env.agents[0].near.y <= -3 else ["dig@below"])
    sp = [e for e in s_block if e["type"] == "path_changed"]
    sc = [e for e in s_cover if e["type"] == "attack_failed" and e["reason"] == "cover"]
    check(len(sp) > 0 and all(schema(e) is None for e in sp), "simulator, block: %d path_changed as rl/events.json says" % len(sp))
    check(len(sc) > 0 and all(schema(e) is None for e in sc), "simulator, arrow (the box): %d attack_failed cover as rl/events.json says" % len(sc))
    ep_b = reward_core.run(f_block, "waves:easy")["episode"]
    ep_c = reward_core.run(f_cover, "night:shelter")["episode"]
    check(ep_b.get("credit.block", 0) > 0, "simulator, block: credit.block %.3f" % ep_b.get("credit.block", 0))
    check(ep_c.get("credit.cover", 0) > 0, "simulator, arrow (the box): credit.cover %.3f" % ep_c.get("credit.cover", 0))
    if sim_only:
        print("test_rl_events4 " + ("PASS (the simulator only)" if not bad else "FAIL (%d)" % bad))
        sys.exit(1 if bad else 0)

    g = game()
    for what, (evs, decs, agent, mob) in g.items():
        wrong = [schema(e) for e in evs if schema(e)]
        check(not wrong, "game, %s: %d events, all as rl/events.json says %s" % (what, len(evs), wrong[:3]))
    if "block" in g:
        evs, decs, agent, mob = g["block"]
        pc = [e for e in evs if e["type"] == "path_changed" and e["mob"] == mob]
        hit = [e for e in pc if set(decs) & set(e["by"]) and e["on_route"]]
        check(len(hit) > 0, "game, block: path_changed on its route by the wall's decision: %s" % pc[:3])
        check(any(agent in e["seen"] for e in hit), "game, block: the agent saw it")
        check(all(e["effect"] in ("blocked", "detour", "stuck", "fell") for e in pc), "game, block: effects %s" % sorted({e["effect"] for e in pc}))
        # against the simulator's: the same kind of event, the same fields, a block's decision in `by`, seen
        check(sp and set(sp[0]) == set(hit[0]) if hit else False, "game = simulator, block: the same fields %s" % (sorted(hit[0]) if hit else None))
        cr = credit(evs)
        check(cr.get("credit.block", 0) > 0, "game, block: credit.block %.3f" % cr.get("credit.block", 0))
    if "arrow" in g:
        evs, decs, agent, mob = g["arrow"]
        af = [e for e in evs if e["type"] == "attack_failed" and e["reason"] == "cover" and set(decs) & set(e.get("by", []))]
        check(len(af) > 0, "game, arrow: attack_failed cover by the wall's decision: %s" % [e for e in evs if e["type"] == "attack_failed"][:3])
        check(sc and af and set(af[0]) == set(sc[0]), "game = simulator, arrow: the same fields")
        cr = credit(evs)
        check(cr.get("credit.cover", 0) > 0, "game, arrow: credit.cover %.3f" % cr.get("credit.cover", 0))
    if "dodge" in g:
        evs, decs, agent, mob = g["dodge"]
        mv = [e for e in evs if e["type"] == "attack_failed" and e["reason"] == "moved" and e["target"] == agent]
        check(len(mv) > 0, "game, dodge: attack_failed moved: %s" % [e for e in evs if e["type"] == "attack_failed"][:3])
        cr = credit(evs)
        check(cr.get("credit.dodge", 0) > 0, "game, dodge: credit.dodge %.3f (the simulator has no dodges)" % cr.get("credit.dodge", 0))
    print("test_rl_events4 " + ("PASS" if not bad else "FAIL (%d)" % bad))
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
