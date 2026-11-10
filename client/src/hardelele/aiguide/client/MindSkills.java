package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraft.world.WorldServer;

/**
 * The skills an agent's mind calls, one at a time. Each is a crew task of its own small automaton over what is there
 * already (Crew.GoTo, Crew.Chop, Crew.Mine, Day1.Steps, Skills.craftOnce, PlaceFrom, Give: the body's honest actions,
 * the reflexes before them) and ends with how it went: OK, or a reason code with words the model can act on (NO_TABLE:
 * the recipe is 3x3 and no table is known; MISSING: what one craft takes). Targets come from what the crew has seen
 * (Exploration), never from a scan of the hidden world. What it takes is reserved on the board (a tree, the blocks).
 */
final class MindSkills {
    private MindSkills() { }

    /** a skill as a crew task: how it ended (OK or a reason code) and its words, for the mind */
    abstract static class Skill implements Crew.Task {
        String reason, detail = "";
        volatile Result result;
        int lease = 20 * 180;
        volatile String interrupt;   // an event of the reflexes (ends a wait)

        public final Result step(Agent a, WorldServer w) {
            if (result != null) return result;
            Result r;
            try {
                r = run(a, w);
            } catch (Exception e) {
                r = Result.BLOCKED;
                reason = "ERROR";
                detail = String.valueOf(e);
            }
            if (r.done()) {
                if (reason == null) reason = r == Result.OK ? "OK" : r.name();
                if (r.failed() && detail.isEmpty() && a.why != null) detail = a.why;
                result = r;
            }
            return r;
        }

        abstract Result run(Agent a, WorldServer w);

        /** ended before it began (nothing to do it with): the reason for the model */
        Skill no(String reason, String detail) {
            this.reason = reason;
            this.detail = detail;
            result = Result.BLOCKED;
            return this;
        }
    }

    /**
     * A skill whose goal is on the surface (the base, home) from under the ground: the skill as it is first; if its walk
     * finds no way (NO_PATH) and the agent is under the ground, up (ascend's way: the stairs, else steps dug up over
     * itself), then the skill again, made anew from up there. On the surface, or no mine: the skill as it is.
     */
    static Skill upFirst(final Crew c, Agent a, WorldServer w, final java.util.function.Supplier<Skill> make) {
        final Skill first = make.get();
        if (c == null || first.result != null || RlWorld.now.mouth == null || RlWorld.now.stairs.isEmpty() || !MineSkills.underground(c, a)) return first;
        Skill s = new Skill() {
            Skill cur = first, up;
            boolean again;
            Result run(Agent ag, WorldServer ww) {
                if (up != null) {
                    Result r = up.step(ag, ww);
                    if (r == Result.RUNNING) return r;
                    if (r.failed()) { reason = up.reason; detail = "no way on foot, and no way up from under the ground: " + up.detail; return r; }
                    up = null;
                    cur = make.get();
                    again = true;
                }
                Result r = cur.step(ag, ww);
                if (r == Result.RUNNING) return r;
                if (!again && r == Result.NO_PATH && MineSkills.underground(c, ag)) {   // no way from down here: up first, then again
                    up = MineSkills.ascend(c, ag, ww);
                    if (up.result == null) { lease += up.lease; return Result.RUNNING; }
                    up = null;
                }
                reason = cur.reason;
                detail = (again ? "up from the mine, then: " : "") + cur.detail;
                return r;
            }
        };
        s.lease = first.lease;
        return s;
    }

    static Skill failed(String reason, String detail) {
        return new Skill() { Result run(Agent a, WorldServer w) { return Result.BLOCKED; } }.no(reason, detail);
    }

    /** a skill wrapping a task: its failures named (reason, words) by the function given */
    interface Why { String[] of(Result r); }

