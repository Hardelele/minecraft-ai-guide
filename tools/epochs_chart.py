"""Pictures of a run of epochs for the montage (ARCHITECTURE-LAYERS.md, "Эпохи обучения"), from epochs/<run>:

  python tools/epochs_chart.py <epochs/run> [--out DIR] [--size 1920x1080] [--frames] [--lessons N] [--font F.ttf]

- chart.png: the waves cleared by epoch, learning (a solid blue line, round marks) against control (a dashed orange line,
  square marks; the same seeds, nothing carried), one axis from 0, a legend and the last value of each written at its
  line's end; chart.csv beside it (the same numbers as a table);
- --frames: frame-001.png, frame-002.png ... the chart growing one epoch a frame (the montage holds each as long as it wants);
- --lessons N: lessons-N.png, the card "Чему научились" after epoch N: the lessons it wrote (lessons.json), its waves, its
  karma (the words from the lang file: aiguide.ui.epochs.karma.*).

Only reads the run's files (report.json, epoch-N/*.json); writes into --out (default: <run>/frames). Pillow."""
import argparse
import csv
import json
import os
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANG = os.path.join(ROOT, "client", "res", "assets", "aiguideclient", "lang", "ru_RU.lang")

# the dark surface and two categorical slots checked for colour-blind separation on it (the dataviz validator: all pass)
SURFACE, GRID, TEXT, TEXT2 = (26, 26, 25), (52, 52, 50), (240, 239, 236), (195, 194, 183)
LEARN, CONTROL = (57, 135, 229), (217, 89, 38)
FONTS = [r"C:\Windows\Fonts\segoeui.ttf", r"C:\Windows\Fonts\arial.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
         "/Library/Fonts/Arial Unicode.ttf", "/System/Library/Fonts/Supplemental/Arial.ttf"]
BOLD = [r"C:\Windows\Fonts\segoeuib.ttf", r"C:\Windows\Fonts\arialbd.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"]


