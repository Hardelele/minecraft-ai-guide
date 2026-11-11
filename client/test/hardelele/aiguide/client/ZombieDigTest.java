package hardelele.aiguide.client;

import java.util.HashMap;
import java.util.Map;

/**
 * The digging zombies' rules without the game (ZombieDig, docs/rl/ZOMBIES-DIG.md): the share of diggers by the wave,
 * the time a block takes by its hardness (obsidian and bedrock never), how far an agent is heard, and the way to an
 * agent underground over a model of the ground (soil, then rock): the zombie plans a step, breaks the cells of it,
 * walks into it. Prints the table of seconds to an agent at 12, 24 and 48 blocks (rl/sim-calibration.json, "dig").
 */
public final class ZombieDigTest {
    static int bad;

    static void check(boolean ok, String what) {
        if (!ok) { bad++; System.out.println("FAIL " + what); }
    }

    /** a model of the ground: air from y = 64, soil of the given depth under it, then rock; some cells replaced (obsidian) or broken */
    static final class Ground implements ZombieDig.Terrain {
        final int soil;
        final float rock;
        final Map<Long, Float> over = new HashMap<Long, Float>();

        Ground(int soil, float rock) { this.soil = soil; this.rock = rock; }

        static long key(int x, int y, int z) { return ((long) (x + 1000) << 40) | ((long) (y + 1000) << 20) | (z + 1000); }

        public float cell(int x, int y, int z) {
            Float o = over.get(key(x, y, z));
            if (o != null) return o;
            if (y >= 64) return ZombieDig.PASS;
            if (y < 1) return ZombieDig.NEVER;
            return y >= 64 - soil ? 0.5f : rock;
        }
    }

    /** seconds a zombie takes from the surface to an agent d blocks under it (ticks of the breaking plus 0.25 s a block of walking); -1 when it gives up */
    static double dig(Ground g, int d, int fromX, ZombieDig.Params p, int[] blocks) {
        int fx = fromX, fy = 64, fz = 0;
        double tx = 0.5, ty = 64 - d, tz = 0.5, ticks = 0;
        int moves = 0;
        for (int guard = 0; guard < 4000; guard++) {
            ZombieDig.Step s = ZombieDig.plan(g, fx, fy, fz, tx, ty, tz, p);
            if (s == null) return Math.hypot(fx - tx, fz - tz) < 1.5 && Math.abs(fy - ty) < 2 ? (ticks / 20.0 + 0.25 * moves) : -1;
            for (int[] c; (c = s.next(g)) != null; ) {
                ticks += ZombieDig.ticks(g.cell(c[0], c[1], c[2]), p);
                g.over.put(Ground.key(c[0], c[1], c[2]), ZombieDig.PASS);
                if (blocks != null) blocks[0]++;
            }
            fx = s.x; fy = s.y; fz = s.z;
            moves++;
        }
        return -1;
    }

    static void share(ZombieDig.Params p) {
        System.out.println("  wave  share  diggers of the zombies (1, 3, 5, 10, 20)");
        double last = 0;
        for (int n = 1; n <= 12; n++) {
            double f = ZombieDig.share(n, p);
            System.out.println(String.format("  %4d  %.2f   %d %d %d %d %d%s", n, f, ZombieDig.diggers(n, 1, p), ZombieDig.diggers(n, 3, p), ZombieDig.diggers(n, 5, p),
                    ZombieDig.diggers(n, 10, p), ZombieDig.diggers(n, 20, p), WavePlan.boss(n) ? "  boss" : ""));
            if (!WavePlan.boss(n) && !WavePlan.boss(n - 1)) check(f >= last, "the share does not fall by the wave: " + n);
            check(f <= p.bossMax + 1e-9, "the share is capped: " + n);
            check(WavePlan.boss(n) || f <= p.fracMax + 1e-9, "an ordinary wave's share is capped at the max: " + n);
            last = f;
        }
        check(ZombieDig.share(1, p) < 0.2, "few at the first wave");
        check(ZombieDig.diggers(1, 1, p) == 0 && ZombieDig.diggers(1, 2, p) == 0, "none among one or two zombies at the first wave");
        check(ZombieDig.diggers(1, 3, p) == 1, "one among three zombies");
        check(ZombieDig.diggers(5, 10, p) <= 8, "a boss wave: at most 0.75");
        check(ZombieDig.diggers(19, 100, p) <= 50 + 1, "never more than half of an ordinary wave");
    }

