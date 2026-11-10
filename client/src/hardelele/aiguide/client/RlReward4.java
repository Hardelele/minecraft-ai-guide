package hardelele.aiguide.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The reward v4 (docs/rl/REWARD-v4.md) with no game in it: the outcomes only and the general causal credit, from a
 * step's stream of events (rl/events.json). The mirror of tools/rl/reward_core.py, step for step (RlTest checks it on
 * the cases tools/tests/test_rl_exploits.py writes): RlEnv gives frames {t, dt, phase, events}, takes each agent's
 * points by component at its next decision and puts the move events (which earlier decision a credit belongs to)
 * into info.events.
 */
final class RlReward4 {
    /** rl/reward-v4.json's parameters as built in (RlTest checks them against the file) */
    static final String DEFAULTS = "{\"outcomes\":{\"noop\":-1.0,\"goal\":5.0,\"human\":2.0,\"crew.damage\":-0.01,\"crew.death\":-1.0,\"base.breach\":-0.5,\"base.mob_seconds\":-0.02},"
            + "\"death\":{\"accident_x\":1.5,\"accident_causes\":[\"accident\",\"starve\",\"fall\",\"lava\",\"fire\",\"drown\"],\"base_r\":10.0,\"far_per_block\":0.01,\"far_cap\":1.0,\"finds_soften\":0.5,\"soften_cap\":0.5},"
            + "\"afk\":{\"rate\":0.01,\"grace_s\":30.0,\"ramp_s\":120.0,\"active_s\":20.0},\"sight\":{\"near_r\":6.0},"
            + "\"threat\":{\"agent_hits\":3.0,\"kill\":1.0,\"deter\":0.5,\"delay_tau_s\":5.0,\"dodge_s\":3.0,\"w\":{\"kill\":2.0,\"blocked\":1.0,\"stuck\":1.0,\"fell\":1.0,\"detour\":1.0,\"cover\":1.0,\"moved\":1.0}},"
            + "\"survive\":{\"hp_low\":6.0,\"hp_safe\":14.0,\"share\":0.1},\"scout\":{\"share\":0.3},\"wasted_day\":{\"share\":0.25},"
            + "\"forfeit\":{\"goal\":1.0,\"scales\":1.0,\"min_s\":60.0,\"cap\":20.0},\"scale_move\":0.5,"
            + "\"calm_phases\":[\"day\",\"prep\",\"break\"],\"no_curiosity_phases\":[\"wave\",\"night\"]}";

    static JsonObject defaults() { return new JsonParser().parse(DEFAULTS).getAsJsonObject(); }

    static final class Ag {
        double x, y, z, hp = 20, baseDist, idle, trip;
        boolean alive = true;
        List<Long> danger;
        Long dangerAt;
    }

    static final class Dec {
        final String agent, kind;
        final double t;
        boolean productive;
        Double end;
        Dec(String agent, double t, String kind) { this.agent = agent; this.t = t; this.kind = kind; }
    }

    static final class Contrib {
        final String agent, reason;
        final double w;
        Contrib(String agent, String reason, double w) { this.agent = agent; this.reason = reason; this.w = w; }
    }

    static final class Threat {
        final List<String> targets = new ArrayList<String>();
        double dmg;
        final Map<Long, Contrib> contrib = new LinkedHashMap<Long, Contrib>();
        final Map<String, Double> taken = new HashMap<String, Double>();
        double until;
        boolean inside;
    }

    static final class Find {
        final long decision;
        final String agent;
        final double value;
        Find(long decision, String agent, double value) { this.decision = decision; this.agent = agent; this.value = value; }
    }

    final JsonObject p;
    final Map<String, Double> sw;
    final Map<String, Double> factors;   // null: every scale on
    final Map<String, Double> o = new HashMap<String, Double>();
    final Set<String> calm = new HashSet<String>(), noCur = new HashSet<String>(), accident = new HashSet<String>();

