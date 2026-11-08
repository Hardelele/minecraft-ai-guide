package hardelele.aiguide.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An agent's mind (layer 3, the slow one), the part with no game in it. The model decides what the agent does next,
 * one skill at a time: it gets the agent's state (bag, health, time, what the crew has seen near, the others, its last
 * steps with the reasons they failed) and calls one tool; the skill runs to its end in the game (AgentMind, MindSkills)
 * and its outcome is the next state's last step. Asked only at a fork (a step ended, a goal came), never each tick.
 * Here: the tools the model sees, the request, the reading of its answer, the checks of a call, the limits of a goal.
 */
final class Mind {
    /** one chat completion: the request's JSON body in (messages, tools), the API's whole answer out */
    interface Model { String complete(String body) throws Exception; }

    /** what one goal may take (config [mind] of aiguide.cfg) */
    static final class Limits {
        int steps = 60, seconds = 30 * 60, tokens = 200000;
        int badCalls = 4;      // calls in a row that were not a valid tool call
        int sameFailure = 3;   // the same step failing with the same reason this many times in a row: the next one ends the goal
        int recent = 10;       // last steps shown to the model
        int says = 8;

        /** a goal of defence has no end of its own (the waves do not end): more steps and time than a goal of things, the tokens as set */
        Limits defense() {
            steps = Math.max(steps, 300);
            seconds = Math.max(seconds, 3 * 3600);
            return this;
        }
    }

    /** a goal: the words, optionally an item to have (structural: "stone_pickaxe 1"), alone or shared by a few agents */
    static final class Goal {
        final String text, item;
        final int count;
        final boolean defense;   // a goal of defence ("защищай базу"): the defence's tools and rules too
        final List<String> members = new ArrayList<String>();   // the agents sharing it (one: its own)
        volatile String state = "OPEN";   // OPEN, DONE, GAVE_UP
        volatile String summary = "";
        Goal(String text, String item, int count) { this.text = text; this.item = item; this.count = count; this.defense = Defense.isGoal(text); }
        boolean open() { return state.equals("OPEN"); }
        boolean shared() { return members.size() > 1; }
        String want() { return item == null ? "" : item + " " + count; }
    }

    /** a decision: the tool, its arguments, why; error when the answer was no valid call */
    static final class Call {
        String tool = "", why = "", id = "", text = "", error;
        JsonObject args = new JsonObject();
        String arg(String k) {
            JsonElement e = args.get(k);
            return e == null || e.isJsonNull() ? "" : e.isJsonPrimitive() ? e.getAsString().trim() : e.toString();
        }
        int num(String k, int def) {
            Matcher m = Pattern.compile("-?\\d+").matcher(arg(k));
            return m.find() ? Integer.parseInt(m.group()) : def;
        }
        /** the call in brief: "craft wooden_pickaxe 1" */
        String brief() {
            StringBuilder b = new StringBuilder(tool);
            for (java.util.Map.Entry<String, JsonElement> e : args.entrySet()) {
                if (e.getKey().equals("why") || e.getKey().equals("summary") || e.getKey().equals("reason") || e.getKey().equals("text")) continue;
                String v = e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : e.getValue().toString();
                if (!v.isEmpty()) b.append(' ').append(v);
            }
            return b.toString();
        }
    }

    /** a step done: the call, how it ended (OK or a reason), what it said, what changed in the bag, how long */
    static final class Step {
        final Call call;
        final String result, detail, changes;
        final double seconds;
        Step(Call call, String result, String detail, String changes, double seconds) {
            this.call = call; this.result = result; this.detail = detail == null ? "" : detail; this.changes = changes == null ? "" : changes; this.seconds = seconds;
        }
        boolean ok() { return result.equals("OK"); }
        String line() {
            return call.brief() + " -> " + result + (detail.isEmpty() ? "" : ": " + detail) + (changes.isEmpty() ? "" : "; " + changes)
                    + (seconds > 0 ? " (" + Math.round(seconds) + " s)" : "");
        }
    }

    // ---- the tools the model sees -----------------------------------------------------------------------------

    static final String[] SKILLS = { "look_around", "go_to", "chop_tree", "mine", "craft", "smelt", "place", "take", "put", "give", "say", "wait", "how_to_make", "done", "give_up" };
    /** the defence's, for a goal of defence only (DefenseSkills) */
    static final String[] DEFENSE = { "build_wall", "repair_wall", "dig_moat", "light_area", "make_weapon", "equip", "take_post", "hold_line", "guard_ally", "retreat", "eat" };

