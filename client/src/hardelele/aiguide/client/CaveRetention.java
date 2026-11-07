package hardelele.aiguide.client;

/** Keep a last valid cave shell briefly while an agent crosses liquid or a client position jitters. */
final class CaveRetention {
    static final long MAX_OLD_TICKS = 80;

    private CaveRetention() {}

    static boolean hold(boolean sameWorld, boolean sameAgent, int oldCells, long lastValidTick, long now) {
        return sameWorld && sameAgent && oldCells > 0 && lastValidTick >= 0
                && now >= lastValidTick && now - lastValidTick <= MAX_OLD_TICKS;
    }
}
