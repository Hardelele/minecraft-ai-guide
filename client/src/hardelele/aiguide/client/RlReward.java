package hardelele.aiguide.client;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The reward of an episode, by agent and by component, with no game in it: RlEnv tells what happened (a weight's key
 * and how many: "mine.iron" 2, "time" 0.05 s, "death" 1), each agent's sum grows between its decisions and is taken
 * at its next one (or at the episode's end). The weights are RlSpec's (rl/reward.json); a scale's component counts
 * only as much as the scenario turns it on (RlSpec.Scenario.scales: 0 off). RlTest checks it.
 */
final class RlReward {
    final Map<String, Double> weights;
    final RlSpec.Scenario scenario;
    /** agent -> component -> points since its last decision */
    final Map<String, Map<String, Double>> open = new TreeMap<String, Map<String, Double>>();
    /** component -> points over the whole episode (all agents) */
    final Map<String, Double> episode = new TreeMap<String, Double>();
    /** component -> the raw amount told over the episode, paid or not (what a scale measured where it is off too) */
    final Map<String, Double> measured = new TreeMap<String, Double>();
    double total;

    RlReward(Map<String, Double> weights, RlSpec.Scenario scenario) { this.weights = weights; this.scenario = scenario; }

    /** how much a weight's key counts in this scenario: a part of a scale named by the scenario its factor, else its scale's (0 when off), the others 1 */
    double factor(String key) {
        String comp = RlSpec.component(key);
        if (!RlSpec.isScale(comp) || scenario == null) return 1;
        Double part = scenario.scales.get(key);
        return part != null ? part : scenario.scale(comp);
    }

    /** the points of n of a weight's key for an agent (unknown key: an error, the contract has none such) */
    double add(String agent, String key, double n) {
        Double w = weights.get(key);
        if (w == null) throw new IllegalArgumentException("no reward weight " + key);
        String comp = RlSpec.component(key);
        if (n != 0) measured.put(key, (measured.containsKey(key) ? measured.get(key) : 0) + n);
        double p = w * n * factor(key);
        if (p == 0) return 0;
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

    /** what each key measured over the episode (paid or not): the metrics of a run */
    JsonObject measuredJson() {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, Double> e : measured.entrySet()) o.addProperty(e.getKey(), round(e.getValue()));
        return o;
    }
}
