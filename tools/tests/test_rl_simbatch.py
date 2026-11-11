"""The batch simulator against the reference (tools/rl/simbatch.py vs tools/rl/sim_env.py): every scenario (the
contract's and the simulator's own), crews of 1-4, the situations forced (hang, ditch, lava) and noise off; an arena
and a SimEnv reset with the same seed and given the same actions (drawn from the reference's mask, noop now and then)
must give the same observations (float32, bit for bit), masks, rewards (1e-9), ends, infos and events, step by step;
the measured amounts (facts) priced by rl/reward.json must give the reward (pluggable reward).

    python tools/tests/test_rl_simbatch.py            # quick: 3 episodes a case
    python tools/tests/test_rl_simbatch.py 40         # more
"""
import os
import random
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
os.environ.pop("AIGUIDE_RL_SPEC", None)
sys.path.insert(0, str(ROOT / "tools" / "rl"))
import spec  # noqa: E402
import simbatch_tables as T  # noqa: E402
from sim_env import SIM_SCEN, SimEnv  # noqa: E402
import simbatch_kernel as KN  # noqa: E402
from simbatch import BatchSim, reward_from_facts  # noqa: E402

bad = 0
NL = "\n     "


def check(ok, what):
    global bad
    print(("ok   " if ok else "FAIL ") + what, flush=True)
    if not ok:
        bad += 1


def norm_id(d, off):
    """an old decision id as the arena's: by a constant offset (one SimEnv) or by the ids' map (several)"""
    return off.get(d, -999) if isinstance(off, dict) else d - off


def norm_chain(ch, off):
    return [(norm_id(c["decision_id"], off), c["agent"], c["tick"], c["kind"], c["via"]) for c in ch]


def norm_events(evs, off):
    out = []
    for ev in evs:
        if ev["type"] == "reward":
            pts = round(ev["points"], 9) if "move" in ev else ev["points"]
            out.append(("reward", int(ev["agent"]), ev["key"], pts, ev["ticks"], ev.get("move"), tuple(norm_chain(ev["chain"], off))))
        elif ev["type"] == "novel_effect":
            out.append(("novel", ev["agent"], ev["cause"], ev["effect"], ev["first_ever"], ev["ticks"], tuple(norm_chain(ev["chain"], off))))
        else:
            out.append(("ach", ev["agent"], ev["id"], ev["ticks"]))
    return out


INFO_KEYS = ("done_reason", "success", "cleared", "breaches", "mob_seconds", "clean_waves", "novel", "repeat_fail", "discoveries", "kills",
             "spawned", "sustain", "achievements", "obstacles")


def compare(o, b, step, off, where):
    """o: SimEnv's output, b: the arena's; '' when the same, else what differs"""
    if list(o["agents"]) != list(b["agents"]):
        return f"{where} step {step}: agents {o['agents']} vs {b['agents']}"
    if o["done"] != b["done"]:
        return f"{where} step {step}: done {o['done']} vs {b['done']}"
    if abs(o["t"] - b["t"]) > 1e-9:
        return f"{where} step {step}: t {o['t']} vs {b['t']}"
    oo = np.asarray(o["obs"], np.float32)
    if oo.shape != b["obs"].shape or not np.array_equal(oo, b["obs"]):
        d = np.argwhere(oo != b["obs"])
        j = d[0] if len(d) else None
        return f"{where} step {step}: obs differ at {[(int(r), spec.NAMES[c], float(oo[r, c]), float(b['obs'][r, c])) for r, c in d[:6]]}" if j is not None \
            else f"{where} step {step}: obs shape {oo.shape} vs {b['obs'].shape}"
    om = np.asarray(o["mask"], bool)
    if not np.array_equal(om, b["mask"]):
        d = np.argwhere(om != b["mask"])
        return f"{where} step {step}: mask differ at {[(int(r), spec.ACTIONS[c]) for r, c in d[:6]]}"
    if not np.allclose(o["reward"], b["reward"], rtol=0, atol=1e-9):
        return f"{where} step {step}: reward {o['reward']} vs {b['reward']}"
    oi, bi = o["info"], b["info"]
    for k in INFO_KEYS:
        if oi.get(k) != bi.get(k) and k != "success" or k == "success" and bool(oi.get(k)) != bool(bi.get(k)):
            return f"{where} step {step}: info {k} {oi.get(k)} vs {bi.get(k)}"
    if abs(oi["goal_progress"] - bi["goal_progress"]) > 1e-12:
        return f"{where} step {step}: goal_progress {oi['goal_progress']} vs {bi['goal_progress']}"
    oa = {int(k): norm_id(v, off) for k, v in oi["assigned"].items()}
    if oa != {int(k): v for k, v in bi["assigned"].items()}:
        return f"{where} step {step}: assigned {oa} vs {bi['assigned']}"
    if norm_events(oi["events"], off) != norm_events(bi["events"], 0):
        return f"{where} step {step}: events\n  {norm_events(oi['events'], off)}\n  {norm_events(bi['events'], 0)}"
    if o["done"]:
        if oi.get("achievement_ids") != bi.get("achievement_ids"):
            return f"{where} end: achievement_ids {oi.get('achievement_ids')} vs {bi.get('achievement_ids')}"
        ec = {k: v for k, v in oi["episode_components"].items() if v != 0}
        if set(ec) != set(bi["episode_components"]) or any(abs(ec[k] - bi["episode_components"][k]) > 1e-6 for k in ec):
            return f"{where} end: components {ec} vs {bi['episode_components']}"
    return ""


