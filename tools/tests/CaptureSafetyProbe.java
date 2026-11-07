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
        List<Object[]> moving = new ArrayList<Object[]>();
        moving.add(new Object[] { "pill", "routines", 0, 180, 220, 190, 235 });
        // The real failed take aimed at (728,173) in a 1920x1061 window, but rendered
        // fewer frames than the eight scripted ticks before it clicked the world.
        check(!CaptureHitTarget.reached(moving, "pill", "routines", 728, 173, 4, 1061, 2, 8),
                "premature click accepted after only two render frames");
        check(CaptureHitTarget.reached(moving, "pill", "routines", 728, 173, 4, 1061, 8, 8),
                "reached exact pill rejected");
        check(!CaptureHitTarget.reached(moving, "pill", "routines", 500, 500, 4, 1061, 8, 8),
                "off-target click accepted");
        moving.clear(); moving.add(new Object[] { "pill", "routine:other", 0, 180, 220, 190, 235 });
        try { CaptureHitTarget.reached(moving, "pill", "routines", 728, 173, 4, 1061, 8, 8);
            throw new AssertionError("changed pill accepted"); }
        catch (IllegalArgumentException expected) { }
        moving.clear();
        try { CaptureHitTarget.reached(moving, "pill", "routines", 728, 173, 4, 1061, 8, 8);
            throw new AssertionError("missing pill accepted at click"); }
        catch (IllegalArgumentException expected) { }
        check(!CaptureStopGate.ready(false, null), "open WorldRec accepted");
        check(CaptureStopGate.ready(true, null), "clean WorldRec close rejected");
        try { CaptureStopGate.ready(true, "gzip failed"); throw new AssertionError("failed WorldRec close accepted"); }
        catch (IllegalStateException expected) { }
        System.out.println("CaptureSafety: exact unique UI target and clean close passed");
    }
}
