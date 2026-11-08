package hardelele.aiguide.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

/**
 * The game's side of the person's ratings and of the share archive: where the labels go (aiguide-labels in the world's
 * folder), what world they are about, the input cube of a rated place, and what a share archive is made of (the
 * world's regions near the crew, its journals, the versions) with the names the game knows for the cleaning.
 */
final class Feedback {
    private Feedback() { }

    static final Minecraft mc = Minecraft.getMinecraft();
    static final ConcurrentLinkedQueue<Runnable> server = new ConcurrentLinkedQueue<Runnable>();
    static final int[] RADII = { 2, 4, 8, 16 };   // chunks around the base and the explored, to choose from

    private static final ExecutorService writer = Executors.newSingleThreadExecutor(new ThreadFactory() {
        public Thread newThread(Runnable r) { Thread t = new Thread(r, "aiguide-labels"); t.setDaemon(true); return t; }
    });

    static void start() {
        FMLCommonHandler.instance().bus().register(new Feedback.Ticks());
        PlaceLayer.start();
    }

    /** the integrated server's ticks: what must be done there (the world saved before an archive) */
    public static final class Ticks {
        @SubscribeEvent
        public void tick(TickEvent.ServerTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            for (Runnable r; (r = server.poll()) != null; ) {
                try { r.run(); } catch (Exception ex) { Director.log("feedback: server step failed: " + ex); }
            }
        }
    }

    // ---- the labels ----------------------------------------------------------------------------------

    static File worldDir() { return Cases.worldDir(); }

    /** aiguide-labels in the world's folder (singleplayer); on someone else's server, in the game's folder */
    static File labelsDir() {
        File w = worldDir();
        return new File(w != null ? w : ClientDir.get(), Labels.DIR);
    }

    static JsonObject world() {
        long seed = 0, tick = mc.theWorld == null ? 0 : mc.theWorld.getTotalWorldTime();
        String folder = "remote";
        MinecraftServer s = MinecraftServer.getServer();
        if (s != null && s.worldServers != null && s.worldServers.length > 0 && s.worldServers[0] != null) {
            seed = s.worldServers[0].getSeed();
            File d = worldDir();
            folder = d == null ? "" : d.getName();
        }
        return Labels.world(seed, folder, tick);
    }

    static void write(final String file, final JsonObject o) {
        final File f = new File(labelsDir(), file);
        writer.execute(new Runnable() {
            public void run() {
                try { Labels.append(f, o); }
                catch (Exception e) { Director.log("labels: not written: " + e); }
            }
        });
    }

    /** a place rated: its cube read now (the source's own when it has it), the line written on the labels' thread */
    static void ratePlace(PlaceView p, String answer, int label, String comment) {
        int[] origin = PlaceLayer.origin(p);
        byte[] cells = null;
        String src = "model";
        try {
            PlaceSource s = PlaceSource.current;
            cells = s == null ? null : s.cube(p);
        } catch (Exception e) {
            cells = null;
        }
        if (cells == null || cells.length != Labels.CELLS) {
            src = "world";
            cells = mc.theWorld == null ? null : PlaceLayer.read(mc.theWorld, origin);
        }
        write(Labels.PLACES, Labels.place(System.currentTimeMillis(), world(), p, PlaceSource.weightsOf(), origin, cells, src, answer, label, comment));
        Director.log("labels: place " + p.id() + " " + Labels.name(p.cls()) + " -> " + answer + (label >= 0 ? " " + Labels.name(label) : ""));
    }

    static void rateAction(Episodes.Ep e, int rating, String reason, String comment) {
        write(Labels.ACTIONS, Labels.action(System.currentTimeMillis(), world(), e, rating, reason, comment));
        Director.log("labels: " + e.type + " " + e.key() + " -> " + (rating > 0 ? "+1" : "-1") + (reason == null ? "" : " " + reason));
    }

    static List<CaseBook.Case> recentCases(int n) {
        try { return Cases.book().recent(n); } catch (Exception e) { return new ArrayList<CaseBook.Case>(); }
    }

    // ---- the share archive ---------------------------------------------------------------------------

    static File shareDir() { return new File(ClientDir.get(), "aiguide-share"); }

    static File configFile() {
        return new File(new File(new File(System.getProperty("aiguide.config", "config")).getAbsoluteFile(), "aiguide"), ShareUpload.NAME);
    }

