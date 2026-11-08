"""PPO with action masking for the crew's policy: free choice from the first step (no teacher), noop allowed and
punished (-1, the world restarts), a curriculum of scenarios, rewards normalised, an optional potential-based
progress bonus (the simulator's, gamma*phi' - phi: it does not change which policy is best).

    python tools/rl/train.py --minutes 30 --run sim1                       # the simulator, on the GPU
    python tools/rl/train.py --minutes 30 --run real1 --env real --init build/rl/runs/sim1/final.pt   # rl/env's servers

Everything goes to build/rl/runs/<run>/: metrics.csv (a row an epoch), episodes.csv, curriculum.log, ckpt.pt,
final.pt, snapshots/policy-epoch-N.bin (the weights after epoch N, for "the agent after epoch 1 / 10").
An epoch is one PPO iteration: a rollout of --rollout decisions over all the envs, then --epochs passes over it.
The CPU is kept light: torch on 2 threads, the simulator in --workers processes at below-normal priority.
"""
from __future__ import annotations

import argparse
import collections
import csv
import json
import multiprocessing as mp
import os
import sys
import time
from pathlib import Path

import numpy as np
import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import spec  # noqa: E402
from policy import Policy  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
RUNS = ROOT / "build" / "rl" / "runs"
STAGES = list(spec.SCEN)   # rl/env's scenarios in the contract's order (siege: not there yet)
# the crew of a scenario's episode: drawn from these sizes
CREW = {"wood": (1, 2), "pickaxe": (1, 2), "sword": (1, 2), "iron": (1, 2), "food": (1, 2), "fortify": (1, 3),
        "waves:easy": (2, 4), "waves:normal": (2, 4)}
COMPONENTS = sorted({k.split(".")[0] for k in spec.WEIGHTS} | {"shaping"})
SNAP = {1, 2, 3, 5, 10, 15, 20, 30, 50, 75, 100, 150, 200, 300, 400, 500, 750, 1000, 1500, 2000, 3000}


def below_normal():
    """This process and its children at below-normal priority (Windows), so the owner's PC does not lag."""
    if os.name == "nt":
        import ctypes
        k = ctypes.windll.kernel32
        k.GetCurrentProcess.restype = ctypes.c_void_p   # the pseudo-handle -1: an int return would cut it
        k.SetPriorityClass.argtypes = [ctypes.c_void_p, ctypes.c_uint32]
        k.SetPriorityClass(k.GetCurrentProcess(), 0x4000)   # BELOW_NORMAL_PRIORITY_CLASS
    else:
        os.nice(5)


# ---- the curriculum -----------------------------------------------------------------------------------------

class Curriculum:
    """The current stage most of the time, the earlier ones a `mix` share (so they are not forgotten), the next one a
    `peek` share (a look ahead: its first successes come before it is the stage); on to the next stage when the
    current one's success over its last `window` episodes reaches `threshold`."""

    def __init__(self, stages, threshold=0.8, window=200, mix=0.3, start=0, rng=None, peek=0.15):
        self.stages, self.threshold, self.window, self.mix, self.peek = stages, threshold, window, mix, peek
        self.cur = start
        self.hist = {s: collections.deque(maxlen=window) for s in stages}
        self.rng = rng or np.random.default_rng(0)
        self.log: list[str] = []

    def sample(self) -> str:
        u = self.rng.random()
        if self.cur + 1 < len(self.stages) and u < self.peek:
            return self.stages[self.cur + 1]
        if self.cur > 0 and u > 1 - self.mix:
            return self.stages[int(self.rng.integers(0, self.cur))]
        return self.stages[self.cur]

    def record(self, scenario: str, success: bool, epoch: int) -> bool:
        h = self.hist[scenario]
        h.append(1.0 if success else 0.0)
        s = self.stages[self.cur]
        if scenario == s and len(h) >= self.window and np.mean(h) >= self.threshold and self.cur + 1 < len(self.stages):
            self.cur += 1
            self.log.append(f"epoch {epoch}: {s} passed ({np.mean(h):.2f} over {len(h)}), now {self.stages[self.cur]}")
            return True
        return False

    def rate(self, s):
        h = self.hist[s]
        return float(np.mean(h)) if h else float("nan")


# ---- the envs: in worker processes ----------------------------------------------------------------------------