PLACE_AT = tuple(k for k, n in enumerate(spec.ACTIONS) if n.startswith(("place:water@", "place:lava@", "place:torch@", "place:block@hole",
                                                                       "place:block@moat", "place:block@mob")))


def run_case(scen, n_ep, seed0, crews, force=None, noise=True, noop_p=0.002, max_steps=4000, prefer=()):
    """n_ep episodes of the reference and an arena side by side; -> (episodes, decisions, first difference or '')"""
    env = SimEnv(0, force=force, noise=noise)
    sim = BatchSim(1, noise=noise)
    pick = random.Random(seed0 + 99)
    dec = 0
    tables = sim.tables
    for ep in range(n_ep):
        seed = seed0 * 1000 + ep
        crew = crews[ep % len(crews)]
        target = None
        if scen == "achievements":
            target = ["buildWorkBench", "buildPickaxe", "acquireIron", "killEnemy", None][ep % 5]
        o = env.reset(scen, seed, crew=crew, target=target)
        sim.reset([0], [(scen, seed, crew, target, force)])
        b = sim.result(0)
        off = None
        msg = compare(o, b, 0, 0, f"{scen} ep {ep} crew {crew}")
        if msg:
            return ep, dec, msg
        steps = 0
        while not o["done"] and steps < max_steps:
            acts = {}
            row = np.full((1, T.MA), -1)
            for i, m in zip(o["agents"], o["mask"]):
                ok = [k for k, v in enumerate(m) if v and k != 0]
                pr = [k for k in ok if k in prefer]
                a = 0 if (pick.random() < noop_p or not ok) else (pick.choice(pr) if pr and pick.random() < 0.5 else pick.choice(ok))
                acts[i] = a
                row[0, i] = a
                dec += 1
            o = env.step(acts)
            sim.step(row)
            b = sim.result(0)
            if off is None and o["info"]["assigned"]:
                k0 = next(iter(o["info"]["assigned"]))
                off = o["info"]["assigned"][k0] - b["info"]["assigned"][int(k0)]
            steps += 1
            msg = compare(o, b, steps, off or 0, f"{scen} ep {ep} crew {crew}")
            if msg:
                return ep, dec, msg
            rows = [i for i in range(T.MA) if sim.valid[i]]
            if rows:
                r2 = reward_from_facts(sim.facts[rows], np.full(len(rows), sim.st.ai[0, 0]), tables["w"], tables["fac"])
                if not np.allclose(r2, sim.rew[rows], rtol=1e-9, atol=1e-9):
                    return ep, dec, f"{scen} ep {ep} step {steps}: reward from facts {r2} vs {sim.rew[rows]}"
        if b["info"]["overflow"]:
            return ep, dec, f"{scen} ep {ep}: overflow {b['info']['overflow']}"
    return n_ep, dec, ""


