package hardelele.aiguide.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The learning environment's spaces of contract 3 (docs/rl/CONTRACT-v2.md: the development v2 and its causes), with no game in them: the discrete
 * actions (a mind's skill with its arguments fixed) each with the rule of its mask (a condition over the raw facts,
 * written into actions.json so that Python reads the very rule instead of a port of it), the observation's features
 * (a name, a kind of scaling, a scale), the reward's weights and scales, the scenarios with the scales each turns on.
 * rl/actions.json, rl/obs.json, rl/reward.json and rl/achievements.json are written from here (main); RlTest and
 * gen_spec.py --check see that they still are. RlEnv fills the raw facts in the game. Contract 1 is RlSpec1, frozen.
 */
final class RlSpec {
    private RlSpec() { }

    static final int CONTRACT = 3, ACTIONS_VERSION = 3, OBS_VERSION = 3, REWARD_VERSION = 3, ACHIEVEMENTS_VERSION = 3;
    static final int MAX_AGENTS = 4;
    /** the first actions are contract 1's, in its order (a v1 net's action k is this k) */
    static final int V1_ACTIONS = 38;

    // ---- the mask's rules ---------------------------------------------------------------------------------------

    /**
     * A condition over the raw facts: all / any of others, or a comparison of a fact with a number or another fact
     * (a fact missing is 0). In actions.json: {"all":[...]}, {"any":[...]}, ["fact", "op", 1.0 or "other_fact"].
     */
    static final class Cond {
        final String kind, fact, op, ref;
        final double num;
        final List<Cond> kids;

        Cond(String kind, List<Cond> kids, String fact, String op, String ref, double num) {
            this.kind = kind; this.kids = kids; this.fact = fact; this.op = op; this.ref = ref; this.num = num;
        }

        boolean eval(Map<String, Double> r) {
            if (kind.equals("all")) { for (Cond c : kids) if (!c.eval(r)) return false; return true; }
            if (kind.equals("any")) { for (Cond c : kids) if (c.eval(r)) return true; return false; }
            double a = g(r, fact), b = ref != null ? g(r, ref) : num;
            switch (op) {
                case ">": return a > b;
                case ">=": return a >= b;
                case "<": return a < b;
                case "<=": return a <= b;
                case "==": return a == b;
                case "!=": return a != b;
                default: throw new IllegalStateException("no op " + op);
            }
        }

        JsonElement json() {
            if (kind.equals("cmp")) {
                JsonArray a = new JsonArray();
                a.add(new JsonPrimitive(fact));
                a.add(new JsonPrimitive(op));
                a.add(ref != null ? new JsonPrimitive(ref) : new JsonPrimitive(num));
                return a;
            }
            JsonObject o = new JsonObject();
            JsonArray a = new JsonArray();
            for (Cond c : kids) a.add(c.json());
            o.add(kind, a);
            return o;
        }
    }

    static Cond all(Cond... c) { return new Cond("all", Arrays.asList(c), null, null, null, 0); }
    static Cond any(Cond... c) { return new Cond("any", Arrays.asList(c), null, null, null, 0); }
    static Cond is(String fact, String op, double v) { return new Cond("cmp", null, fact, op, null, v); }
    static Cond vs(String fact, String op, String other) { return new Cond("cmp", null, fact, op, other, 0); }
    static Cond on(String fact) { return is(fact, ">", 0.5); }
    static Cond off(String fact) { return is(fact, "<", 0.5); }
    static Cond has(String fact) { return is(fact, ">", 0); }
    static Cond none(String fact) { return is(fact, "<=", 0); }

    static final Cond ALWAYS = all();
    static final Cond TABLE = any(all(on("table_known"), is("table_dist", "<=", 24)), has("bag_crafting_table"));
    static final Cond FURNACE = any(all(on("furnace_known"), is("furnace_dist", "<=", 24)), has("bag_furnace"));
    static final Cond BASE = on("base_known");
    static final Cond SURFACE = off("underground");

    // ---- the actions --------------------------------------------------------------------------------------------

    static final class Action {
        final String id, tool;
        final Map<String, String> args = new LinkedHashMap<String, String>();
        final Cond mask;
        Action(String id, String tool, Cond mask, String... kv) {
            this.id = id;
            this.tool = tool;
            this.mask = mask;
            for (int i = 0; i + 1 < kv.length; i += 2) args.put(kv[i], kv[i + 1]);
        }
    }

    /** the crafts: the item, how many wanted a call (contract 1's twelve first) */
    static final String[][] CRAFTS = { { "planks", "4" }, { "stick", "4" }, { "crafting_table", "1" }, { "wooden_pickaxe", "1" }, { "stone_pickaxe", "1" },
            { "iron_pickaxe", "1" }, { "wooden_sword", "1" }, { "stone_sword", "1" }, { "iron_sword", "1" }, { "furnace", "1" }, { "torch", "4" }, { "chest", "1" } };
    static final String[][] CRAFTS2 = { { "diamond_pickaxe", "1" }, { "diamond_sword", "1" }, { "bow", "1" }, { "arrow", "4" }, { "bucket", "1" }, { "flint_and_steel", "1" }, { "wooden_hoe", "1" }, { "stone_hoe", "1" },
            { "bread", "1" }, { "wooden_door", "1" }, { "fence", "2" }, { "fence_gate", "1" }, { "paper", "3" }, { "book", "1" }, { "bookshelf", "1" },
            { "enchanting_table", "1" } };
    /** the armour's materials (craft_armor: the piece of it that adds most to what the agent wears) */
    static final String[] ARMOR = { "leather", "golden", "iron", "diamond" };
    /** the ores a pickaxe of a tier gets: { block, the tier needed (harvest level + 1), count a call } */
    /** the primitives' target slots: in front (the feet's level), its own cell, in front one down, the wall's nearest hole, the nearest moat cell, at the nearest enemy */
    static final String[] TARGETS = { "front", "below", "front_down", "hole", "moat", "mob" };
    /** the hand slots a place takes from: { slot, the fact of its count } */
    static final String[][] PLACE_SLOTS = { { "block", "slot_block" }, { "water", "bag_water_bucket" }, { "lava", "bag_lava_bucket" }, { "torch", "bag_torch" } };
    static final String[][] ORES2 ={ { "gold_ore", "3", "3" }, { "redstone_ore", "3", "4" }, { "diamond_ore", "3", "2" }, { "obsidian", "4", "4" } };

    static final List<Action> ACTIONS = Collections.unmodifiableList(actions());

