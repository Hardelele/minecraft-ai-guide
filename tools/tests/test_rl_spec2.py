"""Contract 2's spaces across Java and Python (docs/rl/CONTRACT-v2.md): the versions, the first 38 actions are
contract 1's, the hashes, and the masks — Java (RlSpec.cases: random raw facts round the rules' thresholds with
Java's mask) against Python reading the same rules from rl/actions.json (tools/rl/mask_rules.py); a negative
control: two actions' rules swapped must disagree. Also the reward file: every scenario's scales are scales, every
scale has weights, the achievements' tree is whole."""
import json
import os
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "rl"))
sys.path.insert(0, str(ROOT / "tools"))
import mask_rules  # noqa: E402
import gen_spec  # noqa: E402

bad = 0


def check(ok, what):
    global bad
    print(("ok   " if ok else "FAIL ") + what)
    if not ok:
        bad += 1


def load(name, sub=""):
    return json.loads((ROOT / "rl" / sub / name).read_text(encoding="utf-8"))


def main():
    act, obs, rew, ach = load("actions.json"), load("obs.json"), load("reward.json"), load("achievements.json")
    a1, o1 = load("actions.json", "v1"), load("obs.json", "v1")
    check(act["version"] == obs["version"] == rew["version"] == ach["version"] == 2, "the files are of contract 2")
    check(a1["version"] == o1["version"] == 1, "rl/v1 is contract 1")
    ids = [a["id"] for a in act["actions"]]
    check(ids[0] == "noop" and len(ids) == act["n"] == len(set(ids)) == 82, "82 actions, noop first, unique")
    check(ids[:38] == [a["id"] for a in a1["actions"]] and act["v1_prefix"] == 38, "the first 38 are contract 1's, in its order")
    check(all(a["args"] == b["args"] and a["tool"] == b["tool"] for a, b in zip(act["actions"], a1["actions"])), "with the same tools and arguments")
    check(all("mask" in a for a in act["actions"]), "every action of contract 2 carries its rule")
    check(obs["dim"] == len(obs["features"]) == 322, "322 features")
    names = [f["name"] for f in obs["features"]]
    check(all(n in names for n in [f["name"] for f in o1["features"] if f["name"] != "scen_siege"]), "contract 1's features are among contract 2's")

    # the masks: Java's against Python's reading of the same rules
    gen_spec.compile_spec()
    with tempfile.TemporaryDirectory() as t:
        p = os.path.join(t, "cases.json")
        gen_spec.cases(p, 3000)
        cases = json.loads(Path(p).read_text(encoding="utf-8"))
    check(cases["actions_hash"] == act["hash"], "Java's cases are of rl/actions.json's actions")
    differ = 0
    ones = [0] * len(ids)
    for c in cases["cases"]:
        m = mask_rules.mask(act["actions"], c["raw"])
        if m != c["mask"]:
            differ += 1
            if differ <= 3:
                print("     differs:", [ids[i] for i in range(len(ids)) if m[i] != c["mask"][i]])
        for i, x in enumerate(m):
            ones[i] += x
    check(differ == 0, "Python's mask = Java's on %d random states" % len(cases["cases"]))
    never = [ids[i] for i in range(len(ids)) if ones[i] == 0]
    check(not never, "every action is allowed somewhere in the cases: %s" % never)
    # the negative control: chop_tree's and mine:stone's rules swapped
    swapped = [dict(a) for a in act["actions"]]
    i, j = ids.index("chop_tree:near"), ids.index("mine:stone")
    swapped[i]["mask"], swapped[j]["mask"] = swapped[j]["mask"], swapped[i]["mask"]
    wrong = sum(1 for c in cases["cases"] if mask_rules.mask(swapped, c["raw"]) != c["mask"])
    check(wrong > 100, "two rules swapped are caught: %d states differ" % wrong)

    # the reward file
    scales = set(rew["scales"])
    comps = {k.split(".")[0] for k in rew["weights"]}
    check(scales <= comps, "every scale has its weights")
    check(set(rew["scenarios"]) == set(rew["scenarioScales"]) == set(rew["scenario"]), "every scenario has its scales and its description")
    bad_keys = [(s, k) for s, d in rew["scenarioScales"].items() for k in d if k.split(".")[0] not in scales or ("." in k and k not in rew["weights"])]
    check(not bad_keys, "the scenarios name scales and their parts only: %s" % bad_keys)
    check(rew["scenarioScales"]["pickaxe"].get("equipment.weapon", 0) == 0 and "safety" not in rew["scenarioScales"]["wood"], "pickaxe pays no sword, wood no wall")
    for k in ("tier_up", "new_item", "built.wall", "gathered.log", "ate"):
        check(k not in rew["weights"], "no " + k + " in contract 2 (the scales instead)")

    # the achievements
    a = {x["id"]: x for x in ach["achievements"]}
    check(len(a) == 33 and sum(1 for x in a.values() if x["supported"]) == 18, "33 achievements, 18 supported")
    check(all(x.get("parent") is None or x["parent"] in a for x in a.values()), "every parent is in the tree")
    check(all(not x["supported"] or x.get("parent") is None or a[x["parent"]]["supported"] for x in a.values()), "a supported one's parent is supported")
    sup = [x["id"] for x in a.values() if x["supported"]]
    check(all("ach_" + s in names and "ach_target_" + s in names for s in sup), "a feature for each supported achievement")
    print("test_rl_spec2 " + ("PASS" if bad == 0 else "FAIL (%d)" % bad))
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
