package hardelele.aiguide.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The epochs of learning without the game: the rules' ratings and the person's over them (EpochScore), the karma of
 * the crew's development, the reward; the lessons merged and pushed out, read from the model's answers, the stand-in
 * model; the grades in the memory of cases (CaseBook: the advice follows them, a person's outweighs, they come back
 * from the file); the thresholds' search (EpochKnobs); a whole run of epochs on a stand-in world and the stand-in model
 * (EpochRun: the tracks in turn, the files of each epoch, the report; learning grows, control stays). Its run is left in
 * build/test-tmp/epochs for tools/tests/test_epochs_tools.py (the chart).
 */
public final class EpochsTest {
    static int bad, passed;

    static void check(boolean ok, String what) {
        if (ok) passed++;
        else { bad++; System.out.println("FAIL " + what); }
    }

    static EpochScore.Episode step(String agent, String tool, String result, String phase) {
        EpochScore.Episode e = new EpochScore.Episode();
        e.kind = "step";
        e.agent = agent;
        e.what = tool;
        e.result = result;
        e.phase = phase;
        e.wave = 2;
        e.ms = 1000;
        e.key = "f:1000:" + agent + ":" + tool;
        return e;
    }

    static EpochScore.Episode death(String agent, String cause, boolean inside, String phase) {
        EpochScore.Episode e = new EpochScore.Episode();
        e.kind = "death";
        e.agent = agent;
        e.what = "death";
        e.cause = cause;
        e.inside = inside;
        e.phase = phase;
        return e;
    }

    static String code(EpochScore.Episode e) { EpochScore.rate(e); return e.autoWhy; }

    // ---- the rules -----------------------------------------------------------------------------------------

    static void rules() {
        check(code(death("Агент 2", "fight", false, "wave")).equals("out_died"), "out of the gate in a wave and dead: bad (out_died)");
        check(code(death("Агент 2", "fight", true, "wave")).equals("died"), "dead inside: died");
        check(code(death("Агент 2", "starve", true, "break")).equals("starved"), "dead of hunger: starved, whatever the place");
        check(code(death("Агент 2", "accident", false, "wave")).equals("accident"), "a fall or lava: accident, not out_died");
        EpochScore.Episode rep = step("Агент 2", "repair_wall", "OK", "break");
        check(code(rep).equals("repaired_before") && rep.auto == 1, "the wall's hole repaired before the wave: good");
        check(code(step("Агент 2", "repair_wall", "OK", "wave")).isEmpty(), "repaired during the wave: no rule");
        check(code(step("Агент 2", "build_wall", "OK", "prep")).equals("built_before"), "the wall built in the preparation: good");
        check(code(step("Агент 2", "make_weapon", "OK", "prep")).equals("armed"), "a weapon before the wave: good");
        check(code(step("Агент 2", "wait", "OK", "wave")).equals("idle_in_wave"), "waiting in a wave: bad");
        check(code(step("Агент 2", "wait", "OK", "break")).isEmpty(), "waiting in a break: no rule (the karma sees it)");
        check(code(step("Агент 2", "mine", "OK", "break")).equals("gathered"), "mined between the waves: good");
        check(code(step("Агент 2", "craft", "BAD_CALL", "wave")).equals("bad_call"), "no valid call: bad");
        check(code(step("Агент 2", "wait", "DIED", "wave")).equals("died_after"), "a step that ended by its death (as on the bench's epoch 0: wait -> DIED): bad");
        EpochScore.Episode ret = step("Агент 3", "retreat", "OK", "wave");
        ret.before = 6;
        check(code(ret).equals("retreat_timely"), "fell back at health 6 and lived: in time");
        ret.before = 18;
        check(code(ret).equals("retreat_early") && ret.auto == -1, "fell back at health 18 in a wave: for nothing");
        ret.before = 6;
        ret.diedSoon = true;
        check(code(ret).equals("died_after"), "fell back and died within 10 s: too late (died_after)");
        EpochScore.Episode hold = step("Агент 3", "hold_line", "OK", "wave");
        hold.fight = new Defense.Tally();
        hold.fight.kills = 2;
        check(code(hold).equals("held_killed"), "held the post and killed, no death: good");
        hold.fight.kills = 0;
        hold.fight.taken = 12;
        check(code(hold).equals("hurt_no_kill"), "lost 12 at the post, killed nothing: bad");
        EpochScore.Episode low = step("Агент 3", "hold_line", "LOW_HEALTH", "wave");
        low.before = 9;
        check(code(low).equals("retreat_timely"), "the fight's own fall back (LOW_HEALTH) from a low health: in time");
        EpochScore.Episode cs = new EpochScore.Episode();
        cs.kind = "case";
        cs.result = "OK";
        check(code(cs).equals("reflex_ok"), "a reflex that helped: good");
        cs.result = "PUSHED";
        check(code(cs).isEmpty(), "a reflex pushed by a higher one: neither");
        for (String[] r : EpochScore.RULES) check(EpochScore.ruleRating(r[0]) == Integer.parseInt(r[1]) && !r[2].isEmpty(), "a rule has a rating and words: " + r[0]);
    }

