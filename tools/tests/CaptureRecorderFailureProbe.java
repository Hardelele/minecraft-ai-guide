package hardelele.aiguide.client;

/** Negative controls for the old odd-height and dead-writer failure paths. */
public final class CaptureRecorderFailureProbe {
    static void check(boolean pass, String why) { if (!pass) throw new AssertionError(why); }

    static void rejects(String failure, boolean process, boolean writer) {
        try {
            CaptureEncoderState.requireHealthy(failure, process, writer);
            throw new AssertionError("unhealthy encoder accepted");
        } catch (IllegalStateException expected) { }
    }

    public static void main(String[] args) {
        check(CaptureVideoGeometry.encoded(1920) == 1920, "even width changed");
        check(CaptureVideoGeometry.encoded(1061) == 1062, "odd height not padded");
        check(CaptureVideoGeometry.filter().contains("pad="), "full UI image would be cropped");
        CaptureEncoderState.requireHealthy(null, true, true);
        rejects("broken pipe", true, false);
        rejects(null, false, true);
        rejects(null, true, false);
        System.out.println("CaptureRecorderFailure: odd height padded, failed encoder rejected");
    }
}
