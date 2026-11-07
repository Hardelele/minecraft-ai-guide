package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.world.WorldServer;

/**
 * The player's orders to the crew (the commander's right click, the guide's "take that hill"): each becomes a card on
 * the crew's one board, pinned to the agent it is for, of the player's origin and above everything else; the agent
 * is taken off what it did at once (that card goes back to the board), or, queued (Shift), does it after its orders
 * before. An agent given an order is the player's unit (commanded): it takes the player's orders only, until given
 * back to the AI. The skills are the crew's own (walk, chop, mine, fight, stash): nothing is done another way.
 */
class Orders {
    enum Kind { MOVE, CHOP, MINE, ATTACK, DEPOSIT, GUARD, CRAFT, SMELT, PLACE, STOP, RELEASE, HOLD, GIVE }

    static final double PRIO = 20;
    /** each agent's (by its number) last order card: a queued order goes after it */
    static final Map<Integer, TaskBoard.Card> last = new HashMap<Integer, TaskBoard.Card>();

    /** from any thread: the order goes to the crew's tick */
    static void give(final int[] agents, final Kind kind, final int x, final int y, final int z, final int entityId, final boolean queue) {
        give(agents, kind, x, y, z, entityId, queue, null, 0);
    }

    /** with a thing and how many (craft it, smelt it, put it there) */
    static void give(final int[] agents, final Kind kind, final int x, final int y, final int z, final int entityId, final boolean queue, final String item, final int n) {
        final Crew c = Crew.ensure();
        c.queue.add(new Runnable() {
            public void run() {
                net.minecraft.server.MinecraftServer s = net.minecraft.server.MinecraftServer.getServer();
                if (s == null) return;
                WorldServer w = s.worldServers[0];
                for (int n : agents) {
                    Agent a = null;
                    for (Agent g : c.agents) if (g.skin() == n && !g.isDead) a = g;
                    if (a == null) continue;
                    try {
                        apply(c, w, a, kind, x, y, z, entityId, queue, item, n);
                    } catch (Exception ex) {
                        Events.log("order failed: " + ex);
                    }
                }
            }
        });
    }

    static void apply(Crew c, WorldServer w, final Agent a, Kind kind, int x, int y, int z, int entityId, boolean queue, String item, int n) {
        if (kind == Kind.RELEASE || kind == Kind.STOP) {   // its orders go; stop: it stands (still the player's), release: back to the AI
            cancel(c, a);
            Crew.clearJobs(a);
            a.getNavigator().clearPathEntity();
            TaskBoard.Card held = c.board.leased.get(a);
            if (held != null) { c.board.release(a, held); held.state = TaskBoard.State.OPEN; }
            a.commanded = kind == Kind.STOP;
            Events.log("order: " + a.getCustomNameTag() + (kind == Kind.STOP ? " stops (holds)" : " is back with the AI"));
            return;
        }
        if (kind == Kind.HOLD) {   // work with this (the n-th stack of its bag): in hand now, its skills take it first
            if (n >= 0 && n < a.bag.size()) {
                ItemStack s = a.bag.get(n);
                a.preferred = s;
                a.setCurrentItemOrArmor(0, s);
                Events.log("order: " + a.getCustomNameTag() + " works with " + Ledger.name(s));
            }
            return;
        }
        if (!queue) cancel(c, a);
        final Crew.Task t = task(c, w, a, kind, x, y, z, entityId, item, n);
        if (t == null) return;
        Origin was = Crew.originNow;
        Crew.originNow = Origin.PLAYER;
        TaskBoard.Card k = c.board.post("order " + kind.name().toLowerCase(), () -> t).pin(a).prio(PRIO).lease(kind == Kind.CRAFT ? 20 * 1800 : 20 * 600);   // a craft may go to the world for its raw things
        Crew.originNow = was;
        k.phase = null;   // the player's, not a phase's
        TaskBoard.Card before = last.get(a.skin());
        if (queue && before != null && before.open()) k.after(before);
        last.put(a.skin(), k);
        a.commanded = true;
        Events.log("order: " + a.getCustomNameTag() + " " + kind + " " + x + " " + y + " " + z + (entityId >= 0 ? " #" + entityId : "") + (queue ? " (queued)" : ""));
        if (!queue || before == null || !before.open()) c.board.assign(k, a, w, "has an order");
    }

