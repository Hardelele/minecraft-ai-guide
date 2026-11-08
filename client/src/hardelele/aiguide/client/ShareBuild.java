package hardelele.aiguide.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The share archive (no game classes): what the person ticked, cleaned (Scrub), into one zip with a manifest.json
 * (what is in it, where from, the versions, each file's hash, the consent and when it was given). Built on a thread of
 * its own; the screen reads the progress. Nothing here sends anything anywhere (ShareUpload does, and only when asked).
 */
final class ShareBuild {
    static final int FORMAT = 1;

    /** how a file goes in: as it is; as cleaned text (UTF-8, line by line); a world recording's log; a region trimmed */
    enum Mode { COPY, TEXT, REC, REGION }

    static final class Part {
        final String path;   // in the zip
        final File file;     // or bytes
        final byte[] bytes;
        final Mode mode;
        BitSet keep;         // REGION: the chunks kept (index x + z * 32 within the region)

        Part(String path, File file, Mode mode) { this.path = path; this.file = file; this.bytes = null; this.mode = mode; }
        Part(String path, byte[] bytes, Mode mode) { this.path = path; this.file = null; this.bytes = bytes; this.mode = mode; }

        /** the bytes before compression, about (a trimmed region: its kept sectors) */
        long size() {
            if (bytes != null) return bytes.length;
            if (file == null || !file.isFile()) return 0;
            if (mode == Mode.REGION && keep != null) return regionSize(file, keep);
            return file.length();
        }
    }

    /** one line of the consent screen: what it is, whether it goes in, its files */
    static final class Item {
        final String id;
        boolean on;
        final List<Part> parts = new ArrayList<Part>();
        Item(String id, boolean on) { this.id = id; this.on = on; }
        long size() { long n = 0; for (Part p : parts) n += p.size(); return n; }
    }

    final List<Item> items = new ArrayList<Item>();
    final Scrub scrub;
    final JsonObject meta;   // versions, world, mods, machine: into the manifest as they are (made by the game, no personal data)

    volatile long done, total;
    volatile String status = "";
    volatile File result;
    volatile String error;
    volatile boolean cancel, finished;
    JsonObject manifest;

    ShareBuild(Scrub scrub, JsonObject meta) { this.scrub = scrub; this.meta = meta == null ? new JsonObject() : meta; }

    Item item(String id, boolean on) {
        for (Item i : items) if (i.id.equals(id)) return i;
        Item i = new Item(id, on);
        items.add(i);
        return i;
    }

    long size() { long n = 0; for (Item i : items) if (i.on) n += i.size(); return n; }

    // ---- regions --------------------------------------------------------------------------------------------

    /** the region files (r.X.Z.mca) and their chunks within r chunks (a square) of these chunks (cx, cz) */
    static Map<String, BitSet> regions(List<int[]> chunks, int r) {
        Map<String, BitSet> out = new TreeMap<String, BitSet>();
        java.util.Set<Long> seen = new java.util.HashSet<Long>();
        for (int[] c : chunks)
            for (int x = c[0] - r; x <= c[0] + r; x++)
                for (int z = c[1] - r; z <= c[1] + r; z++) {
                    if (!seen.add(((long) x << 32) ^ (z & 0xffffffffL))) continue;
                    String n = "r." + (x >> 5) + "." + (z >> 5) + ".mca";
                    BitSet b = out.get(n);
                    if (b == null) out.put(n, b = new BitSet(1024));
                    b.set((x & 31) + (z & 31) * 32);
                }
        return out;
    }

    static long regionSize(File f, BitSet keep) {
        try {
            RandomAccessFile r = new RandomAccessFile(f, "r");
            try {
                if (r.length() < 8192) return 0;
                long n = 8192;
                for (int i = 0; i < 1024; i++) {
                    int loc = r.readInt();
                    if (keep.get(i) && loc != 0) n += (loc & 255) * 4096L;
                }
                return n;
            } finally { r.close(); }
        } catch (IOException e) {
            return 0;
        }
    }

    /** a region file with only the kept chunks (the others empty, as never made); the sectors packed */
    static byte[] trimRegion(byte[] in, BitSet keep) {
        if (in.length < 8192) return new byte[0];
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] head = new byte[8192];
        int sector = 2;
        for (int i = 0; i < 1024; i++) {
            int loc = readInt(in, i * 4), off = loc >>> 8, n = loc & 255;
            if (!keep.get(i) || off < 2 || n == 0 || (long) (off + n) * 4096 > in.length) continue;
            body.write(in, off * 4096, n * 4096);
            writeInt(head, i * 4, sector << 8 | n);
            System.arraycopy(in, 4096 + i * 4, head, 4096 + i * 4, 4);   // its timestamp
            sector += n;
        }
        byte[] b = body.toByteArray(), out = new byte[8192 + b.length];
        System.arraycopy(head, 0, out, 0, 8192);
        System.arraycopy(b, 0, out, 8192, b.length);
        return out;
    }

    static int readInt(byte[] b, int i) { return (b[i] & 255) << 24 | (b[i + 1] & 255) << 16 | (b[i + 2] & 255) << 8 | b[i + 3] & 255; }

    static void writeInt(byte[] b, int i, int v) { b[i] = (byte) (v >>> 24); b[i + 1] = (byte) (v >>> 16); b[i + 2] = (byte) (v >>> 8); b[i + 3] = (byte) v; }

    // ---- the archive -----------------------------------------------------------------------------------------

    /** on a thread of its own: the archive into this folder, named <stamp>-<id>.zip */
    Thread start(final File dir, final String stamp, final String id, final JsonObject consent, final Runnable before) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    if (before != null) { status = "save"; before.run(); }
                    build(dir, stamp, id, consent);
                } catch (Throwable e) {
                    error = String.valueOf(e);
                } finally {
                    finished = true;
                }
            }
        }, "aiguide-share");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** the archive, here and now (the thread's work; the tests call it straight) */
    File build(File dir, String stamp, String id, JsonObject consent) throws IOException {
        dir.mkdirs();
        String name = stamp + "-" + id;
        File tmp = new File(dir, name + ".zip.part"), zip = new File(dir, name + ".zip");
        total = size();
        done = 0;
        JsonArray files = new JsonArray();
        ZipOutputStream z = new ZipOutputStream(new FileOutputStream(tmp));
        boolean ok = false;
        try {
            for (Item i : items) {
                if (!i.on) continue;
                for (Part p : i.parts) {
                    if (cancel) throw new IOException("cancelled");
                    if (p.bytes == null && (p.file == null || !p.file.isFile())) continue;
                    status = p.path;
                    long before = done;
                    z.putNextEntry(new ZipEntry(p.path));
                    Out o = new Out(z);
                    put(p, o);
                    z.closeEntry();
                    JsonObject f = new JsonObject();
                    f.addProperty("path", p.path);
                    f.addProperty("item", i.id);
                    f.addProperty("bytes", o.n);
                    f.addProperty("sha256", Labels.hex(o.d.digest()));
                    f.addProperty("treated", p.mode == Mode.COPY ? "as is" : p.mode == Mode.TEXT ? "text cleaned" : p.mode == Mode.REC ? "recording strings cleaned" : "region trimmed to the chosen chunks");
                    files.add(f);
                    done = before + p.size();
                }
            }
            manifest = manifest(id, consent, files);
            z.putNextEntry(new ZipEntry("manifest.json"));
            z.write(new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(manifest).getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            ok = true;
        } finally {
            z.close();
            if (!ok) tmp.delete();
        }
        if (zip.exists()) zip.delete();
        if (!tmp.renameTo(zip)) throw new IOException("not renamed: " + tmp);
        status = "done";
        result = zip;
        return zip;
    }

    JsonObject manifest(String id, JsonObject consent, JsonArray files) {
        JsonObject m = new JsonObject();
        m.addProperty("format", "aiguide-share");
        m.addProperty("version", FORMAT);
        m.addProperty("id", id);
        m.addProperty("created", Labels.iso(System.currentTimeMillis()));
        for (Map.Entry<String, com.google.gson.JsonElement> e : meta.entrySet()) m.add(e.getKey(), e.getValue());
        m.add("consent", consent == null ? new JsonObject() : consent);
        JsonObject s = new JsonObject();
        s.addProperty("players_pseudonymised", scrub.names());
        s.addProperty("replacements", scrub.replaced);
        JsonArray rules = new JsonArray();
        for (String r : new String[] { "player names -> playerN", "server addresses -> <server>", "IP addresses -> <ip>",
                "user folders in paths -> <user>", "the game's folder -> <minecraft>", "e-mail -> <email>", "UUID -> <uuid>" })
            rules.add(new com.google.gson.JsonPrimitive(r));
        s.add("rules", rules);
        m.add("scrub", s);
        m.add("files", files);
        return m;
    }

    /** the zip's entry as it is written: counted, hashed, never closing the zip (and the progress moved on) */
    final class Out extends OutputStream {
        final OutputStream z;
        final MessageDigest d;
        long n;
        Out(OutputStream z) {
            this.z = z;
            try { d = MessageDigest.getInstance("SHA-256"); } catch (java.security.NoSuchAlgorithmException e) { throw new RuntimeException(e); }
        }
        @Override public void write(int b) throws IOException { z.write(b); d.update((byte) b); n++; done++; }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            if (cancel) throw new IOException("cancelled");
            z.write(b, off, len); d.update(b, off, len); n += len; done += len;
        }
        @Override public void close() { }
    }

    /** a part into the zip's entry, as its mode says (streamed: a recording can be large) */
    void put(Part p, OutputStream o) throws IOException {
        switch (p.mode) {
            case TEXT: o.write(cleanText(p.bytes != null ? p.bytes : read(p.file))); return;
            case REGION: { byte[] raw = p.bytes != null ? p.bytes : read(p.file); o.write(p.keep == null ? raw : trimRegion(raw, p.keep)); return; }
            case REC: {
                InputStream in = new GZIPInputStream(p.bytes != null ? new ByteArrayInputStream(p.bytes) : new FileInputStream(p.file), 1 << 16);
                try {
                    DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(new GZIPOutputStream(o, 1 << 16), 1 << 16));
                    scrub.rec(new DataInputStream(new java.io.BufferedInputStream(in, 1 << 16)), out);
                    out.close();   // finishes the gzip (o itself stays open)
                } finally { in.close(); }
                return;
            }
            default: {
                InputStream in = p.bytes != null ? new ByteArrayInputStream(p.bytes) : new FileInputStream(p.file);
                try {
                    byte[] buf = new byte[1 << 16];
                    for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
                } finally { in.close(); }
            }
        }
    }

    byte[] cleanText(byte[] raw) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(raw), StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder();
        JsonParser jp = new JsonParser();
        for (String l; (l = r.readLine()) != null; ) {
            String t = l.trim();
            if (t.startsWith("{") && t.endsWith("}")) {   // a JSON line (or a one-line file): every string in it cleaned, the JSON kept whole
                try { b.append(scrub.json(jp.parse(t)).toString()).append('\n'); continue; } catch (Exception e) { /* as text */ }
            }
            b.append(scrub.text(l)).append('\n');
        }
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    static byte[] read(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream((int) Math.min(f.length() + 16, Integer.MAX_VALUE));
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
            return o.toByteArray();
        } finally { in.close(); }
    }

    /** a file's sha256 (for the tests and tools) */
    static String sha256(File f) throws IOException {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            InputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) d.update(buf, 0, n);
            } finally { in.close(); }
            return Labels.hex(d.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** the guide window's command that opens the share screen: "/share", "/поделиться", "поделиться данными" */
    static boolean command(String q) {
        String s = q == null ? "" : q.toLowerCase(java.util.Locale.ROOT).replaceAll("[.!\\s]+$", "").trim();
        return s.equals("/share") || s.equals("share data") || s.equals("/поделиться") || s.equals("поделиться данными") || s.equals("/поделиться данными");
    }

    /** a short random id (hex) */
    static String newId() {
        byte[] b = new byte[3];
        new java.security.SecureRandom().nextBytes(b);
        return Labels.hex(b);
    }

    static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.0f KB", bytes / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    static Map<String, Boolean> ticks(List<Item> items) {
        Map<String, Boolean> m = new LinkedHashMap<String, Boolean>();
        for (Item i : items) m.put(i.id, i.on);
        return m;
    }
}
