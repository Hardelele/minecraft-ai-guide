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
    int ticks, state, askedAt;
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
            if (state == 0 && ticks > 60) { setUp(w); state = 1; askedAt = ticks; }
            else if (state == 1) {
                if (Compiler.last != null) { state = 2; Events.log("bench: the guide gave goals"); }
                else if (ticks - askedAt > 20 * 120) {   // the guide gave none in two minutes: the goals straight (marked FALLBACK)
                    state = 2;
                    Events.log("bench: no goals from the guide, the fallback goals");
                    new Thread(new Runnable() { public void run() { Events.log("bench: " + Compiler.plan(name, FALLBACK, Origin.FALLBACK)); } }).start();
                }
            } else if (state == 2) {
                Crew c = Crew.instance;
                boolean done = c != null && Crew.finished.contains("gather") && c.current == null && c.phases.isEmpty();
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
        FakePlayer p = FakePlayerFactory.get(w, new GameProfile(UUID.nameUUIDFromBytes("aiguide-bench-player".getBytes()), name));
        p.setLocationAndAngles(best[0] + 0.5, best[1], best[2] + 0.5, 0, 0);
        Crew.standIn = p;
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
        Events.log("bench: spawn " + sp.posX + " " + sy + " " + sp.posZ + ", base at " + best[0] + " " + best[1] + " " + best[2] + ", " + trunks.size() + " trunks around, score " + (int) bs);
        if (mode.startsWith("goals:")) {
            final String goals = mode.substring(6).replace("|", "\n");
            new Thread(new Runnable() { public void run() { Events.log("bench: " + Compiler.plan(name, goals, Origin.SCRIPT)); } }).start();
        } else {
            String ask = mode.startsWith("ask:") ? mode.substring(4) : "Позови команду из четырёх — к ночи нужна база";
            java.lang.reflect.Method m = Class.forName("hardelele.aiguide.Tools").getDeclaredMethod("crewAsk", String.class, String.class);
            m.setAccessible(true);
            Events.log("bench: asked the guide: " + ask + " -> " + m.invoke(null, name, ask));
        }
    }

    void report(WorldServer w, boolean done) {
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
                ph.add(x);
            }
        }
        o.add("phases", ph);
        Day1.Base b = Day1.lastBase;
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
            boolean door = b.doorAt != null && w.getBlock(b.doorAt[0], b.doorAt[1], b.doorAt[2]) == Blocks.wooden_door;
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