    /** the agent's open player orders go (given up: the player changed them) */
    static void cancel(Crew c, Agent a) {
        for (TaskBoard.Card k : c.board.cards)
            if (k.origin == Origin.PLAYER && k.only != null && TaskBoard.same(k.only, a) && k.open()) {
                if (k.holder != null) c.board.release(k.holder, k);
                k.state = TaskBoard.State.FAILED;
                k.last = Result.GONE;
            }
        last.remove(a.skin());
    }

    /** what the order is in the crew's skills */
    static Crew.Task task(Crew c, WorldServer w, Agent a, Kind kind, int x, int y, int z, int entityId, String item, int n) {
        switch (kind) {
            case MOVE: {   // onto the top of the block clicked (climbing up to it when there is a way), a walk only: nothing dug
                return new Crew.GoTo(x + 0.5, y + 1, z + 0.5, 0.8).walkOnly();
            }
            case CHOP: {   // the whole tree the block is of (trunk and branches), what fell picked up
                List<int[]> logs = Fell.tree(w, x, y, z);
                if (logs.isEmpty()) return null;
                int[] l0 = logs.get(0);
                return new TaskBoard.Seq(new Fell(logs), new Crew.Collect(l0[0] + 0.5, l0[1], l0[2] + 0.5, 6)).lenient();
            }
            case MINE: {   // the clicked block's vein (one kind of block, touching), or the block alone
                Block b = w.getBlock(x, y, z);
                List<int[]> blocks = Day2.cluster(w, x, y, z, b, new HashSet<Long>());
                if (blocks.isEmpty()) blocks = new ArrayList<int[]>(java.util.Arrays.asList(new int[] { x, y, z }));
                return new TaskBoard.Seq(new Crew.GoTo(x + 0.5, y, z + 0.5, Crew.REACH - 0.6), new Crew.Mine(blocks), new Crew.Collect(x + 0.5, y, z + 0.5, 5));
            }
            case ATTACK: {
                Entity e = w.getEntityByID(entityId);
                if (!(e instanceof EntityLiving)) return null;
                return new Guard.Fight((EntityLiving) e);
            }
            case DEPOSIT:
                return c.new Stash(null, 0);
            case GIVE: {   // to another of the crew (its number in entityId): walked over and handed, as the crew hands things
                Agent to = null;
                for (Agent g : c.agents) if (g.skin() == entityId && !g.isDead) to = g;
                net.minecraft.item.Item it = Compiler.item(item);
                if (to == null || it == null || to == a) return null;
                return new Skills.Give(to, it, Math.max(1, n));
            }
            case GUARD:   // stand at that spot and fight off what comes, until the order is taken back
                return new Guard.Fight(new double[] { x + 0.5, y + 1, z + 0.5 }, true);
            case CRAFT: {   // the parts from the chest (what is not in the bag), then made (at the table when the recipe is 3x3)
                net.minecraft.item.Item it = Compiler.item(item);
                if (it == null) return null;
                final net.minecraft.item.Item made = it;
                final int many = n;
                return new Crew.Task() {   // worked out when the card starts (what the bag and the chest have then)
                    Crew.Task t;
                    public Result step(Agent ag, WorldServer ww) {
                        if (t == null) {
                            List<Crew.Task> s = new ArrayList<Crew.Task>();
                            makePlan(c, ww, ag, made, many, 0, s, new java.util.HashMap<net.minecraft.item.Item, Integer>());
                            t = new TaskBoard.Seq(s.toArray(new Crew.Task[0]));
                        }
                        return t.step(ag, ww);
                    }
                };
            }
            case SMELT: {   // n of it (ore) from the chest into the crew's furnace with the best fuel there, the result to the chest
                net.minecraft.item.Item in = Compiler.item(item);
                if (in == null || Day1.furnace == null) return null;
                net.minecraft.item.Item fuel = Day2.fuel(c, w, n);
                return new TaskBoard.Seq(new Day1.Take(c, in, n), new Day1.Take(c, fuel, Day2.fuelFor(fuel, n)), new Skills.Smelt(in, -1, n, fuel), c.new Stash(null, 0));
            }
            case PLACE: {   // one block of a blueprint: from the bag, else from the chest, else made (planks from logs); worked out when the card starts
                final net.minecraft.item.Item it = Compiler.item(item);
                if (it == null) return null;
                final int px = x, py = y, pz = z;
                return new Crew.Task() {
                    Crew.Task t;
                    public Result step(Agent ag, WorldServer ww) {
                        if (t == null) {
                            List<Crew.Task> s = new ArrayList<Crew.Task>();
                            makePlan(c, ww, ag, it, 1, 0, s, new java.util.HashMap<net.minecraft.item.Item, Integer>());
                            s.add(new Skills.PlaceFrom(px, py, pz, it, -1));
                            t = new TaskBoard.Seq(s.toArray(new Crew.Task[0]));
                        }
                        return t.step(ag, ww);
                    }
                };
            }
            default:
                return null;
        }
    }

