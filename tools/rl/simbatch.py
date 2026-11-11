"""The batch simulator: tools/rl/sim_env.py's world for thousands of arenas at once (numba kernels over arrays,
tools/rl/simbatch_kernel.py), the same contract 3.1 (rl/*.json: 136 actions, 721 inputs, the masks, the scenarios
and the simulator's own, the calibration rl/sim-calibration.json with the waves fitted to the engine) and, seed for
seed, the same draws: an arena reset with the seed a SimEnv is reset with, given the same actions, gives the same
observations, masks, rewards, events and ends (tools/tests/test_rl_simbatch.py).

    sim = BatchSim(4096, threads=8)
    sim.reset(np.arange(4096), [("wood", seed, crew, None, None), ...])
    while ...:
        acts = np.full((4096, MA), -1); acts[valid rows] = policy(sim.obs[valid], sim.mask[valid])
        sim.step(acts)                         # the arenas with an agent to move step; obs/mask/rew/valid refilled
        done = sim.done_arenas()               # their last rows are in this step's output; reset them next

Rows: arena e, agent i at e * MA + i; `valid` marks the rows of agents at a decision (all of a crew at its end).
The reward of a row is what that agent got since its previous decision, as sim_env pays it; `facts` is the same
time's measured amounts by key (rl/reward.json's keys and the simulator's own, simbatch_tables.KEYS) before any
weight, so a reward is pluggable: `reward_from_facts(facts, scen, weights)`; the step's events with their causes
(the decisions behind a kill, an ore, a clean wave...) are in `events()`.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import simbatch_tables as T  # noqa: E402
import simbatch_kernel as KN  # noqa: E402
from simbatch_rng import key_of  # noqa: E402

MA, A, D = T.MA, T.A, T.D
FORCES = {None: 0, "hang": 1, "ditch": 2, "lava": 3}


class BatchSim:
    def __init__(self, n: int, noise: bool = True, curiosity: float = -1.0, obstacles: float = 1.0, threads: int | None = None,
                 reward: str = "v3", record: bool = False, ev_cap: int = 0, merge: bool = False):
        """reward: "v3" (rl/reward.json's weights, as sim_env pays them) or "v4" (docs/rl/REWARD-v4.md: sim_env's events under
        reward="v4" and reward_core's points from them, in the kernel); record: keep each step's v4 frames (frames()); ev_cap: an arena's events a
        step at most (0: 96 under v3, 4096 under v4: a long step's frames each move credit); merge: a step's credit moves to one
        (agent, component, decision) summed into one event (what train.py does with them is linear: the same)"""
        import numba
        if threads:
            numba.set_num_threads(max(1, min(threads, numba.config.NUMBA_NUM_THREADS)))
        numba.set_parallel_chunksize(8)   # the arenas handed to the threads 8 at a time: their steps differ much in work
        self.n = n
        self.tables = T.scen_tables(curiosity)
        import types
        self.v4 = reward == "v4"
        ev_cap = ev_cap or (4096 if self.v4 and not merge else (512 if self.v4 else KN.EV_CAP))
        self.ref, arrays = KN.new_state(n, self.tables, noise, obstacles, self.v4, record, ev_cap, merge)   # the kernels' struct
        self.st = types.SimpleNamespace(**arrays)
        rows = n * MA
        self.obs = np.zeros((rows, D), np.float32)
        self.mask = np.zeros((rows, A), np.bool_)
        self.rew = np.zeros(rows)
        self.facts = np.zeros((rows, T.NKEY))
        self.valid = np.zeros(rows, np.bool_)
        self.assigned = np.full((n, MA), -1, np.int64)
        self.active = np.zeros(n, np.bool_)

    # ---- the protocol ---------------------------------------------------------------------------------------------
    def reset(self, which, jobs):
        """jobs[j] = (scenario, seed, crew[, target[, force]]) for arena which[j]; its first rows are filled (the
        rows of the other arenas are left as they were)"""
        which = np.asarray(which, np.int64)
        m = len(which)
        keys = np.zeros((m, 4), np.uint32)
        nk = np.zeros(m, np.int64)
        keys4 = np.zeros((m, 4), np.uint32)   # the v4 events' own draws: Random(seed + 7919)
        nk4 = np.zeros(m, np.int64)
        sc = np.zeros(m, np.int64)
        crew = np.zeros(m, np.int64)
        tg = np.full(m, -1, np.int64)
        fo = np.zeros(m, np.int64)
        for j, job in enumerate(jobs):
            k = key_of(job[1])
            keys[j, :len(k)] = k
            nk[j] = len(k)
            k4 = key_of(job[1] + 7919)
            keys4[j, :len(k4)] = k4
            nk4[j] = len(k4)
            sc[j] = T.SC[job[0]]
            crew[j] = job[2]
            if len(job) > 3 and job[3] in T.ACH_IX:
                tg[j] = T.ACH_IX[job[3]]
            if len(job) > 4:
                fo[j] = FORCES[job[4]]
        self.assigned[which] = -1
        KN.reset_batch(self.ref, which, sc, keys, nk, crew, tg, fo, self.obs, self.mask, self.rew, self.facts, self.valid, keys4, nk4)

    def step(self, acts, active=None):
        """acts [n, MA]: an agent's action, -1 where it has none; active: the arenas to step (default: those with an
        action); -> the valid rows"""
        acts = np.ascontiguousarray(acts, np.int64)
        if active is None:
            active = (acts >= 0).any(1)
        KN.step_batch(self.ref, acts, np.ascontiguousarray(active, np.bool_), self.obs, self.mask, self.rew, self.facts, self.valid,
                      self.assigned)
        return self.valid

    # ---- what came out -----------------------------------------------------------------------------------------------
    @property
    def done(self) -> np.ndarray:
        return self.st.ai[:, KN.A_DONE] == 1

    @property
    def t(self) -> np.ndarray:
        return self.st.af[:, KN.AF_T]

    def info(self, e: int) -> dict:
        """sim_env's info for arena e (its numbers; events by `events`)"""
        st = self.st
        ai, af = st.ai[e], st.af[e]
        ob = {"hang_logs": int(ai[KN.A_OB_HANG_LOGS]), "ditch_met": int(ai[KN.A_OB_DITCH_MET]),
              "ditch_bridged": int(ai[KN.A_OB_DITCH_BRIDGED]), "lava_met": int(ai[KN.A_OB_LAVA_MET]),
              "lava_passed": int(ai[KN.A_OB_LAVA_PASSED]), "shelter_s": round(float(af[KN.AF_OB_SHELTER]), 1),
              "switch_ok": int(ai[KN.A_OB_SWITCH_OK]), "retry_fail": int(ai[KN.A_OB_RETRY_FAIL]), "pit_stuck": int(ai[KN.A_OB_PIT_STUCK]),
              "hang": int(ai[KN.A_HANG])}
        done = bool(ai[KN.A_DONE])
        out = {"t": float(af[KN.AF_T]), "ticks": int(af[KN.AF_T] * 20), "scenario": T.SCENS[ai[KN.A_SCEN]],
               "done_reason": KN.DONE_REASONS[ai[KN.A_REASON]], "success": bool(ai[KN.A_SUCCESS]) if done else False,
               "cleared": int(ai[KN.A_CLEARED]), "goal_progress": float(af[KN.AF_PROGRESS_OUT]),
               "achievements": int(np.delete(st.ach[e, :ai[KN.A_NAG]].any(0), T.ACH_IX["openInventory"]).sum()), "breaches": int(ai[KN.A_BREACHES]), "mob_seconds": round(float(af[KN.AF_MOB_S]), 1),
               "clean_waves": int(ai[KN.A_CLEAN]), "novel": int(ai[KN.A_NOVEL]), "repeat_fail": int(ai[KN.A_REPEAT_FAIL]),
               "discoveries": int(ai[KN.A_DISCOVERIES]), "kills": int(ai[KN.A_KILLS]), "spawned": int(ai[KN.A_SPAWNED]),
               "assigned": {i: int(d) for i, d in enumerate(self.assigned[e]) if d >= 0},
               "sustain": [round(float(af[KN.AF_SUS_FR]), 2), round(float(af[KN.AF_SUS_FE]), 2)], "obstacles": ob,
               "overflow": int(ai[KN.A_OVERFLOW])}
        if done:
            if ai[KN.A_V4]:
                out["episode_components"] = {c: float(v) for c, v in zip(T.C4, st.ep4[e]) if v != 0}
            else:
                out["episode_components"] = {c: float(v) for c, v in zip(T.COMPS, st.comp[e]) if v != 0}
            got = st.ach[e, :ai[KN.A_NAG]].any(0)
            out["achievement_ids"] = sorted(T.ACH[k] for k in np.flatnonzero(got) if T.ACH[k] != "openInventory")
        return out

    def events(self, e: int) -> list[dict]:
        """the step's events of arena e in sim_env's shape (reward with its chain, novel_effect, achievement)"""
        st = self.st
        out = []
        for k in range(st.nev[e]):
            typ, ag, key, tk, first, s, n = (int(x) for x in st.ev_i[e, k, :7])
            chain = [{"decision_id": int(c[0]), "agent": int(c[1]), "tick": int(c[2]),
                      "kind": T.ACTIONS[c[3]] if 0 <= c[3] < A else ("reflex:water" if c[3] == A else ""), "via": T.VIAS[c[4]]}
                     for c in st.links[e, s:s + n]]
            if typ == KN.EV_MOVE:   # the reward v4's credit: the points that belong to an earlier decision
                out.append({"type": "reward", "agent": ag, "key": T.C4[key], "points": float(st.ev_f[e, k]), "move": 1.0, "chain": chain,
                            "ticks": tk})
            elif typ == KN.EV_REWARD:
                out.append({"type": "reward", "agent": ag, "key": T.KEYS[key], "points": round(float(st.ev_f[e, k]), 6), "chain": chain,
                            "ticks": tk})
            elif typ == KN.EV_NOVEL:
                out.append({"type": "novel_effect", "agent": ag, "cause": T.VIAS[key // KN.NEF], "effect": T.EFFECTS[key % KN.NEF],
                            "first_ever": bool(first), "chain": chain, "ticks": tk})
            else:
                out.append({"type": "achievement", "agent": ag, "id": T.ACH[key], "name": T.ACHM.ACH[T.ACH[key]][1], "ticks": tk})
        return out

    def result(self, e: int, with_events: bool = True) -> dict:
        """arena e's step output as SimEnv.step returns it (for the checks and the slow paths)"""
        rows = [e * MA + i for i in range(MA) if self.valid[e * MA + i]]
        o = {"agents": [r - e * MA for r in rows], "obs": self.obs[rows], "mask": self.mask[rows], "reward": [float(self.rew[r]) for r in rows],
             "done": bool(self.st.ai[e, KN.A_DONE]), "t": float(self.st.af[e, KN.AF_T]), "info": self.info(e)}
        if with_events:
            o["info"]["events"] = self.events(e)
        return o


    def frames(self, e: int) -> list[dict]:
        """the v4 frames arena e made in the last call (record=True): reward_core's input, as sim_env(reward="v4") gives it"""
        st = self.st
        out = []
        for f in range(int(st.ai[e, KN.A_REC_NF])):
            t, dt = (float(x) for x in st.rec_ff[e, f])
            ph, s0, n = (int(x) for x in st.rec_fi[e, f])
            evs = []
            for q in range(s0, s0 + n):
                evs.append(_event4(st.rec_ei[e, q], st.rec_ef[e, q], st.rec_l[e]))
            out.append({"t": t, "dt": dt, "phase": T.PHASES4[ph], "events": evs})
        return out


FK = KN.FK
_EFFECTS4 = ["blocked", "detour"]


def _res(num, kind):
    return "%s%d" % (FK[int(kind)], int(num))


def _event4(ei, ef, lst) -> dict:
    """a kernel's v4 event record as rl/events.json's dict (sim_env's fields and order)"""
    typ = T.EV4[int(ei[0])]
    i1, i2, i3, i4 = (int(x) for x in ei[1:5])
    l1 = [int(x) for x in lst[ei[5]:ei[5] + ei[6]]]
    l2 = [int(x) for x in lst[ei[7]:ei[7] + ei[8]]]
    f1, f2, f3 = (float(x) for x in ef)
    if typ == "agent":
        return {"type": "agent", "agent": i1, "x": f1, "y": f2, "z": 0.0, "hp": f3, "alive": bool(i2), "base_dist": f1}
    if typ == "decision":
        return {"type": "decision", "agent": i1, "id": i2, "kind": T.ACTIONS[i3]}
    if typ == "noop":
        return {"type": "noop", "agent": i1, "left_s": f1, "goal": bool(i2)}
    if typ == "goal":
        return {"type": "goal"}
    if typ == "damage":
        d = {"type": "damage", "agent": i1, "hp": f1}
        if i2 >= 0:
            d["mob"] = i2
        return d
    if typ == "death":
        return {"type": "death", "agent": i1, "cause": "accident" if i2 else "mob", "base_dist": f1}
    if typ == "breach":
        return {"type": "breach", "mob": i1}
    if typ == "inside":
        return {"type": "inside", "mob_s": f1}
    if typ == "scale":
        d = {"type": "scale", "agent": i1, "key": T.C4[i2], "delta": f1}
        if l1:
            d["causes"] = l1
        if i3 >= 0:
            d["res"] = _res(i3, f2)
        return d
    if typ == "found":
        return {"type": "found", "agent": i1, "decision": i2, "res": _res(i3, f2), "key": T.C4[i4], "amount": f1}
    if typ == "threat":
        d = {"type": "threat", "mob": i1, "kind": "zombie", "target": "base" if i2 < 0 else i2, "dmg": f1}
        if i3:
            d["stay_s"] = f2
        return d
    if typ == "fall":
        return {"type": "fall", "why": "noop" if i1 == 0 else "lost"}
    if typ == "path_changed":
        return {"type": "path_changed", "mob": i1, "effect": _EFFECTS4[i2], "delay_s": f1, "on_route": bool(i3), "by": l1, "seen": l2}
    if typ == "attack_failed":
        return {"type": "attack_failed", "mob": i1, "target": i2, "reason": "cover", "by": l1, "seen": l2}
    if typ == "kill":
        return {"type": "kill", "mob": i1, "agent": i2, "decision": i3, "seen": l2}
    if typ == "mob_gone":
        return {"type": "mob_gone", "mob": i1, "why": "despawn" if i2 else "wave_end"}
    return {"type": "novel", "agent": i1, "decision": i2}


def reward_from_facts(facts: np.ndarray, scen: np.ndarray, w: np.ndarray, fac: np.ndarray) -> np.ndarray:
    """a reward over the measured amounts: sum over keys of w[scen, key] * amount * factor[scen, key] (rl/reward.json's
    is the simulator's own: BatchSim.tables["w"], ["fac"]); facts [rows, NKEY], scen [rows]"""
    return (facts * w[scen] * fac[scen]).sum(1)
