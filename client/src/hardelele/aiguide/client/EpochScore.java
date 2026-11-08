package hardelele.aiguide.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * An epoch's grades, with no game in it: each episode (a mind's step, a reflex's case, a death) good, bad or neither
 * by the rules (RULES), a person's rating from the ratings' panel over it (three times the weight, and it wins); the
 * crew's development between the waves (Stock, karma: better tools, gathered, built, food; nothing done is a minus);
 * the epoch's reward from its numbers (REWARD: waves, kills, deaths by cause, damage, holes in the wall, retreats,
 * the episodes, the karma); the summary the lessons' model reads. EpochsTest checks it.
 */
final class EpochScore {
    private EpochScore() { }

    /** a person's rating counts this many times the rules' */
    static final double HUMAN = 3, AUTO = 1;
    /** a step after which the agent died this soon (ticks) was a bad one */
    static final int DIED_SOON = 200;

    // ---- the episodes --------------------------------------------------------------------------------------

    /** one episode of an epoch */
    static final class Episode {
        String kind = "step";          // step (a mind's), case (a reflex's), death
        String key = "";               // what a person's rating refers to (Episodes.Ep.key's form)
        String agent = "", what = "", result = "", detail = "";
        String phase = "";             // the waves' phase when it ended: prep, wave, break, over
        String cause = "";             // a death's: fight, starve, accident
        int wave;
        long t, ms;                    // world tick and wall clock at its end
        float before = -1;             // health when it began (-1: not known)
        boolean inside = true;         // inside the ring wall when it ended (no ring: true)
        boolean diedSoon;              // the agent died within DIED_SOON ticks after it
        Defense.Tally fight;           // what it came to in the fight (null: nothing)
        int[] at;
        int auto, human;               // the ratings: +1, -1, 0 none
        String autoWhy = "", humanWhy = "";

        int rating() { return human != 0 ? human : auto; }

        String why() { return human != 0 ? humanWhy : autoWhy; }

        double score() { return human != 0 ? HUMAN * human : AUTO * auto; }

        JsonObject json() {
            JsonObject o = new JsonObject();
            o.addProperty("kind", kind);
            o.addProperty("key", key);
            o.addProperty("agent", agent);
            o.addProperty("what", what);
            o.addProperty("result", result);
            if (!detail.isEmpty()) o.addProperty("detail", detail);
            o.addProperty("phase", phase);
            o.addProperty("wave", wave);
            if (!cause.isEmpty()) o.addProperty("cause", cause);
            o.addProperty("t", t);
            o.addProperty("ms", ms);
            if (before >= 0) o.addProperty("health_before", Math.round(before * 10) / 10.0);
            o.addProperty("inside", inside);
            o.addProperty("died_soon", diedSoon);
            if (fight != null && !fight.empty()) o.add("combat", fight.json());
            if (at != null) o.add("at", Labels.ints(at));
            o.addProperty("auto", auto);
            o.addProperty("auto_why", autoWhy);
            if (human != 0) { o.addProperty("human", human); o.addProperty("human_why", humanWhy); }
            o.addProperty("rating", rating());
            o.addProperty("score", score());
            return o;
        }

        static Episode fromJson(JsonObject o) {
            Episode e = new Episode();
            e.kind = CaseVec.str(o, "kind");
            e.key = CaseVec.str(o, "key");
            e.agent = CaseVec.str(o, "agent");
            e.what = CaseVec.str(o, "what");
            e.result = CaseVec.str(o, "result");
            e.detail = CaseVec.str(o, "detail");
            e.phase = CaseVec.str(o, "phase");
            e.cause = CaseVec.str(o, "cause");
            e.wave = (int) CaseVec.num(o, "wave", 0);
            e.t = (long) CaseVec.num(o, "t", 0);
            e.ms = (long) CaseVec.num(o, "ms", 0);
            e.before = (float) CaseVec.num(o, "health_before", -1);
            e.inside = !o.has("inside") || o.get("inside").getAsBoolean();
            e.diedSoon = o.has("died_soon") && o.get("died_soon").getAsBoolean();
            if (o.has("combat") && o.get("combat").isJsonObject()) e.fight = Defense.Tally.fromJson(o.getAsJsonObject("combat"));
            e.auto = (int) CaseVec.num(o, "auto", 0);
            e.autoWhy = CaseVec.str(o, "auto_why");
            e.human = (int) CaseVec.num(o, "human", 0);
            e.humanWhy = CaseVec.str(o, "human_why");
            return e;
        }
    }

