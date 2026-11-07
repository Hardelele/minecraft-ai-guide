package hardelele.aiguide.client;

import java.nio.ByteBuffer;

/** The diagnostic must expose a flat gray frame and a detailed frame differently. */
public final class CapturePixelStatsProbe {
    public static void main(String[] args) {
        ByteBuffer flat = ByteBuffer.allocate(4 * 4);
        ByteBuffer varied = ByteBuffer.allocate(4 * 4);
        for (int i = 0; i < 4; i++) {
            flat.put(new byte[] { (byte) 190, (byte) 198, (byte) 211, (byte) 255 });
            varied.put(new byte[] { (byte) (30 + i * 30), (byte) 80, (byte) 20, (byte) 255 });
        }
        CapturePixelStats f = new CapturePixelStats(flat), v = new CapturePixelStats(varied);
        if (f.colors != 1 || v.colors != 4 || v.max <= v.min)
            throw new AssertionError("flat and varied render samples indistinguishable");
        System.out.println("CapturePixelStats: flat=1, varied=4 colors");
    }
}
