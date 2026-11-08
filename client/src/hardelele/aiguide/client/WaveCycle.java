package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The waves mode's clock and score, with no game in it (Waves feeds it the world's numbers each tick): off; the
 * preparation (a countdown); a wave, until its mobs are all dead or gone (or its time is up); a break before the next
 * one, which is stronger; over when a wave took too many of the crew (or the run was stopped). A pause stops the
 * clock and the spawning. WavesTest runs it through its states.
 */
final class WaveCycle {
    enum Phase { OFF, PREP, WAVE, BREAK, OVER }

    enum Event { NONE, WAVE_START, WAVE_END, WAVE_TIMEOUT, LOST }

    /** the run's numbers (Waves reads them from -Daiguide.waves.*) */
    static final class Config {
        int prepTicks = 20 * 30, breakTicks = 20 * 20, waveMaxTicks = 20 * 240;
        int lossesPerWave = 6;    // the crew's deaths in one wave that end the run (0: never)
        int lives = 0;            // the crew's deaths in the whole run that end it (0: never)
        int maxAlive = 24, rMin = 20, rMax = 32, maxRise = 12, spawnEvery = 10;
        boolean burn = true;      // the wave's zombies and skeletons burn in the sun as the game's own do
    }

    /** one wave as it went */
    static final class Wave {
        final int n;
        int planned, spawned, killed, gone, losses, ticks;
        boolean timeout;

        Wave(int n) { this.n = n; }
    }

    /** what is shown and what the crew's minds ask (Waves.state()): a copy, safe on any thread */
    static final class View {
        static final View OFF = new View();

        Phase phase = Phase.OFF;
        boolean paused;
        String level = "normal";
        int wave, alive, queued, secondsToNext = -1, kills, losses, cleared, best, seconds;
        long startedMs, endedMs, waveStartedMs;
        String reason;
        /** who killed how many of the wave's mobs: "Агент 2" -> 5, the order most first */
        List<String[]> killers = Collections.emptyList();

        /** a run is on (being prepared, a wave, a break; paused or not) */
        boolean active() { return phase == Phase.PREP || phase == Phase.WAVE || phase == Phase.BREAK; }

        /** the enemies of the wave: alive and still to come */
        int enemies() { return alive + queued; }
    }

    final Config cfg;
    Phase phase = Phase.OFF;
    boolean paused;
    String level = "normal", reason;
    double mult = 1;
    int wave, timer, waveTicks, runTicks, kills, losses, cleared, best;
    long startedMs, endedMs, waveStartedMs;
    final List<Wave> waves = new ArrayList<Wave>();
    final Map<String, Integer> killsBy = new TreeMap<String, Integer>(), lossesBy = new TreeMap<String, Integer>();

    WaveCycle(Config cfg) { this.cfg = cfg; }

    boolean active() { return phase == Phase.PREP || phase == Phase.WAVE || phase == Phase.BREAK; }

    Wave current() { return waves.isEmpty() ? null : waves.get(waves.size() - 1); }

    /** a new run: the preparation's countdown starts (best: the best of the runs before, in this world) */
    void start(String level, int best, long nowMs) {
        this.level = level;
        this.mult = WavePlan.mult(level);
        this.best = best;
        phase = Phase.PREP;
        paused = false;
        reason = null;
        wave = waveTicks = runTicks = kills = losses = cleared = 0;
        timer = cfg.prepTicks;
        startedMs = nowMs;
        endedMs = waveStartedMs = 0;
        waves.clear();
        killsBy.clear();
        lossesBy.clear();
    }

    /** one tick: the wave's living mobs and the ones still to come; what happened (the caller spawns, cleans, tells) */
    Event tick(int alive, int queued, long nowMs) {
        if (!active() || paused) return Event.NONE;
        runTicks++;
        if (phase == Phase.PREP || phase == Phase.BREAK) {
            if (--timer > 0) return Event.NONE;
            wave++;
            phase = Phase.WAVE;
            waveTicks = 0;
            waveStartedMs = nowMs;
            waves.add(new Wave(wave));
            return Event.WAVE_START;
        }
        waveTicks++;
        Wave w = current();
        w.ticks = waveTicks;
        if (cfg.lossesPerWave > 0 && w.losses >= cfg.lossesPerWave || cfg.lives > 0 && losses >= cfg.lives) {
            over("lost", nowMs);
            return Event.LOST;
        }
        boolean timeout = cfg.waveMaxTicks > 0 && waveTicks >= cfg.waveMaxTicks;
        if (alive == 0 && queued == 0 && waveTicks > 20 || timeout) {
            w.timeout = timeout && (alive > 0 || queued > 0);
            cleared = wave;
            best = Math.max(best, cleared);
            phase = Phase.BREAK;
            timer = cfg.breakTicks;
            return w.timeout ? Event.WAVE_TIMEOUT : Event.WAVE_END;
        }
        return Event.NONE;
    }

    void over(String why, long nowMs) {
        if (phase == Phase.OFF || phase == Phase.OVER) return;
        phase = Phase.OVER;
        paused = false;
        reason = why;
        endedMs = nowMs;
    }

    /** a pause on or off (only while a run is on) */
    boolean pause(boolean on) {
        if (!active() || paused == on) return false;
        paused = on;
        return true;
    }

    void planned(int n) { Wave w = current(); if (w != null) w.planned += n; }

    void spawned() { Wave w = current(); if (w != null) w.spawned++; }

    /** one of the wave's mobs gone without being killed (a creeper blew up, it was stuck, it fell out of the world) */
    void gone() { Wave w = current(); if (w != null) w.gone++; }

    void kill(String who) {
        if (!active()) return;
        kills++;
        Wave w = current();
        if (w != null) w.killed++;
        killsBy.put(who, (killsBy.containsKey(who) ? killsBy.get(who) : 0) + 1);
    }

    void loss(String who) {
        if (!active()) return;
        losses++;
        Wave w = current();
        if (w != null && phase == Phase.WAVE) w.losses++;
        lossesBy.put(who, (lossesBy.containsKey(who) ? lossesBy.get(who) : 0) + 1);
    }

    /** seconds to the next wave: in the preparation and the breaks, 0 in a wave, -1 when no run is on */
    int secondsToNext() {
        if (phase == Phase.PREP || phase == Phase.BREAK) return (timer + 19) / 20;
        return phase == Phase.WAVE ? 0 : -1;
    }

    View view(int alive, int queued) {
        View v = new View();
        v.phase = phase;
        v.paused = paused;
        v.level = level;
        v.wave = wave;
        v.alive = alive;
        v.queued = queued;
        v.secondsToNext = secondsToNext();
        v.kills = kills;
        v.losses = losses;
        v.cleared = cleared;
        v.best = best;
        v.seconds = runTicks / 20;
        v.startedMs = startedMs;
        v.endedMs = endedMs;
        v.waveStartedMs = waveStartedMs;
        v.reason = reason;
        List<String[]> k = new ArrayList<String[]>();
        for (Map.Entry<String, Integer> e : killsBy.entrySet()) k.add(new String[] { e.getKey(), String.valueOf(e.getValue()) });
        Collections.sort(k, (a, b) -> Integer.parseInt(b[1]) - Integer.parseInt(a[1]));
        v.killers = k;
        return v;
    }
}
