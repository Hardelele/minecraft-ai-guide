package hardelele.aiguide.client;

/** Internal FOV setting is degrees; the old normalized mapping collapsed 70 to zero. */
public final class CaptureFovProbe {
    static void near(double actual, double wanted) {
        if (Math.abs(actual - wanted) > 1e-6) throw new AssertionError(actual + " != " + wanted);
    }

    public static void main(String[] args) {
        near(CaptureFov.degrees(70), 70);
        near(CaptureFov.degrees(110), 110);
        near(CaptureFov.setting(70), 70); // negative control: old slider mapping returned 0
        near(CaptureFov.setting(110), 110);
        near(CaptureFov.setting(30), 30);
        near(CaptureFov.degrees(CaptureFov.setting(85)), 85);
        near(CaptureFov.setting(200), 110);
        try { CaptureFov.setting(Double.NaN); throw new AssertionError("NaN FOV accepted"); }
        catch (IllegalArgumentException expected) { }
        System.out.println("CaptureFov: internal degree contract passed");
    }
}
