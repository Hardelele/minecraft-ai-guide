package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.WorldServer;

/**
 * From the guide's goals to the crew's work. The model says only WHAT is wanted, one goal a line, from a small
 * vocabulary (summon, shelter, tools, torches, gift, mine, gather); never where, never who. This compiler turns the
 * goals into the day's phases (each a generator of board cards) with the quantities worked out here: the items the
 * goals take are unfolded through the game's own recipes down to logs and cobblestone (charcoal by smelting logs),
 * the base's bill comes from its plan laid on the real site. After a phase, events (cards given up, a phase out of
 * time, a base smaller than asked) are repaired here by rules first; what the rules cannot mend goes back to the guide.
 */
class Compiler {
    static final String VOCAB = "summon N (1-5 agents); shelter auto|hut|house|fenced; tools wood|stone; torches N; "
            + "make ITEM [N], ITEM [N] (any item by its game name: iron_pickaxe, iron_sword, torch...); "
            + "gift ITEM [N], ITEM [N] (things the crew has or makes, to the player); light (torches where the base and the mine are dark); "
            + "inside (the crew goes into its house and waits there, e.g. for the night); mine; gather";

    /** an item by its game name (minecraft:iron_pickaxe or iron_pickaxe), or null */
    static Item item(String name) {
        String n = name.trim();
        Object o = Item.itemRegistry.getObject(n.contains(":") ? n : "minecraft:" + n);
        return o instanceof Item ? (Item) o : null;
    }

    /** the goals, parsed */
    static class Goals {
        int agents = 4;
        int shelter = -1;           // -1 none, 0 hut, 1 house, 2 house with a fence
        String tools;               // null, wood, stone
        int torches;
        final List<ItemStack> gift = new ArrayList<ItemStack>(), make = new ArrayList<ItemStack>();
        boolean mine, gather, summon, light, inside;
        public String toString() {
            return "summon " + (summon ? agents : "-") + ", shelter " + shelter + ", tools " + tools + ", torches " + torches + ", gift " + gift.size() + ", mine " + mine + ", gather " + gather;
        }
    }

    /** null and the goals in g, or the error for the model (no coordinates, no assignments, only the vocabulary) */
    static String parse(String text, Goals g) {
        if (text == null || text.trim().isEmpty()) return "No goals. One goal a line: " + VOCAB;
        for (String raw : text.replace(";", "\n").split("\n")) {
            String l = raw.trim().toLowerCase(Locale.ROOT).replaceAll("^[-*\\d.)\\s]+(?=[a-z])", "");
            if (l.isEmpty()) continue;
            if (l.matches(".*-?\\d+\\s*[ ,]\\s*-?\\d+\\s*[ ,]\\s*-?\\d+.*")) return "Line '" + raw.trim() + "': no coordinates. Say what is wanted; the crew finds the places.";
            if (l.matches(".*(agent|агент|helper)\\s*\\d.*")) return "Line '" + raw.trim() + "': no assignments. Say what is wanted; the crew shares the work itself.";
            String[] w = l.split("[\\s:=]+", 2);
            String k = w[0], arg = w.length > 1 ? w[1].trim() : "";
            if (k.startsWith("summon") || k.startsWith("call")) { g.summon = true; g.agents = clamp(num(arg, 4), 1, 5); }
            else if (k.startsWith("shelter") || k.startsWith("base") || k.startsWith("house")) {
                g.shelter = arg.startsWith("hut") ? 0 : arg.startsWith("house") ? 1 : 2;   // auto and fenced: the biggest the wood allows
            } else if (k.startsWith("tool")) g.tools = arg.startsWith("wood") ? "wood" : arg.startsWith("iron") ? "iron" : "stone";
            else if (k.equals("light") || k.startsWith("lighting")) g.light = true;
            else if (k.startsWith("inside") || k.startsWith("hide")) g.inside = true;
            else if (k.startsWith("torch")) g.torches = clamp(num(arg, 16), 0, 64);
            else if (k.startsWith("gift") || k.startsWith("give") || k.startsWith("make") || k.startsWith("craft")) {
                List<ItemStack> into = k.startsWith("make") || k.startsWith("craft") ? g.make : g.gift;
                for (String part : arg.split(",")) {
                    String[] p = part.trim().split("\\s+");
                    if (p[0].isEmpty()) continue;
                    Item it = item(p[0]);
                    if (it == null) return "'" + part.trim() + "': no such item; use the game's names (iron_pickaxe, iron_sword, torch, stone_pickaxe...).";
                    into.add(new ItemStack(it, clamp(p.length > 1 ? num(p[1], 1) : it == Day1.TORCH ? 8 : 1, 1, 64)));
                }
            } else if (k.startsWith("mine") || k.startsWith("stone")) g.mine = true;
            else if (k.startsWith("gather") || k.startsWith("night")) g.gather = true;
            else return "Unknown goal '" + raw.trim() + "'. Goals: " + VOCAB;
        }
        return null;
    }

