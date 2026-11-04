package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.WorldServer;

/**
 * The crew's board: phases post cards (what is to be done, not who does it), an idle agent takes the card that is
 * worth most to it (priority, distance, whether it can, how often it failed). A taken card is leased: past its
 * lease it goes back on the board. What the game really shares is reserved: target blocks (a tree, a block of the
 * plan), chest quantities a card counts on, the furnace (one inventory). A crafting table is not reserved: every
 * player has his own grid at it, so do the agents, they only stand around it. An agent with a full bag hauls it to
 * the chest; an idle one with nothing on the board works on the crew's biggest deficit (its goals).
 */
class TaskBoard {
    enum State { OPEN, LEASED, DONE, FAILED }

    interface Make { Crew.Task make(); }
    interface Need { boolean ok(Agent a); }

    static class Card {
        final int id;
        final String kind;
        final Make make;
        Origin origin;
        Crew.Phase phase;
        Agent only;           // just this agent
        Card sameAs;          // whoever did that card (it holds what came of it)
        final List<Card> after = new ArrayList<Card>();
        double prio = 1;
        double[] at;          // where the work is (for distance)
        Need need;
        final Map<Item, Integer> wants = new LinkedHashMap<Item, Integer>();   // chest quantities it counts on
        int lease = 20 * 240, tries, maxTries = 2;
        State state = State.OPEN;
        Agent holder, doneBy;
        long since;
        int unfit;
        Result last;
        Card(int id, String kind, Make make) { this.id = id; this.kind = kind; this.make = make; }
        Card pin(Agent a) { only = a; return this; }
        Card same(Card c) { sameAs = c; return this; }
        Card after(Card... cs) { for (Card c : cs) if (c != null) after.add(c); return this; }
        Card after(List<Card> cs) { after.addAll(cs); return this; }
        Card prio(double p) { prio = p; return this; }
        Card at(double x, double y, double z) { at = new double[] { x, y, z }; return this; }
        Card need(Need n) { need = n; return this; }
        Card wants(Item i, int n) { wants.put(i, n); return this; }
        Card lease(int ticks) { lease = ticks; return this; }
        boolean open() { return state == State.OPEN || state == State.LEASED; }
        public String toString() { return "#" + id + " " + kind; }
    }

    final Crew c;
    final List<Card> cards = new ArrayList<Card>();
    final Map<Agent, Card> leased = new HashMap<Agent, Card>();
    final Map<Long, Agent> blocks = new HashMap<Long, Agent>();
    final Map<Card, Map<Item, Integer>> held = new HashMap<Card, Map<Item, Integer>>();   // chest quantities held by a card
    final Map<String, List<Agent>> stations = new HashMap<String, List<Agent>>();
    final Map<Item, Integer> goals = new LinkedHashMap<Item, Integer>();
    final Map<Agent, Long> rest = new HashMap<Agent, Long>();
    int next = 1;
    long now;

    TaskBoard(Crew c) { this.c = c; }

    // ---- posting --------------------------------------------------------------------------------------

    Card post(String kind, Make make) {
        Card k = new Card(next++, kind, make);
        k.origin = Crew.originNow;
        k.phase = c.current;
        cards.add(k);
        return k;
    }

    /** the cards of a phase not done yet */
    boolean pending(Crew.Phase ph) {
        for (Card k : cards) if (k.phase == ph && k.open()) return true;
        return false;
    }

    /** a phase ran out of time: its cards go away (the agents' jobs are cleared by the crew) */
    void cancel(Crew.Phase ph) {
        for (Card k : cards)
            if (k.phase == ph && k.open()) {
                if (k.holder != null) release(k.holder, k);
                k.state = State.FAILED;
                k.last = Result.TIMEOUT;
            }
    }

    // ---- choosing -------------------------------------------------------------------------------------

    boolean ready(Card k) {
        for (Card d : k.after) if (d.open()) return false;
        return true;
    }