    final Map<String, Ag> ag = new LinkedHashMap<String, Ag>();
    final Map<Long, Dec> dec = new HashMap<Long, Dec>();
    final Map<String, Long> cur = new HashMap<String, Long>();
    final Map<String, List<double[]>> recent = new HashMap<String, List<double[]>>();   // agent -> { t, decision }
    List<Long> day = new ArrayList<Long>();
    Double calmEnd;
    Boolean prevCalm;
    final Map<Long, Threat> threats = new LinkedHashMap<Long, Threat>();
    final Map<String, Find> finds = new HashMap<String, Find>();
    final Map<String, Map<String, Double>> open = new LinkedHashMap<String, Map<String, Double>>();
    final Map<String, Double> episode = new LinkedHashMap<String, Double>();
    double total, t;
    double gain;   // the scales' positive points paid so far (the crew): the forfeit's rate
    String phase = "day";
    List<JsonObject> moves = new ArrayList<JsonObject>();

    /** params: rl/reward-v4.json's; sw: the scales' parts' weights (rl/reward.json); factors: the scenario's (null: all 1) */
    RlReward4(JsonObject params, Map<String, Double> sw, Map<String, Double> factors) {
        this.p = params;
        this.sw = sw;
        this.factors = factors;
        for (Map.Entry<String, JsonElement> e : p.getAsJsonObject("outcomes").entrySet()) o.put(e.getKey(), e.getValue().getAsDouble());
        strings(calm, p.has("calm_phases") ? p.getAsJsonArray("calm_phases") : null, "day", "prep", "break");
        strings(noCur, p.has("no_curiosity_phases") ? p.getAsJsonArray("no_curiosity_phases") : null, "wave", "night");
        strings(accident, sec("death").getAsJsonArray("accident_causes"));
    }

    /** the game's: rl/reward.json's scale weights, the scenario's factors */
    static RlReward4 of(JsonObject params, Map<String, Double> weights, RlSpec.Scenario s) {
        Map<String, Double> sw = new LinkedHashMap<String, Double>();
        for (Map.Entry<String, Double> e : weights.entrySet()) if (RlSpec.isScale(RlSpec.component(e.getKey()))) sw.put(e.getKey(), e.getValue());
        return new RlReward4(params == null ? defaults() : params, sw, s == null ? null : new LinkedHashMap<String, Double>(s.scales));
    }

    static void strings(Set<String> into, JsonArray a, String... dflt) {
        if (a == null) { for (String s : dflt) into.add(s); return; }
        for (JsonElement e : a) into.add(e.getAsString());
    }

    JsonObject sec(String k) { return p.getAsJsonObject(k); }
    double num(String sec, String k) { return p.getAsJsonObject(sec).get(k).getAsDouble(); }
    double tw(String k) { return sec("threat").getAsJsonObject("w").has(k) ? sec("threat").getAsJsonObject("w").get(k).getAsDouble() : 0; }

    // ---- helpers -------------------------------------------------------------------------------------------------

    double factor(String key) {
        String comp = RlSpec.component(key);
        if (!RlSpec.isScale(comp) || factors == null) return 1;
        if (factors.containsKey(key)) return factors.get(key);
        Double f = factors.get(comp);
        return f == null ? 0 : f;
    }

    Ag state(String a) {
        Ag s = ag.get(a);
        if (s == null) ag.put(a, s = new Ag());
        return s;
    }

    List<String> alive() {
        List<String> l = new ArrayList<String>();
        for (Map.Entry<String, Ag> e : ag.entrySet()) if (e.getValue().alive) l.add(e.getKey());
        return l;
    }

    void pay(String a, String comp, double pts) {
        if (pts == 0) return;
        Map<String, Double> m = open.get(a);
        if (m == null) open.put(a, m = new LinkedHashMap<String, Double>());
        m.put(comp, (m.containsKey(comp) ? m.get(comp) : 0) + pts);
        episode.put(comp, (episode.containsKey(comp) ? episode.get(comp) : 0) + pts);
        total += pts;
    }

