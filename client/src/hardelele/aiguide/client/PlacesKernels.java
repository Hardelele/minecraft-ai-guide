package hardelele.aiguide.client;

/**
 * The places net's inner loops (written by tools/places_kernels.py, not by hand). A grid of side n is kept per
 * channel as (n + 2) slabs of x, each (n + 2) rows of y, each n cells of z; the border slabs and rows are zero, z has
 * no border: its neighbours come from two shifted copies of the channel (m[i] = x[i - 1], p[i] = x[i + 1], zero at
 * the row's ends). So every read is at a constant offset and the JIT makes vector code of the loops.
 */
final class PlacesKernels {
    private PlacesKernels() { }

    static void conv(int n, float[] d, float[] d2, float[] m, float[] x, float[] p, float[] w, int q, int q2, int s, int e) {
        if (n == 32) conv32(d, d2, m, x, p, w, q, q2, s, e);
        else if (n == 16) conv16(d, d2, m, x, p, w, q, q2, s, e);
        else if (n == 8) conv8(d, d2, m, x, p, w, q, q2, s, e);
        else throw new IllegalArgumentException("side " + n);
    }

    static void fold(int c, int px, int py, float[] d, float[] d2, float[] lo, float[] hi, float[] w, int q, int q2, int s, int e) {
        int k = px * 2 + py;
        if (c == 16) {
            if (k == 0) fold16_00(d, d2, lo, hi, w, q, q2, s, e);
            else if (k == 1) fold16_01(d, d2, lo, hi, w, q, q2, s, e);
            else if (k == 2) fold16_10(d, d2, lo, hi, w, q, q2, s, e);
            else fold16_11(d, d2, lo, hi, w, q, q2, s, e);
        } else if (c == 8) {
            if (k == 0) fold8_00(d, d2, lo, hi, w, q, q2, s, e);
            else if (k == 1) fold8_01(d, d2, lo, hi, w, q, q2, s, e);
            else if (k == 2) fold8_10(d, d2, lo, hi, w, q, q2, s, e);
            else fold8_11(d, d2, lo, hi, w, q, q2, s, e);
        } else throw new IllegalArgumentException("side " + c);
    }

    /** two output channels, one input channel, the 27 taps; side 32 */
    static void conv32(float[] d, float[] d2, float[] m, float[] x, float[] p, float[] w, int q, int q2, int s, int e) {
        {
            float a = w[q + 0], b = w[q + 1], c = w[q + 2], a2 = w[q2 + 0], b2 = w[q2 + 1], c2 = w[q2 + 2];
            for (int i = s; i < e; i++) {
                float u = m[i - 1120], v = x[i - 1120], t = p[i - 1120];
                d[i] += a * u + b * v + c * t;
                d2[i] += a2 * u + b2 * v + c2 * t;
            }
        }
        {
            float a = w[q + 3], b = w[q + 4], c = w[q + 5], a2 = w[q2 + 3], b2 = w[q2 + 4], c2 = w[q2 + 5];
            for (int i = s; i < e; i++) {
                float u = m[i - 1088], v = x[i - 1088], t = p[i - 1088];
                d[i] += a * u + b * v + c * t;
                d2[i] += a2 * u + b2 * v + c2 * t;
            }
        }
        {
            float a = w[q + 6], b = w[q + 7], c = w[q + 8], a2 = w[q2 + 6], b2 = w[q2 + 7], c2 = w[q2 + 8];
            for (int i = s; i < e; i++) {
                float u = m[i - 1056], v = x[i - 1056], t = p[i - 1056];
                d[i] += a * u + b * v + c * t;
                d2[i] += a2 * u + b2 * v + c2 * t;
            }
        }
        {
            float a = w[q + 9], b = w[q + 10], c = w[q + 11], a2 = w[q2 + 9], b2 = w[q2 + 10], c2 = w[q2 + 11];
            for (int i = s; i < e; i++) {
                float u = m[i - 32], v = x[i - 32], t = p[i - 32];
                d[i] += a * u + b * v + c * t;
                d2[i] += a2 * u + b2 * v + c2 * t;
            }
        }
        {
            float a = w[q + 12], b = w[q + 13], c = w[q + 14], a2 = w[q2 + 12], b2 = w[q2 + 13], c2 = w[q2 + 14];
            for (int i = s; i < e; i++) {
                float u = m[i], v = x[i], t = p[i];
                d[i] += a * u + b * v + c * t;
                d2[i] += a2 * u + b2 * v + c2 * t;
            }
        }
        {
            float a = w[q + 15], b = w[q + 16], c = w[q + 17], a2 = w[q2 + 15], b2 = w[q2 + 16], c2 = w[q2 + 17];
            for (int i = s; i < e; i++) {
                float u = m[i + 32], v = x[i + 32], t = p[i + 32];
                d[i] += a * u + b * v + c * t;
                d2[i] += a2 * u + b2 * v + c2 * t;
            }
        }
        {
            float a = w[q + 18], b = w[q + 19], c = w[q + 20], a2 = w[q2 + 18], b2 = w[q2 + 19], c2 = w[q2 + 20];
            for (int i = s; i < e; i++) {
                float u = m[i + 1056], v = x[i + 1056], t = p[i + 1056];
                d[i] += a * u + b * v + c * t;
                d2[i] += a2 * u + b2 * v + c2 * t;
            }
        }
        {
            float a = w[q + 21], b = w[q + 22], c = w[q + 23], a2 = w[q2 + 21], b2 = w[q2 + 22], c2 = w[q2 + 23];
            for (int i = s; i < e; i++) {
                float u = m[i + 1088], v = x[i + 1088], t = p[i + 1088];
                d[i] += a * u + b * v + c * t;
                d2[i] += a2 * u + b2 * v + c2 * t;
            }
        }
        {
            float a = w[q + 24], b = w[q + 25], c = w[q + 26], a2 = w[q2 + 24], b2 = w[q2 + 25], c2 = w[q2 + 26];
            for (int i = s; i < e; i++) {
                float u = m[i + 1120], v = x[i + 1120], t = p[i + 1120];
                d[i] += a * u + b * v + c * t;
                d2[i] += a2 * u + b2 * v + c2 * t;
            }
        }
    }

