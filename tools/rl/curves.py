"""The learning curves of a run (build/rl/runs/<run>/metrics.csv) for a video: PNG panels and a tidy CSV.

    python tools/rl/curves.py --run sim1                 # -> build/rl/runs/sim1/curves.png, curves-*.png, curves.csv
    python tools/rl/curves.py --run sim1 --dark          # dark surface, for a video frame

Panels (one axis each, x = the epoch, one PPO iteration): the reward an agent gets an episode; the share of noop
(of the decisions, and of the episodes that ended by it); success by scenario (smoothed); the game seconds to the
goal by scenario (from episodes.csv); the curriculum's stage; v2, when the run has them: the perimeter's breaches
and mob-seconds by waves scenario, sustain (the renewable food eaten of the food eaten), the crew's achievements an
episode, the discoveries (novel_effect: new effects an epoch, all so far), the reward by scale. Colors: one per scenario in a fixed order (the dataviz palette's categorical slots).
"""
from __future__ import annotations

import argparse
import csv
import math
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

sys.path.insert(0, str(Path(__file__).resolve().parent))
ROOT = Path(__file__).resolve().parents[2]
RUNS = ROOT / "build" / "rl" / "runs"

LIGHT = dict(surface="#fcfcfb", ink="#0b0b0b", ink2="#52514e", grid="#e4e3df",
             series=["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300", "#6250d6", "#e34948"])
DARK = dict(surface="#1a1a19", ink="#f0efec", ink2="#c3c2b7", grid="#383835",
            series=["#3987e5", "#d95926", "#199e70", "#c98500", "#d55181", "#008300", "#9085e9", "#e66767"])
NAMES_RU = {"wood": "дерево", "pickaxe": "кирка", "sword": "меч", "iron": "железо", "food": "еда", "fortify": "укрепление",
            "waves:easy": "волны (легко)", "waves:normal": "волны (норма)", "waves:long": "волны (долго)", "armor": "броня",
            "bow": "лук", "mine_trip": "поход в шахту", "diamond": "алмазы", "farm": "ферма", "pasture": "загон",
            "enchant": "зачарование", "achievements": "достижения", "delay:sword": "отложено: меч",
            "delay:pickaxe": "отложено: кирка", "delay:torches": "отложено: факелы"}
SCALES_RU = {"safety": "безопасность", "perimeter": "периметр", "equipment": "экипировка", "mine": "шахта", "enchant": "зачарование",
             "sustain": "восполняемость", "stock": "запасы", "achievement": "достижения", "stall": "застой", "time": "время"}


def num(x):
    try:
        v = float(x)
        return v if math.isfinite(v) else None
    except (TypeError, ValueError):
        return None


def smooth(xs, ys, k=5):
    """a running mean over the last k known points (gaps stay gaps)"""
    out, win = [], []
    for y in ys:
        if y is not None:
            win.append(y)
            win = win[-k:]
        out.append(sum(win) / len(win) if (y is not None and win) else None)
    return [x for x, y in zip(xs, out) if y is not None], [y for y in out if y is not None]


