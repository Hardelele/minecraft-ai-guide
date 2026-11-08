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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The learning environment's spaces, with no game in them (docs/rl/CONTRACT.md): the discrete actions (a mind's skill
 * with its arguments fixed), the observation's features (a name, a kind of scaling, a scale), the mask of the actions
 * from the raw facts the game gives by name, the reward's weights, the scenarios. rl/actions.json, rl/obs.json and
 * rl/reward.json are written from here (main); RlTest checks they still are. RlEnv fills the raw facts in the game.
 */
final class RlSpec {
    private RlSpec() { }

    static final int CONTRACT = 1, ACTIONS_VERSION = 1, OBS_VERSION = 1, REWARD_VERSION = 1;
    static final int MAX_AGENTS = 4;

    // ---- the actions ------------------------------------------------------------------------------------------

    static final class Action {
        final String id, tool;
        final Map<String, String> args = new LinkedHashMap<String, String>();
        Action(String id, String tool, String... kv) {
            this.id = id;
            this.tool = tool;
            for (int i = 0; i + 1 < kv.length; i += 2) args.put(kv[i], kv[i + 1]);
        }
    }

    /** the crafts: the item, how many wanted a call */
    static final String[][] CRAFTS = { { "planks", "4" }, { "stick", "4" }, { "crafting_table", "1" }, { "wooden_pickaxe", "1" }, { "stone_pickaxe", "1" },
            { "iron_pickaxe", "1" }, { "wooden_sword", "1" }, { "stone_sword", "1" }, { "iron_sword", "1" }, { "furnace", "1" }, { "torch", "4" }, { "chest", "1" } };

    static final List<Action> ACTIONS = Collections.unmodifiableList(actions());

    static List<Action> actions() {
        List<Action> l = new ArrayList<Action>();
        l.add(new Action("noop", "noop"));
        l.add(new Action("look_around", "look_around"));
        l.add(new Action("go_to:frontier", "go_to", "target", "frontier"));
        l.add(new Action("chop_tree:near", "chop_tree", "at", "near"));
        l.add(new Action("mine:stone", "mine", "block", "stone", "count", "8"));
        l.add(new Action("mine:coal_ore", "mine", "block", "coal_ore", "count", "3"));
        l.add(new Action("mine:iron_ore", "mine", "block", "iron_ore", "count", "3"));
        for (String[] c : CRAFTS) l.add(new Action("craft:" + c[0], "craft", "item", c[0], "count", c[1]));
        l.add(new Action("smelt:iron_ingot", "smelt", "item", "iron_ingot", "count", "3"));
        l.add(new Action("smelt:food", "smelt", "item", "food", "count", "4"));
        l.add(new Action("place:crafting_table", "place", "item", "crafting_table", "at", "near"));
        l.add(new Action("place:furnace", "place", "item", "furnace", "at", "near"));
        l.add(new Action("place:chest", "place", "item", "chest", "at", "near"));
        l.add(new Action("build_wall:ring", "build_wall", "shape", "around base", "height", "2", "material", "any"));
        l.add(new Action("repair_wall:nearest", "repair_wall"));
        l.add(new Action("dig_moat:ring", "dig_moat", "depth", "1"));
        l.add(new Action("light_area:base", "light_area", "radius", "12"));
        l.add(new Action("equip:best", "equip", "what", "best"));
        l.add(new Action("take_post:auto", "take_post", "where", "auto"));
        l.add(new Action("hold_line:post", "hold_line", "radius", "4", "seconds", "60"));
        l.add(new Action("retreat:home", "retreat", "where", "home"));
        l.add(new Action("eat:best", "eat"));
        l.add(new Action("hunt:near", "hunt", "at", "near"));
        l.add(new Action("put:chest_all", "put", "chest", "crew", "item", "all", "count", "1728"));
        l.add(new Action("take:chest_food", "take", "chest", "crew", "item", "food", "count", "8"));
        l.add(new Action("go_to:base", "go_to", "target", "base"));
        l.add(new Action("wait:5", "wait", "seconds", "5"));
        return l;
    }

    static int index(String id) {
        for (int i = 0; i < ACTIONS.size(); i++) if (ACTIONS.get(i).id.equals(id)) return i;
        return -1;
    }

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

    // ---- the observation --------------------------------------------------------------------------------------