    static int num(String s, int d) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(s);
        return m.find() ? Integer.parseInt(m.group()) : d;
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ---- what items take, by the game's recipes ---------------------------------------------------------

    /** the raw materials (logs, cobblestone) of n of an item, and the crafts and smelts on the way */
    static void unfold(Item item, int n, Map<Item, Integer> raw, Map<String, Integer> steps, int depth) {
        if (n <= 0) return;
        if (item == Day1.LOG || item == Day1.LOG2 || item == Day1.COBBLE) { add(raw, item == Day1.LOG2 ? Day1.LOG : item, n); return; }
        if (item == Items.iron_ingot) {   // smelted from iron ore (the fuel is counted where the smelting is planned)
            Ledger.add(steps, "smelt iron", n);
            add(raw, Day2.IRON_ORE, n);
            return;
        }
        if (item == Items.coal) {   // charcoal: a log smelted each, planks for fuel (1.5 items a plank)
            Ledger.add(steps, "smelt charcoal", n);
            unfold(Day1.LOG, n, raw, steps, depth + 1);
            unfold(Day1.PLANKS, (n * 2 + 2) / 3, raw, steps, depth + 1);
            return;
        }
        List<Skills.Laid> rs = depth > 6 ? new ArrayList<Skills.Laid>() : Skills.recipesFor(item, -1);
        Skills.Laid l = null;
        for (Skills.Laid x : rs) if (simple(x)) { l = x; break; }
        if (l == null && !rs.isEmpty()) l = rs.get(0);
        if (l == null) { add(raw, item, n); return; }   // no recipe: to be found in the world
        int per = Math.max(1, l.out.stackSize), crafts = (n + per - 1) / per;
        Ledger.add(steps, "craft " + Ledger.name(l.out), crafts);
        Map<Item, Integer> ing = new LinkedHashMap<Item, Integer>();
        for (List<ItemStack> cell : l.cells) if (cell != null) add(ing, cell.get(0).getItem(), crafts);
        for (Map.Entry<Item, Integer> e : ing.entrySet()) unfold(e.getKey(), e.getValue(), raw, steps, depth + 1);
    }

    /** a recipe of the plain day-one things (oak planks, sticks, cobblestone, coal) */
    static boolean simple(Skills.Laid l) {
        for (List<ItemStack> cell : l.cells) {
            if (cell == null) continue;
            ItemStack s = cell.get(0);
            if (s.getItem() == Day1.LOG && s.getMetadata() != 0 && s.getMetadata() != 32767) return false;
            if (s.getItem() == Day1.PLANKS && s.getMetadata() != 0 && s.getMetadata() != 32767) return false;
        }
        return true;
    }

    static void add(Map<Item, Integer> m, Item k, int n) {
        Integer o = m.get(k);
        m.put(k, (o == null ? 0 : o) + n);
    }

    // ---- compiling --------------------------------------------------------------------------------------

