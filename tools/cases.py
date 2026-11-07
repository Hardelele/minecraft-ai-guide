"""The slow level of the crew's memory of cases: reads crew-cases.jsonl (and its rotated .1, .2 ... files) and tells
where the reflexes help and where they do not.

    python tools/cases.py <world dir | crew-cases.jsonl> [more ...]
    python tools/cases.py <world> --events video-events.txt     # also the log metrics of REFLEX-CASES.md
    python tools/cases.py <world> --embed                       # also group the cases' words by OpenAI embeddings
    python tools/cases.py --events before.txt --events after.txt  # the log metrics alone, side by side

Without --embed nothing goes over the network. With it the cases' words ("text") are embedded by
text-embedding-3-small through the local proxy (127.0.0.1:3128), the key read from config/aiguide/openai.key as the
game's Routines does (--key-file to say where; no key: the step is skipped). The key is never printed. The vectors are
kept next to the journal (crew-cases-embeddings.json) so a text is embedded once.
"""
import argparse
import collections
import hashlib
import json
import math
import os
import re
import sys
import urllib.request

PROXY = "http://127.0.0.1:3128"
MODEL = "text-embedding-3-small"


# ---- reading the journal ---------------------------------------------------------------------------------------

def journal_files(path):
    """the journal's files, the oldest first: crew-cases.N.jsonl ... crew-cases.1.jsonl, crew-cases.jsonl"""
    main = os.path.join(path, "crew-cases.jsonl") if os.path.isdir(path) else path
    d, n = os.path.split(main)
    base, ext = os.path.splitext(n)
    rotated = []
    for f in os.listdir(d or "."):
        m = re.fullmatch(re.escape(base) + r"\.(\d+)" + re.escape(ext), f)
        if m:
            rotated.append((int(m.group(1)), os.path.join(d, f)))
    out = [p for _, p in sorted(rotated, reverse=True)]
    if os.path.isfile(main):
        out.append(main)
    return out


def read_cases(paths):
    cases, bad = [], 0
    for p in paths:
        for f in journal_files(p):
            with open(f, encoding="utf-8") as h:
                for line in h:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        c = json.loads(line)
                        if isinstance(c, dict) and c.get("reflex") is not None:
                            cases.append(c)
                        else:
                            bad += 1
                    except ValueError:
                        bad += 1
    return cases, bad


def ok(c):
    return c.get("result") == "OK" and not c.get("escalated")


def kind(s):
    """the task's kind, as CaseVec.kind tells it"""
    t, ph = s.get("task") or "", s.get("phase") or ""
    if not t:
        return "none"
    if t in ("GoTo", "Collect", "Stash", "Take"):
        return "dig way" if ph == "DIG" else "walk"
    if t in ("Build", "PlaceFrom", "Scaffold", "Pillar", "Steps", "Light"):
        return "build"
    if t in ("Mine", "Fell", "Break", "Gather"):
        return "dig"
    if t == "Fight":
        return "fight"
    return "other"


def signature(c):
    """a coarse kind of situation, in words: what the reflex met, roughly"""
    s = c.get("situation") or {}
    parts = [c.get("reflex", "?")]
    for k, w in (("inLava", "lava"), ("burning", "fire"), ("underWater", "under water"), ("falling", "falling")):
        if s.get(k):
            parts.append(w)
    if s.get("inWater") and not s.get("underWater"):
        parts.append("in water")
    t = s.get("target")
    if isinstance(t, list) and len(t) == 3:
        dy = t[1]
        parts.append("target %s" % ("2+ up" if dy >= 2 else "2+ down" if dy <= -2 else "level"))
    if s.get("collided"):
        parts.append("wall")
    if s.get("threat"):
        parts.append(s["threat"])
    parts.append("task " + kind(s))
    return ", ".join(parts)


# ---- statistics ------------------------------------------------------------------------------------------------

def median(xs):
    xs = sorted(xs)
    if not xs:
        return 0
    m = len(xs) // 2
    return xs[m] if len(xs) % 2 else (xs[m - 1] + xs[m]) / 2


def pct(a, b):
    return "%3.0f%%" % (100.0 * a / b) if b else "  - "


