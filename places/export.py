"""Write places.bin (weights) and places-test.bin (one cube, its logits and bottleneck) by places/CONTRACT.md.

    python places/export.py                    # random weights (seed 1), for the Java side to check against
    python places/export.py --ckpt build/places/ckpt/best.pt
"""
from __future__ import annotations

import argparse
import struct
import sys
import time
from pathlib import Path

import numpy as np
import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))
from model import C_IN, K, SIDE, PlacesNet, tensor_names  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
WEIGHTS = ROOT / "res" / "assets" / "aiguide" / "places" / "places.bin"
TEST = ROOT / "places" / "places-test.bin"


def write_tensors(f, magic: bytes, header: list[int], tensors: list[tuple[str, np.ndarray]]):
    f.write(magic)
    f.write(struct.pack("<" + "i" * len(header), *header))
    f.write(struct.pack("<i", len(tensors)))
    for name, a in tensors:
        a = np.ascontiguousarray(a, dtype="<f4")
        nb = name.encode("utf-8")
        f.write(struct.pack("<i", len(nb)))
        f.write(nb)
        f.write(struct.pack("<i", a.ndim))
        f.write(struct.pack("<" + "i" * a.ndim, *a.shape))
        f.write(a.tobytes())


def read_tensors(path: Path) -> tuple[bytes, list[int], dict[str, np.ndarray]]:
    data = path.read_bytes()
    magic = data[:4]
    pos = 4
    nhead = 3 if magic == b"PLC1" else 1
    header = list(struct.unpack_from("<" + "i" * nhead, data, pos))
    pos += 4 * nhead
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


def load_weights(path: Path) -> PlacesNet:
    magic, header, t = read_tensors(path)
    assert magic == b"PLC1" and header[0] == 1
    net = PlacesNet(header[1], header[2])
    with torch.no_grad():
        for name, p in tensor_names(net):
            p.copy_(torch.from_numpy(t[name]))
    return net


def test_cube(seed: int = 7) -> np.ndarray:
    """A one-hot cube with some structure (a floor, a box, a tunnel, unseen corner), so every layer works."""
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    try:
        from synth import sample_house_scene
        from channels import onehot
        rng = np.random.default_rng(seed)
        ch, _ = sample_house_scene(rng)
        x = onehot(ch)
        x[:, 24:, 24:, :] = 0
        x[1, 24:, 24:, :] = 1   # unseen corner
        return x[None]
    except ImportError:
        rng = np.random.default_rng(seed)
        idx = rng.integers(0, C_IN, (SIDE, SIDE, SIDE))
        idx[:, :10, :] = 5
        idx[:, 10:, :] = 0
        idx[8:20, 10:16, 8:20] = 9
        idx[9:19, 11:15, 9:19] = 0
        x = np.zeros((1, C_IN, SIDE, SIDE, SIDE), np.float32)
        np.put_along_axis(x[0], idx[None], 1.0, 0)
        return x


def export(net: PlacesNet, weights: Path = WEIGHTS, test: Path = TEST):
    net = net.float().cpu().eval()
    weights.parent.mkdir(parents=True, exist_ok=True)
    with open(weights, "wb") as f:
        write_tensors(f, b"PLC1", [1, C_IN, K], [(n, p.detach().numpy()) for n, p in tensor_names(net)])
    x = test_cube()
    with torch.no_grad():
        y, b = net(torch.from_numpy(x), with_bottleneck=True)
    with open(test, "wb") as f:
        write_tensors(f, b"PLT1", [1], [("input", x), ("logits", y.numpy()), ("bottleneck", b.numpy())])
    # read back and recompute
    net2 = load_weights(weights)
    with torch.no_grad():
        y2 = net2(torch.from_numpy(read_tensors(test)[2]["input"]))
    assert torch.allclose(y, y2, atol=1e-6)
    print(f"{weights} {weights.stat().st_size} bytes, {sum(p.numel() for p in net.parameters())} params")
    print(f"{test} {test.stat().st_size} bytes")


def cpu_speed(net: PlacesNet, n: int = 20) -> float:
    torch.set_num_threads(1)
    net = net.float().cpu().eval()
    x = torch.from_numpy(test_cube())
    with torch.no_grad():
        for _ in range(3):
            net(x)
        t = time.perf_counter()
        for _ in range(n):
            net(x)
    return (time.perf_counter() - t) / n


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--ckpt")
    ap.add_argument("--speed", action="store_true")
    a = ap.parse_args()
    net = PlacesNet()
    if a.ckpt:
        net.load_state_dict(torch.load(a.ckpt, map_location="cpu")["model"])
    else:
        # He init keeps activations O(1) through the net, so a 1e-4 check against Java means something
        torch.manual_seed(1)
        net = PlacesNet()
        with torch.no_grad():
            for name, p in tensor_names(net):
                if name.endswith(".w"):
                    torch.nn.init.kaiming_normal_(p, nonlinearity="relu")
                else:
                    p.uniform_(-0.1, 0.1)
    export(net)
    if a.speed:
        print(f"CPU 1 thread: {cpu_speed(net) * 1000:.1f} ms per cube")
