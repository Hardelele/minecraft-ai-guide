package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The rating layer's places from the places net (Places): each known place as a PlaceView, the cube it was found in
 * as the net saw it (its input, so a rated place is a training example of exactly that), the weights' hash.
 */
class PlacesSource extends PlaceSource {
    List<PlacesSplit.Place> from;
    List<PlaceView> views = Collections.emptyList();

    public synchronized List<PlaceView> known() {
        List<PlacesSplit.Place> now = Places.known();
        if (now == from) return views;   // the same list: the same views (called every frame)
        List<PlaceView> l = new ArrayList<PlaceView>(now.size());
        for (PlacesSplit.Place p : now)
            l.add(of("p" + p.id, p.cls, new int[] { p.min[0], p.min[1], p.min[2], p.max[0], p.max[1], p.max[2] }, p.confidence, p.cube));
        from = now;
        views = Collections.unmodifiableList(l);
        return views;
    }

    public String weights() { return Places.weights; }

    public byte[] cube(PlaceView p) {
        int[] c = p.cube();
        return c == null ? null : Places.input(c[0], c[1], c[2]);
    }
}
