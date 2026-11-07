package hardelele.aiguide.client;

import java.util.List;

/** Resolve a scripted UI target by its full ID; an absent or duplicate target cannot be clicked. */
final class CaptureHitTarget {
    private CaptureHitTarget() { }

    static Object[] unique(List<Object[]> hits, String kind, String id) {
        Object[] found = null;
        for (Object[] hit : hits) {
            if (!kind.equals(hit[0]) || !id.equals(String.valueOf(hit[1]))) continue;
            if (found != null) throw new IllegalArgumentException("ambiguous UI target: " + kind + " " + id);
            found = hit;
        }
        if (found == null) throw new IllegalArgumentException("missing UI target: " + kind + " " + id);
        return found;
    }

    /** The scripted cursor is in bottom-left pixels; hit boxes are in top-left GUI units. */
    static boolean reached(List<Object[]> hits, String kind, String id, int mouseX, int mouseY,
                           int scale, int displayHeight, int renderSteps, int plannedSteps) {
        Object[] hit = unique(hits, kind, id); // missing or changed target fails closed
        if (scale <= 0 || displayHeight <= 0 || plannedSteps <= 0 || renderSteps < plannedSteps) return false;
        int x = mouseX / scale, y = (displayHeight - mouseY) / scale;
        return x >= (Integer) hit[3] && x < (Integer) hit[5]
                && y >= (Integer) hit[4] && y < (Integer) hit[6];
    }
}
