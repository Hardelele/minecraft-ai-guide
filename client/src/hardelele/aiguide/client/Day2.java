package hardelele.aiguide.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.WorldServer;

/**
 * The crew's second day: ore (coal for fuel and torches, iron for tools) found by looking at the ground around the
 * base and the mine, reached on foot or by digging to it (the same stuck machine and protected zones as everything
 * else), mined with a pickaxe that takes it, smelted in the furnace with what burns, made into iron things at the
 * table; and the base lit against the night. The quantities come from the compiler (the guide's goals); nothing here
 * knows a place in advance.
 */
class Day2 {
    static final Item IRON_ORE = Item.getItemFromBlock(Blocks.iron_ore);

    /** Join only observed blocks. Newly exposed vein neighbours are learned after a real harvest. */
    static List<int[]> cluster(WorldServer w, int x, int y, int z, Block ore, Set<Long> seen) {
        java.util.Map<Long, int[]> known = new java.util.HashMap<Long, int[]>();
        for (int[] p : Exploration.knownBlocks(w, ore)) known.put(Crew.key(p[0], p[1], p[2]), p);
        List<int[]> out = new ArrayList<int[]>();
        if (!known.containsKey(Crew.key(x, y, z))) return out;
        ArrayDeque<int[]> q = new ArrayDeque<int[]>();
        q.add(new int[] { x, y, z });
        seen.add(Crew.key(x, y, z));
        while (!q.isEmpty() && out.size() < 16) {
            int[] c = q.poll();
            out.add(c);
            for (int[] d : Skills.SIDE) {
                int nx = c[0] + d[0], ny = c[1] + d[1], nz = c[2] + d[2];
                if (!known.containsKey(Crew.key(nx, ny, nz)) || !seen.add(Crew.key(nx, ny, nz))) continue;
                q.add(new int[] { nx, ny, nz });
            }
        }
        return out;
    }

    /** an ore block is open: some face of it is air (it can be seen and reached without digging to it) */
    static boolean open(WorldServer w, int x, int y, int z) {
        for (int[] d : Skills.SIDE) if (Ground.passable(w, x + d[0], y + d[1], z + d[2], null)) return true;
        return false;
    }

    /** Observed deposits, without a base-radius or hidden-volume scan. r remains for caller compatibility. */
    static List<List<int[]>> clusters(Crew c, WorldServer w, Block ore, int r) {
        Set<Long> seen = new HashSet<Long>();
        List<List<int[]>> found = new ArrayList<List<int[]>>();
        for (int[] p : Exploration.knownBlocks(w, ore)) {
            if (seen.contains(Crew.key(p[0], p[1], p[2]))) continue;
            List<int[]> cl = cluster(w, p[0], p[1], p[2], ore, seen);
            cl.removeIf(b -> !Ground.mayDig(c, b[0], b[1], b[2], "gather"));
            if (!cl.isEmpty()) found.add(cl);
        }
        java.util.Collections.sort(found, (a, b) -> Double.compare(far(c, w, a), far(c, w, b)));
        return found;
    }

    /** how far a cluster is from the base (the way there is at least that long) */
    static double far(Crew c, WorldServer w, List<int[]> cl) {
        int[] b = Ground.anchor(c, w), o = cl.get(0);
        return Math.abs(o[0] - b[0]) + Math.abs(o[1] - b[1]) + Math.abs(o[2] - b[2]);
    }

    static boolean pickaxeFor(Agent a, Block ore) {
        for (ItemStack s : a.bag) if (s.getItem() instanceof net.minecraft.item.ItemPickaxe && s.canItemHarvestBlock(ore)) return true;
        return false;
    }

