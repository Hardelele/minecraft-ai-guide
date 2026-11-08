package hardelele.aiguide.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The learning environment's spaces without the game (RlSpec, RlReward; docs/rl/CONTRACT.md): the actions and their
 * calls, the features and their scaling, the mask from raw facts, the outcome groups, the reward by agent and
 * component, the weights read over the defaults, the scenarios.
 */
public final class RlTest {
    static int bad, passed;

    static void check(boolean ok, String what) {
        if (ok) passed++;
        else { bad++; System.out.println("FAIL " + what); }
    }

    static Map<String, Double> raw(Object... kv) {
        Map<String, Double> m = new LinkedHashMap<String, Double>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
        return m;
    }

    static boolean on(Map<String, Double> r, String id) { return RlSpec.mask(r)[RlSpec.index(id)] == 1; }

    public static void main(String[] args) {
        // the actions: noop first, ids unique, each a skill the minds have (or the environment's own: hunt)
        check(RlSpec.ACTIONS.get(0).id.equals("noop"), "noop is action 0");
        Set<String> ids = new HashSet<String>(), skills = new HashSet<String>();
        for (String s : Mind.skills(true)) skills.add(s);
        skills.add("hunt");
        for (RlSpec.Action a : RlSpec.ACTIONS) {
            check(ids.add(a.id), "action id unique: " + a.id);
            if (!a.id.equals("noop")) check(skills.contains(a.tool), "a skill of the minds: " + a.tool);
        }
        check(RlSpec.ACTIONS.size() == 38, "38 actions: " + RlSpec.ACTIONS.size());
        Mind.Call c = RlSpec.call(RlSpec.index("mine:stone"));
        check(c.tool.equals("mine") && c.arg("block").equals("stone") && c.num("count", 0) == 8 && c.why.contains("policy"), "an action as a mind's call: " + c.brief());
        check(RlSpec.call(RlSpec.index("craft:stone_pickaxe")).arg("item").equals("stone_pickaxe"), "craft's item");
        check(RlSpec.hash().length() == 12 && RlSpec.hash().equals(RlSpec.hash()), "the actions' hash");

        // the features: unique names, the scaling of each kind
        Set<String> names = new HashSet<String>();
        for (RlSpec.Feature f : RlSpec.FEATURES) check(names.add(f.name), "feature unique: " + f.name);
        for (RlSpec.Action a : RlSpec.ACTIONS) check(names.contains("last_action_" + a.id), "a last-action feature for " + a.id);
        for (String s : RlSpec.SCENARIOS) check(names.contains("scen_" + s.replace(':', '_')), "a scenario feature for " + s);
        float[] v = RlSpec.vector(raw("health", 10, "bag_log", 64, "night", 0.7, "wall_frac", 5, "enemy_dx", -100));
        check(v.length == RlSpec.FEATURES.size(), "the vector's length");
        check(Math.abs(v[idx("health")] - 0.5) < 1e-6, "div: health 10 of 20 -> 0.5");
        check(Math.abs(v[idx("bag_log")] - 1.0) < 1e-6, "log: 64 of 64 -> 1");
        check(v[idx("night")] == 1f && v[idx("scen_wood")] == 0f, "bool: over a half is 1, a missing fact is 0");
        check(v[idx("wall_frac")] == 2f && v[idx("enemy_dx")] == -1f, "div clipped to -1..2");

        // the mask: noop always; a craft only when the bag can, 3x3 only with a table near or in the bag; tiers
        Map<String, Double> none = new HashMap<String, Double>();
        int[] m0 = RlSpec.mask(none);
        check(m0[0] == 1 && on(none, "look_around") && on(none, "wait:5"), "noop, look_around, wait always");
        int ones = 0;
        for (int x : m0) ones += x;
        check(ones == 3, "an empty agent can do only noop, look_around, wait: " + ones);
        check(on(raw("craftable_planks", 1, "small_planks", 1), "craft:planks"), "planks from logs in the hands");
        check(!on(raw("craftable_planks", 1, "small_planks", 1, "bag_planks", 64), "craft:planks"), "no planks over a stack");
        check(!on(raw("craftable_wooden_pickaxe", 1), "craft:wooden_pickaxe"), "a 3x3 craft needs a table");
        check(on(raw("craftable_wooden_pickaxe", 1, "bag_crafting_table", 1), "craft:wooden_pickaxe"), "the bag's table will do");
        check(on(raw("craftable_wooden_pickaxe", 1, "table_known", 1, "table_dist", 5), "craft:wooden_pickaxe"), "a table near will do");
        check(!on(raw("craftable_wooden_pickaxe", 1, "table_known", 1, "table_dist", 30), "craft:wooden_pickaxe"), "a table too far will not");
        check(!on(raw("craftable_stone_pickaxe", 1, "bag_crafting_table", 1, "pick_tier", 2), "craft:stone_pickaxe"), "no pickaxe it has already");
        check(on(raw("craftable_stone_pickaxe", 1, "bag_crafting_table", 1, "pick_tier", 1), "craft:stone_pickaxe"), "a better pickaxe");
        check(!on(raw("pick_tier", 0), "mine:stone") && on(raw("pick_tier", 1), "mine:stone"), "stone needs a pickaxe");
        check(!on(raw("pick_tier", 1, "iron_minable", 3), "mine:iron_ore") && on(raw("pick_tier", 2, "iron_minable", 3), "mine:iron_ore"), "iron needs stone");
        check(!on(raw("pick_tier", 2), "mine:iron_ore"), "no iron seen, no mining it");
        check(on(raw("trees_free", 2), "chop_tree:near") && !on(raw("trees_free", 0), "chop_tree:near"), "a tree seen free");
        check(!on(raw("waves_active", 1, "health", 5), "hold_line:post") && on(raw("waves_active", 1, "health", 15), "hold_line:post"), "no fight begun hurt");
        check(!on(raw("waves_active", 0, "health", 20), "hold_line:post"), "no fight with no waves");
        check(!on(raw("health", 20, "max_health", 20, "bag_food_cooked", 3), "eat:best") && on(raw("health", 12, "max_health", 20, "bag_food_cooked", 3), "eat:best"), "eat only hurt");
        check(on(raw("bag_iron_ore", 2, "fuel", 4, "bag_furnace", 1), "smelt:iron_ingot") && !on(raw("bag_iron_ore", 2, "bag_furnace", 1), "smelt:iron_ingot"), "smelting needs fuel");
        check(on(raw("base_known", 1, "wall_blocks", 20), "build_wall:ring") && !on(raw("base_known", 1, "wall_blocks", 20, "ring_planned", 1, "wall_frac", 1), "build_wall:ring"), "no wall built twice");
        check(on(raw("ring_planned", 1, "posts_free", 2), "take_post:auto") && !on(raw("ring_planned", 1, "has_post", 1, "post_dist", 1), "take_post:auto"), "a post once");
        check(on(raw("chest_known", 1, "chest_food", 4), "take:chest_food") && !on(raw("chest_known", 1), "take:chest_food"), "food from the chest");

        // the outcome groups
        check(RlSpec.outcome("OK").equals("ok") && RlSpec.outcome("NO_PATH").equals("fail_path") && RlSpec.outcome("MISSING").equals("fail_items")
                && RlSpec.outcome("NO_TREE_SEEN").equals("fail_target") && RlSpec.outcome("LOW_HEALTH").equals("fail_danger") && RlSpec.outcome("MASKED").equals("fail_other")
                && RlSpec.outcome(null).equals("none"), "outcome groups");

        // the reward: by agent and component, taken at the agent's decision
        RlReward r = new RlReward(RlSpec.rewardDefaults());
        r.add("1", "time", 20);
        r.add("1", "gathered.log", 5);
        r.add("2", "death", 1);
        r.add("1", "kill", 0);
        JsonObject comp = new JsonObject();
        double t1 = r.take("1", comp);
        check(Math.abs(t1 - (-0.02 + 0.1)) < 1e-9, "agent 1's reward: " + t1);
        check(comp.has("time") && comp.has("gathered") && !comp.has("kill") && Math.abs(comp.get("gathered").getAsDouble() - 0.1) < 1e-9, "its components: " + comp);
        check(r.take("1", null) == 0, "taken once");
        check(Math.abs(r.take("2", null) + 1) < 1e-9, "a death -1");
        check(Math.abs(r.total - (-0.92)) < 1e-9 && r.episodeJson().get("death").getAsDouble() == -1, "the episode's sum by component");
        boolean threw = false;
        try { r.add("1", "nonsense", 1); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "an unknown weight is an error");
        Map<String, Double> w = RlSpec.rewardWeights(new JsonParser().parse("{\"weights\":{\"noop\":-3,\"time\":0}}").getAsJsonObject());
        check(w.get("noop") == -3 && w.get("time") == 0 && w.get("goal") == 5, "a weights file over the defaults");
        check(RlSpec.component("gathered.iron_ore").equals("gathered") && RlSpec.component("noop").equals("noop"), "a weight's component");
        for (String k : RlSpec.rewardDefaults().keySet()) check(!k.isEmpty(), "weight " + k);

        // the scenarios
        for (String s : RlSpec.available()) {
            RlSpec.Scenario x = RlSpec.scenario(s);
            check(x.seconds > 0 && !x.goal.isEmpty(), "scenario " + s);
        }
        check(RlSpec.scenario("siege") == null && RlSpec.scenario("nonsense") == null && !RlSpec.available().contains("siege"), "siege not there yet");
        check(RlSpec.scenario("waves:normal").waves.equals("normal") && RlSpec.scenario("waves:easy").defense && RlSpec.scenario("wood").waves == null, "the waves' levels");
        check(RlSpec.kit("stone_pickaxe 1, planks 16,stick").size() == 3 && RlSpec.kit("stone_pickaxe 1, planks 16,stick").get(2)[1].equals("1"), "a kit read");

        System.out.println("RlTest " + (bad == 0 ? "PASS" : "FAIL") + " (" + passed + " checks, " + bad + " failed)");
        if (bad > 0) System.exit(1);
    }

    static int idx(String name) {
        for (int i = 0; i < RlSpec.FEATURES.size(); i++) if (RlSpec.FEATURES.get(i).name.equals(name)) return i;
        throw new IllegalArgumentException(name);
    }
}
