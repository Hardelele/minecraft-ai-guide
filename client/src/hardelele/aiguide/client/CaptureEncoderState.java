package hardelele.aiguide.client;

/** Shared fail-closed decision for the render thread while a writer drains frames. */
final class CaptureEncoderState {
    private CaptureEncoderState() { }

    static void requireHealthy(String failure, boolean processAlive, boolean writerAlive) {
        if (failure != null) throw new IllegalStateException("encoder writer failed: " + failure);
        if (!processAlive) throw new IllegalStateException("encoder process exited before capture completed");
        if (!writerAlive) throw new IllegalStateException("encoder writer stopped before capture completed");
    }
}
