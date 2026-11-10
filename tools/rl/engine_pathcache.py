"""The exact path cache (FastPath.Cache) measured and checked on the engine: the same arenas and scenarios with the
cache off, on, and on with every search also made by the game's own path finder and compared point by point (a cached
answer included). Per mode: game seconds per wall second, decisions per second; the arenas' path counters (searches,
cache hits, kept, checked, mismatches).

    python tools/rl/engine_pathcache.py --arenas 4 --seconds 120 --scenarios wood,iron,waves:normal
"""
import argparse
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import engine_env as E  # noqa: E402


def run(mode, ports, a):
    env = dict(os.environ)
    os.environ.pop("AIGUIDE_ENGINE_CHECK", None)
    os.environ.pop("AIGUIDE_ENGINE_PATHCACHE", None)
    if mode == "off":
        os.environ["AIGUIDE_ENGINE_PATHCACHE"] = "0"
    if mode == "check":
        os.environ["AIGUIDE_ENGINE_CHECK"] = "1"
    E.start_engine(ports, "%dM" % (1024 + 600 * len(ports)), True, None)
    os.environ.clear()
    os.environ.update(env)
    if not E.wait_ready(ports):
        E.stop(ports)
        sys.exit("the arenas did not start")
    out = {"mode": mode, "scenarios": {}}
    for sc in a.scenarios.split(","):
        while E.league() and not (a.check_in_league and len(ports) <= 2):   # a League of Legends match: the measuring waits for its end (a check on 1-2 Idle arenas need not)
            import time
            time.sleep(60)
        crew = 2 if sc.startswith("waves") else 1
        r = subprocess.run([sys.executable, os.path.join(HERE, "engine_bench.py"), "--ports", ",".join(map(str, ports)), "--scenario", sc,
                            "--seconds", str(a.seconds), "--seed", str(a.seed), "--crew", str(crew), "--weights", a.weights,
                            "--label", "pathcache " + mode], capture_output=True, text=True)
        lines = [l for l in r.stdout.splitlines() if l.startswith("{")]
        if not lines or json.loads(lines[0])["envs"] < len(ports):   # an arena gone: say so, do not count it
            E.stop(ports)
            sys.exit("the bench failed (%s, %s): %s" % (mode, sc, (r.stdout + r.stderr)[-800:]))
        tot = json.loads(lines[0])
        out["scenarios"][sc] = {k: tot[k] for k in ("decisions_per_s", "game_s_per_wall_s", "episodes", "success_rate")}
    paths = {}
    for p in ports:
        eng = E.ask(p, {"op": "info"}, 10).get("engine", {})
        for k, v in eng.items():
            if k.startswith("path_") and isinstance(v, (int, float)):
                paths[k] = paths.get(k, 0) + v
            elif k in ("path_last_mismatch", "path_cache_misses"):
                paths.setdefault("mismatch_examples", []).append(v)
    out["paths"] = paths
    E.stop(ports)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--arenas", type=int, default=4)
    ap.add_argument("--port", type=int, default=26200)
    ap.add_argument("--seconds", type=float, default=120)
    ap.add_argument("--seed", type=int, default=900)
    ap.add_argument("--scenarios", default="wood,iron,waves:normal")
    ap.add_argument("--modes", default="check,off,on")
    ap.add_argument("--check-in-league", action="store_true", help="go on through a League match (only 1-2 arenas, Idle: for --modes check)")
    ap.add_argument("--weights", default=os.path.join(E.ROOT, "build", "rl", "runs", "v31eng1", "final.bin"))
    ap.add_argument("--out", default=os.path.join(E.ROOT, "build", "rl", "engine", "pathcache.jsonl"))
    a = ap.parse_args()
    ports = [a.port + i for i in range(a.arenas)]
    for mode in a.modes.split(","):
        res = run(mode, ports, a)
        print(json.dumps(res, ensure_ascii=False), flush=True)
        with open(a.out, "a", encoding="utf-8") as f:
            f.write(json.dumps(res, ensure_ascii=False) + "\n")


if __name__ == "__main__":
    main()
