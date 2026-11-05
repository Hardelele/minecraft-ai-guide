package hardelele.aiguide.client;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What of the world the crew has seen: cells of 4x4 blocks (columns), kept with the crew's base (crew.json), so the
 * map stays explored from one session to the next. Server and client both read it (it holds no client classes).
 */
public class FogMap {
    static final int CELL = 4;
    static final Set<Long> explored = ConcurrentHashMap.newKeySet();

    static int cell(double v) { return (int) Math.floor(v / CELL); }

    static long key(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xffffffffL); }

    static boolean seen(int x, int z) { return explored.contains(key(cell(x), cell(z))); }
}