    static List<Action> actions() {
        List<Action> l = new ArrayList<Action>();
        // contract 1's, in its order (the rules of some are stricter now: on the surface only, hunger)
        l.add(new Action("noop", "noop", ALWAYS));
        l.add(new Action("look_around", "look_around", ALWAYS));
        l.add(new Action("go_to:frontier", "go_to", all(has("frontier"), SURFACE), "target", "frontier"));
        l.add(new Action("chop_tree:near", "chop_tree", all(has("trees_free"), SURFACE), "at", "near"));
        l.add(new Action("mine:stone", "mine", is("pick_tier", ">=", 1), "block", "stone", "count", "8"));
        l.add(new Action("mine:coal_ore", "mine", all(is("pick_tier", ">=", 1), has("coal_minable")), "block", "coal_ore", "count", "3"));
        l.add(new Action("mine:iron_ore", "mine", all(is("pick_tier", ">=", 2), has("iron_minable")), "block", "iron_ore", "count", "3"));
        for (String[] c : CRAFTS) l.add(new Action("craft:" + c[0], "craft", craftRule(c[0]), "item", c[0], "count", c[1]));
        l.add(new Action("smelt:iron_ingot", "smelt", all(has("bag_iron_ore"), has("fuel"), FURNACE), "item", "iron_ingot", "count", "3"));
        l.add(new Action("smelt:food", "smelt", all(has("bag_food_raw"), has("fuel"), FURNACE), "item", "food", "count", "4"));
        l.add(new Action("place:crafting_table", "place", all(has("bag_crafting_table"), any(off("table_known"), is("table_dist", ">", 6))), "item", "crafting_table", "at", "near"));
        l.add(new Action("place:furnace", "place", all(has("bag_furnace"), any(off("furnace_known"), is("furnace_dist", ">", 6))), "item", "furnace", "at", "near"));
        l.add(new Action("place:chest", "place", all(has("bag_chest"), off("chest_known")), "item", "chest", "at", "near"));
        l.add(new Action("build_wall:ring", "build_wall", all(BASE, SURFACE, has("wall_blocks"), any(off("ring_planned"), is("wall_frac", "<", 1))),
                "shape", "around base", "height", "2", "material", "any"));
        l.add(new Action("repair_wall:nearest", "repair_wall", all(SURFACE, on("ring_planned"), has("holes"), has("wall_blocks"))));
        l.add(new Action("dig_moat:ring", "dig_moat", all(SURFACE, on("ring_planned"), is("moat_frac", "<", 1)), "depth", "1"));
        l.add(new Action("light_area:base", "light_area", all(has("bag_torch"), has("light_spots")), "radius", "12"));
        l.add(new Action("equip:best", "equip", has("can_equip"), "what", "best"));
        l.add(new Action("take_post:auto", "take_post", all(on("ring_planned"), any(all(off("has_post"), has("posts_free")), all(on("has_post"), is("post_dist", ">", 2)))),
                "where", "auto"));
        l.add(new Action("hold_line:post", "hold_line", all(on("waves_active"), vs("health", ">=", "low_health")), "radius", "4", "seconds", "60"));
        l.add(new Action("retreat:home", "retreat", all(BASE, is("base_dist", ">", 3)), "where", "home"));
        l.add(new Action("eat:best", "eat", all(has("food_items"), any(vs("health", "<", "max_health"), all(on("hunger_on"), is("food_level", "<", 20))))));
        l.add(new Action("hunt:near", "hunt", has("animals_seen"), "at", "near"));
        l.add(new Action("put:chest_all", "put", all(on("chest_known"), has("bag_stackables")), "chest", "crew", "item", "all", "count", "1728"));
        l.add(new Action("take:chest_food", "take", all(on("chest_known"), has("chest_food")), "chest", "crew", "item", "food", "count", "8"));
        l.add(new Action("go_to:base", "go_to", all(BASE, is("base_dist", ">", 4)), "target", "base"));
        l.add(new Action("wait:5", "wait", ALWAYS, "seconds", "5"));
        // contract 2's: the mine
        l.add(new Action("descend:mine", "descend", all(is("pick_tier", ">=", 1), is("depth_level", "<", 2), BASE)));
        l.add(new Action("ascend:surface", "ascend", all(on("underground"), on("mine_known"))));
        l.add(new Action("tunnel:branch", "tunnel", all(on("underground"), on("at_horizon"), is("pick_tier", ">=", 1)), "length", "12"));
        for (String[] o : ORES2)
            l.add(new Action("mine:" + o[0], "mine", all(is("pick_tier", ">=", Integer.parseInt(o[1])), has(o[0].replace("_ore", "") + "_minable")), "block", o[0], "count", o[2]));
        l.add(new Action("mine:gravel", "mine", has("gravel_minable"), "block", "gravel", "count", "4"));
        l.add(new Action("smelt:gold_ingot", "smelt", all(has("bag_gold_ore"), has("fuel"), FURNACE), "item", "gold_ingot", "count", "3"));
        // the gear
        for (String[] c : CRAFTS2) {
            if (c[0].equals("wooden_hoe")) break;
            l.add(new Action("craft:" + c[0], "craft", craftRule(c[0]), "item", c[0], "count", c[1]));
        }
        for (String m : ARMOR) l.add(new Action("craft_armor:" + m, "craft_armor", all(has("armor_craftable_" + m), TABLE), "material", m));
        l.add(new Action("shoot:post", "shoot", all(has("bag_bow"), has("bag_arrow"), has("enemies_visible")), "seconds", "30"));
        // the gate
        l.add(new Action("build_gate:door", "build_gate", all(SURFACE, on("ring_planned"), has("bag_wooden_door"), off("door_placed"))));
        l.add(new Action("close:gate", "close_gate", all(on("door_placed"), on("gate_open"))));
        // the farm, the trees, the pasture
        boolean farm = false;
        for (String[] c : CRAFTS2) {
            if (c[0].equals("wooden_hoe")) farm = true;
            if (farm && !c[0].equals("paper") && !c[0].equals("book") && !c[0].equals("bookshelf") && !c[0].equals("enchanting_table"))
                l.add(new Action("craft:" + c[0], "craft", craftRule(c[0]), "item", c[0], "count", c[1]));
        }
        l.add(new Action("till:farm", "till", all(BASE, SURFACE, has("hoes"), is("farm_plots", "<", 16), has("till_spots")), "count", "8"));
        l.add(new Action("plant:wheat", "plant", all(has("bag_wheat_seeds"), has("farm_empty")), "what", "wheat", "count", "8"));
        l.add(new Action("harvest:farm", "harvest", has("farm_ripe"), "count", "16", "replant", "yes"));
        l.add(new Action("gather:seeds", "gather", all(SURFACE, has("grass_seen")), "what", "seeds", "count", "8"));
        l.add(new Action("gather:saplings", "gather", all(SURFACE, has("leaves_seen")), "what", "saplings", "count", "16"));
        l.add(new Action("gather:sugar_cane", "gather", all(SURFACE, has("cane_seen")), "what", "sugar_cane", "count", "6"));
        l.add(new Action("plant:sapling", "plant", all(SURFACE, has("bag_sapling"), has("sapling_spots")), "what", "sapling", "count", "4"));
        l.add(new Action("build_pen:pasture", "build_pen", all(BASE, SURFACE, is("bag_fence", ">=", 15), has("bag_fence_gate"), off("pen_built"))));
        l.add(new Action("lure:pen", "lure", all(on("pen_built"), has("lure_food"), has("animals_seen"), is("pen_animals", "<", 8))));
        l.add(new Action("breed:pen", "breed", has("pen_pairs")));
        l.add(new Action("slaughter:pen", "slaughter", is("pen_adults", ">=", 3)));
        // the enchanting
        for (String[] c : CRAFTS2)
            if (c[0].equals("paper") || c[0].equals("book") || c[0].equals("bookshelf") || c[0].equals("enchanting_table"))
                l.add(new Action("craft:" + c[0], "craft", craftRule(c[0]), "item", c[0], "count", c[1]));
        l.add(new Action("place:enchanting_table", "place", all(BASE, SURFACE, has("bag_enchanting_table"), off("ench_table_known")), "item", "enchanting_table", "at", "base"));
        l.add(new Action("place:bookshelf", "place_shelf", all(has("bag_bookshelf"), on("ench_table_known"), is("ench_table_dist", "<=", 16), has("shelf_spots"))));
        l.add(new Action("enchant:best", "enchant", all(on("ench_table_known"), is("ench_table_dist", "<=", 24), is("xp_level", ">=", 1), has("enchantable"))));
        // the primitives: a thing of a hand slot put or used at one of the fixed target slots, a hit, a door
        for (String[] sl : PLACE_SLOTS)
            for (String t : TARGETS)
                l.add(new Action("place:" + sl[0] + "@" + t, "place_at", all(has(sl[1]), on("tgt_" + t), on("tgt_" + t + "_free")), "slot", sl[0], "target", t));
        for (String t : TARGETS)
            l.add(new Action("use:bucket@" + t, "use_at", all(has("bag_bucket"), on("tgt_" + t), any(on("tgt_" + t + "_water_src"), on("tgt_" + t + "_lava_src"))),
                    "slot", "bucket", "target", t));
        for (String t : TARGETS)
            l.add(new Action("use:fire@" + t, "use_at", all(has("bag_flint_and_steel"), on("tgt_" + t), on("tgt_" + t + "_free")), "slot", "fire", "target", t));
        l.add(new Action("attack@mob", "attack", on("tgt_mob"), "target", "mob"));
        l.add(new Action("attack@animal", "attack", has("animals_seen"), "target", "animal"));
        l.add(new Action("open@front", "open", on("tgt_front_door"), "target", "front"));
        l.add(new Action("open@gate", "open", on("door_placed"), "target", "gate"));
        return l;
    }

