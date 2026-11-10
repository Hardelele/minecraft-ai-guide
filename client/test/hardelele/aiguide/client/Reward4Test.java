package hardelele.aiguide.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RlReward4 against tools/rl/reward_core.py (docs/rl/REWARD-v4.md): the cases tools/tests/test_rl_exploits.py writes
 * (-Daiguide.rl.reward4.cases: the frames of each loophole and owner's decision, what the Python gave) played through
 * the Java and compared: each agent's points by component, the episode's, the moves to decisions; the built-in
 * parameters are rl/reward-v4.json's.
 */
public final class Reward4Test {
    static int bad, passed;

    static void check(boolean ok, String what) {
        if (ok) passed++;
        else { bad++; System.out.println("FAIL " + what); }
    }

    static boolean near(double a, double b) { return Math.abs(a - b) <= 1e-9 * Math.max(1, Math.abs(b)); }

    public static void main(String[] args) throws Exception {
        String f = System.getProperty("aiguide.rl.reward4.cases");
        if (f == null || !new File(f).isFile()) { System.out.println("Reward4Test: no cases (run tools/tests/test_rl_exploits.py --cases-only)"); System.exit(1); }
        JsonObject all = new JsonParser().parse(new String(Files.readAllBytes(new File(f).toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject params = all.getAsJsonObject("params");
        check(sameJson(RlReward4.defaults(), params), "RlReward4.DEFAULTS are rl/reward-v4.json's");
        Map<String, Double> sw = new LinkedHashMap<String, Double>();
        for (Map.Entry<String, JsonElement> e : all.getAsJsonObject("scale_weights").entrySet()) sw.put(e.getKey(), e.getValue().getAsDouble());
        JsonObject ss = all.getAsJsonObject("scenario_scales");
        int n = 0;
        for (JsonElement ce : all.getAsJsonArray("cases")) {
            JsonObject c = ce.getAsJsonObject();
            String name = c.get("name").getAsString();
            Map<String, Double> fac = null;
            if (c.has("scenario") && !c.get("scenario").isJsonNull()) {
                fac = new LinkedHashMap<String, Double>();
                JsonObject s = ss.getAsJsonObject(c.get("scenario").getAsString());
                if (s != null) for (Map.Entry<String, JsonElement> e : s.entrySet()) fac.put(e.getKey(), e.getValue().getAsDouble());
            }
            RlReward4 r = new RlReward4(params, sw, fac);
            JsonArray moves = new JsonArray();
            for (JsonElement fr : c.getAsJsonArray("frames")) for (JsonObject m : r.frame(fr.getAsJsonObject())) moves.add(m);
            JsonObject exp = c.getAsJsonObject("expect");
            check(near(r.total, exp.get("total").getAsDouble()), name + ": total " + r.total + " vs " + exp.get("total"));
            for (Map.Entry<String, JsonElement> e : exp.getAsJsonObject("episode").entrySet()) {
                Double v = r.episode.get(e.getKey());
                check(v != null && near(v, e.getValue().getAsDouble()) || v == null && Math.abs(e.getValue().getAsDouble()) < 1e-12,
                        name + ": episode " + e.getKey() + " " + v + " vs " + e.getValue());
            }
            check(r.episode.size() == exp.getAsJsonObject("episode").entrySet().size(), name + ": the episode's components " + r.episode.keySet());
            for (Map.Entry<String, JsonElement> e : exp.getAsJsonObject("agents").entrySet()) {
                JsonObject comps = new JsonObject();
                double t = r.take(e.getKey(), comps);
                check(near(t, e.getValue().getAsJsonObject().get("total").getAsDouble()), name + ": agent " + e.getKey() + " " + t);
            }
            JsonArray em = exp.getAsJsonArray("moves");
            boolean same = em.size() == moves.size();
            for (int i = 0; same && i < em.size(); i++) {
                JsonObject a = moves.get(i).getAsJsonObject(), b = em.get(i).getAsJsonObject();
                same = a.get("agent").getAsString().equals(b.get("agent").getAsString()) && a.get("key").getAsString().equals(b.get("key").getAsString())
                        && near(a.get("points").getAsDouble(), b.get("points").getAsDouble())
                        && a.getAsJsonArray("chain").get(0).getAsJsonObject().get("decision_id").getAsLong() == b.getAsJsonArray("chain").get(0).getAsJsonObject().get("decision_id").getAsLong();
                if (!same) System.out.println("  move " + i + ": " + a + " vs " + b);
            }
            check(same, name + ": the moves (" + moves.size() + " vs " + em.size() + ")");
            n++;
        }
        check(n > 20, "the cases played: " + n);
        check(RlReward4.afkIntegral(400, 0.01, 30, 120) - RlReward4.afkIntegral(399, 0.01, 30, 120) == 0.01 * 1 || near(RlReward4.afkIntegral(400, 0.01, 30, 120) - RlReward4.afkIntegral(399, 0.01, 30, 120), 0.01), "afk at its cap");
        System.out.println("Reward4Test: " + passed + " passed, " + bad + " failed (" + n + " cases)");
        System.exit(bad == 0 ? 0 : 1);
    }

    static boolean sameJson(JsonElement a, JsonElement b) {
        if (a.isJsonObject() && b.isJsonObject()) {
            JsonObject x = a.getAsJsonObject(), y = b.getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : x.entrySet()) if (!y.has(e.getKey()) || !sameJson(e.getValue(), y.get(e.getKey()))) { System.out.println("  differs: " + e.getKey()); return false; }
            for (Map.Entry<String, JsonElement> e : y.entrySet())
                if (!x.has(e.getKey()) && !e.getKey().equals("version") && !e.getKey().equals("contract") && !e.getKey().equals("note")) { System.out.println("  not built in: " + e.getKey()); return false; }
            return true;
        }
        if (a.isJsonArray() && b.isJsonArray()) {
            if (a.getAsJsonArray().size() != b.getAsJsonArray().size()) return false;
            for (int i = 0; i < a.getAsJsonArray().size(); i++) if (!sameJson(a.getAsJsonArray().get(i), b.getAsJsonArray().get(i))) return false;
            return true;
        }
        if (a.isJsonPrimitive() && b.isJsonPrimitive() && a.getAsJsonPrimitive().isNumber() && b.getAsJsonPrimitive().isNumber()) return a.getAsDouble() == b.getAsDouble();
        return a.equals(b);
    }

    static List<String> unused;
}
