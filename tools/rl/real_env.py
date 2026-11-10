"""rl/env's real environment (a dedicated server with -Daiguide.rl=PORT, docs/rl/CONTRACT.md, contract 3:
docs/rl/CONTRACT-v2.md) in the simulator's shape, for tools/rl/train.py --env real: reset(scenario, seed, crew,
target) / step({agent: action}) -> {agents, obs, mask, reward, done, info}. The protocol is tools/rl/env_client.py's
CrewEnv (rl/env's); agents are its ids as ints.

info: scenario, done_reason, ticks, t (game seconds), cleared (waves: wave_cleared points / its weight, one agent's),
success (the policy side's: the goal, or WAVES_PASS waves of a run), episode_components (summed over the agents);
contract 3: assigned ({agent: decision_id} of the actions just sent), events (reward events with their chains,
achievements, novel effects; agents as ints), and at the end achievements (the crew's count), achievement_ids,
breaches, mob_seconds, kills (measured: the raw sums of the reward's keys), novel and discoveries (novel effects of the
episode, those first ever).
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import spec  # noqa: E402


class RealEnv:
    def __init__(self, addr: str, timeout: float = 600.0):
        from env_client import CrewEnv   # rl/env's client (merged from its branch)
        host, port = addr.rsplit(":", 1)
        self.host, self.port, self.timeout = host, int(port), timeout
        self.env = None
        self.CrewEnv = CrewEnv

    def _out(self, obs, mask, reward, done, info):
        if done:
            ids = sorted(set(reward) | set(obs), key=int)
        else:
            ids = sorted(obs, key=int)
        for a, comps in (info.get("components") or {}).items():
            for k, v in comps.items():
                self.comp[k] = self.comp.get(k, 0.0) + v
        evs = []
        for ev in info.get("events") or ():
            ev = dict(ev)
            if "agent" in ev:
                try:
                    ev["agent"] = int(ev["agent"])
                except (TypeError, ValueError):
                    pass
            evs.append(ev)
            if ev.get("type") == "novel_effect":
                self.novel += 1
                self.discoveries += 1 if ev.get("first_ever") else 0
            if ev.get("type") == "reward" and ev.get("key") in ("wave_cleared", "perimeter.wave_cleared"):
                a = ev.get("agent")
                self.cleared_pts[a] = self.cleared_pts.get(a, 0.0) + 1.0
        # waves cleared: v1's component, contract 3's perimeter.wave_cleared (by the measured sums at the end)
        for a, comps in (info.get("components") or {}).items():
            if "wave_cleared" in comps:
                w = spec.WEIGHTS.get("wave_cleared", 1.0) or 1.0
                self.cleared_v1[a] = self.cleared_v1.get(a, 0.0) + comps["wave_cleared"] / w
        measured = info.get("measured") or {}
        cleared = int(round(max(self.cleared_v1.values(), default=0.0)))
        if "perimeter.wave_cleared" in measured:
            cleared = int(round(measured["perimeter.wave_cleared"] / max(1, len(ids))))
        why = info.get("done_reason", "")
        if self.scenario.startswith("waves"):
            success = cleared >= spec.WAVES_PASS.get(self.scenario, 3)
        else:
            success = why == "goal"
        out_info = {"scenario": self.scenario, "done_reason": why, "ticks": info.get("ticks", 0),
                    "t": info.get("ticks", 0) / 20.0, "cleared": cleared, "success": bool(done and success),
                    "goal_progress": info.get("goal_progress"), "episode_components": dict(self.comp) if done else {},
                    "assigned": {int(k): v for k, v in (info.get("assigned") or {}).items()}, "events": evs,
                    "sim_ms": info.get("sim_ms"), "reset_ms": info.get("reset_ms")}
        if done:
            ach = info.get("achievements") or {}
            got = set()
            for v in (ach.values() if isinstance(ach, dict) else []):
                got |= set(v)
            got.discard("openInventory")
            out_info.update({"achievement_ids": sorted(got), "achievements": len(got),
                             "breaches": int(measured.get("perimeter.breach", 0) / max(1, len(ids))),
                             "mob_seconds": round(measured.get("perimeter.mob_seconds", 0.0) / max(1, len(ids)), 1),
                             "kills": int(measured.get("perimeter.kill", measured.get("kill", 0))),
                             "novel": self.novel, "discoveries": self.discoveries, "measured": measured})
        return {"agents": [int(a) for a in ids],
                "obs": [obs.get(a, [0.0] * spec.D) for a in ids],
                "mask": [[bool(x) for x in mask.get(a, [1] + [0] * (spec.A - 1))] for a in ids],
                "reward": [float(reward.get(a, 0.0)) for a in ids] if reward else [0.0] * len(ids),
                "done": bool(done), "t": out_info["t"], "info": out_info}

    def reset(self, scenario="wood", seed=None, crew=1, target=None):
        if self.env is None:
            self.env = self.CrewEnv(self.host, self.port, scenario, crew=crew, timeout=self.timeout)
        self.env.crew = crew   # the reset's message carries it
        self.scenario = scenario
        self.comp, self.cleared_pts, self.cleared_v1 = {}, {}, {}
        self.novel = self.discoveries = 0
        if target:   # contract 3: the curriculum's achievement target (CrewEnv's reset has no field for it)
            msg = {"op": "reset", "scenario": scenario, "crew": crew, "raw": self.env.raw, "target": target}
            if seed is not None:
                msg["seed"] = int(seed)
            out = self.env.conn.ask(msg)
            obs, mask, info = out["obs"], out["mask"], out["info"]
        else:
            obs, mask, info = self.env.reset(seed=seed, scenario=scenario)
        return self._out(obs, mask, {}, False, info)

    def step(self, actions):
        obs, mask, reward, done, info = self.env.step({str(k): int(v) for k, v in actions.items()})
        return self._out(obs, mask, reward, done, info)

    def close_env(self):
        if self.env is not None:
            self.env.close()