    // ---- the person's ratings ------------------------------------------------------------------------------

    static JsonObject label(String type, long tOrMs, String agent, String what, int rating, String reason) {
        JsonObject t = new JsonObject();
        t.addProperty("type", type);
        if (type.equals("case")) { t.addProperty("t", tOrMs); t.addProperty("reflex", what); }
        else { t.addProperty("ms", tOrMs); t.addProperty("card", what); }
        t.addProperty("agent", agent);
        JsonObject o = Labels.head("action", 0, new JsonObject());
        o.add("target", t);
        o.addProperty("rating", rating);
        o.addProperty("reason", reason);
        return o;
    }

    static void labels() {
        List<EpochScore.Episode> eps = new ArrayList<EpochScore.Episode>();
        EpochScore.Episode w = step("Агент 2", "wait", "OK", "wave");
        w.ms = 50_000;
        eps.add(w);
        EpochScore.Episode c = new EpochScore.Episode();
        c.kind = "case";
        c.agent = "Агент 4";
        c.what = "mob";
        c.result = "OK";
        c.t = 777;
        c.key = "c:777:Агент 4:mob";
        eps.add(c);
        EpochScore.rateAll(eps);
        check(w.rating() == -1 && c.rating() == 1, "before the person: the rules' ratings");
        List<JsonObject> ls = Arrays.asList(label("fight", 51_500, "Агент 2", "wait", 1, "right"), label("case", 777, "Агент 4", "mob", -1, "dangerous"),
                label("fight", 90_000, "Агент 2", "wait", -1, "dumb"));
        int n = EpochScore.applyLabels(eps, ls);
        check(n == 2, "two episodes rated by the person (the third rating is 40 s off: none): " + n);
        check(w.rating() == 1 && w.why().equals("right") && w.score() == EpochScore.HUMAN, "the person's +1 over the rules' -1, weighing 3");
        check(c.rating() == -1 && c.score() == -EpochScore.HUMAN, "a case by its tick, agent and reflex: the person's -1 over the rules' +1");
        check(w.auto == -1, "the rules' rating stays written beside it");
    }

    // ---- the karma and the reward --------------------------------------------------------------------------

    static EpochScore.Stock stock(String at, int wave) {
        EpochScore.Stock s = new EpochScore.Stock();
        s.at = at;
        s.wave = wave;
        return s;
    }

