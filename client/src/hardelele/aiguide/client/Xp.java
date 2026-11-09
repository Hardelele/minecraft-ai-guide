package hardelele.aiguide.client;

/**
 * An agent's experience as a player's of 1.7.10 (EntityPlayer.xpBarCap, addExperience, addExperienceLevel), with no game
 * in it: the points of the level being filled, the level, the total. RlTest checks it.
 */
final class Xp {
    int level, total;
    float progress;   // 0..1 of the bar

    /** the points a level takes to fill (EntityPlayer.xpBarCap) */
    static int cap(int level) {
        return level >= 30 ? 62 + (level - 30) * 7 : level >= 15 ? 17 + (level - 15) * 3 : 17;
    }

    /** points picked up (an orb) */
    void add(int points) {
        int room = Integer.MAX_VALUE - total;
        if (points > room) points = room;
        progress += points / (float) cap(level);
        total += points;
        while (progress >= 1.0F) {
            progress = (progress - 1.0F) * cap(level);
            level++;
            progress /= cap(level);
        }
    }

    /** levels spent (an enchanting: EntityPlayer.addExperienceLevel(-n)) */
    void spend(int levels) {
        level = Math.max(0, level - levels);
        if (level == 0 && levels > 0) progress = 0;
    }

    /** the levels given at the start (a scenario's): an empty bar */
    void setLevels(int levels) {
        level = Math.max(0, levels);
        progress = 0;
        total = 0;
        for (int i = 0; i < level; i++) total += cap(i);
    }
}