    /** can this agent take the card at all */
    boolean fits(Card k, Agent a, WorldServer w) {
        if (k.state != State.OPEN || !ready(k)) return false;
        if (k.only != null && k.only != a) return false;
        if (k.sameAs != null && k.sameAs.doneBy != null && k.sameAs.doneBy != a) return false;
        if (k.sameAs != null && k.sameAs.doneBy == null && k.sameAs.state != State.FAILED) return false;
        if (k.need != null && !k.need.ok(a)) return false;
        for (Map.Entry<Item, Integer> e : k.wants.entrySet()) if (chestFree(w, e.getKey(), null) < e.getValue()) return false;
        return true;
    }

    double utility(Card k, Agent a) {
        double u = k.prio * 10 - k.tries * 4;
        if (k.at != null) u -= Crew.dist(a, k.at[0], k.at[1], k.at[2]) * 0.15;
        if (k.only == a || k.sameAs != null) u += 3;
        return u;
    }

    Card best(Agent a, WorldServer w) {
        Card best = null;
        double bu = -1e9;
        for (Card k : cards) {
            if (!fits(k, a, w)) continue;
            double u = utility(k, a);
            if (u > bu) { bu = u; best = k; }
        }
        return best;
    }

    /** an idle agent: a card, else the haul of a full bag, else the biggest deficit; false when nothing */
    boolean feed(Agent a, WorldServer w) {
        if (stackables(a) >= 12 && chestThere(w)) {
            Crew.job(a, c.new Stash(null, 0), Origin.AUTO_DEFAULT);
            Ledger.act(a, "haul");
            return true;
        }
        Card k = best(a, w);
        if (k != null) { take(k, a, w); return true; }
        Long r = rest.get(a);
        final Crew.Task d = r != null && r > now || c.current == null ? null : deficit(a, w);
        if (d != null) {
            Crew.job(a, new Crew.Task() {
                public Result step(Agent ag, WorldServer ww) {
                    Result x = d.step(ag, ww);
                    if (x.done()) {
                        releaseBlocks(ag);
                        if (x.failed()) rest.put(ag, now + 20 * 10);   // nothing came of it: not again at once
                    }
                    return x;
                }
            }, Origin.AUTO_DEFAULT);
            return true;
        }
        if (stackables(a) > 0 && chestThere(w) && c.current == null) { Crew.job(a, c.new Stash(null, 0), Origin.AUTO_DEFAULT); return true; }
        return false;
    }

    /** an agent on a default job leaves it when a card it fits is waiting */
    boolean preempt(Agent a, WorldServer w) {
        Card k = best(a, w);
        if (k == null) return false;
        Crew.clearJobs(a);
        a.getNavigator().clearPathEntity();
        releaseBlocks(a);
        take(k, a, w);
        return true;
    }

    void take(final Card k, final Agent a, WorldServer w) {
        k.state = State.LEASED;
        k.holder = a;
        k.since = now;
        leased.put(a, k);
        if (!k.wants.isEmpty()) {
            Map<Item, Integer> h = new HashMap<Item, Integer>(k.wants);
            held.put(k, h);
        }
        final Crew.Task t = k.make.make();
        Crew.job(a, new Crew.Task() {
            public Result step(Agent ag, WorldServer ww) {
                if (k.state != State.LEASED || k.holder != ag) return Result.GONE;   // taken back (lease out, phase over)
                Result r = t.step(ag, ww);
                if (r.done()) finish(k, ag, r);
                return r;
            }
        }, k.origin);
        Director.log("board: " + a.getCustomNameTag() + " takes " + k + (k.tries > 0 ? " (try " + (k.tries + 1) + ")" : ""));
    }

    void finish(Card k, Agent a, Result r) {
        release(a, k);
        k.last = r;
        if (r == Result.OK) { k.state = State.DONE; k.doneBy = a; return; }
        k.tries++;
        k.state = k.tries >= k.maxTries || k.only != null && r == Result.NO_TOOL ? State.FAILED : State.OPEN;
        Director.log("board: " + k + " by " + a.getCustomNameTag() + " -> " + r + (k.state == State.OPEN ? ", back on the board" : ", given up"));
    }