    /** a craft's rule: the bag can make it, 2x2 or a table near (or in the bag), and no more of it than is any use */
    static Cond craftRule(String it) {
        Cond can = all(has("craftable_" + it), any(has("small_" + it), TABLE));
        Cond lim;
        switch (it) {
            case "planks": lim = is("bag_planks", "<", 64); break;
            case "stick": lim = is("bag_stick", "<", 32); break;
            case "torch": lim = is("bag_torch", "<", 32); break;
            case "arrow": lim = is("bag_arrow", "<", 64); break;
            case "crafting_table": lim = none("bag_crafting_table"); break;
            case "furnace": lim = none("bag_furnace"); break;
            case "chest": lim = all(none("bag_chest"), off("chest_known")); break;
            case "bow": lim = none("bag_bow"); break;
            case "bucket": lim = is("buckets", "<", 2); break;
            case "flint_and_steel": lim = none("bag_flint_and_steel"); break;
            case "wooden_hoe": lim = none("hoes"); break;
            case "stone_hoe": lim = is("hoe_tier", "<", 2); break;
            case "bread": lim = is("bag_bread", "<", 64); break;
            case "wooden_door": lim = all(none("bag_wooden_door"), off("door_placed")); break;
            case "fence": lim = all(is("bag_fence", "<", 32), off("pen_built")); break;
            case "fence_gate": lim = all(none("bag_fence_gate"), off("pen_built")); break;
            case "paper": lim = is("bag_paper", "<", 32); break;
            case "book": lim = is("bag_book", "<", 16); break;
            case "bookshelf": lim = is("shelves_total", "<", 15); break;
            case "enchanting_table": lim = all(none("bag_enchanting_table"), off("ench_table_known")); break;
            default:
                if (it.endsWith("_pickaxe")) lim = is("pick_tier", "<", tier(it));
                else if (it.endsWith("_sword")) lim = is("sword_tier", "<", tier(it));
                else lim = ALWAYS;
        }
        return all(can, lim);
    }

    static int index(String id) {
        for (int i = 0; i < ACTIONS.size(); i++) if (ACTIONS.get(i).id.equals(id)) return i;
        return -1;
    }

    /** the tools of the actions that are not the minds' own (Mind.skills): the environment's skills */
    static final String[] ENV_TOOLS = { "hunt", "descend", "ascend", "tunnel", "craft_armor", "shoot", "build_gate", "close_gate", "till", "plant", "harvest",
            "gather", "build_pen", "lure", "breed", "slaughter", "place_shelf", "enchant", "place_at", "use_at", "attack", "open" };

    /** the action as a mind's call (its tool and arguments), the why saying it was the policy's */
    static Mind.Call call(int k) {
        Action a = ACTIONS.get(k);
        Mind.Call c = new Mind.Call();
        c.tool = a.tool;
        for (Map.Entry<String, String> e : a.args.entrySet()) c.args.addProperty(e.getKey(), e.getValue());
        c.why = "policy: " + a.id;
        c.args.addProperty("why", c.why);
        return c;
    }

    // ---- the spaces of a net: contract 2's or contract 1's (the weights say which) ------------------------------

    /** the spaces a net was learned on: its vector, mask and actions (contract 1's action k is contract 2's k) */
    static final class Space {
        final int contract;
        Space(int contract) { this.contract = contract; }
        float[] vector(Map<String, Double> raw) { return contract == 2 ? RlSpec.vector(raw) : RlSpec1.vector(raw); }
        int[] mask(Map<String, Double> raw) { return contract == 2 ? RlSpec.mask(raw) : RlSpec1.mask(raw); }
        int actions() { return contract == 2 ? ACTIONS.size() : RlSpec1.ACTIONS.size(); }
        String id(int k) { return ACTIONS.get(k).id; }
        int index(String id) { int i = RlSpec.index(id); return i < actions() ? i : -1; }
        boolean has(String scenario) { return contract == 2 ? scenario(scenario) != null : RlSpec1.scenario(scenario) != null; }
    }

