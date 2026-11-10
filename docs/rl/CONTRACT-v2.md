# Контракт среды и обучения v2 (версия файлов 3 и v3.1)

ТЗ «развитие v2». Версия контракта **3**: 2 — первая редакция этого документа (коммит 10ab7f0, 82 действия, 322
признака); 3 — она же с причинной разметкой, отложенной наградой, примитивами и любопытством (раздел 2.1, 3.1).
Несовместима с v1: число и смысл признаков, число выходов политики и награда меняются вместе. Источник истины — код:
`client/src/hardelele/aiguide/client/RlSpec.java` (действия, правила маски, признаки, веса, шкалы, сценарии),
`RlAchievements.java` (дерево достижений), `RlCause.java` (причины). Файлы `rl/actions.json`, `rl/obs.json`,
`rl/reward.json`, `rl/achievements.json` генерируются из кода (`python tools/rl/gen_spec.py`) и проверяются
(`gen_spec.py --check`, тесты `tools/tests/test_rl_env.py`, `test_rl_spec2.py`). Контракт 1 заморожен:
`RlSpec1.java` → `rl/v1/*.json` (байт в байт прежние файлы, [`CONTRACT.md`](CONTRACT.md)).

**Сейчас в `rl/*.json` — v3.1 (версия файлов 4), раздел 11.** Контракт 3 заморожен: `RlSpec3.java` → `rl/v3/*.json`
(байт в байт те файлы, на которых идёт обучение); сервер по умолчанию отдаёт его.

| контракт | файлы | действия | признаки | награда |
|---|---|---|---|---|
| 3.1 (версия 4) | `rl/*.json` | 136, `45d2a5d06270` | 721, `962ae7c4c441` | `a93b6b405494` |
| 3 | `rl/v3/*.json` | 124, `553dfd026d9d` | 402, `7821a391980f` | `c5c25ba77c8a` |
| 1 | `rl/v1/*.json` | 38, `1e7a66545050` | 137, `4ba70eaea357` | — |

Контракт 3 подробно:

| файл | версия | хеш | размер |
|---|---|---|---|
| `rl/v3/actions.json` | 3 | `553dfd026d9d` | 124 действия (первые 38 — v1 в том же порядке) |
| `rl/v3/obs.json` | 3 | `7821a391980f` | 402 признака |
| `rl/v3/reward.json` | 3 | `c5c25ba77c8a` (веса + `scenarioScales`) | 45 весов, 9 шкал, 17 сценариев |
| `rl/v3/achievements.json` | 3 | — | 33 достижения 1.7.10, 18 поддержаны |

Хеш действий считается по id, навыку, аргументам **и правилу маски** (поменять правило — поменять хеш). Хеши
печатает `gen_spec.py`; среда отдаёт их в `info`.

Мозг экипажа — своя RL-политика; DeepSeek остаётся только у гида. Базовый протокол (TCP, JSON-строки, `info`/`reset`/
`step`/`close`/`shutdown`) и правила честного восприятия — из [`CONTRACT.md`](CONTRACT.md): координаты скрытой руды и
мобов, ещё не увиденных экипажем, в наблюдение не попадают; всё «увиденное» — из памяти экипажа (`Exploration`).

## 1. Версия весов: без молчаливого смешения

`policy.bin` несёт версию в заголовке (`RLP1`, int32 version): 1 — пространства v1 (137 → 38), 3 — эти (402 → 124);
2 — пространства первой редакции (322 → 82), в игре не принимаются. `RlSpec.space(net)` принимает сеть, только если
версия и размеры совпадают с одним из контрактов; иначе мозг пишет `no policy: …` и не решает. Сеть v1 в игре
работает по-прежнему (вектор и маска `RlSpec1` из тех же сырых фактов; её действие k — действие k здесь). Среда
(`-Daiguide.rl=<порт>`) отдаёт только версию 3.

## 2. Протокол: что добавилось

- `info` → `{"contract":3, "actions":{"version":3,"hash","n":124}, "obs":{"version":3,"hash","dim":402},
  "reward":{"version":3,"hash"}, "achievements":{"version":3,"n":33,"supported":18}, "scenarios":[…], …}`.
- `reset` принимает ещё `"target":"<id достижения>"` — цель учебной программы по дереву достижений (признак
  `ach_target_<id>`; на награду не влияет). Без него — нет цели. `"extra_kit":"lava_bucket 2, …"` — добавка к
  стартовому набору каждого агента (для проверок; обучение его не шлёт).
- `step` → в `info` ещё: `assigned` — `{агент: decision_id}` решений, принятых по присланным в этом `step`
  действиям; `events` — события за шаг (раздел 2.1); при `done` — `measured` (сырые суммы всех ключей награды за
  эпизод, оплаченных и нет: метрики) и `achievements` (`{агент: [id…]}`).
