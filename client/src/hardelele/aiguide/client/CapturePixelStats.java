package hardelele.aiguide.client;

import java.nio.ByteBuffer;
import java.util.HashSet;

/** Tiny center patch signature; one color means a blank render, not a valid film frame. */
final class CapturePixelStats {
    final int colors;
    final int min;
    final int max;

    CapturePixelStats(ByteBuffer rgba) {
        HashSet<Integer> unique = new HashSet<Integer>();
        int lo = 255, hi = 0;
        rgba.rewind();
        while (rgba.remaining() >= 4) {
            int r = rgba.get() & 255, g = rgba.get() & 255, b = rgba.get() & 255;
            rgba.get();
            unique.add((r << 16) | (g << 8) | b);
            lo = Math.min(lo, Math.min(r, Math.min(g, b)));
            hi = Math.max(hi, Math.max(r, Math.max(g, b)));
        }
        colors = unique.size(); min = lo; max = hi;
    }

    @Override public String toString() { return colors + "colors/" + min + ".." + max; }
}