    static void karma() {
        List<EpochScore.Stock> idle = new ArrayList<EpochScore.Stock>();
        EpochScore.Stock b = stock("begin", 0);
        b.weapon = 2;
        idle.add(b);
        EpochScore.Stock s1 = stock("wave 1 start", 1); s1.weapon = 2; idle.add(s1);
        EpochScore.Stock e1 = stock("wave 1 end", 1); e1.weapon = 2; idle.add(e1);
        EpochScore.Stock s2 = stock("wave 2 start", 2); s2.weapon = 2; idle.add(s2);
        EpochScore.Stock end = stock("end", 2); end.weapon = 2; idle.add(end);
        List<EpochScore.Karma> k = EpochScore.karma(idle);
        double sum = EpochScore.sum(k);
        int idles = 0;
        for (EpochScore.Karma x : k) if (x.code.equals("idle")) idles++;
        check(idles == 2, "the preparation and the break with nothing grown: two idles (the end came in a wave: no break) (" + idles + ")");
        check(sum == -2 * 2 - 1 - 1, "and no food, no better tool: " + sum);
        check(EpochScore.worst(k).code.equals("idle"), "the worst line: idle");

        List<EpochScore.Stock> busy = new ArrayList<EpochScore.Stock>();
        busy.add(stock("begin", 0));
        busy.get(0).weapon = 1;   // a wooden sword
        EpochScore.Stock a = stock("wave 1 start", 1);
        a.weapon = 2; a.logs = 16; a.wall = 20; a.food = 3;
        busy.add(a);
        busy.add(stock("wave 1 end", 1));
        busy.get(2).weapon = 2; busy.get(2).wall = 18; busy.get(2).food = 3;
        EpochScore.Stock c = stock("wave 2 start", 2);
        c.weapon = 3; c.wall = 20; c.food = 2; c.eaten = 1; c.ore = 4;
        busy.add(c);
        EpochScore.Stock z = stock("end", 2);
        z.weapon = 3; z.wall = 20; z.food = 2; z.eaten = 1;
        busy.add(z);
        List<EpochScore.Karma> kb = EpochScore.karma(busy);
        java.util.Map<String, Double> by = new java.util.HashMap<String, Double>();
        for (EpochScore.Karma x : kb) by.put(x.code, (by.containsKey(x.code) ? by.get(x.code) : 0) + x.points);
        check(by.get("tier_up") == 4.0, "a stone sword, then an iron one: +2 each: " + by.get("tier_up"));
        check(by.get("gathered") == 4.0, "16 logs (+2) before wave 1, 4 ore (+2) before wave 2: " + by.get("gathered"));
        check(by.get("built") == 2.0, "20 wall cells: +2 (8 a point)");
        check(by.get("ate") == 0.5 && by.get("food_stock") == 1.0, "ate once, food left at the end");
        check(!by.containsKey("idle") && !by.containsKey("no_food"), "no idle, no hunger");

        JsonObject run = new JsonObject();
        run.addProperty("waves_cleared", 4);
        run.addProperty("kills_total", 20);
        java.util.Map<String, Defense.Tally> fight = new java.util.LinkedHashMap<String, Defense.Tally>();
        Defense.Tally t = new Defense.Tally();
        t.deaths = 3; t.starved = 1; t.accidents = 1; t.taken = 30; t.dealt = 100;
        fight.put("Агент 2", t);
        List<EpochScore.Episode> eps = new ArrayList<EpochScore.Episode>();
        EpochScore.Episode r = step("Агент 2", "retreat", "OK", "wave");
        r.before = 5;
        eps.add(r);
        EpochScore.rateAll(eps);
        EpochScore.Reward rw = EpochScore.reward(run, fight, Arrays.asList(0, 4, 20), eps, kb);
        double want = 10 * 4 + 20 - 5 * 1 - 6 - 4 - 3 + 5 - 2 * (0 + 4 + 10) + 2 + 0.5 * 1 + EpochScore.sum(kb);
        check(Math.abs(rw.total - want) < 1e-9, "the reward: waves, kills, deaths by cause, damage, holes (10 at most a wave), retreats, episodes, karma: " + rw.total + " = " + want);
        check(rw.parts.get("starved")[2] == -6 && rw.parts.get("accidents")[2] == -4 && rw.parts.get("deaths")[0] == 1, "hunger and accidents apart from the fight's deaths");
        check(rw.json().getAsJsonObject("parts").has("karma"), "the reward's parts in its JSON");
        check(Defense.cause("starve").equals("starve") && Defense.cause("lava").equals("accident") && Defense.cause("mob").equals("fight"), "a death's cause from the game's damage type");
        check(EpochScore.tier("minecraft:iron_sword") == 3 && EpochScore.tier("wooden_pickaxe") == 1 && EpochScore.tier("planks") == 0, "tiers by id");
        check("logs".equals(EpochScore.store("log2:1", false)) && "ore".equals(EpochScore.store("iron_ore", false)) && "food".equals(EpochScore.store("bread", true)), "stores by id");
    }

