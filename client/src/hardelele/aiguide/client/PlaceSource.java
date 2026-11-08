package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the rating layer gets the recognised places from. Nothing is set by default (the layer shows no places): the
 * inference code sets PlaceSource.current to one that maps its known places onto PlaceView. No game classes here.
 */
public abstract class PlaceSource {
    /** the source in use; null: no places */
    public static volatile PlaceSource current;

    /** the places known now (called on the render thread every frame the layer is on: keep it cheap) */
    public abstract List<PlaceView> known();

    /** the weights' version written into each label (a hash of places.bin, say) */
    public String weights() { return "none"; }

    /**
     * The model's own input cube for this place: one channel index (0..15) per cell, x-major ([x][y][z], 32768
     * bytes). Null: the layer reads the world itself when the place is rated.
     */
    public byte[] cube(PlaceView p) { return null; }

    static List<PlaceView> places() {
        PlaceSource s = current;
        if (s == null) return new ArrayList<PlaceView>();
        try {
            List<PlaceView> l = s.known();
            return l == null ? new ArrayList<PlaceView>() : l;
        } catch (Exception e) {
            return new ArrayList<PlaceView>();
        }
    }

    static String weightsOf() {
        PlaceSource s = current;
        try { return s == null ? "none" : String.valueOf(s.weights()); } catch (Exception e) { return "none"; }
    }

    /** a plain place (tests, a source that has only these fields) */
    public static PlaceView of(final String id, final int cls, final int[] box, final double confidence, final int[] cube) {
        return new PlaceView() {
            public String id() { return id; }
            public int cls() { return cls; }
            public int[] box() { return box; }
            public double confidence() { return confidence; }
            public int[] cube() { return cube; }
        };
    }
}