- Остальное как в v1: игра стоит, пока политика думает; награда агента копится до его следующего решения.

### 2.1. Причины и отложенная награда

Каждое решение агента получает **`decision_id`** — целое, растущее за жизнь сервера (не повторяется между
эпизодами). Решения политики, рефлексы тела (вода под себя) и начало эпизода (`kind:"spawn"`) — тоже решения.
Ссылка-причина: `{"decision_id", "agent", "tick", "kind", "via"}`: `kind` — id действия (`craft:stone_sword`,
`place:lava@moat`, `reflex:water`), `via` — чем решение действовало на следствие: `hit` (удар/выстрел самого
решения), `block:<id>` (блок, поставленный этим решением, или растёкшийся/разгоревшийся от него: лава, вода, огонь
наследуют метку источника), `item:<id>` (вещь, сделанная этим решением: скрафченная, переплавленная, наполненное
ведро — метка в NBT вещи, только у нештабелируемых: инструменты, оружие, броня, вёдра), `step` (шедшее в тот момент
решение, когда причина не прослежена дальше).

Движок размечает: поставленные и сломанные агентом блоки, жидкости и огонь с наследованием при растекании,
скрафченные и переплавленные вещи, урон и смерть сущностей (`DamageSource` → атакующий агент / его стрела →
решение-удар и оружие в руке → его метка; `lava`, `inFire`, `onFire`, утопление → метка блока у сущности, горение
помнит последнюю метку огня/лавы). Хранилище — в памяти сервера (карта позиция → ссылка, сущность → ссылка),
очищается при сбросе арены; не более 200 000 меток блоков (старейшие уходят). Взрывы, падение блоков и урон от
падения не прослеживаются (цепочка — пустая или `step`).

`info.events` — список событий шага:

- `{"type":"reward","agent","key","points","chain":[ссылки…]}` — каждое начисление, кроме `time`, сгруппированное
  по (агент, ключ, первая ссылка) за шаг. Для событий тела (убийство, урон, смерть, спасение) цепочка — прослеженные
  причины, ближняя первой (удар → меч, которым ударили → решение, скрафтившее меч); для измеренных изменений шкал —
  решение, шедшее у агента, и решения, менявшие блоки в эту секунду. Отложенная награда: обучение может отнести
  `points` к решению `decision_id` из цепочки, а не только к текущему шагу.
- `{"type":"achievement","agent","id","name","ticks"}`.
- `{"type":"novel_effect","agent","cause","effect","first_ever","chain","ticks"}` — впервые в эпизоде (и
  `first_ever` — впервые за жизнь журнала) встретилась связь вида «причина × следствие», например
  `block:lava` → `mob_killed`, `item:stone_sword` → `mob_killed`, `block:water` → `agent_saved`. Виды следствий:
  `mob_hurt`, `mob_killed`, `animal_killed`, `agent_hurt`, `agent_killed`, `agent_saved` (агент был в лаве или падал
  с высоты ≥ 5 и через 3 с жив и не горит). Каждая новая за эпизод связь со следствием не на экипаже (моб, животное,
  спасение) платит `curiosity.novel` (шкала `curiosity`, включена во всех сценариях).
- Журнал открытий `discoveries.jsonl` (папка мира `aiguide-rl/`, или `-Daiguide.rl.discoveries=<файл>`): строка на
  каждую связь, новую за жизнь журнала: `{"what":"block:lava → mob_killed","who","chain","episode","scenario","ticks",
  "time"}` — для ролика.

