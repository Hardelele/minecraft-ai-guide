package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.List;

/** Pure checks for scripted UI target and recording-close gates. */
public final class CaptureSafetyProbe {
    static void check(boolean pass, String message) {
        if (!pass) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        List<Object[]> hits = new ArrayList<Object[]>();
        Object[] similar = new Object[] { "pill", "routine:stop" };
        Object[] exact = new Object[] { "pill", "stop" };
        hits.add(similar); hits.add(exact);
        check(CaptureHitTarget.unique(hits, "pill", "stop") == exact, "substring selected a different pill");
        try { CaptureHitTarget.unique(hits, "pill", "sto"); throw new AssertionError("missing pill accepted"); }
        catch (IllegalArgumentException expected) { }
        hits.add(new Object[] { "pill", "stop" });
        try { CaptureHitTarget.unique(hits, "pill", "stop"); throw new AssertionError("duplicate pill accepted"); }
        catch (IllegalArgumentException expected) { }
        check(!CaptureStopGate.ready(false, null), "open WorldRec accepted");
        check(CaptureStopGate.ready(true, null), "clean WorldRec close rejected");
        try { CaptureStopGate.ready(true, "gzip failed"); throw new AssertionError("failed WorldRec close accepted"); }
        catch (IllegalStateException expected) { }
        System.out.println("CaptureSafety: exact unique UI target and clean close passed");
    }
}
