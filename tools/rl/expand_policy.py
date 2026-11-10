"""A net of one contract as the start of the next one's (v3 -> v3.1: docs/rl/CONTRACT-v2.md, 11): the inputs and the
actions matched by name, so the old ones keep their weights wherever the new spaces put them; a new input's column
in the first layers (actor and critic) is zero, so it changes nothing until training moves it; a new action's row of
the output layer is a fresh one (orthogonal, gain 0.01, as Policy makes it) with a bias of --new-bias (2.0: the v3 net's
logits are sharp, a log-sum-exp of the allowed ones about 5.7 on the simulator's states; at bias 0 the new actions got 3 %
of the choices, at 2 about a tenth, so they are tried from the first epochs). The observation's running norm:
the old features' mean and variance carried, the new ones' 0 and 1, its count capped (--norm-count) so the new
features' statistics are learned in the first epochs.

    python tools/rl/expand_policy.py --ckpt build/rl/runs/v3real1/final.pt --from rl/v3 --to rl --out build/rl/init/v31-from-v3real1.pt
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

import torch
from torch import nn

sys.path.insert(0, str(Path(__file__).resolve().parent))
from policy import Policy  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]


def spec_of(d: Path) -> dict:
    a = json.loads((Path(d) / "actions.json").read_text(encoding="utf-8"))
    o = json.loads((Path(d) / "obs.json").read_text(encoding="utf-8"))
    return {"actions": [x["id"] for x in a["actions"]], "features": [f["name"] for f in o["features"]], "version": a.get("version")}


@torch.no_grad()
def expand(old: Policy, src: dict, dst: dict, norm_count: float = 2e5, seed: int = 1, new_bias: float = 0.0) -> Policy:
    assert old.d == len(src["features"]) and old.a == len(src["actions"]), (old.d, old.a)
    torch.manual_seed(seed)
    new = Policy(len(dst["features"]), len(dst["actions"]), old.hidden)
    fi = [(i, dst["features"].index(n)) for i, n in enumerate(src["features"]) if n in dst["features"]]
    ai = [(i, dst["actions"].index(n)) for i, n in enumerate(src["actions"]) if n in dst["actions"]]
    o_idx, n_idx = torch.tensor([p[0] for p in fi]), torch.tensor([p[1] for p in fi])
    new.norm.mean.zero_()
    new.norm.var.fill_(1.0)
    new.norm.mean[n_idx] = old.norm.mean[o_idx]
    new.norm.var[n_idx] = old.norm.var[o_idx]
    new.norm.count.fill_(min(float(old.norm.count), norm_count))
    for net_new, net_old in ((new.actor, old.actor), (new.critic, old.critic)):
        l0n, l0o = net_new[0], net_old[0]
        l0n.weight.zero_()
        l0n.weight[:, n_idx] = l0o.weight[:, o_idx]
        l0n.bias.copy_(l0o.bias)
        net_new[2].weight.copy_(net_old[2].weight)
        net_new[2].bias.copy_(net_old[2].bias)
    pn, po = new.actor[4], old.actor[4]
    fresh = torch.empty(new.a, new.hidden)
    nn.init.orthogonal_(fresh, 0.01)
    pn.weight.copy_(fresh)
    pn.bias.fill_(new_bias)
    for i, j in ai:
        pn.weight[j] = po.weight[i]
        pn.bias[j] = po.bias[i]
    new.critic[4].weight.copy_(old.critic[4].weight)
    new.critic[4].bias.copy_(old.critic[4].bias)
    return new.eval()


def load(path: str) -> Policy:
    ck = torch.load(path, map_location="cpu", weights_only=False)
    p = Policy(ck["d"], ck["a"], ck["hidden"])
    p.load_state_dict(ck["model"])
    return p


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--ckpt", required=True, help="the old contract's checkpoint (train.py's final.pt)")
    ap.add_argument("--from", dest="src", default=str(ROOT / "rl" / "v3"))
    ap.add_argument("--to", dest="dst", default=str(ROOT / "rl"))
    ap.add_argument("--out", required=True)
    ap.add_argument("--norm-count", type=float, default=2e5)
    ap.add_argument("--new-bias", type=float, default=2.0, help="the new actions' output bias")
    a = ap.parse_args()
    src, dst = spec_of(Path(a.src)), spec_of(Path(a.dst))
    old = load(a.ckpt)
    new = expand(old, src, dst, a.norm_count, new_bias=a.new_bias)
    same_f = sum(1 for n in src["features"] if n in dst["features"])
    same_a = sum(1 for n in src["actions"] if n in dst["actions"])
    first = dst["actions"][:len(src["actions"])] == src["actions"]
    out = Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    torch.save({"model": new.state_dict(), "opt": None, "d": new.d, "a": new.a, "hidden": new.hidden, "from": a.ckpt,
                "from_version": src["version"], "version": dst["version"]}, out)
    print(f"{a.ckpt} ({old.d} features, {old.a} actions) -> {out} ({new.d}, {new.a}): {same_f} features and {same_a} actions carried "
          f"(the first {len(src['actions'])} actions in the same order: {first}); {new.d - same_f} inputs and {new.a - same_a} actions new; "
          f"norm count {float(old.norm.count):.0f} -> {float(new.norm.count):.0f}; the new actions' bias {a.new_bias}")
    assert math.isfinite(float(sum(p.abs().sum() for p in new.parameters())))
