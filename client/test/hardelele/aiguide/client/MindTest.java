package hardelele.aiguide.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The agent's mind without the game: the model's answers read (tool calls, calls written as text, no call), the checks
 * of a call, the limits of a goal, the state the model sees, the /цель line; and a stone pickaxe from nothing on a
 * stub model against a small simulated world: the steps go without a chest or a furnace, and a step's failure reaches
 * the model with its reason (the stub answers only when the reason is in what it was asked).
 */
public final class MindTest {
    static int checks;

    static void ok(boolean b, String what) {
        checks++;
        if (!b) throw new AssertionError(what);
    }

    /** an API answer with one tool call (arguments as the JSON string the API sends) and its usage */
    static String answer(String tool, String args, int prompt, int completion, int cached) {
        JsonObject f = new JsonObject();
        f.addProperty("name", tool);
        f.addProperty("arguments", args);
        JsonObject call = new JsonObject();
        call.addProperty("id", "call_" + tool);
        call.addProperty("type", "function");
        call.add("function", f);
        JsonArray calls = new JsonArray();
        calls.add(call);
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "assistant");
        msg.add("content", com.google.gson.JsonNull.INSTANCE);
        msg.add("tool_calls", calls);
        JsonObject ch = new JsonObject();
        ch.add("message", msg);
        JsonArray choices = new JsonArray();
        choices.add(ch);
        JsonObject r = new JsonObject();
        r.add("choices", choices);
        JsonObject u = new JsonObject();
        u.addProperty("prompt_tokens", prompt);
        u.addProperty("completion_tokens", completion);
        u.addProperty("prompt_cache_hit_tokens", cached);
        r.add("usage", u);
        return r.toString();
    }

    static String text(String content) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":" + new com.google.gson.JsonPrimitive(content) + "}}]}";
    }

    // ---- a small world: a tree near, stone under the ground, the game's day-one recipes -------------------------

    static final class Sim {
        final Map<String, Integer> bag = new LinkedHashMap<String, Integer>();
        boolean table, stoneSeen;
        int trees = 3;
        final List<String> made = new ArrayList<String>();   // every item crafted or put down

        int n(String k) { return bag.containsKey(k) ? bag.get(k) : 0; }
        void add(String k, int d) { int v = n(k) + d; if (v <= 0) bag.remove(k); else bag.put(k, v); }

        MindState state(Mind.Goal g) {
            MindState s = new MindState();
            s.agent = "Агент 2";
            s.goal = g.text;
            s.want = g.want();
            s.have = n(g.item);
            s.x = 10; s.y = 64; s.z = -3;
            s.on = "grass";
            s.time = "day, 08:30 (dusk in 9 min)";
            s.bag.putAll(bag);
            if (trees > 0) s.near.add("tree (log) at 12 64 -1, 3 m");
            if (stoneSeen) s.near.add("stone (gives cobblestone): 9 blocks seen, nearest at 11 61 -2, 3 m");
            if (table) s.stations.add("crafting_table at 11 64 -3, 1 m (the crew's)");
            s.player = "Player at 8 64 -5, 3 m";
            return s;
        }

        /** a recipe: { out count, grid 2 or 3, ingredient, count, ... } */
        static final Map<String, Object[]> R = new LinkedHashMap<String, Object[]>();
        static {
            R.put("planks", new Object[] { 4, 2, "log", 1 });
            R.put("stick", new Object[] { 4, 2, "planks", 2 });
            R.put("crafting_table", new Object[] { 1, 2, "planks", 4 });
            R.put("wooden_pickaxe", new Object[] { 1, 3, "planks", 3, "stick", 2 });
            R.put("stone_pickaxe", new Object[] { 1, 3, "cobblestone", 3, "stick", 2 });
            R.put("furnace", new Object[] { 1, 3, "cobblestone", 8 });
            R.put("chest", new Object[] { 1, 3, "planks", 8 });
        }

        String takes(Object[] r) {
            StringBuilder b = new StringBuilder();
            for (int i = 2; i < r.length; i += 2) b.append(b.length() > 0 ? ", " : "").append(r[i + 1]).append(' ').append(r[i]);
            return b.toString();
        }

        boolean can(Object[] r) {
            for (int i = 2; i < r.length; i += 2) if (n((String) r[i]) < (Integer) r[i + 1]) return false;
            return true;
        }

        /** a skill done: { result, detail } as MindSkills says it */
        String[] run(Mind.Call c) {
            String t = c.tool;
            if (t.equals("look_around")) { stoneSeen = true; return new String[] { "OK", "seen near now: " + trees + " trees, 9 stone, 0 ore blocks" }; }
            if (t.equals("chop_tree")) {
                if (trees == 0) return new String[] { "NO_TREE_SEEN", "no tree in the crew's memory near here" };
                trees--;
                add("log", 5);
                return new String[] { "OK", "" };
            }
            if (t.equals("mine")) {
                if (n("wooden_pickaxe") == 0 && n("stone_pickaxe") == 0) return new String[] { "NO_TOOL", "stone gives nothing without a pickaxe (wooden_pickaxe or better); nothing such in the bag" };
                String note = stoneSeen ? "" : "no stone seen: dug steps down from 10 64 -3";
                stoneSeen = true;
                add("cobblestone", c.num("count", 1));
                return new String[] { "OK", note };
            }
            if (t.equals("craft")) {
                String item = c.arg("item");
                Object[] r = R.get(item);
                if (r == null) return new String[] { "NO_RECIPE", item + " is not crafted" };
                if (!can(r)) return new String[] { "MISSING", "one craft of " + item + " (makes " + r[0] + ") takes " + takes(r) };
                String note = "";
                if ((Integer) r[1] == 3 && !table) {
                    if (n("crafting_table") == 0) return new String[] { "NO_TABLE", "the recipe of " + item + " is 3x3: it needs a crafting table; none known near (24 blocks) and none in the bag" };
                    add("crafting_table", -1);
                    table = true;
                    made.add("crafting_table (put down)");
                    note = "put its crafting_table at 11 64 -3; ";
                }
                int want = c.num("count", 1), got = 0;
                while (got < want && can(r)) {
                    for (int i = 2; i < r.length; i += 2) add((String) r[i], -(Integer) r[i + 1]);
                    add(item, (Integer) r[0]);
                    got += (Integer) r[0];
                    made.add(item);
                }
                return new String[] { "OK", note + "made " + got + " " + item };
            }
            if (t.equals("how_to_make")) {
                Object[] r = R.get(c.arg("item"));
                return new String[] { "OK", r == null ? "no recipe" : c.arg("item") + " x" + r[0] + " by " + ((Integer) r[1] == 2 ? "2x2 (in the hands)" : "3x3 (at a crafting table)") + ": one craft takes " + takes(r) };
            }
            return new String[] { "BLOCKED", "the simulation does not do " + t };
        }
    }

    /** the stub model: each answer is given only when the request has what it expects (a failure's reason, a fact of the state) */
    static final class Script implements Mind.Model {
        final List<String[]> steps = new ArrayList<String[]>();   // { what the request must hold, tool, arguments }
        final List<String> asked = new ArrayList<String>();
        int i;
        Script then(String expect, String tool, String args) { steps.add(new String[] { expect, tool, args }); return this; }
        public String complete(String body) {
            JsonObject b = new JsonParser().parse(body).getAsJsonObject();
            ok(b.getAsJsonArray("tools").size() == Mind.SKILLS.length, "every request declares the tools");
            String user = b.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
            asked.add(user);
            if (i >= steps.size()) throw new AssertionError("the model was asked more than the script has:\n" + user);
            String[] s = steps.get(i++);
            if (s[0] != null && !user.contains(s[0])) throw new AssertionError("step " + i + " (" + s[1] + "): the request lacks '" + s[0] + "':\n" + user);
            return answer(s[1], s[2], 2200, 60, 1600);
        }
    }

    static void stonePickaxe() throws Exception {
        Mind.Goal g = new Mind.Goal("сделай себе каменную кирку", "stone_pickaxe", 1);
        g.members.add("Агент 2");
        Mind m = new Mind("Агент 2", g, new Mind.Limits(), 0);
        Sim sim = new Sim();
        Script model = new Script()
                .then("BAG: empty", "craft", "{\"item\":\"wooden_pickaxe\",\"count\":1,\"why\":\"try with what I have\"}")
                .then("MISSING: one craft of wooden_pickaxe", "chop_tree", "{\"at\":\"near\",\"why\":\"need logs for planks\"}")
                .then("+5 log", "craft", "{\"item\":\"planks\",\"count\":12,\"why\":\"planks for a table, sticks and a pickaxe\"}")
                .then("made 12 planks", "craft", "{\"item\":\"stick\",\"count\":4,\"why\":\"sticks for both pickaxes\"}")
                .then("made 4 stick", "craft", "{\"item\":\"wooden_pickaxe\",\"count\":1,\"why\":\"to dig stone\"}")
                .then("NO_TABLE", "craft", "{\"item\":\"crafting_table\",\"count\":1,\"why\":\"the pickaxe is 3x3\"}")
                .then("crafting_table x1", "craft", "{\"item\":\"wooden_pickaxe\",\"count\":1,\"why\":\"now with my own table\"}")
                .then("put its crafting_table", "done", "{\"summary\":\"кирка готова\"}")   // too early: not the goal's item
                .then("NOT_DONE", "mine", "{\"block\":\"stone\",\"count\":3,\"why\":\"3 cobblestone for the stone pickaxe\"}")
                .then("+3 cobblestone", "craft", "{\"item\":\"stone_pickaxe\",\"count\":1,\"why\":\"the goal\"}")
                .then("you have 1", "done", "{\"summary\":\"Каменная кирка у меня\"}");
        int guard = 0;
        while (!m.over() && guard++ < 40) {
            ok(m.limit(1000) == null, "no limit hit on the way");
            MindState st = sim.state(g);
            String body = m.request(st, 1000);
            ok(m.status.equals("THINKING"), "thinking while asked");
            Mind.Call c = m.parse(model.complete(body), body.length());
            String bad = m.check(c, st);
            if (bad != null) { m.record(c, "BAD_CALL", bad, "", 0); continue; }
            if (c.tool.equals("done")) { m.record(c, "OK", "the goal is reached", "", 0); m.end("DONE", c.arg("summary")); break; }
            Map<String, Integer> before = new LinkedHashMap<String, Integer>(sim.bag);
            String[] out = sim.run(c);
            m.record(c, out[0], out[1], diff(before, sim.bag), 3);
        }
        ok(m.status.equals("DONE") && g.state.equals("DONE"), "the goal is done: " + m.status + " " + m.endReason);
        ok(sim.n("stone_pickaxe") == 1, "a stone pickaxe in the bag: " + sim.bag);
        ok(model.i == model.steps.size(), "every scripted answer was used: " + model.i);
        for (Mind.Step s : m.steps) {
            ok(!s.call.tool.equals("smelt") && !s.call.tool.equals("put") && !s.call.tool.equals("take"), "no chest or furnace step: " + s.line());
            ok(!s.call.arg("item").equals("chest") && !s.call.arg("item").equals("furnace"), "nothing made for a chest or a furnace: " + s.line());
        }
        for (String x : sim.made) ok(!x.equals("chest") && !x.equals("furnace"), "made no chest or furnace: " + sim.made);
        ok(m.requests == 11 && m.promptTokens == 11 * 2200 && m.completionTokens == 11 * 60 && m.cachedTokens == 11 * 1600, "tokens counted: " + m.requests + " " + m.promptTokens + " " + m.cachedTokens);
        // the failure's reason and words were in what the model read next
        ok(model.asked.get(5).contains("craft wooden_pickaxe 1 -> NO_TABLE: the recipe of wooden_pickaxe is 3x3"), "the reason of a failed step reaches the model:\n" + model.asked.get(5));
        ok(model.asked.get(8).contains("BAD_CALL: NOT_DONE: the goal is 1 stone_pickaxe, you have 0"), "an early done is refused with why");
        ok(model.asked.get(0).contains("GOAL CHECK: wanted stone_pickaxe 1, you have 0"), "the goal's check in the state");
        // one line of the log: state, call, outcome, tokens
        Mind.Step last = m.steps.peekLast();
        JsonObject line = m.logLine(1234, sim.state(g), last.call, last, new int[] { 2200, 60, 1600 }, 900);
        ok(line.get("agent").getAsString().equals("Агент 2") && line.has("state") && line.getAsJsonObject("call").get("tool").getAsString().equals("done")
                && line.getAsJsonObject("tokens").get("cached").getAsInt() == 1600 && line.get("prompt").getAsString().contains("YOUR LAST STEPS"), "a log line: " + line);
        System.out.println("stone pickaxe from nothing: " + m.steps.size() + " steps, " + m.requests + " requests, " + m.promptTokens + "+" + m.completionTokens + " tokens");
        for (Mind.Step s : m.steps) System.out.println("  " + s.line());
    }

    static String diff(Map<String, Integer> before, Map<String, Integer> after) {
        StringBuilder b = new StringBuilder();
        java.util.Set<String> keys = new java.util.LinkedHashSet<String>(before.keySet());
        keys.addAll(after.keySet());
        for (String k : keys) {
            int d = (after.containsKey(k) ? after.get(k) : 0) - (before.containsKey(k) ? before.get(k) : 0);
            if (d != 0) b.append(b.length() > 0 ? ", " : "").append(d > 0 ? "+" : "").append(d).append(' ').append(k);
        }
        return b.toString();
    }

    static Mind fresh() {
        Mind.Goal g = new Mind.Goal("срубить деревья у дома", null, 0);
        g.members.add("Агент 3");
        return new Mind("Агент 3", g, new Mind.Limits(), 0);
    }

    static void parsing() {
        Mind m = fresh();
        Mind.Call c = m.parse(answer("chop_tree", "{\"at\":\"near\",\"why\":\"wood\"}", 100, 10, 40), 400);
        ok(c.error == null && c.tool.equals("chop_tree") && c.arg("at").equals("near") && c.why.equals("wood"), "a tool call read");
        ok(m.promptTokens == 100 && m.completionTokens == 10 && m.cachedTokens == 40, "the usage counted");
        // arguments as an object, two calls (the first done), the OpenAI way of cached tokens
        String two = "{\"choices\":[{\"message\":{\"content\":\"\",\"tool_calls\":[{\"id\":\"a\",\"function\":{\"name\":\"mine\",\"arguments\":{\"block\":\"stone\",\"count\":\"3\"}}},"
                + "{\"id\":\"b\",\"function\":{\"name\":\"craft\",\"arguments\":\"{}\"}}]}}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":1,\"prompt_tokens_details\":{\"cached_tokens\":2}}}";
        c = m.parse(two, 10);
        ok(c.tool.equals("mine") && c.num("count", 0) == 3 && c.text.contains("only the first"), "two calls: the first, said so");
        ok(m.cachedTokens == 42, "cached tokens the OpenAI way");
        // a call written as text
        c = m.parse(text("I will chop. {\"name\": \"chop_tree\", \"arguments\": {\"at\": \"12 64 -1\", \"why\": \"logs\"}}"), 10);
        ok(c.error == null && c.tool.equals("chop_tree") && c.arg("at").equals("12 64 -1"), "a call written as text");
        int before = m.promptTokens;
        c = m.parse(text("Let me think about it."), 400);
        ok(c.error != null && c.error.startsWith("NO_CALL"), "no call: an error for the model");
        ok(m.promptTokens == before + 100, "no usage: the tokens estimated from the length");
        c = m.parse("<html>502</html>", 10);
        ok(c.error != null && c.error.startsWith("NO_ANSWER"), "an unreadable answer");
        c = m.parse(answer("craft", "{not json", 1, 1, 0), 10);
        ok(c.error != null && c.error.startsWith("BAD_ARGS"), "arguments that are not JSON");
    }

    static void checking() {
        Mind m = fresh();
        MindState st = new MindState();
        ok(m.check(m.parse(answer("dance", "{}", 1, 1, 0), 1), st).startsWith("UNKNOWN_TOOL"), "an unknown tool");
        ok(m.check(m.parse(answer("craft", "{\"count\":2,\"why\":\"x\"}", 1, 1, 0), 1), st).startsWith("MISSING_ARG: craft needs item"), "a missing argument");
        ok(m.check(m.parse(answer("wait", "{\"seconds\":600}", 1, 1, 0), 1), st).startsWith("BAD_ARG"), "a wait too long");
        ok(m.check(m.parse(answer("done", "{\"summary\":\"всё\"}", 1, 1, 0), 1), st) == null, "done of a goal with no item: as the model says");
        ok(m.check(m.parse(answer("chop_tree", "{\"at\":\"near\",\"why\":\"x\"}", 1, 1, 0), 1), st) == null, "a good call passes");
        for (int i = 0; i < m.lim.says; i++) m.record(m.parse(answer("say", "{\"text\":\"привет\",\"why\":\"x\"}", 1, 1, 0), 1), "OK", "said", "", 0);
        ok(m.check(m.parse(answer("say", "{\"text\":\"ещё\",\"why\":\"x\"}", 1, 1, 0), 1), st).startsWith("SAID_ENOUGH"), "said enough");
        JsonArray tools = Mind.tools();
        ok(tools.size() == Mind.SKILLS.length, "a tool for every skill");
        for (JsonElement e : tools) {
            JsonObject f = e.getAsJsonObject().getAsJsonObject("function");
            String n = f.get("name").getAsString();
            boolean why = f.getAsJsonObject("parameters").getAsJsonArray("required").toString().contains("\"why\"");
            ok(why == !(n.equals("done") || n.equals("give_up")), n + ": why required for a skill");
            if (n.equals("smelt"))
                ok(f.getAsJsonObject("parameters").getAsJsonObject("properties").has("fuel") && !f.getAsJsonObject("parameters").getAsJsonArray("required").toString().contains("fuel"), "smelt's fuel is optional");
        }
    }

    static void limits() {
        Mind.Limits l = new Mind.Limits();
        l.steps = 3;
        Mind.Goal g = new Mind.Goal("x", null, 0);
        Mind m = new Mind("Агент 2", g, l, 0);
        Mind.Call c = m.parse(answer("look_around", "{\"why\":\"x\"}", 10, 1, 0), 1);
        for (int i = 0; i < 3; i++) m.record(c, "OK", "", "", 1);
        ok(m.limit(0) != null && m.limit(0).startsWith("step limit"), "the step limit");
        m = new Mind("Агент 2", new Mind.Goal("x", null, 0), new Mind.Limits(), 0);
        ok(m.limit(31 * 60 * 1000L).startsWith("time limit"), "the time limit");
        m.parse(answer("look_around", "{}", 250000, 5, 0), 1);
        ok(m.limit(0).startsWith("token limit"), "the token limit");
        // the same failing step again and again: the goal ends with it
        m = new Mind("Агент 2", g = new Mind.Goal("x", null, 0), new Mind.Limits(), 0);
        Mind.Call craft = m.parse(answer("craft", "{\"item\":\"wooden_pickaxe\",\"count\":1,\"why\":\"x\"}", 1, 1, 0), 1);
        for (int i = 0; i < 3; i++) m.record(craft, "NO_TABLE", "no table", "", 1);
        ok(!m.over() && m.repeated() == 3, "three in a row: told, not over yet");
        MindState st = new MindState();
        m.request(st, 0);
        ok(st.render().contains("failed the same way 3 times"), "the state says the step keeps failing");
        m.record(craft, "NO_TABLE", "no table", "", 1);
        ok(m.over() && m.status.equals("GAVE_UP") && g.state.equals("GAVE_UP") && m.endReason.startsWith("the same step failed 4 times"), "the fourth ends the goal: " + m.endReason);
        // on the net (Limits.policy): the same failure does not end the goal, only its time and steps do
        Mind.Limits pl = new Mind.Limits().defense().policy();
        ok(pl.byNet && pl.steps == 3 * 3600 && pl.seconds == 3 * 3600, "on the net: a step a second of the goal's time (" + pl.steps + " steps)");
        m = new Mind("Агент 2", g = new Mind.Goal("защищай базу от волн", null, 0), pl, 0);
        Mind.Call chop = m.parse(answer("chop_tree", "{\"target\":\"near\",\"why\":\"policy\"}", 1, 1, 0), 1);
        for (int i = 0; i < 40; i++) m.record(chop, "NO_PATH", "no spot beside the tree", "", 0);
        ok(!m.over() && g.open() && m.repeated() == 40 && m.limit(0) == null, "on the net: 40 same failures, the goal goes on (" + m.status + ")");
        ok(m.limit(3 * 3600 * 1000L + 1000) != null && m.limit(3 * 3600 * 1000L + 1000).startsWith("time limit"), "on the net: the goal's time still ends it");
        Mind.Limits pw = new Mind.Limits().policy();
        m = new Mind("Агент 2", new Mind.Goal("x", null, 0), pw, 0);
        for (int i = 0; i < pw.steps; i++) m.record(chop, "NO_PATH", "x", "", 0);
        ok(!m.over() && m.limit(0) != null && m.limit(0).startsWith("step limit (1800"), "on the net: the goal's steps still end it: " + m.limit(0));
        // the mind share: who drove the bodies, a line a window
        MindShare sh = new MindShare();
        for (int i = 0; i < 90; i++) sh.tick("Агент 1", MindShare.NET);
        for (int i = 0; i < 10; i++) sh.tick("Агент 1", MindShare.FIGHT);
        for (int i = 0; i < 50; i++) sh.tick("Агент 2", MindShare.GUARD);
        for (int i = 0; i < 50; i++) sh.tick("Агент 2", MindShare.NET);
        sh.decided("Агент 1");
        sh.guarded("Агент 2");
        String line = sh.line();
        ok(line != null && line.contains("crew net 70% self-defence 5% reflex 0% guard 25% other 0%") && line.contains("Агент 1: net 90% self-defence 10% (decisions 1, guard cards 0")
                && line.contains("Агент 2: net 50% guard 50% (decisions 0, guard cards 1"), "the mind share's line: " + line);
        ok(sh.line() == null, "the window is reset after its line");
        // calls that are no calls, in a row
        m = new Mind("Агент 2", new Mind.Goal("x", null, 0), new Mind.Limits(), 0);
        for (int i = 0; i < m.lim.badCalls; i++) { Mind.Call b = m.parse(text("hmm"), 4); m.record(b, "BAD_CALL", b.error, "", 0); }
        ok(m.limit(0) != null && m.limit(0).contains("no valid step"), "bad calls in a row end the goal");
        // the recent steps only, numbered on
        m = new Mind("Агент 2", new Mind.Goal("x", null, 0), new Mind.Limits(), 0);
        for (int i = 0; i < 14; i++) m.record(m.parse(answer("wait", "{\"seconds\":" + (i + 1) + ",\"why\":\"x\"}", 1, 1, 0), 1), "OK", "", "", 1);
        st = new MindState();
        m.request(st, 0);
        String r = st.render();
        ok(st.recent.size() == 10 && r.contains("5. wait 5 -> OK") && !r.contains("4. wait 4") && r.contains("14. wait 14"), "the last ten steps, numbered on:\n" + r);
    }

    static void state() {
        MindState s = new MindState();
        s.agent = "Агент 2";
        s.goal = "сделай каменную кирку";
        s.want = "stone_pickaxe 1";
        s.have = 0;
        s.team = "Агент 3";
        s.others.add("Агент 3 at 1 64 1, goal \"сделай каменную кирку\", now chop_tree 12 64 -1");
        s.reserved.add("12 64 -1 (Агент 3)");
        s.events.add("reflex mob (hurt by Zombie, health 14)");
        String r = s.render();
        for (String part : new String[] { "GOAL: сделай каменную кирку", "GOAL CHECK: wanted stone_pickaxe 1, the crew has 0", "SHARED WITH: Агент 3", "BAG: empty",
                "SEEN NEAR (only what the crew has seen):", "nothing yet: look_around", "STATIONS: none known", "OTHERS:", "RESERVED BY OTHERS", "EVENTS SINCE YOUR LAST STEP",
                "reflex mob", "YOUR LAST STEPS: none yet", "LIMITS LEFT:", "Call one tool." })
            ok(r.contains(part), "the state has '" + part + "':\n" + r);
        ok(s.json().getAsJsonArray("reserved").size() == 1, "the state as JSON");
        ok(Mind.SYSTEM.contains("No chest, no furnace"), "the rules say: only what the goal needs");
    }

    static void command() {
        String[] c = Mind.command("/цель срубить деревья у дома");
        ok(c != null && c[0].equals("all") && c[1].equals("срубить деревья у дома"), "a goal for all");
        c = Mind.command("/цель 2,3 сделать каменную кирку");
        ok(c[0].equals("2,3") && c[1].equals("сделать каменную кирку"), "a goal for 2 and 3: " + c[0]);
        c = Mind.command("/Цель все: построй укрытие");
        ok(c[0].equals("all") && c[1].equals("построй укрытие"), "все: all");
        c = Mind.command("/goal 4 stop");
        ok(c[0].equals("4") && c[1].equals("stop"), "a stop for 4");
        c = Mind.command("/цель");
        ok(c != null && c[1].isEmpty(), "no words: the usage");
        c = Mind.command("/цель 3");
        ok(c[0].equals("all") && c[1].equals("3"), "a lone number is the words (nothing after it)");
        ok(Mind.command("/целься") == null && Mind.command("цель: кирка") == null && Mind.command(null) == null, "not a goal line");
        ok(java.util.Arrays.equals(Mind.want("stone_pickaxe 1"), new String[] { "stone_pickaxe", "1" }), "want: item count");
        ok(java.util.Arrays.equals(Mind.want("2 minecraft:stone_pickaxe"), new String[] { "stone_pickaxe", "2" }), "want: count item");
        ok(java.util.Arrays.equals(Mind.want("torch x16"), new String[] { "torch", "16" }), "want: item x count");
        ok(java.util.Arrays.equals(Mind.want("planks:2"), new String[] { "planks:2", "1" }), "want: a variant, one");
        ok(Mind.want("") == null && Mind.want("каменная кирка") == null, "want: none, or not an id");
    }

    public static void main(String[] args) throws Exception {
        parsing();
        checking();
        limits();
        state();
        command();
        stonePickaxe();
        System.out.println("MindTest PASS (" + checks + " checks)");
    }
}
