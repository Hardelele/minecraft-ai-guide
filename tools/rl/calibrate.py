"""The simulator's calibration from the real environment's logs (a dedicated server's stdout.txt, rl/env's lines):
how often each skill fails and why, whether a failure repeats (a target out of reach stays out of reach), what a
success yields, and how many game seconds a skill takes (non-negative least squares over the crew-of-one episodes:
an episode's game seconds = the sum of its skills' durations, the ticks wait while the policy thinks).

    python tools/rl/calibrate.py --logs <dir>/env-26100/stdout.txt,<dir>/env-26101/stdout.txt --out build/rl/calibration.json

The numbers it prints are what rl/sim-calibration.json (the simulator's constants) is set from; docs/rl/POLICY-v2.md
has the table and what was changed in the simulator by it.
"""
from __future__ import annotations

import argparse
import collections
import json
import re
from pathlib import Path

import numpy as np

EP_START = re.compile(r"rl: episode (\d+) (\S+), seed (-?\d+), crew (\d+)")
EP_DONE = re.compile(r"rl: episode (\d+) (\S+) done \((\w+)\) at (\d+(?:\.\d+)?) game s")
MIND = re.compile(r"mind: \S+ (\d+) ([a-z_]+) -> ([A-Z_]+)(.*)$")
GOT = re.compile(r"\+(\d+) ([a-z_]+)(?::\d+)?\b")
# the v1 simulator's mean durations of a successful skill (game seconds): the ridge's prior
PRIOR = {"chop_tree": 22.0, "mine": 17.0, "craft": 3.5, "smelt": 20.0, "place": 1.5, "build_wall": 20.0, "repair_wall": 15.0,
         "dig_moat": 20.0, "light_area": 10.0, "equip": 1.0, "take_post": 4.0, "hold_line": 60.0, "retreat": 4.0, "go_to": 4.0,
         "eat": 2.0, "hunt": 20.0, "put": 4.0, "take": 4.0, "wait": 5.0, "look_around": 3.0}


