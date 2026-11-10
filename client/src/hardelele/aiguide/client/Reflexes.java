package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Layer 2 of an agent, the part with no game in it: the reflexes in the order of their priority and the choice of one
 * a tick (pick); the slot that runs a reaction while the task waits (Slot: the task is paused, not cancelled, and goes on
 * when the reaction is over); the memory of walks that failed (Walks: the same unreachable goal is not tried again and
 * again). The reactions and the snapshot are made on the game's side (Reacts).
 */
final class Reflexes {
    private Reflexes() { }

    /** a reflex by its name and priority alone: what a task asks for itself (a torch, out of someone's way) */
    static Reflex named(final String name, final int priority) {
        return new Rule(name, priority, false, 0) {
            boolean test(Situation st) { return false; }
        };
    }

    abstract static class Rule implements Reflex {
        final String name;
        final int priority, rest;
        final boolean way;
        Rule(String name, int priority, boolean way, int rest) { this.name = name; this.priority = priority; this.way = way; this.rest = rest; }
        public String name() { return name; }
        public int priority() { return priority; }
        public boolean aboutTheWay() { return way; }
        public int rest() { return rest; }
        public boolean wants(Situation st) { return test(st); }
        abstract boolean test(Situation st);
        public String toString() { return name + " " + priority; }
    }

    // ---- the reflexes ------------------------------------------------------------------------------------
    // priorities as in ARCHITECTURE-LAYERS.md: survival 100, a fall 90, mobs 80/75, water 70, undo 60, the way 55..45;
    // what tasks ask for themselves: clear-cell 40, torch 20. A task's guard is 30 (a player's card 45, a pillar 70, a fight 85).

    static final int TORCH = 20, CLEAR_CELL = 40;

    static final Rule LAVA = new Rule("lava", 100, false, 10) {
        boolean test(Situation st) { return st.inLava; }
    };
    static final Rule DROWN = new Rule("drown", 100, false, 10) {
        boolean test(Situation st) { return st.underWater && st.air < 60; }
    };
    static final Rule FIRE = new Rule("fire", 100, false, 10) {
        boolean test(Situation st) { return st.burning && !st.inWater && !st.inLava; }
    };
    /** buried (gravel, sand on the head) or walled in: it suffocates */
    static final Rule SMOTHER = new Rule("smother", 95, false, 10) {
        boolean test(Situation st) { return st.inWall || st.hurt && st.hurtBy.equals("inWall"); }
    };
    /** falling with air still under it: a block under itself, or toward water */
    static final Rule FALL = new Rule("fall", 90, false, 20) {
        boolean test(Situation st) { return st.falling && st.fall > 2.5F && st.drop >= 2 && !st.inWater; }
    };
    /** a creeper near, or hissing: away from it, never a hit */
    static final Rule CREEPER = new Rule("creeper", 80, false, 20) {
        boolean test(Situation st) { return st.creeper() && !st.fighting && (st.threatDist < 4 || st.threatFuse > 0 && st.threatDist < 7); }
    };
    /** a zombie, a skeleton, a spider at arm's length (or one that hit it): hit back when armed and well, else away to its own */
    static final Rule MOB = new Rule("mob", 75, false, 40) {
        boolean test(Situation st) { return st.melee() && !st.fighting && (st.threatDist < 3 || st.hurt && st.hurtBy.equals(st.threat) && st.threatDist < 8); }
    };
    /** in water and getting nowhere: out onto the nearest shore */
    static final Rule WATER = new Rule("water", 70, true, 60) {
        boolean test(Situation st) { return st.inWater && !st.inLava && st.inWaterTicks >= 20 && (st.walking ? st.stuck >= 20 : st.inWaterTicks >= 60); }
    };
    /** what a task left changed (a pillar, a hole in its wall) and is over: given back */
    static final Rule UNDO = new Rule("undo", 60, false, 100) {
        boolean test(Situation st) { return st.owedDue > 0 && st.onGround && !st.inWater && !st.walkingDigging(); }
    };
    /** a shaft or a hole it cannot walk out of (walks to several goals failed from here): up and out */
    static final Rule ESCAPE = new Rule("escape", 55, true, 200) {
        boolean test(Situation st) { return st.onGround && st.failsHere >= 3 && st.pit && st.still >= 20 && (st.blocks > 0 || st.digTool); }
    };
    /** the goal 2+ blocks up and near (a log over the head, a ledge), or up out of a pit: a pillar under itself or steps cut */
    static final Rule CLIMB = new Rule("climb", 50, true, 100) {
        boolean test(Situation st) {
            return st.walking && st.onGround && st.goalDy >= 2 && (st.goalFlat <= 3 || st.pit) && st.stuck >= 40 && st.still >= 20 && !st.walkingDigging()
                    && !st.inWater && (st.blocks > 0 || st.digTool);
        }
    };
    /** the goal 2+ blocks down (a pit, a ledge below): steps cut down toward it, never a jump of more than three */
    static final Rule DESCEND = new Rule("descend", 50, true, 100) {
        boolean test(Situation st) { return st.walking && st.onGround && st.goalDy <= -2 && st.goalFlat <= 6 && st.stuck >= 40 && st.still >= 20 && !st.walkingDigging() && !st.inWater && st.digTool; }
    };
    /**
     * walking and getting nowhere on the level for 3 s, past the path finder (on its own route or digging way): a jump,
     * a step aside, a step back, a soft block out of the way. Not about the way: when it does not help, the walk's own
     * machine goes on (a route, a way dug) and ends the walk itself; the walks' memory keeps it from coming back to it.
     */
    static final Rule STUCK = new Rule("stuck", 45, false, 100) {
        boolean test(Situation st) { return st.walking && st.walkState >= 1 && st.onGround && st.stuck >= 60 && st.still >= 40 && Math.abs(st.goalDy) <= 1 && !st.inWater && !st.walkingDigging(); }
    };