    /** the guide's goals for a player: checked, compiled into phases, queued; the answer for the model */
    static String plan(final String player, String text, final Origin origin) {
        final Goals g = new Goals();
        String err = parse(text, g);
        if (err != null) { Events.log("compiler: rejected: " + err); return err; }
        Crew.ensure();
        final Crew c = Crew.instance;
        final StringBuilder answer = new StringBuilder();
        java.util.concurrent.FutureTask<String> t = new java.util.concurrent.FutureTask<String>(() -> compile(c, player, g, origin));
        c.queue.add(t);
        try {
            answer.append(t.get(10, java.util.concurrent.TimeUnit.SECONDS));
        } catch (Exception e) {
            answer.append("Queued; the crew works it out on the next tick (" + e.getClass().getSimpleName() + ").");
        }
        return answer.toString();
    }

    static Goals last;   // the goals in force (for the replanning)
    static volatile int plans;   // how many goal lists were compiled (the bench waits on it)
    static int replans;

    /** on the server thread: the quantities, the phases */
    static String compile(Crew c, String player, Goals g, Origin origin) {
        c.player = player;
        net.minecraft.server.MinecraftServer s = net.minecraft.server.MinecraftServer.getServer();
        WorldServer w = s.worldServers[0];
        EntityPlayerMP p = c.playerIn(w);
        if (p == null) return "The player is not in the world.";
        if (c.base == null) { c.base = new int[] { (int) Math.floor(p.posX), (int) Math.floor(p.boundingBox.minY), (int) Math.floor(p.posZ) }; c.baseYaw = p.rotationYaw; }
        c.planSite(w);
        boolean again = last != null;   // new goals replace the plan: what is queued goes, what is done stays done
        if (again) c.phases.clear();
        last = g;
        plans++;
        int n = g.summon ? g.agents : Math.max(1, c.agents.size());
        boolean stone = "stone".equals(g.tools) || !g.gift.isEmpty() && giftHas(g, Items.stone_pickaxe);
        int giftTorches = giftCount(g, Day1.TORCH), torches = g.torches + giftTorches;
        boolean giftPick = giftHas(g, Items.stone_pickaxe);
        Map<Item, Integer> raw = new LinkedHashMap<Item, Integer>();
        Map<String, Integer> steps = new LinkedHashMap<String, Integer>();
        boolean pickaxes = g.tools != null || stone || g.mine || torches > 0;
        if (pickaxes) unfold(Items.wooden_pickaxe, n, raw, steps, 0);
        if (stone) unfold(Items.stone_pickaxe, Math.max(0, n - 1) + (giftPick ? 1 : 0), raw, steps, 0);
        if (torches > 0) { unfold(Day1.FURNACE, 1, raw, steps, 0); unfold(Day1.TORCH, torches, raw, steps, 0); }
        for (ItemStack it : g.gift) if (it.getItem() == Items.wooden_pickaxe) unfold(Items.wooden_pickaxe, it.stackSize, raw, steps, 0);
        int baseLogs = 0, baseStone = 0, toolLogs = raw.containsKey(Day1.LOG) ? raw.get(Day1.LOG) : 0;
        int standing = 0;   // the wood there is: in the crew's hands and chest, and the trunks around the base
        for (int[] t : c.trees(w)) standing += t[3];
        int avail = Day1.crewLogs(c, w) + standing;
        String baseNote = "";
        if (g.shelter >= 0) {   // the base's bill, laid on the real site; smaller when the wood around is short
            int size = g.shelter;
            Day1.Base b = new Day1.Base(size);
            for (; ; size--) {
                b.lay(c, w, size, 0);
                baseLogs = Day1.logsFor(Day1.bill(b.plan, true));
                if (size == 0 || (int) Math.ceil((toolLogs + baseLogs) * 1.1) <= avail) break;
            }
            if (size < g.shelter) baseNote = "smaller than asked: only about " + avail + " logs of wood around; ";
            g.shelter = size;
            Map<String, Integer> bill = Day1.bill(b.plan, true);
            baseNote += (size == 2 ? "house with a fence" : size == 1 ? "house" : "hut") + " " + b.plan.size() + " blocks, " + bill;
        }
        int logs = Math.min(avail, (int) Math.ceil((toolLogs + baseLogs) * 1.1));
        int cobble = (raw.containsKey(Day1.COBBLE) ? raw.get(Day1.COBBLE) : 0) + baseStone;
        List<Crew.Phase> ph = new ArrayList<Crew.Phase>();
        if (g.summon && c.agents.size() < n) ph.add(named(new Crew.Summon(n), "summon"));
        if (logs > 0) ph.add(named(new Day1.WoodChain(3, logs), "wood"));
        if (pickaxes) ph.add(named(new Day1.Tools(), "tools"));
        if (cobble > 0 || g.mine) ph.add(named(new Day1.Mine(Math.max(4, (int) Math.ceil(cobble / 4.0) + 1)), "mine"));   // as deep as the stone wanted takes (two lanes, about 4 a step)
        boolean ironTools = "iron".equals(g.tools);
        if (stone || torches > 0 || ironTools) ph.add(named(new Day1.Upgrade(torches, "stone".equals(g.tools) || ironTools, giftPick), "upgrade"));   // iron wants stone pickaxes first
        // what is made (iron things and the like): their raw things from the ground, smelted, made at the table
        List<ItemStack> make = new ArrayList<ItemStack>(g.make);
        if (ironTools) make.add(new ItemStack(Items.iron_pickaxe, n));
        if (g.light && torches == 0) make.add(new ItemStack(Day1.TORCH, 16));   // lighting the base and the mine takes torches
        for (ItemStack it : g.gift)   // a gift the day-one phases do not make is made here
            if (it.getItem() != Items.stone_pickaxe && it.getItem() != Day1.TORCH && it.getItem() != Items.wooden_pickaxe) {
                boolean listed = false;
                for (ItemStack m : make) if (m.getItem() == it.getItem()) { m.stackSize += it.stackSize; listed = true; }
                if (!listed) make.add(it.copy());
            }
        String day2 = "";
        if (!make.isEmpty()) {
            Map<Item, Integer> raw2 = new LinkedHashMap<Item, Integer>();
            Map<String, Integer> st2 = new LinkedHashMap<String, Integer>();
            for (ItemStack it : make) {
                int need = it.stackSize - Day1.Base.have(c, w, it.getItem());
                if (need > 0) unfold(it.getItem(), need, raw2, st2, 0);
            }
            int ore = (raw2.containsKey(Day2.IRON_ORE) ? raw2.get(Day2.IRON_ORE) : 0) - Day1.Base.have(c, w, Day2.IRON_ORE) - Day1.Base.have(c, w, Items.iron_ingot);
            int coalForMade = 0;   // coal in what is made (a torch: a quarter of one), from coal ore when there is some
            for (ItemStack it : make) if (it.getItem() == Day1.TORCH) coalForMade += (Math.max(0, it.stackSize - Day1.Base.have(c, w, Day1.TORCH)) + 3) / 4;
            int coal = Math.max(0, (ore > 0 ? (ore + 7) / 8 : 0) + coalForMade - Day1.Base.have(c, w, Items.coal));
            boolean anyPick = false, ironPick = false;   // who can mine it: pickaxes first if nobody can
            for (Agent a : c.agents) { if (Day1.hasPickaxe(a)) anyPick = true; if (Day2.pickaxeFor(a, Blocks.iron_ore)) ironPick = true; }
            if (!anyPick && (coal > 0 || ore > 0) && !has(ph, "tools")) ph.add(named(new Day1.Tools(), "tools"));
            if (!ironPick && ore > 0 && !has(ph, "upgrade")) ph.add(named(new Day1.Upgrade(0, true, false), "upgrade"));
            if (coal > 0 && !Day2.clusters(c, w, Blocks.coal_ore, 40).isEmpty()) ph.add(named(new Day2.OreRun(Blocks.coal_ore, coal), "coal"));
            if (ore > 0) {
                ph.add(named(new Day2.OreRun(Blocks.iron_ore, ore), "iron"));
                ph.add(named(new Day2.SmeltOre(Day2.IRON_ORE, ore), "smelt"));
            }
            ph.add(named(new Day2.Make(make), "make"));
            day2 = "; iron ore " + Math.max(0, ore) + ", coal " + coal + ", make " + make + " (" + st2 + ")";
        }
        if (g.light) ph.add(named(new Day2.Light(), "light"));
        if (g.inside) ph.add(named(new Day2.Shelter(20 * 60), "inside"));
        if (!g.gift.isEmpty()) ph.add(named(new Day1.Gift(g.gift), "gift"));
        if (g.shelter >= 0) ph.add(named(new Day1.Base(g.shelter), "house"));
        if (g.gather) ph.add(named(new Day1.Gather(), "gather"));
        if (again) {
            for (java.util.Iterator<Crew.Phase> it = ph.iterator(); it.hasNext(); ) {
                Crew.Phase x = it.next();
                boolean done = x.kind.equals("wood") ? Day1.crewLogs(c, w) >= logs : succeeded.contains(x.kind);
                if (done || c.current != null && c.current.kind.equals(x.kind)) it.remove();
            }
        }
        StringBuilder kinds = new StringBuilder();
        for (Crew.Phase x : ph) {
            x.origin = origin;
            c.phases.add(x);
            c.ordered.add(x.kind);
            kinds.append(kinds.length() > 0 ? ", " : "").append(x.kind);
        }
        if (cobble > 0) c.board.goals.put(Day1.COBBLE, cobble + 8);
        String sum = (again ? "The crew's plan, changed (done stays done): " : "The crew's plan: ") + (kinds.length() == 0 ? "nothing left to do" : kinds.toString()) + ". Wood " + logs + " logs" + (baseLogs > 0 ? " (the base " + baseLogs + ")" : "") + ", stone " + cobble
                + (baseNote.isEmpty() ? "" : "; base: " + baseNote) + day2 + "; crafts and smelts " + steps + ". The crew shares the work itself; "
                + "if something fails it is mended or you are told.";
        Events.log("compiler: goals " + g + " -> " + sum);
        return sum;
    }

