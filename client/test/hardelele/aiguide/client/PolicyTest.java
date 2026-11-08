package hardelele.aiguide.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * The crew policy's net against PyTorch (-Daiguide.policy.weights, -Daiguide.policy.test: policy.bin and
 * policy-test.bin of tools/rl/export_policy.py): logits within 1e-4 (relative past 1), the same masked argmax; a
 * masked action never drawn; RlSpec's vector and mask the same as tools/rl/spec.py's on the simulator's raw facts
 * (-Daiguide.rl.cases, written by tools/tests/test_rl_policy.py); the time of a decision.
 */
public class PolicyTest {
    static int bad;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) bad++;
    }

    public static void main(String[] args) throws Exception {
        choiceHonoursMask();
        specMatchesPython(new File(System.getProperty("aiguide.rl.cases", "build/rl/obs-mask-cases.json")));
        File weights = new File(System.getProperty("aiguide.policy.weights", "res/assets/aiguide/rl/policy.bin"));
        File test = new File(System.getProperty("aiguide.policy.test", "rl/policy-test.bin"));
        if (!weights.isFile() || !test.isFile()) {
            System.out.println("skip PolicyTest's check against PyTorch: no " + weights + " or " + test);
            System.exit(bad == 0 ? 0 : 1);
        }
        PolicyNet net = PolicyNet.load(weights);
        check(net.d == RlSpec.FEATURES.size() && net.a == RlSpec.ACTIONS.size(), "the net fits RlSpec (" + net.d + " features, " + net.a + " actions)");
        FileInputStream in = new FileInputStream(test);
        Map<String, float[]> t;
        try { t = PolicyNet.tensors(PolicyNet.bytes(in), PolicyNet.TEST_MAGIC, 1, null); } finally { in.close(); }
        float[] obs = t.get("obs"), mask = t.get("mask"), logits = t.get("logits"), action = t.get("action");
        int n = action.length;
        PolicyNet.Work w = net.work();
        double worst = 0, rel = 0;
        int same = 0;
        for (int i = 0; i < n; i++) {
            float[] o = Arrays.copyOfRange(obs, i * net.d, (i + 1) * net.d);
            int[] m = new int[net.a];
            for (int k = 0; k < net.a; k++) m[k] = mask[i * net.a + k] > 0.5f ? 1 : 0;
            float[] lg = w.forward(o);
            for (int k = 0; k < net.a; k++) {
                double d = Math.abs(lg[k] - logits[i * net.a + k]);
                worst = Math.max(worst, d);
                rel = Math.max(rel, d / Math.max(1, Math.abs(logits[i * net.a + k])));
            }
            if (PolicyNet.choose(lg, m, null, null) == (int) action[i]) same++;
        }
        System.out.printf("logits of %d cases: max |java - torch| %.3g (relative %.3g)%n", n, worst, rel);
        check(rel <= 1e-4, "logits within 1e-4 * max(1, |torch|) of PyTorch");
        check(same == n, "the masked argmax as PyTorch's in " + same + " of " + n);
        time(w, Arrays.copyOfRange(obs, 0, net.d));
        System.exit(bad == 0 ? 0 : 1);
    }

    /** random logits strongly for the forbidden actions: neither the argmax nor a draw ever takes one */
    static void choiceHonoursMask() {
        Random r = new Random(3);
        boolean ok = true, sums = true;
        for (int it = 0; it < 5000; it++) {
            int a = 38;
            float[] lg = new float[a];
            int[] m = new int[a];
            m[0] = 1;
            for (int k = 1; k < a; k++) m[k] = r.nextInt(3) == 0 ? 1 : 0;
            for (int k = 0; k < a; k++) lg[k] = (float) (r.nextGaussian() * 3 + (m[k] == 0 ? 20 : 0));
            float[] p = new float[a];
            int g = PolicyNet.choose(lg, m, null, null), s = PolicyNet.choose(lg, m, r, p);
            ok &= m[g] == 1 && m[s] == 1;
            double z = 0;
            for (int k = 0; k < a; k++) { z += p[k]; if (m[k] == 0 && p[k] != 0) sums = false; }
            sums &= Math.abs(z - 1) < 1e-5;
        }
        check(ok, "a masked action is never chosen (5000 argmaxes and draws, the forbidden ones favoured)");
        check(sums, "the probabilities are the allowed actions' softmax (0 for the masked)");
        check(PolicyNet.choose(new float[3], new int[3], null, null) == -1, "nothing allowed: -1");
    }

    /** RlSpec (Java, the environment's code) and tools/rl/spec.py (the simulator's) on the same raw facts */
    static void specMatchesPython(File f) throws Exception {
        if (!f.isFile()) {
            System.out.println("skip RlSpec vs spec.py: no " + f + " (tools/tests/test_rl_policy.py writes it)");
            return;
        }
        JsonObject o = new JsonParser().parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
        check(o.get("obs_hash").getAsString().equals(RlSpec.obsHash()) && o.get("actions_hash").getAsString().equals(RlSpec.hash()),
                "spec.py read the same rl/obs.json and rl/actions.json as RlSpec has");
        JsonArray cases = o.getAsJsonArray("cases");
        int vecBad = 0, maskBad = 0;
        double worst = 0;
        for (JsonElement e : cases) {
            JsonObject c = e.getAsJsonObject();
            Map<String, Double> raw = new HashMap<String, Double>();
            for (Map.Entry<String, JsonElement> kv : c.getAsJsonObject("raw").entrySet()) raw.put(kv.getKey(), kv.getValue().getAsDouble());
            float[] v = RlSpec.vector(raw);
            JsonArray pv = c.getAsJsonArray("obs");
            boolean vb = v.length != pv.size();
            for (int i = 0; !vb && i < v.length; i++) {
                double d = Math.abs(v[i] - pv.get(i).getAsDouble());
                worst = Math.max(worst, d);
                if (d > 1e-5) vb = true;
            }
            if (vb) vecBad++;
            int[] m = RlSpec.mask(raw);
            JsonArray pm = c.getAsJsonArray("mask");
            boolean mb = m.length != pm.size();
            for (int i = 0; !mb && i < m.length; i++) if (m[i] != pm.get(i).getAsInt()) {
                mb = true;
                if (maskBad < 3) System.out.println("     mask differs at " + RlSpec.ACTIONS.get(i).id + ": java " + m[i] + ", python " + pm.get(i));
            }
            if (mb) maskBad++;
        }
        check(vecBad == 0, "RlSpec.vector = spec.vector on " + cases.size() + " simulator states (max diff " + (float) worst + ")");
        check(maskBad == 0, "RlSpec.mask = spec.mask on " + cases.size() + " simulator states (" + maskBad + " differ)");
    }

    static void time(PolicyNet.Work w, float[] obs) {
        int[] m = new int[w.logits.length];
        Arrays.fill(m, 1);
        for (int i = 0; i < 2000; i++) w.act(obs, m, null, null);   // the JIT's warm-up
        int n = 5000;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) w.act(obs, m, null, null);
        double us = (System.nanoTime() - t0) / 1e3 / n;
        System.out.printf("a decision on one thread: %.1f us (java %s)%n", us, System.getProperty("java.version"));
        check(us < 2000, "a decision well under a few ms");
    }
}
