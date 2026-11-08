package hardelele.aiguide.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * The waves mode without the game: the curve of the waves (WavePlan: the first ones for 4 agents with swords, the
 * kinds unlocked in their order, the boss waves, the levels), the ring's cells on a model of heights (WaveRing: never in
 * water, on a cliff or too near), the run's clock (WaveCycle: preparation, wave, break, pause, timeout, lost), the
 * report's format (WaveReport) and the commands (WaveCommand). Prints the curve's table.
 */
public final class WavesTest {
    static int bad;

    static void check(boolean ok, String what) {
        if (!ok) { bad++; System.out.println("FAIL " + what); }
    }

    static int count(int[] c, WavePlan.Kind k) { return c[k.ordinal()]; }

    static void curve() {
        System.out.println("  wave  budget  zombie skeleton spider creeper armored witch  total  hp   (normal)");
        int lastBudget = 0;
        for (int n = 1; n <= 20; n++) {
            int[] c = WavePlan.counts(n, 1.0);
            int b = WavePlan.budget(n, 1.0);
            System.out.println(String.format("  %4d  %6d  %6d %8d %6d %7d %7d %5d  %5d  %.2f%s", n, b, c[0], c[1], c[2], c[3], c[4], c[5], WavePlan.total(c), WavePlan.health(n),
                    WavePlan.boss(n) ? "  boss" : ""));
            check(b > lastBudget, "the budget grows: wave " + n);
            lastBudget = b;
            if (n < 3) check(WavePlan.total(c) == count(c, WavePlan.Kind.ZOMBIE), "waves 1-2 are zombies only: " + n);
            if (n < 4) check(count(c, WavePlan.Kind.SPIDER) == 0, "no spiders before the 4th: " + n);
            if (n < 6) check(count(c, WavePlan.Kind.CREEPER) == 0, "no creepers before the 6th: " + n);
            if (n < 10) check(count(c, WavePlan.Kind.WITCH) == 0, "no witch before the 10th: " + n);
            if (WavePlan.boss(n)) check(count(c, WavePlan.Kind.ARMORED_ZOMBIE) >= 1, "a boss wave has armoured zombies: " + n);
            else check(count(c, WavePlan.Kind.ARMORED_ZOMBIE) == 0 && count(c, WavePlan.Kind.WITCH) == 0, "no boss part in an ordinary wave: " + n);
            check(count(c, WavePlan.Kind.ZOMBIE) >= 1, "every wave has zombies: " + n);
        }
        // the first three for 4 agents with wooden or stone swords: a handful each, at most one skeleton
        check(WavePlan.total(WavePlan.counts(1, 1.0)) <= 4, "wave 1 is small");
        check(WavePlan.total(WavePlan.counts(2, 1.0)) <= 6, "wave 2 is small");
        check(WavePlan.total(WavePlan.counts(3, 1.0)) <= 8 && count(WavePlan.counts(3, 1.0), WavePlan.Kind.SKELETON) <= 1, "wave 3 is small");
        check(WavePlan.counts(3, 1.0)[1] >= 1, "skeletons from the 3rd");
        check(WavePlan.counts(10, 1.0)[5] == 1 && WavePlan.counts(20, 1.0)[5] == 2, "a witch on the 10th, two on the 20th");
        check(WavePlan.total(WavePlan.counts(15, 1.0)) >= 3 * WavePlan.total(WavePlan.counts(3, 1.0)), "chaos later: wave 15 has 3 times wave 3's mobs");
        for (int n = 1; n <= 12; n++)
            check(WavePlan.budget(n, 0.7) <= WavePlan.budget(n, 1.0) && WavePlan.budget(n, 1.0) < WavePlan.budget(n, 1.4) && WavePlan.budget(n, 1.4) < WavePlan.budget(n, 2.0),
                    "the levels are in order at wave " + n);
        check(WavePlan.health(10) == 1.0 && WavePlan.health(20) > 1.5, "tougher only after the 10th");
        // the levels' words
        check("normal".equals(WavePlan.level(null)) && "normal".equals(WavePlan.level("")), "no word: normal");
        check("easy".equals(WavePlan.level("Легко")) && "hard".equals(WavePlan.level("сложно")) && "chaos".equals(WavePlan.level("ХАОС")) && "chaos".equals(WavePlan.level("chaos")),
                "the levels' words");
        check("hard".equals(WavePlan.level("слож")) && "normal".equals(WavePlan.level("норм")), "a level by its first letters");
        check(WavePlan.level("ад") == null && WavePlan.level("банан") == null, "an unknown level");
        check(WavePlan.mult("chaos") == 2.0 && WavePlan.mult("nonsense") == 1.0, "the multipliers");
        // the order: all of them, the same for the same seed, the boss part in the middle
        int[] c10 = WavePlan.counts(10, 1.0);
        List<WavePlan.Kind> q1 = WavePlan.queue(c10, new Random(7)), q2 = WavePlan.queue(c10, new Random(7));
        check(q1.size() == WavePlan.total(c10) && q1.equals(q2), "the queue: all the wave's mobs, the same for a seed");
        int firstBoss = -1;
        for (int i = 0; i < q1.size(); i++) if (q1.get(i) == WavePlan.Kind.ARMORED_ZOMBIE || q1.get(i) == WavePlan.Kind.WITCH) { firstBoss = i; break; }
        check(firstBoss >= q1.size() / 3, "the boss part comes when the wave is going: at " + firstBoss + " of " + q1.size());
    }