    static Crew.Phase named(Crew.Phase p, String kind) {
        p.kind = kind;
        p.name = kind;
        return p;
    }

    static boolean giftHas(Goals g, Item i) {
        return giftCount(g, i) > 0;
    }

    static int giftCount(Goals g, Item i) {
        int n = 0;
        for (ItemStack s : g.gift) if (s.getItem() == i) n += s.stackSize;
        return n;
    }

    // ---- replanning on events: what is short is worked out and fetched, then the phase goes again ----------

    /**
     * After a phase. A phase whose cards (the optional ones aside) all went through is done. Otherwise what was short
     * is added up from the cards (each failure says what it lacked and how much; a base says what of its bill is still
     * to be made), and each shortfall is covered from where it can be had: wood from the trunks one can walk to, stone
     * from rock open to the air or by the mine's stairs going deeper by just the steps it takes, a made thing by the
     * phase that makes it (its own raw things first). Then the phase goes again. A phase that failed on the way (no
     * path, out of time) goes again as it is. What cannot be covered is told to the guide.
     */
    static void afterPhase(Crew c, Crew.Phase ph, WorldServer w, boolean outOfTime) {
        List<TaskBoard.Card> failed = new ArrayList<TaskBoard.Card>();
        for (TaskBoard.Card k : c.board.cards) if (k.phase == ph && k.state == TaskBoard.State.FAILED && !k.optional) failed.add(k);
        if (failed.isEmpty() && !outOfTime) { succeeded.add(ph.kind); return; }
        if (ph instanceof Day1.Quarry) quarryFailed = true;   // what the quarry was for is still short: the stairs give it next time
        StringBuilder what = new StringBuilder();
        Map<Item, Integer> lack = new LinkedHashMap<Item, Integer>();
        boolean way = outOfTime;
        for (TaskBoard.Card k : failed) {
            what.append(what.length() > 0 ? ", " : "").append(k.kind).append(" -> ").append(k.last);
            for (Map.Entry<Item, Integer> e : k.shortOf.entrySet()) lack.put(e.getKey(), Math.max(lack.containsKey(e.getKey()) ? lack.get(e.getKey()) : 0, e.getValue()));
            if (k.last == Result.NO_PATH || k.last == Result.TIMEOUT || k.last == Result.BLOCKED) way = true;
        }
        if (ph instanceof Day1.Base) {   // a base: what of its bill is still to be made, in raw things
            Map<String, Integer> bill = ((Day1.Base) ph).toMake(c, w);
            int logs = Day1.logsFor(bill) - Day1.crewLogs(c, w);
            if (logs > 0) lack.put(Day1.LOG, logs);
            int torches = Day1.get(bill, "torch");
            if (torches > 0) lack.put(Day1.TORCH, torches);
        }
        Events.log("replan: after " + ph.kind + (outOfTime ? " (out of time)" : "") + ": " + what + "; short of " + names(lack));
        List<Crew.Phase> mend = new ArrayList<Crew.Phase>();
        List<String> cannot = new ArrayList<String>();
        for (Map.Entry<Item, Integer> e : lack.entrySet()) topUp(c, w, e.getKey(), e.getValue(), mend, cannot, 0);
        if ((!mend.isEmpty() || way) && cannot.isEmpty() && replans < 4 && !phaseRetry.givenUp(ph.kind)) {
            replans++;
            phaseRetry.failed(ph.kind, 0);   // the one retry policy: a phase goes again at most its tries
            Crew.Phase again = again(ph);
            if (again != null) mend.add(named(again, ph.kind));
            for (int i = mend.size() - 1; i >= 0; i--) {   // before what was planned next
                mend.get(i).origin = Origin.REPLAN;
                c.phases.addFirst(mend.get(i));
            }
            StringBuilder k = new StringBuilder();
            for (Crew.Phase x : mend) k.append(k.length() > 0 ? ", " : "").append(x.kind);
            Events.log("replan: " + k + " (" + replans + "/4)");
            return;
        }
        tellGuide(c, "The crew's " + ph.kind + " ended" + (outOfTime ? " out of time" : "") + " with: " + what
                + (cannot.isEmpty() ? "" : "; cannot be had near: " + cannot) + ". Stock: logs " + Day1.crewLogs(c, w)
                + ". If the goals should change, call crew_plan with the new goals; otherwise say nothing about it.");
    }

