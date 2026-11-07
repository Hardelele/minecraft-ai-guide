package hardelele.aiguide.client;

import com.google.gson.JsonParser;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

/** Checks of the memory of cases (CaseVec, CaseBook) with no game: run by tools/test_client.py. */
public class CaseTest {
    static int failed, passed;

    static void check(String what, boolean ok) {
        if (ok) passed++;
        else failed++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    static double at(double[] v, String name) { return v[CaseVec.index(name)]; }

    /** on its feet on stone, full health, walking somewhere */
    static CaseVec.Sit walking() {
        CaseVec.Sit s = new CaseVec.Sit();
        s.feet = new int[] { 10, 64, 4 };
        s.under = "minecraft:stone";
        s.underSolid = true;
        s.task = "GoTo";
        s.phase = "NAV";
        s.target = true;
        s.dx = 5; s.dy = 0; s.dz = 2;
        s.blocks = 12;
        s.light = 15;
        return s;
    }

    /** stuck against a wall, the target so many blocks up */
    static CaseVec.Sit stuck(double up) {
        CaseVec.Sit s = walking();
        s.collided = true;
        s.still = 240;
        s.dx = 1; s.dy = up; s.dz = 0;
        return s;
    }

    static CaseBook.Case kase(String reflex, CaseVec.Sit s, String reaction, String result) {
        CaseBook.Case c = new CaseBook.Case();
        c.agent = "Агент 1";
        c.reflex = reflex;
        c.sit = s;
        c.reaction = reaction;
        c.result = result;
        c.ticks = 12;
        return c;
    }

    /** a fresh empty folder under the test's own (build/test-tmp, tools/test_client.py says where) */
    static File folder(String name) throws java.io.IOException {
        String base = System.getProperty("aiguide.test.dir");
        if (base == null) return Files.createTempDirectory(name).toFile();
        File d = new File(base, name);
        if (d.isDirectory()) for (File f : d.listFiles()) f.delete();
        d.mkdirs();
        return d;
    }

    static CaseBook memory() {
        CaseBook b = new CaseBook(null);
        b.load();
        return b;
    }

    public static void main(String[] args) throws Exception {
        // ---- the vector
        double[] w = CaseVec.vector(walking());
        check("the vector has a component for each name", w.length == CaseVec.DIM && CaseVec.WEIGHTS.length == CaseVec.DIM);
        boolean in = true;
        for (double x : w) in &= x >= 0 && x <= 1;
        CaseVec.Sit wild = walking();
        wild.health = 50; wild.fall = 100; wild.motionY = -9; wild.still = 1 << 20; wild.dy = -40; wild.dx = 300; wild.blocks = 999; wild.light = 0; wild.mobs = 40;
        wild.threat = "Creeper"; wild.threatDist = 0.5; wild.hurt = true; wild.hurtBy = "Zombie";
        for (double x : CaseVec.vector(wild)) in &= x >= 0 && x <= 1;
        check("each component is in 0..1, even for wild numbers", in);
        check("the same situation, the same vector", java.util.Arrays.equals(w, CaseVec.vector(walking())));
        CaseVec.Sit half = walking();
        half.health = 10;
        check("health 10 of 20 is 0.5", Math.abs(at(CaseVec.vector(half), "health") - 0.5) < 1e-9);
        double[] up3 = CaseVec.vector(stuck(3));
        check("a target 3 up: target_up2, target_dy above the middle", at(up3, "target_up2") == 1 && at(up3, "target_down2") == 0 && Math.abs(at(up3, "target_dy") - 0.875) < 1e-9);
        CaseVec.Sit none = walking();
        none.target = false;
        check("no target: target_dy in the middle, target 0", at(CaseVec.vector(none), "target_dy") == 0.5 && at(CaseVec.vector(none), "target") == 0);
        CaseVec.Sit fire = walking();
        fire.hurt = true; fire.hurtBy = "inFire"; fire.burning = true;
        CaseVec.Sit mob = walking();
        mob.hurt = true; mob.hurtBy = "Zombie";
        check("hurt by fire and by a mob are told apart", at(CaseVec.vector(fire), "hurt_fire") == 1 && at(CaseVec.vector(fire), "hurt_mob") == 0
                && at(CaseVec.vector(mob), "hurt_mob") == 1 && at(CaseVec.vector(mob), "hurt_fire") == 0);
        CaseVec.Sit dig = walking();
        dig.phase = "DIG";
        check("a GoTo digging its way is not a GoTo walking", at(CaseVec.vector(dig), "task_dig_way") == 1 && at(CaseVec.vector(dig), "task_walk") == 0);
        CaseVec.Sit creeper = walking();
        creeper.threat = "Creeper"; creeper.threatDist = 4;
        check("a creeper 4 m away: threat, near 0.75, creeper", at(CaseVec.vector(creeper), "threat") == 1 && Math.abs(at(CaseVec.vector(creeper), "threat_near") - 0.75) < 1e-9 && at(CaseVec.vector(creeper), "threat_creeper") == 1);
        long t0 = System.nanoTime();
        double sink = 0;
        CaseVec.Sit s0 = stuck(2);
        for (int i = 0; i < 200000; i++) { s0.still = i & 1023; sink += CaseVec.vector(s0)[3]; }
        double us = (System.nanoTime() - t0) / 1000.0 / 200000;
        System.out.println(String.format(java.util.Locale.ROOT, "     a vector takes %.2f us", us));
        check("a vector takes microseconds (under 20 us)", us < 20 && sink >= 0);
        String text = CaseVec.text(stuck(3));
        check("the words: '" + text + "'", text.contains("target 3 up") && text.contains("on GoTo NAV") && text.contains("against a wall") && text.contains("stuck 12 s"));

        // ---- the distance and the neighbours
        check("a situation is fully like itself", Math.abs(CaseBook.similarity(w, w) - 1) < 1e-12);
        CaseVec.Sit lava = walking();
        lava.inLava = true;
        CaseVec.Sit torch = walking();
        torch.torches = 5;
        double sl = CaseBook.similarity(w, CaseVec.vector(lava)), st = CaseBook.similarity(w, CaseVec.vector(torch));
        check("lava moves a case further than a torch in the bag (" + CaseVec.f1(sl * 100) + " < " + CaseVec.f1(st * 100) + ")", sl < st && st < 1);
        CaseBook b = memory();
        b.add(kase("stuck-jump", stuck(3), "pillar", "OK"));
        b.add(kase("stuck-jump", stuck(1), "jump", "OK"));
        b.add(kase("fall", stuck(3), "block under itself", "OK"));
        CaseVec.Sit far = walking();
        far.inLava = true; far.burning = true; far.health = 4; far.onGround = false; far.underSolid = false;
        b.add(kase("stuck-jump", far, "pillar", "OK"));
        List<CaseBook.Near> ns = b.nearest(CaseVec.vector(stuck(3)), "stuck-jump", 5);
        check("the nearest of the reflex first, the other reflex's and the unlike left out", ns.size() == 2 && ns.get(0).c.reaction.equals("pillar") && ns.get(0).sim > ns.get(1).sim);
        check("any reflex: three near ones", b.nearest(CaseVec.vector(stuck(3)), null, 5).size() == 3);
        CaseBook top = memory();
        for (int i = 0; i < 40; i++) { CaseVec.Sit s = stuck(2); s.still = i * 15; top.add(kase("stuck-jump", s, "r" + i, "OK")); }
        List<CaseBook.Near> k5 = top.nearest(CaseVec.vector(stuck(2)), "stuck-jump", 5);
        boolean sorted = k5.size() == 5;
        for (int i = 1; i < k5.size(); i++) sorted &= k5.get(i - 1).sim >= k5.get(i).sim;
        check("k nearest: k of them, nearest first, the very one on top", sorted && k5.get(0).c.reaction.equals("r16"));

        // ---- the advice
        CaseBook m = memory();
        for (int i = 0; i < 5; i++) m.add(kase("stuck-jump", stuck(3), "jump", "NO_PATH"));
        for (int i = 0; i < 4; i++) m.add(kase("stuck-jump", stuck(3), "pillar", "OK"));
        for (int i = 0; i < 4; i++) m.add(kase("stuck-jump", stuck(1), "jump", "OK"));
        CaseBook.Advice high = m.suggest(CaseVec.vector(stuck(3)), "stuck-jump");
        check("target 3 up: the pillar, which helped there (" + high + ")", high.known() && high.reaction.equals("pillar") && high.confidence >= m.threshold);
        CaseBook.Advice low = m.suggest(CaseVec.vector(stuck(1)), "stuck-jump");
        check("target 1 up: the jump (" + low + ")", low.known() && low.reaction.equals("jump"));
        check("the options limit it: only jump or side step there -> don't know", !m.suggest(CaseVec.vector(stuck(3)), "stuck-jump", "jump", "side step").known());
        check("another reflex: don't know", !m.suggest(CaseVec.vector(stuck(3)), "creeper").known());
        CaseBook one = memory();
        one.add(kase("fall", stuck(3), "block under itself", "OK"));
        check("one case is not enough: don't know", !one.suggest(CaseVec.vector(stuck(3)), "fall").known());
        one.add(kase("fall", stuck(3), "block under itself", "OK"));
        one.add(kase("fall", stuck(3), "block under itself", "OK"));
        CaseBook.Advice three = one.suggest(CaseVec.vector(stuck(3)), "fall");
        check("three of three: 0.75 (" + three + ")", three.known() && Math.abs(three.confidence - 0.75) < 1e-9 && three.support == 3);
        CaseBook.Case up = kase("fall", stuck(3), "block under itself", "OK");
        up.escalated = "task";
        one.add(up);
        one.add(kase("fall", stuck(3), "block under itself", "BLOCKED"));
        check("an escalated case and a failure pull it under the threshold", !one.suggest(CaseVec.vector(stuck(3)), "fall").known());
        CaseBook unread = new CaseBook(null);
        unread.add(kase("fall", stuck(3), "block under itself", "OK"));
        unread.add(kase("fall", stuck(3), "block under itself", "OK"));
        unread.add(kase("fall", stuck(3), "block under itself", "OK"));
        check("while the journal is read: don't know", !unread.suggest(CaseVec.vector(stuck(3)), "fall").known());
        long t1 = System.nanoTime();
        CaseBook big = memory();
        for (int i = 0; i < big.cap; i++) { CaseVec.Sit s = stuck(i % 5); s.still = i % 600; big.add(kase("stuck-jump", s, i % 3 == 0 ? "pillar" : "jump", i % 4 == 0 ? "NO_PATH" : "OK")); }
        t1 = System.nanoTime();
        for (int i = 0; i < 20; i++) big.suggest(CaseVec.vector(stuck(3)), "stuck-jump");
        double ms = (System.nanoTime() - t1) / 1e6 / 20;
        System.out.println(String.format(java.util.Locale.ROOT, "     an advice over %d cases takes %.2f ms", big.size(), ms));
        check("an advice over a full memory takes under 5 ms", ms < 5);
        check("the memory keeps at most cap cases", big.size() == big.cap);

        // ---- the journal
        File dir = folder("crew-cases");
        File f = new File(dir, CaseBook.NAME);
        CaseBook j = new CaseBook(f);
        j.load();
        for (int i = 0; i < 30; i++) j.add(kase("stuck-jump", stuck(3), i % 2 == 0 ? "pillar" : "jump", i % 2 == 0 ? "OK" : "NO_PATH"));
        check("the writer is through with the lines", j.flush(5000));
        List<String> lines = Files.readAllLines(f.toPath(), java.nio.charset.StandardCharsets.UTF_8);
        check("a line a case: " + lines.size(), lines.size() == 30);
        CaseBook.Case back = CaseBook.Case.fromJson(new JsonParser().parse(lines.get(0)).getAsJsonObject());
        check("a line read back is the same case", back.reflex.equals("stuck-jump") && back.reaction.equals("pillar") && back.agent.equals("Агент 1")
                && java.util.Arrays.equals(back.vec(), CaseVec.vector(stuck(3))) && back.escalated == null && back.ticks == 12);
        Files.write(f.toPath(), "not json\n{\"broken\":\n".getBytes("UTF-8"), java.nio.file.StandardOpenOption.APPEND);
        CaseBook again = new CaseBook(f);
        int n = again.load();
        check("the journal read back at the world's start: 30 cases, 2 bad lines skipped", n == 30 && again.size() == 30 && again.badLines == 2);
        CaseBook.Advice ad = again.suggest(CaseVec.vector(stuck(3)), "stuck-jump");
        check("and it advises as before (" + ad + ")", ad.known() && ad.reaction.equals("pillar"));
        String contract = "{\"t\":123456,\"agent\":\"Агент 2\",\"reflex\":\"fall\",\"priority\":90,\"situation\":{\"health\":14.0,\"max\":20.0,\"hurt\":false,\"hurtBy\":\"\",\"fall\":3.2,"
                + "\"motionY\":-0.62,\"onGround\":false,\"inWater\":false,\"inLava\":false,\"burning\":false,\"underWater\":false,\"collided\":false,\"feet\":[10,63,4],"
                + "\"under\":\"minecraft:air\",\"threat\":\"Creeper\",\"threatDist\":3.1,\"task\":\"Scaffold\",\"card\":\"build#41\",\"origin\":\"AUTO_DEFAULT\"},"
                + "\"reaction\":\"block under itself\",\"actions\":[\"place cobblestone 10 62 4\"],\"result\":\"OK\",\"ticks\":7,\"after\":{\"health\":14.0,\"fall\":0.0,\"onGround\":true},\"escalated\":null}";
        CaseBook.Case cc = CaseBook.Case.fromJson(new JsonParser().parse(contract).getAsJsonObject());
        double[] cv = cc.vec();
        check("the contract's example line reads: air under, a creeper 3.1 m, on a build task", cc.ok() && at(cv, "under_air") == 1 && at(cv, "threat_creeper") == 1
                && at(cv, "task_build") == 1 && cc.actions.size() == 1 && cc.priority == 90);

        File rd = folder("crew-cases-rot");
        CaseBook r = new CaseBook(new File(rd, CaseBook.NAME));
        r.maxBytes = 4000;
        r.keep = 2;
        r.load();
        int total = 0;
        for (int round = 0; round < 12; round++) {
            for (int i = 0; i < 3; i++) { r.add(kase("fall", stuck(round), "block under itself", "OK")); total++; }
            r.flush(5000);
        }
        File r1 = r.rotated(1), r2 = r.rotated(2), r3 = r.rotated(3);
        check("the journal goes round: " + r1.getName() + " and " + r2.getName() + " kept, no " + r3.getName(), r1.isFile() && r2.isFile() && !r3.isFile());
        check("no file much past its size", r.file.length() <= r.maxBytes + 2000 && r1.length() <= r.maxBytes + 2000);
        CaseBook rr = new CaseBook(new File(rd, CaseBook.NAME));
        rr.keep = 2;
        int got = rr.load();
        int onDisk = 0;
        for (File x : new File[] { r2, r1, r.file }) onDisk += Files.readAllLines(x.toPath(), java.nio.charset.StandardCharsets.UTF_8).size();
        check("read back from all the kept files: " + got + " of " + total + " written (the oldest went)", got == onDisk && got < total && got > 0);
        List<CaseBook.Near> last = rr.nearest(CaseVec.vector(stuck(11)), "fall", 1);
        check("the newest cases are there", !last.isEmpty() && last.get(0).sim > 0.999);
        r.close();
        j.close();
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
