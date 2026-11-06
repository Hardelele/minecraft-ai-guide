package hardelele.aiguide.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.List;
import java.util.UUID;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

/**
 * The headless test bench: a dedicated server (no client, no window) with the crew and the guide, a fake player
 * standing in for a real one. Started with -Daiguide.bench=ask:<what the player says> (the guide plans the day) or
 * -Daiguide.bench=goals:<a|b|c> (the goals straight); -Daiguide.bench.out=<report.json>. It picks a place with trees
 * near the spawn (the spawn's chunks stay loaded on a server without players), asks, waits for the crew to finish the
 * day (or the time limit), writes the report (the ledger's numbers, the phases with their times, how much of the base
 * stands) and stops the server.
 */
public class Bench {
    static final String FALLBACK = "summon 4\nshelter auto\ntools stone\ntorches 16\ngift stone_pickaxe, torch 8\ngather";
    static Bench instance;
    final String mode = System.getProperty("aiguide.bench", "");
    final File out = new File(System.getProperty("aiguide.bench.out", "bench-report.json"));
    final int limit = Integer.getInteger("aiguide.bench.minutes", 40) * 60 * 20;
    PrintWriter events;
    int ticks, state, askedAt, idleTicks;
    String name = "Player";
    int[] at;

    static void start() {
        instance = new Bench();
        FMLCommonHandler.instance().bus().register(instance);
        try {
            instance.events = new PrintWriter(new OutputStreamWriter(new FileOutputStream(new File(instance.out.getPath().replace(".json", "") + "-events.txt")), "UTF-8"), true);
        } catch (Exception e) {
            System.out.println("[Bench] no events file: " + e);
        }
        Events.tap = new Events.Sink() {
            public void log(String line) {
                PrintWriter p = instance.events;
                if (p != null) p.println("[" + (instance.ticks / 20) + " s] " + line);
            }
        };
        Events.log("bench: " + instance.mode);
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        ticks++;
        MinecraftServer s = MinecraftServer.getServer();
        WorldServer w = s.worldServers[0];
        w.resetUpdateEntityTick();   // a server with no player stops moving entities after a minute: the fake player counts as one
        try {
            if (state == 0 && ticks > 60) {
                setUp(w);
                state = 1;
                askedAt = ticks;
                String rec = System.getProperty("aiguide.bench.rec");
                if (rec != null && !rec.isEmpty()) Events.log("bench: world recording " + rec + ": " + WorldRec.start(rec));   // the whole session, for filming it afterwards
            }
            else if (state == 1) {
                if (Compiler.plans > plansBefore) { state = 2; Events.log("bench: the guide gave goals"); }
                else if (ticks - askedAt > 20 * 120) {   // the guide gave none in two minutes: the goals straight (marked FALLBACK)
                    state = 2;
                    Events.log("bench: no goals from the guide, the fallback goals");
                    new Thread(new Runnable() { public void run() { Events.log("bench: " + Compiler.plan(name, FALLBACK, Origin.FALLBACK)); } }).start();
                }
            } else if (state == 2) {
                Crew c = Crew.instance;
                if (c != null && ticks % 600 == 0)   // every 30 s: where each agent is and what it is on (for a stuck job)
                    for (Agent a : c.agents) {
                        TaskBoard.Card k = c.board.leased.get(a);
                        Events.log("bench: " + a.getCustomNameTag() + " at " + (int) Math.floor(a.posX) + " " + (int) Math.floor(a.posY) + " " + (int) Math.floor(a.posZ)
                                + " " + a.activity() + (k != null ? " on " + k : "") + (a.jobs.isEmpty() ? "" : " job " + Bench.describe(a.jobs.peek())));
                    }
                boolean anyOpen = false;
                if (c != null) for (TaskBoard.Card k : c.board.cards) if (k.open()) anyOpen = true;
                boolean idle = c != null && c.current == null && c.phases.isEmpty() && (!Crew.phaseLog.isEmpty() || !anyOpen && ticks - askedAt > 100);   // (the player's orders: done when no card is open)
                idleTicks = idle ? idleTicks + 1 : 0;
                boolean done = idle && (Crew.finished.contains("gather") || idleTicks > 20 * 45);   // the plan is through (no word from the guide for a while)
                if (done && next < asks.length && ticks <= limit) {   // the next request (the morning after the night...)
                    askNext(w);
                    state = 1;
                    askedAt = ticks;
                    idleTicks = 0;
                    Crew.finished.remove("gather");
                    return;
                }
                if (done || ticks > limit) {
                    state = 3;
                    report(w, done);
                    s.initiateShutdown();
                }
            }
        } catch (Exception ex) {
            Events.log("bench failed: " + ex);
            state = 3;
            report(w, false);
            s.initiateShutdown();
        }
    }