`decision_id` в цепочке может принадлежать решению, ответ на которое уже прошёл; `assigned` связывает id с
действием, которое политика выбрала.

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
| 8 | `craft:stick` | craft (item=stick, count=4) | craftable_stick > 0 и (small_stick > 0 или СТОЛ) и bag_stick < 32 |
| 9 | `craft:crafting_table` | craft (item=crafting_table, count=1) | craftable_crafting_table > 0 и (small_crafting_table > 0 или СТОЛ) и bag_crafting_table <= 0 |
| 10 | `craft:wooden_pickaxe` | craft (item=wooden_pickaxe, count=1) | craftable_wooden_pickaxe > 0 и (small_wooden_pickaxe > 0 или СТОЛ) и pick_tier < 1 |
| 11 | `craft:stone_pickaxe` | craft (item=stone_pickaxe, count=1) | craftable_stone_pickaxe > 0 и (small_stone_pickaxe > 0 или СТОЛ) и pick_tier < 2 |
| 12 | `craft:iron_pickaxe` | craft (item=iron_pickaxe, count=1) | craftable_iron_pickaxe > 0 и (small_iron_pickaxe > 0 или СТОЛ) и pick_tier < 3 |
| 13 | `craft:wooden_sword` | craft (item=wooden_sword, count=1) | craftable_wooden_sword > 0 и (small_wooden_sword > 0 или СТОЛ) и sword_tier < 1 |
| 14 | `craft:stone_sword` | craft (item=stone_sword, count=1) | craftable_stone_sword > 0 и (small_stone_sword > 0 или СТОЛ) и sword_tier < 2 |
| 15 | `craft:iron_sword` | craft (item=iron_sword, count=1) | craftable_iron_sword > 0 и (small_iron_sword > 0 или СТОЛ) и sword_tier < 3 |
| 16 | `craft:furnace` | craft (item=furnace, count=1) | craftable_furnace > 0 и (small_furnace > 0 или СТОЛ) и bag_furnace <= 0 |
| 17 | `craft:torch` | craft (item=torch, count=4) | craftable_torch > 0 и (small_torch > 0 или СТОЛ) и bag_torch < 32 |
| 18 | `craft:chest` | craft (item=chest, count=1) | craftable_chest > 0 и (small_chest > 0 или СТОЛ) и bag_chest <= 0 и chest_known < 0.5 |
| 19 | `smelt:iron_ingot` | smelt (item=iron_ingot, count=3) | bag_iron_ore > 0 и fuel > 0 и ПЕЧЬ |
| 20 | `smelt:food` | smelt (item=food, count=4) | bag_food_raw > 0 и fuel > 0 и ПЕЧЬ |
| 21 | `place:crafting_table` | place (item=crafting_table, at=near) | bag_crafting_table > 0 и place_spot > 0.5 и (table_known < 0.5 или table_dist > 6) |
| 22 | `place:furnace` | place (item=furnace, at=near) | bag_furnace > 0 и place_spot > 0.5 и (furnace_known < 0.5 или furnace_dist > 6) |
| 23 | `place:chest` | place (item=chest, at=near) | bag_chest > 0 и place_spot > 0.5 и chest_known < 0.5 |
| 24 | `build_wall:ring` | build_wall (shape=around base, height=2, material=any) | base_known > 0.5 и underground < 0.5 и wall_blocks > 0 и (ring_planned < 0.5 или wall_frac < 1) |
| 25 | `repair_wall:nearest` | repair_wall | underground < 0.5 и ring_planned > 0.5 и holes > 0 и wall_blocks > 0 |
| 26 | `dig_moat:ring` | dig_moat (depth=1) | underground < 0.5 и ring_planned > 0.5 и moat_frac < 1 |
| 27 | `light_area:base` | light_area (radius=12) | bag_torch > 0 и light_spots > 0 |
| 28 | `equip:best` | equip (what=best) | can_equip > 0 |
| 29 | `take_post:auto` | take_post (where=auto) | ring_planned > 0.5 и ((has_post < 0.5 и posts_free > 0) или (has_post > 0.5 и post_dist > 2)) |
| 30 | `hold_line:post` | hold_line (radius=4, seconds=60) | waves_active > 0.5 и health >= low_health |
| 31 | `retreat:home` | retreat (where=home) | base_known > 0.5 и base_dist > 3 |
| 32 | `eat:best` | eat | food_items > 0 и ((hunger_on < 0.5 и health < max_health) или (hunger_on > 0.5 и food_level < 20)) |
| 33 | `hunt:near` | hunt (at=near) | animals_seen > 0 |
| 34 | `put:chest_all` | put (chest=crew, item=all, count=1728) | chest_known > 0.5 и bag_stackables > 0 |
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
| 47 | `craft:diamond_pickaxe` | craft (item=diamond_pickaxe, count=1) | craftable_diamond_pickaxe > 0 и (small_diamond_pickaxe > 0 или СТОЛ) и pick_tier < 4 |
| 48 | `craft:diamond_sword` | craft (item=diamond_sword, count=1) | craftable_diamond_sword > 0 и (small_diamond_sword > 0 или СТОЛ) и sword_tier < 4 |
| 49 | `craft:bow` | craft (item=bow, count=1) | craftable_bow > 0 и (small_bow > 0 или СТОЛ) и bag_bow <= 0 |
| 50 | `craft:arrow` | craft (item=arrow, count=4) | craftable_arrow > 0 и (small_arrow > 0 или СТОЛ) и bag_arrow < 64 |
| 51 | `craft:bucket` | craft (item=bucket, count=1) | craftable_bucket > 0 и (small_bucket > 0 или СТОЛ) и buckets < 2 |
| 52 | `craft:flint_and_steel` | craft (item=flint_and_steel, count=1) | craftable_flint_and_steel > 0 и (small_flint_and_steel > 0 или СТОЛ) и bag_flint_and_steel <= 0 |
| 53 | `craft_armor:leather` | craft_armor (material=leather) | armor_craftable_leather > 0 и СТОЛ |
| 54 | `craft_armor:golden` | craft_armor (material=golden) | armor_craftable_golden > 0 и СТОЛ |
| 55 | `craft_armor:iron` | craft_armor (material=iron) | armor_craftable_iron > 0 и СТОЛ |
| 56 | `craft_armor:diamond` | craft_armor (material=diamond) | armor_craftable_diamond > 0 и СТОЛ |
| 57 | `shoot:post` | shoot (seconds=30) | bag_bow > 0 и bag_arrow > 0 и enemies_visible > 0 |
| 58 | `build_gate:door` | build_gate | underground < 0.5 и ring_planned > 0.5 и bag_wooden_door > 0 и door_placed < 0.5 |
| 59 | `close:gate` | close_gate | door_placed > 0.5 и gate_open > 0.5 |
| 60 | `craft:wooden_hoe` | craft (item=wooden_hoe, count=1) | craftable_wooden_hoe > 0 и (small_wooden_hoe > 0 или СТОЛ) и hoes <= 0 |
| 61 | `craft:stone_hoe` | craft (item=stone_hoe, count=1) | craftable_stone_hoe > 0 и (small_stone_hoe > 0 или СТОЛ) и hoe_tier < 2 |
| 62 | `craft:bread` | craft (item=bread, count=1) | craftable_bread > 0 и (small_bread > 0 или СТОЛ) и bag_bread < 64 |
| 63 | `craft:wooden_door` | craft (item=wooden_door, count=1) | craftable_wooden_door > 0 и (small_wooden_door > 0 или СТОЛ) и bag_wooden_door <= 0 и door_placed < 0.5 |
| 64 | `craft:fence` | craft (item=fence, count=2) | craftable_fence > 0 и (small_fence > 0 или СТОЛ) и bag_fence < 32 и pen_built < 0.5 |
| 65 | `craft:fence_gate` | craft (item=fence_gate, count=1) | craftable_fence_gate > 0 и (small_fence_gate > 0 или СТОЛ) и bag_fence_gate <= 0 и pen_built < 0.5 |
| 66 | `till:farm` | till (count=8) | base_known > 0.5 и underground < 0.5 и hoes > 0 и farm_plots < 16 и till_spots > 0 |
| 67 | `plant:wheat` | plant (what=wheat, count=8) | bag_wheat_seeds > 0 и farm_empty > 0 |
| 68 | `harvest:farm` | harvest (count=16, replant=yes) | farm_ripe > 0 |
| 69 | `gather:seeds` | gather (what=seeds, count=8) | underground < 0.5 и grass_seen > 0 |
| 70 | `gather:saplings` | gather (what=saplings, count=16) | underground < 0.5 и leaves_seen > 0 |
| 71 | `gather:sugar_cane` | gather (what=sugar_cane, count=6) | underground < 0.5 и cane_seen > 0 |
| 72 | `plant:sapling` | plant (what=sapling, count=4) | underground < 0.5 и bag_sapling > 0 и sapling_spots > 0 |
| 73 | `build_pen:pasture` | build_pen | base_known > 0.5 и underground < 0.5 и bag_fence >= 15 и bag_fence_gate > 0 и pen_built < 0.5 |
| 74 | `lure:pen` | lure | pen_built > 0.5 и lure_targets > 0 и pen_animals < 8 |
| 75 | `breed:pen` | breed | pen_pairs > 0 |
| 76 | `slaughter:pen` | slaughter | pen_adults >= 3 |
| 77 | `craft:paper` | craft (item=paper, count=3) | craftable_paper > 0 и (small_paper > 0 или СТОЛ) и bag_paper < 32 |
| 78 | `craft:book` | craft (item=book, count=1) | craftable_book > 0 и (small_book > 0 или СТОЛ) и bag_book < 16 |
| 79 | `craft:bookshelf` | craft (item=bookshelf, count=1) | craftable_bookshelf > 0 и (small_bookshelf > 0 или СТОЛ) и shelves_total < 15 |
| 80 | `craft:enchanting_table` | craft (item=enchanting_table, count=1) | craftable_enchanting_table > 0 и (small_enchanting_table > 0 или СТОЛ) и bag_enchanting_table <= 0 и ench_table_known < 0.5 |
| 81 | `place:enchanting_table` | place (item=enchanting_table, at=base) | base_known > 0.5 и underground < 0.5 и bag_enchanting_table > 0 и ench_table_known < 0.5 |
| 82 | `place:bookshelf` | place_shelf | bag_bookshelf > 0 и ench_table_known > 0.5 и ench_table_dist <= 16 и shelf_spots > 0 |
| 83 | `enchant:best` | enchant | ench_table_known > 0.5 и ench_table_dist <= 24 и xp_level >= 1 и enchantable > 0 |
| 84–134 | 84 `place:block@front`, 85 `place:block@below`, 86 `place:block@front_down`, 87 `place:block@hole`, 88 `place:block@moat`, 89 `place:block@mob`, 131 `place:block@left`, 132 `place:block@right`, 133 `place:block@back`, 134 `place:block@above` | place_at (slot=block, target=<t>) | slot_block > 0 и tgt_<t> > 0.5 и tgt_<t>_free > 0.5 |
| 90–95 | 90 `place:water@front`, 91 `place:water@below`, 92 `place:water@front_down`, 93 `place:water@hole`, 94 `place:water@moat`, 95 `place:water@mob` | place_at (slot=water, target=<t>) | bag_water_bucket > 0 и tgt_<t> > 0.5 и tgt_<t>_free > 0.5 |
| 96–101 | 96 `place:lava@front`, 97 `place:lava@below`, 98 `place:lava@front_down`, 99 `place:lava@hole`, 100 `place:lava@moat`, 101 `place:lava@mob` | place_at (slot=lava, target=<t>) | bag_lava_bucket > 0 и tgt_<t> > 0.5 и tgt_<t>_free > 0.5 |
| 102–107 | 102 `place:torch@front`, 103 `place:torch@below`, 104 `place:torch@front_down`, 105 `place:torch@hole`, 106 `place:torch@moat`, 107 `place:torch@mob` | place_at (slot=torch, target=<t>) | bag_torch > 0 и tgt_<t> > 0.5 и tgt_<t>_free > 0.5 |
| 108–113 | 108 `use:bucket@front`, 109 `use:bucket@below`, 110 `use:bucket@front_down`, 111 `use:bucket@hole`, 112 `use:bucket@moat`, 113 `use:bucket@mob` | use_at (slot=bucket, target=<t>) | bag_bucket > 0 и tgt_<t> > 0.5 и (tgt_<t>_water_src > 0.5 или tgt_<t>_lava_src > 0.5) |
| 114–119 | 114 `use:fire@front`, 115 `use:fire@below`, 116 `use:fire@front_down`, 117 `use:fire@hole`, 118 `use:fire@moat`, 119 `use:fire@mob` | use_at (slot=fire, target=<t>) | bag_flint_and_steel > 0 и tgt_<t> > 0.5 и tgt_<t>_free > 0.5 |
| 120 | `attack@mob` | attack (target=mob) | tgt_mob > 0.5 |
| 121 | `attack@animal` | attack (target=animal) | animals_seen > 0 |
| 122 | `open@front` | open (target=front) | tgt_front_door > 0.5 |
| 123 | `open@gate` | open (target=gate) | door_placed > 0.5 |
| 124 | `dig@below` | dig_at (target=below) | nb_below_dig > 0.5 |
| 125 | `dig@front` | dig_at (target=front) | nb_front_dig > 0.5 |
| 126 | `dig@front_down` | dig_at (target=front_down) | nb_front_down_dig > 0.5 |
| 127 | `dig@left` | dig_at (target=left) | nb_left_dig > 0.5 |
| 128 | `dig@right` | dig_at (target=right) | nb_right_dig > 0.5 |
| 129 | `dig@back` | dig_at (target=back) | nb_back_dig > 0.5 |
| 130 | `dig@above` | dig_at (target=above) | nb_above_dig > 0.5 |
| 135 | `pillar:up` | pillar | slot_block > 0 и pillar_ok > 0.5 |

