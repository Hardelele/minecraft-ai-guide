package hardelele.aiguide.client;

/** Pure conversion contract between Minecraft's option slider and film degrees. */
public final class CaptureFovProbe {
    static void near(double actual, double wanted) {
        if (Math.abs(actual - wanted) > 1e-6) throw new AssertionError(actual + " != " + wanted);
    }

    public static void main(String[] args) {
        near(CaptureFov.degrees(0), 70);
        near(CaptureFov.degrees(1), 110);
        near(CaptureFov.slider(70), 0);
        near(CaptureFov.slider(110), 1);
        near(CaptureFov.slider(30), -1);
        near(CaptureFov.degrees(CaptureFov.slider(85)), 85);
        near(CaptureFov.slider(200), 1);
        try { CaptureFov.slider(Double.NaN); throw new AssertionError("NaN FOV accepted"); }
        catch (IllegalArgumentException expected) { }
        System.out.println("CaptureFov: slider/degree contract passed");
    }
}
