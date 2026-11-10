"""train.py's rollout on the batch simulator (--env simbatch): all arenas in one process (tools/rl/simbatch.py, numba
over --sim-threads threads), the decisions of a step one batch for the policy, the experience kept on the GPU.

The observations go to the GPU once (pinned, non-blocking) and stay there for PPO's update; the actions come back to
the CPU (the simulator needs them) as int64; the values and log-probabilities stay on the GPU. A transition's reward
comes with its agent's next row (or the end) as in train.py's loop, its next value with that agent's next decision;
the transitions still waiting at an epoch's end are carried into the next one (train.py's pending/waiting).

The same files as train.py's sim path: metrics.csv, episodes.csv, curriculum.log, chains.jsonl (empty: the batch
simulator keeps no film chains), snapshots. Credit by causes (--credit) works on the events' decision ids; RUDDER
and the recurrent net are the old path's only.
"""
from __future__ import annotations

import collections
import csv
import json
import math
import os
import time

import numpy as np
import torch

import spec


class FlatRetNorm:
    """RetNorm over arrays: the discounted return per slot, the running std over all (batched Welford)"""

    def __init__(self, gamma: float, slots: int):
        self.g = gamma
        self.ret = np.zeros(slots)
        self.mean, self.var, self.count = 0.0, 1.0, 1e-4

    def __call__(self, slot: np.ndarray, r: np.ndarray, done: np.ndarray) -> np.ndarray:
        x = self.ret[slot] * self.g + r
        self.ret[slot] = np.where(done, 0.0, x)
        bm, bv, bc = float(x.mean()), float(x.var()), x.size
        d = bm - self.mean
        tot = self.count + bc
        self.mean += d * bc / tot
        self.var = (self.var * self.count + bv * bc + d * d * self.count * bc / tot) / tot
        self.count = tot
        return r / np.sqrt(self.var + 1e-8)


class Buffer:
    """the epoch's transitions: big tensors on the GPU, the bookkeeping in numpy"""

    def __init__(self, cap: int, dev):
        self.cap, self.dev = cap, dev
        self.obs = torch.zeros((cap, spec.D), dtype=torch.float32, device=dev)
        self.msk = torch.zeros((cap, spec.A), dtype=torch.bool, device=dev)
        self.act = torch.zeros(cap, dtype=torch.long, device=dev)
        self.logp = torch.zeros(cap, dtype=torch.float32, device=dev)
        self.val = torch.zeros(cap, dtype=torch.float32, device=dev)
        self.nv = torch.zeros(cap, dtype=torch.float32, device=dev)
        self.rew = np.zeros(cap)
        self.done = np.zeros(cap, bool)
        self.complete = np.zeros(cap, bool)
        self.has_rew = np.zeros(cap, bool)
        self.t = np.zeros(cap)
        self.dt = np.zeros(cap)
        self.slot = np.zeros(cap, np.int64)
        self.nxt = np.full(cap, -1, np.int64)
        self.did = np.full(cap, -1, np.int64)
        self.block = np.zeros(cap, np.int64)
        self.n = 0
        self.events: dict[int, list] = {}

    def grow(self, need: int):
        if self.n + need <= self.cap:
            return
        cap = max(self.cap * 2, self.n + need)
        for k in ("obs", "msk", "act", "logp", "val", "nv"):
            old = getattr(self, k)
            new = torch.zeros((cap,) + tuple(old.shape[1:]), dtype=old.dtype, device=self.dev)
            new[:self.n] = old[:self.n]
            setattr(self, k, new)
        for k in ("rew", "done", "complete", "has_rew", "t", "dt", "slot", "nxt", "did", "block"):
            old = getattr(self, k)
            new = np.full(cap, -1 if k in ("nxt", "did") else 0, old.dtype)
            new[:self.n] = old[:self.n]
            setattr(self, k, new)
        self.cap = cap

    def carry(self, keep: np.ndarray) -> np.ndarray:
        """the transitions `keep` (the incomplete ones) moved to the front; -> their new indices"""
        m = len(keep)
        if m:
            ix = torch.as_tensor(keep, device=self.dev)
            for k in ("obs", "msk", "act", "logp", "val", "nv"):
                t_ = getattr(self, k)
                t_[:m] = t_[ix].clone()
            for k in ("rew", "done", "complete", "has_rew", "t", "dt", "slot", "did", "block"):
                a_ = getattr(self, k)
                a_[:m] = a_[keep]
            self.nxt[:m] = -1
            self.block[:m] = -1
            ev = {}
            for new, old in enumerate(keep):
                if int(old) in self.events:
                    ev[new] = self.events[int(old)]
            self.events = ev
        else:
            self.events = {}
        self.n = m
        return np.arange(m)


