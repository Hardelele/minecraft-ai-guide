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
 * The crew's board: phases post cards (what is to be done, not who does it), an idle doer (an agent, or the player's
 * own character through the autopilot: Doer) takes the card that is worth most to it (priority, distance, whether it
 * can, how often it failed). A taken card is leased: past its lease it goes back on the board. What the game really
 * shares is reserved: target blocks (a tree, a block of the plan), chest quantities a card counts on, the furnace (one
 * inventory). A crafting table is not reserved: every player has his own grid at it, so do the agents, they only stand
 * around it. An agent with a full bag hauls it to the chest; an idle one with nothing on the board works on the crew's
 * biggest deficit (its goals). A card nobody may take only because everyone is on the player's orders waits for them
 * (NO_AGENT at last, not a failure of the plan); a doer whose player orders are all done is back with the AI.
 */
class TaskBoard {
    enum State { OPEN, LEASED, DONE, FAILED }

    interface Make { Crew.Task make(); }
    interface Need { boolean ok(Agent a); }

    /** what a card asks for in the player's own terms: what the autopilot can do of it (PlayerAgent), or none */
    static class Play {
        enum Kind { MOVE, CHOP, MINE, DIG, DEPOSIT, ATTACK, PLACE, CHOP_MORE }
        final Kind kind;
        final int x, y, z, id, n;
        final String item;
        Play(Kind kind, int x, int y, int z, int id, String item, int n) { this.kind = kind; this.x = x; this.y = y; this.z = z; this.id = id; this.item = item; this.n = n; }
        static Play at(Kind kind, int x, int y, int z) { return new Play(kind, x, y, z, -1, null, 0); }
        public String toString() { return kind + " " + x + " " + y + " " + z + (id >= 0 ? " #" + id : "") + (item != null ? " " + item : ""); }
    }

    static class Card {
        final int id;
        final String kind;
        final Make make;
        Origin origin;
        Crew.Phase phase;
        Doer only;            // just this one
        java.util.Set<Integer> group;   // just these (by number): they share it out among themselves
        Card sameAs;          // whoever did that card (it holds what came of it)
        final List<Card> after = new ArrayList<Card>();
        double prio = 1;
        double[] at;          // where the work is (for distance)
        Need need;
        Play play;            // the player's character can take it (through the autopilot), as this
        final Map<Item, Integer> wants = new LinkedHashMap<Item, Integer>();   // chest quantities it aims at (targets, not start conditions)
        final Map<Item, Integer> unit = new LinkedHashMap<Item, Integer>();    // what one unit of its work takes: enough of that to start
        int lease = 20 * 240, tries;
        boolean optional;     // nice to have: its failure does not fail the phase
        Card optional() { optional = true; return this; }
        State state = State.OPEN;
        Doer holder, doneBy;
        long since;
        int unfit, stalled;   // ticks nobody could take it; ticks it waited only for the player's orders
        boolean placeChecked;
        final Map<Item, Integer> shortOf = new LinkedHashMap<Item, Integer>();   // what was missing when it failed, and how much
        Result last;
        Card(int id, String kind, Make make) { this.id = id; this.kind = kind; this.make = make; }
        Card pin(Doer a) { only = a; return this; }
        Card same(Card c) { sameAs = c; return this; }
        Card after(Card... cs) { for (Card c : cs) if (c != null) after.add(c); return this; }
        Card after(List<Card> cs) { after.addAll(cs); return this; }
        Card prio(double p) { prio = p; return this; }
        Card at(double x, double y, double z) { at = new double[] { x, y, z }; return this; }
        Card need(Need n) { need = n; return this; }
        Card play(Play p) { play = p; return this; }
        List<ItemStack> needs;   // the things the 'need' asks the agent to hold (for the shortfall when nobody has them)
        Card needs(List<ItemStack> l) { needs = l; return this; }
        /** the one rule for processing cards: aims at n, starts when there is one unit's worth (what is short is topped up meanwhile) */
        Card wants(Item i, int n, int perUnit) { wants.put(i, n); unit.put(i, Math.max(1, Math.min(n, perUnit))); return this; }
        boolean leaseSet;
        Card lease(int ticks) { lease = ticks; leaseSet = true; return this; }
        /** the card's own work, its lease from the one estimate (Budget); without it the card has its phase's time */
        Card work(double walk, int dig, int place, int craft, int smelt, int waitTicks) { return lease(Budget.ticks(walk, dig, place, craft, smelt, waitTicks)); }
        boolean open() { return state == State.OPEN || state == State.LEASED; }
        public String toString() { return "#" + id + " " + kind; }
    }

