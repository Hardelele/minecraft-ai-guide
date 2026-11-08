"""The crew's words: every key the code asks for (CrewKeys.tr, Crew.note, the key bindings' names) is in both lang
files, with the same placeholders in Russian and in English (a missing key shows as the key itself in the game)."""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "client", "src", "hardelele", "aiguide", "client")
LANG = os.path.join(ROOT, "client", "res", "assets", "aiguideclient", "lang")


def lang(name):
    out = {}
    with open(os.path.join(LANG, name), encoding="utf-8") as h:
        for line in h:
            line = line.rstrip("\n")
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                out[k] = v
    return out


def wanted():
    keys = set()
    for f in sorted(os.listdir(SRC)):
        if not f.endswith(".java"):
            continue
        with open(os.path.join(SRC, f), encoding="utf-8") as h:
            text = h.read()
        keys |= {"aiguide.crew." + k for k in re.findall(r'\btr\("([a-z0-9_.]+)"', text)}
        keys |= {"aiguide.crew." + k for k in re.findall(r'\bnote\("([a-z0-9_.]+)"', text)}
        keys |= set(re.findall(r'new KeyBinding\("(key\.aiguide\.[a-z0-9_.]+)"', text))
        keys |= set(re.findall(r'KeyBinding\("[^"]+", [^,]+, "(key\.categories\.[a-z0-9_.]+)"', text))
    return keys


def main():
    ru, en = lang("ru_RU.lang"), lang("en_US.lang")
    bad = []
    keys = wanted()
    if len(keys) < 20:
        bad.append(f"only {len(keys)} keys found: the scan is broken")
    for k in sorted(keys):
        for name, d in (("ru_RU", ru), ("en_US", en)):
            if k not in d:
                bad.append(f"{name}: no {k}")
    for k in sorted(k for k in keys if k in ru and k in en):
        a, b = re.findall(r"%[sd%]", ru[k]), re.findall(r"%[sd%]", en[k])
        if a != b:
            bad.append(f"{k}: placeholders {a} in ru, {b} in en")
    for b in bad:
        print("FAIL", b)
    print(f"{len(keys)} crew keys checked" + ("" if bad else ", all there"))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