СТОЛ = `(table_known > 0.5 и table_dist <= 24) или (bag_crafting_table > 0 и place_spot > 0.5)`; ПЕЧЬ — то же для печи (`place_spot` — свободная клетка рядом). «craftable»
у крафта — `craftable_<item> > 0 и (small_<item> > 0 или СТОЛ)`; точная формула каждого — в `actions.json`.

### 3.1. Примитивы второго уровня

Кроме навыков-макросов — примитивы: вещь из **слота руки** — в одну из **целей-слотов**. Пространство остаётся
дискретным: цели — фиксированный набор, каждая в наблюдении (`tgt_<цель>` есть, `_dist`, `_free` — можно поставить:
воздух, трава, текучая жидкость; `_water`, `_lava` — там вода/лава; в фактах маски `_water_src`, `_lava_src` —
источник).

| цель | клетка |
|---|---|
| `front` | перед агентом на уровне ног (по взгляду, 1 блок) |
| `below` | своя клетка (ноги) |
| `front_down` | перед агентом на блок ниже ног (яма, склон) |
| `hole` | ближайшая дыра кольцевой стены (в 24) |
| `moat` | ближайшая клетка рва (в 24) |
| `mob` | клетка ног ближайшего видимого врага (в 16) |

Слоты руки для `place`: `block` (строительный блок сумки: булыжник, доски, земля…; факт `slot_block`), `water`
(ведро воды), `lava` (ведро лавы), `torch`. Для `use`: `bucket` — пустым ведром набрать источник воды или лавы в
цели; `fire` — огниво: поджечь цель. `attack@mob` — ударить ближайшего видимого врага (подойти и бить до 10 с или
смерти), `attack@animal` — животное. `open@front` — открыть/закрыть дверь или калитку перед собой, `open@gate` —
дверь ворот. Агент подходит к цели, ставит как игрок (рука, взгляд на грань, досягаемость); ведро после выливания
становится пустым, наполненное — ведром воды/лавы с меткой решения.

