"""The teacher's record (docs/rl/OPEN-WORLD.md section 6): the macro net (v3.1, contract 3.1) plays scenarios on the
engine with {"record_open": true}; every step of 4 ticks of every agent comes back as an open-world observation and
the label of what its body did (RlOpen.Rec). Written to build/rl/open/teacher/<name>.jsonl.gz, a line a step.

    python tools/rl/open_teacher.py record --port 27300 --weights build/rl/teacher/v31eng1.pt --scenarios wood,pickaxe,sword --episodes 6
    python tools/rl/open_teacher.py verify --port 27300 --file build/rl/open/teacher/rec.jsonl.gz
    python tools/rl/open_teacher.py stats --file ...

verify: each recorded episode again on the same seed in the open world (its scenario's kit and time, no waves), the
labels given as the actions, step by step; the macro's results compared: logs in the bag (chop_tree), the wooden
pickaxe (craft), hits on mobs (attack@mob).
"""
from __future__ import annotations

import argparse
import collections
import gzip
import json
import math
import sys
import time
from pathlib import Path

import numpy as np
import torch

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))
from open_env import SPEC, OpenEnv  # noqa: E402
from policy import Policy  # noqa: E402

OUT = ROOT / "build" / "rl" / "open" / "teacher"
VEC = {k: i for i, k in enumerate(SPEC["vec"])}
LOG64 = math.log1p(64)
ACTS = SPEC["heads"]["act"]


def load_teacher(path):
    c = torch.load(path, map_location="cpu", weights_only=False)
    p = Policy(c["d"], c["a"], c["hidden"])
    p.load_state_dict(c["model"])
    p.eval()
    return p


def bag_of(vec, item):
    return int(round(math.expm1(float(vec[VEC["bag_" + item]]) * LOG64)))


def record(port, weights, scenarios, episodes, name, seed0=500, temperature=0.3, max_s=600):
    pol = load_teacher(weights)
    env = OpenEnv(port)
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / (name + ".jsonl.gz")
    n_frames, t0 = 0, time.time()
    with gzip.open(path, "wt", encoding="utf-8") as f:
        for ep in range(episodes):
            sc = scenarios[ep % len(scenarios)]
            seed = seed0 + ep
            r = env.ask({"op": "reset", "scenario": sc, "seed": seed, "crew": 1, "contract": "3.1", "record_open": True})
            frames, chosen, macros = [], collections.Counter(), []
            while True:
                frames += r["info"].get("open", [])
                if r["done"]:
                    break
                acts = {}
                for a, ob in r["obs"].items():
                    o = torch.tensor(np.asarray(ob, np.float32))[None]
                    m = torch.tensor(np.asarray(r["mask"][a], bool))[None]
                    with torch.no_grad():
                        lg = Policy.masked(pol.logits(o) / temperature, m)
                        k = int(torch.distributions.Categorical(logits=lg).sample()[0])
                    acts[a] = k
                    chosen[k] += 1
                r = env.ask({"op": "step", "actions": acts})
                if r["info"].get("ticks", 0) > max_s * 20:
                    break
            for fr in frames:
                fr.update({"ep": ep, "scenario": sc, "seed": seed})
                f.write(json.dumps(fr) + "\n")
            n_frames += len(frames)
            acts = collections.Counter(ACTS[fr["act"][4]] for fr in frames)
            unrep = sum(fr["unrep"] for fr in frames)
            last = frames[-1]["obs"]["vec"] if frames else None
            print(f"ep {ep} {sc} seed {seed}: {r['info'].get('done_reason')} at {r['info'].get('ticks', 0) / 20:.0f} s, {len(frames)} steps "
                  f"({unrep} unrep), acts {dict(acts.most_common(6))}, logs {bag_of(last, 'log') if last else '-'}, "
                  f"wooden_pickaxe {bag_of(last, 'wooden_pickaxe') if last else '-'}, stone_pickaxe {bag_of(last, 'stone_pickaxe') if last else '-'}", flush=True)
    env.close()
    print(f"{n_frames} steps in {time.time() - t0:.0f} s -> {path}")
    return path


def read(path):
    eps = collections.defaultdict(list)
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            fr = json.loads(line)
            eps[(fr["ep"], fr["agent"])].append(fr)
    return eps


def outcome(vec, hits):
    return {"logs": bag_of(vec, "log"), "planks": bag_of(vec, "planks"), "wooden_pickaxe": bag_of(vec, "wooden_pickaxe"),
            "stone_pickaxe": bag_of(vec, "stone_pickaxe"), "cobblestone": bag_of(vec, "cobblestone"), "hits": hits}