def run_multi(n_arenas, steps, seed0):
    """n arenas at once (in parallel over the threads), each against a SimEnv of its own, resets as they end"""
    scen = spec.SCENARIOS + list(SIM_SCEN)
    envs = [SimEnv(0) for _ in range(n_arenas)]
    sim = BatchSim(n_arenas)
    pick = random.Random(seed0)
    jobs = [(scen[e % len(scen)], seed0 * 1000 + e, 1 + e % 3) for e in range(n_arenas)]
    outs = [env.reset(*j) for env, j in zip(envs, jobs)]
    sim.reset(np.arange(n_arenas), jobs)
    maps = [{} for _ in range(n_arenas)]   # an arena's old decision ids -> its own
    ep = n_arenas
    dec = 0
    for step in range(steps):
        row = np.full((n_arenas, T.MA), -1)
        acts = []
        for e, o in enumerate(outs):
            a_ = {}
            for i, m in zip(o["agents"], o["mask"]):
                ok = [k for k, v in enumerate(m) if v and k != 0]
                a_[i] = pick.choice(ok) if ok else 0
                row[e, i] = a_[i]
                dec += 1
            acts.append(a_)
        sim.step(row)
        for e in range(n_arenas):
            o = envs[e].step(acts[e])
            b = sim.result(e)
            for k_, v_ in o["info"]["assigned"].items():
                if int(k_) in b["info"]["assigned"]:
                    maps[e][v_] = b["info"]["assigned"][int(k_)]
            msg = compare(o, b, step, maps[e], f"arena {e} ({o['info']['scenario']})")
            if msg:
                return dec, msg
            outs[e] = o
        done = [e for e in range(n_arenas) if outs[e]["done"]]
        if done:
            jobs = [(scen[(ep + j) % len(scen)], seed0 * 1000 + ep + j, 1 + (ep + j) % 3) for j in range(len(done))]
            ep += len(done)
            sim.reset(np.array(done), jobs)
            for e, j in zip(done, jobs):
                outs[e] = envs[e].reset(*j)
                b = sim.result(e)
                msg = compare(outs[e], b, 0, maps[e], f"arena {e} reset")
                if msg:
                    return dec, msg
                maps[e] = {}
    return dec, ""


ID_FIELDS = {"decision": ("id",), "scale": ("causes",), "found": ("decision",), "path_changed": ("by",), "attack_failed": ("by",),
             "kill": ("decision",), "novel": ("decision",)}


def norm_frame(fr, off):
    """a v4 frame with the decision ids as the arena's and the floats rounded (1e-9)"""
    evs = []
    for ev in fr["events"]:
        d = {}
        for k, v in ev.items():
            if k in ID_FIELDS.get(ev["type"], ()):
                v = [norm_id(x, off) for x in v] if isinstance(v, list) else norm_id(v, off)
            elif isinstance(v, float):
                v = round(v, 9)
            d[k] = v
        evs.append(d)
    return (round(fr["t"], 9), round(fr["dt"], 9), fr["phase"], evs)


def run_case4(scen, n_ep, seed0, crews, noop_p=0.004, max_steps=4000, prefer=()):
    """the reward v4: SimEnv(reward="v4") against BatchSim(reward="v4"): the same frames of events (reward_core's input),
    the same points (the kernel's core against reward_core), moves, observations"""
    import reward_core as RCORE
    env = SimEnv(0, reward="v4")
    sim = BatchSim(1, reward="v4", record=True)
    pick = random.Random(seed0 + 99)
    got: list = []
    orig = RCORE.RewardCore.frame

    def rec(self, fr):
        got.append({"t": fr["t"], "dt": fr["dt"], "phase": fr["phase"], "events": [dict(x) for x in fr["events"]]})
        return orig(self, fr)
    RCORE.RewardCore.frame = rec
    dec = 0
    try:
        for ep in range(n_ep):
            seed = seed0 * 1000 + ep
            crew = crews[ep % len(crews)]
            got.clear()
            o = env.reset(scen, seed, crew=crew)
            sim.reset([0], [(scen, seed, crew, None, None)])
            maps: dict = {}
            b = sim.result(0)
            where = f"v4 {scen} ep {ep} crew {crew}"
            msg = compare(o, b, 0, maps, where)
            steps = 0
            while not msg:
                fo, fb = [norm_frame(f, maps) for f in got], [norm_frame(f, 0) for f in sim.frames(0)]
                if fo != fb:
                    for q, (x, y) in enumerate(zip(fo, fb)):
                        if x != y:
                            return ep, dec, f"{where} step {steps}: frame {q} differs" + NL + str(x) + NL + str(y)
                    return ep, dec, f"{where} step {steps}: {len(fo)} frames vs {len(fb)}"
                got.clear()
                if o["done"] or steps >= max_steps:
                    break
                acts = {}
                row = np.full((1, T.MA), -1)
                for i, m in zip(o["agents"], o["mask"]):
                    ok = [k for k, v in enumerate(m) if v and k != 0]
                    pr = [k for k in ok if k in prefer]
                    a = 0 if (pick.random() < noop_p or not ok) else (pick.choice(pr) if pr and pick.random() < 0.5 else pick.choice(ok))
                    acts[i] = a
                    row[0, i] = a
                    dec += 1
                o = env.step(acts)
                sim.step(row)
                b = sim.result(0)
                for k_, v_ in o["info"]["assigned"].items():
                    if int(k_) in b["info"]["assigned"]:
                        maps[v_] = b["info"]["assigned"][int(k_)]
                steps += 1
                if sim.st.ai[0, KN.A_OVERFLOW]:
                    return ep, dec, f"{where} step {steps}: the arena's buffers overflowed ({sim.st.ai[0, KN.A_OVERFLOW]})"
                msg = compare(o, b, steps, maps, where)
            if msg:
                return ep, dec, msg
            st = sim.st
            if st.ai[0, KN.A_OVERFLOW] or st.ai[0, KN.A_C4_ERR] or st.ai[0, KN.A_C4_FORGOT]:
                return ep, dec, f"{where}: overflow {st.ai[0, KN.A_OVERFLOW]} core misses {st.ai[0, KN.A_C4_ERR]} forgotten {st.ai[0, KN.A_C4_FORGOT]}"
    finally:
        RCORE.RewardCore.frame = orig
    return n_ep, dec, ""


