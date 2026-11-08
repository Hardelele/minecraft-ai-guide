"""The learning environment's client (docs/rl/CONTRACT.md): a gym-like interface over the TCP line protocol of a
server started with -Daiguide.rl=PORT.

    from env_client import CrewEnv, AgentEnv, VecAgentEnv
    env = AgentEnv("127.0.0.1", 26100, scenario="wood")      # one agent: obs vector, mask, reward, done
    obs, mask, info = env.reset(seed=1)
    obs, mask, reward, done, info = env.step(3)

CrewEnv is the protocol as it is (dicts by agent id: several agents decide when each is ready); AgentEnv is a crew of
one as a plain env; VecAgentEnv steps several AgentEnvs (servers) at once, in threads, resetting each when done.
No third-party packages: obs are lists of floats (numpy takes them as they are).
"""
from __future__ import annotations

import json
import random
import socket
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = ROOT / "rl"


def load_spec():
    """the contract's files of this checkout: (actions, features, reward)"""
    a = json.loads((SPEC / "actions.json").read_text(encoding="utf-8"))
    o = json.loads((SPEC / "obs.json").read_text(encoding="utf-8"))
    r = json.loads((SPEC / "reward.json").read_text(encoding="utf-8"))
    return a, o, r


class EnvError(RuntimeError):
    pass


class Conn:
    """one TCP connection: a JSON object a line each way"""

    def __init__(self, host, port, timeout=600.0):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.r = self.sock.makefile("r", encoding="utf-8", newline="\n")
        self.w = self.sock.makefile("w", encoding="utf-8", newline="\n")

    def ask(self, msg):
        self.w.write(json.dumps(msg, separators=(",", ":")) + "\n")
        self.w.flush()
        line = self.r.readline()
        if not line:
            raise EnvError("the server closed the connection")
        out = json.loads(line)
        if "error" in out:
            raise EnvError(out["error"])
        return out

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass


class CrewEnv:
    """the protocol as it is: obs/mask/reward are dicts by agent id ("1", "2"...); only the agents in obs decide"""

    def __init__(self, host="127.0.0.1", port=26100, scenario="wood", crew=1, raw=False, check=True, timeout=600.0):
        self.host, self.port, self.scenario, self.crew, self.raw = host, port, scenario, crew, raw
        self.conn = Conn(host, port, timeout)
        self.info = self.conn.ask({"op": "info"})
        if check:
            a, o, _ = load_spec()
            if self.info["actions"]["hash"] != a["hash"] or self.info["obs"]["hash"] != o["hash"]:
                raise EnvError("the server's spaces differ from rl/*.json here: actions %s vs %s, obs %s vs %s"
                               % (self.info["actions"]["hash"], a["hash"], self.info["obs"]["hash"], o["hash"]))
        self.n_actions = self.info["actions"]["n"]
        self.obs_dim = self.info["obs"]["dim"]

    def reset(self, seed=None, scenario=None):
        msg = {"op": "reset", "scenario": scenario or self.scenario, "crew": self.crew, "raw": self.raw}
        if seed is not None:
            msg["seed"] = int(seed)
        out = self.conn.ask(msg)
        return out["obs"], out["mask"], out["info"]

    def step(self, actions):
        out = self.conn.ask({"op": "step", "actions": {str(k): int(v) for k, v in actions.items()}})
        return out["obs"], out["mask"], out["reward"], out["done"], out["info"]

    def close(self, shutdown=False):
        try:
            self.conn.ask({"op": "shutdown" if shutdown else "close"})
        except (EnvError, OSError):
            pass
        self.conn.close()


class AgentEnv:
    """a crew of one as a plain env: reset() -> (obs, mask, info); step(a) -> (obs, mask, reward, done, info)"""

    def __init__(self, host="127.0.0.1", port=26100, scenario="wood", **kw):
        self.env = CrewEnv(host, port, scenario, crew=1, **kw)
        self.n_actions, self.obs_dim = self.env.n_actions, self.env.obs_dim
        self.agent = None

    def reset(self, seed=None, scenario=None):
        obs, mask, info = self.env.reset(seed, scenario)
        self.agent = next(iter(obs))
        return obs[self.agent], mask[self.agent], info

    def step(self, action):
        obs, mask, reward, done, info = self.env.step({self.agent: action})
        r = sum(reward.values())
        if done or self.agent not in obs:
            last = obs.get(self.agent, [0.0] * self.obs_dim)
            return last, mask.get(self.agent, [1] + [0] * (self.n_actions - 1)), r, True, info
        return obs[self.agent], mask[self.agent], r, False, info

    def close(self, shutdown=False):
        self.env.close(shutdown)


