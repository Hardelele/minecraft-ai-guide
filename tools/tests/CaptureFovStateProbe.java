package hardelele.aiguide.client;

/** Interrupting a key path or replacing its shot must restore the prior degree setting. */
public final class CaptureFovStateProbe {
    static void near(float actual, float wanted) {
        if (!Float.isFinite(actual) || Math.abs(actual - wanted) > 1e-6f) throw new AssertionError(actual + " != " + wanted);
    }

    public static void main(String[] args) {
        CaptureFovState state = new CaptureFovState();
        float prior = 86.0f;
        float path = state.key(prior, 100);
        near(path, CaptureFov.setting(100));
        near(state.restore(path), prior); // release/cancel before the path finishes
        near(state.restore(prior), prior); // repeated release is harmless

        path = state.key(prior, 95);
        float restored = state.restore(path); // start(replacement) restores before its first frame
        near(restored, prior);
        float replacement = state.key(restored, 85);
        near(state.restore(replacement), prior);

        float wide = state.key(30.0f, 110);
        near(state.restore(wide), 30.0f);
        System.out.println("CaptureFovState: interrupt and replacement restore degrees");
    }
}
