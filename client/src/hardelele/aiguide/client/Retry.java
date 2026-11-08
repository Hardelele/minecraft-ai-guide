package hardelele.aiguide.client;

import java.util.HashMap;
import java.util.Map;

/**
 * One policy for every action the crew tries again: after a failure the same thing waits 10 s, then 20, then 40;
 * after the third failure it is given up (for as long as the policy's owner lives: a phase, a card, the day). Keys are
 * anything that names the thing tried (a block, a card, an agent's default work).
 */
class Retry {
    static final int BASE = 20 * 10, GIVE_UP = 3;

    private final Map<Object, int[]> state = new HashMap<Object, int[]>();   // key -> { failures, tick it may be tried again }

    /** may it be tried now */
    boolean allowed(Object key, long now) {
        int[] s = state.get(key);
        return s == null || s[0] < GIVE_UP && now >= s[1];
    }

    boolean givenUp(Object key) {
        int[] s = state.get(key);
        return s != null && s[0] >= GIVE_UP;
    }

    /** it failed: true when that was the last try */
    boolean failed(Object key, long now) {
        int[] s = state.get(key);
        if (s == null) state.put(key, s = new int[2]);
        s[0]++;
        s[1] = (int) Math.min(Integer.MAX_VALUE, now + ((long) BASE << (s[0] - 1)));
        return s[0] >= GIVE_UP;
    }

    /** not now, through no fault of the action (someone stands in the way): later, without counting it as a failure */
    void postpone(Object key, long now, int ticks) {
        int[] s = state.get(key);
        if (s == null) state.put(key, s = new int[2]);
        s[1] = (int) Math.max(s[1], Math.min(Integer.MAX_VALUE, now + ticks));
    }

    int failures(Object key) {
        int[] s = state.get(key);
        return s == null ? 0 : s[0];
    }

    void ok(Object key) {
        state.remove(key);
    }

    /** everything may be tried again (a new owner: a new plan) */
    void clear() {
        state.clear();
    }
}
