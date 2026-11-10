"""The world model and the actor-critic of the open world (docs/rl/OPEN-WORLD.md section 5), DreamerV3-like on the
voxel cube and the vectors: an encoder of the observation, the RSSM (a GRU's deterministic state and a categorical
stochastic one), decoders of the observation, the reward (twohot over symlog bins) and the episode's continuation,
an ensemble for curiosity (the disagreement on the next stochastic state), the actor (one categorical head per action
head, 1 % uniform mixed in) and the critic (twohot).
"""
from __future__ import annotations

import math

import torch
import torch.nn.functional as F
from torch import nn

from . import spaces as S


def categorical(p):
    """a sample of each row's categorical by its probabilities (Gumbel-max: no checks, no sync with the host)"""
    g = -torch.log(-torch.log(torch.rand_like(p).clamp_(1e-10, 1.0)))
    return torch.argmax(torch.log(p + 1e-10) + g, -1)


def symlog(x):
    return torch.sign(x) * torch.log1p(torch.abs(x))


def symexp(x):
    return torch.sign(x) * (torch.exp(torch.abs(x)) - 1)


class TwoHot(nn.Module):
    """a scalar as a distribution over bins evenly spaced in symlog (DreamerV3)"""

    def __init__(self, bins=255, low=-20.0, high=20.0):
        super().__init__()
        self.register_buffer("b", torch.linspace(low, high, bins))

    def mean(self, logits):
        return symexp((logits.softmax(-1) * self.b).sum(-1))

    def loss(self, logits, x):
        y = symlog(x).clamp(self.b[0], self.b[-1])
        idx = torch.searchsorted(self.b, y.contiguous()).clamp(1, len(self.b) - 1)
        lo, hi = self.b[idx - 1], self.b[idx]
        w_hi = ((y - lo) / (hi - lo)).clamp(0, 1)
        target = torch.zeros_like(logits)
        target.scatter_(-1, (idx - 1).unsqueeze(-1), (1 - w_hi).unsqueeze(-1))
        target.scatter_add_(-1, idx.unsqueeze(-1), w_hi.unsqueeze(-1))
        return -(target * logits.log_softmax(-1)).sum(-1)


def mlp(i, h, o, layers=2, out_scale=1.0):
    mods, d = [], i
    for _ in range(layers):
        mods += [nn.Linear(d, h), nn.LayerNorm(h), nn.SiLU()]
        d = h
    last = nn.Linear(d, o)
    if out_scale == 0:
        nn.init.zeros_(last.weight)
        nn.init.zeros_(last.bias)
    mods.append(last)
    return nn.Sequential(*mods)


class Encoder(nn.Module):
    def __init__(self, out=512):
        super().__init__()
        self.blk = nn.Embedding(S.NBLOCK, 8)
        self.conv = nn.Sequential(nn.Conv3d(9, 32, 3, padding=1), nn.SiLU(), nn.Conv3d(32, 64, 3, stride=2, padding=1), nn.SiLU())
        with torch.no_grad():
            n = self.conv(torch.zeros(1, 9, *S.SHAPE)).numel()
        self.vox = nn.Sequential(nn.Linear(n, out), nn.LayerNorm(out), nn.SiLU())
        self.vec = mlp(S.VEC, 256, 256, 1)
        self.ent = mlp(S.ENT_F, 64, 64, 1)
        self.hot = nn.Embedding(S.NITEM, 8)
        self.out = nn.Sequential(nn.Linear(out + 256 + 128 + 8 * S.HOT, out), nn.LayerNorm(out), nn.SiLU())

    def forward(self, o):
        vox = o["vox"].long()
        lead = vox.shape[:-3]
        v = self.blk(vox.reshape(-1, *S.SHAPE)).permute(0, 4, 1, 2, 3)   # N, 8, Y, Z, X
        v = torch.cat([v, o["seen"].reshape(-1, 1, *S.SHAPE).float()], 1)
        v = self.vox(self.conv(v).flatten(1))
        e = self.ent(o["ent"].reshape(-1, S.ENTS, S.ENT_F))
        e = torch.cat([e.mean(1), e.max(1).values], -1)
        h = self.hot(o["hot"].reshape(-1, S.HOT).long()).flatten(1)
        x = torch.cat([v, self.vec(symlog(o["vec"].reshape(-1, S.VEC))), e, h], -1)
        return self.out(x).reshape(*lead, -1)