    static String[] skills(boolean defense) {
        if (!defense) return SKILLS;
        String[] s = new String[SKILLS.length + DEFENSE.length];
        System.arraycopy(SKILLS, 0, s, 0, SKILLS.length);
        System.arraycopy(DEFENSE, 0, s, SKILLS.length, DEFENSE.length);
        return s;
    }

    static JsonArray tools() { return tools(false); }

    static JsonArray tools(boolean defense) {
        JsonArray a = new JsonArray();
        a.add(tool("look_around", "Turn round for a few seconds: the crew's memory sees what is around (trees, stone, ores, stations). For when SEEN NEAR lacks what you need."));
        a.add(tool("go_to", "Walk to a place. Fails NO_PATH when there is no way on foot.",
                "target", "string", "'x y z' from the state, or: table, chest, furnace, player, agent N"));
        a.add(tool("chop_tree", "Chop one whole tree and pick its logs up (a log gives 4 planks). Fails NO_TREE_SEEN, NO_PATH.",
                "at", "string", "'near' (the nearest seen tree nobody reserved) or 'x y z' of a tree from the state"));
        a.add(tool("mine", "Dig blocks of a kind the crew has seen and pick up what they drop (stone drops cobblestone, coal_ore coal). Stone and ores need a "
                + "pickaxe (iron_ore a stone_pickaxe or better). With no stone seen it digs safe steps down to it. Never digs under its own feet. "
                + "Fails NO_TOOL, NOT_SEEN, BLOCKED.",
                "block", "string", "the block: stone, coal_ore, iron_ore, sand, dirt...", "count", "integer", "how many blocks (1-32)"));
        a.add(tool("craft", "Craft by the game's recipes from your bag. 2x2 recipes in the hands; 3x3 ones (tools, furnace, chest) at a crafting table: the "
                + "nearest known one, else your own from the bag put down beside you. Fails MISSING (says what one craft takes), NO_TABLE, NO_RECIPE.",
                "item", "string", "the item: planks, stick, crafting_table, wooden_pickaxe...", "count", "integer", "how many items wanted (not crafts)"));
        a.add(tool("smelt", "Smelt in a furnace: the nearest known one, else your own from the bag put down beside you. Fails NO_FURNACE, NO_FUEL, MISSING.",
                "item", "string", "what goes in (iron_ore, log, sand) or what you want out (iron_ingot, charcoal, glass)", "count", "integer", "how many",
                "fuel", "string", "optional: coal, planks, log... (empty: the best in the bag)?"));
        a.add(tool("place", "Put a block from your bag. Fails NO_ITEMS, OCCUPIED, NOT_VISIBLE.",
                "item", "string", "the block", "at", "string", "'near' (beside you) or 'x y z'"));
        a.add(tool("take", "Take things out of a chest. Fails NO_CHEST, NOT_IN_CHEST.",
                "chest", "string", "'crew', 'near' or 'x y z'", "item", "string", "the item", "count", "integer", "how many"));
        a.add(tool("put", "Put things from your bag into a chest. Fails NO_CHEST, CHEST_FULL, NO_ITEMS.",
                "chest", "string", "'crew', 'near' or 'x y z'", "item", "string", "the item, or all (everything but tools)", "count", "integer", "how many"));
        a.add(tool("give", "Walk to the player or another agent and hand things over. Fails NO_ITEMS, GONE, NO_PATH.",
                "to", "string", "player, or agent N", "item", "string", "the item", "count", "integer", "how many"));
        a.add(tool("say", "One short line to the player in the chat, in Russian. Only what they need to know.", "text", "string", "the line"));
        a.add(tool("wait", "Stand and wait (for the night to pass, for another agent). Ends early on danger.", "seconds", "integer", "1-120"));
        a.add(tool("how_to_make", "The game's recipes for an item: the grid (2x2 in hands or 3x3 at a table) and what one craft takes, or what smelts into it. "
                + "Takes a step but no game time.", "item", "string", "the item"));
        a.add(tool("done", "The goal is reached.", "summary", "string", "one short sentence for the player, in Russian"));
        a.add(tool("give_up", "The goal cannot be reached from here.", "reason", "string", "why, one short sentence for the player, in Russian"));
        if (defense) defenseTools(a);
        return a;
    }

