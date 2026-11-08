package hardelele.aiguide.client;

import java.util.LinkedHashMap;
import java.util.Map;

/** The mind's pieces that live with the game's classes but need no world: the board's keys read back, a bag's change, places in words. */
public final class MindGameTest {
    static void ok(boolean b, String what) {
        if (!b) throw new AssertionError(what);
    }

    public static void main(String[] args) {
        for (int[] p : new int[][] { { 0, 0, 0 }, { 12, 64, -3 }, { -1, 5, -1 }, { -30000000, 255, 29999999 }, { 1234567, 1, -7654321 } }) {
            int[] q = AgentMind.unkey(Crew.key(p[0], p[1], p[2]));
            ok(q[0] == p[0] && q[1] == p[1] && q[2] == p[2], "a reserved block's key read back: " + java.util.Arrays.toString(p) + " -> " + java.util.Arrays.toString(q));
        }
        Map<String, Integer> a = new LinkedHashMap<String, Integer>(), b = new LinkedHashMap<String, Integer>();
        a.put("log", 5);
        a.put("stick", 2);
        b.put("log", 2);
        b.put("planks", 12);
        ok(MindSkills.diff(a, b).equals("-3 log, -2 stick, +12 planks"), "a bag's change: " + MindSkills.diff(a, b));
        ok(MindSkills.diff(a, a).isEmpty(), "no change");
        ok(java.util.Arrays.equals(MindSkills.coords("tree at 12 64 -1"), new int[] { 12, 64, -1 }) && java.util.Arrays.equals(MindSkills.coords("-5,70,  8"), new int[] { -5, 70, 8 }), "coordinates in words");
        ok(MindSkills.coords("near") == null, "no coordinates");
        ok(AgentMind.STOP.matcher("стоп").matches() && AgentMind.STOP.matcher("Stop!").matches() && !AgentMind.STOP.matcher("stop the zombie").matches(), "a stop of the goals");
        System.out.println("MindGameTest PASS");
    }
}