def main():
    from train import below_normal
    below_normal()   # idle while League of Legends runs, else below normal
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 3
    cases = []
    for s in spec.SCENARIOS + list(SIM_SCEN):
        cases.append((s, None, True, (1, 2, 3, 4) if s.startswith("waves") else (1, 2, 3)))
    cases += [("wood", "hang", True, (1, 2)), ("farm", "ditch", True, (1, 2)), ("iron", "lava", True, (1, 2)),
              ("mine_trip", "lava", True, (1, 2)), ("wood", None, False, (1, 2)), ("waves:normal", None, False, (2, 3))]
    tot = 0
    for k, (s, force, noise, crews) in enumerate(cases):
        ep, dec, msg = run_case(s, n, 500 + k, crews, force, noise)
        tot += dec
        check(not msg, f"{s}{'+' + force if force else ''}{'' if noise else ' (noise off)'}: {ep} episodes, {dec} decisions alike" +
              ("\n     " + msg if msg else ""))
    # place_at (the reference's place:<slot>@<target>, once unreachable): half of the choices among them when allowed
    for k, s in enumerate(["delay:lava", "fortify", "waves:normal", "delay:bucket"]):
        ep, dec, msg = run_case(s, n, 700 + k, (2, 3) if s.startswith("waves") else (1, 2), prefer=PLACE_AT)
        tot += dec
        check(not msg, f"{s} (place_at preferred): {ep} episodes, {dec} decisions alike" + (NL + msg if msg else ""))
    for k, s in enumerate(["waves:normal", "delay:lava"]):
        ep, dec, msg = run_case4(s, max(2, n // 2), 950 + k, (2, 3), prefer=PLACE_AT)
        check(not msg, f"v4 {s} (place_at preferred): {ep} episodes, {dec} decisions alike" + (NL + msg if msg else ""))
    # the digging zombies (docs/rl/ZOMBIES-DIG.md): agents sent underground in waves (descend preferred), with the zombies on and off
    DOWN = tuple(k for k, nme in enumerate(spec.ACTIONS) if nme in ("descend:mine", "wait:5", "tunnel:branch"))
    for k, s in enumerate(["waves:normal", "waves:long"]):
        ep, dec, msg = run_case(s, n, 1100 + k, (1, 2), prefer=DOWN)
        tot += dec
        check(not msg, f"{s} (underground preferred, digging zombies): {ep} episodes, {dec} decisions alike" + (NL + msg if msg else ""))
        ep, dec, msg = run_case4(s, max(2, n // 2), 1150 + k, (1, 2), prefer=DOWN)
        tot += dec
        check(not msg, f"v4 {s} (underground preferred, digging zombies): {ep} episodes, {dec} decisions alike" + (NL + msg if msg else ""))
    for k, s in enumerate(["wood", "iron", "farm", "achievements", "waves:easy", "waves:normal", "waves:long", "night:shelter", "fortify", "diamond"]):
        ep, dec, msg = run_case4(s, max(2, n // 2), 900 + k, (2, 3, 1, 4) if s.startswith("waves") else (1, 2, 3))
        tot += dec
        check(not msg, f"v4 {s}: {ep} episodes, {dec} decisions alike (frames of events, points, moves)" + (NL + msg if msg else ""))
    dec, msg = run_multi(32, 300, 77)
    tot += dec
    check(not msg, f"32 arenas at once against 32 SimEnvs, 300 steps, resets as they end: {dec} decisions alike" +
          ("\n     " + msg if msg else ""))
    print(f"{tot} decisions compared")
    if bad:
        print(bad, "FAILED")
        sys.exit(1)
    print("all ok")


if __name__ == "__main__":
    main()
