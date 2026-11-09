# Контракт среды и обучения v2

Версия контракта **2**. Несовместима с v1: число и смысл признаков, число выходов политики и награда меняются
вместе. Источник истины — код: `client/src/hardelele/aiguide/client/RlSpec.java` (действия, правила маски, признаки,
веса, шкалы, сценарии) и `RlAchievements.java` (дерево достижений). Файлы `rl/actions.json`, `rl/obs.json`,
`rl/reward.json`, `rl/achievements.json` генерируются из кода (`python tools/rl/gen_spec.py`) и проверяются
(`gen_spec.py --check`, тест `tools/tests/test_rl_env.py`). Контракт 1 заморожен: `RlSpec1.java` → `rl/v1/*.json`
(байт в байт прежние файлы, [`CONTRACT.md`](CONTRACT.md)).

| файл | версия | хеш | размер |
|---|---|---|---|
| `rl/actions.json` | 2 | `c757991e534a` | 82 действия (первые 38 — v1 в том же порядке) |
| `rl/obs.json` | 2 | `1809e9ced85f` | 322 признака |
| `rl/reward.json` | 2 | `766b9461e818` (веса + `scenarioScales`) | 44 веса, 8 шкал, 17 сценариев |
| `rl/achievements.json` | 2 | — | 33 достижения 1.7.10, 18 поддержаны |

Хеш действий v2 считается по id, навыку, аргументам **и правилу маски** (поменять правило — поменять хеш). Хеши
печатает `gen_spec.py`; среда отдаёт их в `info`.

Мозг экипажа — своя RL-политика; DeepSeek остаётся только у гида. Базовый протокол (TCP, JSON-строки, `info`/`reset`/
`step`/`close`/`shutdown`) и правила честного восприятия — из [`CONTRACT.md`](CONTRACT.md): координаты скрытой руды и
мобов, ещё не увиденных экипажем, в наблюдение не попадают; всё «увиденное» — из памяти экипажа (`Exploration`).

## 1. Версия весов: без молчаливого смешения

`policy.bin` несёт версию в заголовке (`RLP1`, int32 version): 1 — пространства v1 (137 → 38), 2 — v2 (322 → 82).
`RlSpec.space(net)` принимает сеть, только если версия и размеры совпадают с одним из контрактов; иначе мозг пишет
`no policy: …` и не решает (ни молча, ни «как получится»). Сеть v1 в игре работает по-прежнему (вектор и маска
`RlSpec1` из тех же сырых фактов; её действие k — действие k v2). Среда (`-Daiguide.rl=<порт>`) отдаёт только v2.

## 2. Протокол: что добавилось

- `info` → `{"contract":2, "actions":{"version":2,"hash","n":82}, "obs":{"version":2,"hash","dim":322},
  "reward":{"version":2,"hash"}, "achievements":{"version":2,"n":33,"supported":18}, "scenarios":[…], …}`.
- `reset` принимает ещё `"target":"<id достижения>"` — цель учебной программы по дереву достижений (признак
  `ach_target_<id>`; на награду не влияет). Без него — нет цели.
- `step` → в `info` ещё: `events` — список `{"type":"achievement","agent":"1","id":"mineWood","name":"Нарубить дров","ticks":…}`
  за шаг; при `done` — `measured` (сырые суммы всех ключей награды за эпизод, оплаченных и нет: метрики) и
  `achievements` (`{агент: [id…]}`).
- Остальное как в v1: игра стоит, пока политика думает; награда агента копится до его следующего решения.

## 3. Действия — `rl/actions.json`

Каждое действие — навык мозга с фиксированными аргументами; цели выбирает код навыка по увиденному. У каждого
действия в файле есть `"mask"` — **правило маски данными**: `{"all":[…]}`, `{"any":[…]}` или
`["факт", "оп", число или "другой_факт"]`, оп ∈ `> >= < <= == !=`, отсутствующий факт = 0. Java (`RlSpec.Cond.eval`)
и Python (`tools/rl/mask_rules.py: holds`) читают одно и то же правило — переписывать маску в симуляторе не нужно.
`noop` доступен всегда. Маска — подсказка, не обещание успеха: прерванный путь, исчезнувшая цель дают обычный отказ.
Действие с маской 0 исполняется как провал `MASKED`.

`underground` — агент ниже поверхности у устья шахты (на 4 блока и больше). Поверхностные навыки (рубка, стена, ров,
разведка, ферма, загон) из-под земли недоступны.

