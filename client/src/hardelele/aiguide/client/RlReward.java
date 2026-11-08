package hardelele.aiguide.client;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The reward of an episode, by agent and by component, with no game in it: RlEnv tells what happened (a weight's key
 * and how many: "gathered.log" 5, "time" 0.05 s, "death" 1), each agent's sum grows between its decisions and is taken
 * at its next one (or at the episode's end). The weights are RlSpec's (rl/reward.json). RlTest checks it.
 */
final class RlReward {
    final Map<String, Double> weights;
    /** agent -> component -> points since its last decision */
    final Map<String, Map<String, Double>> open = new TreeMap<String, Map<String, Double>>();
    /** component -> points over the whole episode (all agents) */
    final Map<String, Double> episode = new TreeMap<String, Double>();
    double total;

    RlReward(Map<String, Double> weights) { this.weights = weights; }

    /** the points of n of a weight's key for an agent (unknown key: an error, the contract has none such) */
    double add(String agent, String key, double n) {
        Double w = weights.get(key);
        if (w == null) throw new IllegalArgumentException("no reward weight " + key);
        double p = w * n;
        if (p == 0) return 0;
        String comp = RlSpec.component(key);
        Map<String, Double> m = open.get(agent);
        if (m == null) open.put(agent, m = new LinkedHashMap<String, Double>());
        m.put(comp, (m.containsKey(comp) ? m.get(comp) : 0) + p);
        episode.put(comp, (episode.containsKey(comp) ? episode.get(comp) : 0) + p);
        total += p;
        return p;
    }

    /** the agent's points since its last decision (its components cleared) */
    double take(String agent, JsonObject components) {
        Map<String, Double> m = open.remove(agent);
        double s = 0;
        if (m == null) return 0;
        for (Map.Entry<String, Double> e : m.entrySet()) {
            s += e.getValue();
            if (components != null) components.addProperty(e.getKey(), round(e.getValue()));
        }
        return s;
    }

    static double round(double v) { return Math.round(v * 1e6) / 1e6; }

    JsonObject episodeJson() {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, Double> e : episode.entrySet()) o.addProperty(e.getKey(), round(e.getValue()));
        o.addProperty("total", round(total));
        return o;
    }
}
