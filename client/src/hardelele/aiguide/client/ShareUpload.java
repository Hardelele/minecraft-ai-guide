package hardelele.aiguide.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Sending a share archive, only when the person pressed Send and said yes again (no game classes). Off unless the
 * config names an address: config/aiguide/share.json, {"shareUrl": "", "shareMethod": "post"}; an empty shareUrl (the
 * default) hides the Send button. No address is written in the code.
 *
 * The protocol, one request:
 *   post (default): POST shareUrl, multipart/form-data, part "manifest" (application/json, the archive's manifest.json)
 *                   and part "archive" (application/zip, filename <stamp>-<id>.zip). Any 2xx is taken.
 *   put:            PUT shareUrl with the zip as the body (Content-Type application/zip): for an upload link the
 *                   receiving side gave out (a pre-signed URL); the manifest is inside the zip.
 * Both send X-AIGuide-Share: <format version> and the archive's id in X-AIGuide-Share-Id. Only https, or http to
 * this computer (localhost, 127.0.0.1: the tests' stub server).
 */
final class ShareUpload {
    private ShareUpload() { }

    static final String NAME = "share.json";

    static final class Config {
        String url = "", method = "post";
        int timeoutSec = 120;

        boolean enabled() { return url != null && !url.trim().isEmpty(); }

        static Config read(File f) {
            Config c = new Config();
            if (f == null || !f.isFile()) return c;
            try {
                JsonObject o = new JsonParser().parse(new String(ShareBuild.read(f), StandardCharsets.UTF_8)).getAsJsonObject();
                if (o.has("shareUrl") && !o.get("shareUrl").isJsonNull()) c.url = o.get("shareUrl").getAsString().trim();
                if (o.has("shareMethod") && !o.get("shareMethod").isJsonNull()) c.method = o.get("shareMethod").getAsString().trim().toLowerCase(java.util.Locale.ROOT);
                if (o.has("timeoutSec")) c.timeoutSec = Math.max(5, o.get("timeoutSec").getAsInt());
            } catch (Exception e) {
                c.url = "";   // a broken file sends nothing
            }
            return c;
        }

        /** the template written when there is no file: the address empty */
        static String template() {
            return "{\n  \"shareUrl\": \"\",\n  \"shareMethod\": \"post\",\n  \"timeoutSec\": 120\n}\n";
        }
    }

    /** the host the archive would go to (for the confirmation), or null when the address is not allowed */
    static String host(Config c) {
        try {
            URL u = new URL(c.url);
            if (!allowed(u)) return null;
            return u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (Exception e) {
            return null;
        }
    }

    static boolean allowed(URL u) {
        String p = u.getProtocol(), h = u.getHost();
        if ("https".equals(p)) return true;
        return "http".equals(p) && ("localhost".equalsIgnoreCase(h) || "127.0.0.1".equals(h));
    }

    /** progress of a send: bytes out of all */
    static final class Progress { volatile long sent, total; volatile String answer; volatile String error; volatile boolean finished; }

    /** the archive sent as the config says; the server's answer (its code and the start of its body) */
    static String send(Config c, File zip, String id, byte[] manifest, Progress p) throws IOException {
        if (!c.enabled()) throw new IOException("no shareUrl in the config");
        URL u = new URL(c.url);
        if (!allowed(u)) throw new IOException("only https (or http to this computer)");
        boolean put = "put".equals(c.method);
        HttpURLConnection h = (HttpURLConnection) u.openConnection();
        h.setConnectTimeout(c.timeoutSec * 1000);
        h.setReadTimeout(c.timeoutSec * 1000);
        h.setDoOutput(true);
        h.setUseCaches(false);
        h.setInstanceFollowRedirects(false);
        h.setRequestMethod(put ? "PUT" : "POST");
        h.setRequestProperty("X-AIGuide-Share", String.valueOf(ShareBuild.FORMAT));
        h.setRequestProperty("X-AIGuide-Share-Id", id == null ? "" : id);
        String boundary = "----aiguide" + ShareBuild.newId() + ShareBuild.newId();
        byte[] head, tail;
        if (put) {
            head = new byte[0];
            tail = new byte[0];
            h.setRequestProperty("Content-Type", "application/zip");
        } else {
            h.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            b.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"manifest\"; filename=\"manifest.json\"\r\nContent-Type: application/json\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            b.write(manifest == null ? new byte[0] : manifest);
            b.write(("\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"archive\"; filename=\"" + zip.getName().replace("\"", "")
                    + "\"\r\nContent-Type: application/zip\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            head = b.toByteArray();
            tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        }
        long total = head.length + zip.length() + tail.length;
        if (p != null) p.total = total;
        h.setFixedLengthStreamingMode(total);
        OutputStream o = h.getOutputStream();
        try {
            o.write(head);
            InputStream in = new FileInputStream(zip);
            try {
                byte[] buf = new byte[1 << 16];
                long sent = head.length;
                for (int n; (n = in.read(buf)) > 0; ) { o.write(buf, 0, n); sent += n; if (p != null) p.sent = sent; }
            } finally { in.close(); }
            o.write(tail);
        } finally {
            o.close();
        }
        int code = h.getResponseCode();
        InputStream r = code >= 400 ? h.getErrorStream() : h.getInputStream();
        String body = "";
        if (r != null) {
            try {
                byte[] buf = new byte[512];
                int n = r.read(buf);
                body = n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8).trim() : "";
            } finally { r.close(); }
        }
        h.disconnect();
        if (p != null) p.sent = total;
        if (code / 100 != 2) throw new IOException("HTTP " + code + (body.isEmpty() ? "" : ": " + body));
        return "HTTP " + code + (body.isEmpty() ? "" : ": " + body);
    }

    /** on a thread of its own */
    static Progress start(final Config c, final File zip, final String id, final byte[] manifest) {
        final Progress p = new Progress();
        Thread t = new Thread(new Runnable() {
            public void run() {
                try { p.answer = send(c, zip, id, manifest, p); }
                catch (Throwable e) { p.error = String.valueOf(e.getMessage() == null ? e : e.getMessage()); }
                finally { p.finished = true; }
            }
        }, "aiguide-share-upload");
        t.setDaemon(true);
        t.start();
        return p;
    }
}