    /** every reflex, the highest priority first */
    static final List<Reflex> ALL = new ArrayList<Reflex>();
    static {
        Collections.addAll(ALL, LAVA, DROWN, FIRE, SMOTHER, FALL, CREEPER, MOB, WATER, UNDO, ESCAPE, CLIMB, DESCEND, STUCK);
        sort(ALL);
    }

    static void sort(List<Reflex> l) {
        Collections.sort(l, (p, q) -> q.priority() - p.priority());   // stable: equal ones keep their order
    }

    /** the highest one that wants to, if its priority is over the task's guard; else null */
    static Reflex pick(Situation st) {
        return pick(ALL, st, null, 0);
    }

    /** the same, over a list, leaving out those resting (name -> tick they may fire again) and those not over `over` */
    static Reflex pick(List<Reflex> all, Situation st, Map<String, Long> rest, int over) {
        int floor = Math.max(st.taskGuard, over);
        for (Reflex r : all) {
            if (r.priority() <= floor) break;
            if (rest != null) {
                Long t = rest.get(r.name());
                if (t != null && st.now < t) continue;
            }
            if (r.wants(st)) return r;
        }
        return null;
    }

    // ---- the slot: a reaction that has the body while the task waits ------------------------------------

    /**
     * Where an agent's reaction runs, in front of its jobs (what `aside` was): while it runs the task gets no step (it
     * is paused, its state untouched) and goes on when it is over. A reflex of a higher priority than the one running
     * takes over (the duties to undo stay owed). A failed reaction is kept for the task (failed) to end with, when it
     * was about its way; the same reflex then rests a while.
     */
    static final class Slot<T> {
        /** the game's side of it */
        interface Host<T> {
            /** the reaction to a reflex (null: none can be made now) */
            T react(Reflex r);
            /** one tick of the reaction */
            Result step(T t);
            /** the task is to wait: it gets no steps from now (a walk stops its legs) */
            void paused();
            /** the reaction is over (failed: why it failed, or null): the task goes on */
            void resumed(Result failed);
            /** a case for the journal: what it was, what was done, how it ended */
            void began(Reflex r, T t);
            void ended(Reflex r, T t, Result result, long ticks, String note);
        }

        T task;              // the reaction running (null: none)
        Reflex of;           // the reflex it is for
        int prio;
        long at;             // the tick it began
        boolean started;
        Result failed;       // the last reaction's failure (for the task when it goes on)
        Reflex failedOf;
        long endedAt = -1000;
        final Map<String, Long> rest = new HashMap<String, Long>();
        List<Reflex> all = ALL;

        boolean busy() { return task != null; }

        /** a reaction a task asks for itself (own) or for another (out of its way): only into an empty slot, and over the guard unless its own */
        boolean interject(T t, int priority, String why, boolean own, int guard) {
            if (task != null || t == null) return false;
            if (!own && priority <= guard) return false;
            task = t;
            of = named(why, priority);
            prio = priority;
            started = false;
            return true;
        }

