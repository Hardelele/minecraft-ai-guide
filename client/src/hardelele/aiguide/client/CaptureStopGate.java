package hardelele.aiguide.client;

/** A closed recorder is usable only when its final write completed without error. */
final class CaptureStopGate {
    private CaptureStopGate() { }

    static boolean ready(boolean stopped, String closeError) {
        if (!stopped) return false;
        if (closeError != null) throw new IllegalStateException("WorldRec closed with error: " + closeError);
        return true;
    }
}
