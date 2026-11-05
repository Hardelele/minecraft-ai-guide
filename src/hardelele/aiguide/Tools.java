package hardelele.aiguide;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import net.minecraft.init.Blocks;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

/** What the assistant may look up, and the text it gets back. */
class Tools {
    static final int MAX_RESULT = 4000, MAX_GUIDE = 20000;   // characters of one tool result
    final AIGuide mod;
    List<String[]> names;   // key, lowercase searchable text, display name

    static Tools instance;

    Tools(AIGuide mod) {
        this.mod = mod;
        instance = this;
    }

    /** the headless bench asks the guide as its fake player would (a player's own words) */
    static String crewAsk(String player, String text) {
        if (instance == null || instance.mod.assistant == null) return "no guide";
        return instance.mod.assistant.ask(player, text) ? "asked" : "busy";
    }

    /** the crew (client mod, by reflection) tells the guide what it could not mend; the guide may answer with crew_plan */
    static String crewEvent(String player, String text) {
        if (instance == null || instance.mod.assistant == null) return "no guide";
        return instance.mod.assistant.ask(player, "[crew event, not from the player] " + text) ? "asked" : "busy";
    }

    /** the tools; over MCP the ones about a player take the player's name */
    JsonArray definitions(boolean mcp) {
        JsonArray a = new JsonArray();
        String P = mcp ? "player" : null, PD = "the player's name";
        a.add(tool("find_items", "Find items and blocks by name (in any language, part of the name works). Returns ids for the other tools.",
                "query", "a name or a part of it"));
        a.add(tool("how_to_make", "All the ways to get an item: crafting grids, furnace, machines of every mod.",
                "item", "an id from find_items, or a name"));
        a.add(tool("what_uses", "What an item is used for: recipes that take it.", "item", "an id from find_items, or a name"));
        a.add(tool("read_guide", "Read a guide from the knowledge base (the list is in the system prompt).", "topic", "the guide's name"));
        a.add(tool("player_status", "The player's inventory, position, health and the machines and blocks with tile entities around them.", P, PD));
        a.add(tool("where_is", "Where an item lies: the player's inventory, chests, barrels, drawers, machines and AE2 networks around them, with coordinates.",
                P, PD, "item", "an id from find_items, or a name"));
        a.add(tool("inspect_blocks", "A close look at the pipes, ducts, conduits, machines and chests around a point: for each block what "
                + "it touches on which side, what it holds and the settings it saves (filters, modes, modules, extraction sides, the items "
                + "travelling in pipes right now). For questions how items, fluids or energy flow, where things go, why an automation "
                + "does not work. Read the data and explain it; do not dump it.", P, PD,
                "at", "x y z of the point; empty for the block the player looks at (or where they stand)",
                "radius", "blocks around the point, 1-10 (default 4)"));
        a.add(tool("find_blocks", "Blocks of the world itself around the player, nearest first, with coordinates: trees (each trunk: its "
                + "logs bottom to top), stone, ores, sand, any block by name. For \"get me wood / stone\": find them here, then dig them with build.",
                P, PD, "query", "tree, stone, or a block's name (in any language, part of the name works)", "radius", "blocks around the player, up to 32 (default 24)"));
        a.add(tool("storage_overview", "The inventories and AE2 networks around the player and what is most in each.", P, PD));
        a.add(tool("plan_crafting", "Can the player make it? Unfolds the item into recipes down to what they have (inventory and storage around) "
                + "and lists the steps in order and what is missing.", P, PD, "item", "an id from find_items, or a name", "count", "how many (a number)"));
        a.add(tool("remember", "Save a short note about this player for later conversations (their goals, progress, what they have built). Use sparingly.",
                P, PD, "note", "one sentence"));
        if (mod.config.actions) {
            a.add(tool("fetch", "Get an item from the chests near the player (" + mod.config.fetchRadius
                    + " blocks) the way a player would: a trail of sparks leads the player to each chest and the things go to them there "
                    + "(or your villager body walks and brings them). Only when the player asks for it.",
                    P, PD, "item", "an id from find_items, or a name", "count", "how many (a number)", "how", "lead (the default: sparks lead the player, who walks there) or body (your villager body walks instead; only if the player asks you to go yourself)"));
            a.add(tool("craft", "Craft an item at a crafting table near the player: sparks lead the player to the chests with the parts and "
                    + "then to the table, where it is crafted from their inventory (or your villager body collects, crafts and brings it, asking "
                    + "before it takes anything of theirs). Without a table, what fits the 2x2 grid (planks, sticks, a crafting table) is "
                    + "crafted in the player's own inventory. One recipe level: the parts must exist. Only when the player asks for it.",
                    P, PD, "item", "an id from find_items, or a name", "count", "how many (a number)", "how", "lead (the default: sparks lead the player, who walks there) or body (your villager body walks instead; only if the player asks you to go yourself)"));
            a.add(tool("build", "Plan blocks to place and to dig near the player (up to 64, within 24 blocks). The plan is shown to them as "
                    + "see-through blocks and they are asked; with their yes the autopilot of their client walks them and builds it with what "
                    + "they carry. Coordinates are absolute: take them from player_status (where they stand and the block they face). Only "
                    + "when the player asks you to build or dig something.", P, PD,
                    "place", "one block a line: x y z item (item: an id from find_items, or a name); empty if none",
                    "dig", "one block a line: x y z; empty if none"));
            if (crewAvailable())
                a.add(tool("crew_plan", "Your crew: helper agents with players' bodies who do the early game for the player, on their own (no yes "
                        + "needed). Give only GOALS, one a line, from this list: summon N (1-5 agents); shelter auto|hut|house|fenced (build a base); "
                        + "tools wood|stone|iron (pickaxes for the crew); torches N; make ITEM N, ITEM N (any item by its game name, e.g. "
                        + "iron_pickaxe 1, iron_sword 1: the crew finds the ore, smelts and crafts it); gift ITEM [N], ITEM [N] (to the player); "
                        + "light (torches where the base and the mine are dark); inside (the crew goes into its house and waits there, e.g. "
                        + "for the night); guard (with inside: the best-armed agent stays out and fights off mobs until the others are in; "
                        + "mobs that come near the crew are fought off anyway); mine; gather (at nightfall at the mine). Never coordinates, never which agent does what: the crew "
                        + "works out the quantities (wood, stone, ore, crafts) and shares the work itself, and tells you if something cannot "
                        + "be mended. For 'a base by night' e.g.: summon 4, shelter auto, tools stone, torches 16, gift stone_pickaxe, torch 8, "
                        + "gather. For 'iron tools' e.g.: make iron_pickaxe 1, iron_sword 1, gift iron_pickaxe 1, iron_sword 1, light. "
                        + "Call it again with new goals to change the plan.",
                        P, PD, "goals", "one goal a line"));
            if (crewAvailable())
                a.add(tool("crew_order", "Direct orders to the crew's agents, as the player gives them in the commander view (when the player asks "
                        + "for something at a place: 'take that hill', 'chop this tree', 'all of you here', 'kill the zombie'). The agents ordered "
                        + "do only the player's orders until released. Use find_blocks or player_status for the place.", P, PD,
                        "agents", "all, or agent numbers: 1,3", "order", "move, chop, mine, attack (the nearest hostile mob to the place), guard (stand there and fight off mobs), stop, release (back to the plan)",
                        "x", "x", "y", "y", "z", "z"));
            if (false && crewAvailable())
                a.add(tool("helpers","Your crew: helper agents with players' bodies who do the early game for the player, together and on their "
                        + "own (no yes needed). Tasks run one after another in the order you call them, so call several in one answer for a "
                        + "whole plan, e.g. summon, wood, tools, mine, upgrade, gift, house, gather. summon: the agents come running (count: "
                        + "how many, 1-5); wood: they chop trees in a chain, one carries the logs to a chest (count: trees per agent, 1-4); "
                        + "tools: a crafting table and wooden pickaxes; mine: two dig a mine down in steps for stone, one carries; upgrade: a "
                        + "furnace, charcoal and torches, stone pickaxes; gift: one brings the player a stone pickaxe and torches; house: a base "
                        + "(a house with a door, windows and a roof, a fence with a gate, torches) by a see-through plan; gather: at nightfall "
                        + "they meet at the mouth of the mine. Use it when the player asks for help, a team, helpers, or to do the early game "
                        + "for them.", P, PD, "task", "summon, wood, tools, mine, upgrade, gift, house or gather", "count", "a number (see the task)"));            a.add(tool("mark", "Show the player a block: a column of sparks over it for a while.", P, PD,
                    "x", "x", "y", "y", "z", "z", "seconds", "how long, 5-120"));
        }
        if (mcp) {
            a.add(tool("list_players", "The players online, with their positions."));
            a.add(tool("tell", "Write to a player in chat, as the assistant.", P, PD, "text", "the message"));
        }
        return a;
    }