Рефлекс тела (без политики, кроме решения): агент с ведром воды, упавший в лаву или падающий с высоты ≥ 5 (за 2
блока до земли), ставит воду под себя (`reflex:water`, отдельное решение с `decision_id`); вода остаётся, ведро —
пустое.

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

- сценарий one-hot (17), включённые шкалы `scale_<шкала>` (9), номер агента (4), размер экипажа;
- тело: здоровье, броня, тиры (в руке, оружие, кирка), «дома»; **голод**: `hunger_on`, `food_level` (0–20),
  `saturation`; **опыт**: `xp_level`, `xp_progress`;
- время суток (sin/cos, ночь, до сумерек);
- сумка: 53 счётчика (`log` до 64) — v1 + руды, слитки, алмаз, редстоун, обсидиан, гравий, кремень, нить, перо, кожа,
  лук, стрелы, алмазные инструменты, `bag_armor` (ненадетая броня), мотыги, семена, пшеница, хлеб, саженцы, тростник,
  бумага, книги, полки, стол зачарований, дверь, забор, калитка, ведро (пустое, с водой, с лавой), огниво; всего предметов;
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
- цели примитивов (раздел 3.1): по 6 целям `tgt_<цель>`, `_dist`, `_free`, `_water`, `_lava`; дверь перед собой
  `tgt_front_door`; строительных блоков в руке `slot_block`;
