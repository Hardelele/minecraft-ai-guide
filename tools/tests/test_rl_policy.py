"""The crew policy's checks (tools/rl): the spaces read from rl/*.json, the mask never broken, PPO learning a toy
task, the simulator's recipes and rewards, the weights' export read back, a short training end to end. Writes
build/rl/obs-mask-cases.json: raw facts with the vector and mask Python gives, for PolicyTest (Java's RlSpec)."""
import json
import random
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
import torch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "rl"))
import spec  # noqa: E402
from policy import Policy  # noqa: E402
from sim_env import SimEnv  # noqa: E402

torch.set_num_threads(2)
bad = 0


def check(ok, what):
    global bad
    print(("ok   " if ok else "FAIL ") + what)
    if not ok:
        bad += 1


def spaces():
    obs = json.loads((ROOT / "rl" / "obs.json").read_text(encoding="utf-8"))
    act = json.loads((ROOT / "rl" / "actions.json").read_text(encoding="utf-8"))
    check(spec.D == obs["dim"] == len(obs["features"]), f"obs: {spec.D} features as rl/obs.json says")
    check(spec.A == act["n"] == len(act["actions"]) and spec.ACTIONS[0] == "noop", f"actions: {spec.A}, noop first")
    check(set(spec.SCEN) == set(json.loads((ROOT / "rl" / "reward.json").read_text(encoding="utf-8"))["scenarios"]),
          "the simulator's scenarios are rl/env's")
    for k in ("noop", "time", "skill_fail", "skill_ok", "goal", "wave_cleared"):
        check(k in spec.WEIGHTS, "reward weight " + k)


def sim_states(n=400, seed=5):
    """raw facts of the simulator's states under a random policy, all scenarios and crews"""
    env = SimEnv(seed)
    rng = random.Random(seed)
    out = []
    scen = list(spec.SCEN)
    while len(out) < n:
        o = env.reset(scen[len(out) % len(scen)], rng.randrange(1 << 30), crew=1 + len(out) % 4)
        for _ in range(rng.randint(1, 40)):
            if o["done"]:
                break
            out += o["raw"]
            acts = {i: rng.choice([k for k, v in enumerate(m) if v and k]) for i, m in zip(o["agents"], o["mask"])}
            o = env.step(acts)
    return out[:n]


def masking():
    torch.manual_seed(0)
    pol = Policy(spec.D, spec.A, 64)
    raws = sim_states(300)
    obs = torch.tensor([spec.vector(r) for r in raws], dtype=torch.float32)
    mask = torch.tensor([spec.mask(r) for r in raws])
    check(bool(mask[:, 0].all()), "noop is always allowed")
    with torch.no_grad():
        pol.actor[4].bias[:] = 0
        pol.actor[4].bias[torch.randperm(spec.A)[:10]] = 50.0   # strong preferences, many of them for forbidden actions
        ok = True
        for _ in range(20):
            a, lp, _, _ = pol.act(obs, mask)
            ok &= bool(mask[torch.arange(len(a)), a].all()) and bool(torch.isfinite(lp).all())
        g, _, _, _ = pol.act(obs, mask, greedy=True)
        ok &= bool(mask[torch.arange(len(g)), g].all())
        lp, ent, _ = pol.evaluate(obs, mask, g)
    check(ok, "a masked action is never chosen (6000 draws, the forbidden ones favoured by the logits)")
    check(bool(torch.isfinite(ent).all()) and bool(torch.isfinite(lp).all()), "log-probs and entropy finite under the mask")
    cases = [{"raw": r, "obs": spec.vector(r), "mask": [int(x) for x in spec.mask(r)]} for r in raws]
    p = ROOT / "build" / "rl" / "obs-mask-cases.json"
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps({"obs_hash": spec.OBS_HASH, "actions_hash": spec.ACTIONS_HASH, "cases": cases}), encoding="utf-8")
    print("     wrote", p, len(cases), "cases for PolicyTest")


def ppo_toy():
    """a contextual bandit: 8 contexts, the right action depends on it, half the actions masked at random"""
    from train import ppo_loss
    torch.manual_seed(1)
    rng = np.random.default_rng(1)
    d, a = 8, 6
    pol = Policy(d, a, 32)
    opt = torch.optim.Adam(pol.parameters(), lr=3e-3)
    right = rng.integers(0, a, d)
    acc = 0.0
    for it in range(150):
        ctx = rng.integers(0, d, 256)
        obs = torch.tensor(np.eye(d, dtype=np.float32)[ctx])
        m = rng.random((256, a)) < 0.5
        m[np.arange(256), right[ctx]] = True
        m[:, 0] = True
        mask = torch.tensor(m)
        pol.norm.update(obs)
        with torch.no_grad():
            act, lp, v, _ = pol.act(obs, mask)
        r = (act.numpy() == right[ctx]).astype(np.float32) * 2 - 1
        ret = torch.tensor(r)
        adv = ret - v
        adv = (adv - adv.mean()) / (adv.std() + 1e-8)
        for _ in range(4):
            loss, *_ = ppo_loss(pol, obs, mask, act, lp, adv, ret, 0.2, 0.5, 0.01)
            opt.zero_grad()
            loss.backward()
            opt.step()
        acc = float((act.numpy() == right[ctx]).mean())
    with torch.no_grad():
        g = pol.act(obs, mask, greedy=True)[0].numpy()
    check(acc > 0.9 and (g == right[ctx]).mean() > 0.95, f"PPO learns a masked toy task (sampled {acc:.2f}, greedy {(g == right[ctx]).mean():.2f})")


