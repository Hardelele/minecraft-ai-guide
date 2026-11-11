"""The open world's spaces for the trainer (rl/open.json, OpenSpec.java): shapes, the action heads, one-hots, and the
indices of the vector the curiosity's phase mask reads."""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import torch

SPEC = json.loads((Path(__file__).resolve().parents[3] / "rl" / "open.json").read_text(encoding="utf-8"))
SHAPE = (SPEC["cube"]["y"], SPEC["cube"]["z"], SPEC["cube"]["x"])
CELLS = SHAPE[0] * SHAPE[1] * SHAPE[2]
NBLOCK = len(SPEC["blocks"])
NITEM = len(SPEC["items"])
VEC = len(SPEC["vec"])
ENTS, ENT_F = SPEC["ents"], SPEC["ent_features"]
HOT = SPEC["hotbar"]
SIZES = SPEC["heads"]["sizes"]
ACT_DIM = sum(SIZES)
VI = {k: i for i, k in enumerate(SPEC["vec"])}
NIGHT, WAVE = VI["night"], VI["phase_wave"]


def onehot(a: torch.Tensor) -> torch.Tensor:
    """(…, 5) ints -> (…, ACT_DIM) one-hots of the heads"""
    return torch.cat([(a[..., i].long().unsqueeze(-1) == torch.arange(n, device=a.device)).float() for i, n in enumerate(SIZES)], -1)   # (no host check: a graph captures it)


def from_strings(o: dict) -> dict:
    """a raw observation (the protocol's or a recorded frame's: strings and lists) -> numpy arrays"""
    vox = np.frombuffer(o["vox"].encode("ascii"), np.uint8) - ord("A")
    seen = np.frombuffer(o["seen"].encode("ascii"), np.uint8) == ord("1")
    return {"vox": vox.reshape(SHAPE).astype(np.uint8), "seen": seen.reshape(SHAPE), "vec": np.asarray(o["vec"], np.float32),
            "ent": np.asarray(o["ent"], np.float32).reshape(ENTS, ENT_F), "hot": np.asarray(o["hot"], np.int64)}


def stack(obs: list[dict], device) -> dict:
    """a list of decoded observations -> a batch of tensors"""
    return {k: torch.as_tensor(np.stack([o[k] for o in obs]), device=device) for k in ("vox", "seen", "vec", "ent", "hot")}