    void progress(String a) { state(a).idle = 0; }

    void productive(Long d) { Dec x = d == null ? null : dec.get(d); if (x != null) x.productive = true; }

    void move(String a, String comp, double pts, long d) {
        Dec info = dec.get(d);
        JsonObject m = new JsonObject();
        m.addProperty("type", "reward");
        m.addProperty("agent", a);
        m.addProperty("key", comp);
        m.addProperty("points", pts);
        m.addProperty("move", 1.0);
        JsonObject c = new JsonObject();
        c.addProperty("decision_id", d);
        c.addProperty("agent", info != null ? info.agent : a);
        c.addProperty("tick", Math.round((info != null ? info.t : t) * 20));
        c.addProperty("kind", info != null ? info.kind : "");
        c.addProperty("via", "credit");
        JsonArray ch = new JsonArray();
        ch.add(c);
        m.add("chain", ch);
        moves.add(m);
    }

    void transfer(String from, String to, String comp, double pts, long d) {
        if (pts == 0) return;
        pay(from, comp, -pts);
        pay(to, comp, pts);
        move(to, comp, pts, d);
    }

    boolean witness(String a, JsonArray seen) {
        if (seen == null || seen.size() == 0) return false;
        for (JsonElement e : seen) if (e.getAsString().equals(a)) return true;
        Ag me = ag.get(a);
        if (me == null) return false;
        double r = num("sight", "near_r"), r2 = r * r;
        for (JsonElement e : seen) {
            Ag x = ag.get(e.getAsString());
            if (x != null && (x.x - me.x) * (x.x - me.x) + (x.y - me.y) * (x.y - me.y) + (x.z - me.z) * (x.z - me.z) <= r2) return true;
        }
        return false;
    }

    static double afkIntegral(double x, double rate, double grace, double ramp) {
        if (x <= grace) return 0;
        if (ramp <= 0) return rate * (x - grace);
        if (x < grace + ramp) return rate * (x - grace) * (x - grace) / (2 * ramp);
        return rate * (ramp / 2 + (x - grace - ramp));
    }

    // ---- losses and the empty day --------------------------------------------------------------------------------

    void loss(String a, String comp, double pts) {
        pay(a, comp, pts);
        if (pts >= 0 || calm.contains(phase) || calmEnd == null) return;
        double share = num("wasted_day", "share");
        List<long[]> ids = new ArrayList<long[]>();
        List<Double> durs = new ArrayList<Double>();
        double tot = 0;
        for (Long d : day) {
            Dec info = dec.get(d);
            if (info.productive) continue;
            double end = info.end != null ? info.end : calmEnd;
            double dur = Math.min(end, calmEnd) - info.t;
            if (dur > 0) { ids.add(new long[] { d }); durs.add(dur); }
        }
        for (Double x : durs) tot += x;
        if (tot <= 0) return;
        for (int i = 0; i < ids.size(); i++) {
            long d = ids.get(i)[0];
            double x = share * pts * durs.get(i) / tot;
            transfer(a, dec.get(d).agent, "credit.wasted_day", x, d);
        }
    }

    // ---- threats ---------------------------------------------------------------------------------------------------

    void contrib(Threat th, long d, String agent, String reason, double w) {
        if (w <= 0) return;
        Contrib c = th.contrib.get(d);
        if (c == null || c.w < w) th.contrib.put(d, new Contrib(agent, reason, w));
    }

    double value(Threat th) {
        double wd = Math.abs(o.get("crew.damage")), v = 0;
        for (String tg : th.targets) {
            if (tg.equals("base")) v = Math.max(v, (th.inside ? 0 : Math.abs(o.get("base.breach"))) + Math.abs(o.get("base.mob_seconds")) * Math.max(0, th.until - t));
            else {
                Double tk = th.taken.get(tg);
                v = Math.max(v, num("threat", "agent_hits") * th.dmg * wd - (tk == null ? 0 : tk) * wd);
            }
        }
        return Math.max(0, v);
    }