    void release(Agent a, Card k) {
        k.holder = null;
        if (leased.get(a) == k) leased.remove(a);
        held.remove(k);
        releaseBlocks(a);
        for (List<Agent> s : stations.values()) s.remove(a);
    }

    /** each tick: leases running out */
    void tick(WorldServer w) {
        now++;
        for (Card k : cards) {
            if (k.state == State.OPEN && ready(k) && now % 20 == 0) {   // nobody can take it (no tool, no items, no one pinned): given up after a while
                boolean someone = false;
                for (Agent a : c.agents) if (!a.isDead && fits(k, a, w)) someone = true;
                if (someone) k.unfit = 0;
                else if ((k.unfit += 20) > 20 * 30) {
                    k.state = State.FAILED;
                    k.last = k.need != null ? Result.NO_TOOL : Result.NO_ITEMS;
                    Ledger.result(k.last);
                    Director.log("board: " + k + " nobody can take it -> " + k.last);
                }
            }
            if (k.state != State.LEASED) continue;
            Agent a = k.holder;
            if (a == null || a.isDead || now - k.since > k.lease) {
                if (a != null) {
                    Crew.clearJobs(a);
                    a.getNavigator().clearPathEntity();
                    release(a, k);
                }
                k.tries++;
                k.last = Result.TIMEOUT;
                Ledger.result(Result.TIMEOUT);
                k.state = k.tries >= k.maxTries ? State.FAILED : State.OPEN;
                Director.log("board: " + k + " lease out" + (a == null ? "" : " (" + a.getCustomNameTag() + ")") + (k.state == State.OPEN ? ", back on the board" : ", given up"));
            }
        }
    }

    // ---- reservations ---------------------------------------------------------------------------------

    /** a target block for one agent (a tree, a block of a plan): false when someone else has it */
    boolean reserve(Agent a, int x, int y, int z) {
        long key = Crew.key(x, y, z);
        Agent o = blocks.get(key);
        if (o != null && o != a && !o.isDead) return false;
        blocks.put(key, a);
        return true;
    }

    boolean reserved(Agent a, int x, int y, int z) {
        Agent o = blocks.get(Crew.key(x, y, z));
        return o != null && o != a && !o.isDead;
    }

    void unreserve(int x, int y, int z) {
        blocks.remove(Crew.key(x, y, z));
    }

    void releaseBlocks(Agent a) {
        for (java.util.Iterator<Map.Entry<Long, Agent>> it = blocks.entrySet().iterator(); it.hasNext(); ) if (it.next().getValue() == a) it.remove();
    }

    /** a station of the game with one inventory (the furnace): one agent at a time */
    boolean station(String name, Agent a, int slots) {
        List<Agent> s = stations.get(name);
        if (s == null) stations.put(name, s = new ArrayList<Agent>());
        for (java.util.Iterator<Agent> it = s.iterator(); it.hasNext(); ) if (it.next().isDead) it.remove();
        if (s.contains(a)) return true;
        if (s.size() >= slots) return false;
        s.add(a);
        return true;
    }

    void leave(String name, Agent a) {
        List<Agent> s = stations.get(name);
        if (s != null) s.remove(a);
    }

    static int chestCount(IInventory inv, Item item) {
        if (inv == null) return 0;
        int n = Crew.count(inv, item);
        if (item == Day1.LOG) n += Crew.count(inv, Day1.LOG2);
        return n;
    }

    /** what of an item the chest has that no other card counts on (for the card an agent holds: its share too) */
    int chestFree(WorldServer w, Item item, Agent forAgent) {
        int n = chestCount(c.chestInv(w), item);
        Card mine = forAgent == null ? null : leased.get(forAgent);
        for (Map.Entry<Card, Map<Item, Integer>> e : held.entrySet()) {
            if (e.getKey() == mine) continue;
            Integer v = e.getValue().get(item);
            if (v != null) n -= v;
        }
        return Math.max(0, n);
    }

