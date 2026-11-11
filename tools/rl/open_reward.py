"""The open world's reward (docs/rl/OPEN-WORLD.md section 4): reward v4's core (docs/rl/REWARD-v4.md) over the frames
of events the open world gives each step, with no scenario (every scale at its weight: the achievements as steps, the
equipment). Curiosity is not here: it is the world model's, in imagination (tools/rl/dreamer).

reward_core.py, rl/reward-v4.json and rl/reward.json come from the tree once v4 is merged in (through rl/fixes); until
then they are read, unchanged, from the branch rl/reward-outcomes into build/rl/v4core (never edited here).

    r = OpenReward(); points = r.frame(answer["frame"], answer["agents"])   # {agent: (points, components)}
"""
from __future__ import annotations

import importlib.util
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
CORE = ROOT / "build" / "rl" / "v4core"
BRANCH = "rl/reward-outcomes"
FILES = ["tools/rl/reward_core.py", "rl/reward-v4.json", "rl/reward.json"]

# the game's damage types as v4's causes (an accident costs more than a death in a fight)
CAUSES = {"inFire": "fire", "onFire": "fire", "inWall": "accident", "cactus": "accident", "outOfWorld": "accident", "generic": "accident"}


def load_core():
    """reward_core as a module: the tree's when v4 is merged (with its rl/reward-v4.json), else the branch's copy"""
    local = HERE / "reward_core.py"
    if local.is_file() and (ROOT / "rl" / "reward-v4.json").is_file():
        path = local
    else:
        for f in FILES:
            dst = CORE / f
            if not dst.is_file():
                dst.parent.mkdir(parents=True, exist_ok=True)
                out = subprocess.run(["git", "show", BRANCH + ":" + f], cwd=ROOT, capture_output=True, check=True).stdout
                dst.write_bytes(out)
        path = CORE / "tools" / "rl" / "reward_core.py"
    spec = importlib.util.spec_from_file_location("reward_core_v4", path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules["reward_core_v4"] = mod
    spec.loader.exec_module(mod)
    return mod


class OpenReward:
    def __init__(self):
        self.mod = load_core()
        self.core = self.mod.RewardCore(None)   # no scenario: every scale paid at its weight
        self.total = {}

    def frame(self, fr: dict, agents) -> dict:
        evs = []
        for e in fr.get("events", ()):
            if e.get("type") == "death":
                e["cause"] = CAUSES.get(e.get("cause"), e.get("cause"))
            if hasattr(self.core, "ev_" + e.get("type", "")):   # (the achievement itself is for the logs: its scale events pay)
                evs.append(e)
        self.core.frame(dict(fr, events=evs))
        out = {}
        for a in agents:
            pts, comps = self.core.take(str(a))
            out[str(a)] = (pts, comps)
            self.total[str(a)] = self.total.get(str(a), 0.0) + pts
        return out

    def rest(self) -> dict:
        """what is left for every agent at the episode's end (the dead too)"""
        return {k: self.core.take(k) for k in list(self.core.ag)}

    def episode(self) -> dict:
        return dict(self.core.episode)
