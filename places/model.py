"""The places net: a 3D U-Net of depth 2, fixed by places/CONTRACT.md (the Java inference repeats it)."""
from __future__ import annotations

import torch
import torch.nn as nn
import torch.nn.functional as F

CHANNELS = ["air", "unseen", "water", "lava", "ground", "stone", "ore", "wood_natural", "plant",
            "built_solid", "door", "fence", "climb", "light", "storage", "other_solid"]
CLASSES = ["solid", "open_outside", "passage", "room", "cave_hall", "shaft", "junction", "built_structure"]
C_IN = len(CHANNELS)
K = len(CLASSES)
SIDE = 32


class Block(nn.Module):
    def __init__(self, cin: int, cout: int):
        super().__init__()
        self.c1 = nn.Conv3d(cin, cout, 3, padding=1)
        self.c2 = nn.Conv3d(cout, cout, 3, padding=1)

    def forward(self, x):
        return F.relu(self.c2(F.relu(self.c1(x))))


class PlacesNet(nn.Module):
    def __init__(self, c_in: int = C_IN, k: int = K):
        super().__init__()
        self.enc1 = Block(c_in, 16)
        self.enc2 = Block(16, 32)
        self.bott = Block(32, 64)
        self.dec2 = Block(96, 32)
        self.dec1 = Block(48, 16)
        self.head = nn.Conv3d(16, k, 1)

    def forward(self, x, with_bottleneck: bool = False):
        e1 = self.enc1(x)
        e2 = self.enc2(F.max_pool3d(e1, 2))
        b = self.bott(F.max_pool3d(e2, 2))
        d2 = self.dec2(torch.cat([F.interpolate(b, scale_factor=2, mode="nearest"), e2], 1))
        d1 = self.dec1(torch.cat([F.interpolate(d2, scale_factor=2, mode="nearest"), e1], 1))
        y = self.head(d1)
        return (y, b) if with_bottleneck else y


def tensor_names(net: PlacesNet) -> list[tuple[str, torch.Tensor]]:
    """The tensors in the file order of the contract: enc1.c1.w, enc1.c1.b, ..., head.w, head.b."""
    out = []
    for blk in ("enc1", "enc2", "bott", "dec2", "dec1"):
        for c in ("c1", "c2"):
            conv = getattr(getattr(net, blk), c)
            out.append((f"{blk}.{c}.w", conv.weight))
            out.append((f"{blk}.{c}.b", conv.bias))
    out.append(("head.w", net.head.weight))
    out.append(("head.b", net.head.bias))
    return out
