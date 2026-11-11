"""A tiny stand-in of the open world with the same shapes and protocol (for the trainer's smoke test, no game): a flat
field with trees (two logs high), the agent turns by 90 degrees (yaw head ±90), steps forward, and chops the log in
front by holding attack for 2 steps; each log is a frame event of the achievement scale (0.3 points under v4's core).
"""
from __future__ import annotations

import numpy as np

from . import spaces as S

LOG = S.SPEC["blocks"].index("log")
DIRT = S.SPEC["blocks"].index("dirt")
AIR = S.SPEC["blocks"].index("air")
ATTACK = S.SPEC["heads"]["act"].index("attack")
YAW = S.SPEC["heads"]["yaw"]
FWD = [(0, 1), (-1, 0), (0, -1), (1, 0)]


class FakeEnv:
    def __init__(self, seed=0, size=15, trees=12, steps=200):
        self.rng = np.random.default_rng(seed)
        self.size, self.ntrees, self.max = size, trees, steps

    def reset(self, seed=None, **kw):
        if seed is not None:
            self.rng = np.random.default_rng(seed)
        n = self.size
        self.logs = np.zeros((n, n), np.int64)
        for _ in range(self.ntrees):
            x, z = self.rng.integers(0, n, 2)
            self.logs[x, z] = 2
        self.x = self.z = n // 2
        self.logs[self.x, self.z] = 0
        self.face = int(self.rng.integers(4))
        self.t, self.bag, self.chop = 0, 0, 0
        return self.answer([])

    def obs(self):
        vox = np.full(S.SHAPE, AIR, np.uint8)   # y, z(forward), x(across)
        vox[:3] = DIRT
        fx, fz = FWD[self.face]
        rx, rz = -fz, fx
        for iz in range(S.SHAPE[1]):
            for ix in range(S.SHAPE[2]):
                lx, lz = ix - 5, iz - 5
                wx, wz = self.x + lx * rx + lz * fx, self.z + lx * rz + lz * fz
                if 0 <= wx < self.size and 0 <= wz < self.size:
                    h = self.logs[wx, wz]
                    vox[3:3 + h, iz, ix] = LOG
                else:
                    vox[3:, iz, ix] = 0
        vec = np.zeros(S.VEC, np.float32)
        vec[S.VI["bag_log"]] = np.log1p(self.bag) / np.log1p(64)
        vec[S.VI["yaw_sin"]], vec[S.VI["yaw_cos"]] = np.sin(self.face * np.pi / 2), np.cos(self.face * np.pi / 2)
        vec[S.VI["health"]] = 1.0
        vec[S.VI["on_ground"]] = 1.0
        return {"vox": vox, "seen": np.ones(S.SHAPE, bool), "vec": vec, "ent": np.zeros((S.ENTS, S.ENT_F), np.float32), "hot": np.zeros(S.HOT, np.int64)}

    def answer(self, events):
        done = self.t >= self.max
        evs = [{"type": "agent", "agent": "1", "x": float(self.x), "y": 64.0, "z": float(self.z), "hp": 20.0, "alive": True, "base_dist": 0.0}] + events
        mask = np.ones(S.SIZES[4], bool)
        return {"open": True, "agents": [] if done else ["1"], "obs": {} if done else {"1": self.obs()}, "mask": {"1": mask}, "done": done,
                "frame": {"t": self.t * 0.2, "dt": 0.2, "phase": "day", "events": evs}, "info": {"logs": self.bag}}

    def step(self, actions):
        a = actions.get("1", [0, 0, 0, 0, 0])
        self.t += 1
        ev = [{"type": "decision", "agent": "1", "id": self.t, "kind": "x"}]
        y = YAW[a[2]]
        if y >= 90:
            self.face = (self.face + 3) % 4
        elif y <= -90:
            self.face = (self.face + 1) % 4
        fx, fz = FWD[self.face]
        tx, tz = self.x + fx, self.z + fz
        inside = 0 <= tx < self.size and 0 <= tz < self.size
        if a[4] == ATTACK and inside and self.logs[tx, tz] > 0:
            self.chop += 1
            if self.chop >= 2:
                self.chop = 0
                self.logs[tx, tz] -= 1
                self.bag += 1
                ev.append({"type": "scale", "agent": "1", "key": "achievement.new", "delta": 1.0})
        else:
            self.chop = 0
            if a[0] == 1 and inside and self.logs[tx, tz] == 0:
                self.x, self.z = tx, tz
        return self.answer(ev)

    def close(self):
        pass
