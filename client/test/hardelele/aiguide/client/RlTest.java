package hardelele.aiguide.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The learning environment's spaces without the game (RlSpec, RlSpec1, RlReward, RlAchievements, RlScore;
 * docs/rl/CONTRACT-v2.md): the actions and their calls, contract 1's kept as the first 38, the features and their
 * scaling, the mask from raw facts by the rules, the outcome groups, the reward by agent and component with its
 * scales by scenario, the weights read over the defaults, the scenarios, the achievements' tree, the measures.
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
        actions();
        features();
        masks();
        reward();
        scenarios();
        achievements();
        score();
        System.out.println("RlTest " + (bad == 0 ? "PASS" : "FAIL") + " (" + passed + " checks, " + bad + " failed)");
        if (bad > 0) System.exit(1);
    }

    static void actions() {
        // the actions: noop first, ids unique, each a skill the minds have or the environment's own
        check(RlSpec.ACTIONS.get(0).id.equals("noop"), "noop is action 0");
        Set<String> ids = new HashSet<String>(), skills = new HashSet<String>();
        for (String s : Mind.skills(true)) skills.add(s);
        for (String s : RlSpec.ENV_TOOLS) skills.add(s);
        for (RlSpec.Action a : RlSpec.ACTIONS) {
            check(ids.add(a.id), "action id unique: " + a.id);
            if (!a.id.equals("noop")) check(skills.contains(a.tool), "a skill of the minds or the environment: " + a.tool);
        }
        check(RlSpec.ACTIONS.size() == 82, "82 actions: " + RlSpec.ACTIONS.size());
        // contract 1's actions are the first 38, the same (a v1 net's action k is k here)
        check(RlSpec1.ACTIONS.size() == RlSpec.V1_ACTIONS, "contract 1 had 38");
        boolean same = true;
        for (int i = 0; i < RlSpec1.ACTIONS.size(); i++) {
            RlSpec1.Action o = RlSpec1.ACTIONS.get(i);
            RlSpec.Action n = RlSpec.ACTIONS.get(i);
            same &= o.id.equals(n.id) && o.tool.equals(n.tool) && o.args.equals(n.args);
        }
        check(same, "the first 38 are contract 1's in its order");
        check(RlSpec1.hash().equals("1e7a66545050") && RlSpec1.obsHash().equals("4ba70eaea357"), "contract 1's hashes as they were: " + RlSpec1.hash() + " " + RlSpec1.obsHash());
        Mind.Call c = RlSpec.call(RlSpec.index("mine:stone"));
        check(c.tool.equals("mine") && c.arg("block").equals("stone") && c.num("count", 0) == 8 && c.why.contains("policy"), "an action as a mind's call: " + c.brief());
        check(RlSpec.call(RlSpec.index("craft:stone_pickaxe")).arg("item").equals("stone_pickaxe"), "craft's item");
        check(RlSpec.call(RlSpec.index("craft_armor:iron")).arg("material").equals("iron"), "craft_armor's material");
        check(RlSpec.hash().length() == 12 && !RlSpec.hash().equals(RlSpec1.hash()), "the actions' hash (its rules in it)");
        // the spaces of a net by its version and size: none silently
        check(RlSpec.space(fake(1, 137, 38)) == RlSpec.V1 && RlSpec.space(fake(2, RlSpec.FEATURES.size(), 82)) == RlSpec.V2, "a net's spaces by its version and size");
        check(RlSpec.space(fake(2, 137, 38)) == null && RlSpec.space(fake(1, RlSpec.FEATURES.size(), 82)) == null && RlSpec.space(fake(2, 137, 82)) == null,
                "a net whose version and size disagree is refused");
        check(RlSpec.V1.index("wait:5") == 37 && RlSpec.V1.index("descend:mine") == -1 && RlSpec.V2.index("descend:mine") == 38, "the actions a net of each contract has");
    }

    static PolicyNet fake(int version, int d, int a) {
        Map<String, float[]> t = new HashMap<String, float[]>();
        int h = 2;
        t.put("obs_mean", new float[d]); t.put("obs_std", new float[d]); t.put("l1.w", new float[h * d]); t.put("l1.b", new float[h]);
        t.put("l2.w", new float[h * h]); t.put("l2.b", new float[h]); t.put("pi.w", new float[a * h]); t.put("pi.b", new float[a]);
        PolicyNet n = new PolicyNet(d, a, h, t);
        n.version = version;
        return n;
    }

    static void features() {
        Set<String> names = new HashSet<String>();
        for (RlSpec.Feature f : RlSpec.FEATURES) check(names.add(f.name), "feature unique: " + f.name);
        for (RlSpec.Action a : RlSpec.ACTIONS) check(names.contains("last_action_" + a.id), "a last-action feature for " + a.id);
        for (String s : RlSpec.SCENARIOS) check(names.contains("scen_" + s.replace(':', '_')), "a scenario feature for " + s);
        for (String s : RlSpec.SCALES) check(names.contains("scale_" + s), "a scale feature for " + s);
        for (String a : RlAchievements.supported()) check(names.contains("ach_" + a) && names.contains("ach_target_" + a), "achievement features for " + a);
        // contract 1's features are all still there under their names (a v1 net reads them from the same raw facts)
        int kept = 0;
        for (RlSpec1.Feature f : RlSpec1.FEATURES) if (names.contains(f.name) || f.name.equals("scen_siege")) kept++;
        check(kept == RlSpec1.FEATURES.size(), "contract 1's features are among contract 2's facts: " + kept + " of " + RlSpec1.FEATURES.size());
        float[] v = RlSpec.vector(raw("health", 10, "bag_log", 64, "night", 0.7, "wall_frac", 5, "enemy_dx", -100, "y_rel", -40));
        check(v.length == RlSpec.FEATURES.size(), "the vector's length");
        check(Math.abs(v[idx("health")] - 0.5) < 1e-6, "div: health 10 of 20 -> 0.5");
        check(Math.abs(v[idx("bag_log")] - 1.0) < 1e-6, "log: 64 of 64 -> 1");
        check(v[idx("night")] == 1f && v[idx("scen_wood")] == 0f, "bool: over a half is 1, a missing fact is 0");
        check(v[idx("wall_frac")] == 2f && v[idx("enemy_dx")] == -1f, "div clipped to -1..2");
        check(Math.abs(v[idx("y_rel")] + 0.625) < 1e-6, "under the ground: y_rel negative");
    }

    static void masks() {
        Map<String, Double> none = new HashMap<String, Double>();
        int[] m0 = RlSpec.mask(none);
        check(m0[0] == 1 && on(none, "look_around") && on(none, "wait:5"), "noop, look_around, wait always");
        int ones = 0;
        for (int x : m0) ones += x;
        check(ones == 3, "an empty agent can do only noop, look_around, wait: " + ones);
        // contract 1's rules, as they were
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
        check(on(raw("trees_free", 2), "chop_tree:near") && !on(raw("trees_free", 0), "chop_tree:near"), "a tree seen free");
        check(!on(raw("waves_active", 1, "health", 5, "low_health", 8), "hold_line:post") && on(raw("waves_active", 1, "health", 15, "low_health", 8), "hold_line:post"), "no fight begun hurt");
        check(!on(raw("health", 20, "max_health", 20, "food_items", 3), "eat:best") && on(raw("health", 12, "max_health", 20, "food_items", 3), "eat:best"), "eat when hurt");
        check(on(raw("bag_iron_ore", 2, "fuel", 4, "bag_furnace", 1), "smelt:iron_ingot") && !on(raw("bag_iron_ore", 2, "bag_furnace", 1), "smelt:iron_ingot"), "smelting needs fuel");
        check(on(raw("base_known", 1, "wall_blocks", 20), "build_wall:ring") && !on(raw("base_known", 1, "wall_blocks", 20, "ring_planned", 1, "wall_frac", 1), "build_wall:ring"), "no wall built twice");
        check(on(raw("ring_planned", 1, "posts_free", 2), "take_post:auto") && !on(raw("ring_planned", 1, "has_post", 1, "post_dist", 1), "take_post:auto"), "a post once");
        check(on(raw("chest_known", 1, "chest_food", 4), "take:chest_food") && !on(raw("chest_known", 1), "take:chest_food"), "food from the chest");
        // contract 2's
        check(!on(raw("trees_free", 2, "underground", 1), "chop_tree:near") && !on(raw("base_known", 1, "wall_blocks", 20, "underground", 1), "build_wall:ring"), "no trees, no wall from under the ground");
        check(on(raw("health", 20, "max_health", 20, "food_items", 3, "hunger_on", 1, "food_level", 14), "eat:best")
                && !on(raw("health", 20, "max_health", 20, "food_items", 3, "hunger_on", 1, "food_level", 20), "eat:best"), "with hunger: eat when hungry, not when full");
        check(on(raw("pick_tier", 1, "base_known", 1), "descend:mine") && !on(raw("pick_tier", 1, "base_known", 1, "depth_level", 2), "descend:mine")
                && !on(raw("base_known", 1), "descend:mine"), "down the mine with a pickaxe, not below the last horizon");
        check(on(raw("underground", 1, "mine_known", 1), "ascend:surface") && !on(raw("mine_known", 1), "ascend:surface"), "up only from under the ground");
        check(!on(raw("pick_tier", 2, "diamond_minable", 1), "mine:diamond_ore") && on(raw("pick_tier", 3, "diamond_minable", 1), "mine:diamond_ore"), "diamond needs iron");
        check(!on(raw("pick_tier", 3, "obsidian_minable", 1), "mine:obsidian") && on(raw("pick_tier", 4, "obsidian_minable", 1), "mine:obsidian"), "obsidian needs diamond");
        check(on(raw("bag_bow", 1, "bag_arrow", 4, "enemies_visible", 1), "shoot:post") && !on(raw("bag_bow", 1, "enemies_visible", 1), "shoot:post"), "a shot needs arrows");
        check(on(raw("armor_craftable_iron", 1, "bag_crafting_table", 1), "craft_armor:iron") && !on(raw("armor_craftable_iron", 1), "craft_armor:iron"), "armour at a table");
        check(on(raw("bag_wheat_seeds", 3, "farm_empty", 2), "plant:wheat") && !on(raw("bag_wheat_seeds", 3), "plant:wheat"), "seeds on empty farmland");
        check(on(raw("farm_ripe", 1), "harvest:farm") && !on(raw("farm_planted", 5), "harvest:farm"), "a harvest of the ripe only");
        check(on(raw("pen_adults", 3), "slaughter:pen") && !on(raw("pen_adults", 2), "slaughter:pen"), "the pen keeps its two");
        check(on(raw("ench_table_known", 1, "ench_table_dist", 3, "xp_level", 2, "enchantable", 1), "enchant:best")
                && !on(raw("ench_table_known", 1, "ench_table_dist", 3, "enchantable", 1), "enchant:best"), "an enchanting needs levels");
        check(on(raw("ring_planned", 1, "bag_wooden_door", 1), "build_gate:door") && !on(raw("ring_planned", 1, "bag_wooden_door", 1, "door_placed", 1), "build_gate:door"), "one gate");
        check(!on(raw("craftable_wooden_hoe", 1, "small_wooden_hoe", 0, "bag_crafting_table", 1, "hoes", 1), "craft:wooden_hoe"), "no second hoe");
        // the rules are data: Cond's JSON read back gives the same
        int[] mk = RlSpec.mask(raw("trees_free", 1, "pick_tier", 1, "base_known", 1, "craftable_planks", 1, "small_planks", 1));
        check(mk[RlSpec.index("chop_tree:near")] == 1 && mk[RlSpec.index("descend:mine")] == 1 && mk[RlSpec.index("ascend:surface")] == 0, "a mask of several");
        // a negative control: the rules of two actions swapped give another mask on some states
        JsonObject cs = RlSpec.cases(500, 3);
        int differ = 0, i1 = RlSpec.index("chop_tree:near"), i2 = RlSpec.index("mine:stone");
        for (com.google.gson.JsonElement e : cs.getAsJsonArray("cases")) {
            Map<String, Double> r = new HashMap<String, Double>();
            for (Map.Entry<String, com.google.gson.JsonElement> kv : e.getAsJsonObject().getAsJsonObject("raw").entrySet()) r.put(kv.getKey(), kv.getValue().getAsDouble());
            if (RlSpec.ACTIONS.get(i1).mask.eval(r) != (e.getAsJsonObject().getAsJsonArray("mask").get(i2).getAsInt() == 1)) differ++;
        }
        check(differ > 20, "a swapped rule is caught on the cases: " + differ + " of 500 differ");
        check(RlSpec.outcome("OK").equals("ok") && RlSpec.outcome("NO_PATH").equals("fail_path") && RlSpec.outcome("MISSING").equals("fail_items")
                && RlSpec.outcome("NO_TREE_SEEN").equals("fail_target") && RlSpec.outcome("LOW_HEALTH").equals("fail_danger") && RlSpec.outcome("MASKED").equals("fail_other")
                && RlSpec.outcome(null).equals("none") && RlSpec.outcome("NOT_RIPE").equals("fail_target") && RlSpec.outcome("NO_XP").equals("fail_items"), "outcome groups");
    }

    static void reward() {
        RlReward r = new RlReward(RlSpec.rewardDefaults(), RlSpec.scenario("wood"));
        r.add("1", "time", 20);
        r.add("1", "stock.value", 0.2);
        r.add("2", "death", 1);
        r.add("1", "perimeter.kill", 3);   // the wood has no perimeter: nothing
        r.add("1", "goal", 0);
        JsonObject comp = new JsonObject();
        double t1 = r.take("1", comp);
        check(Math.abs(t1 - (-0.02 + 0.1)) < 1e-9, "agent 1's reward: " + t1);
        check(comp.has("time") && comp.has("stock") && !comp.has("perimeter") && Math.abs(comp.get("stock").getAsDouble() - 0.1) < 1e-9, "its components: " + comp);
        check(r.take("1", null) == 0, "taken once");
        check(Math.abs(r.take("2", null) + 1) < 1e-9, "a death -1");
        check(Math.abs(r.total - (-0.92)) < 1e-9 && r.episodeJson().get("death").getAsDouble() == -1, "the episode's sum by component");
        RlReward waves = new RlReward(RlSpec.rewardDefaults(), RlSpec.scenario("waves:easy"));
        check(waves.add("1", "perimeter.kill", 1) > 0 && waves.add("1", "safety.wall", 0.5) > 0 && waves.add("1", "mine.iron", 1) == 0, "the waves pay the perimeter and safety, not the mine");
        RlReward pick = new RlReward(RlSpec.rewardDefaults(), RlSpec.scenario("pickaxe"));
        check(pick.add("1", "safety.moat", 0.5) == 0 && pick.add("1", "safety.wall", 1) == 0 && pick.add("1", "equipment.pickaxe", 0.25) > 0, "the pickaxe pays no moat, no wall");
        check(pick.add("1", "equipment.weapon", 0.25) == 0 && new RlReward(RlSpec.rewardDefaults(), RlSpec.scenario("sword")).add("1", "equipment.weapon", 0.25) > 0,
                "the pickaxe pays its pickaxe, not a sword; the sword its sword");
        RlReward ach = new RlReward(RlSpec.rewardDefaults(), RlSpec.scenario("achievements"));
        check(Math.abs(ach.add("1", "achievement.new", 1) - 0.6) < 1e-9, "the achievements' scenario doubles them");
        boolean threw = false;
        try { r.add("1", "nonsense", 1); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "an unknown weight is an error");
        Map<String, Double> w = RlSpec.rewardWeights(new JsonParser().parse("{\"weights\":{\"noop\":-3,\"time\":0}}").getAsJsonObject());
        check(w.get("noop") == -3 && w.get("time") == 0 && w.get("goal") == 5, "a weights file over the defaults");
        check(RlSpec.component("mine.iron").equals("mine") && RlSpec.component("noop").equals("noop"), "a weight's component");
        Set<String> comps = new HashSet<String>();
        for (String k : RlSpec.rewardDefaults().keySet()) comps.add(RlSpec.component(k));
        for (String s : RlSpec.SCALES) check(comps.contains(s), "the scale " + s + " has weights");
        for (String k : new String[] { "tier_up", "new_item", "gathered.log", "built.wall", "ate" }) check(!RlSpec.rewardDefaults().containsKey(k), "contract 1's " + k + " is gone (scales instead)");
    }

    static void scenarios() {
        for (String s : RlSpec.available()) {
            RlSpec.Scenario x = RlSpec.scenario(s);
            check(x.seconds > 0 && !x.goal.isEmpty(), "scenario " + s);
            for (String k : x.scales.keySet()) check(RlSpec.isScale(RlSpec.component(k)) && (k.indexOf('.') < 0 || RlSpec.rewardDefaults().containsKey(k)), "scenario " + s + "'s scale " + k);
        }
        check(RlSpec.available().size() == 17, "17 scenarios: " + RlSpec.available());
        check(RlSpec.scenario("siege") == null && RlSpec.scenario("nonsense") == null, "siege and the unknown are not there");
        check(RlSpec.scenario("waves:normal").waves.equals("normal") && RlSpec.scenario("waves:easy").defense && RlSpec.scenario("wood").waves == null, "the waves' levels");
        check(RlSpec.scenario("wood").scale("safety") == 0 && RlSpec.scenario("pickaxe").scale("safety") == 0 && RlSpec.scenario("fortify").scale("safety") == 1, "the wall pays only where it is the work");
        check(RlSpec.scenario("farm").hunger && RlSpec.scenario("mine_trip").hunger && !RlSpec.scenario("wood").hunger, "hunger where food is the matter");
        check(RlSpec.scenario("enchant").xpLevels > 0 && RlSpec.scenario("diamond").crewGoal, "the enchanting's levels, the diamond the crew's");
        check(RlSpec.kit("stone_pickaxe 1, planks 16,stick").size() == 3 && RlSpec.kit("stone_pickaxe 1, planks 16,stick").get(2)[1].equals("1"), "a kit read");
    }

    static void achievements() {
        check(RlAchievements.ALL.size() == 33, "1.7.10 has 33 achievements: " + RlAchievements.ALL.size());
        check(RlAchievements.supported().size() == 18, "18 an agent can get: " + RlAchievements.supported());
        for (RlAchievements.Ach a : RlAchievements.ALL.values()) check(a.parent == null || RlAchievements.ALL.containsKey(a.parent), "the parent of " + a.id);
        check(RlAchievements.depth("acquireIron") == 5 && RlAchievements.depth("openInventory") == 0 && RlAchievements.ALL.get("spawnWither").parent.equals("theEnd2"), "the tree as the game's");
        Set<String> got = new HashSet<String>();
        check(RlAchievements.available(got).size() == 1 && RlAchievements.available(got).contains("openInventory"), "at first only the root");
        check(!RlAchievements.canGet(got, "mineWood"), "no child before its parent");
        got.add("openInventory");
        got.add("mineWood");
        got.add("buildWorkBench");
        check(RlAchievements.available(got).containsAll(java.util.Arrays.asList("buildPickaxe", "buildHoe", "buildSword")) && !RlAchievements.available(got).contains("bakeCake"),
                "the next ones, the unsupported never: " + RlAchievements.available(got));
        for (String s : RlAchievements.supported()) {
            String p = RlAchievements.ALL.get(s).parent;
            check(p == null || RlAchievements.ALL.get(p).supported, "a supported one's parent is supported: " + s);
        }
    }

    /** the scales' measures: the change of a value, a baseline unpaid, the renewable only when used, a trip, the perimeter, the stall */
    static void score() {
        RlScore.Track t = new RlScore.Track();
        check(t.delta("a", 5) == 0 && t.delta("a", 7) == 2 && t.delta("a", 4) == -3, "a measure pays its change, the first value is the baseline");
        check(t.rise("h", 0) == 0 && t.rise("h", 1) == 1 && t.rise("h", 0) == 0 && t.rise("h", 1) == 0 && t.rise("h", 2) == 1, "a rise pays a new best only");
        double[] e = RlScore.equipment(6, 2, 3, true, 5);
        check(e[0] == 0.3 && e[1] == 0.5 && e[2] == 0.75 && e[3] == 1 && RlScore.equipment(0, 0, 0, true, 2)[3] == 0, "equipment's parts");
        Map<String, Double> st = new HashMap<String, Double>();
        st.put("log", 1000.0);
        double capped = RlScore.stock(st);
        st.put("log", 32.0);
        check(capped == RlScore.stock(st) && capped == 1.0, "the stock is capped: hoarding past the cap pays nothing");
        RlScore.Sustain su = new RlScore.Sustain();
        su.add(0, RlScore.FOOD_RENEW, 10);
        check(su.pay()[0] == 0, "a farm's harvest nobody eats pays nothing");
        su.add(5, RlScore.FOOD_EATEN, 4);
        check(su.pay()[0] == 4, "eaten: the renewable that fed pays");
        su.add(10, RlScore.FOOD_EATEN, 20);
        check(su.pay()[0] == 6, "no more than the renewable got");
        RlScore.Sustain hunt = new RlScore.Sustain();
        hunt.add(0, RlScore.FOOD_EATEN, 30);
        check(hunt.pay()[0] == 0, "eating what is not grown pays nothing");
        check(su.recent(10, 120)[1] == 24 && su.recent(200, 120)[1] == 0, "the window's sums");
        check(RlScore.sources(16, 2, 4) == 3 && RlScore.sources(8, 1, 0) == 1, "the sources kept");
        check(RlScore.horizon(70) == 0 && RlScore.horizon(43) == 1 && RlScore.horizon(14) == 2 && RlScore.atHorizon(38) && !RlScore.atHorizon(25), "the horizons");
        RlScore.Trip tr = new RlScore.Trip();
        check(!tr.update(0, false, 0) && !tr.update(1, true, 0) && !tr.update(1, true, 2) && tr.update(0, false, 0) && tr.done == 1, "a trip down, ore, up: done");
        check(!tr.update(1, true, 0) && !tr.update(0, false, 0) && tr.done == 1, "down and up with no ore: no trip");
        RlScore.Perimeter pm = new RlScore.Perimeter();
        double[] s1 = pm.second(java.util.Arrays.asList(1, 2), 1);
        double[] s2 = pm.second(java.util.Arrays.asList(1), 1);
        check(s1[0] == 2 && s1[1] == 2 && s2[0] == 1 && s2[1] == 0 && pm.mobSeconds == 3, "mob-seconds, a breach counted once");
        check(!pm.waveEnd() && pm.waveEnd() && pm.clean == 1, "a wave with a breach is not clean, one without is");
        RlScore.Stall sl = new RlScore.Stall();
        check(!sl.check("1", 0) && !sl.check("1", 59) && sl.check("1", 60) && !sl.check("1", 61), "a stall a window without progress");
        sl.progress("1", 100);
        check(!sl.check("1", 150) && sl.check("1", 160), "progress starts the window again");
        check(RlScore.progress("mine") && RlScore.progress("goal") && !RlScore.progress("time") && !RlScore.progress("skill_fail"), "what counts as progress");
    }

    static int idx(String name) {
        for (int i = 0; i < RlSpec.FEATURES.size(); i++) if (RlSpec.FEATURES.get(i).name.equals(name)) return i;
        throw new IllegalArgumentException(name);
    }
}
