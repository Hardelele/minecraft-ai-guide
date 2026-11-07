package hardelele.aiguide.client;

/**
 * What an agent senses and is busy with this tick, as plain values: the body's snapshot (Body.Senses), the walk its
 * task is on, the bag in short, the task and its guard. Filled once a tick on the game's side (Reacts.situation) from
 * what is already sensed; the reflexes choose by this alone (no game in it, so the choosing is tested without one).
 */
final class Situation {
    long now;

    // ---- the body (Body.Senses) -------------------------------------------------------------------------
    float health = 20, maxHealth = 20;
    boolean hurt;                 // hurt since the last tick
    String hurtBy = "";           // by what, the last time ("fall", "inWall", "inFire", a mob's name)
    float fall;                   // the fall so far
    double motionY;
    boolean onGround = true, falling, inWater, inLava, burning, underWater, collided, inWall;
    int air = 300;                // breath (300 full; it drowns below 0)
    int[] feet = { 0, 0, 0 };
    String under = "";            // the block under the feet
    int drop;                     // free cells under the feet (0: it stands on something), up to 6
    boolean headroom = true;      // the cell over the head is free (room for a jump, a pillar)
    boolean pit;                  // walled in at the feet and the head on every side (a shaft, a hole)
    boolean waterNear;            // water within a few blocks (seen lately)
    int inWaterTicks;             // in water this long without a break
    String threat = "";           // the nearest hostile mob it sees: "Creeper", "Zombie", "Skeleton", "Spider", ...
    double threatDist = 1e9;
    int threatFuse;               // a creeper's fuse: above 0 it hisses

    // ---- the bag in short ----------------------------------------------------------------------------------
    int blocks;                   // blocks it could put under itself (a pillar, a step)
    double weapon = 1;            // the best attack damage of the bag (1: bare hands)
    boolean digTool;              // something that digs ground and stone (a shovel or a pickaxe)

    // ---- the walk its task is on (Crew.GoTo, when it stepped last tick) -------------------------------------
    boolean walking;
    int goalX, goalY, goalZ;
    double goalFlat;              // how far the goal is across (not up or down)
    int goalDy;                   // the goal's feet height over its own
    int stuck;                    // ticks of the walk without getting nearer
    int walkState;                // Crew.GoTo.NAV, ROUTE, DIG
    boolean digging;              // the walk is breaking a block on its way (no progress meanwhile is not being stuck)
    int failsHere;                // walks that failed lately from this feet cell (a shaft it cannot leave)
    int still;                    // ticks it has not moved a third of a block (whatever it does)

    // ---- the task ------------------------------------------------------------------------------------------
    String task = "", card = "", origin = "";
    int taskGuard = 30;           // priority a reflex must be over to interrupt the task (Agent.guard)
    boolean fighting;             // the task is a fight (Guard.Fight): the mob reflexes leave it be
    int owedDue;                  // undo duties whose task is over

    boolean walkingDigging() { return walking && digging; }

    boolean creeper() { return threat.equals("Creeper"); }

    boolean melee() { return threat.equals("Zombie") || threat.equals("Skeleton") || threat.equals("Spider") || threat.equals("CaveSpider") || threat.equals("Silverfish") || threat.equals("PigZombie"); }

    /** the body in words: the key for the cases' search ("fall 3.2, health 14/20, creeper 3 m, on Scaffold") */
    String words() {
        StringBuilder b = new StringBuilder();
        if (inLava) b.append("in lava, ");
        if (burning) b.append("burning, ");
        if (underWater) b.append("under water air ").append(air).append(", ");
        else if (inWater) b.append("in water ").append(inWaterTicks / 20).append(" s, ");
        if (inWall) b.append("in a block, ");
        if (falling) b.append("fall ").append(Math.round(fall * 10) / 10.0).append(", ");
        else if (drop > 0) b.append("air under ").append(drop).append(", ");
        if (pit) b.append("in a pit, ");
        b.append("health ").append(Math.round(health)).append('/').append(Math.round(maxHealth));
        if (hurt) b.append(" hurt by ").append(hurtBy);
        if (!threat.isEmpty()) b.append(", ").append(threat.toLowerCase()).append(' ').append(Math.round(threatDist)).append(" m").append(threatFuse > 0 ? " hissing" : "");
        if (walking) b.append(", walk ").append(goalDy > 0 ? "up " + goalDy : goalDy < 0 ? "down " + -goalDy : "level").append(" at ").append(Math.round(goalFlat)).append(" m, stuck ").append(stuck / 20).append(" s");
        if (failsHere > 0) b.append(", ").append(failsHere).append(" walks failed here");
        if (!task.isEmpty()) b.append(", on ").append(task);
        return b.toString();
    }
}
