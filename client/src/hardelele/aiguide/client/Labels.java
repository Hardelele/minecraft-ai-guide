package hardelele.aiguide.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * The person's ratings, as training examples (no game classes): a recognised place judged right, given another class
 * or said to be no place (aiguide-labels/places.jsonl in the world), and an agent's episode judged good or bad with a
 * reason (aiguide-labels/actions.jsonl). Each line is one JSON object; a place's line carries the model's input cube,
 * so the line alone is a training example (tools/labels.py).
 */
final class Labels {
    private Labels() { }

    static final int VERSION = 1;
    static final String DIR = "aiguide-labels", PLACES = "places.jsonl", ACTIONS = "actions.jsonl";
    static final int SIDE = 32, CELLS = SIDE * SIDE * SIDE, CHANNELS = 16;

    /** a place's answers: the class is right; another class is (label); it is no place, or its border is wrong */
    static final String CORRECT = "correct", RELABEL = "relabel", NOT_PLACE = "not_place";
    /** an episode's reasons (the words shown are in the lang files: aiguide.fb.reason.<key>) */
    static final String[] REASONS = { "dumb", "dangerous", "breaks_build", "right", "fast" };

    static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    /** the world as a label names it: an id from the seed and the folder (not the name itself), the seed, the tick */
    static JsonObject world(long seed, String folder, long tick) {
        JsonObject w = new JsonObject();
        w.addProperty("id", worldId(seed, folder));
        w.addProperty("seed", seed);
        w.addProperty("tick", tick);
        return w;
    }

    static String worldId(long seed, String folder) {
        return sha256(("" + seed + "/" + (folder == null ? "" : folder)).getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 12);
    }

    // ---- the cube ---------------------------------------------------------------------------------------

    /** one channel index per cell ([x][y][z]), deflated, in base64 */
    static String pack(byte[] cells) {
        Deflater d = new Deflater(9);
        d.setInput(cells);
        d.finish();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!d.finished()) o.write(buf, 0, d.deflate(buf));
        d.end();
        return java.util.Base64.getEncoder().encodeToString(o.toByteArray());
    }

    static byte[] unpack(String data) throws java.util.zip.DataFormatException {
        Inflater i = new Inflater();
        i.setInput(java.util.Base64.getDecoder().decode(data));
        byte[] out = new byte[CELLS];
        int n = 0;
        while (n < CELLS && !i.finished()) {
            int k = i.inflate(out, n, CELLS - n);
            if (k == 0 && (i.needsInput() || i.needsDictionary())) break;
            n += k;
        }
        i.end();
        if (n != CELLS) throw new java.util.zip.DataFormatException("a cube of " + n + " cells");
        return out;
    }

    static int index(int x, int y, int z) { return (x * SIDE + y) * SIDE + z; }

    static JsonObject cube(int[] origin, byte[] cells, String source) {
        JsonObject c = new JsonObject();
        c.add("origin", ints(origin));
        c.addProperty("size", SIDE);
        c.addProperty("channels", CHANNELS);
        c.addProperty("order", "x,y,z");
        c.addProperty("enc", "u8-deflate-base64");
        c.addProperty("source", source);   // "model": the model's own input; "world": read from the world when rated
        c.addProperty("data", pack(cells));
        return c;
    }

    // ---- the records -----------------------------------------------------------------------------------

    /**
     * A place's rating. answer CORRECT: the label is the predicted class; RELABEL: the label is 'label'; NOT_PLACE:
     * no label (the predicted class is wrong there, or the box is). cells may be null (then no cube: not a full example).
     */
    static JsonObject place(long ms, JsonObject world, PlaceView p, String weights, int[] origin, byte[] cells, String cubeSource,
                            String answer, int label, String comment) {
        JsonObject o = head("place", ms, world);
        JsonObject pl = new JsonObject();
        pl.addProperty("id", p.id());
        pl.add("box", ints(p.box()));
        pl.addProperty("pred", name(p.cls()));
        pl.addProperty("pred_i", p.cls());
        pl.addProperty("conf", Math.round(p.confidence() * 1000) / 1000.0);
        o.add("place", pl);
        JsonObject m = new JsonObject();
        m.addProperty("weights", weights == null ? "none" : weights);
        o.add("model", m);
        if (cells != null && origin != null) o.add("cube", cube(origin, cells, cubeSource));
        o.addProperty("answer", answer);
        int l = CORRECT.equals(answer) ? p.cls() : RELABEL.equals(answer) ? label : -1;
        if (l >= 0) { o.addProperty("label", name(l)); o.addProperty("label_i", l); }
        else { o.add("label", JsonNull.INSTANCE); o.addProperty("label_i", -1); }
        o.addProperty("comment", comment == null ? "" : comment.trim());
        return o;
    }

    /** an episode's rating: +1 good, -1 bad, a reason (one of REASONS, or null), the episode it is about */
    static JsonObject action(long ms, JsonObject world, Episodes.Ep e, int rating, String reason, String comment) {
        JsonObject o = head("action", ms, world);
        o.add("target", e.ref());
        o.addProperty("rating", rating > 0 ? 1 : -1);
        if (reason == null) o.add("reason", JsonNull.INSTANCE);
        else o.addProperty("reason", reason);
        o.addProperty("comment", comment == null ? "" : comment.trim());
        return o;
    }

    static JsonObject head(String kind, long ms, JsonObject world) {
        JsonObject o = new JsonObject();
        o.addProperty("v", VERSION);
        o.addProperty("kind", kind);
        o.addProperty("time", iso(ms));
        o.addProperty("ms", ms);
        o.add("world", world == null ? new JsonObject() : world);
        return o;
    }

    static String name(int cls) { return cls >= 0 && cls < PlaceView.CLASSES.length ? PlaceView.CLASSES[cls] : "?"; }

    static JsonArray ints(int[] a) {
        JsonArray j = new JsonArray();
        if (a != null) for (int v : a) j.add(new JsonPrimitive(v));
        return j;
    }

    /** one line at the file's end (its folder made when there is none) */
    static synchronized void append(File f, JsonObject o) throws java.io.IOException {
        File dir = f.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory()) dir.mkdirs();
        OutputStream out = new FileOutputStream(f, true);
        try { out.write((o.toString() + "\n").getBytes("UTF-8")); } finally { out.close(); }
    }

    static String sha256(byte[] b) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            return hex(d.digest(b));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 255));
        return s.toString();
    }
}