def simulator():
    env = SimEnv(3)
    o = env.reset("pickaxe", 1, crew=1)
    a = env.agents[0]
    r = env.raw(a)
    m = spec.mask(r)
    check(not m[spec.IDX["craft:wooden_pickaxe"]] and not m[spec.IDX["mine:stone"]], "empty bag: no pickaxe to craft, no stone to mine")
    a.add("log", 2)
    m = spec.mask(env.raw(a))
    check(m[spec.IDX["craft:planks"]] and not m[spec.IDX["craft:crafting_table"]], "a log: planks yes, a table (4 planks) not yet")
    a.bag = {"planks": 3, "stick": 2}
    check(not spec.mask(env.raw(a))[spec.IDX["craft:wooden_pickaxe"]], "a pickaxe needs a table (3x3)")
    a.bag = {"planks": 3, "stick": 2, "crafting_table": 1}
    check(spec.mask(env.raw(a))[spec.IDX["craft:wooden_pickaxe"]], "with a table in the bag it may")
    o = env.step({0: spec.IDX["craft:wooden_pickaxe"]})
    check(a.n("wooden_pickaxe") == 1 and a.n("planks") == 0 and a.n("stick") == 0 and env.table, "the craft takes 3 planks and 2 sticks, puts the table down")
    check(o["reward"][0] > 0.4, f"a new tier and a new item are rewarded ({o['reward'][0]:.3f})")
    check(spec.mask(env.raw(a))[spec.IDX["mine:stone"]], "a pickaxe: stone may be mined")
    o = env.step({0: spec.IDX["noop"]})
    check(o["done"] and o["info"]["done_reason"] == "noop" and o["reward"][0] <= -0.99, "noop: -1 and the episode ends")
    env.reset("wood", 2, crew=1)
    env.agents[0].add("log", 9)
    o = env.step({0: spec.IDX["chop_tree:near"]} if spec.mask(env.raw(env.agents[0]))[spec.IDX["chop_tree:near"]] else {0: spec.IDX["look_around"]})
    while not o["done"]:
        m = o["mask"][0]
        o = env.step({o["agents"][0]: spec.IDX["chop_tree:near"] if m[spec.IDX["chop_tree:near"]] else spec.IDX["go_to:frontier"]})
    check(o["info"]["done_reason"] in ("goal", "time"), "wood ends by its goal or its time")
    if o["info"]["done_reason"] == "goal":
        check(o["info"]["episode_components"].get("goal", 0) >= 5, "the goal gives +5")
    env.reset("food", 4, crew=1)
    a = env.agents[0]
    a.bag = {"iron_ore": 1}
    o = env.step({0: spec.IDX["smelt:iron_ingot"]})
    check(a.n("iron_ingot") == 0 and o["info"]["t"] == 0.0, "a masked action fails at once, nothing done (MASKED)")
    check(env.agents[0].outcome == "fail_other", "MASKED counts as a failure of the other kind")
    env.reset("waves:easy", 7, crew=3)
    check(env.phase == "prep" and env.chest_food == 16 and all(x.n("stone_sword") == 1 for x in env.agents), "a waves run starts with its kit and chest")


def export():
    from export_policy import read_policy, write_policy
    torch.manual_seed(2)
    p = Policy(spec.D, spec.A)
    p.norm.update(torch.randn(100, spec.D) * 3 + 1)
    with tempfile.TemporaryDirectory() as t:
        f = Path(t) / "p.bin"
        write_policy(p, f)
        q = read_policy(f)
        x = torch.randn(50, spec.D)
        with torch.no_grad():
            check(torch.allclose(p.logits(x), q.logits(x), atol=1e-5), "policy.bin read back gives the same logits")
        n = 4 * 5 + sum(len(s) + 4 + 4 + 4 * len(tt.shape) + 4 * tt.numel() for s, tt in
                        [(nm, t2) for nm, t2 in __import__("policy").tensors(p)])
        check(f.stat().st_size == 4 + 16 + 4 + (n - 20), f"policy.bin is {f.stat().st_size} bytes as the format says")


def end_to_end():
    run = "test-e2e"
    r = subprocess.run([sys.executable, str(ROOT / "tools" / "rl" / "train.py"), "--run", run, "--minutes", "2", "--max-epochs", "3",
                        "--envs", "8", "--workers", "1", "--rollout", "512", "--minibatch", "256"], capture_output=True, text=True, timeout=600)
    out = ROOT / "build" / "rl" / "runs" / run
    rows = (out / "metrics.csv").read_text(encoding="utf-8").strip().splitlines() if (out / "metrics.csv").exists() else []
    check(r.returncode == 0 and len(rows) == 4, f"train.py runs 3 epochs end to end ({len(rows) - 1 if rows else 0} rows)")
    check((out / "snapshots" / "policy-epoch-1.bin").exists() and (out / "final.bin").exists(), "a snapshot after epoch 1 and the final weights")
    if r.returncode:
        print(r.stdout[-2000:], r.stderr[-2000:])


if __name__ == "__main__":
    if "--cases-only" in sys.argv:
        masking()
        sys.exit(1 if bad else 0)
    spaces()
    masking()
    ppo_toy()
    simulator()
    export()
    end_to_end()
    sys.exit(1 if bad else 0)