    static void defenseTools(JsonArray a) {
        a.add(tool("build_wall", "Build wall blocks from your bag where the eyes see them go: a ring round the base (a gate left open, posts inside) or a line. "
                + "Builds up to 24 cells a call, bottom first, the cells others reserved left to them; call again for the rest. "
                + "Fails NO_BASE, NO_ITEMS (how many blocks it takes), ALL_TAKEN, DONE_ALREADY, BAD_ARG.",
                "shape", "string", "'around base' (optionally 'around base 8': the radius 3-24), or 'line x1 z1 x2 z2'",
                "height", "integer", "1-4 blocks (2 keeps zombies out)?", "material", "string", "cobblestone, planks, dirt... or 'any' (the most in the bag)?",
                "gate", "string", "the ring's gate side: north, south, east, west?"));
        a.add(tool("repair_wall", "Fill the holes of the planned wall (a creeper's, a dug block) from your bag. Fails NO_PERIMETER, NO_HOLES, NO_ITEMS."));
        a.add(tool("dig_moat", "Dig a trench two cells outside the ring wall (its gate's way left), up to 16 blocks a call. Fails NO_PERIMETER, DONE_ALREADY, NO_TOOL.",
                "depth", "integer", "1-2?"));
        a.add(tool("light_area", "Put torches from your bag on dark ground round the base (every 5 blocks), so mobs do not spawn there. Fails NO_TORCHES, NO_SPOT.",
                "radius", "integer", "2-24 blocks from the base?"));
        a.add(tool("make_weapon", "Craft a weapon by the game's recipes (as craft): sword (the best the bag allows: diamond, iron, stone, wooden), bow, arrows. "
                + "Fails as craft does (MISSING says what it takes), UNKNOWN_WEAPON.",
                "type", "string", "sword, bow or arrows", "count", "integer", "how many (arrows: 4 a craft)?"));
        a.add(tool("equip", "Take the best weapon into the hand, or put on the armour of the bag. Fails NO_WEAPON, NO_ARMOR.",
                "what", "string", "weapon, armor or best (both)"));
        a.add(tool("take_post", "Walk to a post of the perimeter and hold it (reserved: nobody else takes it). Fails NO_PERIMETER, UNKNOWN_POST, POST_TAKEN, NO_PATH.",
                "where", "string", "'auto' (the free post that falls to you), a post's name from PERIMETER (gate, north, north-east...) or 'x y z'"));
        a.add(tool("hold_line", "Fight at your post (or where you stand): the body picks its targets (nearest the base, those on a wounded ally, never a creeper "
                + "in melee), stays within radius of the post and inside the wall, falls back when hurt. Ends when the time is up, the wave is over, "
                + "or an event (a breach, a creeper at the wall). Fails LOW_HEALTH (it fell back), INTERRUPTED.",
                "radius", "integer", "1-8 blocks from the post it may go?", "seconds", "integer", "5-600?"));
        a.add(tool("guard_ally", "Keep by an ally and fight what comes at it (inside the wall when it is). Fails GONE, BAD_ARG, LOW_HEALTH, INTERRUPTED.",
                "ally", "string", "agent N, or player", "seconds", "integer", "5-600?"));
        a.add(tool("retreat", "Fall back inside: to the base's middle (home) or behind the wall. Fails NO_HOME, NO_PATH.",
                "where", "string", "home or wall?"));
        a.add(tool("eat", "Eat food of the bag to heal (bread, cooked meat, apples...). Fails NO_FOOD, FULL_HEALTH.", "count", "integer", "how many at most?"));
    }

    /** a function tool: name, description, then params as name, type, description (a description ending in '?' is optional); every skill but done/give_up says why */
    static JsonObject tool(String name, String description, String... params) {
        JsonObject f = new JsonObject(), p = new JsonObject(), props = new JsonObject();
        JsonArray req = new JsonArray();
        f.addProperty("name", name);
        f.addProperty("description", description);
        p.addProperty("type", "object");
        for (int i = 0; i + 2 < params.length; i += 3) {
            JsonObject s = new JsonObject();
            s.addProperty("type", params[i + 1]);
            boolean opt = params[i + 2].endsWith("?");
            s.addProperty("description", opt ? params[i + 2].substring(0, params[i + 2].length() - 1) : params[i + 2]);
            props.add(params[i], s);
            if (!opt) req.add(new JsonPrimitive(params[i]));
        }
        if (!name.equals("done") && !name.equals("give_up")) {
            JsonObject s = new JsonObject();
            s.addProperty("type", "string");
            s.addProperty("description", "why this step, one short sentence");
            props.add("why", s);
            req.add(new JsonPrimitive("why"));
        }
        p.add("properties", props);
        p.add("required", req);
        f.add("parameters", p);
        JsonObject t = new JsonObject();
        t.addProperty("type", "function");
        t.add("function", f);
        return t;
    }

