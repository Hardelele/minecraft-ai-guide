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

    /** a cluster of one ore: its blocks, the nearest first */
    static List<int[]> cluster(WorldServer w, int x, int y, int z, Block ore, Set<Long> seen) {
        List<int[]> out = new ArrayList<int[]>();
        ArrayDeque<int[]> q = new ArrayDeque<int[]>();
        q.add(new int[] { x, y, z });
        seen.add(Crew.key(x, y, z));
        while (!q.isEmpty() && out.size() < 16) {
            int[] c = q.poll();
            out.add(c);
            for (int[] d : Skills.SIDE) {
                int nx = c[0] + d[0], ny = c[1] + d[1], nz = c[2] + d[2];
                if (w.getBlock(nx, ny, nz) != ore || !seen.add(Crew.key(nx, ny, nz))) continue;
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

    /**
     * The clusters of an ore around the base and the mine (within r, down to 40 below the base), each with its count;
     * the ones that can be got at are first: open and in reach of a walk from the base, then buried ones by how far
     * they are from the bottom of the mine (where digging to them starts); none in a protected zone.
     */
    static List<List<int[]>> clusters(Crew c, WorldServer w, Block ore, int r) {
        int bx = c.base[0], by = c.base[1], bz = c.base[2];
        int[] m = Day1.mine;
        double fx = m != null ? m[0] + m[3] * Math.max(8, Day1.mineDepth) : bx, fz = m != null ? m[2] + m[4] * Math.max(8, Day1.mineDepth) : bz;
        double fy = m != null ? m[1] - Math.max(8, Day1.mineDepth) : by;
        Ground.Flood f = Ground.baseFlood(c, w);
        Set<Long> seen = new HashSet<Long>();
        List<List<int[]>> found = new ArrayList<List<int[]>>();
        final List<Double> score = new ArrayList<Double>();
        for (int x = bx - r; x <= bx + r; x++)
            for (int z = bz - r; z <= bz + r; z++)
                for (int y = Math.max(2, by - 40); y <= by + 12; y++) {
                    if (w.getBlock(x, y, z) != ore || seen.contains(Crew.key(x, y, z))) continue;
                    List<int[]> cl = cluster(w, x, y, z, ore, seen);
                    boolean free = true;
                    for (int[] b : cl) if (!Ground.mayDig(c, b[0], b[1], b[2], "gather")) free = false;
                    if (!free) continue;
                    boolean reach = false;
                    for (int[] b : cl) if (open(w, b[0], b[1], b[2]) && f != null && f.near(b[0], b[1], b[2], Crew.REACH) != null) reach = true;
                    double d = Math.sqrt((x - fx) * (x - fx) + (y - fy) * (y - fy) * 2 + (z - fz) * (z - fz));
                    found.add(cl);
                    score.add((reach ? 0 : 100) + d - cl.size() * 0.5);
                }
        List<Integer> idx = new ArrayList<Integer>();
        for (int i = 0; i < found.size(); i++) idx.add(i);
        java.util.Collections.sort(idx, (a, b) -> Double.compare(score.get(a), score.get(b)));
        List<List<int[]>> out = new ArrayList<List<int[]>>();
        for (int i : idx) out.add(found.get(i));
        return out;
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
        OreRun(Block ore, int n) { this.ore = ore; this.n = n; this.level = ore == Blocks.iron_ore ? 1 : 0; max = 20 * 600; }
        void start(final Crew c, WorldServer w, EntityPlayerMP p) {
            c.planSite(w);
            List<List<int[]>> all = clusters(c, w, ore, 40);
            int there = 0;
            for (List<int[]> cl : all) there += cl.size();
            Events.log("crew: " + all.size() + " clusters of " + Ledger.name(new ItemStack(ore)) + " around (" + there + " blocks), " + n + " wanted");
            if (all.isEmpty()) return;   // none: the cards cannot be posted; the compiler is told by the failure below
            final Set<Long> taken = new HashSet<Long>();
            int planned = 0;
            for (List<int[]> cl : all) {
                if (planned >= n) break;
                planned += cl.size();
                c.board.post("ore", () -> new Crew.Task() {
                    List<int[]> mine;
                    Crew.Task t;
                    public Result step(Agent a, WorldServer ww) {
                        if (t == null) {   // the best cluster not taken by another card
                            for (List<int[]> k : clusters(c, ww, ore, 40)) {
                                if (taken.contains(Crew.key(k.get(0)[0], k.get(0)[1], k.get(0)[2]))) continue;
                                taken.add(Crew.key(k.get(0)[0], k.get(0)[1], k.get(0)[2]));
                                mine = k;
                                break;
                            }
                            if (mine == null) return Result.GONE;
                            int[] b = mine.get(0);
                            Events.log("crew: " + a.getCustomNameTag() + " goes for " + mine.size() + " " + Ledger.name(new ItemStack(ore)) + " at " + b[0] + " " + b[1] + " " + b[2]);
                            t = new TaskBoard.Seq(new Crew.GoTo(b[0] + 0.5, b[1], b[2] + 0.5, Crew.REACH - 0.6), new Crew.Mine(mine),
                                    new Crew.Collect(b[0] + 0.5, b[1], b[2] + 0.5, 5), c.new Stash(null, 0));
                        }
                        return t.step(a, ww);
                    }
                }).need(a -> pickaxeFor(a, ore)).needs(java.util.Arrays.asList(new ItemStack(level >= 1 ? Items.stone_pickaxe : Items.wooden_pickaxe))).prio(3);
            }
        }
    }

    /** smelt n of an ore in the crew's furnace: the ore and the fuel from the chest, the result to the chest */
    static class SmeltOre extends Crew.Phase {
        final Item in;
        final int n;
        SmeltOre(Item in, int n) { this.in = in; this.n = n; max = 20 * (60 + 11 * n); }
        void start(final Crew c, WorldServer w, EntityPlayerMP p) {
            final Item fuel = Day2.fuel(c, w, n);
            final int fuelN = fuelFor(fuel, n);
            int[] f = Day1.furnace;
            if (f == null) return;
            c.board.post("smelt", () -> new TaskBoard.Seq(new Day1.Take(c, in, n), new Day1.Take(c, fuel, fuelN), new Skills.Smelt(in, -1, n, fuel), c.new Stash(null, 0)))
                    .wants(in, n).wants(fuel, fuelN).prio(3).at(f[0], f[1], f[2]);
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
        Make(List<ItemStack> items) { this.items = items; max = 20 * 180; }
        void start(final Crew c, WorldServer w, EntityPlayerMP p) {
            int[] t = c.table;
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
        Shelter(int waitTicks) { this.wait = waitTicks; max = 20 * 180 + waitTicks; }
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
            final double[] look = b.doorAt == null ? new double[] { s[0] + 2.5, y0 + 1.5, s[2] + 2.5 } : new double[] { b.doorAt[0] + 0.5, b.doorAt[1] + 1.5, b.doorAt[2] + 0.5 };
            int k = 0;
            for (Agent a : c.agents) {
                if (spots.isEmpty()) break;
                final int[] sp = spots.get((k * 3) % spots.size());
                k++;
                c.board.post("inside", () -> new TaskBoard.Seq(new Crew.GoTo(sp[0] + 0.5, sp[1], sp[2] + 0.5, 0.7), new Crew.Look(look[0], look[1], look[2], wait)))
                        .pin(a).prio(3).at(sp[0], sp[1], sp[2]).lease(20 * 180 + wait);
            }
        }
    }

    /**
     * Light against the night: around the base (inside the fence) and down the mine, every spot where blocks give
     * less than 7 light (the sky does not count: it is the night's light) gets a torch, from the torches the crew has.
     */
    static class Light extends Crew.Phase {
        Light() { max = 20 * 240; }
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
            for (final int[] d : dark)
                c.board.post("light", () -> new TaskBoard.Seq(new Crew.GoTo(d[0] + 0.5, d[1], d[2] + 0.5, 1.2), new Skills.PlaceFrom(d[0], d[1], d[2], Day1.TORCH, -1)))
                        .need(a -> Skills.count(a.bag, Day1.TORCH, -1) > 0).prio(2).at(d[0], d[1], d[2]).optional();
            c.board.post("torches", () -> new Day1.Take(c, Day1.TORCH, dark.size())).prio(4);   // the torches from the chest first
        }
    }
}
