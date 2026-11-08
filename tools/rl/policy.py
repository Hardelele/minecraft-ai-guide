"""The crew's policy net: one for every agent (parameter sharing; the agent's number and the scenario are in the obs).

    obs (rl/obs.json, D floats) -> normalised by the running mean/std -> actor MLP (D-H-H-A, tanh) -> logits over the
    actions (rl/actions.json); the actions the mask forbids get MASKED before softmax, so they are never chosen.
    A critic MLP of the same shape gives the value (training only; not exported).

An MLP rather than a transformer over agents: the obs already carries what the others do (allies, the wall, posts)
as counts, the agents decide one at a time (not all at once), and Java runs a few small matrix products in a
fraction of a millisecond.
"""
from __future__ import annotations

import math

import torch
from torch import nn

MASKED = -1e8


class RunningNorm(nn.Module):
    """Running mean/var of the observations (Welford by batches); frozen in eval and in the export."""

    def __init__(self, d: int, clip: float = 10.0):
        super().__init__()
        self.register_buffer("mean", torch.zeros(d, dtype=torch.float64))
        self.register_buffer("var", torch.ones(d, dtype=torch.float64))
        self.register_buffer("count", torch.tensor(1e-4, dtype=torch.float64))
        self.clip = clip

    @torch.no_grad()
    def update(self, x: torch.Tensor):
        x = x.double()
        bm, bv, bc = x.mean(0), x.var(0, unbiased=False), x.shape[0]
        d = bm - self.mean
        tot = self.count + bc
        self.mean += d * bc / tot
        self.var = (self.var * self.count + bv * bc + d * d * self.count * bc / tot) / tot
        self.count = tot

    def std(self) -> torch.Tensor:
        return torch.sqrt(self.var + 1e-8)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return torch.clamp((x - self.mean.float()) / self.std().float(), -self.clip, self.clip)


def layer(i: int, o: int, gain: float) -> nn.Linear:
    lin = nn.Linear(i, o)
    nn.init.orthogonal_(lin.weight, gain)
    nn.init.zeros_(lin.bias)
    return lin


class Policy(nn.Module):
    def __init__(self, d: int, a: int, hidden: int = 256):
        super().__init__()
        self.d, self.a, self.hidden = d, a, hidden
        self.norm = RunningNorm(d)
        g = math.sqrt(2)
        self.actor = nn.Sequential(layer(d, hidden, g), nn.Tanh(), layer(hidden, hidden, g), nn.Tanh(), layer(hidden, a, 0.01))
        self.critic = nn.Sequential(layer(d, hidden, g), nn.Tanh(), layer(hidden, hidden, g), nn.Tanh(), layer(hidden, 1, 1.0))

    def logits(self, obs: torch.Tensor) -> torch.Tensor:
        return self.actor(self.norm(obs))

    def value(self, obs: torch.Tensor) -> torch.Tensor:
        return self.critic(self.norm(obs)).squeeze(-1)

    @staticmethod
    def masked(logits: torch.Tensor, mask: torch.Tensor) -> torch.Tensor:
        return torch.where(mask, logits, torch.full_like(logits, MASKED))

    def dist(self, obs, mask):
        return torch.distributions.Categorical(logits=self.masked(self.logits(obs), mask))

    def act(self, obs, mask, greedy: bool = False):
        x = self.norm(obs)
        lg = self.masked(self.actor(x), mask)
        d = torch.distributions.Categorical(logits=lg)
        a = lg.argmax(-1) if greedy else d.sample()
        return a, d.log_prob(a), self.critic(x).squeeze(-1), d.entropy()

    def evaluate(self, obs, mask, act):
        x = self.norm(obs)
        d = torch.distributions.Categorical(logits=self.masked(self.actor(x), mask))
        return d.log_prob(act), d.entropy(), self.critic(x).squeeze(-1)


def tensors(p: Policy) -> list[tuple[str, torch.Tensor]]:
    """What the Java side needs, in the file's order (tools/rl/export_policy.py)."""
    l1, l2, l3 = p.actor[0], p.actor[2], p.actor[4]
    return [("obs_mean", p.norm.mean.float()), ("obs_std", p.norm.std().float()),
            ("l1.w", l1.weight), ("l1.b", l1.bias), ("l2.w", l2.weight), ("l2.b", l2.bias), ("pi.w", l3.weight), ("pi.b", l3.bias)]
