package hardelele.aiguide.client;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * #1053: a felled tree leaves the crew's memory (Exploration.recheck on a made-up world): looked at from the eyes, a
 * trunk that is gone is forgotten, one standing is kept, one behind a wall is kept (nothing seen through it), one
 * further than near is looked at only when the agent has come into another chunk; and a walk that failed to a
 * forgotten trunk is forgotten with it (Reflexes.Walks.forget).
 */
public final class TreeMemoryGameTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    static final Map<String, String> world = new HashMap<String, String>();

    static String key(int x, int y, int z) { return x + " " + y + " " + z; }

    /** the world's blocks: a ray stops at the first that is not air (steps of 0.05) */
    static final Exploration.View VIEW = new Exploration.View() {
        public Exploration.Hit ray(double x, double y, double z, double tx, double ty, double tz) {
            double dx = tx - x, dy = ty - y, dz = tz - z, len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            int n = (int) Math.ceil(len / 0.05);
            for (int i = 1; i <= n; i++) {
                double f = i / (double) n;
                int bx = (int) Math.floor(x + dx * f), by = (int) Math.floor(y + dy * f), bz = (int) Math.floor(z + dz * f);
                if (world.containsKey(key(bx, by, bz))) return new Exploration.Hit(bx, by, bz, 0);
            }
            return null;
        }
        public boolean loaded(double x, double z, double tx, double tz) { return true; }
        public String block(Exploration.Pos p) { String b = world.get(key(p.x, p.y, p.z)); return b == null ? "minecraft:air" : b; }
        public boolean floor(Exploration.Pos p) { return world.containsKey(key(p.x, p.y, p.z)); }
        public boolean air(Exploration.Pos p) { return !world.containsKey(key(p.x, p.y, p.z)); }
    };

    static boolean remembered(Exploration.Memory m, int x, int y, int z) { return m.seen.containsKey(new Exploration.Pos(x, y, z)); }

    public static void main(String[] args) {
        Set<String> logs = new HashSet<String>(Arrays.asList("minecraft:log", "minecraft:log2"));
        Exploration.Memory m = new Exploration.Memory();
        for (int y = 64; y <= 67; y++) {
            m.seen.put(new Exploration.Pos(5, y, 0), "minecraft:log");     // A: felled (not in the world)
            m.seen.put(new Exploration.Pos(3, y, 3), "minecraft:log");     // B: standing
            world.put(key(3, y, 3), "minecraft:log");
            m.seen.put(new Exploration.Pos(-6, y, 0), "minecraft:log2");   // C: felled, behind a wall
            m.seen.put(new Exploration.Pos(14, y, 0), "minecraft:log");    // D: felled, 13.5 away
        }
        for (int y = 60; y <= 72; y++) for (int z = -3; z <= 3; z++) world.put(key(-3, y, z), "minecraft:stone");
        double ex = 0.5, ey = 65.6, ez = 0.5;
        List<int[]> gone = Exploration.recheck(m, VIEW, ex, ey, ez, false, logs, 10, 48);
        check(!remembered(m, 5, 64, 0) && !remembered(m, 5, 67, 0), "the felled tree near is forgotten");
        check(remembered(m, 3, 64, 3) && remembered(m, 3, 67, 3), "the standing tree stays");
        check(remembered(m, -6, 64, 0), "a tree behind a wall stays: nothing is seen through it");
        check(remembered(m, 14, 64, 0), "the far one is not looked at in the same chunk");
        check(gone.size() == 4, "the forgotten are returned: " + gone.size());
        Exploration.recheck(m, VIEW, ex, ey, ez, true, logs, 10, 48);
        check(!remembered(m, 14, 64, 0), "come into another chunk: all within sight (16) looked at, the far felled one forgotten");
        check(remembered(m, 3, 64, 3), "and the standing one still kept");
        // the failed walks to a trunk go with it
        Reflexes.Walks wk = new Reflexes.Walks();
        long goal = 12345L;
        for (int i = 0; i < 3; i++) wk.failed(goal, 1L, 100 + i);
        check(!wk.mayTry(goal, 105), "three failed walks: not tried again soon");
        wk.forget(goal);
        check(wk.mayTry(goal, 105), "the trunk forgotten: its failures too");
        System.out.println("TreeMemoryGameTest PASS: felled forgotten, standing and walled kept, far on a new chunk, failures forgotten");
    }
}