    /** the rules, in the order they are tried: the reason's code, the rating, what it means (ARCHITECTURE-LAYERS.md, "Эпохи обучения") */
    static final String[][] RULES = {
            { "starved", "-1", "died of hunger" },
            { "accident", "-1", "died of a fall, water, lava or fire" },
            { "out_died", "-1", "died outside the wall during a wave" },
            { "died", "-1", "died in the fight" },
            { "died_after", "-1", "died in this step or within 10 s after it" },
            { "bad_call", "-1", "the model gave no valid step" },
            { "retreat_timely", "1", "fell back hurt (health at most 12) and lived" },
            { "retreat_early", "-1", "fell back during a wave with health 16 or more" },
            { "repaired_before", "1", "repaired the wall before the wave" },
            { "built_before", "1", "built the wall before the wave" },
            { "prepared", "1", "a moat or torches before the wave" },
            { "armed", "1", "made or put on a weapon or armour before the wave" },
            { "held_killed", "1", "held its post (or an ally) and killed, no death" },
            { "hurt_no_kill", "-1", "lost 10 health or more at its post and killed nothing" },
            { "idle_in_wave", "-1", "waited or looked round while a wave was on" },
            { "ate", "1", "ate to heal" },
            { "gathered", "1", "chopped or mined between the waves" },
            { "reflex_ok", "1", "a reflex's reaction helped" },
            { "reflex_failed", "-1", "a reflex's reaction did not help" },
    };

    static final String[] FIGHTS = { "hold_line", "guard_ally" };
    static final String[] IDLE = { "wait", "look_around" };

    static boolean in(String[] a, String s) {
        for (String x : a) if (x.equals(s)) return true;
        return false;
    }

    static int ruleRating(String code) {
        for (String[] r : RULES) if (r[0].equals(code)) return Integer.parseInt(r[1]);
        return 0;
    }

    static String ruleText(String code) {
        for (String[] r : RULES) if (r[0].equals(code)) return r[2];
        return code;
    }

    /** the rules' rating of an episode (its auto and autoWhy set; 0 and "" when no rule holds) */
    static int rate(Episode e) {
        String code = code(e);
        e.autoWhy = code == null ? "" : code;
        e.auto = code == null ? 0 : ruleRating(code);
        return e.auto;
    }

