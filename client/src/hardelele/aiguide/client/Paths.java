package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.world.World;

/**
 * A way on foot through the loaded world, as the player walks: flat steps and diagonals, a jump up one
 * block, drops of up to three, wading through water; never through lava, fire or a block with a body.
 */
class Paths {
    static final int MAX = 12000, RANGE = 64;
    static final int[][] DIRS = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 }, { 1, 1 }, { 1, -1 }, { -1, 1 }, { -1, -1 } };

    static final class Node implements Comparable<Node> {
        final int x, y, z;
        double g, f;
        Node from;

        Node(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override public int compareTo(Node o) {
            return Double.compare(f, o.f);
        }
    }

    /** a block that stops a body */
    static boolean solid(World w, int x, int y, int z) {
        Block b = w.getBlock(x, y, z);
        return b.getMaterial() != Material.air && b.getCollisionBoundingBoxFromPool(w, x, y, z) != null;
    }

    static boolean hurts(World w, int x, int y, int z) {
        Material m = w.getBlock(x, y, z).getMaterial();
        return m == Material.lava || m == Material.fire || m == Material.cactus;
    }

    static boolean free(World w, int x, int y, int z) {
        return !solid(w, x, y, z) && !hurts(w, x, y, z);
    }

    static boolean water(World w, int x, int y, int z) {
        return w.getBlock(x, y, z).getMaterial() == Material.water;
    }

    /** the feet can be here: room for the body and ground (or water) under it */
    static boolean stand(World w, int x, int y, int z) {
        return free(w, x, y, z) && free(w, x, y + 1, z) && (solid(w, x, y - 1, z) || water(w, x, y, z) || water(w, x, y - 1, z)) && !hurts(w, x, y - 1, z);
    }

    /** close enough to reach the target block, and not standing in it */
    static boolean near(int[] f, int tx, int ty, int tz, double reach) {
        if (f[0] == tx && f[2] == tz && (f[1] == ty || f[1] + 1 == ty || Pilot.SMOOTH)) return false;   // (smooth: never under it, the camera would look straight up)
        double dx = tx + 0.5 - (f[0] + 0.5), dy = ty + 0.5 - (f[1] + 1.6), dz = tz + 0.5 - (f[2] + 0.5);
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= reach;
    }

    static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** the steps from the feet to a place within reach of the target, or null */
    static List<int[]> find(World w, int[] start, int tx, int ty, int tz, double reach) {
        Map<Long, Node> seen = new HashMap<Long, Node>();
        PriorityQueue<Node> open = new PriorityQueue<Node>();
        Node s = new Node(start[0], start[1], start[2]);
        s.f = h(s, tx, ty, tz);
        seen.put(key(s.x, s.y, s.z), s);
        open.add(s);
        Node best = null;
        for (int n = 0; !open.isEmpty() && n < MAX; n++) {
            Node c = open.poll();
            if (near(new int[] { c.x, c.y, c.z }, tx, ty, tz, reach)) { best = c; break; }
            for (int[] d : DIRS) {
                int nx = c.x + d[0], nz = c.z + d[1];
                if (Math.abs(nx - start[0]) > RANGE || Math.abs(nz - start[2]) > RANGE) continue;
                boolean diag = d[0] != 0 && d[1] != 0;
                if (diag && !(free(w, c.x + d[0], c.y, c.z) && free(w, c.x + d[0], c.y + 1, c.z) && free(w, c.x, c.y, c.z + d[1]) && free(w, c.x, c.y + 1, c.z + d[1])))
                    continue;   // no cutting corners
                double step = diag ? 1.414 : 1;
                if (stand(w, nx, c.y, nz)) visit(seen, open, c, nx, c.y, nz, step, tx, ty, tz);
                else if (!diag && stand(w, nx, c.y + 1, nz) && free(w, c.x, c.y + 2, c.z)) visit(seen, open, c, nx, c.y + 1, nz, step + 0.6, tx, ty, tz);
                else if (free(w, nx, c.y, nz) && free(w, nx, c.y + 1, nz))
                    for (int drop = 1; drop <= 3; drop++) {
                        if (stand(w, nx, c.y - drop, nz)) { visit(seen, open, c, nx, c.y - drop, nz, step + 0.3 * drop, tx, ty, tz); break; }
                        if (!free(w, nx, c.y - drop, nz)) break;
                    }
            }
        }
        if (best == null) return null;
        List<int[]> out = new ArrayList<int[]>();
        for (Node c = best; c != null && c != s; c = c.from) out.add(new int[] { c.x, c.y, c.z });
        Collections.reverse(out);
        return out;
    }

    static void visit(Map<Long, Node> seen, PriorityQueue<Node> open, Node c, int x, int y, int z, double cost, int tx, int ty, int tz) {
        long k = key(x, y, z);
        Node n = seen.get(k);
        double g = c.g + cost;
        if (n != null && n.g <= g) return;
        if (n == null) seen.put(k, n = new Node(x, y, z));
        else open.remove(n);
        n.g = g;
        n.f = g + h(n, tx, ty, tz);
        n.from = c;
        open.add(n);
    }

    static double h(Node n, int tx, int ty, int tz) {
        double dx = tx - n.x, dy = ty - n.y, dz = tz - n.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
