package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.List;

/** The places' input from sight, on a made-up world (no game running): what the rays see and what they do not. */
public class PlacesInputTest {
    static int bad;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) bad++;
    }

    public static void main(String[] args) {
        // a world of stone with a room of air 7x4x7 at 10..16, 70..73, 10..16, a glass-less window: a fence at 17,71,13 and a
        // corridor of air past it to 25; a torch on the room's floor; the chunks with x >= 32 not loaded
        PlacesInput.View world = new PlacesInput.View() {
            public boolean loaded(int cx, int cz) { return cx < 2; }
            public int channel(int x, int y, int z) {
                if (x >= 10 && x <= 16 && y >= 70 && y <= 73 && z >= 10 && z <= 16) return x == 13 && y == 70 && z == 13 ? PlacesClasses.LIGHT : PlacesClasses.AIR;
                if (x == 17 && y == 71 && z == 13) return PlacesClasses.FENCE;
                if (x >= 18 && x <= 40 && y == 71 && z == 13) return PlacesClasses.AIR;
                return PlacesClasses.STONE;
            }
        };
        List<double[]> eyes = new ArrayList<double[]>();
        eyes.add(new double[] { 12.5, 71.6, 12.5 });
        PlacesInput in = new PlacesInput(0, 64, 0, eyes);
        int rounds = 0;
        while (!in.step(world, 20000)) rounds++;
        check(rounds > 0, "the rays take several steps of a budget (" + (rounds + 1) + ")");
        check(at(in, 12, 71, 12) == PlacesClasses.AIR && at(in, 16, 73, 16) == PlacesClasses.AIR, "the room's air seen");
        check(at(in, 13, 70, 13) == PlacesClasses.LIGHT, "the torch seen (and sight past it)");
        check(at(in, 9, 71, 12) == PlacesClasses.STONE && at(in, 12, 69, 12) == PlacesClasses.STONE && at(in, 12, 74, 12) == PlacesClasses.STONE, "the walls, floor and ceiling seen");
        check(at(in, 8, 71, 12) == PlacesClasses.UNSEEN && at(in, 12, 66, 12) == PlacesClasses.UNSEEN, "behind the walls: unseen");
        check(at(in, 17, 71, 13) == PlacesClasses.FENCE && at(in, 20, 71, 13) == PlacesClasses.AIR, "through the fence: the corridor seen");
        // open air: seen to the radius of 20, not past it
        PlacesInput.View open = new PlacesInput.View() {
            public boolean loaded(int cx, int cz) { return true; }
            public int channel(int x, int y, int z) { return PlacesClasses.AIR; }
        };
        List<double[]> corner = new ArrayList<double[]>();
        corner.add(new double[] { 0.5, 64.5, 0.5 });
        PlacesInput air = new PlacesInput(0, 64, 0, corner);
        while (!air.step(open, 1 << 30)) { }
        check(at(air, 15, 64, 0) == PlacesClasses.AIR && at(air, 10, 70, 10) == PlacesClasses.AIR, "in open air: seen within 20");
        check(at(air, 31, 64, 0) == PlacesClasses.UNSEEN && at(air, 20, 20 + 64, 20) == PlacesClasses.UNSEEN, "and not past it");
        int seen = in.seen();
        check(seen > 7 * 4 * 7 && seen < 2000, "seen: the room and its shell, little else (" + seen + " cells)");
        // a cube over the top of the world and one under it
        PlacesInput high = new PlacesInput(0, 240, 0, new ArrayList<double[]>());
        check(at(high, 0, 256, 0) == PlacesClasses.AIR && at(high, 0, 250, 0) == PlacesClasses.UNSEEN, "above the world: air, below its top with no eyes: unseen");
        PlacesInput low = new PlacesInput(0, -16, 0, new ArrayList<double[]>());
        check(at(low, 0, -1, 0) == PlacesClasses.OTHER && at(low, 0, 0, 0) == PlacesClasses.UNSEEN, "under the world: other_solid");
        // the unloaded chunks: an eye by the edge sees nothing past x 31
        List<double[]> edge = new ArrayList<double[]>();
        edge.add(new double[] { 25.5, 71.6, 13.5 });
        PlacesInput far = new PlacesInput(32, 64, 0, edge);
        while (!far.step(world, 1 << 30)) { }
        check(far.seen() == 0, "no reads in a chunk not loaded");
        // the eyes nearest the cube's centre first, at most EYES of them
        List<double[]> many = new ArrayList<double[]>();
        for (int i = 0; i < 40; i++) many.add(new double[] { 16 + i, 80, 16 });
        PlacesInput capped = new PlacesInput(0, 64, 0, many);
        check(capped.eyes.size() == PlacesInput.EYES && capped.eyes.get(0)[0] == 16 && capped.eyes.get(PlacesInput.EYES - 1)[0] == 16 + PlacesInput.EYES - 1, "the nearest eyes kept");
        System.exit(bad == 0 ? 0 : 1);
    }

    static int at(PlacesInput in, int x, int y, int z) {
        int i = in.inside(x, y, z);
        return i < 0 ? -1 : in.cls[i];
    }
}
