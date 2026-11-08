package hardelele.aiguide.client;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A block's input channel for the places net (places/CONTRACT.md), by its registry name: the same tables and name
 * rules as the model's training (places/channels.py of the model's branch), so the net sees in the game what it
 * learned on. A modded block the name rules do not know is other_solid there; here what the game tells of it (a
 * liquid, no collision, an ore of the ore dictionary) may still place it. No game classes: the caller says those.
 */
final class PlacesClasses {
    private PlacesClasses() { }

    static final int AIR = 0, UNSEEN = 1, WATER = 2, LAVA = 3, GROUND = 4, STONE = 5, ORE = 6, WOOD = 7, PLANT = 8, BUILT = 9, DOOR = 10,
            FENCE = 11, CLIMB = 12, LIGHT = 13, STORAGE = 14, OTHER = 15;

    /** what sight goes through (as the training's visibility: the first cell not of these is the last one seen) */
    static final boolean[] SEE_THROUGH = new boolean[16];
    static {
        for (int c : new int[] { AIR, WATER, PLANT, CLIMB, LIGHT, FENCE }) SEE_THROUGH[c] = true;
    }

    static final Map<String, Integer> VANILLA = new HashMap<String, Integer>();

    private static void v(int ch, String names) {
        for (String n : names.split(" ")) VANILLA.put("minecraft:" + n, ch);
    }

    static {
        v(AIR, "air rail golden_rail detector_rail activator_rail web redstone_wire standing_sign wall_sign lever stone_pressure_plate "
                + "wooden_pressure_plate light_weighted_pressure_plate heavy_weighted_pressure_plate stone_button wooden_button snow_layer "
                + "unpowered_repeater powered_repeater unpowered_comparator powered_comparator tripwire_hook tripwire portal end_portal carpet");
        v(WATER, "water flowing_water");
        v(LAVA, "lava flowing_lava fire");
        v(GROUND, "grass dirt sand gravel farmland ice snow clay netherrack soul_sand mycelium end_stone packed_ice");
        v(STONE, "stone bedrock sandstone obsidian monster_egg hardened_clay");
        v(ORE, "gold_ore iron_ore coal_ore lapis_ore diamond_ore redstone_ore lit_redstone_ore emerald_ore quartz_ore");
        v(WOOD, "log log2 leaves leaves2 brown_mushroom_block red_mushroom_block");
        v(PLANT, "sapling tallgrass deadbush yellow_flower red_flower brown_mushroom red_mushroom wheat cactus reeds pumpkin melon_block "
                + "pumpkin_stem melon_stem waterlily nether_wart cocoa carrots potatoes double_plant");
        v(BUILT, "cobblestone planks sponge glass lapis_block noteblock bed sticky_piston piston piston_head wool piston_extension "
                + "gold_block iron_block double_stone_slab stone_slab brick_block tnt bookshelf mossy_cobblestone oak_stairs diamond_block "
                + "stone_stairs jukebox glowstone lit_pumpkin cake stained_glass stonebrick glass_pane brick_stairs stone_brick_stairs "
                + "nether_brick nether_brick_stairs end_portal_frame redstone_lamp lit_redstone_lamp double_wooden_slab wooden_slab "
                + "sandstone_stairs emerald_block spruce_stairs birch_stairs jungle_stairs beacon flower_pot skull daylight_detector "
                + "redstone_block quartz_block quartz_stairs stained_hardened_clay stained_glass_pane acacia_stairs dark_oak_stairs "
                + "hay_block coal_block");
        v(DOOR, "wooden_door iron_door trapdoor fence_gate");
        v(FENCE, "fence iron_bars nether_brick_fence cobblestone_wall");
        v(CLIMB, "ladder vine");
        v(LIGHT, "torch redstone_torch unlit_redstone_torch");
        v(STORAGE, "dispenser chest crafting_table furnace lit_furnace enchanting_table brewing_stand cauldron ender_chest anvil "
                + "trapped_chest hopper dropper");
        v(OTHER, "mob_spawner dragon_egg command_block");
    }

