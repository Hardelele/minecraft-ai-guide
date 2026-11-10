"""Delayed credit for PPO's batch (tools/rl/train.py), before the advantages are computed: a reward that comes long
after the decision that made it possible (a sword crafted, kills a wave later; a pickaxe, iron an hour later) reaches
that decision.

1. By the causes the environment names (`redistribute`): a reward event carries `causes`, the ids of the decisions
   behind it (the simulator's: the craft of the weapon that killed, of the pickaxe that dug; contract 2's
   environment: its causal labels). A share of the event's points moves from the step it landed on to its causes:
       moved = beta * points * exp(-dt / tau),   dt = the game seconds from the cause's decision to the event,
   split evenly over the causes found in the batch (a cause in an earlier batch is gone: its share stays). beta 0.5:
   the step that saw the event keeps half, so the policy still learns to finish what was started; tau (default
   3600 s) only damps credit to causes from long ago, it does not decide it. The sum of the rewards is unchanged.

2. Learned (`Rudder`, after RUDDER, Arjona-Medina et al. 2019): a small GRU reads an agent's episode, observation and
   action by step, and predicts the episode's return; the change of its prediction at a step is that step's
   redistributed reward (the last step also gets what the prediction missed, so the episode's sum is the return).
   It finds links no event names (torches put out: fewer mobs come). The reward used is
       r = (1 - alpha) * r_env + alpha * r_rudder.
"""
from __future__ import annotations

import math

import numpy as np
import torch
from torch import nn

# a transition in train.py's streams: [obs, mask, act, logp, val, rew, done, next_val, t, dt, did, events, pos]
REW, T, DID, EVENTS = 5, 8, 10, 11


def redistribute(streams: dict, beta: float = 0.5, tau: float = 3600.0) -> dict:
    """moves shares of events' points to their causes in place; -> stats (events, with causes found, points moved)"""
    where = {}
    for k, L in streams.items():
        for i, tr in enumerate(L):
            if tr[DID] is not None and tr[DID] >= 0:
                where[tr[DID]] = (k, i)
    st = {"events": 0, "linked": 0, "moved": 0.0, "lag_s": []}
    for k, L in streams.items():
        for tr in L:
            for ev in tr[EVENTS] or ():
                st["events"] += 1
                found = [where[c] for c in ev.get("causes", ()) if c in where]
                found = [f for f in found if streams[f[0]][f[1]] is not tr]
                if not found:
                    continue
                st["linked"] += 1
                p = float(ev["points"])
                for kk, ii in found:
                    src = streams[kk][ii]
                    lag = max(0.0, float(ev.get("t", tr[T])) - float(src[T]))
                    m = beta * p * math.exp(-lag / tau) / len(found)
                    src[REW] += m
                    tr[REW] -= m
                    st["moved"] += abs(m)
                    st["lag_s"].append(lag)
    st["lag_s"] = float(np.median(st["lag_s"])) if st["lag_s"] else 0.0
    return st


class Rudder(nn.Module):
    """the return predictor: a GRU over (observation, action) by step -> the episode's return after each step"""

    def __init__(self, d: int, a: int, hidden: int = 128):
        super().__init__()
        self.a = a
        self.inp = nn.Sequential(nn.Linear(d + a, hidden), nn.Tanh())
        self.gru = nn.GRU(hidden, hidden, batch_first=True)
        self.out = nn.Linear(hidden, 1)

    def forward(self, obs: torch.Tensor, act: torch.Tensor) -> torch.Tensor:
        """obs [B, T, d], act [B, T] -> [B, T] predicted return"""
        x = torch.cat([obs, nn.functional.one_hot(act, self.a).float()], -1)
        h, _ = self.gru(self.inp(x))
        return self.out(h).squeeze(-1)


class RudderTrainer:
    """keeps finished episodes (observations normalised by the policy's running norm, actions, return), trains the
    predictor a few steps a PPO epoch, and gives each step's redistributed reward"""

    def __init__(self, d: int, a: int, dev, hidden: int = 128, keep: int = 512, lr: float = 1e-3, max_len: int = 400):
        self.net = Rudder(d, a, hidden).to(dev)
        self.opt = torch.optim.Adam(self.net.parameters(), lr=lr)
        self.dev, self.keep, self.max_len = dev, keep, max_len
        self.done: list[tuple[np.ndarray, np.ndarray, float]] = []
        self.loss = float("nan")

    def _pad(self, seqs):
        n = max(len(s[0]) for s in seqs)
        d = seqs[0][0].shape[1]
        O = np.zeros((len(seqs), n, d), np.float32)
        A = np.zeros((len(seqs), n), np.int64)
        M = np.zeros((len(seqs), n), np.float32)
        for i, (o, a, *_) in enumerate(seqs):
            O[i, :len(o)], A[i, :len(a)], M[i, :len(o)] = o, a, 1
        return (torch.tensor(O, device=self.dev), torch.tensor(A, device=self.dev), torch.tensor(M, device=self.dev))

    def add(self, obs: np.ndarray, act: np.ndarray, ret: float):
        self.done.append((obs[-self.max_len:], act[-self.max_len:], ret))
        if len(self.done) > self.keep:
            self.done = self.done[-self.keep:]

    def train(self, steps: int = 30, batch: int = 32):
        if len(self.done) < batch:
            return
        rng = np.random.default_rng()
        ls = []
        for _ in range(steps):
            idx = rng.choice(len(self.done), batch, replace=False)
            seqs = [self.done[i] for i in idx]
            O, A, M = self._pad(seqs)
            G = torch.tensor([s[2] for s in seqs], device=self.dev, dtype=torch.float32)
            p = self.net(O, A)
            last = (M.sum(1).long() - 1).clamp(min=0)
            # every step predicts the return (an auxiliary target, as RUDDER's), the last one most of all
            err = ((p - G[:, None]) ** 2 * M).sum() / M.sum()
            end = ((p[torch.arange(len(seqs)), last] - G) ** 2).mean()
            loss = 0.5 * err + end
            self.opt.zero_grad()
            loss.backward()
            nn.utils.clip_grad_norm_(self.net.parameters(), 1.0)
            self.opt.step()
            ls.append(loss.item())
        self.loss = float(np.mean(ls))

    @torch.no_grad()
    def predict(self, histories: list[tuple[np.ndarray, np.ndarray]]) -> list[np.ndarray]:
        """histories: (obs [T, d], act [T]) of episodes so far -> the predicted return after each step"""
        out = []
        for i in range(0, len(histories), 64):
            part = [(o[-self.max_len:], a[-self.max_len:]) for o, a in histories[i:i + 64]]
            O, A, M = self._pad(part)
            p = self.net(O, A).cpu().numpy()
            for j, (o, a) in enumerate(histories[i:i + 64]):
                n = min(len(o), self.max_len)
                q = p[j, :n]
                if len(o) > n:   # the start cut off: the steps before keep no prediction of their own
                    q = np.concatenate([np.zeros(len(o) - n, np.float32), q])
                out.append(q)
        return out
