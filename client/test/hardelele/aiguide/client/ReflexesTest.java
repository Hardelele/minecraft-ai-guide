package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Checks of layer 2 with no game: the choice of a reflex by the snapshot, the slot's pause and return, the undo duties, the walks' backing off, the climbing moves. */
public class ReflexesTest {
    static int failed, passed;

    static void check(String what, boolean ok) {
        if (ok) passed++;
        else failed++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    static Situation calm() {
        Situation s = new Situation();
        s.now = 1000;
        return s;
    }

    static String pick(Situation s) {
        Reflex r = Reflexes.pick(s);
        return r == null ? "-" : r.name();
    }

    /** a fake host: reactions are counters of ticks, the task's steps are counted */
    static class Host implements Reflexes.Slot.Host<int[]> {
        int taskSteps, paused, resumed, began, ended;
        Result lastFailed, lastResult;
        String lastNote;
        final List<String> names = new ArrayList<String>();
        int[] react(String name) { return new int[] { name.equals("lava") ? 3 : 5, 0 }; }
        public int[] react(Reflex r) { names.add(r.name()); return react(r.name()); }
        public Result step(int[] t) { return ++t[1] >= t[0] ? (t.length > 2 ? Result.NO_PATH : Result.OK) : Result.RUNNING; }
        public void paused() { paused++; }
        public void resumed(Result f) { resumed++; lastFailed = f; }
        public void began(Reflex r, int[] t) { began++; }
        public void ended(Reflex r, int[] t, Result res, long ticks, String note) { ended++; lastResult = res; lastNote = note; }
    }

    /** a tick of an agent: the slot first, the task only when the slot leaves the body to it */
    static boolean tick(Reflexes.Slot<int[]> slot, Situation s, Host h) {
        boolean took = slot.tick(s, h);
        if (!took) h.taskSteps++;
        s.now++;
        return took;
    }

    static class Blocks implements Sight.Space {
        final Set<Long> solid = new HashSet<Long>();
        void put(int x, int y, int z) { solid.add(Sight.key(x, y, z)); }
        public boolean solid(int x, int y, int z) { return y < 0 || solid.contains(Sight.key(x, y, z)); }
        public boolean free(int x, int y, int z) { return !solid(x, y, z); }
        public boolean floor(int x, int y, int z) { return solid(x, y, z); }
    }

    public static void main(String[] args) {
        // ---- the choice ----
        Situation s = calm();
        check("nothing wrong: no reflex", pick(s).equals("-"));

        s = calm(); s.inLava = true; s.falling = true; s.fall = 4; s.drop = 3; s.threat = "Creeper"; s.threatDist = 2;
        check("in lava, falling and a creeper near: lava first", pick(s).equals("lava"));
        s.inLava = false;
        check("falling and a creeper near: the fall first", pick(s).equals("fall"));
        s.falling = false;
        check("a creeper at 2 m: away from it", pick(s).equals("creeper"));
        s.fighting = true;
        check("a creeper while fighting: the fight goes on (its guard is 85)", pick(s).equals("-"));

        s = calm(); s.threat = "Creeper"; s.threatDist = 6;
        check("a creeper at 6 m, quiet: nothing yet", pick(s).equals("-"));
        s.threatFuse = 1;
        check("a creeper at 6 m, hissing: away", pick(s).equals("creeper"));

        s = calm(); s.threat = "Zombie"; s.threatDist = 2.5;
        check("a zombie at arm's length: mob", pick(s).equals("mob"));
        s.threatDist = 6;
        check("a zombie at 6 m: nothing (the guard's watch is for that)", pick(s).equals("-"));
        s.hurt = true; s.hurtBy = "Zombie";
        check("a zombie at 6 m that hit it: mob", pick(s).equals("mob"));

        s = calm(); s.burning = true;
        check("burning: fire", pick(s).equals("fire"));
        s.inWater = true;
        check("burning in water: nothing (the water puts it out)", pick(s).equals("-") || pick(s).equals("water"));

        s = calm(); s.underWater = true; s.inWater = true; s.air = 40;
        check("under water out of breath: drown", pick(s).equals("drown"));
        s.air = 200;
        check("under water with breath: not drown", !pick(s).equals("drown"));

        s = calm(); s.hurt = true; s.hurtBy = "inWall";
        check("hurt in a wall: smother", pick(s).equals("smother"));

        s = calm(); s.falling = true; s.fall = 1.5F; s.drop = 3;
        check("a short fall: nothing", pick(s).equals("-"));
        s.fall = 3; s.drop = 1;
        check("a fall of 3 a block over the ground: nothing to do", pick(s).equals("-"));
        s.drop = 4; s.inWater = true;
        check("falling into water: nothing", pick(s).equals("-"));

        // the way
        s = calm(); s.walking = true; s.goalDy = 3; s.goalFlat = 1; s.stuck = 50; s.still = 30; s.blocks = 4;
        check("goal 3 up, near, stuck 2.5 s, blocks in the bag: climb", pick(s).equals("climb"));
        s.blocks = 0;
        check("the same with no blocks and no tool: nothing", pick(s).equals("-"));
        s.digTool = true;
        check("the same with a pickaxe: climb (steps cut)", pick(s).equals("climb"));
        s.digging = true;
        check("the walk is digging its way: not stuck", pick(s).equals("-"));
        s.digging = false; s.goalFlat = 8;
        check("goal 3 up but 8 away: its walk's business", pick(s).equals("-"));
        s.pit = true;
        check("goal 3 up, 8 away, in a pit: climb", pick(s).equals("climb"));

        s = calm(); s.walking = true; s.goalDy = -4; s.goalFlat = 3; s.stuck = 50; s.still = 30; s.digTool = true;
        check("goal 4 down, stuck, a tool: descend", pick(s).equals("descend"));
        s.digTool = false;
        check("goal 4 down without a tool: nothing", pick(s).equals("-"));

        s = calm(); s.walking = true; s.goalDy = 0; s.goalFlat = 6; s.stuck = 70; s.still = 50;
        check("goal on the level, stuck 3.5 s, not moving: stuck", pick(s).equals("stuck"));
        s.still = 0;
        check("stuck on the goal but moving about (a way round): nothing", pick(s).equals("-"));

        s = calm(); s.walking = true; s.inWater = true; s.inWaterTicks = 30; s.stuck = 25;
        check("in water, walk stuck: water", pick(s).equals("water"));
        s.walking = false; s.inWaterTicks = 30;
        check("in water 1.5 s, no walk: not yet", pick(s).equals("-"));
        s.inWaterTicks = 70;
        check("in water 3.5 s, no walk: water", pick(s).equals("water"));

        s = calm(); s.failsHere = 3; s.pit = true; s.still = 40; s.blocks = 2;
        check("three walks failed from a pit: escape", pick(s).equals("escape"));
        s.failsHere = 2;
        check("two walks failed from a pit: not yet", pick(s).equals("-"));

        s = calm(); s.owedDue = 1;
        check("a duty due: undo", pick(s).equals("undo"));
        s.taskGuard = 70;
        check("a duty due while on a pillar (guard 70): not now", pick(s).equals("-"));
        s.inLava = true;
        check("lava on a pillar: lava still", pick(s).equals("lava"));

        s = calm(); s.walking = true; s.goalDy = 0; s.goalFlat = 6; s.stuck = 70; s.still = 50; s.taskGuard = 45;
        check("a player's card (guard 45): stuck (45) does not interrupt it", pick(s).equals("-"));

        // the order of the list
        boolean sorted = true;
        for (int i = 1; i < Reflexes.ALL.size(); i++) if (Reflexes.ALL.get(i - 1).priority() < Reflexes.ALL.get(i).priority()) sorted = false;
        check("the reflexes are in the order of their priority", sorted);

        // ---- the slot: pause and return ----
        Reflexes.Slot<int[]> slot = new Reflexes.Slot<int[]>();
        Host h = new Host();
        s = calm();
        for (int i = 0; i < 3; i++) tick(slot, s, h);
        check("no reflex: the task steps every tick", h.taskSteps == 3 && h.paused == 0);
        s.threat = "Creeper"; s.threatDist = 2;
        int before = h.taskSteps;
        tick(slot, s, h);
        check("a creeper: the reaction begins the same tick, the task waits", h.taskSteps == before && h.paused == 1 && h.began == 1 && slot.busy());
        s.threat = ""; s.threatDist = 1e9;
        for (int i = 0; i < 3; i++) tick(slot, s, h);
        check("the task gets no step while the reaction runs", h.taskSteps == before);
        tick(slot, s, h);
        check("the reaction over: the task goes on (resumed once, not restarted)", !slot.busy() && h.resumed == 1 && h.ended == 1 && h.lastResult == Result.OK && h.lastFailed == null);
        tick(slot, s, h);
        check("and steps again", h.taskSteps == before + 1);

        // a higher one takes over
        s.threat = "Creeper"; s.threatDist = 2;
        tick(slot, s, h);
        s.threat = ""; s.inLava = true;
        tick(slot, s, h);
        check("lava while running from a creeper: lava takes over, the creeper's case is closed as displaced", "lava".equals(slot.of.name()) && h.lastNote != null && h.lastNote.startsWith("displaced"));
        s.inLava = false;
        int resumedBefore = h.resumed;
        for (int i = 0; i < 3; i++) tick(slot, s, h);
        check("the lava reaction over: the task goes on", !slot.busy() && h.resumed == resumedBefore + 1);

        // a failed reaction about the way: the walk gets it, the reflex rests
        Reflexes.Slot<int[]> s2 = new Reflexes.Slot<int[]>();
        Host h2 = new Host() {
            public int[] react(Reflex r) { names.add(r.name()); return new int[] { 2, 0, 1 }; }   // fails after 2 ticks
        };
        Situation w = calm(); w.walking = true; w.goalDy = 0; w.goalFlat = 6; w.stuck = 70; w.still = 50;
        tick(s2, w, h2);
        tick(s2, w, h2);
        check("a stuck reaction that failed: the walk is told NO_PATH", h2.lastFailed == Result.NO_PATH && s2.wayFailed(w.now) == Result.NO_PATH);
        check("taken once", s2.wayFailed(w.now) == null);
        int reacted = h2.names.size();
        for (int i = 0; i < 20; i++) tick(s2, w, h2);
        check("the same reflex rests after a failure (no loop on it)", h2.names.size() == reacted);
        w.now += 200;
        tick(s2, w, h2);
        check("and may fire again after its rest", h2.names.size() == reacted + 1);

        // asked for by a task
        Reflexes.Slot<int[]> s3 = new Reflexes.Slot<int[]>();
        check("a torch asked by its own task: taken over a guard of 30", s3.interject(new int[] { 2, 0 }, Reflexes.TORCH, "torch", true, 30));
        check("a second one while the first runs: not taken", !s3.interject(new int[] { 2, 0 }, Reflexes.TORCH, "torch", true, 30));
        Reflexes.Slot<int[]> s4 = new Reflexes.Slot<int[]>();
        check("out of another's way while on a pillar (guard 70): not taken", !s4.interject(new int[] { 2, 0 }, Reflexes.CLEAR_CELL, "clear-cell", false, 70));
        check("out of another's way at work (guard 30): taken", s4.interject(new int[] { 2, 0 }, Reflexes.CLEAR_CELL, "clear-cell", false, 30));
        Host h4 = new Host();
        Situation q = calm();
        tick(s4, q, h4);
        check("an asked-for reaction is a case like a reflex's (paused, begun)", h4.paused == 1 && h4.began == 1 && h4.taskSteps == 0);

        // ---- the undo duties ----
        Undo.Book book = new Undo.Book();
        Object job = new Object(), other = new Object();
        List<Object> live = new ArrayList<Object>();
        live.add(job);
        book.owe(Undo.remove(5, 64, 5, "pillar", job, 0));
        book.owe(Undo.remove(5, 65, 5, "pillar", job, 0));
        book.owe(Undo.remove(5, 66, 5, "pillar", job, 0));
        check("a pillar while its job is on: nothing due", book.dueCount(live) == 0 && book.next(live) == null);
        book.paid(5, 66, 5);
        check("a block taken down by the job itself: no duty", book.owed.size() == 2);
        live.clear(); live.add(other);
        check("the job gone (cleared by the board): the pillar is due", book.dueCount(live) == 2);
        check("from its top down", book.next(live).y == 65);
        Undo top = book.next(live);
        check("a try failed: kept", !book.failed(top, 10) && book.owed.contains(top));
        book.failed(top, 20);
        check("the third failure: given up", book.failed(top, 30) && !book.owed.contains(top));
        book.owe(Undo.remove(1, 1, 1, "step", null, 0));
        check("no owner: due at once", book.dueCount(live) == 2);
        List<Undo> old = book.expire(Undo.MAX_AGE + 1);
        check("five minutes old: given up", old.size() == 2 && book.isEmpty());
        book.owe(Undo.restore(2, 64, 2, 5, 0, "dug to get out", job, 0));
        book.owe(Undo.remove(3, 64, 3, "step", job, 0));
        check("a block put back comes after the blocks taken away", book.next(live).kind == Undo.Kind.REMOVE);
        book.owe(Undo.remove(3, 64, 3, "step again", job, 5));
        check("one duty per cell", book.owed.size() == 2);

        // ---- the walks that failed ----
        Reflexes.Walks walks = new Reflexes.Walks();
        long goal = Sight.key(10, 70, 10), spot = Sight.key(0, 64, 0);
        check("a new goal may be tried", walks.mayTry(goal, 0));
        walks.failed(goal, spot, 0);
        check("failed once: not again for 10 s", !walks.mayTry(goal, 100) && walks.mayTry(goal, 200));
        walks.failed(goal, spot, 200);
        check("failed twice: 20 s", !walks.mayTry(goal, 500) && walks.mayTry(goal, 600));
        check("failed three times: given up", walks.failed(goal, spot, 600) && walks.givenUp(goal, 700) && !walks.mayTry(goal, 2000));
        check("given up for two minutes, then forgotten", walks.mayTry(goal, 600 + Reflexes.Walks.FORGET + 1));
        Reflexes.Walks w2 = new Reflexes.Walks();
        w2.failed(Sight.key(1, 70, 1), spot, 0);
        w2.failed(Sight.key(2, 70, 2), spot, 10);
        w2.failed(Sight.key(3, 60, 3), spot, 20);
        check("three goals failed from one cell: a place it cannot leave", w2.failsAt(spot, 30) == 3);
        check("not from another cell", w2.failsAt(Sight.key(9, 64, 9), 30) == 0);
        w2.ok(Sight.key(1, 70, 1));
        check("got to one: it may be tried at once", w2.mayTry(Sight.key(1, 70, 1), 31));
        w2.left(spot);
        check("climbed out of the place: its goals may be tried again", w2.failsAt(spot, 40) == 0 && w2.mayTry(Sight.key(2, 70, 2), 41));
        // the case from the runs: one unreachable goal tried 81 times in a row; now a walk is started at most 3 times in 2 minutes
        Reflexes.Walks w3 = new Reflexes.Walks();
        int starts = 0;
        for (long t = 0; t < 20 * 120; t += 20) if (w3.mayTry(goal, t)) { starts++; w3.failed(goal, spot, t); }
        check("an unreachable goal asked for every second for 2 minutes: 3 walks, not 120 (" + starts + ")", starts == 3);

        // ---- the moves ----
        Blocks b = new Blocks();
        // a wall of 2 in front (+x) at the feet cell 0 0 0
        b.put(1, 0, 0); b.put(1, 1, 0);
        Moves.Dig any = (x, y, z) -> true;
        Moves.Plan p = Moves.up(b, any, 0, 0, 0, new int[] { 1, 0 }, true, false);
        check("a wall of 2 in front: a step cut (one block out over the step)", p.kind == Moves.Plan.STEP && p.dig.size() == 1 && p.dig.get(0)[1] == 1 && p.to[1] == 1);
        p = Moves.up(b, null, 0, 0, 0, new int[] { 1, 0 }, true, false);
        check("the same wall not to be dug, blocks in the bag: a pillar", p.kind == Moves.Plan.PILLAR);
        p = Moves.up(b, null, 0, 0, 0, new int[] { 1, 0 }, false, false);
        check("not to be dug and no blocks: nothing, with the reason", p.kind == Moves.Plan.NONE && !p.why.isEmpty());
        p = Moves.up(b, any, 0, 0, 0, new int[] { 1, 0 }, true, true);
        check("the goal straight over it: a pillar first", p.kind == Moves.Plan.PILLAR);
        b.put(0, 2, 0);
        p = Moves.up(b, null, 0, 0, 0, new int[] { 1, 0 }, true, true);
        check("a ceiling over the head, nothing to dig: no pillar", p.kind == Moves.Plan.NONE);
        p = Moves.up(b, any, 0, 0, 0, new int[] { 1, 0 }, true, true);
        check("a ceiling of ground: the step, the ceiling dug", p.kind == Moves.Plan.STEP && p.dig.size() == 2);

        Blocks c = new Blocks();   // flat ground at y 0 (floor below 0): a step down dug into it
        p = Moves.down(c, any, 0, 0, 0, new int[] { 1, 0 });
        check("level ground, the goal below: the floor in front dug for a step down", p.kind == Moves.Plan.STEP && p.dig.size() == 1 && p.to[1] == -1);
        p = Moves.down(c, null, 0, 0, 0, new int[] { 1, 0 });
        check("not to be dug: nothing", p.kind == Moves.Plan.NONE);
        Blocks e = new Blocks() {   // a cliff: nothing under x >= 1 down to y -10
            public boolean solid(int x, int y, int z) { return x >= 1 ? y < -10 : y < 0; }
        };
        p = Moves.down(e, any, 0, 0, 0, new int[] { 1, 0 });
        check("a cliff of 10 in front: not jumped", p.kind == Moves.Plan.NONE && p.why.contains("drop"));
        Blocks f = new Blocks() {   // a ledge 2 down in front
            public boolean solid(int x, int y, int z) { return x >= 1 ? y < -2 : y < 0; }
        };
        p = Moves.down(f, any, 0, 0, 0, new int[] { 1, 0 });
        check("a ledge 2 down: walked down", p.kind == Moves.Plan.WALK && p.to[1] == -2);
        Blocks g = new Blocks();
        g.put(1, 0, 0); g.put(1, 1, 0);
        p = Moves.down(g, any, 0, 0, 0, new int[] { 1, 0 });
        check("a wall in front, the goal below: three cells cut, a step down", p.kind == Moves.Plan.STEP && p.dig.size() == 3 && p.to[1] == -1);

        Blocks pit = new Blocks();   // a shaft at 0 0 0, 3 deep
        for (int y = 0; y < 3; y++) for (int[] d : new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) pit.put(d[0], y, d[1]);
        check("in a shaft: a pit", Moves.pit(pit, 0, 0, 0));
        check("no way out at the bottom", Moves.exit(pit, 0, 0, 0) == null);
        check("on a pillar of 2 in it: a step out onto the rim", Moves.exit(pit, 0, 2, 0) != null);
        check("on open ground: not a pit", !Moves.pit(c, 0, 0, 0));
        double[] run = Moves.away(0, 0, 3, 0, 7);
        check("away from a creeper at +3: to -4 (7 from it)", Math.abs(run[0] + 4) < 1e-9 && Math.abs(run[1]) < 1e-9);

        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