    static final String SYSTEM = "You are the mind of one agent of a crew in Minecraft 1.7.10: a body like a player's, with a player's limits. "
            + "Each turn you call exactly ONE tool: a skill the body then does to its end. You get its outcome in YOUR LAST STEPS next turn.\n"
            + "Rules:\n"
            + "- You know only the state below: what the crew has seen. Use coordinates from it, or 'near'. Never invent places.\n"
            + "- Plan from the game's recipes (how_to_make when unsure). Logs give planks (4 a log) in the hands; sticks from planks; tools need a "
            + "crafting_table (3x3). Stone needs a wooden_pickaxe at least, iron ore a stone_pickaxe.\n"
            + "- Make only what the goal needs. No chest, no furnace, no house unless the goal asks for it or a step truly needs it "
            + "(a furnace only for smelting, a chest only to store things for others).\n"
            + "- A failed step says why (a reason code and words). Read it and change the plan: MISSING -> get what one craft takes; NO_TABLE -> "
            + "craft a crafting_table; NO_TOOL -> make the pickaxe; NO_TREE_SEEN / NOT_SEEN -> look_around or go_to somewhere else. Never repeat "
            + "a failed step unchanged.\n"
            + "- In a shared goal see OTHERS and RESERVED: take another tree, another part of the work; do not all do the same. Give things to "
            + "the one who needs them.\n"
            + "- Night is dangerous outside; the body's reflexes handle mobs and falls, not you.\n"
            + "- When the goal is reached call done; if it cannot be, give_up. say only rarely, in Russian, short.\n"
            + "- Items and blocks by game id without 'minecraft:' (log, planks, stick, crafting_table, wooden_pickaxe, cobblestone, stone_pickaxe, "
            + "coal, iron_ore, furnace, chest, torch); ':N' after an id is its variant (planks:2 is birch planks).";

    /**
     * The lessons of the epochs before (Lessons.prompt: the epoch and what each came from), for a goal of defence: set
     * once when an epoch begins and the same all through it (the API's cache keeps taking the system prompt); "" none.
     */
    static volatile String lessons = "";

    /** the system prompt: the rules; for a goal of defence the defence's and the epochs' lessons too */
    static String system(boolean defense) {
        if (!defense) return SYSTEM;
        String l = lessons;
        return SYSTEM + Defense.RULES + (l == null ? "" : l);
    }

    // ---- one mind: a goal's decisions --------------------------------------------------------------------------

    final String agent;
    final Goal goal;
    final Limits lim;
    final Deque<Step> steps = new ArrayDeque<Step>();   // every step of the goal, the last ones shown
    final long startedMs;
    int requests, promptTokens, completionTokens, cachedTokens, badInRow, says, errors;
    String status = "IDLE";   // IDLE, THINKING, ACTING, DONE, GAVE_UP
    String endReason = "";

    Mind(String agent, Goal goal, Limits lim, long nowMs) {
        this.agent = agent;
        this.goal = goal;
        this.lim = lim == null ? new Limits() : lim;
        this.startedMs = nowMs;
    }

    boolean over() { return status.equals("DONE") || status.equals("GAVE_UP"); }

    int tokens() { return promptTokens + completionTokens; }

    /** the goal ends (DONE or GAVE_UP) with the words why */
    void end(String how, String why) {
        status = how;
        endReason = why == null ? "" : why;
        if (goal.open()) { goal.state = how; goal.summary = endReason; }
    }

    /** null while the goal may go on, else why not (the limits are the config's) */
    String limit(long nowMs) {
        if (steps.size() >= lim.steps) return "step limit (" + lim.steps + " steps)";
        if (nowMs - startedMs > lim.seconds * 1000L) return "time limit (" + lim.seconds / 60 + " min)";
        if (tokens() >= lim.tokens) return "token limit (" + tokens() + " of " + lim.tokens + ")";
        if (badInRow >= lim.badCalls) return "the model gave no valid step " + badInRow + " times in a row";
        if (errors >= 3) return "the model is not reachable (" + errors + " errors)";
        return null;
    }