        /** the failure of the last reaction, if it was about the way and just over (the walk ends with it); taken once */
        Result wayFailed(long now) {
            if (failed == null || failedOf == null || !failedOf.aboutTheWay() || now - endedAt > 2) return null;
            Result r = failed;
            failed = null;
            return r;
        }

        /** one tick: true when the body was the reaction's (the task gets no step this tick) */
        boolean tick(Situation st, Host<T> h) {
            Reflex r = pick(all, st, rest, task == null ? 0 : prio);
            if (r != null) {
                T t = h.react(r);
                if (t == null) rest.put(r.name(), st.now + 20);   // nothing to be done about it now: not asked again at once
                else {
                    if (task != null && started) h.ended(of, task, Result.BLOCKED, st.now - at, "displaced by " + r.name());
                    task = t;
                    of = r;
                    prio = r.priority();
                    started = false;
                }
            }
            if (task == null) return false;
            if (!started) {
                started = true;
                at = st.now;
                h.paused();
                h.began(of, task);
            }
            Result res = h.step(task);
            if (res == null) res = Result.BLOCKED;
            if (!res.done()) return true;
            T t = task;
            Reflex o = of;
            task = null;
            of = null;
            endedAt = st.now;
            failed = res.failed() ? res : null;
            failedOf = o;
            if (res.failed()) rest.put(o.name(), st.now + o.rest());
            h.ended(o, t, res, st.now - at + 1, null);
            h.resumed(failed);
            return true;
        }
    }

    // ---- the walks that failed -----------------------------------------------------------------------------

    /**
     * An agent's memory of walks that did not get there: the same goal waits 10 s after a failure, 20 after the second,
     * and after the third it is not tried again for two minutes (the one Retry policy; then forgotten: the world may have
     * changed). It also counts the goals that failed from one feet cell: three or more is a place it cannot leave.
     */
    static final class Walks {
        static final int FORGET = 20 * 120;
        final Retry retry = new Retry();
        final Map<Long, Long> last = new HashMap<Long, Long>();          // goal -> its last failure
        final Map<Long, Map<Long, Long>> from = new HashMap<Long, Map<Long, Long>>();   // feet cell -> goal -> when it failed from there

        private void forget(long goal, long now) {
            Long t = last.get(goal);
            if (t != null && now - t > FORGET) { last.remove(goal); retry.ok(goal); }
        }

        /** the goal is no more (a felled tree): its failed walks forgotten at once */
        void forget(long goal) {
            last.remove(goal);
            retry.ok(goal);
            for (Map<Long, Long> m : from.values()) m.remove(goal);
        }

        /** may a walk to it be tried now */
        boolean mayTry(long goal, long now) {
            forget(goal, now);
            return retry.allowed(goal, now);
        }

        boolean givenUp(long goal, long now) {
            forget(goal, now);
            return retry.givenUp(goal);
        }

        int failures(long goal) { return retry.failures(goal); }

        /** a walk to it failed from that feet cell: true when it is given up now */
        boolean failed(long goal, long spot, long now) {
            forget(goal, now);
            last.put(goal, now);
            Map<Long, Long> m = from.get(spot);
            if (m == null) {
                if (from.size() > 64) from.clear();   // a memory of places, not a map of the world
                from.put(spot, m = new HashMap<Long, Long>());
            }
            m.put(goal, now);
            return retry.failed(goal, now);
        }

        final Map<Long, Long> told = new HashMap<Long, Long>();

        /** to be told now that a walk to it is not tried (once in 10 s a goal, not every time it is asked) */
        boolean tell(long goal, long now) {
            Long t = told.get(goal);
            if (t != null && now - t < 200) return false;
            if (told.size() > 256) told.clear();
            told.put(goal, now);
            return true;
        }

        /** got there: forgotten */
        void ok(long goal) {
            retry.ok(goal);
            last.remove(goal);
        }

        /** the goals that failed lately from this feet cell */
        int failsAt(long spot, long now) {
            Map<Long, Long> m = from.get(spot);
            if (m == null) return 0;
            for (Iterator<Long> it = m.values().iterator(); it.hasNext(); ) if (now - it.next() > FORGET) it.remove();
            return m.size();
        }

        /** out of that place (it climbed out): what failed from there is not held against it */
        void left(long spot) {
            Map<Long, Long> m = from.remove(spot);
            if (m == null) return;
            Set<Long> goals = new HashSet<Long>(m.keySet());
            for (Long g : goals) ok(g);
        }
    }
}
