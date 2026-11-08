"""The ratings' and the share screen's words: every key the code asks for is in both lang files, with the same
placeholders in Russian and in English (a missing key shows as the key itself in the game)."""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "client", "src", "hardelele", "aiguide", "client")
LANG = os.path.join(ROOT, "client", "res", "assets", "aiguideclient", "lang")
PREFIX = "aiguide.fb."


def lang(name):
    out = {}
    with open(os.path.join(LANG, name), encoding="utf-8") as h:
        for line in h:
            line = line.rstrip("\n")
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                out[k] = v
    return out


def src(name):
    with open(os.path.join(SRC, name), encoding="utf-8") as h:
        return h.read()


def main():
    ru, en = lang("ru_RU.lang"), lang("en_US.lang")
    bad = []
    if set(ru) != set(en):
        bad.append("keys only in one file: " + ", ".join(sorted(set(ru) ^ set(en))))
    wanted = set()
    for f in ("FeedbackUi.java", "GuiShare.java", "Commander.java", "PlaceLayer.java"):
        for m in re.finditer(r'\bt\("([a-z0-9_.]+)"', src(f)):
            if not m.group(1).endswith("."):   # a key made of a prefix and a name: the names are added below
                wanted.add(m.group(1))
    classes = re.search(r"CLASSES = \{([^}]*)\}", src("PlaceView.java")).group(1)
    wanted |= {"class." + c for c in re.findall(r'"(\w+)"', classes)}
    reasons = re.search(r"REASONS = \{([^}]*)\}", src("Labels.java")).group(1)
    wanted |= {"reason." + r for r in re.findall(r'"(\w+)"', reasons)}
    items = re.search(r"ITEMS = \{([^}]*)\}", src("GuiShare.java")).group(1)
    wanted |= {"share.item." + i for i in re.findall(r'"(\w+)"', items)}
    results = re.search(r"enum Result \{(.*?)\}", src("Result.java"), re.S).group(1)
    wanted |= {"result." + r.lower() for r in re.findall(r"\b([A-Z][A-Z_]+)\b", re.sub(r"/\*\*.*?\*/", "", results, flags=re.S)) if r != "RUNNING"}
    wanted |= {"result.pushed", "result.none", "share.err.quota.other"}
    with open(os.path.join(ROOT, "tools", "share-service", "server.py"), encoding="utf-8") as h:   # every quota the service names
        wanted |= {"share.err.quota." + r for r in re.findall(r'"(\w+)"\)', re.search(r"for over, reason in \((.*?)\):", h.read(), re.S).group(1))}
    for k in sorted(wanted):
        for name, d in (("ru_RU", ru), ("en_US", en)):
            if PREFIX + k not in d:
                bad.append(f"{name}: no {PREFIX}{k}")
    for k in sorted(set(ru) & set(en)):
        a, b = re.findall(r"%[sd%]", ru[k]), re.findall(r"%[sd%]", en[k])
        if a != b:
            bad.append(f"{k}: placeholders {a} in ru, {b} in en")
    for b in bad:
        print("FAIL", b)
    print(f"{len(wanted)} keys checked" + ("" if bad else ", all there"))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