    /** a place with trees around near the spawn; the fake player stands there and asks */
    void setUp(WorldServer w) throws Exception {
        ChunkCoordinates sp = w.getSpawnPoint();
        int sy = w.getTopSolidOrLiquidBlock(sp.posX, sp.posZ);
        Crew.ensure();
        Crew c = Crew.instance;
        if (!c.loaded) c.load(w);
        if (c.base != null) {   // a world the crew has lived in (a copy of it): the day goes on at its base
            at = c.base.clone();
            w.setSpawnLocation(at[0], at[1], at[2]);   // (the copy's spawn: its chunks stay loaded without a player)
            for (int cx = (at[0] >> 4) - 5; cx <= (at[0] >> 4) + 5; cx++)
                for (int cz = (at[2] >> 4) - 5; cz <= (at[2] >> 4) + 5; cz++) w.theChunkProviderServer.loadChunk(cx, cz);
            c.adopt(w);
            Events.log("bench: the crew's world: base at " + at[0] + " " + at[1] + " " + at[2] + ", " + c.agents.size() + " agents, chest " + java.util.Arrays.toString(c.chest)
                    + ", furnace " + java.util.Arrays.toString(Day1.furnace) + ", mine " + java.util.Arrays.toString(Day1.mine));
            standAndAsk(w, at);
            return;
        }
        List<int[]> trunks = Crew.trunks(w, sp.posX, sy, sp.posZ, 72);
        int[] best = null;
        double bs = -1;
        for (int dx = -48; dx <= 48; dx += 4)
            for (int dz = -48; dz <= 48; dz += 4) {
                int x = sp.posX + dx, z = sp.posZ + dz, y = Crew.ground(w, x, w.getTopSolidOrLiquidBlock(x, z), z);
                if (y < 0 || w.getBlock(x, y - 1, z).getMaterial().isLiquid()) continue;
                double score = 0;
                for (int[] t : trunks) {
                    double d = Math.hypot(t[0] - x, t[2] - z);
                    if (d < 28 && Math.abs(t[1] - y) <= 6) score += t[3] / (1 + d * 0.05);
                }
                score -= Math.hypot(dx, dz) * 0.2;   // the nearer the spawn, the safer its chunks stay loaded
                if (score > bs) { bs = score; best = new int[] { x, y, z }; }
            }
        if (best == null) best = new int[] { sp.posX, sy, sp.posZ };
        at = best;
        Events.log("bench: spawn " + sp.posX + " " + sy + " " + sp.posZ + ", base at " + best[0] + " " + best[1] + " " + best[2] + ", " + trunks.size() + " trunks around, score " + (int) bs);
        standAndAsk(w, best);
    }