| # | id | навык (аргументы) | маска (правило) |
|---|---|---|---|
| 0 | `noop` | noop | всегда |
| 1 | `look_around` | look_around | всегда |
| 2 | `go_to:frontier` | go_to (target=frontier) | frontier > 0 и underground < 0.5 |
| 3 | `chop_tree:near` | chop_tree (at=near) | trees_free > 0 и underground < 0.5 |
| 4 | `mine:stone` | mine (block=stone, count=8) | pick_tier >= 1 |
| 5 | `mine:coal_ore` | mine (block=coal_ore, count=3) | pick_tier >= 1 и coal_minable > 0 |
| 6 | `mine:iron_ore` | mine (block=iron_ore, count=3) | pick_tier >= 2 и iron_minable > 0 |
| 7 | `craft:planks` | craft (item=planks, count=4) | craftable_planks > 0 и (small_planks > 0 или СТОЛ) и bag_planks < 64 |
| 8 | `craft:stick` | craft (item=stick, count=4) | craftable, 2×2 или СТОЛ, bag_stick < 32 |
| 9 | `craft:crafting_table` | craft (item=crafting_table) | craftable, 2×2 или СТОЛ, bag_crafting_table <= 0 |
| 10 | `craft:wooden_pickaxe` | craft (item=wooden_pickaxe) | craftable, СТОЛ, pick_tier < 1 |
| 11 | `craft:stone_pickaxe` | craft (item=stone_pickaxe) | craftable, СТОЛ, pick_tier < 2 |
| 12 | `craft:iron_pickaxe` | craft (item=iron_pickaxe) | craftable, СТОЛ, pick_tier < 3 |
| 13 | `craft:wooden_sword` | craft (item=wooden_sword) | craftable, СТОЛ, sword_tier < 1 |
| 14 | `craft:stone_sword` | craft (item=stone_sword) | craftable, СТОЛ, sword_tier < 2 |
| 15 | `craft:iron_sword` | craft (item=iron_sword) | craftable, СТОЛ, sword_tier < 3 |
| 16 | `craft:furnace` | craft (item=furnace) | craftable, СТОЛ, bag_furnace <= 0 |
| 17 | `craft:torch` | craft (item=torch, count=4) | craftable, 2×2 или СТОЛ, bag_torch < 32 |
| 18 | `craft:chest` | craft (item=chest) | craftable, СТОЛ, bag_chest <= 0 и chest_known < 0.5 |
| 19 | `smelt:iron_ingot` | smelt (item=iron_ingot, count=3) | bag_iron_ore > 0 и fuel > 0 и ПЕЧЬ |
| 20 | `smelt:food` | smelt (item=food, count=4) | bag_food_raw > 0 и fuel > 0 и ПЕЧЬ |
| 21 | `place:crafting_table` | place (item=crafting_table, at=near) | bag_crafting_table > 0 и (table_known < 0.5 или table_dist > 6) |
| 22 | `place:furnace` | place (item=furnace, at=near) | bag_furnace > 0 и (furnace_known < 0.5 или furnace_dist > 6) |
| 23 | `place:chest` | place (item=chest, at=near) | bag_chest > 0 и chest_known < 0.5 |
| 24 | `build_wall:ring` | build_wall (around base, height 2, any) | base_known > 0.5 и underground < 0.5 и wall_blocks > 0 и (ring_planned < 0.5 или wall_frac < 1) |
| 25 | `repair_wall:nearest` | repair_wall | underground < 0.5 и ring_planned > 0.5 и holes > 0 и wall_blocks > 0 |
| 26 | `dig_moat:ring` | dig_moat (depth=1) | underground < 0.5 и ring_planned > 0.5 и moat_frac < 1 |
| 27 | `light_area:base` | light_area (radius=12) | bag_torch > 0 и light_spots > 0 |
| 28 | `equip:best` | equip (what=best) | can_equip > 0 |
| 29 | `take_post:auto` | take_post (where=auto) | ring_planned > 0.5 и ((has_post < 0.5 и posts_free > 0) или (has_post > 0.5 и post_dist > 2)) |
| 30 | `hold_line:post` | hold_line (radius=4, seconds=60) | waves_active > 0.5 и health >= low_health |
| 31 | `retreat:home` | retreat (where=home) | base_known > 0.5 и base_dist > 3 |
| 32 | `eat:best` | eat | food_items > 0 и (health < max_health или (hunger_on > 0.5 и food_level < 20)) |
| 33 | `hunt:near` | hunt (at=near) | animals_seen > 0 |
| 34 | `put:chest_all` | put (chest=crew, item=all) | chest_known > 0.5 и bag_stackables > 0 |
| 35 | `take:chest_food` | take (chest=crew, item=food, count=8) | chest_known > 0.5 и chest_food > 0 |
| 36 | `go_to:base` | go_to (target=base) | base_known > 0.5 и base_dist > 4 |
| 37 | `wait:5` | wait (seconds=5) | всегда |
| 38 | `descend:mine` | descend | pick_tier >= 1 и depth_level < 2 и base_known > 0.5 |
| 39 | `ascend:surface` | ascend | underground > 0.5 и mine_known > 0.5 |
| 40 | `tunnel:branch` | tunnel (length=12) | underground > 0.5 и at_horizon > 0.5 и pick_tier >= 1 |
| 41 | `mine:gold_ore` | mine (block=gold_ore, count=3) | pick_tier >= 3 и gold_minable > 0 |
| 42 | `mine:redstone_ore` | mine (block=redstone_ore, count=4) | pick_tier >= 3 и redstone_minable > 0 |
| 43 | `mine:diamond_ore` | mine (block=diamond_ore, count=2) | pick_tier >= 3 и diamond_minable > 0 |
| 44 | `mine:obsidian` | mine (block=obsidian, count=4) | pick_tier >= 4 и obsidian_minable > 0 |
| 45 | `mine:gravel` | mine (block=gravel, count=4) | gravel_minable > 0 |
| 46 | `smelt:gold_ingot` | smelt (item=gold_ingot, count=3) | bag_gold_ore > 0 и fuel > 0 и ПЕЧЬ |
| 47 | `craft:diamond_pickaxe` | craft (item=diamond_pickaxe) | craftable, СТОЛ, pick_tier < 4 |
| 48 | `craft:diamond_sword` | craft (item=diamond_sword) | craftable, СТОЛ, sword_tier < 4 |
| 49 | `craft:bow` | craft (item=bow) | craftable, СТОЛ, bag_bow <= 0 |
| 50 | `craft:arrow` | craft (item=arrow, count=4) | craftable, СТОЛ, bag_arrow < 64 |
| 51 | `craft_armor:leather` | craft_armor (material=leather) | armor_craftable_leather > 0 и СТОЛ |
| 52 | `craft_armor:golden` | craft_armor (material=golden) | armor_craftable_golden > 0 и СТОЛ |
| 53 | `craft_armor:iron` | craft_armor (material=iron) | armor_craftable_iron > 0 и СТОЛ |
| 54 | `craft_armor:diamond` | craft_armor (material=diamond) | armor_craftable_diamond > 0 и СТОЛ |
| 55 | `shoot:post` | shoot (seconds=30) | bag_bow > 0 и bag_arrow > 0 и enemies_visible > 0 |
| 56 | `build_gate:door` | build_gate | underground < 0.5 и ring_planned > 0.5 и bag_wooden_door > 0 и door_placed < 0.5 |
| 57 | `close:gate` | close_gate | door_placed > 0.5 и gate_open > 0.5 |
| 58 | `craft:wooden_hoe` | craft (item=wooden_hoe) | craftable, СТОЛ, hoes <= 0 |
| 59 | `craft:stone_hoe` | craft (item=stone_hoe) | craftable, СТОЛ, hoe_tier < 2 |
| 60 | `craft:bread` | craft (item=bread) | craftable, СТОЛ, bag_bread < 64 |
| 61 | `craft:wooden_door` | craft (item=wooden_door) | craftable, СТОЛ, bag_wooden_door <= 0 и door_placed < 0.5 |
| 62 | `craft:fence` | craft (item=fence, count=2) | craftable, СТОЛ, bag_fence < 32 и pen_built < 0.5 |
| 63 | `craft:fence_gate` | craft (item=fence_gate) | craftable, СТОЛ, bag_fence_gate <= 0 и pen_built < 0.5 |
| 64 | `till:farm` | till (count=8) | base_known > 0.5 и underground < 0.5 и hoes > 0 и farm_plots < 16 и till_spots > 0 |
| 65 | `plant:wheat` | plant (what=wheat, count=8) | bag_wheat_seeds > 0 и farm_empty > 0 |
| 66 | `harvest:farm` | harvest (count=16, replant=yes) | farm_ripe > 0 |
| 67 | `gather:seeds` | gather (what=seeds, count=8) | underground < 0.5 и grass_seen > 0 |
| 68 | `gather:saplings` | gather (what=saplings, count=16) | underground < 0.5 и leaves_seen > 0 |
| 69 | `gather:sugar_cane` | gather (what=sugar_cane, count=6) | underground < 0.5 и cane_seen > 0 |
| 70 | `plant:sapling` | plant (what=sapling, count=4) | underground < 0.5 и bag_sapling > 0 и sapling_spots > 0 |
| 71 | `build_pen:pasture` | build_pen | base_known > 0.5 и underground < 0.5 и bag_fence >= 15 и bag_fence_gate > 0 и pen_built < 0.5 |
| 72 | `lure:pen` | lure | pen_built > 0.5 и lure_food > 0 и animals_seen > 0 и pen_animals < 8 |
| 73 | `breed:pen` | breed | pen_pairs > 0 |
| 74 | `slaughter:pen` | slaughter | pen_adults >= 3 |
| 75 | `craft:paper` | craft (item=paper, count=3) | craftable, СТОЛ, bag_paper < 32 |
| 76 | `craft:book` | craft (item=book) | craftable, 2×2 или СТОЛ, bag_book < 16 |
| 77 | `craft:bookshelf` | craft (item=bookshelf) | craftable, СТОЛ, shelves_total < 15 |
| 78 | `craft:enchanting_table` | craft (item=enchanting_table) | craftable, СТОЛ, bag_enchanting_table <= 0 и ench_table_known < 0.5 |
| 79 | `place:enchanting_table` | place (item=enchanting_table, at=base) | base_known > 0.5 и underground < 0.5 и bag_enchanting_table > 0 и ench_table_known < 0.5 |
| 80 | `place:bookshelf` | place_shelf | bag_bookshelf > 0 и ench_table_known > 0.5 и ench_table_dist <= 16 и shelf_spots > 0 |
| 81 | `enchant:best` | enchant | ench_table_known > 0.5 и ench_table_dist <= 24 и xp_level >= 1 и enchantable > 0 |