def style(ax, t, title, ylabel):
    ax.set_facecolor(t["surface"])
    ax.set_title(title, loc="left", color=t["ink"], fontsize=11, pad=6)
    ax.set_ylabel(ylabel, color=t["ink2"], fontsize=9)
    ax.tick_params(colors=t["ink2"], labelsize=8, length=0)
    ax.grid(True, color=t["grid"], linewidth=0.6)
    ax.set_axisbelow(True)
    for s in ax.spines.values():
        s.set_visible(False)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", default="sim1")
    ap.add_argument("--dark", action="store_true")
    ap.add_argument("--smooth", type=int, default=5)
    a = ap.parse_args()
    t = DARK if a.dark else LIGHT
    d = RUNS / a.run
    rows = list(csv.DictReader(open(d / "metrics.csv", encoding="utf-8")))
    if not rows:
        sys.exit("no rows in " + str(d / "metrics.csv"))
    ep = [int(r["epoch"]) for r in rows]
    scen = [k[5:] for k in rows[0] if k.startswith("succ_")]
    stages = scen

    # the tidy CSV: one row an epoch, the numbers a video's chart needs
    out_csv = d / "curves.csv"
    with open(out_csv, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["epoch", "minutes", "decisions", "stage", "return", "noop_frac", "noop_end_frac", "game_s_to_success", "ms_per_decision"]
                   + ["success_" + s for s in scen])
        for r in rows:
            w.writerow([r["epoch"], r["minutes"], r["decisions"], r["stage"], r["return"], r["noop_frac"], r["noop_end_frac"],
                        r["game_s_to_success"], r["ms_per_decision"]] + [r["succ_" + s] for s in scen])

    sfx = "-dark" if a.dark else ""
    panels = []

    def panel(name, title, ylabel, draw, ylim=None):
        fig, ax = plt.subplots(figsize=(8, 4.2), dpi=150)
        fig.patch.set_facecolor(t["surface"])
        style(ax, t, title, ylabel)
        draw(ax)
        ax.set_xlabel("эпоха обучения", color=t["ink2"], fontsize=9)
        if ylim:
            ax.set_ylim(*ylim)
        fig.tight_layout()
        p = d / f"curves-{name}{sfx}.png"
        fig.savefig(p, facecolor=t["surface"])
        plt.close(fig)
        panels.append((name, title, ylabel, draw, ylim))
        return p

    def ret(ax):
        x, y = smooth(ep, [num(r["return"]) for r in rows], a.smooth)
        ax.plot(x, y, color=t["series"][0], linewidth=2)
        ax.axhline(0, color=t["ink2"], linewidth=0.8)

    def noop(ax):
        x, y = smooth(ep, [num(r["noop_frac"]) for r in rows], 1)
        ax.plot(x, [v * 100 for v in y], color=t["series"][1], linewidth=2, label="решений «ничего не делать»")
        x, y = smooth(ep, [num(r["noop_end_frac"]) for r in rows], a.smooth)
        ax.plot(x, [v * 100 for v in y], color=t["series"][0], linewidth=2, label="эпизодов, оборванных им")
        leg = ax.legend(frameon=False, fontsize=8, labelcolor=t["ink"])
        leg.get_frame().set_facecolor(t["surface"])

    def succ(ax):
        for i, s in enumerate(scen):
            x, y = smooth(ep, [num(r["succ_" + s]) for r in rows], a.smooth)
            if x:
                ax.plot(x, [v * 100 for v in y], color=t["series"][i % len(t["series"])], linewidth=2, label=NAMES_RU.get(s, s))
        ax.legend(frameon=False, fontsize=8, ncol=2, labelcolor=t["ink"], loc="lower right")

    # the game seconds to the goal by scenario (episodes.csv; the waves runs have no goal of their own: left out)
    by = {}
    if (d / "episodes.csv").exists():
        for e in csv.DictReader(open(d / "episodes.csv", encoding="utf-8")):
            if e["success"] == "1" and not e["scenario"].startswith("waves"):
                by.setdefault(e["scenario"], {}).setdefault(int(e["epoch"]), []).append(float(e["game_s"]))

    def tts(ax):
        for i, s in enumerate(scen):
            if s not in by:
                continue
            xs = sorted(by[s])
            x, y = smooth(xs, [sum(by[s][k]) / len(by[s][k]) for k in xs], a.smooth)
            ax.plot(x, y, color=t["series"][i % len(t["series"])], linewidth=2, label=NAMES_RU.get(s, s))
        ax.legend(frameon=False, fontsize=8, ncol=3, labelcolor=t["ink"])

    def stage(ax):
        ax.step(ep, [int(r["stage_idx"]) for r in rows], where="post", color=t["series"][6], linewidth=2)
        ax.set_yticks(range(len(stages)))
        ax.set_yticklabels([NAMES_RU.get(s, s) for s in stages])

    made = [panel("return", "Награда агента за эпизод", "награда", ret),
            panel("noop", "«Ничего не делать»: штраф и перезапуск мира", "%", noop, (0, None)),
            panel("success", "Успех по сценариям", "% эпизодов с целью", succ, (0, 102)),
            panel("time", "Время до цели (удачные эпизоды)", "игровых секунд", tts, (0, None)),
            panel("stage", "Учебная программа", "", stage)]
    cols = rows[0].keys()

    def series(ax, keys, labels, scale=1.0):
        for i, (k, lab) in enumerate(zip(keys, labels)):
            x, y = smooth(ep, [num(r.get(k)) for r in rows], a.smooth)
            if x:
                ax.plot(x, [v * scale for v in y], color=t["series"][i % len(t["series"])], linewidth=2, label=lab)
        if any(num(r.get(k)) is not None for k in keys for r in rows):
            ax.legend(frameon=False, fontsize=8, ncol=2, labelcolor=t["ink"])

    waves = [k[9:] for k in cols if k.startswith("breaches_") and any(num(r[k]) is not None for r in rows)]
    if waves:   # the perimeter: "do not let the zombies into the base"
        made.append(panel("breaches", "Прорывы периметра: мобов внутри стены за эпизод", "мобов",
                          lambda ax: series(ax, ["breaches_" + w for w in waves], [NAMES_RU.get(w, w) for w in waves]), (0, None)))
        made.append(panel("mob-seconds", "Мобо-секунды внутри стены за эпизод", "моб·с",
                          lambda ax: series(ax, ["mob_s_" + w for w in waves], [NAMES_RU.get(w, w) for w in waves]), (0, None)))
    if "sustain" in cols and any(num(r["sustain"]) is not None for r in rows):
        made.append(panel("sustain", "Восполняемость: доля съеденной еды с фермы и загона", "%",
                          lambda ax: series(ax, ["sustain"], ["ферма, загон, долгие волны"], 100), (0, 102)))
    if "achievements" in cols:
        made.append(panel("achievements", "Достижения Minecraft: у экипажа за эпизод", "достижений",
                          lambda ax: series(ax, ["achievements", "achievements_scen"], ["все сценарии", "сценарий «достижения»"]), (0, None)))
    if "novel" in cols and any(num(r["novel"]) for r in rows):
        made.append(panel("discoveries", "Открытия: новые следствия действий", "открытий",
                          lambda ax: series(ax, ["novel", "novel_total"], ["за эпоху", "всего"]), (0, None)))
    sc_cols = [k for k in cols if k.startswith("comp_") and k[5:] in SCALES_RU]
    if sc_cols:
        made.append(panel("scales", "Награда по шкалам (агент, эпизод)", "награда",
                          lambda ax: series(ax, sc_cols, [SCALES_RU[k[5:]] for k in sc_cols])))

    # all panels on one sheet
    fig, axs = plt.subplots(len(panels), 1, figsize=(8, 3.0 * len(panels)), dpi=120)
    fig.patch.set_facecolor(t["surface"])
    for ax, (name, title, ylabel, draw, ylim) in zip(axs, panels):
        style(ax, t, title, ylabel)
        draw(ax)
        if ylim:
            ax.set_ylim(*ylim)
    axs[-1].set_xlabel("эпоха обучения (одна итерация PPO)", color=t["ink2"], fontsize=9)
    fig.tight_layout()
    sheet = d / f"curves{sfx}.png"
    fig.savefig(sheet, facecolor=t["surface"])
    plt.close(fig)
    print(sheet)
    for p in made:
        print(p)
    print(out_csv)


if __name__ == "__main__":
    main()
