package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the crew has made of the world in an episode of the learning environment (and in the game under its policy):
 * its mine (the mouth, the stairs dug, the horizon reached), its farm's cells, the saplings it planted, its pen and
 * the young born in it, the gate's door, its enchanting table. The skills of contract 3 (MineSkills, FarmSkills,
 * GearSkills, PrimSkills) write it; RlEnv's facts and measures read it; a new episode starts it over (RlEnv.reset).
 * Positions are keys (RlCause.key) or { x, y, z }.
 */
final class RlWorld {
    static volatile RlWorld now = new RlWorld();

    static void reset() { now = new RlWorld(); }

    // ---- the mine ------------------------------------------------------------------------------------------------
    /** the mouth: the surface cell the stairs start from (the feet's), and their first direction; null: none yet */
    int[] mouth;
    int dx, dz;
    /** the stairs' cells (the feet's), top first: the way down and the way back */
    final List<int[]> stairs = new ArrayList<int[]>();
    /** the deepest horizon the stairs reach (0 none, 1 y 40, 2 y 12) */
    int level;
    /** the branch tunnels dug (each a list of the feet's cells) */
    final List<List<int[]>> tunnels = new ArrayList<List<int[]>>();

    /** the surface's y where the agents stand at the base (the mouth's, else the base's) */
    int surfaceY(Crew c) {
        if (mouth != null) return mouth[1];
        return c != null && c.base != null ? c.base[1] : 64;
    }

    // ---- the farm, the trees -------------------------------------------------------------------------------------
    /** the farmland cells the crew tilled (the farmland block's position) */
    final Set<Long> farm = new LinkedHashSet<Long>();
    /** the saplings the crew planted (their cell), and those of them that grew into a tree */
    final Set<Long> saplings = new LinkedHashSet<Long>(), grown = new LinkedHashSet<Long>();
    /** the logs of the crew's grown trees (their cells): chopped, they are renewable wood */
    final Set<Long> grownLogs = new HashSet<Long>();

    // ---- the pen -------------------------------------------------------------------------------------------------
    /** the pen: its centre (the feet's level) and the half side inside (the fence ring at r + 1); null: none */
    int[] pen;
    int penR = 2;
    int[] penGate;
    /** the animals born in the pen (entity ids): their meat is renewable food */
    final Set<Integer> born = new HashSet<Integer>();
    /** an animal led into the pen by an agent: entity id -> the agent's number (who fed it, for breedCow) */
    final Map<Integer, Integer> fedBy = new HashMap<Integer, Integer>();

    boolean inPen(double x, double z) {
        return pen != null && Math.abs(Math.floor(x) - pen[0]) <= penR && Math.abs(Math.floor(z) - pen[2]) <= penR;
    }

    // ---- the gate, the enchanting --------------------------------------------------------------------------------
    /** the gate's door (its lower cell) */
    int[] door;
    /** the crew's enchanting table */
    int[] enchTable;

    static long key(int x, int y, int z) { return RlCause.key(x, y, z); }

    static int[] unkey(long k) {
        int x = (int) (k >> 38), z = (int) ((k >> 12) & 0x3FFFFFF), y = (int) (k & 0xFFF);
        if (x >= 1 << 25) x -= 1 << 26;
        if (z >= 1 << 25) z -= 1 << 26;
        return new int[] { x, y, z };
    }
}
