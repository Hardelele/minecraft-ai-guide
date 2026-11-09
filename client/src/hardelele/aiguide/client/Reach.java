package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Choices by what can be reached, with no game in it: a tree to chop is one a walk gets to (the unreachable ones
 * remembered and skipped, as a failed walk's goal), a cell to fall back to from a mob is one a path goes to (none:
 * hit back instead of standing under its blows), and a walk that has stopped getting nearer ends early. The game
 * gives the answers (a path finder's) through the interfaces; RlTest checks the choices.
 */
final class Reach {
    private Reach() { }

    interface Cells { boolean ok(int[] p); }

    /**
     * The targets in order (the nearest first) that may be tried (not given up) and are reached; at most `checks`
     * looked at with the path finder. The ones looked at and not reached go to `unreached`.
     */
    static List<int[]> reachable(List<int[]> targets, Cells mayTry, Cells reached, int checks, List<int[]> unreached) {
        List<int[]> out = new ArrayList<int[]>();
        int n = 0;
        for (int[] p : targets) {
            if (!mayTry.ok(p)) continue;
            if (n++ >= checks) break;
            if (reached.ok(p)) out.add(p);
            else if (unreached != null) unreached.add(p);
        }
        return out;
    }

    /**
     * Where to fall back from a mob at mx mz: the places given first (the house, the others), then cells 6 and 10 away
     * from the mob round the compass (straight away first); the first a path reaches, or null (nowhere: hit back).
     */
    static int[] fallBack(double ax, double ay, double az, double mx, double mz, List<int[]> preferred, Cells reached) {
        List<int[]> c = new ArrayList<int[]>(preferred);
        double base = Math.atan2(az - mz, ax - mx);
        for (double d : new double[] { 10, 6 })
            for (int i = 0; i < 8; i++) {
                double ang = base + (i % 2 == 0 ? 1 : -1) * ((i + 1) / 2) * Math.PI / 4;   // straight away, then ever more aside
                c.add(new int[] { (int) Math.floor(ax + Math.cos(ang) * d), (int) Math.floor(ay), (int) Math.floor(az + Math.sin(ang) * d) });
            }
        for (int[] p : c) {
            if (Math.hypot(p[0] + 0.5 - mx, p[2] + 0.5 - mz) < Math.hypot(ax - mx, az - mz)) continue;   // not toward the mob
            if (reached.ok(p)) return p;
        }
        return null;
    }

    /**
     * How a tree is chopped from its lowest log at footY: from the ground when some cell round it stands within one of
     * it (the heights of the cells round, -1 none); a top left hanging in the air (the ground far under it) only up a
     * pillar of the bag's blocks, else it is skipped. "chop", "pillar" or "skip".
     */
    static String trunkPlan(int footY, int[] around, boolean blocks) {
        for (int g : around) if (g >= 0 && Math.abs(g - footY) <= 1) return "chop";
        return blocks ? "pillar" : "skip";
    }

    /** a walk's progress: its best distance to the goal and when it got it; stuck after `patience` ticks with none */
    static final class Progress {
        double best = Double.MAX_VALUE;
        int at, ticks;
        final int patience;
        Progress(int patience) { this.patience = patience; }
        /** a tick at this distance: true when it has gone `patience` ticks without getting 0.3 nearer */
        boolean stuck(double d) {
            ticks++;
            if (d < best - 0.3) { best = d; at = ticks; }
            return ticks - at > patience;
        }
    }
}