    static final Space V2 = new Space(2), V1 = new Space(1);

    /** the spaces of the net's size and version, null when it fits neither (it is refused: no silent mismatch) */
    static Space space(PolicyNet n) {
        if (n == null) return null;
        if (n.version == ACTIONS_VERSION && n.d == FEATURES.size() && n.a == ACTIONS.size()) return V2;
        if (n.version == 1 && n.d == RlSpec1.FEATURES.size() && n.a == RlSpec1.ACTIONS.size()) return V1;
        return null;
    }

    // ---- the observation ----------------------------------------------------------------------------------------

    static final class Feature {
        final String name, kind;
        final double scale;
        Feature(String name, String kind, double scale) { this.name = name; this.kind = kind; this.scale = scale; }
    }

    static final String[] SCENARIOS = { "wood", "pickaxe", "sword", "armor", "iron", "bow", "mine_trip", "diamond", "farm", "pasture", "enchant", "fortify", "food",
            "waves:easy", "waves:normal", "waves:long", "achievements" };
    /** the reward's scales: each the change of a measured value, on in the scenarios that name it */
    static final String[] SCALES = { "safety", "perimeter", "equipment", "mine", "enchant", "sustain", "stock", "achievement", "curiosity" };
    static final String[] BAG = { "log", "planks", "stick", "crafting_table", "cobblestone", "coal", "iron_ore", "iron_ingot", "furnace", "chest", "torch",
            "wooden_pickaxe", "stone_pickaxe", "iron_pickaxe", "wooden_sword", "stone_sword", "iron_sword", "food_raw", "food_cooked", "dirt",
            // contract 2's
            "gold_ore", "gold_ingot", "diamond", "redstone", "obsidian", "gravel", "flint", "string", "feather", "leather", "bow", "arrow",
            "diamond_pickaxe", "diamond_sword", "armor", "wooden_hoe", "stone_hoe", "wheat_seeds", "wheat", "bread", "sapling", "sugar_cane", "paper",
            "book", "bookshelf", "enchanting_table", "wooden_door", "fence", "fence_gate", "bucket", "water_bucket", "lava_bucket", "flint_and_steel" };
    /** the groups of a step's end (its reason code): the last action's one-hot */
    static final String[] OUTCOMES = { "none", "ok", "fail_path", "fail_items", "fail_target", "fail_danger", "fail_other" };

    static final List<Feature> FEATURES = Collections.unmodifiableList(features());

    static List<Feature> features() {
        List<Feature> f = new ArrayList<Feature>();
        for (String s : SCENARIOS) f.add(new Feature("scen_" + s.replace(':', '_'), "bool", 1));
        for (String s : SCALES) f.add(new Feature("scale_" + s, "bool", 1));
        for (int i = 0; i < MAX_AGENTS; i++) f.add(new Feature("agent_" + i, "bool", 1));
        f.add(new Feature("n_agents", "div", MAX_AGENTS));
        // the body
        add(f, "div", 20, "health", "armor");
        add(f, "div", 4, "held_tier", "weapon_tier", "pick_tier");
        f.add(new Feature("at_home", "bool", 1));
        f.add(new Feature("hunger_on", "bool", 1));
        add(f, "div", 20, "food_level", "saturation");
        f.add(new Feature("xp_level", "div", 30)); f.add(new Feature("xp_progress", "div", 1));
        // the time
        add(f, "div", 1, "day_sin", "day_cos");
        f.add(new Feature("night", "bool", 1));
        f.add(new Feature("dusk_in", "div", 600));
        // the bag
        for (String b : BAG) f.add(new Feature("bag_" + b, "log", 64));
        f.add(new Feature("bag_total", "log", 256));
        // what the crew has seen near (its memory only)
        f.add(new Feature("trees_seen", "div", 10)); f.add(new Feature("tree_dist", "div", 48));
        f.add(new Feature("stone_seen", "div", 32)); f.add(new Feature("stone_dist", "div", 32));
        f.add(new Feature("coal_seen", "div", 8)); f.add(new Feature("coal_dist", "div", 48));
        f.add(new Feature("iron_seen", "div", 8)); f.add(new Feature("iron_dist", "div", 48));
        f.add(new Feature("gold_seen", "div", 8)); f.add(new Feature("gold_dist", "div", 48));
        f.add(new Feature("redstone_seen", "div", 8)); f.add(new Feature("redstone_dist", "div", 48));
        f.add(new Feature("diamond_seen", "div", 4)); f.add(new Feature("diamond_dist", "div", 48));
        f.add(new Feature("obsidian_seen", "div", 8)); f.add(new Feature("gravel_seen", "div", 16));
        f.add(new Feature("grass_seen", "div", 16)); f.add(new Feature("leaves_seen", "div", 32)); f.add(new Feature("cane_seen", "div", 8));
        f.add(new Feature("animals_seen", "div", 5)); f.add(new Feature("animal_dist", "div", 24));
        // the stations
        f.add(new Feature("table_known", "bool", 1)); f.add(new Feature("table_dist", "div", 24));
        f.add(new Feature("furnace_known", "bool", 1)); f.add(new Feature("furnace_dist", "div", 24));
        f.add(new Feature("chest_known", "bool", 1)); f.add(new Feature("chest_dist", "div", 48));
        f.add(new Feature("base_known", "bool", 1)); f.add(new Feature("base_dist", "div", 32));
        f.add(new Feature("chest_food", "log", 64));
        f.add(new Feature("ench_table_known", "bool", 1)); f.add(new Feature("ench_table_dist", "div", 24));
        f.add(new Feature("bookshelves", "div", 15)); f.add(new Feature("shelf_spots", "div", 15));
        // the mine
        f.add(new Feature("underground", "bool", 1)); f.add(new Feature("y_rel", "div", 64));
        f.add(new Feature("depth_level", "div", 2)); f.add(new Feature("at_horizon", "bool", 1));
        f.add(new Feature("mine_known", "bool", 1)); f.add(new Feature("mine_level", "div", 2)); f.add(new Feature("mine_dist", "div", 64));
        f.add(new Feature("tunnels", "div", 8)); f.add(new Feature("trip_ores", "log", 64));
        // the farm, the trees, the pasture
        add(f, "div", 16, "farm_plots", "farm_planted", "farm_ripe", "farm_empty", "till_spots");
        f.add(new Feature("hoes", "div", 2));
        add(f, "div", 8, "saplings_planted", "trees_grown", "sapling_spots");
        f.add(new Feature("pen_built", "bool", 1)); add(f, "div", 8, "pen_animals", "pen_adults");
        add(f, "div", 4, "pen_babies", "pen_pairs"); f.add(new Feature("pen_dist", "div", 32));
        // the gate
        add(f, "bool", 1, "door_placed", "gate_open");
        // the waves
        add(f, "bool", 1, "waves_active", "wave_on", "phase_prep", "phase_break");
        f.add(new Feature("wave_num", "div", 10)); f.add(new Feature("next_wave_in", "div", 120));
        f.add(new Feature("enemies", "div", 20)); f.add(new Feature("enemy_dist", "div", 32));
        add(f, "div", 32, "enemy_dx", "enemy_dz");
        f.add(new Feature("enemies_near", "div", 10)); f.add(new Feature("enemies_visible", "div", 10));
        // the perimeter
        f.add(new Feature("ring_planned", "bool", 1)); f.add(new Feature("wall_frac", "div", 1)); f.add(new Feature("holes", "div", 16));
        f.add(new Feature("moat_frac", "div", 1)); f.add(new Feature("torches_placed", "div", 16)); f.add(new Feature("posts_free", "div", 8));
        add(f, "bool", 1, "has_post", "inside_wall");
        add(f, "div", 8, "mobs_inside", "breaches");
        f.add(new Feature("mob_seconds", "log", 600)); f.add(new Feature("clean_waves", "div", 10));
        // the renewable and the stock
        add(f, "log", 64, "food_renew", "food_eaten", "wood_renew", "wood_used");
        f.add(new Feature("sources", "div", 3)); f.add(new Feature("stock_value", "div", 10));
        // the primitives' target slots: there, how far, free to put into, water, lava (a source or flowing); a door in front
        for (String t : TARGETS) {
            f.add(new Feature("tgt_" + t, "bool", 1)); f.add(new Feature("tgt_" + t + "_dist", "div", 16));
            add(f, "bool", 1, "tgt_" + t + "_free", "tgt_" + t + "_water", "tgt_" + t + "_lava");
        }
        f.add(new Feature("tgt_front_door", "bool", 1)); f.add(new Feature("slot_block", "log", 64));
        // the causes: kinds of cause and effect new in this episode (curiosity)
        f.add(new Feature("novel_count", "div", 20));
        // the others
        f.add(new Feature("allies_alive", "div", 3)); add(f, "div", 20, "allies_health_mean", "allies_health_min");
        f.add(new Feature("allies_low", "div", 3)); f.add(new Feature("ally_dist", "div", 32));
        // the achievements: got by this agent, the episode's target (the curriculum's), how many
        for (String a : RlAchievements.supported()) f.add(new Feature("ach_" + a, "bool", 1));
        for (String a : RlAchievements.supported()) f.add(new Feature("ach_target_" + a, "bool", 1));
        f.add(new Feature("ach_count", "div", 18)); f.add(new Feature("ach_available", "div", 18));
        // the last step
        for (String o : OUTCOMES) f.add(new Feature("last_" + o, "bool", 1));
        f.add(new Feature("repeated", "div", 4)); f.add(new Feature("last_seconds", "div", 60));
        for (Action a : actions()) f.add(new Feature("last_action_" + a.id, "bool", 1));
        // the episode
        f.add(new Feature("time_frac", "div", 1)); f.add(new Feature("goal_progress", "div", 1)); f.add(new Feature("stall", "div", 60));
        return f;
    }

