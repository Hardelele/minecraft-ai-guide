package hardelele.aiguide.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;

/**
 * A run of the waves mode in numbers: how far it got, who killed what, whom the crew lost, how long; one JSON line a
 * run in the world's waves-runs.jsonl (and the bench's report, with its own fields added). No game here (WavesTest
 * checks the format). SCHEMA goes up when a field changes meaning.
 */
final class WaveReport {
    static final int SCHEMA = 1;

    static JsonObject run(WaveCycle c, String world, String source, boolean peaceful, int spawned, int removed, List<String> errors) {
        JsonObject o = new JsonObject();
        o.addProperty("schema", SCHEMA);
        o.addProperty("world", world);
        o.addProperty("source", source);
        o.addProperty("level", c.level);
        o.addProperty("mult", c.mult);
        o.addProperty("started", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new java.util.Date(c.startedMs)));
        o.addProperty("ended_by", c.reason == null ? "running" : c.reason);
        o.addProperty("seconds", c.runTicks / 20);
        o.addProperty("wave_reached", c.wave);
        o.addProperty("waves_cleared", c.cleared);
        o.addProperty("best_before", c.best);
        o.addProperty("kills_total", c.kills);
        o.add("kills", counts(c.killsBy));
        o.addProperty("losses_total", c.losses);
        o.add("losses", counts(c.lossesBy));
        o.addProperty("spawned", spawned);
        o.addProperty("removed_at_end", removed);
        o.addProperty("peaceful_world", peaceful);
        o.addProperty("burn", c.cfg.burn);
        JsonArray ws = new JsonArray();
        for (WaveCycle.Wave w : c.waves) {
            JsonObject x = new JsonObject();
            x.addProperty("n", w.n);
            x.addProperty("planned", w.planned);
            x.addProperty("spawned", w.spawned);
            x.addProperty("killed", w.killed);
            x.addProperty("gone", w.gone);
            x.addProperty("losses", w.losses);
            x.addProperty("seconds", w.ticks / 20);
            x.addProperty("timeout", w.timeout);
            ws.add(x);
        }
        o.add("waves", ws);
        JsonArray er = new JsonArray();
        if (errors != null) for (String e : errors) er.add(new com.google.gson.JsonPrimitive(e));
        o.add("errors", er);
        return o;
    }

    static JsonObject counts(Map<String, Integer> m) {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, Integer> e : m.entrySet()) o.addProperty(e.getKey(), e.getValue());
        return o;
    }

    /** the run as one line of waves-runs.jsonl */
    static String line(JsonObject o) { return new Gson().toJson(o); }

    /** the best of the runs before (the most waves cleared) from the lines of waves-runs.jsonl; a broken line is skipped */
    static int best(List<String> lines) {
        int best = 0;
        if (lines == null) return 0;
        for (String l : lines) {
            if (l == null || l.trim().isEmpty()) continue;
            try {
                JsonElement e = new JsonParser().parse(l);
                if (e.isJsonObject() && e.getAsJsonObject().has("waves_cleared")) best = Math.max(best, e.getAsJsonObject().get("waves_cleared").getAsInt());
            } catch (RuntimeException ex) {
                // a line cut short (the game closed while writing): not counted
            }
        }
        return best;
    }
}