    static Skill wrap(final Crew.Task t, final Why why) {
        return new Skill() {
            Result run(Agent a, WorldServer w) {
                Result r = t.step(a, w);
                if (r.failed() && why != null) {
                    String[] n = why.of(r);
                    if (n != null) { reason = n[0]; detail = n[1]; }
                }
                return r;
            }
        };
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** the skill of a call (an ended one when it cannot even begin) */
    static Skill make(Crew c, Agent a, WorldServer w, Mind.Call call) {
        int n = call.num("count", 1);
        switch (call.tool) {
            case "look_around": return new Look();
            case "go_to": {
                String tg = call.arg("target") == null ? "" : call.arg("target").trim().toLowerCase(Locale.ROOT);
                if (!tg.startsWith("base") && !tg.startsWith("home")) return goTo(c, a, w, call.arg("target"));
                final String target = call.arg("target");
                return upFirst(c, a, w, () -> goTo(c, a, w, target));   // the base is on the surface
            }
            case "chop_tree": return chop(c, a, w, call.arg("at"));
            case "mine": return mine(c, a, w, call.arg("block"), clamp(n, 1, 32));
            case "craft": return craft(c, a, w, call.arg("item"), clamp(n, 1, 64));
            case "smelt": {
                String what = call.arg("item");
                if (what.equalsIgnoreCase("food")) {   // the raw food of the bag, whichever it is
                    what = RlSkills.rawFood(a);
                    if (what == null) return failed("NO_FOOD", "no raw food in the bag (raw meat, fish, a potato) to cook");
                }
                return smelt(c, a, w, what, clamp(n, 1, 64), call.arg("fuel"));
            }
            case "place": return place(c, a, w, call.arg("item"), call.arg("at"));
            case "take": case "put": return chest(c, a, w, call.tool.equals("take"), call.arg("chest"), call.arg("item"), clamp(n, 1, 64 * 27));
            case "give": return give(c, a, w, call.arg("to"), call.arg("item"), clamp(n, 1, 64 * 9));
            case "wait": return new Wait(clamp(call.num("seconds", 5), 1, 120));
            case "hunt": return RlSkills.hunt(c, a, w);
            default: {   // contract 3's skills, then the defence's (or UNKNOWN_TOOL)
                Skill s = PrimSkills.make(c, a, w, call);
                if (s == null) s = MineSkills.make(c, a, w, call);
                if (s == null) s = GearSkills.make(c, a, w, call);
                if (s == null) s = FarmSkills.make(c, a, w, call);
                return s != null ? s : DefenseSkills.make(c, a, w, call);
            }
        }
    }

    // ---- names: the game's ids without "minecraft:", ":N" for a variant ---------------------------------------

    static String id(Item it, int meta) {
        Object o = Item.itemRegistry.getNameForObject(it);
        String n = o == null ? "?" : o.toString();
        if (n.startsWith("minecraft:")) n = n.substring(10);
        if (meta == 32767) return n + " (any)";
        if (meta > 0 && it.getHasSubtypes()) n += ":" + meta;
        return n;
    }

    static String id(ItemStack s) {
        return s == null || s.getItem() == null ? "?" : id(s.getItem(), s.getMetadata());
    }

    static String idOf(Block b) {
        Object o = Block.blockRegistry.getNameForObject(b);
        String n = o == null ? "?" : o.toString();
        return n.startsWith("minecraft:") ? n.substring(10) : n;
    }

    static final String[][] ALIASES = { { "wood", "log" }, { "oak_log", "log" }, { "logs", "log" }, { "oak_planks", "planks" }, { "wooden_planks", "planks" },
            { "plank", "planks" }, { "sticks", "stick" }, { "table", "crafting_table" }, { "workbench", "crafting_table" }, { "cobble", "cobblestone" },
            { "charcoal", "coal:1" }, { "torches", "torch" }, { "wood_pickaxe", "wooden_pickaxe" }, { "wood_axe", "wooden_axe" } };

    /** an item by its id ("planks", "planks:2", "minecraft:log", a few plain words): { Item, meta (-1 any) }, or null */
    static Object[] item(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace("minecraft:", "");
        for (String[] al : ALIASES) if (t.equals(al[0])) t = al[1];
        int meta = -1;
        Matcher m = Pattern.compile("^(.*?):(\\d+)$").matcher(t);
        if (m.matches()) { t = m.group(1); meta = Integer.parseInt(m.group(2)); }
        if (t.isEmpty()) return null;
        Item it = Compiler.item(t);
        if (it == null && t.endsWith("s")) it = Compiler.item(t.substring(0, t.length() - 1));
        return it == null ? null : new Object[] { it, meta };
    }

    /** the bag by ids */
    static Map<String, Integer> bag(Agent a) {
        Map<String, Integer> m = new LinkedHashMap<String, Integer>();
        for (ItemStack s : a.bag) if (s != null && s.getItem() != null) Ledger.add(m, id(s), s.stackSize);
        return m;
    }

    /** what changed between two bags: "+5 log, -2 planks" */
    static String diff(Map<String, Integer> before, Map<String, Integer> after) {
        StringBuilder b = new StringBuilder();
        Set<String> keys = new java.util.LinkedHashSet<String>(before.keySet());
        keys.addAll(after.keySet());
        for (String k : keys) {
            int d = (after.containsKey(k) ? after.get(k) : 0) - (before.containsKey(k) ? before.get(k) : 0);
            if (d != 0) b.append(b.length() > 0 ? ", " : "").append(d > 0 ? "+" : "").append(d).append(' ').append(k);
        }
        return b.toString();
    }

    static int[] coords(String s) {
        Matcher m = Pattern.compile("(-?\\d+)[\\s,]+(-?\\d+)[\\s,]+(-?\\d+)").matcher(s == null ? "" : s);
        return m.find() ? new int[] { Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)) } : null;
    }

    static double hdist(Agent a, int[] p) {
        return Math.hypot(a.posX - (p[0] + 0.5), a.posZ - (p[2] + 0.5));
    }

    static void nearestFirst(List<int[]> l, final Agent a) {
        Collections.sort(l, new Comparator<int[]>() {
            @Override public int compare(int[] p, int[] q) { return Double.compare(Crew.dist(a, p[0] + 0.5, p[1], p[2] + 0.5), Crew.dist(a, q[0] + 0.5, q[1], q[2] + 0.5)); }
        });
    }

    // ---- stations: the crew's own and the ones seen ----------------------------------------------------------

    static Block[] kinds(Roles.Role role) {
        switch (role) {
            case TABLE: return new Block[] { Blocks.crafting_table };
            case CHEST: return new Block[] { Blocks.chest, Blocks.trapped_chest };
            case FURNACE: return new Block[] { Blocks.furnace, Blocks.lit_furnace };
            default: return new Block[0];
        }
    }

    static int[] own(Crew c, Roles.Role role) {
        return role == Roles.Role.TABLE ? c.table : role == Roles.Role.CHEST ? c.chest : role == Roles.Role.FURNACE ? Day1.furnace : null;
    }

    /** the stations of a kind the crew knows within r (its own first, then the ones seen), nearest first; only those that still stand */
    static List<int[]> stations(Crew c, Agent a, WorldServer w, Roles.Role role, double r) {
        List<int[]> l = new ArrayList<int[]>();
        Set<Long> had = new HashSet<Long>();
        int[] o = own(c, role);
        List<int[]> cand = new ArrayList<int[]>();
        if (o != null) cand.add(o);
        for (Block b : kinds(role)) cand.addAll(Exploration.knownBlocks(w, b));
        for (int[] p : cand)
            if (had.add(Crew.key(p[0], p[1], p[2])) && Crew.dist(a, p[0] + 0.5, p[1], p[2] + 0.5) <= r && Roles.is(w, p, role)) l.add(p);
        nearestFirst(l, a);
        return l;
    }

    static int[] station(Crew c, Agent a, WorldServer w, Roles.Role role, double r) {
        List<int[]> l = stations(c, a, w, role, r);
        return l.isEmpty() ? null : l.get(0);
    }

    /** a free cell beside the agent to put a block in: room, firm ground under it, nobody standing in it, a face seen to put it against */
    static int[] beside(Crew c, Agent a, WorldServer w) {
        int[] f = Ground.feet(w, a.posX, a.posY, a.posZ);
        for (int r = 1; r <= 2; r++)
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int dy : new int[] { 0, -1, 1 }) {
                        int x = f[0] + dx, y = f[1] + dy, z = f[2] + dz;
                        if (!Crew.soft(w, x, y, z) || !Crew.solid(w, x, y - 1, z) || Ground.liquid(w, x, y, z)) continue;
                        boolean taken = a.boundingBox.intersectsWith(net.minecraft.util.AxisAlignedBB.getBoundingBox(x, y, z, x + 1, y + 1, z + 1))
                                || a.boundingBox.intersectsWith(net.minecraft.util.AxisAlignedBB.getBoundingBox(x, y - 1, z, x + 1, y, z + 1));   // its own body (on stairs too)
                        for (Doer d : c.doers())
                            if (d.alive() && Math.floor(d.px()) == x && Math.floor(d.pz()) == z && Math.abs(Math.floor(d.py()) - y) <= 1) taken = true;
                        // and a face to put it against that the eyes see from here (else place finds none: NOT_VISIBLE under the ground)
                        if (!taken && a.body.seesFace(w, x, y, z)) return new int[] { x, y, z };
                    }
                }
        return null;
    }

    /** the crew's station from now on, if it has none of this kind (or its own is gone) */
    static void adopt(Crew c, WorldServer w, int[] at, Item placed) {
        if (placed == Day1.TABLE && !Roles.is(w, c.table, Roles.Role.TABLE)) c.table = at.clone();
        else if (placed == Day1.CHEST && !Roles.is(w, c.chest, Roles.Role.CHEST)) c.chest = at.clone();
        else if (placed == Day1.FURNACE && !Roles.is(w, Day1.furnace, Roles.Role.FURNACE)) Day1.furnace = at.clone();
    }

    // ---- look around -----------------------------------------------------------------------------------------

    /** a turn round of three seconds, the head up and down: the crew's memory (Exploration) sees what is about */
    static final class Look extends Skill {
        int t;
        double yaw0 = Double.NaN;
        Look() { lease = 20 * 20; }
        Result run(Agent a, WorldServer w) {
            if (Double.isNaN(yaw0)) yaw0 = Math.toRadians(a.rotationYawHead);
            a.body.stop();
            double ang = yaw0 + t * Math.PI * 2 / 60.0, up = (t / 10) % 2 == 0 ? -3 : 2;
            a.body.lookAt(a.posX - Math.sin(ang) * 8, a.posY + a.getEyeHeight() + up, a.posZ + Math.cos(ang) * 8);
            if (t % 20 == 10) Exploration.observe(Crew.instance, a, w);
            return ++t >= 60 ? Result.OK : Result.RUNNING;
        }
    }

    // ---- go to -----------------------------------------------------------------------------------------------

    /** a place by words: { x, y, z, within } of 'x y z', table, chest, furnace, player, agent N, home; null if none */
    static double[] where(Crew c, Agent a, WorldServer w, String target) {
        String t = target == null ? "" : target.trim().toLowerCase(Locale.ROOT);
        if (t.equals("frontier")) {   // where the crew has not looked yet (the edge of the ground it saw)
            int[] f = RlSkills.frontier(a, w);
            return f == null ? null : new double[] { f[0] + 0.5, f[1], f[2] + 0.5, 1.5 };
        }
        int[] p = coords(t);
        if (p != null) return new double[] { p[0] + 0.5, p[1], p[2] + 0.5, 1.5 };
        Roles.Role role = t.contains("table") || t.contains("верстак") ? Roles.Role.TABLE : t.contains("chest") || t.contains("сундук") ? Roles.Role.CHEST
                : t.contains("furnace") || t.contains("печ") ? Roles.Role.FURNACE : null;
        if (role != null) {
            int[] s = station(c, a, w, role, 96);
            return s == null ? null : new double[] { s[0] + 0.5, s[1], s[2] + 0.5, 2.3 };
        }
        if (t.startsWith("player") || t.startsWith("игрок") || t.equals("me")) {
            EntityPlayerMP pl = c.playerIn(w);
            return pl == null ? null : new double[] { pl.posX, pl.boundingBox.minY, pl.posZ, 2.5 };
        }
        Matcher m = Pattern.compile("(?:agent|агент)\\s*(\\d+)").matcher(t);
        if (m.find()) {
            Doer d = c.doer(Integer.parseInt(m.group(1)));
            return d == null ? null : new double[] { d.px(), d.py(), d.pz(), 2.5 };
        }
        if ((t.startsWith("home") || t.startsWith("base") || t.startsWith("дом")) && c.base != null) {
            int[] b = Ground.anchor(c, w);
            return new double[] { b[0] + 0.5, b[1], b[2] + 0.5, 1.5 };
        }
        return null;
    }

    static Skill goTo(Crew c, Agent a, WorldServer w, String target) {
        double[] p = where(c, a, w, target);
        if (p == null) return failed("UNKNOWN_TARGET", "'" + target + "': give 'x y z' from the state, or table, chest, furnace, player, agent N (none such known)");
        Skill s = wrap(new Crew.GoTo(p[0], p[1], p[2], p[3]), r -> r == Result.NO_PATH ? new String[] { "NO_PATH", "no way there on foot from here" } : null);
        s.lease = Budget.ticks(Crew.dist(a, p[0], p[1], p[2]) * 1.5, 0, 0, 0, 0, 0);
        return s;
    }

    // ---- chop a tree -----------------------------------------------------------------------------------------

    /** the trunks the crew has seen within r: { x, y (the lowest log seen), z }, nearest first; others' reserved left out unless asked */
    static List<int[]> trunks(Crew c, Agent a, WorldServer w, double r, boolean reservedToo) {
        // the logs it remembers near looked at again (a felled tree is forgotten, #1053), and a forgotten trunk's
        // failed walks with it (the crew's "not reached" and the agent's own): nothing to go back to
        for (int[] g : Exploration.recheck(a, w, LOGS, 10, 48)) {
            crewUnreached.remove(Crew.key(g[0], g[1], g[2]));
            a.walks.forget(Crew.key(g[0], g[1], g[2]));
        }
        Map<Long, int[]> col = new LinkedHashMap<Long, int[]>();
        for (Block b : new Block[] { Blocks.log, Blocks.log2 })
            for (int[] p : Exploration.knownBlocks(w, b)) {
                if (hdist(a, p) > r) continue;
                long k = ((long) p[0] << 32) ^ (p[2] & 0xffffffffL);
                int[] o = col.get(k);
                if (o == null || p[1] < o[1]) col.put(k, p.clone());
            }
        List<int[]> l = new ArrayList<int[]>();
        for (int[] p : col.values()) {
            boolean neighbour = false;   // a big tree's second trunk (2x2) or a branch: one entry for the tree
            for (int[] q : l) if (Math.abs(q[0] - p[0]) <= 1 && Math.abs(q[2] - p[2]) <= 1) neighbour = true;
            if (neighbour) continue;
            boolean taken = false;   // reserved at its foot, which may be under the lowest log seen
            for (int dy = 0; dy <= 8 && !taken; dy++) taken = c.board.reserved(a, p[0], p[1] - dy, p[2]);
            if (!reservedToo && taken) continue;
            l.add(p);
        }
        nearestFirst(l, a);
        return l;
    }

    static final java.util.Set<String> LOGS = new java.util.HashSet<String>(java.util.Arrays.asList("minecraft:log", "minecraft:log2"));

    /** a cache of the path finder's answers: agent and trunk -> { tick, 1 reached / 0 not } (10 s) */
    static final Map<Long, long[]> reachCache = new java.util.HashMap<Long, long[]>();
    /** trunks no agent of the crew got to (its key -> until when it is not tried): shared, so four do not go to one */
    static final Map<Long, Long> crewUnreached = new java.util.HashMap<Long, Long>();
    static final int UNREACHED_TICKS = 20 * 120;
    /** a hanging top climbed by the skill itself: off (no detours inside the skills; the net may pillar up: pillar:up, dig@, place@) */
    static final boolean PILLAR_TOPS = Boolean.getBoolean("aiguide.chop.pillar");

    static void unreached(int[] t, long now) {
        if (crewUnreached.size() > 1024) crewUnreached.clear();
        crewUnreached.put(Crew.key(t[0], t[1], t[2]), now + UNREACHED_TICKS);
    }

    static boolean mayTryTrunk(Agent a, int[] t, long now) {
        Long until = crewUnreached.get(Crew.key(t[0], t[1], t[2]));
        return (until == null || until <= now) && a.walks.mayTry(Crew.key(t[0], t[1], t[2]), now);
    }

    /** the trunk's foot (its lowest log, the column down while logs) */
    static int footY(WorldServer w, int[] t) {
        int y = t[1];
        while (y > t[1] - 12 && (w.getBlock(t[0], y - 1, t[2]) == Blocks.log || w.getBlock(t[0], y - 1, t[2]) == Blocks.log2)) y--;
        return y;
    }

    /** chop it from the ground, up a pillar (a hanging top: blocks in the bag), or skip it (Reach.trunkPlan) */
    static String trunkPlan(Agent a, WorldServer w, int[] t) {
        int y = footY(w, t);
        int[][] around = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 }, { 1, 1 }, { -1, 1 }, { 1, -1 }, { -1, -1 }, { 2, 0 }, { -2, 0 }, { 0, 2 }, { 0, -2 } };
        int[] g = new int[around.length];
        for (int i = 0; i < around.length; i++) g[i] = Crew.ground(w, t[0] + around[i][0], y, t[2] + around[i][1]);
        return Reach.trunkPlan(y, g, PILLAR_TOPS && Skills.Scaffold.block(a, null) != null);
    }

    /** a walk gets there: the game's path finder ends within 2 of it */
    static boolean reached(Agent a, WorldServer w, int[] p) {
        long now = w.getTotalWorldTime(), key = Crew.key(p[0], p[1], p[2]) * 31 + a.skin();
        long[] c = reachCache.get(key);
        if (c != null && now - c[0] < 200 && now >= c[0]) return c[1] == 1;
        net.minecraft.pathfinding.PathEntity path = RlEngine.pathTo(a, p[0] + 0.5, p[1], p[2] + 0.5);
        net.minecraft.pathfinding.PathPoint end = path == null ? null : path.getFinalPathPoint();
        boolean ok = end != null && Math.abs(end.xCoord - p[0]) + Math.abs(end.zCoord - p[2]) <= 2 && Math.abs(end.yCoord - p[1]) <= 3;
        if (reachCache.size() > 4096) reachCache.clear();
        reachCache.put(key, new long[] { now, ok ? 1 : 0 });
        return ok;
    }

    /**
     * The free trunks seen near that a walk gets to, nearest first (the five nearest looked at): one not reached is
     * remembered as a failed walk's goal (Walks: tried again after a while), so chop never takes it again and again.
     */
    static List<int[]> reachableTrunks(Crew c, final Agent a, final WorldServer w, double r) {
        final long now = w.getTotalWorldTime();
        List<int[]> unreached = new ArrayList<int[]>();
        List<int[]> ok = Reach.reachable(trunks(c, a, w, r, false), p -> mayTryTrunk(a, p, now), p -> !trunkPlan(a, w, p).equals("skip") && reached(a, w, p), 5, unreached);
        for (int[] p : unreached) { a.walks.failed(Crew.key(p[0], p[1], p[2]), Crew.key((int) Math.floor(a.posX), (int) Math.floor(a.posY), (int) Math.floor(a.posZ)), now); unreached(p, now); }
        return ok;
    }

    static Skill chop(final Crew c, Agent a, WorldServer w, String at) {
        int[] want = coords(at);
        List<int[]> l = want == null ? reachableTrunks(c, a, w, 48) : trunks(c, a, w, 48, false);
        int[] t = null;
        if (want != null) {
            double bd = 3.5;
            for (int[] p : l) {
                double d = Math.hypot(p[0] - want[0], p[2] - want[2]);
                if (d <= bd) { bd = d; t = p; }
            }
            if (t == null) return failed("NO_TREE_THERE", "no tree the crew has seen at " + want[0] + " " + want[1] + " " + want[2] + " (or another agent has it)");
        } else if (!l.isEmpty()) t = l.get(0);
        if (t == null) return failed("NO_TREE_SEEN", "no tree in the crew's memory near here that a walk gets to (or all are taken by others): look_around, or go_to somewhere else");
        int y = t[1];   // down to the trunk's foot (it stands on what the eyes see there)
        while (y > t[1] - 12 && (w.getBlock(t[0], y - 1, t[2]) == Blocks.log || w.getBlock(t[0], y - 1, t[2]) == Blocks.log2)) y--;
        final int[] base = { t[0], y, t[2] };
        c.board.reserve(a, base[0], base[1], base[2]);
        final int[] trunk = t;
        if (PILLAR_TOPS && want == null && trunkPlan(a, w, t).equals("pillar")) {   // a top hanging in the air: the whole tree felled, up a pillar of its blocks (taken down after)
            List<int[]> logs = Fell.tree(w, base[0], base[1], base[2]);
            if (!logs.isEmpty()) {
                Skill f = wrap(new TaskBoard.Seq(new Fell(logs), new Crew.Collect(base[0] + 0.5, base[1], base[2] + 0.5, 6)), r -> {
                    if (r != Result.NO_PATH && r != Result.BLOCKED) return null;
                    unreached(trunk, w.getTotalWorldTime());
                    return new String[] { "NO_PATH", "could not get at the logs hanging at " + base[0] + " " + base[1] + " " + base[2] + " even up a pillar" };
                });
                f.lease = Budget.ticks(Crew.dist(a, base[0], base[1], base[2]) * 1.5 + 20, logs.size() * 2, 6, 0, 0, 60);
                f.detail = "a hanging top: up a pillar";
                return f;
            }
        }
        Skill s = wrap(new TaskBoard.Seq(new Crew.Chop(base[0], base[1], base[2]), new Crew.Collect(base[0] + 0.5, base[1], base[2] + 0.5, 5)), r -> {
            if (r != Result.NO_PATH) return null;
            Exploration.recheck(a, w, LOGS, 10, 48);   // there now: is it still standing? (a felled tree is no failure to remember, #1053)
            boolean still = false;
            for (int[] p : Exploration.knownBlocks(w, Blocks.log)) if (p[0] == trunk[0] && p[2] == trunk[2]) still = true;
            for (int[] p : Exploration.knownBlocks(w, Blocks.log2)) if (p[0] == trunk[0] && p[2] == trunk[2]) still = true;
            if (!still) return new String[] { "GONE", "the tree at " + base[0] + " " + base[1] + " " + base[2] + " is not there any more" };
            a.walks.failed(Crew.key(trunk[0], trunk[1], trunk[2]), Crew.key((int) Math.floor(a.posX), (int) Math.floor(a.posY), (int) Math.floor(a.posZ)), w.getTotalWorldTime());   // not this one again soon
            unreached(trunk, w.getTotalWorldTime());   // nor by the others
            return new String[] { "NO_PATH", "no spot beside the tree at " + base[0] + " " + base[1] + " " + base[2] + " to chop it from" };
        });
        s.lease = Budget.ticks(Crew.dist(a, base[0], base[1], base[2]) * 1.5 + 8, 7, 0, 0, 0, 40);
        return s;
    }

    // ---- mine ------------------------------------------------------------------------------------------------

    /** a tool in the bag that gets the block's drop (or the block needs none) */
    static boolean canHarvest(Agent a, Block b) {
        if (b.getMaterial().isToolNotRequired()) return true;
        for (ItemStack s : a.bag)
            if (s != null && (s.canItemHarvestBlock(b) || net.minecraftforge.common.ForgeHooks.canToolHarvestBlock(b, 0, s))) return true;
        return false;
    }

    static String toolFor(Block b) {
        if (b == Blocks.iron_ore || b == Blocks.lapis_ore) return "a pickaxe (stone_pickaxe or better)";
        if (b == Blocks.gold_ore || b == Blocks.diamond_ore || b == Blocks.redstone_ore || b == Blocks.lit_redstone_ore || b == Blocks.emerald_ore) return "a pickaxe (iron_pickaxe or better)";
        if (b == Blocks.obsidian) return "a diamond_pickaxe";
        if (b.getMaterial() == net.minecraft.block.material.Material.rock || b.getMaterial() == net.minecraft.block.material.Material.iron) return "a pickaxe (wooden_pickaxe or better)";
        return "the right tool";
    }

    /** a cell beside the block to stand in and dig it from (its level or one lower), or null */
    static int[] mineSpot(WorldServer w, int[] p) {
        for (int[] d : new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } })
            for (int dy = 0; dy >= -1; dy--)
                if (Crew.stand(w, p[0] + d[0], p[1] + dy, p[2] + d[1])) return new int[] { p[0] + d[0], p[1] + dy, p[2] + d[1] };
        return null;
    }

    static Skill mine(final Crew c, Agent a, WorldServer w, String name, int n) {
        String t = name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "").replace(' ', '_');
        if (t.equals("cobblestone") || t.equals("cobble") || t.equals("rock")) t = "stone";
        if (t.equals("coal")) t = "coal_ore";
        if (t.equals("iron")) t = "iron_ore";
        final Block b = Block.getBlockFromName("minecraft:" + t) != null ? Block.getBlockFromName("minecraft:" + t) : Block.getBlockFromName(t);
        if (b == null || b == Blocks.air) return failed("UNKNOWN_BLOCK", "'" + name + "': the block's game id (stone, coal_ore, iron_ore, sand, dirt...)");
        if (!canHarvest(a, b)) return failed("NO_TOOL", idOf(b) + " gives nothing without " + toolFor(b) + "; nothing such in the bag");
        List<int[]> seen = new ArrayList<int[]>(Exploration.knownBlocks(w, b));
        if (b == Blocks.stone) seen.addAll(Exploration.knownBlocks(w, Blocks.cobblestone));
        List<int[]> near = new ArrayList<int[]>();
        for (int[] p : seen) if (hdist(a, p) <= 32 && Math.abs(p[1] - a.posY) <= 12) near.add(p);
        nearestFirst(near, a);
        List<int[]> pick = new ArrayList<int[]>();
        int checks = 0;
        for (int[] p : near) {
            if (pick.size() >= n || checks > 24) break;
            Block is = w.getBlock(p[0], p[1], p[2]);
            if (is != b && !(b == Blocks.stone && is == Blocks.cobblestone)) continue;   // gone since it was seen
            if (c.board.reserved(a, p[0], p[1], p[2]) || Reacts.standsOn(a, w, p[0], p[1], p[2]) || !Ground.mayDig(c, p[0], p[1], p[2], "gather")) continue;
            if (!pick.isEmpty() && Math.hypot(p[0] - pick.get(0)[0], p[2] - pick.get(0)[2]) > 6) continue;   // a few together
            checks++;
            if (!Ground.digLeavesWay(c, w, p[0], p[1], p[2], "gather")) continue;   // the dig would leave a pit with no way back
            int[] spot = mineSpot(w, p);   // where one stands to dig it
            // an ore (not stone) with no place beside it to stand, or none a walk gets to: not picked (it came to BLOCKED, 43 % of the allowed)
            if (b != Blocks.stone && (spot == null || !reached(a, w, spot))) continue;
            pick.add(spot == null ? new int[] { p[0], p[1], p[2] } : new int[] { p[0], p[1], p[2], spot[0], spot[1], spot[2] });
        }
        if (pick.isEmpty()) {
            if (b != Blocks.stone) return failed("NOT_SEEN", "no " + idOf(b) + " the crew has seen within reach of a walk (or all taken): look_around, or go_to where it was seen");
            // no stone seen: safe steps down from here, the way the agent looks (each step a column of three, a floor under each)
            int[] f = Ground.feet(w, a.posX, a.posY, a.posZ);
            double yaw = Math.toRadians(a.rotationYawHead);
            double fx = -Math.sin(yaw), fz = Math.cos(yaw);
            int dx = Math.abs(fx) >= Math.abs(fz) ? (fx > 0 ? 1 : -1) : 0, dz = dx == 0 ? (fz > 0 ? 1 : -1) : 0;
            Skill s = wrap(new TaskBoard.Seq(new Day1.Steps(f[0], f[1], f[2], dx, dz, 6), new Crew.Collect(a.posX, a.posY, a.posZ, 6)),
                    r -> r == Result.NO_TOOL ? new String[] { "NO_TOOL", "stone in the way needs " + toolFor(Blocks.stone) } : null);
            s.detail = "no stone seen: dug steps down from " + f[0] + " " + f[1] + " " + f[2];
            s.lease = Budget.ticks(12, 18, 6, 0, 0, 60);
            return s;
        }
        for (int[] p : pick) c.board.reserve(a, p[0], p[1], p[2]);
        final int got = pick.size();
        Skill s = wrap(new TaskBoard.Seq(new Crew.Mine(pick), new Crew.Collect(pick.get(0)[0] + 0.5, pick.get(0)[1], pick.get(0)[2] + 0.5, 5)), r -> {
            if (r == Result.NO_TOOL) return new String[] { "NO_TOOL", idOf(b) + " needs " + toolFor(b) };
            if (r == Result.BLOCKED) return new String[] { "BLOCKED", "could not get at any of the " + got + " blocks picked (not seen or not reached from where it got to)" };
            return null;
        });
        if (got < n) s.detail = "only " + got + " seen near that could be dug";
        s.lease = Budget.ticks(Crew.dist(a, pick.get(0)[0], pick.get(0)[1], pick.get(0)[2]) * 1.5 + got * 3, got * 2, 0, 0, 0, 60);
        return s;
    }

    // ---- recipes ---------------------------------------------------------------------------------------------

    /** what one craft takes: "3 cobblestone, 2 stick" */
    static String takes(Skills.Laid l) {
        Map<String, Integer> m = new LinkedHashMap<String, Integer>();
        for (List<ItemStack> cell : l.cells) if (cell != null) Ledger.add(m, id(cell.get(0)) + (cell.size() > 1 ? " (or the like)" : ""), 1);
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Integer> e : m.entrySet()) b.append(b.length() > 0 ? ", " : "").append(e.getValue()).append(' ').append(e.getKey());
        return b.toString();
    }

    /** what of one craft the bag lacks: "3 cobblestone (has 0), 2 stick (has 1)" */
    static String lacks(Agent a, Skills.Laid l) {
        Map<String, int[]> m = new LinkedHashMap<String, int[]>();   // ingredient -> { needed, had }
        for (List<ItemStack> cell : l.cells) {
            if (cell == null) continue;
            String k = id(cell.get(0));
            int[] v = m.get(k);
            if (v == null) {
                int had = 0;
                for (ItemStack opt : cell) had = Math.max(had, Skills.count(a.bag, opt.getItem(), opt.getMetadata()));
                m.put(k, v = new int[] { 0, had });
            }
            v[0]++;
        }
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, int[]> e : m.entrySet())
            if (e.getValue()[1] < e.getValue()[0]) b.append(b.length() > 0 ? ", " : "").append(e.getValue()[0]).append(' ').append(e.getKey()).append(" (has ").append(e.getValue()[1]).append(')');
        return b.toString();
    }

    /** of the item's recipes: the first the bag can do (the plain day-one ones first), else null */
    static Skills.Laid doable(Agent a, List<Skills.Laid> rs) {
        for (int pass = 0; pass < 2; pass++)
            for (Skills.Laid l : rs) if ((pass == 1 || Compiler.simple(l)) && Skills.pick(a.bag, l) != null) return l;
        return null;
    }

    static Skills.Laid plainest(List<Skills.Laid> rs) {
        for (Skills.Laid l : rs) if (Compiler.simple(l)) return l;
        return rs.isEmpty() ? null : rs.get(0);
    }

    /** the inputs the furnace turns into the item (ids), up to 3 */
    @SuppressWarnings("unchecked")
    static List<String> smeltsFrom(Item item, int meta) {
        List<String> l = new ArrayList<String>();
        for (Map.Entry<Object, Object> e : ((Map<Object, Object>) FurnaceRecipes.instance().getSmeltingList()).entrySet()) {
            ItemStack out = (ItemStack) e.getValue(), in = (ItemStack) e.getKey();
            if (out != null && out.getItem() == item && (meta < 0 || out.getMetadata() == meta) && in != null && l.size() < 3) l.add(id(in));
        }
        return l;
    }

    /** the game's recipes of an item, in brief, for the model */
    static String howToMake(String name) {
        Object[] it = item(name);
        if (it == null) return "UNKNOWN_ITEM: '" + name + "': use the game's id (stick, crafting_table, stone_pickaxe...)";
        Item item = (Item) it[0];
        int meta = (Integer) it[1];
        StringBuilder b = new StringBuilder();
        Set<String> had = new HashSet<String>();
        for (Skills.Laid l : Skills.recipesFor(item, meta)) {
            String t = takes(l);
            if (!had.add(t) || had.size() > 3) continue;
            b.append(b.length() > 0 ? "; " : "").append(id(l.out)).append(" x").append(l.out.stackSize).append(" by ")
                    .append(l.small() ? "2x2 (in the hands)" : "3x3 (at a crafting table)").append(": one craft takes ").append(t);
        }
        List<String> from = smeltsFrom(item, meta);
        if (!from.isEmpty()) b.append(b.length() > 0 ? "; " : "").append("smelted in a furnace from ").append(String.join(" or ", from));
        if (b.length() == 0) b.append(id(item, Math.max(0, meta))).append(": no recipe; it is got from the world (chopped, mined, picked up)");
        return b.toString();
    }

    // ---- craft -----------------------------------------------------------------------------------------------

    static Skill craft(Crew c, Agent a, WorldServer w, String name, int want) {
        Object[] it = item(name);
        if (it == null) return failed("UNKNOWN_ITEM", "'" + name + "': use the game's id (planks, stick, crafting_table, wooden_pickaxe...)");
        Item item = (Item) it[0];
        int meta = (Integer) it[1];
        List<Skills.Laid> rs = Skills.recipesFor(item, meta);
        if (rs.isEmpty()) {
            List<String> from = smeltsFrom(item, meta);
            return failed("NO_RECIPE", id(item, Math.max(0, meta)) + " is not crafted" + (from.isEmpty() ? ": it is got from the world" : ": it is smelted from " + String.join(" or ", from) + " (smelt)"));
        }
        Skills.Laid l = doable(a, rs);
        if (l == null) {
            Skills.Laid p = plainest(rs);
            return failed("MISSING", "one craft of " + id(p.out) + " (makes " + p.out.stackSize + ") takes " + takes(p) + "; the bag lacks " + lacks(a, p));
        }
        Craft k = new Craft(c, item, meta, want, rs, l);
        if (!l.small()) {
            k.table = station(c, a, w, Roles.Role.TABLE, 24);
            if (k.table == null) {
                if (Skills.count(a.bag, Day1.TABLE, -1) == 0)
                    return failed("NO_TABLE", "the recipe of " + id(l.out) + " is 3x3: it needs a crafting table; none known near (24 blocks) and none in the bag "
                            + "(crafting_table: 4 planks in the hands)");
                int[] cell = beside(c, a, w);
                if (cell == null) return failed("NO_TABLE", "no free cell beside it to put its crafting_table in");
                k.table = cell;
                k.put = new Skills.PlaceFrom(cell[0], cell[1], cell[2], Day1.TABLE, 0);
            }
        }
        k.lease = Budget.ticks(k.table == null ? 0 : Crew.dist(a, k.table[0], k.table[1], k.table[2]) * 1.5, 0, k.put == null ? 0 : 1, (want + l.out.stackSize - 1) / l.out.stackSize, 0, 40);
        return k;
    }

    /** crafts by the game's recipes from the bag: in the hands (2x2), or at a table it walks to (its own put down first when it has none near) */
    static final class Craft extends Skill {
        final Crew c;
        final Item item;
        final int meta, want;
        final List<Skills.Laid> rs;
        Skills.Laid l;
        int[] table;
        Skills.PlaceFrom put;
        Crew.GoTo walk;
        int made, ticks, state;
        String note = "";
        Craft(Crew c, Item item, int meta, int want, List<Skills.Laid> rs, Skills.Laid l) { this.c = c; this.item = item; this.meta = meta; this.want = want; this.rs = rs; this.l = l; }

        Result run(Agent a, WorldServer w) {
            if (state == 0 && put != null) {   // its own table down beside it first
                Result r = put.step(a, w);
                if (r == Result.RUNNING) return r;
                if (r != Result.OK) { reason = "NO_TABLE"; detail = "could not put its crafting_table at " + table[0] + " " + table[1] + " " + table[2] + " (" + r + (a.why != null ? ", " + a.why : "") + ")"; return r; }
                note = "put its crafting_table at " + table[0] + " " + table[1] + " " + table[2] + "; ";
                adopt(c, w, table, Day1.TABLE);
                Events.log("mind: " + a.who() + " put its crafting table at " + table[0] + " " + table[1] + " " + table[2]);
            }
            if (state == 0) state = 1;
            if (state == 1) {   // to the table (a table is everyone's: each at its own side)
                if (l.small()) state = 2;
                else if (!Roles.is(w, table, Roles.Role.TABLE)) { reason = "NO_TABLE"; detail = "the table at " + table[0] + " " + table[1] + " " + table[2] + " is gone"; return Result.GONE; }
                else if (!Skills.nearRole(a, w, table, Roles.Role.TABLE, 2.8)) {
                    double ang = Math.toRadians(a.skin() * 72 + 20);
                    if (walk == null) walk = new Crew.GoTo(table[0] + 0.5 + Math.sin(ang) * 1.6, table[1], table[2] + 0.5 + Math.cos(ang) * 1.6, 0.6);
                    Result r = walk.step(a, w);
                    if (r == Result.RUNNING) return r;
                    walk = null;
                    if (r != Result.OK && !Skills.nearRole(a, w, table, Roles.Role.TABLE, 3.2)) { reason = "NO_PATH"; detail = "no way to the table at " + table[0] + " " + table[1] + " " + table[2]; return r; }
                    state = 2;
                } else state = 2;
            }
            if (!l.small()) { Crew.face(a, table[0] + 0.5, table[1] + 0.9, table[2] + 0.5); a.act(table[0], table[1], table[2], 2); }
            else a.act((int) Math.floor(a.posX), (int) Math.floor(a.posY), (int) Math.floor(a.posZ), 2);
            if (++ticks % 6 == 0) a.swingItem();
            if (ticks < 14) return Result.RUNNING;   // a craft takes a moment
            ticks = 0;
            ItemStack r = Skills.craftOnce(a, w, l);
            if (r == null) {   // out of an ingredient: another recipe of it the bag can do (other wood), if it needs no table it lacks
                Skills.Laid o = doable(a, rs);
                if (o != null && o != l && (o.small() || table != null)) { l = o; return Result.RUNNING; }
                return finish(a);
            }
            made += r.stackSize;
            if (!r.isStackable()) RlEnv.made(a, Skills.lastOf(a, r.getItem()));   // its decision in it (the causes)
            w.playSoundEffect(a.posX, a.posY + 1, a.posZ, "random.wood_click", 0.4F, 1.2F);
            if (!r.isStackable()) a.body.hold(Skills.lastOf(a, r.getItem()));
            return made >= want ? finish(a) : Result.RUNNING;
        }

        Result finish(Agent a) {
            if (made == 0) { reason = "MISSING"; detail = note + "one craft of " + id(l.out) + " takes " + takes(l) + "; the bag lacks " + lacks(a, l); return Result.NO_ITEMS; }
            detail = note + "made " + made + " " + id(l.out) + (made < want ? " of " + want + " wanted (the bag lacks " + lacks(a, l) + " for more)" : "");
            return Result.OK;
        }
    }

    // ---- smelt -----------------------------------------------------------------------------------------------

    static final Item[] FUELS = { Items.coal, Day1.PLANKS, Day1.LOG, Day1.LOG2, Items.stick };

    static Skill smelt(Crew c, Agent a, WorldServer w, String name, int n, String fuelName) {
        Object[] it = item(name);
        if (it == null) return failed("UNKNOWN_ITEM", "'" + name + "': use the game's id (iron_ore, log, sand, iron_ingot...)");
        Item item = (Item) it[0];
        int meta = (Integer) it[1];
        ItemStack in = null;
        for (ItemStack s : a.bag)   // what goes in: the item itself if it smelts, else something of the bag that smelts into it
            if (Skills.same(s, item, meta) && FurnaceRecipes.instance().getSmeltingResult(s) != null) { in = s; break; }
        if (in == null)
            for (ItemStack s : a.bag) {
                ItemStack out = FurnaceRecipes.instance().getSmeltingResult(s);
                if (out != null && out.getItem() == item && (meta < 0 || out.getMetadata() == meta)) { in = s; break; }
            }
        if (in == null) {
            List<String> from = smeltsFrom(item, meta);
            return failed("MISSING", "nothing in the bag smelts " + (from.isEmpty() ? "from or into " + id(item, Math.max(0, meta)) : "into " + id(item, Math.max(0, meta)) + " (it is smelted from " + String.join(" or ", from) + ")"));
        }
        ItemStack fuel = null;
        Object[] fw = fuelName == null || fuelName.trim().isEmpty() ? null : item(fuelName);
        for (ItemStack s : a.bag) {
            if (s == in && s.stackSize <= n || TileEntityFurnace.getItemBurnTime(s) <= 0) continue;
            if (fw != null) { if (Skills.same(s, (Item) fw[0], (Integer) fw[1])) { fuel = s; break; } continue; }
            for (int i = 0; i < FUELS.length; i++)
                if (s.getItem() == FUELS[i] && (fuel == null || rank(fuel) > i)) fuel = s;
            if (fuel == null) fuel = s;
        }
        if (fuel == null) return failed("NO_FUEL", "no fuel in the bag" + (fw != null ? " of " + fuelName : "") + " (coal, planks, logs, sticks burn)");
        Smelt k = new Smelt(c, in.copy(), Math.min(n, in.stackSize), fuel.copy());
        k.furnace = station(c, a, w, Roles.Role.FURNACE, 24);
        if (k.furnace == null) {
            if (Skills.count(a.bag, Day1.FURNACE, -1) == 0)
                return failed("NO_FURNACE", "smelting needs a furnace: none known near (24 blocks) and none in the bag (furnace: 8 cobblestone at a crafting table)");
            int[] cell = beside(c, a, w);
            if (cell == null) return failed("NO_FURNACE", "no free cell beside it to put its furnace in");
            k.furnace = cell;
            k.put = new Skills.PlaceFrom(cell[0], cell[1], cell[2], Day1.FURNACE, 0);
        }
        k.lease = 20 * (12 * k.n + 90);
        return k;
    }

    static int rank(ItemStack s) {
        for (int i = 0; i < FUELS.length; i++) if (s.getItem() == FUELS[i]) return i;
        return FUELS.length;
    }

    /** the input and its fuel in, the output out when done (one furnace inventory: one agent at a time) */
    static final class Smelt extends Skill {
        final Crew c;
        final ItemStack in, fuel;
        final int n;
        int[] furnace;
        Skills.PlaceFrom put;
        Crew.GoTo walk;
        int state, ticks, inside;
        String note = "";
        Smelt(Crew c, ItemStack in, int n, ItemStack fuel) { this.c = c; this.in = in; this.n = n; this.fuel = fuel; }

        Result run(Agent a, WorldServer w) {
            int[] f = furnace;
            if (state == 0 && put != null) {
                Result r = put.step(a, w);
                if (r == Result.RUNNING) return r;
                if (r != Result.OK) { reason = "NO_FURNACE"; detail = "could not put its furnace at " + f[0] + " " + f[1] + " " + f[2] + " (" + r + ")"; return r; }
                note = "put its furnace at " + f[0] + " " + f[1] + " " + f[2] + "; ";
                adopt(c, w, f, Day1.FURNACE);
            }
            if (state == 0) state = 1;
            TileEntity te = w.getTileEntity(f[0], f[1], f[2]);
            if (!(te instanceof TileEntityFurnace)) { reason = "NO_FURNACE"; detail = "the furnace at " + f[0] + " " + f[1] + " " + f[2] + " is gone"; return Result.GONE; }
            TileEntityFurnace fu = (TileEntityFurnace) te;
            if (Crew.dist(a, f[0] + 0.5, f[1], f[2] + 0.5) > 2.8) {
                if (walk == null) walk = new Crew.GoTo(f[0] + 0.5, f[1], f[2] + 0.5, 2.3);
                Result r = walk.step(a, w);
                if (r == Result.RUNNING) return r;
                walk = null;
                if (r != Result.OK && Crew.dist(a, f[0] + 0.5, f[1], f[2] + 0.5) > 3.2) { reason = "NO_PATH"; detail = "no way to the furnace"; return r; }
            }
            Crew.face(a, f[0] + 0.5, f[1] + 0.5, f[2] + 0.5);
            a.act(f[0], f[1], f[2], 2);
            ticks++;
            String key = "furnace " + f[0] + " " + f[1] + " " + f[2];
            if (state == 1) {
                if (!c.board.station(key, a, 1)) {
                    if (ticks > 20 * 60) { reason = "BUSY"; detail = "another agent has used the furnace for a minute"; return Result.BLOCKED; }
                    return Result.RUNNING;
                }
                ItemStack was = fu.getStackInSlot(0);
                if (was != null && !(was.getItem() == in.getItem() && was.getMetadata() == in.getMetadata())) {
                    c.board.leave(key, a);
                    reason = "BUSY";
                    detail = "the furnace smelts " + id(was) + " now";
                    return Result.BLOCKED;
                }
                int put = Skills.take(a, in.getItem(), in.getMetadata(), n, Ledger.Way.INGREDIENT);
                if (put == 0) { c.board.leave(key, a); reason = "MISSING"; detail = "no " + id(in) + " in the bag any more"; return Result.NO_ITEMS; }
                fu.setInventorySlotContents(0, new ItemStack(in.getItem(), put + (was == null ? 0 : was.stackSize), in.getMetadata()));
                ItemStack fs = fu.getStackInSlot(1);
                int burn = TileEntityFurnace.getItemBurnTime(fuel);
                int need = (int) Math.ceil(200.0 * put / burn);
                if (fs == null || fs.getItem() == fuel.getItem() && fs.getMetadata() == fuel.getMetadata()) {
                    int got = Skills.take(a, fuel.getItem(), fuel.getMetadata(), Math.min(need, 64 - (fs == null ? 0 : fs.stackSize)), Ledger.Way.FUEL);
                    if (got > 0) fu.setInventorySlotContents(1, new ItemStack(fuel.getItem(), got + (fs == null ? 0 : fs.stackSize), fuel.getMetadata()));
                }
                fu.markDirty();
                a.swingItem();
                Ledger.act(a, "fill the furnace");
                inside = put;
                state = 2;
                ticks = 0;
                return Result.RUNNING;
            }
            boolean through = fu.getStackInSlot(0) == null;
            boolean cold = fu.getStackInSlot(1) == null && !fu.isBurning() && !through;
            if (through || cold || ticks > 200 * inside + 400) {
                ItemStack out = fu.getStackInSlot(2);
                if (out != null) {
                    fu.setInventorySlotContents(2, null);
                    Skills.put(a, out, Ledger.Way.SMELTED);
                    a.swingItem();
                    Ledger.act(a, "take from the furnace");
                }
                fu.markDirty();
                c.board.leave(key, a);
                if (out == null) { reason = cold ? "NO_FUEL" : "TIMEOUT"; detail = note + (cold ? "the fuel ran out before anything came out" : "nothing came out in time"); return Result.TIMEOUT; }
                detail = note + "smelted " + out.stackSize + " " + id(out) + (out.stackSize < inside ? " of " + inside + (cold ? " (the fuel ran out; the rest is in the furnace)" : "") : "");
                return Result.OK;
            }
            return Result.RUNNING;
        }
    }

    // ---- place -----------------------------------------------------------------------------------------------

    static Skill place(final Crew c, Agent a, WorldServer w, String name, String at) {
        Object[] it = item(name);
        if (it == null) return failed("UNKNOWN_ITEM", "'" + name + "': use the game's id");
        ItemStack s = null;
        for (ItemStack b : a.bag) if (Skills.same(b, (Item) it[0], (Integer) it[1])) { s = b; break; }
        if (s == null) return failed("NO_ITEMS", "no " + name + " in the bag");
        int[] cell = coords(at);
        if (cell == null) cell = beside(c, a, w);
        if (cell == null) return failed("NO_SPOT", "no free cell beside it");
        if (!Crew.soft(w, cell[0], cell[1], cell[2])) return failed("OCCUPIED", cell[0] + " " + cell[1] + " " + cell[2] + " holds " + idOf(w.getBlock(cell[0], cell[1], cell[2])));
        final int[] at3 = cell;
        final Item item = s.getItem();
        final Crew.Task p = new Skills.PlaceFrom(cell[0], cell[1], cell[2], item, s.getMetadata());
        Skill k = new Skill() {
            Result run(Agent ag, WorldServer ww) {
                Result r = p.step(ag, ww);
                if (r == Result.OK) { adopt(c, ww, at3, item); detail = "put at " + at3[0] + " " + at3[1] + " " + at3[2]; }
                return r;
            }
        };
        k.lease = Budget.ticks(Crew.dist(a, cell[0], cell[1], cell[2]) * 1.5, 0, 1, 0, 0, 40);
        return k;
    }

    // ---- a chest: take, put ----------------------------------------------------------------------------------

    static Skill chest(Crew c, Agent a, WorldServer w, boolean take, String which, String name, int n) {
        String t = which == null ? "" : which.trim().toLowerCase(Locale.ROOT);
        int[] at = coords(t);
        if (at == null && (t.isEmpty() || t.startsWith("crew") || t.startsWith("our"))) at = Roles.is(w, c.chest, Roles.Role.CHEST) ? c.chest : null;
        if (at == null && coords(t) == null) at = station(c, a, w, Roles.Role.CHEST, 48);
        if (at == null || !Roles.is(w, at, Roles.Role.CHEST)) return failed("NO_CHEST", "no chest known " + (coords(t) != null ? "at " + t : "near (48 blocks)"));
        boolean all = !take && (name.equalsIgnoreCase("all") || name.equalsIgnoreCase("everything"));
        if (take && name.equalsIgnoreCase("food")) {   // whatever food the chest has
            TileEntity te = w.getTileEntity(at[0], at[1], at[2]);
            name = RlSkills.foodIn(te instanceof IInventory ? (IInventory) te : null);
            if (name == null) return failed("NOT_IN_CHEST", "no food in the chest at " + at[0] + " " + at[1] + " " + at[2]);
        }
        Object[] it = all ? null : item(name);
        if (!all && it == null) return failed("UNKNOWN_ITEM", "'" + name + "': use the game's id");
        if (!take && !all && Skills.count(a.bag, (Item) it[0], (Integer) it[1]) == 0) return failed("NO_ITEMS", "no " + name + " in the bag");
        ChestJob k = new ChestJob(at, take, all ? null : (Item) it[0], all ? -1 : (Integer) it[1], n);
        k.lease = Budget.ticks(Crew.dist(a, at[0], at[1], at[2]) * 1.5, 0, 0, 0, 0, 60);
        return k;
    }

    static final class ChestJob extends Skill {
        final int[] at;
        final boolean take;
        final Item item;
        final int meta, n;
        Crew.GoTo walk;
        int ticks, moved;
        boolean there;
        ChestJob(int[] at, boolean take, Item item, int meta, int n) { this.at = at; this.take = take; this.item = item; this.meta = meta; this.n = n; }

        Result run(Agent a, WorldServer w) {
            if (!Roles.is(w, at, Roles.Role.CHEST)) { reason = "NO_CHEST"; detail = "the chest at " + at[0] + " " + at[1] + " " + at[2] + " is gone"; return Result.GONE; }
            if (!there) {
                if (Crew.dist(a, at[0] + 0.5, at[1], at[2] + 0.5) > 2.8) {
                    if (walk == null) walk = new Crew.GoTo(at[0] + 0.5, at[1], at[2] + 0.5, 2.3);
                    Result r = walk.step(a, w);
                    if (r == Result.RUNNING) return r;
                    walk = null;
                    if (r != Result.OK && Crew.dist(a, at[0] + 0.5, at[1], at[2] + 0.5) > 3.2) { reason = "NO_PATH"; detail = "no way to the chest"; return r; }
                }
                there = true;
            }
            Crew.face(a, at[0] + 0.5, at[1] + 0.5, at[2] + 0.5);
            ticks++;
            Block b = w.getBlock(at[0], at[1], at[2]);
            if (ticks == 3) { w.addBlockEvent(at[0], at[1], at[2], b, 1, 1); w.playSoundEffect(at[0] + 0.5, at[1] + 0.5, at[2] + 0.5, "random.chestopen", 0.5F, 1.0F); }
            if (ticks == 9) {
                TileEntity te = w.getTileEntity(at[0], at[1], at[2]);
                IInventory inv = te instanceof IInventory ? (IInventory) te : null;
                if (inv != null) {
                    a.swingItem();
                    if (take) moved = Day1.Take.takeAny(inv, a, item, n);
                    else moved = putIn(a, inv);
                    if (moved > 0) Ledger.act(a, take ? "take from chest" : "stash");
                    if (take && moved == 0) detail = "the chest has: " + contents(inv);
                    inv.markDirty();
                }
            }
            if (ticks == 16) { w.addBlockEvent(at[0], at[1], at[2], b, 1, 0); w.playSoundEffect(at[0] + 0.5, at[1] + 0.5, at[2] + 0.5, "random.chestclosed", 0.5F, 1.0F); }
            if (ticks < 18) return Result.RUNNING;
            if (moved == 0) { reason = take ? "NOT_IN_CHEST" : "CHEST_FULL"; if (!take) detail = "nothing went in"; return Result.NO_ITEMS; }
            detail = (take ? "took " : "put in ") + moved + (item == null ? " things" : " " + id(item, Math.max(0, meta)));
            return Result.OK;
        }

        /** from the bag into the chest (tools stay unless asked for by name), booked as what really went in */
        int putIn(Agent a, IInventory inv) {
            int total = 0;
            for (ItemStack s : new ArrayList<ItemStack>(a.bag)) {
                if (total >= n) break;
                if (item == null ? s.getItem() instanceof net.minecraft.item.ItemTool : !Skills.same(s, item, meta)) continue;
                int want = Math.min(n - total, s.stackSize), in = 0;
                for (int i = 0; i < inv.getSizeInventory() && in < want; i++) {
                    ItemStack c = inv.getStackInSlot(i);
                    if (c == null) {
                        int k = Math.min(want - in, s.getMaxStackSize());
                        inv.setInventorySlotContents(i, new ItemStack(s.getItem(), k, s.getMetadata()));
                        in += k;
                    } else if (c.getItem() == s.getItem() && c.getMetadata() == s.getMetadata() && c.stackSize < c.getMaxStackSize() && !s.hasTagCompound()) {
                        int k = Math.min(want - in, c.getMaxStackSize() - c.stackSize);
                        c.stackSize += k;
                        in += k;
                    }
                }
                if (in > 0) total += Skills.take(a, s.getItem(), s.getMetadata(), in, Ledger.Way.CHEST_IN);
            }
            return total;
        }
    }

    static String contents(IInventory inv) {
        Map<String, Integer> m = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s != null) Ledger.add(m, id(s), s.stackSize);
        }
        if (m.isEmpty()) return "nothing";
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Integer> e : m.entrySet()) b.append(b.length() > 0 ? ", " : "").append(e.getKey()).append(" x").append(e.getValue());
        return b.toString();
    }

    // ---- give ------------------------------------------------------------------------------------------------

    static Skill give(Crew c, Agent a, WorldServer w, String to, String name, int n) {
        Object[] it = item(name);
        if (it == null) return failed("UNKNOWN_ITEM", "'" + name + "': use the game's id");
        Item item = (Item) it[0];
        String t = to == null ? "" : to.trim().toLowerCase(Locale.ROOT);
        Crew.Task g;
        Matcher m = Pattern.compile("(?:agent|агент)?\\s*(\\d+)").matcher(t);
        if (t.startsWith("player") || t.startsWith("игрок") || t.equals("me")) g = new Skills.Give(item, n);
        else if (m.matches()) {
            Doer d = c.doer(Integer.parseInt(m.group(1)));
            if (d == null) return failed("GONE", "no " + to + " in the crew now");
            if (d == a) return failed("BAD_ARG", "that is you");
            g = d instanceof Agent ? new Skills.Give((Agent) d, item, n) : new Skills.Give(item, n);   // number 1: the player's own character
        } else return failed("UNKNOWN_TARGET", "'" + to + "': player, or agent N");
        if (Skills.count(a.bag, item, -1) == 0 && c.chestInv(w) == null) return failed("NO_ITEMS", "no " + name + " in the bag (and no crew chest to take it from)");
        Skill s = wrap(g, r -> r == Result.NO_ITEMS ? new String[] { "NO_ITEMS", "no " + name + " in the bag nor in the crew's chest" }
                : r == Result.GONE ? new String[] { "GONE", to + " is not there" } : r == Result.NO_PATH ? new String[] { "NO_PATH", "no way to " + to } : null);
        s.lease = 20 * 120;
        return s;
    }

    // ---- wait ------------------------------------------------------------------------------------------------

    /** stands still (looks at the player when near); a danger ends it early */
    static final class Wait extends Skill {
        final int len;
        int t;
        Wait(int seconds) { len = seconds * 20; lease = len + 20 * 10; }
        Result run(Agent a, WorldServer w) {
            a.body.stop();
            EntityPlayerMP p = Crew.instance == null ? null : Crew.instance.playerIn(w);
            if (p != null && Crew.dist(a, p.posX, p.posY, p.posZ) < 16) Crew.face(a, p.posX, p.posY + 1.5, p.posZ);
            if (interrupt != null) { reason = "INTERRUPTED"; detail = interrupt + " after " + t / 20 + " s"; return Result.BLOCKED; }
            return ++t >= len ? Result.OK : Result.RUNNING;
        }
    }
}