    static final class Feature {
        final String name, kind;
        final double scale;
        Feature(String name, String kind, double scale) { this.name = name; this.kind = kind; this.scale = scale; }
    }

    static final String[] SCENARIOS = { "wood", "pickaxe", "sword", "iron", "food", "fortify", "waves:easy", "waves:normal", "siege" };
    static final String[] BAG = { "log", "planks", "stick", "crafting_table", "cobblestone", "coal", "iron_ore", "iron_ingot", "furnace", "chest", "torch",
            "wooden_pickaxe", "stone_pickaxe", "iron_pickaxe", "wooden_sword", "stone_sword", "iron_sword", "food_raw", "food_cooked", "dirt" };
    /** the groups of a step's end (its reason code): the last action's one-hot */
    static final String[] OUTCOMES = { "none", "ok", "fail_path", "fail_items", "fail_target", "fail_danger", "fail_other" };

    static final List<Feature> FEATURES = Collections.unmodifiableList(features());

    static List<Feature> features() {
        List<Feature> f = new ArrayList<Feature>();
        for (String s : SCENARIOS) f.add(new Feature("scen_" + s.replace(':', '_'), "bool", 1));
        for (int i = 0; i < MAX_AGENTS; i++) f.add(new Feature("agent_" + i, "bool", 1));
        f.add(new Feature("n_agents", "div", MAX_AGENTS));
        add(f, "div", 20, "health", "armor");
        add(f, "div", 4, "held_tier", "weapon_tier", "pick_tier");
        f.add(new Feature("at_home", "bool", 1));
        add(f, "div", 1, "day_sin", "day_cos");
        f.add(new Feature("night", "bool", 1));
        f.add(new Feature("dusk_in", "div", 600));
        for (String b : BAG) f.add(new Feature("bag_" + b, "log", 64));
        f.add(new Feature("bag_total", "log", 256));
        f.add(new Feature("trees_seen", "div", 10)); f.add(new Feature("tree_dist", "div", 48));
        f.add(new Feature("stone_seen", "div", 32)); f.add(new Feature("stone_dist", "div", 32));
        f.add(new Feature("coal_seen", "div", 8)); f.add(new Feature("coal_dist", "div", 48));
        f.add(new Feature("iron_seen", "div", 8)); f.add(new Feature("iron_dist", "div", 48));
        f.add(new Feature("animals_seen", "div", 5)); f.add(new Feature("animal_dist", "div", 24));
        f.add(new Feature("table_known", "bool", 1)); f.add(new Feature("table_dist", "div", 24));
        f.add(new Feature("furnace_known", "bool", 1)); f.add(new Feature("furnace_dist", "div", 24));
        f.add(new Feature("chest_known", "bool", 1)); f.add(new Feature("chest_dist", "div", 48));
        f.add(new Feature("base_known", "bool", 1)); f.add(new Feature("base_dist", "div", 32));
        f.add(new Feature("chest_food", "log", 64));
        add(f, "bool", 1, "waves_active", "wave_on", "phase_prep", "phase_break");
        f.add(new Feature("wave_num", "div", 10)); f.add(new Feature("next_wave_in", "div", 120));
        f.add(new Feature("enemies", "div", 20)); f.add(new Feature("enemy_dist", "div", 32));
        add(f, "div", 32, "enemy_dx", "enemy_dz");
        f.add(new Feature("enemies_near", "div", 10));
        f.add(new Feature("ring_planned", "bool", 1)); f.add(new Feature("wall_frac", "div", 1)); f.add(new Feature("holes", "div", 16));
        f.add(new Feature("moat_frac", "div", 1)); f.add(new Feature("torches_placed", "div", 16)); f.add(new Feature("posts_free", "div", 8));
        add(f, "bool", 1, "has_post", "inside_wall");
        f.add(new Feature("allies_alive", "div", 3)); add(f, "div", 20, "allies_health_mean", "allies_health_min");
        f.add(new Feature("allies_low", "div", 3)); f.add(new Feature("ally_dist", "div", 32));
        for (String o : OUTCOMES) f.add(new Feature("last_" + o, "bool", 1));
        f.add(new Feature("repeated", "div", 4)); f.add(new Feature("last_seconds", "div", 60));
        for (Action a : actions()) f.add(new Feature("last_action_" + a.id, "bool", 1));
        f.add(new Feature("time_frac", "div", 1)); f.add(new Feature("goal_progress", "div", 1));
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
        if (r.equals("NO_PATH") || r.equals("NO_SPOT") || r.equals("OUT_OF_REACH") || r.equals("NOT_VISIBLE") || r.equals("BLOCKED") || r.equals("OCCUPIED") || r.equals("BUSY")) return "fail_path";
        if (r.equals("NO_TOOL") || r.equals("NO_ITEMS") || r.equals("MISSING") || r.equals("NO_TABLE") || r.equals("NO_FURNACE") || r.equals("NO_FUEL")
                || r.equals("NO_FOOD") || r.equals("NO_TORCHES") || r.equals("NO_WEAPON") || r.equals("NO_ARMOR") || r.equals("CHEST_FULL") || r.equals("NOT_IN_CHEST")) return "fail_items";
        if (r.equals("NOT_SEEN") || r.equals("NO_TREE_SEEN") || r.equals("NO_TREE_THERE") || r.equals("GONE") || r.equals("NO_CHEST") || r.equals("NO_PERIMETER") || r.equals("NO_BASE")
                || r.equals("NO_HOME") || r.equals("NO_HOLES") || r.equals("UNKNOWN_TARGET") || r.equals("POST_TAKEN") || r.equals("ALL_TAKEN") || r.equals("DONE_ALREADY")
                || r.equals("NO_ANIMAL") || r.equals("NO_FRONTIER")) return "fail_target";
        if (r.equals("LOW_HEALTH") || r.equals("DIED") || r.equals("INTERRUPTED")) return "fail_danger";
        return "fail_other";
    }

