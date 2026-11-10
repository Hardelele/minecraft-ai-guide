"""The film's lines of delayed causes: what a thing an agent made did much later ("каменный меч скрафчен 4.2 мин
назад -> убил 3 зомби"), from a run's chains.jsonl (tools/rl/train.py writes the simulator's: info.chains at an
episode's end), the longest lags first.

    python tools/rl/chains.py --run v3sim1 [--top 30]       # -> build/rl/runs/<run>/chains-ru.txt
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
THING = {"wooden_sword": "деревянный меч", "stone_sword": "каменный меч", "iron_sword": "железный меч", "diamond_sword": "алмазный меч",
         "wooden_pickaxe": "деревянная кирка", "stone_pickaxe": "каменная кирка", "iron_pickaxe": "железная кирка",
         "diamond_pickaxe": "алмазная кирка", "bow": "лук"}
EFFECT = {"kill": "убил {n} зомби", "coal": "добыл {n} угля", "iron_ore": "добыл {n} железной руды", "gold_ore": "добыл {n} золотой руды",
          "diamond": "добыл {n} алм.", "redstone": "добыл {n} редстоуна", "obsidian": "добыл {n} обсидиана"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", required=True)
    ap.add_argument("--runs", default=str(ROOT / "build" / "rl" / "runs"))
    ap.add_argument("--top", type=int, default=30)
    a = ap.parse_args()
    d = Path(a.runs) / a.run
    rows = []
    for line in open(d / "chains.jsonl", encoding="utf-8"):
        ep = json.loads(line)
        for c in ep["chains"]:
            lag = (c["last_s"] - c["made_s"]) / 60
            eff = ", ".join(EFFECT.get(k, k + " {n}").format(n=v) for k, v in c["effects"].items())
            rows.append((lag, f"{THING.get(c['cause'], c['cause'])} скрафчен(а) на {c['made_s'] / 60:.1f} мин -> за {lag:.1f} мин "
                              f"после: {eff} (агент {c['agent'] + 1}, сценарий {ep['scenario']}, эпоха {ep['epoch']})"))
    rows.sort(key=lambda r: -r[0])
    out = d / "chains-ru.txt"
    out.write_text("\n".join(r[1] for r in rows[:a.top]) + "\n", encoding="utf-8")
    print(out, len(rows), "chains")


if __name__ == "__main__":
    main()