СТОЛ = `(table_known > 0.5 и table_dist <= 24) или bag_crafting_table > 0`; ПЕЧЬ — то же для печи. «craftable»
у крафта — `craftable_<item> > 0 и (small_<item> > 0 или СТОЛ)`; точная формула каждого — в `actions.json`.

Что делают новые навыки (честно, телом агента, как игрок):

- `descend:mine` — к устью шахты экипажа (первый — выбирает и копает устье у базы), вниз по своей лестнице и
  лестницей дальше до следующего горизонта: y≈40 (железо, уголь), затем y≈12 (алмазы, золото, редстоун). Ступени
  кладутся из своего булыжника, факелы — где темно. `ascend:surface` — вверх по той же лестнице к устью.
  `tunnel:branch` — ветка 1×2 на 12 блоков на текущем горизонте (руда в стенах становится увиденной).
- `mine:<руда>` — как в v1: только увиденное, в досягаемости, нужный тир кирки (золото/редстоун/алмаз — железная,
  обсидиан — алмазная); гравий — ради кремня.
- `craft_armor:<материал>` — у верстака кусок брони из этого материала, который больше всего прибавит к надетому
  (нагрудник 8, поножи 7, шлем 5, ботинки 4 единицы материала). Надевает `equip:best`. Кольчугу не крафтят (в 1.7.10
  нет рецепта), но `equip:best` наденет найденную.