    // ---- the mask: which actions may be chosen, from the raw facts ---------------------------------------------

    /** the health under which a fight is not begun (Defense.LOW at the start; RlEnv passes the live one as low_health) */
    static final double LOW = 8;

    static double g(Map<String, Double> raw, String k) {
        Double v = raw.get(k);
        return v == null ? 0 : v;
    }

    /** 1/0 for each action; noop always 1 */
    static int[] mask(Map<String, Double> r) {
        int[] m = new int[ACTIONS.size()];
        for (int i = 0; i < m.length; i++) m[i] = allowed(ACTIONS.get(i), r) ? 1 : 0;
        return m;
    }

    static boolean allowed(Action a, Map<String, Double> r) {
        String id = a.id;
        boolean table = g(r, "table_known") > 0.5 && g(r, "table_dist") <= 24 || g(r, "bag_crafting_table") > 0;
        boolean furnace = g(r, "furnace_known") > 0.5 && g(r, "furnace_dist") <= 24 || g(r, "bag_furnace") > 0;
        boolean base = g(r, "base_known") > 0.5;
        if (id.equals("noop") || id.equals("look_around") || id.equals("wait:5")) return true;
        if (id.equals("go_to:frontier")) return g(r, "frontier") > 0;
        if (id.equals("chop_tree:near")) return g(r, "trees_free") > 0;
        if (id.equals("mine:stone")) return g(r, "pick_tier") >= 1;
        if (id.equals("mine:coal_ore")) return g(r, "pick_tier") >= 1 && g(r, "coal_minable") > 0;
        if (id.equals("mine:iron_ore")) return g(r, "pick_tier") >= 2 && g(r, "iron_minable") > 0;
        if (id.startsWith("craft:")) {
            String it = id.substring(6);
            if (g(r, "craftable_" + it) <= 0) return false;
            if (g(r, "small_" + it) <= 0 && !table) return false;
            if (it.equals("planks")) return g(r, "bag_planks") < 64;
            if (it.equals("stick")) return g(r, "bag_stick") < 32;
            if (it.equals("torch")) return g(r, "bag_torch") < 32;
            if (it.equals("crafting_table")) return g(r, "bag_crafting_table") == 0;
            if (it.equals("furnace")) return g(r, "bag_furnace") == 0;
            if (it.equals("chest")) return g(r, "bag_chest") == 0 && g(r, "chest_known") < 0.5;
            if (it.endsWith("_pickaxe")) return g(r, "pick_tier") < tier(it);
            if (it.endsWith("_sword")) return g(r, "weapon_tier") < tier(it);
            return true;
        }
        if (id.equals("smelt:iron_ingot")) return g(r, "bag_iron_ore") > 0 && g(r, "fuel") > 0 && furnace;
        if (id.equals("smelt:food")) return g(r, "bag_food_raw") > 0 && g(r, "fuel") > 0 && furnace;
        if (id.equals("place:crafting_table")) return g(r, "bag_crafting_table") > 0 && !(g(r, "table_known") > 0.5 && g(r, "table_dist") <= 6);
        if (id.equals("place:furnace")) return g(r, "bag_furnace") > 0 && !(g(r, "furnace_known") > 0.5 && g(r, "furnace_dist") <= 6);
        if (id.equals("place:chest")) return g(r, "bag_chest") > 0 && g(r, "chest_known") < 0.5;
        if (id.equals("build_wall:ring")) return base && g(r, "wall_blocks") > 0 && !(g(r, "ring_planned") > 0.5 && g(r, "wall_frac") >= 1);
        if (id.equals("repair_wall:nearest")) return g(r, "ring_planned") > 0.5 && g(r, "holes") > 0 && g(r, "wall_blocks") > 0;
        if (id.equals("dig_moat:ring")) return g(r, "ring_planned") > 0.5 && g(r, "moat_frac") < 1;
        if (id.equals("light_area:base")) return g(r, "bag_torch") > 0 && g(r, "light_spots") > 0;
        if (id.equals("equip:best")) return g(r, "can_equip") > 0;
        if (id.equals("take_post:auto")) return g(r, "ring_planned") > 0.5 && (g(r, "has_post") < 0.5 ? g(r, "posts_free") > 0 : g(r, "post_dist") > 2);
        if (id.equals("hold_line:post")) return g(r, "waves_active") > 0.5 && g(r, "health") >= (r.containsKey("low_health") ? g(r, "low_health") : LOW);
        if (id.equals("retreat:home")) return base && g(r, "base_dist") > 3;
        if (id.equals("eat:best")) return g(r, "bag_food_raw") + g(r, "bag_food_cooked") > 0 && g(r, "health") < g(r, "max_health");
        if (id.equals("hunt:near")) return g(r, "animals_seen") > 0;
        if (id.equals("put:chest_all")) return g(r, "chest_known") > 0.5 && g(r, "bag_stackables") > 0;
        if (id.equals("take:chest_food")) return g(r, "chest_known") > 0.5 && g(r, "chest_food") > 0;
        if (id.equals("go_to:base")) return base && g(r, "base_dist") > 4;
        return false;
    }

