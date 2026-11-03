package hardelele.aiguide;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** Talks to the model for each question, off the server thread, with tool calls in between. */
class Assistant {
    final AIGuide mod;
    final Map<String, List<JsonObject>> history = new HashMap<String, List<JsonObject>>();
    final Set<String> busy = new HashSet<String>();
    final ExecutorService pool = Executors.newFixedThreadPool(2, new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "AIGuide");
            t.setDaemon(true);
            return t;
        }
    });

    Assistant(AIGuide mod) {
        this.mod = mod;
    }

    /** answers in chat later; false when the player already waits for an answer */
    boolean ask(final String player, final String question) {
        synchronized (busy) {
            if (!busy.add(player)) return false;
        }
        pool.submit(new Runnable() {
            @Override public void run() {
                try {
                    mod.say(player, answer(player, question));
                } catch (Throwable t) {
                    AIGuide.LOG.warn("question of " + player + " failed", t);
                    mod.say(player, mod.msg("error") + " " + t.getMessage());
                } finally {
                    synchronized (busy) {
                        busy.remove(player);
                    }
                }
            }
        });
        return true;
    }

    void forget(String player) {
        synchronized (history) {
            history.remove(player);
        }
    }

    String answer(String player, String question) throws Exception {
        if (mod.config.key().isEmpty()) return mod.msg("nokey");
        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt(player)));
        List<JsonObject> past;
        synchronized (history) {
            past = history.containsKey(player) ? new ArrayList<JsonObject>(history.get(player)) : new ArrayList<JsonObject>();
        }
        for (JsonObject m : past) messages.add(m);
        JsonObject user = message("user", question);
        messages.add(user);
        long t0 = System.currentTimeMillis();
        int calls = 0;
        for (int round = 0; ; round++) {
            boolean last = round >= mod.config.maxToolRounds;
            if (last) messages.add(message("system", "No more lookups: answer the player now with what you have found."));
            JsonObject msg = complete(messages, !last);
            JsonArray tc = msg.has("tool_calls") && msg.get("tool_calls").isJsonArray() ? msg.getAsJsonArray("tool_calls") : null;
            if (tc == null || tc.size() == 0 || last) {
                String text = msg.has("content") && !msg.get("content").isJsonNull() ? msg.get("content").getAsString().trim() : "";
                // a model that still wants a tool may write the call as text: that is not for the chat
                text = text.replaceAll("(?s)<[｜|]+DSML.*", "").replaceAll("(?s)<tool_call>.*", "").trim();
                AIGuide.LOG.info("{} asked ({} lookups, {} ms): {}", player, calls, System.currentTimeMillis() - t0, question);
                remember(player, user, message("assistant", text));
                return text.isEmpty() ? mod.msg("empty") : text;
            }
            messages.add(msg);   // as it came, with reasoning: some APIs want it back during tool calls
            for (JsonElement e : tc) {
                JsonObject c = e.getAsJsonObject();
                JsonObject f = c.getAsJsonObject("function");
                String args = f.has("arguments") ? f.get("arguments").getAsString() : "{}";
                String result = mod.tools.call(f.get("name").getAsString(), args, player);
                if (mod.config.logTools) AIGuide.LOG.info("{}: {} {} -> {} chars", player, f.get("name").getAsString(), args, result.length());
                calls++;
                JsonObject r = new JsonObject();
                r.addProperty("role", "tool");
                r.addProperty("tool_call_id", c.get("id").getAsString());
                r.addProperty("content", result);
                messages.add(r);
            }
        }
    }

    void remember(String player, JsonObject q, JsonObject a) {
        synchronized (history) {
            List<JsonObject> h = history.get(player);
            if (h == null) history.put(player, h = new ArrayList<JsonObject>());
            h.add(q);
            h.add(a);
            while (h.size() > mod.config.historyMessages) h.remove(0);
        }
    }

    static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    String systemPrompt(String player) {
        String p = AIGuide.text(new File(mod.config.dir, "system.md"));
        String about = mod.profiles.about(player), notes = mod.profiles.notes(player);
        return p.replace("{name}", mod.config.assistantName)
                .replace("{language}", mod.config.language)
                .replace("{player}", player)
                .replace("{mods}", AIGuide.modList())
                .replace("{guides}", mod.knowledge.index())
                .replace("{about}", about.isEmpty() ? "(nothing yet)" : about)
                .replace("{notes}", notes.isEmpty() ? "(none yet)" : notes);
    }

    JsonObject complete(JsonArray messages, boolean tools) throws Exception {
        JsonObject extra;
        try {
            extra = new JsonParser().parse(mod.config.extraBody).getAsJsonObject();
        } catch (Exception e) {
            AIGuide.LOG.warn("extraBody is not a JSON object: " + mod.config.extraBody);
            extra = new JsonObject();
        }
        // tools stay declared on the last round (some models write calls as text otherwise), but may not be called
        if (!tools) extra.addProperty("tool_choice", "none");
        return request(messages, mod.tools.definitions(), mod.config.model, mod.config.maxTokens, extra, mod.config.timeoutSeconds);
    }

    /** one chat completion; the message the model answered with */
    JsonObject request(JsonArray messages, JsonArray tools, String model, int maxTokens, JsonObject extra, int timeoutSeconds) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.addProperty("max_tokens", maxTokens);
        body.addProperty("temperature", mod.config.temperature);
        if (tools != null) body.add("tools", tools);
        for (Map.Entry<String, JsonElement> e : extra.entrySet()) body.add(e.getKey(), e.getValue());
        String url = mod.config.baseUrl.replaceAll("/+$", "") + "/chat/completions";
        Proxy proxy = Proxy.NO_PROXY;
        if (!mod.config.proxy.isEmpty()) {
            String[] hp = mod.config.proxy.split(":");
            proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(hp[0], Integer.parseInt(hp[1])));
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(proxy);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(timeoutSeconds * 1000);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + mod.config.key());
        OutputStream out = c.getOutputStream();
        out.write(body.toString().getBytes(Knowledge.UTF8));
        out.close();
        int code = c.getResponseCode();
        String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
        if (code >= 400) throw new Exception("API " + code + ": " + (text.length() > 300 ? text.substring(0, 300) : text));
        JsonObject r = new JsonParser().parse(text).getAsJsonObject();
        return r.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");
    }

    static String read(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        in.close();
        return new String(b.toByteArray(), Knowledge.UTF8);
    }
}