    JsonArray definitions() {
        return definitions(false);
    }

    static JsonObject tool(String name, String description, String... params) {
        JsonObject f = new JsonObject();
        f.addProperty("name", name);
        f.addProperty("description", description);
        JsonObject p = new JsonObject(), props = new JsonObject();
        JsonArray req = new JsonArray();
        p.addProperty("type", "object");
        for (int i = 0; i + 1 < params.length; i += 2) {
            if (params[i] == null) continue;
            JsonObject s = new JsonObject();
            s.addProperty("type", "string");
            s.addProperty("description", params[i + 1]);
            props.add(params[i], s);
            req.add(new com.google.gson.JsonPrimitive(params[i]));
        }
        p.add("properties", props);
        p.add("required", req);
        f.add("parameters", p);
        JsonObject t = new JsonObject();
        t.addProperty("type", "function");
        t.add("function", f);
        return t;
    }

    /** runs a tool; player is the asking player, or null over MCP (then it comes in the arguments) */
    String call(String name, String argsJson, String player) {
        JsonObject args;
        try {
            args = new JsonParser().parse(argsJson == null || argsJson.isEmpty() ? "{}" : argsJson).getAsJsonObject();
        } catch (Exception e) {
            args = new JsonObject();
        }
        if (player == null) player = str(args, "player");
        String r;
        try {
            if (name.equals("find_items")) r = findItems(str(args, "query"));
            else if (name.equals("how_to_make")) r = recipes(str(args, "item"), true);
            else if (name.equals("what_uses")) r = recipes(str(args, "item"), false);
            else if (name.equals("player_status")) r = playerStatus(player);
            else if (name.equals("find_blocks")) r = findBlocks(player, str(args, "query"), num(args, "radius", 24));
            else if (name.equals("crew_plan")) {
                if (!crewAvailable()) r = "No crew here.";
                else {
                    Class<?> origin = Class.forName("hardelele.aiguide.client.Origin");
                    Object llm = origin.getField("LLM_PLAN").get(null);
                    java.lang.reflect.Method plan = Class.forName("hardelele.aiguide.client.Compiler").getDeclaredMethod("plan", String.class, String.class, origin);
                    plan.setAccessible(true);
                    r = (String) plan.invoke(null, player, str(args, "goals"), llm);
                }
            }
            else if (name.equals("crew_order")) {
                if (!crewAvailable()) r = "No crew here.";
                else {
                    java.lang.reflect.Method m = Class.forName("hardelele.aiguide.client.Orders").getDeclaredMethod("fromGuide", String.class, String.class, int.class, int.class, int.class);
                    m.setAccessible(true);
                    r = (String) m.invoke(null, str(args, "agents"), str(args, "order"), num(args, "x", 0), num(args, "y", 0), num(args, "z", 0));
                }
            }
            else if (name.equals("helpers")) {
                if (!crewAvailable()) r = "No crew here.";
                else r = (String) Class.forName("hardelele.aiguide.client.Crew").getMethod("order", String.class, String.class, int.class)
                        .invoke(null, player, str(args, "task"), num(args, "count", 0));
            }
            else if (name.equals("read_guide")) {
                String t = mod.knowledge.read(str(args, "topic"));
                r = t == null ? "No such guide. Guides: " + mod.knowledge.titles.keySet() : t;
            } else if (name.equals("remember")) {
                mod.profiles.addNote(player, str(args, "note"), mod.config.maxNotes);
                r = "saved";
            } else if (name.equals("list_players")) r = listPlayers();
            else if (name.equals("tell")) {
                mod.say(player, str(args, "text"));
                r = "sent";
            } else r = world(name, args, player);
        } catch (Exception e) {
            AIGuide.LOG.warn("tool " + name + " failed", e);
            r = "error: " + e;
        }
        int max = name.equals("read_guide") || name.equals("inspect_blocks") ? MAX_GUIDE : MAX_RESULT;   // a guide is read whole
        return r.length() > max ? r.substring(0, max) + "\n... (cut)" : r;
    }