    /** a tool's tier by its id (EpochScore.tier) */
    static int tier(String id) { return EpochScore.tier(id); }

    // ---- the reward's weights ---------------------------------------------------------------------------------

    static Map<String, Double> rewardDefaults() {
        Map<String, Double> w = new LinkedHashMap<String, Double>();
        w.put("noop", -1.0);
        w.put("time", -0.001);
        w.put("skill_fail", -0.05);
        w.put("skill_ok", 0.02);
        w.put("tier_up", 0.5);
        w.put("new_item", 0.1);
        w.put("gathered.log", 0.02);
        w.put("gathered.cobblestone", 0.01);
        w.put("gathered.coal", 0.05);
        w.put("gathered.iron_ore", 0.1);
        w.put("gathered.food", 0.05);
        w.put("built.wall", 0.02);
        w.put("built.moat", 0.01);
        w.put("built.torch", 0.02);
        w.put("ate", 0.05);
        w.put("kill", 0.2);
        w.put("damage", -0.01);
        w.put("death", -1.0);
        w.put("accident", -1.5);
        w.put("out_died", -0.5);
        w.put("wave_cleared", 1.0);
        w.put("holes_at_wave", -0.05);
        w.put("goal", 5.0);
        w.put("human", 2.0);
        return w;
    }

    /** the component a weight belongs to ("gathered.log" -> "gathered") */
    static String component(String weight) {
        int d = weight.indexOf('.');
        return d < 0 ? weight : weight.substring(0, d);
    }

    // ---- the scenarios ----------------------------------------------------------------------------------------

    static final class Scenario {
        final String name;
        String kit = "", chest = "", goalItem = "", goal = "";
        int seconds = 300, perAgent = 1;
        long time = 1000;
        float health = 20;
        String waves;          // a level of waves (easy, normal), null: none
        boolean defense;       // the minds' goal is one of defence (Defense.isGoal)
        Scenario(String name) { this.name = name; }
    }

