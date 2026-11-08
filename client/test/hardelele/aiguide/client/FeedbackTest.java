package hardelele.aiguide.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Checks of the person's ratings and the share archive (Labels, Episodes, Scrub, ShareBuild, ShareUpload) with no game. */
public class FeedbackTest {
    static int failed, passed;

    static void check(String what, boolean ok) {
        if (ok) passed++;
        else failed++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    static File folder(String name) throws java.io.IOException {
        String base = System.getProperty("aiguide.test.dir");
        File d = base == null ? Files.createTempDirectory(name).toFile() : new File(base, name);
        if (d.isDirectory()) delete(d);
        d.mkdirs();
        return d;
    }

    static void delete(File f) {
        File[] fs = f.listFiles();
        if (fs != null) for (File x : fs) delete(x);
        f.delete();
    }

    public static void main(String[] args) throws Exception {
        records();
        episodes();
        scrub();
        rec();
        command();
        regions();
        archive();
        upload();
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---- the records -------------------------------------------------------------------------------------

    static void records() throws Exception {
        byte[] cells = new byte[Labels.CELLS];
        for (int x = 0; x < 32; x++) for (int y = 0; y < 32; y++) for (int z = 0; z < 32; z++) cells[Labels.index(x, y, z)] = (byte) (y < 10 ? 5 : y == 10 ? 9 : 0);
        String packed = Labels.pack(cells);
        check("a cube packs small (" + packed.length() + " chars of base64)", packed.length() < 2000);
        check("a cube unpacks to the same cells", java.util.Arrays.equals(Labels.unpack(packed), cells));
        PlaceView p = PlaceSource.of("p7", 3, new int[] { 4, 11, 4, 9, 14, 9 }, 0.8234, new int[] { 0, 0, 0 });
        JsonObject w = Labels.world(12345L, "New World", 600);
        JsonObject ok = Labels.place(1700000000000L, w, p, "sha256:abc", p.cube(), cells, "world", Labels.CORRECT, -1, "  ");
        check("a place's record: kind, version, time", "place".equals(ok.get("kind").getAsString()) && ok.get("v").getAsInt() == 1 && ok.get("time").getAsString().equals("2023-11-14T22:13:20Z"));
        check("a place's record: the predicted class and confidence", "room".equals(ok.getAsJsonObject("place").get("pred").getAsString()) && ok.getAsJsonObject("place").get("conf").getAsDouble() == 0.823);
        check("a place's record: its box and the cube's origin", ok.getAsJsonObject("place").getAsJsonArray("box").size() == 6 && ok.getAsJsonObject("cube").getAsJsonArray("origin").get(1).getAsInt() == 0);
        check("correct: the label is the predicted class", "room".equals(ok.get("label").getAsString()) && ok.get("label_i").getAsInt() == 3);
        check("the weights' version is kept", "sha256:abc".equals(ok.getAsJsonObject("model").get("weights").getAsString()));
        check("the world is named by an id, not by its name", !ok.toString().contains("New World") && ok.getAsJsonObject("world").get("id").getAsString().length() == 12);
        JsonObject re = Labels.place(0, w, p, "none", p.cube(), cells, "world", Labels.RELABEL, 2, "узкий проход");
        check("relabel: the label is the one chosen, the comment kept", "passage".equals(re.get("label").getAsString()) && "узкий проход".equals(re.get("comment").getAsString()));
        JsonObject no = Labels.place(0, w, p, "none", null, null, null, Labels.NOT_PLACE, 2, null);
        check("not a place: no label, no cube when none was read", no.get("label").isJsonNull() && no.get("label_i").getAsInt() == -1 && !no.has("cube"));
        File dir = folder("labels");
        File f = new File(new File(dir, Labels.DIR), Labels.PLACES);
        Labels.append(f, ok);
        Labels.append(f, re);
        List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        check("the journal: one JSON line each, made with its folder", lines.size() == 2 && new JsonParser().parse(lines.get(1)).getAsJsonObject().get("answer").getAsString().equals("relabel"));
        check("the journal's lines end with LF only", !new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).contains("\r"));

        CaseBook.Case c = new CaseBook.Case();
        c.t = 4242; c.agent = "Агент 2"; c.reflex = "stuck-jump"; c.reaction = "jump"; c.result = "OK"; c.ticks = 9;
        c.sit.feet = new int[] { 10, 64, -3 };
        JsonObject a = Labels.action(0, w, Episodes.of(c), -5, "dumb", "прыгал на месте");
        JsonObject t = a.getAsJsonObject("target");
        check("an action's record: the case by tick, agent and reflex", "case".equals(t.get("type").getAsString()) && t.get("t").getAsLong() == 4242 && "Агент 2".equals(t.get("agent").getAsString()) && "stuck-jump".equals(t.get("reflex").getAsString()));
        check("an action's record: rating -1, the reason, where", a.get("rating").getAsInt() == -1 && "dumb".equals(a.get("reason").getAsString()) && t.getAsJsonArray("at").get(2).getAsInt() == -3);
    }

    static void episodes() {
        Episodes.clear();
        Episodes.event(1000, "board: chop#12 by Агент 1 -> OK");
        Episodes.event(1001, "reflex: Агент 1 stuck-jump jump -> OK in 4 ticks");
        Episodes.event(1002, "board: mine#13 by Агент 3 -> NO_PATH, given up");
        List<Episodes.Ep> k = Episodes.cards(10);
        check("cards from the board's lines, newest first", k.size() == 2 && "mine#13".equals(k.get(0).card) && "Агент 3".equals(k.get(0).agent) && "NO_PATH".equals(k.get(0).result));
        check("a card's reference: its line's time and the card", "card".equals(k.get(1).ref().get("type").getAsString()) && k.get(1).ref().get("ms").getAsLong() == 1000);
        List<CaseBook.Case> cs = new ArrayList<CaseBook.Case>();
        for (int i = 0; i < 5; i++) { CaseBook.Case c = new CaseBook.Case(); c.t = i; c.reflex = "r" + i; cs.add(c); }
        List<Episodes.Ep> e = Episodes.cases(cs, 3);
        check("cases newest first, cut", e.size() == 3 && e.get(0).t == 4 && e.get(2).t == 2);
        check("the kept lines go out with their time", Episodes.lines().get(0).startsWith("1000\t"));
    }

    // ---- cleaning ---------------------------------------------------------------------------------------

    static Scrub scrubber() {
        return new Scrub().name("Steve_42").name("Рената").name("harde").host("mc.example.org:25565")
                .path("C:\\Users\\harde\\AppData\\Roaming\\.minecraft", "<minecraft>").path("C:\\Users\\harde", "<home>");
    }

    static void scrub() {
        Scrub s = scrubber();
        check("a player's name: a pseudonym, any case", s.text("player:Steve_42 joined; steve_42 left").equals("player:player1 joined; player1 left"));
        check("not a part of a longer word", s.text("Steve_420").equals("Steve_420"));
        check("a Cyrillic name too", s.text("Рената сказала").equals("player2 сказала"));
        check("an IP with its port", s.text("connected to 192.168.1.9:25565 ok").equals("connected to <ip> ok"));
        check("versions are no addresses", s.text("Forge 10.13.4.1614 on 1.7.10, 999.1.1.1").equals("Forge 10.13.4.1614 on 1.7.10, 999.1.1.1"));
        check("an IPv6 address", s.text("from fe80::1c2b:3d4e and 2001:db8:0:0:0:0:2:1").equals("from <ip> and <ip>"));
        check("the game's folder", s.text("saved to C:\\Users\\harde\\AppData\\Roaming\\.minecraft\\saves\\x").equals("saved to <minecraft>\\saves\\x"));
        check("the game's folder with forward slashes", s.text("C:/Users/harde/AppData/Roaming/.minecraft/logs").equals("<minecraft>/logs"));
        check("another user's folder", s.text("D:\\Users\\Bob Smith\\x and /home/alice/y and /Users/eve/z").equals("D:\\Users\\<user>\\x and /home/<user>/y and /Users/<user>/z"));
        check("the computer's user name alone", s.text("by harde at noon").equals("by player3 at noon"));
        check("a server's address", s.text("joined mc.example.org:25565 / mc.example.org").equals("joined <server> / <server>"));
        check("e-mail and UUID", s.text("a@b.org 0f8e7d6c-1111-2222-3333-444455556666").equals("<email> <uuid>"));
        JsonObject o = new JsonObject();
        o.addProperty("who", "Steve_42");
        o.addProperty("n", 3);
        JsonObject in = new JsonObject();
        in.addProperty("Steve_42", "C:\\Users\\harde\\x");
        o.add("by", in);
        String j = s.json(o).toString();
        check("JSON: values and keys cleaned, numbers kept", j.equals("{\"who\":\"player1\",\"n\":3,\"by\":{\"player1\":\"<home>\\\\x\"}}"));
    }

    static byte[] recording(boolean cut) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(new GZIPOutputStream(b));
        o.writeInt(0x41495245); o.writeInt(1);
        o.writeByte(6); o.writeShort(0); o.writeUTF("player:Steve_42");
        o.writeByte(6); o.writeShort(1); o.writeUTF("agent");
        o.writeByte(6); o.writeShort(2); o.writeUTF("Агент 1");
        o.writeByte(1); o.writeInt(7); o.writeLong(1234L); o.writeBoolean(false);
        // a player (not an agent): no agent's tail; holding an item
        o.writeByte(2); o.writeInt(100); o.writeShort(0); o.writeDouble(1.5); o.writeDouble(64); o.writeDouble(-2.5);
        o.writeFloat(90); o.writeFloat(10); o.writeFloat(90); o.writeByte(0); o.writeFloat(20); o.writeByte(0); o.writeShort(276); o.writeShort(0); o.writeByte(1);
        // an agent: empty hand, the agent's tail
        o.writeByte(2); o.writeInt(101); o.writeShort(1); o.writeDouble(3); o.writeDouble(65); o.writeDouble(4);
        o.writeFloat(0); o.writeFloat(0); o.writeFloat(0); o.writeByte(0); o.writeFloat(20); o.writeByte(0); o.writeShort(-1);
        o.writeShort(2); o.writeByte(3); o.writeInt(5); o.writeShort(64); o.writeInt(6);
        o.writeByte(3); o.writeInt(5); o.writeShort(64); o.writeInt(6); o.writeShort(4); o.writeByte(0);
        o.writeByte(4); o.writeInt(9); o.writeInt(5); o.writeShort(64); o.writeInt(6); o.writeByte(3);
        o.writeByte(5); o.writeShort(1); o.writeFloat(1); o.writeFloat(2); o.writeFloat(3); o.writeFloat(1); o.writeFloat(1);
        if (cut) { o.writeByte(3); o.writeInt(5); }   // the game closed in the middle of a record
        else o.writeByte(0);
        o.close();
        return b.toByteArray();
    }

