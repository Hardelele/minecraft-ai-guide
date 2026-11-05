package hardelele.aiguide.client;

/**
 * One estimate of how long work takes, for every card and every phase: a walk (blocks of way), blocks to dig, blocks
 * to put, crafts, smelts and waits, at the agents' own pace, with room for the way being longer than the straight line.
 * A card's lease and a phase's time limit both come from here.
 */
class Budget {
    static final double WALK = 0.35, DIG = 1.2, PLACE = 0.6, CRAFT = 0.8, SMELT = 10.0;   // seconds each
    static final int SLACK = 45;   // seconds always added (getting started, a detour)

    /** ticks for the work, 1.5 times the estimate (the way is not straight) plus the slack */
    static int ticks(double walk, int dig, int place, int craft, int smelt, int waitTicks) {
        double s = walk * WALK + dig * DIG + place * PLACE + craft * CRAFT + smelt * SMELT;
        return (int) Math.ceil((s * 1.5 + SLACK) * 20) + waitTicks;
    }

    /** a phase of several cards worked at once by the crew: the longest card, or all of them shared, whichever is more */
    static int phase(int sumTicks, int longestTicks, int agents) {
        return Math.max(longestTicks, sumTicks / Math.max(1, agents)) + SLACK * 20;
    }
}
