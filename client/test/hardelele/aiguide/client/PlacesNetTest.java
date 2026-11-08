package hardelele.aiguide.client;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;

/**
 * The places net against PyTorch: places.bin and places-test.bin of the model's branch (-Daiguide.places.weights and
 * -Daiguide.places.test; tools/test_client.py finds them), logits and bottleneck within 1e-4; the folded upsampling
 * against a plain one on random grids; the time of a pass.
 */
public class PlacesNetTest {
    static int bad;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) bad++;
    }

    public static void main(String[] args) throws Exception {
        File weights = new File(System.getProperty("aiguide.places.weights", "build/places/places.bin"));
        File test = new File(System.getProperty("aiguide.places.test", "build/places/places-test.bin"));
        foldMatchesPlain();
        if (!weights.isFile() || !test.isFile()) {
            System.out.println("skip PlacesNetTest's check against PyTorch: no " + weights + " or " + test);
            System.exit(bad == 0 ? 0 : 1);
        }
        PlacesNet net = PlacesNet.load(weights);
        FileInputStream in = new FileInputStream(test);
        Map<String, float[]> t;
        try { t = PlacesNet.tensors(PlacesNet.bytes(in), "PLT1", 1, null); } finally { in.close(); }
        byte[] cls = PlacesNet.channels(t.get("input"));
        PlacesNet.Work w = net.work();
        w.forward(cls);
        double dl = maxDiff(w.logits, t.get("logits")), db = maxDiff(w.bottleneck, t.get("bottleneck"));
        System.out.printf("logits: max |java - torch| %.3g (|torch| up to %.3g); bottleneck: %.3g (up to %.3g)%n", dl, maxAbs(t.get("logits")), db, maxAbs(t.get("bottleneck")));
        check(dl <= 1e-4, "logits within 1e-4 of PyTorch");
        check(db <= 1e-4, "bottleneck within 1e-4 of PyTorch");
        byte[] mine = new byte[PlacesNet.CELLS];
        float[] prob = new float[PlacesNet.CELLS];
        w.classes(mine, prob);
        int same = 0;
        float[] lt = t.get("logits");
        for (int i = 0; i < PlacesNet.CELLS; i++) {
            int best = 0;
            for (int k = 1; k < PlacesNet.K; k++) if (lt[k * PlacesNet.CELLS + i] > lt[best * PlacesNet.CELLS + i]) best = k;
            if (best == mine[i]) same++;
        }
        System.out.println("argmax as PyTorch's in " + same + " of " + PlacesNet.CELLS + " cells");
        check(same >= PlacesNet.CELLS - 8, "argmax as PyTorch's (ties aside)");
        PlacesNet.Work again = net.work();
        again.forward(cls);
        check(Arrays.equals(again.logits, w.logits), "a pass gives the same twice (no state left in the buffers)");
        w.forward(new byte[PlacesNet.CELLS]);
        w.forward(cls);
        check(maxDiff(w.logits, t.get("logits")) <= 1e-4, "a reused Work gives the same after another cube");
        try {
            PlacesNet.channels(new float[3]);
            check(false, "a short cube is refused");
        } catch (IllegalArgumentException e) {
            check(true, "a short cube is refused");
        }
        time(w, cls);
        System.exit(bad == 0 ? 0 : 1);
    }

    static void time(PlacesNet.Work w, byte[] cls) {
        for (int i = 0; i < 6; i++) w.forward(cls);   // the JIT's warm-up
        long[] ms = new long[10];
        for (int i = 0; i < ms.length; i++) {
            long t0 = System.nanoTime();
            w.forward(cls);
            ms[i] = (System.nanoTime() - t0) / 1000000;
        }
        Arrays.sort(ms);
        System.out.println("a pass on one thread: median " + ms[ms.length / 2] + " ms, best " + ms[0] + ", worst " + ms[ms.length - 1]
                + " (java " + System.getProperty("java.version") + ")");
    }

    /** dec2.c1's upsampled part folded vs the same convolution over a grid upsampled by hand */
    static void foldMatchesPlain() {
        Random r = new Random(5);
        int c = 8, cin = 4, cout = 2, n = 2 * c;
        float[] w = new float[cout * cin * 27];
        for (int i = 0; i < w.length; i++) w[i] = (float) r.nextGaussian();
        float[][][][] coarse = new float[cin][c][c][c];
        PlacesNet.Grid g = new PlacesNet.Grid(cin, c);
        for (int i = 0; i < cin; i++)
            for (int x = 0; x < c; x++)
                for (int y = 0; y < c; y++)
                    for (int z = 0; z < c; z++) g.x[i][g.at(x, y, z)] = coarse[i][x][y][z] = (float) r.nextGaussian();
        g.finish(false);
        PlacesNet.Grid out = new PlacesNet.Grid(cout, n);
        PlacesNet.Work work = new PlacesNet.Work(netForShapes());
        work.up(g, PlacesNet.fold(w, cout, cin, 0, cin), out);
        double diff = 0;
        for (int o = 0; o < cout; o++)
            for (int x = 0; x < n; x++)
                for (int y = 0; y < n; y++)
                    for (int z = 0; z < n; z++) {
                        double s = 0;
                        for (int i = 0; i < cin; i++)
                            for (int kx = 0; kx < 3; kx++)
                                for (int ky = 0; ky < 3; ky++)
                                    for (int kz = 0; kz < 3; kz++) {
                                        int fx = x + kx - 1, fy = y + ky - 1, fz = z + kz - 1;
                                        if (fx < 0 || fy < 0 || fz < 0 || fx >= n || fy >= n || fz >= n) continue;
                                        s += w[(o * cin + i) * 27 + kx * 9 + ky * 3 + kz] * coarse[i][fx / 2][fy / 2][fz / 2];
                                    }
                        diff = Math.max(diff, Math.abs(s - out.x[o][out.at(x, y, z)]));
                    }
        check(diff < 1e-4, "folded upsampled convolution = plain one (max diff " + (float) diff + ")");
    }

    /** a net of zero weights, only for a Work's buffers */
    static PlacesNet netForShapes() {
        Map<String, float[]> t = new java.util.HashMap<String, float[]>();
        for (int l = 0; l < PlacesNet.LAYERS.length; l++) {
            int taps = l == PlacesNet.LAYERS.length - 1 ? 1 : 27;
            t.put(PlacesNet.LAYERS[l] + ".w", new float[PlacesNet.SHAPES[l][0] * PlacesNet.SHAPES[l][1] * taps]);
            t.put(PlacesNet.LAYERS[l] + ".b", new float[PlacesNet.SHAPES[l][0]]);
        }
        return new PlacesNet(t);
    }

    static double maxDiff(float[] a, float[] b) {
        if (a.length != b.length) return Double.POSITIVE_INFINITY;
        double d = 0;
        for (int i = 0; i < a.length; i++) d = Math.max(d, Math.abs(a[i] - b[i]));
        return d;
    }

    static double maxAbs(float[] a) {
        double d = 0;
        for (float v : a) d = Math.max(d, Math.abs(v));
        return d;
    }
}