    static Scenario scenario(String name) {
        Scenario s = new Scenario(name == null ? "" : name);
        switch (s.name) {
            case "wood": s.goal = "items"; s.goalItem = "log"; s.perAgent = 10; s.seconds = 300; break;
            case "pickaxe": s.goal = "items"; s.goalItem = "stone_pickaxe"; s.seconds = 600; break;
            case "sword": s.goal = "items"; s.goalItem = "stone_sword"; s.seconds = 600; break;
            case "iron": s.goal = "items"; s.goalItem = "iron_pickaxe"; s.seconds = 900; s.kit = "stone_pickaxe 1, planks 16, stick 4"; break;
            case "food": s.goal = "food"; s.perAgent = 2; s.seconds = 300; s.kit = "wooden_sword 1"; s.health = 10; break;
            case "fortify": s.goal = "fortify"; s.seconds = 600; s.kit = "cobblestone 140, stone_pickaxe 1"; s.defense = true; break;
            case "waves:easy": case "waves:normal":
                s.goal = "none"; s.seconds = 900; s.kit = "stone_sword 1, cobblestone 64, bread 6, torch 8"; s.chest = "bread 16"; s.time = 14000;
                s.waves = s.name.substring(6); s.defense = true; break;
            default: return null;   // siege and the unknown: not there
        }
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

    static String hash() {
        StringBuilder b = new StringBuilder();
        for (Action a : ACTIONS) b.append(a.id).append('|').append(a.tool).append('|').append(a.args).append('\n');
        return sha(b.toString());
    }

    static String obsHash() {
        StringBuilder b = new StringBuilder();
        for (Feature f : FEATURES) b.append(f.name).append('|').append(f.kind).append('|').append(f.scale).append('\n');
        return sha(b.toString());
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
        o.addProperty("note", "Generated from RlSpec.java (docs/rl/CONTRACT.md). The index is the action's number; noop (0) is always allowed and ends the episode with -1.");
        JsonArray a = new JsonArray();
        for (Action x : ACTIONS) {
            JsonObject j = new JsonObject();
            j.addProperty("id", x.id);
            j.addProperty("tool", x.tool);
            JsonObject args = new JsonObject();
            for (Map.Entry<String, String> e : x.args.entrySet()) args.addProperty(e.getKey(), e.getValue());
            j.add("args", args);
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
        o.addProperty("note", "Generated from RlSpec.java. The server sends the scaled vector in this order. div: clip(x/scale, -1, 2); log: log1p(max(x,0))/log1p(scale); "
                + "bool: 1 if x > 0.5 else 0. A distance to nothing seen is scale (1 scaled).");
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
        o.addProperty("note", "Generated from RlSpec.java: the weights the server uses unless -Daiguide.rl.reward=<file> gives others (same keys). "
                + "A key 'a.b' is part b of the component a in info.components.");
        JsonObject w = new JsonObject();
        for (Map.Entry<String, Double> e : rewardDefaults().entrySet()) w.addProperty(e.getKey(), e.getValue());
        o.add("weights", w);
        JsonArray sc = new JsonArray();
        for (String s : available()) sc.add(new JsonPrimitive(s));
        o.add("scenarios", sc);
        return o;
    }

    /** the weights of a reward file (its keys over the defaults); the defaults when there is none */
    static Map<String, Double> rewardWeights(JsonObject file) {
        Map<String, Double> w = rewardDefaults();
        if (file != null && file.has("weights") && file.get("weights").isJsonObject())
            for (Map.Entry<String, com.google.gson.JsonElement> e : file.getAsJsonObject("weights").entrySet()) w.put(e.getKey(), e.getValue().getAsDouble());
        return w;
    }

    static String pretty(JsonObject o) {
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(o) + "\n";
    }

    /** writes rl/actions.json, rl/obs.json, rl/reward.json into the folder given (the repository's rl/) */
    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "rl");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("no folder " + dir);
        Files.write(new File(dir, "actions.json").toPath(), pretty(actionsJson()).getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "obs.json").toPath(), pretty(obsJson()).getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "reward.json").toPath(), pretty(rewardJson()).getBytes(StandardCharsets.UTF_8));
        System.out.println("rl spec: " + ACTIONS.size() + " actions (" + hash() + "), " + FEATURES.size() + " features (" + obsHash() + ") -> " + dir);
    }
}