    static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    static int num(JsonObject o, String k, int def) {
        try {
            return (int) Math.round(Double.parseDouble(str(o, k).trim()));
        } catch (Exception e) {
            return def;
        }
    }

    /** the tools that look at or act in the world around a player: on the server thread */
    String world(final String name, final JsonObject args, final String player) throws Exception {
        final boolean act = name.equals("fetch") || name.equals("craft") || name.equals("mark") || name.equals("build");
        if (!name.equals("where_is") && !name.equals("storage_overview") && !name.equals("plan_crafting") && !name.equals("inspect_blocks") && !act)
            return "unknown tool " + name;
        if (act && !mod.config.actions) return "Actions are switched off on this server.";
        final ItemStack item = args.has("item") ? resolve(str(args, "item")) : null;
        if (args.has("item") && item == null) return "No item '" + str(args, "item") + "'. Use find_items.";
        final List<int[]> places = new ArrayList<int[]>(), digs = new ArrayList<int[]>();
        final List<ItemStack> items = new ArrayList<ItemStack>();
        if (name.equals("build")) {
            for (String l : str(args, "place").split("[\\n;]+")) {
                String[] w = l.trim().split("\\s+", 4);
                if (w.length < 4) {
                    if (l.trim().length() > 0) return "Bad place line '" + l + "': x y z item";
                    continue;
                }
                ItemStack it = resolve(w[3]);
                if (it == null) return "No item '" + w[3] + "'. Use find_items.";
                try {
                    places.add(new int[] { Integer.parseInt(w[0]), Integer.parseInt(w[1]), Integer.parseInt(w[2]) });
                } catch (NumberFormatException e) {
                    return "Bad place line '" + l + "': x y z item";
                }
                items.add(it);
            }
            for (String l : str(args, "dig").split("[\\n;]+")) {
                String[] w = l.trim().split("[\\s,]+");
                if (w.length < 3) continue;
                try {
                    digs.add(new int[] { Integer.parseInt(w[0]), Integer.parseInt(w[1]), Integer.parseInt(w[2]) });
                } catch (NumberFormatException e) {
                    return "Bad dig line '" + l + "': x y z";
                }
            }
            if (places.isEmpty() && digs.isEmpty()) return "Nothing to build: give place and/or dig lines.";
            if (places.size() + digs.size() > 64) return "Too much at once: up to 64 blocks.";
        }
        return mod.onMain(new Callable<String>() {
            @Override public String call() {
                EntityPlayerMP p = AIGuide.player(player);
                if (p == null) return "The player '" + player + "' is offline.";
                int count = Math.max(1, Math.min(2304, num(args, "count", 1)));
                if (name.equals("where_is")) {
                    int inInv = 0;
                    for (ItemStack s : p.field_71071_by.field_70462_a)
                        if (s != null && RecipeIndex.key(s).equals(RecipeIndex.key(item))) inInv += s.field_77994_a;
                    String w = mod.storage.where(p, item, mod.config.storageRadius);
                    return mod.lang.name(item) + ": " + (inInv > 0 ? inInv + " in the player's inventory. " : "none in the inventory. ")
                            + (w.isEmpty() ? "None in the storage within " + mod.config.storageRadius + " blocks." : "Around:\n" + w);
                }
                if (name.equals("storage_overview")) return overview(p);
                if (name.equals("inspect_blocks")) {
                    int[] at = null;
                    String[] w = str(args, "at").trim().split("[\\s,]+");
                    if (w.length == 3) try {
                        at = new int[] { Integer.parseInt(w[0]), Integer.parseInt(w[1]), Integer.parseInt(w[2]) };
                    } catch (NumberFormatException e) {
                        return "Bad point '" + str(args, "at") + "': x y z, or empty.";
                    }
                    if (at == null) {
                        net.minecraft.util.MovingObjectPosition hit = sight(p);
                        at = hit != null ? new int[] { hit.field_72311_b, hit.field_72312_c, hit.field_72309_d }
                                : new int[] { (int) Math.floor(p.field_70165_t), (int) Math.floor(p.field_70163_u), (int) Math.floor(p.field_70161_v) };
                    }
                    if (p.func_70092_e(at[0], at[1], at[2]) > 64 * 64) return "The point is more than 64 blocks from the player.";
                    return new Inspect(mod).around(p, at, Math.max(1, Math.min(10, num(args, "radius", 4))), MAX_GUIDE - 100);
                }
                if (name.equals("plan_crafting"))
                    return new Planner(mod, mod.storage.totals(p, mod.config.storageRadius, true)).plan(item, count);
                if (name.equals("build")) {
                    List<int[]> all = new ArrayList<int[]>(places);
                    all.addAll(digs);
                    for (int[] c : all)
                        if (p.func_70092_e(c[0] + 0.5, c[1] + 0.5, c[2] + 0.5) > 24 * 24)
                            return "The block " + c[0] + " " + c[1] + " " + c[2] + " is more than 24 blocks from the player.";
                    return mod.errands.build(p, places, items, digs);
                }
                if (name.equals("fetch")) return mod.errands.fetch(p, item, count, !str(args, "how").trim().equalsIgnoreCase("body"));
                if (name.equals("craft")) return mod.errands.craft(p, item, count, !str(args, "how").trim().equalsIgnoreCase("body"));
                return mod.actions.mark(p, num(args, "x", 0), num(args, "y", 0), num(args, "z", 0), num(args, "seconds", 30));
            }
        });
    }

