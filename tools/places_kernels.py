"""Writes client/src/hardelele/aiguide/client/PlacesKernels.java: the inner loops of the places net's convolutions.

Java 8's JIT turns a loop into vector code only when the offsets of its array reads are constants it can see, so
each grid side (32, 16, 8) gets loops of its own with its row offsets written out. Run it again after a change here;
the Java file is not edited by hand.

    python tools/places_kernels.py
"""
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "client", "src", "hardelele", "aiguide", "client", "PlacesKernels.java")


def offset(dx, dy, n):
    sl = (n + 2) * n   # a slab: (n + 2) rows of n cells
    o = dx * sl + dy * n
    return "" if o == 0 else (" + %d" % o if o > 0 else " - %d" % -o)


def conv(n):
    lines = ["    /** two output channels, one input channel, the 27 taps; side %d */" % n,
             "    static void conv%d(float[] d, float[] d2, float[] m, float[] x, float[] p, float[] w, int q, int q2, int s, int e) {" % n]
    r = 0
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            o = offset(dx, dy, n)
            k = 3 * r
            lines.append("        {")
            lines.append("            float a = w[q + %d], b = w[q + %d], c = w[q + %d], a2 = w[q2 + %d], b2 = w[q2 + %d], c2 = w[q2 + %d];" % (k, k + 1, k + 2, k, k + 1, k + 2))
            lines.append("            for (int i = s; i < e; i++) {")
            lines.append("                float u = m[i%s], v = x[i%s], t = p[i%s];" % (o, o, o))
            lines.append("                d[i] += a * u + b * v + c * t;")
            lines.append("                d2[i] += a2 * u + b2 * v + c2 * t;")
            lines.append("            }")
            lines.append("        }")
            r += 1
    lines.append("    }")
    return lines


def fold(c, px, py):
    lines = ["    /** two output channels, one input channel of the coarse grid (side %d), the 8 folded taps of fine cells odd in x: %d, in y: %d */" % (c, px, py),
             "    static void fold%d_%d%d(float[] d, float[] d2, float[] lo, float[] hi, float[] w, int q, int q2, int s, int e) {" % (c, px, py)]
    for a in (0, 1):
        for b in (0, 1):
            o = offset(a - 1 + px, b - 1 + py, c)
            k = a * 4 + b * 2
            lines.append("        {")
            lines.append("            float f = w[q + %d], g = w[q + %d], f2 = w[q2 + %d], g2 = w[q2 + %d];" % (k, k + 1, k, k + 1))
            lines.append("            for (int i = s; i < e; i++) {")
            lines.append("                float u = lo[i%s], v = hi[i%s];" % (o, o))
            lines.append("                d[i] += f * u + g * v;")
            lines.append("                d2[i] += f2 * u + g2 * v;")
            lines.append("            }")
            lines.append("        }")
    lines.append("    }")
    return lines


def main():
    out = ["package hardelele.aiguide.client;",
           "",
           "/**",
           " * The places net's inner loops (written by tools/places_kernels.py, not by hand). A grid of side n is kept per",
           " * channel as (n + 2) slabs of x, each (n + 2) rows of y, each n cells of z; the border slabs and rows are zero, z has",
           " * no border: its neighbours come from two shifted copies of the channel (m[i] = x[i - 1], p[i] = x[i + 1], zero at",
           " * the row's ends). So every read is at a constant offset and the JIT makes vector code of the loops.",
           " */",
           "final class PlacesKernels {",
           "    private PlacesKernels() { }",
           "",
           "    static void conv(int n, float[] d, float[] d2, float[] m, float[] x, float[] p, float[] w, int q, int q2, int s, int e) {",
           "        if (n == 32) conv32(d, d2, m, x, p, w, q, q2, s, e);",
           "        else if (n == 16) conv16(d, d2, m, x, p, w, q, q2, s, e);",
           "        else if (n == 8) conv8(d, d2, m, x, p, w, q, q2, s, e);",
           "        else throw new IllegalArgumentException(\"side \" + n);",
           "    }",
           "",
           "    static void fold(int c, int px, int py, float[] d, float[] d2, float[] lo, float[] hi, float[] w, int q, int q2, int s, int e) {",
           "        int k = px * 2 + py;",
           "        if (c == 16) {",
           "            if (k == 0) fold16_00(d, d2, lo, hi, w, q, q2, s, e);",
           "            else if (k == 1) fold16_01(d, d2, lo, hi, w, q, q2, s, e);",
           "            else if (k == 2) fold16_10(d, d2, lo, hi, w, q, q2, s, e);",
           "            else fold16_11(d, d2, lo, hi, w, q, q2, s, e);",
           "        } else if (c == 8) {",
           "            if (k == 0) fold8_00(d, d2, lo, hi, w, q, q2, s, e);",
           "            else if (k == 1) fold8_01(d, d2, lo, hi, w, q, q2, s, e);",
           "            else if (k == 2) fold8_10(d, d2, lo, hi, w, q, q2, s, e);",
           "            else fold8_11(d, d2, lo, hi, w, q, q2, s, e);",
           "        } else throw new IllegalArgumentException(\"side \" + c);",
           "    }",
           ""]
    for n in (32, 16, 8):
        out += conv(n) + [""]
    for c in (16, 8):
        for px in (0, 1):
            for py in (0, 1):
                out += fold(c, px, py) + [""]
    out[-1] = "}"
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(out) + "\n")
    print("written:", OUT)


if __name__ == "__main__":
    main()
