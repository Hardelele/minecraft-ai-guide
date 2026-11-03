package hardelele.aiguide;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.Executors;

/**
 * The assistant's tools for agents outside the game, over MCP (Streamable HTTP, JSON responses only):
 * POST http://host:port/mcp with "Authorization: Bearer <token>". The tools that are about a player
 * take the player's name as an argument.
 */
class McpServer implements HttpHandler {
    static final String PROTOCOL = "2025-06-18";
    final AIGuide mod;
    HttpServer http;
    String token;

    McpServer(AIGuide mod) {
        this.mod = mod;
    }

    void start() {
        try {
            File f = new File(mod.config.mcpTokenFile);
            if (!f.isFile() || AIGuide.text(f).trim().isEmpty()) {
                byte[] b = new byte[24];
                new SecureRandom().nextBytes(b);
                StringBuilder s = new StringBuilder();
                for (byte x : b) s.append(String.format("%02x", x));
                f.getParentFile().mkdirs();
                Files.write(f.toPath(), s.toString().getBytes(Knowledge.UTF8));
                AIGuide.LOG.info("MCP token made: {}", f);
            }
            token = AIGuide.text(f).trim();
            http = HttpServer.create(new InetSocketAddress(mod.config.mcpHost, mod.config.mcpPort), 0);
            http.createContext("/mcp", this);
            http.setExecutor(Executors.newFixedThreadPool(4));
            http.start();
            AIGuide.LOG.info("MCP at http://{}:{}/mcp (token in {})", mod.config.mcpHost, mod.config.mcpPort, mod.config.mcpTokenFile);
        } catch (Exception e) {
            AIGuide.LOG.warn("MCP not started: " + e);
        }
    }

    void stop() {
        if (http != null) http.stop(0);
        http = null;
    }

    @Override public void handle(HttpExchange x) {
        try {
            String auth = x.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !MessageDigest.isEqual(auth.getBytes(Knowledge.UTF8), ("Bearer " + token).getBytes(Knowledge.UTF8))) {
                send(x, 401, "{\"error\":\"unauthorized\"}");
                return;
            }
            if (!x.getRequestMethod().equals("POST")) {
                send(x, 405, "");   // no server-sent events: every answer comes in the POST response
                return;
            }
            JsonElement in = new JsonParser().parse(Assistant.read(x.getRequestBody()));
            if (in.isJsonArray()) {
                JsonArray out = new JsonArray();
                for (JsonElement e : in.getAsJsonArray()) {
                    JsonObject r = rpc(e.getAsJsonObject());
                    if (r != null) out.add(r);
                }
                if (out.size() == 0) send(x, 202, "");
                else send(x, 200, out.toString());
            } else {
                JsonObject r = rpc(in.getAsJsonObject());
                if (r == null) send(x, 202, "");
                else send(x, 200, r.toString());
            }
        } catch (Exception e) {
            AIGuide.LOG.warn("MCP request failed", e);
            try {
                send(x, 400, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32700,\"message\":\"" + e.toString().replace("\"", "'") + "\"}}");
            } catch (Exception ignored) {
                // the client is gone
            }
        }
    }

    static void send(HttpExchange x, int code, String body) throws Exception {
        byte[] b = body.getBytes(Knowledge.UTF8);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            OutputStream o = x.getResponseBody();
            o.write(b);
            o.close();
        }
        x.close();
    }

    /** one JSON-RPC message; null for notifications */
    JsonObject rpc(JsonObject m) {
        if (!m.has("id")) return null;
        String method = m.has("method") ? m.get("method").getAsString() : "";
        JsonObject params = m.has("params") && m.get("params").isJsonObject() ? m.getAsJsonObject("params") : new JsonObject();
        JsonObject res = new JsonObject();
        res.addProperty("jsonrpc", "2.0");
        res.add("id", m.get("id"));
        if (method.equals("initialize")) {
            JsonObject r = new JsonObject();
            r.addProperty("protocolVersion", params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : PROTOCOL);
            JsonObject caps = new JsonObject(), tools = new JsonObject();
            tools.addProperty("listChanged", false);
            caps.add("tools", tools);
            r.add("capabilities", caps);
            JsonObject info = new JsonObject();
            info.addProperty("name", "aiguide");
            info.addProperty("version", AIGuide.VERSION);
            r.add("serverInfo", info);
            r.addProperty("instructions", "A Minecraft 1.7.10 server with mods: " + AIGuide.modList()
                    + ". Item names come in the players' language (" + mod.config.language + "). Guides of the server: "
                    + mod.knowledge.titles.keySet() + ". Tools about a player take their name: list_players first.");
            res.add("result", r);
        } else if (method.equals("ping")) {
            res.add("result", new JsonObject());
        } else if (method.equals("tools/list")) {
            JsonArray list = new JsonArray();
            for (JsonElement e : mod.tools.definitions(true)) {
                JsonObject f = e.getAsJsonObject().getAsJsonObject("function");
                JsonObject t = new JsonObject();
                t.add("name", f.get("name"));
                t.add("description", f.get("description"));
                t.add("inputSchema", f.get("parameters"));
                list.add(t);
            }
            JsonObject r = new JsonObject();
            r.add("tools", list);
            res.add("result", r);
        } else if (method.equals("tools/call")) {
            String name = params.has("name") ? params.get("name").getAsString() : "";
            String args = params.has("arguments") ? params.get("arguments").toString() : "{}";
            String text = mod.tools.call(name, args, null);
            if (mod.config.logTools) AIGuide.LOG.info("MCP: {} {} -> {} chars", name, args, text.length());
            JsonObject r = new JsonObject(), c = new JsonObject();
            JsonArray content = new JsonArray();
            c.addProperty("type", "text");
            c.addProperty("text", text);
            content.add(c);
            r.add("content", content);
            r.addProperty("isError", text.startsWith("error:") || text.startsWith("unknown tool"));
            res.add("result", r);
        } else {
            JsonObject err = new JsonObject();
            err.addProperty("code", -32601);
            err.addProperty("message", "method not found: " + method);
            res.add("error", err);
        }
        return res;
    }
}
