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


class RecPolicy(nn.Module):
    """The policy with a memory: obs -> tanh layer -> GRU cell (its state carried from one decision of the agent to
    its next, zero at an episode's start) -> tanh layer on [layer, state] -> logits; the critic reads the same.
    For the check whether memory helps (tools/rl/train.py --recurrent H); in Java a GRU cell is three more
    products of a matrix and a vector."""

    def __init__(self, d: int, a: int, hidden: int = 256, mem: int = 64):
        super().__init__()
        self.d, self.a, self.hidden, self.mem = d, a, hidden, mem
        self.norm = RunningNorm(d)
        g = math.sqrt(2)
        self.enc = nn.Sequential(layer(d, hidden, g), nn.Tanh())
        self.cell = nn.GRUCell(hidden, mem)
        self.pi = nn.Sequential(layer(hidden + mem, hidden, g), nn.Tanh(), layer(hidden, a, 0.01))
        self.v = nn.Sequential(layer(hidden + mem, hidden, g), nn.Tanh(), layer(hidden, 1, 1.0))

    def init_state(self, n: int, device=None) -> torch.Tensor:
        return torch.zeros(n, self.mem, device=device)

    def step(self, obs, h):
        e = self.enc(self.norm(obs))
        h2 = self.cell(e, h)
        z = torch.cat([e, h2], -1)
        return self.pi(z), self.v(z).squeeze(-1), h2

    def act(self, obs, mask, h, greedy: bool = False):
        lg, v, h2 = self.step(obs, h)
        lg = Policy.masked(lg, mask)
        d = torch.distributions.Categorical(logits=lg)
        a = lg.argmax(-1) if greedy else d.sample()
        return a, d.log_prob(a), v, d.entropy(), h2

    def evaluate_seq(self, obs, mask, act, h0, reset):
        """obs [B, T, d], mask [B, T, A], act [B, T], h0 [B, mem] (the state before the chunk), reset [B, T] (1: the
        step starts an episode, its state is zero) -> log-prob, entropy, value, each [B, T]"""
        B, T = act.shape
        h = h0
        lps, ents, vs = [], [], []
        for t in range(T):
            h = h * (1 - reset[:, t:t + 1])
            lg, v, h = self.step(obs[:, t], h)
            d = torch.distributions.Categorical(logits=Policy.masked(lg, mask[:, t]))
            lps.append(d.log_prob(act[:, t]))
            ents.append(d.entropy())
            vs.append(v)
        return torch.stack(lps, 1), torch.stack(ents, 1), torch.stack(vs, 1)
