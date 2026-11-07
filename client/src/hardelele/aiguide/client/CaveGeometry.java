package hardelele.aiguide.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/** A bounded, three-dimensional connected air volume. No gameplay system consumes this result. */
final class CaveGeometry {
    static final int OPEN = 0, SOLID = 1, UNLOADED = 2;
    static final int[] DX = {0, 0, 0, 0, -1, 1};
    static final int[] DY = {-1, 1, 0, 0, 0, 0};
    static final int[] DZ = {0, 0, -1, 1, 0, 0};
    static final int[] FACE_SIDE = {1, 0, 3, 2, 5, 4};

    interface Probe {
        int type(int x, int y, int z);
        int surface(int x, int z);
    }

    static final class Face {
        final int x, y, z, side;
        Face(int x, int y, int z, int side) {
            this.x = x; this.y = y; this.z = z; this.side = side;
        }
    }

    static final class Result {
        final List<Face> faces = new ArrayList<Face>();
        int cells, minLevel = 255, maxLevel, exitX, exitY, exitZ;
        boolean exit, unloaded, radiusBound, verticalBound, cellBound, faceBound;
        private BitSet seen;
        private int ax, az, radius, lowY, highY, span, plane;
        boolean limited() { return unloaded || radiusBound || verticalBound || cellBound || faceBound; }
        boolean contains(int x, int y, int z) {
            return seen != null && y >= lowY && y <= highY && Math.abs(x - ax) <= radius && Math.abs(z - az) <= radius
                    && seen.get(index(x, y, z, ax, az, radius, lowY, span, plane));
        }
        boolean nearbyOpenChanged(Probe p, int cx, int cy, int cz, int reach) {
            for (int x = cx - reach; x <= cx + reach; x++) for (int y = cy - reach; y <= cy + reach; y++)
                for (int z = cz - reach; z <= cz + reach; z++)
                    if (contains(x, y, z) && p.type(x, y, z) != OPEN) return true;
            return false;
        }
    }

    private CaveGeometry() {}

    /** The renderer advances this on game ticks, keeping a large cave scan off any single frame. */
    static final class Scanner {
        final Probe p;
        final Result out = new Result();
        final ArrayDeque<int[]> todo = new ArrayDeque<int[]>();
        final int ax, az, radius, lowY, highY, maxCells, maxFaces, span, plane;
        boolean done;

        Scanner(Probe p, int ax, int ay, int az, int radius, int lowY, int highY, int maxCells, int maxFaces) {
            this.p = p; this.ax = ax; this.az = az; this.radius = radius; this.lowY = lowY; this.highY = highY;
            this.maxCells = maxCells; this.maxFaces = maxFaces;
            span = radius * 2 + 1; plane = span * span;
            if (radius < 1 || lowY > highY || ay < lowY || ay > highY || p.type(ax, ay, az) != OPEN) { done = true; return; }
            out.seen = new BitSet(plane * (highY - lowY + 1));
            out.ax = ax; out.az = az; out.radius = radius; out.lowY = lowY; out.highY = highY; out.span = span; out.plane = plane;
            todo.add(new int[] {ax, ay, az});
            out.seen.set(index(ax, ay, az, ax, az, radius, lowY, span, plane));
        }

        void step(int budget) {
            if (done) return;
            for (int n = 0; n < budget && !todo.isEmpty(); n++) {
                if (out.cells >= maxCells) { out.cellBound = true; done = true; return; }
                int[] v = todo.removeFirst();
                int x = v[0], y = v[1], z = v[2];
                out.cells++;
                out.minLevel = Math.min(out.minLevel, y);
                out.maxLevel = Math.max(out.maxLevel, y);
                int surface = p.surface(x, z);
                // This is an air connection to the open top, not a promise an agent can walk it.
                if (y >= surface && !out.exit) {
                    out.exit = true; out.exitX = x; out.exitY = y; out.exitZ = z;
                }
                for (int d = 0; d < 6; d++) {
                    int nx = x + DX[d], ny = y + DY[d], nz = z + DZ[d];
                    if (Math.abs(nx - ax) > radius || Math.abs(nz - az) > radius) { out.radiusBound = true; continue; }
                    if (ny < lowY || ny > highY) { out.verticalBound = true; continue; }
                    int i = index(nx, ny, nz, ax, az, radius, lowY, span, plane);
                    if (out.seen.get(i)) continue;
                    int kind = p.type(nx, ny, nz);
                    if (kind == UNLOADED) { out.unloaded = true; continue; }
                    if (kind == SOLID) {
                        if (out.faces.size() >= maxFaces) out.faceBound = true;
                        else out.faces.add(new Face(nx, ny, nz, FACE_SIDE[d]));
                    } else if (y < surface) {
                        out.seen.set(i); todo.addLast(new int[] {nx, ny, nz});
                    }
                }
            }
            if (todo.isEmpty()) done = true;
        }
    }

    /** 6-connected traversal prevents two tunnels crossing at the same X/Z from joining through rock. */
    static Result build(Probe p, int ax, int ay, int az, int radius, int lowY, int highY, int maxCells, int maxFaces) {
        Scanner s = new Scanner(p, ax, ay, az, radius, lowY, highY, maxCells, maxFaces);
        while (!s.done) s.step(Math.max(1, maxCells));
        return s.out;
    }

    private static int index(int x, int y, int z, int ax, int az, int radius, int lowY, int span, int plane) {
        return (y - lowY) * plane + (x - ax + radius) * span + z - az + radius;
    }
}