    /** the request's body: the rules and the tools (the same every time: the API's cache takes them), the state */
    String request(MindState st, long nowMs) {
        st.stepsLeft = lim.steps - steps.size();
        st.minutesLeft = (int) Math.max(0, (lim.seconds * 1000L - (nowMs - startedMs)) / 60000);
        st.tokensLeft = Math.max(0, lim.tokens - tokens());
        st.recent.clear();
        int skip = Math.max(0, steps.size() - lim.recent), i = 0;
        for (Step s : steps) if (i++ >= skip) st.recent.add(s);
        st.firstStep = skip + 1;
        st.repeated = repeated();
        JsonObject body = new JsonObject();
        JsonArray m = new JsonArray();
        m.add(message("system", system(goal.defense)));
        m.add(message("user", st.render()));
        body.add("messages", m);
        body.add("tools", tools(goal.defense));
        body.addProperty("tool_choice", "auto");
        requests++;
        status = "THINKING";
        return body.toString();
    }

    static JsonObject message(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    /** the model's answer: the first tool call (or one written as text); the tokens counted (estimated when the answer has no usage) */
    Call parse(String response, int requestChars) {
        Call c = new Call();
        JsonObject msg = null;
        try {
            JsonObject r = new JsonParser().parse(response).getAsJsonObject();
            if (r.has("usage") && r.get("usage").isJsonObject()) {
                JsonObject u = r.getAsJsonObject("usage");
                promptTokens += intOf(u, "prompt_tokens");
                completionTokens += intOf(u, "completion_tokens");
                int cached = intOf(u, "prompt_cache_hit_tokens");   // DeepSeek
                if (cached == 0 && u.has("prompt_tokens_details") && u.get("prompt_tokens_details").isJsonObject()) cached = intOf(u.getAsJsonObject("prompt_tokens_details"), "cached_tokens");
                cachedTokens += cached;
            } else {
                promptTokens += requestChars / 4;
                completionTokens += response.length() / 4;
            }
            msg = r.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");
        } catch (Exception e) {
            c.error = "NO_ANSWER: the model's answer could not be read (" + e.getClass().getSimpleName() + ")";
            return c;
        }
        c.text = msg.has("content") && !msg.get("content").isJsonNull() ? msg.get("content").getAsString().trim() : "";
        JsonArray tc = msg.has("tool_calls") && msg.get("tool_calls").isJsonArray() ? msg.getAsJsonArray("tool_calls") : null;
        if (tc != null && tc.size() > 0) {
            JsonObject t = tc.get(0).getAsJsonObject();
            JsonObject f = t.getAsJsonObject("function");
            c.id = t.has("id") ? t.get("id").getAsString() : "";
            c.tool = f.has("name") ? f.get("name").getAsString().trim() : "";
            if (c.tool.equals("heal")) c.tool = "eat";   // the same skill by its other name
            c.args = args(f.get("arguments"));
            if (c.args == null) { c.args = new JsonObject(); c.error = "BAD_ARGS: the arguments of " + c.tool + " are not a JSON object"; }
            if (tc.size() > 1) c.text = (c.text + " [" + tc.size() + " calls: only the first is done]").trim();
        } else if (!textCall(c)) {
            c.error = "NO_CALL: the answer called no tool; call exactly one";
        }
        c.why = c.arg("why");
        if (c.why.isEmpty()) c.why = c.arg("reason");
        if (c.why.isEmpty()) c.why = c.arg("summary");
        if (c.why.isEmpty() && !c.text.isEmpty()) c.why = c.text.length() > 160 ? c.text.substring(0, 160) : c.text;
        return c;
    }

    static int intOf(JsonObject o, String k) {
        try {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** arguments as the API gives them: a JSON string (the usual) or an object */
    static JsonObject args(JsonElement e) {
        try {
            if (e == null || e.isJsonNull()) return new JsonObject();
            if (e.isJsonObject()) return e.getAsJsonObject();
            String s = e.getAsString().trim();
            return s.isEmpty() ? new JsonObject() : new JsonParser().parse(s).getAsJsonObject();
        } catch (Exception x) {
            return null;
        }
    }

    /** a call written as text (some models do): a JSON object with name (or tool) and arguments */
    static boolean textCall(Call c) {
        String t = c.text;
        for (int i = t.indexOf('{'); i >= 0; i = t.indexOf('{', i + 1)) {
            int depth = 0;
            for (int j = i; j < t.length(); j++) {
                char ch = t.charAt(j);
                if (ch == '{') depth++;
                else if (ch == '}' && --depth == 0) {
                    try {
                        JsonObject o = new JsonParser().parse(t.substring(i, j + 1)).getAsJsonObject();
                        String name = o.has("name") ? o.get("name").getAsString() : o.has("tool") ? o.get("tool").getAsString() : null;
                        if (name != null) {
                            c.tool = name.trim().equals("heal") ? "eat" : name.trim();
                            JsonObject a = args(o.has("arguments") ? o.get("arguments") : o.get("parameters"));
                            c.args = a == null ? new JsonObject() : a;
                            return true;
                        }
                    } catch (Exception ignored) {
                        // not JSON: the next brace
                    }
                    break;
                }
            }
        }
        return false;
    }

    /** null when the call may be done, else why not (BAD_CALL for the model): unknown tool, missing arguments, a done that is not */
    String check(Call c, MindState st) {
        if (c.error != null) return c.error;
        boolean known = false;
        String[] all = skills(goal.defense);
        for (String s : all) if (s.equals(c.tool)) known = true;
        if (!known) return "UNKNOWN_TOOL: " + c.tool + "; the tools: " + String.join(", ", all);
        if (c.tool.equals("make_weapon") && c.arg("type").isEmpty()) return "MISSING_ARG: make_weapon needs type";
        if (c.tool.equals("guard_ally") && c.arg("ally").isEmpty()) return "MISSING_ARG: guard_ally needs ally";
        if (c.tool.equals("build_wall") && !c.arg("height").isEmpty() && (c.num("height", 2) < 1 || c.num("height", 2) > Defense.MAX_HEIGHT)) return "BAD_ARG: build_wall height 1-" + Defense.MAX_HEIGHT;
        if (c.tool.equals("hold_line") && !c.arg("radius").isEmpty() && (c.num("radius", 4) < 1 || c.num("radius", 4) > 8)) return "BAD_ARG: hold_line radius 1-8";
        if ((c.tool.equals("hold_line") || c.tool.equals("guard_ally")) && !c.arg("seconds").isEmpty() && (c.num("seconds", 60) < 5 || c.num("seconds", 60) > 600))
            return "BAD_ARG: " + c.tool + " seconds 5-600";
        if (c.tool.equals("light_area") && !c.arg("radius").isEmpty() && (c.num("radius", 12) < 2 || c.num("radius", 12) > Defense.MAX_RADIUS)) return "BAD_ARG: light_area radius 2-" + Defense.MAX_RADIUS;
        if (c.tool.equals("dig_moat") && !c.arg("depth").isEmpty() && (c.num("depth", 1) < 1 || c.num("depth", 1) > 2)) return "BAD_ARG: dig_moat depth 1-2";
        if (c.tool.equals("done") && goal.defense && st.waveOn) return "NOT_DONE: a wave is on; the defence goes on";
        String[] need = c.tool.equals("go_to") ? new String[] { "target" } : c.tool.equals("mine") ? new String[] { "block" }
                : c.tool.equals("craft") || c.tool.equals("smelt") || c.tool.equals("how_to_make") ? new String[] { "item" }
                : c.tool.equals("place") ? new String[] { "item" } : c.tool.equals("take") || c.tool.equals("put") ? new String[] { "item" }
                : c.tool.equals("give") ? new String[] { "to", "item" } : c.tool.equals("say") ? new String[] { "text" } : new String[0];
        for (String k : need) if (c.arg(k).isEmpty()) return "MISSING_ARG: " + c.tool + " needs " + k;
        if (c.tool.equals("wait") && (c.num("seconds", 0) < 1 || c.num("seconds", 0) > 120)) return "BAD_ARG: wait seconds 1-120";
        if ((c.tool.equals("mine") || c.tool.equals("craft") || c.tool.equals("smelt")) && c.num("count", 1) > 64) return "BAD_ARG: count at most 64";
        if (c.tool.equals("say")) {
            if (c.arg("text").length() > 200) return "BAD_ARG: say at most 200 characters";
            if (says >= lim.says) return "SAID_ENOUGH: " + says + " lines said for this goal; work instead";
        }
        if (c.tool.equals("done") && goal.item != null && st.have < goal.count)
            return "NOT_DONE: the goal is " + goal.count + " " + goal.item + ", " + (goal.shared() ? "the crew has " : "you have ") + st.have;
        return null;
    }

    /** a step ended: kept for the next state; the same failing step again and again ends the goal */
    Step record(Call c, String result, String detail, String changes, double seconds) {
        Step s = new Step(c, result, detail, changes, seconds);
        steps.add(s);
        if (result.startsWith("BAD_CALL")) badInRow++;
        else badInRow = 0;
        if (c.tool.equals("say") && result.equals("OK")) says++;
        status = "IDLE";
        if (repeated() > lim.sameFailure) end("GAVE_UP", "the same step failed " + repeated() + " times: " + s.line());
        return s;
    }

    /** how many times in a row the last step failed the same way (0 when it went well) */
    int repeated() {
        Step last = steps.peekLast();
        if (last == null || last.ok()) return 0;
        int n = 0;
        java.util.Iterator<Step> it = steps.descendingIterator();
        while (it.hasNext()) {
            Step s = it.next();
            if (s.ok() || !s.result.equals(last.result) || !s.call.brief().equals(last.call.brief())) break;
            n++;
        }
        return n;
    }

    /** one line of crew-minds.jsonl: the state the model saw, its call, the outcome, the tokens of that call */
    JsonObject logLine(long tick, MindState st, Call c, Step s, int[] tokens, long ms) {
        JsonObject o = new JsonObject();
        o.addProperty("t", tick);
        o.addProperty("agent", agent);
        o.addProperty("goal", goal.text);
        if (goal.item != null) o.addProperty("want", goal.want());
        o.add("state", st.json());
        o.addProperty("prompt", st.render());
        JsonObject call = new JsonObject();
        call.addProperty("tool", c.tool);
        call.add("args", c.args);
        call.addProperty("why", c.why);
        if (!c.text.isEmpty()) call.addProperty("text", c.text);
        o.add("call", call);
        JsonObject out = new JsonObject();
        out.addProperty("result", s.result);
        out.addProperty("detail", s.detail);
        out.addProperty("changes", s.changes);
        out.addProperty("seconds", Math.round(s.seconds * 10) / 10.0);
        o.add("outcome", out);
        JsonObject t = new JsonObject();
        t.addProperty("prompt", tokens[0]);
        t.addProperty("completion", tokens[1]);
        t.addProperty("cached", tokens[2]);
        t.addProperty("ms", ms);
        o.add("tokens", t);
        return o;
    }

    // ---- the guide's window: /цель [numbers|все] text ----------------------------------------------------------

    /** { agents ("all" or "2,3"), the goal's words } of a "/цель" line ("" words: show the usage), or null when it is not one */
    static String[] command(String line) {
        if (line == null) return null;
        String s = line.trim();
        String low = s.toLowerCase(Locale.ROOT);
        String rest;
        if (low.equals("/цель") || low.startsWith("/цель ")) rest = s.substring(5).trim();
        else if (low.equals("/goal") || low.startsWith("/goal ")) rest = s.substring(5).trim();
        else return null;
        Matcher m = Pattern.compile("^((?:\\d+\\s*,\\s*)*\\d+|все|всем|all)\\s*:?\\s+(.*)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL).matcher(rest);
        if (m.matches()) {
            String who = m.group(1).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
            return new String[] { who.matches("[\\d,]+") ? who : "all", m.group(2).trim() };
        }
        return new String[] { "all", rest };
    }

    /** "stone_pickaxe 1" (or "2 stone_pickaxe", "stone_pickaxe x2") as { item, count }; null when empty or unreadable */
    static String[] want(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        String t = s.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "");
        Matcher a = Pattern.compile("^([a-z_][a-z0-9_:]*)\\s*[x×]?\\s*(\\d+)?$").matcher(t), b = Pattern.compile("^(\\d+)\\s*[x×]?\\s*([a-z_][a-z0-9_:]*)$").matcher(t);
        if (a.matches()) return new String[] { a.group(1), a.group(2) == null ? "1" : a.group(2) };
        if (b.matches()) return new String[] { b.group(2), b.group(1) };
        return null;
    }
}