- `shoot:post` — с места (поста) стрелять из лука по видимым врагам до 30 с: полное натяжение 1 с, стрела из сумки.
- `build_gate:door` — деревянная дверь в проём ворот кольцевой стены (проём 1×2 всегда был открыт); зомби дверь не
  открывают. `close:gate` — закрыть открытую дверь.
- Ферма: `till:farm` — мотыгой до 8 грядок у базы (ближе к воде); `plant:wheat` — семена на пустые грядки;
  `harvest:farm` — собрать спелую пшеницу и сразу пересадить своими семенами; `gather:seeds` — трава → семена;
  `gather:saplings` — листва увиденного дерева → саженцы; `gather:sugar_cane` — тростник (бумага → книги);
  `plant:sapling` — посадить саженцы у базы.
- Загон: `build_pen:pasture` — загон 5×5 из 15 заборов и калитки у базы; `lure:pen` — пшеницей/семенами в руке
  увести видимое животное в загон (животное идёт за агентом, как за игроком с кормом: `EntityAITempt` повторён для
  агента); `breed:pen` — накормить пару взрослых одного вида (корова/овца — пшеница, курица — семена); `slaughter:pen`
  — забить взрослое, если их больше двух.
- Зачарование: `place:enchanting_table` у базы; `place:bookshelf` — полка на месте, которое стол засчитает (кольцо
  5×5 на 2 от стола, на уровне стола или выше, проход пуст); `enchant:best` — лучшая вещь сумки/надетое без чар на
  самый дорогой слот, который позволяет опыт агента (`EnchantmentHelper` игры: уровень по числу полок; стоимость в
  уровнях, как у игрока 1.7.10).

