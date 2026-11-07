package hardelele.aiguide.client;

/** Minecraft 1.7.10 stores fovSetting in degrees; only the options UI serializes a slider. */
final class CaptureFov {
    private CaptureFov() { }

    static double degrees(float setting) { return setting; }

    static float setting(double degrees) {
        if (!Double.isFinite(degrees)) throw new IllegalArgumentException("nonfinite FOV");
        return (float) Math.max(30.0, Math.min(110.0, degrees));
    }
}
