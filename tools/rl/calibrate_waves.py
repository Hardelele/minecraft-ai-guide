"""The simulator's waves fitted to the game's engine (docs/rl/ENGINE.md, section 9): the same policy, the same crew;
three numbers of rl/sim-calibration.json "waves" (the kills' and the hits' multipliers, the gate's opening's leak) moved
until the simulator's kills, breaches and damage a game second are the engine's; its deaths and mob-seconds are the check.
The engine's side: a trace of tools/rl/engine_bench.py --trace (its measured sums; the perimeter's are paid to every
agent, so divided by the crew).

    python tools/rl/calibrate_waves.py --trace build/rl/engine/waves-diag.jsonl --weights build/rl/runs/v31eng1/final.bin --crew 2
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import sim_env  # noqa: E402
import spec  # noqa: E402

KEYS = ("kills", "breaches", "damage", "deaths", "mob_seconds")


def game_rates(path, crew):
    eps = [json.loads(l) for l in open(path, encoding="utf-8-sig") if l.strip()]
    t = sum(e["t"] for e in eps)
    s = dict.fromkeys(KEYS, 0.0)
    for e in eps:
        m = e.get("measured") or {}
        s["kills"] += m.get("perimeter.kill", 0)
        s["breaches"] += m.get("perimeter.breach", 0) / crew
        s["damage"] += m.get("damage", 0)
        s["deaths"] += m.get("death", 0) + m.get("accident", 0)
        s["mob_seconds"] += m.get("perimeter.mob_seconds", 0) / crew
    return {k: v / t for k, v in s.items()}, t / len(eps), len(eps)


def sim_rates(pol, scenario, crew, n, seconds, seed):
    env = sim_env.SimEnv(seed)
    s, t = dict.fromkeys(KEYS, 0.0), 0.0
    for ep in range(n):
        o = env.reset(scenario, seed + ep, crew=crew)
        env.sc = dict(env.sc, seconds=seconds)   # as long as the engine's runs lasted (they ended lost, the simulator's ran out of time)
        while not o["done"]:
            with torch.no_grad():
                a = pol.act(torch.tensor(np.asarray(o["obs"], np.float32)), torch.tensor(np.asarray(o["mask"], bool)))[0].tolist()
            o = env.step(dict(zip(o["agents"], a)))
        i, c = o["info"], o["info"].get("episode_components") or {}
        s["kills"] += i.get("kills", 0)
        s["breaches"] += i.get("breaches", 0)
        s["mob_seconds"] += i.get("mob_seconds", 0)
        s["damage"] += -c.get("damage", 0) / abs(spec.WEIGHTS["damage"])
        s["deaths"] += -c.get("death", 0) / abs(spec.WEIGHTS["death"]) - c.get("accident", 0) / abs(spec.WEIGHTS["accident"])
        t += i["t"]
    return {k: v / t for k, v in s.items()}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--trace", required=True)
    ap.add_argument("--weights", required=True)
    ap.add_argument("--scenario", default="waves:normal")
    ap.add_argument("--crew", type=int, default=2)
    ap.add_argument("--n", type=int, default=24)
    ap.add_argument("--iters", type=int, default=6)
    ap.add_argument("--seed", type=int, default=4100)
    ap.add_argument("--write", action="store_true", help="put the fitted numbers into rl/sim-calibration.json")
    ap.add_argument("--fixed", default="", help="kill,hurt,gate_leak: only these looked at (iters 0)")
    a = ap.parse_args()
    torch.set_num_threads(1)
    from export_policy import read_policy
    pol = read_policy(Path(a.weights))
    game, secs, neps = game_rates(a.trace, a.crew)
    print("engine (%d episodes, %.0f s each): %s" % (neps, secs, {k: round(v, 4) for k, v in game.items()}))
    w = {k: sim_env.WCAL[k] for k in ("kill", "hurt", "gate_leak")}
    w.update({"kill": 1.0, "hurt": 1.0, "gate_leak": 0.0})
    if a.fixed:
        w.update(dict(zip(("kill", "hurt", "gate_leak"), (float(x) for x in a.fixed.split(",")))))
        a.iters = 0
    hist = []
    for it in range(a.iters + 1):
        sim_env.WCAL.update(w)
        sim = sim_rates(pol, a.scenario, a.crew, a.n, secs, a.seed)
        hist.append({"params": dict(w), "sim": {k: round(v, 4) for k, v in sim.items()}})
        print("iter %d %s -> %s" % (it, {k: round(v, 3) for k, v in w.items()}, {k: round(v, 4) for k, v in sim.items()}), flush=True)
        if it == a.iters:
            break
        r = lambda k: game[k] / max(1e-6, sim[k])   # noqa: E731
        w["kill"] = float(np.clip(w["kill"] * r("kills") ** 0.8, 0.05, 5))
        w["hurt"] = float(np.clip(w["hurt"] * r("damage") ** 0.8, 0.2, 20))
        w["gate_leak"] = float(np.clip(max(0.05, w["gate_leak"]) * r("breaches") ** 0.8, 0.0, 1.0))
    best = min(hist, key=lambda h: sum(abs(np.log(max(1e-6, h["sim"][k]) / max(1e-6, game[k]))) for k in ("kills", "breaches", "damage", "deaths")))
    print("best:", best)
    if a.write:
        p = spec.ROOT / "rl" / "sim-calibration.json"
        cal = json.loads(p.read_text(encoding="utf-8"))
        cal["waves"] = {"kill": round(best["params"]["kill"], 4), "hurt": round(best["params"]["hurt"], 4), "gate_leak": round(best["params"]["gate_leak"], 4),
                        "source": "tools/rl/calibrate_waves.py: %d engine episodes of %s, crew %d, %s; a game second: engine %s, simulator %s"
                                  % (neps, a.scenario, a.crew, Path(a.weights).name, {k: round(v, 4) for k, v in game.items()}, best["sim"])}
        p.write_text(json.dumps(cal, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        print("written to", p)


if __name__ == "__main__":
    main()
