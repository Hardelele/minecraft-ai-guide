package hardelele.aiguide.client;

/** Interrupting a key path or replacing its shot must restore the prior slider. */
public final class CaptureFovStateProbe {
    static void near(float actual, float wanted) {
        if (!Float.isFinite(actual) || Math.abs(actual - wanted) > 1e-6f) throw new AssertionError(actual + " != " + wanted);
    }

    public static void main(String[] args) {
        CaptureFovState state = new CaptureFovState();
        float prior = 0.4f;
        float path = state.key(prior, 100);
        near(path, CaptureFov.slider(100));
        near(state.restore(path), prior); // release/cancel before the path finishes
        near(state.restore(prior), prior); // repeated release is harmless

        path = state.key(prior, 95);
        float restored = state.restore(path); // start(replacement) restores before its first frame
        near(restored, prior);
        float replacement = state.key(restored, 85);
        near(state.restore(replacement), prior);

        float wide = state.key(-1.0f, 110); // 30-degree original is a valid negative slider
        near(state.restore(wide), -1.0f);
        System.out.println("CaptureFovState: interrupt, replacement and negative slider restored");
    }
}