def stats(cases):
    rep = {"cases": len(cases), "reflexes": {}, "situations": []}
    by_reflex = collections.defaultdict(list)
    for c in cases:
        by_reflex[c.get("reflex", "?")].append(c)
    for r, cs in sorted(by_reflex.items(), key=lambda kv: -len(kv[1])):
        reactions = collections.defaultdict(list)
        for c in cs:
            reactions[c.get("reaction", "")].append(c)
        rep["reflexes"][r] = {
            "n": len(cs), "ok": sum(ok(c) for c in cs),
            "escalated": sum(1 for c in cs if c.get("escalated")),
            "ticks_median": median([c.get("ticks", 0) for c in cs]),
            "results": dict(collections.Counter(c.get("result", "") for c in cs)),
            "reactions": {k: {"n": len(v), "ok": sum(ok(c) for c in v), "ticks_median": median([c.get("ticks", 0) for c in v])}
                          for k, v in sorted(reactions.items(), key=lambda kv: -len(kv[1]))},
        }
    groups = collections.defaultdict(list)
    for c in cases:
        groups[signature(c)].append(c)
    for sig, cs in groups.items():
        reactions = collections.Counter(c.get("reaction", "") for c in cs)
        best = max(reactions, key=lambda r: (sum(ok(c) for c in cs if c.get("reaction") == r) / reactions[r], reactions[r]))
        rep["situations"].append({"situation": sig, "n": len(cs), "ok": sum(ok(c) for c in cs), "best": best,
                                  "reactions": dict(reactions), "example": cs[-1].get("text", "")})
    rep["situations"].sort(key=lambda g: (g["ok"] / g["n"], -g["n"]))
    return rep


def print_stats(rep, bad):
    print("cases: %d%s" % (rep["cases"], " (%d bad lines skipped)" % bad if bad else ""))
    if not rep["cases"]:
        return
    print("\nby reflex (helped = OK and not escalated):")
    print("  %-14s %5s %7s %9s %6s  results" % ("reflex", "n", "helped", "escalated", "ticks"))
    for r, v in rep["reflexes"].items():
        print("  %-14s %5d %7s %9s %6g  %s" % (r, v["n"], pct(v["ok"], v["n"]), pct(v["escalated"], v["n"]), v["ticks_median"],
                                               " ".join("%s %d" % kv for kv in sorted(v["results"].items()))))
        for k, rv in v["reactions"].items():
            print("      - %s: %d, helped %s, ticks %g" % (k or "(none)", rv["n"], pct(rv["ok"], rv["n"]).strip(), rv["ticks_median"]))
    print("\nsituations, where the reflexes help least first:")
    for g in rep["situations"]:
        print("  %4s of %-4d %s -> best so far: %s" % (pct(g["ok"], g["n"]), g["n"], g["situation"], g["best"] or "(none)"))
        if g["example"]:
            print("               e.g. %s" % g["example"])


# ---- the log metrics (REFLEX-CASES.md): before and after ---------------------------------------------------------

LOG = {
    "no_way": re.compile(r"(Агент \d+) has no way to (-?\d+ -?\d+ -?\d+)"),
    "unreachable_log": re.compile(r"(Агент \d+) (?:gives up an unreachable log|cannot get at \d+ logs)"),
    "goto_no_path": re.compile(r"(Агент \d+) GoTo -> NO_PATH"),
    "goto_timeout": re.compile(r"(Агент \d+) GoTo -> TIMEOUT"),
    "build_blocked": re.compile(r"(Агент \d+) build \S+ at (-?\d+ -?\d+ -?\d+) -> BLOCKED"),
    "wall_passage": re.compile(r"(Агент \d+) (?:will )?opens? a passage in the house wall"),
    "hurt_fall": re.compile(r"(Агент \d+) hurt by fall"),
    "hurt_in_wall": re.compile(r"(Агент \d+) hurt by inWall"),
    "hurt_fire": re.compile(r"(Агент \d+) hurt by (?:inFire|onFire|lava)"),
    "died": re.compile(r"(Агент \d+) died"),
    "gone": re.compile(r"(Агент \d+) +\S* ?-> GONE"),
    "reflex": re.compile(r"reflex: (Агент \d+) (\S+) .* -> (\S+) in"),
}


def log_metrics(path):
    """lines and episodes (an agent and its target, or the agent alone) of each kind of failure in an events log"""
    lines = collections.Counter()
    episodes = collections.defaultdict(set)
    reflexes = collections.Counter()
    with open(path, encoding="utf-8", errors="replace") as h:
        for line in h:
            for k, rx in LOG.items():
                m = rx.search(line)
                if not m:
                    continue
                lines[k] += 1
                episodes[k].add(m.groups()[:2] if k != "reflex" else m.group(1))
                if k == "reflex":
                    reflexes["%s %s" % (m.group(2), "OK" if m.group(3) == "OK" else "failed")] += 1
    return {"lines": dict(lines), "episodes": {k: len(v) for k, v in episodes.items()}, "reflexes": dict(reflexes)}


def print_logs(named):
    keys = list(LOG)
    print("\nlog metrics (episodes / lines):")
    print("  %-16s" % "" + "".join(" %22s" % os.path.basename(n)[-22:] for n, _ in named))
    for k in keys:
        print("  %-16s" % k + "".join(" %22s" % ("%d / %d" % (m["episodes"].get(k, 0), m["lines"].get(k, 0))) for _, m in named))
    rs = sorted(set(r for _, m in named for r in m["reflexes"]))
    for r in rs:
        print("  %-16s" % r[:16] + "".join(" %22d" % m["reflexes"].get(r, 0) for _, m in named))


