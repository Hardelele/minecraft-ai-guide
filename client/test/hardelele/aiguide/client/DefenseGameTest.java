package hardelele.aiguide.client;

/** The defence's pieces that live with the game's classes but need no world: the waves mode's run read as the defence's wave. */
public final class DefenseGameTest {
    static void ok(boolean b, String what) {
        if (!b) throw new AssertionError(what);
    }

    public static void main(String[] args) {
        WaveCycle.View v = new WaveCycle.View();
        v.phase = WaveCycle.Phase.WAVE;
        v.wave = 3;
        v.alive = 5;
        v.queued = 2;
        v.secondsToNext = 0;
        v.cleared = 2;
        Defense.Wave d = DefenseWorld.fromWaves(v, null);
        ok(d.known && d.on && d.number == 3 && d.enemies == 7 && d.nextIn == -1 && d.cleared == 2 && d.phase.equals("wave"), "a wave on: " + d.line(0, 0));
        v.paused = true;
        ok(!DefenseWorld.fromWaves(v, null).on && DefenseWorld.fromWaves(v, null).line(0, 0).startsWith("paused"), "paused: no wave on");
        v.paused = false;
        v.phase = WaveCycle.Phase.BREAK;
        v.alive = 0; v.queued = 0;
        v.secondsToNext = 18;
        d = DefenseWorld.fromWaves(v, null);
        ok(!d.on && d.nextIn == 18 && d.line(0, 0).equals("pause after wave 3, 2 cleared, next wave in 18 s"), "a break: " + d.line(0, 0));
        v.phase = WaveCycle.Phase.PREP;
        v.wave = 0;
        v.cleared = 0;
        v.secondsToNext = 30;
        ok(DefenseWorld.fromWaves(v, null).line(0, 0).equals("preparation, next wave in 30 s"), "the preparation: " + DefenseWorld.fromWaves(v, null).line(0, 0));
        ok(DefenseSkills.isDefense("hold_line") && !DefenseSkills.isDefense("craft"), "the defence's tools");
        System.out.println("DefenseGameTest PASS");
    }
}