    /** flat ground at 64; a lake (no cell) east of x 110; a cliff at 90 north of z -25; a wall ring (no cell) at distance 24-25 */
    static final WaveRing.Ground MODEL = new WaveRing.Ground() {
        public int stand(int x, int z) {
            double d = Math.hypot(x, z);
            if (x > 110) return WaveRing.NONE;
            if (x >= 22 && x <= 34 && Math.abs(z) <= 6) return WaveRing.NONE;   // a lake east of the base
            if (z < -25) return 90;                                             // a cliff to the north
            if (d >= 24 && d < 25) return WaveRing.NONE;                        // a wall
            return 64;
        }
    };

    static void ring() {
        Random r = new Random(3);
        List<int[]> taken = new ArrayList<int[]>();
        int found = 0, nearFront = 0;
        for (int i = 0; i < 600; i++) {
            double front = (i % 8) * Math.PI / 4;
            int[] p = WaveRing.pick(MODEL, 0, 64, 0, 20, 32, 12, front, r, taken);
            if (p == null) continue;
            found++;
            double d = Math.hypot(p[0], p[2]);
            check(d >= 19.5 && d <= 32.5, "on the ring: " + Arrays.toString(p) + " at " + d);
            check(p[1] == 64, "not on the cliff: " + Arrays.toString(p));
            check(!(p[0] >= 22 && p[0] <= 34 && Math.abs(p[2]) <= 6) && !(d >= 24 && d < 25), "not in the lake or the wall: " + Arrays.toString(p));
            for (int[] o : taken) check(!(Math.abs(o[0] - p[0]) < 2 && Math.abs(o[2] - p[2]) < 2), "two mobs on one cell: " + Arrays.toString(p));
            double da = Math.abs(WaveRing.norm(Math.atan2(p[2], p[0]) - front + Math.PI) - Math.PI);
            if (da <= 0.6) nearFront++;
            taken.add(p);
            if (taken.size() > 12) taken.remove(0);
        }
        check(found > 500, "cells are found on an open ring: " + found + " of 600");
        check(nearFront > found * 0.7, "the mobs come from their fronts: " + nearFront + " of " + found);
        // the north is a cliff: a front to the north still finds cells (the other half of the tries go round the ring)
        int north = 0;
        for (int i = 0; i < 50; i++) if (WaveRing.pick(MODEL, 0, 64, 0, 26, 32, 12, -Math.PI / 2, r, null) != null) north++;
        check(north > 40, "a front over a cliff finds cells elsewhere: " + north);
        WaveRing.Ground sea = new WaveRing.Ground() { public int stand(int x, int z) { return WaveRing.NONE; } };
        check(WaveRing.pick(sea, 0, 64, 0, 20, 32, 12, 0, r, null) == null, "no cell in the sea");
        WaveRing.Ground deep = new WaveRing.Ground() { public int stand(int x, int z) { return 30; } };   // a base on a mountain over a valley
        check(WaveRing.pick(deep, 0, 64, 0, 20, 32, 12, 0, r, null) == null, "no cell 34 below the base");
        // the fronts: 1 for waves 1-3, 2 for 4-6, 3 for 7-9, 4 from the 10th and on boss waves; the sides' names
        check(WaveRing.fronts(1, r).length == 1 && WaveRing.fronts(3, r).length == 1 && WaveRing.fronts(4, r).length == 2 && WaveRing.fronts(7, r).length == 3
                && WaveRing.fronts(5, r).length == 4 && WaveRing.fronts(11, r).length == 4, "the fronts' number");
        check(WaveRing.side(1, 0) == 0 && WaveRing.side(0, 1) == 2 && WaveRing.side(-1, 0) == 4 && WaveRing.side(0, -1) == 6 && WaveRing.side(1, -1) == 7, "the sides: east south west north");
    }

