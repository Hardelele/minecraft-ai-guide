package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Checks of the eyes' geometry (Sight) with no game: run by tools/test_client.py. */
public class SightTest {
    static int failed, passed;

    static void check(String what, boolean ok) {
        if (ok) passed++;
        else failed++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    /** blocks in a set; the ground is solid under y 0 everywhere */
    static final class Blocks implements Sight.Space {
        final Set<Long> solid = new HashSet<Long>();
        void put(int x, int y, int z) { solid.add(Sight.key(x, y, z)); }
        public boolean solid(int x, int y, int z) { return y < 0 || solid.contains(Sight.key(x, y, z)); }
        public boolean free(int x, int y, int z) { return !solid(x, y, z); }
        public boolean floor(int x, int y, int z) { return solid(x, y, z); }
    }

    /** the supports of a cell as the crew's rule gives them: firm neighbours, the face of each that looks at the cell */
    static List<int[]> supports(Blocks b, int x, int y, int z) {
        List<int[]> out = new ArrayList<int[]>();
        for (int i : new int[] { 0, 2, 3, 4, 5, 1 }) {
            int[] d = Sight.SIDE[i];
            if (b.solid(x + d[0], y + d[1], z + d[2])) out.add(new int[] { x + d[0], y + d[1], z + d[2], i ^ 1 });
        }
        return out;
    }

    /** on foot: any standing cell at ground level (y 0) outside a box, by distance from a start */
    static Sight.Walk ground(final Blocks b, final int sx, final int sz, final int x0, final int z0, final int x1, final int z1) {
        return (x, y, z) -> {
            if (y != 0 || !Sight.body(b, x, y, z)) return -1;
            if (x >= x0 && x <= x1 && z >= z0 && z <= z1) return -1;   // inside the house: not walked to here
            return Math.abs(x - sx) + Math.abs(z - sz);
        };
    }

    /** a house: walls x0..x1, z0..z1 from y 0 to top (inclusive), hollow */
    static void walls(Blocks b, int x0, int z0, int x1, int z1, int top) {
        for (int y = 0; y <= top; y++)
            for (int x = x0; x <= x1; x++)
                for (int z = z0; z <= z1; z++)
                    if (x == x0 || x == x1 || z == z0 || z == z1) b.put(x, y, z);
    }

    public static void main(String[] args) {
        // the ray
        Blocks b = new Blocks();
        check("ray through air meets nothing", Sight.trace(b, 0.5, 1.62, 0.5, 3.5, 1.5, 0.5) == null);
        b.put(3, 1, 0);
        int[] hit = Sight.trace(b, 0.5, 1.62, 0.5, 3.5, 1.5, 0.5);
        check("ray meets the block in front by its west face", hit != null && hit[0] == 3 && hit[1] == 1 && hit[2] == 0 && hit[3] == 4);
        hit = Sight.trace(b, 0.5, 1.62, 0.5, 0.5, -0.5, 0.5);
        check("ray down meets the ground by its top face", hit != null && hit[1] == -1 && hit[3] == 1);

        // a face seen or not
        check("the near face of the block is seen", Sight.seeFace(b, 0.5, 1.62, 0.5, 3, 1, 0, 4, Crew_REACH) != null);
        check("the far face of the block is not (it looks away)", Sight.seeFace(b, 0.5, 1.62, 0.5, 3, 1, 0, 5, Crew_REACH) == null);
        b.put(2, 1, 0);
        b.put(2, 2, 0);
        b.put(2, 0, 0);
        check("a face behind another block is not seen", Sight.seeFace(b, 0.5, 1.62, 0.5, 3, 1, 0, 4, Crew_REACH) == null);
        Blocks far = new Blocks();
        far.put(8, 1, 0);
        check("a face out of reach is not seen", Sight.seeFace(far, 0.5, 1.62, 0.5, 8, 1, 0, 4, Crew_REACH) == null);
        far.put(1, 3, 0);
        check("the top face of a block above the eyes is not seen", Sight.seeFace(far, 0.5, 1.62, 0.5, 1, 3, 0, 1, Crew_REACH) == null);
        check("its bottom face is", Sight.seeFace(far, 0.5, 1.62, 0.5, 1, 3, 0, 0, Crew_REACH) != null);

        // a house 7x7 with walls y 0..2: a wall block of the second row is put from the ground
        Blocks h = new Blocks();
        walls(h, 0, 0, 6, 6, 0);
        Sight.Walk out = ground(h, -4, 3, 0, 0, 6, 6);
        Sight.View v = Sight.find(h, out, 0, 1, 3, 1, supports(h, 0, 1, 3), Crew_REACH, 0);
        check("second row: seen from the ground (" + v + ")", v != null && v.pillar == 0 && v.support[3] == 1 && v.stand[0] < 0);

        // the third row's first block: the wall's top is over the eyes from the ground (y 2 > 1.62)
        walls(h, 0, 0, 6, 6, 1);
        v = Sight.find(h, out, 0, 2, 3, 1, supports(h, 0, 2, 3), Crew_REACH, 0);
        check("third row from the ground only: no place (the top face is over the eyes)", v == null);
        v = Sight.find(h, out, 0, 2, 3, 1, supports(h, 0, 2, 3), Crew_REACH, 4);
        check("third row with a pillar: on one block (" + v + ")", v != null && v.pillar == 1 && v.support[3] == 1);
        // the next block of the row: against the side of the one put, from the ground
        h.put(0, 2, 3);
        v = Sight.find(h, out, 0, 2, 4, 1, supports(h, 0, 2, 4), Crew_REACH, 4);
        check("the next block of the row: from the ground, against a side or the top (" + v + ")", v != null && v.pillar == 0 || v != null && v.pillar == 1);

        // the roof: on the wall's top (y 3) from outside needs a pillar; the block must not be put from where it was blind
        walls(h, 0, 0, 6, 6, 2);
        v = Sight.find(h, out, 0, 3, 3, 1, supports(h, 0, 3, 3), Crew_REACH, 0);
        check("roof over the wall from the ground: no place", v == null);
        v = Sight.find(h, out, 0, 3, 3, 1, supports(h, 0, 3, 3), Crew_REACH, 4);
        check("roof over the wall: from a pillar of two (" + v + ")", v != null && v.pillar == 2 && v.support[0] == 0 && v.support[1] == 2 && v.support[3] == 1);

        // a ceiling block from below, inside: a block under one that hangs (its bottom face)
        Blocks c = new Blocks();
        c.put(2, 3, 2);
        Sight.Walk any = (x, y, z) -> y == 0 && Sight.body(c, x, y, z) ? Math.abs(x) + Math.abs(z) : -1;
        v = Sight.find(c, any, 2, 2, 2, 1, supports(c, 2, 2, 2), Crew_REACH, 0);
        check("a block under a ceiling: from below, its bottom face (" + v + ")", v != null && v.support[3] == 0);

        // a flat roof 11x11 at y 3 on walls: its middle is far from the edge; seen a few steps off a pillar's top
        Blocks r = new Blocks();
        walls(r, 0, 0, 10, 10, 2);
        for (int x = 0; x <= 10; x++) for (int z = 0; z <= 10; z++) r.put(x, 3, z);
        Sight.Walk around = ground(r, -3, 5, 0, 0, 10, 10);
        v = Sight.find(r, around, 5, 4, 5, 1, supports(r, 5, 4, 5), Crew_REACH, 5);
        check("the roof's middle: up a pillar and onto the roof (" + v + ")", v != null && v.pillar > 0 && v.steps > 0 && v.stand[1] == 4);

        // the placed cell itself is never the stand (a block does not go where the body is)
        Blocks f = new Blocks();
        Sight.Walk everywhere = (x, y, z) -> y == 0 && Sight.body(f, x, y, z) ? Math.abs(x - 2) + Math.abs(z - 2) : -1;
        v = Sight.find(f, everywhere, 2, 0, 2, 1, supports(f, 2, 0, 2), Crew_REACH, 0);
        check("a block on the ground: not from its own cell (" + v + ")", v != null && !(v.stand[0] == 2 && v.stand[2] == 2));
        v = Sight.find(f, everywhere, 2, 0, 2, 2, supports(f, 2, 0, 2), Crew_REACH, 0);
        check("a door (two tall): not from under it either", v != null && !(v.stand[0] == 2 && v.stand[2] == 2));

        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static final double Crew_REACH = 4.6;
}