    /**
     * n of an item for this agent: what the bag has is used, what the chest has is taken, the rest is made from its parts
     * by the game's recipe (planks from logs, sticks from planks...), each part the same way, a few levels down.
     */
    static void makePlan(Crew c, WorldServer w, Agent a, net.minecraft.item.Item it, int n, int depth, List<Crew.Task> s, Map<net.minecraft.item.Item, Integer> have) {
        if (!have.containsKey(it)) have.put(it, Skills.count(a.bag, it, -1) * 1000 + TaskBoard.chestCount(c.chestInv(w), it));   // bag*1000 + chest
        int v = have.get(it), bag = v / 1000, chest = v % 1000;
        int fromBag = Math.min(bag, n), fromChest = Math.min(chest, n - fromBag), rest = n - fromBag - fromChest;
        have.put(it, (bag - fromBag) * 1000 + chest - fromChest);
        if (fromChest > 0) s.add(new Day1.Take(c, it, fromChest));
        if (rest <= 0) return;
        // 1. made at a table from its parts (a recipe that does not go round through the thing itself: no ingot from its block)
        List<Skills.Laid> rs = depth > 5 ? new ArrayList<Skills.Laid>() : Skills.recipesFor(it, -1);
        Skills.Laid l = null;
        for (Skills.Laid x : rs) if (!circular(x, it) && (l == null || Compiler.simple(x) && !Compiler.simple(l))) l = x;
        if (l != null) {
            int crafts = (rest + l.out.stackSize - 1) / l.out.stackSize;
            Map<net.minecraft.item.Item, Integer> parts = new java.util.LinkedHashMap<net.minecraft.item.Item, Integer>();
            for (List<net.minecraft.item.ItemStack> cell : l.cells) if (cell != null) Compiler.add(parts, cell.get(0).getItem(), crafts);
            for (Map.Entry<net.minecraft.item.Item, Integer> e : parts.entrySet()) makePlan(c, w, a, e.getKey(), e.getValue(), depth + 1, s, have);
            s.add(new Skills.Craft(it, -1, crafts * l.out.stackSize));
            return;
        }
        // 2. smelted in the crew's furnace from what the game smelts into it (ore into ingots...), with fuel the same way
        ItemStack in = depth > 5 || Day1.furnace == null ? null : smeltedFrom(it);
        if (in != null) {
            makePlan(c, w, a, in.getItem(), rest, depth + 1, s, have);
            net.minecraft.item.Item fuel = Day2.fuel(c, w, rest);
            if (Skills.count(a.bag, net.minecraft.init.Items.coal, -1) > 0) fuel = net.minecraft.init.Items.coal;
            makePlan(c, w, a, fuel, Day2.fuelFor(fuel, rest), depth + 1, s, have);
            s.add(new Skills.Smelt(in.getItem(), in.getMetadata() == 32767 ? -1 : in.getMetadata(), rest, fuel));
            return;
        }
        // 3. from the world: blocks that give it when broken (trees for logs, stone for cobblestone, ore for coal...)
        s.add(new Gather(it, rest));
    }

