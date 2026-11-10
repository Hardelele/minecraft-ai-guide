"""The open world's learner side with no game (docs/rl/OPEN-WORLD.md): the spaces as rl/open.json says, the observation's
strings decoded, the replay's sequences within one stream, the reward from frames by v4's core (death, achievement,
breach), the trainer's model: one update on the fake world, acting with the mask, imagination's curiosity off in the night.

    python tools/tests/test_open_world.py
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import torch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "rl"))
sys.path.insert(0, str(ROOT / "tools"))

from dreamer import spaces as S  # noqa: E402
from dreamer.agent import DEFAULT, Dreamer  # noqa: E402
from dreamer.fake_env import FakeEnv  # noqa: E402
from dreamer.replay import Replay  # noqa: E402

failed = 0


def check(what, ok):
    global failed
    print(("ok   " if ok else "FAIL ") + what)
    failed += 0 if ok else 1


def main():
    check("heads: move 9, jump 3, yaw 7, pitch 5, act 51 (15 + 36 recipes)", S.SIZES == [9, 3, 7, 5, 51] and S.ACT_DIM == 75)
    check("cube 8 x 11 x 11, 25 block classes, 136 vector features", S.SHAPE == (8, 11, 11) and S.NBLOCK == 25 and S.VEC == 136)
    vox = "".join(chr(ord("A") + (i % S.NBLOCK)) for i in range(S.CELLS))
    o = S.from_strings({"vox": vox, "seen": "1" * S.CELLS, "vec": [0.0] * S.VEC, "ent": [0.0] * (S.ENTS * S.ENT_F), "hot": [0] * S.HOT})
    check("the cube's string: index (y * Z + z) * X + x", o["vox"][0, 0, 3] == 3 and o["vox"][1, 0, 0] == (S.SHAPE[1] * S.SHAPE[2]) % S.NBLOCK and o["seen"].all())

    rep = Replay(1000)
    env = FakeEnv(seed=1, steps=60)
    for stream in (1, 2):
        a = env.reset(seed=stream)
        first = True
        while not a["done"]:
            act = [1, 0, 0, 0, 1]
            rep.add(stream, a["obs"]["1"], np.asarray(act), 0.0, 1.0, first, bc=True)
            first = False
            a = env.step({"1": act})
    b = rep.sample(8, 16)
    streams = rep.stream[:rep.size]
    ok = b is not None and b["first"][:, 0].all()
    check("replay: 8 sequences of 16, each from one stream, its first step a start", ok and len({int(streams[i]) for i in range(rep.size)}) == 2)

    # the reward: v4's core over frames (read from rl/reward-outcomes until v4 is merged)
    try:
        from open_reward import OpenReward
        r = OpenReward()
        ag = {"type": "agent", "agent": "1", "x": 0, "y": 64, "z": 0, "hp": 20, "alive": True, "base_dist": 0}
        p0 = r.frame({"t": 0.2, "dt": 0.2, "phase": "day", "events": [ag, {"type": "scale", "agent": "1", "key": "achievement.new", "delta": 1}]}, ["1"])["1"][0]
        p1 = r.frame({"t": 0.4, "dt": 0.2, "phase": "night", "events": [ag, {"type": "death", "agent": "1", "cause": "mob", "base_dist": 0}]}, ["1"])["1"][0]
        p2 = r.frame({"t": 0.6, "dt": 0.2, "phase": "wave", "events": [ag, {"type": "threat", "mob": 5, "kind": "zombie", "target": "base", "dmg": 3, "stay_s": 60},
                                                                       {"type": "breach", "mob": 5}]}, ["1"])["1"][0]
        p3 = r.frame({"t": 0.8, "dt": 0.2, "phase": "day", "events": [ag, {"type": "death", "agent": "1", "cause": "inFire", "base_dist": 0}]}, ["1"])["1"][0]
        check(f"reward: an achievement +0.3 ({p0:.2f}), a death -1 ({p1:.2f}), a breach -0.5 ({p2:.2f}), death by fire an accident x1.5 ({p3:.2f})",
              abs(p0 - 0.3) < 1e-6 and abs(p1 + 1) < 1e-6 and abs(p2 + 0.5) < 1e-6 and abs(p3 + 1.5) < 1e-6)
    except Exception as e:   # (no git, no branch)
        check("reward: v4's core loaded (" + repr(e) + ")", False)

    torch.manual_seed(0)
    cfg = dict(DEFAULT, embed=64, deter=64, stoch=4, classes=4, hidden=64, imag_starts=64, horizon=5)
    agent = Dreamer(cfg, "cpu")
    m = agent.train_step(b, bc_batch=b, bc_weight=1.0)
    check("one update on the fake world: the losses are numbers", all(np.isfinite(v) for v in m.values()) and "bc/nll" in m and "ac/ret" in m)
    obs = S.stack([b and env.reset(seed=3)["obs"]["1"]] * 3, "cpu")
    mask = torch.zeros(3, S.SIZES[4], dtype=torch.bool)
    mask[:, 1] = True
    acts, _ = agent.act(obs, agent.initial(3), torch.zeros(3, 5, dtype=torch.long), torch.ones(3, dtype=torch.bool), mask)
    check("acting: five heads in range, the act head only where the mask allows", acts.shape == (3, 5) and (acts[:, 4] == 1).all()
          and all((acts[:, i] < n).all() for i, n in enumerate(S.SIZES)))
    f = torch.zeros(4, 2, agent.rssm.feat_dim)
    a = torch.zeros(3, 2, 5, dtype=torch.long)
    with torch.no_grad():
        agent.dec.vec[-1].bias.zero_()
        agent.dec.vec[-1].weight.zero_()
        agent.dec.vec[-1].bias[S.NIGHT] = 2.0   # the decoded vector says: night (symlog 2 -> 6.4)
    cur, off = agent.curiosity(f, a)
    check("curiosity: nothing paid when the model sees the night", float(cur.abs().sum()) == 0.0 and float(off) == 1.0)
    print("test_open_world: %d failed" % failed)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