def episodes(paths):
    """the episodes of the logs: scenario, crew, done reason, game seconds, the skills' results in order"""
    for p in paths:
        cur = None
        for line in open(p, encoding="utf-8", errors="replace"):
            m = EP_START.search(line)
            if m:
                cur = {"scenario": m.group(2), "crew": int(m.group(4)), "steps": []}
                continue
            m = EP_DONE.search(line)
            if m:
                if cur is not None and cur["scenario"] == m.group(2):
                    cur["reason"], cur["game_s"] = m.group(3), float(m.group(4))
                    yield cur
                cur = None
                continue
            if cur is None:
                continue
            m = MIND.search(line)
            if m:
                rest = m.group(4)
                got = collections.Counter()
                for n, k in GOT.findall(rest.split(");")[-1] if ");" in rest else rest):
                    got[k] += int(n)
                cur["steps"].append({"agent": int(m.group(1)), "skill": m.group(2), "result": m.group(3), "got": dict(got), "text": rest})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--logs", required=True, help="comma-separated stdout.txt of rl/env's servers")
    ap.add_argument("--out", default="build/rl/calibration.json")
    ap.add_argument("--prior", type=float, default=20.0, help="the v1 durations' weight, in episodes")
    a = ap.parse_args()
    eps = list(episodes([x for x in a.logs.split(",") if x]))
    print(len(eps), "episodes", collections.Counter(e["scenario"] for e in eps))

    # failures: by skill, by reason; repeats (the same agent's next step of the same skill after a failure)
    res = collections.defaultdict(collections.Counter)
    after_fail = collections.defaultdict(lambda: [0, 0])   # skill -> [next of the same skill, of them failed]
    yields = collections.defaultdict(list)
    zero_ok = collections.Counter()
    by_scen = collections.defaultdict(collections.Counter)
    for e in eps:
        last = {}
        for s in e["steps"]:
            k, r = s["skill"], s["result"]
            res[k][r] += 1
            by_scen[(k, e["scenario"])][r == "OK"] += 1
            prev = last.get(s["agent"])
            if prev and prev[0] == k and prev[1] != "OK":
                after_fail[k][0] += 1
                after_fail[k][1] += r != "OK"
            last[s["agent"]] = (k, r)
            if r == "OK":
                g = s["got"]
                if k == "chop_tree":
                    yields[k].append(g.get("log", 0))
                    zero_ok[k] += g.get("log", 0) == 0
                elif k == "mine":
                    yields[k].append(g.get("cobblestone", 0))
                    yields["mine_coal"].append(g.get("coal", 0))
                    yields["mine_iron"].append(g.get("iron_ore", 0))
                elif k == "hunt":
                    f = sum(v for kk, v in g.items() if kk in ("chicken", "beef", "porkchop", "mutton", "rabbit", "fish"))
                    yields[k].append(f)
                    zero_ok[k] += f == 0
                elif k in ("build_wall", "repair_wall", "light_area"):
                    m = re.search(r"put (\d+)", s["text"])
                    yields[k].append(int(m.group(1)) if m else 0)
                elif k == "dig_moat":
                    m = re.search(r"stretch of (\d+)", s["text"])
                    yields[k].append(int(m.group(1)) if m else 0)
                elif k == "smelt":
                    m = re.search(r"smelted (\d+)", s["text"])
                    yields[k].append(int(m.group(1)) if m else 0)
    skills = {}
    for k, c in sorted(res.items()):
        n = sum(c.values())
        fails = {r: v for r, v in c.items() if r != "OK"}
        af = after_fail[k]
        # a fresh attempt: not right after the same skill failed (its target new, not the one out of reach)
        fresh_n, fresh_fail = n - af[0], sum(fails.values()) - af[1]
        skills[k] = {"n": n, "fail": round(1 - c["OK"] / n, 3), "reasons": dict(sorted(fails.items(), key=lambda x: -x[1])),
                     "fail_after_fail": round(af[1] / af[0], 3) if af[0] >= 10 else None, "after_fail_n": af[0],
                     "fail_fresh": round(fresh_fail / fresh_n, 3) if fresh_n else None}
        if yields.get(k):
            y = np.array(yields[k])
            skills[k]["yield_mean"] = round(float(y.mean()), 2)
            skills[k]["yield_zero"] = round(float((y == 0).mean()), 3)
    for k in ("mine_coal", "mine_iron"):
        if yields.get(k):
            skills[k] = {"yield_mean": round(float(np.mean(yields[k])), 3), "yield_nonzero": round(float(np.mean(np.array(yields[k]) > 0)), 3)}

    # durations: an episode's game seconds over its skills' counts (crew of one; ok and failed apart)
    # the skills' counts are collinear (a chop and its crafts come together): a ridge toward the v1 simulator's
    # durations (PRIOR, game seconds) keeps the fit sane; the prior weighs as `--prior` episodes
    one = [e for e in eps if e["crew"] == 1 and e["steps"]]
    def col(s):   # a chop that is OK but brings no log (the tree's logs not picked up) is a column of its own
        if s["result"] != "OK":
            return s["skill"], "fail"
        if s["skill"] == "chop_tree" and not s["got"].get("log"):
            return s["skill"], "ok_empty"
        return s["skill"], "ok"
    cols = sorted({col(s) for e in one for s in e["steps"]})
    X = np.zeros((len(one), len(cols)))
    y = np.zeros(len(one))
    ci = {c: i for i, c in enumerate(cols)}
    for j, e in enumerate(one):
        for s in e["steps"]:
            X[j, ci[col(s)]] += 1
        y[j] = e["game_s"]
    from scipy.optimize import nnls
    keep = X.sum(0) >= 15   # too rare a column: left out (its time lands on the others)
    kc = [c for c, k in zip(cols, keep) if k]
    d0 = np.array([PRIOR.get(c[0], 3.0) if c[1] == "ok" else 2.0 for c in kc])
    lam = np.sqrt(a.prior * (X[:, keep].sum(0) / len(one)))   # a column's prior as `prior` episodes' worth of it
    Xa = np.vstack([X[:, keep], np.diag(lam)])
    ya = np.concatenate([y, lam * d0])
    coef, _ = nnls(Xa, ya)
    raw_coef, _ = nnls(X[:, keep], y)
    dur, dur_raw = {}, {}
    for c, v, w in zip(kc, coef, raw_coef):
        dur.setdefault(c[0], {})[c[1]] = round(float(v), 1)
        dur_raw.setdefault(c[0], {})[c[1]] = round(float(w), 1)
    pred = X[:, keep] @ coef
    r2 = 1 - float(((y - pred) ** 2).sum() / ((y - y.mean()) ** 2).sum())
    decisions = [len(e["steps"]) / max(1, e["crew"]) for e in eps]
    per_dec = [e["game_s"] / max(1, len(e["steps"]) / e["crew"]) for e in eps]
    out = {"episodes": len(eps), "crew1_episodes": len(one), "skills": skills, "durations_s": dur, "durations_r2": round(r2, 3),
           "durations_s_no_prior": dur_raw,
           "game_s_per_decision_median": round(float(np.median(per_dec)), 1), "decisions_per_episode_median": float(np.median(decisions)),
           "success_by_scenario": {k: round(float(np.mean([e["reason"] == "goal" for e in eps if e["scenario"] == k])), 3)
                                   for k in sorted({e["scenario"] for e in eps})}}
    Path(a.out).parent.mkdir(parents=True, exist_ok=True)
    Path(a.out).write_text(json.dumps(out, indent=1, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(out, indent=1, ensure_ascii=False))


if __name__ == "__main__":
    main()