    /** two output channels, one input channel, the 27 taps; side 16 */
    static void conv16(float[] d, float[] d2, float[] m, float[] x, float[] p, float[] w, int q, int q2, int s, int e) {
        {
            float a = w[q + 0], b = w[q + 1], c = w[q + 2], a2 = w[q2 + 0], b2 = w[q2 + 1], c2 = w[q2 + 2];
            for (int i = s; i < e; i++) {
                float u = m[i - 304], v = x[i - 304], t = p[i - 304];
                d[i] += a * u + b * v + c * t;
                d2[i] += a2 * u + b2 * v + c2 * t;
            }
        }
        {
            float a = w[q + 3], b = w[q + 4], c = w[q + 5], a2 = w[q2 + 3], b2 = w[q2 + 4], c2 = w[q2 + 5];
            for (int i = s; i < e; i++) {
                float u = m[i - 288], v = x[i - 288], t = p[i - 288];
                d[i] += a * u + b * v + c * t;
                d2[i] += a2 * u + b2 * v + c2 * t;
            }
        }
        {
            float a = w[q + 6], b = w[q + 7], c = w[q + 8], a2 = w[q2 + 6], b2 = w[q2 + 7], c2 = w[q2 + 8];
            for (int i = s; i < e; i++) {
                float u = m[i - 272], v = x[i - 272], t = p[i - 272];
                d[i] += a * u + b * v + c * t;
                d2[i] += a2 * u + b2 * v + c2 * t;
            }
        }
        {
            float a = w[q + 9], b = w[q + 10], c = w[q + 11], a2 = w[q2 + 9], b2 = w[q2 + 10], c2 = w[q2 + 11];
            for (int i = s; i < e; i++) {
                float u = m[i - 16], v = x[i - 16], t = p[i - 16];
                d[i] += a * u + b * v + c * t;
                d2[i] += a2 * u + b2 * v + c2 * t;
            }
        }
        {
            float a = w[q + 12], b = w[q + 13], c = w[q + 14], a2 = w[q2 + 12], b2 = w[q2 + 13], c2 = w[q2 + 14];
            for (int i = s; i < e; i++) {
                float u = m[i], v = x[i], t = p[i];
                d[i] += a * u + b * v + c * t;
                d2[i] += a2 * u + b2 * v + c2 * t;
            }
        }
        {
            float a = w[q + 15], b = w[q + 16], c = w[q + 17], a2 = w[q2 + 15], b2 = w[q2 + 16], c2 = w[q2 + 17];
            for (int i = s; i < e; i++) {
                float u = m[i + 16], v = x[i + 16], t = p[i + 16];
                d[i] += a * u + b * v + c * t;
                d2[i] += a2 * u + b2 * v + c2 * t;
            }
        }
        {
            float a = w[q + 18], b = w[q + 19], c = w[q + 20], a2 = w[q2 + 18], b2 = w[q2 + 19], c2 = w[q2 + 20];
            for (int i = s; i < e; i++) {
                float u = m[i + 272], v = x[i + 272], t = p[i + 272];
                d[i] += a * u + b * v + c * t;
                d2[i] += a2 * u + b2 * v + c2 * t;
            }
        }
        {
            float a = w[q + 21], b = w[q + 22], c = w[q + 23], a2 = w[q2 + 21], b2 = w[q2 + 22], c2 = w[q2 + 23];
            for (int i = s; i < e; i++) {
                float u = m[i + 288], v = x[i + 288], t = p[i + 288];
                d[i] += a * u + b * v + c * t;
                d2[i] += a2 * u + b2 * v + c2 * t;
            }
        }
        {
            float a = w[q + 24], b = w[q + 25], c = w[q + 26], a2 = w[q2 + 24], b2 = w[q2 + 25], c2 = w[q2 + 26];
            for (int i = s; i < e; i++) {
                float u = m[i + 304], v = x[i + 304], t = p[i + 304];
                d[i] += a * u + b * v + c * t;
                d2[i] += a2 * u + b2 * v + c2 * t;
            }
        }
    }