    /** an agent took some from the chest: its card's hold on it shrinks */
    void took(Agent a, Item item, int n) {
        Card k = leased.get(a);
        Map<Item, Integer> h = k == null ? null : held.get(k);
        if (h == null) return;
        Integer v = h.get(item == Day1.LOG2 ? Day1.LOG : item);
        if (v != null) h.put(item == Day1.LOG2 ? Day1.LOG : item, Math.max(0, v - n));
    }

    boolean chestThere(WorldServer w) {
        return c.chest != null && w.getBlock(c.chest[0], c.chest[1], c.chest[2]) == Blocks.chest;
    }

    static int stackables(Agent a) {
        int n = 0;
        for (ItemStack s : a.bag) if (s.isStackable()) n += s.stackSize;
        return n;
    }

    // ---- the default: the crew's biggest deficit ------------------------------------------------------

    int has(WorldServer w, Item item) {
        int n = chestCount(c.chestInv(w), item);
        for (Agent a : c.agents) n += Skills.count(a.bag, item, -1) + (item == Day1.LOG ? Skills.count(a.bag, Day1.LOG2, -1) : 0);
        return n;
    }

    Crew.Task deficit(Agent a, WorldServer w) {
        Item worst = null;
        double wd = 0;
        for (Map.Entry<Item, Integer> e : goals.entrySet()) {
            int lack = e.getValue() - has(w, e.getKey());
            if (lack <= 0) continue;
            if (e.getKey() == Day1.COBBLE && !Day1.hasPickaxe(a)) continue;   // stone wants a pickaxe
            double d = lack / (double) e.getValue();
            if (d > wd) { wd = d; worst = e.getKey(); }
        }
        if (worst == null) return null;
        if (worst == Day1.LOG) return new TaskBoard.Seq(new Day1.ChopMore(c, goals.get(worst)), c.new Stash(null, 0));
        if (worst == Day1.COBBLE) {
            List<int[]> st = Crew.openStone(c, w, a, 6);
            if (st.isEmpty()) return null;
            return new TaskBoard.Seq(new Crew.Mine(st), new Crew.Collect(st.get(0)[0] + 0.5, st.get(0)[1], st.get(0)[2] + 0.5, 5), c.new Stash(null, 0));
        }
        return null;
    }

    // ---- a few tasks one after another ----------------------------------------------------------------

    /** steps in order; a failed step ends it with its result (unless lenient: it goes on) */
    static class Seq implements Crew.Task {
        final Crew.Task[] steps;
        boolean lenient;
        int i;
        Seq(Crew.Task... steps) { this.steps = steps; }
        Seq lenient() { lenient = true; return this; }
        public Result step(Agent a, WorldServer w) {
            if (i >= steps.length) return Result.OK;
            Result r = steps[i].step(a, w);
            if (r == Result.RUNNING) return r;
            if (r.failed() && !lenient) return r;
            if (r.failed()) Director.log("crew: " + a.getCustomNameTag() + " step " + steps[i].getClass().getSimpleName() + " -> " + r + " (going on)");
            i++;
            return i >= steps.length ? Result.OK : Result.RUNNING;
        }
    }

    String summary() {
        int open = 0, done = 0, failed = 0, leasedN = 0;
        for (Card k : cards)
            switch (k.state) {
                case OPEN: open++; break;
                case LEASED: leasedN++; break;
                case DONE: done++; break;
                default: failed++;
            }
        StringBuilder b = new StringBuilder("board: " + cards.size() + " cards, done " + done + ", failed " + failed + ", open " + open + ", leased " + leasedN);
        for (Card k : cards) if (k.state == State.FAILED) b.append("; failed ").append(k).append(" -> ").append(k.last);
        return b.toString();
    }
}
