package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.world.WorldServer;

/**
 * The crew's first day on the honest economy: everything an agent has came from a drop, the chest, a craft or a
 * smelt, and everything it puts down or hands over goes out of its bag (Skills, Ledger). Wood in a chain (choppers
 * hand their logs to a carrier), a camp made of the first logs (planks, a crafting table, a chest), wooden pickaxes
 * made at the table and handed to each, a mine going down in steps (stone only with a pickaxe), a furnace of
 * cobblestone with charcoal smelted for torches, stone pickaxes, a gift to the player, a base by a see-through plan
 * built from what the chest holds (its size by the wood there is), and at nightfall the crew at the mine.
 */
class Day1 {
    static final Item LOG = Item.getItemFromBlock(Blocks.log), LOG2 = Item.getItemFromBlock(Blocks.log2), PLANKS = Item.getItemFromBlock(Blocks.planks),
            COBBLE = Item.getItemFromBlock(Blocks.cobblestone), FENCE = Item.getItemFromBlock(Blocks.fence), TORCH = Item.getItemFromBlock(Blocks.torch),
            TABLE = Item.getItemFromBlock(Blocks.crafting_table), CHEST = Item.getItemFromBlock(Blocks.chest), FURNACE = Item.getItemFromBlock(Blocks.furnace);

    // ---- handing things over: a throw, the other one catches ------------------------------------------

