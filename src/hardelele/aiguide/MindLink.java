package hardelele.aiguide;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;

/**
 * The crew agents' minds (the client mod's AgentMind, by reflection, on a thread of its own) ask the model through the
 * guide's own client: one config, one key, one proxy. The mind sends its messages and tools; the model, the length of
 * the answer, the temperature and the API's options come from [mind] (and [api]) of aiguide.cfg. The key is never logged.
 */
final class MindLink {
    private MindLink() { }

    /** one completion: the API's whole answer (the choice and the usage) */
    static String complete(String body) throws Exception {
        Tools t = Tools.instance;
        if (t == null || t.mod.assistant == null) throw new IllegalStateException("no guide on this server");
        GuideConfig cfg = t.mod.config;
        if (cfg.key().isEmpty()) throw new IllegalStateException("no API key (config/aiguide/api.key)");
        JsonObject b = new JsonParser().parse(body).getAsJsonObject();
        b.addProperty("model", cfg.mindModel.isEmpty() ? cfg.model : cfg.mindModel);
        b.addProperty("max_tokens", cfg.mindMaxTokens);
        b.addProperty("temperature", cfg.mindTemperature);
        for (Map.Entry<String, JsonElement> e : t.mod.assistant.extra().entrySet()) if (!b.has(e.getKey())) b.add(e.getKey(), e.getValue());
        long t0 = System.currentTimeMillis();
        String r = t.mod.assistant.post(b, cfg.timeoutSeconds);
        if (cfg.logTools) AIGuide.LOG.info("mind: asked {} chars, answered {} chars in {} ms", body.length(), r.length(), System.currentTimeMillis() - t0);
        return r;
    }

    /** the limits of one goal, for the client mod: {"steps", "minutes", "tokens"} */
    static String config() {
        JsonObject o = new JsonObject();
        Tools t = Tools.instance;
        if (t == null) return o.toString();
        o.addProperty("steps", t.mod.config.mindSteps);
        o.addProperty("minutes", t.mod.config.mindMinutes);
        o.addProperty("tokens", t.mod.config.mindTokens);
        return o.toString();
    }
}