    static WaveCycle cycle(int prep, int brk, int max, int losses) {
        WaveCycle.Config cfg = new WaveCycle.Config();
        cfg.prepTicks = prep;
        cfg.breakTicks = brk;
        cfg.waveMaxTicks = max;
        cfg.lossesPerWave = losses;
        return new WaveCycle(cfg);
    }

    static void states() {
        WaveCycle c = cycle(100, 60, 2000, 3);
        check(c.view(0, 0).phase == WaveCycle.Phase.OFF && c.secondsToNext() == -1 && c.tick(0, 0, 0) == WaveCycle.Event.NONE, "off: nothing happens");
        c.start("hard", 4, 1000);
        check(c.phase == WaveCycle.Phase.PREP && c.secondsToNext() == 5 && c.mult == 1.4 && c.best == 4, "the preparation: 5 s");
        WaveCycle.Event e = WaveCycle.Event.NONE;
        int t = 0;
        while (e == WaveCycle.Event.NONE && t < 1000) { e = c.tick(0, 0, 2000); t++; }
        check(e == WaveCycle.Event.WAVE_START && t == 100 && c.wave == 1 && c.phase == WaveCycle.Phase.WAVE && c.secondsToNext() == 0, "the first wave after the preparation: " + t);
        c.planned(5);
        for (int i = 0; i < 5; i++) c.spawned();
        check(c.tick(5, 0, 0) == WaveCycle.Event.NONE, "a wave with mobs goes on");
        c.kill("Агент 2"); c.kill("Агент 2"); c.kill("Агент 3"); c.kill("world:inFire");
        c.loss("Агент 4");
        for (int i = 0; i < 30 && e != WaveCycle.Event.WAVE_END; i++) e = c.tick(0, 0, 0);
        check(e == WaveCycle.Event.WAVE_END && c.cleared == 1 && c.phase == WaveCycle.Phase.BREAK && c.secondsToNext() == 3, "the wave cleared: a break of 3 s");
        WaveCycle.View v = c.view(0, 0);
        check(v.kills == 4 && v.losses == 1 && v.killers.get(0)[0].equals("Агент 2") && v.killers.get(0)[1].equals("2"), "the score: kills by who, most first");
        check(c.current().killed == 4 && c.current().losses == 1 && c.current().spawned == 5 && c.current().planned == 5, "the wave's own numbers");
        // a pause holds the clock
        c.pause(true);
        int before = c.timer;
        for (int i = 0; i < 200; i++) check(c.tick(0, 0, 0) == WaveCycle.Event.NONE, "nothing while paused");
        check(c.timer == before && c.view(0, 0).paused, "the clock stood while paused");
        check(!c.pause(true) && c.pause(false), "a pause on twice is one");
        e = WaveCycle.Event.NONE;
        for (int i = 0; i < 100 && e == WaveCycle.Event.NONE; i++) e = c.tick(0, 0, 3000);
        check(e == WaveCycle.Event.WAVE_START && c.wave == 2 && c.waveStartedMs == 3000, "the second wave after the break");
        // out of time: the wave ends with mobs left
        e = WaveCycle.Event.NONE;
        for (int i = 0; i < 3000 && e == WaveCycle.Event.NONE; i++) e = c.tick(3, 2, 0);
        check(e == WaveCycle.Event.WAVE_TIMEOUT && c.current().timeout && c.cleared == 2, "a wave out of time ends (its mobs taken away)");
        for (int i = 0; i < 100 && e != WaveCycle.Event.WAVE_START; i++) e = c.tick(0, 0, 0);
        // too many lost in one wave: the run is lost
        c.loss("Агент 2"); c.loss("Агент 3"); c.loss("Player");
        e = c.tick(4, 0, 9000);
        check(e == WaveCycle.Event.LOST && c.phase == WaveCycle.Phase.OVER && "lost".equals(c.reason) && c.endedMs == 9000 && c.cleared == 2 && c.wave == 3, "a wave that took 3 of the crew: lost");
        check(c.tick(0, 0, 0) == WaveCycle.Event.NONE && !c.view(0, 0).active() && c.secondsToNext() == -1, "over: nothing happens");
        c.kill("x");
        check(c.kills == 4, "no kills counted after the end");
        check(c.best == 4, "the best stays the best before (2 < 4)");
        // stopped by the player
        WaveCycle s = cycle(10, 10, 100, 0);
        s.start("normal", 0, 0);
        s.over("stop", 5);
        check(s.phase == WaveCycle.Phase.OVER && "stop".equals(s.reason), "stopped");
        // losses in a break do not count against a wave; lives: the whole run's
        WaveCycle l = cycle(1, 5, 100, 0);
        l.cfg.lives = 2;
        l.start("easy", 0, 0);
        l.tick(0, 0, 0);
        l.loss("a");
        l.tick(1, 0, 0);
        l.loss("b");
        check(l.tick(1, 0, 0) == WaveCycle.Event.LOST, "the run's lives used up: lost");
    }

