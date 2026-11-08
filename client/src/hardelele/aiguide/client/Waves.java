package hardelele.aiguide.client;

import com.google.gson.JsonObject;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.EntitySpider;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MathHelper;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.world.ExplosionEvent;

/**
 * The waves mode: endless waves of mobs on the crew's base, each stronger than the one before (WavePlan), on the
 * server's thread (the integrated server of a singleplayer game, or the headless bench). Started by /волны старт in
 * the guide's window, the commander's button, -Daiguide.waves=LEVEL or the bench (-Daiguide.bench=waves). A run:
 * the preparation (a countdown), a wave, a break, the next wave (WaveCycle); the mobs appear on a ring around the base
 * (WaveRing: the crew's base, else where the player stands) whatever the light or the world's difficulty (a peaceful
 * world too: WaveMobs), at most cfg.maxAlive at once, and go for the base: the crew member or the player nearest to
 * them as their target (the game's own fighting and path finding: setAttackTarget, a path; nobody is moved by hand),
 * else a path to the base. Each is marked (its class, WaveMobs); it drops and gives experience as the game's own;
 * /волны стоп takes away the marked ones only. A run's numbers go to the events, to the world's waves-runs.jsonl
 * (WaveReport) and to the screen (WavesHud). The crew's minds and the guard read it through state(), next(),
 * enemies(), brief() (ARCHITECTURE-LAYERS.md, "Режим волн").
 */
public class Waves {
    /** how far a wave's mob looks for the crew (its follow range: the path finder's reach too) */
    static final double SEEK = 48;
    static final String AUTO = System.getProperty("aiguide.waves");

    static Waves instance;
    static final WaveCycle.Config cfg = config();
    static final WaveCycle cycle = new WaveCycle(cfg);
    /** what the screen and the minds read (a copy, renewed every tick) */
    static volatile WaveCycle.View view = WaveCycle.View.OFF;
    /** what the client (the commander's button, the guide's window) asks: done on the server's thread */
    static final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<Runnable>();
    /** the run's last report (the bench writes it) */
    static volatile JsonObject lastReport;

    // the run, on the server's thread
    static final List<EntityLiving> alive = new ArrayList<EntityLiving>();
    static final ArrayDeque<WavePlan.Kind> pending = new ArrayDeque<WavePlan.Kind>();
    static final List<int[]> recent = new ArrayList<int[]>();
    static final Map<Integer, double[]> still = new HashMap<Integer, double[]>();   // a mob's id: x, z, since (a stuck one)
    static final List<String> errors = new ArrayList<String>();
    static final List<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    static int[] center;
    static double[] fronts = { 0 };
    static Random rnd = new Random();
    static int run, spawnIndex, spawnedTotal, removedAtEnd, noSpot, requeued;
    static long ticks, endedTick, seedNow;
    static String source = "";
    static boolean autoDone;

    /** what the crew's code may hear of a run: "start", "end", "timeout", "pause", "resume", "over" */
    interface Listener { void wave(String what, WaveCycle.View v); }

    static WaveCycle.Config config() {
        WaveCycle.Config c = new WaveCycle.Config();
        c.prepTicks = 20 * Integer.getInteger("aiguide.waves.prep", 30);
        c.breakTicks = 20 * Integer.getInteger("aiguide.waves.break", 20);
        c.waveMaxTicks = 20 * Integer.getInteger("aiguide.waves.wavemax", 240);
        c.lossesPerWave = Integer.getInteger("aiguide.waves.losses", 6);
        c.lives = Integer.getInteger("aiguide.waves.lives", 0);
        c.maxAlive = Math.max(1, Integer.getInteger("aiguide.waves.max", 24));
        c.rMin = Math.max(6, Integer.getInteger("aiguide.waves.rmin", 20));
        c.rMax = Math.max(c.rMin, Integer.getInteger("aiguide.waves.rmax", 32));
        c.burn = !"false".equals(System.getProperty("aiguide.waves.burn"));
        return c;
    }

    static void init() {
        if (instance != null) return;
        instance = new Waves();
        FMLCommonHandler.instance().bus().register(instance);
        MinecraftForge.EVENT_BUS.register(instance);
    }

