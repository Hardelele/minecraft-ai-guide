package hardelele.aiguide.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What an agent's eyes can see and from where, with no game in it (the world comes in through Space): a ray from
 * the eyes through the block grid, a block's face seen or not, and where to stand to see a face in reach: on the
 * ground it walks to, on a pillar of its own blocks, or a few steps from the top of such a pillar (onto a roof).
 * Faces are numbered as the game numbers them: 0 down, 1 up, 2 north (-z), 3 south (+z), 4 west (-x), 5 east (+x).
 */
final class Sight {
    static final int[][] SIDE = { { 0, -1, 0 }, { 0, 1, 0 }, { 0, 0, -1 }, { 0, 0, 1 }, { -1, 0, 0 }, { 1, 0, 0 } };
    static final double EYE = 1.62;

    /** the world as the eyes and the feet know it */
    interface Space {
        /** stops a ray from the eyes */
        boolean solid(int x, int y, int z);
        /** a body can be in it */
        boolean free(int x, int y, int z);
        /** one can stand on it */
        boolean floor(int x, int y, int z);
    }

    /** what it costs to get on foot to a feet cell; below 0: no way there */
    interface Walk { double cost(int x, int y, int z); }

    static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** a space with cells of its own on top: blocks put (a pillar) and cells a body must not take */
    static final class Overlay implements Space {
        final Space under;
        final Set<Long> blocks = new HashSet<Long>(), banned = new HashSet<Long>();
        Overlay(Space under) { this.under = under; }
        public boolean solid(int x, int y, int z) { return blocks.contains(key(x, y, z)) || under.solid(x, y, z); }
        public boolean free(int x, int y, int z) { long k = key(x, y, z); return !blocks.contains(k) && !banned.contains(k) && under.free(x, y, z); }
        public boolean floor(int x, int y, int z) { return blocks.contains(key(x, y, z)) || under.floor(x, y, z); }
    }

    /**
     * The first solid cell a ray from a to b goes into, and the face it goes in by: { x, y, z, face }, or null when the
     * way is clear. The cell the ray starts in does not count (the eyes are in it).
     */
    static int[] trace(Space s, double ax, double ay, double az, double bx, double by, double bz) {
        int x = (int) Math.floor(ax), y = (int) Math.floor(ay), z = (int) Math.floor(az);
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        int sx = dx > 0 ? 1 : dx < 0 ? -1 : 0, sy = dy > 0 ? 1 : dy < 0 ? -1 : 0, sz = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double tx = sx == 0 ? Double.MAX_VALUE : ((sx > 0 ? x + 1 - ax : ax - x) / Math.abs(dx));
        double ty = sy == 0 ? Double.MAX_VALUE : ((sy > 0 ? y + 1 - ay : ay - y) / Math.abs(dy));
        double tz = sz == 0 ? Double.MAX_VALUE : ((sz > 0 ? z + 1 - az : az - z) / Math.abs(dz));
        double ddx = sx == 0 ? Double.MAX_VALUE : 1 / Math.abs(dx), ddy = sy == 0 ? Double.MAX_VALUE : 1 / Math.abs(dy), ddz = sz == 0 ? Double.MAX_VALUE : 1 / Math.abs(dz);
        for (int i = 0; i < 256; i++) {
            int face;
            if (tx <= ty && tx <= tz) {
                if (tx > 1) return null;
                x += sx; tx += ddx; face = sx > 0 ? 4 : 5;
            } else if (ty <= tz) {
                if (ty > 1) return null;
                y += sy; ty += ddy; face = sy > 0 ? 0 : 1;
            } else {
                if (tz > 1) return null;
                z += sz; tz += ddz; face = sz > 0 ? 2 : 3;
            }
            if (s.solid(x, y, z)) return new int[] { x, y, z, face };
        }
        return null;
    }

    /** a point on a face of a block, just inside it (u, v across the face, -0.5..0.5) */
    static double[] facePoint(int x, int y, int z, int face, double u, double v) {
        int[] n = SIDE[face];
        double px = x + 0.5 + n[0] * 0.49, py = y + 0.5 + n[1] * 0.49, pz = z + 0.5 + n[2] * 0.49;
        if (n[0] != 0) { py += u; pz += v; }
        else if (n[1] != 0) { px += u; pz += v; }
        else { px += u; py += v; }
        return new double[] { px, py, pz };
    }

