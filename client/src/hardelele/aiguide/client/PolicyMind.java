package hardelele.aiguide.client;

import java.io.File;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;
import net.minecraft.world.WorldServer;

/**
 * The minds' own brain: the crew's policy net (PolicyNet, trained by tools/rl/train.py) decides an agent's next skill
 * at its fork instead of a language model. The default; -Daiguide.mind=llm brings the model back (DeepSeek through the
 * server mod). The agent's raw facts (PolicyFacts, RlSpec's names) become the observation and the mask (RlSpec.vector,
 * RlSpec.mask: the same code as the learning environment's), the net picks an allowed action, RlSpec.call makes it a
 * mind's call, AgentMind does it as any other. In the game noop is masked (the world does not restart here); a goal
 * with an item is done when the item is there. No thread, no wait: a decision takes well under a millisecond.
 *
 * -Daiguide.policy.weights=<file> (else the jar's /assets/aiguide/rl/policy.bin), -Daiguide.policy.sample=true (draw
 * by the net's probabilities instead of the best: more varied, as in training).
 */
final class PolicyMind {
    private PolicyMind() { }

    static final String RESOURCE = "/assets/aiguide/rl/policy.bin";
    static volatile PolicyNet net;
    static volatile String error = "";
    static PolicyNet.Work work;
    static final Random rnd = new Random();
    static final Map<AgentMind, PolicyFacts.Memo> memos = new WeakHashMap<AgentMind, PolicyFacts.Memo>();
    static final Map<AgentMind, Integer> seenSteps = new WeakHashMap<AgentMind, Integer>();

    /** the policy decides (the default), not the model */
    static boolean on() {
        return !"llm".equalsIgnoreCase(System.getProperty("aiguide.mind", "policy").trim());
    }

    static boolean sample() {
        return Boolean.parseBoolean(System.getProperty("aiguide.policy.sample", "false"));
    }

    /** the net, loaded once (null and error set when there is none or it does not fit RlSpec) */
    static synchronized PolicyNet net() {
        if (net != null || !error.isEmpty()) return net;
        try {
            String f = System.getProperty("aiguide.policy.weights", "");
            PolicyNet n;
            if (!f.isEmpty()) n = PolicyNet.load(new File(f));
            else {
                InputStream in = PolicyMind.class.getResourceAsStream(RESOURCE);
                if (in == null) throw new IllegalStateException("no " + RESOURCE + " in the jar");
                try { n = PolicyNet.load(in); } finally { in.close(); }
            }
            if (n.d != RlSpec.FEATURES.size() || n.a != RlSpec.ACTIONS.size())
                throw new IllegalStateException("the policy's net takes " + n.d + " features and gives " + n.a + " actions; RlSpec has " + RlSpec.FEATURES.size() + " and " + RlSpec.ACTIONS.size());
            net = n;
            work = n.work();
            Events.log("mind: the policy's net is loaded (" + n.d + " features, " + n.h + " hidden, " + n.a + " actions)" + (f.isEmpty() ? "" : " from " + f));
        } catch (Exception e) {
            error = "no policy: " + e.getMessage();
            Events.log("mind: " + error);
        }
        return net;
    }

    // ---- the goal as a scenario ----------------------------------------------------------------------------

    /** the learning scenario nearest to a goal: what the net was taught for it */
    static String scenario(Mind.Goal g, boolean wavesOn) {
        if (g.defense) return wavesOn ? "waves:normal" : "fortify";
        String it = g.item == null ? "" : g.item.toLowerCase(Locale.ROOT);
        if (!it.isEmpty()) {
            if (it.startsWith("iron")) return "iron";
            if (it.endsWith("_pickaxe")) return "pickaxe";
            if (it.endsWith("_sword")) return "sword";
            if (it.equals("log") || it.equals("log2") || it.equals("planks") || it.equals("stick")) return "wood";
            if (it.matches("beef|porkchop|chicken|bread|cooked_.*|apple|fish|mutton")) return "food";
        }
        String t = g.text.toLowerCase(Locale.ROOT);
        if (t.matches("(?s).*(желез|iron).*")) return "iron";
        if (t.matches("(?s).*(кирк|pickaxe).*")) return "pickaxe";
        if (t.matches("(?s).*(меч|sword|оруж).*")) return "sword";
        if (t.matches("(?s).*(ед[аыу]|food|охот|мяс|hunt).*")) return "food";
        if (t.matches("(?s).*(стен|укреп|ров|wall|fort).*")) return "fortify";
        return "wood";
    }

    // ---- a decision ----------------------------------------------------------------------------------------

    /** the agent's next call (or done when its goal's item is there); null when there is no net (error says why) */
    static Mind.Call decide(AgentMind m, Crew c, Agent a, WorldServer w) {
        PolicyNet n = net();
        if (n == null) return null;
        PolicyFacts.Memo mm = memos.get(m);
        if (mm == null) memos.put(m, mm = new PolicyFacts.Memo());
        WaveCycle.View v = Waves.state();
        mm.scenario = scenario(m.goal, v.active());
        RlSpec.Scenario sc = RlSpec.scenario(mm.scenario);
        double limit = sc == null ? 600 : sc.seconds;
        mm.timeFrac = Math.min(1, (System.currentTimeMillis() - m.mind.startedMs) / 1000.0 / limit);
        lastStep(m, mm);
        if (m.goal.item != null && m.goal.count > 0) {
            int have = m.have(c, w);
            mm.progress = Math.min(1, have / (double) m.goal.count);
            if (have >= m.goal.count) {
                Mind.Call d = new Mind.Call();
                d.tool = "done";
                d.args.addProperty("summary", "Готово: " + m.goal.want() + ".");
                d.why = "policy: the goal's item is there";
                return d;
            }
        }
        long t0 = System.nanoTime();
        Map<String, Double> raw = PolicyFacts.of(c, a, w, mm);
        float[] obs = RlSpec.vector(raw);
        int[] mask = RlSpec.mask(raw);
        mask[0] = 0;   // noop: in the game the world does not restart; look_around and wait:5 are always there
        float[] probs = new float[n.a];
        int k;
        synchronized (PolicyMind.class) {
            k = work.act(obs, mask, sample() ? rnd : null, probs);
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        if (k < 0) k = RlSpec.index("wait:5");
        mm.last = k;
        Mind.Call cl = RlSpec.call(k);
        cl.why = "policy: " + RlSpec.ACTIONS.get(k).id + String.format(Locale.ROOT, " (p %.2f, %s)", probs[k], mm.scenario);
        cl.args.addProperty("why", cl.why);
        Events.log("mind: " + a.who() + " policy -> " + RlSpec.ACTIONS.get(k).id + String.format(Locale.ROOT, " (p %.2f, scenario %s, %.2f ms)", probs[k], mm.scenario, ms));
        return cl;
    }

    /** the outcome of the agent's last step into the memo (its group, how long, how many times the same failure) */
    static void lastStep(AgentMind m, PolicyFacts.Memo mm) {
        int n = m.mind.steps.size();
        Integer was = seenSteps.get(m);
        seenSteps.put(m, n);
        if (was != null && was == n) return;
        Mind.Step s = m.mind.steps.peekLast();
        if (s == null) return;
        String out = RlSpec.outcome(s.result);
        if (!out.equals("ok") && out.equals(mm.outcome)) mm.repeated++;
        else mm.repeated = out.equals("ok") ? 0 : 1;
        mm.outcome = out;
        mm.seconds = s.seconds;
    }
}