## 4. Сырые факты и наблюдение — `rl/obs.json`

Нормировка как в v1: `div` — `clip(x/scale, -1, 2)`; `log` — `log1p(max(x,0))/log1p(scale)`; `bool` — `x > 0.5`.
Расстояние до того, чего нет, — `scale`. Сервер присылает нормированный вектор; `"raw": true` в `reset` — ещё
сырые факты по именам в `info.raw` (там же факты маски, которых нет среди признаков).

Группы признаков (имена — в `obs.json`):

- сценарий one-hot (17), включённые шкалы `scale_<шкала>` (8), номер агента (4), размер экипажа;
- тело: здоровье, броня, тиры (в руке, оружие, кирка), «дома»; **голод**: `hunger_on`, `food_level` (0–20),
  `saturation`; **опыт**: `xp_level`, `xp_progress`;
- время суток (sin/cos, ночь, до сумерек);
- сумка: 49 счётчиков (`log` до 64) — v1 + руды, слитки, алмаз, редстоун, обсидиан, гравий, кремень, нить, перо, кожа,
  лук, стрелы, алмазные инструменты, `bag_armor` (ненадетая броня), мотыги, семена, пшеница, хлеб, саженцы, тростник,
  бумага, книги, полки, стол зачарований, дверь, забор, калитка; всего предметов;
- увиденное (память экипажа): деревья, камень, уголь, железо, золото, редстоун, алмаз (сколько и ближайшее),
  обсидиан, гравий, трава, листва, тростник, животные;
- станции: верстак, печь, сундук, база, еда в сундуке; стол зачарований (есть, расстояние), полки у стола, свободные
  места для полок;
- шахта: `underground`, `y_rel` (y агента минус y устья/поверхности, `div 64`), `depth_level` (0/1/2 — горизонт),
  `at_horizon`, `mine_known`, `mine_level` (до какого горизонта дошла лестница), `mine_dist` (до устья), `tunnels`,
  `trip_ores` (руды за текущий поход);
- ферма/деревья/загон: грядки, засеяно, спелых, пустых, где можно вспахать, мотыги; посажено саженцев, выросло
  деревьев, места для саженцев; загон есть, животных, взрослых, детёнышей, готовых пар, расстояние;
- ворота: `door_placed`, `gate_open`;
- волны (v1) + `enemies_visible` (видимые враги в 32);
- периметр (v1) + `mobs_inside`, `breaches` (прорывов в этой волне), `mob_seconds` (за эпизод), `clean_waves`;
- восполняемость за окно 120 с: `food_renew`, `food_eaten`, `wood_renew`, `wood_used`; `sources` (0–3); `stock_value`;
- союзники (v1);
- достижения: `ach_<id>` (получено этим агентом) и `ach_target_<id>` (цель из `reset.target`) для 18 поддержанных,
  `ach_count`, `ach_available` (сколько можно получить следующими по дереву);
- прошлый шаг: итог по группам, повторы, длительность, one-hot действия (82);
- эпизод: доля времени, прогресс цели, `stall` (секунд без измеренного прогресса).

Факты только для маски (в `info.raw`, не в векторе): `frontier`, `trees_free`, `<руда>_minable` (coal, iron, gold,
redstone, diamond, obsidian, gravel: увиденное в 32 по горизонтали и 12 по высоте, ещё на месте, не занятое),
`craftable_<item>`, `small_<item>`, `armor_craftable_<материал>`, `fuel`, `wall_blocks`, `light_spots`, `can_equip`
(лучшее оружие не в руке или броня лучше надетой), `post_dist`, `low_health`, `max_health`, `food_items` (съедобное в
сумке), `bag_stackables`, `sword_tier`, `hoe_tier`, `shelves_total` (полки у стола + в сумке), `lure_food`
(пшеница + семена), `enchantable` (вещь без чар, которую можно зачаровать).

Тиры (`pick_tier`, `sword_tier`, `hoe_tier`): дерево 1, камень 2, железо 3, алмаз 4; золотая кирка — 1 (её уровень
добычи деревянный: железо она не берёт).

## 5. Награда — `rl/reward.json`