    static void add(List<Feature> f, String kind, double scale, String... names) {
        for (String n : names) f.add(new Feature(n, kind, scale));
    }

    static double norm(Feature f, double x) {
        if (f.kind.equals("bool")) return x > 0.5 ? 1 : 0;
        if (f.kind.equals("log")) return Math.log1p(Math.max(x, 0)) / Math.log1p(f.scale);
        return Math.max(-1, Math.min(2, x / f.scale));
    }

    /** the raw facts (by name; a missing one is 0) as the vector the policy reads */
    static float[] vector(Map<String, Double> raw) {
        float[] v = new float[FEATURES.size()];
        for (int i = 0; i < v.length; i++) {
            Feature f = FEATURES.get(i);
            Double x = raw.get(f.name);
            v[i] = (float) norm(f, x == null ? 0 : x);
        }
        return v;
    }

    /** the outcome group of a step's reason code */
    static String outcome(String reason) {
        if (reason == null || reason.isEmpty()) return "none";
        String r = reason;
        if (r.equals("OK")) return "ok";
        if (in(r, "NO_PATH", "NO_SPOT", "OUT_OF_REACH", "NOT_VISIBLE", "BLOCKED", "OCCUPIED", "BUSY", "NO_WAY_BACK")) return "fail_path";
        if (in(r, "NO_TOOL", "NO_ITEMS", "MISSING", "NO_TABLE", "NO_FURNACE", "NO_FUEL", "NO_FOOD", "NO_TORCHES", "NO_WEAPON", "NO_ARMOR", "CHEST_FULL", "NOT_IN_CHEST",
                "NO_SEEDS", "NO_XP", "NO_ARROWS", "NO_BOW", "NO_HOE", "NO_FENCES", "NO_DOOR", "NO_BAIT", "NO_SAPLING", "NO_SHELF")) return "fail_items";
        if (in(r, "NOT_SEEN", "NO_TREE_SEEN", "NO_TREE_THERE", "GONE", "NO_CHEST", "NO_PERIMETER", "NO_BASE", "NO_HOME", "NO_HOLES", "UNKNOWN_TARGET", "POST_TAKEN",
                "ALL_TAKEN", "DONE_ALREADY", "NO_ANIMAL", "NO_FRONTIER", "NOT_RIPE", "NO_FARM", "NO_PEN", "NO_MATE", "NO_MINE", "AT_BOTTOM", "ON_SURFACE",
                "NO_ENEMY", "NO_GATE", "NOT_HUNGRY", "FULL_HEALTH", "NO_ENCHANTER", "NOTHING_TO_ENCHANT", "NOT_AT_HORIZON")) return "fail_target";
        if (in(r, "LOW_HEALTH", "DIED", "INTERRUPTED")) return "fail_danger";
        return "fail_other";
    }

    static boolean in(String r, String... l) {
        for (String s : l) if (s.equals(r)) return true;
        return false;
    }

    // ---- the mask: which actions may be chosen, from the raw facts ----------------------------------------------

    /** the health under which a fight is not begun (Defense.LOW at the start; RlEnv passes the live one as low_health) */
    static final double LOW = 8;

    static double g(Map<String, Double> raw, String k) {
        Double v = raw.get(k);
        return v == null ? 0 : v;
    }

    /** 1/0 for each action; noop always 1 */
    static int[] mask(Map<String, Double> r) {
        int[] m = new int[ACTIONS.size()];
        for (int i = 0; i < m.length; i++) m[i] = ACTIONS.get(i).mask.eval(r) ? 1 : 0;
        m[0] = 1;
        return m;
    }

