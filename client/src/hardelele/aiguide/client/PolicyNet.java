package hardelele.aiguide.client;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * The crew's policy net (tools/rl/policy.py), with no game in it: the observation (RlSpec.vector) normalised by the
 * training's running mean and std, two tanh layers, the logits over RlSpec's actions; an action the mask forbids is
 * never chosen. Weights: policy.bin ("RLP1", tools/rl/export_policy.py; the container is places.bin's). One pass is
 * D*H + H*H + H*A multiply-adds (about 0.1 M): well under a millisecond. PolicyTest checks it against PyTorch.
 */
final class PolicyNet {
    static final String MAGIC = "RLP1", TEST_MAGIC = "RPT1";
    static final float CLIP = 10f;

    final int d, a, h;
    final float[] mean, std, w1, b1, w2, b2, w3, b3;
    int version = 1;

    PolicyNet(int d, int a, int h, Map<String, float[]> t) {
        this.d = d;
        this.a = a;
        this.h = h;
        mean = need(t, "obs_mean", d);
        std = need(t, "obs_std", d);
        w1 = need(t, "l1.w", h * d);
        b1 = need(t, "l1.b", h);
        w2 = need(t, "l2.w", h * h);
        b2 = need(t, "l2.b", h);
        w3 = need(t, "pi.w", a * h);
        b3 = need(t, "pi.b", a);
    }

    static float[] need(Map<String, float[]> t, String name, int n) {
        float[] v = t.get(name);
        if (v == null) throw new IllegalArgumentException("policy.bin has no " + name);
        if (v.length != n) throw new IllegalArgumentException(name + " has " + v.length + " numbers, not " + n);
        return v;
    }

    // ---- the file ------------------------------------------------------------------------------------------

    static PolicyNet load(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try { return load(in); } finally { in.close(); }
    }

    static PolicyNet load(InputStream in) throws IOException {
        int[] head = new int[4];
        Map<String, float[]> t = tensors(bytes(in), MAGIC, 4, head);
        // version 1: the v1 spaces (38 actions, 137 features); 2, 3, 4: the v2 spaces (docs/rl/CONTRACT-v2.md: 4 is v3.1); the same layout
        if (head[0] < 1 || head[0] > 4) throw new IOException("policy.bin version " + head[0] + ", not 1 to 4");
        PolicyNet n = new PolicyNet(head[1], head[2], head[3], t);
        n.version = head[0];
        return n;
    }

    static byte[] bytes(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
        return o.toByteArray();
    }

    /** the container: magic, nHead ints (into head), the tensors by name (row-major floats) */
    static Map<String, float[]> tensors(byte[] data, String magic, int nHead, int[] head) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        byte[] m = new byte[4];
        b.get(m);
        if (!new String(m, StandardCharsets.US_ASCII).equals(magic)) throw new IOException("not a " + magic + " file");
        for (int i = 0; i < nHead; i++) {
            int v = b.getInt();
            if (head != null && i < head.length) head[i] = v;
        }
        int n = b.getInt();
        Map<String, float[]> out = new LinkedHashMap<String, float[]>();
        for (int k = 0; k < n; k++) {
            byte[] nm = new byte[b.getInt()];
            b.get(nm);
            int nd = b.getInt(), cnt = 1;
            for (int i = 0; i < nd; i++) cnt *= b.getInt();
            float[] v = new float[cnt];
            for (int i = 0; i < cnt; i++) v[i] = b.getFloat();
            out.put(new String(nm, StandardCharsets.UTF_8), v);
        }
        if (b.hasRemaining()) throw new IOException("trailing bytes in a " + magic + " file");
        return out;
    }

    // ---- a pass --------------------------------------------------------------------------------------------

    /** a pass's buffers (one per thread) */
    final class Work {
        final float[] x = new float[d], h1 = new float[h], h2 = new float[h], logits = new float[a];

        /** logits of one observation (d floats, RlSpec.vector) */
        float[] forward(float[] obs) {
            if (obs.length != d) throw new IllegalArgumentException("an observation of " + obs.length + " floats, the net takes " + d);
            for (int i = 0; i < d; i++) x[i] = Math.max(-CLIP, Math.min(CLIP, (obs[i] - mean[i]) / std[i]));
            layer(w1, b1, x, h1, d, h, true);
            layer(w2, b2, h1, h2, h, h, true);
            layer(w3, b3, h2, logits, h, a, false);
            return logits;
        }

        /** the action: the best allowed (greedy) or one drawn by the allowed actions' softmax; -1 when none is allowed */
        int act(float[] obs, int[] mask, Random rnd, float[] probs) {
            forward(obs);
            return choose(logits, mask, rnd, probs);
        }
    }

    Work work() { return new Work(); }

    static void layer(float[] w, float[] b, float[] in, float[] out, int nin, int nout, boolean tanh) {
        for (int o = 0; o < nout; o++) {
            double s = b[o];
            int row = o * nin;
            for (int i = 0; i < nin; i++) s += w[row + i] * in[i];
            out[o] = tanh ? (float) Math.tanh(s) : (float) s;
        }
    }

    /** an allowed action from the logits: argmax when rnd is null, else a draw; probs (if given) gets the allowed softmax */
    static int choose(float[] logits, int[] mask, Random rnd, float[] probs) {
        int best = -1;
        for (int k = 0; k < logits.length; k++) if (mask[k] != 0 && (best < 0 || logits[k] > logits[best])) best = k;
        if (best < 0) return -1;
        if (rnd == null && probs == null) return best;
        double z = 0;
        double[] p = new double[logits.length];
        for (int k = 0; k < logits.length; k++) if (mask[k] != 0) z += p[k] = Math.exp(logits[k] - logits[best]);
        if (probs != null) for (int k = 0; k < logits.length; k++) probs[k] = (float) (p[k] / z);
        if (rnd == null) return best;
        double u = rnd.nextDouble() * z;
        for (int k = 0; k < logits.length; k++) {
            if (mask[k] == 0) continue;
            u -= p[k];
            if (u <= 0) return k;
        }
        return best;
    }
}