    /** two output channels, one input channel, the 27 taps; side 8 */
    static void conv8(float[] d, float[] d2, float[] m, float[] x, float[] p, float[] w, int q, int q2, int s, int e) {
        {
            float a = w[q + 0], b = w[q + 1], c = w[q + 2], a2 = w[q2 + 0], b2 = w[q2 + 1], c2 = w[q2 + 2];
            for (int i = s; i < e; i++) {
                float u = m[i - 88], v = x[i - 88], t = p[i - 88];
                d[i] += a * u + b * v + c * t;
                d2[i] += a2 * u + b2 * v + c2 * t;
            }
        }
        {
            float a = w[q + 3], b = w[q + 4], c = w[q + 5], a2 = w[q2 + 3], b2 = w[q2 + 4], c2 = w[q2 + 5];
            for (int i = s; i < e; i++) {
                float u = m[i - 80], v = x[i - 80], t = p[i - 80];
                d[i] += a * u + b * v + c * t;
                d2[i] += a2 * u + b2 * v + c2 * t;
            }
        }
        {
            float a = w[q + 6], b = w[q + 7], c = w[q + 8], a2 = w[q2 + 6], b2 = w[q2 + 7], c2 = w[q2 + 8];
            for (int i = s; i < e; i++) {
                float u = m[i - 72], v = x[i - 72], t = p[i - 72];
                d[i] += a * u + b * v + c * t;
                d2[i] += a2 * u + b2 * v + c2 * t;
            }
        }
        {
            float a = w[q + 9], b = w[q + 10], c = w[q + 11], a2 = w[q2 + 9], b2 = w[q2 + 10], c2 = w[q2 + 11];
            for (int i = s; i < e; i++) {
                float u = m[i - 8], v = x[i - 8], t = p[i - 8];
                d[i] += a * u + b * v + c * t;
                d2[i] += a2 * u + b2 * v + c2 * t;
            }
        }
        {
            float a = w[q + 12], b = w[q + 13], c = w[q + 14], a2 = w[q2 + 12], b2 = w[q2 + 13], c2 = w[q2 + 14];
            for (int i = s; i < e; i++) {
                float u = m[i], v = x[i], t = p[i];
                d[i] += a * u + b * v + c * t;
                d2[i] += a2 * u + b2 * v + c2 * t;
            }
        }
        {
            float a = w[q + 15], b = w[q + 16], c = w[q + 17], a2 = w[q2 + 15], b2 = w[q2 + 16], c2 = w[q2 + 17];
            for (int i = s; i < e; i++) {
                float u = m[i + 8], v = x[i + 8], t = p[i + 8];
                d[i] += a * u + b * v + c * t;
                d2[i] += a2 * u + b2 * v + c2 * t;
            }
        }
        {
            float a = w[q + 18], b = w[q + 19], c = w[q + 20], a2 = w[q2 + 18], b2 = w[q2 + 19], c2 = w[q2 + 20];
            for (int i = s; i < e; i++) {
                float u = m[i + 72], v = x[i + 72], t = p[i + 72];
                d[i] += a * u + b * v + c * t;
                d2[i] += a2 * u + b2 * v + c2 * t;
            }
        }
        {
            float a = w[q + 21], b = w[q + 22], c = w[q + 23], a2 = w[q2 + 21], b2 = w[q2 + 22], c2 = w[q2 + 23];
            for (int i = s; i < e; i++) {
                float u = m[i + 80], v = x[i + 80], t = p[i + 80];
                d[i] += a * u + b * v + c * t;
                d2[i] += a2 * u + b2 * v + c2 * t;
            }
        }
        {
            float a = w[q + 24], b = w[q + 25], c = w[q + 26], a2 = w[q2 + 24], b2 = w[q2 + 25], c2 = w[q2 + 26];
            for (int i = s; i < e; i++) {
                float u = m[i + 88], v = x[i + 88], t = p[i + 88];
                d[i] += a * u + b * v + c * t;
                d2[i] += a2 * u + b2 * v + c2 * t;
            }
        }
    }