    // ---- the lessons ---------------------------------------------------------------------------------------

    static Lessons.Lesson lesson(String text) { Lessons.Lesson l = new Lessons.Lesson(); l.text = text; l.from = "test"; return l; }

    static void lessons() throws Exception {
        Lessons ls = new Lessons();
        ls.cap = 3;
        ls.merge(Arrays.asList(lesson("Во время волны не выходи за стену: держи пост внутри."), lesson("Между волнами чини стену repair_wall.")), 1);
        check(ls.list.size() == 2, "two lessons of epoch 1");
        ls.merge(Arrays.asList(lesson("Во время волны не выходи за стену, держи пост внутри стены.")), 2);
        check(ls.list.size() == 2 && ls.list.get(0).seen == 2 && ls.list.get(0).epoch == 2, "a lesson like a kept one confirms it (seen 2, epoch 2), no new one");
        List<Lessons.Lesson> out = ls.merge(Arrays.asList(lesson("Ешь хлеб, когда здоровье ниже половины."), lesson("Сделай каменный меч до первой волны.")), 3);
        check(ls.list.size() == 3 && out.size() == 1 && out.get(0).text.contains("repair_wall"), "past the cap the weakest goes (seen once, oldest)");
        String p = ls.prompt("-3, the worst: no food");
        check(p.startsWith("\nLessons of the earlier epochs") && p.contains("[epoch 2, confirmed 2 times, from: test]") && p.contains("karma"), "the prompt's block: epoch, confirmations, source, karma");
        check(new Lessons().prompt("").isEmpty(), "no lessons, no karma: nothing in the prompt");
        check(Lessons.alike("Чини стену между волнами", "Между волнами чини стену") == 1.0, "the same words in another order: alike");
        String fenced = "{\"choices\":[{\"message\":{\"content\":\"```json\\n{\\\"lessons\\\":[{\\\"text\\\":\\\"Один\\\",\\\"from\\\":\\\"x\\\"},\\\"Два\\\"]}\\n```\"}}],\"usage\":{\"prompt_tokens\":900,\"completion_tokens\":60,\"prompt_cache_hit_tokens\":128}}";
        List<Lessons.Lesson> got = Lessons.parse(fenced);
        check(got.size() == 2 && got.get(0).text.equals("Один") && got.get(0).from.equals("x") && got.get(1).text.equals("Два"), "the answer's JSON, in a code fence, objects or words");
        int[] u = Lessons.usage(fenced, 4000);
        check(u[0] == 900 && u[1] == 60 && u[2] == 128 && u[3] == 0, "the call's tokens from its usage");
        check(Lessons.usage("not json", 4000)[0] == 1000 && Lessons.usage("not json", 4000)[3] == 1, "no usage: estimated from the characters");
        List<Lessons.Lesson> lines = Lessons.parse("- раз\n2) два\nпросто текст\n- " + new String(new char[300]).replace('\0', 'я'));
        check(lines.size() == 3 && lines.get(2).text.length() == Lessons.MAX_CHARS, "lines with '-' or a number, cut to " + Lessons.MAX_CHARS);
        StringBuilder many = new StringBuilder("{\"lessons\":[");
        for (int i = 0; i < 12; i++) many.append(i > 0 ? "," : "").append("\"урок ").append(i).append('"');
        check(Lessons.parse(many.append("]}").toString()).size() == Lessons.PER_EPOCH, "at most " + Lessons.PER_EPOCH + " an epoch");
        String body = Lessons.request("EPOCH 1: ...\nWHAT WENT BADLY:\n- BAD out_died x3: died outside\n- BAD idle_in_wave x2: ...\n", ls);
        JsonObject req = new JsonParser().parse(body).getAsJsonObject();
        check(!req.has("tools") && req.getAsJsonArray("messages").size() == 2, "the request: no tools, the system and the summary");
        check(req.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString().contains("LESSONS KEPT:\n- "), "the lessons kept go with it");
        String a1 = Lessons.stub().complete(body), a2 = Lessons.stub().complete(body);
        List<Lessons.Lesson> st = Lessons.parse(a1);
        check(a1.equals(a2) && st.size() == 2 && st.get(0).from.equals("out_died"), "the stand-in: the same lessons for the same summary, one a bad reason: " + st.size());
    }

