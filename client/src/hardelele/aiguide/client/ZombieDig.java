package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The digging zombies' rules of a waves run, with no game in it (ZombieDigWorld does them in the world); the design is
 * docs/rl/ZOMBIES-DIG.md. An agent hidden underground used to be out of a wave's reach (no path to it, and a mob could
 * not break a block); some of the zombies now dig to it: a share of the wave's zombies (growing with the wave) are
 * diggers, one that hears an agent under the ground it cannot walk to breaks the blocks on its way, a block by its
 * hardness (soil quickly, stone longer, obsidian and bedrock never), and the tunnel stays for the others. The same
 * numbers are in rl/sim-calibration.json ("dig") for the two simulators. Here: the mode, the share, the time a block
 * takes, how far an agent is heard, and the next step of the way (which cells to clear) over a Terrain.
 */
final class ZombieDig {
    private ZombieDig() { }

    /** off: the old waves; below: a target under the ground (3 blocks or more below the zombie's feet); all: a target it has no path to as well (the walls too) */
    enum Mode { OFF, BELOW, ALL }

    static Mode mode(String s) {
        if (s == null) return Mode.BELOW;
        String w = s.trim().toLowerCase(Locale.ROOT);
        if (w.equals("off") || w.equals("false") || w.equals("0") || w.equals("no")) return Mode.OFF;
        if (w.equals("all") || w.equals("walls")) return Mode.ALL;
        return Mode.BELOW;
    }

    /** the numbers (-Daiguide.waves.dig.*) */
    static final class Params {
        double frac = 0.10, step = 0.06, fracMax = 0.50, bossMult = 1.5, bossMax = 0.75;
        int max = 6;             // diggers breaking at once, at most
        double detect = 24;      // blocks, in a straight line: a target this near is heard at once
        double hearGrow = 0.4;   // blocks a second more, after hearGrace seconds under the ground in the wave
        double hearGrace = 20;
        double hearMax = 48;     // the waves' mobs' follow range (Waves.SEEK)
        double cell = 24;        // ticks for a point of hardness
        int minTicks = 10;       // ticks at the least for a block
        double maxHardness = 10; // this hard or harder (obsidian 50), or unbreakable (bedrock -1): never
        int below = 3;           // a target this many blocks below the zombie's feet is "under the ground"
    }

    // ---- who digs -------------------------------------------------------------------------------------------

    /** the share of a wave's zombies that dig: 0.16 at the 1st, 0.28 at the 3rd, 0.4 at the 5th, 0.5 from the 8th; a boss wave 1.5 times that, 0.75 at most */
    static double share(int wave, Params p) {
        double f = Math.min(p.fracMax, p.frac + p.step * Math.max(0, wave));
        if (WavePlan.boss(wave)) f = Math.min(p.bossMax, f * p.bossMult);
        return f;
    }

    /** is the k-th zombie (from 0) of a wave with total zombies a digger: by the share, with no random numbers (the wave is the same with the digging off); the first is one when there are 3 or more */
    static boolean digger(int wave, int k, int total, Params p) {
        double f = share(wave, p);
        if (Math.floor(f * (k + 1)) > Math.floor(f * k)) return true;
        return k == 0 && total >= 3;
    }

    /** how many of a wave's zombies dig */
    static int diggers(int wave, int total, Params p) {
        int n = 0;
        for (int k = 0; k < total; k++) if (digger(wave, k, total, p)) n++;
        return n;
    }

    // ---- how far an agent is heard ------------------------------------------------------------------------------

    /** blocks (in a straight line) an agent that has been underground for t seconds of the wave is heard at */
    static double hear(double t, Params p) {
        return Math.min(p.hearMax, p.detect + Math.min(p.hearMax - p.detect, p.hearGrow * Math.max(0, t - p.hearGrace)));
    }

    // ---- the block ------------------------------------------------------------------------------------------------

    /** what a cell is, for the plan: passable (air, plants), a liquid, a block that never breaks, or a hardness above 0 */
    static final float PASS = 0f, LIQUID = -3f, NEVER = -1f;

    interface Terrain { float cell(int x, int y, int z); }