    /** two output channels, one input channel of the coarse grid (side 16), the 8 folded taps of fine cells odd in x: 0, in y: 0 */
    static void fold16_00(float[] d, float[] d2, float[] lo, float[] hi, float[] w, int q, int q2, int s, int e) {
        {
            float f = w[q + 0], g = w[q + 1], f2 = w[q2 + 0], g2 = w[q2 + 1];
            for (int i = s; i < e; i++) {
                float u = lo[i - 304], v = hi[i - 304];
                d[i] += f * u + g * v;
                d2[i] += f2 * u + g2 * v;
            }
        }
        {
            float f = w[q + 2], g = w[q + 3], f2 = w[q2 + 2], g2 = w[q2 + 3];
            for (int i = s; i < e; i++) {
                float u = lo[i - 288], v = hi[i - 288];
                d[i] += f * u + g * v;
                d2[i] += f2 * u + g2 * v;
            }
        }
        {
            float f = w[q + 4], g = w[q + 5], f2 = w[q2 + 4], g2 = w[q2 + 5];
            for (int i = s; i < e; i++) {
                float u = lo[i - 16], v = hi[i - 16];
                d[i] += f * u + g * v;
                d2[i] += f2 * u + g2 * v;
            }
        }
        {
            float f = w[q + 6], g = w[q + 7], f2 = w[q2 + 6], g2 = w[q2 + 7];
            for (int i = s; i < e; i++) {
                float u = lo[i], v = hi[i];
                d[i] += f * u + g * v;
                d2[i] += f2 * u + g2 * v;
            }
        }
    }

    /** two output channels, one input channel of the coarse grid (side 16), the 8 folded taps of fine cells odd in x: 0, in y: 1 */
    static void fold16_01(float[] d, float[] d2, float[] lo, float[] hi, float[] w, int q, int q2, int s, int e) {
        {
            float f = w[q + 0], g = w[q + 1], f2 = w[q2 + 0], g2 = w[q2 + 1];
            for (int i = s; i < e; i++) {
                float u = lo[i - 288], v = hi[i - 288];
                d[i] += f * u + g * v;
                d2[i] += f2 * u + g2 * v;
            }
        }
        {
            float f = w[q + 2], g = w[q + 3], f2 = w[q2 + 2], g2 = w[q2 + 3];
            for (int i = s; i < e; i++) {
                float u = lo[i - 272], v = hi[i - 272];
                d[i] += f * u + g * v;
                d2[i] += f2 * u + g2 * v;
            }
        }
        {
            float f = w[q + 4], g = w[q + 5], f2 = w[q2 + 4], g2 = w[q2 + 5];
            for (int i = s; i < e; i++) {
                float u = lo[i], v = hi[i];
                d[i] += f * u + g * v;
                d2[i] += f2 * u + g2 * v;
            }
        }
        {
            float f = w[q + 6], g = w[q + 7], f2 = w[q2 + 6], g2 = w[q2 + 7];
            for (int i = s; i < e; i++) {
                float u = lo[i + 16], v = hi[i + 16];
                d[i] += f * u + g * v;
                d2[i] += f2 * u + g2 * v;
            }
        }
    }

