package hardelele.aiguide.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The memory of cases, with no game in it: every reflex's case (the situation it began in, the reaction, how it ended)
 * kept in memory for the nearest-neighbour search and written to a journal of JSON lines (crew-cases.jsonl in the
 * world) by a thread of its own, so the tick never waits for the disk. The journal goes round: past its size the file
 * becomes crew-cases.1.jsonl (the older ones .2, .3, then gone). At a world's start the journal is read back into
 * memory (on a thread too: until then nothing is known and every advice is "don't know").
 *
 * The advice: the cases of the same reflex whose vectors are nearest to this one (CaseVec, a weighted distance), and
 * of the reactions they tried the one that most often helped; below the threshold of confidence, "don't know", and the
 * reflex does its default.
 */
final class CaseBook {
    /** one case of the journal */
    static final class Case {
        long t;
        String agent = "", reflex = "", reaction = "", result = "", escalated;
        int priority, ticks;
        CaseVec.Sit sit = new CaseVec.Sit();
        double[] vec;
        final List<String> actions = new ArrayList<String>();
        JsonObject after;
        /** the epoch's grade of it (-1 bad .. +1 good; NaN: none), and whether a person gave it (it then outweighs and overrides the automatic one) */
        double grade = Double.NaN;
        boolean human;

        /** it helped: the reaction ended OK and nothing had to go up */
        boolean ok() { return "OK".equals(result) && (escalated == null || escalated.isEmpty()); }

        /** how much it helped, 0..1: its outcome; a person's grade instead of it; an automatic grade half and half with it */
        double helped() {
            double base = ok() ? 1 : 0;
            if (Double.isNaN(grade) || grade == 0) return base;
            double g = grade > 0 ? 1 : 0;
            return human ? g : (base + g) / 2;
        }

        /** how much it counts among the neighbours: a person's grade three times an automatic one's */
        double weight() { return human && !Double.isNaN(grade) ? GRADE_HUMAN : 1; }

        String key() { return t + ":" + agent + ":" + reflex; }

        double[] vec() { if (vec == null) vec = CaseVec.vector(sit); return vec; }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("t", t);
            o.addProperty("agent", agent);
            o.addProperty("reflex", reflex);
            o.addProperty("priority", priority);
            o.add("situation", CaseVec.toJson(sit));
            o.addProperty("text", CaseVec.text(sit));
            JsonArray v = new JsonArray();   // for the offline tool (read back, the vector is made again from the situation)
            for (double x : vec()) v.add(new com.google.gson.JsonPrimitive(Math.round(x * 1000) / 1000.0));
            o.add("vec", v);
            o.addProperty("reaction", reaction);
            JsonArray a = new JsonArray();
            for (String s : actions) a.add(new com.google.gson.JsonPrimitive(s));
            o.add("actions", a);
            o.addProperty("result", result);
            o.addProperty("ticks", ticks);
            if (after != null) o.add("after", after);
            if (escalated == null) o.add("escalated", com.google.gson.JsonNull.INSTANCE);
            else o.addProperty("escalated", escalated);
            return o;
        }