    /** ticks a block of this hardness takes a digger; -1: never (unbreakable, too hard) */
    static int ticks(float hardness, Params p) {
        if (hardness < 0 || hardness >= p.maxHardness) return -1;
        return Math.max(p.minTicks, (int) Math.ceil(hardness * p.cell));
    }

    // ---- the way --------------------------------------------------------------------------------------------------

    /** one step of the way: the cell the zombie's feet go to and the cells that must be clear for it, in the order they are broken */
    static final class Step {
        final int x, y, z;
        final List<int[]> cells = new ArrayList<int[]>();

        Step(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }

        /** the first cell on the list that is not passable (the one to break now), null: the way is open */
        int[] next(Terrain t) {
            for (int[] c : cells) if (t.cell(c[0], c[1], c[2]) != PASS) return c;
            return null;
        }
    }

    /**
     * The next step from the feet cell (fx, fy, fz) towards a target's feet (tx, ty, tz): down a stair of three cells a
     * level when the target is 2 or more blocks lower and the drop is at least half the way across, up when higher,
     * else a step along the longer axis (two cells: feet and head); if that way has a block that never breaks or a
     * liquid in it, the other axis, then the other kinds of step; null when none is possible or the zombie is there.
     */
    static Step plan(Terrain t, int fx, int fy, int fz, double tx, double ty, double tz, Params p) {
        double dx = tx - (fx + 0.5), dz = tz - (fz + 0.5), dy = ty - fy, dh = Math.hypot(dx, dz);
        if (dh < 1.0 && Math.abs(dy) < 2) return null;
        int sy = 0;
        if (dy <= -2 && -dy >= 0.5 * dh) sy = -1;
        else if (dy >= 2 && dy >= 0.5 * dh) sy = 1;
        int[][] axes;   // the axes to try: the longer first, then the other, then the opposite of the longer
        int sx = dx >= 0 ? 1 : -1, sz = dz >= 0 ? 1 : -1;
        if (Math.abs(dx) >= Math.abs(dz)) axes = new int[][] { { sx, 0 }, { 0, sz }, { -sx, 0 } };
        else axes = new int[][] { { 0, sz }, { sx, 0 }, { 0, -sz } };
        int[] sys = sy == 0 ? new int[] { 0 } : new int[] { sy, 0 };
        for (int s : sys)
            for (int[] a : axes) {
                Step st = step(t, fx, fy, fz, a[0], s, a[1], p);
                if (st != null) return st;
            }
        return null;
    }

    static Step step(Terrain t, int fx, int fy, int fz, int ax, int sy, int az, Params p) {
        int nx = fx + ax, nz = fz + az, ny = fy + sy;
        Step st = new Step(nx, ny, nz);
        if (sy < 0) { st.cells.add(new int[] { nx, fy, nz }); st.cells.add(new int[] { nx, fy + 1, nz }); st.cells.add(new int[] { nx, fy - 1, nz }); }
        else if (sy > 0) { st.cells.add(new int[] { fx, fy + 2, fz }); st.cells.add(new int[] { nx, fy + 1, nz }); st.cells.add(new int[] { nx, fy + 2, nz }); }
        else { st.cells.add(new int[] { nx, fy, nz }); st.cells.add(new int[] { nx, fy + 1, nz }); }
        for (int[] c : st.cells) {
            float h = t.cell(c[0], c[1], c[2]);
            if (h == LIQUID || h == NEVER || h < 0 || h >= p.maxHardness) return null;
        }
        float below = t.cell(nx, ny - 1, nz);
        if (below == LIQUID) return null;   // not into the water or the lava of a cave
        return st;
    }

    // ---- the time to an agent (for the tables and the simulators) ----------------------------------------------------

    /**
     * Seconds a digger takes to dig down to an agent d blocks below it (a stair: 3 cells a level) through a soil layer
     * of soilDepth blocks (hardness soil) and then rock (hardness rock), walking the finished tunnel at 0.25 s a block.
     */
    static double reach(int d, int soilDepth, float soil, float rock, Params p) {
        double ticks = 0;
        for (int i = 0; i < d; i++) ticks += 3.0 * ticks(i < soilDepth ? soil : rock, p);
        return ticks / 20.0 + 0.25 * d;
    }
}