    // ---- the grades in the memory of cases -----------------------------------------------------------------

    static CaseBook.Case kase(long t, String reaction, String result) {
        CaseBook.Case c = new CaseBook.Case();
        c.t = t;
        c.agent = "Агент 2";
        c.reflex = "mob";
        c.sit = CaseTest.stuck(1);
        c.reaction = reaction;
        c.result = result;
        return c;
    }

    static void grades() throws Exception {
        File d = CaseTest.folder("epochs-grades");
        new File(d, CaseBook.GRADES).delete();
        CaseBook b = new CaseBook(new File(d, CaseBook.NAME));
        b.load();
        for (int i = 0; i < 4; i++) b.add(kase(100 + i, "fight", "OK"));
        for (int i = 0; i < 4; i++) b.add(kase(200 + i, "flee", "OK"));
        double[] v = CaseVec.vector(CaseTest.stuck(1));
        CaseBook.Advice before = b.suggest(v, "mob");
        check(before.known(), "before the grades: an advice (both helped)");
        for (int i = 0; i < 4; i++) b.grade(100 + i, "Агент 2", "mob", -1, false, "died_after", 1);
        CaseBook.Advice auto = b.suggest(v, "mob");
        check(auto.known() && auto.reaction.equals("flee"), "the rules' grade of 'fight' (it died after): half its help gone, 'flee' advised: " + auto);
        for (int i = 0; i < 4; i++) b.grade(200 + i, "Агент 2", "mob", -1, true, "dangerous", 1);
        CaseBook.Advice human = b.suggest(v, "mob");
        check(!"flee".equals(human.reaction), "a person's -1 on 'flee' outweighs: no longer advised: " + human);
        check(b.grade(200, "Агент 2", "mob", 1, false, "reflex_ok", 2) == 1 && b.recent(8).get(4).grade == -1 && b.recent(8).get(4).human, "the rules' grade does not override a person's");
        check(b.grade(999, "Агент 2", "mob", 1, false, "x", 2) == 0, "a grade of a case not in memory: none");
        check(b.flush(5000), "the journal on the disk");
        CaseBook again = new CaseBook(new File(d, CaseBook.NAME));
        again.load();
        List<CaseBook.Case> r = again.recent(8);
        check(r.size() == 8 && r.get(0).grade == -1 && !r.get(0).human && r.get(4).grade == -1 && r.get(4).human, "the grades back from crew-grades.jsonl with the journal");
        CaseBook.Case plain = kase(1, "fight", "OK");
        check(plain.helped() == 1 && plain.weight() == 1, "a case with no grade: as before (its outcome, weight 1)");
        plain.grade = -1;
        check(plain.helped() == 0.5, "an automatic -1 on an OK case: half");
        plain.human = true;
        check(plain.helped() == 0 && plain.weight() == CaseBook.GRADE_HUMAN, "a person's -1: none, weighing 3");
    }

