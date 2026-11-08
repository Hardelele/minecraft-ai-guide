package hardelele.aiguide.client;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The places net (places/CONTRACT.md of the model's branch): a small 3D U-Net that tells, for each block of a cube of
 * 32x32x32, what kind of place it is part of. Plain Java, no game in it: the weights come from places.bin (a resource
 * of the jar or a file), a forward pass takes the cube's channels (one class a cell, 0..15) and gives the logits of
 * the 8 place classes a cell and the bottleneck (64 x 8x8x8, the places' embeddings come from it).
 *
 * Speed: the first convolution reads the one-hot input as a sum of the weights of each cell's class; the others run
 * on grids kept so the JIT makes vector code of them (PlacesKernels); a convolution over an upsampled grid is done on
 * the coarse grid with its weights folded (8 taps instead of 27). A Work holds every buffer of one pass and is reused:
 * nothing is allocated in a pass. One Work a thread.
 */
public final class PlacesNet {
    public static final int SIDE = 32, C_IN = 16, K = 8, EMB = 64, CELLS = SIDE * SIDE * SIDE;
    public static final String RESOURCE = "/assets/aiguide/places/places.bin";
    public static final String[] CHANNELS = { "air", "unseen", "water", "lava", "ground", "stone", "ore", "wood_natural", "plant",
        "built_solid", "door", "fence", "climb", "light", "storage", "other_solid" };
    public static final String[] CLASSES = { "solid", "open_outside", "passage", "room", "cave_hall", "shaft", "junction", "built_structure" };
    static final String[] LAYERS = { "enc1.c1", "enc1.c2", "enc2.c1", "enc2.c2", "bott.c1", "bott.c2", "dec2.c1", "dec2.c2", "dec1.c1", "dec1.c2", "head" };
    static final int[][] SHAPES = { { 16, 16 }, { 16, 16 }, { 32, 16 }, { 32, 32 }, { 64, 32 }, { 64, 64 }, { 32, 96 }, { 32, 32 }, { 16, 48 }, { 16, 16 }, { 8, 16 } };
    static final int CHUNK = 1024;   // cells of a grid a convolution goes through at once (its buffers stay in the cache)

    final float[][] w = new float[LAYERS.length][], b = new float[LAYERS.length][];
    /** enc1.c1 by tap and class: [tap][class][out] */
    final float[] first;
    /** the folded weights of the upsampled parts of dec2.c1 and dec1.c1: [parity][out][in][8] */
    final float[] fold2, fold1;

    PlacesNet(Map<String, float[]> t) {
        for (int l = 0; l < LAYERS.length; l++) {
            w[l] = t.get(LAYERS[l] + ".w");
            b[l] = t.get(LAYERS[l] + ".b");
            int taps = l == LAYERS.length - 1 ? 1 : 27;
            if (w[l] == null || b[l] == null) throw new IllegalArgumentException("places.bin has no " + LAYERS[l]);
            if (w[l].length != SHAPES[l][0] * SHAPES[l][1] * taps || b[l].length != SHAPES[l][0])
                throw new IllegalArgumentException("places.bin: " + LAYERS[l] + " is not " + SHAPES[l][0] + "x" + SHAPES[l][1] + "x" + taps);
        }
        first = new float[27 * C_IN * 16];
        for (int o = 0; o < 16; o++) for (int c = 0; c < C_IN; c++) for (int t2 = 0; t2 < 27; t2++) first[(t2 * C_IN + c) * 16 + o] = w[0][(o * C_IN + c) * 27 + t2];
        fold2 = fold(w[6], 32, 96, 0, 64);
        fold1 = fold(w[8], 16, 48, 0, 32);
    }

    /**
     * The 3x3x3 weights over a grid upsampled x2 (nearest), as weights over the coarse grid: a fine cell of parity p
     * (0 even, 1 odd) along an axis meets with its taps -1, 0, +1 the coarse cells floor((2X + p + t) / 2), two of them;
     * the taps that meet the same coarse cell are summed.
     */
    static float[] fold(float[] w, int cout, int cinAll, int ci0, int cin) {
        float[] f = new float[8 * cout * cin * 8];
        for (int par = 0; par < 8; par++) {
            int[] p = { par >> 2, (par >> 1) & 1, par & 1 };
            for (int o = 0; o < cout; o++)
                for (int i = 0; i < cin; i++)
                    for (int kx = 0; kx < 3; kx++)
                        for (int ky = 0; ky < 3; ky++)
                            for (int kz = 0; kz < 3; kz++) {
                                int a = Math.floorDiv(p[0] + kx - 1, 2) + 1 - p[0], bb = Math.floorDiv(p[1] + ky - 1, 2) + 1 - p[1], c = Math.floorDiv(p[2] + kz - 1, 2) + 1 - p[2];
                                f[((par * cout + o) * cin + i) * 8 + a * 4 + bb * 2 + c] += w[((o * cinAll) + ci0 + i) * 27 + kx * 9 + ky * 3 + kz];
                            }
        }
        return f;
    }

    // ---- the file ---------------------------------------------------------------------------------------------

    /** places.bin (or places-test.bin): the magic, the header and the tensors by name, in the file's order */
    static Map<String, float[]> tensors(byte[] data, String magic, int header, Map<String, int[]> shapes) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        byte[] m = new byte[4];
        if (data.length < 8) throw new IOException("too short");
        bb.get(m);
        if (!new String(m, "US-ASCII").equals(magic)) throw new IOException("not " + magic + ": " + new String(m, "US-ASCII"));
        int[] h = new int[header];
        for (int i = 0; i < header; i++) h[i] = bb.getInt();
        if (h[0] != 1) throw new IOException(magic + " version " + h[0] + " (this code reads version 1)");
        if (magic.equals("PLC1") && (h[1] != C_IN || h[2] != K)) throw new IOException("C_IN " + h[1] + ", K " + h[2] + " (this code has " + C_IN + ", " + K + ")");
        int n = bb.getInt();
        if (n < 0 || n > 1000) throw new IOException("tensors: " + n);
        Map<String, float[]> out = new LinkedHashMap<String, float[]>();
        for (int k = 0; k < n; k++) {
            int len = bb.getInt();
            if (len < 0 || len > 256) throw new IOException("name length " + len);
            byte[] nb = new byte[len];
            bb.get(nb);
            String name = new String(nb, "UTF-8");
            int nd = bb.getInt();
            if (nd < 0 || nd > 8) throw new IOException(name + ": ndim " + nd);
            int[] shape = new int[nd];
            long count = 1;
            for (int i = 0; i < nd; i++) { shape[i] = bb.getInt(); count *= shape[i]; }
            if (count < 0 || count * 4 > bb.remaining()) throw new IOException(name + ": " + Arrays.toString(shape) + " is past the end");
            float[] v = new float[(int) count];
            bb.asFloatBuffer().get(v);
            bb.position(bb.position() + 4 * (int) count);
            out.put(name, v);
            if (shapes != null) shapes.put(name, shape);
        }
        if (bb.remaining() != 0) throw new IOException(bb.remaining() + " bytes past the tensors");
        return out;
    }

    static byte[] bytes(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream(1 << 21);
        byte[] buf = new byte[1 << 16];
        for (int r; (r = in.read(buf)) > 0; ) o.write(buf, 0, r);
        return o.toByteArray();
    }

    public static PlacesNet read(InputStream in) throws IOException {
        try {
            return new PlacesNet(tensors(bytes(in), "PLC1", 3, null));
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage());
        }
    }

    public static PlacesNet load(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try { return read(in); } finally { in.close(); }
    }

    /** the weights packed in the jar, or null when there are none */
    public static PlacesNet resource() throws IOException {
        InputStream in = PlacesNet.class.getResourceAsStream(RESOURCE);
        if (in == null) return null;
        try { return read(in); } finally { in.close(); }
    }

    public Work work() { return new Work(this); }

    // ---- a pass -----------------------------------------------------------------------------------------------

    /** a grid of side n, ch channels, each with its two shifted copies (see PlacesKernels) */
    static final class Grid {
        final int n, ch, sl, size, s0, e0;
        final float[][] x, m, p;

        Grid(int ch, int n) {
            this.n = n;
            this.ch = ch;
            sl = (n + 2) * n;
            size = (n + 2) * sl;
            s0 = sl + n;                  // x 1, y 1, z 0
            e0 = n * sl + (n + 1) * n;    // past x n, y n, z n-1
            x = new float[ch][size];
            m = new float[ch][size];
            p = new float[ch][size];
        }

        int at(int cx, int cy, int cz) { return (cx + 1) * sl + (cy + 1) * n + cz; }

        void bias(float[] b) { for (int c = 0; c < ch; c++) Arrays.fill(x[c], s0, e0, b[c]); }

        /** after the sums: ReLU, the border rows zero again (the loops ran over them), the shifted copies */
        void finish(boolean relu) {
            for (int c = 0; c < ch; c++) {
                float[] v = x[c], vm = m[c], vp = p[c];
                if (relu) for (int i = s0; i < e0; i++) if (v[i] < 0) v[i] = 0;
                for (int cx = 1; cx <= n; cx++) {
                    int s = cx * sl;
                    Arrays.fill(v, s, s + n, 0);
                    Arrays.fill(v, s + (n + 1) * n, s + (n + 2) * n, 0);
                }
                for (int cx = 1; cx <= n; cx++)
                    for (int cy = 1; cy <= n; cy++) {
                        int r = cx * sl + cy * n;
                        vm[r] = 0;
                        System.arraycopy(v, r, vm, r + 1, n - 1);
                        System.arraycopy(v, r + 1, vp, r, n - 1);
                        vp[r + n - 1] = 0;
                    }
            }
        }
    }

    /** the buffers of a pass; not for two threads at once */
    public static final class Work {
        final PlacesNet net;
        final Grid a32, b32, p16, a16, e16, p8, a8, b8, c16, d16;
        final float[] t0, t1, acc = new float[16];
        /** the last pass's logits [class][x][y][z] and bottleneck [channel][x][y][z] (8^3) */
        public final float[] logits = new float[K * CELLS], bottleneck = new float[EMB * 512];

        Work(PlacesNet net) {
            this.net = net;
            a32 = new Grid(16, 32);   // enc1.c1, later dec1.c1
            b32 = new Grid(16, 32);   // enc1 (e1), later dec1 (d1)
            p16 = new Grid(16, 16);
            a16 = new Grid(32, 16);
            e16 = new Grid(32, 16);   // e2
            p8 = new Grid(32, 8);
            a8 = new Grid(64, 8);
            b8 = new Grid(64, 8);     // the bottleneck
            c16 = new Grid(32, 16);
            d16 = new Grid(32, 16);   // d2
            t0 = new float[c16.size];
            t1 = new float[c16.size];
        }

        /** one pass: cls[(x * 32 + y) * 32 + z] is the cell's channel (0..15); the results in logits and bottleneck */
        public void forward(byte[] cls) {
            if (cls.length != CELLS) throw new IllegalArgumentException("a cube is " + CELLS + " cells");
            for (byte c : cls) if (c < 0 || c >= C_IN) throw new IllegalArgumentException("channel " + c);
            PlacesNet n = net;
            first(cls, a32);
            conv(a32, 0, 16, n.w[1], n.b[1], b32, true);             // e1
            pool(b32, p16);
            conv(p16, 0, 16, n.w[2], n.b[2], a16, true);
            conv(a16, 0, 32, n.w[3], n.b[3], e16, true);             // e2
            pool(e16, p8);
            conv(p8, 0, 32, n.w[4], n.b[4], a8, true);
            conv(a8, 0, 64, n.w[5], n.b[5], b8, true);               // b
            c16.bias(n.b[6]);
            up(b8, n.fold2, c16);                                     // up2(b): channels 0..63 of dec2.c1
            sum(e16, 64, 96, n.w[6], c16);                            // e2: 64..95
            c16.finish(true);
            conv(c16, 0, 32, n.w[7], n.b[7], d16, true);             // d2
            a32.bias(n.b[8]);
            up(d16, n.fold1, a32);                                    // up2(d2): 0..31 of dec1.c1
            sum(b32, 32, 48, n.w[8], a32);                            // e1: 32..47
            a32.finish(true);
            conv(a32, 0, 16, n.w[9], n.b[9], b32, true);             // d1
            head(b32);
            for (int c = 0; c < EMB; c++)
                for (int x = 0; x < 8; x++)
                    for (int y = 0; y < 8; y++) System.arraycopy(b8.x[c], b8.at(x, y, 0), bottleneck, ((c * 8 + x) * 8 + y) * 8, 8);
        }

        /** enc1.c1 on the one-hot cube: each output the bias plus, for each of its 27 taps, the weight of the class there */
        void first(byte[] cls, Grid out) {
            float[] f = net.first, bias = net.b[0], acc = this.acc;
            for (int x = 0; x < SIDE; x++)
                for (int y = 0; y < SIDE; y++)
                    for (int z = 0; z < SIDE; z++) {
                        System.arraycopy(bias, 0, acc, 0, 16);
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = x + dx;
                            if (nx < 0 || nx >= SIDE) continue;
                            for (int dy = -1; dy <= 1; dy++) {
                                int ny = y + dy;
                                if (ny < 0 || ny >= SIDE) continue;
                                for (int dz = -1; dz <= 1; dz++) {
                                    int nz = z + dz;
                                    if (nz < 0 || nz >= SIDE) continue;
                                    int q = (((dx + 1) * 9 + (dy + 1) * 3 + dz + 1) * C_IN + cls[(nx * SIDE + ny) * SIDE + nz]) * 16;
                                    for (int o = 0; o < 16; o++) acc[o] += f[q + o];
                                }
                            }
                        }
                        int i = out.at(x, y, z);
                        for (int o = 0; o < 16; o++) out.x[o][i] = acc[o];
                    }
            out.finish(true);
        }

        /** out = bias + conv(in) (in's channels are the weights' inputs ci0..ci0 + in.ch of cinAll), then finish */
        void conv(Grid in, int ci0, int cinAll, float[] w, float[] bias, Grid out, boolean relu) {
            out.bias(bias);
            sum(in, ci0, cinAll, w, out);
            out.finish(relu);
        }

        /** out += conv(in), the weights' input channels from ci0 */
        void sum(Grid in, int ci0, int cinAll, float[] w, Grid out) {
            int n = out.n;
            for (int s = out.s0; s < out.e0; s += CHUNK) {
                int e = Math.min(out.e0, s + CHUNK);
                for (int o = 0; o < out.ch; o += 2)
                    for (int i = 0; i < in.ch; i++)
                        PlacesKernels.conv(n, out.x[o], out.x[o + 1], in.m[i], in.x[i], in.p[i], w, (o * cinAll + ci0 + i) * 27, ((o + 1) * cinAll + ci0 + i) * 27, s, e);
            }
        }

        /** out (side 2c) += conv(up2(in)) by the folded weights: on the coarse grid for each parity, then spread */
        void up(Grid in, float[] f, Grid out) {
            int c = in.n, cin = in.ch, cout = out.ch;
            float[] t0 = this.t0, t1 = this.t1;
            for (int par = 0; par < 8; par++) {
                int px = par >> 2, py = (par >> 1) & 1, pz = par & 1;
                for (int o = 0; o < cout; o += 2) {
                    Arrays.fill(t0, 0, in.size, 0);
                    Arrays.fill(t1, 0, in.size, 0);
                    for (int s = in.s0; s < in.e0; s += CHUNK) {
                        int e = Math.min(in.e0, s + CHUNK);
                        for (int i = 0; i < cin; i++)
                            PlacesKernels.fold(c, px, py, t0, t1, pz == 0 ? in.m[i] : in.x[i], pz == 0 ? in.x[i] : in.p[i], f,
                                    ((par * cout + o) * cin + i) * 8, ((par * cout + o + 1) * cin + i) * 8, s, e);
                    }
                    float[] d0 = out.x[o], d1 = out.x[o + 1];
                    for (int x = 0; x < c; x++)
                        for (int y = 0; y < c; y++) {
                            int src = in.at(x, y, 0), dst = out.at(2 * x + px, 2 * y + py, pz);
                            for (int z = 0; z < c; z++) {
                                d0[dst + 2 * z] += t0[src + z];
                                d1[dst + 2 * z] += t1[src + z];
                            }
                        }
                }
            }
        }

        void pool(Grid in, Grid out) {
            int c = out.n;
            for (int ch = 0; ch < out.ch; ch++) {
                float[] v = in.x[ch], o = out.x[ch];
                for (int x = 0; x < c; x++)
                    for (int y = 0; y < c; y++)
                        for (int z = 0; z < c; z++) {
                            int i = in.at(2 * x, 2 * y, 2 * z), dx = in.sl, dy = in.n;
                            float a = Math.max(Math.max(v[i], v[i + 1]), Math.max(v[i + dy], v[i + dy + 1]));
                            float b = Math.max(Math.max(v[i + dx], v[i + dx + 1]), Math.max(v[i + dx + dy], v[i + dx + dy + 1]));
                            o[out.at(x, y, z)] = Math.max(a, b);
                        }
            }
            out.finish(false);
        }

        void head(Grid in) {
            float[] w = net.w[10], bias = net.b[10];
            for (int k = 0; k < K; k++) {
                Arrays.fill(logits, k * CELLS, (k + 1) * CELLS, bias[k]);
                for (int c = 0; c < in.ch; c++) {
                    float a = w[k * in.ch + c];
                    float[] v = in.x[c];
                    for (int x = 0; x < SIDE; x++)
                        for (int y = 0; y < SIDE; y++) {
                            int s = in.at(x, y, 0), d = k * CELLS + (x * SIDE + y) * SIDE;
                            for (int z = 0; z < SIDE; z++) logits[d + z] += a * v[s + z];
                        }
                }
            }
        }

        /** each cell's class (argmax of its logits) into cls, and its softmax probability into prob (if not null) */
        public void classes(byte[] cls, float[] prob) {
            for (int i = 0; i < CELLS; i++) {
                int best = 0;
                float top = logits[i];
                for (int k = 1; k < K; k++) if (logits[k * CELLS + i] > top) { top = logits[k * CELLS + i]; best = k; }
                cls[i] = (byte) best;
                if (prob != null) {
                    double sum = 0;
                    for (int k = 0; k < K; k++) sum += Math.exp(logits[k * CELLS + i] - top);
                    prob[i] = (float) (1 / sum);
                }
            }
        }
    }

    /** the channel of each cell of a one-hot cube [channel][x][y][z]; an error if a cell is not one-hot */
    public static byte[] channels(float[] oneHot) {
        if (oneHot.length != C_IN * CELLS) throw new IllegalArgumentException("a one-hot cube is " + C_IN * CELLS + " numbers");
        byte[] cls = new byte[CELLS];
        for (int i = 0; i < CELLS; i++) {
            int hot = -1;
            for (int c = 0; c < C_IN; c++) {
                float v = oneHot[c * CELLS + i];
                if (v == 1) {
                    if (hot >= 0) throw new IllegalArgumentException("cell " + i + " has two channels");
                    hot = c;
                } else if (v != 0) throw new IllegalArgumentException("cell " + i + " is not one-hot: " + v);
            }
            if (hot < 0) throw new IllegalArgumentException("cell " + i + " has no channel");
            cls[i] = (byte) hot;
        }
        return cls;
    }
}