    // ---- the API for the crew's minds and the guard (ARCHITECTURE-LAYERS.md, "Режим волн") -------------------

    /** the run now: phase, wave, enemies, seconds to the next wave, the score; never null (WaveCycle.View.OFF when none) */
    static WaveCycle.View state() { return view; }

    /** seconds to the next wave: > 0 in the preparation and the breaks (held while paused), 0 in a wave, -1 when no run is on */
    static int next() { return view.secondsToNext; }

    /** the run's mobs alive now (a copy; the server's thread only) */
    static List<EntityLiving> enemies() {
        List<EntityLiving> out = new ArrayList<EntityLiving>();
        for (EntityLiving m : alive) if (!m.isDead && m.getHealth() > 0) out.add(m);
        return out;
    }

    /** a run is on (the preparation, a wave or a break, paused or not) */
    static boolean active() { return view.active(); }

    /** the point the waves go for (the base), or null when no run is on */
    static int[] base() { int[] c = center; return c == null || !active() ? null : c.clone(); }

    /** the run's mob nearest to a point, or null (the server's thread only) */
    static EntityLiving nearestEnemy(double x, double y, double z) {
        EntityLiving best = null;
        double bd = Double.MAX_VALUE;
        for (EntityLiving m : alive) {
            if (m.isDead || m.getHealth() <= 0) continue;
            double d = m.getDistanceSq(x, y, z);
            if (d < bd) { bd = d; best = m; }
        }
        return best;
    }

    /** the run in a line for a mind's prompt (Russian, as the crew's other lines for the model), "" when no run is on */
    static String brief() {
        WaveCycle.View v = view;
        if (!v.active()) return "";
        if (v.paused) return "Волны: пауза, волна " + Math.max(1, v.wave) + ".";
        if (v.phase == WaveCycle.Phase.PREP) return "Волны: подготовка, первая волна через " + v.secondsToNext + " с — готовь оборону у базы.";
        if (v.phase == WaveCycle.Phase.BREAK) return "Волны: перерыв, волна " + (v.wave + 1) + " через " + v.secondsToNext + " с (отбито " + v.cleared + ").";
        String s = "Волны: идёт волна " + v.wave + ", врагов " + v.enemies() + " (на поле " + v.alive + ")";
        int[] c = center;
        EntityLiving m = null;
        try {
            m = c == null ? null : nearestEnemy(c[0] + 0.5, c[1], c[2] + 0.5);
        } catch (RuntimeException e) {
            // asked off the server's thread while the list changed: the line without the nearest one
        }
        if (m != null) {
            int dx = MathHelper.floor_double(m.posX) - c[0], dz = MathHelper.floor_double(m.posZ) - c[2];
            String[] sides = { "востоке", "юго-востоке", "юге", "юго-западе", "западе", "северо-западе", "севере", "северо-востоке" };
            s += ", ближайший в " + (int) Math.round(Math.hypot(dx, dz)) + " блоках от базы на " + sides[WaveRing.side(dx, dz)];
        }
        return s + ".";
    }

    static void listen(Listener l) { listeners.add(l); }

    // ---- what the player asks (any thread: done on the server's) ---------------------------------------------------

    /** a command of the guide's window (WaveCommand) */
    static void command(final WaveCommand c) {
        queue.add(new Runnable() {
            public void run() {
                if (c.kind == WaveCommand.START) start(c.level, "command");
                else if (c.kind == WaveCommand.STOP) stop("stop");
                else if (c.kind == WaveCommand.PAUSE) pause();
            }
        });
    }

    /** the commander's button: no run: one at the normal level; a run: a pause or on; with Shift: stopped */
    static void button(final boolean shift) {
        queue.add(new Runnable() {
            public void run() {
                if (!cycle.active()) start("normal", "button");
                else if (shift) stop("stop");
                else pause();
            }
        });
    }

    // ---- the run --------------------------------------------------------------------------------------------------

    static WorldServer world() {
        MinecraftServer s = MinecraftServer.getServer();
        return s == null || s.worldServers == null || s.worldServers.length == 0 ? null : s.worldServers[0];
    }

    /** a run at a level (easy, normal, hard, chaos), from its source (command, button, flag, bench); what was done */
    static String start(String level, String from) { return start(level, from, null); }

