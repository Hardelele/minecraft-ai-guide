package hardelele.aiguide.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.block.BlockFence;
import net.minecraft.block.BlockWall;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.world.WorldServer;

/**
 * What the crew knows of the ground, the same for every skill:
 * <ul>
 * <li>where one can walk: a search over feet cells with the walker's own rules (a step up with room over the head, a
 * drop of up to three, swimming), so a place is known reachable or not before anyone sets off;</li>
 * <li>what must not be dug: one registry of protected zones (the base and the ground under it, the stations, the mine's
 * stairs and the floor of the way to it); every dig asks it, the mine's own digging included;</li>
 * <li>whether a dig leaves a way back: a dig is allowed only when the cell stays walkable back to the base, so nobody
 * digs a pit it cannot leave.</li>
 * </ul>
 */
class Ground {
    static final int MAX_NODES = 12000, MAX_DROP = 3;

    // ---- walking --------------------------------------------------------------------------------------

    static boolean liquid(WorldServer w, int x, int y, int z) {
        return w.getBlock(x, y, z).getMaterial().isLiquid();
    }

    /** a body can be in this cell: air, plants, or water (it swims); not lava */
    static boolean passable(WorldServer w, int x, int y, int z, Set<Long> air) {
        if (air != null && air.contains(Crew.key(x, y, z))) return true;
        Block b = w.getBlock(x, y, z);
        if (b.getMaterial() == Material.lava) return false;
        return Crew.soft(w, x, y, z) || b.getMaterial() == Material.water || b == Blocks.wooden_door;   // a wooden door opens to a hand
    }

    /** one can stand on this block: solid, not a fence or a wall (too high to step on), not leaves */
    static boolean floor(WorldServer w, int x, int y, int z, Set<Long> air) {
        if (air != null && air.contains(Crew.key(x, y, z))) return false;
        Block b = w.getBlock(x, y, z);
        if (b instanceof BlockFence || b instanceof BlockWall || b == Blocks.fence_gate) return false;
        return Crew.solid(w, x, y, z);
    }

    /** feet can be here: the cell and the one over it free, and a floor under it (or water to swim in) */
    static boolean stand(WorldServer w, int x, int y, int z, Set<Long> air) {
        if (!passable(w, x, y, z, air) || !passable(w, x, y + 1, z, air)) return false;
        return floor(w, x, y - 1, z, air) || liquid(w, x, y, z);
    }