    void resolve(long mob, double share) {
        Threat th = threats.remove(mob);
        if (th == null || th.contrib.isEmpty()) return;
        double pie = share * value(th), tot = 0;
        for (Contrib c : th.contrib.values()) tot += c.w;
        if (pie <= 0 || tot <= 0) return;
        double act = num("afk", "active_s");
        for (Map.Entry<Long, Contrib> e : th.contrib.entrySet()) {
            Contrib c = e.getValue();
            double pts = pie * c.w / tot;
            String comp = "credit." + c.reason;
            pay(c.agent, comp, pts);
            move(c.agent, comp, pts, e.getKey());
            productive(e.getKey());
            Dec info = dec.get(e.getKey());
            if (info != null && t - info.t <= act) progress(c.agent);
        }
    }

    List<Long> recentOf(String a, double now) {
        double win = num("threat", "dodge_s");
        List<Long> ds = new ArrayList<Long>();
        List<double[]> r = recent.get(a);
        if (r != null) for (double[] x : r) if (x[0] >= now - win) ds.add((long) x[1]);
        if (ds.isEmpty() && cur.containsKey(a)) ds.add(cur.get(a));
        return ds;
    }

    // ---- a frame ---------------------------------------------------------------------------------------------------

    List<JsonObject> frame(JsonObject fr) {
        moves = new ArrayList<JsonObject>();
        double dt = fr.has("dt") ? fr.get("dt").getAsDouble() : 0;
        t = fr.get("t").getAsDouble();
        phase = fr.has("phase") ? fr.get("phase").getAsString() : "day";
        boolean c = calm.contains(phase);
        if (c && !Boolean.TRUE.equals(prevCalm)) { day = new ArrayList<Long>(); calmEnd = null; }
        else if (!c && Boolean.TRUE.equals(prevCalm)) calmEnd = t - dt;
        prevCalm = c;
        if (fr.has("events")) for (JsonElement el : fr.getAsJsonArray("events")) event(el.getAsJsonObject());
        endFrame(dt);
        Map<String, JsonObject> merged = new LinkedHashMap<String, JsonObject>();
        for (JsonObject m : moves) {
            String k = m.get("agent").getAsString() + "|" + m.get("key").getAsString() + "|" + m.getAsJsonArray("chain").get(0).getAsJsonObject().get("decision_id").getAsLong();
            JsonObject was = merged.get(k);
            if (was != null) was.addProperty("points", was.get("points").getAsDouble() + m.get("points").getAsDouble());
            else merged.put(k, m);
        }
        moves = new ArrayList<JsonObject>(merged.values());
        return moves;
    }

    void endFrame(double dt) {
        double low = num("survive", "hp_low"), safe = num("survive", "hp_safe");
        for (Map.Entry<String, Ag> e : ag.entrySet()) {
            String k = e.getKey();
            Ag s = e.getValue();
            if (!s.alive) { s.danger = null; continue; }
            if (s.danger == null) {
                boolean threatened = false;
                for (Threat th : threats.values()) if (th.targets.contains(k)) { threatened = true; break; }
                if (s.hp <= low && threatened) { s.danger = new ArrayList<Long>(); s.dangerAt = cur.get(k); }
            } else if (s.hp >= safe) {
                List<Long> ds = s.danger;
                if (ds.isEmpty() && s.dangerAt != null) { ds = new ArrayList<Long>(); ds.add(s.dangerAt); }
                s.danger = null;
                if (!ds.isEmpty()) {
                    double pts = num("survive", "share") * Math.abs(o.get("crew.death")) / ds.size();
                    for (Long d : ds) {
                        pay(k, "credit.survive", pts);
                        move(k, "credit.survive", pts, d);
                        productive(d);
                    }
                }
            }
        }
        double rate = num("afk", "rate"), grace = num("afk", "grace_s"), ramp = num("afk", "ramp_s");
        for (Map.Entry<String, Ag> e : ag.entrySet()) {
            Ag s = e.getValue();
            if (!s.alive || dt <= 0) continue;
            double i0 = s.idle;
            s.idle = i0 + dt;
            double pen = afkIntegral(i0 + dt, rate, grace, ramp) - afkIntegral(i0, rate, grace, ramp);
            pay(e.getKey(), "afk", -pen);
        }
    }