    static void blocks(ZombieDig.Params p) {
        check(ZombieDig.ticks(0.5f, p) == 12, "soil 0.5: 12 ticks, " + ZombieDig.ticks(0.5f, p));
        check(ZombieDig.ticks(0.1f, p) == p.minTicks, "a soft block takes the minimum");
        check(ZombieDig.ticks(1.5f, p) == 36, "stone 1.5: 36 ticks");
        check(ZombieDig.ticks(2f, p) == 48, "cobblestone and planks 2: 48 ticks");
        check(ZombieDig.ticks(3f, p) == 72, "iron ore 3: 72 ticks");
        check(ZombieDig.ticks(50f, p) == -1, "obsidian never");
        check(ZombieDig.ticks(-1f, p) == -1, "bedrock never");
        check(ZombieDig.ticks(9.9f, p) > 0 && ZombieDig.ticks(10f, p) == -1, "10 and over never");
        check(ZombieDig.mode(null) == ZombieDig.Mode.BELOW && ZombieDig.mode("off") == ZombieDig.Mode.OFF && ZombieDig.mode("all") == ZombieDig.Mode.ALL, "the modes' words");
    }

    static void hearing(ZombieDig.Params p) {
        check(ZombieDig.hear(0, p) == 24 && ZombieDig.hear(20, p) == 24, "24 blocks at once and for 20 s");
        check(Math.abs(ZombieDig.hear(70, p) - 44) < 1e-9, "+0.4 a second after that");
        check(ZombieDig.hear(1000, p) == 48, "up to 48");
        double t = 20 + (48 - 24) / p.hearGrow;
        System.out.println("  heard at 48 blocks after " + Math.round(t) + " s underground");
    }

    static void ways(ZombieDig.Params p) {
        System.out.println("  seconds to an agent under the ground (a soil layer of 5, then the rock; a zombie 10 blocks off)");
        System.out.println("  depth      soil      stone  cobblestone   iron ore");
        float[] rocks = { 0.5f, 1.5f, 2f, 3f };
        for (int d : new int[] { 12, 24, 48 }) {
            StringBuilder b = new StringBuilder(String.format("  %5d", d));
            for (float r : rocks) {
                int[] n = new int[1];
                double s = dig(new Ground(5, r), d, 10, p, n);
                double f = ZombieDig.reach(d, 5, 0.5f, r, p);
                b.append(String.format("  %9.1f", s));
                check(s > 0, "a way down: " + d + " in " + r);
                check(s <= f + 3 && s >= 0.5 * f - 3, "the plan's time is under the formula's (3 cells a level) and over half of it: " + d + " in " + r + ": " + s + " vs " + f);
                check(n[0] <= 3 * d + 24, "no more than ~3 cells a level (and the way across): " + d + " in " + r + ": " + n[0]);
            }
            System.out.println(b);
        }
        // a floor of obsidian across the way: no digging through it
        Ground g = new Ground(5, 1.5f);
        for (int x = -50; x <= 50; x++) for (int z = -50; z <= 50; z++) g.over.put(Ground.key(x, 50, z), 50f);
        check(dig(g, 24, 10, p, null) < 0, "a floor of obsidian is not dug through");
        // an agent right under the zombie: the stair goes down at once
        ZombieDig.Step u = ZombieDig.plan(new Ground(5, 1.5f), 0, 64, 0, 0.5, 40, 0.5, p);
        check(u != null && u.y == 63, "right above: a step down");
        check(ZombieDig.plan(new Ground(5, 1.5f), 0, 40, 0, 0.5, 40, 0.5, p) == null, "there: no step");
        // water in the way is not entered
        Ground w = new Ground(5, 1.5f);
        w.over.put(Ground.key(1, 64, 0), ZombieDig.LIQUID);
        ZombieDig.Step l = ZombieDig.plan(w, 0, 64, 0, 10.5, 64, 0.5, p);
        check(l == null || !(l.x == 1 && l.z == 0), "not through a liquid");
    }

    public static void main(String[] a) {
        ZombieDig.Params p = new ZombieDig.Params();
        share(p);
        blocks(p);
        hearing(p);
        ways(p);
        System.out.println(bad == 0 ? "ZombieDigTest ok" : "ZombieDigTest FAILED: " + bad);
        System.exit(bad == 0 ? 0 : 1);
    }
}
