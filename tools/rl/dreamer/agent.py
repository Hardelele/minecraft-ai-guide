"""The learner: the world model's update on replayed sequences, the actor and critic in imagination (λ-returns, the
returns' scale by percentiles, the slow critic), curiosity (the ensemble's disagreement, not paid in the night or a
wave as the model itself sees them), behaviour cloning of the teacher's labels on the posterior states, and acting.
"""
from __future__ import annotations

import copy

import numpy as np
import torch
import torch.nn.functional as F

from . import spaces as S
from .nets import RSSM, Actor, Decoder, Encoder, Ensemble, TwoHot, mlp


class Dreamer(torch.nn.Module):
    def __init__(self, cfg: dict, device):
        super().__init__()
        self.cfg = cfg
        self.device = device
        self.enc = Encoder(cfg["embed"])
        self.rssm = RSSM(cfg["embed"], cfg["deter"], cfg["stoch"], cfg["classes"], cfg["hidden"])
        f = self.rssm.feat_dim
        self.dec = Decoder(f)
        self.twohot = TwoHot()
        self.rew = mlp(f, cfg["hidden"], 255, 2, out_scale=0)
        self.cont = mlp(f, cfg["hidden"], 1, 2)
        self.actor = Actor(f, cfg["hidden"])
        self.critic = mlp(f, cfg["hidden"], 255, 2, out_scale=0)
        self.slow = copy.deepcopy(self.critic).requires_grad_(False)
        self.ens = Ensemble(f, cfg["stoch"] * cfg["classes"], cfg["ensemble"])
        wm = list(self.enc.parameters()) + list(self.rssm.parameters()) + list(self.dec.parameters()) + list(self.rew.parameters()) + list(self.cont.parameters())
        self.opt_wm = torch.optim.Adam(wm, lr=cfg["lr_wm"], eps=1e-8)
        self.opt_actor = torch.optim.Adam(self.actor.parameters(), lr=cfg["lr_ac"], eps=1e-5)
        self.opt_critic = torch.optim.Adam(self.critic.parameters(), lr=cfg["lr_ac"], eps=1e-5)
        self.opt_ens = torch.optim.Adam(self.ens.parameters(), lr=cfg["lr_wm"])
        self.ret_lo = self.ret_hi = None
        self.to(device)

    def feat(self, h, z):
        return torch.cat([h, z], -1)

    def tensors(self, b: dict) -> dict:
        return {k: torch.as_tensor(v, device=self.device) for k, v in b.items()}

    # ---- the world model ---------------------------------------------------------------------------------------------

    def wm_loss(self, b):
        e = self.enc(b)
        prev = S.onehot(b["prev"])
        h, z, post, prior = self.rssm.observe(e, prev, b["first"])
        f = self.feat(h, z)
        rec, parts = Decoder.loss(self.dec(f), b)
        rl = self.twohot.loss(self.rew(f), b["rew"]) * b["rew_ok"].float()
        cl = F.binary_cross_entropy_with_logits(self.cont(f).squeeze(-1), b["cont"], reduction="none")
        dyn, kl_raw = RSSM.kl(post.detach(), prior, self.cfg["free"])
        rep, _ = RSSM.kl(post, prior.detach(), self.cfg["free"])
        loss = (rec + rl + cl + 0.5 * dyn + 0.1 * rep).mean()
        m = {"wm/loss": loss.item(), "wm/rew": rl.mean().item(), "wm/cont": cl.mean().item(), "wm/kl": kl_raw.mean().item()}
        m.update({"wm/" + k: v.mean().item() for k, v in parts.items()})
        return loss, f, z, m

    # ---- imagination -------------------------------------------------------------------------------------------------

    @torch.no_grad()
    def imagine(self, f0, z0, h0, horizon):
        """the states the actor's own actions lead to in the model (no gradient through the model)"""
        h, z = h0, z0
        feats, acts = [f0], []
        for _ in range(horizon):
            a = Actor.sample(self.actor.dists(feats[-1]))
            acts.append(a)
            h, z, _ = self.rssm.img_step(h, z, S.onehot(a))
            feats.append(self.feat(h, z))
        return torch.stack(feats, 0), torch.stack(acts, 0)

    def curiosity(self, feats, acts):
        """the ensemble's disagreement on the next state, nothing in the night or a wave (the decoded vector's flags)"""
        with torch.no_grad():
            d = self.ens.disagreement(feats[:-1], S.onehot(acts))
            vec = self.dec.vec(feats[1:])
            night = torch.sign(vec[..., S.NIGHT]) * torch.expm1(torch.abs(vec[..., S.NIGHT]))   # (decoded in symlog)
            wave = torch.sign(vec[..., S.WAVE]) * torch.expm1(torch.abs(vec[..., S.WAVE]))
            off = ((night > 0.5) | (wave > 0.5)).float()
        return d * (1 - off), off.mean()

    def ac_loss(self, f, h, z):
        cfg = self.cfg
        f0, h0, z0 = f.detach().reshape(-1, f.shape[-1]), h.detach().reshape(-1, h.shape[-1]), z.detach().reshape(-1, z.shape[-1])
        if f0.shape[0] > cfg["imag_starts"]:
            pick = torch.randperm(f0.shape[0], device=f0.device)[:cfg["imag_starts"]]
            f0, h0, z0 = f0[pick], h0[pick], z0[pick]
        feats, acts = self.imagine(f0, z0, h0, cfg["horizon"])
        ps = self.actor.dists(feats[:-1])
        logps, ents = Actor.logp(ps, acts), Actor.entropy(ps)
        with torch.no_grad():
            r = self.twohot.mean(self.rew(feats[1:]))
            cur, off = self.curiosity(feats, acts)
            r_tot = r + cfg["curiosity"] * cur
            cont = torch.sigmoid(self.cont(feats[1:]).squeeze(-1))
            disc = cfg["gamma"] * cont
            v = self.twohot.mean(self.critic(feats))
            # λ-returns, backwards
            ret = [v[-1]]
            for t in reversed(range(cfg["horizon"])):
                ret.append(r_tot[t] + disc[t] * ((1 - cfg["lam"]) * v[t + 1] + cfg["lam"] * ret[-1]))
            ret = torch.stack(ret[::-1][:-1], 0)   # (H, N): the return from each of the first H states
            w = torch.cumprod(torch.cat([torch.ones_like(disc[:1]), disc[:-1]], 0), 0)
            lo, hi = torch.quantile(ret, 0.05), torch.quantile(ret, 0.95)
            if self.ret_lo is None:
                self.ret_lo, self.ret_hi = lo, hi
            self.ret_lo = 0.99 * self.ret_lo + 0.01 * lo
            self.ret_hi = 0.99 * self.ret_hi + 0.01 * hi
            scale = torch.clamp(self.ret_hi - self.ret_lo, min=1.0)
            adv = (ret - v[:-1]) / scale
        actor_loss = -(w * (adv * logps + cfg["entropy"] * ents)).mean()
        crit = self.critic(feats[:-1].detach())
        slow = self.twohot.mean(self.slow(feats[:-1].detach())).detach()
        critic_loss = (w * (self.twohot.loss(crit, ret) + self.twohot.loss(crit, slow))).mean()
        m = {"ac/ret": ret.mean().item(), "ac/r_ext": r.mean().item(), "ac/curiosity": cur.mean().item(), "ac/cur_off": off.item(),
             "ac/entropy": ents.mean().item(), "ac/scale": scale.item(), "ac/actor": actor_loss.item(), "ac/critic": critic_loss.item()}
        return actor_loss, critic_loss, m

    def bc_loss(self, f, b):
        """the teacher's labels on its own posterior states (those it could not show in these actions left out)"""
        sel = b["bc"]
        if not sel.any():
            return torch.zeros((), device=self.device), {}
        ps = self.actor.dists(f.detach()[sel], b["mask"][sel])
        a = b["act"][sel]
        nll = -Actor.logp(ps, a).mean()
        acc = [(p.argmax(-1) == a[:, i]).float().mean().item() for i, p in enumerate(ps)]
        m = {"bc/nll": nll.item()}
        m.update({"bc/acc_" + n: x for n, x in zip(S.SPEC["heads"]["names"], acc)})
        return nll, m

    def train_step(self, batch, bc_batch=None, bc_weight=0.0, train_ac=True):
        """one update: the world model on the agent's and the teacher's sequences at once (one pass, rows stacked), the
        ensemble, the actor (imagination's returns + bc_weight x the teacher's labels) and the critic"""
        nb = len(batch["act"])
        if bc_batch is not None and bc_batch is not batch:
            batch = {k: np.concatenate([batch[k], bc_batch[k]], 0) for k in batch}
        b = self.tensors(batch)
        loss, f, z, m = self.wm_loss(b)
        h = f[..., :self.rssm.deter]
        f2 = bb = None
        if bc_batch is not None:
            sl = slice(nb, None) if bc_batch is not batch else slice(None)
            bb = {k: v[sl] for k, v in b.items()}
            f2 = f[sl]
            if bc_batch is not batch:   # the agent's own rows for the imagination
                f, z, h = f[:nb], z[:nb], h[:nb]
                b = {k: v[:nb] for k, v in b.items()}
        self.opt_wm.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(self.opt_wm.param_groups[0]["params"], 1000.0)
        self.opt_wm.step()
        # the ensemble: next posterior probabilities from (feat, action)
        with torch.no_grad():
            tgt = z[:, 1:].detach()
            inp_f, inp_a = f[:, :-1].detach(), S.onehot(b["act"][:, :-1])
        el = ((self.ens(inp_f, inp_a) - tgt.unsqueeze(0)) ** 2).mean()
        self.opt_ens.zero_grad(set_to_none=True)
        el.backward()
        self.opt_ens.step()
        m["ens/loss"] = el.item()
        if train_ac or bc_weight > 0:
            al, cl, ma = self.ac_loss(f.detach(), h.detach(), z.detach()) if train_ac else (torch.zeros((), device=self.device),) * 2 + ({},)
            bcl = torch.zeros((), device=self.device)
            if f2 is not None and bc_weight > 0:   # the actor on the teacher's posterior states (the model's features as they are)
                bcl, mb = self.bc_loss(f2.detach(), bb)
                m.update(mb)
            self.opt_actor.zero_grad(set_to_none=True)
            (al + bc_weight * bcl).backward()
            torch.nn.utils.clip_grad_norm_(self.actor.parameters(), 100.0)
            self.opt_actor.step()
            if train_ac:
                self.opt_critic.zero_grad(set_to_none=True)
                cl.backward()
                torch.nn.utils.clip_grad_norm_(self.critic.parameters(), 100.0)
                self.opt_critic.step()
                with torch.no_grad():
                    for p, q in zip(self.slow.parameters(), self.critic.parameters()):
                        p.mul_(0.98).add_(0.02 * q)
            m.update(ma)
        return m

    # ---- acting ----------------------------------------------------------------------------------------------------

    @torch.no_grad()
    def act(self, obs: dict, state, prev_act, first, mask=None, greedy=False):
        """a batch of agents: observation tensors, (h, z), the actions before (N, 5), first (N,) -> actions, new state"""
        e = self.enc(obs)
        h, z = state
        h, z, _, _ = self.rssm.obs_step(h, z, S.onehot(prev_act), e, first)
        ps = self.actor.dists(self.feat(h, z), mask)
        return Actor.sample(ps, greedy), (h, z)

    def initial(self, n):
        return self.rssm.initial(n, self.device)


DEFAULT = {"embed": 512, "deter": 512, "stoch": 32, "classes": 16, "hidden": 512, "ensemble": 5, "lr_wm": 1e-4, "lr_ac": 3e-5, "free": 1.0,
           "horizon": 15, "gamma": 0.997, "lam": 0.95, "entropy": 3e-4, "curiosity": 0.05, "imag_starts": 1024}


def params(m: torch.nn.Module) -> int:
    return sum(p.numel() for p in m.parameters())


def np_obs(o: dict) -> dict:
    return {k: np.asarray(v) for k, v in o.items()}
