package hardelele.aiguide.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The open world's spaces (docs/rl/OPEN-WORLD.md), with no game in them: a player's actions in five heads (the legs,
 * the jump, the head's turn across and up, the hands), the step's ticks, the voxel cube round the feet with its block
 * classes, the bag's items, the seen things' kinds, the vector's names, the recipes the hands may craft; and the
 * labeler that turns what a body did over a step (a macro's work, docs section 6) into such an action. rl/open.json is
 * written from here (main); tools/rl/open_spec.py --check sees that it still is.
 */
final class OpenSpec {
    private OpenSpec() { }

    static final int VERSION = 1;
    /** ticks a step (5 a second) */
    static final int STEP = 4;
    /** the cube round the feet: x across (left to right), y from Y0 up, z along the look (back to front) */
    static final int VX = 11, VY = 8, VZ = 11, Y0 = -3, R = 5;
    /** the cells the eyes write into the memory: within this of the eyes, within this angle of the look */
    static final double SEE = 8, SEE_COS = Math.cos(Math.toRadians(70));
    static final int HOTBAR = 9, ENTS = 8;

    // ---- the actions -------------------------------------------------------------------------------------------

    static final String[] HEADS = { "move", "jump", "yaw", "pitch", "act" };
    /** the legs: forward, strafe (left +) */
    static final int[][] MOVES = { { 0, 0 }, { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 }, { 1, 1 }, { 1, -1 }, { -1, 1 }, { -1, -1 } };
    static final String[] MOVE_NAMES = { "none", "forward", "back", "left", "right", "forward_left", "forward_right", "back_left", "back_right" };
    static final String[] JUMPS = { "none", "jump", "sneak" };
    /** the head's turn a step, degrees (yaw +: to the right, as the mouse) */
    static final int[] YAWS = { 0, -90, -30, -10, 10, 30, 90 };
    /** pitch +: down, as the game's */
    static final int[] PITCHES = { 0, -30, -10, 10, 30 };
    static final int ACT_NONE = 0, ACT_ATTACK = 1, ACT_USE = 2, ACT_SLOT = 3, ACT_DROP = 12, ACT_LOAD = 13, ACT_TAKE = 14, ACT_CRAFT = 15;
    /** what the hands may craft (one craft of any of the item's recipes the bag can do; a 3x3 one at a table seen in reach) */
    static final String[] RECIPES = { "planks", "stick", "crafting_table", "wooden_pickaxe", "wooden_axe", "wooden_shovel", "wooden_sword", "wooden_hoe",
            "stone_pickaxe", "stone_axe", "stone_shovel", "stone_sword", "stone_hoe", "furnace", "chest", "torch", "iron_pickaxe", "iron_axe", "iron_shovel",
            "iron_sword", "iron_hoe", "iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots", "bucket", "flint_and_steel", "bow", "arrow", "bread",
            "wooden_door", "fence", "fence_gate", "ladder", "diamond_pickaxe", "diamond_sword" };
    static final int ACTS = ACT_CRAFT + RECIPES.length;
    static final int[] SIZES = { MOVES.length, JUMPS.length, YAWS.length, PITCHES.length, ACTS };

    static String actName(int k) {
        if (k == ACT_NONE) return "none";
        if (k == ACT_ATTACK) return "attack";
        if (k == ACT_USE) return "use";
        if (k >= ACT_SLOT && k < ACT_SLOT + HOTBAR) return "slot" + (k - ACT_SLOT + 1);
        if (k == ACT_DROP) return "drop";
        if (k == ACT_LOAD) return "furnace_load";
        if (k == ACT_TAKE) return "furnace_take";
        return "craft:" + RECIPES[k - ACT_CRAFT];
    }

    static int recipe(String item) { return Arrays.asList(RECIPES).indexOf(item); }

    // ---- the blocks -----------------------------------------------------------------------------------------------

    static final String[] BLOCKS = { "unknown", "air", "water", "lava", "stone", "cobblestone", "dirt", "sand", "log", "leaves", "planks", "crafting_table",
            "furnace", "chest", "coal_ore", "iron_ore", "gold_ore", "diamond_ore", "redstone_ore", "plant", "torch", "door", "hard", "solid", "soft" };
    static final int B_UNKNOWN = 0, B_AIR = 1, B_SOLID = 23, B_SOFT = 24;
    static final Map<String, Integer> BLOCK_OF = new HashMap<String, Integer>();
    static {
        String[][] by = {
                { "water", "water", "flowing_water" }, { "lava", "lava", "flowing_lava" }, { "stone", "stone" }, { "cobblestone", "cobblestone", "mossy_cobblestone", "stone_stairs", "stone_slab" },
                { "dirt", "dirt", "grass", "farmland", "mycelium", "clay" }, { "sand", "sand", "gravel", "sandstone" }, { "log", "log", "log2" },
                { "leaves", "leaves", "leaves2" }, { "planks", "planks", "oak_stairs", "spruce_stairs", "birch_stairs", "jungle_stairs", "wooden_slab" },
                { "crafting_table", "crafting_table" }, { "furnace", "furnace", "lit_furnace" }, { "chest", "chest", "trapped_chest" },
                { "coal_ore", "coal_ore" }, { "iron_ore", "iron_ore" }, { "gold_ore", "gold_ore" }, { "diamond_ore", "diamond_ore" },
                { "redstone_ore", "redstone_ore", "lit_redstone_ore" },
                { "plant", "tallgrass", "yellow_flower", "red_flower", "sapling", "wheat", "reeds", "deadbush", "vine", "double_plant", "brown_mushroom", "red_mushroom", "carrots", "potatoes", "waterlily", "melon_stem", "pumpkin_stem" },
                { "torch", "torch" }, { "door", "wooden_door", "iron_door", "fence", "fence_gate", "trapdoor", "ladder", "nether_brick_fence" },
                { "hard", "bedrock", "obsidian" } };
        for (String[] b : by) for (int i = 1; i < b.length; i++) BLOCK_OF.put(b[i], Arrays.asList(BLOCKS).indexOf(b[0]));
    }

    /** a block's class by its registry name ("minecraft:" left off): the named ones, else solid or soft by its box */
    static int block(String name, boolean air, boolean solid) {
        if (air) return B_AIR;
        Integer k = BLOCK_OF.get(name);
        if (k != null) return k;
        return solid ? B_SOLID : B_SOFT;
    }

    // ---- the bag ----------------------------------------------------------------------------------------------------

    static final String[] ITEMS = { "empty", "log", "planks", "stick", "crafting_table", "cobblestone", "dirt", "sand", "gravel", "coal", "iron_ore", "gold_ore",
            "iron_ingot", "gold_ingot", "diamond", "redstone", "flint", "furnace", "chest", "torch", "wooden_pickaxe", "stone_pickaxe", "iron_pickaxe",
            "diamond_pickaxe", "wooden_sword", "stone_sword", "iron_sword", "diamond_sword", "wooden_axe", "stone_axe", "iron_axe", "wooden_shovel",
            "stone_shovel", "iron_shovel", "wooden_hoe", "stone_hoe", "iron_hoe", "bucket", "water_bucket", "lava_bucket", "sapling", "wheat_seeds", "wheat",
            "bread", "apple", "raw_meat", "cooked_meat", "rotten_flesh", "string", "bone", "arrow", "bow", "feather", "leather", "armor", "wooden_door",
            "fence", "fence_gate", "ladder", "flint_and_steel", "other" };
    static final Map<String, Integer> ITEM_OF = new HashMap<String, Integer>();
    static {
        for (int i = 1; i < ITEMS.length - 1; i++) ITEM_OF.put(ITEMS[i], i);
        ITEM_OF.put("log2", ITEM_OF.get("log"));
        ITEM_OF.put("grass", ITEM_OF.get("dirt"));
        for (String m : new String[] { "beef", "porkchop", "chicken", "fish" }) ITEM_OF.put(m, ITEM_OF.get("raw_meat"));
        for (String m : new String[] { "cooked_beef", "cooked_porkchop", "cooked_chicken", "cooked_fished" }) ITEM_OF.put(m, ITEM_OF.get("cooked_meat"));
        for (String m : new String[] { "leather", "chainmail", "iron", "golden", "diamond" })
            for (String p : new String[] { "_helmet", "_chestplate", "_leggings", "_boots" }) ITEM_OF.put(m + p, ITEM_OF.get("armor"));
    }
    static final int I_OTHER = ITEMS.length - 1;

    /** an item's class by its registry name ("minecraft:" left off); 0 for none */
    static int item(String name) {
        if (name == null || name.isEmpty()) return 0;
        Integer k = ITEM_OF.get(name);
        return k != null ? k : I_OTHER;
    }

    // ---- the things seen --------------------------------------------------------------------------------------------

    static final String[] KINDS = { "zombie", "skeleton", "spider", "creeper", "witch", "cow", "sheep", "pig", "chicken", "agent", "item", "other" };
    /** one seen thing: its kind (one-hot), across, along, up (/16), its distance (/16), its health (0..1), hostile */
    static final int ENT_F = KINDS.length + 6;

    static int kind(String entityName) {
        String n = entityName == null ? "" : entityName.toLowerCase();
        for (int i = 0; i < KINDS.length - 1; i++) if (n.contains(KINDS[i])) return i;
        if (n.contains("cavespider")) return 2;
        return KINDS.length - 1;
    }

    // ---- the vector ---------------------------------------------------------------------------------------------------

    static final List<String> VEC = new ArrayList<String>();
    static {
        Collections.addAll(VEC, "health", "food", "saturation", "air", "armor", "xp_level", "on_ground", "in_water", "in_lava", "burning", "under_water", "collided",
                "sneaking", "yaw_sin", "yaw_cos", "pitch", "aim_dist", "aim_entity", "dig_progress", "day_sin", "day_cos", "night", "phase_none", "phase_prep",
                "phase_wave", "phase_break", "night_n", "base_x", "base_z", "base_y", "base_dist", "held", "eating");
        for (int i = 0; i < BLOCKS.length; i++) VEC.add("aim_" + BLOCKS[i]);
        for (int i = 1; i < ITEMS.length; i++) VEC.add("bag_" + ITEMS[i]);
        for (int i = 0; i < HOTBAR; i++) VEC.add("hot_n" + (i + 1));
        for (int i = 0; i < HOTBAR; i++) VEC.add("hot_sel" + (i + 1));
    }

    // ---- the labeler: what a body did over a step -> an action (the teacher's record, docs section 6) ------------

    /** the index of the value of a grid nearest to v */
    static int nearest(int[] grid, double v) {
        int best = 0;
        for (int i = 1; i < grid.length; i++) if (Math.abs(grid[i] - v) < Math.abs(grid[best] - v)) best = i;
        return best;
    }

    /** the legs' head from the step's way in the look's axes at its start (blocks: forward, left); under 0.05 none */
    static int move(double forward, double left) {
        double d = Math.hypot(forward, left);
        if (d < 0.05) return 0;
        double ang = Math.toDegrees(Math.atan2(left, forward));   // 0 forward, 90 left
        int[] sector = { 1, 5, 3, 7, 2, 8, 4, 6 };   // forward, forward_left, left, back_left, back, back_right, right, forward_right
        int s = (int) Math.floor(((ang + 360 + 22.5) % 360) / 45);
        return sector[s];
    }

    /** a player's walk on the ground, a tick: the push of the keys (0.98 x 0.1) and what of the speed is kept (0.6 x 0.91) */
    static final double PUSH = 0.098, KEEP = 0.546;
    /** blocks a step of the legs takes a body from standing (for the checks: 0.63) */
    static final double STEP_WAY = way(0, 1)[0];

    /** a step of the legs on flat ground from a speed (vx, along the push): { the way, the speed after }; push 0 or 1 */
    static double[] way(double v, double push) {
        double d = 0;
        for (int t = 0; t < STEP; t++) { v += PUSH * push; d += v; v *= KEEP; }
        return new double[] { d, v };
    }

    /** the turns and the legs quantized with the error carried on: the learner's head and feet keep near the teacher's */
    static final class Labeler {
        double yawDebt, pitchDebt, wayX, wayZ, vx, vz;

        /**
         * The legs' head for the step's way in the world (dx, dz) with the look's yaw at its start (degrees, the game's:
         * forward is (-sin, cos)): the move whose step, as a player's walk goes on from the learner's speed (its
         * momentum kept, PUSH and KEEP a tick), comes nearest the way and what was left over before; what it misses
         * is carried on. A slow walk comes out as a step now and then.
         */
        int move(double dx, double dz, double yawDeg) {
            double wx = dx + wayX, wz = dz + wayZ;
            double y = Math.toRadians(yawDeg), fx = -Math.sin(y), fz = Math.cos(y), lx = fz, lz = -fx;   // forward; left (strafe +)
            int best = -1;
            double bd = 1e18, bx = 0, bz = 0, bvx = 0, bvz = 0;
            for (int k = 0; k < MOVES.length; k++) {
                double f = MOVES[k][0], l = MOVES[k][1], n = Math.max(1, Math.hypot(f, l));
                double ux = (f * fx + l * lx) / n, uz = (f * fz + l * lz) / n;
                double px = 0, pz = 0, qx = vx, qz = vz;
                for (int t = 0; t < STEP; t++) { qx += PUSH * ux; qz += PUSH * uz; px += qx; pz += qz; qx *= KEEP; qz *= KEEP; }
                double d = Math.hypot(wx - px, wz - pz);
                if (d < bd - 1e-9) { bd = d; best = k; bx = px; bz = pz; bvx = qx; bvz = qz; }
            }
            wayX = wx - bx;
            wayZ = wz - bz;
            vx = bvx;
            vz = bvz;
            if (Math.hypot(wayX, wayZ) > 2) wayX = wayZ = vx = vz = 0;   // (pushed, fallen, back from death: not the legs' doing)
            return best;
        }

        /** the step's turn (degrees, yaw + right, pitch + down): { yaw head, pitch head } */
        int[] turn(double dYaw, double dPitch) {
            double wy = dYaw + yawDebt, wp = dPitch + pitchDebt;
            int y = nearest(YAWS, wy), p = nearest(PITCHES, wp);
            yawDebt = wy - YAWS[y];
            pitchDebt = wp - PITCHES[p];
            if (Math.abs(yawDebt) > 180) yawDebt = 0;   // (a turn the grid cannot follow: a respawn, a teleport)
            if (Math.abs(pitchDebt) > 90) pitchDebt = 0;
            return new int[] { y, p };
        }

        /**
         * The hands' head by priority (a craft, the furnace, a slot, the right button, the left, a drop): the step's
         * recipe (-1 none), furnace (0 none, ACT_LOAD, ACT_TAKE), used, attacked, slot (-1 none), dropped. A slot
         * before the buttons: the hand must hold the tool before it digs with it (the button's step after it is lost).
         */
        static int act(int recipe, int furnace, boolean used, boolean attacked, int slot, boolean dropped) {
            if (recipe >= 0) return ACT_CRAFT + recipe;
            if (furnace != 0) return furnace;
            if (slot >= 0) return ACT_SLOT + slot;
            if (used) return ACT_USE;
            if (attacked) return ACT_ATTACK;
            if (dropped) return ACT_DROP;
            return ACT_NONE;
        }
    }

    /** a pitch kept to the game's limits */
    static float clampPitch(float p) { return Math.max(-90F, Math.min(90F, p)); }

    // ---- the file -------------------------------------------------------------------------------------------------------

    static JsonArray strings(String[] a) {
        JsonArray j = new JsonArray();
        for (String s : a) j.add(new JsonPrimitive(s));
        return j;
    }

    static JsonArray ints(int[] a) {
        JsonArray j = new JsonArray();
        for (int s : a) j.add(new JsonPrimitive(s));
        return j;
    }

    static JsonObject json() {
        JsonObject o = new JsonObject();
        o.addProperty("version", VERSION);
        o.addProperty("step_ticks", STEP);
        JsonObject cube = new JsonObject();
        cube.addProperty("x", VX);
        cube.addProperty("y", VY);
        cube.addProperty("z", VZ);
        cube.addProperty("y0", Y0);
        cube.addProperty("order", "x fastest, then z, then y: index = (y * VZ + z) * VX + x");
        cube.addProperty("see", SEE);
        o.add("cube", cube);
        JsonObject h = new JsonObject();
        h.add("names", strings(HEADS));
        h.add("sizes", ints(SIZES));
        h.add("move", strings(MOVE_NAMES));
        h.add("jump", strings(JUMPS));
        h.add("yaw", ints(YAWS));
        h.add("pitch", ints(PITCHES));
        String[] acts = new String[ACTS];
        for (int i = 0; i < ACTS; i++) acts[i] = actName(i);
        h.add("act", strings(acts));
        o.add("heads", h);
        o.add("recipes", strings(RECIPES));
        o.add("blocks", strings(BLOCKS));
        o.add("items", strings(ITEMS));
        o.add("kinds", strings(KINDS));
        o.addProperty("hotbar", HOTBAR);
        o.addProperty("ents", ENTS);
        o.addProperty("ent_features", ENT_F);
        o.add("vec", strings(VEC.toArray(new String[0])));
        o.addProperty("hash", hash(o.toString()));
        return o;
    }

    static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 6; i++) b.append(String.format("%02x", d[i]));
            return b.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static void main(String[] args) throws Exception {
        File f = new File(args.length > 0 ? args[0] : "rl/open.json");
        Files.write(f.toPath(), (new GsonBuilder().setPrettyPrinting().create().toJson(json()) + "\n").getBytes(StandardCharsets.UTF_8));
        System.out.println("open world spec " + VERSION + ": heads " + Arrays.toString(SIZES) + ", " + VEC.size() + " vector features, " + BLOCKS.length + " block classes -> " + f);
    }
}