    static final int[][] DIRS = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };

    /** the feet cells next to one: up a block (room over the head first), level, or down up to three */
    static List<int[]> next(WorldServer w, int x, int y, int z, Set<Long> air) {
        List<int[]> out = new ArrayList<int[]>(6);
        for (int[] d : DIRS) {
            int nx = x + d[0], nz = z + d[1];
            if (stand(w, nx, y, nz, air)) { out.add(new int[] { nx, y, nz }); continue; }
            if (passable(w, x, y + 2, z, air) && stand(w, nx, y + 1, nz, air)) { out.add(new int[] { nx, y + 1, nz }); continue; }
            if (!passable(w, nx, y, nz, air) || !passable(w, nx, y + 1, nz, air)) continue;
            for (int k = 1; k <= MAX_DROP; k++) {   // over the edge: down to the first floor
                if (stand(w, nx, y - k, nz, air)) { out.add(new int[] { nx, y - k, nz }); break; }
                if (!passable(w, nx, y - k, nz, air)) break;
            }
        }
        return out;
    }

    /** the feet cell of a body at x y z (a fraction above a block counts as on it) */
    static int[] feet(WorldServer w, double x, double y, double z) {
        int fx = (int) Math.floor(x), fy = (int) Math.floor(y + 0.01), fz = (int) Math.floor(z);
        for (int dy = 0; dy >= -2; dy--) if (stand(w, fx, fy + dy, fz, null)) return new int[] { fx, fy + dy, fz };
        return new int[] { fx, fy, fz };
    }

    /** every feet cell reachable on foot from a start, with its parent (for the route), within a box */
    static final class Flood {
        final Map<Long, Long> parent = new HashMap<Long, Long>();
        final Map<Long, int[]> cell = new HashMap<Long, int[]>();
        boolean has(int x, int y, int z) { return parent.containsKey(Crew.key(x, y, z)); }

        /** a reached feet cell from which a block is within reach (the eye at feet + 1.6) */
        int[] near(int x, int y, int z, double reach) {
            int[] best = null;
            double bd = 1e9;
            int r = (int) Math.ceil(reach);
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++)
                    for (int dy = -r - 2; dy <= r; dy++) {
                        int[] c = cell.get(Crew.key(x + dx, y + dy, z + dz));
                        if (c == null) continue;
                        double ex = c[0] + 0.5 - (x + 0.5), ey = c[1] + 1.6 - (y + 0.5), ez = c[2] + 0.5 - (z + 0.5);
                        double d = Math.sqrt(ex * ex + ey * ey + ez * ez);
                        if (d <= reach && d < bd) { bd = d; best = c; }
                    }
            return best;
        }

        /** the route to a reached cell, from the start */
        List<int[]> route(int[] to) {
            List<int[]> r = new ArrayList<int[]>();
            Long k = Crew.key(to[0], to[1], to[2]);
            while (k != null && cell.containsKey(k)) {
                r.add(cell.get(k));
                Long p = parent.get(k);
                if (p == null || p.equals(k)) break;
                k = p;
            }
            Collections.reverse(r);
            return r;
        }
    }

    static Flood flood(WorldServer w, int[] from, int radius, Set<Long> air) {
        Flood f = new Flood();
        ArrayDeque<int[]> q = new ArrayDeque<int[]>();
        long k0 = Crew.key(from[0], from[1], from[2]);
        f.parent.put(k0, k0);
        f.cell.put(k0, from);
        q.add(from);
        while (!q.isEmpty() && f.cell.size() < MAX_NODES) {
            int[] c = q.poll();
            long kc = Crew.key(c[0], c[1], c[2]);
            for (int[] n : next(w, c[0], c[1], c[2], air)) {
                if (Math.abs(n[0] - from[0]) > radius || Math.abs(n[2] - from[2]) > radius) continue;
                long kn = Crew.key(n[0], n[1], n[2]);
                if (f.parent.containsKey(kn)) continue;
                f.parent.put(kn, kc);
                f.cell.put(kn, n);
                q.add(n);
            }
        }
        return f;
    }

    // the flood from the base, kept a while (the world changes slowly; digs and builds drop it)
    static Flood fromBase;
    static long fromBaseAt = -1000;

    static Flood baseFlood(Crew c, WorldServer w) {
        if (c.base == null) return null;
        long t = w.getTotalWorldTime();
        if (fromBase == null || t - fromBaseAt > 100) {
            fromBase = flood(w, anchor(c, w), 64, null);
            fromBaseAt = t;
        }
        return fromBase;
    }

    static int[] anchorCell;

    /**
     * Where "the base" is for walking: the nearest cell one can stand in to the base's point (the point itself may be
     * built over: a fence, a wall); found again when the one before is no longer free
     */
    static int[] anchor(Crew c, WorldServer w) {
        int[] a = anchorCell;
        if (a != null && stand(w, a[0], a[1], a[2], null) && Math.abs(a[0] - c.base[0]) + Math.abs(a[2] - c.base[2]) <= 8) return a;
        int[] best = null;
        double bd = 1e9;
        for (int dx = -6; dx <= 6; dx++)
            for (int dz = -6; dz <= 6; dz++)
                for (int dy = -3; dy <= 3; dy++) {
                    int x = c.base[0] + dx, y = c.base[1] + dy, z = c.base[2] + dz;
                    if (!stand(w, x, y, z, null)) continue;
                    double d = Math.abs(dx) + Math.abs(dz) + Math.abs(dy) * 1.5;
                    if (d < bd) { bd = d; best = new int[] { x, y, z }; }
                }
        anchorCell = best != null ? best : new int[] { c.base[0], c.base[1], c.base[2] };
        return anchorCell;
    }

    static void changed() {
        fromBaseAt = -1000;
    }

    /** can an agent get within reach of a block from where it stands now */
    static boolean reachable(Agent a, WorldServer w, int x, int y, int z, double reach) {
        Flood f = flood(w, feet(w, a.posX, a.posY, a.posZ), 64, null);
        return f.near(x, y, z, reach) != null;
    }

    /** what a dig route may go through and at what cost: 0 free, -1 never */
    interface DigCost { int cost(int x, int y, int z); }

    /**
     * The cheapest way on foot from one cell to near a point when the way may also go through blocks (each costing
     * what the rule says: natural ground a little, a wall of one's own a lot, a station never). Feet cells; a step up
     * needs room over the head where it starts. Returns the cells, the start first, or null.
     */
    static List<int[]> digRoute(WorldServer w, int[] from, int tx, int ty, int tz, double near, DigCost dc, int radius) {
        java.util.PriorityQueue<long[]> q = new java.util.PriorityQueue<long[]>(64, (p, r) -> Long.compare(p[0], r[0]));
        Map<Long, Long> parent = new HashMap<Long, Long>();
        Map<Long, Integer> dist = new HashMap<Long, Integer>();
        Map<Long, int[]> cell = new HashMap<Long, int[]>();
        long k0 = Crew.key(from[0], from[1], from[2]);
        dist.put(k0, 0);
        cell.put(k0, from);
        q.add(new long[] { 0, k0 });
        long found = Long.MIN_VALUE;
        int popped = 0;
        while (!q.isEmpty() && popped++ < MAX_NODES) {
            long[] e = q.poll();
            long kc = e[1];
            int[] c = cell.get(kc);
            if (e[0] > dist.get(kc)) continue;
            double dx = c[0] + 0.5 - tx, dy = c[1] + 1.6 - ty, dz = c[2] + 0.5 - tz;
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) <= near) { found = kc; break; }
            for (int[] d : DIRS)
                for (int up = -1; up <= 1; up++) {
                    int nx = c[0] + d[0], ny = c[1] + up, nz = c[2] + d[1];
                    if (Math.abs(nx - from[0]) > radius || Math.abs(nz - from[2]) > radius) continue;
                    // the cells to be free: the new feet and head; going up, the head room over the start too
                    int[][] need = up > 0 ? new int[][] { { c[0], c[1] + 2, c[2] }, { nx, ny, nz }, { nx, ny + 1, nz } }
                            : up < 0 ? new int[][] { { nx, ny + 2, nz }, { nx, ny + 1, nz }, { nx, ny, nz } }
                            : new int[][] { { nx, ny, nz }, { nx, ny + 1, nz } };
                    int cost = 1;
                    boolean ok = true;
                    for (int[] n : need) {
                        if (passable(w, n[0], n[1], n[2], null)) continue;
                        int k = dc.cost(n[0], n[1], n[2]);
                        if (k < 0) { ok = false; break; }
                        cost += k;
                    }
                    if (!ok) continue;
                    if (!floor(w, nx, ny - 1, nz, null) && !liquid(w, nx, ny, nz)) {   // nothing under: only a drop onto a floor below
                        continue;
                    }
                    long kn = Crew.key(nx, ny, nz);
                    int nd = dist.get(kc) + cost;
                    Integer od = dist.get(kn);
                    if (od != null && od <= nd) continue;
                    dist.put(kn, nd);
                    parent.put(kn, kc);
                    cell.put(kn, new int[] { nx, ny, nz });
                    q.add(new long[] { nd, kn });
                }
        }
        if (found == Long.MIN_VALUE) return null;
        List<int[]> r = new ArrayList<int[]>();
        for (Long k = found; k != null; k = parent.get(k)) r.add(cell.get(k));
        Collections.reverse(r);
        return r;
    }

    // ---- protected zones --------------------------------------------------------------------------------

    /** a zone: a box of blocks; 'owner' may dig in it (the mine its own stairs), nobody else */
    static final class Zone {
        final String name, owner;
        final int x0, y0, z0, x1, y1, z1;
        Zone(String name, String owner, int x0, int y0, int z0, int x1, int y1, int z1) {
            this.name = name; this.owner = owner;
            this.x0 = Math.min(x0, x1); this.y0 = Math.min(y0, y1); this.z0 = Math.min(z0, z1);
            this.x1 = Math.max(x0, x1); this.y1 = Math.max(y0, y1); this.z1 = Math.max(z0, z1);
        }
        boolean in(int x, int y, int z) { return x >= x0 && x <= x1 && y >= y0 && y <= y1 && z >= z0 && z <= z1; }
    }

    /** the zones now, worked out from what the crew has: rebuilt whenever asked (cheap: a handful of boxes) */
    static List<Zone> zones(Crew c) {
        List<Zone> z = new ArrayList<Zone>();
        int[] s = Crew.site;
        if (s != null) {   // the base: the house, its fence ring and the ground under them
            z.add(new Zone("base", null, s[0] - 7, s[1] - 3, s[2] - 7, s[0] + 11, s[1] + 8, s[2] + 11));
        }
        for (int[] st : new int[][] { c.chest, c.table, Day1.furnace })   // the stations and what they stand on
            if (st != null) z.add(new Zone("station", null, st[0], st[1] - 1, st[2], st[0], st[1], st[2]));
        int[] m = Day1.mine;
        if (m != null) {   // the stairs: the lanes and the floor under every step, down to as deep as planned
            int depth = Math.max(8, Day1.mineDepth);
            for (int k = 0; k <= depth + 1; k++) {
                int cx = m[0] + m[3] * k, cz = m[2] + m[4] * k, floor = m[1] - k;
                int ox = m[3] == 0 ? 1 : 0, oz = m[4] == 0 ? 1 : 0;   // the second lane beside the first
                z.add(new Zone("mine", "mine", cx, floor, cz, cx + ox, m[1] + 2, cz + oz));                // the steps: the mine's own digging
                z.add(new Zone("mine floor", null, cx - ox, floor - 1, cz - oz, cx + 2 * ox, floor - 1, cz + 2 * oz));   // what the steps stand on (both lanes and a block either side)
            }
            for (int[] p : mineWay) z.add(new Zone("way", null, p[0], p[1] - 1, p[2], p[0], p[1] - 1, p[2]));   // the floor of the way to it
        }
        return z;
    }

    static final List<int[]> mineWay = new ArrayList<int[]>();

    /** the way on foot from the base to the mine's mouth: its floor is kept */
    static void markMineWay(Crew c, WorldServer w) {
        mineWay.clear();
        Flood f = baseFlood(c, w);
        int[] m = Day1.mine;
        if (f == null || m == null) return;
        int[] at = f.near(m[0], m[1], m[2], 1.5);
        if (at != null) mineWay.addAll(f.route(at));
    }

    /** may this block be dug, by whom ("mine" for the mine's own steps, "exit" for a way out of the crew's own building, anything else for the rest) */
    static boolean mayDig(Crew c, int x, int y, int z, String who) {
        for (Zone zn : zones(c)) {
            if (!zn.in(x, y, z) || zn.owner != null && zn.owner.equals(who)) continue;
            if ("exit".equals(who) && zn.name.equals("base") && ownWall(c, x, y, z)) continue;   // the last way out: through its own wall
            return false;
        }
        return true;
    }

    /**
     * A block of the crew's own building that may be opened as a way out when there is no other: a wall block of its
     * base's plan (planks, logs, cobblestone) that stands as planned; never a station, a chest, a door, a fence, a torch.
     */
    static boolean ownWall(Crew c, int x, int y, int z) {
        Day1.Base b = Day1.lastBase;
        if (b == null || c.base == null) return false;
        net.minecraft.block.Block is = ((WorldServer) net.minecraft.server.MinecraftServer.getServer().worldServers[0]).getBlock(x, y, z);
        for (int[] p : b.plan)
            if (p[0] == x && p[1] == y && p[2] == z && p[3] <= 2 && (is == Crew.block(p[3]) || p[3] == 1 && (is == Blocks.log || is == Blocks.log2))) return true;
        return false;
    }

    /** the passages the crew opened in its own building: kept open (the base's builds leave them), saved with the base */
    static final java.util.Set<Long> passages = new java.util.HashSet<Long>();

    /**
     * May the block be dug and still leave a way back: the block itself and every block the dig is in (the zones),
     * and from the cell it leaves (and the cell it opens below, where one would fall) there is a walk to the base.
     */
    static boolean digLeavesWay(Crew c, WorldServer w, int x, int y, int z, String who) {
        if (!mayDig(c, x, y, z, who)) return false;
        if (c.base == null) return true;
        Set<Long> air = new java.util.HashSet<Long>();
        air.add(Crew.key(x, y, z));
        int[] base = anchor(c, w);
        // where a body could end up: in the hole (on what is under it) or where it stood beside it
        int fy = y;
        while (fy > y - 4 && passable(w, x, fy - 1, z, air)) fy--;
        int[] in = { x, fy, z };
        if (!stand(w, in[0], in[1], in[2], air)) return true;   // nobody can be in the hole (no floor at all): nothing to be trapped in
        Flood f = flood(w, in, 40, air);
        return f.has(base[0], base[1], base[2]) || f.near(base[0], base[1], base[2], 3) != null;
    }
}
