package hardelele.aiguide.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The causes of what happens in the learning environment, with no game in it (docs/rl/CONTRACT-v2.md 2.1): every
 * decision of an agent has a decision_id; a block an agent's decision put (and the liquid or fire spread from it)
 * carries that decision's mark, an item it made carries it (RlCauseHooks puts it into the item's NBT), a hit is the
 * decision's own. An effect (a mob hurt or killed, an agent saved) is given the chain of the links that led to it, the
 * nearest first: what the reward's events carry so that a learner can credit an earlier decision. The first time a
 * kind of link meets a kind of effect is novel (curiosity, the discoveries' journal). Kept in memory, bounded, cleared
 * with the arena. RlTest checks it on synthetic events.
 */
final class RlCause {
    static final int BLOCKS_MAX = 200000, DECISIONS_MAX = 100000;

    /** a decision as a cause: its id, agent, tick, kind (the action's id), and how it reached the effect (via) */
    static final class Link {
        final long id, tick;
        final String agent, kind, via;
        Link(long id, String agent, long tick, String kind, String via) { this.id = id; this.agent = agent; this.tick = tick; this.kind = kind; this.via = via; }
        Link via(String v) { return new Link(id, agent, tick, kind, v); }
        JsonObject json() {
            JsonObject o = new JsonObject();
            o.addProperty("decision_id", id);
            o.addProperty("agent", agent);
            o.addProperty("tick", tick);
            o.addProperty("kind", kind);
            o.addProperty("via", via == null ? "step" : via);
            return o;
        }
    }

    /** a block's mark: the decision that put it (or that its liquid or fire spread from), the block's family or id, spread or put */
    static final class Mark {
        final long decision;
        final String block;
        final boolean spread;
        Mark(long decision, String block, boolean spread) { this.decision = decision; this.block = block; this.spread = spread; }
    }

    long next = 1;
    final Map<Long, Link> decisions = new LinkedHashMap<Long, Link>() {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Link> e) { return size() > DECISIONS_MAX; }
    };
    final Map<String, Link> current = new HashMap<String, Link>();
    final Map<Long, Mark> blocks = new LinkedHashMap<Long, Mark>() {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Mark> e) { return size() > BLOCKS_MAX; }
    };
    /** an entity -> the link of the fire or lava it last burned in (onFire keeps burning after) */
    final Map<Integer, Link> burning = new HashMap<Integer, Link>();
    /** the kinds of link x effect seen: ever (the journal's), in this episode */
    final Set<String> ever = new HashSet<String>(), episode = new LinkedHashSet<String>();

    /** a new decision of an agent: its id, from now its current one */
    Link decide(String agent, long tick, String kind) {
        Link l = new Link(next++, agent, tick, kind, null);
        decisions.put(l.id, l);
        current.put(agent, l);
        return l;
    }

    /** a decision beside the agent's own (a reflex of its body): an id of its own, the agent's current one stays */
    Link aside(String agent, long tick, String kind) {
        Link l = new Link(next++, agent, tick, kind, null);
        decisions.put(l.id, l);
        return l;
    }

    Link decision(long id) { return decisions.get(id); }

    /** the agent's decision running now (via step), or null */
    Link now(String agent) {
        Link l = current.get(agent);
        return l == null ? null : l.via("step");
    }

    /** a decision by its id as a link via v (null when it is not known any more) */
    Link decision(long id, String v) {
        Link l = decisions.get(id);
        return l == null ? null : l.via(v);
    }

    /** the decision that made an item (its NBT's id) as a link via the item */
    Link item(long id, String item) {
        Link l = decisions.get(id);
        return l == null ? null : l.via("item:" + item);
    }

    // ---- the blocks --------------------------------------------------------------------------------------------

    static long key(int x, int y, int z) { return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF); }

    /** water, lava, fire: what spreads by itself and keeps its source's mark (flowing_water is water) */
    static String family(String block) {
        if (block == null) return null;
        String b = block.replace("minecraft:", "");
        if (b.equals("water") || b.equals("flowing_water")) return "water";
        if (b.equals("lava") || b.equals("flowing_lava")) return "lava";
        if (b.equals("fire")) return "fire";
        return null;
    }

    static String plain(String block) {
        String f = family(block);
        return f != null ? f : block == null ? "?" : block.replace("minecraft:", "");
    }

    /** an agent's decision put a block (a liquid from a bucket, fire from flint and steel) */
    void placed(long pos, String block, Link by) {
        if (by == null) return;
        blocks.put(pos, new Mark(by.id, plain(block), false));
    }

    /** a block is gone (broken): its mark too */
    void broken(long pos) { blocks.remove(pos); }

    /**
     * The world changed a block by itself (a liquid flowed, fire spread, a mob broke it, it burned): a liquid or fire
     * takes the mark of a neighbour of its family (its source's decision); anything else loses the mark it had.
     */
    void changed(long pos, String block, long[] neighbours) {
        String f = family(block);
        Mark m = blocks.get(pos);
        if (f == null) {
            if (m != null) blocks.remove(pos);
            return;
        }
        if (m != null && m.block.equals(f)) return;
        Mark from = null;
        for (long n : neighbours) {
            Mark k = blocks.get(n);
            if (k != null && k.block.equals(f)) { from = k; break; }
        }
        if (from != null) blocks.put(pos, new Mark(from.decision, f, true));
        else if (m != null) blocks.remove(pos);
    }

    /** (3.1) the cells the crew's decisions dug (a pit, the moat): pos -> decision (block_credit) */
    final Map<Long, Long> dugBy = new java.util.LinkedHashMap<Long, Long>();

    void dug(long pos, Link by) {
        dugBy.put(pos, by.id);
        while (dugBy.size() > 50000) dugBy.remove(dugBy.keySet().iterator().next());
    }

    /** the decision that dug the cell, as a link (via dug), or null */
    Link dugAt(long pos) {
        Long d = dugBy.get(pos);
        Link l = d == null ? null : decisions.get(d);
        return l == null ? null : l.via("dug");
    }

    /** the link of a block's mark (via block:<id>), or null */
    Link at(long pos) {
        Mark m = blocks.get(pos);
        if (m == null) return null;
        Link l = decisions.get(m.decision);
        return l == null ? null : l.via("block:" + m.block);
    }

    // ---- the chains and the novelty ---------------------------------------------------------------------------

    /** the links given (nulls left out), the same decision by the same way once, in the order given (the nearest first) */
    static List<Link> chain(Link... ls) {
        List<Link> out = new ArrayList<Link>();
        Set<String> had = new HashSet<String>();
        for (Link l : ls) if (l != null && had.add(l.id + "|" + l.via)) out.add(l);
        return out;
    }

    static JsonArray json(List<Link> chain) {
        JsonArray a = new JsonArray();
        for (Link l : chain) a.add(l.json());
        return a;
    }

    /** the kinds of cause of a chain: each link's way (hit, block:lava, item:stone_sword, step) */
    static List<String> kinds(List<Link> chain) {
        List<String> k = new ArrayList<String>();
        for (Link l : chain) {
            String v = l.via == null ? "step" : l.via;
            if (!k.contains(v)) k.add(v);
        }
        return k;
    }

    /** the effects whose novelty pays curiosity: on what is not the crew, or an agent saved */
    static boolean curious(String effect) {
        return effect.equals("mob_hurt") || effect.equals("mob_killed") || effect.equals("animal_killed") || effect.equals("agent_saved");
    }

    /** a new kind of link x effect: { the pair, first in the episode, first ever } for each kind of the chain new in the episode */
    List<Object[]> novel(List<Link> chain, String effect) {
        List<Object[]> out = new ArrayList<Object[]>();
        for (String k : kinds(chain)) {
            if (k.equals("step")) continue;   // the decision running then: no cause found, nothing discovered
            String pair = k + " → " + effect;
            if (!episode.add(pair)) continue;
            out.add(new Object[] { pair, k, ever.add(pair) });
        }
        return out;
    }

    /** a new episode: the world's marks gone (the arena is back), the decisions' ids go on, the journal's kinds too */
    void reset() {
        blocks.clear();
        dugBy.clear();
        current.clear();
        burning.clear();
        episode.clear();
    }
}