    String overview(EntityPlayerMP p) {
        StringBuilder b = new StringBuilder();
        List<Storage.Place> places = mod.storage.around(p, mod.config.storageRadius);
        if (places.isEmpty()) return "No inventories within " + mod.config.storageRadius + " blocks.";
        for (Storage.Place pl : places) {
            if (b.length() > MAX_RESULT - 200) { b.append("... more places\n"); break; }
            List<Map.Entry<String, Integer>> l = new ArrayList<Map.Entry<String, Integer>>(pl.counts.entrySet());
            Collections.sort(l, new Comparator<Map.Entry<String, Integer>>() {
                @Override public int compare(Map.Entry<String, Integer> x, Map.Entry<String, Integer> y) { return y.getValue() - x.getValue(); }
            });
            b.append("- ").append(pl.name).append(" at ").append(pl.x).append(' ').append(pl.y).append(' ').append(pl.z)
                    .append(" (").append((int) pl.dist).append(" blocks): ");
            for (int i = 0; i < l.size() && i < 6; i++)
                b.append(i > 0 ? ", " : "").append(mod.lang.name(pl.stacks.get(l.get(i).getKey()))).append(" x").append(l.get(i).getValue());
            if (l.size() > 6) b.append(", +").append(l.size() - 6).append(" more kinds");
            b.append('\n');
        }
        return b.toString();
    }