    static String code(Episode e) {
        boolean ok = "OK".equals(e.result), waveOn = "wave".equals(e.phase);
        if (e.kind.equals("death")) {
            if (e.cause.equals("starve")) return "starved";
            if (e.cause.equals("accident")) return "accident";
            if (!e.inside && waveOn) return "out_died";
            return "died";
        }
        if (e.kind.equals("case")) {
            if (e.diedSoon) return "died_after";
            if (e.result.equals("PUSHED") || e.result.isEmpty()) return null;   // a higher reflex took the body: neither
            return ok ? "reflex_ok" : "reflex_failed";
        }
        if (e.diedSoon || e.result.equals("DIED")) return "died_after";   // (DIED: the step itself ended by its death, AgentMind)
        if (e.result.startsWith("BAD_CALL")) return "bad_call";
        boolean fight = in(FIGHTS, e.what);
        boolean fellBack = e.what.equals("retreat") || fight && e.result.equals("LOW_HEALTH");
        if (fellBack) {
            if (e.before >= 0 && e.before <= Defense.LOW + 4) return "retreat_timely";
            if (e.before >= 16 && waveOn && e.what.equals("retreat")) return "retreat_early";
            return null;
        }
        if (ok && !waveOn) {
            if (e.what.equals("repair_wall")) return "repaired_before";
            if (e.what.equals("build_wall")) return "built_before";
            if (e.what.equals("dig_moat") || e.what.equals("light_area")) return "prepared";
            if (e.what.equals("make_weapon") || e.what.equals("equip")) return "armed";
            if (e.what.equals("chop_tree") || e.what.equals("mine")) return "gathered";
        }
        if (fight && e.fight != null) {
            if (e.fight.kills > 0 && e.fight.deaths == 0) return "held_killed";
            if (e.fight.taken >= 10 && e.fight.kills == 0) return "hurt_no_kill";
        }
        if (in(IDLE, e.what) && waveOn) return "idle_in_wave";
        if (e.what.equals("eat") && ok) return "ate";
        return null;
    }

    /** every episode rated by the rules */
    static void rateAll(List<Episode> eps) { for (Episode e : eps) rate(e); }

    // ---- a person's ratings (the ratings' panel: aiguide-labels/actions.jsonl) -----------------------------

    /** how far apart (ms) a rated fight's line and the epoch's step may be and still be the same one */
    static final long SAME_MS = 3000;

    /**
     * The person's ratings over the episodes they are about: a case by its tick, agent and reflex (the journal's
     * key); a step of the fight by its agent, its skill and its time (within SAME_MS). A later rating wins over an
     * earlier one. Returns how many episodes got one.
     */
    static int applyLabels(List<Episode> eps, List<JsonObject> labels) {
        int n = 0;
        if (labels == null) return 0;
        for (JsonObject l : labels) {
            if (!"action".equals(CaseVec.str(l, "kind")) || !l.has("target") || !l.get("target").isJsonObject()) continue;
            JsonObject t = l.getAsJsonObject("target");
            int rating = CaseVec.num(l, "rating", 0) > 0 ? 1 : -1;
            JsonElement re = l.get("reason");
            String why = re == null || re.isJsonNull() ? "human" : re.getAsString();
            String type = CaseVec.str(t, "type");
            Episode hit = null;
            if (type.equals("case")) {
                String key = "c:" + (long) CaseVec.num(t, "t", -1) + ":" + CaseVec.str(t, "agent") + ":" + CaseVec.str(t, "reflex");
                for (Episode e : eps) if (e.kind.equals("case") && e.key.equals(key)) hit = e;
            } else if (type.equals("fight")) {
                long ms = (long) CaseVec.num(t, "ms", -1);
                long best = SAME_MS + 1;
                for (Episode e : eps) {
                    if (!e.kind.equals("step") || !e.agent.equals(CaseVec.str(t, "agent")) || !e.what.equals(CaseVec.str(t, "card"))) continue;
                    long d = Math.abs(e.ms - ms);
                    if (d < best) { best = d; hit = e; }
                }
            }
            if (hit == null) continue;
            if (hit.human == 0) n++;
            hit.human = rating;
            hit.humanWhy = why;
        }
        return n;
    }

    // ---- the development (karma) ---------------------------------------------------------------------------

    /** what the crew has at a moment: its best tiers, its stores, what it has built and eaten so far */
    static final class Stock {
        String at = "";                        // begin, "wave N start", "wave N end", end
        int wave;
        long t;
        int weapon, pick, armor;               // tiers: 0 none, 1 wood (leather), 2 stone (gold, chain), 3 iron, 4 diamond
        int logs, stone, ore, food;            // in the bags, worn, the crew's chest
        int wall, moat, torches, eaten;        // standing wall cells, dug moat cells, torches put, food eaten (so far)

        int tiers() { return weapon + pick + armor; }