Платится **изменение измеренного значения**, а не значение каждый тик и не слово навыка `OK`. Измерения — раз в
игровую секунду по миру (блоки, сумки, мобы) и по событиям тела (урон, смерть, съеденное). Каждая составляющая
отдельно в `info.components`; при `done` — `measured`: сырые суммы всех ключей за эпизод, в том числе выключенных
шкал (это метрики, награды в них нет). Командные измерения (стена, периметр, запас, ферма) платятся каждому агенту
экипажа.

### Всегда

| ключ | вес | за что |
|---|---|---|
| `noop` | −1 | выбрал `noop` (и конец эпизода, мир перезапускается при `reset`) |
| `time` | −0.001 | за игровую секунду живого агента |
| `stall` | −0.05 | 60 игровых секунд подряд без измеренного прогресса (окно начинается заново) |
| `skill_fail` | −0.05 | провал навыка (в т. ч. `MASKED`) |
| `skill_ok` | +0.02 | `OK` с проверяемым следом: изменилась сумка. Никогда за `wait:5`, `look_around` |
| `damage` | −0.01 | за единицу полученного урона |
| `death` / `accident` | −1 / −1.5 | смерть в бою / от падения, воды, лавы, огня, **голода** |
| `goal` | +5 | цель сценария достигнута (каждому; конец эпизода) |
| `human` | ±2 | ручная оценка (как v1) |

Прогресс для `stall` — любая положительная выплата шкалы, `goal` или `skill_ok`. Повторные `OK` без прироста
предметов, стройки, защиты или знания времени не окупают.

### Шкалы (только в сценариях, которые их включают)

| шкала | ключи и веса | как измеряется |
|---|---|---|
| `safety` | `safety.wall` 2.0, `safety.moat` 1.0, `safety.light` 0.5, `safety.gate` 0.5 | доля стоящих клеток кольца стены; доля вырытого рва; доля освещённых мест (свет ≥ 8) из мест под факелы внутри кольца; дверь в проёме ворот (0/1). Платится изменение каждой доли |
| `perimeter` | `perimeter.mob_seconds` −0.02, `perimeter.breach` −0.2, `perimeter.hurt_inside` −0.02, `perimeter.clean_wave` +1.0, `perimeter.wave_cleared` +0.5, `perimeter.kill` +0.2, `perimeter.out_died` −0.5, `perimeter.holes_at_wave` −0.05 | **не пропускать зомби на базу**: каждую секунду — враги внутри кольца (Чебышёв < r): мобо-секунды; прорыв — враг внутри впервые за волну; урон агентам внутри кольца; волна без единого прорыва — бонус; отбитая волна; свой килл; гибель вне стены в волну; дыры к началу волны |
| `equipment` | `equipment.armor` 1.0, `equipment.weapon` 0.5, `equipment.pickaxe` 0.5, `equipment.ranged` 0.3 | у агента: надетая броня / 20; лучший меч: тир / 4; лучшая кирка: тир / 4; лук и ≥ 4 стрел (0/1). Изменение; стартовый набор — база (за кит не платится) |
| `mine` | `mine.depth` 0.5, `mine.coal` 0.03, `mine.iron` 0.1, `mine.gold` 0.15, `mine.redstone` 0.03, `mine.diamond` 0.5, `mine.obsidian` 0.1, `mine.return` 1.0 | новый достигнутый горизонт агента (y ≤ 44, y ≤ 16): по 1 за каждый; прибыль руды в сумке (уголь, железная/золотая руда, редстоун, алмаз, обсидиан); **возврат**: агент был на горизонте, добыл руду и вышел на поверхность — 1 за поход |
| `enchant` | `enchant.table` 0.5, `enchant.shelf` 0.05, `enchant.item` 1.0, `enchant.levels` 0.05 | стол зачарований стоит (0/1); полок, которые стол засчитывает (до 15); зачарованных вещей у агента (сумка + надетое); уровней опыта, реально потраченных на чары |
| `sustain` | `sustain.food` 0.05, `sustain.wood` 0.05, `sustain.source` 0.5 | **восполняемость**: R — очки еды из возобновляемого (пшеница с грядок экипажа: хлеб 5 очков на 3 пшеницы; мясо животных, рождённых в загоне), E — очки съеденной еды (любой); платится рост min(R, E): ферма кормит — платится, урожай, который никто не ест, — нет; то же для брёвен с посаженных экипажем деревьев против использованных брёвен; `source` — изменение числа источников: засеянные грядки (из 16) + пара взрослых в загоне + растущие посаженные деревья (из 4), 0..3 (вытоптал и не пересадил — минус) |
| `stock` | `stock.value` 0.5 | Σ min(n, cap)/cap · ценность по запасу экипажа (сумки + сундук): брёвна 32·1, булыжник 128·1, уголь 32·1, железо 24·2, еда (очки) 60·2, факелы 32·0.5, стрелы 32·0.5, семена 16·0.3, пшеница 32·0.5. С потолком: копить сверх — ноль |
| `achievement` | `achievement.new` 0.3, `achievement.depth` 0.05 | новое достижение агента: 0.3 + 0.05 × глубина в дереве |

