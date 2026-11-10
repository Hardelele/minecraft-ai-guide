package hardelele.aiguide.client;

import java.util.HashMap;
import java.util.Map;

/**
 * What a body really did, tick by tick, while a teacher's macros drive it (docs/rl/OPEN-WORLD.md section 6): the
 * hands' clicks as the body makes them (Body.dig's cracks, Guard.strike, Skills.use, Body.activate, Skills.craftOnce,
 * the eating, the furnace), gathered over the open world's step and read by RlOpen.Rec, which adds the head's turn
 * and the legs' way and turns them into a label in OpenSpec's heads. Off (one test of a flag) unless a recording runs.
 */
final class OpenRec {
    private OpenRec() { }

    static volatile boolean on;
    static final int ATTACK = 1, USE = 2, FURNACE = 3, DROP = 4, UNREP = 5;

    /** one agent's clicks over the step so far */
    static final class Acc {
        boolean attacked, used, dropped, unrep;
        int recipe = -1, furnace;
        String what = "";
        void clear() { attacked = used = dropped = unrep = false; recipe = -1; furnace = 0; what = ""; }
    }

    static final Map<Integer, Acc> accs = new HashMap<Integer, Acc>();

    static synchronized Acc acc(int skin) {
        Acc c = accs.get(skin);
        if (c == null) accs.put(skin, c = new Acc());
        return c;
    }

    static void note(Agent a, int kind, int arg) {
        if (!on || a == null) return;
        Acc c = acc(a.skin());
        switch (kind) {
            case ATTACK: c.attacked = true; break;
            case USE: c.used = true; break;
            case DROP: c.dropped = true; break;
            case FURNACE: c.furnace = arg; if (arg == OpenSpec.ACT_LOAD) c.unrep = true; break;   // a skill's load is not of the held stack
            default: c.unrep = true;
        }
    }

    /** a craft by the body (Skills.craftOnce): its recipe in OpenSpec's list, else not a player's action of this space */
    static void crafted(Agent a, String id) {
        if (!on || a == null) return;
        Acc c = acc(a.skin());
        int k = OpenSpec.recipe(id);
        if (k >= 0) c.recipe = k;
        else c.unrep = true;
        c.what = id;
    }
}