        JsonObject json() {
            JsonObject o = new JsonObject();
            o.addProperty("at", at);
            o.addProperty("wave", wave);
            o.addProperty("t", t);
            o.addProperty("weapon", weapon); o.addProperty("pick", pick); o.addProperty("armor", armor);
            o.addProperty("logs", logs); o.addProperty("stone", stone); o.addProperty("ore", ore); o.addProperty("food", food);
            o.addProperty("wall", wall); o.addProperty("moat", moat); o.addProperty("torches", torches); o.addProperty("eaten", eaten);
            return o;
        }

        static Stock fromJson(JsonObject o) {
            Stock s = new Stock();
            s.at = CaseVec.str(o, "at");
            s.wave = (int) CaseVec.num(o, "wave", 0);
            s.t = (long) CaseVec.num(o, "t", 0);
            s.weapon = (int) CaseVec.num(o, "weapon", 0); s.pick = (int) CaseVec.num(o, "pick", 0); s.armor = (int) CaseVec.num(o, "armor", 0);
            s.logs = (int) CaseVec.num(o, "logs", 0); s.stone = (int) CaseVec.num(o, "stone", 0); s.ore = (int) CaseVec.num(o, "ore", 0); s.food = (int) CaseVec.num(o, "food", 0);
            s.wall = (int) CaseVec.num(o, "wall", 0); s.moat = (int) CaseVec.num(o, "moat", 0); s.torches = (int) CaseVec.num(o, "torches", 0); s.eaten = (int) CaseVec.num(o, "eaten", 0);
            return s;
        }
    }

    /** an item's tier by its game id: wood 1, stone (gold, chain, leather...) 1-2, iron 3, diamond 4; 0 not a tool of that kind */
    static int tier(String id) {
        String s = id == null ? "" : id.replace("minecraft:", "");
        if (s.startsWith("diamond_")) return 4;
        if (s.startsWith("iron_") || s.startsWith("chainmail_")) return 3;
        if (s.startsWith("stone_") || s.startsWith("golden_")) return 2;
        if (s.startsWith("wooden_") || s.startsWith("leather_")) return 1;
        if (s.equals("bow")) return 2;
        return 0;
    }

    /** which of a stock's counts an item goes to (by its id): logs, stone, ore, food; or null */
    static String store(String id, boolean food) {
        if (food) return "food";
        String s = id == null ? "" : id.replace("minecraft:", "");
        int c = s.indexOf(':');
        if (c >= 0) s = s.substring(0, c);
        if (s.equals("log") || s.equals("log2")) return "logs";
        if (s.equals("cobblestone") || s.equals("stone")) return "stone";
        if (s.endsWith("_ore") || s.equals("coal") || s.equals("iron_ingot") || s.equals("gold_ingot") || s.equals("diamond") || s.equals("redstone") || s.equals("emerald"))
            return "ore";
        return null;
    }

    /** one line of the karma: the points and the reason's code (aiguide.ui.epochs.karma.CODE), where (the wave after which) */
    static final class Karma {
        final double points;
        final String code;
        final int wave;
        Karma(double points, String code, int wave) { this.points = points; this.code = code; this.wave = wave; }

        JsonObject json() {
            JsonObject o = new JsonObject();
            o.addProperty("points", points);
            o.addProperty("code", code);
            o.addProperty("wave", wave);
            o.addProperty("text", karmaText(code));
            return o;
        }
    }

    static String karmaText(String code) {
        if (code.equals("tier_up")) return "a better tool, weapon or armour";
        if (code.equals("gathered")) return "logs, stone or ore gathered";
        if (code.equals("built")) return "wall, moat or torches built";
        if (code.equals("ate")) return "ate";
        if (code.equals("idle")) return "a break with nothing gathered, made or built";
        if (code.equals("no_food")) return "no food got nor eaten all the epoch";
        if (code.equals("food_stock")) return "food in store at the end";
        if (code.equals("no_tools")) return "no better tool all the epoch";
        return code;
    }