    /**
     * Ore of one kind, n of what it drops: a card for every cluster the count takes; the cluster is picked when an agent
     * takes the card (the best free one then), the agent walks there or digs its way (lighting it), mines the cluster
     * and brings it to the chest.
     */
    static class OreRun extends Crew.Phase {
        final Block ore;
        final int n, level;
        OreRun(Block ore, int n) { this.ore = ore; this.n = n; this.level = ore == Blocks.iron_ore ? 1 : 0; }
        void start(final Crew c, WorldServer w, EntityPlayerMP p) {
            c.planSite(w);
            List<List<int[]>> all = clusters(c, w, ore, 40);
            int there = 0;
            for (List<int[]> cl : all) there += cl.size();
            Events.log("crew: " + all.size() + " clusters of " + Ledger.name(new ItemStack(ore)) + " around (" + there + " blocks), " + n + " wanted");
            double walk = 0;
            int dig = 0, got = 0;
            for (List<int[]> cl : all) { if (got >= n) break; got += cl.size(); walk += far(c, w, cl) * 2; dig += (int) far(c, w, cl) * 2 + cl.size(); }
            budget(walk, dig, 0, 0, 0, 0);   // there and back for every cluster, dug at worst
            max = Math.max(max, 20 * 1800); // unknown terrain needs a bounded exploration budget too
            final Item drop = ore.getItemDropped(0, new java.util.Random(0), 0);
            c.board.post("ore", () -> new TaskBoard.Seq(new Prospecting(c, drop, n), c.new Stash(null, 0)))
                    .need(a -> pickaxeFor(a, ore)).needs(java.util.Arrays.asList(new ItemStack(level >= 1 ? Items.stone_pickaxe : Items.wooden_pickaxe))).prio(3)
                    .lease(20 * 1800);
        }
    }

    /** smelt n of an ore in the crew's furnace: the ore and the fuel from the chest, the result to the chest */
    static class SmeltOre extends Crew.Phase {
        final Item in;
        final int n;
        SmeltOre(Item in, int n) { this.in = in; this.n = n; }
        void start(final Crew c, WorldServer w, EntityPlayerMP p) {
            final Item fuel = Day2.fuel(c, w, n);
            budget(30, 0, 0, 0, n, 0);
            final int fuelN = fuelFor(fuel, n);
            int[] f = Day1.furnace;
            if (f == null) return;
            c.board.post("smelt", () -> new TaskBoard.Seq(new Day1.Take(c, in, n), new Day1.Take(c, fuel, fuelN), new Skills.Smelt(in, -1, n, fuel), c.new Stash(null, 0)))
                    .wants(in, n, 1).wants(fuel, fuelN, 1).prio(3).at(f[0], f[1], f[2]);
        }
    }

    /** the best fuel the crew has for n items: coal (8 each), then planks (1.5 each), then logs (1.5 each) */
    static Item fuel(Crew c, WorldServer w, int n) {
        if (Day1.Base.have(c, w, Items.coal) >= (n + 7) / 8) return Items.coal;
        if (Day1.Base.have(c, w, Day1.PLANKS) >= (n * 2 + 2) / 3) return Day1.PLANKS;
        return Day1.LOG;
    }

    static int fuelFor(Item fuel, int n) {
        int burn = net.minecraft.tileentity.TileEntityFurnace.getItemBurnTime(new ItemStack(fuel));
        return Math.max(1, (int) Math.ceil(200.0 * n / Math.max(1, burn)));
    }

    /** make things at the table from the chest: each item's parts taken, then crafted (one card a kind) */
    static class Make extends Crew.Phase {
        final List<ItemStack> items;
        Make(List<ItemStack> items) { this.items = items; }
        void start(final Crew c, WorldServer w, EntityPlayerMP p) {
            int[] t = c.table;
            int allCrafts = 0;
            for (ItemStack it : items) allCrafts += it.stackSize;
            budget(30, 0, 0, allCrafts, 0, 0);
            for (final ItemStack it : items) {
                c.board.post("make", () -> {
                    List<Crew.Task> s = new ArrayList<Crew.Task>();
                    java.util.Map<Item, Integer> parts = new java.util.LinkedHashMap<Item, Integer>();
                    List<Skills.Laid> rs = Skills.recipesFor(it.getItem(), -1);
                    if (!rs.isEmpty()) {
                        Skills.Laid l = rs.get(0);
                        int crafts = (it.stackSize + l.out.stackSize - 1) / l.out.stackSize;
                        for (List<ItemStack> cell : l.cells) if (cell != null) Compiler.add(parts, cell.get(0).getItem(), crafts);
                    }
                    for (java.util.Map.Entry<Item, Integer> e : parts.entrySet()) s.add(new Day1.Take(c, e.getKey(), e.getValue()));
                    s.add(new Skills.Craft(it.getItem(), -1, it.stackSize));
                    return new TaskBoard.Seq(s.toArray(new Crew.Task[0]));
                }).prio(3).at(t[0], t[1], t[2]);
            }
        }
    }

