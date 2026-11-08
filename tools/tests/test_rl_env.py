"""The learning environment without the game (docs/rl/CONTRACT.md): rl/*.json is what RlSpec.java says, the files agree
with each other and with the contract's text, the client (tools/rl/env_client.py) against a stand-in server that keeps
the protocol (reset, step, the masks, noop ends the episode, the errors), the vector of envs resetting by itself, a
random masked policy's numbers; and in the mod's sources: the minds in the environment never reach the model."""
import json
import re
import socket
import subprocess
import sys
import threading
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "rl"))
import env_client  # noqa: E402

ACTIONS = json.loads((ROOT / "rl" / "actions.json").read_text(encoding="utf-8"))
OBS = json.loads((ROOT / "rl" / "obs.json").read_text(encoding="utf-8"))
REWARD = json.loads((ROOT / "rl" / "reward.json").read_text(encoding="utf-8"))
SRC = ROOT / "client" / "src" / "hardelele" / "aiguide" / "client"


class Stub:
    """a stand-in server: the protocol and its rules, a crew of agents that each decide every step, no game"""

    def __init__(self, crew_ok=True, hash_=None):
        self.sock = socket.socket()
        self.sock.bind(("127.0.0.1", 0))
        self.sock.listen(4)
        self.port = self.sock.getsockname()[1]
        self.hash = hash_ or ACTIONS["hash"]
        self.episodes = 0
        threading.Thread(target=self.serve, daemon=True).start()

    def serve(self):
        while True:
            c, _ = self.sock.accept()
            threading.Thread(target=self.talk, args=(c,), daemon=True).start()

    def talk(self, c):
        f = c.makefile("rw", encoding="utf-8", newline="\n")
        state, agents, t = None, [], 0
        n, dim = ACTIONS["n"], OBS["dim"]
        for line in f:
            m = json.loads(line)
            op = m.get("op")
            if op == "info":
                out = {"ok": True, "contract": 1, "actions": {"version": 1, "hash": self.hash, "n": n}, "obs": {"version": 1, "hash": OBS["hash"], "dim": dim}}
            elif op == "reset":
                if m.get("scenario") not in REWARD["scenarios"]:
                    out = {"error": "unknown scenario"}
                else:
                    self.episodes += 1
                    agents, t, state = [str(i + 1) for i in range(m.get("crew", 1))], 0, "wait"
                    out = {"obs": {a: [0.0] * dim for a in agents}, "mask": {a: self.mask() for a in agents}, "info": {"reset_ms": 3, "ticks": 0}}
            elif op == "step":
                acts = m.get("actions", {})
                if state != "wait":
                    out = {"error": "no episode: reset first"}
                elif any(a not in agents for a in acts) or any(not 0 <= k < n for k in acts.values()):
                    out = {"error": "bad action"}
                elif any(k == 0 for k in acts.values()):
                    state = None
                    out = {"obs": {a: [0.0] * dim for a in agents}, "mask": {a: self.mask() for a in agents},
                           "reward": {a: (-1.0 if acts.get(a) == 0 else 0.0) for a in agents}, "done": True,
                           "info": {"done_reason": "noop", "ticks": t, "components": {}}}
                else:
                    masked = [a for a, k in acts.items() if self.mask()[k] == 0]
                    t += 20
                    done = t >= 200
                    if done:
                        state = None
                    out = {"obs": {a: [t / 200.0] * dim for a in agents}, "mask": {a: self.mask() for a in agents},
                           "reward": {a: (-0.05 if a in masked else 0.02) - 0.001 for a in acts}, "done": done,
                           "info": {"ticks": t, "components": {}, **({"done_reason": "time", "game_s_per_s": 150.0} if done else {})}}
            elif op in ("close", "shutdown"):
                f.write(json.dumps({"ok": True}) + "\n")
                f.flush()
                c.close()
                return
            else:
                out = {"error": "unknown op"}
            f.write(json.dumps(out) + "\n")
            f.flush()

    @staticmethod
    def mask():
        m = [0] * ACTIONS["n"]
        for i in (0, 1, 3, 37):   # noop, look_around, chop_tree:near, wait:5
            m[i] = 1
        return m


def raises(fn, text):
    try:
        fn()
    except env_client.EnvError as e:
        assert text in str(e), str(e)
        return
    raise AssertionError("no error: " + text)