    /** a recipe that needs, among its parts, something that is itself made from the thing (a block of ingots for ingots) */
    static boolean circular(Skills.Laid l, net.minecraft.item.Item it) {
        for (List<ItemStack> cell : l.cells) {
            if (cell == null) continue;
            net.minecraft.item.Item part = cell.get(0).getItem();
            if (part == it) return true;
            for (Skills.Laid r : Skills.recipesFor(part, -1)) for (List<ItemStack> c2 : r.cells) if (c2 != null && c2.get(0).getItem() == it) return true;
        }
        return false;
    }

    /** what the furnace turns into this item, or null */
    @SuppressWarnings("unchecked")
    static ItemStack smeltedFrom(net.minecraft.item.Item it) {
        for (Map.Entry<ItemStack, ItemStack> e : ((Map<ItemStack, ItemStack>) net.minecraft.item.crafting.FurnaceRecipes.instance().getSmeltingList()).entrySet())
            if (e.getValue().getItem() == it && e.getKey().getItem() != it) {
                net.minecraft.item.Item src = e.getKey().getItem();
                if (src == Day1.LOG || src == Day1.LOG2) continue;   // charcoal: the world's coal first (gathered); a log is not burnt for it here
                return e.getKey();
            }
        return null;
    }

    /**
     * n of an item from the world, into the bag: the nearest blocks that give it when broken (a whole tree for logs, the
     * vein for ore), one after another, near the agent first; what cannot be found within 32 blocks fails honestly.
     */
    static class Gather implements Crew.Task {
        final net.minecraft.item.Item it;
        final int n;
        Crew.Task cur;
        final HashSet<Long> tried = new HashSet<Long>();
        int[] target;
        int start = -1, tries;
        Gather(net.minecraft.item.Item it, int n) { this.it = it; this.n = n; }

        static int[] nearestKnown(List<int[]> points, HashSet<Long> tried, int ax, int ay, int az,
                                  int radius, int below, int above, boolean planar) {
            int[] best = null;
            double distance = Double.MAX_VALUE;
            for (int[] p : points) {
                if (tried.contains(Crew.key(p[0], p[1], p[2]))) continue;
                if (Math.abs(p[0] - ax) > radius || Math.abs(p[2] - az) > radius || p[1] < ay - below || p[1] > ay + above) continue;
                double d = planar ? Math.hypot(p[0] - ax, p[2] - az) : Math.abs(p[0] - ax) + Math.abs(p[2] - az) + Math.abs(p[1] - ay) * 1.5;
                if (d < distance) { best = p; distance = d; }
            }
            return best;
        }