    /**
     * The crew's development from its stocks in their order: for each time to get ready (the preparation, each
     * break: from a "begin" or "wave N end" to the next "wave start" or "end") a tier up +2 each, gathered (logs/8,
     * stone/16, ore/2, at most +3), built (wall, moat, torches /8, at most +3), eaten (+0.5 each, at most +2), and -2
     * when nothing at all grew; over the whole epoch -1 with no food got nor eaten (+1 food left), -1 with no better tool.
     */
    static List<Karma> karma(List<Stock> stocks) {
        List<Karma> out = new ArrayList<Karma>();
        if (stocks == null || stocks.size() < 2) return out;
        for (int i = 0; i + 1 < stocks.size(); i++) {
            Stock a = stocks.get(i), b = stocks.get(i + 1);
            boolean ready = (a.at.equals("begin") || a.at.endsWith(" end")) && (b.at.endsWith(" start") || b.at.equals("end"));
            if (!ready) continue;
            int wave = b.at.endsWith(" start") ? b.wave : a.wave;
            int ups = Math.max(0, b.weapon - a.weapon) + Math.max(0, b.pick - a.pick) + Math.max(0, b.armor - a.armor);
            int dl = b.logs - a.logs, ds = b.stone - a.stone, dor = b.ore - a.ore, df = b.food - a.food;
            int built = Math.max(0, b.wall - a.wall) + Math.max(0, b.moat - a.moat) + Math.max(0, b.torches - a.torches);
            int gathered = Math.min(3, Math.max(0, dl) / 8 + Math.max(0, ds) / 16 + Math.max(0, dor) / 2);
            int ate = Math.max(0, b.eaten - a.eaten);
            if (ups > 0) out.add(new Karma(2 * ups, "tier_up", wave));
            if (gathered > 0) out.add(new Karma(gathered, "gathered", wave));
            if (built >= 8) out.add(new Karma(Math.min(3, built / 8), "built", wave));
            if (ate > 0) out.add(new Karma(Math.min(2, 0.5 * ate), "ate", wave));
            if (ups == 0 && dl <= 0 && ds <= 0 && dor <= 0 && df <= 0 && built == 0 && ate == 0) out.add(new Karma(-2, "idle", wave));
        }
        Stock first = stocks.get(0), last = stocks.get(stocks.size() - 1);
        boolean gotFood = last.eaten > first.eaten || last.food > first.food;
        for (int i = 1; i < stocks.size() && !gotFood; i++) if (stocks.get(i).food > stocks.get(i - 1).food) gotFood = true;
        if (!gotFood && last.food == 0) out.add(new Karma(-1, "no_food", 0));
        else if (last.food > 0) out.add(new Karma(1, "food_stock", 0));
        if (last.tiers() <= first.tiers()) {
            boolean any = false;
            for (Stock s : stocks) if (s.tiers() > first.tiers()) any = true;
            if (!any) out.add(new Karma(-1, "no_tools", 0));
        }
        return out;
    }

    static double sum(List<Karma> k) {
        double s = 0;
        for (Karma x : k) s += x.points;
        return s;
    }

    /** the karma's worst line (the most minus), or null when none is below zero */
    static Karma worst(List<Karma> k) {
        Karma w = null;
        for (Karma x : k) if (x.points < 0 && (w == null || x.points < w.points)) w = x;
        return w;
    }

    // ---- the epoch's reward --------------------------------------------------------------------------------

    /** the reward's parts: name, weight a unit (ARCHITECTURE-LAYERS.md has the table) */
    static final Object[][] REWARD = {
            { "waves", 10.0 }, { "kills", 1.0 }, { "deaths", -5.0 }, { "starved", -6.0 }, { "accidents", -4.0 },
            { "taken", -0.1 }, { "dealt", 0.05 }, { "holes", -2.0 }, { "retreats_timely", 2.0 }, { "retreats_bad", -2.0 },
            { "episodes", 0.5 }, { "karma", 1.0 },
    };

