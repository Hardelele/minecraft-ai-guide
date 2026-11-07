package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.List;

/**
 * How a body gets up or down a block where its walk gets nowhere, worked out on the blocks next to it (Sight.Space,
 * no game in it): a step cut into the wall in front (the block at head height and the one over it out: the wall's
 * block becomes a step), a pillar under itself, a step cut down (the three cells in front, the floor two under them
 * kept), never a drop of more than three. The reactions do what these say through the body.
 */
final class Moves {
    private Moves() { }

    /** may this block be dug (natural ground or rock outside the protected zones, and the bag's tools take it) */
    interface Dig { boolean may(int x, int y, int z); }

    static final int MAX_DROP = 3;

    /** what to do: nothing possible, a step (dig these, top first, then step onto `to`), a pillar under itself, a walk to `to` */
    static final class Plan {
        static final int NONE = 0, STEP = 1, PILLAR = 2, WALK = 3;
        final int kind;
        final List<int[]> dig = new ArrayList<int[]>();
        int[] to;
        String why = "";
        Plan(int kind) { this.kind = kind; }
        static Plan none(String why) { Plan p = new Plan(NONE); p.why = why; return p; }
        public String toString() {
            return (kind == NONE ? "none (" + why + ")" : kind == STEP ? "step" : kind == PILLAR ? "pillar" : "walk") + (to != null ? " to " + to[0] + " " + to[1] + " " + to[2] : "") + (dig.isEmpty() ? "" : ", dig " + dig.size());
        }
    }

    /** the axis direction (one of four) that goes most toward a point */
    static int[] toward(int fx, int fz, double gx, double gz) {
        double dx = gx - (fx + 0.5), dz = gz - (fz + 0.5);
        if (Math.abs(dx) >= Math.abs(dz)) return new int[] { dx >= 0 ? 1 : -1, 0 };
        return new int[] { 0, dz >= 0 ? 1 : -1 };
    }

    /** cells to clear for a body (free already, or dug): false when one of them can be neither */
    static boolean clear(Sight.Space s, Dig d, Plan p, int[]... cells) {
        for (int[] c : cells) {
            if (s.free(c[0], c[1], c[2])) continue;
            if (d == null || !d.may(c[0], c[1], c[2])) return false;
            p.dig.add(c);
        }
        return true;
    }

    /**
     * Up one block toward a direction from a feet cell: a step onto the block in front (room cut over it when the
     * wall is natural ground), else (or first, when the goal is straight over it) a pillar under itself.
     */
    static Plan up(Sight.Space s, Dig d, int x, int y, int z, int[] dir, boolean blocks, boolean over) {
        boolean head = s.free(x, y + 2, z);
        Plan pillar = new Plan(Plan.PILLAR);
        pillar.to = new int[] { x, y + 1, z };
        if (over && blocks && head) return pillar;
        int nx = x + dir[0], nz = z + dir[1];
        Plan step = new Plan(Plan.STEP);
        step.to = new int[] { nx, y + 1, nz };
        if (s.floor(nx, y, nz) && clear(s, d, step, new int[] { x, y + 2, z }, new int[] { nx, y + 2, nz }, new int[] { nx, y + 1, nz })) return step;
        if (blocks && head) return pillar;
        if (!blocks) return Plan.none(head ? "no blocks for a pillar, no step to cut" : "no room over the head, no step to cut");
        return Plan.none("no room over the head");
    }

    /**
     * Down toward a direction: a step down into the cell in front (its three cells cleared, the floor under it kept);
     * where the front is open, only a drop of up to three; a deeper one is not jumped.
     */
    static Plan down(Sight.Space s, Dig d, int x, int y, int z, int[] dir) {
        int nx = x + dir[0], nz = z + dir[1];
        if (!s.free(nx, y, nz) || !s.free(nx, y + 1, nz)) {   // a wall in front: cut a step down into it
            Plan p = new Plan(Plan.STEP);
            if (!clear(s, d, p, new int[] { nx, y + 1, nz }, new int[] { nx, y, nz }, new int[] { nx, y - 1, nz })) return Plan.none("the wall in front is not dug");
            if (!s.floor(nx, y - 2, nz)) return Plan.none("nothing to stand on under the step");
            p.to = new int[] { nx, y - 1, nz };
            return p;
        }
        int k = 0;   // open in front: how far down to a floor
        while (k <= MAX_DROP + 1 && s.free(nx, y - 1 - k, nz)) k++;
        if (k == 0) {   // level ground in front: dig the floor of the step (keep the one under it)
            Plan p = new Plan(Plan.STEP);
            if (!clear(s, d, p, new int[] { nx, y - 1, nz })) return Plan.none("the ground in front is not dug");
            if (!s.floor(nx, y - 2, nz)) return Plan.none("nothing to stand on under the step");
            p.to = new int[] { nx, y - 1, nz };
            return p;
        }
        if (k > MAX_DROP) return Plan.none("a drop of more than " + MAX_DROP);
        Plan p = new Plan(Plan.WALK);
        p.to = new int[] { nx, y - k, nz };
        return p;
    }

    /** a feet cell next to this one a body stands in (out of a pit): one of the four sides, level or a step up; or null */
    static int[] exit(Sight.Space s, int x, int y, int z) {
        for (int[] d : new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) {
            if (Sight.body(s, x + d[0], y, z + d[1])) return new int[] { x + d[0], y, z + d[1] };
            if (s.free(x, y + 2, z) && Sight.body(s, x + d[0], y + 1, z + d[1])) return new int[] { x + d[0], y + 1, z + d[1] };
        }
        return null;
    }

    /** walled in: no side open at the feet or the head */
    static boolean pit(Sight.Space s, int x, int y, int z) {
        for (int[] d : new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } })
            if (s.free(x + d[0], y, z + d[1]) && s.free(x + d[0], y + 1, z + d[1])) return false;
        return true;
    }

    /** where to run from a point to be `far` away from it (straight away, on the level) */
    static double[] away(double x, double z, double fromX, double fromZ, double far) {
        double dx = x - fromX, dz = z - fromZ, d = Math.sqrt(dx * dx + dz * dz);
        if (d < 1e-3) { dx = 1; dz = 0; d = 1; }
        return new double[] { fromX + dx / d * far, fromZ + dz / d * far };
    }
}
