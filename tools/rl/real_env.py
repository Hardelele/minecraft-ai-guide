"""rl/env's real environment (a dedicated server with -Daiguide.rl=PORT, docs/rl/CONTRACT.md) in the simulator's
shape, for tools/rl/train.py --env real: reset(scenario, seed, crew) / step({agent: action}) -> {agents, obs, mask,
reward, done, info}. The protocol is tools/rl/env_client.py's CrewEnv (rl/env's); agents are its ids as ints.

info: scenario, done_reason, ticks, t (game seconds), cleared (waves: wave_cleared points / its weight, one agent's),
success (the policy side's: the goal, or WAVES_PASS waves of a run), episode_components (summed over the agents).
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
                if k == "wave_cleared":
                    self.cleared_pts[a] = self.cleared_pts.get(a, 0.0) + v
        w = spec.WEIGHTS.get("wave_cleared", 1.0) or 1.0
        cleared = int(round(max(self.cleared_pts.values(), default=0.0) / w))
        why = info.get("done_reason", "")
        if self.scenario.startswith("waves"):
            success = cleared >= spec.WAVES_PASS.get(self.scenario, 3)
        else:
            success = why == "goal"
        return {"agents": [int(a) for a in ids],
                "obs": [obs.get(a, [0.0] * spec.D) for a in ids],
                "mask": [[bool(x) for x in mask.get(a, [1] + [0] * (spec.A - 1))] for a in ids],
                "reward": [float(reward.get(a, 0.0)) for a in ids] if reward else [0.0] * len(ids),
                "done": bool(done),
                "info": {"scenario": self.scenario, "done_reason": why, "ticks": info.get("ticks", 0),
                         "t": info.get("ticks", 0) / 20.0, "cleared": cleared, "success": bool(done and success),
                         "goal_progress": info.get("goal_progress"), "episode_components": dict(self.comp) if done else {}}}

    def reset(self, scenario="wood", seed=None, crew=1):
        if self.env is None:
            self.env = self.CrewEnv(self.host, self.port, scenario, crew=crew, timeout=self.timeout)
        self.env.crew = crew   # the reset's message carries it
        self.scenario = scenario
        self.comp, self.cleared_pts = {}, {}
        obs, mask, info = self.env.reset(seed=seed, scenario=scenario)
        return self._out(obs, mask, {}, False, info)

    def step(self, actions):
        obs, mask, reward, done, info = self.env.step({str(k): int(v) for k, v in actions.items()})
        return self._out(obs, mask, reward, done, info)

    def close_env(self):
        if self.env is not None:
            self.env.close()