    static double weight(String part) {
        for (Object[] r : REWARD) if (r[0].equals(part)) return (Double) r[1];
        return 0;
    }

    /** the reward: each part's count, weight and points, the total */
    static final class Reward {
        final Map<String, double[]> parts = new LinkedHashMap<String, double[]>();   // name -> { count, weight, points }
        double total;

        void add(String name, double count) {
            double w = weight(name);
            parts.put(name, new double[] { count, w, count * w });
            total += count * w;
        }

        JsonObject json() {
            JsonObject o = new JsonObject();
            JsonObject p = new JsonObject();
            for (Map.Entry<String, double[]> e : parts.entrySet()) {
                JsonObject x = new JsonObject();
                x.addProperty("count", round(e.getValue()[0]));
                x.addProperty("weight", e.getValue()[1]);
                x.addProperty("points", round(e.getValue()[2]));
                p.add(e.getKey(), x);
            }
            o.add("parts", p);
            o.addProperty("total", round(total));
            return o;
        }
    }

    static double round(double v) { return Math.round(v * 100) / 100.0; }

    /**
     * The reward of an epoch: its run (WaveReport's line), the crew's fight (the tallies by agent), the holes in the
     * wall at each wave's start, the episodes (rated) and the karma.
     */
    static Reward reward(JsonObject run, Map<String, Defense.Tally> fight, List<Integer> holes, List<Episode> eps, List<Karma> karma) {
        Reward r = new Reward();
        Defense.Tally all = new Defense.Tally();
        if (fight != null) for (Defense.Tally t : fight.values()) all.add(t);
        r.add("waves", run == null ? 0 : CaseVec.num(run, "waves_cleared", 0));
        r.add("kills", run == null ? all.kills : CaseVec.num(run, "kills_total", all.kills));
        r.add("deaths", all.fightDeaths());
        r.add("starved", all.starved);
        r.add("accidents", all.accidents);
        r.add("taken", all.taken);
        r.add("dealt", all.dealt);
        int h = 0;
        if (holes != null) for (int x : holes) h += Math.min(10, Math.max(0, x));
        r.add("holes", h);
        int ok = 0, bad = 0;
        double sc = 0;
        for (Episode e : eps) {
            if (e.rating() > 0 && e.why().equals("retreat_timely")) ok++;
            if (e.rating() < 0 && e.why().equals("retreat_early")) bad++;
            sc += e.score();
        }
        r.add("retreats_timely", ok);
        r.add("retreats_bad", bad);
        r.add("episodes", sc);
        r.add("karma", sum(karma));
        return r;
    }

    // ---- the summary the lessons' model reads --------------------------------------------------------------

    /** reason codes counted over the rated episodes, the most first: { code, good, bad } */
    static List<Object[]> reasons(List<Episode> eps) {
        final Map<String, int[]> m = new LinkedHashMap<String, int[]>();
        for (Episode e : eps) {
            if (e.rating() == 0) continue;
            String w = e.why();
            int[] c = m.get(w);
            if (c == null) m.put(w, c = new int[2]);
            c[e.rating() > 0 ? 0 : 1]++;
        }
        List<Object[]> out = new ArrayList<Object[]>();
        for (Map.Entry<String, int[]> e : m.entrySet()) out.add(new Object[] { e.getKey(), e.getValue()[0], e.getValue()[1] });
        Collections.sort(out, (a, b) -> ((Integer) b[1] + (Integer) b[2]) - ((Integer) a[1] + (Integer) a[2]));
        return out;
    }