class Decoder(nn.Module):
    def __init__(self, feat):
        super().__init__()
        self.grid = (64, 4, 6, 6)
        self.lin = nn.Sequential(nn.Linear(feat, math.prod(self.grid)), nn.SiLU())
        self.deconv = nn.Sequential(nn.ConvTranspose3d(64, 32, 3, stride=2, padding=1, output_padding=(1, 0, 0)), nn.SiLU(),
                                    nn.Conv3d(32, S.NBLOCK + 1, 3, padding=1))
        self.vec = mlp(feat, 512, S.VEC, 2)
        self.ent = mlp(feat, 512, S.ENTS * S.ENT_F, 2)
        self.hot = mlp(feat, 256, S.HOT * S.NITEM, 1)

    def forward(self, f):
        lead = f.shape[:-1]
        x = f.reshape(-1, f.shape[-1])
        g = self.deconv(self.lin(x).reshape(-1, *self.grid))[:, :, :S.SHAPE[0], :S.SHAPE[1], :S.SHAPE[2]]
        return {"vox": g[:, :S.NBLOCK].reshape(*lead, S.NBLOCK, *S.SHAPE), "seen": g[:, S.NBLOCK].reshape(*lead, *S.SHAPE),
                "vec": self.vec(x).reshape(*lead, S.VEC), "ent": self.ent(x).reshape(*lead, S.ENTS, S.ENT_F),
                "hot": self.hot(x).reshape(*lead, S.HOT, S.NITEM)}

    @staticmethod
    def loss(pred, o):
        """per step: the cube's classes (cross-entropy, cells averaged, x 50 so a cube weighs about as a vector), seen,
        the vectors in symlog, the hotbar's classes"""
        lead = o["vec"].shape[:-1]
        vox = F.cross_entropy(pred["vox"].reshape(-1, S.NBLOCK, *S.SHAPE), o["vox"].reshape(-1, *S.SHAPE).long(), reduction="none").reshape(*lead, -1).mean(-1)
        seen = F.binary_cross_entropy_with_logits(pred["seen"], o["seen"].float(), reduction="none").reshape(*lead, -1).mean(-1)
        vec = ((pred["vec"] - symlog(o["vec"])) ** 2).sum(-1)
        ent = ((pred["ent"] - o["ent"]) ** 2).reshape(*lead, -1).sum(-1)
        hot = F.cross_entropy(pred["hot"].reshape(-1, S.NITEM), o["hot"].reshape(-1).long(), reduction="none").reshape(*lead, -1).sum(-1)
        parts = {"vox": 50 * vox, "seen": 10 * seen, "vec": vec, "ent": ent, "hot": hot}
        return sum(parts.values()), parts