def worker(conn, kind: str, items: list, shaping: float):
    below_normal()
    torch.set_num_threads(1)
    if kind == "sim":
        from sim_env import SimEnv
        envs = [SimEnv(s, shaping=shaping) for s in items]
    else:   # rl/env's servers, one an env: host:port
        from real_env import RealEnv
        envs = [RealEnv(addr) for addr in items]
    while True:
        cmd, arg = conn.recv()
        if cmd == "reset":
            conn.send([e.reset(*job) for e, job in zip(envs, arg)])
        elif cmd == "reset1":
            i, job = arg
            conn.send(envs[i].reset(*job))
        elif cmd == "step":
            out = {}
            for i, acts in arg.items():
                out[i] = envs[i].step(acts)
            conn.send(out)
        elif cmd == "close":
            for e in envs:
                if hasattr(e, "close_env"):
                    e.close_env()
            conn.send(None)
            return


def strip(o):
    o.pop("raw", None)
    return o


class Envs:
    """N envs across W worker processes; env e lives in worker e % W."""

    def __init__(self, n: int, workers: int, kind: str, seed: int, shaping: float, url=None):
        addrs = []
        if kind == "real":   # one env a server
            addrs = [x.strip() for x in (url or "").split(",") if x.strip()]
            n, workers = len(addrs), max(1, min(workers, len(addrs)))
        self.n, self.w = n, workers
        ctx = mp.get_context("spawn")
        self.conns, self.procs = [], []
        for k in range(workers):
            mine = [(seed * 1000 + e if kind == "sim" else addrs[e]) for e in range(n) if e % workers == k]
            a, b = ctx.Pipe()
            p = ctx.Process(target=worker, args=(b, kind, mine, shaping), daemon=True)
            p.start()
            self.conns.append(a)
            self.procs.append(p)

    def local(self, e):
        return e % self.w, e // self.w

    def reset_all(self, jobs):   # jobs[e] = (scenario, seed, crew)
        per = [[] for _ in range(self.w)]
        for e, j in enumerate(jobs):
            per[e % self.w].append(j)
        for k, c in enumerate(self.conns):
            c.send(("reset", per[k]))
        res = [None] * self.n
        for k, c in enumerate(self.conns):
            for li, o in enumerate(c.recv()):
                res[li * self.w + k] = strip(o)
        return res

    def step(self, acts: dict[int, dict[int, int]], resets: dict[int, tuple]):
        """acts[e] = {agent: action}; resets[e] = (scenario, seed, crew) for envs to reset instead; -> results by env"""
        per = [dict() for _ in range(self.w)]
        for e, a in acts.items():
            per[e % self.w][e // self.w] = a
        for k, c in enumerate(self.conns):
            c.send(("step", per[k]))
        out = {}
        for k, c in enumerate(self.conns):
            for li, o in c.recv().items():
                out[li * self.w + k] = strip(o)
        for e, job in resets.items():
            k, li = self.local(e)
            self.conns[k].send(("reset1", (li, job)))
            out[e] = strip(self.conns[k].recv())
        return out

    def close(self):
        for c in self.conns:
            try:
                c.send(("close", None))
                c.recv()
            except Exception:
                pass
        for p in self.procs:
            p.join(timeout=5)


# ---- PPO ------------------------------------------------------------------------------------------------------

class RetNorm:
    """Rewards scaled by the running std of the discounted return (per stream), as gym's NormalizeReward."""

    def __init__(self, gamma):
        self.g = gamma
        self.ret: dict = {}
        self.mean, self.var, self.count = 0.0, 1.0, 1e-4

    def __call__(self, key, r, done):
        x = self.ret.get(key, 0.0) * self.g + r
        self.ret[key] = 0.0 if done else x
        self.count += 1
        d = x - self.mean
        self.mean += d / self.count
        self.var += (d * (x - self.mean) - self.var) / self.count
        return r / np.sqrt(self.var + 1e-8)


def ppo_loss(pol, obs, mask, act, logp_old, adv, ret, clip, vf, ent_coef):
    """PPO's clipped surrogate over the masked distribution, the value's squared error, the entropy bonus"""
    lp, ent, v = pol.evaluate(obs, mask, act)
    ratio = torch.exp(lp - logp_old)
    pg = -torch.min(ratio * adv, torch.clamp(ratio, 1 - clip, 1 + clip) * adv).mean()
    vl = 0.5 * ((v - ret) ** 2).mean()
    return pg + vf * vl - ent_coef * ent.mean(), pg, vl, ent, ratio


def save(path, pol, opt, extra):
    torch.save({"model": pol.state_dict(), "opt": opt.state_dict() if opt else None, "d": pol.d, "a": pol.a,
                "hidden": pol.hidden, **extra}, path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", default="sim1")
    ap.add_argument("--env", default="sim", choices=["sim", "real"])
    ap.add_argument("--url", default=None, help="real: host:port[,host:port...] of rl/env's servers")
    ap.add_argument("--minutes", type=float, default=30)
    ap.add_argument("--max-epochs", type=int, default=100000)
    ap.add_argument("--envs", type=int, default=96)
    ap.add_argument("--workers", type=int, default=3)
    ap.add_argument("--rollout", type=int, default=8192)
    ap.add_argument("--epochs", type=int, default=4)
    ap.add_argument("--minibatch", type=int, default=1024)
    ap.add_argument("--lr", type=float, default=3e-4)
    ap.add_argument("--gamma", type=float, default=0.99)
    ap.add_argument("--lam", type=float, default=0.95)
    ap.add_argument("--clip", type=float, default=0.2)
    ap.add_argument("--vf", type=float, default=0.5)
    ap.add_argument("--ent", type=float, default=0.05)
    ap.add_argument("--ent-end", type=float, default=0.01)
    ap.add_argument("--max-grad", type=float, default=0.5)
    ap.add_argument("--hidden", type=int, default=256)
    ap.add_argument("--shaping", type=float, default=0.0, help="the simulator's progress bonus (0: off)")
    ap.add_argument("--threshold", type=float, default=0.8)
    ap.add_argument("--window", type=int, default=200)
    ap.add_argument("--peek", type=float, default=0.15, help="share of episodes of the NEXT stage (a look ahead)")
    ap.add_argument("--stages", default=",".join(STAGES))
    ap.add_argument("--start-stage", type=int, default=0)
    ap.add_argument("--init", default=None, help="a checkpoint to start from")
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--threads", type=int, default=2)
    a = ap.parse_args()

    below_normal()
    torch.set_num_threads(a.threads)
    torch.manual_seed(a.seed)
    dev = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    out = RUNS / a.run
    (out / "snapshots").mkdir(parents=True, exist_ok=True)
    (out / "args.json").write_text(json.dumps(vars(a), indent=1), encoding="utf-8")
    from export_policy import write_policy

    pol = Policy(spec.D, spec.A, a.hidden)
    if a.init:
        ck = torch.load(a.init, map_location="cpu", weights_only=False)
        pol.load_state_dict(ck["model"])
    pol.to(dev)
    opt = torch.optim.Adam(pol.parameters(), lr=a.lr, eps=1e-5)
    rng = np.random.default_rng(a.seed)
    cur = Curriculum(a.stages.split(","), a.threshold, a.window, start=a.start_stage, rng=rng, peek=a.peek)
    envs = Envs(a.envs, a.workers, a.env, a.seed, a.shaping, a.url)
    retnorm = RetNorm(a.gamma)

    def job():
        sc = cur.sample()
        lo, hi = CREW.get(sc, (1, 1))
        return sc, int(rng.integers(1 << 30)), int(rng.integers(lo, hi + 1))

    res = envs.reset_all([job() for _ in range(envs.n)])
    ep_info = [{"ret": 0.0, "dec": 0, "noop": 0} for _ in range(envs.n)]
    pending: dict = {}      # (e, agent) -> [obs, mask, act, logp, val, rew, done] waiting for its next value
    waiting: dict = {}      # (e, agent) -> the transition whose reward came, its next value not yet
    streams: dict = collections.defaultdict(list)

    mcsv = open(out / "metrics.csv", "w", newline="", encoding="utf-8")
    ecsv = open(out / "episodes.csv", "w", newline="", encoding="utf-8")
    ew = csv.writer(ecsv)
    ew.writerow(["epoch", "scenario", "success", "return", "decisions", "game_s", "noop_end", "agents", "cleared", "done_reason"])
    mw = None
    t0 = time.time()
    epoch = 0
    total_dec = 0
    while epoch < a.max_epochs and time.time() - t0 < a.minutes * 60:
        epoch += 1
        frac = min(1.0, (time.time() - t0) / (a.minutes * 60))
        ent_coef = a.ent + (a.ent_end - a.ent) * frac
        n_done = 0
        stats = collections.Counter()
        eps = []
        infer_s = 0.0
        sim_s = 0.0
        pol.eval()
        while n_done < a.rollout:
            # every env's agents at a decision now: one batch
            keys, obs, msk = [], [], []
            for e, o in enumerate(res):
                for ag, v, m in zip(o["agents"], o["obs"], o["mask"]):
                    keys.append((e, ag))
                    obs.append(v)
                    msk.append(m)
            ti = time.perf_counter()
            ot = torch.tensor(np.asarray(obs, np.float32), device=dev)
            mt = torch.tensor(np.asarray(msk, bool), device=dev)
            pol.norm.update(ot)
            with torch.no_grad():
                act, logp, val, _ = pol.act(ot, mt)
            act, logp, val = act.cpu().numpy(), logp.cpu().numpy(), val.cpu().numpy()
            infer_s += time.perf_counter() - ti
            acts = collections.defaultdict(dict)
            for j, k in enumerate(keys):
                if k in waiting:   # its last step's next value is this one
                    tr = waiting.pop(k)
                    tr[7] = float(val[j])
                    streams[k].append(tr)
                    n_done += 1
                pending[k] = [obs[j], msk[j], int(act[j]), float(logp[j]), float(val[j]), 0.0, False, 0.0]
                acts[k[0]][k[1]] = int(act[j])
                stats["dec"] += 1
                if act[j] == 0:
                    stats["noop"] += 1
                ep_info[k[0]]["dec"] += 1
            ts = time.perf_counter()
            res2 = envs.step(dict(acts), {})
            resets = {}
            for e, o in res2.items():
                for ag, r in zip(o["agents"], o["reward"]):
                    k = (e, ag)
                    ep_info[e]["ret"] += r
                    if k not in pending:
                        continue
                    tr = pending.pop(k)
                    tr[5] = retnorm(k, r, o["done"])
                    tr[6] = o["done"]
                    if o["done"]:
                        streams[k].append(tr)
                        n_done += 1
                    else:
                        waiting[k] = tr
                if o["done"]:
                    for k in [k for k in pending if k[0] == e]:   # an agent mid-step when the world ended
                        tr = pending.pop(k)
                        tr[6] = True
                        streams[k].append(tr)
                        n_done += 1
                    for k in [k for k in waiting if k[0] == e]:
                        tr = waiting.pop(k)
                        tr[6] = True
                        streams[k].append(tr)
                    inf = o["info"]
                    sc = inf["scenario"]
                    noop_end = inf.get("done_reason") == "noop"
                    na = len(o["agents"])
                    gs = inf.get("t", inf.get("ticks", 0) / 20.0)
                    eps.append((sc, bool(inf["success"]), ep_info[e]["ret"] / max(1, na), ep_info[e]["dec"], gs, noop_end,
                                inf.get("episode_components", {}), inf.get("cleared", 0), na))
                    ew.writerow([epoch, sc, int(inf["success"]), round(ep_info[e]["ret"] / max(1, na), 3), ep_info[e]["dec"], round(gs, 1),
                                 int(noop_end), na, inf.get("cleared", 0), inf.get("done_reason", "")])
                    if cur.record(sc, inf["success"], epoch):
                        print(cur.log[-1], flush=True)
                    ep_info[e] = {"ret": 0.0, "dec": 0, "noop": 0}
                    resets[e] = job()
            if resets:
                res2.update(envs.step({}, resets))
            for e, o in res2.items():
                res[e] = o
            sim_s += time.perf_counter() - ts
        # ---- the batch: GAE per stream (an agent of an env), cut at the rollout's end with its next value -------
        B_obs, B_msk, B_act, B_logp, B_adv, B_ret = [], [], [], [], [], []
        for k, L in streams.items():
            adv = 0.0
            for i in range(len(L) - 1, -1, -1):
                o_, m_, ac, lp, v, r, d, nv = L[i]
                nonterm = 0.0 if d else 1.0
                delta = r + a.gamma * nv * nonterm - v
                cont = i + 1 < len(L)
                adv = delta + a.gamma * a.lam * nonterm * (adv if cont else 0.0)
                B_obs.append(o_)
                B_msk.append(m_)
                B_act.append(ac)
                B_logp.append(lp)
                B_adv.append(adv)
                B_ret.append(adv + v)
        streams.clear()
        pol.train()
        bo = torch.tensor(np.asarray(B_obs, np.float32), device=dev)
        bm = torch.tensor(np.asarray(B_msk, bool), device=dev)
        ba = torch.tensor(B_act, device=dev)
        blp = torch.tensor(B_logp, device=dev)
        badv = torch.tensor(B_adv, device=dev, dtype=torch.float32)
        bret = torch.tensor(B_ret, device=dev, dtype=torch.float32)
        badv = (badv - badv.mean()) / (badv.std() + 1e-8)
        n = bo.shape[0]
        lst = collections.defaultdict(list)
        for _ in range(a.epochs):
            perm = torch.randperm(n, device=dev)
            for s in range(0, n, a.minibatch):
                idx = perm[s:s + a.minibatch]
                loss, pg, vl, ent, ratio = ppo_loss(pol, bo[idx], bm[idx], ba[idx], blp[idx], badv[idx], bret[idx], a.clip, a.vf, ent_coef)
                opt.zero_grad()
                loss.backward()
                torch.nn.utils.clip_grad_norm_(pol.parameters(), a.max_grad)
                opt.step()
                with torch.no_grad():
                    lst["pg"].append(pg.item())
                    lst["vl"].append(vl.item())
                    lst["ent"].append(ent.mean().item())
                    lst["kl"].append(((ratio - 1) - torch.log(ratio)).mean().item())
        total_dec += stats["dec"]
        # ---- the epoch's row ---------------------------------------------------------------------------------
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
               "ent_coef": round(ent_coef, 4)}
        for s in cur.stages:
            got = [x[1] for x in eps if x[0] == s]
            row["succ_" + s] = round(float(np.mean(got)), 3) if got else ""
            row["n_" + s] = len(got)
        for s in ("waves:easy", "waves:normal"):
            got = [x[7] for x in eps if x[0] == s]
            row["cleared_" + s] = round(float(np.mean(got)), 2) if got else ""
        for c in COMPONENTS:   # the reward by component, a mean an agent an episode
            got = [x[6].get(c, 0.0) / max(1, x[8]) for x in eps]
            row["comp_" + c] = round(float(np.mean(got)), 4) if got else ""
        if mw is None:
            mw = csv.DictWriter(mcsv, fieldnames=list(row))
            mw.writeheader()
        mw.writerow(row)
        mcsv.flush()
        ecsv.flush()
        if epoch in SNAP:
            write_policy(_cpu_copy(pol, a), out / "snapshots" / f"policy-epoch-{epoch}.bin")
        if epoch % 10 == 0 or epoch == 1:
            print(f"ep {epoch} {row['minutes']}m stage {row['stage']} ret {row['return']} succ {row['success']} noop {row['noop_frac']} "
                  f"ent {row['entropy']} kl {row['approx_kl']} dec {total_dec}", flush=True)
            save(out / "ckpt.pt", pol, opt, {"epoch": epoch, "curriculum": cur.cur})
        (out / "curriculum.log").write_text("\n".join(cur.log) + "\n", encoding="utf-8")
    save(out / "final.pt", pol, opt, {"epoch": epoch, "curriculum": cur.cur})
    write_policy(_cpu_copy(pol, a), out / "snapshots" / f"policy-epoch-{epoch}.bin")
    write_policy(_cpu_copy(pol, a), out / "final.bin")
    summary = {"epochs": epoch, "minutes": round((time.time() - t0) / 60, 2), "decisions": total_dec, "stage": cur.stages[cur.cur],
               "rates": {s: cur.rate(s) for s in cur.stages}, "curriculum": cur.log}
    (out / "summary.json").write_text(json.dumps(summary, indent=1), encoding="utf-8")
    print(json.dumps(summary, indent=1))
    envs.close()
    mcsv.close()
    ecsv.close()


def _cpu_copy(pol, a):
    p = Policy(spec.D, spec.A, a.hidden)
    p.load_state_dict({k: v.detach().cpu() for k, v in pol.state_dict().items()})
    return p.eval()


if __name__ == "__main__":
    main()