        static Case fromJson(JsonObject o) {
            Case c = new Case();
            c.t = (long) CaseVec.num(o, "t", 0);
            c.agent = CaseVec.str(o, "agent");
            c.reflex = CaseVec.str(o, "reflex");
            c.priority = (int) CaseVec.num(o, "priority", 0);
            JsonElement s = o.get("situation");
            if (s != null && s.isJsonObject()) c.sit = CaseVec.fromJson(s.getAsJsonObject());
            c.reaction = CaseVec.str(o, "reaction");
            JsonElement a = o.get("actions");
            if (a != null && a.isJsonArray()) for (JsonElement e : a.getAsJsonArray()) c.actions.add(e.getAsString());
            c.result = CaseVec.str(o, "result");
            c.ticks = (int) CaseVec.num(o, "ticks", 0);
            JsonElement af = o.get("after");
            if (af != null && af.isJsonObject()) c.after = af.getAsJsonObject();
            JsonElement es = o.get("escalated");
            c.escalated = es == null || es.isJsonNull() || es.getAsString().isEmpty() ? null : es.getAsString();
            return c;
        }
    }

    /** what the memory advises: a reaction and how sure (reaction null: don't know) */
    static final class Advice {
        static final Advice UNKNOWN = new Advice(null, 0, 0, 0);
        final String reaction;
        final double confidence;   // 0..1: the reaction's helped cases over its cases plus one (three of three: 0.75)
        final int support;         // near cases with this reaction
        final double rate;         // the share of them, weighted by nearness, that helped
        Advice(String reaction, double confidence, int support, double rate) { this.reaction = reaction; this.confidence = confidence; this.support = support; this.rate = rate; }
        boolean known() { return reaction != null; }
        public String toString() { return reaction == null ? "don't know" : reaction + String.format(java.util.Locale.ROOT, " (%.2f of %d, helped %.0f%%)", confidence, support, rate * 100); }
    }

    static final String NAME = "crew-cases.jsonl", GRADES = "crew-grades.jsonl";
    /** a case a person graded counts so many times one graded by the rules (Labels: the person's word is worth more) */
    static final double GRADE_HUMAN = 3;

    final File file;          // null: in memory only
    long maxBytes = 4L << 20; // a file this big goes round
    int keep = 3;             // rotated files kept
    int cap = 6000;           // cases in memory (the oldest go)
    int k = 16;               // neighbours looked at
    double near = 0.75;       // a case nearer than this is no neighbour (similarity 0..1)
    double threshold = 0.6;   // confidence under this: don't know

    private final List<Case> cases = new ArrayList<Case>();
    private volatile boolean loaded;
    volatile int badLines, dropped;

    CaseBook(File file) { this.file = file; }

    boolean loaded() { return loaded; }

    synchronized int size() { return cases.size(); }

    /** the last n cases in memory, oldest first (a copy: for the person's rating panel) */
    synchronized List<Case> recent(int n) {
        return new ArrayList<Case>(cases.subList(Math.max(0, cases.size() - n), cases.size()));
    }

    // ---- the nearest cases and the advice -------------------------------------------------------------

    /** how alike two vectors are, 0..1: one minus the root of the weighted mean squared difference */
    static double similarity(double[] a, double[] b) {
        double d = 0, w = 0;
        for (int i = 0; i < CaseVec.DIM; i++) {
            double x = a[i] - b[i];
            d += CaseVec.WEIGHTS[i] * x * x;
            w += CaseVec.WEIGHTS[i];
        }
        return 1 - Math.sqrt(d / w);
    }

    /** a case with how near it is */
    static final class Near {
        final Case c;
        final double sim;
        Near(Case c, double sim) { this.c = c; this.sim = sim; }
    }

    /** the k cases of this reflex (null: any) nearest to v, nearest first, none further than 'near' */
    List<Near> nearest(double[] v, String reflex, int k) {
        List<Near> out = new ArrayList<Near>();
        synchronized (this) {
            for (Case c : cases) {
                if (reflex != null && !reflex.equals(c.reflex)) continue;
                double s = similarity(v, c.vec());
                if (s < near) continue;
                if (out.size() < k) { out.add(new Near(c, s)); if (out.size() == k) sort(out); continue; }
                if (s <= out.get(k - 1).sim) continue;
                out.set(k - 1, new Near(c, s));
                sort(out);
            }
        }
        sort(out);
        return out;
    }

    private static void sort(List<Near> l) {
        Collections.sort(l, (p, q) -> Double.compare(q.sim, p.sim));
    }

    /**
     * The reaction that most often helped in the cases most like this one, of this reflex, among these options
     * (none given: any it tried). Each near case counts by its nearness (1 for the same situation, down to 0 at the
     * edge of 'near'); confidence = helped / (all + 1), so one case is never enough and failures pull it down. Under
     * the threshold: Advice.UNKNOWN.
     */
    Advice suggest(double[] v, String reflex, String... options) {
        if (!loaded) return Advice.UNKNOWN;
        List<Near> ns = nearest(v, reflex, k);
        Map<String, double[]> by = new LinkedHashMap<String, double[]>();   // reaction -> { weight, helped weight, cases }
        for (Near n : ns) {
            String r = n.c.reaction;
            if (options != null && options.length > 0 && !contains(options, r)) continue;
            double[] t = by.get(r);
            if (t == null) by.put(r, t = new double[3]);
            double wt = (n.sim - near) / (1 - near) * n.c.weight();   // 1 the same situation, 0 at the edge of 'near' (a person's grade: three times)
            t[0] += wt;
            t[1] += wt * n.c.helped();   // its outcome, or the epoch's grade of it (Epochs)
            t[2]++;
        }
        String best = null;
        double bc = -1;
        double[] bt = null;
        for (Map.Entry<String, double[]> e : by.entrySet()) {
            double[] t = e.getValue();
            double conf = t[1] / (t[0] + 1);
            if (conf > bc) { bc = conf; best = e.getKey(); bt = t; }
        }
        if (best == null || bc < threshold) return Advice.UNKNOWN;
        return new Advice(best, bc, (int) bt[2], bt[1] / bt[0]);
    }

    static boolean contains(String[] a, String s) {
        for (String x : a) if (x.equals(s)) return true;
        return false;
    }

    // ---- the epochs' grades -----------------------------------------------------------------------------

    /**
     * An epoch's grade of a case (-1..+1) by its key (tick, agent, reflex), from the rules or from a person: a person's
     * overrides the rules', the rules' never overrides a person's. Kept in memory and, with a journal, on a line of
     * crew-grades.jsonl beside it (read back with the journal). Returns how many cases it graded (0: not in memory).
     */
    int grade(long t, String agent, String reflex, double g, boolean human, String why, int epoch) {
        int n = apply(t, agent, reflex, g, human);
        if (file != null && n > 0) {
            JsonObject o = new JsonObject();
            o.addProperty("t", t);
            o.addProperty("agent", agent);
            o.addProperty("reflex", reflex);
            o.addProperty("grade", g);
            o.addProperty("human", human);
            if (why != null) o.addProperty("why", why);
            o.addProperty("epoch", epoch);
            try {
                Labels.append(grades(), o);
            } catch (Exception e) {
                Events.log("cases: the grade is not written: " + e);
            }
        }
        return n;
    }

    private synchronized int apply(long t, String agent, String reflex, double g, boolean human) {
        int n = 0;
        for (Case c : cases) {
            if (c.t != t || !c.agent.equals(agent) || !c.reflex.equals(reflex)) continue;
            if (c.human && !human && !Double.isNaN(c.grade)) { n++; continue; }   // a person's grade stays
            c.grade = Math.max(-1, Math.min(1, g));
            c.human = human;
            n++;
        }
        return n;
    }

    File grades() { return new File(file.getAbsoluteFile().getParentFile(), GRADES); }

    /** the grades file read back onto the cases in memory (a bad line skipped); the grades applied */
    int loadGrades() {
        if (file == null || !grades().isFile()) return 0;
        int n = 0;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(grades()), "UTF-8"));
            try {
                JsonParser p = new JsonParser();
                for (String line; (line = r.readLine()) != null; ) {
                    if (line.trim().isEmpty()) continue;
                    try {
                        JsonObject o = p.parse(line).getAsJsonObject();
                        n += apply((long) CaseVec.num(o, "t", 0), CaseVec.str(o, "agent"), CaseVec.str(o, "reflex"), CaseVec.num(o, "grade", 0),
                                o.has("human") && o.get("human").getAsBoolean()) > 0 ? 1 : 0;
                    } catch (Exception e) {
                        badLines++;
                    }
                }
            } finally {
                r.close();
            }
        } catch (Exception e) {
            Events.log("cases: grades not read: " + e);
        }
        return n;
    }

    // ---- the journal ----------------------------------------------------------------------------------

    /** a case into memory and onto the journal (the line is written by the writer's thread) */
    void add(Case c) {
        c.vec();
        remember(c);
        if (file != null) write(c.toJson().toString());
    }

    private synchronized void remember(Case c) {
        cases.add(c);
        if (cases.size() > cap) cases.subList(0, cases.size() - cap).clear();
    }

    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<String>(20000);
    private Thread writer;
    private volatile boolean closing;

    private final Object written = new Object();
    private long added, done;   // lines queued, lines the writer is through with (written or failed)

    private synchronized void write(String line) {
        if (closing || !queue.offer(line)) { dropped++; return; }
        synchronized (written) { added++; }
        if (writer == null) {
            writer = new Thread(new Runnable() { public void run() { drain(); } }, "crew-cases");
            writer.setDaemon(true);
            writer.start();
        }
    }

    private void drain() {
        List<String> batch = new ArrayList<String>();
        while (true) {
            batch.clear();
            try {
                String first = queue.poll(500, TimeUnit.MILLISECONDS);
                if (first == null) { if (closing) return; continue; }
                batch.add(first);
                queue.drainTo(batch);
                append(batch);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                Events.log("cases: not written: " + e);
            } finally {
                synchronized (written) { done += batch.size(); written.notifyAll(); }
            }
        }
    }

    /** the lines at the journal's end (the file goes round first when they would not fit) */
    void append(List<String> lines) throws java.io.IOException {
        StringBuilder b = new StringBuilder();
        for (String l : lines) b.append(l).append('\n');
        byte[] bytes = b.toString().getBytes("UTF-8");
        File dir = file.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory()) dir.mkdirs();
        if (file.isFile() && file.length() > 0 && file.length() + bytes.length > maxBytes) rotate();
        OutputStream o = new FileOutputStream(file, true);
        try { o.write(bytes); } finally { o.close(); }
    }

    /** crew-cases.jsonl -> crew-cases.1.jsonl -> .2 ...; the oldest beyond 'keep' goes */
    void rotate() {
        File last = rotated(keep);
        if (last.isFile()) last.delete();
        for (int i = keep - 1; i >= 1; i--) {
            File f = rotated(i);
            if (f.isFile()) f.renameTo(rotated(i + 1));
        }
        file.renameTo(rotated(1));
    }

    File rotated(int i) {
        String n = file.getName();
        int dot = n.lastIndexOf('.');
        String base = dot < 0 ? n : n.substring(0, dot), ext = dot < 0 ? "" : n.substring(dot);
        return new File(file.getAbsoluteFile().getParentFile(), base + "." + i + ext);
    }

    /** wait until what was added is on the disk (at most so long); true when it is */
    boolean flush(long millis) {
        long until = System.currentTimeMillis() + millis;
        synchronized (written) {
            long want = added;
            while (done < want) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) return false;
                try { written.wait(Math.min(left, 50)); } catch (InterruptedException e) { return false; }
            }
        }
        return true;
    }

    /** no more writing: what is queued is written, then the writer ends */
    void close() {
        flush(2000);
        closing = true;
    }

    /** the journal read back into memory, the oldest file first (a bad line is skipped and counted); the cases read */
    int load() {
        List<Case> got = new ArrayList<Case>();
        if (file != null) {
            for (int i = keep; i >= 1; i--) read(rotated(i), got);
            read(file, got);
        }
        synchronized (this) {
            List<Case> mine = new ArrayList<Case>(cases);   // what came while it was read
            cases.clear();
            cases.addAll(got);
            cases.addAll(mine);
            if (cases.size() > cap) cases.subList(0, cases.size() - cap).clear();
        }
        loadGrades();   // the epochs' grades of them (crew-grades.jsonl), if any
        loaded = true;
        return got.size();
    }

    /** the same on a thread of its own (the advice says "don't know" until it is done) */
    void loadLater(final Runnable then) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try { load(); } catch (Exception e) { loaded = true; Events.log("cases: not read: " + e); }
                if (then != null) then.run();
            }
        }, "crew-cases-load");
        t.setDaemon(true);
        t.start();
    }

    private void read(File f, List<Case> into) {
        if (!f.isFile()) return;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            try {
                JsonParser p = new JsonParser();
                for (String line; (line = r.readLine()) != null; ) {
                    if (line.trim().isEmpty()) continue;
                    try {
                        Case c = Case.fromJson(p.parse(line).getAsJsonObject());
                        c.vec();
                        into.add(c);
                        if (into.size() > cap * 2) into.subList(0, into.size() - cap).clear();
                    } catch (Exception e) {
                        badLines++;
                    }
                }
            } finally {
                r.close();
            }
        } catch (Exception e) {
            Events.log("cases: " + f.getName() + " not read: " + e);
        }
    }
}