    /** the fake player stands there and asks (or the goals go straight to the compiler) */
    void standAndAsk(WorldServer w, int[] best) throws Exception {
        FakePlayer p = FakePlayerFactory.get(w, new GameProfile(UUID.nameUUIDFromBytes("aiguide-bench-player".getBytes()), name));
        p.setLocationAndAngles(best[0] + 0.5, best[1], best[2] + 0.5, 0, 0);
        Crew.standIn = p;
        if (Boolean.getBoolean("aiguide.bench.spawns") && !w.playerEntities.contains(p)) {
            // the game spawns mobs only around players: the stand-in counts as one (the world's own spawning, nothing spawned by hand)
            w.playerEntities.add(p);
            Events.log("bench: natural spawns around the stand-in player, difficulty " + w.difficultySetting);
        }
        w.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
        w.setWorldTime(2000);
        try {   // the guide finds the fake player by its name (its self-test's way)
            Class<?> g = Class.forName("hardelele.aiguide.AIGuide");
            java.lang.reflect.Field f = g.getDeclaredField("extraPlayers");
            f.setAccessible(true);
            @SuppressWarnings("unchecked") java.util.Map<String, Object> m = (java.util.Map<String, Object>) f.get(null);
            m.put(name, p);
        } catch (Exception ex) {
            Events.log("bench: the guide does not know the fake player: " + ex);
        }
        asks = mode.split("\\|\\|");
        askNext(w);
    }

    String[] asks = new String[0];
    int next, plansBefore;

    /**
     * The next request: "ask:<words>" (to the guide) or "goals:<a|b>" (straight), with an optional "@<time>:" before it
     * that sets the time of day first (as the camera's script sets the light)
     */
    void askNext(WorldServer w) throws Exception {
        String r = asks[next++].trim();
        if (r.startsWith("@")) {
            int colon = r.indexOf(':');
            w.setWorldTime(Long.parseLong(r.substring(1, colon)));
            r = r.substring(colon + 1);
        }
        plansBefore = Compiler.plans;
        if (r.startsWith("order:")) {   // order:craft ITEM N AGENT[,AGENT]: the player's order, as the commander gives it
            String[] a = r.substring(6).trim().split("\\s+");
            String[] who = a[3].split(",");
            int[] ws = new int[who.length];
            for (int i = 0; i < ws.length; i++) ws[i] = Integer.parseInt(who[i]);
            Orders.give(ws, Orders.Kind.valueOf(a[0].toUpperCase()), 0, 0, 0, -1, false, a[1], Integer.parseInt(a[2]));
            Compiler.plans++;   // (no plan from the guide to wait for)
            Events.log("bench: order " + r.substring(6));
            return;
        }
        if (r.startsWith("goals:")) {
            final String goals = r.substring(6).replace("|", "\n");
            new Thread(new Runnable() { public void run() { Events.log("bench: " + Compiler.plan(name, goals, Origin.SCRIPT)); } }).start();
        } else {
            String ask = r.startsWith("ask:") ? r.substring(4) : "Позови команду из четырёх — к ночи нужна база";
            java.lang.reflect.Method m = Class.forName("hardelele.aiguide.Tools").getDeclaredMethod("crewAsk", String.class, String.class);
            m.setAccessible(true);
            Events.log("bench: asked the guide (" + next + "/" + asks.length + "): " + ask + " -> " + m.invoke(null, name, ask));
        }
    }

