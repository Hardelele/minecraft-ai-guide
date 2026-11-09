package hardelele.aiguide.client;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The measures of contract 2's reward scales, with no game in them (docs/rl/CONTRACT-v2.md, "Награда"): a scale pays
 * the change of a value measured in the world, never a skill's word OK. RlEnv measures (the bags, the blocks, the
 * mobs) once a second and pays what these give; RlTest checks them on numbers.
 */
final class RlScore {
    private RlScore() { }

    /** the last value of each measure: the change since (the first value is the baseline: nothing paid for a kit) */
    static final class Track {
        final Map<String, Double> last = new HashMap<String, Double>();
        double delta(String key, double v) {
            Double was = last.put(key, v);
            return was == null ? 0 : v - was;
        }
        /** the change of a value that only counts as it rises past its best (a horizon reached, a peak) */
        double rise(String key, double v) {
            Double was = last.get(key);
            if (was == null) { last.put(key, v); return 0; }
            if (v <= was) return 0;
            last.put(key, v);
            return v - was;
        }
    }

    // ---- equipment: what an agent wears and wields ------------------------------------------------------------

    /** { armour points worn / 20, best sword's tier / 4, best pickaxe's tier / 4, a bow with 4 arrows or more } */
    static double[] equipment(double armor, int swordTier, int pickTier, boolean bow, int arrows) {
        return new double[] { Math.min(1, armor / 20.0), Math.min(1, swordTier / 4.0), Math.min(1, pickTier / 4.0), bow && arrows >= 4 ? 1 : 0 };
    }

    static final String[] EQUIPMENT = { "equipment.armor", "equipment.weapon", "equipment.pickaxe", "equipment.ranged" };

    // ---- stock: useful things up to a cap each -----------------------------------------------------------------

    /** the crew's stock: the sum over RlSpec.STOCK of min(count, cap) / cap * value (counts by the stock's keys; food in points) */
    static double stock(Map<String, Double> counts) {
        double v = 0;
        for (String[] s : RlSpec.STOCK) {
            Double n = counts.get(s[0]);
            double cap = Double.parseDouble(s[1]), val = Double.parseDouble(s[2]);
            v += Math.min(n == null ? 0 : n, cap) / cap * val;
        }
        return v;
    }

    // ---- sustain: the renewable that is used, the sources kept ---------------------------------------------------

    /**
     * The renewable's ledger: food points and logs that came from what the crew grows (its farm, its pen's young,
     * its planted trees) against the food points eaten and the logs used. What is paid is the rise of
     * min(renewable got, used): a harvest that nobody eats pays nothing; eating hunted meat pays nothing while the
     * farm has given nothing.
     */
    static final class Sustain {
        double foodRenew, foodEaten, woodRenew, woodUsed;
        final ArrayDeque<double[]> window = new ArrayDeque<double[]>();   // { time s, kind 0..3, amount }
        double paidFood, paidWood;

        void add(double t, int kind, double amount) {
            if (amount <= 0) return;
            if (kind == 0) foodRenew += amount;
            else if (kind == 1) foodEaten += amount;
            else if (kind == 2) woodRenew += amount;
            else woodUsed += amount;
            window.add(new double[] { t, kind, amount });
        }

        /** { food points, logs } to pay now */
        double[] pay() {
            double f = Math.min(foodRenew, foodEaten), w = Math.min(woodRenew, woodUsed);
            double[] d = { f - paidFood, w - paidWood };
            paidFood = f;
            paidWood = w;
            return d;
        }

        /** the last window's sums: { food renewable, food eaten, wood renewable, wood used } */
        double[] recent(double now, double seconds) {
            while (!window.isEmpty() && window.peek()[0] < now - seconds) window.poll();
            double[] s = new double[4];
            for (double[] e : window) s[(int) e[1]] += e[2];
            return s;
        }
    }

    static final int FOOD_RENEW = 0, FOOD_EATEN = 1, WOOD_RENEW = 2, WOOD_USED = 3;