PUSH, KEEP, STEP = 0.098, 0.546, 4
MOVES = [(0, 0), (1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (1, -1), (-1, 1), (-1, -1)]
YAWS, PITCHES = SPEC["heads"]["yaw"], SPEC["heads"]["pitch"]


def yaw_pitch(vec):
    return math.degrees(math.atan2(vec[VEC["yaw_sin"]], vec[VEC["yaw_cos"]])), vec[VEC["pitch"]] * 90


def track(frame_next, here, vel, vec, label):
    """the tracking replay's action: the teacher's hands and jump, the legs and the head toward where the teacher is at
    the next step's start (a player's walk with its momentum, OpenSpec.Labeler's model)"""
    tgt = frame_next["pos"]
    wx, wz = tgt[0] - here[0], tgt[2] - here[2]
    yaw, pitch = yaw_pitch(vec)
    ty, tp = yaw_pitch(frame_next["obs"]["vec"])
    y = math.radians(yaw)
    fx, fz = -math.sin(y), math.cos(y)
    lx, lz = fz, -fx
    best, bd, bv = 0, 1e18, vel
    for k, (f, l) in enumerate(MOVES):
        n = max(1.0, math.hypot(f, l))
        ux, uz = (f * fx + l * lx) / n, (f * fz + l * lz) / n
        px = pz = 0.0
        qx, qz = vel
        for _ in range(STEP):
            qx += PUSH * ux; qz += PUSH * uz; px += qx; pz += qz; qx *= KEEP; qz *= KEEP
        d = math.hypot(wx - px, wz - pz)
        if d < bd:
            bd, best, bv = d, k, (qx, qz)
    dy = (ty - yaw + 180) % 360 - 180
    yi = min(range(len(YAWS)), key=lambda i: abs(YAWS[i] - dy))
    pi = min(range(len(PITCHES)), key=lambda i: abs(PITCHES[i] - (tp - pitch)))
    jump = label[1] if label[1] else (1 if tgt[1] > here[1] + 0.4 else 0)
    return [best, jump, yi, pi, label[4]], bv


def verify(port, path, limit=0, tracking=False):
    env = OpenEnv(port)
    eps = read(path)
    rows = []
    for (ep, agent), frames in sorted(eps.items()):
        if limit and len(rows) >= limit:
            break
        fr0 = frames[0]
        o = env.reset(seed=fr0["seed"], scenario=fr0["scenario"], waves="off", days=5)
        ag = o["agents"][0]
        hits_teacher = sum(1 for fr in frames if ACTS[fr["act"][4]] == "attack" and "attack@" in fr.get("macro", ""))
        hits, vel, broke_logs = 0, (0.0, 0.0), 0
        for i, fr in enumerate(frames):
            act = fr["act"]
            if tracking and i + 1 < len(frames):
                here = next(np.array([e["x"], e["y"], e["z"]]) for e in o["frame"]["events"] if e["type"] == "agent" and e["agent"] == ag)
                act, vel = track(frames[i + 1], here, vel, o["obs"][ag]["vec"], fr["act"])
            o = env.step({ag: act})
            if o["done"] or ag not in o["obs"]:
                break
            last = o["info"]["last"].get(ag, "")
            hits += last.startswith("hit")
            broke_logs += last in ("broke log", "broke log2")
        if ag in o["obs"]:
            replay = outcome(o["obs"][ag]["vec"], hits)
            replay["logs_broken"] = broke_logs
        else:
            replay = {"died": True}
        # the teacher's end: the last frame's observation is at the start of its last step (close enough to the end)
        teacher = outcome(frames[-1]["obs"]["vec"], sum(1 for fr in frames if fr.get("what", "").startswith("hit")))
        unrep = sum(fr["unrep"] for fr in frames)
        print(f"ep {ep} {fr0['scenario']} seed {fr0['seed']}: {len(frames)} steps ({unrep} unrep); teacher {teacher}; replay {replay}", flush=True)
        rows.append({"ep": ep, "scenario": fr0["scenario"], "steps": len(frames), "unrep": unrep, "teacher": teacher, "replay": replay})
    env.close()
    return rows


def stats(path):
    eps = read(path)
    heads = [collections.Counter() for _ in range(5)]
    n = un = 0
    by_macro = collections.Counter()
    for frames in eps.values():
        for fr in frames:
            n += 1
            un += fr["unrep"]
            by_macro[fr.get("macro", "")] += 1
            for i, x in enumerate(fr["act"]):
                heads[i][x] += 1
    print(f"{len(eps)} episodes, {n} steps, {un} ({un / max(1, n):.1%}) not representable")
    for i, name in enumerate(SPEC["heads"]["names"]):
        lab = SPEC["heads"][name] if name in SPEC["heads"] else None
        print(name, {(lab[k] if lab else k): v for k, v in heads[i].most_common(10)})
    print("macros", dict(by_macro.most_common(12)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("what", choices=["record", "verify", "stats"])
    ap.add_argument("--port", type=int, default=27300)
    ap.add_argument("--weights", default=str(ROOT / "build" / "rl" / "teacher" / "v31eng1.pt"))
    ap.add_argument("--scenarios", default="wood,pickaxe,sword")
    ap.add_argument("--episodes", type=int, default=6)
    ap.add_argument("--name", default="rec")
    ap.add_argument("--seed", type=int, default=500)
    ap.add_argument("--file", default=str(OUT / "rec.jsonl.gz"))
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--track", action="store_true", help="verify: the legs and the head steered to the teacher's next position (the hands as recorded)")
    a = ap.parse_args()
    if a.what == "record":
        record(a.port, a.weights, a.scenarios.split(","), a.episodes, a.name, a.seed)
    elif a.what == "verify":
        verify(a.port, a.file, a.limit, a.track)
    else:
        stats(a.file)


if __name__ == "__main__":
    main()