class VecAgentEnv:
    """several AgentEnvs (one a server) stepped together in threads; each resets by itself when its episode ends
    (the info of the step that ended it has terminal=True, its obs is the next episode's first)"""

    def __init__(self, addrs, scenario="wood", seed=0, **kw):
        self.envs = [AgentEnv(h, p, scenario, **kw) for h, p in addrs]
        self.n, self.seed = len(self.envs), seed
        self.n_actions, self.obs_dim = self.envs[0].n_actions, self.envs[0].obs_dim
        self.episodes = 0

    def _run(self, fns):
        out, errs = [None] * len(fns), []

        def go(i, f):
            try:
                out[i] = f()
            except Exception as e:  # noqa: BLE001 - every server's error is told
                errs.append((i, e))

        ts = [threading.Thread(target=go, args=(i, f)) for i, f in enumerate(fns)]
        for t in ts:
            t.start()
        for t in ts:
            t.join()
        if errs:
            raise EnvError("; ".join("env %d: %s" % (i, e) for i, e in errs))
        return out

    def _next_seed(self):
        self.episodes += 1
        return self.seed + self.episodes

    def reset(self):
        res = self._run([lambda e=e, s=self._next_seed(): e.reset(seed=s) for e in self.envs])
        return [r[0] for r in res], [r[1] for r in res], [r[2] for r in res]

    def step(self, actions):
        def one(e, a, s):
            o, m, r, d, info = e.step(a)
            if d:
                info = dict(info, terminal=True, terminal_obs=o)
                o, m, _ = e.reset(seed=s)
            return o, m, r, d, info

        res = self._run([lambda e=e, a=a, s=self._next_seed(): one(e, a, s) for e, a in zip(self.envs, actions)])
        return [x[0] for x in res], [x[1] for x in res], [x[2] for x in res], [x[3] for x in res], [x[4] for x in res]

    def close(self, shutdown=False):
        for e in self.envs:
            e.close(shutdown)


def masked_random(mask, rng=random, allow_noop=False):
    """a random allowed action (noop only when nothing else is allowed, unless allow_noop)"""
    ok = [i for i, m in enumerate(mask) if m and (allow_noop or i != 0)]
    return rng.choice(ok) if ok else 0


def random_rollout(env: AgentEnv, steps=100, seed=0, noop_p=0.0, scenario=None, log=None):
    """a random masked policy for so many steps (resetting when an episode ends): the numbers of the run"""
    rng = random.Random(seed)
    t0 = time.time()
    obs, mask, info = env.reset(seed=seed, scenario=scenario)
    reset_ms = [info.get("reset_ms")]
    total, n, eps, noops, ep_r, ep_rs, reasons, speeds = 0.0, 0, 0, 0, 0.0, [], {}, []
    for _ in range(steps):
        a = 0 if rng.random() < noop_p else masked_random(mask, rng)
        obs, mask, r, done, info = env.step(a)
        n += 1
        total += r
        ep_r += r
        if log:
            log({"step": n, "action": a, "reward": r, "done": done, "info": info})
        if done:
            eps += 1
            why = info.get("done_reason", "?")
            reasons[why] = reasons.get(why, 0) + 1
            noops += why == "noop"
            ep_rs.append(ep_r)
            ep_r = 0.0
            speeds.append(info.get("game_s_per_s"))
            obs, mask, info = env.reset(seed=seed + eps, scenario=scenario)
            reset_ms.append(info.get("reset_ms"))
    dt = time.time() - t0
    return {"steps": n, "seconds": round(dt, 2), "steps_per_s": round(n / dt, 2), "mean_step_reward": round(total / n, 4),
            "episodes_done": eps, "episode_rewards": [round(x, 3) for x in ep_rs], "done_reasons": reasons,
            "noop_resets": noops, "noop_share": round(noops / eps, 3) if eps else 0.0, "reset_ms": reset_ms,
            "game_s_per_s_at_done": speeds}


if __name__ == "__main__":
    import argparse

    ap = argparse.ArgumentParser(description="a random masked policy against a running environment")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=26100)
    ap.add_argument("--scenario", default="wood")
    ap.add_argument("--steps", type=int, default=100)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--noop-p", type=float, default=0.0, help="chance of choosing noop at a step (it resets the world)")
    ap.add_argument("--log", help="a JSONL file of every step")
    a = ap.parse_args()
    f = open(a.log, "w", encoding="utf-8") if a.log else None
    env = AgentEnv(a.host, a.port, a.scenario)
    try:
        res = random_rollout(env, a.steps, a.seed, a.noop_p, log=(lambda x: f.write(json.dumps(x) + "\n")) if f else None)
    finally:
        env.close()
        if f:
            f.close()
    print(json.dumps(res, indent=1))
