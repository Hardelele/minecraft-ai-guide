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

    /** the numbers (-Daiguide.waves.dig.*; the same in rl/zombie-dig.json, ZombieDigTest checks it) */
    static final class Params {
        double bossMult = 1.5, tiersMax = 0.75;   // a boss wave's shares times this; all the tiers together at most this share of the zombies
        int max = 6;             // diggers breaking at once, at most
        double detect = 24;      // blocks, in a straight line: a target this near is heard at once
        double hearGrow = 0.4;   // blocks a second more, after hearGrace seconds under the ground in the wave
        double hearGrace = 20;
        double hearMax = 64;     // a little more than the waves' mobs' follow range (48): a digger hears what a target of the others is not
        double slow = 3;         // a digger breaks a block this many times slower than a player with the same tool (30 ticks a point of hardness at speed 1 when it harvests)
        int minTicks = 10;       // ticks at the least for a block
        int maxTicks = 1200;     // a block that takes longer is never (60 s)
        int below = 3;           // a target this many blocks below the zombie's feet is "under the ground"
    }

    // ---- the tools and who has them ---------------------------------------------------------------------------------

    /** a tool of a zombie's hand: its kind (a shovel digs soil, an axe wood, a pickaxe rock), its speed on what it is for (the game's tiers: 2, 4, 6, 8), its harvest level (stone 0, iron ore 1, diamond 2, obsidian 3) */
    enum Tool {
        HAND(0, 1, -1), IRON_SHOVEL(1, 6, 0), IRON_AXE(2, 6, 0), WOODEN_PICKAXE(3, 2, 0), STONE_PICKAXE(3, 4, 1), IRON_PICKAXE(3, 6, 2), DIAMOND_PICKAXE(3, 8, 3);

        final int kind;      // 0 none, 1 shovel, 2 axe, 3 pickaxe
        final double speed;
        final int level;

        Tool(int kind, double speed, int level) { this.kind = kind; this.speed = speed; this.level = level; }
    }

    /** what a block is for the tools: soil (a shovel's), wood (an axe's), rock (a pickaxe's: needs a pickaxe to harvest), other (a hand's) */
    enum Mat { SOIL, WOOD, ROCK, OTHER }

    /**
     * Ticks a block takes a digger with a tool, -1 for never: the game's rule of the player's breaking (a block that wants a tool
     * is harvested only by the right tool of a level enough: obsidian only by diamond; bedrock, hardness below 0, by none), the
     * game's time (30 ticks a point of hardness at the tool's speed on what it is for, 1 for a hand or another tool) times
     * Params.slow; at least minTicks, never more than maxTicks. need: the block wants a tool (not isToolNotRequired); level: its harvest level.
     */
    static int ticks(Tool tool, Mat mat, float hardness, boolean need, int level, Params p) {
        if (hardness < 0) return -1;
        boolean right = tool.kind == 1 && mat == Mat.SOIL || tool.kind == 2 && mat == Mat.WOOD || tool.kind == 3 && mat == Mat.ROCK;
        if (need && !(right && tool.level >= level)) return -1;
        double t = Math.ceil(hardness * 30 / (right ? tool.speed : 1) * p.slow);
        if (t > p.maxTicks) return -1;
        return Math.max(p.minTicks, (int) t);
    }

    /** a tier: a kind of the wave's zombies with a tool, by the wave: from the wave `from`, share base + step * (wave - from), at most max */
    static final class Tier {
        final String name;
        final Tool tool;
        final int from;
        final double base, step, max;

        Tier(String name, Tool tool, int from, double base, double step, double max) { this.name = name; this.tool = tool; this.from = from; this.base = base; this.step = step; this.max = max; }

        double share(int wave, Params p) {
            if (wave < from) return 0;
            double f = Math.min(max, base + step * (wave - from));
            return WavePlan.boss(wave) ? f * p.bossMult : f;
        }
    }

    /** the tiers as built in (rl/zombie-dig.json is the same): the plain zombie has no tool and does not dig */
    static final Tier[] TIERS = {
            new Tier("shoveler", Tool.IRON_SHOVEL, 1, 0.06, 0.02, 0.15),       // soil, sand, gravel: the top layer of the ground
            new Tier("digger", Tool.STONE_PICKAXE, 1, 0.06, 0.03, 0.20),  // stone and the ores to iron
            new Tier("miner", Tool.IRON_PICKAXE, 3, 0.04, 0.03, 0.20),    // the same, faster, the ores to redstone and diamond
            new Tier("breaker", Tool.DIAMOND_PICKAXE, 7, 0.03, 0.02, 0.10) };   // obsidian too

    /** the tier of the k-th zombie (from 0) of a wave of total zombies, or -1 for a plain one: by the shares, with no random numbers (the wave is the same with the digging off) */
    static int tier(int wave, int k, int total, Params p) {
        double[] s = new double[TIERS.length];
        double sum = 0;
        for (int i = 0; i < s.length; i++) sum += s[i] = TIERS[i].share(wave, p);
        if (sum > p.tiersMax) for (int i = 0; i < s.length; i++) s[i] *= p.tiersMax / sum;
        double u = ((k + 1) * 0.6180339887498949) % 1.0;   // a low-discrepancy sequence: the shares hold for a few zombies
        double acc = 0;
        for (int i = s.length - 1; i >= 0; i--) {   // the rarest (strongest) tier first
            acc += s[i];
            if (u < acc) return i;
        }
        return k == 0 && total >= 3 ? 1 : -1;   // one digger at least among three
    }

    /** how many of a wave's zombies have a tool, the rest plain */
    static int diggers(int wave, int total, Params p) {
        int n = 0;
        for (int k = 0; k < total; k++) if (tier(wave, k, total, p) >= 0) n++;
        return n;
    }

    // ---- how far an agent is heard ------------------------------------------------------------------------------

    /** blocks (in a straight line) an agent that has been underground for t seconds of the wave is heard at */
    static double hear(double t, Params p) {
        return Math.min(p.hearMax, p.detect + Math.min(p.hearMax - p.detect, p.hearGrow * Math.max(0, t - p.hearGrace)));
    }

    /** what a cell is, for the plan: passable (air, plants), a liquid, a block this digger never breaks, else the ticks it takes (above 0) */
    static final float PASS = 0f, LIQUID = -3f, NEVER = -1f;

    interface Terrain { float cell(int x, int y, int z); }

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
            if (h < 0) return null;   // a liquid, or a block this digger never breaks
        }
        float below = t.cell(nx, ny - 1, nz);
        if (below == LIQUID) return null;   // not into the water or the lava of a cave
        return st;
    }
}