    /** the sending's config (the template written when there is none: the address empty, nothing is sent) */
    static ShareUpload.Config config() {
        File f = configFile();
        if (!f.exists()) {
            try {
                f.getParentFile().mkdirs();
                java.nio.file.Files.write(f.toPath(), ShareUpload.Config.template().getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                // no template: still nothing to send
            }
        }
        ShareUpload.Config c = ShareUpload.Config.read(f);
        if (c.viaService() && !c.installId.matches("[0-9a-f]{32}")) c.installId = ShareUpload.Config.installId(f);   // made once, random
        return c;
    }

    /** the names and places the game knows, to be cleaned out */
    static Scrub scrubber() {
        Scrub s = new Scrub();
        try { s.name(mc.getSession().getUsername()); } catch (Exception e) { /* none */ }
        if (mc.thePlayer != null) s.name(mc.thePlayer.getCommandSenderName());
        if (mc.theWorld != null) for (Object o : mc.theWorld.playerEntities) s.name(((net.minecraft.entity.player.EntityPlayer) o).getCommandSenderName());
        MinecraftServer srv = MinecraftServer.getServer();
        if (srv != null && srv.getConfigurationManager() != null) for (String n : srv.getConfigurationManager().getAllUsernames()) s.name(n);
        try {   // Forge's cache of every name this game has met
            File uc = new File(ClientDir.get(), "usernamecache.json");
            if (uc.isFile()) {
                JsonObject o = new JsonParser().parse(new String(ShareBuild.read(uc), StandardCharsets.UTF_8)).getAsJsonObject();
                for (Map.Entry<String, com.google.gson.JsonElement> e : o.entrySet()) s.name(e.getValue().getAsString());
            }
        } catch (Exception e) { /* none */ }
        String user = System.getProperty("user.name", "");
        if (!user.matches("(?i)user|admin|administrator|player|owner|pc|default|guest")) s.name(user);   // a word that common: the paths' rule is enough
        try {
            net.minecraft.client.multiplayer.ServerData cur = mc.getCurrentServerData();
            if (cur != null) s.host(cur.serverIP);
            net.minecraft.client.multiplayer.ServerList list = new net.minecraft.client.multiplayer.ServerList(mc);
            for (int i = 0; i < list.countServers(); i++) s.host(list.getServerData(i).serverIP);
        } catch (Exception e) { /* none */ }
        s.path(ClientDir.get().getAbsolutePath(), "<minecraft>");
        s.path(System.getProperty("user.home"), "<home>");
        return s;
    }

    /** the chunks the archive's regions are around: the crew's base and the explored columns (else where the player is) */
    static List<int[]> centres() {
        Set<Long> seen = new LinkedHashSet<Long>();
        List<int[]> out = new ArrayList<int[]>();
        Crew c = Crew.instance;
        if (c != null && c.base != null) add(out, seen, c.base[0] >> 4, c.base[2] >> 4);
        for (Long k : FogMap.explored) {
            int cx = (int) (k >> 32), cz = (int) (long) k;
            add(out, seen, (cx * FogMap.CELL) >> 4, (cz * FogMap.CELL) >> 4);
        }
        if (out.isEmpty() && mc.thePlayer != null) add(out, seen, (int) Math.floor(mc.thePlayer.posX) >> 4, (int) Math.floor(mc.thePlayer.posZ) >> 4);
        return out;
    }

    private static void add(List<int[]> out, Set<Long> seen, int cx, int cz) {
        if (seen.add(((long) cx << 32) ^ (cz & 0xffffffffL))) out.add(new int[] { cx, cz });
    }

    /** the items of the consent screen, their files and sizes (ticks kept from the plan before, if any) */
    static ShareBuild plan(int radius, ShareBuild before) {
        ShareBuild b = new ShareBuild(scrubber(), new JsonObject());
        File w = worldDir();
        if (w != null) {
            ShareBuild.Item regions = b.item("regions", true);
            File rd = new File(w, "region");
            for (Map.Entry<String, BitSet> e : ShareBuild.regions(centres(), radius).entrySet()) {
                File f = new File(rd, e.getKey());
                if (!f.isFile()) continue;
                ShareBuild.Part p = new ShareBuild.Part("world/region/" + e.getKey(), f, ShareBuild.Mode.REGION);
                p.keep = e.getValue();
                regions.parts.add(p);
            }
            byte[] level = levelDat(new File(w, "level.dat"));
            if (level != null) regions.parts.add(new ShareBuild.Part("world/level.dat", level, ShareBuild.Mode.COPY));
            ShareBuild.Item cases = b.item("cases", true);
            for (int i = 3; i >= 1; i--) cases.parts.add(new ShareBuild.Part("crew/crew-cases." + i + ".jsonl", new File(w, "crew-cases." + i + ".jsonl"), ShareBuild.Mode.TEXT));
            cases.parts.add(new ShareBuild.Part("crew/" + CaseBook.NAME, new File(w, CaseBook.NAME), ShareBuild.Mode.TEXT));
        }
        ShareBuild.Item labels = b.item("labels", true);
        File ld = labelsDir();
        File[] lf = ld.listFiles();
        if (lf != null) for (File f : lf) if (f.isFile() && f.getName().endsWith(".jsonl")) labels.parts.add(new ShareBuild.Part("labels/" + f.getName(), f, ShareBuild.Mode.TEXT));
        ShareBuild.Item events = b.item("events", true);
        StringBuilder ev = new StringBuilder(), chat = new StringBuilder();
        for (String l : Episodes.lines()) (isChat(l) ? chat : ev).append(l).append('\n');
        events.parts.add(new ShareBuild.Part("crew/crew-events.txt", ev.toString().getBytes(StandardCharsets.UTF_8), ShareBuild.Mode.TEXT));
        if (w != null) {
            events.parts.add(new ShareBuild.Part("crew/crew.json", new File(w, "crew.json"), ShareBuild.Mode.TEXT));
            events.parts.add(new ShareBuild.Part("crew/ledger.json", new File(w, "ledger.json"), ShareBuild.Mode.TEXT));
        }
        File video = new File(ClientDir.get(), "video-events.txt");   // the director's log (video runs only); its chat lines apart
        if (video.isFile() && video.length() < 64L << 20) {
            try {
                StringBuilder v = new StringBuilder();
                for (String l : new String(ShareBuild.read(video), StandardCharsets.UTF_8).split("\n")) (isChat(l) ? chat : v).append(l).append('\n');
                events.parts.add(new ShareBuild.Part("crew/video-events.txt", v.toString().getBytes(StandardCharsets.UTF_8), ShareBuild.Mode.TEXT));
            } catch (Exception e) { /* left out */ }
        }
        ShareBuild.Item rec = b.item("worldrec", false);
        File[] recs = WorldRec.home().listFiles();
        WorldRec now = WorldRec.active;
        int k = 0;
        if (recs != null) for (File d : recs) {
            File s = new File(d, "session.bin.gz");
            if (!s.isFile() || now != null && d.equals(now.dir)) continue;   // the one being written is not taken
            k++;
            rec.parts.add(new ShareBuild.Part("recordings/rec" + k + "/session.bin.gz", s, ShareBuild.Mode.REC));
            rec.parts.add(new ShareBuild.Part("recordings/rec" + k + "/coverage.json", new File(d, "coverage.json"), ShareBuild.Mode.TEXT));
        }
        b.item("mods", true).parts.add(new ShareBuild.Part("meta/mods.json", json(mods()), ShareBuild.Mode.COPY));
        b.item("machine", false).parts.add(new ShareBuild.Part("meta/machine.json", json(machine()), ShareBuild.Mode.COPY));
        for (String[] h : new ArrayList<String[]>(GuiGuide.history)) chat.append(h[0] == null ? "guide" : h[0]).append(": ").append(h[1].replace('\n', ' ')).append('\n');
        b.item("chat", false).parts.add(new ShareBuild.Part("chat/guide-chat.txt", chat.toString().getBytes(StandardCharsets.UTF_8), ShareBuild.Mode.TEXT));
        if (before != null) for (ShareBuild.Item i : b.items) for (ShareBuild.Item j : before.items) if (i.id.equals(j.id)) i.on = j.on;
        JsonObject a = new JsonObject();
        a.addProperty("client", AIGuideClient.VERSION);
        ModContainer server = Loader.instance().getIndexedModList().get("aiguide");
        if (server != null) a.addProperty("server", server.getVersion());
        b.meta.add("aiguide", a);
        b.meta.addProperty("minecraft", Loader.MC_VERSION);
        b.meta.add("world", world());
        b.meta.addProperty("radiusChunks", radius);
        b.meta.addProperty("weights", PlaceSource.weightsOf());
        return b;
    }

    /** a line that carries what a person wrote (the commander's words to the guide, the guide's chat) */
    static boolean isChat(String l) { return l.contains("asked the guide") || l.contains("\tchat ") || l.contains("\tsay ") || l.contains("\task "); }

    static byte[] json(JsonObject o) { return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(o).getBytes(StandardCharsets.UTF_8); }

    /** level.dat without the player in it and with no name (the seed and the mods' block ids stay: they are needed) */
    static byte[] levelDat(File f) {
        if (!f.isFile()) return null;
        try {
            FileInputStream in = new FileInputStream(f);
            NBTTagCompound root;
            try { root = CompressedStreamTools.readCompressed(in); } finally { in.close(); }
            NBTTagCompound data = root.getCompoundTag("Data");
            data.removeTag("Player");
            data.setString("LevelName", "world");
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            CompressedStreamTools.writeCompressed(root, o);
            return o.toByteArray();
        } catch (Exception e) {
            Director.log("share: level.dat left out: " + e);
            return null;
        }
    }

    static JsonObject mods() {
        JsonObject o = new JsonObject();
        o.addProperty("minecraft", Loader.MC_VERSION);
        o.addProperty("aiguideClient", AIGuideClient.VERSION);
        JsonArray a = new JsonArray();
        for (ModContainer m : Loader.instance().getActiveModList()) {
            JsonObject x = new JsonObject();
            x.addProperty("id", m.getModId());
            x.addProperty("name", m.getName());
            x.addProperty("version", m.getVersion());
            a.add(x);
        }
        o.add("mods", a);
        return o;
    }

    static JsonObject machine() {
        JsonObject o = new JsonObject();
        o.addProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        o.addProperty("java", System.getProperty("java.version") + " " + System.getProperty("java.vendor"));
        o.addProperty("cores", Runtime.getRuntime().availableProcessors());
        o.addProperty("maxMemoryMB", Runtime.getRuntime().maxMemory() >> 20);
        o.addProperty("display", mc.displayWidth + "x" + mc.displayHeight);
        try {
            o.addProperty("gl", org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER) + " / " + org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION));
        } catch (Exception e) { /* not on the render thread */ }
        o.addProperty("renderDistance", mc.gameSettings.renderDistanceChunks);
        return o;
    }

    /** before the archive: the world saved on the server's thread (the regions then whole on the disk); waits at most 30 s */
    static Runnable saveFirst() {
        return new Runnable() {
            public void run() {
                if (MinecraftServer.getServer() == null) return;
                final CountDownLatch done = new CountDownLatch(1);
                server.add(new Runnable() {
                    public void run() {
                        try {
                            for (WorldServer ws : MinecraftServer.getServer().worldServers) {
                                if (ws == null) continue;
                                ws.saveAllChunks(true, null);
                                ws.flush();
                            }
                        } catch (Exception e) {
                            Director.log("share: save failed: " + e);
                        } finally {
                            done.countDown();
                        }
                    }
                });
                try { done.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { /* go on */ }
            }
        };
    }

    /** the consent as the archive keeps it: what was ticked, when, in what language */
    static JsonObject consent(ShareBuild b, int radius) {
        JsonObject c = new JsonObject();
        c.addProperty("at", Labels.iso(System.currentTimeMillis()));
        c.addProperty("text", "aiguide.fb.share.consent.v1");
        c.addProperty("language", mc.gameSettings.language);
        c.add("items", new com.google.gson.Gson().toJsonTree(ShareBuild.ticks(b.items)));
        c.addProperty("radiusChunks", radius);
        c.addProperty("chatIncluded", b.item("chat", false).on);
        return c;
    }

    static void openFolder(File d) {
        d.mkdirs();
        try {
            java.awt.Desktop.getDesktop().open(d);
        } catch (Throwable e) {
            try { org.lwjgl.Sys.openURL("file:///" + d.getAbsolutePath().replace('\\', '/')); } catch (Throwable ignored) { /* nothing more to try */ }
        }
    }
}
