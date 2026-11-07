package hardelele.aiguide.client;

import java.util.HashSet;
import java.util.Set;

/** Real 3D adjacency and bounded visibility controls; runs without Minecraft or OpenGL. */
public final class CaveGeometryTest {
    static final class Fixture implements CaveGeometry.Probe {
        final Set<String> air = new HashSet<String>();
        final Set<String> unloaded = new HashSet<String>();
        int reads;
        void open(int x, int y, int z) { air.add(key(x,y,z)); }
        public int type(int x, int y, int z) { reads++; return unloaded.contains(key(x,y,z)) ? CaveGeometry.UNLOADED : air.contains(key(x,y,z)) ? CaveGeometry.OPEN : CaveGeometry.SOLID; }
        public int surface(int x, int z) { return 6; }
    }
    static String key(int x, int y, int z) { return x + "," + y + "," + z; }
    static void check(boolean v, String m) { if (!v) throw new AssertionError(m); }

    public static void main(String[] args) {
        Fixture f = new Fixture();
        // A winding, rising tunnel: one contiguous 3D component and an actual surface exit.
        int[][] path = {{0,1,0},{1,1,0},{1,2,0},{1,3,0},{2,3,0},{2,4,0},{2,5,0},{2,6,0}};
        for (int[] p : path) f.open(p[0],p[1],p[2]);
        // Crosses the X/Z of the lower route but two solid levels separate the passages.
        f.open(0,4,0);
        CaveGeometry.Result r = CaveGeometry.build(f,0,1,0,8,0,8,100,1000);
        check(r.cells == path.length, "disconnected upper passage was joined through stone");
        check(r.minLevel == 1 && r.maxLevel == 6 && r.exit, "three-level ascent or exit missing");
        check(r.exitX == 2 && r.exitY == 6, "exit must be reached by real 3D air path");
        check(r.contains(2,5,0) && !r.contains(0,4,0), "component membership leaked across rock");
        for (CaveGeometry.Face face : r.faces)
            check(!(face.x == 0 && face.y == 4 && face.z == 0), "unreached air rendered as solid");

        // A nearby 34-cell pocket must not replace the already mapped route to the top
        // merely because the tracked agent steps into it. The viewer keeps the first seed.
        for (int x = -8; x <= -3; x++) for (int z = -8; z <= -3; z++)
            if (!(x == -8 && z == -3) && !(x == -3 && z == -8)) f.open(x,3,z);
        CaveGeometry.Result pocket = CaveGeometry.build(f,-8,3,-8,8,0,8,100,1000);
        check(pocket.cells == 34 && !pocket.exit, "disjoint pocket fixture did not stay local");
        CaveGeometry.Result mappedAgain = CaveGeometry.build(f,0,1,0,8,0,8,100,1000);
        check(mappedAgain.cells == path.length && mappedAgain.exit,
                "fixed seed lost the mapped exit after a disjoint pocket appeared");

        Fixture sealed = new Fixture();
        sealed.open(0,1,0); sealed.open(0,4,0); // same X/Z, no connected ascent
        CaveGeometry.Result s = CaveGeometry.build(sealed,0,1,0,4,0,8,100,1000);
        check(!s.exit && s.cells == 1 && s.maxLevel == 1, "sealed vertical gap leaked");

        CaveGeometry.Result small = CaveGeometry.build(f,0,1,0,8,0,8,2,1000);
        check(small.cellBound && small.cells == 2, "cell budget did not bound work");
        CaveGeometry.Result narrow = CaveGeometry.build(f,0,1,0,1,0,8,100,1000);
        check(narrow.radiusBound && !narrow.exit, "radius boundary must be explicit");
        CaveGeometry.Result fewFaces = CaveGeometry.build(f,0,1,0,8,0,8,100,1);
        check(fewFaces.faceBound && fewFaces.exit && fewFaces.cells == path.length, "face budget hid connected upper air");
        Fixture missingChunk = new Fixture();
        missingChunk.open(0,1,0); missingChunk.unloaded.add(key(1,1,0));
        CaveGeometry.Result missing = CaveGeometry.build(missingChunk,0,1,0,4,0,8,100,1000);
        check(missing.unloaded && !missing.exit, "unloaded boundary must remain uncertain");
        CaveGeometry.Scanner incremental = new CaveGeometry.Scanner(f,0,1,0,8,0,8,100,1000);
        incremental.step(2);
        check(!incremental.done && incremental.out.cells == 2, "one frame exceeded scan budget");
        while (!incremental.done) incremental.step(2);
        check(incremental.out.cells == path.length && incremental.out.exit, "incremental scan lost upper route");
        check(!r.nearbyOpenChanged(f,1,3,0,3), "unchanged air looks blocked");
        f.air.remove(key(1,3,0));
        check(r.nearbyOpenChanged(f,1,3,0,3), "newly blocked connected air left a ghost corridor");
        System.out.println("CaveGeometryTest PASS cells=" + r.cells + " levels=" + r.minLevel + ".." + r.maxLevel + " faces=" + r.faces.size());
    }
}
