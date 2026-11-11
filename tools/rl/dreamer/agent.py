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
        cap = str(device).startswith("cuda")   # (capturable: the steps' counters on the GPU, so that a CUDA graph can hold the optimizers)
        self.opt_wm = torch.optim.Adam(wm, lr=cfg["lr_wm"], eps=1e-8, capturable=cap)
        self.opt_actor = torch.optim.Adam(self.actor.parameters(), lr=cfg["lr_ac"], eps=1e-5, capturable=cap)
        self.opt_critic = torch.optim.Adam(self.critic.parameters(), lr=cfg["lr_ac"], eps=1e-5, capturable=cap)
        self.opt_ens = torch.optim.Adam(self.ens.parameters(), lr=cfg["lr_wm"], capturable=cap)
        self.bf16 = bool(cfg.get("bf16", False))
        self.to(device)
        self.init_state()

    def feat(self, h, z):
        return torch.cat([h, z], -1)

    def tensors(self, b: dict) -> dict:
        return {k: torch.as_tensor(v, device=self.device) for k, v in b.items()}

    # ---- the world model ---------------------------------------------------------------------------------------------

    def autocast(self):
        """the encoder, decoder and heads in bf16 on the GPU (the recurrence stays in full precision: nets.RSSM)"""
        return torch.autocast(device_type=str(self.device).split(":")[0], dtype=torch.bfloat16,
                              enabled=self.bf16 and str(self.device).startswith("cuda"), cache_enabled=False)

    def wm_loss(self, b):
        e = self.enc(b)
        prev = S.onehot(b["prev"])
        h, z, post, prior = self.rssm.observe(e, prev, b["first"])
        f = self.feat(h, z)
        rec, parts = Decoder.loss(self.dec(f), b)
        rl = self.twohot.loss(self.rew(f).float(), b["rew"]) * b["rew_ok"].float()
        cl = F.binary_cross_entropy_with_logits(self.cont(f).squeeze(-1).float(), b["cont"], reduction="none")
        dyn, kl_raw = RSSM.kl(post.detach(), prior, self.cfg["free"])
        rep, _ = RSSM.kl(post, prior.detach(), self.cfg["free"])
        loss = (rec + rl + cl + 0.5 * dyn + 0.1 * rep).mean()
        with torch.no_grad():   # (tensors: nothing is read back to the host here, a CUDA graph has no host)
            m = {"wm/loss": loss.detach(), "wm/rew": rl.mean(), "wm/cont": cl.mean(), "wm/kl": kl_raw.mean()}
            m.update({"wm/" + k: v.mean() for k, v in parts.items()})
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
            vec = self.dec.vec(feats[1:]).float()
            night = torch.sign(vec[..., S.NIGHT]) * torch.expm1(torch.abs(vec[..., S.NIGHT]))   # (decoded in symlog)
            wave = torch.sign(vec[..., S.WAVE]) * torch.expm1(torch.abs(vec[..., S.WAVE]))
            off = ((night > 0.5) | (wave > 0.5)).float()
        return d.float() * (1 - off), off.mean()

    def ac_loss(self, f, h, z):
        cfg = self.cfg
        f0, h0, z0 = f.detach().reshape(-1, f.shape[-1]), h.detach().reshape(-1, h.shape[-1]), z.detach().reshape(-1, z.shape[-1])
        if f0.shape[0] > cfg["imag_starts"]:   # (drawn with replacement: no host-side permutation)
            pick = torch.randint(0, f0.shape[0], (cfg["imag_starts"],), device=f0.device)
            f0, h0, z0 = f0[pick], h0[pick], z0[pick]
        feats, acts = self.imagine(f0, z0, h0, cfg["horizon"])
        ps = self.actor.dists(feats[:-1])
        logps, ents = Actor.logp(ps, acts), Actor.entropy(ps)
        with torch.no_grad():
            r = self.twohot.mean(self.rew(feats[1:]).float())
            cur, off = self.curiosity(feats, acts)
            r_tot = r + cfg["curiosity"] * cur
            cont = torch.sigmoid(self.cont(feats[1:]).squeeze(-1).float())
            disc = cfg["gamma"] * cont
            v = self.twohot.mean(self.critic(feats).float())
            # lambda-returns, backwards
            ret = [v[-1]]
            for t in reversed(range(cfg["horizon"])):
                ret.append(r_tot[t] + disc[t] * ((1 - cfg["lam"]) * v[t + 1] + cfg["lam"] * ret[-1]))
            ret = torch.stack(ret[::-1][:-1], 0)   # (H, N): the return from each of the first H states
            w = torch.cumprod(torch.cat([torch.ones_like(disc[:1]), disc[:-1]], 0), 0)
            srt = ret.flatten().sort().values   # (the 5th and 95th percentiles by position: torch.quantile would build a tensor on the host)
            n = srt.numel()
            lo, hi = srt[int(0.05 * (n - 1))], srt[int(0.95 * (n - 1))]
            first = (self.ret_n == 0).float()
            self.ret_lo.copy_(first * lo + (1 - first) * (0.99 * self.ret_lo + 0.01 * lo))
            self.ret_hi.copy_(first * hi + (1 - first) * (0.99 * self.ret_hi + 0.01 * hi))
            self.ret_n.add_(1)
            scale = torch.clamp(self.ret_hi - self.ret_lo, min=1.0)
            adv = (ret - v[:-1]) / scale
        actor_loss = -(w * (adv * logps + cfg["entropy"] * ents)).mean()
        crit = self.critic(feats[:-1].detach()).float()
        slow = self.twohot.mean(self.slow(feats[:-1].detach()).float()).detach()
        critic_loss = (w * (self.twohot.loss(crit, ret) + self.twohot.loss(crit, slow))).mean()
        m = {"ac/ret": ret.mean(), "ac/r_ext": r.mean(), "ac/curiosity": cur.mean(), "ac/cur_off": off,
             "ac/entropy": ents.mean().detach(), "ac/scale": scale.clone(), "ac/actor": actor_loss.detach(), "ac/critic": critic_loss.detach()}
        return actor_loss, critic_loss, m

    def bc_loss(self, f, b):
        """the teacher's labels on its own posterior states (those it could not show in these actions weigh nothing: a
        weight, not a selection of rows: the shapes stay as they are)"""
        w = b["bc"].float()
        den = w.sum().clamp_min(1.0)
        ps = self.actor.dists(f.detach(), b["mask"])
        a = b["act"]
        nll = (-Actor.logp(ps, a) * w).sum() / den
        with torch.no_grad():
            m = {"bc/nll": nll.detach()}
            for i, (name, p) in enumerate(zip(S.SPEC["heads"]["names"], ps)):
                m["bc/acc_" + name] = ((p.argmax(-1) == a[..., i]).float() * w).sum() / den
        return nll, m

    def init_state(self):
        for n in ("ret_lo", "ret_hi", "ret_n"):
            if not hasattr(self, n):
                self.register_buffer(n, torch.zeros((), device=self.device), persistent=False)

    def step(self, b, ac_n, bc, bc_w, train_ac=True):
        """one update on stacked rows b (tensors, no host reads): the world model on all, the ensemble and the imagination
        on the first ac_n rows, behaviour cloning on rows bc = (from, to) weighted bc_w (a float or a 0-d tensor)"""
        with self.autocast():
            loss, f, z, m = self.wm_loss(b)
        h = f[..., :self.rssm.deter]
        self.opt_wm.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(self.opt_wm.param_groups[0]["params"], 1000.0)
        self.opt_wm.step()
        fa, za, ha, ba = f[:ac_n], z[:ac_n], h[:ac_n], {k: v[:ac_n] for k, v in b.items()}
        with torch.no_grad():   # the ensemble: next posterior probabilities from (feat, action)
            tgt = za[:, 1:].detach()
            inp_f, inp_a = fa[:, :-1].detach(), S.onehot(ba["act"][:, :-1])
        with self.autocast():
            el = ((self.ens(inp_f, inp_a).float() - tgt.unsqueeze(0)) ** 2).mean()
        self.opt_ens.zero_grad(set_to_none=True)
        el.backward()
        self.opt_ens.step()
        m["ens/loss"] = el.detach()
        if train_ac or bc is not None:
            with self.autocast():
                if train_ac:
                    al, cl, ma = self.ac_loss(fa.detach(), ha.detach(), za.detach())
                else:
                    al = cl = torch.zeros((), device=self.device)
                    ma = {}
                bcl = torch.zeros((), device=self.device)
                if bc is not None:   # the actor on the teacher's posterior states (the model's features as they are)
                    sl = slice(*bc)
                    bcl, mb = self.bc_loss(f[sl].detach(), {k: v[sl] for k, v in b.items()})
                    m.update(mb)
            self.opt_actor.zero_grad(set_to_none=True)
            (al + bc_w * bcl).backward()
            torch.nn.utils.clip_grad_norm_(self.actor.parameters(), 100.0)
            self.opt_actor.step()
            if train_ac:
                self.opt_critic.zero_grad(set_to_none=True)
                cl.backward()
                torch.nn.utils.clip_grad_norm_(self.critic.parameters(), 100.0)
                self.opt_critic.step()
                with torch.no_grad():
                    torch._foreach_mul_(list(self.slow.parameters()), 0.98)
                    torch._foreach_add_(list(self.slow.parameters()), list(self.critic.parameters()), alpha=0.02)
            m.update(ma)
        return m

    def train_step(self, batch, bc_batch=None, bc_weight=0.0, train_ac=True):
        """the eager update (numpy batches): the agent's sequences, the teacher's alongside; returns floats"""
        nb = len(batch["act"])
        if bc_batch is not None and bc_batch is not batch:
            batch = {k: np.concatenate([batch[k], bc_batch[k]], 0) for k in batch}
            ac_n, bc = nb, ((nb, None) if bc_weight > 0 else None)
        else:
            ac_n, bc = nb, ((0, None) if bc_batch is not None and bc_weight > 0 else None)
        b = self.tensors(batch)
        m = self.step(b, ac_n, bc, bc_weight, train_ac)
        return {k: float(v) for k, v in m.items()}

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
           "horizon": 15, "gamma": 0.997, "lam": 0.95, "entropy": 3e-4, "curiosity": 0.05, "imag_starts": 1024, "bf16": False}


def params(m: torch.nn.Module) -> int:
    return sum(p.numel() for p in m.parameters())


def np_obs(o: dict) -> dict:
    return {k: np.asarray(v) for k, v in o.items()}