    /** a tool's tier by its id (wood 1 .. diamond 4: EpochScore.tier) */
    static int tier(String id) { return EpochScore.tier(id); }

    // ---- the reward ---------------------------------------------------------------------------------------------

    /**
     * The weights: a key "a.b" is part b of the component a (info.components has a). The scales' components
     * (SCALES) count only in the scenarios that turn them on (scenarioScales: the factor), the others always.
     */
    static Map<String, Double> rewardDefaults() {
        Map<String, Double> w = new LinkedHashMap<String, Double>();
        // always
        w.put("noop", -1.0);
        w.put("time", -0.001);
        w.put("stall", -0.05);
        w.put("skill_fail", -0.05);
        w.put("skill_ok", 0.02);
        w.put("damage", -0.01);
        w.put("death", -1.0);
        w.put("accident", -1.5);
        w.put("goal", 5.0);
        w.put("human", 2.0);
        // safety: the base's defences as they stand (the change of each)
        w.put("safety.wall", 2.0);
        w.put("safety.moat", 1.0);
        w.put("safety.light", 0.5);
        w.put("safety.gate", 0.5);
        // perimeter: no zombie into the base
        w.put("perimeter.mob_seconds", -0.02);
        w.put("perimeter.breach", -0.2);
        w.put("perimeter.hurt_inside", -0.02);
        w.put("perimeter.clean_wave", 1.0);
        w.put("perimeter.wave_cleared", 0.5);
        w.put("perimeter.kill", 0.2);
        w.put("perimeter.out_died", -0.5);
        w.put("perimeter.holes_at_wave", -0.05);
        // equipment: what it wears and wields (the change)
        w.put("equipment.armor", 1.0);
        w.put("equipment.weapon", 0.5);
        w.put("equipment.pickaxe", 0.5);
        w.put("equipment.ranged", 0.3);
        // mine: depth reached, ore got, the way back
        w.put("mine.depth", 0.5);
        w.put("mine.coal", 0.03);
        w.put("mine.iron", 0.1);
        w.put("mine.gold", 0.15);
        w.put("mine.redstone", 0.03);
        w.put("mine.diamond", 0.5);
        w.put("mine.obsidian", 0.1);
        w.put("mine.return", 1.0);
        // enchant
        w.put("enchant.table", 0.5);
        w.put("enchant.shelf", 0.05);
        w.put("enchant.item", 1.0);
        w.put("enchant.levels", 0.05);
        // sustain: the renewable that is used, the sources kept
        w.put("sustain.food", 0.05);
        w.put("sustain.wood", 0.05);
        w.put("sustain.source", 0.5);
        // stock: useful things up to a cap each
        w.put("stock.value", 0.5);
        // achievement: a new one of the agent's (and more for a deeper one)
        w.put("achievement.new", 0.3);
        w.put("achievement.depth", 0.05);
        // curiosity: a kind of cause and effect new in the episode (on a mob, an animal, a block, or the agent saved)
        w.put("curiosity.novel", 0.2);
        return w;
    }

    /** the component a weight belongs to ("mine.iron" -> "mine") */
    static String component(String weight) {
        int d = weight.indexOf('.');
        return d < 0 ? weight : weight.substring(0, d);
    }

    static boolean isScale(String component) {
        for (String s : SCALES) if (s.equals(component)) return true;
        return false;
    }

    /** the stock's items: { key, cap, value } (a key of the bag, or food: food points) */
    static final String[][] STOCK = { { "log", "32", "1" }, { "cobblestone", "128", "1" }, { "coal", "32", "1" }, { "iron_ingot", "24", "2" }, { "food", "60", "2" },
            { "torch", "32", "0.5" }, { "arrow", "32", "0.5" }, { "wheat_seeds", "16", "0.3" }, { "wheat", "32", "0.5" } };
    /** the windows (game seconds): of stall (no measured progress), of the renewable's balance in the observation */
    static final int STALL_S = 60, WINDOW_S = 120;
    /** the horizons of the mine: the feet's y of each (iron and coal, diamonds, gold and redstone) and how near counts as at it */
    static final int[] HORIZONS = { 40, 12 };
    static final int HORIZON_NEAR = 4;
    /** how many times faster than the game's the crops, saplings and the young animals of the crew grow in the environment */
    static final int GROW = 20;

    // ---- the scenarios ------------------------------------------------------------------------------------------

    static final class Scenario {
        final String name;
        String kit = "", chest = "", goalItem = "", goal = "";
        int seconds = 300, perAgent = 1, xpLevels;
        long time = 1000;
        float health = 20;
        String waves;          // a level of waves (easy, normal), null: none
        boolean defense;       // the minds' goal is one of defence (Defense.isGoal)
        boolean hunger;        // the agents get hungry (Hunger)
        boolean crewGoal;      // the goal counts the crew's, not per agent
        final Map<String, Double> scales = new LinkedHashMap<String, Double>();
        Scenario(String name) { this.name = name; }
        Scenario on(String... s) { for (String x : s) scales.put(x, 1.0); return this; }
        /** only these parts of a scale (its other parts 0) */
        Scenario only(String scale, String... parts) {
            scales.put(scale, 0.0);
            for (String x : parts) scales.put(scale + "." + x, 1.0);
            return this;
        }
        double scale(String s) { Double v = scales.get(s); return v == null ? 0 : v; }
    }

    static final String WAVE_KIT = "stone_sword 1, cobblestone 64, bread 6, torch 8";