def redistribute(buf: Buffer, idx: np.ndarray, rew: np.ndarray, beta: float, tau: float) -> dict:
    """credit.redistribute over the buffer: an event's share moves to its causes' transitions in this batch (idx:
    the complete transitions, rew: their raw rewards, changed in place)"""
    pos = {int(j): q for q, j in enumerate(idx)}
    where = {int(buf.did[j]): q for q, j in enumerate(idx) if buf.did[j] >= 0}
    st = {"events": 0, "linked": 0, "moved": 0.0, "lag_s": []}
    for j, evs in buf.events.items():
        q = pos.get(j)
        if q is None:
            continue
        for key, p, t_ev, causes, mv in evs:
            st["events"] += 1
            if mv is None and not beta:
                continue
            found = [where[c] for c in causes if c in where and where[c] != q]
            if not found:
                continue
            st["linked"] += 1
            for f in found:
                lag = max(0.0, t_ev - float(buf.t[idx[f]]))
                # the reward v4's credit moves its share (all of it), undamped; the rest by --credit's beta and tau
                m = (mv * p if mv is not None else beta * p * math.exp(-lag / tau)) / len(found)
                rew[f] += m
                rew[q] -= m
                st["moved"] += abs(m)
                st["lag_s"].append(lag)
    st["lag_s"] = float(np.median(st["lag_s"])) if st["lag_s"] else 0.0
    return st


