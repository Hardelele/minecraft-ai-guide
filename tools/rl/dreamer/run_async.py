"""The trainer with the collector in its own process (tools/rl/dreamer/collect.py: several arenas at once, their agents in
one batch), and the owner's view of the run: build/rl/runs/<name>/metrics.csv every ~2 min, curriculum.log (read by
aiguide/tools/rl/progress_tg.py, sent to Telegram every 30 min). Called by train.py --async.
"""
from __future__ import annotations

import csv
import json
import os
import time
from pathlib import Path

import numpy as np
import torch

HERE = Path(__file__).resolve().parent


class Progress:
    """metrics.csv: epoch (100 updates), minutes, decisions (env steps), stage, noop_end_frac (0: the open world has no
    noop), succ_/n_ of the episodes ended since the last row: wood (mineWood), pickaxe (buildPickaxe), night (alive all
    night, no breach; of the episodes that saw a night); curriculum.log: the first of each and the stages"""
    KEYS = ["epoch", "minutes", "decisions", "stage", "noop_end_frac", "succ_wood", "n_wood", "succ_pickaxe", "n_pickaxe", "succ_night", "n_night",
            "updates", "env_steps_per_s", "updates_per_s", "return_mean", "wm_loss", "bc_nll", "entropy"]

    def __init__(self, name):
        self.dir = HERE.parents[2] / "build" / "rl" / "runs" / name
        self.dir.mkdir(parents=True, exist_ok=True)
        new = not (self.dir / "metrics.csv").exists()
        self.f = open(self.dir / "metrics.csv", "a", newline="", encoding="utf8")
        self.w = csv.DictWriter(self.f, self.KEYS, extrasaction="ignore")
        if new:
            self.w.writeheader()
        self.eps, self.firsts = [], set()
        self.m0 = self.d0 = 0.0   # a resumed run: its minutes and decisions go on from the last row
        if not new:
            rows = list(csv.DictReader(open(self.dir / "metrics.csv", encoding="utf8")))
            if rows:
                self.m0, self.d0 = float(rows[-1]["minutes"] or 0), float(rows[-1]["decisions"] or 0)
        cl = self.dir / "curriculum.log"
        for line in cl.read_text(encoding="utf8").splitlines() if cl.exists() else []:
            for k in ("wood", "pickaxe", "night"):
                if line.startswith("достиг " + k + " "):
                    self.firsts.add(k)

    @staticmethod
    def marks(rec):
        ach = set(rec["achievements"])
        m = {"wood": "mineWood" in ach, "pickaxe": "buildPickaxe" in ach}
        if rec.get("night_reached"):
            m["night"] = bool(rec["survived_night"])
        return m

    def episode(self, rec, steps):
        self.eps.append(rec)
        for k, ok in self.marks(rec).items():
            if ok and k not in self.firsts:
                self.firsts.add(k)
                self.note(f"достиг {k} на шаге {steps} (эпизод {rec['episode']} {rec['kind']}, этап {rec['stage']})")

    def note(self, line):
        with open(self.dir / "curriculum.log", "a", encoding="utf8") as f:
            f.write(line + " — " + time.strftime("%Y-%m-%d %H:%M") + "\n")

    def row(self, updates, minutes, steps, stage, sps, ups, m):
        r = {"epoch": updates // 100, "minutes": round(self.m0 + minutes, 1), "decisions": int(self.d0 + steps), "stage": stage, "noop_end_frac": 0.0, "updates": updates,
             "env_steps_per_s": round(sps, 1), "updates_per_s": round(ups, 2), "wm_loss": round(m.get("wm/loss", 0), 3),
             "bc_nll": round(m.get("bc/nll", 0), 3), "entropy": round(m.get("ac/entropy", 0), 3)}
        for k in ("wood", "pickaxe", "night"):
            got = [self.marks(e)[k] for e in self.eps if k in self.marks(e)]
            if got:
                r["succ_" + k], r["n_" + k] = round(sum(got) / len(got), 3), len(got)
        if self.eps:
            r["return_mean"] = round(float(np.mean([np.mean(list(e["return"].values()) or [0]) for e in self.eps])), 3)
        self.w.writerow(r)
        self.f.flush()
        self.eps = []


def push_weights(agent, q_w):
    sd = {k: v.detach().to("cpu", copy=True) for k, v in agent.state_dict().items() if k.startswith(("enc.", "rssm.", "actor."))}
    try:
        while True:
            q_w.get_nowait()
    except Exception:
        pass
    q_w.put(sd)