    // ---- the thresholds ------------------------------------------------------------------------------------

    static void knobs() {
        EpochKnobs k = new EpochKnobs();
        String first = k.after(1, 10);
        check(first.startsWith("the first measure") && k.trying != null, "epoch 1 measures the defaults, the next tries one threshold: " + k.line());
        String tried = k.trying;
        double was = k.best.get(tried), to = k.now.get(tried);
        check(Math.abs(to - was) == EpochKnobs.RANGE[EpochKnobs.index(tried)][2], "one step of one threshold");
        String kept = k.after(2, 12);
        check(kept.contains("kept") && k.best.get(tried) == to && k.history.size() == 1, "better: kept (" + kept + ")");
        String back = k.after(3, 5);
        check(back.contains("put back") && k.history.size() == 2 && !k.history.get(1).getAsJsonObject().get("kept").getAsBoolean(), "worse: put back (" + back + ")");
        check(k.best.get(tried) == to, "the best stays as it was");
        for (int e = 4; e < 30; e++) k.after(e, 5);
        for (int i = 0; i < EpochKnobs.NAMES.length; i++) {
            double v = k.now.get(EpochKnobs.NAMES[i]);
            check(v >= EpochKnobs.RANGE[i][0] && v <= EpochKnobs.RANGE[i][1], "within its range: " + EpochKnobs.NAMES[i] + " " + v);
        }
        EpochKnobs back2 = EpochKnobs.fromJson(new JsonParser().parse(k.json().toString()).getAsJsonObject());
        check(back2.line().equals(k.line()) && back2.history.size() == k.history.size(), "kept and read back");
        k.apply();
        check(Defense.LOW == (float) k.get("retreat") && Defense.LEASH == (int) Math.round(k.get("leash")), "into the fight's thresholds");
        EpochKnobs.defaults();
        check(Defense.LOW == 8 && Defense.LEASH == 4 && Defense.WOUNDED == 12, "and back to the defaults");
    }

    // ---- a run of epochs on a stand-in world ---------------------------------------------------------------

    /** the stand-in's troubles: a bad reason and the stand-in's lesson that cures it */
    static final String[][] TROUBLES = { { "out_died", "не выходи за стену" }, { "idle_in_wave", "не жди и не осматривайся" }, { "died", "отходи (retreat)" } };

    /**
     * One epoch of the stand-in world: each trouble happens (its episodes, a death) unless the minds' prompt has its
     * lesson; the waves cleared are 2 and one more for each trouble cured. No randomness: the same in, the same out.
     */
    static EpochRun.Outcome simulate(String track, int epoch, long seed, String prompt, CaseBook book) {
        EpochRun.Outcome o = new EpochRun.Outcome();
        o.track = track;
        o.epoch = epoch;
        o.seed = seed;
        o.restored = true;
        o.startTick = 1000L * epoch;
        int cured = 0;
        Defense.Tally t = new Defense.Tally();
        for (String[] tr : TROUBLES) {
            if (prompt.contains(tr[1])) { cured++; continue; }
            for (int i = 0; i < 2; i++) {
                EpochScore.Episode e = tr[0].equals("idle_in_wave") ? step("Агент 2", "wait", "OK", "wave")
                        : death("Агент 3", "fight", !tr[0].equals("out_died"), "wave");
                e.t = o.startTick + 100 * i;
                o.episodes.add(e);
                if (e.kind.equals("death")) t.deaths++;
            }
        }
        EpochScore.Episode c = new EpochScore.Episode();   // a reflex's case, graded into the book
        c.kind = "case";
        c.agent = "Агент 2";
        c.what = "mob";
        c.result = "OK";
        c.t = 100 + epoch;
        c.key = "c:" + c.t + ":Агент 2:mob";
        o.episodes.add(c);
        if (book != null) book.add(kase(c.t, "fight", "OK"));
        EpochScore.Episode good = step("Агент 4", "repair_wall", "OK", "break");
        o.episodes.add(good);
        t.kills = 3 + 4 * cured;
        o.fight.put("Агент 3", t);
        o.run = new JsonObject();
        o.run.addProperty("waves_cleared", 2 + cured);
        o.run.addProperty("kills_total", t.kills);
        o.run.addProperty("losses_total", t.deaths);
        o.run.addProperty("ended_by", "lost");
        o.run.addProperty("seconds", 300);
        EpochScore.Stock b = stock("begin", 0), e = stock("end", 2 + cured);   // the crew grows (no karma's minus: the troubles are the fight's)
        b.weapon = 2;
        e.weapon = 3;
        e.food = 1;
        o.stocks.add(b);
        o.stocks.add(e);
        o.holes.add(cured == TROUBLES.length ? 0 : 3);
        o.knobs = "test";
        o.lessonsShown = prompt.isEmpty() ? 0 : 1;
        o.minds.add("{\"t\":1}");
        return o;
    }

