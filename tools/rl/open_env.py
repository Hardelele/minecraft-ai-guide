"""The open world's environment on the engine (docs/rl/OPEN-WORLD.md), the learner's side: the protocol's client
(reset {"mode":"open"}, step with five heads an agent), the observation as arrays, and the arenas started on ports of
our own (engine_env's host; never 262xx: other agents' ports).

    python tools/rl/open_env.py start --n 1 --base-port 27300    # one arena (engine_env's host)
    python tools/rl/open_env.py random --port 27300 --steps 2000  # a random policy: nothing falls over
    python tools/rl/open_env.py stop --base-port 27300 --n 1

OpenEnv(port).reset(seed=…, crew=1, time=0, days=1) -> Obs; .step({agent: [move, jump, yaw, pitch, act]}) -> Obs.
Obs: agents (ids), obs {id: {vox uint8[Y,Z,X], seen bool[Y,Z,X], vec f32[V], ent f32[E,F], hot int[9]}}, mask {id:
bool[A]}, frame (the events for the reward), done, info.
"""
from __future__ import annotations

import argparse
import json
import socket
import sys
import time
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import open_spec  # noqa: E402

SPEC = open_spec.load()
CUBE = SPEC["cube"]
SHAPE = (CUBE["y"], CUBE["z"], CUBE["x"])
SIZES = SPEC["heads"]["sizes"]
VEC = len(SPEC["vec"])
ENTS, ENT_F = SPEC["ents"], SPEC["ent_features"]
NBLOCK = len(SPEC["blocks"])
NITEM = len(SPEC["items"])
BASE_PORT = 27300   # ours (262xx are other agents')


def decode(o: dict) -> dict:
    vox = (np.frombuffer(o["vox"].encode("ascii"), np.uint8) - ord("A")).reshape(SHAPE)
    seen = (np.frombuffer(o["seen"].encode("ascii"), np.uint8) == ord("1")).reshape(SHAPE)
    return {"vox": vox, "seen": seen, "vec": np.asarray(o["vec"], np.float32), "ent": np.asarray(o["ent"], np.float32).reshape(ENTS, ENT_F),
            "hot": np.asarray(o["hot"], np.int64)}


class OpenEnv:
    def __init__(self, port: int, host: str = "127.0.0.1", timeout: float = 600.0):
        self.addr = (host, port)
        self.timeout = timeout
        self.sock = None
        self.f = None

    def _connect(self):
        if self.sock is None:
            self.sock = socket.create_connection(self.addr, timeout=self.timeout)
            self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            self.f = self.sock.makefile("r", encoding="utf-8")

    def ask(self, msg: dict) -> dict:
        self._connect()
        self.sock.sendall((json.dumps(msg) + "\n").encode("utf-8"))
        line = self.f.readline()
        if not line:
            raise ConnectionError("the environment closed the connection")
        r = json.loads(line)
        if "error" in r:
            raise RuntimeError("environment: " + r["error"])
        return r

    def _out(self, r: dict) -> dict:
        if not r.get("open") and "obs" in r:   # a scenario's answer (the teacher's record)
            return r
        r["obs"] = {k: decode(v) for k, v in r.get("obs", {}).items()}
        r["mask"] = {k: np.asarray(v, bool) for k, v in r.get("mask", {}).items()}
        return r

    def reset(self, seed=0, crew=1, time=0, days=1, waves="normal", kit="", scenario=None, max_steps=0, **extra) -> dict:
        msg = {"op": "reset", "mode": "open", "seed": seed, "crew": crew, "time": time, "days": days, "waves": waves, "max_steps": max_steps, **extra}
        if kit:
            msg["kit"] = kit
        if scenario:
            msg["scenario"] = scenario
        return self._out(self.ask(msg))

    def step(self, actions: dict) -> dict:
        return self._out(self.ask({"op": "step", "actions": {str(k): [int(x) for x in v] for k, v in actions.items()}}))

    def close(self):
        if self.sock is not None:
            try:
                self.sock.sendall(b'{"op":"close"}\n')
            except OSError:
                pass
            self.sock.close()
            self.sock = None


def random_action(rng, mask, sticky=None, p_keep=0.75):
    """a random player: heads held for a while (a uniform pick each step would only jitter), the act head by its mask"""
    if sticky is not None and rng.random() < p_keep:
        a = list(sticky)
        a[2] = a[3] = 0
        if a[4] != 1:
            a[4] = 0
        return a
    act = int(rng.choice(np.flatnonzero(mask))) if mask is not None and mask.any() else 0
    return [int(rng.integers(n)) for n in SIZES[:4]] + [act]


def run_random(port, steps, seed=1, crew=1, days=1, time_=0, log_every=500):
    env = OpenEnv(port)
    rng = np.random.default_rng(seed)
    o = env.reset(seed=seed, crew=crew, time=time_, days=days)
    t0, n, eps, sticky = time.time(), 0, 0, {}
    counts = {}
    while n < steps:
        acts = {}
        for a in o["agents"]:
            acts[a] = sticky[a] = random_action(rng, o["mask"][a], sticky.get(a))
        o = env.step(acts)
        n += 1
        for e in o["frame"]["events"]:
            if e["type"] not in ("agent", "decision"):
                counts[e["type"]] = counts.get(e["type"], 0) + 1
        if n % log_every == 0:
            print(f"step {n}: {n / (time.time() - t0):.0f} steps/s, t {o['frame']['t']:.0f} s, tod {o['info']['time_of_day']}, phase {o['frame']['phase']}, "
                  f"last {o['info'].get('last')}, events {counts}", flush=True)
        if o["done"]:
            eps += 1
            print("done:", o["info"].get("done_reason"), o["info"].get("stats"), o["info"].get("achievements"), flush=True)
            o = env.reset(seed=seed + eps, crew=crew, time=time_, days=days)
    env.close()
    return n / (time.time() - t0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("action", choices=["start", "stop", "random"])
    ap.add_argument("--n", type=int, default=1)
    ap.add_argument("--base-port", type=int, default=BASE_PORT)
    ap.add_argument("--port", type=int, default=BASE_PORT)
    ap.add_argument("--xmx", default="2048M")
    ap.add_argument("--steps", type=int, default=2000)
    ap.add_argument("--crew", type=int, default=1)
    ap.add_argument("--time", type=int, default=0)
    ap.add_argument("--days", type=int, default=1)
    a = ap.parse_args()
    import engine_env
    ports = [a.base_port + i for i in range(a.n)]
    if a.action == "start":
        assert all(not 26200 <= p < 26300 for p in ports), "262xx are other agents' ports"
        engine_env.start_engine(ports, a.xmx, True, "")
        sys.exit(0 if engine_env.wait_ready(ports) else 1)
    elif a.action == "stop":
        engine_env.stop(ports)
    else:
        print("steps/s", run_random(a.port, a.steps, crew=a.crew, days=a.days, time_=a.time))


if __name__ == "__main__":
    main()