    static void rec() throws Exception {
        for (boolean cut : new boolean[] { false, true }) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(new GZIPOutputStream(b));
            scrubber().rec(new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(recording(cut)))), out);
            out.close();
            DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(b.toByteArray())));
            boolean shape = in.readInt() == 0x41495245 && in.readInt() == 1;
            List<String> strings = new ArrayList<String>();
            int records = 0, ents = 0;
            boolean end = false;
            try {
                for (; ; ) {
                    int r = in.readByte();
                    if (r == 0) { end = true; break; }
                    records++;
                    if (r == 6) { in.readShort(); strings.add(in.readUTF()); }
                    else if (r == 1) { shape &= in.readInt() == 7 && in.readLong() == 1234L; in.readBoolean(); }
                    else if (r == 2) {
                        ents++; in.readInt(); int type = in.readShort(); in.readDouble(); in.readDouble(); in.readDouble(); in.readFloat(); in.readFloat(); in.readFloat();
                        in.readByte(); in.readFloat(); in.readByte(); int item = in.readShort(); if (item >= 0) { in.readShort(); in.readByte(); }
                        if ("agent".equals(strings.get(type))) { in.readShort(); in.readByte(); in.readInt(); shape &= in.readShort() == 64; in.readInt(); }
                    } else if (r == 3) { in.readInt(); in.readShort(); in.readInt(); in.readShort(); in.readByte(); }
                    else if (r == 4) { in.readInt(); in.readInt(); in.readShort(); in.readInt(); in.readByte(); }
                    else if (r == 5) { in.readShort(); for (int i = 0; i < 5; i++) in.readFloat(); }
                    else { shape = false; break; }
                }
            } catch (java.io.EOFException eof) { /* cut */ }
            String tag = cut ? " (a log cut short)" : "";
            check("a recording's player becomes a pseudonym, the agent's name stays" + tag, strings.size() == 3 && "player:player1".equals(strings.get(0)) && "Агент 1".equals(strings.get(2)));
            check("a recording's other records copied as they were" + tag, shape && records == 9 && ents == 2 && end == !cut);
        }
    }

    // ---- regions and the archive -------------------------------------------------------------------------

    /** a region file with chunks at these indexes, each a sector of its own filled with its index */
    static byte[] region(int... idx) {
        byte[] b = new byte[8192 + idx.length * 4096];
        for (int k = 0; k < idx.length; k++) {
            ShareBuild.writeInt(b, idx[k] * 4, (2 + k) << 8 | 1);
            ShareBuild.writeInt(b, 4096 + idx[k] * 4, 1000 + k);
            for (int i = 0; i < 4096; i++) b[8192 + k * 4096 + i] = (byte) idx[k];
        }
        return b;
    }

    static void command() {
        check("the guide window's command opens the share screen", ShareBuild.command("/поделиться") && ShareBuild.command(" Поделиться данными! ") && ShareBuild.command("/share"));
        check("a question about sharing is still a question", !ShareBuild.command("как поделиться данными с другом?") && !ShareBuild.command("поделиться"));
    }

    static void regions() {
        List<int[]> at = new ArrayList<int[]>();
        at.add(new int[] { -1, 0 });
        Map<String, BitSet> r0 = ShareBuild.regions(at, 0);
        check("a chunk to its region and index (negative x)", r0.size() == 1 && r0.containsKey("r.-1.0.mca") && r0.get("r.-1.0.mca").get(31) && r0.get("r.-1.0.mca").cardinality() == 1);
        Map<String, BitSet> r1 = ShareBuild.regions(at, 1);
        check("a radius crosses into the next regions", r1.keySet().toString().equals("[r.-1.-1.mca, r.-1.0.mca, r.0.-1.mca, r.0.0.mca]") && r1.get("r.-1.0.mca").cardinality() == 4);
        byte[] reg = region(0, 33, 500);
        BitSet keep = new BitSet();
        keep.set(33);
        keep.set(7);   // kept but never made: stays empty
        byte[] t = ShareBuild.trimRegion(reg, keep);
        check("a trimmed region keeps only the chosen chunk", t.length == 8192 + 4096 && ShareBuild.readInt(t, 33 * 4) == (2 << 8 | 1) && ShareBuild.readInt(t, 0) == 0 && ShareBuild.readInt(t, 500 * 4) == 0);
        check("its sector and timestamp moved with it", t[8192] == 33 && ShareBuild.readInt(t, 4096 + 33 * 4) == 1001);
    }

    static void archive() throws Exception {
        File d = folder("share");
        File world = new File(d, "world");
        new File(world, "region").mkdirs();
        File cases = new File(world, "crew-cases.jsonl");
        Files.write(cases.toPath(), ("{\"t\":1,\"agent\":\"Агент 1\",\"text\":\"hit by Steve_42\"}\n{\"t\":2,\"agent\":\"Агент 2\"}\n").getBytes(StandardCharsets.UTF_8));
        File reg = new File(new File(world, "region"), "r.0.0.mca");
        Files.write(reg.toPath(), region(0, 1, 2));
        File chat = new File(d, "chat.txt");
        Files.write(chat.toPath(), "Steve_42: hello".getBytes(StandardCharsets.UTF_8));

        ShareBuild b = new ShareBuild(scrubber(), meta());
        b.item("cases", true).parts.add(new ShareBuild.Part("crew/crew-cases.jsonl", cases, ShareBuild.Mode.TEXT));
        ShareBuild.Part rp = new ShareBuild.Part("world/region/r.0.0.mca", reg, ShareBuild.Mode.REGION);
        rp.keep = new BitSet();
        rp.keep.set(1);
        b.item("regions", true).parts.add(rp);
        b.item("events", true).parts.add(new ShareBuild.Part("crew/crew-events.txt", "1\tworldrec: start C:\\Users\\harde\\AppData\\Roaming\\.minecraft\\recordings\\a\n".getBytes(StandardCharsets.UTF_8), ShareBuild.Mode.TEXT));
        b.item("chat", false).parts.add(new ShareBuild.Part("chat/guide-chat.txt", chat, ShareBuild.Mode.TEXT));
        b.item("missing", true).parts.add(new ShareBuild.Part("x/none.txt", new File(d, "none.txt"), ShareBuild.Mode.COPY));
        check("the size before: the ticked ones, a region by its kept sectors", b.size() == cases.length() + 8192 + 4096 + b.items.get(2).size());
        JsonObject consent = new JsonObject();
        consent.addProperty("at", Labels.iso(0));
        consent.add("items", new com.google.gson.Gson().toJsonTree(ShareBuild.ticks(b.items)));
        File out = new File(d, "out");
        b.start(out, "20261108-120000", "abc123", consent, null).join(20000);
        check("built on its thread, finished, no error", b.finished && b.error == null && b.result != null);
        File zip = new File(out, "20261108-120000-abc123.zip");
        check("the archive's name: <stamp>-<id>.zip, no part file left", zip.isFile() && !new File(out, "20261108-120000-abc123.zip.part").exists());
        ZipFile z = new ZipFile(zip);
        try {
            JsonObject m = new JsonParser().parse(new String(bytes(z, "manifest.json"), StandardCharsets.UTF_8)).getAsJsonObject();
            check("the manifest: format, id, versions, consent", "aiguide-share".equals(m.get("format").getAsString()) && "abc123".equals(m.get("id").getAsString())
                    && "0.2".equals(m.getAsJsonObject("aiguide").get("client").getAsString()) && !m.getAsJsonObject("consent").getAsJsonObject("items").get("chat").getAsBoolean());
            boolean hashes = true;
            for (com.google.gson.JsonElement e : m.getAsJsonArray("files")) {
                JsonObject f = e.getAsJsonObject();
                byte[] c = bytes(z, f.get("path").getAsString());
                hashes &= c != null && Labels.sha256(c).equals(f.get("sha256").getAsString()) && c.length == f.get("bytes").getAsLong();
            }
            check("each file's hash and size in the manifest are the entry's", hashes && m.getAsJsonArray("files").size() == 3);
            check("what was not ticked is not there (the chat), nor what does not exist", z.getEntry("chat/guide-chat.txt") == null && z.getEntry("x/none.txt") == null);
            String cs = new String(bytes(z, "crew/crew-cases.jsonl"), StandardCharsets.UTF_8);
            check("the cases cleaned, still JSON lines", cs.contains("hit by player1") && !cs.contains("Steve") && new JsonParser().parse(cs.split("\n")[0]).isJsonObject());
            String ev = new String(bytes(z, "crew/crew-events.txt"), StandardCharsets.UTF_8);
            check("the events' paths cleaned", ev.contains("<minecraft>\\recordings") && !ev.contains("harde"));
            byte[] r = bytes(z, "world/region/r.0.0.mca");
            check("the region trimmed in the archive", r.length == 8192 + 4096 && r[8192] == 1);
            check("the manifest says the cleaning", m.getAsJsonObject("scrub").get("replacements").getAsInt() >= 2);
        } finally {
            z.close();
        }
        ShareBuild c = new ShareBuild(new Scrub(), meta());
        c.item("cases", true).parts.add(new ShareBuild.Part("crew/crew-cases.jsonl", cases, ShareBuild.Mode.TEXT));
        c.cancel = true;
        c.start(out, "20261108-120001", "def456", consent, null).join(20000);
        check("cancelled: an error, no archive, no part file", c.error != null && c.result == null && out.listFiles().length == 1);
    }

    static JsonObject meta() {
        JsonObject m = new JsonObject();
        JsonObject a = new JsonObject();
        a.addProperty("client", "0.2");
        m.add("aiguide", a);
        return m;
    }

    static byte[] bytes(ZipFile z, String name) throws Exception {
        ZipEntry e = z.getEntry(name);
        if (e == null) return null;
        InputStream in = z.getInputStream(e);
        try { return readAll(in); } finally { in.close(); }
    }

    static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
        return o.toByteArray();
    }

    // ---- sending, to a stub server on this computer -------------------------------------------------------

    static void upload() throws Exception {
        File d = folder("upload");
        ShareUpload.Config none = ShareUpload.Config.read(new File(d, "share.json"));
        check("no config: no address, Send hidden", !none.enabled());
        File cfg = new File(d, "share.json");
        Files.write(cfg.toPath(), ShareUpload.Config.template().getBytes(StandardCharsets.UTF_8));
        check("the template: the address empty", !ShareUpload.Config.read(cfg).enabled() && "post".equals(ShareUpload.Config.read(cfg).method));
        Files.write(cfg.toPath(), "{\"shareUrl\": ".getBytes(StandardCharsets.UTF_8));
        check("a broken config sends nothing", !ShareUpload.Config.read(cfg).enabled());
        ShareUpload.Config plain = new ShareUpload.Config();
        plain.url = "http://example.org/upload";
        check("plain http to another computer is not allowed", ShareUpload.host(plain) == null);
        boolean refused = false;
        try { ShareUpload.send(plain, cfg, "x", null, null); } catch (java.io.IOException e) { refused = true; }
        check("and is refused before any connection", refused);

        File zip = new File(d, "20261108-x.zip");
        byte[] content = new byte[200000];
        new java.util.Random(3).nextBytes(content);
        Files.write(zip.toPath(), content);
        final List<Object[]> got = new ArrayList<Object[]>();
        final int[] status = { 200 };
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/", new HttpHandler() {
            public void handle(HttpExchange x) throws java.io.IOException {
                byte[] body = readAll(x.getRequestBody());
                synchronized (got) { got.add(new Object[] { x.getRequestMethod(), x.getRequestHeaders().getFirst("Content-Type"), body, x.getRequestHeaders().getFirst("X-AIGuide-Share-Id") }); }
                byte[] r = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                x.sendResponseHeaders(status[0], r.length);
                OutputStream o = x.getResponseBody();
                o.write(r);
                o.close();
            }
        });
        srv.start();
        try {
            ShareUpload.Config c = new ShareUpload.Config();
            c.url = "http://127.0.0.1:" + srv.getAddress().getPort() + "/share";
            c.timeoutSec = 10;
            check("http to this computer is allowed (the stub)", ShareUpload.host(c) != null);
            ShareUpload.Progress p = ShareUpload.start(c, zip, "abc123", "{\"id\":\"abc123\"}".getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < 200 && !p.finished; i++) Thread.sleep(25);
            Object[] g = got.isEmpty() ? null : got.get(0);
            String body = g == null ? "" : new String((byte[]) g[2], StandardCharsets.ISO_8859_1);
            check("POST multipart: the manifest part and the archive part", g != null && "POST".equals(g[0]) && ((String) g[1]).startsWith("multipart/form-data; boundary=")
                    && body.contains("name=\"manifest\"") && body.contains("{\"id\":\"abc123\"}") && body.contains("name=\"archive\"; filename=\"20261108-x.zip\""));
            check("POST: the zip's bytes whole in it, the id in the header", g != null && body.contains(new String(content, StandardCharsets.ISO_8859_1)) && "abc123".equals(g[3]));
            check("POST: the answer and the progress", p.error == null && p.answer.startsWith("HTTP 200") && p.sent == p.total && p.total > content.length);
            c.method = "put";
            String a = ShareUpload.send(c, zip, "abc123", null, null);
            g = got.get(1);
            check("PUT: the zip as the body", "PUT".equals(g[0]) && "application/zip".equals(g[1]) && java.util.Arrays.equals((byte[]) g[2], content) && a.startsWith("HTTP 200"));
            status[0] = 500;
            boolean failedUp = false;
            try { ShareUpload.send(c, zip, "abc123", null, null); } catch (java.io.IOException e) { failedUp = e.getMessage().startsWith("HTTP 500"); }
            check("a server's error is an error", failedUp);
        } finally {
            srv.stop(0);
        }
    }
}