- причины: `novel_count` — новых за эпизод связей «причина × следствие»;
- союзники (v1);
- достижения: `ach_<id>` (получено этим агентом) и `ach_target_<id>` (цель из `reset.target`) для 18 поддержанных,
  `ach_count`, `ach_available` (сколько можно получить следующими по дереву);
- прошлый шаг: итог по группам, повторы, длительность, one-hot действия (124);
- эпизод: доля времени, прогресс цели, `stall` (секунд без измеренного прогресса).

Факты только для маски (в `info.raw`, не в векторе): `frontier`, `trees_free`, `<руда>_minable` (coal, iron, gold,
redstone, diamond, obsidian, gravel: увиденное в 32 по горизонтали и 12 по высоте, ещё на месте, не занятое),
`craftable_<item>`, `small_<item>`, `armor_craftable_<материал>`, `fuel`, `wall_blocks`, `light_spots`, `can_equip`
(лучшее оружие не в руке или броня лучше надетой), `post_dist`, `low_health`, `max_health`, `food_items` (съедобное в
сумке), `bag_stackables`, `sword_tier`, `hoe_tier`, `shelves_total` (полки у стола + в сумке), `lure_food`
(пшеница + семена), `lure_targets` (видимые взрослые животные вне загона, чей корм есть в сумке), `place_spot` (свободная клетка рядом: свой
верстак, печь, сундук), `enchantable` (вещь без чар, которую можно зачаровать), `buckets` (вёдер всех видов),
`tgt_<цель>_water_src`, `tgt_<цель>_lava_src` (источник жидкости в цели).

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
| `curiosity` | `curiosity.novel` 0.2 | связь «причина × следствие», новая в эпизоде, со следствием на мобе, животном или спасением агента (раздел 2.1) |

`scenarioScales` в `reward.json` — множители по сценарию: шкала, не названная у сценария, = 0; `curiosity` включена у всех (1). Ключ вида
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
| `armor` | 12 `iron_ingot`, 5 `leather`, верстак | надетая броня ≥ 8 у каждого (только 4 слота брони) | 600 |
| `iron` | `stone_pickaxe`, 16 `planks`, 4 `stick` | `iron_pickaxe` ≥ экипаж | 900 |
| `bow` | 3 `string`, 8 `stick`, 4 `flint`, 4 `feather`, верстак | лук и ≥ 8 стрел у каждого | 600 |
| `mine_trip` | 2 `stone_pickaxe`, 16 факелов, 4 `cooked_beef`, 16 булыжника; голод | каждый сходил: горизонт y≈40, руда, назад на поверхность | 900 |
| `diamond` | 2 `iron_pickaxe`, 32 факела, 6 `cooked_beef`, 32 булыжника; голод | ≥ 1 алмаз у экипажа | 1500 |
| `farm` | `wooden_hoe`, 8 семян, 2 `cooked_beef`, верстак; голод | хлеба испечено ≥ 2 × экипаж | 1200 |
| `pasture` | 15 заборов, калитка, 16 пшеницы, 8 семян, деревянный меч; голод; 3 коровы и 3 овцы в 10–20 блоках от базы | ≥ 1 детёныш в загоне | 1200 |
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
- `RlTest`: причины на синтетических событиях — лава агента растеклась и убила зомби (цепочка до `place:lava@moat`),
  меч, скрафченный решением, → удар → убийство (цепочка удар → крафт), ведро воды → рефлекс → спасение; новизна
  связи — один раз за эпизод;