    /** the sources of the renewable that stand: planted crops (of 16), the pen's breeding pair, growing planted trees (of 4); 0..3 */
    static double sources(int planted, int penAdults, int saplings) {
        return Math.min(planted, 16) / 16.0 + Math.min(penAdults, 2) / 2.0 + Math.min(saplings, 4) / 4.0;
    }

    // ---- the mine: a trip down and back ---------------------------------------------------------------------------

    /** which horizon a y is at or below: 0 above the first, 1 at or below RlSpec.HORIZONS[0] (+ near), 2 at or below the second */
    static int horizon(double y) {
        int h = 0;
        for (int i = 0; i < RlSpec.HORIZONS.length; i++) if (y <= RlSpec.HORIZONS[i] + RlSpec.HORIZON_NEAR) h = i + 1;
        return h;
    }

    /** within HORIZON_NEAR of a horizon's y */
    static boolean atHorizon(double y) {
        for (int h : RlSpec.HORIZONS) if (Math.abs(y - h) <= RlSpec.HORIZON_NEAR) return true;
        return false;
    }

    /** an agent's trip: down to a horizon, ore got, back on the surface; done once per trip */
    static final class Trip {
        int deepest, ores, done;
        boolean out;

        /** a second of it: the horizon it is at, whether under the ground, ore got this second; true when a trip ends now with ore */
        boolean update(int horizon, boolean underground, int oreGain) {
            if (horizon >= 1) { out = true; deepest = Math.max(deepest, horizon); }
            if (out) ores += Math.max(0, oreGain);
            if (out && !underground) {
                boolean ok = ores > 0;
                if (ok) done++;
                out = false;
                ores = 0;
                deepest = 0;
                return ok;
            }
            return false;
        }
    }

    // ---- the perimeter: no zombie into the base ---------------------------------------------------------------------

    /** the enemies inside the ring by the second: mob-seconds, breaches (an enemy inside for the first time this wave), clean waves */
    static final class Perimeter {
        final Set<Integer> inside = new HashSet<Integer>(), ever = new HashSet<Integer>();
        double mobSeconds, waveMobSeconds;
        int breaches, waveBreaches, clean, waves;

        /** a second: the ids of the enemies inside now; { mob-seconds, new breaches } this second */
        double[] second(Collection<Integer> now, double dt) {
            inside.clear();
            inside.addAll(now);
            int fresh = 0;
            for (Integer id : now) if (ever.add(id)) fresh++;
            double ms = now.size() * dt;
            mobSeconds += ms;
            waveMobSeconds += ms;
            breaches += fresh;
            waveBreaches += fresh;
            return new double[] { ms, fresh };
        }

        /** a wave over: true when no enemy got inside in it */
        boolean waveEnd() {
            boolean c = waveBreaches == 0;
            waves++;
            if (c) clean++;
            waveBreaches = 0;
            waveMobSeconds = 0;
            ever.clear();
            return c;
        }
    }

    // ---- stall: no measured progress for a while ------------------------------------------------------------------

    /** an agent's time of its last measured progress; a stall each STALL_S seconds without */
    static final class Stall {
        final Map<String, Double> since = new HashMap<String, Double>();
        void progress(String agent, double t) { since.put(agent, t); }
        /** true when the agent has gone STALL_S seconds without progress (the window starts again) */
        boolean check(String agent, double t) {
            Double s = since.get(agent);
            if (s == null) { since.put(agent, t); return false; }
            if (t - s >= RlSpec.STALL_S) { since.put(agent, t); return true; }
            return false;
        }
        double idle(String agent, double t) { Double s = since.get(agent); return s == null ? 0 : t - s; }
    }

    /** the components whose payment is progress (the stall's clock starts again): the scales, a goal, an achievement, a useful step */
    static boolean progress(String comp) {
        return RlSpec.isScale(comp) || comp.equals("goal") || comp.equals("skill_ok");
    }
}
