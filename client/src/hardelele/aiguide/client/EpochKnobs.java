package hardelele.aiguide.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The epochs' third channel, with no game in it: the fight's thresholds tried between epochs by a plain search, one at
 * a time. An epoch runs with one of them moved a step from the best known; its reward against the best decides: as good
 * or better, kept (and the next step goes the same way); worse, put back (the other way next, then the next one).
 * Every move is written down (history). The game's side puts the values into Defense (apply).
 *
 *   retreat: health under which a defender falls back (Defense.LOW: hold_line, guard_ally), 4..14, step 2
 *   leash:   how far from its post hold_line goes when the model gives no radius (Defense.LEASH), 2..8, step 1
 *   wounded: health under which an ally counts as wounded and its foes come first (Defense.WOUNDED), 8..16, step 2
 */
final class EpochKnobs {
    static final String[] NAMES = { "retreat", "leash", "wounded" };
    static final double[][] RANGE = { { 4, 14, 2 }, { 2, 8, 1 }, { 8, 16, 2 } };   // min, max, step
    static final double[] START = { 8, 4, 12 };

    final Map<String, Double> best = new LinkedHashMap<String, Double>(), now = new LinkedHashMap<String, Double>();
    double bestScore = Double.NaN;
    int param, dir = 1, flips;
    String trying;               // the threshold moved in the epoch running now (null: none, the best as it is)
    final JsonArray history = new JsonArray();

    EpochKnobs() {
        for (int i = 0; i < NAMES.length; i++) { best.put(NAMES[i], START[i]); now.put(NAMES[i], START[i]); }
    }

    static int index(String name) {
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(name)) return i;
        return -1;
    }

    /**
     * An epoch ended with this reward (the values of now): the move it tried kept or put back, the next move chosen
     * (now: what the next epoch runs with). Returns a line of what was done, for the report.
     */
    String after(int epoch, double score) {
        String done;
        if (trying == null || Double.isNaN(bestScore)) {
            bestScore = score;
            best.clear();
            best.putAll(now);
            done = "the first measure: " + EpochScore.fmt(score);
        } else {
            JsonObject h = new JsonObject();
            h.addProperty("epoch", epoch);
            h.addProperty("param", trying);
            h.addProperty("from", best.get(trying));
            h.addProperty("to", now.get(trying));
            h.addProperty("score", EpochScore.round(score));
            h.addProperty("best_before", EpochScore.round(bestScore));
            boolean kept = score >= bestScore;
            h.addProperty("kept", kept);
            history.add(h);
            if (kept) {
                done = trying + " " + EpochScore.fmt(best.get(trying)) + " -> " + EpochScore.fmt(now.get(trying)) + " kept (" + EpochScore.fmt(score) + " >= " + EpochScore.fmt(bestScore) + ")";
                bestScore = score;
                best.clear();
                best.putAll(now);
                flips = 0;
            } else {
                done = trying + " " + EpochScore.fmt(now.get(trying)) + " put back to " + EpochScore.fmt(best.get(trying)) + " (" + EpochScore.fmt(score) + " < " + EpochScore.fmt(bestScore) + ")";
                now.clear();
                now.putAll(best);
                dir = -dir;
                if (++flips >= 2) { flips = 0; param = (param + 1) % NAMES.length; }
            }
        }
        trying = null;
        for (int tries = 0; tries < 2 * NAMES.length && trying == null; tries++) {   // the next move: a step of one threshold within its range
            String n = NAMES[param];
            double[] r = RANGE[param];
            double v = best.get(n) + dir * r[2];
            if (v < r[0] || v > r[1]) {
                dir = -dir;
                if (++flips >= 2) { flips = 0; param = (param + 1) % NAMES.length; }
                continue;
            }
            now.put(n, v);
            trying = n;
        }
        return done;
    }

    double get(String n) { Double v = now.get(n); return v == null ? START[index(n)] : v; }

    /** the values in a line: "retreat 8, leash 4, wounded 12 (trying leash)" */
    String line() {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Double> e : now.entrySet()) b.append(b.length() > 0 ? ", " : "").append(e.getKey()).append(' ').append(EpochScore.fmt(e.getValue()));
        if (trying != null) b.append(" (trying ").append(trying).append(')');
        return b.toString();
    }

    /** the values into the fight (Defense's thresholds) */
    void apply() {
        Defense.LOW = (float) get("retreat");
        Defense.LEASH = (int) Math.round(get("leash"));
        Defense.WOUNDED = (float) get("wounded");
    }

    /** the fight's thresholds back to their defaults (a control epoch, no tuning) */
    static void defaults() {
        Defense.LOW = (float) START[0];
        Defense.LEASH = (int) START[1];
        Defense.WOUNDED = (float) START[2];
    }

    JsonObject json() {
        JsonObject o = new JsonObject();
        JsonObject b = new JsonObject(), n = new JsonObject();
        for (Map.Entry<String, Double> e : best.entrySet()) b.addProperty(e.getKey(), e.getValue());
        for (Map.Entry<String, Double> e : now.entrySet()) n.addProperty(e.getKey(), e.getValue());
        o.add("best", b);
        o.add("now", n);
        if (!Double.isNaN(bestScore)) o.addProperty("best_score", bestScore);
        o.addProperty("param", param);
        o.addProperty("dir", dir);
        o.addProperty("flips", flips);
        if (trying != null) o.addProperty("trying", trying);
        o.add("history", history);
        return o;
    }

    static EpochKnobs fromJson(JsonObject o) {
        EpochKnobs k = new EpochKnobs();
        if (o == null) return k;
        if (o.has("best")) for (Map.Entry<String, com.google.gson.JsonElement> e : o.getAsJsonObject("best").entrySet()) if (index(e.getKey()) >= 0) k.best.put(e.getKey(), e.getValue().getAsDouble());
        if (o.has("now")) for (Map.Entry<String, com.google.gson.JsonElement> e : o.getAsJsonObject("now").entrySet()) if (index(e.getKey()) >= 0) k.now.put(e.getKey(), e.getValue().getAsDouble());
        k.bestScore = o.has("best_score") ? o.get("best_score").getAsDouble() : Double.NaN;
        k.param = (int) CaseVec.num(o, "param", 0);
        k.dir = (int) CaseVec.num(o, "dir", 1);
        k.flips = (int) CaseVec.num(o, "flips", 0);
        k.trying = o.has("trying") ? o.get("trying").getAsString() : null;
        if (o.has("history") && o.get("history").isJsonArray()) for (com.google.gson.JsonElement e : o.getAsJsonArray("history")) k.history.add(e);
        return k;
    }
}
