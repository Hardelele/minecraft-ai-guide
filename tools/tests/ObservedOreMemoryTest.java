package hardelele.aiguide.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.HashMap;
import java.util.Map;

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
        System.out.println("sealed ore absent; exposed ore observed; memory persisted and isolated");
    }
}
