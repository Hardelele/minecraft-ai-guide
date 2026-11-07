package hardelele.aiguide.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Exercises the observation seam with a sealed ore and an explicitly exposed face. */
public final class ObservedOreMemoryTest {
    static final class Scene implements Exploration.View {
        final Map<Exploration.Pos, String> blocks = new HashMap<Exploration.Pos, String>();
        final boolean leak;
        Scene(boolean leak) {
            this.leak = leak;
            for (int x = 2; x <= 4; x++) for (int y = 63; y <= 65; y++) for (int z = -1; z <= 1; z++)
                blocks.put(new Exploration.Pos(x, y, z), "minecraft:stone");
            blocks.put(new Exploration.Pos(3, 64, 0), "minecraft:iron_ore");
        }
        public boolean loaded(double x, double z, double tx, double tz) { return true; }
        public Exploration.Hit ray(double x, double y, double z, double tx, double ty, double tz) {
            double length = Math.sqrt((tx - x) * (tx - x) + (ty - y) * (ty - y) + (tz - z) * (tz - z));
            int steps = Math.max(1, (int) Math.ceil(length * 20));
            for (int i = 1; i <= steps; i++) {
                double t = i / (double) steps;
                Exploration.Pos p = new Exploration.Pos((int) Math.floor(x + (tx - x) * t),
                        (int) Math.floor(y + (ty - y) * t), (int) Math.floor(z + (tz - z) * t));
                String name = blocks.get(p);
                if (name == null || leak && name.equals("minecraft:stone")) continue;
                return new Exploration.Hit(p.x, p.y, p.z, 4);
            }
            return null;
        }
        public String block(Exploration.Pos p) { return blocks.get(p); }
        public boolean floor(Exploration.Pos p) { return blocks.containsKey(p); }
        public boolean air(Exploration.Pos p) { return !blocks.containsKey(p); }
    }

    static boolean knowsOre(Exploration.Memory m) { return m.seen.containsValue("minecraft:iron_ore"); }

    public static void main(String[] args) throws Exception {
        Scene scene = new Scene(args.length > 0 && args[0].equals("--mutant-ignore-stone"));
        Exploration.Memory memory = new Exploration.Memory();
        Exploration.scan(memory, scene, 0.5, 64.5, 0.5, 63);
        Exploration.Pos ore = new Exploration.Pos(3, 64, 0);
        Exploration.look(memory, scene, 0.5, 64.5, 0.5, ore);
        if (knowsOre(memory)) throw new AssertionError("sealed ore entered observation memory");
        scene.blocks.remove(new Exploration.Pos(2, 64, 0));
        if (!Exploration.look(memory, scene, 0.5, 64.5, 0.5, ore) || !knowsOre(memory))
            throw new AssertionError("exposed ore was not observed");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Exploration.write(memory, new DataOutputStream(bytes));
        Exploration.Memory reloaded = new Exploration.Memory();
        Exploration.read(reloaded, new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        if (!knowsOre(reloaded) || knowsOre(new Exploration.Memory()))
            throw new AssertionError("observation memory did not persist or isolate");
        Exploration.Memory frontiers = new Exploration.Memory();
        Exploration.Pos start = new Exploration.Pos(5, 64, 5);
        for (int x = 0; x <= 10; x++) for (int z = 0; z <= 10; z++) frontiers.ground.add(new Exploration.Pos(x, 64, z));
        frontiers.trail.add(start);
        Set<Exploration.Pos> reached = new HashSet<Exploration.Pos>();
        for (int salt = 0; salt < 64; salt++) reached.add(Exploration.choose(frontiers, start, salt));
        if (reached.size() <= 24) throw new AssertionError("frontier enumeration stranded candidate 25");
        java.util.List<int[]> sources = java.util.Arrays.asList(new int[] {1, 64, 0}, new int[] {2, 64, 0});
        HashSet<Long> tried = new HashSet<Long>();
        int[] first = Orders.Gather.nearestKnown(sources, tried, 0, 64, 0, 24, 16, 8, false);
        tried.add(Crew.key(first[0], first[1], first[2]));
        int[] second = Orders.Gather.nearestKnown(sources, tried, 0, 64, 0, 24, 16, 8, false);
        if (second == null || second[0] != 2) throw new AssertionError("failed resource blocks next observed source");
        System.out.println("sealed ore absent; exposed ore observed; memory persisted and isolated");
    }
}