    /** two output channels, one input channel of the coarse grid (side 16), the 8 folded taps of fine cells odd in x: 1, in y: 0 */
    static void fold16_10(float[] d, float[] d2, float[] lo, float[] hi, float[] w, int q, int q2, int s, int e) {
        {
            float f = w[q + 0], g = w[q + 1], f2 = w[q2 + 0], g2 = w[q2 + 1];
            for (int i = s; i < e; i++) {
                float u = lo[i - 16], v = hi[i - 16];
                d[i] += f * u + g * v;
                d2[i] += f2 * u + g2 * v;
            }
        }
        {
            float f = w[q + 2], g = w[q + 3], f2 = w[q2 + 2], g2 = w[q2 + 3];
            for (int i = s; i < e; i++) {
                float u = lo[i], v = hi[i];
                d[i] += f * u + g * v;
                d2[i] += f2 * u + g2 * v;
            }
        }
        {
            float f = w[q + 4], g = w[q + 5], f2 = w[q2 + 4], g2 = w[q2 + 5];
            for (int i = s; i < e; i++) {
                float u = lo[i + 272], v = hi[i + 272];
                d[i] += f * u + g * v;
                d2[i] += f2 * u + g2 * v;
            }
        }
        {
            float f = w[q + 6], g = w[q + 7], f2 = w[q2 + 6], g2 = w[q2 + 7];
            for (int i = s; i < e; i++) {
                float u = lo[i + 288], v = hi[i + 288];
                d[i] += f * u + g * v;
                d2[i] += f2 * u + g2 * v;
            }
        }
    }

    /** two output channels, one input channel of the coarse grid (side 16), the 8 folded taps of fine cells odd in x: 1, in y: 1 */
    static void fold16_11(float[] d, float[] d2, float[] lo, float[] hi, float[] w, int q, int q2, int s, int e) {
        {
            float f = w[q + 0], g = w[q + 1], f2 = w[q2 + 0], g2 = w[q2 + 1];
            for (int i = s; i < e; i++) {
                float u = lo[i], v = hi[i];
                d[i] += f * u + g * v;
                d2[i] += f2 * u + g2 * v;
            }
        }
        {
            float f = w[q + 2], g = w[q + 3], f2 = w[q2 + 2], g2 = w[q2 + 3];
            for (int i = s; i < e; i++) {
                float u = lo[i + 16], v = hi[i + 16];
                d[i] += f * u + g * v;
                d2[i] += f2 * u + g2 * v;
            }
        }
        {
            float f = w[q + 4], g = w[q + 5], f2 = w[q2 + 4], g2 = w[q2 + 5];
            for (int i = s; i < e; i++) {
                float u = lo[i + 288], v = hi[i + 288];
                d[i] += f * u + g * v;
                d2[i] += f2 * u + g2 * v;
            }
        }
        {
            float f = w[q + 6], g = w[q + 7], f2 = w[q2 + 6], g2 = w[q2 + 7];
            for (int i = s; i < e; i++) {
                float u = lo[i + 304], v = hi[i + 304];
                d[i] += f * u + g * v;
                d2[i] += f2 * u + g2 * v;
            }
        }
    }

    /** two output channels, one input channel of the coarse grid (side 8), the 8 folded taps of fine cells odd in x: 0, in y: 0 */
    static void fold8_00(float[] d, float[] d2, float[] lo, float[] hi, float[] w, int q, int q2, int s, int e) {
        {
            float f = w[q + 0], g = w[q + 1], f2 = w[q2 + 0], g2 = w[q2 + 1];
            for (int i = s; i < e; i++) {
                float u = lo[i - 88], v = hi[i - 88];
                d[i] += f * u + g * v;
                d2[i] += f2 * u + g2 * v;
            }
        }
        {
            float f = w[q + 2], g = w[q + 3], f2 = w[q2 + 2], g2 = w[q2 + 3];
            for (int i = s; i < e; i++) {
                float u = lo[i - 80], v = hi[i - 80];
                d[i] += f * u + g * v;
                d2[i] += f2 * u + g2 * v;
            }
        }
        {
            float f = w[q + 4], g = w[q + 5], f2 = w[q2 + 4], g2 = w[q2 + 5];
            for (int i = s; i < e; i++) {
                float u = lo[i - 8], v = hi[i - 8];
                d[i] += f * u + g * v;
                d2[i] += f2 * u + g2 * v;
            }
        }
        {
            float f = w[q + 6], g = w[q + 7], f2 = w[q2 + 6], g2 = w[q2 + 7];
            for (int i = s; i < e; i++) {
                float u = lo[i], v = hi[i];
                d[i] += f * u + g * v;
                d2[i] += f2 * u + g2 * v;
            }
        }
    }