def main_async(a, agent, cfg, rep, teach, out, t0, updates, log):
    import multiprocessing as mp
    from dreamer import collect
    ctx = mp.get_context("spawn")
    q_out, q_w = ctx.Queue(maxsize=256), ctx.Queue(maxsize=1)
    stage_val, stop = ctx.Value("i", collect.STAGES.index(a.stage)), ctx.Event()
    allowed = ctx.Value("q", a.warmup + a.lead * a.train_every)
    active = ctx.Value("i", len(a.ports.split(",")))   # the arenas stepped (the governor's advice)   # the collector's steps the trainer allows (warmup, then a lead of updates)
    ports = [int(p) for p in a.ports.split(",")]
    proc = ctx.Process(target=collect.run, args=(ports, [a.env] * len(ports), cfg, q_out, q_w, stage_val, stop, a.seed, a.device, allowed, active), daemon=True)
    push_weights(agent, q_w)
    proc.start()
    prog = Progress(out.name)
    prog.note(f"старт {out.name}: этап {a.stage}, {len(ports)} арен, от {a.init or 'нуля'}")
    ef = open(out / "episodes.jsonl", "a")
    env_steps, upd0, m = 0, updates, {}
    last_row = last_snap = rate_t = time.time()
    rate_steps, rate_upd = 0, updates
    pick = []
    adv_file, adv_t, duty = os.environ.get("AIGUIDE_GOVERNOR"), 0.0, 1.0
    while env_steps < a.steps:
        if adv_file and time.time() - adv_t > 10:   # the governor's advice: threads, arenas, the GPU's duty (League: Idle, 1 thread, 1 arena, a quarter)
            adv_t = time.time()
            try:
                adv = json.load(open(adv_file, encoding="utf-8"))
                torch.set_num_threads(max(1, int(adv.get("threads", 1))))
                active.value = max(1, min(len(ports), int(adv.get("arenas", len(ports)))))
                duty = max(0.05, float(adv.get("gpu_duty", 1.0)))
            except Exception:
                pass
        got = 0
        while got < 400:   # what the collector sent
            try:
                msg = q_out.get(timeout=0.002 if got or rep.size < a.warmup or env_steps / a.train_every > updates - upd0 else 0.2)
            except Exception:
                break
            got += 1
            if msg[0] == "steps":
                for _, stream, ob, act, r, first, mask in msg[1]:
                    rep.add(stream, ob, act, r, 1.0, first, mask)
                env_steps += len(msg[1])
            elif msg[0] == "end":
                rep.end(msg[1], msg[2], msg[3])
            else:
                rec = msg[1]
                ef.write(json.dumps(rec) + "\n")
                ef.flush()
                prog.episode(rec, env_steps)
                print(f"episode {rec['episode']} ({rec['kind']}, {rec['stage']}): {rec['done_reason']}, {rec['steps']} steps, return {rec['return']}, "
                      f"achievements {rec['achievements']}, night {rec['survived_night']}", flush=True)
                if rec["stage"] == "pickaxe":
                    pick.append("buildPickaxe" in rec["achievements"])
                    if collect.STAGES[stage_val.value] == "pickaxe" and len(pick) >= 20 and sum(pick[-20:]) >= 10:
                        stage_val.value = collect.STAGES.index("night")
                        prog.note(f"этап night с шага {env_steps}: кирка в {sum(pick[-20:])} из 20 последних эпизодов")
        if not proc.is_alive():
            print("the collector died", flush=True)
            break
        if rep.size >= a.warmup and env_steps / a.train_every > updates - upd0:
            b = rep.sample(a.batch, a.seq)
            if b is None and updates == upd0:
                print(f"no sequence yet: replay {rep.size}", flush=True)
            if b is not None:
                k = updates - a.bc_steps
                w = a.bc_final + (a.bc_weight - a.bc_final) * max(0.0, 1 - k / max(1, a.bc_decay)) if teach is not None else 0.0
                bb = teach.sample(a.bc_batch, a.seq) if teach is not None else None
                tu = time.time()
                m = agent.train_step(b, bc_batch=bb, bc_weight=w)
                m["bc/weight"] = w
                if duty < 1.0:   # the GPU a part of the time only
                    time.sleep(min(5.0, (time.time() - tu) * (1 / duty - 1)))
                updates += 1
                allowed.value = max(allowed.value, a.warmup + (updates - upd0 + a.lead) * a.train_every)
                if updates % 10 == 0:
                    push_weights(agent, q_w)
                if updates % 25 == 0:
                    log(m, "rl", env_steps, updates)
                    print(f"update {updates} steps {env_steps}: wm {m['wm/loss']:.2f} rew {m['wm/rew']:.3f} ret {m.get('ac/ret', 0):.3f} cur {m.get('ac/curiosity', 0):.4f} "
                          f"ent {m.get('ac/entropy', 0):.2f} bc {m.get('bc/nll', 0):.2f} ({time.time() - t0:.0f} s)", flush=True)
                if updates % 500 == 0:
                    torch.save({"model": agent.state_dict(), "cfg": cfg, "updates": updates}, out / "ckpt.pt")
        now = time.time()
        if now - last_row >= 120:
            dt = now - rate_t
            prog.row(updates, (now - t0) / 60, env_steps, collect.STAGES[stage_val.value], (env_steps - rate_steps) / dt, (updates - rate_upd) / dt, m)
            print(f"rate: {(env_steps - rate_steps) / dt:.1f} env steps/s, {(updates - rate_upd) / dt:.2f} updates/s", flush=True)
            last_row, rate_t, rate_steps, rate_upd = now, now, env_steps, updates
        if now - last_snap >= 1800:
            torch.save({"model": agent.state_dict(), "cfg": cfg, "updates": updates}, out / f"snap-{updates}.pt")
            last_snap = now
    stop.set()
    torch.save({"model": agent.state_dict(), "cfg": cfg, "updates": updates}, out / "ckpt.pt")
    proc.join(30)
    return env_steps, updates
