package hardelele.aiguide.client;

import java.util.List;
import java.util.Random;

/**
 * Where a wave's mobs appear: on a ring around the base (rMin..rMax blocks out), on the fronts the wave comes from
 * (1 front for waves 1-3, 2 for 4-6, 3 for 7-9, 4 from the 10th and on boss waves), on a cell a mob can stand on (the
 * world says which: Ground, from the column's surface; water, lava, a wall, leaves or no room are not), not far above
 * or below the base (a mountain top or a cave is not a way to the base). No game here: WavesTest gives it a model of
 * heights.
 */
final class WaveRing {
    /** the world's columns: the y a mob's feet stand at on the surface of column x z, or NONE (no safe cell there) */
    interface Ground { int stand(int x, int z); }

    static final int NONE = Integer.MIN_VALUE;
    static final int TRIES = 16;

    /** the fronts of wave n: angles (radians, 0 = east +x, pi/2 = south +z) a wave comes from */
    static double[] fronts(int n, Random rnd) {
        int k = WavePlan.boss(n) ? 4 : Math.min(4, 1 + (n - 1) / 3);
        double[] f = new double[k];
        double start = rnd.nextDouble() * 2 * Math.PI;
        for (int i = 0; i < k; i++) f[i] = norm(start + i * 2 * Math.PI / k + (rnd.nextDouble() - 0.5) * 0.6);
        return f;
    }

    static double norm(double a) {
        double t = a % (2 * Math.PI);
        return t < 0 ? t + 2 * Math.PI : t;
    }

    /** the side of the base an angle is: 0 E, 1 SE, 2 S, 3 SW, 4 W, 5 NW, 6 N, 7 NE (north is -z in the game) */
    static int side(double angle) {
        return (int) Math.round(norm(angle) / (Math.PI / 4)) % 8;
    }

    static int side(int dx, int dz) { return side(Math.atan2(dz, dx)); }

    /**
     * a cell for one mob of front f on the ring around cx cy cz: x, y, z, or null when none was found in TRIES (the
     * second half of the tries anywhere on the ring); never within 2 blocks of the cells in taken (this wave's last ones)
     */
    static int[] pick(Ground g, int cx, int cy, int cz, int rMin, int rMax, int maxRise, double front, Random rnd, List<int[]> taken) {
        for (int t = 0; t < TRIES; t++) {
            double a = t < TRIES / 2 ? front + (rnd.nextDouble() - 0.5) * 1.0 : rnd.nextDouble() * 2 * Math.PI;
            double r = rMin + rnd.nextDouble() * Math.max(0, rMax - rMin);
            int x = cx + (int) Math.round(Math.cos(a) * r), z = cz + (int) Math.round(Math.sin(a) * r);
            double d = Math.hypot(x - cx, z - cz);
            if (d < rMin - 0.5 || d > rMax + 0.5) continue;
            int y = g.stand(x, z);
            if (y == NONE || Math.abs(y - cy) > maxRise) continue;
            boolean near = false;
            if (taken != null) for (int[] o : taken) if (Math.abs(o[0] - x) < 2 && Math.abs(o[2] - z) < 2) near = true;
            if (near) continue;
            return new int[] { x, y, z };
        }
        return null;
    }
}
