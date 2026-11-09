"""The mask's rules of contract 2 as rl/actions.json writes them (RlSpec.Cond): an action's "mask" is a condition over
the raw facts, {"all": [...]}, {"any": [...]} or [fact, op, number or another fact]; a fact missing is 0. The same
reading as Java's Cond.eval, so the simulator's mask is the environment's by construction, not by a port.

    holds(rule, raw) -> bool
    mask(actions, raw) -> [0/1 per action], noop (0) always 1
"""
from __future__ import annotations

OPS = {
    ">": lambda a, b: a > b,
    ">=": lambda a, b: a >= b,
    "<": lambda a, b: a < b,
    "<=": lambda a, b: a <= b,
    "==": lambda a, b: a == b,
    "!=": lambda a, b: a != b,
}


def holds(rule, raw: dict) -> bool:
    if isinstance(rule, dict):
        if "all" in rule:
            return all(holds(c, raw) for c in rule["all"])
        if "any" in rule:
            return any(holds(c, raw) for c in rule["any"])
        raise ValueError("a rule is all, any or a comparison: %r" % rule)
    fact, op, other = rule
    a = float(raw.get(fact, 0.0))
    b = float(raw.get(other, 0.0)) if isinstance(other, str) else float(other)
    return OPS[op](a, b)


def mask(actions: list, raw: dict) -> list:
    """actions: rl/actions.json's list; the 0/1 of each, noop always allowed"""
    m = [1 if holds(a["mask"], raw) else 0 for a in actions]
    m[0] = 1
    return m