        public Result step(Agent a, WorldServer w) {
            Exploration.observe(Crew.instance, a, w);
            int have = Skills.count(a.bag, it, -1) + (it == Day1.LOG ? Skills.count(a.bag, Day1.LOG2, -1) : 0);
            if (start < 0) start = have;
            if (have - start >= n) return Result.OK;
            if (cur != null) {
                Result result = cur.step(a, w);
                if (result == Result.RUNNING || result == Result.BUSY) return Result.RUNNING;
                boolean expedition = cur instanceof Prospecting;
                cur = null;
                if (expedition) return result;
                if (target != null) { tried.add(Crew.key(target[0], target[1], target[2])); target = null; }
                have = Skills.count(a.bag, it, -1) + (it == Day1.LOG ? Skills.count(a.bag, Day1.LOG2, -1) : 0);
                if (have - start >= n) return Result.OK;
                if (++tries > n + 6) return have > start ? Result.OK : Result.NO_ITEMS;
            }
            int ax = (int) Math.floor(a.posX), ay = (int) Math.floor(a.posY), az = (int) Math.floor(a.posZ);
            if (it == Day1.LOG || it == Day1.LOG2) {
                List<int[]> known = new ArrayList<int[]>(Exploration.knownResources(w, it));
                if (it == Day1.LOG) known.addAll(Exploration.knownResources(w, Day1.LOG2));
                int[] best = nearestKnown(known, tried, ax, ay, az, 32, 8, 8, true);
                if (best == null) return have > start ? Result.OK : Result.NO_ITEMS;
                target = best.clone();
                final int[] found = target.clone();
                Crew.Task fell = new Crew.Task() {
                    Crew.Task task;
                    public Result step(Agent who, WorldServer world) {
                        if (task == null) {
                            if (!Exploration.visible(who, world, found[0], found[1], found[2])) return Result.NO_PATH;
                            if (!isLog(world.getBlock(found[0], found[1], found[2]))) {
                                Exploration.invalidate(world, found[0], found[1], found[2]); return Result.GONE;
                            }
                            List<int[]> logs = Fell.tree(world, found[0], found[1], found[2]);
                            if (logs.isEmpty()) return Result.NO_ITEMS;
                            task = new Fell(logs);
                        }
                        Result result = task.step(who, world);
                        if (world.isAirBlock(found[0], found[1], found[2])) Exploration.invalidate(world, found[0], found[1], found[2]);
                        return result;
                    }
                };
                cur = new TaskBoard.Seq(new Crew.GoTo(found[0] + 0.5, found[1], found[2] + 0.5, 3), fell,
                        new Crew.Collect(found[0] + 0.5, found[1], found[2] + 0.5, 6)).lenient();
                Events.log("crew: " + a.getCustomNameTag() + " approaches observed logs at " + found[0] + " " + found[1] + " " + found[2]);
                return Result.RUNNING;
            }
            if (it == Day2.IRON_ORE || it == net.minecraft.init.Items.coal) {
                cur = new Prospecting(Crew.instance, it, n - (have - start));
                return Result.RUNNING;
            }
            List<int[]> candidates = new ArrayList<int[]>();
            for (int[] point : Exploration.knownResources(w, it)) {
                if (Crew.instance == null || Ground.mayDig(Crew.instance, point[0], point[1], point[2], "gather")) candidates.add(point);
            }
            int[] best = nearestKnown(candidates, tried, ax, ay, az, 24, 16, 8, false);
            if (best == null) { Events.log("crew: " + a.getCustomNameTag() + " finds no " + Ledger.name(new ItemStack(it)) + " to dig near"); return have > start ? Result.OK : Result.NO_ITEMS; }
            final int[] found = best.clone();
            target = found.clone();
            Crew.Task harvest = new Crew.Task() {
                Crew.Task mine;
                public Result step(Agent who, WorldServer world) {
                    if (mine == null) {
                        if (!Exploration.visible(who, world, found[0], found[1], found[2])) return Result.NO_PATH;
                        boolean matches = false;
                        for (int[] p : Exploration.knownResources(world, it))
                            if (p[0] == found[0] && p[1] == found[1] && p[2] == found[2]) { matches = true; break; }
                        if (!matches) return Result.GONE;
                        mine = new Crew.Mine(new ArrayList<int[]>(java.util.Arrays.asList(found)));
                    }
                    Result result = mine.step(who, world);
                    if (world.isAirBlock(found[0], found[1], found[2])) Exploration.invalidate(world, found[0], found[1], found[2]);
                    return result;
                }
            };
            cur = new TaskBoard.Seq(new Crew.GoTo(found[0] + 0.5, found[1], found[2] + 0.5, Crew.REACH - 0.6), harvest,
                    new Crew.Collect(found[0] + 0.5, found[1], found[2] + 0.5, 5)).lenient();
            Events.log("crew: " + a.getCustomNameTag() + " approaches observed " + Ledger.name(new ItemStack(it)) + " at " + found[0] + " " + found[1] + " " + found[2]);
            return Result.RUNNING;
        }
    }

