"""The reward v4 (docs/rl/REWARD-v4.md): the outcomes only and the general causal credit, from a step's stream of
events (rl/events.json), with no world in it. Every environment (the game's RlEnv through RlReward4.java, the Python
simulator, the batch simulator) gives frames {t, dt, phase, events}; this gives each agent's points by component
(taken at its next decision) and the move events that tell train.py which earlier decision a credit belongs to.

    core = RewardCore("waves:normal")
    moves = core.frame({"t": 1.0, "dt": 1.0, "phase": "wave", "events": [...]})
    points, components = core.take(agent)

RlReward4.java is its mirror: tools/tests/test_rl_exploits.py writes cases (frames and what this gives) that RlTest
checks the Java against.
"""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RL = ROOT / "rl"


def load_params(path: Path | None = None) -> dict:
    return json.loads((path or RL / "reward-v4.json").read_text(encoding="utf-8"))


def load_scales(path: Path | None = None) -> tuple[dict, dict, tuple]:
    """rl/reward.json's scales: their parts' weights, the scenarios' factors, the scales' names"""
    r = json.loads((path or RL / "reward.json").read_text(encoding="utf-8"))
    scales = tuple(r.get("scales", ()))
    w = {k: float(v) for k, v in r["weights"].items() if k.split(".")[0] in scales}
    return w, r.get("scenarioScales", {}), scales


_P = None
_S = None


def defaults():
    global _P, _S
    if _P is None:
        _P = load_params()
        _S = load_scales()
    return _P, _S


def afk_integral(x: float, rate: float, grace: float, ramp: float) -> float:
    """the afk penalty's integral over 0..x seconds idle: the rate per second grows from 0 to `rate` over `ramp` s after `grace` s"""
    if x <= grace:
        return 0.0
    if ramp <= 0:
        return rate * (x - grace)
    if x < grace + ramp:
        return rate * (x - grace) ** 2 / (2 * ramp)
    return rate * (ramp / 2 + (x - grace - ramp))