def font(size, bold=False, path=None):
    """a font with Cyrillic: the one given, else the system's; Pillow's own (no Cyrillic) when none is found"""
    for p in ([path] if path else []) + (BOLD if bold else []) + FONTS:
        if p and os.path.isfile(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


def lang():
    out = {}
    if os.path.isfile(LANG):
        with open(LANG, encoding="utf-8") as h:
            for line in h:
                line = line.rstrip("\n")
                if "=" in line and not line.startswith("#"):
                    k, v = line.split("=", 1)
                    out[k] = v
    return out


def load(run):
    """the run's report (report.json; else its state.json): {config, epochs[...], waves_by_epoch{learn, control}}"""
    for name in ("report.json", "state.json"):
        p = os.path.join(run, name)
        if os.path.isfile(p):
            with open(p, encoding="utf-8") as h:
                r = json.load(h)
            if "waves_by_epoch" not in r:
                r["waves_by_epoch"] = {t: [e["waves_cleared"] for e in r.get("epochs", []) if e.get("track") == t] for t in ("learn", "control")}
            return r
    raise SystemExit("no report.json or state.json in " + run)


def text_w(d, s, f):
    b = d.textbbox((0, 0), s, font=f)
    return b[2] - b[0]


def dashed(d, a, b, col, width, dash):
    x0, y0 = a
    x1, y1 = b
    n = max(1, int(((x1 - x0) ** 2 + (y1 - y0) ** 2) ** 0.5 / dash))
    for i in range(0, n, 2):
        t0, t1 = i / n, min(1, (i + 1) / n)
        d.line([(x0 + (x1 - x0) * t0, y0 + (y1 - y0) * t0), (x0 + (x1 - x0) * t1, y0 + (y1 - y0) * t1)], fill=col, width=width)


def chart(series, size, upto=None, fontpath=None, title="Волны по эпохам", sub="сколько волн отбито: обучение против контроля (те же сиды, без переноса)"):
    """the chart as an image: series {"learn": [...], "control": [...]}, the first `upto` epochs shown (None: all)"""
    w, h = size
    k = h / 1080.0
    img = Image.new("RGB", size, SURFACE)
    d = ImageDraw.Draw(img)
    ft, fs, fa, fl = font(int(56 * k), True, fontpath), font(int(30 * k), False, fontpath), font(int(28 * k), False, fontpath), font(int(34 * k), True, fontpath)
    n = max(len(series.get("learn", [])), len(series.get("control", [])), 1)
    shown = n if upto is None else max(0, min(n, upto))
    top = max([1] + series.get("learn", []) + series.get("control", []))
    top = top + 1 if top < 4 else int(top * 1.15) + 1
    left, right, y0, y1 = int(120 * k), w - int(260 * k), int(250 * k), h - int(130 * k)
    d.text((left, int(60 * k)), title, fill=TEXT, font=ft)
    d.text((left, int(60 * k) + int(70 * k)), sub, fill=TEXT2, font=fs)
    # the legend, in one row above the plot
    lx, ly = left, int(195 * k)
    for name, col, dash in (("обучение", LEARN, False), ("контроль", CONTROL, True)):
        if dash:
            dashed(d, (lx, ly + 14 * k), (lx + 44 * k, ly + 14 * k), col, max(2, int(5 * k)), 9 * k)
        else:
            d.line([(lx, ly + 14 * k), (lx + 44 * k, ly + 14 * k)], fill=col, width=max(2, int(5 * k)))
        d.text((lx + 56 * k, ly - 2 * k), name, fill=TEXT2, font=fa)
        lx += int(56 * k) + text_w(d, name, fa) + int(48 * k)
    # the axis: recessive grid, whole waves
    step = max(1, top // 6)
    for v in range(0, top + 1, step):
        y = y1 - (y1 - y0) * v / top
        d.line([(left, y), (right, y)], fill=GRID, width=max(1, int(2 * k)))
        s = str(v)
        d.text((left - int(16 * k) - text_w(d, s, fa), y - 16 * k), s, fill=TEXT2, font=fa)
    xs = [left + (right - left) * (i / max(1, n - 1)) if n > 1 else (left + right) / 2 for i in range(n)]
    every = max(1, n // 12)
    for i in range(0, n, every):
        s = str(i + 1)
        d.text((xs[i] - text_w(d, s, fa) / 2, y1 + int(14 * k)), s, fill=TEXT2, font=fa)
    lab = "эпоха"
    d.text((right - text_w(d, lab, fa), y1 + int(54 * k)), lab, fill=TEXT2, font=fa)
    ends = []
    for name, col, dash in (("control", CONTROL, True), ("learn", LEARN, False)):
        vals = series.get(name, [])[:shown]
        if not vals:
            continue
        pts = [(xs[i], y1 - (y1 - y0) * v / top) for i, v in enumerate(vals)]
        lw = max(2, int(5 * k))
        for a, b in zip(pts, pts[1:]):
            if dash:
                dashed(d, a, b, col, lw, 12 * k)
            else:
                d.line([a, b], fill=col, width=lw)
        r = max(5, int(9 * k))
        for x, y in pts:   # a ring of the surface round each mark (they overlap where the tracks meet)
            if dash:
                d.rectangle([x - r - 2, y - r - 2, x + r + 2, y + r + 2], fill=SURFACE)
                d.rectangle([x - r, y - r, x + r, y + r], fill=col)
            else:
                d.ellipse([x - r - 3, y - r - 3, x + r + 3, y + r + 3], fill=SURFACE)
                d.ellipse([x - r, y - r, x + r, y + r], fill=col)
        ends.append([pts[-1][0], pts[-1][1], ("обучение: " if name == "learn" else "контроль: ") + str(vals[-1]), col])
    # the last values at the lines' ends, kept apart when they meet
    ends.sort(key=lambda e: e[1])
    for i in range(1, len(ends)):
        if ends[i][1] - ends[i - 1][1] < 44 * k:
            ends[i][1] = ends[i - 1][1] + 44 * k
    for x, y, s, col in ends:
        d.text((x + int(22 * k), y - 20 * k), s, fill=TEXT, font=fl)
    return img


def wrap(d, s, f, width):
    out, line = [], ""
    for word in s.split():
        t = (line + " " + word).strip()
        if text_w(d, t, f) <= width or not line:
            line = t
        else:
            out.append(line)
            line = word
    if line:
        out.append(line)
    return out


def lessons_card(run, report, epoch, size, fontpath=None):
    """the card after a learning epoch: its number and waves, up to three lessons it wrote, its karma"""
    w, h = size
    k = h / 1080.0
    words = lang()
    line = next((e for e in report.get("epochs", []) if e.get("track") == "learn" and e.get("epoch") == epoch), None)
    if line is None:
        raise SystemExit("no learning epoch %d in the report" % epoch)
    lj = os.path.join(run, "epoch-%d" % epoch, "lessons.json")
    written = []
    if os.path.isfile(lj):
        with open(lj, encoding="utf-8") as hh:
            written = [x["text"] for x in json.load(hh).get("written", [])]
    img = Image.new("RGB", size, SURFACE)
    d = ImageDraw.Draw(img)
    ft, fb, fs = font(int(64 * k), True, fontpath), font(int(46 * k), False, fontpath), font(int(34 * k), False, fontpath)
    x, y = int(110 * k), int(90 * k)
    d.text((x, y), "Эпоха %d: продержались %d" % (epoch, line.get("waves_cleared", 0)), fill=TEXT, font=ft)
    y += int(110 * k)
    d.text((x, y), "Чему научились:", fill=TEXT2, font=fs)
    y += int(70 * k)
    if not written:
        d.text((x, y), "уроков нет", fill=TEXT, font=fb)
        y += int(70 * k)
    for s in written[:3]:
        r = int(14 * k)
        d.ellipse([x, y + 18 * k, x + 2 * r, y + 18 * k + 2 * r], fill=LEARN)
        for ln in wrap(d, s, fb, w - x - int(150 * k)):
            d.text((x + int(50 * k), y), ln, fill=TEXT, font=fb)
            y += int(60 * k)
        y += int(26 * k)
    karma = line.get("karma", 0)
    why = line.get("karma_worst")
    kt = "Карма: %s" % (int(karma) if karma == int(karma) else karma) +(": " + words.get("aiguide.ui.epochs.karma." + why, why) if why and karma < 0 else "")
    d.text((x, h - int(140 * k)), kt, fill=TEXT2, font=fs)
    return img


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("run", help="epochs/<run>")
    ap.add_argument("--out", help="where the pictures go (default <run>/frames)")
    ap.add_argument("--size", default="1920x1080")
    ap.add_argument("--frames", action="store_true", help="one frame an epoch, the chart growing")
    ap.add_argument("--lessons", type=int, action="append", default=[], help="the card of what learning epoch N taught")
    ap.add_argument("--font")
    a = ap.parse_args(argv)
    size = tuple(int(v) for v in a.size.lower().split("x"))
    report = load(a.run)
    series = report["waves_by_epoch"]
    out = a.out or os.path.join(a.run, "frames")
    os.makedirs(out, exist_ok=True)
    chart(series, size, fontpath=a.font).save(os.path.join(out, "chart.png"))
    with open(os.path.join(out, "chart.csv"), "w", newline="", encoding="utf-8") as h:
        c = csv.writer(h)
        c.writerow(["epoch", "learn", "control"])
        n = max(len(series.get("learn", [])), len(series.get("control", [])))
        for i in range(n):
            c.writerow([i + 1] + [series[t][i] if i < len(series.get(t, [])) else "" for t in ("learn", "control")])
    made = ["chart.png", "chart.csv"]
    if a.frames:
        n = max(len(series.get("learn", [])), len(series.get("control", [])))
        for i in range(1, n + 1):
            name = "frame-%03d.png" % i
            chart(series, size, upto=i, fontpath=a.font).save(os.path.join(out, name))
            made.append(name)
    for e in a.lessons:
        name = "lessons-%d.png" % e
        lessons_card(a.run, report, e, size, a.font).save(os.path.join(out, name))
        made.append(name)
    print("epochs_chart: %s -> %s" % (", ".join(made), out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