    /**
     * The player's designations, painted on the ground: blocks to dig out (from the top down: nothing of them is left
     * hanging) or to build (from the bottom up, each on the one under it), a card each on the board. For the picked
     * agents (shared round them), or for whoever is free when none is picked.
     */
    static void designate(final int[] agents, final boolean build, final List<int[]> blocks, final String material) {
        final Crew c = Crew.ensure();
        c.queue.add(new Runnable() {
            public void run() {
                WorldServer w = net.minecraft.server.MinecraftServer.getServer().worldServers[0];
                List<int[]> bs = new ArrayList<int[]>(blocks);
                java.util.Collections.sort(bs, (p, q) -> build ? p[1] - q[1] : q[1] - p[1]);
                List<Agent> who = new ArrayList<Agent>();
                for (int n : agents) for (Agent g : c.agents) if (g.skin() == n && !g.isDead) who.add(g);
                Map<Long, TaskBoard.Card> at = new HashMap<Long, TaskBoard.Card>();
                for (TaskBoard.Card k : c.board.cards) if (k.open() && k.at != null && (k.kind.equals("paint dig") || k.kind.equals("paint build")))
                    at.put(Crew.key((int) Math.floor(k.at[0]), (int) Math.floor(k.at[1]), (int) Math.floor(k.at[2])), k);
                net.minecraft.item.Item mat = build ? Compiler.item(material) : null;
                int n = 0, skipped = 0;
                Origin was = Crew.originNow;
                Crew.originNow = Origin.PLAYER;
                for (final int[] b : bs) {
                    long key = Crew.key(b[0], b[1], b[2]);
                    if (at.containsKey(key)) continue;   // painted already
                    Block blk = w.getBlock(b[0], b[1], b[2]);
                    Crew.Task t;
                    if (build) {
                        if (mat == null || !(Crew.soft(w, b[0], b[1], b[2]) || blk.getMaterial().isReplaceable())) { skipped++; continue; }
                        t = task(c, w, null, Kind.PLACE, b[0], b[1], b[2], -1, material, 1);
                    } else {
                        if (blk.getMaterial() == Material.air || blk.getMaterial().isLiquid() || blk.getBlockHardness(w, b[0], b[1], b[2]) < 0
                                || !Ground.mayDig(c, b[0], b[1], b[2], "gather")) { skipped++; continue; }   // nothing there, bedrock, or the crew's own (its base, its stations)
                        t = new TaskBoard.Seq(new Crew.Mine(new ArrayList<int[]>(java.util.Arrays.asList(b))), new Crew.Collect(b[0] + 0.5, b[1], b[2] + 0.5, 4)).lenient();
                    }
                    final Crew.Task tt = t;
                    TaskBoard.Card k = c.board.post(build ? "paint build" : "paint dig", () -> tt).prio(8 + (build ? -b[1] : b[1]) * 0.001).at(b[0] + 0.5, b[1], b[2] + 0.5).lease(20 * 300);
                    k.placeChecked = true;   // (its own way there is the dig's: not the base's walk)
                    k.phase = null;
                    if (who.size() == 1) { k.pin(who.get(0)); who.get(0).commanded = true; }   // one agent: its own
                    else if (!who.isEmpty()) {   // several: theirs, shared out among them as each is free (who takes which, they decide)
                        k.group = new java.util.HashSet<Integer>();
                        for (Agent g : who) { k.group.add(g.skin()); g.commanded = true; }
                    }
                    TaskBoard.Card dep = at.get(Crew.key(b[0], b[1] + (build ? -1 : 1), b[2]));   // the one above (dig) or below (build) first
                    if (dep != null) k.after(dep);
                    at.put(key, k);
                    n++;
                }
                Crew.originNow = was;
                Events.log("order: " + (build ? "build " + material : "dig") + " " + n + " blocks" + (skipped > 0 ? " (" + skipped + " not)" : "") + (who.isEmpty() ? " for anyone free" : " for " + who.size() + " agents"));
            }
        });
    }

    /** the designations inside a box taken back */
    static void erase(final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
        final Crew c = Crew.ensure();
        c.queue.add(new Runnable() {
            public void run() {
                int n = 0;
                for (TaskBoard.Card k : c.board.cards) {
                    if (!k.open() || k.at == null || !(k.kind.equals("paint dig") || k.kind.equals("paint build"))) continue;
                    double x = k.at[0], y = k.at[1], z = k.at[2];
                    if (x < Math.min(x0, x1) || x > Math.max(x0, x1) + 1 || y < Math.min(y0, y1) || y > Math.max(y0, y1) || z < Math.min(z0, z1) || z > Math.max(z0, z1) + 1) continue;
                    if (k.holder != null) { Crew.clearJobs(k.holder); c.board.release(k.holder, k); }
                    k.state = TaskBoard.State.FAILED;
                    k.last = Result.GONE;
                    n++;
                }
                Events.log("order: " + n + " designations taken back");
            }
        });
    }

