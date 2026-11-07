package hardelele.aiguide.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * A duty to undo: something a task (or a reflex for it) changed for a while and has to give back. REMOVE: a block it
 * put (a pillar, a step) is to come away; RESTORE: a block it dug (a hole in its own wall) is to be put back. The task
 * registers it, not the body: only the task knows that a change is for a while. While its owner (the job it was taken
 * for) is still on, nothing is due; once the owner is gone (done, cleared by the board, the agent died), the undo reflex
 * does it. No game in it (blocks by id).
 */
final class Undo {
    enum Kind { REMOVE, RESTORE }

    static final int MAX_AGE = 20 * 60 * 5, MAX_TRIES = 3;

    final Kind kind;
    final int x, y, z;
    final int block, meta;     // RESTORE: which block was there (its id)
    final String why;          // "pillar", "step", "dug to get out"
    final Object owner;        // the job it was taken for (null: none, due at once)
    final long since;
    int tries;

    Undo(Kind kind, int x, int y, int z, int block, int meta, String why, Object owner, long since) {
        this.kind = kind; this.x = x; this.y = y; this.z = z; this.block = block; this.meta = meta; this.why = why; this.owner = owner; this.since = since;
    }

    static Undo remove(int x, int y, int z, String why, Object owner, long now) { return new Undo(Kind.REMOVE, x, y, z, 0, 0, why, owner, now); }

    static Undo restore(int x, int y, int z, int block, int meta, String why, Object owner, long now) { return new Undo(Kind.RESTORE, x, y, z, block, meta, why, owner, now); }

    boolean at(int x, int y, int z) { return this.x == x && this.y == y && this.z == z; }

    public String toString() { return kind.name().toLowerCase() + " " + x + " " + y + " " + z + " (" + why + ")"; }

    /** an agent's duties */
    static final class Book {
        final Deque<Undo> owed = new ArrayDeque<Undo>();

        /** a new duty (one per cell: the newer one stands) */
        void owe(Undo u) {
            for (Iterator<Undo> it = owed.iterator(); it.hasNext(); ) if (it.next().at(u.x, u.y, u.z)) it.remove();
            owed.add(u);
        }

        /** the task gave it back itself: no duty any more; true when there was one */
        boolean paid(int x, int y, int z) {
            for (Iterator<Undo> it = owed.iterator(); it.hasNext(); ) if (it.next().at(x, y, z)) { it.remove(); return true; }
            return false;
        }

        boolean isEmpty() { return owed.isEmpty(); }

        static boolean due(Undo u, Collection<?> live) { return u.owner == null || !live.contains(u.owner); }

        /** how many are due: their owner is not among the jobs on (the queue and the reflex running) */
        int dueCount(Collection<?> live) {
            int n = 0;
            for (Undo u : owed) if (due(u, live)) n++;
            return n;
        }

        /** the one to do next: of those due, a block put the highest first (a pillar comes down from its top) */
        Undo next(Collection<?> live) {
            Undo best = null;
            for (Undo u : owed) {
                if (!due(u, live)) continue;
                if (best == null || u.kind == Kind.REMOVE && (best.kind != Kind.REMOVE || u.y > best.y)) best = u;
            }
            return best;
        }

        /** a try that did not do it: true when it is given up (the third, or it is too old) */
        boolean failed(Undo u, long now) {
            u.tries++;
            if (u.tries < MAX_TRIES && now - u.since <= MAX_AGE) return false;
            owed.remove(u);
            return true;
        }

        /** those too old to be done (5 minutes): taken off and given back, to be told */
        List<Undo> expire(long now) {
            List<Undo> out = new ArrayList<Undo>();
            for (Iterator<Undo> it = owed.iterator(); it.hasNext(); ) {
                Undo u = it.next();
                if (now - u.since > MAX_AGE) { it.remove(); out.add(u); }
            }
            return out;
        }
    }
}