    /**
     * Into the base and wait there (the night): every agent to a free spot inside the house, by whatever way there is
     * to it (the door, or, when there is none, the stuck machine's last resort: a passage through the house's own wall),
     * then it waits there looking at the door.
     */
    static class Shelter extends Crew.Phase {
        final int wait;
        final boolean guard;
        Shelter(int waitTicks, boolean guard) { this.wait = waitTicks; this.guard = guard; }
        void start(final Crew c, WorldServer w, EntityPlayerMP p) {
            Day1.Base b = Day1.lastBase;
            int[] s = Crew.site;
            if (b == null || s == null) { Events.log("crew: no house to shelter in"); return; }
            int half = b.size == 0 ? 2 : 3, x0 = s[0] + 2 - half, z0 = s[2] + 2 - half, y0 = s[1];
            List<int[]> spots = new ArrayList<int[]>();
            for (int x = x0 + 1; x <= x0 + 2 * half - 1; x++)
                for (int z = z0 + 1; z <= z0 + 2 * half - 1; z++)
                    if (Ground.stand(w, x, y0, z, null)) spots.add(new int[] { x, y0, z });
            Events.log("crew: " + spots.size() + " free spots inside the house");
            budget(60, 4, 0, 0, 0, wait);   // the way in (a wall opened at worst), the wait
            final double[] look = b.doorAt == null ? new double[] { s[0] + 2.5, y0 + 1.5, s[2] + 2.5 } : new double[] { b.doorAt[0] + 0.5, b.doorAt[1] + 1.5, b.doorAt[2] + 0.5 };
            int k = 0;
            for (Agent a : c.agents) {
                if (spots.isEmpty()) break;
                final int[] sp = spots.get((k * 3) % spots.size());
                k++;
                c.board.post("inside", () -> new TaskBoard.Seq(new Crew.GoTo(sp[0] + 0.5, sp[1], sp[2] + 0.5, 0.7), new Crew.Look(look[0], look[1], look[2], wait)))
                        .pin(a).prio(3).at(sp[0], sp[1], sp[2]).work(40, 0, 0, 0, 0, wait);
            }
            if (guard) {   // the planned guard: the best-armed one stays out by the door until the others are in (the same skill as any fight)
                Agent g = Guard.pick(c, null);
                final double[] post = b.doorAt == null ? new double[] { s[0] + 2.5, y0, s[2] - 1.5 }
                        : new double[] { b.doorAt[0] + 0.5 + (b.doorAt[0] - (s[0] + 2)) * 0.6, y0, b.doorAt[2] + 0.5 + (b.doorAt[2] - (s[2] + 2)) * 0.6 };
                if (g != null) c.board.post("guard", () -> new Guard.Fight(post)).pin(g).prio(4).work(20, 0, 0, 0, 0, 20 * 90);
            }
        }
    }

    /**
     * Light against the night: around the base (inside the fence) and down the mine, every spot where blocks give
     * less than 7 light (the sky does not count: it is the night's light) gets a torch, from the torches the crew has.
     */
    static class Light extends Crew.Phase {
        Light() { }
        void start(final Crew c, WorldServer w, EntityPlayerMP p) {
            List<int[]> dark = new ArrayList<int[]>();
            int[] s = Crew.site;
            if (s != null)
                for (int x = s[0] - 6; x <= s[0] + 10; x += 3)
                    for (int z = s[2] - 6; z <= s[2] + 10; z += 3) {
                        int y = Crew.ground(w, x, s[1], z);
                        if (y >= 0 && w.getSavedLightValue(EnumSkyBlock.Block, x, y, z) < 7) dark.add(new int[] { x, y, z });
                    }
            int[] m = Day1.mine;
            if (m != null)
                for (int k = 2; k <= Math.max(8, Day1.mineDepth); k += 3) {
                    int x = m[0] + m[3] * k, y = m[1] - k, z = m[2] + m[4] * k;
                    if (w.getSavedLightValue(EnumSkyBlock.Block, x, y, z) < 7) dark.add(new int[] { x, y, z });
                }
            Events.log("crew: " + dark.size() + " dark spots at the base and in the mine");
            budget(dark.size() * 8, 0, dark.size(), 0, 0, 0);
            for (final int[] d : dark)
                c.board.post("light", () -> new TaskBoard.Seq(new Crew.GoTo(d[0] + 0.5, d[1], d[2] + 0.5, 1.2), new Skills.PlaceFrom(d[0], d[1], d[2], Day1.TORCH, -1)))
                        .need(a -> Skills.count(a.bag, Day1.TORCH, -1) > 0).prio(2).at(d[0], d[1], d[2]).optional();
            c.board.post("torches", () -> new Day1.Take(c, Day1.TORCH, dark.size())).prio(4);   // the torches from the chest first
        }
    }
}
