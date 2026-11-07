package hardelele.aiguide.client;

/** Keep the player's FOV in degrees across completion, cancellation and shot replacement. */
final class CaptureFovState {
    private float original = Float.NaN;

    float key(float current, double degrees) {
        float target = CaptureFov.setting(degrees);
        if (Float.isNaN(original)) original = current;
        return target;
    }

    float restore(float current) {
        if (Float.isNaN(original)) return current;
        float restored = original;
        original = Float.NaN;
        return restored;
    }
}