    String listPlayers() throws Exception {
        return mod.onMain(new Callable<String>() {
            @Override public String call() {
                StringBuilder b = new StringBuilder();
                for (Object o : net.minecraft.server.MinecraftServer.func_71276_C().func_71203_ab().field_72404_b) {
                    EntityPlayerMP p = (EntityPlayerMP) o;
                    b.append(p.func_70005_c_()).append(": dimension ").append(p.field_71093_bK).append(", at ")
                            .append((int) p.field_70165_t).append(' ').append((int) p.field_70163_u).append(' ').append((int) p.field_70161_v).append('\n');
                }
                return b.length() == 0 ? "Nobody is online." : b.toString();
            }
        });
    }

    // ---- items --------------------------------------------------------------------------

    static String norm(String s) {
        return s.toLowerCase().replace('ё', 'е').replaceAll("§.", "");
    }

    synchronized List<String[]> names() {
        if (names == null) {
            names = new ArrayList<String[]>();
            for (Map.Entry<String, ItemStack> e : mod.recipes.stacks.entrySet()) {
                String n = mod.lang.name(e.getValue());
                String en;
                try {
                    en = e.getValue().func_82833_r();
                } catch (RuntimeException x) {
                    en = "";
                }
                names.add(new String[] { e.getKey(), norm(n + " | " + en + " | " + e.getKey()), n });
            }
            for (Fluid f : FluidRegistry.getRegisteredFluids().values()) {
                String n = fluid(new FluidStack(f, 0)).replaceAll(" 0 mB$", "") + " (fluid)";
                names.add(new String[] { RecipeIndex.fluidKey(f), norm(n + " | " + f.getName()), n });
            }
        }
        return names;
    }

    List<String[]> search(String q, int max) {
        final String n = norm(q.trim());
        final String[] words = n.split("\\s+");
        List<String[]> hits = new ArrayList<String[]>();
        final Map<String[], Integer> score = new LinkedHashMap<String[], Integer>();
        for (String[] e : names()) {
            String name = norm(e[2]);
            int s = name.equals(n) ? 100 : name.startsWith(n) ? 80 : e[1].contains(n) ? 60 : 0;
            if (s == 0) {
                boolean all = true;
                for (String w : words) if (!e[1].contains(w.length() > 4 ? w.substring(0, w.length() - 1) : w)) all = false;
                if (all) s = 40;
            }
            if (s > 0) {
                hits.add(e);
                score.put(e, s * 1000 - name.length());
            }
        }
        Collections.sort(hits, new Comparator<String[]>() {
            @Override public int compare(String[] a, String[] b) { return score.get(b) - score.get(a); }
        });
        return hits.subList(0, Math.min(max, hits.size()));
    }

    String findItems(String q) {
        List<String[]> hits = search(q, 15);
        if (hits.isEmpty()) return "Nothing found for '" + q + "'. Try a shorter part of the name or another language.";
        StringBuilder b = new StringBuilder();
        for (String[] h : hits) b.append(h[2]).append("  [").append(h[0]).append("]\n");
        return b.toString();
    }

    ItemStack resolve(String item) {
        ItemStack s = mod.recipes.stacks.get(item.trim());
        if (s != null) return s;
        if (!item.contains("@") && mod.recipes.stacks.containsKey(item.trim() + "@0")) return mod.recipes.stacks.get(item.trim() + "@0");
        List<String[]> hits = search(item, 1);
        return hits.isEmpty() ? null : mod.recipes.stacks.get(hits.get(0)[0]);
    }

