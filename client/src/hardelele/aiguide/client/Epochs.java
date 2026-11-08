package hardelele.aiguide.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemPickaxe;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.world.WorldServer;

/**
 * The epochs of learning in the game (the bench's -Daiguide.bench=epochs; ARCHITECTURE-LAYERS.md, "Эпохи обучения"):
 * one server run is one epoch, a run of waves from the world as its copy was (tools/epochs.py puts it back before each
 * start and starts the next); EpochRun says which track and epoch it is, keeps what is carried. Here: the channels set
 * before the run (the learning track's memory of cases out of the world, the lessons in the minds' prompt, the fight's
 * thresholds; the control track's none), the episodes gathered as they end (a mind's step, a reflex's case, a death:
 * each rated by the rules at once for the screen), the crew's stocks at each wave's turn, the holes in the wall, and
 * when the run is over the epoch closed on a thread of its own (the model's one call for the lessons), the bench
 * waiting for it before it stops the server. Nothing of it runs when no epoch is on.
 */
final class Epochs {
    private Epochs() { }

    static volatile boolean on;
    static EpochRun run;
    static EpochRun.Outcome now;
    static String track = EpochRun.LEARN;
    static int epoch;
    static boolean listening;
    static long mindsFrom = -1;
    static Future<?> closing;
    static final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AIGuide-epochs");
        t.setDaemon(true);
        return t;
    });

    /** the run's config from -Daiguide.epochs.* (and the bench's level, crew, guard) */
    static EpochRun.Config config(String level) {
        EpochRun.Config c = new EpochRun.Config();
        c.run = System.getProperty("aiguide.epochs.run", "epochs").replaceAll("[^A-Za-z0-9_.-]", "_");
        c.count = Math.max(1, Integer.getInteger("aiguide.epochs.count", 10));
        c.level = level;
        c.seed = Long.getLong("aiguide.epochs.seed", 0L);
        c.grow = "grow".equals(System.getProperty("aiguide.epochs.seeds", "same"));
        c.control = !"off".equals(System.getProperty("aiguide.epochs.control", "on")) && !"false".equals(System.getProperty("aiguide.epochs.control"));
        c.minutes = Math.max(1, Integer.getInteger("aiguide.epochs.minutes", Integer.getInteger("aiguide.bench.minutes", 20)));
        c.crew = Integer.getInteger("aiguide.bench.waves.crew", 4);
        c.guard = System.getProperty("aiguide.bench.waves.guard", "mind");
        c.learn(System.getProperty("aiguide.epochs.learn", "cases,lessons,knobs"));
        return c;
    }

    /** where the runs are: -Daiguide.epochs.dir, else epochs in the server's folder */
    static File root() { return new File(System.getProperty("aiguide.epochs.dir", "epochs")).getAbsoluteFile(); }

    // ---- the epoch begins (the bench, before the run of waves) ----------------------------------------------

    /**
     * The next epoch of the run: its track, its number, its seed; the world's mark checked (put back from its copy?);
     * the channels set. Returns the seed of its waves, or null when the run is through (nothing to do: the bench stops).
     */
    static Long begin(WorldServer w, String level) {
        EpochRun.Config cfg = config(level);
        run = EpochRun.open(root(), cfg);
        for (String n : run.notes) Events.log("epochs: " + n);
        if (run.finished()) {
            Events.log("epochs: the run " + cfg.run + " is through (" + run.done(EpochRun.LEARN) + " learning, " + run.done(EpochRun.CONTROL) + " control epochs)");
            return null;
        }
        track = run.nextTrack();
        epoch = run.nextEpoch(track);
        now = new EpochRun.Outcome();
        now.track = track;
        now.epoch = epoch;
        now.seed = run.seed(epoch, w.getSeed());
        now.startMs = System.currentTimeMillis();
        now.startTick = w.getTotalWorldTime();
        File wd = w.getSaveHandler().getWorldDirectory();
        now.restored = mark(wd);
        if (!now.restored) now.notes.add("the world was not put back from its copy before this epoch (no fresh aiguide-epoch.json): it goes on from the last one's");
        File minds = new File(wd, "crew-minds.jsonl");
        mindsFrom = minds.isFile() ? minds.length() : 0;
        // (a) the memory of cases: the learning track's out of the world (carried), the control's the world's own (put back each time)
        if (run.learns(track, "cases")) {
            File ld = run.learnDir();
            if (!new File(ld, CaseBook.NAME).isFile())   // the first learning epoch: the world's memory as both tracks begin with it
                for (String n : new String[] { "crew-cases.3.jsonl", "crew-cases.2.jsonl", "crew-cases.1.jsonl", CaseBook.NAME })
                    try { if (new File(wd, n).isFile()) Files.copy(new File(wd, n).toPath(), new File(ld, n).toPath()); } catch (Exception e) { Events.log("epochs: " + n + " not copied: " + e); }
            Cases.dirOverride = ld;
        } else Cases.dirOverride = null;
        // (b) the lessons in the minds' prompt (the same all the epoch)
        if (run.learns(track, "lessons")) {
            Lessons ls = run.lessons();
            String karma = lastKarma();
            Mind.lessons = ls.prompt(karma);
            now.lessonsShown = ls.list.size();
            now.lessonsUsed = Mind.lessons;
        } else Mind.lessons = "";
        // (c) the fight's thresholds
        if (run.learns(track, "knobs")) {
            EpochKnobs k = run.knobs();   // (the first epoch: the defaults, measured as they are)
            k.apply();
            now.knobs = k.line();
        } else {
            EpochKnobs.defaults();
            now.knobs = "defaults";
        }
        Defense.clear();
        on = true;
        if (!listening) {
            listening = true;
            Waves.listen(new Waves.Listener() {
                public void wave(String what, WaveCycle.View v) {
                    if (!on || now == null) return;
                    WorldServer ws = Waves.world();
                    if (ws == null) return;
                    try {
                        if (what.equals("start") && v.phase == WaveCycle.Phase.WAVE) {
                            now.stocks.add(stock(Crew.instance, ws, "wave " + v.wave + " start", v.wave));
                            int holes = 0;
                            for (Defense.Perimeter p : DefenseWorld.walls()) { int s = DefenseWorld.standing(ws, p); if (s > 0) holes += p.cells.size() - s; }
                            now.holes.add(holes);
                        } else if (what.equals("end") || what.equals("timeout")) {
                            now.stocks.add(stock(Crew.instance, ws, "wave " + v.wave + " end", v.wave));
                            show(true, 0, v.cleared);
                        }
                    } catch (Exception e) {
                        Events.log("epochs: the wave's turn not taken: " + e);
                    }
                }
            });
        }
        if (Boolean.getBoolean("aiguide.epochs.rec")) {   // the epoch recorded for filming (WorldRec), named by the run, track and epoch
            now.recording = "epoch-" + cfg.run + "-" + track + "-" + epoch;
            Events.log("epochs: world recording " + now.recording + ": " + WorldRec.start(now.recording));
        }
        Events.log("epochs: " + cfg.run + " " + track + " epoch " + epoch + " of " + cfg.count + ", seed " + now.seed + ", world put back " + now.restored
                + ", learning " + (track.equals(EpochRun.LEARN) ? cfg.learn : "none") + ", lessons " + now.lessonsShown + ", thresholds " + now.knobs);
        show(true, 0, 0);
        return now.seed;
    }

    /** the run of waves has begun (the crew armed): the first stock */
    static void started(WorldServer w) {
        if (!on || now == null) return;
        now.stocks.add(stock(Crew.instance, w, "begin", 0));
    }

    /** the karma of the learning track's last epoch, in a line for the minds ("" none) */
    static String lastKarma() {
        com.google.gson.JsonArray eps = run.state.getAsJsonArray("epochs");
        for (int i = eps.size() - 1; i >= 0; i--) {
            JsonObject x = eps.get(i).getAsJsonObject();
            if (!CaseVec.str(x, "track").equals(EpochRun.LEARN)) continue;
            String w = x.has("karma_worst") ? ", the worst: " + EpochScore.karmaText(x.get("karma_worst").getAsString()) : "";
            return EpochScore.fmt(CaseVec.num(x, "karma", 0)) + w;
        }
        return "";
    }

    /**
     * The world's mark (aiguide-epoch.json, written by tools/epochs.py when it put the world back): fresh (not used
     * yet) means this epoch begins from the copy; it is marked used now, so a start without putting it back is told.
     */
    static boolean mark(File worldDir) {
        File f = new File(worldDir, "aiguide-epoch.json");
        JsonObject o = EpochRun.read(f);
        boolean fresh = o != null && !(o.has("used") && o.get("used").getAsBoolean());
        if (o == null) o = new JsonObject();
        o.addProperty("used", true);
        o.addProperty("run", run.cfg.run);
        o.addProperty("track", track);
        o.addProperty("epoch", epoch);
        EpochRun.write(f, o);
        return fresh;
    }

    static void show(boolean live, long closedMs, int cleared) {
        if (run == null) return;
        try { EpochRun.view = run.viewOf(track, epoch, live, closedMs, cleared); } catch (Exception e) { /* the screen without it */ }
    }

    // ---- the episodes, as they end ----------------------------------------------------------------------------

    static String phase() {
        WaveCycle.View v = Waves.state();
        return v.phase == WaveCycle.Phase.OFF ? "" : v.phase.name().toLowerCase(java.util.Locale.ROOT);
    }

    static boolean inside(Agent a) {
        Defense.Perimeter p = DefenseWorld.ring;
        return p == null || p.inside(a.posX, a.posZ);
    }

    static int[] at(Agent a) { return new int[] { (int) Math.floor(a.posX), (int) Math.floor(a.posY), (int) Math.floor(a.posZ) }; }

    static synchronized void add(Agent a, EpochScore.Episode e) {
        if (now == null) return;
        now.episodes.add(e);
        int r = EpochScore.rate(e);   // at once, for the screen (rated again at the end, the deaths after it known)
        if (r != 0) EpochRun.pop(a.getEntityId(), r, e.autoWhy, e.ms);
    }

    /** a mind's step ended (AgentMind.record): its tool, how it ended, the health it began with, what it came to in the fight */
    static void step(Agent a, WorldServer w, String tool, String result, String detail, float before, Defense.Tally fight) {
        if (!on) return;
        try {
            EpochScore.Episode e = new EpochScore.Episode();
            e.kind = "step";
            e.agent = a.who();
            e.what = tool;
            e.result = result == null ? "OK" : result;
            e.detail = detail == null ? "" : detail.length() > 200 ? detail.substring(0, 200) : detail;
            e.ms = System.currentTimeMillis();
            e.t = w.getTotalWorldTime();
            e.key = "f:" + e.ms + ":" + e.agent + ":" + tool;
            e.phase = phase();
            e.wave = Waves.state().wave;
            e.before = before;
            e.inside = inside(a);
            e.fight = fight;
            e.at = at(a);
            add(a, e);
        } catch (Exception x) {
            Events.log("epochs: a step not taken: " + x);
        }
    }

    /** a reflex's case ended (Cases.save) */
    static void reflexCase(Agent a, CaseBook.Case c) {
        if (!on) return;
        try {
            EpochScore.Episode e = new EpochScore.Episode();
            e.kind = "case";
            e.agent = c.agent;
            e.what = c.reflex;
            e.result = c.result;
            e.detail = c.reaction;
            e.t = c.t;
            e.ms = System.currentTimeMillis();
            e.key = "c:" + c.t + ":" + c.agent + ":" + c.reflex;
            e.phase = phase();
            e.wave = Waves.state().wave;
            e.before = c.sit == null ? -1 : c.sit.health;
            e.inside = inside(a);
            e.at = at(a);
            add(a, e);
        } catch (Exception x) {
            Events.log("epochs: a case not taken: " + x);
        }
    }

    /** an agent died (Agent.onDeath): an episode of its own; its steps and cases of the last DIED_SOON ticks were bad ones */
    static void died(Agent a, String damageType, String cause) {
        if (!on) return;
        try {
            long t = a.worldObj.getTotalWorldTime();
            EpochScore.Episode e = new EpochScore.Episode();
            e.kind = "death";
            e.agent = a.who();
            e.what = "death";
            e.result = damageType == null ? "" : damageType;
            e.cause = cause;
            e.t = t;
            e.ms = System.currentTimeMillis();
            e.key = "d:" + t + ":" + e.agent;
            e.phase = phase();
            e.wave = Waves.state().wave;
            e.inside = inside(a);
            e.at = at(a);
            synchronized (Epochs.class) {
                if (now != null) for (EpochScore.Episode x : now.episodes) if (x.agent.equals(e.agent) && !x.kind.equals("death") && t - x.t <= EpochScore.DIED_SOON && x.t <= t) x.diedSoon = true;
            }
            add(a, e);
        } catch (Exception x) {
            Events.log("epochs: a death not taken: " + x);
        }
    }

    // ---- the crew's stocks -----------------------------------------------------------------------------------

    /** what the crew has now: its best tiers, its stores (bags, armour worn, the crew's chest), what it has built and eaten */
    static EpochScore.Stock stock(Crew c, WorldServer w, String at, int wave) {
        EpochScore.Stock s = new EpochScore.Stock();
        s.at = at;
        s.wave = wave;
        s.t = w.getTotalWorldTime();
        if (c != null) {
            for (Agent a : c.agents) {
                if (a.isDead) continue;
                for (ItemStack it : a.bag) count(s, it);
                for (int i = 1; i <= 4; i++) count(s, a.getEquipmentInSlot(i));
            }
            try {
                IInventory inv = c.chestInv(w);
                if (inv != null) for (int i = 0; i < inv.getSizeInventory(); i++) count(s, inv.getStackInSlot(i));
            } catch (Exception e) {
                // no chest
            }
        }
        for (Defense.Perimeter p : DefenseWorld.walls()) s.wall += DefenseWorld.standing(w, p);
        if (DefenseWorld.moat != null) for (int[] m : DefenseWorld.moat) if (DefenseWorld.open(w, m[0], m[1], m[2])) s.moat++;
        for (Defense.Tally t : Defense.all().values()) { s.torches += t.torches; s.eaten += t.eaten; }
        return s;
    }

    static void count(EpochScore.Stock s, ItemStack it) {
        if (it == null || it.getItem() == null) return;
        String id = MindSkills.id(it);
        if (it.getItem() instanceof ItemSword || it.getItem() instanceof ItemBow) s.weapon = Math.max(s.weapon, EpochScore.tier(id));
        else if (it.getItem() instanceof ItemPickaxe) s.pick = Math.max(s.pick, EpochScore.tier(id));
        else if (it.getItem() instanceof ItemArmor) s.armor = Math.max(s.armor, EpochScore.tier(id));
        boolean food = it.getItem() instanceof ItemFood && !id.startsWith("rotten_flesh") && !id.startsWith("spider_eye") && !id.startsWith("poisonous_potato");
        String k = EpochScore.store(id, food);
        if (k == null) return;
        if (k.equals("logs")) s.logs += it.stackSize;
        else if (k.equals("stone")) s.stone += it.stackSize;
        else if (k.equals("ore")) s.ore += it.stackSize;
        else s.food += it.stackSize;
    }

    // ---- the epoch ends (the bench, after the run) ------------------------------------------------------------

    /** the run of waves is over: the outcome gathered, the epoch closed on the epochs' thread (the bench waits for done()) */
    static void close(WorldServer w) {
        if (!on || now == null) return;
        on = false;
        final EpochRun.Outcome o = now;
        try {
            o.endMs = System.currentTimeMillis();
            o.run = Waves.lastReport;
            o.fight.putAll(Defense.all());
            o.stocks.add(stock(Crew.instance, w, "end", Waves.state().wave));
            File wd = w.getSaveHandler().getWorldDirectory();
            o.labels.addAll(labels(new File(new File(wd, Labels.DIR), Labels.ACTIONS), o.startMs, o.endMs));
            File extra = new File(run.dir, "labels");   // ratings put there by hand (from watching a recording): every one counts
            File[] fs = extra.listFiles();
            if (fs != null) for (File f : fs) if (f.getName().endsWith(".jsonl")) o.labels.addAll(labels(f, Long.MIN_VALUE, Long.MAX_VALUE));
            o.minds.addAll(tail(new File(wd, "crew-minds.jsonl"), mindsFrom));
        } catch (Exception e) {
            o.notes.add("the outcome was not all gathered: " + e);
        }
        if (WorldRec.active != null && !o.recording.isEmpty()) Events.log("epochs: " + WorldRec.stop());
        final Mind.Model model = !run.learns(o.track, "lessons") ? null
                : "stub".equals(System.getProperty("aiguide.epochs.model")) ? Lessons.stub() : AgentMind.model;
        final CaseBook book = run.learns(o.track, "cases") ? Cases.book() : null;
        closing = pool.submit(() -> {
            try {
                if (book != null) book.flush(5000);
                EpochRun.Closed c = run.close(o, model, book);
                Events.log("epochs: " + o.track + " epoch " + o.epoch + " closed: " + c.line);
                if (c.error != null) Events.log("epochs: " + c.error);
                for (Lessons.Lesson l : c.fresh) Events.log("epochs: lesson: " + l.text + (l.from.isEmpty() ? "" : " (" + l.from + ")"));
                if (!c.knobs.isEmpty()) Events.log("epochs: thresholds: " + c.knobs);
                show(false, System.currentTimeMillis(), o.run == null ? 0 : (int) CaseVec.num(o.run, "waves_cleared", 0));
            } catch (Exception e) {
                Events.log("epochs: the epoch was not closed: " + e);
            }
        });
    }

    /** the epoch is closed (or none was on) */
    static boolean done() { return closing == null || closing.isDone(); }

    /** the person's ratings of a labels file within a time (their ms) */
    static List<JsonObject> labels(File f, long from, long to) {
        List<JsonObject> out = new ArrayList<JsonObject>();
        for (String l : EpochRun.lines(f)) {
            try {
                JsonObject o = new JsonParser().parse(l).getAsJsonObject();
                long ms = (long) CaseVec.num(o, "ms", 0);
                if (ms >= from && ms <= to) out.add(o);
            } catch (Exception e) {
                // a broken line
            }
        }
        return out;
    }

    /** a file's lines from a byte offset on (this epoch's part of a journal the world had before it) */
    static List<String> tail(File f, long from) {
        List<String> out = new ArrayList<String>();
        if (!f.isFile() || from < 0) return out;
        try {
            byte[] b = Files.readAllBytes(f.toPath());
            if (from > b.length) return out;
            for (String l : new String(b, (int) from, b.length - (int) from, StandardCharsets.UTF_8).split("\n")) if (!l.trim().isEmpty()) out.add(l);
        } catch (Exception e) {
            // none
        }
        return out;
    }
}
