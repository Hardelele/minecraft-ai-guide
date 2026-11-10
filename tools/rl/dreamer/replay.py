"""Sequences of steps in memory (flat ring arrays; a sample is T steps of one stream, never across two streams)."""
from __future__ import annotations

import numpy as np

from . import spaces as S


class Replay:
    def __init__(self, capacity=1_000_000, seed=0):
        self.n = capacity
        self.vox = np.zeros((capacity, S.CELLS), np.uint8)
        self.seen = np.zeros((capacity, (S.CELLS + 7) // 8), np.uint8)
        self.vec = np.zeros((capacity, S.VEC), np.float16)
        self.ent = np.zeros((capacity, S.ENTS * S.ENT_F), np.float16)
        self.hot = np.zeros((capacity, S.HOT), np.int8)
        self.mask = np.zeros((capacity, S.SIZES[4]), bool)
        self.act = np.zeros((capacity, 5), np.int8)        # the action taken at this obs
        self.rew = np.zeros(capacity, np.float32)          # the reward that came with this obs (of the action before it)
        self.cont = np.ones(capacity, np.float32)          # 0: this obs ends the episode (not by time)
        self.first = np.zeros(capacity, bool)
        self.stream = np.full(capacity, -1, np.int64)      # which stream (an agent's episode)
        self.rew_ok = np.zeros(capacity, bool)             # the reward known (a teacher's record: no)
        self.bc = np.zeros(capacity, bool)                 # a teacher's label to imitate
        self.i = 0
        self.size = 0
        self.rng = np.random.default_rng(seed)

    def add(self, stream, o, act, rew, cont, first, mask=None, rew_ok=True, bc=False):
        k = self.i
        self.vox[k] = o["vox"].reshape(-1)
        self.seen[k] = np.packbits(o["seen"].reshape(-1))
        self.vec[k] = o["vec"]
        self.ent[k] = o["ent"].reshape(-1)
        self.hot[k] = o["hot"]
        self.mask[k] = True if mask is None else mask
        self.act[k] = act
        self.rew[k] = rew
        self.cont[k] = cont
        self.first[k] = first
        self.stream[k] = stream
        self.rew_ok[k] = rew_ok
        self.bc[k] = bc
        self.i = (self.i + 1) % self.n
        self.size = min(self.size + 1, self.n)

    def end(self, stream, rew, cont):
        """an episode's end with no observation after it: its last reward and end go to the stream's last step"""
        k = (self.i - 1) % self.n
        if self.stream[k] == stream:
            self.rew[k] += rew
            self.cont[k] = cont

    def sample(self, B, T, only_bc=False):
        """B sequences of T steps, each within one stream and written in order (the ring's newest edge avoided)"""
        idx = []
        tries = 0
        cand = np.flatnonzero(self.bc[:self.size]) if only_bc else None
        while len(idx) < B and tries < B * 50:
            tries += 1
            s = int(self.rng.choice(cand)) if only_bc else int(self.rng.integers(0, self.size))
            s = max(0, s - int(self.rng.integers(0, T)))
            e = s + T
            if e > self.size or (self.size == self.n and s <= self.i < e):
                continue
            if self.stream[s] != self.stream[e - 1]:
                continue
            idx.append(np.arange(s, e))
        if not idx:
            return None
        ix = np.stack(idx)
        first = self.first[ix].copy()
        first[:, 0] = True
        seen = np.unpackbits(self.seen[ix], axis=-1)[..., :S.CELLS].astype(bool)
        prev = np.zeros_like(self.act[ix])
        prev[:, 1:] = self.act[ix][:, :-1]
        return {"vox": self.vox[ix].reshape(*ix.shape, *S.SHAPE), "seen": seen.reshape(*ix.shape, *S.SHAPE), "vec": self.vec[ix].astype(np.float32),
                "ent": self.ent[ix].astype(np.float32).reshape(*ix.shape, S.ENTS, S.ENT_F), "hot": self.hot[ix].astype(np.int64),
                "mask": self.mask[ix], "act": self.act[ix].astype(np.int64), "prev": prev.astype(np.int64), "rew": self.rew[ix],
                "cont": self.cont[ix], "first": first, "rew_ok": self.rew_ok[ix], "bc": self.bc[ix]}