    static String str(JsonObject e, String k) { return e.get(k).getAsString(); }
    static double dbl(JsonObject e, String k, double d) { return e.has(k) && !e.get(k).isJsonNull() ? e.get(k).getAsDouble() : d; }

    static List<Long> ids(JsonObject e, String k) {
        List<Long> l = new ArrayList<Long>();
        if (e.has(k) && e.get(k).isJsonArray()) for (JsonElement x : e.getAsJsonArray(k)) l.add(x.getAsLong());
        return l;
    }

    JsonArray seen(JsonObject e) { return e.has("seen") && e.get("seen").isJsonArray() ? e.getAsJsonArray("seen") : null; }

    void event(JsonObject e) {
        String type = str(e, "type");
        if (type.equals("agent")) {
            Ag s = state(str(e, "agent"));
            s.x = dbl(e, "x", 0); s.y = dbl(e, "y", 0); s.z = dbl(e, "z", 0);
            s.hp = dbl(e, "hp", s.hp);
            s.alive = !e.has("alive") || e.get("alive").getAsBoolean();
            s.baseDist = dbl(e, "base_dist", 0);
            if (s.baseDist <= num("death", "base_r")) s.trip = 0;
        } else if (type.equals("decision")) {
            String k = str(e, "agent");
            state(k);
            long d = e.get("id").getAsLong();
            if (cur.containsKey(k) && dec.containsKey(cur.get(k))) dec.get(cur.get(k)).end = t;
            dec.put(d, new Dec(k, t, e.has("kind") ? str(e, "kind") : ""));
            cur.put(k, d);
            List<double[]> r = recent.get(k);
            if (r == null) recent.put(k, r = new ArrayList<double[]>());
            r.add(new double[] { t, d });
            double win = num("threat", "dodge_s");
            while (!r.isEmpty() && r.get(0)[0] < t - win) r.remove(0);
            if (calm.contains(phase)) day.add(d);
            List<Long> dg = ag.get(k).danger;
            if (dg != null) dg.add(d);
        } else if (type.equals("noop")) {
            pay(str(e, "agent"), "noop", o.get("noop"));
            JsonObject f = p.has("forfeit") && p.get("forfeit").isJsonObject() ? p.getAsJsonObject("forfeit") : null;
            if (f != null) {   // the rest of the episode given up: the open goal and the scales still to come, to the one who gave up
                boolean g = e.has("goal") && !e.get("goal").isJsonNull() && e.get("goal").getAsBoolean();
                double goal = g ? o.get("goal") * f.get("goal").getAsDouble() * ag.size() : 0;
                double left = Math.max(0, dbl(e, "left_s", 0));
                double est = Math.min(f.get("cap").getAsDouble(), f.get("scales").getAsDouble() * gain / Math.max(t, f.get("min_s").getAsDouble()) * left);
                pay(str(e, "agent"), "forfeit", -(goal + est));
            }
        } else if (type.equals("fall")) {
            for (Long mob : new ArrayList<Long>(threats.keySet())) {
                Threat th = threats.remove(mob);
                if (th.targets.contains("base")) {
                    double stay = Math.max(0, th.until - t);
                    for (String k : alive()) pay(k, "surrender", (th.inside ? 0 : o.get("base.breach")) + o.get("base.mob_seconds") * stay);
                } else {
                    double wd = Math.abs(o.get("crew.damage"));
                    for (String tg : th.targets) {
                        Ag s = ag.get(tg);
                        if (s != null && s.alive) {
                            Double tk = th.taken.get(tg);
                            double v = Math.max(0, num("threat", "agent_hits") * th.dmg * wd - (tk == null ? 0 : tk) * wd);
                            pay(tg, "surrender", -v);
                        }
                    }
                }
            }
        } else if (type.equals("goal")) {
            for (String k : new ArrayList<String>(ag.keySet())) {
                pay(k, "goal", o.get("goal"));
                progress(k);
                productive(cur.get(k));
            }
        } else if (type.equals("human")) {
            double pts = o.get("human") * e.get("rating").getAsDouble();
            pay(str(e, "agent"), "human", pts);
            if (pts > 0) progress(str(e, "agent"));
        } else if (type.equals("damage")) {
            double hp = e.get("hp").getAsDouble();
            String k = str(e, "agent");
            loss(k, "crew.damage", o.get("crew.damage") * hp);
            if (e.has("mob") && !e.get("mob").isJsonNull()) {
                Threat th = threats.get(e.get("mob").getAsLong());
                if (th != null) th.taken.put(k, (th.taken.containsKey(k) ? th.taken.get(k) : 0) + hp);
            }
        } else if (type.equals("death")) {
            String k = str(e, "agent");
            Ag s = state(k);
            double wdeath = Math.abs(o.get("crew.death"));
            double x = e.has("cause") && accident.contains(str(e, "cause")) ? num("death", "accident_x") : 1;
            double far = Math.min(num("death", "far_cap"), Math.max(0, dbl(e, "base_dist", 0) - num("death", "base_r")) * num("death", "far_per_block"));
            double soft = Math.min(num("death", "soften_cap") * wdeath, num("death", "finds_soften") * s.trip);
            loss(k, "crew.death", -(wdeath * x * (1 + far)) + soft);
            s.alive = false;
            s.trip = 0;
            s.danger = null;
        } else if (type.equals("breach")) {
            for (String k : alive()) loss(k, "base.breach", o.get("base.breach"));
            Threat th = threats.get(e.get("mob").getAsLong());
            if (th != null) { th.inside = true; th.contrib.clear(); }
        } else if (type.equals("inside")) {
            double ms = e.get("mob_s").getAsDouble();
            for (String k : alive()) loss(k, "base.mob_seconds", o.get("base.mob_seconds") * ms);
        } else if (type.equals("scale")) {
            scale(e);
        } else if (type.equals("found")) {
            String res = str(e, "res");
            if (finds.containsKey(res)) return;
            String key = e.has("key") && !e.get("key").isJsonNull() ? str(e, "key") : null;
            Double w = key == null ? null : sw.get(key);
            double v = key != null ? Math.abs(w == null ? 0 : w) * dbl(e, "amount", 1) : 0;
            long d = e.get("decision").getAsLong();
            String k = str(e, "agent");
            finds.put(res, new Find(d, k, v));
            state(k).trip += v;
            productive(d);
            progress(k);
        } else if (type.equals("threat")) {
            long mob = e.get("mob").getAsLong();
            Threat th = threats.get(mob);
            if (th == null) { threats.put(mob, th = new Threat()); th.until = t; }
            th.until = Math.max(th.until, t + dbl(e, "stay_s", 0));
            String tg = str(e, "target");
            if (!th.targets.contains(tg)) th.targets.add(tg);
            th.dmg = Math.max(th.dmg, dbl(e, "dmg", 0));
        } else if (type.equals("path_changed")) {
            Threat th = threats.get(e.get("mob").getAsLong());
            if (th == null || !e.has("on_route") || !e.get("on_route").getAsBoolean()) return;
            String eff = str(e, "effect");
            double w = tw(eff);
            if (eff.equals("detour")) {
                double dl = Math.max(0, dbl(e, "delay_s", 0));
                w *= dl > 0 ? dl / (dl + num("threat", "delay_tau_s")) : 0;
            }
            List<Long> by = known(ids(e, "by"));
            for (Long d : by) {
                String a = dec.get(d).agent;
                if (witness(a, seen(e))) contrib(th, d, a, "block", w / by.size());
            }
        } else if (type.equals("attack_failed")) {
            Threat th = threats.get(e.get("mob").getAsLong());
            if (th == null) return;
            if (str(e, "reason").equals("moved")) {
                String tg = str(e, "target");
                if (!witness(tg, seen(e))) return;
                List<Long> ds = recentOf(tg, t);
                for (Long d : ds) contrib(th, d, tg, "dodge", tw("moved") / ds.size());
                return;
            }
            List<Long> by = known(ids(e, "by"));
            for (Long d : by) {
                String a = dec.get(d).agent;
                if (witness(a, seen(e))) contrib(th, d, a, "cover", tw("cover") / by.size());
            }
        } else if (type.equals("kill")) {
            long mob = e.get("mob").getAsLong();
            Threat th = threats.get(mob);
            if (th == null) return;
            long d = e.get("decision").getAsLong();
            if (dec.containsKey(d)) contrib(th, d, str(e, "agent"), "kill", tw("kill"));
            resolve(mob, num("threat", "kill"));
        } else if (type.equals("mob_gone")) {
            resolve(e.get("mob").getAsLong(), num("threat", "deter"));
        } else if (type.equals("novel")) {
            if (noCur.contains(phase)) return;
            String key = "curiosity.novel";
            Double w = sw.get(key);
            pay(str(e, "agent"), key, (w == null ? 0 : w) * factor(key));
        } else throw new IllegalArgumentException("no event type " + type);
    }