    /** how long a card waits for the player's orders to be done before it is given up as NO_AGENT (10 minutes) */
    static final int STALL_MAX = 20 * 60 * 10;

    final Crew c;
    final List<Card> cards = new ArrayList<Card>();
    final Map<Doer, Card> leased = new HashMap<Doer, Card>();
    final Map<Long, Doer> blocks = new HashMap<Long, Doer>();
    final Map<Card, Map<Item, Integer>> held = new HashMap<Card, Map<Item, Integer>>();   // chest quantities held by a card
    final Map<String, List<Agent>> stations = new HashMap<String, List<Agent>>();
    final Map<Item, Integer> goals = new LinkedHashMap<Item, Integer>();
    final Retry retry = new Retry();   // the crew's one retry policy: cards, an agent's default work, its way home
    int next = 1;
    long now;
    /** a card waits only for the player's orders now (the phase waits with it); said to the player once a stop */
    boolean stalled, stallSaid;

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

    /** a card of the phase waits for the player's orders (its time does not run meanwhile) */
    boolean stalled(Crew.Phase ph) {
        if (ph == null) return false;
        for (Card k : cards) if (k.phase == ph && k.state == State.OPEN && k.stalled > 0) return true;
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

    boolean fits(Card k, Doer a, WorldServer w) {
        return fits(k, a, w, false);
    }

    /** can this one take the card at all (freeOfOrders: as if it were not on the player's orders) */
    boolean fits(Card k, Doer a, WorldServer w, boolean freeOfOrders) {
        if (k.state != State.OPEN || !ready(k) || !retry.allowed(k, now)) return false;
        if (k.only != null && !same(k.only, a)) return false;
        if (k.group != null && !k.group.contains(a.num())) return false;
        if (!freeOfOrders && a.ordered() && k.origin != Origin.PLAYER) return false;   // a commanded unit: the player's orders only
        if (k.sameAs != null && k.sameAs.doneBy != null && !same(k.sameAs.doneBy, a)) return false;
        if (k.sameAs != null && k.sameAs.doneBy == null && k.sameAs.state != State.FAILED) return false;
        if (!a.can(k)) return false;
        if (a instanceof Agent) {
            if (k.need != null && !k.need.ok((Agent) a)) return false;
            if (k.at != null && here != null && here.near((int) Math.floor(k.at[0]), (int) Math.floor(k.at[1]), (int) Math.floor(k.at[2]), Crew.REACH + 1) == null) return false;   // it cannot walk there from where it is
        } else if (k.need != null) return false;   // a need is about an agent's bag
        for (Map.Entry<Item, Integer> e : k.unit.entrySet()) if (chestFree(w, e.getKey(), null) < e.getValue()) return false;
        return true;
    }

    /** the same member of the crew: the doer itself, or the one it was before it died and came back (same number) */
    static boolean same(Doer was, Doer a) {
        return was == a || !was.alive() && was.num() == a.num();
    }

    double utility(Card k, Doer a) {
        double u = k.prio * 10 - k.tries * 4;
        if (k.at != null) {
            double dx = a.px() - k.at[0], dy = a.py() - k.at[1], dz = a.pz() - k.at[2];
            u -= Math.sqrt(dx * dx + dy * dy + dz * dz) * 0.15;
        }
        if (k.only == a || k.sameAs != null) u += 3;
        return u;
    }

    Ground.Flood here;   // the walk from the agent being matched (set by feed and preempt)

    final Map<Agent, Object[]> floods = new HashMap<Agent, Object[]>();   // agent -> { the cell it was made from, when, the flood }

    /** where an agent can walk: made again only when it has moved to another cell or 2 s have passed (the search is not cheap) */
    Ground.Flood floodOf(Agent a, WorldServer w) {
        int[] f = Ground.feet(w, a.posX, a.posY, a.posZ);
        long k = Crew.key(f[0], f[1], f[2]);
        Object[] o = floods.get(a);
        if (o != null && (Long) o[0] == k && now - (Long) o[1] < 40) return (Ground.Flood) o[2];
        Ground.Flood fl = Ground.flood(w, f, 48, null);
        floods.put(a, new Object[] { k, now, fl });
        return fl;
    }

    Card best(Doer a, WorldServer w) {
        Card best = null;
        double bu = -1e9;
        for (Card k : cards) {
            if (!fits(k, a, w)) continue;
            double u = utility(k, a);
            if (u > bu) { bu = u; best = k; }
        }
        return best;
    }

    /** a doer that is not an agent (the player's character): the best card it can take, if any */
    boolean feedDoer(Doer d, WorldServer w) {
        if (leased.containsKey(d)) return false;
        here = null;
        Card k = best(d, w);
        if (k == null) return false;
        take(k, d, w);
        return true;
    }

    /** an idle agent: a card, else the haul of a full bag, else the biggest deficit; false when nothing */
    boolean feed(Agent a, WorldServer w) {
        if (a.commanded) {   // the player's unit: its own orders, nothing else (no defaults, no hauls)
            here = floodOf(a, w);
            Card k = best(a, w);
            if (k != null) { take(k, a, w); return true; }
            return false;
        }
        if (stackables(a) >= 12 && chestThere(w)) {
            Crew.job(a, c.new Stash(null, 0), Origin.AUTO_DEFAULT);
            Ledger.act(a, "haul");
            return true;
        }
        here = floodOf(a, w);   // where it can walk from where it is
        if (c.base != null) {
            int[] b = Ground.anchor(c, w);
            if (!here.has(b[0], b[1], b[2]) && here.near(b[0], b[1], b[2], 3) == null) {   // cut off from the base: back first (the stuck machine digs a way)
                if (retry.allowed("home " + a.getEntityId(), now)) {
                    retry.postpone("home " + a.getEntityId(), now, 20 * 20);   // not again at once if it does not get there
                    Crew.job(a, new Crew.GoTo(b[0] + 0.5, b[1], b[2] + 0.5, 1.5), Origin.AUTO_DEFAULT);
                    return true;
                }
            }
        }
        Card k = best(a, w);
        if (k != null) { take(k, a, w); return true; }
        final Crew.Task d = !retry.allowed("default " + a.getEntityId(), now) || c.current == null ? null : deficit(a, w);
        if (d != null) {
            Crew.job(a, new Crew.Task() {
                public Result step(Agent ag, WorldServer ww) {
                    Result x = d.step(ag, ww);
                    if (x.done()) {
                        releaseBlocks(ag);
                        if (x.failed()) retry.failed("default " + ag.getEntityId(), now);   // nothing came of it: the retry policy says when again
                        else retry.ok("default " + ag.getEntityId());
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
        here = floodOf(a, w);
        Card k = best(a, w);
        if (k == null) return false;
        Crew.clearJobs(a);
        a.body.stop();
        releaseBlocks(a);
        take(k, a, w);
        return true;
    }

    /**
     * A card for this doer now (a fight, the player's order): what it held goes back on the board as it was (not a try
     * of it), its work stops, it takes the card. The one way anything takes a doer off its work.
     */
    void assign(Card k, Doer a, WorldServer w, String why) {
        Card held = leased.get(a);
        if (held != null && held != k) {
            release(a, held);
            held.state = State.OPEN;
            Events.log("board: " + held + " back on the board (" + a.who() + " " + why + ")");
        }
        a.cancel(why);
        releaseBlocks(a);
        take(k, a, w);
    }

    /** the card back from its holder as it was, not a try of it (the player took the controls, it had to stop) */
    void giveBack(Doer a, String why) {
        Card k = leased.get(a);
        if (k == null) return;
        release(a, k);
        k.state = State.OPEN;
        Events.log("board: " + k + " back on the board (" + a.who() + ": " + why + ")");
    }

    void take(final Card k, final Doer a, WorldServer w) {
        if (!k.leaseSet && k.phase != null) k.lease = k.phase.max;   // no estimate of its own: the time of its phase
        k.state = State.LEASED;
        k.holder = a;
        k.since = now;
        k.stalled = 0;
        leased.put(a, k);
        if (!k.wants.isEmpty()) {
            Map<Item, Integer> h = new HashMap<Item, Integer>();   // what is there of the target is held for it, no more
            for (Map.Entry<Item, Integer> e : k.wants.entrySet()) h.put(e.getKey(), Math.min(e.getValue(), chestFree(w, e.getKey(), null)));
            held.put(k, h);
        }
        a.begin(this, k);
        Events.log("board: " + a.who() + " takes " + k + (k.tries > 0 ? " (try " + (k.tries + 1) + ")" : ""));
    }

    void finish(Card k, Doer a, Result r) {
        release(a, k);
        k.last = r;
        if (r == Result.OK) { k.state = State.DONE; k.doneBy = a; return; }
        k.tries++;
        boolean last = retry.failed(k, now);   // backed off before anyone takes it again; given up after the policy's tries
        k.state = last || k.only != null && r == Result.NO_TOOL ? State.FAILED : State.OPEN;
        String why = a instanceof Agent ? ((Agent) a).why : null;
        Events.log("board: " + k + " by " + a.who() + " -> " + r + (why != null ? " (" + why + ")" : "") + (k.state == State.OPEN ? ", back on the board" : ", given up"));
    }

    void release(Doer a, Card k) {
        k.holder = null;
        if (leased.get(a) == k) leased.remove(a);
        held.remove(k);
        releaseBlocks(a);
        for (List<Agent> s : stations.values()) s.remove(a);
    }

    /** an open card of the player's for this doer (its own, or its group's) */
    boolean playerCards(Doer a) {
        for (Card k : cards)
            if (k.open() && k.origin == Origin.PLAYER && (k.only != null && same(k.only, a) || k.group != null && k.group.contains(a.num()))) return true;
        return false;
    }

    /**
     * Back with the AI by itself, as an idle unit of a strategy game: a doer under the player's command whose orders are
     * all done (none open, nothing leased) and that the player did not hold (Stop).
     */
    void muster() {
        for (Doer d : c.doers())
            if (d.alive() && d.ordered() && !d.holding() && !leased.containsKey(d) && !playerCards(d)) {
                d.ordered(false);
                Events.log("order: " + d.who() + " is back with the AI (its orders are done)");
            }
    }

    /** each tick: leases running out, cards nobody can take */
    void tick(WorldServer w) {
        now++;
        if (now % 20 == 0) muster();
        boolean stall = false;
        for (Card k : cards) {
            if (k.state == State.OPEN && !k.placeChecked && k.at != null) {   // a place nobody can walk to from the base: NO_PATH before anyone sets off
                k.placeChecked = true;
                Ground.Flood f = Ground.baseFlood(c, w);
                if (f != null && f.near((int) Math.floor(k.at[0]), (int) Math.floor(k.at[1]), (int) Math.floor(k.at[2]), Crew.REACH + 1) == null) {
                    k.state = State.FAILED;
                    k.last = Result.NO_PATH;
                    Ledger.result(Result.NO_PATH); if (k.phase != null) k.phase.tally(Result.NO_PATH);
                    Events.log("board: " + k + " cannot be reached from the base -> NO_PATH");
                    continue;
                }
            }
            if (k.state == State.OPEN && k.stalled > 0) stall = true;
            if (k.state == State.OPEN && ready(k) && retry.allowed(k, now) && now % 20 == 0) {   // nobody can take it (no tool, no items, no one pinned): given up after a while
                boolean someone = false, ifFree = false;   // someone may take it; someone would, were it not on the player's orders
                Ground.Flood keep = here;
                here = null;   // (where each one could walk is checked when it takes a card, not here)
                for (Doer a : c.doers()) {
                    if (!a.alive()) continue;
                    if (fits(k, a, w)) someone = true;
                    else if (k.origin != Origin.PLAYER && a.ordered() && fits(k, a, w, true)) ifFree = true;
                }
                here = keep;
                if (someone) { k.unfit = 0; k.stalled = 0; }
                else if (ifFree) {   // everyone who could is on the player's orders: it waits for them (not short of anything)
                    k.unfit = 0;
                    k.stalled += 20;
                    stall = true;
                    if (k.stalled > STALL_MAX) {
                        k.state = State.FAILED;
                        k.last = Result.NO_AGENT;
                        Ledger.result(k.last); if (k.phase != null) k.phase.tally(k.last);
                        Events.log("board: " + k + " waited " + STALL_MAX / 1200 + " min for the player's orders -> NO_AGENT");
                    }
                } else if ((k.unfit += 20) > 20 * 30) {
                    k.stalled = 0;
                    k.state = State.FAILED;
                    boolean needOk = false;   // what keeps everyone off it: the agent (a tool, a thing in the bag) or the chest
                    for (Agent a : c.agents) if (k.need == null || k.need.ok(a)) needOk = true;
                    k.last = needOk ? Result.NO_ITEMS : Result.NO_TOOL;
                    for (Map.Entry<Item, Integer> e : k.wants.entrySet()) {   // the chest's shortfall
                        int lack = e.getValue() - chestFree(w, e.getKey(), null);
                        if (lack > 0) k.shortOf.put(e.getKey(), lack);
                    }
                    if (!needOk && k.needs != null) for (ItemStack s : k.needs) k.shortOf.put(s.getItem(), s.stackSize);   // what the agent would have to hold
                    Ledger.result(k.last); if (k.phase != null) k.phase.tally(k.last);
                    Events.log("board: " + k + " nobody can take it -> " + k.last);
                }
            }
            if (k.state != State.LEASED) continue;
            Doer a = k.holder;
            if (a != null && !a.alive()) {   // its holder died: the card goes back as it was (not a try of its), someone takes it
                release(a, k);
                k.state = State.OPEN;
                Events.log("board: " + k + " back on the board (" + a.who() + " died)");
                continue;
            }
            if (a != null && a.paused()) { k.since++; continue; }   // a reaction has its holder's body: the lease waits with the card's work
            if (a == null || now - k.since > k.lease) {
                if (a != null) {
                    a.cancel("lease out");
                    release(a, k);
                }
                k.tries++;
                k.last = Result.TIMEOUT;
                Ledger.result(Result.TIMEOUT); if (k.phase != null) k.phase.tally(Result.TIMEOUT);
                k.state = retry.failed(k, now) ? State.FAILED : State.OPEN;
                Events.log("board: " + k + " lease out" + (a == null ? "" : " (" + a.who() + ")") + (k.state == State.OPEN ? ", back on the board" : ", given up"));
            }
        }
        if (stall && !stallSaid) {   // once a stop: the player is told why the plan stands (not the guide: nothing is short)
            stallSaid = true;
            Crew.note("stall");
            Events.log("board: the plan waits: everyone who could do it is on the player's orders");
        } else if (!stall && stalled) stallSaid = false;
        stalled = stall;
    }

    // ---- reservations ---------------------------------------------------------------------------------

    /** a target block for one doer (a tree, a block of a plan): false when someone else has it */
    boolean reserve(Doer a, int x, int y, int z) {
        long key = Crew.key(x, y, z);
        Doer o = blocks.get(key);
        if (o != null && o != a && o.alive()) return false;
        blocks.put(key, a);
        return true;
    }

    boolean reserved(Doer a, int x, int y, int z) {
        Doer o = blocks.get(Crew.key(x, y, z));
        return o != null && o != a && o.alive();
    }

    void unreserve(int x, int y, int z) {
        blocks.remove(Crew.key(x, y, z));
    }

    void releaseBlocks(Doer a) {
        for (java.util.Iterator<Map.Entry<Long, Doer>> it = blocks.entrySet().iterator(); it.hasNext(); ) if (it.next().getValue() == a) it.remove();
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

    /**
     * The defence's posts (a name of the perimeter's, or "x y z"): one doer each, held across the doer's cards (a mind's
     * steps come and go, its post stays) until it takes another, leaves it, dies, or its mind's goal is over.
     */
    final Map<String, Doer> posts = new LinkedHashMap<String, Doer>();

    /** the post for this doer (its old one let go): false when someone else alive holds it */
    boolean takePost(String post, Doer a) {
        Doer o = posts.get(post);
        if (o != null && o != a && o.alive()) return false;
        leavePost(a);
        posts.put(post, a);
        return true;
    }

    /** the post it holds, or null */
    String postOf(Doer a) {
        for (Map.Entry<String, Doer> e : posts.entrySet()) if (e.getValue() == a) return e.getKey();
        return null;
    }

    /** who holds it (alive), or null */
    Doer postHolder(String post) {
        Doer o = posts.get(post);
        return o != null && o.alive() ? o : null;
    }

    void leavePost(Doer a) {
        for (java.util.Iterator<Map.Entry<String, Doer>> it = posts.entrySet().iterator(); it.hasNext(); ) {
            Doer d = it.next().getValue();
            if (d == a || !d.alive()) it.remove();
        }
    }

    static int chestCount(IInventory inv, Item item) {
        if (inv == null) return 0;
        int n = Crew.count(inv, item);
        if (item == Day1.LOG) n += Crew.count(inv, Day1.LOG2);
        return n;
    }

    /** what of an item the chest has that no other card counts on (for the card a doer holds: its share too) */
    int chestFree(WorldServer w, Item item, Doer forAgent) {
        int n = chestCount(c.chestInv(w), item);
        Card mine = forAgent == null ? null : leased.get(forAgent);
        for (Map.Entry<Card, Map<Item, Integer>> e : held.entrySet()) {
            if (e.getKey() == mine) continue;
            Integer v = e.getValue().get(item);
            if (v != null) n -= v;
        }
        return Math.max(0, n);
    }

    /** an agent's step found too little of something (the chest short): its card remembers what and how much */
    void shortage(Agent a, Item item, int n) {
        Card k = leased.get(a);
        if (k == null || n <= 0) return;
        Item key = item == Day1.LOG2 ? Day1.LOG : item;
        Integer o = k.shortOf.get(key);
        k.shortOf.put(key, (o == null ? 0 : o) + n);
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
        return Roles.is(w, c.chest, Roles.Role.CHEST);
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

    /** a card's work as an agent's job: the card finished with what the work ends with; taken back, it ends GONE */
    static final class CardJob implements Crew.Task {
        final TaskBoard b;
        final Card k;
        final Crew.Task t;
        CardJob(TaskBoard b, Card k, Crew.Task t) { this.b = b; this.k = k; this.t = t; }
        public Result step(Agent ag, WorldServer ww) {
            if (k.state != State.LEASED || k.holder != ag) return Result.GONE;   // taken back (lease out, phase over)
            Result r = t.step(ag, ww);
            if (r.done()) b.finish(k, ag, r);
            return r;
        }
    }

    /** a task's name for the crew's lines: a card's kind and the step it is at ("furnace/PlaceFrom") */
    static String name(Crew.Task t) {
        if (t == null) return "";
        if (t instanceof CardJob) return ((CardJob) t).k.kind + "/" + name(((CardJob) t).t);
        if (t instanceof Seq) { Seq s = (Seq) t; return s.steps.length == 0 ? "Seq" : name(s.steps[Math.min(s.i, s.steps.length - 1)]); }
        String n = t.getClass().getSimpleName();
        if (n.isEmpty()) { n = t.getClass().getName(); n = n.substring(n.lastIndexOf('.') + 1); }   // an anonymous task: its outer class's name
        return n;
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
            if (r.failed()) Events.log("crew: " + a.getCustomNameTag() + " step " + steps[i].getClass().getSimpleName() + " -> " + r + " (going on)");
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