class RSSM(nn.Module):
    def __init__(self, embed=512, deter=512, stoch=32, classes=16, hidden=512, unimix=0.01):
        super().__init__()
        self.deter, self.stoch, self.classes, self.unimix = deter, stoch, classes, unimix
        self.inp = nn.Sequential(nn.Linear(stoch * classes + S.ACT_DIM, hidden), nn.LayerNorm(hidden), nn.SiLU())
        self.gru = nn.GRUCell(hidden, deter)
        self.prior = mlp(deter, hidden, stoch * classes, 1)
        self.post = mlp(deter + embed, hidden, stoch * classes, 1)

    @property
    def feat_dim(self):
        return self.deter + self.stoch * self.classes

    def initial(self, n, device):
        return torch.zeros(n, self.deter, device=device), torch.zeros(n, self.stoch * self.classes, device=device)

    def _dist(self, logits):
        lg = logits.reshape(*logits.shape[:-1], self.stoch, self.classes)
        p = lg.softmax(-1)
        p = (1 - self.unimix) * p + self.unimix / self.classes
        return p

    def sample(self, p):
        idx = categorical(p)
        oh = F.one_hot(idx, self.classes).float()
        return (oh + p - p.detach()).flatten(-2)   # straight-through

    def img_step(self, h, z, a):
        h = self.gru(self.inp(torch.cat([z, a], -1)), h)
        p = self._dist(self.prior(h))
        return h, self.sample(p), p

    def obs_step(self, h, z, a, e, first):
        keep = (1 - first.float()).unsqueeze(-1)
        h, z, a = h * keep, z * keep, a * keep
        h = self.gru(self.inp(torch.cat([z, a], -1)), h)
        prior = self._dist(self.prior(h))
        post = self._dist(self.post(torch.cat([h, e], -1)))
        return h, self.sample(post), post, prior

    def observe(self, embed, acts_prev, first, h=None, z=None):
        """over a sequence (B, T): the posterior states; acts_prev[t] is the action before obs t (one-hot)"""
        B, T = embed.shape[:2]
        if h is None:
            h, z = self.initial(B, embed.device)
        hs, zs, posts, priors = [], [], [], []
        for t in range(T):
            h, z, post, prior = self.obs_step(h, z, acts_prev[:, t], embed[:, t], first[:, t])
            hs.append(h); zs.append(z); posts.append(post); priors.append(prior)
        return torch.stack(hs, 1), torch.stack(zs, 1), torch.stack(posts, 1), torch.stack(priors, 1)

    @staticmethod
    def kl(p, q, free=1.0):
        """KL(p || q) summed over the stochastic variables, free bits"""
        k = (p * (torch.log(p + 1e-8) - torch.log(q + 1e-8))).sum(-1).sum(-1)
        return torch.clamp(k, min=free), k


class Actor(nn.Module):
    def __init__(self, feat, hidden=512, unimix=0.01):
        super().__init__()
        self.net = mlp(feat, hidden, S.ACT_DIM, 2, out_scale=0)
        self.unimix = unimix

    def dists(self, f, mask=None):
        lg = self.net(f)
        out, o = [], 0
        for i, n in enumerate(S.SIZES):
            x = lg[..., o:o + n]
            p = x.softmax(-1)
            p = (1 - self.unimix) * p + self.unimix / n
            if i == 4 and mask is not None:
                p = p * mask.float()
                p = p / p.sum(-1, keepdim=True).clamp_min(1e-8)
            out.append(p)
            o += n
        return out

    @staticmethod
    def sample(ps, greedy=False):
        return torch.stack([p.argmax(-1) if greedy else categorical(p) for p in ps], -1)

    @staticmethod
    def logp(ps, a):
        return sum(torch.log(p.gather(-1, a[..., i:i + 1]).squeeze(-1) + 1e-8) for i, p in enumerate(ps))

    @staticmethod
    def entropy(ps):
        return sum(-(p * torch.log(p + 1e-8)).sum(-1) for p in ps)


class Ensemble(nn.Module):
    """Plan2Explore: K heads predict the next stochastic state's probabilities from the features and the action"""

    def __init__(self, feat, target, k=5, hidden=256):
        super().__init__()
        self.heads = nn.ModuleList([mlp(feat + S.ACT_DIM, hidden, target, 2) for _ in range(k)])

    def forward(self, f, a):
        x = torch.cat([f, a], -1)
        return torch.stack([h(x) for h in self.heads], 0)

    def disagreement(self, f, a):
        return self(f, a).var(0).mean(-1)
