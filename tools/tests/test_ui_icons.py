"""The commander's icons: the atlas in the tree is what tools/ui_icons.py draws now, UiIcon.java has the same names in
the same order, every icon is outlined (no bare edge over a light or a dark world), and its words and the states' words
(aiguide.ui.*, UiKit.ui) are in both lang files with the same placeholders."""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "tools"))
import ui_icons  # noqa: E402
from PIL import Image, ImageChops  # noqa: E402

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


def main():
    bad = []
    names = [n for n, _ in ui_icons.ICONS]
    enum = open(os.path.join(SRC, "UiIcon.java"), encoding="utf-8").read()
    body = enum.split("{", 1)[1].split(";", 1)[0]
    java = [m.lower() for m in re.findall(r"\b([A-Z][A-Z_0-9]*)\(", body)]
    if java != names:
        bad.append("UiIcon.java and tools/ui_icons.py differ: %s vs %s" % (java, names))
    atlas = Image.open(ui_icons.OUT).convert("RGBA")
    if atlas.size != (ui_icons.ATLAS, ui_icons.ATLAS):
        bad.append("the atlas is %s" % (atlas.size,))
    fresh = ui_icons.atlas()
    if ImageChops.difference(atlas, fresh).getbbox() is not None:
        bad.append("the atlas in the tree is not what tools/ui_icons.py draws: run it again")
    px = atlas.load()
    for i, n in enumerate(names):
        for large in (False, True):
            x0, y0, s = ui_icons.cell(i, large)
            opaque = [(x, y) for x in range(s) for y in range(s) if px[x0 + x, y0 + y][3]]
            if len(opaque) < s * s // 8:
                bad.append("%s (%d): almost empty" % (n, s))
            for x, y in opaque:   # an edge of the drawing (next to see-through or the cell's border) is the outline
                edge = x in (0, s - 1) or y in (0, s - 1) or any(px[x0 + x + dx, y0 + y + dy][3] == 0 for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
                if edge and px[x0 + x, y0 + y] != ui_icons.OUTLINE:
                    bad.append("%s (%d): a bare edge at %d,%d" % (n, s, x, y))
                    break
    ru, en = lang("ru_RU.lang"), lang("en_US.lang")
    wanted = {"aiguide.ui.icon." + n for n in names}
    for f in os.listdir(SRC):
        if f.endswith(".java"):
            wanted |= {"aiguide.ui." + k for k in re.findall(r'\bui\("([a-z0-9_.]+)"', open(os.path.join(SRC, f), encoding="utf-8").read())}
    for k in sorted(wanted):
        for name, d in (("ru_RU", ru), ("en_US", en)):
            if k not in d:
                bad.append("%s: no %s" % (name, k))
    for k in sorted(k for k in ru if k.startswith("aiguide.ui.")):
        if k in en and re.findall(r"%[sd%]", ru[k]) != re.findall(r"%[sd%]", en[k]):
            bad.append("%s: placeholders differ" % k)
    for b in bad:
        print("FAIL", b)
    print("%d icons, %d words checked" % (len(names), len(wanted)) + ("" if bad else ", all there"))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