    static Scenario scenario(String name) {
        Scenario s = new Scenario(name == null ? "" : name);
        switch (s.name) {
            case "wood": s.goal = "items"; s.goalItem = "log"; s.perAgent = 10; s.seconds = 300; s.on("stock", "achievement"); break;
            case "pickaxe": s.goal = "items"; s.goalItem = "stone_pickaxe"; s.seconds = 600; s.on("achievement").only("equipment", "pickaxe"); break;
            case "sword": s.goal = "items"; s.goalItem = "stone_sword"; s.seconds = 600; s.on("achievement").only("equipment", "weapon"); break;
            case "armor": s.goal = "armor"; s.perAgent = 6; s.seconds = 600; s.kit = "iron_ingot 8, leather 5, crafting_table 1"; s.on("achievement").only("equipment", "armor"); break;
            case "iron": s.goal = "items"; s.goalItem = "iron_pickaxe"; s.seconds = 900; s.kit = "stone_pickaxe 1, planks 16, stick 4"; s.on("mine", "achievement").only("equipment", "pickaxe"); break;
            case "bow": s.goal = "bow"; s.perAgent = 8; s.seconds = 600; s.kit = "string 3, stick 8, flint 4, feather 4, crafting_table 1"; s.on("achievement").only("equipment", "ranged"); break;
            case "mine_trip":
                s.goal = "mine_trip"; s.seconds = 900; s.kit = "stone_pickaxe 1, torch 16, cooked_beef 4, cobblestone 16"; s.hunger = true; s.on("mine", "achievement"); break;
            case "diamond":
                s.goal = "items"; s.goalItem = "diamond"; s.crewGoal = true; s.seconds = 1500; s.kit = "iron_pickaxe 1, torch 32, cooked_beef 6, cobblestone 32"; s.hunger = true;
                s.on("mine", "equipment", "achievement"); break;
            case "farm": s.goal = "farm"; s.perAgent = 2; s.seconds = 1200; s.kit = "wooden_hoe 1, wheat_seeds 8, cooked_beef 2"; s.hunger = true; s.on("sustain", "stock", "achievement"); break;
            case "pasture":
                s.goal = "pasture"; s.crewGoal = true; s.seconds = 1200; s.kit = "fence 15, fence_gate 1, wheat 16, wheat_seeds 8, wooden_sword 1"; s.hunger = true;
                s.on("sustain", "stock", "achievement"); break;
            case "enchant": s.goal = "enchant"; s.seconds = 600; s.kit = "enchanting_table 1, bookshelf 6, iron_sword 1, iron_pickaxe 1"; s.xpLevels = 8; s.on("enchant", "equipment", "achievement"); break;
            case "fortify": s.goal = "fortify"; s.seconds = 600; s.kit = "cobblestone 140, stone_pickaxe 1, wooden_door 1"; s.defense = true; s.on("safety"); break;
            case "food": s.goal = "food"; s.perAgent = 2; s.seconds = 300; s.kit = "wooden_sword 1"; s.health = 10; s.on("stock", "sustain"); break;
            case "waves:easy": case "waves:normal":
                s.goal = "none"; s.seconds = 900; s.kit = WAVE_KIT; s.chest = "bread 16"; s.time = 14000;
                s.waves = s.name.substring(6); s.defense = true; s.on("safety", "perimeter"); break;
            case "waves:long":
                s.goal = "none"; s.seconds = 2400; s.kit = WAVE_KIT + ", wooden_hoe 1, wheat_seeds 8, bow 1, arrow 32, wooden_door 1"; s.chest = "bread 16"; s.time = 14000;
                s.waves = "normal"; s.defense = true; s.hunger = true; s.on("safety", "perimeter", "sustain", "equipment"); break;
            case "achievements": s.goal = "achievements"; s.perAgent = 8; s.seconds = 1800; s.hunger = true; s.on("achievement"); s.scales.put("achievement", 2.0); break;
            default: return null;   // the unknown: not there
        }
        if (!s.scales.containsKey("curiosity")) s.scales.put("curiosity", 1.0);   // everywhere: a new effect found is worth a little
        return s;
    }

    static List<String> available() {
        List<String> l = new ArrayList<String>();
        for (String s : SCENARIOS) if (scenario(s) != null) l.add(s);
        return l;
    }

    /** "stone_pickaxe 1, planks 16": { id, count } pairs */
    static List<String[]> kit(String s) {
        List<String[]> l = new ArrayList<String[]>();
        if (s == null) return l;
        for (String part : s.split(",")) {
            String[] p = part.trim().split("\\s+");
            if (p.length == 0 || p[0].isEmpty()) continue;
            l.add(new String[] { p[0], p.length > 1 ? p[1] : "1" });
        }
        return l;
    }

    // ---- the files ----------------------------------------------------------------------------------------------

    /** the actions' hash: each one's id, tool, arguments and the rule of its mask */
    static String hash() {
        StringBuilder b = new StringBuilder();
        for (Action a : ACTIONS) b.append(a.id).append('|').append(a.tool).append('|').append(a.args).append('|').append(a.mask.json()).append('\n');
        return sha(b.toString());
    }

    static String obsHash() {
        StringBuilder b = new StringBuilder();
        for (Feature f : FEATURES) b.append(f.name).append('|').append(f.kind).append('|').append(f.scale).append('\n');
        return sha(b.toString());
    }

    static String rewardHash() {
        return sha(rewardJson().get("weights").toString() + rewardJson().get("scenarioScales").toString());
    }