    /** the phases that get n more of an item: raw things from the ground, made things by the phase that makes them */
    static void topUp(Crew c, WorldServer w, Item item, int n, List<Crew.Phase> mend, List<String> cannot, int depth) {
        if (n <= 0 || depth > 4) return;
        if (item == Day1.LOG || item == Day1.LOG2) {
            int standing = 0;
            Ground.Flood f = Ground.baseFlood(c, w);
            for (int[] t : c.trees(w)) if (f == null || f.near(t[0], t[1], t[2], Crew.REACH) != null) standing += t[3];
            if (standing == 0) { cannot.add("wood"); return; }
            if (!has(mend, "wood")) mend.add(named(new Day1.WoodChain(3, Day1.crewLogs(c, w) + Math.min(n, standing)), "wood"));
            if (standing < n) cannot.add("wood (" + standing + " of " + n + " logs around)");
            return;
        }
        if (item == Day1.COBBLE) {   // rock open to the air first; what it lacks, from the stairs going deeper
            int open = quarryFailed ? 0 : Day1.openRock(c, w, n);   // a quarry that found nothing: the stairs only
            if (open > 0) mend.add(named(new Day1.Quarry(Math.min(n, open)), "quarry"));
            if (open < n) {
                int steps = (int) Math.ceil((n - open) / 4.0);   // a new step of two lanes gives about 4 stone (some is dirt)
                Day1.mineDepth = Math.max(Day1.mineDepth, 8) + steps;
                mend.add(named(new Day1.Mine(Day1.mineDepth), "mine"));
            }
            return;
        }
        // a made thing: its raw things first (less what the crew has), then the phase that makes it
        Map<Item, Integer> raw = new LinkedHashMap<Item, Integer>();
        unfold(item, n, raw, new LinkedHashMap<String, Integer>(), 0);
        for (Map.Entry<Item, Integer> e : raw.entrySet()) {
            int have = Day1.Base.have(c, w, e.getKey()) + (e.getKey() == Day1.LOG ? Day1.Base.have(c, w, Day1.LOG2) : 0);
            topUp(c, w, e.getKey(), e.getValue() - have, mend, cannot, depth + 1);
        }
        Crew.Phase made = null;   // one upgrade makes torches and a pickaxe together
        for (Crew.Phase x : mend) if (x instanceof Day1.Upgrade) made = x;
        if (item == Day1.TORCH || item == Items.coal) {
            if (made != null) ((Day1.Upgrade) made).torches += item == Day1.TORCH ? n : n * 4;
            else mend.add(named(new Day1.Upgrade(item == Day1.TORCH ? n : n * 4, false, false), "upgrade"));
        } else if (item == Items.stone_pickaxe) {
            if (made != null) ((Day1.Upgrade) made).giftPickaxe = true;
            else mend.add(named(new Day1.Upgrade(0, false, true), "upgrade"));
        }
        else if (item == Items.wooden_pickaxe) { if (!has(mend, "tools")) mend.add(named(new Day1.Tools(), "tools")); }
        // planks and sticks are made by the phase that uses them (the base's Prepare, the furnace's card, Make): their logs are topped up above
        else if (item == Day1.FURNACE) mend.add(named(new Day1.Upgrade(0, false, false), "upgrade"));
        // the base's own things (fences, a door) are made by its Prepare when it goes again
    }

