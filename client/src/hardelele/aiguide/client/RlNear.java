package hardelele.aiguide.client;

import java.util.Map;

/**
 * What is round an agent, with no game in it (docs/rl/CONTRACT-v2.md, v3.1): its six neighbour cells by its facing
 * (front, back, left, right at the feet; above over the head; below under the feet), what each holds (solid, air,
 * liquid, a mob), whether it may dig there (a block its tools take, not bedrock; under itself not over a drop or lava)
 * or put a block there (free, with something to put it against), whether it may pillar up (room over the head), how
 * many sides are walled at the feet, and whether it is shut in (walls at the feet and the head on all four sides, a
 * roof). RlEnv asks the world through Grid; RlTest checks it on synthetic grids (a pillar, a pit, four blocks round).
 */
final class RlNear {
    private RlNear() { }

    /** a cell's kind: what the agent meets there */
    static final int AIR = 0, SOLID = 1, WATER = 2, LAVA = 3, BEDROCK = 4, HARD = 5, LEAVES = 6;   // HARD: solid, but no tool of its bag takes it; LEAVES: a wall, nothing to put a block against

    interface Grid {
        int at(int x, int y, int z);
        boolean mob(int x, int y, int z);
    }

    static final String[] DIRS = { "front", "back", "left", "right", "above", "below" };
    static final String[] DIG = { "below", "front", "front_down", "left", "right", "back", "above" };
    static final String[] PUT = { "left", "right", "back", "above" };

    static boolean solid(int k) { return k == SOLID || k == HARD || k == BEDROCK || k == LEAVES; }

    /** a direction's offset for a facing (dx, dz the front): { dx, dy, dz } of the cell (the feet's level for the sides) */
    static int[] off(String d, int fx, int fz) {
        switch (d) {
            case "front": return new int[] { fx, 0, fz };
            case "back": return new int[] { -fx, 0, -fz };
            case "left": return new int[] { fz, 0, -fx };
            case "right": return new int[] { -fz, 0, fx };
            case "above": return new int[] { 0, 2, 0 };
            case "below": return new int[] { 0, -1, 0 };
            case "front_down": return new int[] { fx, -1, fz };
            default: throw new IllegalArgumentException(d);
        }
    }

    /** the cells a dig at d takes: a side its column (the head's cell first, then the feet's), else the one cell */
    static int[][] digCells(String d, int x, int y, int z, int fx, int fz) {
        int[] o = off(d, fx, fz);
        if (o[1] == 0) return new int[][] { { x + o[0], y + 1, z + o[2] }, { x + o[0], y, z + o[2] } };
        return new int[][] { { x + o[0], y + o[1], z + o[2] } };
    }

    /** something to put a block at c against (a solid neighbour other than the agent's own two cells) */
    static boolean support(Grid g, int[] c, int x, int y, int z) {
        int[][] n = { { 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 }, { 0, 0, 1 }, { 0, 0, -1 } };
        for (int[] d : n) {
            int nx = c[0] + d[0], ny = c[1] + d[1], nz = c[2] + d[2];
            if (nx == x && nz == z && (ny == y || ny == y + 1)) continue;
            int k = g.at(nx, ny, nz);
            if (solid(k) && k != LEAVES) return true;
        }
        return false;
    }

    /** the raw facts round an agent standing with its feet at x y z, facing fx fz; night or a wave on: shelter counts */
    static void facts(Map<String, Double> r, Grid g, int x, int y, int z, int fx, int fz, boolean nightOrWave) {
        for (String d : DIRS) {
            int[] o = off(d, fx, fz);
            int cx = x + o[0], cy = y + o[1], cz = z + o[2];
            int k = g.at(cx, cy, cz);
            r.put("nb_" + d + "_solid", solid(k) ? 1.0 : 0.0);
            r.put("nb_" + d + "_air", k == AIR ? 1.0 : 0.0);
            r.put("nb_" + d + "_liquid", k == WATER || k == LAVA ? 1.0 : 0.0);
            r.put("nb_" + d + "_mob", g.mob(cx, cy, cz) || o[1] == 0 && g.mob(cx, cy + 1, cz) ? 1.0 : 0.0);
        }
        for (String d : DIG) {
            boolean any = false, ok = true;
            for (int[] c : digCells(d, x, y, z, fx, fz)) {
                int k = g.at(c[0], c[1], c[2]);
                if (k == SOLID || k == LEAVES) any = true;
                if (k == HARD || k == BEDROCK) ok = false;
            }
            if (d.equals("below") && any) {   // under itself: not over lava, not over a drop of three or more
                int k2 = g.at(x, y - 2, z);
                if (k2 == LAVA) ok = false;
                if (k2 == AIR && g.at(x, y - 3, z) == AIR && g.at(x, y - 4, z) == AIR) ok = false;
                int[][] side = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
                for (int[] s : side) if (g.at(x + s[0], y - 1, z + s[1]) == LAVA) ok = false;   // lava beside the hole flows in
            }
            if (d.equals("front_down") && solid(g.at(x + fx, y, z + fz))) ok = false;   // hidden under the block in front: the eyes do not see it (the game: NOT_VISIBLE)
            r.put("nb_" + d + "_dig", any && ok ? 1.0 : 0.0);
        }
        for (String d : PUT) {
            int[] o = off(d, fx, fz);
            int[] c = { x + o[0], y + o[1], z + o[2] };
            int k = g.at(c[0], c[1], c[2]);
            r.put("nb_" + d + "_free", (k == AIR || k == WATER) && !g.mob(c[0], c[1], c[2]) && support(g, c, x, y, z) ? 1.0 : 0.0);
        }
        r.put("pillar_ok", g.at(x, y + 2, z) == AIR && solid(g.at(x, y - 1, z)) ? 1.0 : 0.0);
        int walls = 0, shut = 0;
        for (String d : new String[] { "front", "back", "left", "right" }) {
            int[] o = off(d, fx, fz);
            boolean feet = solid(g.at(x + o[0], y, z + o[2])), head = solid(g.at(x + o[0], y + 1, z + o[2]));
            if (feet) walls++;
            if (feet && head) shut++;
        }
        boolean enclosed = shut == 4 && solid(g.at(x, y + 2, z));
        r.put("walls_around", (double) walls);
        r.put("enclosed", enclosed ? 1.0 : 0.0);
        r.put("shelter", enclosed && nightOrWave ? 1.0 : 0.0);
    }
}