    String recipes(String item, boolean make) {
        String id = item.trim();
        if (!id.startsWith("fluid:")) {   // a fluid by its name ("Хладагент" is both an item and a fluid in IC2): show both
            String fl = null;
            for (String[] h : search(id, 8)) if (h[0].startsWith("fluid:") && norm(h[2]).startsWith(norm(id))) { fl = h[0]; break; }
            if (fl != null) {
                ItemStack s = resolve(id);
                return recipes(fl, make) + (s == null ? "" : "\n" + recipes(RecipeIndex.key(s), make));
            }
        }
        if (id.startsWith("fluid:")) {
            Fluid f = FluidRegistry.getFluid(id.substring(6));
            if (f == null) return "No fluid '" + id + "'.";
            List<RecipeIndex.Recipe> l = make ? mod.recipes.producing(id) : mod.recipes.using(id);
            String name = fluid(new FluidStack(f, 0)).replaceAll(" 0 mB$", "") + " (fluid) [" + id + "]";
            if (l.isEmpty()) return name + ": " + (make ? "no machine recipe makes it (it may be pumped from the world or come from a mod's own machine)."
                    : "no recipe the guide can see takes it.");
            StringBuilder b = new StringBuilder(name + ", " + l.size() + (make ? " way(s) to make:\n" : " use(s):\n"));
            for (RecipeIndex.Recipe r : l) {
                if (b.length() > MAX_RESULT - 300) break;
                b.append(make ? text(r) : textUse(r)).append('\n');
            }
            return b.toString();
        }
        ItemStack s = resolve(item);
        if (s == null) return "No item '" + item + "'. Use find_items.";
        List<RecipeIndex.Recipe> l = make ? mod.recipes.producing(s) : mod.recipes.using(s);
        String name = mod.lang.name(s);
        if (l.isEmpty()) return name + " [" + RecipeIndex.key(s) + "]: " + (make
                ? "no recipe found. It is likely found in the world, dropped by mobs, or made in a way the guide cannot see (in-world crafting, rituals, AE2 inscriber)."
                : "not used in any recipe the guide can see.");
        StringBuilder b = new StringBuilder(name + " [" + RecipeIndex.key(s) + "], " + l.size() + (make ? " way(s) to make:\n" : " use(s):\n"));
        int shown = 0;
        for (RecipeIndex.Recipe r : l) {
            if (b.length() > MAX_RESULT - 300) { b.append("... and ").append(l.size() - shown).append(" more\n"); break; }
            b.append(make ? text(r) : textUse(r)).append('\n');
            shown++;
        }
        return b.toString();
    }

    String machine(String m) {
        return m.equals("crafting") ? "Crafting table (Верстак)" : m.equals("furnace") ? "Furnace (Печь)" : m;
    }

    String text(RecipeIndex.Recipe r) {
        StringBuilder b = new StringBuilder("- ").append(machine(r.machine)).append(": ");
        for (int i = 0; i < r.in.size(); i++) {
            if (i > 0) b.append(", ");
            if (r.grid != null) b.append((char) ('A' + i)).append('=');
            b.append(ingredient(r.in.get(i)));
        }
        if (r.grid != null) {
            b.append("; grid ");
            for (int i = 0; i < r.grid.length; i++) b.append(i > 0 ? "/" : "").append(r.grid[i]);
        }
        b.append(" -> ");
        for (int i = 0; i < r.out.size(); i++) b.append(i > 0 ? " + " : "").append(output(r.out.get(i)));
        if (!r.notes.isEmpty()) b.append(" ").append(r.notes);
        return b.toString();
    }

    String textUse(RecipeIndex.Recipe r) {
        StringBuilder b = new StringBuilder("- ").append(machine(r.machine)).append(" -> ");
        for (int i = 0; i < r.out.size(); i++) b.append(i > 0 ? " + " : "").append(output(r.out.get(i)));
        return b.toString();
    }

    String ingredient(RecipeIndex.Ingredient g) {
        StringBuilder b = new StringBuilder();
        if (g.fluid != null) b.append(fluid(g.fluid)).append(g.options.isEmpty() ? "" : " in ");
        if (g.fluid != null && !g.options.isEmpty() && cpw.mods.fml.common.Loader.isModLoaded("modglue"))
            b.append("any container holding it (Mod Glue), e.g. ");
        if (!g.options.isEmpty()) {
            b.append(mod.lang.name(g.options.get(0)));
            if (g.ore != null) b.append(" (any ").append(g.ore).append(")");
            else if (g.options.size() > 1) b.append(" or ").append(mod.lang.name(g.options.get(1)))
                    .append(g.options.size() > 2 ? " or other" : "");
        }
        int n = g.amount > 1 ? g.amount : g.options.isEmpty() ? 1 : Math.max(1, g.options.get(0).field_77994_a);
        if (n > 1) b.append(" x").append(n);
        return b.toString();
    }

    String output(RecipeIndex.Output o) {
        String s = o.stack != null ? mod.lang.name(o.stack) + (o.stack.field_77994_a > 1 ? " x" + o.stack.field_77994_a : "")
                : o.fluid != null ? fluid(o.fluid) : "?";
        return o.note == null ? s : s + " (" + o.note + ")";
    }

    String fluid(FluidStack f) {
        Fluid fl = f.getFluid();
        String n = fl == null ? "?" : fl.getName();
        if (fl != null) {
            String u = fl.getUnlocalizedName();
            for (String k : new String[] { u, u + ".name", Lang.strip(u), Lang.strip(u) + ".name" })
                if (mod.lang.names.containsKey(k)) { n = mod.lang.names.get(k); break; }
        }
        return n + " " + f.amount + " mB";
    }

    // ---- the player ---------------------------------------------------------------------