    static String names(Map<Item, Integer> m) {
        StringBuilder b = new StringBuilder("{");
        for (Map.Entry<Item, Integer> e : m.entrySet()) b.append(b.length() > 1 ? ", " : "").append(Ledger.name(new ItemStack(e.getKey()))).append(' ').append(e.getValue());
        return b.append('}').toString();
    }

    static final java.util.Set<String> succeeded = new java.util.HashSet<String>();
    static boolean quarryFailed;
    static final Retry phaseRetry = new Retry();

    /** a fresh phase like this one (same parameters) */
    static Crew.Phase again(Crew.Phase ph) {
        if (ph instanceof Day1.WoodChain) return new Day1.WoodChain(3, ((Day1.WoodChain) ph).target);
        if (ph instanceof Day1.Upgrade) { Day1.Upgrade u = (Day1.Upgrade) ph; return new Day1.Upgrade(u.torches, u.crewStone, u.giftPickaxe); }
        if (ph instanceof Day1.Gift) return new Day1.Gift(((Day1.Gift) ph).items);
        if (ph instanceof Day1.Base) return new Day1.Base(((Day1.Base) ph).maxSize);
        if (ph instanceof Day1.Tools) return new Day1.Tools();
        if (ph instanceof Day1.Mine) return new Day1.Mine(((Day1.Mine) ph).depth);
        if (ph instanceof Day1.Quarry) return new Day1.Quarry(((Day1.Quarry) ph).n);
        if (ph instanceof Day1.Gather) return new Day1.Gather();
        return null;
    }

    static boolean has(List<Crew.Phase> l, String kind) {
        for (Crew.Phase p : l) if (p.kind.equals(kind)) return true;
        return false;
    }

    static int told;

    /** a word to the guide (the server mod), which may answer with new goals */
    static void tellGuide(Crew c, String text) {
        if (told >= 2 || c.player == null) { Events.log("replan: not told to the guide (" + told + "): " + text); return; }
        told++;
        try {
            java.lang.reflect.Method m = Class.forName("hardelele.aiguide.Tools").getDeclaredMethod("crewEvent", String.class, String.class);
            m.setAccessible(true);
            Object r = m.invoke(null, c.player, text);
            Events.log("replan: told the guide (" + told + "/2): " + text + " -> " + r);
        } catch (Exception e) {
            Events.log("replan: the guide is not there: " + e);
        }
    }
}
