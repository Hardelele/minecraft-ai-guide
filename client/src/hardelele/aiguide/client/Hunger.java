package hardelele.aiguide.client;

/**
 * An agent's hunger as a player's (the game's FoodStats of 1.7.10), with no game in it: the food level (0-20) and the
 * saturation over it, the exhaustion its actions add (a metre walked 0.01, run 0.1, swum 0.015, a jump 0.2 or 0.8
 * running, a hit 0.3, a block broken 0.025, a hurt the damage's own, 3.0 a healed point), 4 exhaustion a point of
 * saturation, then of food. Fed (food 18+) it heals a point every 4 s; starved (food 0) it takes a point of damage every
 * 4 s down to the floor of its rule: to death by default (as the game's hard; the game's normal stops at 1). The agent
 * (Agent.onLivingUpdate) feeds it the body's numbers each tick and does what tick() says. SiegeTest checks it.
 */
final class Hunger {
    static final int MAX = 20, FED = 18, LOW = 6, TIMER = 80;
    static final float WALK = 0.01F, RUN = 0.1F, SWIM = 0.015F, JUMP = 0.2F, RUN_JUMP = 0.8F, ATTACK = 0.3F, BREAK = 0.025F, REGEN = 3.0F,
            HUNGER_EFFECT = 0.025F, MAX_EXHAUSTION = 40F;
    /** blocks a tick over which a step counts as running (a player sprints about 0.28, walks 0.22) */
    static final double RUN_SPEED = 0.27;
    /** what tick() asks the body to do */
    static final int NOTHING = 0, HEAL = 1, STARVE = -1;

    /** how far starving goes: "death" (the default, as the game's hard), "normal" (stops at 1 health), "easy" (stops at 10) */
    static float floor(String rule) {
        if ("normal".equals(rule)) return 1;
        if ("easy".equals(rule)) return 10;
        return 0;
    }

    int food = MAX, timer;
    float saturation = 5, exhaustion;
    // what it came to, for the tally and the tests
    float exhausted, satGained;
    int eaten, foodGained, healed, starvedDamage;

    void exhaust(float f) {
        if (f <= 0) return;
        exhaustion = Math.min(exhaustion + f, MAX_EXHAUSTION);
        exhausted += f;
    }

    /** a step of the body this tick: dx dy dz moved, under water (the eyes in it), in water, on the ground */
    void moved(double dx, double dy, double dz, boolean underWater, boolean inWater, boolean onGround) {
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (underWater) exhaust((float) (SWIM * Math.sqrt(dx * dx + dy * dy + dz * dz)));
        else if (inWater) exhaust((float) (SWIM * flat));
        else if (onGround) exhaust((float) ((flat > RUN_SPEED ? RUN : WALK) * flat));
    }

    /** a jump: running (the last step faster than RUN_SPEED) or not */
    void jumped(boolean running) { exhaust(running ? RUN_JUMP : JUMP); }

    /** it may eat this now (a player cannot eat when full, but for what is always edible: the golden apple) */
    boolean canEat(boolean always) { return always || food < MAX; }

    /** a food eaten: its food points and saturation modifier (the game's ItemFood numbers) */
    void eat(int points, float satMod) {
        int was = food;
        food = Math.min(points + food, MAX);
        float s = saturation;
        saturation = Math.min(saturation + points * satMod * 2.0F, food);
        eaten++;
        foodGained += food - was;
        satGained += Math.max(0, saturation - s);
    }

    /** one tick: the exhaustion spent; HEAL (a point), STARVE (a point of damage) or NOTHING, as the game's FoodStats.onUpdate */
    int tick(float health, float maxHealth, float floor) {
        if (exhaustion > 4) {
            exhaustion -= 4;
            if (saturation > 0) saturation = Math.max(saturation - 1, 0);
            else food = Math.max(food - 1, 0);
        }
        if (food >= FED && health > 0 && health < maxHealth) {
            if (++timer >= TIMER) {
                timer = 0;
                exhaust(REGEN);
                healed++;
                return HEAL;
            }
        } else if (food <= 0) {
            if (++timer >= TIMER) {
                timer = 0;
                if (health > floor) { starvedDamage++; return STARVE; }
            }
        } else timer = 0;
        return NOTHING;
    }

    boolean hungry() { return food <= LOW; }

    /** "food 14/20, saturation 2.5" */
    String line() {
        return "food " + food + "/" + MAX + ", saturation " + Math.round(saturation * 10) / 10.0;
    }
}