    String playerStatus(final String player) throws Exception {
        return mod.onMain(new Callable<String>() {
            @Override public String call() {
                EntityPlayerMP p = AIGuide.player(player);
                if (p == null) return "The player is offline.";
                StringBuilder b = new StringBuilder();
                b.append("Player ").append(player).append(", dimension ").append(p.field_71093_bK).append(", at ")
                        .append((int) Math.floor(p.field_70165_t)).append(' ').append((int) Math.floor(p.field_70163_u)).append(' ')
                        .append((int) Math.floor(p.field_70161_v)).append(", health ").append((int) p.func_110143_aJ()).append("/20\n");
                b.append("Facing ").append(facing(p)).append('\n');
                b.append("Inventory: ").append(stacks(p.field_71071_by.field_70462_a)).append('\n');
                b.append("Armor: ").append(stacks(p.field_71071_by.field_70460_b)).append('\n');
                b.append(mod.link.has(player) ? "Has the client mod: autopilot and see-through plans work.\n"
                        : "No client mod: the guide leads with sparks; plans show as sparks.\n");
                b.append("Nearby (").append(mod.config.scanRadius).append(" blocks): ").append(nearby(p)).append('\n');
                return b.toString();
            }
        });
    }

    /** the crew of the client mod's video build: only in singleplayer, where both mods share one game */
    static boolean crewAvailable() {
        try {
            Class.forName("hardelele.aiguide.client.Crew");
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** natural blocks around the player: trees by trunk, other blocks that are open to the air, nearest first */
    String findBlocks(final String player, final String query, final int radius) throws Exception {
        return mod.onMain(new Callable<String>() {
            @Override public String call() {
                EntityPlayerMP p = AIGuide.player(player);
                if (p == null) return "The player is offline.";
                World w = p.field_70170_p;
                int r = Math.max(4, Math.min(32, radius));
                int px = (int) Math.floor(p.field_70165_t), py = (int) Math.floor(p.field_70163_u), pz = (int) Math.floor(p.field_70161_v);
                String q = query.trim().toLowerCase();
                boolean trees = q.isEmpty() || q.matches(".*(tree|log|wood|trunk|дерев|брев|брёв|ствол|древес).*");
                List<double[]> found = new ArrayList<double[]>();   // dist, x, y, z, height
                Map<String, String> names = new HashMap<String, String>();
                for (int x = px - r; x <= px + r; x++)
                    for (int z = pz - r; z <= pz + r; z++)
                        for (int y = Math.max(1, py - 12); y <= Math.min(250, py + 16); y++) {
                            Block b = w.func_147439_a(x, y, z);
                            if (b == Blocks.field_150350_a) continue;
                            if (trees) {
                                if (b != Blocks.field_150364_r && b != Blocks.field_150363_s) continue;
                                if (w.func_147439_a(x, y - 1, z) == b) continue;   // not the bottom of a trunk
                                int h = 0;
                                while (h < 30 && w.func_147439_a(x, y + h, z) == b) h++;
                                found.add(new double[] { Math.sqrt(p.func_70092_e(x + 0.5, y, z + 0.5)), x, y, z, h });
                                names.put(x + " " + y + " " + z, blockName(w, x, y, z));
                                continue;
                            }
                            boolean open = false;
                            for (int[] d : new int[][] { { 0, 1, 0 }, { 0, -1, 0 }, { 1, 0, 0 }, { -1, 0, 0 }, { 0, 0, 1 }, { 0, 0, -1 } })
                                if (w.func_147437_c(x + d[0], y + d[1], z + d[2])) open = true;
                            if (!open) continue;
                            String n = blockName(w, x, y, z);
                            String key = q.equals("stone") || q.equals("камень") ? (b == Blocks.field_150348_b ? "stone" : "") : n.toLowerCase();
                            if (!(key.equals("stone") && b == Blocks.field_150348_b) && !key.contains(q)) continue;
                            found.add(new double[] { Math.sqrt(p.func_70092_e(x + 0.5, y + 0.5, z + 0.5)), x, y, z, 1 });
                            names.put(x + " " + y + " " + z, n);
                        }
                Collections.sort(found, new Comparator<double[]>() {
                    @Override public int compare(double[] a, double[] b) { return Double.compare(a[0], b[0]); }
                });
                if (found.isEmpty()) return "Nothing like '" + query + "' within " + r + " blocks (open to the air" + (trees ? ", trunks of trees" : "") + ").";
                StringBuilder b = new StringBuilder("Player at " + px + " " + py + " " + pz + ". Nearest first:\n");
                for (int i = 0; i < found.size() && i < (trees ? 6 : 12); i++) {
                    double[] f = found.get(i);
                    int x = (int) f[1], y = (int) f[2], z = (int) f[3], h = (int) f[4];
                    String n = names.get(x + " " + y + " " + z);
                    if (trees) b.append("- tree (").append(n).append("), trunk at ").append(x).append(' ').append(z).append(", logs y ").append(y)
                            .append(h > 1 ? ".." + (y + h - 1) : "").append(" (").append(h).append(" logs), ").append((int) f[0]).append(" blocks away\n");
                    else b.append("- ").append(n).append(" at ").append(x).append(' ').append(y).append(' ').append(z).append(", ").append((int) f[0]).append(" blocks away\n");
                }
                return b.toString();
            }
        });
    }

    String blockName(World w, int x, int y, int z) {
        Block bl = w.func_147439_a(x, y, z);
        Item i = Item.func_150898_a(bl);
        return i == null ? bl.func_149739_a() : mod.lang.name(new ItemStack(i, 1, bl.func_149692_a(w.func_72805_g(x, y, z))));
    }

    static final String[] SIDES = { "bottom", "top", "north (-z)", "south (+z)", "west (-x)", "east (+x)" };

    /** where the player looks: the compass direction and the block in reach (for "put it here") */
    String facing(EntityPlayerMP p) {
        String[] dirs = { "south (+z)", "west (-x)", "north (-z)", "east (+x)" };
        String dir = dirs[net.minecraft.util.MathHelper.func_76128_c(p.field_70177_z * 4.0F / 360.0F + 0.5D) & 3];
        net.minecraft.util.MovingObjectPosition hit = sight(p);
        if (hit == null) return dir + ", at no block in reach";
        int x = hit.field_72311_b, y = hit.field_72312_c, z = hit.field_72309_d;
        Block bl = p.field_70170_p.func_147439_a(x, y, z);
        Item i = Item.func_150898_a(bl);
        String n = i == null ? bl.func_149739_a() : mod.lang.name(new ItemStack(i, 1, bl.func_149692_a(p.field_70170_p.func_72805_g(x, y, z))));
        return dir + ", at " + n + " " + x + " " + y + " " + z + " (its " + SIDES[Math.max(0, Math.min(5, hit.field_72310_e))] + " side)";
    }

    /** the block the player looks at within 6 blocks, or null */
    static net.minecraft.util.MovingObjectPosition sight(EntityPlayerMP p) {
        double yaw = Math.toRadians(p.field_70177_z), pitch = Math.toRadians(p.field_70125_A);
        double ex = p.field_70165_t, ey = p.field_70163_u + p.func_70047_e(), ez = p.field_70161_v;
        double lx = -Math.sin(yaw) * Math.cos(pitch), ly = -Math.sin(pitch), lz = Math.cos(yaw) * Math.cos(pitch);
        net.minecraft.util.MovingObjectPosition hit = p.field_70170_p.func_72933_a(net.minecraft.util.Vec3.func_72443_a(ex, ey, ez),
                net.minecraft.util.Vec3.func_72443_a(ex + lx * 6, ey + ly * 6, ez + lz * 6));
        return hit == null || !"BLOCK".equals(String.valueOf(hit.field_72313_a)) ? null : hit;
    }

    String stacks(ItemStack[] inv) {
        Map<String, Integer> count = new LinkedHashMap<String, Integer>();
        for (ItemStack s : inv) {
            if (s == null || s.func_77973_b() == null) continue;
            String n = mod.lang.name(s);
            count.put(n, (count.containsKey(n) ? count.get(n) : 0) + s.field_77994_a);
        }
        return count.isEmpty() ? "empty" : join(count);
    }

    String nearby(EntityPlayerMP p) {
        World w = p.field_70170_p;
        int r = mod.config.scanRadius;
        Map<String, Integer> count = new LinkedHashMap<String, Integer>();
        for (Object o : new ArrayList<Object>(w.field_147482_g)) {
            TileEntity t = (TileEntity) o;
            if (Math.abs(t.field_145851_c - p.field_70165_t) > r || Math.abs(t.field_145848_d - p.field_70163_u) > r
                    || Math.abs(t.field_145849_e - p.field_70161_v) > r) continue;
            Block b = w.func_147439_a(t.field_145851_c, t.field_145848_d, t.field_145849_e);
            Item i = Item.func_150898_a(b);
            String n;
            if (i == null) n = b.func_149739_a();
            else {
                int meta = w.func_72805_g(t.field_145851_c, t.field_145848_d, t.field_145849_e);
                ItemStack s = new ItemStack(i, 1, i.func_77614_k() ? b.func_149692_a(meta) : 0);
                n = mod.lang.name(s);
            }
            count.put(n, (count.containsKey(n) ? count.get(n) : 0) + 1);
        }
        return count.isEmpty() ? "nothing" : join(count);
    }

    static String join(Map<String, Integer> m) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (b.length() > 0) b.append(", ");
            b.append(e.getKey());
            if (e.getValue() > 1) b.append(" x").append(e.getValue());
        }
        return b.toString();
    }
}