    /** the same with the run's seed given (an epoch's: the same waves for the same seed), null: -Daiguide.waves.seed, else the world's ^ the run's id */
    static String start(String level, String from, Long seedOf) {
        WorldServer w = world();
        if (w == null) return "no world";
        if (cycle.active()) { Crew.note("waves.already"); return "already on"; }
        sweep(w, true);
        reset();
        center = where(w);
        run = (int) (System.currentTimeMillis() / 1000L);
        long seed = seedOf != null ? seedOf : Long.getLong("aiguide.waves.seed", w.getSeed() ^ run);
        rnd = new Random(seed);
        seedNow = seed;
        source = from;
        cycle.start(level, best(w), System.currentTimeMillis());
        Events.log("waves: start " + level + " (" + from + ") at " + center[0] + " " + center[1] + " " + center[2] + ", ring " + cfg.rMin + "-" + cfg.rMax
                + ", at most " + cfg.maxAlive + " alive, first wave in " + cfg.prepTicks / 20 + " s, world difficulty " + w.difficultySetting
                + (w.difficultySetting == EnumDifficulty.PEACEFUL ? " (the wave's mobs stay all the same)" : "") + ", burn " + cfg.burn + ", best before " + cycle.best);
        Crew.note("waves.prep", "@aiguide.crew.waves.level." + level, String.valueOf(cfg.prepTicks / 20));
        update();
        tell("start");
        return "started";
    }

    /** the run ends by the player (stop) or the bench's time (bench_time): its mobs are taken away, the run written */
    static String stop(String why) {
        WorldServer w = world();
        if (!cycle.active()) { if (cycle.phase != WaveCycle.Phase.OVER) Crew.note("waves.off"); if (w != null) sweep(w, true); return "not on"; }
        cycle.over(why, System.currentTimeMillis());
        if (w != null) removedAtEnd += sweep(w, true);
        pending.clear();
        finish(w);
        return "stopped";
    }

    static void pause() {
        if (!cycle.active()) { Crew.note("waves.off"); return; }
        boolean on = !cycle.paused;
        cycle.pause(on);
        Events.log("waves: " + (on ? "paused" : "goes on") + " (wave " + cycle.wave + ")");
        Crew.note(on ? "waves.paused" : "waves.resumed");
        update();
        tell(on ? "pause" : "resume");
    }

    static void reset() {
        alive.clear();
        pending.clear();
        recent.clear();
        still.clear();
        errors.clear();
        spawnIndex = spawnedTotal = removedAtEnd = noSpot = requeued = 0;
        endedTick = 0;
        lastReport = null;
    }

    /** the base: the crew's, else where the player stands (the bench's stand-in), else the world's spawn */
    static int[] where(WorldServer w) {
        Crew c = Crew.instance;
        if (c != null && c.base != null) return c.base.clone();
        for (Object o : w.playerEntities)
            if (o instanceof EntityPlayerMP && !(o instanceof FakePlayer)) {
                EntityPlayerMP p = (EntityPlayerMP) o;
                return new int[] { MathHelper.floor_double(p.posX), MathHelper.floor_double(p.boundingBox.minY), MathHelper.floor_double(p.posZ) };
            }
        EntityPlayerMP s = Crew.standIn;
        if (s != null) return new int[] { MathHelper.floor_double(s.posX), MathHelper.floor_double(s.posY), MathHelper.floor_double(s.posZ) };
        ChunkCoordinates sp = w.getSpawnPoint();
        return new int[] { sp.posX, w.getTopSolidOrLiquidBlock(sp.posX, sp.posZ), sp.posZ };
    }

    static File runsFile(WorldServer w) { return new File(w.getSaveHandler().getWorldDirectory(), "waves-runs.jsonl"); }