# ---- the words embedded: groups of situations nobody foresaw ---------------------------------------------------

def key_file(paths, given):
    if given:
        return given
    for p in paths:   # the game's folder: <game>/saves/<world>/crew-cases.jsonl -> <game>/config/aiguide/openai.key
        d = os.path.abspath(p if os.path.isdir(p) else os.path.dirname(p))
        for up in (d, os.path.dirname(d), os.path.dirname(os.path.dirname(d))):
            f = os.path.join(up, "config", "aiguide", "openai.key")
            if os.path.isfile(f):
                return f
    return os.path.join("config", "aiguide", "openai.key")


def embed(texts, key):
    req = urllib.request.Request("https://api.openai.com/v1/embeddings", data=json.dumps({"model": MODEL, "input": texts}).encode("utf-8"),
                                 headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({"https": PROXY, "http": PROXY}))
    with opener.open(req, timeout=60) as r:
        data = json.loads(r.read().decode("utf-8"))["data"]
    return [d["embedding"] for d in sorted(data, key=lambda d: d["index"])]


def cos(a, b):
    d = sum(x * y for x, y in zip(a, b))
    return d / max(1e-12, math.sqrt(sum(x * x for x in a) * sum(y * y for y in b)))


def embedded_groups(cases, paths, key_path, threshold):
    if not os.path.isfile(key_path):
        print("\nembeddings: no key at %s, skipped" % key_path)
        return None
    with open(key_path, encoding="utf-8") as h:
        key = h.read().strip()
    first = paths[0]
    cache_path = os.path.join(first if os.path.isdir(first) else os.path.dirname(os.path.abspath(first)), "crew-cases-embeddings.json")
    cache = {}
    if os.path.isfile(cache_path):
        with open(cache_path, encoding="utf-8") as h:
            cache = json.load(h)
    texts = sorted(set(c.get("text", "") for c in cases if c.get("text")))
    h = lambda t: hashlib.sha1(t.encode("utf-8")).hexdigest()
    todo = [t for t in texts if h(t) not in cache]
    try:
        for i in range(0, len(todo), 256):
            for t, v in zip(todo[i:i + 256], embed(todo[i:i + 256], key)):
                cache[h(t)] = v
    except Exception as e:   # no network, no proxy, a refused key: the statistics stand without it
        print("\nembeddings: not got (%s), skipped" % type(e).__name__)
        return None
    if todo:
        with open(cache_path, "w", encoding="utf-8") as f:
            json.dump(cache, f)
    groups = []   # the leader's way: a case joins the first group whose leader is this alike, else leads a new one
    for c in cases:
        v = cache.get(h(c.get("text", "")))
        if v is None:
            continue
        for g in groups:
            if cos(v, g["vec"]) >= threshold:
                g["cases"].append(c)
                break
        else:
            groups.append({"vec": v, "cases": [c]})
    out = []
    for g in groups:
        cs = g["cases"]
        out.append({"n": len(cs), "ok": sum(ok(c) for c in cs), "reflexes": dict(collections.Counter(c.get("reflex") for c in cs)),
                    "leader": cs[0].get("text", ""), "signatures": len(set(signature(c) for c in cs))})
    out.sort(key=lambda g: (g["ok"] / g["n"], -g["n"]))
    print("\ngroups by the words' embeddings (cos >= %.2f), where the reflexes help least first:" % threshold)
    for g in out:
        print("  %4s of %-4d %s  [%s]" % (pct(g["ok"], g["n"]), g["n"], g["leader"], ", ".join("%s %d" % kv for kv in g["reflexes"].items())))
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("journal", nargs="*", help="a world folder or a crew-cases.jsonl")
    ap.add_argument("--events", action="append", default=[], help="an events log (video-events.txt, a server log): its failure metrics")
    ap.add_argument("--embed", action="store_true", help="group the cases' words by OpenAI embeddings (through the proxy)")
    ap.add_argument("--key-file", help="the OpenAI key's file (default: config/aiguide/openai.key of the game)")
    ap.add_argument("--alike", type=float, default=0.9, help="cosine for one group of words (default 0.9)")
    ap.add_argument("--json", help="the report into this file too")
    a = ap.parse_args()
    if not a.journal and not a.events:
        ap.error("a journal or an events log")
    report = {}
    if a.journal:
        cases, bad = read_cases(a.journal)
        report = stats(cases)
        report["bad_lines"] = bad
        print_stats(report, bad)
        if a.embed and cases:
            report["embedded_groups"] = embedded_groups(cases, a.journal, key_file(a.journal, a.key_file), a.alike)
    if a.events:
        named = [(p, log_metrics(p)) for p in a.events]
        report["logs"] = {p: m for p, m in named}
        print_logs(named)
    if a.json:
        with open(a.json, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