    static final double[][] SPOTS = { { 0, 0 }, { 0.3, 0.3 }, { -0.3, 0.3 }, { 0.3, -0.3 }, { -0.3, -0.3 } };

    /**
     * Where on a face of a block the eyes see it in reach: the point to aim at (the face's middle first, then near its
     * corners), or null. The face must look at the eyes and the ray to it must meet nothing before it.
     */
    static double[] seeFace(Space s, double ex, double ey, double ez, int x, int y, int z, int face, double reach) {
        int[] n = SIDE[face];
        double cx = x + 0.5 + n[0] * 0.5, cy = y + 0.5 + n[1] * 0.5, cz = z + 0.5 + n[2] * 0.5;
        if ((ex - cx) * n[0] + (ey - cy) * n[1] + (ez - cz) * n[2] <= 0.01) return null;   // the face looks away
        for (double[] uv : SPOTS) {
            double[] p = facePoint(x, y, z, face, uv[0], uv[1]);
            double ddx = p[0] - ex, ddy = p[1] - ey, ddz = p[2] - ez;
            if (ddx * ddx + ddy * ddy + ddz * ddz > reach * reach) continue;
            int[] hit = trace(s, ex, ey, ez, p[0], p[1], p[2]);
            if (hit != null && hit[0] == x && hit[1] == y && hit[2] == z && hit[3] == face) return p;
        }
        return null;
    }

