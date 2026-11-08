package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A cube's classes as places: the connected regions (by faces) of one class, solid aside, of at least so many
 * cells, the cells the agents never saw aside (the net guesses there, nobody saw it). Each place: its box, centre,
 * volume, the mean softmax of its cells (how sure the net is) and its embedding (the mean of the bottleneck's vectors
 * over its cells, L2-normed). No game classes.
 */
final class PlacesSplit {
    private PlacesSplit() { }

    static final int S = PlacesNet.SIDE;
    /** the least cells of a place, by class (solid is never a place) */
    static final int[] MIN = { Integer.MAX_VALUE, 8, 6, 8, 12, 6, 4, 12 };
    static final AtomicInteger ids = new AtomicInteger();

    static final class Place {
        final int id, cls, volume;
        final int[] cube, min, max;   // the cube's corner and the place's box, world cells (max inclusive)
        final double[] center;
        final float confidence;
        final float[] embedding;

        Place(int id, int cls, int[] cube, int[] min, int[] max, double[] center, int volume, float confidence, float[] embedding) {
            this.id = id;
            this.cls = cls;
            this.cube = cube;
            this.min = min;
            this.max = max;
            this.center = center;
            this.volume = volume;
            this.confidence = confidence;
            this.embedding = embedding;
        }

        String kind() { return PlacesNet.CLASSES[cls]; }

        public String toString() {
            return kind() + " #" + id + " " + volume + " cells " + min[0] + "," + min[1] + "," + min[2] + ".." + max[0] + "," + max[1] + "," + max[2]
                    + String.format(" sure %.2f", confidence);
        }
    }

    /**
     * cls: each cell's class [x][y][z]; prob: its softmax; input: its channel (unseen cells are not in places, may be
     * null); bottleneck [64][8][8][8]; cube: the world cell of the cube's corner.
     */
    static List<Place> split(byte[] cls, float[] prob, byte[] input, float[] bottleneck, int[] cube) {
        List<Place> out = new ArrayList<Place>();
        int n = S * S * S;
        boolean[] done = new boolean[n];
        int[] queue = new int[n];
        for (int start = 0; start < n; start++) {
            int c = cls[start];
            if (done[start] || c == 0 || (input != null && input[start] == PlacesClasses.UNSEEN)) continue;
            int head = 0, tail = 0;
            queue[tail++] = start;
            done[start] = true;
            while (head < tail) {
                int i = queue[head++], x = i / (S * S), y = i / S % S, z = i % S;
                if (x > 0) tail = visit(i - S * S, c, cls, input, done, queue, tail);
                if (x < S - 1) tail = visit(i + S * S, c, cls, input, done, queue, tail);
                if (y > 0) tail = visit(i - S, c, cls, input, done, queue, tail);
                if (y < S - 1) tail = visit(i + S, c, cls, input, done, queue, tail);
                if (z > 0) tail = visit(i - 1, c, cls, input, done, queue, tail);
                if (z < S - 1) tail = visit(i + 1, c, cls, input, done, queue, tail);
            }
            if (tail < MIN[c]) continue;
            int[] min = { S, S, S }, max = { -1, -1, -1 };
            double sx = 0, sy = 0, sz = 0, sure = 0;
            float[] emb = new float[PlacesNet.EMB];
            for (int k = 0; k < tail; k++) {
                int i = queue[k], x = i / (S * S), y = i / S % S, z = i % S;
                min[0] = Math.min(min[0], x); min[1] = Math.min(min[1], y); min[2] = Math.min(min[2], z);
                max[0] = Math.max(max[0], x); max[1] = Math.max(max[1], y); max[2] = Math.max(max[2], z);
                sx += x; sy += y; sz += z;
                if (prob != null) sure += prob[i];
                if (bottleneck != null) {
                    int b = ((x >> 2) * 8 + (y >> 2)) * 8 + (z >> 2);
                    for (int e = 0; e < PlacesNet.EMB; e++) emb[e] += bottleneck[e * 512 + b];
                }
            }
            double norm = 0;
            for (float v : emb) norm += (double) v * v;
            norm = Math.sqrt(norm);
            if (norm > 0) for (int e = 0; e < emb.length; e++) emb[e] = (float) (emb[e] / norm);   // the mean's 1/n goes in the norm
            for (int a = 0; a < 3; a++) { min[a] += cube[a]; max[a] += cube[a]; }
            double[] center = { cube[0] + sx / tail + 0.5, cube[1] + sy / tail + 0.5, cube[2] + sz / tail + 0.5 };
            out.add(new Place(ids.incrementAndGet(), c, cube.clone(), min, max, center, tail, prob == null ? 0 : (float) (sure / tail), emb));
        }
        return out;
    }

    private static int visit(int j, int c, byte[] cls, byte[] input, boolean[] done, int[] queue, int tail) {
        if (done[j] || cls[j] != c || (input != null && input[j] == PlacesClasses.UNSEEN)) return tail;
        done[j] = true;
        queue[tail] = j;
        return tail + 1;
    }

    /** "room 2, passage 3": how many places of each class, by class order */
    static String summary(List<Place> places) {
        int[] count = new int[PlacesNet.K];
        for (Place p : places) count[p.cls]++;
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < count.length; k++) if (count[k] > 0) b.append(b.length() > 0 ? ", " : "").append(PlacesNet.CLASSES[k]).append(' ').append(count[k]);
        return b.toString();
    }
}
