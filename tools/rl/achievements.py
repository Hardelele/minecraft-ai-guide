"""Minecraft 1.7.10's achievements (net.minecraft.stats.AchievementList) with their parents, as the game has them,
the names the game shows in Russian (assets minecraft/lang/ru_RU.lang of 1.7.10), and which of them the simulator can
express (the others are never got there: no Nether, no End, no rails, no cake, no fishing).

The simulator gives an achievement to an agent the first time its condition holds and its parent is got by that agent
(the game's rule: an achievement needs its parent); the crew has one when any agent has it. The curriculum's
achievement track (tools/rl/train.py) walks this tree: the next available achievement (its parent got, it not) is a
target. rl/achievements.json (rl/env's, the contract's) overrides `supported` when it is there.
"""
from __future__ import annotations

import json
from pathlib import Path

# id (the game's statId tail) -> (parent or None, the game's Russian name, whether the simulator can express it)
ACH: dict[str, tuple[str | None, str, bool]] = {
    "openInventory": (None, "Проверить карманы", True),
    "mineWood": ("openInventory", "Нарубить дров", True),
    "buildWorkBench": ("mineWood", "Рабочий стол", True),
    "buildPickaxe": ("buildWorkBench", "Пора в шахту!", True),
    "buildFurnace": ("buildPickaxe", "Горячая штучка", True),
    "acquireIron": ("buildFurnace", "Куй железо пока...", True),
    "buildHoe": ("buildWorkBench", "Дачный сезон!", True),
    "makeBread": ("buildHoe", "Хлеб насущный", True),
    "bakeCake": ("buildHoe", "Вот такие пироги!", False),
    "buildBetterPickaxe": ("buildPickaxe", "Обновка!", True),
    "cookFish": ("buildFurnace", "Вкусная рыбка", False),
    "onARail": ("acquireIron", "Стук колёс", False),
    "buildSword": ("buildWorkBench", "К бою готов!", True),
    "killEnemy": ("buildSword", "Охотник на монстров", True),
    "killCow": ("buildSword", "Мясник", True),
    "flyPig": ("killCow", "Жаль, что свиньи не летают...", False),
    "snipeSkeleton": ("killEnemy", "Снайперская дуэль", True),
    "diamonds": ("acquireIron", "АЛМАЗЫ!", True),
    "diamondsToYou": ("diamonds", "Алмазы для вас!", False),
    "portal": ("diamonds", "В самое пекло", False),
    "ghast": ("portal", "Вернуть отправителю", False),
    "blazeRod": ("portal", "Пламенный привет", False),
    "potion": ("blazeRod", "Юный алхимик", False),
    "theEnd": ("blazeRod", "Конец?", False),
    "theEnd2": ("theEnd", "Конец.", False),
    "enchantments": ("diamonds", "Заклинатель", True),
    "overkill": ("enchantments", "Смертельный удар", False),
    "bookcase": ("enchantments", "Библиотекарь", True),
    "breedCow": ("killCow", "Перенаселение", True),
    "spawnWither": ("theEnd2", "Начало?", False),
    "killWither": ("spawnWither", "Начало.", False),
    "fullBeacon": ("killWither", "Маяковский", False),
    "exploreAllBiomes": ("theEnd", "Время приключений", False),
}

_contract = Path(__file__).resolve().parents[2] / "rl" / "achievements.json"
if _contract.exists():   # the contract's flags win: what the environment can give, the simulator may too
    try:
        for _a in json.loads(_contract.read_text(encoding="utf-8")).get("achievements", []):
            _k = _a.get("id", "").split(".")[-1]
            if _k in ACH and "supported" in _a:
                ACH[_k] = (ACH[_k][0], ACH[_k][1], bool(_a["supported"]) and ACH[_k][2])
    except (ValueError, AttributeError):
        pass

ALL = list(ACH)
SIM = [k for k, v in ACH.items() if v[2]]


def available(got: set[str]) -> list[str]:
    """the next achievements: not got, the parent got (the root when nothing is)"""
    return [k for k in ALL if k not in got and (ACH[k][0] is None or ACH[k][0] in got)]


def depth(k: str) -> int:
    d = 0
    while ACH[k][0] is not None:
        k = ACH[k][0]
        d += 1
    return d
