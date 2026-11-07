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
}