    /** modded names: the first rule that matches wins, the order matters (as channels.py's RULES) */
    static final Object[][] RULES = {
        { AIR, "(track|residual|laserair|fake|invisible|ghost|blockfire_water)" },
        { STONE, "(skystone(?!brick|block)|^cube$|blockskystone$)" },
        { PLANT, "(foliage|poison|fruit)" },
        { WATER, "spring" },
        { WATER, "(fluid|liquid|oil|fuel|still|flowing|_water|water_|blockfluid|molten|steam)" },
        { ORE, "(^|[:._])ore|ore$|oreblock|blockore|ores|crystal_?ore" },
        { DOOR, "(door|hatch|gate)" },
        { FENCE, "(fence|railing|bars|wall$|walls)" },
        { CLIMB, "(ladder|vine|rope|scaffold|willow|ivy)" },
        { LIGHT, "(torch|lantern|candle|lamp(?!post)|light(?!ning))" },
        { STORAGE, "(chest|barrel|drawer|crate|storage|tank|jabba|safe|strongbox|machine|furnace|generator|engine|crafter|table|"
                + "bench|altar|oven|press|grinder|smelter|cell|reactor|turbine|capacitor|battery|controller|terminal|interface|"
                + "drive|pump|quarry|planter|harvester|computer|monitor|peripheral|turtle|kettle|cauldron|distill|still|forge|"
                + "assembler|charger|inscriber|charger|energy|power|conduit_bundle|chunkloader|anchor|spawner)" },
        { WOOD, "(log|leaves|leaf|wood_?natural|trunk|rubwood|rubberwood|bamboo)" },
        { PLANT, "(sapling|flower|plant|crop|grass(?!_?block)|bush|mushroom|shroom|reed|lily|seed|herb|berry|coral|kelp|moss|ivy|"
                + "fern|weed|sprout|cactus|bloom|petal|wart|garlic|belladonna|mandrake|artichoke|snowbell|wolfsbane|wispy|tallgrass)" },
        { BUILT, "(plank|brick|slab|stair|glass|tile|pillar|block|cobble|panel|frame|pipe|cable|duct|wire|conduit|transport|"
                + "planks|wool|cloth|carpet|bed|decor|chisel|marble_?brick|concrete|insulat|casing|cover|facade|path|road|metal|"
                + "steel|iron|copper|tin|bronze|lead|silver|gold)" },
        { STONE, "(stone|granite|marble|basalt|slate|andesite|diorite|limestone|rock|bedrock|flint|cliff)" },
        { GROUND, "(dirt|sand|gravel|soil|mud|clay|ash|silt|peat|snow|ice|turf|loam|hardened)" },
    };
    static final Pattern[] PATTERNS = new Pattern[RULES.length];
    static {
        for (int i = 0; i < RULES.length; i++) PATTERNS[i] = Pattern.compile((String) RULES[i][1]);
    }

    /** the channel by the registry name alone (exactly as the training's channel_of) */
    static int byName(String name) {
        if (name == null) return OTHER;
        Integer v = VANILLA.get(name);
        if (v != null) return v;
        String n = name.toLowerCase();
        if (n.startsWith("minecraft:")) return OTHER;
        int colon = n.indexOf(':');
        String tail = colon < 0 ? n : n.substring(colon + 1);
        for (int i = 0; i < PATTERNS.length; i++) if (PATTERNS[i].matcher(tail).find()) return (Integer) RULES[i][0];
        return OTHER;
    }

    /**
     * The channel of a block: by its name, and for a modded one the name rules do not know, by what the game says of
     * it: a liquid is water (lava when hot), a block nothing collides with is air, an ore of the ore dictionary is ore.
     */
    static int channel(String name, boolean liquid, boolean hot, boolean blocksMovement, boolean ore) {
        int c = byName(name);
        if (c != OTHER || name == null || name.startsWith("minecraft:")) return c;
        if (liquid) return hot ? LAVA : WATER;
        if (ore) return ORE;
        if (!blocksMovement) return AIR;
        return OTHER;
    }

    static String name(int channel) { return PlacesNet.CHANNELS[channel]; }
}