    static void cycle() throws Exception {
        File root = CaseTest.folder("epochs");
        deleteTree(new File(root, "stand-in"));
        EpochRun.Config cfg = new EpochRun.Config();
        cfg.run = "stand-in";
        cfg.count = 4;
        cfg.seed = 4242;
        EpochRun run = EpochRun.open(root, cfg);
        CaseBook book = new CaseBook(new File(run.learnDir(), CaseBook.NAME));
        book.load();
        List<String> order = new ArrayList<String>();
        for (int guard = 0; !run.finished() && guard < 20; guard++) {
            String track = run.nextTrack();
            int n = run.nextEpoch(track);
            order.add(track.charAt(0) + "" + n);
            String prompt = run.learns(track, "lessons") ? run.lessons().prompt("") : "";
            EpochRun.Outcome o = simulate(track, n, run.seed(n, 1), prompt, run.learns(track, "cases") ? book : null);
            EpochRun.Closed c = run.close(o, Lessons.stub(), run.learns(track, "cases") ? book : null);
            check(c.line != null && c.error == null, "epoch " + track + " " + n + " closed");
            run = EpochRun.open(root, cfg);   // as the next server's start reads it
        }
        check(order.equals(Arrays.asList("l1", "c1", "l2", "c2", "l3", "c3", "l4", "c4")), "learning and control in turn: " + order);
        check(run.finished() && run.done(EpochRun.LEARN) == 4 && run.done(EpochRun.CONTROL) == 4, "the run is through: 4 and 4");
        List<Integer> learn = run.series(EpochRun.LEARN), control = run.series(EpochRun.CONTROL);
        check(learn.get(0) == 2 && learn.get(learn.size() - 1) > learn.get(0), "learning: the waves grow from the lessons: " + learn);
        for (int i = 1; i < learn.size(); i++) check(learn.get(i) >= learn.get(i - 1), "learning never falls here: " + learn);
        check(control.equals(Arrays.asList(2, 2, 2, 2)), "control: the same seeds, nothing carried, the same waves: " + control);
        check(run.seed(3, 1) == 4242 && run.cfg.seed == 4242, "the same seed every epoch");
        check(run.best(EpochRun.LEARN) == 5 && run.best(EpochRun.CONTROL) == 2, "the best of each track");
        // the files
        File e1 = run.epochDir(EpochRun.LEARN, 1), c1 = run.epochDir(EpochRun.CONTROL, 1);
        for (String f : new String[] { "run.json", "combat.json", "episodes.jsonl", "stocks.json", "score.json", "lessons.json", "knobs.json", "timeline.jsonl", "crew-minds.jsonl" })
            check(new File(e1, f).isFile(), "epoch-1/" + f);
        check(c1.getPath().replace('\\', '/').endsWith("stand-in/control/epoch-1") && new File(c1, "summary.txt").isFile() && !new File(c1, "lessons.json").exists()
                && !new File(c1, "knobs.json").exists(), "control/epoch-1: its summary, no lessons, no thresholds");
        JsonObject lj = EpochRun.read(new File(e1, "lessons.json"));
        check(lj.getAsJsonArray("written").size() == 3 && lj.getAsJsonObject("usage").get("prompt").getAsInt() > 0 && lj.get("summary").getAsString().contains("- BAD out_died x2"),
                "epoch-1's lessons: three written from the three troubles, the call's tokens, the summary it read");
        JsonObject score = EpochRun.read(new File(e1, "score.json"));
        check(score.getAsJsonObject("reward").has("total") && score.getAsJsonArray("karma").size() > 0 && score.getAsJsonArray("reasons").size() > 0
                && score.get("start_tick").getAsLong() == 1000, "score.json: reward, karma, reasons, the start tick");
        List<String> eps = EpochRun.lines(new File(e1, "episodes.jsonl"));
        EpochScore.Episode back = EpochScore.Episode.fromJson(new JsonParser().parse(eps.get(0)).getAsJsonObject());
        check(eps.size() == 8 && back.rating() != 0 && !back.autoWhy.isEmpty(), "episodes.jsonl: every episode, rated, read back");
        check(EpochRun.lines(new File(e1, "timeline.jsonl")).size() == 8, "timeline.jsonl: the rated moments");
        JsonObject report = EpochRun.read(new File(run.dir, "report.json"));
        check(report.getAsJsonArray("epochs").size() == 8 && report.getAsJsonObject("waves_by_epoch").getAsJsonArray("learn").size() == 4
                && report.get("finished").getAsBoolean() && report.getAsJsonObject("config").get("seed").getAsString().equals("4242"), "report.json: every epoch, the waves by track, finished");
        JsonObject l1 = report.getAsJsonArray("epochs").get(0).getAsJsonObject();
        for (String k : new String[] { "track", "epoch", "seed", "waves_cleared", "reward", "karma", "good", "bad", "lessons_after", "lesson_prompt_tokens", "cases_graded", "knobs_after", "world_restored" })
            check(l1.has(k), "an epoch's line has " + k);
        check(l1.get("cases_graded").getAsInt() == 1 && report.getAsJsonArray("epochs").get(1).getAsJsonObject().get("cases_graded").getAsInt() == 0, "the learning epoch grades its case, the control one does not");
        check(new File(run.learnDir(), CaseBook.GRADES).isFile(), "learn/crew-grades.jsonl");
        Lessons kept = run.lessons();
        check(kept.list.size() == 3 && kept.list.get(0).seen == 1, "the lessons kept: three (once cured, the trouble and its lesson are not written again)");
        EpochKnobs k = run.knobs();
        check(k.history.size() == 3, "the thresholds: three tries after the first measure (" + k.history.size() + ")");
        EpochRun.View v = run.viewOf(EpochRun.LEARN, 4, false, 1, 5);
        check(v.lessons == 3 && v.top.size() == 3 && v.learn.size() == 4 && v.control.size() == 4 && v.best == 5, "the screen's view: lessons, the series, the best");
        EpochRun.Config other = new EpochRun.Config();
        other.run = "stand-in";
        other.count = 9;
        EpochRun again = EpochRun.open(root, other);
        check(again.cfg.count == 4 && !again.notes.isEmpty(), "a run keeps the config it began with (another asked is noted)");
        EpochKnobs.defaults();
    }

    static void deleteTree(File f) {
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) deleteTree(c);
        f.delete();
    }

    public static void main(String[] args) throws Exception {
        rules();
        labels();
        karma();
        lessons();
        grades();
        knobs();
        cycle();
        System.out.println("EpochsTest " + (bad == 0 ? "PASS" : "FAIL") + " (" + passed + " checks" + (bad > 0 ? ", " + bad + " failed" : "") + ")");
        if (bad > 0) System.exit(1);
    }
}
