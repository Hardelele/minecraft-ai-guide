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
 * config names an address: config/aiguide/share.json, {"shareService": "", "shareUrl": "", "shareMethod": "post",
 * "installId": <random>}; both empty (the default) hide the Send button. No address is written in the code.
 *
 * shareService (first when set): the share service (tools/share-service) gives a one-time presigned PUT link to its
 * storage for this install's archive (its size and SHA-256 asked for up front, quotas by the install id), the zip is
 * PUT there, then the service is told it is complete. See viaService.
 *
 * shareUrl, one request:
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
        String url = "", method = "post", service = "", installId = "";
        int timeoutSec = 120;

        /** the share service (tools/share-service) comes first: it gives a one-time link to the storage */
        boolean viaService() { return service != null && !service.trim().isEmpty(); }

        boolean enabled() { return viaService() || url != null && !url.trim().isEmpty(); }

        static Config read(File f) {
            Config c = new Config();
            if (f == null || !f.isFile()) return c;
            try {
                JsonObject o = new JsonParser().parse(new String(ShareBuild.read(f), StandardCharsets.UTF_8)).getAsJsonObject();
                c.url = str(o, "shareUrl");
                c.service = str(o, "shareService");
                c.installId = str(o, "installId");
                if (!str(o, "shareMethod").isEmpty()) c.method = str(o, "shareMethod").toLowerCase(java.util.Locale.ROOT);
                if (o.has("timeoutSec")) c.timeoutSec = Math.max(5, o.get("timeoutSec").getAsInt());
            } catch (Exception e) {
                c.url = c.service = "";   // a broken file sends nothing
            }
            return c;
        }

        static String str(JsonObject o, String k) {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString().trim() : "";
        }

        /** the template written when there is no file: the addresses empty */
        static String template() {
            return "{\n  \"shareService\": \"\",\n  \"shareUrl\": \"\",\n  \"shareMethod\": \"post\",\n  \"timeoutSec\": 120\n}\n";
        }

        /**
         * This game's install id in the file (made the first time: random, nothing to do with the player's name; the
         * service counts its quota by it). The file's other keys are kept; a broken file is left alone (no id).
         */
        static String installId(File f) {
            try {
                JsonObject o = f.isFile() ? new JsonParser().parse(new String(ShareBuild.read(f), StandardCharsets.UTF_8)).getAsJsonObject()
                        : new JsonParser().parse(template()).getAsJsonObject();
                String id = str(o, "installId");
                if (id.matches("[0-9a-f]{32}")) return id;
                byte[] b = new byte[16];
                new java.security.SecureRandom().nextBytes(b);
                id = Labels.hex(b);
                o.addProperty("installId", id);
                File dir = f.getAbsoluteFile().getParentFile();
                if (dir != null) dir.mkdirs();
                java.nio.file.Files.write(f.toPath(), (new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(o) + "\n").getBytes(StandardCharsets.UTF_8));
                return id;
            } catch (Exception e) {
                return "";
            }
        }
    }

    /** a refusal the person should read in words: the service's error and its reason (a quota's name), when to try again */
    static final class Refused extends IOException {
        final String error, reason;
        final long retryAfter, maxSize;
        Refused(String error, String reason, long retryAfter, long maxSize) {
            super(error + (reason.isEmpty() ? "" : ": " + reason));
            this.error = error; this.reason = reason; this.retryAfter = retryAfter; this.maxSize = maxSize;
        }
    }

    /** the host the archive would go to (for the confirmation), or null when the address is not allowed */
    static String host(Config c) {
        try {
            URL u = new URL(c.viaService() ? c.service : c.url);
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

    /** progress of a send: the phase (ask, put, complete; one request: put), bytes out of all; a refusal kept whole */
    static final class Progress {
        volatile long sent, total;
        volatile String answer, error, phase = "put";
        volatile Refused refused;
        volatile boolean finished;
    }

    /** the archive sent as the config says; the server's answer (its code and the start of its body) */
    static String send(Config c, File zip, String id, byte[] manifest, Progress p) throws IOException {
        if (!c.enabled()) throw new IOException("no shareUrl or shareService in the config");
        if (c.viaService()) return viaService(c, zip, manifest, p);
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

    // ---- through the share service: a link asked for, the archive put there, the upload completed ----------------

    /**
     * POST <service>/v1/upload {install_id, size, sha256, manifest_summary} -> a presigned PUT link and the headers to
     * send with it; PUT the zip there; POST <service>/v1/complete {upload_id, install_id}. A refusal (a quota, too
     * large, not configured) is thrown as Refused.
     */
    static String viaService(Config c, File zip, byte[] manifest, Progress p) throws IOException {
        String base = c.service.replaceAll("/+$", "");
        if (!allowed(new URL(base))) throw new IOException("only https (or http to this computer)");
        if (!c.installId.matches("[0-9a-f]{32}")) throw new IOException("no installId in the config");
        if (p != null) p.phase = "ask";
        JsonObject ask = new JsonObject();
        ask.addProperty("install_id", c.installId);
        ask.addProperty("size", zip.length());
        ask.addProperty("sha256", ShareBuild.sha256(zip));
        ask.add("manifest_summary", summary(manifest));
        JsonObject link = post(base + "/v1/upload", ask, c.timeoutSec);
        URL put = new URL(link.get("url").getAsString());
        if (!allowed(put)) throw new IOException("the storage link is not https");
        if (p != null) { p.phase = "put"; p.total = zip.length(); }
        HttpURLConnection h = (HttpURLConnection) put.openConnection();
        h.setConnectTimeout(c.timeoutSec * 1000);
        h.setReadTimeout(c.timeoutSec * 1000);
        h.setDoOutput(true);
        h.setUseCaches(false);
        h.setInstanceFollowRedirects(false);
        h.setRequestMethod("PUT");
        if (link.has("headers")) for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : link.getAsJsonObject("headers").entrySet())
            if (!e.getKey().equalsIgnoreCase("content-length")) h.setRequestProperty(e.getKey(), e.getValue().getAsString());   // the length: by the streaming mode
        h.setFixedLengthStreamingMode(zip.length());
        OutputStream o = h.getOutputStream();
        try {
            InputStream in = new FileInputStream(zip);
            try {
                byte[] buf = new byte[1 << 16];
                long sent = 0;
                for (int n; (n = in.read(buf)) > 0; ) { o.write(buf, 0, n); sent += n; if (p != null) p.sent = sent; }
            } finally { in.close(); }
        } finally {
            o.close();
        }
        int code = h.getResponseCode();
        read(code >= 400 ? h.getErrorStream() : h.getInputStream(), 512);
        h.disconnect();
        if (code / 100 != 2) throw new Refused("storage", String.valueOf(code), 0, 0);
        if (p != null) p.phase = "complete";
        JsonObject done = new JsonObject();
        done.addProperty("upload_id", link.get("upload_id").getAsString());
        done.addProperty("install_id", c.installId);
        JsonObject r = post(base + "/v1/complete", done, c.timeoutSec);
        return "upload " + link.get("upload_id").getAsString() + ": " + (r.has("status") ? r.get("status").getAsString() : "?");
    }

    /** what the service keeps about an archive: its id, format, files and what was ticked (no names, no paths) */
    static JsonObject summary(byte[] manifest) {
        JsonObject s = new JsonObject();
        try {
            JsonObject m = new JsonParser().parse(new String(manifest, StandardCharsets.UTF_8)).getAsJsonObject();
            for (String k : new String[] { "id", "version", "created", "aiguide", "minecraft", "radiusChunks" }) if (m.has(k)) s.add(k, m.get(k));
            if (m.has("files")) s.addProperty("files", m.getAsJsonArray("files").size());
            if (m.has("consent") && m.getAsJsonObject("consent").has("items")) s.add("items", m.getAsJsonObject("consent").get("items"));
        } catch (Exception e) {
            // no manifest: an empty summary
        }
        return s;
    }

    /** a JSON request to the service and its JSON answer; a refusal as Refused */
    static JsonObject post(String url, JsonObject body, int timeoutSec) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(timeoutSec * 1000);
        h.setReadTimeout(timeoutSec * 1000);
        h.setDoOutput(true);
        h.setInstanceFollowRedirects(false);
        h.setRequestMethod("POST");
        h.setRequestProperty("Content-Type", "application/json");
        byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
        h.setFixedLengthStreamingMode(b.length);
        OutputStream o = h.getOutputStream();
        try { o.write(b); } finally { o.close(); }
        int code = h.getResponseCode();
        String text = read(code >= 400 ? h.getErrorStream() : h.getInputStream(), 64 * 1024);
        h.disconnect();
        JsonObject r;
        try { r = new JsonParser().parse(text).getAsJsonObject(); } catch (Exception e) { r = new JsonObject(); }
        if (code / 100 == 2) return r;
        String error = Config.str(r, "error"), reason = Config.str(r, "reason");
        long retry = r.has("retry_after") ? r.get("retry_after").getAsLong() : 0, max = r.has("max_size") ? r.get("max_size").getAsLong() : 0;
        throw new Refused(error.isEmpty() ? "http" : error, error.isEmpty() ? String.valueOf(code) : reason, retry, max);
    }

    static String read(InputStream in, int max) throws IOException {
        if (in == null) return "";
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; o.size() < max && (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
            return new String(o.toByteArray(), StandardCharsets.UTF_8).trim();
        } finally { in.close(); }
    }

    /** on a thread of its own */
    static Progress start(final Config c, final File zip, final String id, final byte[] manifest) {
        final Progress p = new Progress();
        Thread t = new Thread(new Runnable() {
            public void run() {
                try { p.answer = send(c, zip, id, manifest, p); }
                catch (Refused e) { p.refused = e; p.error = e.getMessage(); }
                catch (Throwable e) { p.error = String.valueOf(e.getMessage() == null ? e : e.getMessage()); }
                finally { p.finished = true; }
            }
        }, "aiguide-share-upload");
        t.setDaemon(true);
        t.start();
        return p;
    }
}