class RewardCore:
    def __init__(self, scenario: str | None = None, params: dict | None = None, scales: tuple | None = None):
        p, s = defaults()
        self.p = params if params is not None else p
        sw, ss, names = scales if scales is not None else s
        self.sw = sw
        self.scale_names = names
        self.factors = None if scenario is None else dict(ss.get(scenario, {}))
        o = self.p["outcomes"]
        self.o = {k: float(v) for k, v in o.items()}
        self.calm = set(self.p.get("calm_phases", ("day", "prep", "break")))
        self.no_cur = set(self.p.get("no_curiosity_phases", ("wave", "night")))
        # agents: id -> state
        self.ag: dict[str, dict] = {}
        self.dec: dict[int, dict] = {}         # decision id -> {agent, t, kind, productive, end}
        self.cur: dict[str, int] = {}          # agent -> its running decision
        self.recent: dict[str, list] = {}      # agent -> [(t, decision)] of the last seconds (dodges)
        self.day: list[int] = []               # the decisions of the last calm period
        self.calm_end: float | None = None
        self.prev_calm: bool | None = None
        self.threats: dict[int, dict] = {}     # mob -> its open ledger
        self.finds: dict[str, tuple] = {}      # res -> (decision, agent, value)
        self.open: dict[str, dict] = {}        # agent -> component -> points since its last decision
        self.episode: dict[str, float] = {}
        self.total = 0.0
        self.gain = 0.0                        # the scales' positive points paid so far (the crew): forfeit's rate
        self.t = 0.0
        self.phase = "day"
        self.moves: list[dict] = []

    # ---- helpers ---------------------------------------------------------------------------------------------------
    def factor(self, key: str) -> float:
        comp = key.split(".")[0]
        if comp not in self.scale_names or self.factors is None:
            return 1.0
        if key in self.factors:
            return float(self.factors[key])
        return float(self.factors.get(comp, 0.0))

    def state(self, a) -> dict:
        k = str(a)
        s = self.ag.get(k)
        if s is None:
            s = self.ag[k] = {"x": 0.0, "y": 0.0, "z": 0.0, "hp": 20.0, "alive": True, "base_dist": 0.0, "idle": 0.0,
                              "trip": 0.0, "danger": None}
        return s

    def alive(self) -> list[str]:
        return [k for k, s in self.ag.items() if s["alive"]]

    def pay(self, a, comp: str, pts: float):
        if pts == 0:
            return
        k = str(a)
        m = self.open.setdefault(k, {})
        m[comp] = m.get(comp, 0.0) + pts
        self.episode[comp] = self.episode.get(comp, 0.0) + pts
        self.total += pts

    def progress(self, a):
        self.state(a)["idle"] = 0.0

    def productive(self, d):
        if d in self.dec:
            self.dec[d]["productive"] = True

    def move(self, a, comp: str, pts: float, d: int):
        """an event for train.py: the points just paid to `a` belong to decision d"""
        info = self.dec.get(d)
        self.moves.append({"type": "reward", "agent": a, "key": comp, "points": pts, "move": 1.0,
                           "chain": [{"decision_id": d, "agent": info["agent"] if info else a,
                                      "tick": int(round((info["t"] if info else self.t) * 20)),
                                      "kind": info["kind"] if info else "", "via": "credit"}]})

    def transfer(self, frm, to, comp: str, pts: float, d: int):
        """pts from `frm`'s step to `to`'s decision d (the crew's sum unchanged)"""
        if pts == 0:
            return
        self.pay(frm, comp, -pts)
        self.pay(to, comp, pts)
        self.move(to, comp, pts, d)

    def witness(self, a, seen) -> bool:
        if not seen:
            return False
        k = str(a)
        ss = [str(x) for x in seen]
        if k in ss:
            return True
        me = self.ag.get(k)
        if me is None:
            return False
        r2 = float(self.p["sight"]["near_r"]) ** 2
        for x in ss:
            o = self.ag.get(x)
            if o is not None and (o["x"] - me["x"]) ** 2 + (o["y"] - me["y"]) ** 2 + (o["z"] - me["z"]) ** 2 <= r2:
                return True
        return False

    # ---- losses and the empty day --------------------------------------------------------------------------------
    def loss(self, a, comp: str, pts: float):
        """a loss paid to a; in the dark its share goes to the crew's empty decisions of the last calm period"""
        self.pay(a, comp, pts)
        if pts >= 0 or self.phase in self.calm or self.calm_end is None:
            return
        share = float(self.p["wasted_day"]["share"])
        cands = []
        for d in self.day:
            info = self.dec[d]
            if info["productive"]:
                continue
            end = info["end"] if info["end"] is not None else self.calm_end
            dur = min(end, self.calm_end) - info["t"]
            if dur > 0:
                cands.append((d, dur))
        tot = sum(x[1] for x in cands)
        if tot <= 0:
            return
        for d, dur in cands:
            x = share * pts * dur / tot   # negative: the empty decision's part of the loss
            self.transfer(a, self.dec[d]["agent"], "credit.wasted_day", x, d)

    # ---- threats ---------------------------------------------------------------------------------------------------
    def contrib(self, th: dict, d: int, agent, reason: str, w: float):
        if w <= 0:
            return
        c = th["contrib"].get(d)
        if c is None or c[2] < w:
            th["contrib"][d] = [str(agent), reason, w]

    def value(self, th: dict) -> float:
        wd = abs(self.o["crew.damage"])
        v = 0.0
        for tg in th["targets"]:
            if tg == "base":   # a breach (unless it is in already) and the mob-seconds of its stay inside
                stay = max(0.0, th["until"] - self.t)
                v = max(v, (0.0 if th["inside"] else abs(self.o["base.breach"])) + abs(self.o["base.mob_seconds"]) * stay)
            else:
                hits = float(self.p["threat"]["agent_hits"])
                v = max(v, hits * th["dmg"] * wd - th["taken"].get(tg, 0.0) * wd)
        return max(0.0, v)

    def resolve(self, mob: int, share: float):
        th = self.threats.pop(mob, None)
        if th is None or not th["contrib"]:
            return
        pie = share * self.value(th)
        tot = sum(c[2] for c in th["contrib"].values())
        if pie <= 0 or tot <= 0:
            return
        act = float(self.p["afk"]["active_s"])
        for d, (agent, reason, w) in th["contrib"].items():
            pts = pie * w / tot
            comp = "credit." + reason
            self.pay(agent, comp, pts)
            self.move(agent, comp, pts, d)
            self.productive(d)
            info = self.dec.get(d)
            if info is not None and self.t - info["t"] <= act:
                self.progress(agent)

    def recent_of(self, a, t: float) -> list[int]:
        win = float(self.p["threat"]["dodge_s"])
        ds = [d for (td, d) in self.recent.get(str(a), []) if td >= t - win]
        if not ds and str(a) in self.cur:
            ds = [self.cur[str(a)]]
        return ds

    # ---- a frame ---------------------------------------------------------------------------------------------------
    def frame(self, fr: dict) -> list[dict]:
        self.moves = []
        dt = float(fr.get("dt", 0.0))
        self.t = float(fr["t"])
        self.phase = fr.get("phase", "day")
        calm = self.phase in self.calm
        if calm and self.prev_calm is not True:
            self.day = []
            self.calm_end = None
        elif not calm and self.prev_calm is True:
            self.calm_end = self.t - dt
        self.prev_calm = calm
        for ev in fr.get("events", ()):
            getattr(self, "ev_" + ev["type"])(ev)
        self.end_frame(dt)
        # one move a frame for each (agent, component, decision): the losses of a frame to the same empty decision summed
        merged: dict = {}
        for m in self.moves:
            key = (str(m["agent"]), m["key"], m["chain"][0]["decision_id"])
            if key in merged:
                merged[key]["points"] += m["points"]
            else:
                merged[key] = m
        self.moves = list(merged.values())
        return self.moves

    def end_frame(self, dt: float):
        sv = self.p["survive"]
        low, safe = float(sv["hp_low"]), float(sv["hp_safe"])
        for k, s in self.ag.items():
            if not s["alive"]:
                s["danger"] = None
                continue
            if s["danger"] is None:
                if s["hp"] <= low and any(k in th["targets"] for th in self.threats.values()):
                    s["danger"] = []
                    s["danger_at"] = self.cur.get(k)
            elif s["hp"] >= safe:
                ds = s["danger"] or ([s["danger_at"]] if s.get("danger_at") is not None else [])
                s["danger"] = None
                if ds:
                    pts = float(sv["share"]) * abs(self.o["crew.death"]) / len(ds)
                    for d in ds:
                        self.pay(k, "credit.survive", pts)
                        self.move(k, "credit.survive", pts, d)
                        self.productive(d)
        a = self.p["afk"]
        rate, grace, ramp = float(a["rate"]), float(a["grace_s"]), float(a["ramp_s"])
        for k, s in self.ag.items():
            if not s["alive"] or dt <= 0:
                continue
            i0 = s["idle"]
            s["idle"] = i0 + dt
            pen = afk_integral(i0 + dt, rate, grace, ramp) - afk_integral(i0, rate, grace, ramp)
            self.pay(k, "afk", -pen)

    # ---- the events ------------------------------------------------------------------------------------------------
    def ev_agent(self, e):
        s = self.state(e["agent"])
        s["x"], s["y"], s["z"] = float(e.get("x", 0)), float(e.get("y", 0)), float(e.get("z", 0))
        s["hp"] = float(e.get("hp", s["hp"]))
        s["alive"] = bool(e.get("alive", True))
        s["base_dist"] = float(e.get("base_dist", 0))
        if s["base_dist"] <= float(self.p["death"]["base_r"]):
            s["trip"] = 0.0

    def ev_decision(self, e):
        k = str(e["agent"])
        self.state(k)
        d = int(e["id"])
        if k in self.cur and self.cur[k] in self.dec:
            self.dec[self.cur[k]]["end"] = self.t
        self.dec[d] = {"agent": e["agent"], "t": self.t, "kind": e.get("kind", ""), "productive": False, "end": None}
        self.cur[k] = d
        r = self.recent.setdefault(k, [])
        r.append((self.t, d))
        win = float(self.p["threat"]["dodge_s"])
        while r and r[0][0] < self.t - win:
            r.pop(0)
        if self.phase in self.calm:
            self.day.append(d)
        dg = self.ag[k]["danger"]
        if dg is not None:
            dg.append(d)

    def ev_noop(self, e):
        self.pay(e["agent"], "noop", self.o["noop"])
        f = self.p.get("forfeit")
        if f:   # the rest of the episode given up: the open goal and the scales still to come, to the one who gave up
            goal = self.o["goal"] * float(f["goal"]) * len(self.ag) if e.get("goal") else 0.0
            left = max(0.0, float(e.get("left_s", 0.0)))
            est = min(float(f["cap"]), float(f["scales"]) * self.gain / max(self.t, float(f["min_s"])) * left)
            self.pay(e["agent"], "forfeit", -(goal + est))

    def ev_fall(self, e):
        """the base given up (noop) or lost: every open threat counts as done, a threat to the base as a breach and the
        mob-seconds of its staying inside (until its wave's end)"""
        for mob in list(self.threats):
            th = self.threats.pop(mob)
            if "base" in th["targets"]:
                stay = max(0.0, th["until"] - self.t)
                for k in self.alive():
                    self.pay(k, "surrender", (0.0 if th["inside"] else self.o["base.breach"]) + self.o["base.mob_seconds"] * stay)
            else:
                wd = abs(self.o["crew.damage"])
                for tg in th["targets"]:
                    if tg in self.ag and self.ag[tg]["alive"]:
                        v = max(0.0, float(self.p["threat"]["agent_hits"]) * th["dmg"] * wd - th["taken"].get(tg, 0.0) * wd)
                        self.pay(tg, "surrender", -v)

    def ev_goal(self, e):
        for k in list(self.ag):
            self.pay(k, "goal", self.o["goal"])
            self.progress(k)
            if k in self.cur:
                self.productive(self.cur[k])

    def ev_human(self, e):
        pts = self.o["human"] * float(e["rating"])
        self.pay(e["agent"], "human", pts)
        if pts > 0:
            self.progress(e["agent"])

    def ev_damage(self, e):
        hp = float(e["hp"])
        self.loss(e["agent"], "crew.damage", self.o["crew.damage"] * hp)
        mob = e.get("mob")
        if mob is not None and int(mob) in self.threats:
            tk = self.threats[int(mob)]["taken"]
            k = str(e["agent"])
            tk[k] = tk.get(k, 0.0) + hp

    def ev_death(self, e):
        d = self.p["death"]
        k = str(e["agent"])
        s = self.state(k)
        wdeath = abs(self.o["crew.death"])
        x = float(d["accident_x"]) if e.get("cause") in d["accident_causes"] else 1.0
        far = min(float(d["far_cap"]), max(0.0, float(e.get("base_dist", 0)) - float(d["base_r"])) * float(d["far_per_block"]))
        soft = min(float(d["soften_cap"]) * wdeath, float(d["finds_soften"]) * s["trip"])
        self.loss(k, "crew.death", -(wdeath * x * (1 + far)) + soft)
        s["alive"] = False
        s["trip"] = 0.0
        s["danger"] = None

    def ev_breach(self, e):
        for k in self.alive():
            self.loss(k, "base.breach", self.o["base.breach"])
        th = self.threats.get(int(e["mob"]))
        if th is not None:   # in: what turned it did not hold; its threat now is its stay inside
            th["inside"] = True
            th["contrib"] = {}

    def ev_inside(self, e):
        ms = float(e["mob_s"])
        for k in self.alive():
            self.loss(k, "base.mob_seconds", self.o["base.mob_seconds"] * ms)

    def ev_scale(self, e):
        key = e["key"]
        if key not in self.sw:
            raise KeyError("no scale weight " + key)
        raw = self.sw[key] * float(e["delta"])   # the measured change: progress whether or not the scenario pays it
        pts = raw * self.factor(key)
        if raw == 0:
            return
        payees = self.alive() if e["agent"] == "*" else [str(e["agent"])]
        causes = [int(c) for c in e.get("causes") or () if int(c) in self.dec]
        res = e.get("res")
        find = self.finds.get(res) if res is not None else None
        for k in payees:
            self.pay(k, key, pts)
            if pts > 0:
                self.gain += pts
            if raw <= 0:
                continue
            if e["agent"] != "*" or not causes:
                self.progress(k)
                if k in self.cur:
                    self.productive(self.cur[k])
            if pts <= 0:
                continue
            if find is not None:
                self.transfer(k, find[1], "credit.scout", float(self.p["scout"]["share"]) * pts, find[0])
            elif causes:
                m = float(self.p["scale_move"]) * pts / len(causes)
                for c in causes:
                    self.transfer(k, self.dec[c]["agent"], "credit.scale", m, c)
        if raw > 0:
            for c in causes:
                self.productive(c)
                self.progress(self.dec[c]["agent"])

    def ev_found(self, e):
        res = e["res"]
        if res in self.finds:
            return
        key = e.get("key")
        v = abs(self.sw.get(key, 0.0)) * float(e.get("amount", 1.0)) if key else 0.0
        d = int(e["decision"])
        self.finds[res] = (d, str(e["agent"]), v)
        s = self.state(e["agent"])
        s["trip"] += v
        self.productive(d)
        self.progress(e["agent"])

    def ev_threat(self, e):
        mob = int(e["mob"])
        th = self.threats.get(mob)
        if th is None:
            th = self.threats[mob] = {"targets": [], "dmg": 0.0, "contrib": {}, "taken": {}, "until": self.t, "inside": False}
        th["until"] = max(th["until"], self.t + float(e.get("stay_s", 0.0)))
        tg = e["target"]
        tg = "base" if tg == "base" else str(tg)
        if tg not in th["targets"]:
            th["targets"].append(tg)
        th["dmg"] = max(th["dmg"], float(e.get("dmg", 0.0)))

    def ev_path_changed(self, e):
        th = self.threats.get(int(e["mob"]))
        if th is None or not e.get("on_route"):
            return
        t = self.p["threat"]
        eff = e["effect"]
        w = float(t["w"].get(eff, 0.0))
        if eff == "detour":
            dl = max(0.0, float(e.get("delay_s", 0.0)))
            w *= dl / (dl + float(t["delay_tau_s"])) if dl > 0 else 0.0
        by = [int(d) for d in e.get("by") or () if int(d) in self.dec]
        if not by:
            return
        for d in by:
            ag = self.dec[d]["agent"]
            if self.witness(ag, e.get("seen")):
                self.contrib(th, d, ag, "block", w / len(by))

    def ev_attack_failed(self, e):
        th = self.threats.get(int(e["mob"]))
        if th is None:
            return
        t = self.p["threat"]
        reason = e["reason"]
        if reason == "moved":
            tgt = e["target"]
            if not self.witness(tgt, e.get("seen")):
                return
            ds = self.recent_of(tgt, self.t)
            for d in ds:
                self.contrib(th, d, tgt, "dodge", float(t["w"]["moved"]) / len(ds))
            return
        by = [int(d) for d in e.get("by") or () if int(d) in self.dec]
        for d in by:
            ag = self.dec[d]["agent"]
            if self.witness(ag, e.get("seen")):
                self.contrib(th, d, ag, "cover", float(t["w"]["cover"]) / len(by))

    def ev_kill(self, e):
        mob = int(e["mob"])
        th = self.threats.get(mob)
        if th is None:
            return
        d = int(e["decision"])
        if d in self.dec:
            self.contrib(th, d, e["agent"], "kill", float(self.p["threat"]["w"]["kill"]))
        self.resolve(mob, float(self.p["threat"]["kill"]))

    def ev_mob_gone(self, e):
        self.resolve(int(e["mob"]), float(self.p["threat"]["deter"]))

    def ev_novel(self, e):
        if self.phase in self.no_cur:
            return
        key = "curiosity.novel"
        pts = self.sw.get(key, 0.0) * self.factor(key)
        self.pay(e["agent"], key, pts)

    # ---- taking ----------------------------------------------------------------------------------------------------
    def take(self, a) -> tuple[float, dict]:
        m = self.open.pop(str(a), {})
        return sum(m.values()), m


def run(frames: list[dict], scenario: str | None = None, params: dict | None = None) -> dict:
    """a whole stream: each agent's total and components, the moves (for the tests and the Java check)"""
    core = RewardCore(scenario, params)
    moves = []
    for fr in frames:
        moves += core.frame(fr)
    out = {}
    for k in list(core.open):
        s, m = core.take(k)
        out[k] = {"total": s, "components": m}
    return {"agents": out, "episode": dict(core.episode), "total": core.total, "moves": moves}
