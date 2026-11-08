package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.List;

/**
 * The board and the player's orders (no world: stand-in doers). A unit on the player's orders is back with the AI by
 * itself once its orders are done, unless held by Stop. A plan's card nobody may take only because everyone who could is
 * on the player's orders waits (not NO_ITEMS: the phase does not fail, the guide is not told), the player is told once,
 * and only after the wait's limit it is NO_AGENT, which the replanning does not count as a failure of the plan.
 */
public final class BoardOrdersTest {
    /** a member of the crew with no body: it can do any card, it does nothing */
    static final class Stand implements Doer {
        final int n;
        boolean ordered, holding, alive = true, begun, cancelled;
        Stand(int n) { this.n = n; }
        public int num() { return n; }
        public String who() { return "stand-in " + n; }
        public boolean alive() { return alive; }
        public boolean ordered() { return ordered; }
        public void ordered(boolean on) { ordered = on; }
        public boolean holding() { return holding; }
        public void holding(boolean on) { holding = on; }
        public double px() { return 0; }
        public double py() { return 64; }
        public double pz() { return 0; }
        public boolean can(TaskBoard.Card k) { return true; }
        public boolean paused() { return false; }
        public void begin(TaskBoard b, TaskBoard.Card k) { begun = true; }
        public void cancel(String why) { cancelled = true; }
    }

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    static TaskBoard.Card card(Crew c, String kind, Origin o) {
        Origin was = Crew.originNow;
        Crew.originNow = o;
        TaskBoard.Card k = c.board.post(kind, () -> null);
        Crew.originNow = was;
        return k;
    }

    static void ticks(Crew c, int n) {
        for (int i = 0; i < n; i++) c.board.tick(null);
    }

    static int notes(String key) {
        int n = 0;
        List<String[]> keep = new ArrayList<String[]>();
        for (String[] s; (s = Crew.notes.poll()) != null; ) { keep.add(s); if (s[0].equals(key)) n++; }
        return n;
    }

    /** an order done: back with the AI; held (Stop), or with an order still open: not */
    static void muster() {
        Crew c = new Crew();
        Stand done = new Stand(2), held = new Stand(3), busy = new Stand(4);
        c.extra.add(done);
        c.extra.add(held);
        c.extra.add(busy);
        TaskBoard.Card order = card(c, "order chop", Origin.PLAYER).pin(done);
        c.board.take(order, done, null);
        done.ordered(true);
        held.ordered(true);
        held.holding(true);
        TaskBoard.Card queued = card(c, "order move", Origin.PLAYER).pin(busy);   // open, not taken yet
        busy.ordered(true);
        ticks(c, 40);
        check(done.ordered, "a unit on an order is not let go while it does it");
        c.board.finish(order, done, Result.OK);
        ticks(c, 20);
        check(!done.ordered, "its orders done, the unit is back with the AI");
        check(held.ordered, "a unit held by Stop stays the player's");
        check(busy.ordered, "a unit with an order still open stays the player's");
        queued.state = TaskBoard.State.FAILED;   // the player changed it
        ticks(c, 20);
        check(!busy.ordered, "its last order gone, it is back too");
        System.out.println("BoardOrdersTest muster PASS");
    }