- в игре — живой прогон сценариев (`tools/rl/rl_smoke.py`).

Симулятор v2 калибруется по логам реальной среды v1 и затем v2; в отчёте — число эпизодов, причины отказа,
длительности, пределы применимости.

## 10. Что честно и чего нет

- Minecraft не детерминирован: тот же `seed` даёт те же стартовые условия, не тот же исход.
- Навыки ходят, видят, копают и бьют по правилам игрока (`Body`, `Exploration`, `Guard.strike`); цели — только из
  увиденного.
- Агенты не игроки: опыт, достижения, приманивание кормом и голод повторены у себя по коду 1.7.10 (выше).
- Рост растений и детёнышей экипажа ускорен (раздел 8).
- Причины не прослеживаются через взрывы, падающие блоки, урон от падения и предметы, переложенные из штабелей
  (метка — только у нештабелируемых вещей).

## 11. v3.1: копать и строить вокруг себя

Зачем: в v3 у агентов не было действий «копнуть соседний блок», «поставить блок сбоку/над головой», «встать на блок
под собой» — поэтому они не копали вниз, не строили столб, не обкладывались блоками и не складывали укрытия даже
случайно. v3.1 = v3 + эти примитивы; ничего готового (бункер, яма) нет — складывается из них.

**Совместимость.** Первые 124 действия v3.1 — действия v3 с теми же правилами маски; веса награды — те же плюс
`safety.shelter` с весом 0. Сервер отдаёт оба контракта: по умолчанию 3 (`info` как раньше, плюс `contracts` — хеши
обоих), `reset` с `"contract":"3.1"` — эпизод v3.1 (`"contract":"3"` — явно v3); `-Daiguide.rl.contract=3.1` меняет
умолчание. В эпизоде v3 вектор, маска, сырые факты и награда — как были (новые факты из `info.raw` убраны, метрика
укрытия не считается). `policy.bin` версии 4 — сеть v3.1, версии 3 — сеть v3 (в игре берётся по версии и размеру).

**Действия (124–135).** Направления — по взгляду агента: `front`, `back`, `left`, `right` — клетки на уровне ног,
`above` — над головой (y+2), `below` — под ногами, `front_down` — перед собой на блок ниже.

- `dig@below`, `dig@front`, `dig@front_down`, `dig@left`, `dig@right`, `dig@back`, `dig@above` — копать телом, как
  игрок: видимое, в досягаемости, лучшим инструментом сумки. Сбоку копается столбец (голова, потом ноги). Под собой —
  копает и падает в яму (без шага в сторону), но не над лавой (лава под блоком или рядом с ним) и не над обрывом в 3+
  блока: маска 0 и отказ `NO_SPOT`. Блок, который инструменты сумки не берут, и бедрок — маска 0.
  Маска: `nb_<d>_dig > 0.5`.
- `place:block@left`, `place:block@right`, `place:block@back`, `place:block@above` — строительный блок сумки в клетку
  (вдобавок к `@front`, `@below`, `@front_down`, `@hole`, `@moat`, `@mob` из v3). Маска: `slot_block > 0` и
  `nb_<d>_free > 0.5` (клетка свободна, без моба, есть к чему приставить).
- `pillar:up` — прыжок и блок под ноги в верхней точке (как игрок строит под себя). Маска: `slot_block > 0` и
  `pillar_ok > 0.5` (над головой пусто, под ногами твёрдое).

**Признаки (+319, всего 721).** По 6 соседним клеткам (`front`, `back`, `left`, `right`, `above`, `below`):
`nb_<d>_solid`, `nb_<d>_air`, `nb_<d>_liquid`, `nb_<d>_mob` (враг в клетке; у боковых — и на уровне головы);
`walls_around` (сколько из 4 сторон закрыто на уровне ног, `div 4`), `enclosed` (4 стороны закрыты на уровне ног и
головы и есть крыша над головой — дверь считается стеной), `shelter` (`enclosed` ночью или во время волны). Факты
только для маски: `nb_<d>_dig` (7 направлений), `nb_<d>_free` (4), `pillar_ok`. Геометрия — `RlNear.java` (без игры).

**Награда.** Новой большой награды нет: укрытие уже окупается шкалами выживания (`damage`, `death`), периметра и
безопасности. Метрика `safety.shelter` (вес 0) — секунды, которые агент провёл закрытым ночью или во время волны;
видна в `info.measured`.

