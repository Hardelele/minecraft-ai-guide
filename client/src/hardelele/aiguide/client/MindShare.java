package hardelele.aiguide.client;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Who drives each agent's body, tick by tick: its mind on the net (its decisions and the steps they began), a reflex
 * of self-defence (hit back or fall back from a mob, a creeper), another reflex of the body (a fall, water, a stuck
 * walk), the crew's guard (Guard.watch's card: the whole fight, not the agent's own choice), or nothing of these (idle,
 * the board's work, the player's orders). Counted in Crew's tick, logged as "mind share" a minute at a time, so a film
 * shows whether the net or the guard fought the waves. No game in it (client/test).
 */
final class MindShare {
    static final int NET = 0, FIGHT = 1, REFLEX = 2, GUARD = 3, OTHER = 4;
    static final String[] NAMES = { "net", "self-defence", "reflex", "guard", "other" };
    static final int WINDOW = 20 * 60;   // ticks a line covers

    static final class Of {
        final long[] window = new long[NAMES.length], all = new long[NAMES.length];
        int decisions, decisionsAll, guards, guardsAll;
    }

    final Map<String, Of> agents = new LinkedHashMap<String, Of>();
    long ticks;

    Of of(String agent) {
        Of o = agents.get(agent);
        if (o == null) agents.put(agent, o = new Of());
        return o;
    }

    /** the agent's body this tick was the kind's */
    void tick(String agent, int kind) {
        Of o = of(agent);
        o.window[kind]++;
        o.all[kind]++;
    }

    /** a decision of its net */
    void decided(String agent) {
        Of o = of(agent);
        o.decisions++;
        o.decisionsAll++;
    }

    /** the guard's card was put on it */
    void guarded(String agent) {
        Of o = of(agent);
        o.guards++;
        o.guardsAll++;
    }

    static int pct(long part, long sum) {
        return sum <= 0 ? 0 : (int) Math.round(100.0 * part / sum);
    }

    /** one line over the window (and since the start), the window reset; null when nothing was counted */
    String line() {
        StringBuilder b = new StringBuilder();
        long[] crew = new long[NAMES.length], crewAll = new long[NAMES.length];
        for (Map.Entry<String, Of> e : agents.entrySet()) {
            Of o = e.getValue();
            long sum = 0, sumAll = 0;
            for (int k = 0; k < NAMES.length; k++) { sum += o.window[k]; sumAll += o.all[k]; crew[k] += o.window[k]; crewAll[k] += o.all[k]; }
            if (sum == 0) continue;
            b.append(b.length() == 0 ? "" : "; ").append(e.getKey()).append(':');
            for (int k = 0; k < NAMES.length; k++) if (o.window[k] > 0) b.append(' ').append(NAMES[k]).append(' ').append(pct(o.window[k], sum)).append('%');
            b.append(String.format(Locale.ROOT, " (decisions %d, guard cards %d; since start net %d%%, guard %d%%)", o.decisions, o.guards,
                    pct(o.all[NET], sumAll), pct(o.all[GUARD], sumAll)));
            o.decisions = 0;
            o.guards = 0;
            java.util.Arrays.fill(o.window, 0);
        }
        if (b.length() == 0) return null;
        long sum = 0, sumAll = 0;
        for (int k = 0; k < NAMES.length; k++) { sum += crew[k]; sumAll += crewAll[k]; }
        StringBuilder head = new StringBuilder("mind share, the last " + WINDOW / 20 + " s: crew");
        for (int k = 0; k < NAMES.length; k++) head.append(' ').append(NAMES[k]).append(' ').append(pct(crew[k], sum)).append('%');
        head.append(String.format(Locale.ROOT, " (since start net %d%%, self-defence %d%%, guard %d%%) | ", pct(crewAll[NET], sumAll), pct(crewAll[FIGHT], sumAll), pct(crewAll[GUARD], sumAll)));
        return head.append(b).toString();
    }

    void clear() {
        agents.clear();
        ticks = 0;
    }
}
