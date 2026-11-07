package hardelele.aiguide.client;

/** Minecraft 1.7.10 stores the FOV option as a slider, while film keys use degrees. */
final class CaptureFov {
    private CaptureFov() { }

    static double degrees(float slider) { return 70.0 + slider * 40.0; }

    static float slider(double degrees) {
        if (!Double.isFinite(degrees)) throw new IllegalArgumentException("nonfinite FOV");
        return (float) ((Math.max(30.0, Math.min(110.0, degrees)) - 70.0) / 40.0);
    }
}