    static void report() {
        WaveCycle c = cycle(1, 1, 100, 0);
        c.start("chaos", 2, 0);
        c.tick(0, 0, 0);
        c.planned(3); c.spawned(); c.spawned(); c.spawned();
        c.kill("Агент 2"); c.kill("Player"); c.gone();
        c.loss("Агент 3");
        c.over("stop", 1);
        JsonObject o = WaveReport.run(c, "New World", "command", true, 3, 1, Arrays.asList("x failed"));
        String line = WaveReport.line(o);
        check(!line.contains("\n"), "one line a run");
        JsonObject b = new JsonParser().parse(line).getAsJsonObject();
        for (String k : new String[] { "schema", "world", "source", "level", "mult", "started", "ended_by", "seconds", "wave_reached", "waves_cleared", "best_before", "kills_total",
                "kills", "losses_total", "losses", "spawned", "removed_at_end", "peaceful_world", "burn", "waves", "errors" })
            check(b.has(k), "the report has " + k);
        check(b.get("schema").getAsInt() == WaveReport.SCHEMA && b.get("level").getAsString().equals("chaos") && b.get("ended_by").getAsString().equals("stop"), "the report's values");
        check(b.get("wave_reached").getAsInt() == 1 && b.get("waves_cleared").getAsInt() == 0 && b.get("kills_total").getAsInt() == 2, "how far, how many");
        check(b.getAsJsonObject("kills").get("Агент 2").getAsInt() == 1 && b.getAsJsonObject("losses").get("Агент 3").getAsInt() == 1, "kills and losses by who");
        JsonObject w1 = b.getAsJsonArray("waves").get(0).getAsJsonObject();
        for (String k : new String[] { "n", "planned", "spawned", "killed", "gone", "losses", "seconds", "timeout" }) check(w1.has(k), "a wave's record has " + k);
        check(w1.get("killed").getAsInt() == 2 && w1.get("gone").getAsInt() == 1, "the wave's record");
        check(b.getAsJsonArray("errors").size() == 1 && b.get("peaceful_world").getAsBoolean(), "errors and the world's peace");
        List<String> lines = Arrays.asList(line.replace("\"waves_cleared\":0", "\"waves_cleared\":7"), "{broken", "", line);
        check(WaveReport.best(lines) == 7 && WaveReport.best(null) == 0, "the best of the runs before (a broken line skipped)");
    }

    static void is(String line, int kind, String level) {
        WaveCommand c = WaveCommand.parse(line);
        check(c != null && c.kind == kind && (level == null ? c.level == null : level.equals(c.level)), line + " -> " + (c == null ? "null" : c.kind + " " + c.level));
    }

    static void commands() {
        is("/волны старт", WaveCommand.START, "normal");
        is("  /Волны   старт   хаос ", WaveCommand.START, "chaos");
        is("/волны старт сложно", WaveCommand.START, "hard");
        is("/waves start easy", WaveCommand.START, "easy");
        is("/waves start", WaveCommand.START, "normal");
        is("/волны стоп", WaveCommand.STOP, null);
        is("/waves stop", WaveCommand.STOP, null);
        is("/волны пауза", WaveCommand.PAUSE, null);
        is("/waves pause", WaveCommand.PAUSE, null);
        is("/волны", WaveCommand.USAGE, null);
        is("/волны старт ад", WaveCommand.USAGE, null);
        is("/волны стоп сейчас", WaveCommand.USAGE, null);
        is("/waves dance", WaveCommand.USAGE, null);
        check(WaveCommand.parse("волны старт") == null && WaveCommand.parse("/волнызомби") == null && WaveCommand.parse("/агент 2") == null
                && WaveCommand.parse("") == null && WaveCommand.parse(null) == null, "not the waves' lines");
    }

    public static void main(String[] args) {
        curve();
        ring();
        states();
        report();
        commands();
        if (bad > 0) { System.out.println("WavesTest FAIL " + bad); System.exit(1); }
        System.out.println("WavesTest PASS");
    }
}