    /** what is painted and not done yet (for the screen: the client reads the board) */
    static List<int[]> painted() {
        List<int[]> out = new ArrayList<int[]>();
        Crew c = Crew.instance;
        if (c == null) return out;
        try {
            for (TaskBoard.Card k : new ArrayList<TaskBoard.Card>(c.board.cards))
                if (k.open() && k.at != null && (k.kind.equals("paint dig") || k.kind.equals("paint build")))
                    out.add(new int[] { (int) Math.floor(k.at[0]), (int) Math.floor(k.at[1]), (int) Math.floor(k.at[2]), k.kind.equals("paint build") ? 1 : 0, k.holder != null ? 1 : 0 });
        } catch (Exception e) {
            // read from another thread: the next frame shows it
        }
        return out;
    }

    /** the guide's crew_order tool: the same orders as the commander's clicks (the agents by number, a place) */
    static String fromGuide(String agents, String order, int x, int y, int z) {
        Crew c = Crew.ensure();
        List<Integer> who = new ArrayList<Integer>();
        if (agents == null || agents.trim().isEmpty() || agents.trim().equalsIgnoreCase("all")) { for (Agent a : new ArrayList<Agent>(c.agents)) who.add(a.skin()); }
        else for (String p : agents.split("[,\\s]+")) if (p.matches("\\d+")) who.add(Integer.parseInt(p));
        if (who.isEmpty()) return "No such agents (the crew: " + c.agents.size() + ").";
        int[] ws = new int[who.size()];
        for (int i = 0; i < ws.length; i++) ws[i] = who.get(i);
        String o = order == null ? "" : order.trim().toLowerCase();
        Kind k = o.startsWith("move") || o.startsWith("go") ? Kind.MOVE : o.startsWith("chop") ? Kind.CHOP : o.startsWith("mine") ? Kind.MINE : o.startsWith("attack") ? Kind.ATTACK
                : o.startsWith("guard") ? Kind.GUARD : o.startsWith("stop") ? Kind.STOP : o.startsWith("release") ? Kind.RELEASE : null;
        if (k == null) return "Unknown order '" + order + "': move, chop, mine, attack, guard, stop, release.";
        int id = -1;
        if (k == Kind.ATTACK) {   // the nearest hostile mob to the place
            WorldServer w = net.minecraft.server.MinecraftServer.getServer().worldServers[0];
            double bd = 24;
            for (Object e : w.loadedEntityList)
                if (Guard.hostile((Entity) e)) {
                    double d = ((Entity) e).getDistance(x + 0.5, y, z + 0.5);
                    if (d < bd) { bd = d; id = ((Entity) e).getEntityId(); }
                }
            if (id < 0) return "No hostile mob within 24 blocks of " + x + " " + y + " " + z + ".";
        }
        give(ws, k, x, y, z, id, false);
        return "Ordered: " + who + " " + k.name().toLowerCase() + (k == Kind.STOP || k == Kind.RELEASE ? "" : " at " + x + " " + y + " " + z) + ".";
    }

    static boolean isLog(Block b) {
        return b == Blocks.log || b == Blocks.log2 || b.getMaterial() == Material.wood && String.valueOf(Block.blockRegistry.getNameForObject(b)).contains("log");
    }

    /** what a right click on this block means: a tree is chopped, ore mined, the crew's chest gets the bag, else go there */
    static Kind meaning(net.minecraft.world.World w, int[] chest, int x, int y, int z) {
        Block b = w.getBlock(x, y, z);
        if ((isLog(b) || b.getMaterial() == Material.leaves) && !Fell.tree(w, x, y, z).isEmpty()) return Kind.CHOP;   // any block of a grown tree
        if (chest != null && chest[0] == x && chest[1] == y && chest[2] == z) return Kind.DEPOSIT;
        String n = Block.blockRegistry.getNameForObject(b);
        if (n != null && n.contains("_ore")) return Kind.MINE;
        return Kind.MOVE;
    }
}