    /** everyone on the player's orders: the plan's card waits (said once), NO_AGENT only after the limit */
    static void stall() {
        Crew.notes.clear();
        Crew c = new Crew();
        Stand a = new Stand(2);
        c.extra.add(a);
        TaskBoard.Card order = card(c, "order guard", Origin.PLAYER).pin(a);
        c.board.take(order, a, null);
        a.ordered(true);
        TaskBoard.Card plan = card(c, "pickaxes", Origin.LLM_PLAN);
        ticks(c, 20 * 45);   // past the 30 s after which a card nobody can take is given up
        check(plan.state == TaskBoard.State.OPEN, "the plan's card waits for the player's orders, state " + plan.state + " " + plan.last);
        check(plan.last != Result.NO_ITEMS, "not short of anything");
        check(plan.stalled > 0 && c.board.stalled, "the board knows it waits");
        check(notes("stall") == 1, "the player is told once");
        ticks(c, 20 * 60);
        check(notes("stall") == 0, "and not again while it waits");
        ticks(c, TaskBoard.STALL_MAX);
        check(plan.state == TaskBoard.State.FAILED && plan.last == Result.NO_AGENT, "after the wait's limit: NO_AGENT, was " + plan.state + " " + plan.last);
        List<TaskBoard.Card> failed = new ArrayList<TaskBoard.Card>();
        failed.add(plan);
        check(Compiler.onlyNoAgent(failed), "the replanning sees no failure of the plan in it");
        TaskBoard.Card other = new TaskBoard.Card(99, "camp", () -> null);
        other.last = Result.NO_ITEMS;
        failed.add(other);
        check(!Compiler.onlyNoAgent(failed), "a real shortage is still one");

        // the order done: the unit is back with the AI and may take the next plan card at once; a new wait is told again
        Crew.notes.clear();
        TaskBoard.Card next = card(c, "camp", Origin.LLM_PLAN);
        ticks(c, 40);
        check(next.stalled > 0, "a second card waits too");
        c.board.finish(order, a, Result.OK);
        ticks(c, 40);
        check(!a.ordered, "back with the AI");
        check(c.board.fits(next, a, null), "and it may take the waiting card");
        check(next.stalled == 0 && !c.board.stalled, "the card does not wait any more");

        // nobody at all could (no one on orders): the old rule, given up after 30 s, not NO_AGENT
        Crew c2 = new Crew();
        TaskBoard.Card lone = card(c2, "pickaxes", Origin.LLM_PLAN);
        ticks(c2, 20 * 45);
        check(lone.state == TaskBoard.State.FAILED && lone.last != Result.NO_AGENT, "with nobody on orders it is a real failure: " + lone.last);
        System.out.println("BoardOrdersTest stall PASS");
    }

    /** the player's character (number 1) is a doer like the others: pinned cards are its, groups by number */
    static void numbers() {
        Crew c = new Crew();
        Stand me = new Stand(1), b = new Stand(2);
        c.extra.add(me);
        c.extra.add(b);
        TaskBoard.Card mine = card(c, "order move", Origin.PLAYER).pin(me);
        check(c.board.fits(mine, me, null) && !c.board.fits(mine, b, null), "a card pinned to 1 is the player's character's only");
        TaskBoard.Card group = card(c, "paint dig", Origin.PLAYER);
        group.group = new java.util.HashSet<Integer>(java.util.Arrays.asList(1));
        check(c.board.fits(group, me, null) && !c.board.fits(group, b, null), "a group of numbers");
        check(c.doer(1) == me && c.doer(2) == b && c.doer(3) == null, "the crew's member by number");
        System.out.println("BoardOrdersTest numbers PASS");
    }

    /** the replanning's limits are a plan's: a new list of goals starts them again */
    static void planBudget() {
        Compiler.replans = 4;
        Compiler.told = 2;
        for (int i = 0; i < Retry.GIVE_UP; i++) Compiler.phaseRetry.failed("upgrade", 0);
        check(Compiler.phaseRetry.givenUp("upgrade"), "the phase was given up");
        Compiler.fresh();
        check(Compiler.replans == 0 && Compiler.told == 0 && !Compiler.phaseRetry.givenUp("upgrade"), "a new plan: replans, words to the guide and the phases' tries anew");
        System.out.println("BoardOrdersTest planBudget PASS");
    }

    /** a failed card's line names the card and the step it failed at (not an empty anonymous class) */
    static void names() {
        Crew c = new Crew();
        TaskBoard.Card k = card(c, "furnace", Origin.LLM_PLAN);
        TaskBoard.Seq s = new TaskBoard.Seq(new Crew.Look(0, 0, 0, 1), new Crew.Wait(() -> true));
        s.i = 1;
        String n = TaskBoard.name(new TaskBoard.CardJob(c.board, k, s));
        check(n.equals("furnace/Wait"), "the card's line: " + n);
        check(!TaskBoard.name(new Crew.Task() { public Result step(Agent a, net.minecraft.world.WorldServer w) { return Result.OK; } }).isEmpty(), "an anonymous task has a name too");
        System.out.println("BoardOrdersTest names PASS");
    }

    public static void main(String[] args) {
        muster();
        stall();
        numbers();
        planBudget();
        names();
        System.out.println("BoardOrdersTest PASS");
    }
}