    /** a task and what it is doing inside (its fields that are tasks too), as far as it goes */
    static String describe(Object t) {
        StringBuilder b = new StringBuilder();
        for (int depth = 0; t != null && depth < 12; depth++) {
            b.append(depth > 0 ? " > " : "").append(t.getClass().getSimpleName().isEmpty() ? t.getClass().getName().replaceAll(".*\\.", "") : t.getClass().getSimpleName());
            Object next = null;
            for (Class<?> cl = t.getClass(); cl != null && next == null; cl = cl.getSuperclass())
                for (java.lang.reflect.Field f : cl.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(t);
                        if (v instanceof Crew.Task && v != t) { next = v; break; }
                        if (v instanceof Crew.Task[]) { Object[] arr = (Object[]) v; java.lang.reflect.Field fi = cl.getDeclaredField("i"); fi.setAccessible(true); int i = fi.getInt(t); if (i < arr.length) { next = arr[i]; break; } }
                        if (f.getType() == Crew.Break.class && v != null) { b.append(" (breaking)"); }
                    } catch (Exception ex) {
                        // not readable: as far as it goes
                    }
                }
            if (next == null) {   // the innermost: its numbers (where it is in its work)
                b.append(" {");
                for (java.lang.reflect.Field f : t.getClass().getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(t);
                        if (v instanceof Integer || v instanceof Double || v instanceof Boolean) b.append(f.getName()).append('=').append(v).append(' ');
                        else if (v instanceof Crew.GoTo) { Crew.GoTo g = (Crew.GoTo) v; b.append(f.getName()).append("=GoTo(state ").append(g.state).append(" ticks ").append(g.ticks).append(" since ").append(g.since).append(") "); }
                        else if (v instanceof Crew.Break) b.append(f.getName()).append("=Break ");
                    } catch (Exception ex) {
                        // as far as it goes
                    }
                }
                b.append('}');
            }
            t = next;
        }
        return b.toString();
    }

    void report(WorldServer w, boolean done) {
        if (WorldRec.active != null) Events.log("bench: " + WorldRec.stop());
        JsonObject o = new JsonObject();
        o.addProperty("seed", String.valueOf(w.getSeed()));
        o.addProperty("finished", done);
        o.addProperty("seconds", ticks / 20);
        if (at != null) o.addProperty("base_at", at[0] + " " + at[1] + " " + at[2]);
        o.addProperty("ledger", Ledger.report());
        com.google.gson.Gson g = new GsonBuilder().setPrettyPrinting().create();
        synchronized (Ledger.class) {
            o.add("flows", g.toJsonTree(Ledger.flows));
            o.add("fakes", g.toJsonTree(Ledger.fakes));
            o.add("actions", g.toJsonTree(Ledger.actions));
            o.add("results", g.toJsonTree(Ledger.results));
        }
        JsonArray ph = new JsonArray();
        synchronized (Crew.phaseLog) {
            for (String[] p : Crew.phaseLog) {
                JsonObject x = new JsonObject();
                x.addProperty("kind", p[0]);
                x.addProperty("seconds", Integer.parseInt(p[1]));
                x.addProperty("cards_given_up", Integer.parseInt(p[2]));
                x.addProperty("out_of_time", Boolean.parseBoolean(p[3]));
                x.addProperty("origin", p[4]);
                if (p.length > 5) x.addProperty("results", p[5]);
                ph.add(x);
            }
        }
        o.add("phases", ph);
        Day1.Base b = Day1.firstBase != null ? Day1.firstBase : Day1.lastBase;   // against the plan as first laid (a re-laid plan leaves out what stands)
        if (b != null) {   // how much of the base stands: every block of its plan, the door
            int total = b.plan.size() + 1, standing = 0;
            JsonObject byKind = new JsonObject();
            int[][] count = new int[6][2];
            for (int[] p : b.plan) {
                Block want = Crew.block(p[3]), is = w.getBlock(p[0], p[1], p[2]);
                boolean ok = is == want || p[3] == 1 && (is == Blocks.log || is == Blocks.log2);
                count[p[3]][0]++;
                if (ok) { standing++; count[p[3]][1]++; }
            }
            boolean door = b.doorAt != null && Roles.is(w, b.doorAt, Roles.Role.DOOR);
            if (door) standing++;
            String[] names = { "planks", "log", "cobblestone", "glass", "fence", "torch" };
            for (int k = 0; k < 6; k++) if (count[k][0] > 0) byKind.addProperty(names[k], count[k][1] + "/" + count[k][0]);
            byKind.addProperty("door", door ? "1/1" : "0/1");
            o.addProperty("base_size", b.plan.size() > 200 ? "house with a fence" : b.plan.size() > 140 ? "house" : "hut");
            o.addProperty("base_percent", Math.round(1000.0 * standing / total) / 10.0);
            o.add("base_blocks", byKind);
        } else o.addProperty("base_percent", 0);
        try {
            java.nio.file.Files.write(out.toPath(), g.toJson(o).getBytes("UTF-8"));
            Ledger.save(w.getSaveHandler().getWorldDirectory());
        } catch (Exception e) {
            System.out.println("[Bench] report not written: " + e);
        }
        Events.log("bench: report " + out + ": " + o.get("ledger") + ", base " + o.get("base_percent") + "%");
        if (events != null) events.close();
    }
}