`scenarioScales` в `reward.json` — множители по сценарию: шкала, не названная у сценария, = 0. Ключ вида
`equipment.pickaxe` у сценария — множитель только этой части (`pickaxe` платит за кирку, но не за меч и броню; `sword` —
за меч). Так `wood` не платит за ров и стену, `pickaxe` — за стену и деревянный меч.

| сценарий | шкалы |
|---|---|
| `wood` | stock, achievement |
| `pickaxe` | equipment (только pickaxe), achievement |
| `sword` | equipment (только weapon), achievement |
| `armor` | equipment (только armor), achievement |
| `iron` | mine, equipment (только pickaxe), achievement |
| `bow` | equipment (только ranged), achievement |
| `mine_trip` | mine, achievement |
| `diamond` | mine, equipment, achievement |
| `farm` | sustain, stock, achievement |
| `pasture` | sustain, stock, achievement |
| `enchant` | enchant, equipment, achievement |
| `fortify` | safety |
| `food` | stock, sustain |
| `waves:easy`, `waves:normal` | safety, perimeter |
| `waves:long` | safety, perimeter, sustain, equipment |
| `achievements` | achievement ×2 |

Константы (`reward.json: constants`): окно застоя 60 с, окно восполняемости 120 с, горизонты y 40 и 12 (±4),
ускорение роста `grow` 20.

## 6. Голод

В сценариях с `hunger: true` агенты голодают как игрок 1.7.10 (`Hunger.java`, перенос `FoodStats`): уровень еды
0–20 и насыщение, истощение от действий (метр ходьбы 0.01, бега 0.1, плавания 0.015, прыжок 0.2/0.8, удар 0.3,
сломанный блок 0.025, урон — свой, вылеченная единица 3.0; 4 истощения — единица насыщения, затем еды). Сыт
(еда ≥ 18) — лечится 1 за 4 с; еда 0 — 1 урона за 4 с до смерти (как «сложно»). Без голода агент сыт всегда (v1).
`eat:best` при голоде ест, пока не наестся (как игрок: нельзя есть сытым), и лечит только через сытость.
Смерть от голода — `accident`.

## 7. Опыт и достижения

Опыт агента — как у игрока: шары опыта (от руды, добытой агентом, от мобов, убитых его ударом, из печи, от
разведения) притягиваются к агенту в 8 блоках и подбираются; уровни по формуле 1.7.10 (17 / 17+3(l−15) / 62+7(l−30) на
уровень). Сценарий `enchant` начинает с 8 уровнями (`xp_levels` в описании сценария).

Достижение даётся **агенту**, а не фиктивному игроку: условие повторено у себя (`rl/achievements.json: condition`)
и проверяется по игровому состоянию; родитель должен быть уже получен этим агентом (правило игры). 18 поддержанных:
`openInventory` (первое решение), `mineWood`, `buildWorkBench`, `buildPickaxe`, `buildFurnace`, `acquireIron`,
`buildHoe`, `makeBread`, `buildBetterPickaxe`, `buildSword`, `killEnemy`, `killCow` (кожа в сумке), `snipeSkeleton`,
`diamonds`, `enchantments`, `overkill`, `bookcase`, `breedCow`. Незер, Энд, рельсы, торт, рыба, свинья с седлом,
алмазы игроку — `supported: false`, учебная программа их не назначает. Новое достижение — событие `achievement` в
`info.events` и строка в журнале сервера; в игре с окном — тост «Новое достижение!» с названием и чат-строка с именем
агента.

## 8. Сценарии

Стартовый набор — каждому агенту; время суток стоит, погода ясная, мобы — только волнами.