    /** the items in the air from a hand-over (caught, they are booked as handed, not found) */
    static final Map<EntityItem, Boolean> HANDED = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<EntityItem, Boolean>());

    /** throw the giver's bag (but what it keeps) to someone; the items fly and are caught where they land */
    static void toss(Agent from, double tx, double ty, double tz, WorldServer w, Item keep) {
        from.swingItem();
        double dx = tx - from.posX, dy = ty - (from.posY + 1.3), dz = tz - from.posZ, d = Math.max(0.5, Math.sqrt(dx * dx + dz * dz));
        for (java.util.Iterator<ItemStack> it = from.bag.iterator(); it.hasNext(); ) {
            ItemStack s = it.next();
            if (keep != null && s.getItem() == keep) continue;
            if (!s.isStackable() && s == from.getHeldItem() && keep == null && from.bag.size() > 1) { }   // a tool goes too when asked
            Ledger.flow(Ledger.Way.HANDED_OUT, s);
            Ledger.act(from, "hand over");
            EntityItem e = new EntityItem(w, from.posX, from.posY + 1.3, from.posZ, s.copy());
            e.motionX = dx / d * Math.min(0.42, d * 0.1);
            e.motionZ = dz / d * Math.min(0.42, d * 0.1);
            e.motionY = 0.22 + dy * 0.04;
            e.delayBeforeCanPickup = 12;
            HANDED.put(e, Boolean.TRUE);   // caught, it is a hand-over, not a find
            w.spawnEntityInWorld(e);
            if (s == from.getHeldItem()) from.body.hold(null);
            it.remove();
        }
        w.playSoundEffect(from.posX, from.posY + 1, from.posZ, "random.pop", 0.4F, 1.3F);
        if (from.getHeldItem() != null && !from.bag.contains(from.getHeldItem())) from.body.hold(null);
    }

    /** the agent picks up what lies within reach of its hands */
    @SuppressWarnings("unchecked")
    static int catchNear(Agent a, WorldServer w, double r) {
        int got = 0;
        for (Object o : w.getEntitiesWithinAABB(EntityItem.class, a.boundingBox.expand(r, 1.0, r))) {
            EntityItem it = (EntityItem) o;
            if (it.isDead || it.age < 8) continue;
            ItemStack s = it.getEntityItem();
            a.onItemPickup(it, s.stackSize);
            Skills.put(a, s, HANDED.containsKey(it) ? Ledger.Way.HANDED_IN : Ledger.Way.DROP);
            Ledger.act(a, "pick up");
            it.setDead();
            got += s.stackSize;
            if (a.getHeldItem() == null) a.body.hold(Skills.lastOf(a, s.getItem()));
        }
        return got;
    }

    /** a line an agent says: to the events (there are no speech bubbles yet) */
    static void say(Agent a, String line) {
        Events.log("crew: " + a.getCustomNameTag() + " says: " + line);
    }

    static int logs(List<ItemStack> bag) {
        return Skills.count(bag, LOG, -1) + Skills.count(bag, LOG2, -1);
    }

    /** logs the crew has: the chest's and every bag's */
    static int crewLogs(Crew c, WorldServer w) {
        int n = 0;
        IInventory inv = c.chestInv(w);
        if (inv != null) n += Crew.count(inv, LOG) + Crew.count(inv, LOG2);
        for (Agent a : c.agents) n += logs(a.bag);
        return n;
    }

    /** the carrier: runs to whichever worker has the most, takes it (thrown over), carries it to the chest */
    static class Courier implements Crew.Task {
        final Crew c;
        final List<String> kinds;
        List<Agent> workers = new ArrayList<Agent>();
        Agent from;
        Crew.GoTo walk;
        Crew.Task stash;
        int state, ticks, idle, age;
        /** carries for whoever holds a card of these kinds on the board */
        Courier(Crew c, String... kinds) { this.c = c; this.kinds = java.util.Arrays.asList(kinds); }
        static int load(Agent g) {
            int n = 0;
            for (ItemStack s : g.bag) if (s.isStackable()) n += s.stackSize;
            return n;
        }
        public Result step(Agent a, WorldServer w) {
            ticks++;
            age++;
            if (stash != null) { if (stash.step(a, w).done()) stash = null; return Result.RUNNING; }
            if (state == 0) {   // whom to go to
                workers = new ArrayList<Agent>();
                for (Agent g : c.agents) {
                    TaskBoard.Card k = c.board.leased.get(g);
                    if (g != a && k != null && kinds.contains(k.kind)) workers.add(g);
                }
                from = null;
                int most = 0;
                for (Agent g : workers) { int n = load(g); if (n > most) { most = n; from = g; } }
                boolean working = !workers.isEmpty() || age < 20 * 8;   // at first the others are still taking their cards
                int mine = load(a);
                if (from == null || most < 3 && working) {
                    if (mine > 0 && (!working || mine >= 12)) { stash = c.new Stash(null, 0); return Result.RUNNING; }
                    if (!working) return Result.OK;
                    if (++idle % 40 == 0 && !workers.isEmpty()) {   // wait near one of them
                        Agent g = workers.get((idle / 40) % workers.size());
                        walk = new Crew.GoTo(g.posX + 2, g.posY, g.posZ + 1, 2.5);
                    }
                    if (walk != null && walk.step(a, w).done()) walk = null;
                    return Result.RUNNING;
                }
                walk = new Crew.GoTo(from.posX, from.posY, from.posZ, 3.2);
                state = 1;
                return Result.RUNNING;
            }
            if (state == 1) {   // run to it; it throws its load (not its tools), the carrier catches
                if (Crew.dist(a, from.posX, from.posY, from.posZ) > 3.4) {
                    if (walk.step(a, w).done()) walk = new Crew.GoTo(from.posX, from.posY, from.posZ, 3.2);
                    if (ticks > 20 * 40) { state = 0; ticks = 0; }
                    return Result.RUNNING;
                }
                a.body.stop();
                Crew.face(a, from.posX, from.posY + 1.4, from.posZ);
                Crew.face(from, a.posX, a.posY + 1.4, a.posZ);
                List<ItemStack> tools = new ArrayList<ItemStack>();
                for (ItemStack s : from.bag) if (!s.isStackable()) tools.add(s);
                from.bag.removeAll(tools);
                toss(from, a.posX, a.posY + 1.0, a.posZ, w, null);
                from.bag.addAll(tools);
                if (from.getHeldItem() == null && !tools.isEmpty()) from.body.hold(tools.get(0));
                say(from, "Лови, " + a.getCustomNameTag() + "!");
                ticks = 0;
                state = 2;
                return Result.RUNNING;
            }
            Crew.face(a, from.posX, from.posY + 1.4, from.posZ);
            catchNear(a, w, 1.6);
            if (ticks > 26) {
                catchNear(a, w, 3.0);
                if (load(a) >= 12) { stash = c.new Stash(null, 0); say(a, "Несу в сундук"); }
                state = 0;
            }
            return Result.RUNNING;
        }
    }

    /** chop trees until the crew has the logs it needs (or the trees near run out): one tree after another */
    static class ChopMore implements Crew.Task {
        final Crew c;
        final int target;
        final boolean own;   // count only the agent's own bag (logs for its own craft), not the crew's
        Crew.Task cur;
        int done;
        ChopMore(Crew c, int target) { this(c, target, false); }
        ChopMore(Crew c, int target, boolean own) { this.c = c; this.target = target; this.own = own; }
        public Result step(Agent a, WorldServer w) {
            if (cur != null) {
                Result r = cur.step(a, w);
                if (r == Result.RUNNING) return r;
                if (cur instanceof Crew.Chop) {
                    Crew.Chop ch = (Crew.Chop) cur;
                    c.board.unreserve(ch.tx, ch.ty, ch.tz);
                    if (r != Result.OK) c.taken.add(Crew.key(ch.tx, ch.ty, ch.tz));   // could not get at it: not this one again
                    cur = new Crew.Collect(ch.tx + 0.5, ch.ty, ch.tz + 0.5, 5);
                } else cur = null;
                return Result.RUNNING;
            }
            if ((own ? logs(a.bag) : crewLogs(c, w)) >= target) return Result.OK;
            int[] best = null;
            double bd = 1e9;
            boolean others = false;   // trees left, but others are on them
            for (int[] t : c.trees(w)) {
                if (c.taken.contains(Crew.key(t[0], t[1], t[2]))) continue;
                if (c.board.reserved(a, t[0], t[1], t[2])) { others = true; continue; }
                double d = Math.hypot(t[0] - a.posX, t[2] - a.posZ) + Math.hypot(t[0] - c.base[0], t[2] - c.base[2]) * 0.5;
                if (d < bd) { bd = d; best = t; }
            }
            if (best == null) return done > 0 || others ? Result.OK : Result.GONE;   // GONE: no wood left around at all
            c.board.reserve(a, best[0], best[1], best[2]);
            cur = new Crew.Chop(best[0], best[1], best[2]);
            done++;
            say(a, done == 1 ? "Беру дерево" : "Ещё одно");
            return Result.RUNNING;
        }
    }

    /** the camp from the first logs: planks, a crafting table and a chest, made and put down where the base will be */
    static class Camp implements Crew.Task {
        final Crew c;
        final List<Crew.Task> steps = new ArrayList<Crew.Task>();
        int i;
        Camp(Crew c) { this.c = c; }
        public Result step(Agent a, WorldServer w) {
            if (steps.isEmpty()) {
                int[] t = c.table, k = c.chest;
                boolean table = Roles.is(w, t, Roles.Role.TABLE), chest = Roles.is(w, k, Roles.Role.CHEST);
                if (table && chest) return Result.OK;
                if (logs(a.bag) < 3) return Result.NO_ITEMS;
                say(a, "Ставлю лагерь: верстак и сундук");
                steps.add(new Skills.Craft(PLANKS, -1, 12));
                if (!table) { steps.add(new Skills.Craft(TABLE, -1, 1)); steps.add(new Skills.PlaceFrom(t[0], t[1], t[2], TABLE, -1)); }
                if (!chest) { steps.add(new Skills.Craft(CHEST, -1, 1)); steps.add(new Skills.PlaceFrom(k[0], k[1], k[2], CHEST, -1)); }
            }
            if (i >= steps.size()) return Result.OK;
            Result r = steps.get(i).step(a, w);
            if (r == Result.RUNNING) return r;
            if (r.failed()) return r;
            i++;
            return Result.RUNNING;
        }
    }

    static class WoodChain extends Crew.Phase {
        final int target;
        WoodChain(int perAgent) { this(perAgent, Base.woodNeeded()); }
        WoodChain(int perAgent, int target) { this.target = target; }
        void start(final Crew c, WorldServer w, EntityPlayerMP p) {
            if (c.base == null) c.base = new int[] { (int) Math.floor(p.posX), (int) Math.floor(p.boundingBox.minY), (int) Math.floor(p.posZ) };
            c.planSite(w);
            c.board.goals.put(LOG, target);
            budget(target * 3, target * 3, 2, 8, 0, 0);   // logs and the leaves round them, the camp
            int n = c.agents.size();
            boolean carry = n >= 3;
            int[] t = c.table;
            // the camp first (its logs are its own, the carrier leaves them), then wood; one carries for the choppers
            c.board.post("camp", () -> new TaskBoard.Seq(new ChopMore(c, 3, true), new Camp(c), new TaskBoard.Seq(new ChopMore(c, target), c.new Stash(null, 0)).lenient())).prio(3).at(t[0], t[1], t[2]);
            for (int i = 0; i < n - 1 - (carry ? 1 : 0); i++)
                c.board.post("chop", () -> carry ? new ChopMore(c, target) : new TaskBoard.Seq(new ChopMore(c, target), c.new Stash(null, 0))).prio(2);
            if (carry) c.board.post("carry", () -> new Courier(c, "chop")).prio(1.5);
            Events.log("crew: wood chain for " + target + " logs: camp, " + (n - 1 - (carry ? 1 : 0)) + " chop" + (carry ? ", carry" : "") + " cards");
        }
    }

    // ---- tools: made at the table and handed round ------------------------------------------------------

    /** the crafter takes logs, makes planks and sticks, a wooden pickaxe for each and sticks to spare, hands them round */
    static class Tools extends Crew.Phase {
        Tools() { }
        void start(final Crew c, WorldServer w, EntityPlayerMP p) {
            c.planSite(w);
            if (c.agents.isEmpty()) return;
            int without = 0;
            for (Agent a : c.agents) if (!hasPickaxe(a)) without++;
            if (without == 0) { Events.log("crew: everyone has a pickaxe"); return; }
            final int n = without;
            // a pickaxe is 3 planks and 2 sticks; 2 planks make 4 sticks; to spare: the stone pickaxes' (n + the player's) and 4 for torches
            final int sticks = 2 * n + 2 * (n + 1) + 4, planks = 3 * n + (sticks + 3) / 4 * 2, logs = (planks + 3) / 4;
            budget(40 + 20 * n, 0, 0, logs + (sticks + 3) / 4 + n, 0, 0);   // to the chest and the table, the crafts, the hand-overs
            int[] t = c.table;
            c.board.post("pickaxes", () -> {
                List<Crew.Task> s = new ArrayList<Crew.Task>();
                s.add(new Take(c, LOG, logs));
                s.add(new Skills.Craft(PLANKS, -1, planks));
                s.add(new Skills.Craft(Items.stick, -1, sticks));
                s.add(new Skills.Craft(Items.wooden_pickaxe, -1, n));
                s.add(new GiveRound(c, Items.wooden_pickaxe));
                s.add(c.new Stash(null, 0));   // the spare sticks go to the chest
                return new TaskBoard.Seq(s.toArray(new Crew.Task[0]));
            }).wants(LOG, logs, 1).prio(3).at(t[0], t[1], t[2]);
        }
    }

    /** hand one of an item to every agent who has none (the giver keeps one) */
    static class GiveRound implements Crew.Task {
        final Crew c;
        final Item item;
        Crew.Task cur;
        final java.util.Set<Agent> tried = new java.util.HashSet<Agent>();
        GiveRound(Crew c, Item item) { this.c = c; this.item = item; }
        public Result step(Agent a, WorldServer w) {
            if (cur != null) {
                Result r = cur.step(a, w);
                if (r == Result.RUNNING) return r;
                cur = null;
            }
            for (Agent g : c.agents)
                if (g != a && !tried.contains(g) && Skills.count(g.bag, item, -1) == 0 && Skills.count(a.bag, item, -1) > 1) {
                    tried.add(g);
                    say(a, "Держи, " + g.getCustomNameTag());
                    cur = new Skills.Give(g, item, 1);
                    return Result.RUNNING;
                }
            return Result.OK;
        }
    }

    // ---- the mine: steps down, two abreast ----------------------------------------------------------------

    /** dig down in steps from a point in a direction: each step a column of three, the agent standing a step higher */
    static class Steps implements Crew.Task {
        final int x0, y0, z0, dx, dz, n;
        int k = 1, tries;
        Crew.Break br;
        Crew.GoTo walk;
        Skills.PlaceFrom put;
        Steps(int x0, int y0, int z0, int dx, int dz, int n) { this.x0 = x0; this.y0 = y0; this.z0 = z0; this.dx = dx; this.dz = dz; this.n = n; }
        public Result step(Agent a, WorldServer w) {
            if (br != null) {
                Result r = br.step(a, w);
                if (r == Result.RUNNING) return r;
                br = null;
                if (r == Result.NO_TOOL) { say(a, "Нечем копать камень"); return Result.NO_TOOL; }
                if ((r == Result.NOT_VISIBLE || r == Result.OUT_OF_REACH) && ++tries > 4) { tries = 0; k++; walk = null; return Result.RUNNING; }   // not got at: the next step
                catchNear(a, w, 1.4);
                return Result.RUNNING;
            }
            if (k > n) { catchNear(a, w, 2.5); return Result.OK; }
            int cx = x0 + dx * k, cz = z0 + dz * k, floor = y0 - k;
            for (int y = floor + 2; y >= floor; y--) {   // the blocks of this step, the top first
                Block b = w.getBlock(cx, y, cz);
                if (Crew.soft(w, cx, y, cz) || b == Blocks.bedrock || b.getMaterial().isLiquid()) continue;
                double reach = Crew.eyeDist(a, cx, y, cz);
                int[] in = Crew.blocker(a, w, cx, y, cz);
                boolean inOk = in == null || in[0] == cx && in[2] == cz && Ground.mayDig(Crew.instance, in[0], in[1], in[2], "mine");
                if (reach <= Crew.REACH && inOk && Ground.mayDig(Crew.instance, cx, y, cz, "mine")) {
                    br = in != null && (in[0] != cx || in[1] != y || in[2] != cz) ? new Crew.Break(in[0], in[1], in[2], 0) : new Crew.Break(cx, y, cz, 0);
                    return Result.RUNNING;
                }
                int px = x0 + dx * (k - 1), pz = z0 + dz * (k - 1), pf = y0 - (k - 1);   // not seen from here: stand on the step before
                if (walk == null) walk = new Crew.GoTo(px + 0.5, pf, pz + 0.5, 0.6);
                if (walk.step(a, w).done()) { walk = null; if (++tries > 4) { tries = 0; k++; } }
                return Result.RUNNING;
            }
            // the step is open: a floor under it, from the agent's own cobblestone (or dirt), or the stairs end here
            if (Crew.soft(w, cx, floor - 1, cz)) {
                if (put == null) {
                    Item fill = Skills.count(a.bag, COBBLE, -1) > 0 ? COBBLE : Skills.count(a.bag, Item.getItemFromBlock(Blocks.dirt), -1) > 0 ? Item.getItemFromBlock(Blocks.dirt) : null;
                    if (fill == null) { catchNear(a, w, 2.5); return Result.OK; }
                    put = new Skills.PlaceFrom(cx, floor - 1, cz, fill, -1).pillar(0);   // from where its eyes see a face of the hole (no pillar in a mine)
                }
                Result r = put.step(a, w);   // someone in the cell steps aside meanwhile
                if (r == Result.RUNNING) return r;
                put = null;
                if (r != Result.OK) { catchNear(a, w, 2.5); return Result.OK; }
            }
            k++;
            tries = 0;
            walk = null;
            Skills.lightUp(a, w);   // the stairs going down get their torches where it is dark
            return Result.RUNNING;
        }
    }

    /** the mouth of the mine: by the base, where the ground goes on for a while in one direction */
    static int[] mineSpot(Crew c, WorldServer w) {
        int bx = c.base[0], by = c.base[1], bz = c.base[2];
        int[][] dirs = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
        int[] best = null;
        double bs = -1e9;
        int[] s = Crew.site;
        for (int ox = -12; ox <= 12; ox += 2)
            for (int oz = -12; oz <= 12; oz += 2) {
                int x = bx + ox, z = bz + oz, y = Crew.ground(w, x, by, z);
                if (y < 0) continue;
                Ground.Flood from = Ground.baseFlood(c, w);
                if (from != null && !from.has(x, y, z)) continue;   // the mouth: where one walks from the base
                for (int[] d : dirs) {
                    boolean free = true;   // the stairs may be dug (no protected zone in their way)
                    for (int k = 1; k <= 10 && free; k++)
                        for (int dy = 0; dy <= 2; dy++) {
                            int cx = x + d[0] * k, cz = z + d[1] * k;
                            if (!Ground.mayDig(c, cx, y - k + dy, cz, "mine") || !Ground.mayDig(c, cx + (d[0] == 0 ? 1 : 0), y - k + dy, cz + (d[1] == 0 ? 1 : 0), "mine")) { free = false; break; }
                        }
                    if (!free) continue;
                    int good = 0;
                    for (int k = 0; k < 9; k++)
                        for (int side = 0; side <= 1; side++) {
                            int cx = x + d[0] * k + (d[0] == 0 ? side : 0), cz = z + d[1] * k + (d[1] == 0 ? side : 0);
                            if (Crew.stand(w, cx, y, cz)) good++;
                        }
                    double score = good - Math.hypot(ox, oz) * 0.3;
                    if (score > bs) { bs = score; best = new int[] { x, y, z, d[0], d[1] }; }
                }
            }
        return best;
    }

    static int[] mine;   // x, y, z, dx, dz of the mouth (for the camera and the next day)
    static int mineDepth;   // the steps planned so far (the stairs' zone reaches as deep)
    static Base lastBase;   // the base laid last (how much of it stands)
    static Base firstBase;  // the base as first laid in this run (what the day was measured against)

    static boolean hasPickaxe(Agent a) {
        for (ItemStack s : a.bag) if (s.getItem() instanceof net.minecraft.item.ItemPickaxe) return true;
        return false;
    }

    static class Mine extends Crew.Phase {
        int depth = 8;   // steps down (a second mine goes on deeper: the steps already dug give nothing)
        Mine() { }
        Mine(int depth) { this(); this.depth = depth; }
        void start(final Crew c, WorldServer w, EntityPlayerMP p) {
            c.planSite(w);
            int[] m = mine != null ? mine : mineSpot(c, w);
            if (m == null) { Events.log("crew: no place for a mine"); return; }
            mine = m;
            mineDepth = Math.max(mineDepth, depth);
            budget(20 + depth * 2, depth * 6, depth, 0, 0, 0);   // two lanes of three a step, a floor block now and then
            Ground.markMineWay(c, w);   // the floor of the way there is kept from now on
            Events.log("crew: mine at " + m[0] + " " + m[1] + " " + m[2] + " going " + m[3] + " " + m[4]);
            for (int k = 0; k < 2; k++) {   // two lanes abreast, for whoever has a pickaxe
                final int sx = m[0] + (m[3] == 0 ? k : 0), sz = m[2] + (m[4] == 0 ? k : 0), y = m[1], dx = m[3], dz = m[4], n = depth;
                c.board.post("dig", () -> new TaskBoard.Seq(new Crew.GoTo(sx + 0.5, y, sz + 0.5, 0.8), new Steps(sx, y, sz, dx, dz, n), c.new Stash(null, 0)))
                        .need(Day1::hasPickaxe).needs(java.util.Arrays.asList(new ItemStack(Items.wooden_pickaxe))).prio(3).at(sx, y, sz);
            }
            if (c.agents.size() >= 3) c.board.post("carry", () -> new Courier(c, "dig")).prio(2);   // what comes up goes to the chest
        }
    }

    /**
     * stone open to the air around the base that a quarry would take (counted up to n): by the very rules the quarry
     * picks by (Crew.openStone): may be dug, in reach of a walk from the base, and a way back left after the dig
     */
    static int openRock(Crew c, WorldServer w, int n) {
        Ground.Flood f = Ground.baseFlood(c, w);
        int bx = c.base[0], by = c.base[1], bz = c.base[2], found = 0, checks = 0;
        for (int x = bx - 24; x <= bx + 24 && found < n && checks < 60; x++)
            for (int z = bz - 24; z <= bz + 24 && found < n && checks < 60; z++)
                for (int y = by - 6; y <= by + 10; y++) {
                    if (w.getBlock(x, y, z) != Blocks.stone || !w.isAirBlock(x, y + 1, z)) continue;
                    if (!Ground.mayDig(c, x, y, z, "gather") || f != null && f.near(x, y, z, Crew.REACH) == null) continue;
                    checks++;
                    if (!Ground.digLeavesWay(c, w, x, y, z, "gather")) continue;
                    found++;
                }
        return found;
    }

    /** stone from rock open to the air: a card for every few blocks, for whoever has a pickaxe */
    static class Quarry extends Crew.Phase {
        final int n;
        Quarry(int n) { this.n = n; }
        void start(final Crew c, WorldServer w, EntityPlayerMP p) {
            budget(n * 3, n * 2, 0, 0, 0, 0);
            for (int left = n; left > 0; left -= 6) {
                final int k = Math.min(6, left);
                c.board.post("quarry", () -> new Crew.Task() {   // the blocks are picked when an agent takes it (near it, a way back left)
                    Crew.Task t;
                    public Result step(Agent a, WorldServer ww) {
                        if (t == null) {
                            List<int[]> st = Crew.openStone(c, ww, a, k);
                            if (st.isEmpty()) return Result.GONE;
                            t = new TaskBoard.Seq(new Crew.Mine(st), new Crew.Collect(st.get(0)[0] + 0.5, st.get(0)[1], st.get(0)[2] + 0.5, 5), c.new Stash(null, 0));
                        }
                        return t.step(a, ww);
                    }
                }).need(Day1::hasPickaxe).needs(java.util.Arrays.asList(new ItemStack(Items.wooden_pickaxe))).prio(3);
            }
        }
    }

    // ---- stone tools, the furnace, charcoal and torches ---------------------------------------------------

    /** take things from the chest: walk there, the lid up, the hand full */
    static class Take implements Crew.Task {
        final Crew c;
        final Item item;
        final int n;
        int state, ticks, got;
        Crew.GoTo walk;
        Take(Crew c, Item item, int n) { this.c = c; this.item = item; this.n = n; }
        public Result step(Agent a, WorldServer w) {
            if (c.chest == null || c.chestInv(w) == null) return Result.GONE;
            if (n <= 0) return Result.OK;
            if (state == 0) { walk = new Crew.GoTo(c.chest[0] + 0.5, c.chest[1], c.chest[2] + 0.5, 2.3); state = 1; }
            if (state == 1) {
                Result r = walk.step(a, w);
                if (r == Result.RUNNING) return r;
                if (r != Result.OK && Crew.dist(a, c.chest[0] + 0.5, c.chest[1], c.chest[2] + 0.5) > 3.2) return r;
                state = 2;
            }
            Crew.face(a, c.chest[0] + 0.5, c.chest[1] + 0.5, c.chest[2] + 0.5);
            ticks++;
            if (ticks == 3) w.addBlockEvent(c.chest[0], c.chest[1], c.chest[2], w.getBlock(c.chest[0], c.chest[1], c.chest[2]), 1, 1);
            if (ticks == 9) {
                IInventory inv = c.chestInv(w);
                int may = Math.min(n, c.board.chestFree(w, item, a));   // not what another card counts on
                if (item == LOG) {   // any wood
                    got = takeAny(inv, a, LOG, may);
                    got += takeAny(inv, a, LOG2, may - got);
                } else got = takeAny(inv, a, item, may);
                c.board.took(a, item, got);
                if (got < n) c.board.shortage(a, item, n - got);   // the chest had too little: what is missing goes on the card
                if (got > 0) Ledger.act(a, "take from chest");
                a.swingItem();
            }
            if (ticks == 16) w.addBlockEvent(c.chest[0], c.chest[1], c.chest[2], w.getBlock(c.chest[0], c.chest[1], c.chest[2]), 1, 0);
            return ticks >= 18 ? (got > 0 ? Result.OK : Result.NO_ITEMS) : Result.RUNNING;
        }
        static int takeAny(IInventory inv, Agent a, Item item, int n) {
            int got = 0;
            for (int i = 0; i < inv.getSizeInventory() && got < n; i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (s == null || s.getItem() != item) continue;
                ItemStack t = inv.decrStackSize(i, n - got);
                if (t == null) continue;
                got += t.stackSize;
                Skills.put(a, t, Ledger.Way.CHEST_OUT);
            }
            inv.markDirty();
            return got;
        }
    }

    static int[] furnace;

    /** a furnace stands there (burning or cold) */
    static boolean isFurnace(WorldServer w, int[] f) {
        return Roles.is(w, f, Roles.Role.FURNACE);
    }

    /**
     * The smith: cobblestone from the chest, a furnace made at the table and put down, logs in it with planks for fuel,
     * charcoal out, torches of charcoal and sticks; a stone pickaxe for the player. The others: a stone pickaxe each.
     */
    static class Upgrade extends Crew.Phase {
        int torches = 16;
        boolean crewStone = true, giftPickaxe = true;
        Upgrade() { }
        Upgrade(int torches, boolean crewStone, boolean giftPickaxe) { this(); this.torches = torches; this.crewStone = crewStone; this.giftPickaxe = giftPickaxe; }
        void start(final Crew c, final WorldServer w, EntityPlayerMP p) {
            c.planSite(w);
            final int[] t = c.table;
            if (t == null || c.agents.isEmpty()) return;
            int[] d = c.doorDir();
            if (furnace == null) {
                furnace = new int[] { t[0] + d[1] * -2, t[1], t[2] + d[0] * -2 };
                int fy = Crew.ground(w, furnace[0], t[1], furnace[2]);
                if (fy >= 0) furnace[1] = fy;
            }
            final int[] f = furnace;
            // the smith's card: the furnace, charcoal, torches and the player's pickaxe
            final boolean built = isFurnace(w, furnace);   // a furnace from before: no cobblestone for it
            final int smelt = (torches + 3) / 4, fuelPlanks = Math.max(4, (smelt * 2 + 2) / 3 + 3) / 4 * 4, cobble = (built ? 0 : 8) + (giftPickaxe ? 3 : 0),
                    sticks = smelt + (giftPickaxe ? 2 : 0), logs = smelt + fuelPlanks / 4;
            final int torchN = torches;
            budget(60, 0, 1, 1 + fuelPlanks / 4 + (torches + 3) / 4 + c.agents.size(), smelt, 0);
            final boolean gp = giftPickaxe;
            TaskBoard.Card fc = c.board.post("furnace", () -> {
                List<Crew.Task> s = new ArrayList<Crew.Task>();
                s.add(new Take(c, COBBLE, cobble));
                if (sticks > 0) s.add(new Take(c, Items.stick, sticks));
                if (!isFurnace(w, f)) {
                    s.add(new Skills.Craft(FURNACE, -1, 1));
                    s.add(new Skills.PlaceFrom(f[0], f[1], f[2], FURNACE, -1));
                }
                if (torchN > 0) {
                    s.add(new Take(c, LOG, logs));
                    s.add(new Skills.Craft(PLANKS, -1, fuelPlanks));   // the fuel: a plank burns 1.5 items
                    s.add(new Skills.Smelt(LOG, -1, smelt, PLANKS));
                    s.add(new Skills.Craft(TORCH, -1, torchN));
                }
                if (gp) s.add(new Skills.Craft(Items.stone_pickaxe, -1, 1));
                return new TaskBoard.Seq(s.toArray(new Crew.Task[0]));
            }).wants(COBBLE, cobble, built ? Math.min(cobble, 3) : 8).prio(3).at(f[0], f[1], f[2]);
            if (sticks > 0) fc.wants(Items.stick, sticks, 1);
            if (torches > 0) fc.wants(LOG, logs, 1);
            int without = 0;
            for (Agent a : c.agents) if (Skills.count(a.bag, Items.stone_pickaxe, -1) == 0) without++;
            if (crewStone) for (int k = 0; k < Math.min(without, c.agents.size() - 1); k++)   // a stone pickaxe for each of the others who has none, made by himself
                c.board.post("stone pickaxe", () -> new TaskBoard.Seq(new Take(c, COBBLE, 3), new Take(c, Items.stick, 2), new Skills.Craft(Items.stone_pickaxe, -1, 1)))
                        .need(a -> Skills.count(a.bag, Items.stone_pickaxe, -1) == 0).wants(COBBLE, 3, 3).wants(Items.stick, 2, 2).prio(2).at(t[0], t[1], t[2]).optional();
        }
    }

    /** the smith brings the player the stone pickaxe it made, and torches */
    static class Gift extends Crew.Phase {
        List<ItemStack> items = new ArrayList<ItemStack>(java.util.Arrays.asList(new ItemStack(Items.stone_pickaxe), new ItemStack(TORCH, 8)));
        Gift() { }
        Gift(List<ItemStack> items) { this.items = items; }
        void start(Crew c, final WorldServer w, final EntityPlayerMP p) {
            if (c.agents.isEmpty()) return;
            final double px = p.posX, py = p.posY, pz = p.posZ;
            budget(80, 0, 0, 0, 0, 0);   // to the chest, to the player
            // whoever made the torches (and so the player's pickaxe) brings them; the others turn to look
            final List<ItemStack> give = items;
            final TaskBoard.Card gift = c.board.post("gift", () -> {
                Crew.Task[] s = new Crew.Task[give.size()];
                for (int i = 0; i < s.length; i++) s[i] = new Skills.Give(give.get(i).getItem(), give.get(i).stackSize);
                return new TaskBoard.Seq(s);   // each Give fetches from the chest what the bag lacks; a shortfall fails the card with what was short
            }).prio(4);
            for (Agent g : c.agents)
                c.board.post("watch", () -> new Crew.Look(px, py + 1.5, pz, 50)).need(a -> gift.state != TaskBoard.State.OPEN).pin(g).prio(1).work(0, 0, 0, 0, 0, 50);
        }
    }

    // ---- the base: a house with a stepped roof, a door, windows; a fence with a gate; torches --------------

    /** the planks, logs, cobblestone, fences, torches and door a plan takes */
    static Map<String, Integer> bill(List<int[]> plan, boolean door) {
        Map<String, Integer> m = new LinkedHashMap<String, Integer>();
        for (int[] b : plan) Ledger.add(m, new String[] { "planks", "log", "cobblestone", "glass", "fence", "torch" }[b[3]], 1);
        if (door) Ledger.add(m, "door", 1);
        return m;
    }

    /** logs a bill takes, made into planks, sticks, fences and the door (torches need charcoal, counted apart) */
    static int logsFor(Map<String, Integer> m) {
        int planks = get(m, "planks") + get(m, "fence") * 3 / 2 + get(m, "door") * 6 + (get(m, "torch") + 3) / 4 / 2;
        return get(m, "log") + (planks + 3) / 4;
    }

    static int get(Map<String, Integer> m, String k) {
        Integer v = m.get(k);
        return v == null ? 0 : v;
    }

    /** the wood the day's base takes, with its tools (for the wood chain's target) */
    static int woodNeeded() {
        return 75 + 12;
    }

    static class Base extends Crew.House {
        int[] doorAt;
        int maxSize = 2;   // the biggest asked for (the wood may make it smaller)
        Base() { }
        Base(int maxSize) { this(); this.maxSize = maxSize; }

        static int woodNeeded() {
            return Day1.woodNeeded();
        }

        /** the plan of a size: 2 the full base (house, fence, torches), 1 the house only, 0 a small hut */
        void lay(Crew c, WorldServer w, int size, int stone) {
            plan.clear();
            int[] s = Crew.site;
            int half = size == 0 ? 2 : 3;
            int x0 = s[0] + 2 - half, y0 = s[1], z0 = s[2] + 2 - half, side = 2 * half;
            int[] d = c.doorDir();
            int cx = x0 + half, cz = z0 + half;
            for (int y = y0; y <= y0 + (size == 0 ? 2 : 3); y++)
                for (int x = x0; x <= x0 + side; x++)
                    for (int z = z0; z <= z0 + side; z++) {
                        if (!(x == x0 || x == x0 + side || z == z0 || z == z0 + side)) continue;
                        boolean corner = (x == x0 || x == x0 + side) && (z == z0 || z == z0 + side);
                        int lx = x - cx, lz = z - cz;
                        boolean front = d[0] != 0 ? lx == half * d[0] : lz == half * d[1];
                        boolean door = front && (d[0] != 0 ? lz == 0 : lx == 0) && y <= y0 + 1;
                        boolean window = (y == y0 + 1 || size > 0 && y == y0 + 2) && !corner && !front
                                && (Math.abs(lx) == 1 && Math.abs(lz) == half || Math.abs(lz) == 1 && Math.abs(lx) == half);
                        if (door) continue;
                        if (window) { plan.add(new int[] { x, y, z, 4 }); continue; }
                        plan.add(new int[] { x, y, z, corner ? 1 : y == y0 && stone >= 2 * side * 2 ? 2 : 0 });
                    }
            int ry = y0 + (size == 0 ? 3 : 4);
            for (int x = x0 - 1; x <= x0 + side + 1; x++)
                for (int z = z0 - 1; z <= z0 + side + 1; z++) plan.add(new int[] { x, ry, z, 0 });
            for (int x = x0 + 1; x <= x0 + side - 1; x++)
                for (int z = z0 + 1; z <= z0 + side - 1; z++) plan.add(new int[] { x, ry + 1, z, 0 });
            plan.add(new int[] { cx, ry + 2, cz, 1 });
            doorAt = new int[] { cx + half * d[0], y0, cz + half * d[1] };
            plan.add(new int[] { doorAt[0] + d[0] + (d[1] != 0 ? 1 : 0), y0, doorAt[2] + d[1] + (d[0] != 0 ? 1 : 0), 5 });   // torches either side of the door
            plan.add(new int[] { doorAt[0] + d[0] - (d[1] != 0 ? 1 : 0), y0, doorAt[2] + d[1] - (d[0] != 0 ? 1 : 0), 5 });
            if (size < 2) return;
            int fx0 = x0 - 5, fx1 = x0 + side + 5, fz0 = z0 - 5, fz1 = z0 + side + 5;   // the fence, a gate before the door
            for (int x = fx0; x <= fx1; x++)
                for (int z = fz0; z <= fz1; z++) {
                    if (x != fx0 && x != fx1 && z != fz0 && z != fz1) continue;
                    int lx = x - cx, lz = z - cz;
                    boolean gate = d[0] != 0 ? lx * d[0] > 0 && x == (d[0] > 0 ? fx1 : fx0) && Math.abs(lz) <= 1
                            : lz * d[1] > 0 && z == (d[1] > 0 ? fz1 : fz0) && Math.abs(lx) <= 1;
                    if (gate) continue;
                    int gy = Crew.ground(w, x, y0, z);
                    if (gy < 0 || Math.abs(gy - y0) > 2) continue;
                    if (mine != null && Math.abs(x - mine[0]) <= 2 && Math.abs(z - mine[2]) <= 2) continue;
                    plan.add(new int[] { x, gy, z, 4 });
                    boolean corner = (x == fx0 || x == fx1) && (z == fz0 || z == fz1);
                    boolean byGate = d[0] != 0 ? Math.abs(lz) == 2 && x == (d[0] > 0 ? fx1 : fx0) : Math.abs(lx) == 2 && z == (d[1] > 0 ? fz1 : fz0);
                    if (corner || byGate) plan.add(new int[] { x, gy + 1, z, 5 });
                }
        }

        /** the bill of what is still to be made for the laid plan: blocks not standing yet, less what the crew has made already */
        Map<String, Integer> toMake(Crew c, WorldServer w) {
            List<int[]> left = new ArrayList<int[]>();
            for (int[] b : plan) if (Crew.soft(w, b[0], b[1], b[2]) && !Ground.passages.contains(Crew.key(b[0], b[1], b[2]))) left.add(b);
            boolean door = doorAt == null || !Roles.is(w, doorAt, Roles.Role.DOOR);
            Map<String, Integer> m = bill(left, door);
            less(m, "planks", have(c, w, PLANKS));
            less(m, "fence", have(c, w, FENCE));
            less(m, "door", have(c, w, Items.wooden_door));
            less(m, "torch", have(c, w, TORCH));
            return m;
        }

        static void less(Map<String, Integer> m, String k, int n) {
            if (m.containsKey(k)) m.put(k, Math.max(0, m.get(k) - n));
        }

        static int have(Crew c, WorldServer w, Item item) {
            IInventory inv = c.chestInv(w);
            int n = inv == null ? 0 : Crew.count(inv, item);
            for (Agent a : c.agents) n += Skills.count(a.bag, item, -1);
            return n;
        }

        int size;
        Base lastBefore;

        /** blocks of the plan standing as planned */
        int standing(WorldServer w) {
            int n = 0;
            for (int[] b : plan) {
                Block is = w.getBlock(b[0], b[1], b[2]);
                if (is == Crew.block(b[3]) || b[3] == 1 && (is == Blocks.log || is == Blocks.log2)) n++;
            }
            return n;
        }

        void start(final Crew c, WorldServer w, EntityPlayerMP p) {
            c.planSite(w);
            lastBefore = lastBase;
            lastBase = this;
            if (firstBase == null) firstBase = this;
            IInventory inv = c.chestInv(w);
            int stone = inv == null ? 0 : Crew.count(inv, COBBLE);
            int wood = crewLogs(c, w), torches = 0;
            if (inv != null) torches = Crew.count(inv, TORCH);
            for (Agent a : c.agents) torches += Skills.count(a.bag, TORCH, -1);
            int size = maxSize;   // the biggest the wood allows (what stands already and what is made already do not count)
            Base before = lastBefore;
            if (before != null && before.standing(w) > 0) {   // a base begun before: the same one, on (what it lacks is fetched by need)
                size = before.size;
                lay(c, w, size, stone);
            } else {
                for (; size > 0; size--) {
                    lay(c, w, size, stone);
                    if (logsFor(toMake(c, w)) <= wood) break;
                }
                if (size == 0) lay(c, w, 0, stone);
            }
            this.size = size;
            int high = 0;   // blocks over the eyes from the ground: a row's or a layer's first one goes up from a pillar (two blocks put, two dug)
            for (int[] pb : plan) if (pb[1] >= Crew.site[1] + 2) high++;
            int pillars = high / 6;
            budget(plan.size() + pillars * 6, pillars * 3, plan.size() + pillars * 3, plan.size() / 4, 0, 0);   // every block walked to and put, the planks and fences made
            Map<String, Integer> b = toMake(c, w);
            Events.log("crew: base of size " + size + ", " + plan.size() + " blocks, bill " + b + ", wood " + wood + " logs (needs " + logsFor(b) + "), torches " + torches);
            show(c, w);
            final Base self = this;
            final Map<String, Integer> bill = b;
            final int[] t = c.table, dr = doorAt;
            int logs = logsFor(b) - get(b, "log");
            // the materials made at the table, into the chest; then everyone builds; the door last
            TaskBoard.Card prep = c.board.post("prepare", () -> new Prepare(c, bill)).wants(LOG, Math.min(logs, wood), 1).prio(4).at(t[0], t[1], t[2]);
            List<TaskBoard.Card> builds = new ArrayList<TaskBoard.Card>();
            for (int i = 0; i < c.agents.size(); i++) builds.add(c.board.post("build", () -> new Crew.Build(self)).after(prep).prio(3).at(Crew.site[0] + 2, Crew.site[1], Crew.site[2] + 2));
            c.board.post("door", () -> new TaskBoard.Seq(new Take(c, Items.wooden_door, 1), new Skills.PlaceFrom(dr[0], dr[1], dr[2], Items.wooden_door, -1))).after(builds).prio(3).at(dr[0], dr[1], dr[2]);
            Events.log("crew: " + (size == 2 ? "wood enough for a house with a fence" : size == 1 ? "a house, the fence later" : "little wood: a hut"));
        }
    }

    /** the crafter makes what a bill needs from the chest's logs (and the torches from what charcoal there is), into the chest */
    static class Prepare implements Crew.Task {
        final Crew c;
        final Map<String, Integer> bill;
        final List<Crew.Task> steps = new ArrayList<Crew.Task>();
        int i;
        Prepare(Crew c, Map<String, Integer> bill) { this.c = c; this.bill = bill; }
        public Result step(Agent a, WorldServer w) {
            if (steps.isEmpty()) {
                int fences = get(bill, "fence"), doors = get(bill, "door");
                int sticks = (fences + 1) / 2 * 6;   // 6 sticks make 2 fences (rounded up to a whole craft)
                int planks = get(bill, "planks") + (sticks + 3) / 4 * 2 + doors * 6;
                steps.add(new Take(c, LOG, (planks + 3) / 4));
                steps.add(new Skills.Craft(PLANKS, -1, planks));
                if (sticks > 0) steps.add(new Skills.Craft(Items.stick, -1, sticks));
                if (fences > 0) steps.add(new Skills.Craft(FENCE, -1, fences));
                if (doors > 0) steps.add(new Skills.Craft(Items.wooden_door, -1, doors));
                steps.add(c.new Stash(null, 0));
                say(a, "Нарежу досок, забор и дверь");
            }
            if (i >= steps.size()) return Result.OK;
            Result r = steps.get(i).step(a, w);
            if (r == Result.RUNNING) return r;
            if (r.failed()) Events.log("crew: prepare step " + i + " -> " + r);
            i++;
            return Result.RUNNING;
        }
    }

    /** nightfall: the crew comes to the mouth of the mine and looks down into it; one puts a torch there */
    static class Gather extends Crew.Phase {
        Gather() { }
        void start(Crew c, WorldServer w, EntityPlayerMP p) {
            int[] m = mine;
            if (m == null) { new Crew.Cheer().start(c, w, p); return; }
            c.board.goals.clear();   // the day's work is over: no default work
            int k = 0, n = c.agents.size();
            double far = 0;
            final double mx = m[0] + 0.5 + m[3] * 2.5 + (m[3] == 0 ? 0.5 : 0), mz = m[2] + 0.5 + m[4] * 2.5 + (m[4] == 0 ? 0.5 : 0);
            front = new double[] { mx, m[1] - 2, mz };
            Crew.front = front;
            Agent torcher = null;
            for (Agent a : c.agents) if (Skills.count(a.bag, TORCH, -1) > 0) torcher = a;
            final int tx0 = m[0] - m[3] + (m[3] == 0 ? -1 : 0), ty0 = m[1], tz0 = m[2] - m[4] + (m[4] == 0 ? -1 : 0), my = m[1];
            for (Agent a : c.agents) {   // a place for each round the mouth, pinned to it (the camera knows who stands where)
                double ang = Math.toRadians(-60 + 120.0 * k / Math.max(1, n - 1)) + Math.atan2(-m[3], -m[4]);
                final double tx = m[0] + 0.5 - m[3] * 1.5 + Math.sin(ang) * 2.2, tz = m[2] + 0.5 - m[4] * 1.5 + Math.cos(ang) * 2.2;
                int gy = Crew.ground(w, MathHelper.floor_double(tx), m[1], MathHelper.floor_double(tz));
                final int ty = gy < 0 ? m[1] : gy;
                final boolean torch = a == torcher;
                c.board.post("gather", () -> torch
                        ? new TaskBoard.Seq(new Crew.GoTo(tx, ty, tz, 0.7), new Skills.PlaceFrom(tx0, ty0, tz0, TORCH, -1), new Crew.Look(mx, my - 2.5, mz, 20 * 30)).lenient()
                        : new TaskBoard.Seq(new Crew.GoTo(tx, ty, tz, 0.7), new Crew.Look(mx, my - 2.5, mz, 20 * 30)).lenient()).pin(a).prio(2);
                far = Math.max(far, Crew.dist(a, tx, ty, tz));
                k++;
            }
            budget(far, 0, 1, 0, 0, 20 * 30);   // the walk there, the torch, the half minute of looking
            if (torcher != null) say(torcher, "Посвечу у входа");
        }
    }

    static volatile double[] front;
}