    /** the most waves a run of this world cleared */
    static int best(WorldServer w) {
        try {
            File f = runsFile(w);
            return f.isFile() ? WaveReport.best(java.nio.file.Files.readAllLines(f.toPath(), java.nio.charset.StandardCharsets.UTF_8)) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** the run is over: its numbers to the events, to the world's waves-runs.jsonl, to the screen */
    static void finish(WorldServer w) {
        endedTick = ticks;
        String world = w == null ? "" : w.getSaveHandler().getWorldDirectoryName();
        JsonObject o = WaveReport.run(cycle, world, source, w != null && w.difficultySetting == EnumDifficulty.PEACEFUL, spawnedTotal, removedAtEnd, errors);
        o.addProperty("waves_seed", String.valueOf(seedNow));   // (a string: a long's digits stay whole in any JSON reader)
        lastReport = o;
        String line = WaveReport.line(o);
        if (w != null)
            try {
                java.nio.file.Files.write(runsFile(w).toPath(), (line + "\n").getBytes("UTF-8"), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception e) {
                Events.log("waves: the run is not written: " + e);
            }
        Events.log("waves: run over (" + cycle.reason + "): " + cycle.cleared + " waves cleared, " + cycle.kills + " kills, " + cycle.losses + " lost; " + line);
        Crew.note("waves.over", String.valueOf(cycle.cleared), String.valueOf(cycle.kills), String.valueOf(cycle.losses));
        update();
        tell("over");
    }

    static void tell(String what) {
        WaveCycle.View v = view;
        for (Listener l : listeners)
            try {
                l.wave(what, v);
            } catch (RuntimeException e) {
                Events.log("waves: a listener failed: " + e);
            }
    }

    static void update() { view = cycle.view(alive.size(), pending.size()); }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        ticks++;
        for (Runnable r; (r = queue.poll()) != null; ) r.run();
        WorldServer w = world();
        if (w == null) return;
        try {
            if (AUTO != null && !autoDone && System.getProperty("aiguide.bench") == null && ticks > 200 && realPlayer(w)) {   // -Daiguide.waves=LEVEL: the run starts by itself
                autoDone = true;
                String lv = WavePlan.level(AUTO);
                start(lv == null ? "normal" : lv, "flag");
            }
            if (ticks % 100 == 0) sweep(w, false);
            if (!cycle.active()) { update(); return; }
            prune(w);
            WaveCycle.Event ev = cycle.tick(alive.size(), pending.size(), System.currentTimeMillis());
            if (ev == WaveCycle.Event.WAVE_START) begin(w);
            else if (ev == WaveCycle.Event.WAVE_END) {
                Events.log("waves: wave " + cycle.wave + " cleared in " + cycle.current().ticks / 20 + " s: killed " + cycle.current().killed + ", gone " + cycle.current().gone
                        + ", crew lost " + cycle.current().losses + "; next in " + cfg.breakTicks / 20 + " s");
                Crew.note("waves.end", String.valueOf(cycle.wave), String.valueOf(cfg.breakTicks / 20));
                tell("end");
            } else if (ev == WaveCycle.Event.WAVE_TIMEOUT) {
                int n = 0;
                for (EntityLiving m : new ArrayList<EntityLiving>(alive)) { gone(m); WaveMobs.remove(m); n++; }
                alive.clear();
                pending.clear();
                Events.log("waves: wave " + cycle.wave + " out of time: " + n + " of its mobs taken away");
                Crew.note("waves.timeout", String.valueOf(cycle.wave), String.valueOf(n));
                tell("timeout");
            } else if (ev == WaveCycle.Event.LOST) {
                Events.log("waves: wave " + cycle.wave + " took " + cycle.current().losses + " of the crew: the run is lost");
                finish(w);
                return;
            }
            if (!cycle.paused && cycle.phase == WaveCycle.Phase.WAVE && ticks % cfg.spawnEvery == 0) spawn(w);
            if (!cycle.paused) for (EntityLiving m : new ArrayList<EntityLiving>(alive)) if ((m.getEntityId() + ticks) % 20 == 0) steer(m, w);
            update();
        } catch (Exception ex) {
            StringBuilder at = new StringBuilder();
            StackTraceElement[] st = ex.getStackTrace();
            for (int i = 0; i < Math.min(3, st.length); i++) at.append(" @ ").append(st[i].getClassName().replace("hardelele.aiguide.client.", "")).append(':').append(st[i].getLineNumber());
            if (errors.size() < 20) errors.add(ex + at.toString());
            Events.log("waves failed: " + ex + at);
        }
    }

    static boolean realPlayer(WorldServer w) {
        for (Object o : w.playerEntities) if (o instanceof EntityPlayerMP && !(o instanceof FakePlayer)) return true;
        return MinecraftServer.getServer() != null && MinecraftServer.getServer().isDedicatedServer();
    }

    /** a new wave: its mobs in their order, the sides it comes from */
    static void begin(WorldServer w) {
        int n = cycle.wave;
        int[] counts = WavePlan.counts(n, cycle.mult);
        pending.clear();
        pending.addAll(WavePlan.queue(counts, rnd));
        cycle.planned(pending.size());
        fronts = WaveRing.fronts(n, rnd);
        recent.clear();
        requeued = 0;
        StringBuilder sides = new StringBuilder(), keys = new StringBuilder();
        for (double f : fronts) {
            int s = WaveRing.side(f);
            sides.append(sides.length() > 0 ? "," : "").append(new String[] { "E", "SE", "S", "SW", "W", "NW", "N", "NE" }[s]);
            keys.append(keys.length() > 0 ? "," : "@").append("aiguide.crew.waves.side.").append(s);
        }
        Events.log("waves: wave " + n + " begins" + (WavePlan.boss(n) ? " (a boss wave)" : "") + ": " + WavePlan.describe(counts) + ", from " + sides + ", budget " + WavePlan.budget(n, cycle.mult));
        Crew.note(WavePlan.boss(n) ? "waves.boss" : "waves.start", String.valueOf(n), String.valueOf(pending.size()), keys.toString());
        tell("start");
    }

    /** the wave's next mobs onto the ring, two at a time, as long as there is room (cfg.maxAlive) */
    static void spawn(final WorldServer w) {
        WaveRing.Ground g = new WaveRing.Ground() {
            public int stand(int x, int z) {
                if (!w.blockExists(x, 64, z)) return WaveRing.NONE;   // not loaded: not there
                int y = w.getTopSolidOrLiquidBlock(x, z);
                if (y < 2 || y > 250) return WaveRing.NONE;
                if (!Crew.stand(w, x, y, z) || Crew.hot(w, x, y, z)) return WaveRing.NONE;
                if (w.getBlock(x, y, z).getMaterial().isLiquid() || w.getBlock(x, y + 1, z).getMaterial().isLiquid()) return WaveRing.NONE;
                return y;
            }
        };
        for (int i = 0; i < 2 && !pending.isEmpty() && alive.size() < cfg.maxAlive; i++) {
            WavePlan.Kind k = pending.poll();
            double front = fronts[spawnIndex++ % fronts.length];
            int[] at = WaveRing.pick(g, center[0], center[1], center[2], cfg.rMin, cfg.rMax, cfg.maxRise, front, rnd, recent);
            if (at == null) {
                pending.addFirst(k);
                if (noSpot++ % 40 == 0) Events.log("waves: no cell on the ring to spawn on (" + noSpot + " times): the ground around the base is water, wall or not loaded");
                return;
            }
            EntityLiving m = WaveMobs.make(k, w, run, cycle.wave, cfg.burn);
            float yaw = (float) Math.toDegrees(Math.atan2(center[2] - at[2], center[0] - at[0])) - 90;
            m.setLocationAndAngles(at[0] + 0.5, at[1], at[2] + 0.5, yaw, 0);
            m.rotationYawHead = yaw;
            w.spawnEntityInWorld(m);
            w.func_147487_a("largesmoke", at[0] + 0.5, at[1] + 1, at[2] + 0.5, 12, 0.3, 0.5, 0.3, 0.01);
            alive.add(m);
            cycle.spawned();
            spawnedTotal++;
            recent.add(at);
            if (recent.size() > 12) recent.remove(0);
        }
    }

    /** the mobs gone from the world since the last tick: killed ones were counted when they died, the rest are gone */
    static void prune(WorldServer w) {
        for (Iterator<EntityLiving> it = alive.iterator(); it.hasNext(); ) {
            EntityLiving m = it.next();
            if (!m.isDead && m.worldObj == w && w.getEntityByID(m.getEntityId()) == m) continue;
            it.remove();
            still.remove(m.getEntityId());
            if (m.isDead) gone(m);   // (not dead: its chunk was unloaded; the sweep takes it back when it is loaded again)
        }
    }

    static void gone(EntityLiving m) {
        WaveMobs.Tag t = ((WaveMobs.Mob) m).tag();
        if (t.counted) return;
        t.counted = true;
        cycle.gone();
    }

    /**
     * every 5 s (and at a start or a stop: all): the marked mobs of no run on now (a run stopped, lost a while ago, or
     * one of before the world was saved) are taken away; the run's own back from an unloaded chunk are its again
     */
    static int sweep(WorldServer w, boolean all) {
        int n = 0;
        boolean linger = cycle.phase == WaveCycle.Phase.OVER && ticks - endedTick <= 20 * 30;   // a lost run's mobs stay half a minute
        for (Object o : new ArrayList<Object>(w.loadedEntityList)) {
            if (!(o instanceof WaveMobs.Mob)) continue;
            EntityLiving m = (EntityLiving) o;
            if (m.isDead) continue;
            WaveMobs.Tag t = ((WaveMobs.Mob) m).tag();
            boolean ours = !all && t.run == run && (cycle.active() || linger);
            if (!ours) {
                w.func_147487_a("largesmoke", m.posX, m.posY + 1, m.posZ, 8, 0.3, 0.5, 0.3, 0.01);
                WaveMobs.remove(m);
                n++;
            } else if (cycle.active() && !alive.contains(m)) alive.add(m);
        }
        if (n > 0) Events.log("waves: " + n + " marked mobs taken away");
        return n;
    }

    // ---- the mobs' way to the base ------------------------------------------------------------------------------

    /** a target a wave's mob may have: alive, near, not the agents' hands (a fake player out of the world), not a player who cannot be hurt */
    static boolean valid(Entity t, EntityLiving m) {
        if (t == null || !t.isEntityAlive() || t instanceof FakePlayer || t.worldObj != m.worldObj) return false;
        if (t instanceof EntityPlayer && ((EntityPlayer) t).capabilities.disableDamage) return false;
        return m.getDistanceToEntity(t) < SEEK + 16;
    }

    /** the crew member (an agent) or the player nearest to a mob within r, or null */
    static Entity nearestTarget(EntityLiving m, double r) {
        Entity best = null;
        double bd = r * r;
        Crew c = Crew.instance;
        if (c != null)
            for (Agent a : c.agents) {
                if (a.isDead || a.getHealth() <= 0 || a.worldObj != m.worldObj) continue;
                double d = m.getDistanceSqToEntity(a);
                if (d < bd) { bd = d; best = a; }
            }
        for (Object o : m.worldObj.playerEntities) {
            if (!(o instanceof EntityPlayerMP) || o instanceof FakePlayer) continue;
            EntityPlayerMP p = (EntityPlayerMP) o;
            if (p.isDead || p.capabilities.disableDamage) continue;
            double d = m.getDistanceSqToEntity(p);
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    /** once a second for each mob: its target (the nearest of the crew), else a path to the base; one stuck too long comes again from the ring */
    static void steer(EntityLiving m, WorldServer w) {
        boolean old = m instanceof EntitySpider;   // the game's old fighting: entityToAttack and its own path
        Entity t = old ? ((EntityCreature) m).getEntityToAttack() : m.getAttackTarget();
        if (!valid(t, m)) {
            t = nearestTarget(m, SEEK);
            if (old) ((EntityCreature) m).setTarget(t);
            else m.setAttackTarget(t instanceof EntityLivingBase ? (EntityLivingBase) t : null);
        }
        if (t == null) {   // nobody near: to the base
            if (old) {
                if (!((EntityCreature) m).hasPath()) ((EntityCreature) m).setPathToEntity(w.getEntityPathToXYZ(m, center[0], center[1], center[2], (float) SEEK, true, false, false, true));
            } else if (m.getNavigator().noPath()) m.getNavigator().tryMoveToXYZ(center[0] + 0.5, center[1], center[2] + 0.5, 1.0);
        } else if (old && (!((EntityCreature) m).hasPath() || m.getDistanceToEntity(t) > 12))
            ((EntityCreature) m).setPathToEntity(w.getPathEntityToEntity(m, t, (float) SEEK, true, false, false, true));
        // stuck: not 3 blocks from where it was 45 s ago, and not at the base nor at its target
        double[] s = still.get(m.getEntityId());
        if (s == null || Math.hypot(m.posX - s[0], m.posZ - s[1]) > 3) { still.put(m.getEntityId(), new double[] { m.posX, m.posZ, ticks }); return; }
        boolean near = t != null && m.getDistanceToEntity(t) < 6 || Math.hypot(m.posX - center[0], m.posZ - center[2]) < 8;
        if (near || ticks - s[2] < 20 * 45) return;
        still.remove(m.getEntityId());
        WaveMobs.remove(m);
        alive.remove(m);
        WaveCycle.Wave cw = cycle.current();
        if (requeued++ < 2 * Math.max(1, cw == null ? 1 : cw.planned)) {
            ((WaveMobs.Mob) m).tag().counted = true;   // (not gone: it comes again)
            pending.addLast(kindOf(m));
            Events.log("waves: " + m.getCommandSenderName() + " stuck at " + (int) m.posX + " " + (int) m.posY + " " + (int) m.posZ + ": again from the ring");
        } else gone(m);
    }

    static WavePlan.Kind kindOf(EntityLiving m) {
        if (m instanceof WaveMobs.Skeleton) return WavePlan.Kind.SKELETON;
        if (m instanceof WaveMobs.Spider) return WavePlan.Kind.SPIDER;
        if (m instanceof WaveMobs.Creeper) return WavePlan.Kind.CREEPER;
        if (m instanceof WaveMobs.Witch) return WavePlan.Kind.WITCH;
        return m.getEquipmentInSlot(4) != null ? WavePlan.Kind.ARMORED_ZOMBIE : WavePlan.Kind.ZOMBIE;
    }

    // ---- the score: who killed the wave's mobs, whom the crew lost -------------------------------------------------

    static final Pattern HANDS = Pattern.compile("\\[Agent (\\d+)\\]");

    /** who a kill is: an agent (its hands are a fake player "[Agent N]"), the player, a mob, or the world (sun, lava, a fall) */
    static String who(DamageSource s) {
        Entity e = s.getEntity();
        if (e instanceof Agent) return ((Agent) e).getCustomNameTag();
        if (e instanceof FakePlayer) {
            Matcher k = HANDS.matcher(e.getCommandSenderName());
            return k.matches() ? Crew.name(Integer.parseInt(k.group(1))) : e.getCommandSenderName();
        }
        if (e instanceof EntityPlayer) return e.getCommandSenderName();
        if (e != null) return "mob";
        return "world:" + s.getDamageType();
    }

    @SubscribeEvent
    public void died(LivingDeathEvent e) {
        if (e.entityLiving == null || e.entityLiving.worldObj.isRemote || !cycle.active()) return;
        if (e.entityLiving instanceof WaveMobs.Mob) {
            WaveMobs.Tag t = ((WaveMobs.Mob) e.entityLiving).tag();
            if (t.counted || t.run != run) return;
            t.counted = true;
            cycle.kill(who(e.source));
        } else if (e.entityLiving instanceof Agent || e.entityLiving instanceof EntityPlayerMP && !(e.entityLiving instanceof FakePlayer)) {
            String name = e.entityLiving instanceof Agent ? ((Agent) e.entityLiving).getCustomNameTag() : e.entityLiving.getCommandSenderName();
            cycle.loss(name);
            Events.log("waves: the crew lost " + name + " (" + e.source.getDamageType() + ") in wave " + cycle.wave);
            Crew.note("waves.loss", name);
        }
    }

    /** a wave's creeper that blows up is gone for good (the peaceful world's sparing does not keep it) */
    @SubscribeEvent
    public void boom(ExplosionEvent.Start e) {
        if (e.explosion != null && e.explosion.exploder instanceof WaveMobs.Mob) ((WaveMobs.Mob) e.explosion.exploder).tag().exploded = true;
    }

    /** the server stops (the world is left): a run on is written as such, nothing is kept for the next world */
    static void serverStopping() {
        WorldServer w = world();
        if (cycle.active()) {
            cycle.over("server_stop", System.currentTimeMillis());
            finish(w);
        }
        reset();
        center = null;
        cycle.phase = WaveCycle.Phase.OFF;
        ticks = 0;
        autoDone = false;
        view = WaveCycle.View.OFF;
    }
}
