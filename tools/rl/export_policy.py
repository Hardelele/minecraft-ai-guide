"""policy.bin (the actor's weights and the obs normalisation) and policy-test.bin (obs, masks, the logits PyTorch
gives, the masked argmax) for PolicyNet.java; the container is places' (places/export.py), with its own magic.

    python tools/rl/export_policy.py --ckpt build/rl/runs/<run>/final.pt            # -> res/assets/aiguide/rl/policy.bin, rl/policy-test.bin
    python tools/rl/export_policy.py --ckpt ... --out build/rl/snapshots/x.bin --no-test

policy.bin, little-endian:
    "RLP1", int32 version (rl/actions.json's: 1 the v1 spaces, 2 and 3 the v2 ones; the layout is the same), int32 D, int32 A, int32 H, int32 n_tensors=8, then each tensor as in places.bin:
    int32 len, UTF-8 name, int32 ndim, int32 shape[ndim], float32 data (row-major):
    obs_mean[D], obs_std[D], l1.w[H,D], l1.b[H], l2.w[H,H], l2.b[H], pi.w[A,H], pi.b[A]
    x = clip((obs - obs_mean) / obs_std, -10, 10); h1 = tanh(l1.w x + l1.b); h2 = tanh(l2.w h1 + l2.b); logits = pi.w h2 + pi.b
policy-test.bin: "RPT1", int32 version=1, n_tensors=4: obs[N,D], mask[N,A] (0/1), logits[N,A], action[N] (masked argmax)
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))


def _places_export():
    """places/export.py: the same container (its writer and reader), loaded by path (its name is common)."""
    import importlib.util
    p = Path(__file__).resolve().parents[2] / "places" / "export.py"
    s = importlib.util.spec_from_file_location("places_export", p)
    m = importlib.util.module_from_spec(s)
    sys.path.insert(0, str(p.parent))
    s.loader.exec_module(m)
    return m


_pe = _places_export()
read_tensors, write_tensors = _pe.read_tensors, _pe.write_tensors
import spec  # noqa: E402
from policy import Policy, tensors  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
WEIGHTS = ROOT / "res" / "assets" / "aiguide" / "rl" / "policy.bin"
TEST = ROOT / "rl" / "policy-test.bin"


def write_policy(p: Policy, path: Path):
    p = p.float().cpu().eval()
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "wb") as f:
        write_tensors(f, b"RLP1", [spec.VERSION, p.d, p.a, p.hidden], [(n, t.detach().numpy()) for n, t in tensors(p)])


def read_policy(path: Path) -> Policy:
    magic, head, t = read_tensors_any(path)
    assert magic == b"RLP1" and head[0] in (1, 2, 3), (magic, head)
    p = Policy(head[1], head[2], head[3])
    with torch.no_grad():
        l1, l2, l3 = p.actor[0], p.actor[2], p.actor[4]
        p.norm.mean.copy_(torch.from_numpy(t["obs_mean"]).double())
        p.norm.var.copy_(torch.from_numpy(t["obs_std"]).double() ** 2 - 1e-8)
        for lin, n in ((l1, "l1"), (l2, "l2"), (l3, "pi")):
            lin.weight.copy_(torch.from_numpy(t[n + ".w"]))
            lin.bias.copy_(torch.from_numpy(t[n + ".b"]))
    return p.eval()


def read_tensors_any(path: Path):
    """places' reader knows PLC1's 3 header ints; RLP1 has 4, RPT1 1."""
    import struct
    data = path.read_bytes()
    magic = data[:4]
    nhead = {b"RLP1": 4, b"RPT1": 1}.get(magic)
    if nhead is None:
        return read_tensors(path)
    header = list(struct.unpack_from("<" + "i" * nhead, data, 4))
    pos = 4 + 4 * nhead
    (n,) = struct.unpack_from("<i", data, pos)
    pos += 4
    out = {}
    for _ in range(n):
        (ln,) = struct.unpack_from("<i", data, pos)
        pos += 4
        name = data[pos:pos + ln].decode("utf-8")
        pos += ln
        (nd,) = struct.unpack_from("<i", data, pos)
        pos += 4
        shape = struct.unpack_from("<" + "i" * nd, data, pos)
        pos += 4 * nd
        cnt = int(np.prod(shape))
        out[name] = np.frombuffer(data, "<f4", cnt, pos).reshape(shape).copy()
        pos += 4 * cnt
    assert pos == len(data), "trailing bytes"
    return magic, header, out


def test_cases(n: int = 256, seed: int = 3):
    """Observations of the simulator's states under a random policy, with their masks."""
    from sim_env import SimEnv
    env = SimEnv(seed)
    rng = np.random.default_rng(seed)
    obs, masks = [], []
    while len(obs) < n:
        sc = list(spec.SCEN)[len(obs) % len(spec.SCEN)]
        o = env.reset(sc, int(rng.integers(1 << 30)), crew=1 + len(obs) % 4)
        while not o["done"] and len(obs) < n:
            acts = {}
            for i, v, m in zip(o["agents"], o["obs"], o["mask"]):
                obs.append(v)
                masks.append(m)
                ok = [k for k, x in enumerate(m) if x and k != 0]
                acts[i] = int(rng.choice(ok)) if ok else 0
            o = env.step(acts)
    return np.array(obs[:n], np.float32), np.array(masks[:n], bool)


def load_ckpt(path: str) -> Policy:
    ck = torch.load(path, map_location="cpu", weights_only=False)
    p = Policy(ck["d"], ck["a"], ck["hidden"])
    p.load_state_dict(ck["model"])
    return p.eval()


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--ckpt")
    ap.add_argument("--out", default=str(WEIGHTS))
    ap.add_argument("--test", default=str(TEST))
    ap.add_argument("--no-test", action="store_true")
    a = ap.parse_args()
    if a.ckpt:
        pol = load_ckpt(a.ckpt)
    else:   # random weights, for the Java side's check
        torch.manual_seed(1)
        pol = Policy(spec.D, spec.A)
        pol.norm.update(torch.from_numpy(test_cases(512, 9)[0]))
    out = Path(a.out)
    write_policy(pol, out)
    print(out, out.stat().st_size, "bytes,", sum(t.numel() for _, t in tensors(pol)), "numbers")
    if not a.no_test:
        obs, mask = test_cases()
        with torch.no_grad():
            lg = pol.float().logits(torch.from_numpy(obs))
            act = Policy.masked(lg, torch.from_numpy(mask)).argmax(-1)
        tp = Path(a.test)
        with open(tp, "wb") as f:
            write_tensors(f, b"RPT1", [1], [("obs", obs), ("mask", mask.astype(np.float32)), ("logits", lg.numpy()),
                                            ("action", act.numpy().astype(np.float32))])
        back = read_policy(out)
        with torch.no_grad():
            lg2 = back.logits(torch.from_numpy(obs))
        print(tp, tp.stat().st_size, "bytes; read back: max diff", float((lg - lg2).abs().max()))
        assert torch.allclose(lg, lg2, atol=1e-5)
