package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.List;

/** A sealed ore cannot be selected by a routine until an agent actually sees its face. */
public final class RoutinesObservedTargetTest {
    static List<int[]> knownIron(Exploration.Memory memory) {
        List<int[]> out = new ArrayList<int[]>();
        for (java.util.Map.Entry<Exploration.Pos, String> e : memory.seen.entrySet())
            if ("minecraft:iron_ore".equals(e.getValue())) out.add(e.getKey().array());
        return out;
    }

    public static void main(String[] args) {
        final ObservedOreMemoryTest.Scene scene = new ObservedOreMemoryTest.Scene(false);
        final Exploration.Memory memory = new Exploration.Memory();
        final Exploration.Pos ore = new Exploration.Pos(3, 64, 0);
        final Routines.Loop.CandidatePolicy visible = new Routines.Loop.CandidatePolicy() {
            public boolean accepts(int x, int y, int z) {
                return Exploration.look(memory, scene, .5, 64.5, .5, new Exploration.Pos(x, y, z));
            }
        };
        Exploration.scan(memory, scene, .5, 64.5, .5, 63);
        Exploration.look(memory, scene, .5, 64.5, .5, ore);
        if (Routines.Loop.nearestKnown(knownIron(memory), 0, 64, 0, 8, 12, 8, visible) != null)
            throw new AssertionError("sealed ore was selected");

        scene.blocks.remove(new Exploration.Pos(2, 64, 0));
        if (!Exploration.look(memory, scene, .5, 64.5, .5, ore))
            throw new AssertionError("exposed ore was not observed");
        int[] selected = Routines.Loop.nearestKnown(knownIron(memory), 0, 64, 0, 8, 12, 8, visible);
        if (selected == null || selected[0] != 3 || selected[1] != 64 || selected[2] != 0)
            throw new AssertionError("exposed ore was not selectable");
        if (Routines.Loop.nearestKnown(knownIron(memory), 0, 64, 0, 2, 12, 8, visible) != null)
            throw new AssertionError("outside-radius ore was selected");
        List<int[]> alternatives = new ArrayList<int[]>();
        alternatives.add(new int[] {3, 64, 0}); alternatives.add(new int[] {4, 64, 0});
        int[] second = Routines.Loop.nearestKnown(alternatives, 0, 64, 0, 8, 12, 8,
                new Routines.Loop.CandidatePolicy() {
                    public boolean accepts(int x, int y, int z) { return x != 3; }
                });
        if (second == null || second[0] != 4)
            throw new AssertionError("rejected nearest target stranded the next observed target");

        scene.blocks.put(new Exploration.Pos(2, 64, 0), "minecraft:stone");
        if (Routines.Loop.nearestKnown(knownIron(memory), 0, 64, 0, 8, 12, 8, visible) != null)
            throw new AssertionError("occluded remembered ore was selected");
        System.out.println("sealed ore absent; exposed ore selected; radius and current visibility enforced");
    }
}