def main():
    # rl/ is what the code says (compiles RlSpec and the classes with no game in them)
    r = subprocess.run([sys.executable, str(ROOT / "tools" / "rl" / "gen_spec.py"), "--check"], capture_output=True, text=True)
    assert r.returncode == 0, r.stdout + r.stderr
    # the files agree with each other and with the contract
    ids = [a["id"] for a in ACTIONS["actions"]]
    names = [f["name"] for f in OBS["features"]]
    assert ids[0] == "noop" and len(ids) == ACTIONS["n"] == len(set(ids)), "the actions"
    assert OBS["dim"] == len(names) == len(set(names)), "the features"
    assert all("last_action_" + a in names for a in ids), "a last-action feature for every action"
    assert all(f["kind"] in ("div", "log", "bool") and f["scale"] > 0 for f in OBS["features"]), "the kinds of scaling"
    contract = (ROOT / "docs" / "rl" / "CONTRACT.md").read_text(encoding="utf-8")
    missing = [a for a in ids if "`" + a + "`" not in contract]
    assert not missing, "actions the contract does not name: %s" % missing
    comps = {k.split(".")[0] for k in REWARD["weights"]}
    missing = [c for c in comps if "`" + c + "`" not in contract]
    assert not missing, "reward components the contract does not name: %s" % missing
    for s in REWARD["scenarios"]:
        assert "`" + s + "`" in contract, "scenario %s not in the contract" % s

    # the client against the stand-in server
    stub = Stub()
    env = env_client.AgentEnv("127.0.0.1", stub.port, "wood")
    assert env.n_actions == ACTIONS["n"] and env.obs_dim == OBS["dim"]
    obs, mask, info = env.reset(seed=1)
    assert len(obs) == OBS["dim"] and len(mask) == ACTIONS["n"] and info["reset_ms"] == 3
    o, m, rew, done, info = env.step(3)
    assert not done and abs(rew - 0.019) < 1e-9, rew
    o, m, rew, done, info = env.step(5)   # masked: a failure's reward
    assert abs(rew + 0.051) < 1e-9, rew
    o, m, rew, done, info = env.step(0)   # noop: -1 and the end
    assert done and rew == -1.0 and info["done_reason"] == "noop"
    raises(lambda: env.step(1), "reset first")
    raises(lambda: env.env.reset(scenario="nowhere"), "unknown scenario")
    env.reset()
    raises(lambda: env.env.step({"7": 1}), "bad action")
    env.close()
    raises(lambda: env_client.CrewEnv("127.0.0.1", Stub(hash_="different").port), "differ")
    # a crew: every agent in obs, its mask
    crew = env_client.CrewEnv("127.0.0.1", stub.port, "sword", crew=3)
    obs, mask, _ = crew.reset()
    assert sorted(obs) == ["1", "2", "3"] and all(len(mask[a]) == ACTIONS["n"] for a in obs)
    obs, mask, rew, done, _ = crew.step({a: 1 for a in obs})
    assert sorted(rew) == ["1", "2", "3"] and not done
    crew.close()
    # the masked random choice: never a masked action, noop only when nothing else
    import random
    rng = random.Random(0)
    mk = Stub.mask()
    assert all(mk[env_client.masked_random(mk, rng)] and env_client.masked_random(mk, rng) != 0 for _ in range(200))
    assert env_client.masked_random([1] + [0] * (ACTIONS["n"] - 1), rng) == 0
    # a vector of envs: each resets by itself at its end
    vec = env_client.VecAgentEnv([("127.0.0.1", stub.port), ("127.0.0.1", Stub().port)], "wood", seed=10)
    obs, masks, _ = vec.reset()
    ends = 0
    for _ in range(12):
        obs, masks, rews, dones, infos = vec.step([1, 3])
        ends += sum(dones)
        for d, i in zip(dones, infos):
            assert not d or i["terminal"], "the end of an episode is marked"
    assert ends == 2 and len(obs) == 2, ends   # 10 steps an episode in the stand-in: each ended once
    vec.close()
    # a random policy's numbers
    env = env_client.AgentEnv("127.0.0.1", stub.port, "wood")
    res = env_client.random_rollout(env, steps=30, seed=2)
    assert res["steps"] == 30 and res["episodes_done"] == 3 and res["noop_resets"] == 0, res
    res = env_client.random_rollout(env, steps=10, seed=2, noop_p=1.0)
    assert res["noop_share"] == 1.0 and res["episodes_done"] == 10, res
    env.close()

    # in the mod: the environment's minds never ask the model
    mind = (SRC / "AgentMind.java").read_text(encoding="utf-8")
    step = mind[mind.index("boolean step(Crew c, Agent a, WorldServer w)"):]
    assert step.index("if (RlEnv.on())") < step.index("pool.submit"), "the environment's branch comes before the model is asked"
    branch = step[step.index("if (RlEnv.on())"):step.index("String lim = mind.limit")]
    assert "return" in branch and "RlEnv.decide" in branch and "complete(" not in branch and "pool." not in branch, "the branch decides and returns without the model"
    rl = (SRC / "RlEnv.java").read_text(encoding="utf-8")
    assert re.search(r"AgentMind\.model = body -> \{\s*// .*\n\s*modelCalls\+\+;\s*\n\s*throw new IllegalStateException", rl), "the model is replaced by a refusal that counts"
    assert "RlEnv.start(" in (SRC / "AIGuideClient.java").read_text(encoding="utf-8")
    print("test_rl_env PASS")


if __name__ == "__main__":
    main()