    /** the nearest any face of a block is from the eyes */
    static double faceDist(double ex, double ey, double ez, int x, int y, int z) {
        double dx = Math.max(0, Math.max(x - ex, ex - (x + 1))), dy = Math.max(0, Math.max(y - ey, ey - (y + 1))), dz = Math.max(0, Math.max(z - ez, ez - (z + 1)));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** where to stand to put a block against a face: on the ground, on a pillar of `pillar` blocks, or a few steps off its top */
    static final class View {
        int[] foot;        // the feet cell walked to on the ground
        int pillar;        // blocks put under itself there (0: none)
        int[] stand;       // the feet cell the face is seen from (the foot, the pillar's top, or a step off it)
        int[] support;     // x, y, z, face: the block clicked and its face
        double[] aim;      // the point of the face looked at
        double cost;
        int steps;         // steps off the pillar's top (0: on it)
        public String toString() {
            return "stand " + stand[0] + " " + stand[1] + " " + stand[2] + (pillar > 0 ? " (pillar of " + pillar + " at " + foot[0] + " " + foot[1] + " " + foot[2] + (steps > 0 ? ", " + steps + " steps off it" : "") + ")" : "")
                    + ", face " + support[3] + " of " + support[0] + " " + support[1] + " " + support[2];
        }
    }

    /** the first support (x, y, z, face) seen from the eyes at a feet cell, with its aim point: { support, aim } or null */
    static Object[] seen(Space s, int fx, int fy, int fz, List<int[]> supports, double reach) {
        double ex = fx + 0.5, ey = fy + EYE, ez = fz + 0.5;
        for (int[] c : supports) {
            double[] p = seeFace(s, ex, ey, ez, c[0], c[1], c[2], c[3], reach);
            if (p != null) return new Object[] { c, p };
        }
        return null;
    }

    static boolean body(Space s, int x, int y, int z) {
        return s.free(x, y, z) && s.free(x, y + 1, z) && s.floor(x, y - 1, z);
    }

    /**
     * The cheapest place to stand to put a block at x y z (a body `height` cells tall must not be in it) against one of
     * its supports: first a feet cell it walks to; else the top of a pillar of 1..maxPillar blocks put on such a cell
     * (each costs); else a cell a few steps off a pillar's top (onto what is up there). Null: no such place.
     */
    static View find(Space s, Walk walk, int x, int y, int z, int height, List<int[]> supports, double reach, int maxPillar) {
        if (supports.isEmpty()) return null;
        Overlay o = new Overlay(s);
        for (int k = 0; k < height; k++) o.banned.add(key(x, y + k, z));
        int r = (int) Math.ceil(reach) + 1;
        View best = null;
        List<int[]> ground = new ArrayList<int[]>();
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                for (int dy = -r - 2; dy <= r; dy++) {
                    int fx = x + dx, fy = y + dy, fz = z + dz;
                    double c = walk.cost(fx, fy, fz);
                    if (c < 0 || !o.free(fx, fy, fz) || !o.free(fx, fy + 1, fz)) continue;
                    ground.add(new int[] { fx, fy, fz, (int) Math.round(c * 16) });
                    if (best != null && c >= best.cost) continue;
                    Object[] seen = seen(o, fx, fy, fz, supports, reach);
                    if (seen == null) continue;
                    best = view(new int[] { fx, fy, fz }, 0, new int[] { fx, fy, fz }, seen, c, 0);
                }
        if (best != null || maxPillar <= 0) return best;
        for (int pass = 0; pass < 2 && best == null; pass++)   // the pillar's top first; steps off it only when no top sees it
            for (int[] g : ground) {
                double c = g[3] / 16.0;
                for (int h = 1; h <= maxPillar; h++) {
                    boolean room = true;   // the pillar's cells and the room for the body on it
                    for (int k = 0; k <= h + 1 && room; k++) room = o.free(g[0], g[1] + k, g[2]);
                    if (!room) break;
                    double ch = c + h * 4 + 2;
                    if (best != null && ch >= best.cost) break;
                    Overlay p = new Overlay(s);
                    p.banned.addAll(o.banned);
                    for (int k = 0; k < h; k++) p.blocks.add(key(g[0], g[1] + k, g[2]));
                    int[] top = { g[0], g[1] + h, g[2] };
                    if (pass == 0) {
                        Object[] seen = seen(p, top[0], top[1], top[2], supports, reach);
                        if (seen != null) { best = view(new int[] { g[0], g[1], g[2] }, h, top, seen, ch, 0); break; }
                        continue;
                    }
                    for (int[] st : steps(p, top, 3)) {   // st: x, y, z, steps
                        double cs = ch + 4 + st[3] * 1.5;
                        if (best != null && cs >= best.cost) continue;
                        Object[] seen = seen(p, st[0], st[1], st[2], supports, reach);
                        if (seen != null) best = view(new int[] { g[0], g[1], g[2] }, h, new int[] { st[0], st[1], st[2] }, seen, cs, st[3]);
                    }
                }
            }
        return best;
    }

    static View view(int[] foot, int pillar, int[] stand, Object[] seen, double cost, int steps) {
        View v = new View();
        v.foot = foot;
        v.pillar = pillar;
        v.stand = stand;
        v.support = (int[]) seen[0];
        v.aim = (double[]) seen[1];
        v.cost = cost;
        v.steps = steps;
        return v;
    }

    /** the feet cells a few steps from a start (level, a step up with room over the head, a step down), the start left out */
    static List<int[]> steps(Space s, int[] from, int max) {
        List<int[]> out = new ArrayList<int[]>();
        Map<Long, Integer> seen = new HashMap<Long, Integer>();
        ArrayDeque<int[]> q = new ArrayDeque<int[]>();
        q.add(new int[] { from[0], from[1], from[2], 0 });
        seen.put(key(from[0], from[1], from[2]), 0);
        while (!q.isEmpty()) {
            int[] c = q.poll();
            if (c[3] >= max) continue;
            for (int[] d : SIDE) {
                if (d[1] != 0) continue;
                int nx = c[0] + d[0], nz = c[2] + d[2];
                for (int dy : new int[] { 0, 1, -1 }) {
                    int ny = c[1] + dy;
                    if (dy > 0 && !s.free(c[0], c[1] + 2, c[2])) continue;
                    if (!body(s, nx, ny, nz) || seen.containsKey(key(nx, ny, nz))) continue;
                    seen.put(key(nx, ny, nz), c[3] + 1);
                    int[] n = { nx, ny, nz, c[3] + 1 };
                    out.add(n);
                    q.add(n);
                    break;
                }
            }
        }
        return out;
    }
}