    static String sha(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder h = new StringBuilder();
            for (int i = 0; i < 6; i++) h.append(String.format("%02x", d[i]));
            return h.toString();
        } catch (Exception e) {
            return "?";
        }
    }

    static JsonObject actionsJson() {
        JsonObject o = new JsonObject();
        o.addProperty("version", ACTIONS_VERSION);
        o.addProperty("hash", hash());
        o.addProperty("n", ACTIONS.size());
        o.addProperty("v1_prefix", V1_ACTIONS);
        o.addProperty("note", "Generated from RlSpec.java (docs/rl/CONTRACT-v2.md). The index is the action's number; noop (0) is always allowed and ends the episode "
                + "with -1. The first 38 are contract 1's in its order. 'mask': the rule of the action's mask over the raw facts (a fact missing is 0): "
                + "{\"all\":[...]}, {\"any\":[...]}, [fact, op, number or another fact], op one of > >= < <= == !=.");
        JsonArray a = new JsonArray();
        for (Action x : ACTIONS) {
            JsonObject j = new JsonObject();
            j.addProperty("id", x.id);
            j.addProperty("tool", x.tool);
            JsonObject args = new JsonObject();
            for (Map.Entry<String, String> e : x.args.entrySet()) args.addProperty(e.getKey(), e.getValue());
            j.add("args", args);
            j.add("mask", x.mask.json());
            a.add(j);
        }
        o.add("actions", a);
        return o;
    }

    static JsonObject obsJson() {
        JsonObject o = new JsonObject();
        o.addProperty("version", OBS_VERSION);
        o.addProperty("hash", obsHash());
        o.addProperty("dim", FEATURES.size());
        o.addProperty("note", "Generated from RlSpec.java (docs/rl/CONTRACT-v2.md). The server sends the scaled vector in this order. div: clip(x/scale, -1, 2); "
                + "log: log1p(max(x,0))/log1p(scale); bool: 1 if x > 0.5 else 0. A distance to nothing seen is scale (1 scaled).");
        JsonArray a = new JsonArray();
        for (Feature f : FEATURES) {
            JsonObject j = new JsonObject();
            j.addProperty("name", f.name);
            j.addProperty("kind", f.kind);
            j.addProperty("scale", f.scale);
            a.add(j);
        }
        o.add("features", a);
        return o;
    }

    static JsonObject rewardJson() {
        JsonObject o = new JsonObject();
        o.addProperty("version", REWARD_VERSION);
        o.addProperty("note", "Generated from RlSpec.java (docs/rl/CONTRACT-v2.md): the weights the server uses unless -Daiguide.rl.reward=<file> gives others (same keys). "
                + "A key 'a.b' is part b of the component a in info.components. The components named in 'scales' are paid only in the scenarios whose "
                + "scenarioScales name them (times the factor); the others always.");
        JsonObject w = new JsonObject();
        for (Map.Entry<String, Double> e : rewardDefaults().entrySet()) w.addProperty(e.getKey(), e.getValue());
        o.add("weights", w);
        JsonArray sl = new JsonArray();
        for (String s : SCALES) sl.add(new JsonPrimitive(s));
        o.add("scales", sl);
        JsonObject ss = new JsonObject();
        JsonObject sc = new JsonObject();
        for (String n : available()) {
            Scenario s = scenario(n);
            JsonObject f = new JsonObject();
            for (Map.Entry<String, Double> e : s.scales.entrySet()) f.addProperty(e.getKey(), e.getValue());
            ss.add(n, f);
            JsonObject d = new JsonObject();
            d.addProperty("goal", s.goal);
            if (!s.goalItem.isEmpty()) d.addProperty("goal_item", s.goalItem);
            d.addProperty("per_agent", s.perAgent);
            d.addProperty("crew_goal", s.crewGoal);
            d.addProperty("seconds", s.seconds);
            d.addProperty("time", s.time);
            d.addProperty("health", s.health);
            d.addProperty("kit", s.kit);
            d.addProperty("chest", s.chest);
            d.addProperty("xp_levels", s.xpLevels);
            d.addProperty("hunger", s.hunger);
            if (s.waves != null) d.addProperty("waves", s.waves);
            d.addProperty("defense", s.defense);
            sc.add(n, d);
        }
        o.add("scenarioScales", ss);
        JsonArray names = new JsonArray();
        for (String s : available()) names.add(new JsonPrimitive(s));
        o.add("scenarios", names);
        o.add("scenario", sc);
        JsonObject k = new JsonObject();
        JsonObject st = new JsonObject();
        for (String[] x : STOCK) { JsonArray e = new JsonArray(); e.add(new JsonPrimitive(Double.parseDouble(x[1]))); e.add(new JsonPrimitive(Double.parseDouble(x[2]))); st.add(x[0], e); }
        k.add("stock_cap_value", st);
        k.addProperty("stall_s", STALL_S);
        k.addProperty("window_s", WINDOW_S);
        JsonArray hz = new JsonArray();
        for (int h : HORIZONS) hz.add(new JsonPrimitive(h));
        k.add("horizons_y", hz);
        k.addProperty("horizon_near", HORIZON_NEAR);
        k.addProperty("grow", GROW);
        o.add("constants", k);
        return o;
    }

    /** the weights of a reward file (its keys over the defaults); the defaults when there is none */
    static Map<String, Double> rewardWeights(JsonObject file) {
        Map<String, Double> w = rewardDefaults();
        if (file != null && file.has("weights") && file.get("weights").isJsonObject())
            for (Map.Entry<String, JsonElement> e : file.getAsJsonObject("weights").entrySet()) w.put(e.getKey(), e.getValue().getAsDouble());
        return w;
    }

    /** the facts the masks read, each with the numbers its rules compare it with */
    static Map<String, java.util.TreeSet<Double>> maskFacts() {
        Map<String, java.util.TreeSet<Double>> m = new java.util.TreeMap<String, java.util.TreeSet<Double>>();
        for (Action a : ACTIONS) facts(a.mask, m);
        return m;
    }

    static void facts(Cond c, Map<String, java.util.TreeSet<Double>> m) {
        if (!c.kind.equals("cmp")) { for (Cond k : c.kids) facts(k, m); return; }   // (a comparison of two facts: each round 10)
        for (String f : c.ref == null ? new String[] { c.fact } : new String[] { c.fact, c.ref }) {
            if (!m.containsKey(f)) m.put(f, new java.util.TreeSet<Double>());
            if (c.ref == null) m.get(f).add(c.num);
            else m.get(f).add(10.0);
        }
    }

    /** random raw facts round the masks' thresholds (each fact at, under or over a number its rules name, or missing) with Java's mask */
    static JsonObject cases(int n, long seed) {
        java.util.Random rnd = new java.util.Random(seed);
        Map<String, java.util.TreeSet<Double>> fs = maskFacts();
        JsonObject o = new JsonObject();
        o.addProperty("actions_hash", hash());
        JsonArray cs = new JsonArray();
        for (int i = 0; i < n; i++) {
            Map<String, Double> raw = new LinkedHashMap<String, Double>();
            double p = 0.3 + 0.6 * rnd.nextDouble();   // how many facts are given: few to most
            for (Map.Entry<String, java.util.TreeSet<Double>> e : fs.entrySet()) {
                if (rnd.nextDouble() > p) continue;
                Double[] at = e.getValue().toArray(new Double[0]);
                double t = at.length == 0 ? 1 : at[rnd.nextInt(at.length)];
                double[] near = { t - 1, t - 0.5, t, t + 0.5, t + 1, 0, rnd.nextInt(30) };
                raw.put(e.getKey(), near[rnd.nextInt(near.length)]);
            }
            JsonObject c = new JsonObject();
            JsonObject r = new JsonObject();
            for (Map.Entry<String, Double> e : raw.entrySet()) r.addProperty(e.getKey(), e.getValue());
            c.add("raw", r);
            JsonArray mk = new JsonArray();
            for (int x : mask(raw)) mk.add(new JsonPrimitive(x));
            c.add("mask", mk);
            cs.add(c);
        }
        o.add("cases", cs);
        return o;
    }

    static String pretty(JsonObject o) {
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(o) + "\n";
    }

    /**
     * writes rl/actions.json, rl/obs.json, rl/reward.json, rl/achievements.json into the folder given (the repository's
     * rl/); "cases <file> [n]": random raw facts with the mask Java gives, for tools/tests/test_rl_spec2.py
     */
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("cases")) {
            Files.write(new File(args[1]).toPath(), pretty(cases(args.length > 2 ? Integer.parseInt(args[2]) : 2000, 7)).getBytes(StandardCharsets.UTF_8));
            return;
        }
        File dir = new File(args.length > 0 ? args[0] : "rl");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("no folder " + dir);
        Files.write(new File(dir, "actions.json").toPath(), pretty(actionsJson()).getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "obs.json").toPath(), pretty(obsJson()).getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "reward.json").toPath(), pretty(rewardJson()).getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "achievements.json").toPath(), pretty(RlAchievements.json(ACHIEVEMENTS_VERSION)).getBytes(StandardCharsets.UTF_8));
        System.out.println("rl spec " + CONTRACT + ": " + ACTIONS.size() + " actions (" + hash() + "), " + FEATURES.size() + " features (" + obsHash() + "), reward ("
                + rewardHash() + ") -> " + dir);
    }
}
