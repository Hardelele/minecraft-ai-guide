package hardelele.aiguide.client;

/**
 * One reflex of an agent: a cheap check every tick on the snapshot of what the agent senses (Situation), and a name
 * and a priority. The reaction itself (a Crew.Task acting through the body only) is made on the game's side
 * (Reacts); this side has no game in it, so the choosing is checked without one (client/test).
 */
interface Reflex {
    /** "fall", "lava", "creeper", "undo", "climb", ... (the journal's name of it) */
    String name();

    /** higher goes first; it interrupts a task only above the task's guard (Situation.taskGuard) */
    int priority();

    /** O(1) on the snapshot: no entity lists, no floods, no scans of blocks */
    boolean wants(Situation st);

    /** a reaction about the way the task walks (climb, descend, stuck, water): when it fails, the walk fails with it */
    default boolean aboutTheWay() { return false; }

    /** ticks the same reflex waits after a failed reaction before it may fire again (no reflex loops) */
    default int rest() { return 40; }
}