**Память неудач (только 3.1).** Чтобы при неудаче пробовать иначе: по каждому действию — `fail_run_<id>` (провалов
подряд с его последнего успеха, `div 4`) и `fail_recent_<id>` (доля провалов этим действием среди последних 20
решений агента); `lastfail_<группа>` — группа причины последнего провала: `no_path` (нет пути, далеко, таймаут),
`no_spot` (нет места, `BLOCKED`), `not_visible` (не видно, не найдено, ушло), `no_items` (нет предмета), `no_tool`
(нет инструмента, верстака, печи), `occupied` (занято), `other`. В награде (всегда, вне шкал; в эпизодах v3 не
платятся): `retry_fail` −0.03 × (k−1) за k-й подряд провал того же действия (цель задаёт само действие: «ближайшее
дерево», «руда в досягаемости»), растёт с каждым повтором; `switch_ok` +0.05 — успех другим действием сразу после
провала (смена подхода). Отдельные компоненты в `info.components`. Обходы внутри навыков не добавляются: обход —
задача сети из примитивов (`dig@`, `place:block@`, `pillar:up`). Логика — `RlScore.Fails`, проверка `RlTest.fails`.

**Исправления по живому дублю v1 (и в v3 — без смены пространства).** `chop_tree:near` берёт только дерево, до
которого есть путь (поиск пути игры; смотрятся 5 ближайших), недоступное запоминается как неудавшаяся цель ходьбы
(`Walks`: повтор через 10 с, 20 с, после третьего — через 2 мин) и не выбирается снова; факт `trees_free` (маска
`chop_tree:near` в обоих контрактах) теперь считает только такие деревья; в v3.1 то же число — признак
`trees_reachable` (`div 5`). Рефлекс отступления от моба выбирает клетку, до которой есть путь (дом, свои, затем
прочь от моба по 8 направлениям на 10 и 6 блоков); некуда — бьёт в ответ; ходьба, не приблизившаяся за 2 с,
кончается (раньше стоял до 200 тиков с `BLOCKED`). Логика выбора — `Reach.java` (без игры), проверена в `RlTest.reach`.
Клетка ног агента считается с допуском 0.1 по высоте (раньше на склоне бралась клетка под ногами). Висящая
верхушка (ни одной клетки рядом в пределах блока по высоте от нижнего бревна) не считается доступным деревом и не
выбирается — сам навык столб не строит (по правилу «без обходов внутри навыков»; `Fell` со столбом включается
только `-Daiguide.chop.pillar=true`). Недоступные стволы помнит весь экипаж (2 мин), четверо к одному не идут.

**Проверка.** `RlTest.near`: синтетические миры — столб вверх (на столбе можно вверх ещё, блок сбоку не к чему
приставить), яма вниз (стены вокруг, открыто сверху; блок над головой → `enclosed`, ночью `shelter`), нельзя вниз над
лавой и над обрывом, обложиться 4 блоками (`walls_around` 4, не `enclosed`); маски тех же фактов. `test_rl_spec2.py`:
маски Java = Python и для новых действий, первые 124 действия и веса — v3, `rl/v3` — прежние хеши.

## Журнал изменений

- 1 — исходные 38 действий, 137 признаков, общая награда за навыки.
- 3.1 (версия файлов 4) — копать и строить вокруг себя: `dig@…` (7), `place:block@left|right|back|above`, `pillar:up`;
  39 признаков соседних клеток и укрытия; метрика `safety.shelter`; сервер отдаёт и 3, и 3.1 (раздел 11).
- 3 — причинная разметка движка и цепочки причин у событий награды (`decision_id`, `assigned`, `events`),
  примитивы второго уровня (`place:<слот>@<цель>`, `use:bucket|fire@<цель>`, `attack@`, `open@`; крафт ведра и огнива),
  42 + 2 действия (124), признаки целей и `novel_count` (402), шкала `curiosity`, журнал открытий, рефлекс «вода под
  себя». Правка по живому прогону (хеш действий d0bdaadf0eb3 → 553dfd026d9d, версия та же): `eat:best` при голоде —
  только когда не сыт (как игрок, даже раненый), `lure:pen` — по `lure_targets`, свой верстак/печь/сундук из сумки — по
  `place_spot`; наборы `armor`, `mine_trip`, `diamond`, `farm`, животные `pasture`.
- 2 — 82 действия (шахта по горизонтам с возвратом, руды, броня, лук и стрельба, ворота, ферма, саженцы, загон,
  зачарование), маска данными в `actions.json`, 322 признака (голод, опыт, шахта, ферма, загон, периметр,
  восполняемость, достижения), награда шкалами по сценариям (изменение измеренного; `skill_ok` только со следом;
  застой), голод агентов, опыт, 33 достижения 1.7.10 (18 поддержаны), версия в весах.
