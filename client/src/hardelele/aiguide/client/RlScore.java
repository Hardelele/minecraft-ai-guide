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

    // ---- failures: the same again, or another way (3.1) -------------------------------------------------------

    /**
     * An agent's memory of its failures by action: how many times running each failed (since its last success), how
     * many of the last WINDOW decisions failed by each, the group of the last failure's reason. A step tells: { the k-th
     * failure in a row of the same action (k >= 2: the retry's growing penalty, k - 1), 1 when it succeeded with another
     * action than the one that failed last (a change of approach) }.
     */
    static final class Fails {
        final Map<Integer, Integer> run = new HashMap<Integer, Integer>();
        final ArrayDeque<int[]> window = new ArrayDeque<int[]>();   // { action, failed }
        int lastFailed = -1;
        String group = "";

        double[] step(int action, boolean ok, String reason) {
            window.add(new int[] { action, ok ? 0 : 1 });
            while (window.size() > RlSpec.FAIL_WINDOW) window.poll();
            if (!ok) {
                int k = (run.containsKey(action) ? run.get(action) : 0) + 1;
                run.put(action, k);
                lastFailed = action;
                group = RlSpec.failGroup(reason);
                return new double[] { k >= 2 ? k - 1 : 0, 0 };
            }
            run.remove(action);
            boolean other = lastFailed >= 0 && lastFailed != action;
            lastFailed = -1;
            return new double[] { 0, other ? 1 : 0 };
        }

        int run(int action) { Integer k = run.get(action); return k == null ? 0 : k; }

        double recent(int action) {
            int n = 0;
            for (int[] e : window) if (e[0] == action && e[1] == 1) n++;
            return n / (double) RlSpec.FAIL_WINDOW;
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

    /**
     * (3.1) Idling (AFK): the time an agent has gone without a real deed (a measured gain, a new thing in the bag, a hit
     * on a mob) and without going more than move blocks from where its idling began; standing, waiting, blocks put
     * round itself, the same failure again all count. A second of it pays the "afk" weight times min(cap, (t/tau)^2):
     * at once nothing, a minute of it more than a death (contract 3.1, rl/reward.json constants.afk). The Python
     * simulator has the same (sim_env.Afk; tools/tests/test_rl_afk.py: Java = Python).
     */
    static final class Afk {
        final double tau, cap, move;
        double since, ax, az;
        boolean anchored;
        Afk(double tau, double cap, double move) { this.tau = tau; this.cap = cap; this.move = move; }

        /** a game second at t, the agent at x z, real: a real deed in it; the factor of the second's afk */
        double second(double t, double x, double z, boolean real) {
            if (!anchored || real || Math.hypot(x - ax, z - az) > move) {
                since = t;
                ax = x;
                az = z;
                anchored = true;
                return 0;
            }
            double s = (t - since) / tau;
            return Math.min(cap, s * s);
        }

        double idle(double t) { return anchored ? t - since : 0; }
    }

    /**
     * (3.1) The day's wasted time, for the delayed credit: each decision by day (not at night nor in a wave) is a stretch
     * of time, useful when a real deed or a measured step (an OK that changed the bag: a wall, a moat, a sword, a
     * shelter's blocks, food) came of it, else wasted (walks, waits, failures). A penalty at night (a death, a breach,
     * mob-seconds) names the wasted decisions of the window before the night began: share x penalty x the wasted
     * part of that window, moved back to them by train.py's credit (no new payment: the event's points are part of
     * the penalty paid). The Python simulator has the same (sim_env.WastedDay; tools/tests/test_rl_afk.py).
     */
    static final class WastedDay {
        final double window, share;
        final java.util.List<double[]> segs = new java.util.ArrayList<double[]>();   // { decision id, from, to, useful }
        long cur = -1;
        double curFrom, nightFrom = -1;
        boolean curUseful, night;
        double dayTotal, dayWasted;   // the episode's: the share of the day wasted (a metric)
        WastedDay(double window, double share) { this.window = window; this.share = share; }

        /** a new decision at t (night: it is night or a wave now) */
        void decide(long id, double t, boolean nightNow) {
            phase(t, nightNow);
            close(t);
            if (!night) { cur = id; curFrom = t; curUseful = false; }
        }

        /** something useful came of the decision running */
        void useful() { curUseful = true; }

        /** the time of day at t: at the night's start the day's stretch closes, at its end a new day */
        void phase(double t, boolean nightNow) {
            if (nightNow && !night) { close(t); nightFrom = t; night = true; }
            else if (!nightNow && night) { night = false; }
        }

        void close(double t) {
            if (cur >= 0 && t > curFrom) {
                segs.add(new double[] { cur, curFrom, t, curUseful ? 1 : 0 });
                dayTotal += t - curFrom;
                if (!curUseful) dayWasted += t - curFrom;
            }
            cur = -1;
            while (segs.size() > 256) segs.remove(0);
        }

        /** a penalty p (< 0) at night at t: { points to move, the wasted decisions' ids... } or null */
        double[] night(double t, double p) {
            if (!night || nightFrom < 0 || p >= 0) return null;
            double from = nightFrom - window, all = 0, wasted = 0;
            java.util.List<Double> ids = new java.util.ArrayList<Double>();
            for (double[] s : segs) {
                double a = Math.max(from, s[1]), b = Math.min(nightFrom, s[2]);
                if (b <= a) continue;
                all += b - a;
                if (s[3] == 0) { wasted += b - a; ids.add(s[0]); }
            }
            if (all <= 0 || ids.isEmpty()) return null;
            double[] out = new double[1 + ids.size()];
            out[0] = share * p * wasted / all;
            for (int i = 0; i < ids.size(); i++) out[1 + i] = ids.get(i);
            return out;
        }

        double wastedFrac() { return dayTotal <= 0 ? 0 : dayWasted / dayTotal; }
    }

    /**
     * (3.1) The fight's bonuses, capped so that defending the base stays the most paid: a trade (a kill with little
     * health lost in the window before it, the most for none; low health, healed, back to hitting a mob in time) and a
     * dodge (a real threat met with no hit taken: the environment decides the threat, the leash and that the agent is
     * not shut in). Each returns the points to pay (its weight given); a dodge of the same mob again without a hit on it
     * is worth decay^k; the trade's and the dodge's points of a wave (or of a minute without waves) are capped. The
     * Python simulator has the same (sim_env.Fight; tools/tests/test_rl_afk.py).
     */
    static final class Fight {
        final double hpScale, window, low, high, back, decay, capTrade, capDodge, wKill, wRecover, wDodge;
        final ArrayDeque<double[]> hurt = new ArrayDeque<double[]>();   // { t, amount }
        final Map<Integer, Integer> dodged = new HashMap<Integer, Integer>();
        double lowAt = -1, healedAt = -1, capFrom = 0, trade, dodge;
        int wave = -1;
        Fight(double hpScale, double window, double low, double high, double back, double decay, double capTrade, double capDodge,
              double wKill, double wRecover, double wDodge) {
            this.hpScale = hpScale; this.window = window; this.low = low; this.high = high; this.back = back; this.decay = decay;
            this.capTrade = capTrade; this.capDodge = capDodge; this.wKill = wKill; this.wRecover = wRecover; this.wDodge = wDodge;
        }

        /** the caps' period: a new wave (its number), or a minute when there are no waves (wave < 0) */
        void period(double t, int waveNow) {
            if (waveNow >= 0 ? waveNow != wave : t - capFrom >= 60) {
                wave = waveNow; capFrom = t; trade = dodge = coverPts = blockPts = 0;
                covered.clear(); blocked.clear();
            }
        }

        void hurtBy(double t, double amount) { hurt.add(new double[] { t, amount }); prune(t); }

        void prune(double t) { while (!hurt.isEmpty() && t - hurt.peek()[0] > window) hurt.poll(); }

        double lost(double t) { prune(t); double s = 0; for (double[] h : hurt) s += h[1]; return s; }

        /** its health now: low marks the start of a retreat, high after it that it is healed */
        void health(double t, double hp) {
            if (hp < low && lowAt < 0) { lowAt = t; healedAt = -1; }
            else if (lowAt >= 0 && healedAt < 0 && hp >= high) healedAt = t;
            if (healedAt >= 0 && t - healedAt > back) { lowAt = healedAt = -1; }   // healed but never back in the fight
        }

        /** it killed the mob at t: the trade's points (its dodges of it forgotten) */
        double kill(double t, int mob) {
            dodged.remove(mob);
            return capT(wKill * Math.max(0, 1 - lost(t) / hpScale));
        }

        /** it hit the mob at t: a healed agent back in the fight is paid once */
        double hit(double t, int mob) {
            dodged.remove(mob);
            if (healedAt < 0 || t - healedAt > back) return 0;
            lowAt = healedAt = -1;
            return capT(wRecover);
        }

        /** a real threat of the mob met with no hit taken: decayed by the dodges of it before, capped */
        double dodge(int mob) {
            int k = dodged.containsKey(mob) ? dodged.get(mob) : 0;
            dodged.put(mob, k + 1);
            double p = Math.min(wDodge * Math.pow(decay, k), Math.max(0, capDodge - dodge));
            dodge += p;
            return p;
        }

        double capT(double p) { p = Math.min(p, Math.max(0, capTrade - trade)); trade += p; return p; }

        // a cover that worked (an arrow into a block, a mob kept off by one) and a block a mob had to go round: capped the same way
        double capCover, capBlock, wCover, wBlock, coverPts, blockPts;
        final Map<Integer, Integer> covered = new HashMap<Integer, Integer>();
        final java.util.Set<Long> blocked = new java.util.HashSet<Long>();

        Fight cover(double capCover, double capBlock, double wCover, double wBlock) {
            this.capCover = capCover; this.capBlock = capBlock; this.wCover = wCover; this.wBlock = wBlock;
            return this;
        }

        /** a cover that kept the mob off: decayed by the times before against it, capped */
        double cover(int mob) {
            int k = covered.containsKey(mob) ? covered.get(mob) : 0;
            covered.put(mob, k + 1);
            double p = Math.min(wCover * Math.pow(decay, k), Math.max(0, capCover - coverPts));
            coverPts += p;
            return p;
        }

        /** a mob that had to go round (or fell into) a block of the crew: once a pair (mob, block) a period, capped */
        double block(int mob, long pos) {
            if (!blocked.add(pos * 31 + mob)) return 0;
            double p = Math.min(wBlock, Math.max(0, capBlock - blockPts));
            blockPts += p;
            return p;
        }
    }

    /** (3.1) a death outside the base: the measure of perimeter.out_died by the distance d from the base, min(cap, d / scale) */
    static double outDied(double d, double scale, double cap) { return Math.min(cap, Math.max(0, d) / scale); }

    /**
     * (3.1) What a scout found (ores newly seen): each find's value counts within window seconds; a death then costs
     * less by min(cap, per x the values), the finds kept in the crew's memory all the same
     */
    static final class Finds {
        final double window, per, cap;
        final ArrayDeque<double[]> finds = new ArrayDeque<double[]>();   // { t, value }
        Finds(double window, double per, double cap) { this.window = window; this.per = per; this.cap = cap; }
        void found(double t, double value) { finds.add(new double[] { t, value }); }
        double soft(double t) {
            while (!finds.isEmpty() && t - finds.peek()[0] > window) finds.poll();
            double v = 0;
            for (double[] f : finds) v += f[1];
            return Math.min(cap, per * v);
        }
    }

    /** a found block's value for the scout (ores by worth; 0: not a find) */
    static double findValue(String block) {
        String b = block.replace("minecraft:", "");
        if (b.equals("diamond_ore")) return 3;
        if (b.equals("gold_ore")) return 1.5;
        if (b.equals("iron_ore") || b.equals("obsidian")) return 1;
        if (b.equals("redstone_ore") || b.equals("lit_redstone_ore")) return 0.5;
        if (b.equals("coal_ore")) return 0.3;
        return 0;
    }

    /** "afk e.json out.json": the factors of a run of seconds ({t, x, z, real} each), for tools/tests/test_rl_afk.py */
    public static void main(String[] args) throws Exception {
        if (args.length < 3 || !args[0].equals("afk")) { System.err.println("RlScore afk <events.json> <out.json>"); return; }
        com.google.gson.JsonObject in = new com.google.gson.JsonParser().parse(new String(java.nio.file.Files.readAllBytes(new java.io.File(args[1]).toPath()), "UTF-8")).getAsJsonObject();
        Afk a = new Afk(in.get("tau").getAsDouble(), in.get("cap").getAsDouble(), in.get("move").getAsDouble());
        com.google.gson.JsonArray out = new com.google.gson.JsonArray();
        for (com.google.gson.JsonElement e : in.getAsJsonArray("events")) {
            com.google.gson.JsonArray v = e.getAsJsonArray();
            out.add(new com.google.gson.JsonPrimitive(a.second(v.get(0).getAsDouble(), v.get(1).getAsDouble(), v.get(2).getAsDouble(), v.get(3).getAsInt() != 0)));
        }
        com.google.gson.JsonObject res = new com.google.gson.JsonObject();
        res.add("afk", out);
        if (in.has("wasted")) {   // the wasted day's ops: ["decide", id, t, night], ["useful"], ["phase", t, night], ["night", t, p]
            com.google.gson.JsonObject wi = in.getAsJsonObject("wasted");
            WastedDay wd = new WastedDay(wi.get("window").getAsDouble(), wi.get("share").getAsDouble());
            com.google.gson.JsonArray wo = new com.google.gson.JsonArray();
            for (com.google.gson.JsonElement e : wi.getAsJsonArray("ops")) {
                com.google.gson.JsonArray v = e.getAsJsonArray();
                String op = v.get(0).getAsString();
                if (op.equals("decide")) wd.decide(v.get(1).getAsLong(), v.get(2).getAsDouble(), v.get(3).getAsInt() != 0);
                else if (op.equals("useful")) wd.useful();
                else if (op.equals("phase")) wd.phase(v.get(1).getAsDouble(), v.get(2).getAsInt() != 0);
                else if (op.equals("night")) {
                    double[] r = wd.night(v.get(1).getAsDouble(), v.get(2).getAsDouble());
                    com.google.gson.JsonArray ra = new com.google.gson.JsonArray();
                    if (r != null) for (double x : r) ra.add(new com.google.gson.JsonPrimitive(x));
                    wo.add(ra);
                }
            }
            res.add("wasted", wo);
            res.addProperty("wasted_frac", wd.wastedFrac());
        }
        if (in.has("fight")) {   // ["period", t, wave], ["hurt", t, amount], ["health", t, hp], ["kill", t, mob], ["hit", t, mob], ["dodge", mob]
            com.google.gson.JsonObject fi = in.getAsJsonObject("fight");
            Fight f = new Fight(fi.get("hp_scale").getAsDouble(), fi.get("window").getAsDouble(), fi.get("low").getAsDouble(), fi.get("high").getAsDouble(),
                    fi.get("back").getAsDouble(), fi.get("decay").getAsDouble(), fi.get("cap_trade").getAsDouble(), fi.get("cap_dodge").getAsDouble(),
                    fi.get("w_kill").getAsDouble(), fi.get("w_recover").getAsDouble(), fi.get("w_dodge").getAsDouble())
                    .cover(fi.get("cap_cover").getAsDouble(), fi.get("cap_block").getAsDouble(), fi.get("w_cover").getAsDouble(), fi.get("w_block").getAsDouble());
            com.google.gson.JsonArray fo = new com.google.gson.JsonArray();
            for (com.google.gson.JsonElement e : fi.getAsJsonArray("ops")) {
                com.google.gson.JsonArray v = e.getAsJsonArray();
                String op = v.get(0).getAsString();
                if (op.equals("period")) f.period(v.get(1).getAsDouble(), v.get(2).getAsInt());
                else if (op.equals("hurt")) f.hurtBy(v.get(1).getAsDouble(), v.get(2).getAsDouble());
                else if (op.equals("health")) f.health(v.get(1).getAsDouble(), v.get(2).getAsDouble());
                else if (op.equals("kill")) fo.add(new com.google.gson.JsonPrimitive(f.kill(v.get(1).getAsDouble(), v.get(2).getAsInt())));
                else if (op.equals("hit")) fo.add(new com.google.gson.JsonPrimitive(f.hit(v.get(1).getAsDouble(), v.get(2).getAsInt())));
                else if (op.equals("dodge")) fo.add(new com.google.gson.JsonPrimitive(f.dodge(v.get(1).getAsInt())));
                else if (op.equals("cover")) fo.add(new com.google.gson.JsonPrimitive(f.cover(v.get(1).getAsInt())));
                else if (op.equals("block")) fo.add(new com.google.gson.JsonPrimitive(f.block(v.get(1).getAsInt(), v.get(2).getAsLong())));
            }
            res.add("fight", fo);
        }
        if (in.has("finds")) {   // ["found", t, block], ["soft", t]; and out_died over distances
            com.google.gson.JsonObject fi = in.getAsJsonObject("finds");
            Finds f = new Finds(fi.get("window").getAsDouble(), fi.get("per").getAsDouble(), fi.get("cap").getAsDouble());
            com.google.gson.JsonArray fo = new com.google.gson.JsonArray();
            for (com.google.gson.JsonElement e : fi.getAsJsonArray("ops")) {
                com.google.gson.JsonArray v = e.getAsJsonArray();
                if (v.get(0).getAsString().equals("found")) f.found(v.get(1).getAsDouble(), findValue(v.get(2).getAsString()));
                else fo.add(new com.google.gson.JsonPrimitive(f.soft(v.get(1).getAsDouble())));
            }
            res.add("finds", fo);
            com.google.gson.JsonArray od = new com.google.gson.JsonArray();
            for (com.google.gson.JsonElement e : fi.getAsJsonArray("out_died"))
                od.add(new com.google.gson.JsonPrimitive(outDied(e.getAsDouble(), fi.get("out_scale").getAsDouble(), fi.get("out_cap").getAsDouble())));
            res.add("out_died", od);
        }
        java.nio.file.Files.write(new java.io.File(args[2]).toPath(), res.toString().getBytes("UTF-8"));
    }

    /** the components whose payment is progress (the stall's clock starts again): the scales, a goal, an achievement, a useful step */
    static boolean progress(String comp) {
        return RlSpec.isScale(comp) || comp.equals("goal") || comp.equals("skill_ok");
    }
}