| сценарий | старт (каждому) | цель | предел, с |
|---|---|---|---|
| `wood` | пусто | брёвен ≥ 10 × экипаж | 300 |
| `pickaxe` | пусто | `stone_pickaxe` ≥ экипаж | 600 |
| `sword` | пусто | `stone_sword` ≥ экипаж | 600 |
| `armor` | 8 `iron_ingot`, 5 `leather`, верстак | надетая броня ≥ 6 у каждого | 600 |
| `iron` | `stone_pickaxe`, 16 `planks`, 4 `stick` | `iron_pickaxe` ≥ экипаж | 900 |
| `bow` | 3 `string`, 8 `stick`, 4 `flint`, 4 `feather`, верстак | лук и ≥ 8 стрел у каждого | 600 |
| `mine_trip` | `stone_pickaxe`, 16 факелов, 4 `cooked_beef`, 16 булыжника; голод | каждый сходил: горизонт y≈40, руда, назад на поверхность | 900 |
| `diamond` | `iron_pickaxe`, 32 факела, 6 `cooked_beef`, 32 булыжника; голод | ≥ 1 алмаз у экипажа | 1500 |
| `farm` | `wooden_hoe`, 8 семян, 2 `cooked_beef`; голод | хлеба испечено ≥ 2 × экипаж | 1200 |
| `pasture` | 15 заборов, калитка, 16 пшеницы, 8 семян, деревянный меч; голод | ≥ 1 детёныш в загоне | 1200 |
| `enchant` | стол зачарований, 6 полок, железные меч и кирка; 8 уровней | зачарованная вещь у каждого | 600 |
| `fortify` | 140 булыжника, `stone_pickaxe`, дверь | стена целиком и ров ≥ 90 % | 600 |
| `food` | `wooden_sword`, здоровье 10 | еды ≥ 2 × экипаж (регрессия v1) | 300 |
| `waves:easy`, `waves:normal` | `stone_sword`, 64 булыжника, 6 хлеба, 8 факелов; в сундуке 16 хлеба; ночь | нет; конец — проигрыш забега или время | 900 |
| `waves:long` | как волны + мотыга, 8 семян, лук, 32 стрелы, дверь; голод | нет | 2400 |
| `achievements` | пусто; голод | ≥ 8 достижений у каждого | 1800 |

Для волн меряется выживание и прорыв (шкала `perimeter`), не число выполненных навыков.

### Ускорение роста

В среде пшеница, саженцы и детёныши **экипажа** (посаженные/рождённые в эпизоде) растут в `grow` = 20 раз быстрее
игры (лишние случайные тики блоку `updateTick`, детёнышу — лишний возраст): иначе пшеница зреет ~30 мин игрового
времени и ферма в эпизод не помещается. Дикие растения и животные растут как в игре. Это правило среды, как
стоящее время суток; симулятор должен повторять его.

## 9. Проверка перед обучением

- `gen_spec.py --check`: `rl/` и `rl/v1/` — то, что говорит код;
- `tools/tests/test_rl_spec2.py`: Java пишет случайные сырые факты вокруг порогов правил с маской Java
  (`RlSpec cases`), Python (`mask_rules.holds`) даёт ту же маску на всех; отрицательный контроль — правила двух
  действий переставлены — обязан разойтись; версия, порядок, первые 38 действий — v1, хеши;
- `RlTest`: маска, шкалы на числах — `wait` без прибыли не платит, `pickaxe` не платит за ров и стену, ферма без
  съеденного урожая не платит, прорыв моба — штраф, чистая волна — бонус, поход в шахту без руды не засчитан;
- в игре — живой прогон сценариев (`tools/rl/rl_smoke.py`).

Симулятор v2 калибруется по логам реальной среды v1 и затем v2; в отчёте — число эпизодов, причины отказа,
длительности, пределы применимости.

## 10. Что честно и чего нет

- Minecraft не детерминирован: тот же `seed` даёт те же стартовые условия, не тот же исход.
- Навыки ходят, видят, копают и бьют по правилам игрока (`Body`, `Exploration`, `Guard.strike`); цели — только из
  увиденного.
- Агенты не игроки: опыт, достижения, приманивание кормом и голод повторены у себя по коду 1.7.10 (выше).
- Рост растений и детёнышей экипажа ускорен (раздел 8).

## Журнал изменений

- 1 — исходные 38 действий, 137 признаков, общая награда за навыки.
- 2 — 82 действия (шахта по горизонтам с возвратом, руды, броня, лук и стрельба, ворота, ферма, саженцы, загон,
  зачарование), маска данными в `actions.json`, 322 признака (голод, опыт, шахта, ферма, загон, периметр,
  восполняемость, достижения), награда шкалами по сценариям (изменение измеренного; `skill_ok` только со следом;
  застой), голод агентов, опыт, 33 достижения 1.7.10 (18 поддержаны), версия в весах.
