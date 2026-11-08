package hardelele.aiguide.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A run of epochs, with no game in it: where it is kept, which epoch comes next, and what is done when one ends. One
 * epoch is one run of waves from the same world (put back from its copy before each: tools/epochs.py) and the same
 * seed; the learning track's epochs carry what was learned to the next (its memory of cases with their grades, the
 * minds' lessons, the fight's thresholds), the control track's do not (the same seeds, nothing carried): the two
 * take turns, learning first. Epochs (the game's side) gathers an epoch's Outcome and calls close().
 *
 *   epochs/RUN/run.json            the run's config (its first one stays)
 *   epochs/RUN/state.json          how far each track is, the best of each, every epoch's line
 *   epochs/RUN/report.json         the report by epochs (both tracks)
 *   epochs/RUN/learn/              what the learning track carries: lessons.json, knobs.json, crew-cases.jsonl, crew-grades.jsonl
 *   epochs/RUN/epoch-N/            a learning epoch: run.json (waves-runs' line), combat.json, episodes.jsonl, stocks.json,
 *                                  score.json (reward, karma, reasons), lessons.json (the call and what it wrote), knobs.json,
 *                                  timeline.jsonl (the rated moments, for the montage), crew-minds.jsonl (this epoch's lines)
 *   epochs/RUN/control/epoch-N/    a control epoch: the same, no lessons, no knobs
 */
final class EpochRun {
    static final String LEARN = "learn", CONTROL = "control";
    static final String[] CHANNELS = { "cases", "lessons", "knobs" };
    static final int SCHEMA = 1;

    /** the run's config (-Daiguide.epochs.*) */
    static final class Config {
        String run = "epochs", level = "normal", guard = "mind";
        int count = 10, minutes = 20, crew = 4;
        long seed;                 // the waves' seed of epoch 1 (0: the world's)
        boolean grow;              // false: the same seed every epoch; true: seed + epoch - 1
        boolean control = true;    // the control track too
        final Set<String> learn = new LinkedHashSet<String>(Arrays.asList(CHANNELS));

        JsonObject json() {
            JsonObject o = new JsonObject();
            o.addProperty("schema", SCHEMA);
            o.addProperty("run", run);
            o.addProperty("count", count);
            o.addProperty("level", level);
            o.addProperty("seed", String.valueOf(seed));   // (a string: a 64-bit seed's digits stay whole)
            o.addProperty("seeds", grow ? "grow" : "same");
            o.addProperty("control", control);
            o.addProperty("minutes", minutes);
            o.addProperty("crew", crew);
            o.addProperty("guard", guard);
            JsonArray l = new JsonArray();
            for (String s : learn) l.add(new com.google.gson.JsonPrimitive(s));
            o.add("learn", l);
            return o;
        }

        static Config fromJson(JsonObject o) {
            Config c = new Config();
            c.run = CaseVec.str(o, "run");
            c.count = (int) CaseVec.num(o, "count", 10);
            c.level = CaseVec.str(o, "level");
            try { c.seed = Long.parseLong(CaseVec.str(o, "seed").trim()); } catch (NumberFormatException e) { c.seed = 0; }
            c.grow = "grow".equals(CaseVec.str(o, "seeds"));
            c.control = !o.has("control") || o.get("control").getAsBoolean();
            c.minutes = (int) CaseVec.num(o, "minutes", 20);
            c.crew = (int) CaseVec.num(o, "crew", 4);
            c.guard = o.has("guard") ? CaseVec.str(o, "guard") : "mind";
            if (o.has("learn") && o.get("learn").isJsonArray()) {
                c.learn.clear();
                for (JsonElement e : o.getAsJsonArray("learn")) c.learn.add(e.getAsString());
            }
            return c;
        }

        /** "cases,lessons" -> the channels (unknown words left out; "none" or "" none) */
        void learn(String s) {
            learn.clear();
            if (s == null) return;
            for (String x : s.split("[,; ]+")) for (String c : CHANNELS) if (c.equals(x.trim())) learn.add(c);
        }
    }

    final File dir;
    final Config cfg;
    JsonObject state;
    final List<String> notes = new ArrayList<String>();

    EpochRun(File dir, Config cfg, JsonObject state) { this.dir = dir; this.cfg = cfg; this.state = state; }

    /** the run in this folder (root/RUN): its config the first one written (a different one asked for is noted), its state read back */
    static EpochRun open(File root, Config want) {
        File d = new File(root, want.run);
        d.mkdirs();
        File rc = new File(d, "run.json");
        Config cfg = want;
        List<String> notes = new ArrayList<String>();
        JsonObject have = read(rc);
        if (have != null) {
            cfg = Config.fromJson(have);
            if (!have.equals(want.json())) notes.add("the run's config is the one it began with; asked now: " + want.json());
        } else write(rc, want.json());
        JsonObject st = read(new File(d, "state.json"));
        if (st == null) st = new JsonObject();
        for (String t : new String[] { LEARN, CONTROL }) if (!st.has(t)) { JsonObject x = new JsonObject(); x.addProperty("done", 0); x.addProperty("best", 0); st.add(t, x); }
        if (!st.has("epochs")) st.add("epochs", new JsonArray());
        EpochRun r = new EpochRun(d, cfg, st);
        r.notes.addAll(notes);
        return r;
    }

    int done(String track) { return (int) CaseVec.num(state.getAsJsonObject(track), "done", 0); }

    int best(String track) { return (int) CaseVec.num(state.getAsJsonObject(track), "best", 0); }

    /** the whole run is through: each track its count of epochs */
    boolean finished() { return done(LEARN) >= cfg.count && (!cfg.control || done(CONTROL) >= cfg.count); }

    /** the track of the next epoch: learning and control in turn, learning first */
    String nextTrack() {
        if (!cfg.control || done(LEARN) < cfg.count && done(LEARN) <= done(CONTROL)) return LEARN;
        return CONTROL;
    }

    int nextEpoch(String track) { return done(track) + 1; }

    /** the waves' seed of an epoch (the same for both tracks): the run's, or growing by one an epoch */
    long seed(int epoch, long worldSeed) {
        long base = cfg.seed != 0 ? cfg.seed : worldSeed;
        return cfg.grow ? base + epoch - 1 : base;
    }

    File epochDir(String track, int n) { return track.equals(CONTROL) ? new File(new File(dir, "control"), "epoch-" + n) : new File(dir, "epoch-" + n); }

    File learnDir() { File f = new File(dir, LEARN); f.mkdirs(); return f; }

    /** a channel of learning is on for this track (the control track has none) */
    boolean learns(String track, String channel) { return track.equals(LEARN) && cfg.learn.contains(channel); }

    Lessons lessons() { return Lessons.fromJson(read(new File(learnDir(), "lessons.json"))); }

    EpochKnobs knobs() { return EpochKnobs.fromJson(read(new File(learnDir(), "knobs.json"))); }

    // ---- an epoch ended ------------------------------------------------------------------------------------

    /** what an epoch came to, gathered by the game's side (Epochs) or a test */
    static final class Outcome {
        String track = LEARN;
        int epoch = 1;
        long seed, startMs, endMs, startTick;
        String recording = "";                                             // the WorldRec recording's name, "" none
        JsonObject run;                                                    // WaveReport's line
        Map<String, Defense.Tally> fight = new LinkedHashMap<String, Defense.Tally>();
        List<EpochScore.Episode> episodes = new ArrayList<EpochScore.Episode>();
        List<EpochScore.Stock> stocks = new ArrayList<EpochScore.Stock>();
        List<Integer> holes = new ArrayList<Integer>();                    // open wall cells at each wave's start
        List<JsonObject> labels = new ArrayList<JsonObject>();             // the person's ratings (actions.jsonl lines)
        boolean restored;                                                  // the world was put back from its copy before it
        String knobs = "", lessonsUsed = "";
        int lessonsShown;
        List<String> minds = new ArrayList<String>();                      // this epoch's crew-minds.jsonl lines
        List<String> notes = new ArrayList<String>();
    }

    /** what close() did */
    static final class Closed {
        EpochScore.Reward reward;
        List<EpochScore.Karma> karma;
        List<Lessons.Lesson> fresh = new ArrayList<Lessons.Lesson>(), pushed = new ArrayList<Lessons.Lesson>();
        int[] usage;
        String knobs = "", error;
        int graded, humans;
        JsonObject line;
    }

    /**
     * An epoch ended: its episodes rated (the rules, then the person's ratings over them), the karma and the reward,
     * all written to its folder; then, on the learning track, what is carried: the cases' grades into the memory of
     * cases (book), one call of the model for the lessons (model; null: none), the next thresholds. The state and the
     * report after it. A failed model call is noted and the lessons stay as they were.
     */
    Closed close(Outcome o, Mind.Model model, CaseBook book) {
        Closed c = new Closed();
        EpochScore.rateAll(o.episodes);
        c.humans = EpochScore.applyLabels(o.episodes, o.labels);
        c.karma = EpochScore.karma(o.stocks);
        c.reward = EpochScore.reward(o.run, o.fight, o.holes, o.episodes, c.karma);
        int bestBefore = best(o.track);
        File d = epochDir(o.track, o.epoch);
        d.mkdirs();
        write(new File(d, "run.json"), o.run == null ? new JsonObject() : o.run);
        JsonObject fight = new JsonObject();
        for (Map.Entry<String, Defense.Tally> e : o.fight.entrySet()) fight.add(e.getKey(), e.getValue().json());
        write(new File(d, "combat.json"), fight);
        StringBuilder eps = new StringBuilder(), tl = new StringBuilder();
        for (EpochScore.Episode e : o.episodes) {
            eps.append(e.json()).append('\n');
            if (e.rating() != 0) {
                JsonObject t = new JsonObject();
                t.addProperty("t", e.t);
                t.addProperty("ms", e.ms);
                t.addProperty("agent", e.agent);
                t.addProperty("rating", e.rating());
                t.addProperty("why", e.why());
                t.addProperty("human", e.human != 0);
                t.addProperty("wave", e.wave);
                if (e.at != null) t.add("at", Labels.ints(e.at));
                tl.append(t).append('\n');
            }
        }
        text(new File(d, "episodes.jsonl"), eps.toString());
        text(new File(d, "timeline.jsonl"), tl.toString());
        JsonArray st = new JsonArray();
        for (EpochScore.Stock s : o.stocks) st.add(s.json());
        write(new File(d, "stocks.json"), st);
        if (!o.minds.isEmpty()) text(new File(d, "crew-minds.jsonl"), String.join("\n", o.minds) + "\n");
        JsonObject score = new JsonObject();
        score.add("reward", c.reward.json());
        score.add("karma", EpochScore.karmaJson(c.karma));
        score.addProperty("karma_total", EpochScore.round(EpochScore.sum(c.karma)));
        JsonArray rs = new JsonArray();
        for (Object[] x : EpochScore.reasons(o.episodes)) {
            JsonObject r = new JsonObject();
            r.addProperty("why", (String) x[0]);
            r.addProperty("good", (Integer) x[1]);
            r.addProperty("bad", (Integer) x[2]);
            rs.add(r);
        }
        score.add("reasons", rs);
        score.add("holes", new com.google.gson.Gson().toJsonTree(o.holes));
        score.addProperty("human_rated", c.humans);
        score.addProperty("start_tick", o.startTick);   // the timeline's ticks less this: from the epoch's start (and its recording's)
        score.addProperty("start_ms", o.startMs);
        score.addProperty("end_ms", o.endMs);
        if (!o.recording.isEmpty()) score.addProperty("recording", o.recording);
        write(new File(d, "score.json"), score);

        if (learns(o.track, "cases") && book != null) {   // (a): the grades into the memory of cases (a person's over the rules')
            for (EpochScore.Episode e : o.episodes)
                if (e.kind.equals("case") && e.rating() != 0) c.graded += book.grade(e.t, e.agent, e.what, e.rating(), e.human != 0, e.why(), o.epoch) > 0 ? 1 : 0;
        }
        String summary = EpochScore.summary(o.epoch, o.track, o.run, c.reward, o.episodes, c.karma, bestBefore, o.knobs);
        Lessons ls = lessons();
        if (learns(o.track, "lessons") && model != null) {   // (b): one call: the lessons for the next epoch
            String body = Lessons.request(summary, ls);
            JsonObject lj = new JsonObject();
            lj.addProperty("summary", summary);
            lj.addProperty("request_chars", body.length());
            try {
                String resp = model.complete(body);
                c.usage = Lessons.usage(resp, body.length());
                c.fresh = Lessons.parse(resp);
                c.pushed = ls.merge(c.fresh, o.epoch);
                write(new File(learnDir(), "lessons.json"), ls.json());
                lj.addProperty("response", resp.length() > 20000 ? resp.substring(0, 20000) : resp);
            } catch (Exception e) {
                c.error = "the lessons' model failed: " + e;
                lj.addProperty("error", c.error);
            }
            JsonArray f = new JsonArray(), p = new JsonArray();
            for (Lessons.Lesson l : c.fresh) f.add(l.json());
            for (Lessons.Lesson l : c.pushed) p.add(l.json());
            lj.add("written", f);
            lj.add("pushed_out", p);
            lj.add("kept", ls.json().get("lessons"));
            if (c.usage != null) {
                JsonObject u = new JsonObject();
                u.addProperty("prompt", c.usage[0]);
                u.addProperty("completion", c.usage[1]);
                u.addProperty("cached", c.usage[2]);
                u.addProperty("estimated", c.usage[3] == 1);
                lj.add("usage", u);
            }
            write(new File(d, "lessons.json"), lj);
        } else text(new File(d, "summary.txt"), summary);
        if (learns(o.track, "knobs")) {   // (c): the thresholds' next step
            EpochKnobs k = knobs();
            c.knobs = k.after(o.epoch, c.reward.total);
            write(new File(learnDir(), "knobs.json"), k.json());
            JsonObject kj = new JsonObject();
            kj.addProperty("used", o.knobs);
            kj.addProperty("done", c.knobs);
            kj.addProperty("next", k.line());
            write(new File(d, "knobs.json"), kj);
        }

        // the state and the report
        int cleared = o.run == null ? 0 : (int) CaseVec.num(o.run, "waves_cleared", 0);
        JsonObject t = state.getAsJsonObject(o.track);
        t.addProperty("done", Math.max(done(o.track), o.epoch));
        t.addProperty("best", Math.max(bestBefore, cleared));
        JsonObject line = new JsonObject();
        line.addProperty("track", o.track);
        line.addProperty("epoch", o.epoch);
        line.addProperty("seed", String.valueOf(o.seed));
        line.addProperty("waves_cleared", cleared);
        line.addProperty("kills", o.run == null ? 0 : (int) CaseVec.num(o.run, "kills_total", 0));
        line.addProperty("losses", o.run == null ? 0 : (int) CaseVec.num(o.run, "losses_total", 0));
        line.addProperty("ended_by", o.run == null ? "" : CaseVec.str(o.run, "ended_by"));
        line.addProperty("seconds", o.run == null ? 0 : (int) CaseVec.num(o.run, "seconds", 0));
        line.addProperty("reward", EpochScore.round(c.reward.total));
        line.addProperty("karma", EpochScore.round(EpochScore.sum(c.karma)));
        EpochScore.Karma w = EpochScore.worst(c.karma);
        if (w != null) { line.addProperty("karma_worst", w.code); line.addProperty("karma_worst_points", w.points); }
        int good = 0, bad = 0;
        for (EpochScore.Episode e : o.episodes) { if (e.rating() > 0) good++; else if (e.rating() < 0) bad++; }
        line.addProperty("good", good);
        line.addProperty("bad", bad);
        line.addProperty("human_rated", c.humans);
        line.addProperty("lessons_used", o.lessonsShown);
        line.addProperty("lessons_after", o.track.equals(LEARN) ? ls.list.size() : 0);
        if (c.usage != null) { line.addProperty("lesson_prompt_tokens", c.usage[0]); line.addProperty("lesson_completion_tokens", c.usage[1]); }
        line.addProperty("cases_graded", c.graded);
        line.addProperty("knobs", o.knobs);
        if (!c.knobs.isEmpty()) line.addProperty("knobs_after", c.knobs);
        line.addProperty("world_restored", o.restored);
        if (c.error != null) line.addProperty("error", c.error);
        if (!o.notes.isEmpty()) line.add("notes", new com.google.gson.Gson().toJsonTree(o.notes));
        state.getAsJsonArray("epochs").add(line);
        write(new File(dir, "state.json"), state);
        write(new File(dir, "report.json"), report());
        c.line = line;
        return c;
    }

    /** the report: the config, every epoch's line in order, each track's waves by epoch, the lessons now */
    JsonObject report() {
        JsonObject o = new JsonObject();
        o.addProperty("schema", SCHEMA);
        o.add("config", cfg.json());
        o.add("epochs", state.getAsJsonArray("epochs"));
        JsonObject series = new JsonObject();
        for (String tr : new String[] { LEARN, CONTROL }) {
            JsonArray a = new JsonArray();
            for (JsonElement e : state.getAsJsonArray("epochs")) {
                JsonObject l = e.getAsJsonObject();
                if (CaseVec.str(l, "track").equals(tr)) a.add(l.get("waves_cleared"));
            }
            series.add(tr, a);
        }
        o.add("waves_by_epoch", series);
        JsonObject best = new JsonObject();
        best.addProperty(LEARN, best(LEARN));
        best.addProperty(CONTROL, best(CONTROL));
        o.add("best", best);
        o.add("lessons", lessons().json().get("lessons"));
        o.addProperty("finished", finished());
        if (!notes.isEmpty()) o.add("notes", new com.google.gson.Gson().toJsonTree(notes));
        return o;
    }

    /** waves cleared by epoch of a track, in order */
    List<Integer> series(String track) {
        List<Integer> out = new ArrayList<Integer>();
        for (JsonElement e : state.getAsJsonArray("epochs")) {
            JsonObject l = e.getAsJsonObject();
            if (CaseVec.str(l, "track").equals(track)) out.add((int) CaseVec.num(l, "waves_cleared", 0));
        }
        return out;
    }

    // ---- files ---------------------------------------------------------------------------------------------

    static JsonObject read(File f) {
        if (!f.isFile()) return null;
        try {
            JsonElement e = new JsonParser().parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    static void write(File f, JsonElement o) {
        text(f, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(o) + "\n");
    }

    /** a file written whole (to a new name, then moved over the old: a cut-off write leaves the old one) */
    static void text(File f, String s) {
        try {
            File dir = f.getAbsoluteFile().getParentFile();
            if (dir != null) dir.mkdirs();
            File tmp = new File(dir, f.getName() + ".tmp");
            Files.write(tmp.toPath(), s.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new RuntimeException("not written: " + f + ": " + e, e);
        }
    }

    /** the lines of a file ("" lines left out; none when it is not there) */
    static List<String> lines(File f) {
        List<String> out = new ArrayList<String>();
        if (!f.isFile()) return out;
        try {
            for (String l : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) if (!l.trim().isEmpty()) out.add(l);
        } catch (Exception e) {
            // none
        }
        return out;
    }

    // ---- what the screen shows (EpochsHud) -----------------------------------------------------------------

    /** the epoch now, for the screen: a copy, safe on any thread */
    static final class View {
        static final View OFF = new View();
        boolean on;
        String track = LEARN;
        int epoch, count, best, lessons;
        double karma;
        String karmaWorst;                 // the karma's worst code, or null
        List<String> top = new ArrayList<String>();   // the lessons to show (2-3), most confirmed first
        List<Integer> learn = new ArrayList<Integer>(), control = new ArrayList<Integer>();
        long closedMs;                     // the epoch ended (the card "Чему научились" then)
        int cleared;
    }

    static volatile View view = View.OFF;

    /** a rated moment for the screen: who, good or bad, why, when */
    static final class Pop {
        final int entity;
        final int rating;
        final String why;
        final long ms;
        Pop(int entity, int rating, String why, long ms) { this.entity = entity; this.rating = rating; this.why = why; this.ms = ms; }
    }

    static final java.util.concurrent.ConcurrentLinkedDeque<Pop> pops = new java.util.concurrent.ConcurrentLinkedDeque<Pop>();

    static void pop(int entity, int rating, String why, long ms) {
        pops.addLast(new Pop(entity, rating, why, ms));
        while (pops.size() > 32) pops.pollFirst();
    }

    /** the view of this run with the epoch on now (or just over) */
    View viewOf(String track, int epoch, boolean on, long closedMs, int cleared) {
        View v = new View();
        v.on = on;
        v.track = track;
        v.epoch = epoch;
        v.count = cfg.count;
        v.best = Math.max(best(track), cleared);
        Lessons ls = lessons();
        v.lessons = track.equals(LEARN) ? ls.list.size() : 0;
        List<Lessons.Lesson> l = new ArrayList<Lessons.Lesson>(ls.list);
        java.util.Collections.sort(l, (p, q) -> p.epoch != q.epoch ? q.epoch - p.epoch : q.seen - p.seen);   // the newest first: what this epoch taught
        for (int i = 0; i < Math.min(3, l.size()); i++) v.top.add(l.get(i).text);
        v.learn = series(LEARN);
        v.control = series(CONTROL);
        v.closedMs = closedMs;
        v.cleared = cleared;
        JsonArray eps = state.getAsJsonArray("epochs");
        for (int i = eps.size() - 1; i >= 0; i--) {
            JsonObject x = eps.get(i).getAsJsonObject();
            if (!CaseVec.str(x, "track").equals(track)) continue;
            v.karma = CaseVec.num(x, "karma", 0);
            v.karmaWorst = x.has("karma_worst") ? x.get("karma_worst").getAsString() : null;
            break;
        }
        return v;
    }
}