def run(a, pol, opt, cur, rng, out, dev, job, ach_depth, write_policy, cpu_copy, save, ppo_loss, COMPONENTS, SNAP, OB_KEYS):
    import achievements as ACHS
    import simbatch_kernel as KN
    import simbatch_tables as T
    from simbatch import BatchSim
    MA = T.MA
    N = a.envs
    v4 = getattr(a, "reward", "v3") == "v4"
    sim = BatchSim(N, curiosity=a.curiosity, threads=a.sim_threads, reward="v4" if v4 else "v3", merge=True)
    # the resource governor (tools/rl/governor.py): the simulator's threads, torch's, pauses, the GPU's duty by what the PC has
    # spare (League of Legends: Idle, 1 + 1 threads, the GPU a quarter)
    import numba
    gov = None
    gov_file = os.environ.get("AIGUIDE_GOVERNOR")
    if not getattr(a, "no_governor", False) and not gov_file:
        import governor as GOV
        gov = GOV.Governor(log=str(out / "governor.jsonl"), max_threads=max(1, a.threads), max_arenas=a.sim_threads).start()

    def advice():
        if gov is not None:
            return gov.advice()
        if gov_file:
            import governor as GOV
            return GOV.read_advice(gov_file) or {}
        return {}

    def govern():
        """the advice applied between steps: a pause waits; the threads follow it"""
        d = advice()
        while d.get("pause"):
            time.sleep(1.0)
            d = advice()
        if d:
            numba.set_num_threads(int(max(1, min(a.sim_threads, d.get("arenas", a.sim_threads) or 1))))
            torch.set_num_threads(int(max(1, min(a.threads, d.get("threads", 1)))))
        return d
    adv = govern()
    adv_t = time.time()
    sim.reset(np.arange(N), [job() for _ in range(N)])
    rows = N * MA
    last = np.full(rows, -1, np.int64)        # a slot's transition waiting for its reward or its next value
    ep_ret = np.zeros(N)
    ep_dec = np.zeros(N, np.int64)
    retnorm = FlatRetNorm(a.gamma_s ** 20 if a.gamma_s else a.gamma, rows)
    buf = Buffer(int(a.rollout * 1.5) + rows, dev)
    pin = dev.type == "cuda"
    mcsv = open(out / "metrics.csv", "w", newline="", encoding="utf-8")
    ecsv = open(out / "episodes.csv", "w", newline="", encoding="utf-8")
    ew = csv.writer(ecsv)
    ew.writerow(["epoch", "scenario", "success", "return", "decisions", "game_s", "noop_end", "agents", "cleared", "done_reason",
                 "achievements", "breaches", "mob_seconds", "sustain_ren", "sustain_use", "progress", "achievement_ids"])
    mw = None
    t0 = time.time()
    epoch = 0
    total_dec = 0
    novel_total = 0
    block = 0
    while epoch < a.max_epochs and time.time() - t0 < a.minutes * 60:
        epoch += 1
        frac = min(1.0, (time.time() - t0) / (a.minutes * 60))
        ent_coef = a.ent + (a.ent_end - a.ent) * frac
        stats = collections.Counter()
        for k_ in OB_KEYS:
            stats["ob_" + k_] += 0
        eps = []
        infer_s = sim_s = 0.0
        n_done = int(buf.complete[:buf.n].sum())
        pol.eval()
        while n_done < a.rollout:
            if time.time() - adv_t > 2.0:
                adv, adv_t = govern(), time.time()
            vrows = np.flatnonzero(sim.valid)
            m = len(vrows)
            ti = time.perf_counter()
            o_np = sim.obs[vrows]
            k_np = sim.mask[vrows]
            if pin:
                ot = torch.from_numpy(o_np).pin_memory().to(dev, non_blocking=True)
                mt = torch.from_numpy(k_np).pin_memory().to(dev, non_blocking=True)
            else:
                ot, mt = torch.from_numpy(o_np), torch.from_numpy(k_np)
            pol.norm.update(ot)
            with torch.no_grad():
                act, logp, val, _ = pol.act(ot, mt)
            act_np = act.cpu().numpy()
            infer_s += time.perf_counter() - ti
            envs_of = vrows // MA
            tnow = sim.t[envs_of]
            # the waiting transitions of these slots: their next value is this one
            w = last[vrows]
            sel = np.flatnonzero(w >= 0)
            buf.grow(m)
            j0 = buf.n
            new = np.arange(j0, j0 + m)
            if len(sel):
                wi = w[sel]
                buf.nv[torch.as_tensor(wi, device=dev)] = val[torch.as_tensor(sel, device=dev)]
                buf.dt[wi] = np.maximum(0.0, tnow[sel] - buf.t[wi])
                buf.complete[wi] = True
                buf.nxt[wi] = new[sel]
                n_done += len(sel)
            sl = slice(j0, j0 + m)
            buf.obs[sl] = ot
            buf.msk[sl] = mt
            buf.act[sl] = act
            buf.logp[sl] = logp
            buf.val[sl] = val
            buf.rew[sl] = 0.0
            buf.done[sl] = False
            buf.complete[sl] = False
            buf.has_rew[sl] = False
            buf.t[sl] = tnow
            buf.slot[sl] = vrows
            buf.nxt[sl] = -1
            buf.did[sl] = -1
            buf.block[sl] = block
            buf.n = j0 + m
            block += 1
            last[vrows] = new
            stats["dec"] += m
            stats["noop"] += int((act_np == 0).sum())
            np.add.at(ep_dec, envs_of, 1)
            acts = np.full(rows, -1, np.int64)
            acts[vrows] = act_np
            ts = time.perf_counter()
            sim.step(acts.reshape(N, MA))
            sim_s += time.perf_counter() - ts
            # the decision ids just taken (for the credit by causes)
            asg = sim.assigned.reshape(-1)
            got_id = np.flatnonzero(asg >= 0)
            if len(got_id):
                buf.did[last[got_id]] = asg[got_id]
            if a.credit or v4:   # the step's reward events with causes: to the transition running at the time
                for e in np.flatnonzero(sim.st.nev):
                    for ev in sim.events(int(e)):
                        if ev["type"] == "reward" and ev["chain"]:
                            j = last[int(e) * MA + ev["agent"]]
                            if j >= 0:
                                buf.events.setdefault(int(j), []).append(
                                    (ev["key"], float(ev["points"]), ev["ticks"] / 20.0,
                                     [int(c["decision_id"]) for c in ev["chain"] if c.get("via") != "step"], ev.get("move")))
                        elif ev["type"] == "novel_effect" and ev["first_ever"]:
                            stats["discoveries"] += 1
            orows = np.flatnonzero(sim.valid)
            oj = last[orows]
            r = sim.rew[orows].copy()
            np.add.at(ep_ret, orows // MA, r)
            if a.reward_clip:
                r = np.maximum(-a.reward_clip, r)
            ok = oj >= 0
            oj, r, orw = oj[ok], r[ok], orows[ok]
            if a.noop_extra:
                noop = buf.act[torch.as_tensor(oj, device=dev)].cpu().numpy() == 0
                r = r - a.noop_extra * noop
            buf.rew[oj] = r
            buf.has_rew[oj] = True
            done_e = np.flatnonzero(sim.done)
            if len(done_e):
                dmask = sim.done[orw // MA]
                dj = oj[dmask]
                buf.done[dj] = True
                buf.complete[dj] = True
                buf.dt[dj] = np.maximum(0.0, sim.t[orw[dmask] // MA] - buf.t[dj])
                n_done += len(dj)
                for e in done_e:   # every slot of a finished arena: nothing waits any more
                    last[e * MA:(e + 1) * MA] = -1
                resets = []
                for e in done_e:
                    e = int(e)
                    inf = sim.info(e)
                    sc = inf["scenario"]
                    na = int(sim.st.ai[e, KN.A_NAG])
                    noop_end = inf["done_reason"] == "noop"
                    gs = inf["t"]
                    sus = inf["sustain"]
                    eps.append((sc, bool(inf["success"]), ep_ret[e] / max(1, na), int(ep_dec[e]), gs, noop_end, inf.get("episode_components", {}),
                                inf["cleared"], na, inf["achievements"], inf["breaches"], inf["mob_seconds"], sus))
                    ew.writerow([epoch, sc, int(inf["success"]), round(ep_ret[e] / max(1, na), 3), int(ep_dec[e]), round(gs, 1), int(noop_end), na,
                                 inf["cleared"], inf["done_reason"], inf["achievements"], inf["breaches"], inf["mob_seconds"], sus[0], sus[1],
                                 round(float(inf["goal_progress"]), 3), " ".join(inf.get("achievement_ids", []))])
                    for k_ in inf.get("achievement_ids", []):
                        if k_ in ACHS.ACH:
                            ach_depth[0] = max(ach_depth[0], ACHS.depth(k_))
                    novel_total += inf["novel"]
                    stats["novel"] += inf["novel"]
                    stats["kills"] += inf["kills"]
                    stats["repeat_fail"] += inf["repeat_fail"]
                    stats["spawned"] += inf["spawned"]
                    for k_, v_ in inf["obstacles"].items():
                        stats["ob_" + k_] += v_
                    if cur.record(sc, inf["success"], epoch):
                        print(cur.log[-1], flush=True)
                    ep_ret[e] = 0.0
                    ep_dec[e] = 0
                    resets.append(job())
                ts = time.perf_counter()
                sim.reset(done_e, resets)
                sim_s += time.perf_counter() - ts
        # ---- the batch: the complete transitions; the waiting ones carried to the next epoch ---------------------
        n = buf.n
        comp = np.flatnonzero(buf.complete[:n])
        rew = buf.rew[comp].copy()
        cst = redistribute(buf, comp, rew, a.credit, a.credit_tau) if (a.credit or v4) else {}
        # the rewards' scale in time order (block by block)
        blk = buf.block[comp]
        order = np.argsort(blk, kind="stable")
        rn = np.empty_like(rew)
        bounds = np.flatnonzero(np.diff(blk[order])) + 1
        for part in np.split(order, bounds):
            rn[part] = retnorm(buf.slot[comp[part]], rew[part], buf.done[comp[part]])
        # GAE: by the next transition of the same slot (when it is in this batch), blocks backward
        pos = np.full(n, -1, np.int64)
        pos[comp] = np.arange(len(comp))
        nxt = buf.nxt[comp]
        nxt_pos = np.where(nxt >= 0, pos[np.maximum(nxt, 0)], -1)
        vals = buf.val[torch.as_tensor(comp, device=dev)].double().cpu().numpy()
        nvs = buf.nv[torch.as_tensor(comp, device=dev)].double().cpu().numpy()
        dts = buf.dt[comp]
        g_ = a.gamma_s ** dts if a.gamma_s else np.full(len(comp), a.gamma)
        l_ = a.lam_s ** dts if a.lam_s else np.full(len(comp), a.lam)
        nonterm = (~buf.done[comp]).astype(np.float64)
        delta = rn + g_ * nvs * nonterm - vals
        adv = np.zeros(len(comp))
        for part in reversed(np.split(order, bounds)):
            nn = nxt_pos[part]
            cont = nn >= 0
            nxt_adv = np.where(cont, adv[np.maximum(nn, 0)], 0.0)
            adv[part] = delta[part] + g_[part] * l_[part] * nonterm[part] * nxt_adv
        ret = adv + vals
        ci = torch.as_tensor(comp, device=dev)
        bo, bm, ba, blp = buf.obs[ci], buf.msk[ci], buf.act[ci], buf.logp[ci]
        badv = torch.as_tensor(adv, device=dev, dtype=torch.float32)
        bret = torch.as_tensor(ret, device=dev, dtype=torch.float32)
        badv = (badv - badv.mean()) / (badv.std() + 1e-8)
        keep = np.flatnonzero(~buf.complete[:n])
        newpos = np.full(n, -1, np.int64)
        newpos[keep] = buf.carry(keep)
        live = last >= 0
        last[live] = newpos[last[live]]
        pol.train()
        lst = collections.defaultdict(list)
        nb = bo.shape[0]
        for _ in range(a.epochs):
            perm = torch.randperm(nb, device=dev)
            for s0 in range(0, nb, a.minibatch):
                duty = float(adv.get("gpu_duty", 1.0) or 0.0)
                t_mb = time.perf_counter()
                idx = perm[s0:s0 + a.minibatch]
                loss, pg, vl, ent, ratio = ppo_loss(pol, bo[idx], bm[idx], ba[idx], blp[idx], badv[idx], bret[idx], a.clip, a.vf, ent_coef)
                opt.zero_grad()
                loss.backward()
                torch.nn.utils.clip_grad_norm_(pol.parameters(), a.max_grad)
                opt.step()
                if duty < 1.0:   # the governor's GPU duty: the update sleeps between its minibatches
                    if dev.type == "cuda":
                        torch.cuda.synchronize()
                    time.sleep((time.perf_counter() - t_mb) * (1.0 / max(duty, 0.05) - 1.0))
                with torch.no_grad():
                    lst["pg"].append(pg.item())
                    lst["vl"].append(vl.item())
                    lst["ent"].append(ent.mean().item())
                    lst["kl"].append(((ratio - 1) - torch.log(ratio)).mean().item())
        total_dec += stats["dec"]
        row = {"epoch": epoch, "minutes": round((time.time() - t0) / 60, 2), "decisions": total_dec, "episodes": len(eps),
               "stage": cur.stages[cur.cur], "stage_idx": cur.cur,
               "return": round(float(np.mean([x[2] for x in eps])) if eps else float("nan"), 3),
               "success": round(float(np.mean([x[1] for x in eps])) if eps else float("nan"), 3),
               "noop_frac": round(stats["noop"] / max(1, stats["dec"]), 4),
               "noop_end_frac": round(float(np.mean([x[5] for x in eps])) if eps else float("nan"), 3),
               "decisions_per_ep": round(float(np.mean([x[3] for x in eps])) if eps else float("nan"), 1),
               "game_s_to_success": round(float(np.mean([x[4] for x in eps if x[1]])) if any(x[1] for x in eps) else float("nan"), 1),
               "ms_per_decision": round(1000 * infer_s / max(1, stats["dec"]), 4),
               "sim_ms_per_decision": round(1000 * sim_s / max(1, stats["dec"]), 4),
               "entropy": round(float(np.mean(lst["ent"])), 4), "policy_loss": round(float(np.mean(lst["pg"])), 4),
               "value_loss": round(float(np.mean(lst["vl"])), 4), "approx_kl": round(float(np.mean(lst["kl"])), 5),
               "ent_coef": round(ent_coef, 4),
               "novel": stats["novel"], "novel_total": novel_total, "discoveries": stats["discoveries"],
               "kills": stats["kills"], "spawned": stats["spawned"],
               "repeat_fail_per_ep": round(stats["repeat_fail"] / max(1, len(eps)), 2),
               **{k_: round(v_ / max(1, len(eps)), 3) for k_, v_ in sorted(stats.items()) if k_.startswith("ob_")},
               "credit_linked": cst.get("linked", ""), "credit_moved": round(cst.get("moved", 0.0), 3) if cst else "",
               "credit_lag_s": round(cst.get("lag_s", 0.0), 1) if cst else "", "rudder_loss": "",
               "game_s_per_s": round(float(sum(x[4] for x in eps)) / max(1e-9, sim_s), 1)}
        for s in cur.stages:
            got = [x[1] for x in eps if x[0] == s]
            row["succ_" + s] = round(float(np.mean(got)), 3) if got else ""
            row["n_" + s] = len(got)
        for s in ("waves:easy", "waves:normal", "waves:long"):
            got = [x[7] for x in eps if x[0] == s]
            row["cleared_" + s] = round(float(np.mean(got)), 2) if got else ""
            got = [x[10] for x in eps if x[0] == s]
            row["breaches_" + s] = round(float(np.mean(got)), 2) if got else ""
            got = [x[11] for x in eps if x[0] == s]
            row["mob_s_" + s] = round(float(np.mean(got)), 1) if got else ""
        row["achievements"] = round(float(np.mean([x[9] for x in eps])), 3) if eps else ""
        got = [x[9] for x in eps if x[0] == "achievements"]
        row["achievements_scen"] = round(float(np.mean(got)), 3) if got else ""
        ren = sum(x[12][0] for x in eps if x[0] in ("farm", "pasture", "waves:long"))
        use = sum(x[12][1] for x in eps if x[0] in ("farm", "pasture", "waves:long"))
        row["sustain"] = round(ren / use, 4) if use > 0 else ""
        for c in COMPONENTS:
            got = [x[6].get(c, 0.0) / max(1, x[8]) for x in eps]
            row["comp_" + c] = round(float(np.mean(got)), 4) if got else ""
        if mw is None:
            mw = csv.DictWriter(mcsv, fieldnames=list(row))
            mw.writeheader()
        mw.writerow(row)
        mcsv.flush()
        ecsv.flush()
        if epoch in SNAP:
            write_policy(cpu_copy(pol, a), out / "snapshots" / f"policy-epoch-{epoch}.bin")
        if epoch % 10 == 0 or epoch == 1:
            print(f"ep {epoch} {row['minutes']}m stage {row['stage']} ret {row['return']} succ {row['success']} noop {row['noop_frac']} "
                  f"ent {row['entropy']} kl {row['approx_kl']} dec {total_dec} sim {row['sim_ms_per_decision']}ms/dec "
                  f"infer {row['ms_per_decision']}ms/dec", flush=True)
            save(out / "ckpt.pt", pol, opt, {"epoch": epoch, "curriculum": cur.cur})
        (out / "curriculum.log").write_text("\n".join(cur.log) + "\n", encoding="utf-8")
    mcsv.close()
    ecsv.close()
    if gov is not None:
        gov.stop()
    return epoch, total_dec