    /**
     * The epoch in plain lines for the model that writes the lessons (English, as the minds' prompt): the numbers,
     * the reward's parts, the karma, the reasons of the good and the bad with a few episodes each (a person's
     * ratings marked: they weigh more), the thresholds tried.
     */
    static String summary(int epoch, String track, JsonObject run, Reward r, List<Episode> eps, List<Karma> karma, int bestBefore, String knobs) {
        StringBuilder b = new StringBuilder();
        b.append("EPOCH ").append(epoch).append(track.equals("control") ? " (control)" : "").append(": ");
        b.append(run == null ? 0 : (int) CaseVec.num(run, "waves_cleared", 0)).append(" waves cleared (best before ").append(bestBefore).append("), ");
        b.append(run == null ? 0 : (int) CaseVec.num(run, "kills_total", 0)).append(" kills, ");
        b.append(run == null ? 0 : (int) CaseVec.num(run, "losses_total", 0)).append(" of the crew lost, ended by ").append(run == null ? "?" : CaseVec.str(run, "ended_by")).append('\n');
        b.append("REWARD ").append(round(r.total)).append(':');
        for (Map.Entry<String, double[]> e : r.parts.entrySet())
            if (e.getValue()[0] != 0) b.append(' ').append(e.getKey()).append(' ').append(fmt(e.getValue()[0])).append(" (").append(fmt(e.getValue()[2])).append(')');
        b.append('\n');
        b.append("KARMA (development) ").append(fmt(sum(karma))).append(':');
        if (karma.isEmpty()) b.append(" none");
        for (Karma k : karma) b.append(" ").append(fmt(k.points)).append(' ').append(karmaText(k.code)).append(k.wave > 0 ? " (before wave " + k.wave + ")" : "").append(';');
        b.append('\n');
        if (knobs != null && !knobs.isEmpty()) b.append("THRESHOLDS: ").append(knobs).append('\n');
        List<Object[]> rs = reasons(eps);
        b.append("WHAT WENT BADLY:\n");
        int shown = 0;
        for (Object[] x : rs) if ((Integer) x[2] > 0 && shown++ < 6) b.append("- BAD ").append(x[0]).append(" x").append(x[2]).append(": ").append(ruleText((String) x[0])).append(examples(eps, (String) x[0], -1)).append('\n');
        if (shown == 0) b.append("- nothing rated bad\n");
        b.append("WHAT WENT WELL:\n");
        shown = 0;
        for (Object[] x : rs) if ((Integer) x[1] > 0 && shown++ < 4) b.append("- GOOD ").append(x[0]).append(" x").append(x[1]).append(": ").append(ruleText((String) x[0])).append(examples(eps, (String) x[0], 1)).append('\n');
        if (shown == 0) b.append("- nothing rated good\n");
        int people = 0;
        for (Episode e : eps) if (e.human != 0) people++;
        if (people > 0) b.append("A PERSON RATED ").append(people).append(" episodes (marked [person]): their word counts three times.\n");
        return b.toString();
    }

    /** two episodes of a reason, briefly: " e.g. Агент 2 hold_line LOW_HEALTH in wave 3 [person]; ..." */
    static String examples(List<Episode> eps, String code, int sign) {
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (Episode e : eps) {
            if (!e.why().equals(code) || Integer.signum(e.rating()) != sign) continue;
            if (n++ >= 2) break;
            b.append(n == 1 ? " e.g. " : "; ").append(e.agent).append(' ').append(e.kind.equals("death") ? "died (" + e.cause + ")" : e.what + " " + e.result)
                    .append(e.wave > 0 ? " in " + e.phase + " " + e.wave : "").append(e.human != 0 ? " [person]" : "");
            if (!e.detail.isEmpty()) b.append(": ").append(e.detail.length() > 90 ? e.detail.substring(0, 90) + "..." : e.detail);
        }
        return b.toString();
    }

    static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }

    static JsonArray karmaJson(List<Karma> k) {
        JsonArray a = new JsonArray();
        for (Karma x : k) a.add(x.json());
        return a;
    }
}