    /** two output channels, one input channel of the coarse grid (side 8), the 8 folded taps of fine cells odd in x: 0, in y: 1 */
    static void fold8_01(float[] d, float[] d2, float[] lo, float[] hi, float[] w, int q, int q2, int s, int e) {
        {
            float f = w[q + 0], g = w[q + 1], f2 = w[q2 + 0], g2 = w[q2 + 1];
            for (int i = s; i < e; i++) {
                float u = lo[i - 80], v = hi[i - 80];
                d[i] += f * u + g * v;
                d2[i] += f2 * u + g2 * v;
            }
        }
        {
            float f = w[q + 2], g = w[q + 3], f2 = w[q2 + 2], g2 = w[q2 + 3];
            for (int i = s; i < e; i++) {
                float u = lo[i - 72], v = hi[i - 72];
                d[i] += f * u + g * v;
                d2[i] += f2 * u + g2 * v;
            }
        }
        {
            float f = w[q + 4], g = w[q + 5], f2 = w[q2 + 4], g2 = w[q2 + 5];
            for (int i = s; i < e; i++) {
                float u = lo[i], v = hi[i];
                d[i] += f * u + g * v;
                d2[i] += f2 * u + g2 * v;
            }
        }
        {
            float f = w[q + 6], g = w[q + 7], f2 = w[q2 + 6], g2 = w[q2 + 7];
            for (int i = s; i < e; i++) {
                float u = lo[i + 8], v = hi[i + 8];
                d[i] += f * u + g * v;
                d2[i] += f2 * u + g2 * v;
            }
        }
    }

    /** two output channels, one input channel of the coarse grid (side 8), the 8 folded taps of fine cells odd in x: 1, in y: 0 */
    static void fold8_10(float[] d, float[] d2, float[] lo, float[] hi, float[] w, int q, int q2, int s, int e) {
        {
            float f = w[q + 0], g = w[q + 1], f2 = w[q2 + 0], g2 = w[q2 + 1];
            for (int i = s; i < e; i++) {
                float u = lo[i - 8], v = hi[i - 8];
                d[i] += f * u + g * v;
                d2[i] += f2 * u + g2 * v;
            }
        }
        {
            float f = w[q + 2], g = w[q + 3], f2 = w[q2 + 2], g2 = w[q2 + 3];
            for (int i = s; i < e; i++) {
                float u = lo[i], v = hi[i];
                d[i] += f * u + g * v;
                d2[i] += f2 * u + g2 * v;
            }
        }
        {
            float f = w[q + 4], g = w[q + 5], f2 = w[q2 + 4], g2 = w[q2 + 5];
            for (int i = s; i < e; i++) {
                float u = lo[i + 72], v = hi[i + 72];
                d[i] += f * u + g * v;
                d2[i] += f2 * u + g2 * v;
            }
        }
        {
            float f = w[q + 6], g = w[q + 7], f2 = w[q2 + 6], g2 = w[q2 + 7];
            for (int i = s; i < e; i++) {
                float u = lo[i + 80], v = hi[i + 80];
                d[i] += f * u + g * v;
                d2[i] += f2 * u + g2 * v;
            }
        }
    }

    /** two output channels, one input channel of the coarse grid (side 8), the 8 folded taps of fine cells odd in x: 1, in y: 1 */
    static void fold8_11(float[] d, float[] d2, float[] lo, float[] hi, float[] w, int q, int q2, int s, int e) {
        {
            float f = w[q + 0], g = w[q + 1], f2 = w[q2 + 0], g2 = w[q2 + 1];
            for (int i = s; i < e; i++) {
                float u = lo[i], v = hi[i];
                d[i] += f * u + g * v;
                d2[i] += f2 * u + g2 * v;
            }
        }
        {
            float f = w[q + 2], g = w[q + 3], f2 = w[q2 + 2], g2 = w[q2 + 3];
            for (int i = s; i < e; i++) {
                float u = lo[i + 8], v = hi[i + 8];
                d[i] += f * u + g * v;
                d2[i] += f2 * u + g2 * v;
            }
        }
        {
            float f = w[q + 4], g = w[q + 5], f2 = w[q2 + 4], g2 = w[q2 + 5];
            for (int i = s; i < e; i++) {
                float u = lo[i + 80], v = hi[i + 80];
                d[i] += f * u + g * v;
                d2[i] += f2 * u + g2 * v;
            }
        }
        {
            float f = w[q + 6], g = w[q + 7], f2 = w[q2 + 6], g2 = w[q2 + 7];
            for (int i = s; i < e; i++) {
                float u = lo[i + 88], v = hi[i + 88];
                d[i] += f * u + g * v;
                d2[i] += f2 * u + g2 * v;
            }
        }
    }
}