    List<Long> known(List<Long> l) {
        List<Long> out = new ArrayList<Long>();
        for (Long d : l) if (dec.containsKey(d)) out.add(d);
        return out;
    }

    void scale(JsonObject e) {
        String key = str(e, "key");
        Double w = sw.get(key);
        if (w == null) throw new IllegalArgumentException("no scale weight " + key);
        double raw = w * e.get("delta").getAsDouble(), pts = raw * factor(key);   // the measured change: progress whether or not the scenario pays it
        if (raw == 0) return;
        boolean crew = str(e, "agent").equals("*");
        List<String> payees = new ArrayList<String>();
        if (crew) payees.addAll(alive());
        else payees.add(str(e, "agent"));
        List<Long> causes = known(ids(e, "causes"));
        Find find = e.has("res") && !e.get("res").isJsonNull() ? finds.get(str(e, "res")) : null;
        for (String k : payees) {
            pay(k, key, pts);
            if (pts > 0) gain += pts;
            if (raw <= 0) continue;
            if (!crew || causes.isEmpty()) {
                progress(k);
                productive(cur.get(k));
            }
            if (pts <= 0) continue;
            if (find != null) transfer(k, find.agent, "credit.scout", num("scout", "share") * pts, find.decision);
            else if (!causes.isEmpty()) {
                double m = p.get("scale_move").getAsDouble() * pts / causes.size();
                for (Long c : causes) transfer(k, dec.get(c).agent, "credit.scale", m, c);
            }
        }
        if (raw > 0) for (Long c : causes) { productive(c); progress(dec.get(c).agent); }
    }

    /** the agent's points since its last decision (its components into `components`, cleared) */
    double take(String agent, JsonObject components) {
        Map<String, Double> m = open.remove(agent);
        double s = 0;
        if (m == null) return 0;
        for (Map.Entry<String, Double> e : m.entrySet()) {
            s += e.getValue();
            if (components != null) components.addProperty(e.getKey(), RlReward.round(e.getValue()));
        }
        return s;
    }

    JsonObject episodeJson() {
        JsonObject j = new JsonObject();
        for (Map.Entry<String, Double> e : episode.entrySet()) j.addProperty(e.getKey(), RlReward.round(e.getValue()));
        j.addProperty("total", RlReward.round(total));
        return j;
    }
}
