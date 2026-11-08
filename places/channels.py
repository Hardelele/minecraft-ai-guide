"""Block -> input channel (places/CONTRACT.md), by the registry name of the block (FML registry in level.dat).

Close to hardelele.aiguide.Semantics: vanilla by name, modded by name patterns; what the rules do not know is
other_solid.
"""
from __future__ import annotations

import re

import numpy as np

from names import CHANNELS

C = {n: i for i, n in enumerate(CHANNELS)}
AIR, UNSEEN, WATER, LAVA, GROUND, STONE, ORE, WOOD, PLANT, BUILT, DOOR, FENCE, CLIMB, LIGHT, STORAGE, OTHER = range(16)

V = {}


def _v(ch: int, names: str):
    for n in names.split():
        V["minecraft:" + n] = ch


_v(AIR, "air rail golden_rail detector_rail activator_rail web redstone_wire standing_sign wall_sign lever stone_pressure_plate "
        "wooden_pressure_plate light_weighted_pressure_plate heavy_weighted_pressure_plate stone_button wooden_button snow_layer "
        "unpowered_repeater powered_repeater unpowered_comparator powered_comparator tripwire_hook tripwire portal end_portal carpet")
_v(WATER, "water flowing_water")
_v(LAVA, "lava flowing_lava fire")
_v(GROUND, "grass dirt sand gravel farmland ice snow clay netherrack soul_sand mycelium end_stone packed_ice")
_v(STONE, "stone bedrock sandstone obsidian monster_egg hardened_clay")
_v(ORE, "gold_ore iron_ore coal_ore lapis_ore diamond_ore redstone_ore lit_redstone_ore emerald_ore quartz_ore")
_v(WOOD, "log log2 leaves leaves2 brown_mushroom_block red_mushroom_block")
_v(PLANT, "sapling tallgrass deadbush yellow_flower red_flower brown_mushroom red_mushroom wheat cactus reeds pumpkin melon_block "
          "pumpkin_stem melon_stem waterlily nether_wart cocoa carrots potatoes double_plant")
_v(BUILT, "cobblestone planks sponge glass lapis_block noteblock bed sticky_piston piston piston_head wool piston_extension "
          "gold_block iron_block double_stone_slab stone_slab brick_block tnt bookshelf mossy_cobblestone oak_stairs diamond_block "
          "stone_stairs jukebox glowstone lit_pumpkin cake stained_glass stonebrick glass_pane brick_stairs stone_brick_stairs "
          "nether_brick nether_brick_stairs end_portal_frame redstone_lamp lit_redstone_lamp double_wooden_slab wooden_slab "
          "sandstone_stairs emerald_block spruce_stairs birch_stairs jungle_stairs beacon flower_pot skull daylight_detector "
          "redstone_block quartz_block quartz_stairs stained_hardened_clay stained_glass_pane acacia_stairs dark_oak_stairs "
          "hay_block coal_block")
_v(DOOR, "wooden_door iron_door trapdoor fence_gate")
_v(FENCE, "fence iron_bars nether_brick_fence cobblestone_wall")
_v(CLIMB, "ladder vine")
_v(LIGHT, "torch redstone_torch unlit_redstone_torch")
_v(STORAGE, "dispenser chest crafting_table furnace lit_furnace enchanting_table brewing_stand cauldron ender_chest anvil "
            "trapped_chest hopper dropper")
_v(OTHER, "mob_spawner dragon_egg command_block")

# modded: first match wins, the order matters
RULES = [
    (AIR, r"(track|residual|laserair|fake|invisible|ghost|blockfire_water)"),
    (STONE, r"(skystone(?!brick|block)|^cube$|blockskystone$)"),   # meteorites, Railcraft abyssal/quarried stone
    (PLANT, r"(foliage|poison|fruit)"),
    (WATER, r"spring"),
    (WATER, r"(fluid|liquid|oil|fuel|still|flowing|_water|water_|blockfluid|molten|steam)"),
    (ORE, r"(^|[:._])ore|ore$|oreblock|blockore|ores|crystal_?ore"),
    (DOOR, r"(door|hatch|gate)"),
    (FENCE, r"(fence|railing|bars|wall$|walls)"),
    (CLIMB, r"(ladder|vine|rope|scaffold|willow|ivy)"),
    (LIGHT, r"(torch|lantern|candle|lamp(?!post)|light(?!ning))"),
    (STORAGE, r"(chest|barrel|drawer|crate|storage|tank|jabba|safe|strongbox|machine|furnace|generator|engine|crafter|table|"
              r"bench|altar|oven|press|grinder|smelter|cell|reactor|turbine|capacitor|battery|controller|terminal|interface|"
              r"drive|pump|quarry|planter|harvester|computer|monitor|peripheral|turtle|kettle|cauldron|distill|still|forge|"
              r"assembler|charger|inscriber|charger|energy|power|conduit_bundle|chunkloader|anchor|spawner)"),
    (WOOD, r"(log|leaves|leaf|wood_?natural|trunk|rubwood|rubberwood|bamboo)"),
    (PLANT, r"(sapling|flower|plant|crop|grass(?!_?block)|bush|mushroom|shroom|reed|lily|seed|herb|berry|coral|kelp|moss|ivy|"
            r"fern|weed|sprout|cactus|bloom|petal|wart|garlic|belladonna|mandrake|artichoke|snowbell|wolfsbane|wispy|tallgrass)"),
    (BUILT, r"(plank|brick|slab|stair|glass|tile|pillar|block|cobble|panel|frame|pipe|cable|duct|wire|conduit|transport|"
            r"planks|wool|cloth|carpet|bed|decor|chisel|marble_?brick|concrete|insulat|casing|cover|facade|path|road|metal|"
            r"steel|iron|copper|tin|bronze|lead|silver|gold)"),
    (STONE, r"(stone|granite|marble|basalt|slate|andesite|diorite|limestone|rock|bedrock|flint|cliff)"),
    (GROUND, r"(dirt|sand|gravel|soil|mud|clay|ash|silt|peat|snow|ice|turf|loam|hardened)"),
]
RULES = [(c, re.compile(p)) for c, p in RULES]


def channel_of(name: str) -> int:
    if name in V:
        return V[name]
    n = name.lower()
    if n.startswith("minecraft:"):
        return OTHER
    tail = n.split(":", 1)[-1]
    for c, rx in RULES:
        if rx.search(tail):
            return c
    return OTHER


def lut(reg: dict[int, str]) -> np.ndarray:
    """id -> channel, 4096 entries; ids absent from the registry are other_solid, 0 is air."""
    t = np.full(4096, OTHER, np.uint8)
    for i, n in reg.items():
        if 0 <= i < 4096:
            t[i] = channel_of(n)
    t[0] = AIR
    return t


def onehot(ch: np.ndarray) -> np.ndarray:
    """[X, Y, Z] channel indices -> [C_IN, X, Y, Z] float32 one-hot."""
    x = np.zeros((len(CHANNELS),) + ch.shape, np.float32)
    np.put_along_axis(x, ch[None].astype(np.int64), 1.0, 0)
    return x


# what a player can be in (feet/head), by channel
PASSABLE = np.zeros(16, bool)
PASSABLE[[AIR, WATER, PLANT, DOOR, CLIMB, LIGHT]] = True
# what light/sight goes through
SEE_THROUGH = np.zeros(16, bool)
SEE_THROUGH[[AIR, WATER, PLANT, CLIMB, LIGHT, FENCE]] = True
# solid enough to stand on / to be a wall
SOLID = ~PASSABLE
SOLID[UNSEEN] = False
