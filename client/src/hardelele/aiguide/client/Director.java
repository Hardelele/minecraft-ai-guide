package hardelele.aiguide.client;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiCreateWorld;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiSelectWorld;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.MathHelper;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;

/**
 * For a promo video only (-Daiguide.video=<script file>): plays a script of what a player does, from the
 * main menu on: makes a new world, asks the guide in its window (typed letter by letter), answers its
 * questions, turns the camera, and logs each step with the wall clock (video-events.txt) for the edit.
 * The guide itself and the autopilot work as always: the script only types and waits.
 */
public class Director {
    static final String SCRIPT = System.getProperty("aiguide.video");
    static final String STOPFILE = System.getProperty("aiguide.video.stopfile");
    static final Charset UTF8 = Charset.forName("UTF-8");

    final Minecraft mc = Minecraft.getMinecraft();
    final List<String> lines = new ArrayList<String>();
    final ConcurrentLinkedQueue<Runnable> serverQueue = new ConcurrentLinkedQueue<Runnable>();
    int pc, t, step, seen, logged, yesState, setupRadius = 96;
    double takeSpeed = 1;
    int stopCheckTicks;
    boolean stopRequested, captureAudioConfigured, quitQueued;
    volatile Thread quitServerThread;
    volatile boolean quitPrepared;
    String typed;
    String pendingPill;
    long pendingPillAtMs;
    volatile int cmddigAck; // 0 pending, 1 server validated, 2 server rejected
    Writer log;
    // an eased look: from, to, over ticks
    float lookYaw0, lookPitch0, lookYaw1, lookPitch1;
    int lookT, lookLen;

    static Director instance;

    static void start() {
        if (SCRIPT == null || SCRIPT.isEmpty()) return;
        instance = new Director();
        try {
            for (String l : Files.readAllLines(new File(SCRIPT).toPath(), UTF8)) {
                l = l.replace("\uFEFF", "").trim();
                if (!l.isEmpty() && !l.startsWith("#")) instance.lines.add(l);
            }
            instance.log = new OutputStreamWriter(new FileOutputStream(new File(Minecraft.getMinecraft().mcDataDir, "video-events.txt"), true), UTF8);
        } catch (Exception e) {
            System.out.println("[Director] no script: " + e);
            return;
        }
        FMLCommonHandler.instance().bus().register(instance);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(instance);
        instance.event("start " + SCRIPT);
    }

    static void log(String what) {
        if (instance != null) instance.event(what);
        else System.out.println("[Director] " + what);
    }

    void event(String what) {
        System.out.println("[Director] " + what);
        try {
            log.write(System.currentTimeMillis() + "\t" + what + "\n");
            log.flush();
        } catch (Exception e) {
            // no log
        }
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        for (Runnable r; (r = serverQueue.poll()) != null; ) {
            try {
                r.run();
            } catch (Exception ex) {
                event("server step failed: " + ex);
            }
        }
    }

    /** Only a scripted capture with its own launcher marker may stop cooperatively. */
    void checkStopRequest() {
        if (stopRequested || SCRIPT == null || SCRIPT.isEmpty() || STOPFILE == null || STOPFILE.isEmpty()) return;
        if (stopCheckTicks++ % 20 != 0) return;
        File marker = new File(STOPFILE);
        if (!marker.exists()) return;
        stopRequested = true;
        String reason = "stop marker requested";
        try (java.io.Reader reader = Files.newBufferedReader(marker.toPath(), UTF8)) {
            char[] buffer = new char[512]; int n = reader.read(buffer);
            if (n > 0) reason = new String(buffer, 0, n).replace('\n', ' ').replace('\r', ' ').trim();
        } catch (Exception ex) { reason += " (reason unavailable: " + ex + ")"; }
        event("cooperative stop: " + reason);
        serverQueue.clear();
        lines.clear();
        lines.add("recstop"); lines.add("worldrec stop"); lines.add("worldrec waitstop"); lines.add("quit");
        pc = t = step = yesState = 0;
        typed = null; pchopBg = false; lookT = lookLen;
    }

    /** Discard pending scripted clicks but still close recording and world through the normal quit path. */
    void abortToQuit(String reason) {
        event("script aborted: " + reason);
        pendingPill = null;
        serverQueue.clear();
        lines.clear();
        lines.add("quit");
        pc = -1; t = step = 0;
    }

    void abortCaptureToQuit(String reason) {
        event("script aborted: " + reason);
        serverQueue.clear();
        lines.clear();
        lines.add("recstop"); lines.add("worldrec stop"); lines.add("worldrec waitstop"); lines.add("quit");
        pc = -1; t = step = 0;
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (!captureAudioConfigured) {
            if (Boolean.parseBoolean(System.getProperty("aiguide.video.mute", "true")))
                mc.gameSettings.setSoundLevel(net.minecraft.client.audio.SoundCategory.MASTER, 0);
            captureAudioConfigured = true;
        }
        checkStopRequest();
        if (mc.currentScreen instanceof net.minecraft.client.gui.GuiIngameMenu) {   // the window lost focus (someone plays in front): the world must not pause
            mc.displayGuiScreen(null);
            mc.setIngameNotInFocus();
            event("unpaused: the pause menu came up (focus lost)");
        }
        look();
        camera();
        if (pchopBg && mc.thePlayer != null) pchopTick();
        while (logged < GuiGuide.history.size()) {   // every message of the window, for the edit and the report
            String[] h = GuiGuide.history.get(logged++);
            event((h[0] == null ? "GUIDE: " : "PLAYER: ") + h[1].replace('\n', ' '));
        }
        if (pc >= lines.size()) return;
        String l = lines.get(pc);
        int sp = l.indexOf(' ');
        String cmd = sp < 0 ? l : l.substring(0, sp), arg = sp < 0 ? "" : l.substring(sp + 1).trim();
        boolean done;
        try {
            done = run(cmd, arg);
        } catch (Exception ex) {
            event("step failed: " + l + ": " + ex);
            done = true;
        }
        t++;
        if (done) {
            event("done " + l);
            pc++;
            t = 0;
            step = 0;
            yesState = 0;
        }
    }

    /** one tick of a command; true when it is done */
    boolean run(String cmd, String arg) throws Exception {
        if (cmd.equals("menu")) {   // wait on the main menu
            if (!(mc.currentScreen instanceof GuiMainMenu)) { t = -1; return false; }
            if (t == 0) event("menu");
            return t >= num(arg, 0);
        }
        if (cmd.equals("create")) return create(arg);
        if (cmd.equals("inworld")) {   // wait for the world and the guide's hello
            if (mc.thePlayer == null || mc.theWorld == null || Pilot.instance.server < 2) { t = -1; return false; }
            if (t == 0) { event("inworld"); Crew.ensure(); }
            return t >= num(arg, 0);
        }
        if (cmd.equals("wait")) return t >= num(arg, 0);
        if (cmd.equals("waitday")) return mc.theWorld != null && mc.theWorld.getWorldTime() % 24000L >= num(arg, 0);   // until the world's time of day reaches it
        // the commander, as a player would use it (the same calls as its clicks)
        if (cmd.equals("commander")) { if (arg.equals("off")) mc.displayGuiScreen(null); else Commander.instance.open(); return true; }
        if (cmd.equals("cmdlook")) { String[] a = arg.split(" "); Commander.instance.look(Double.parseDouble(a[0]), Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3])); return true; }
        if (cmd.equals("cmdsel")) { Commander.instance.selected.clear(); for (String s : arg.split(" ")) if (!s.isEmpty()) Commander.instance.selected.add(Integer.parseInt(s)); return true; }
        if (cmd.equals("cmdclick")) {   // x y z [q]: a right click on the top of that block
            String[] a = arg.split(" ");
            Commander.instance.orderBlock(Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]), 1, a.length > 3);
            return true;
        }
        if (cmd.equals("cmddig")) {   // capture fixture only: wait for one real player-style dig, never edit a block
            String[] v = arg.trim().split("\\s+");
            Commander c = Commander.instance;
            int[] who = c == null ? new int[0] : c.picked();
            if (v.length != 3 || who.length != 1 || who[0] != 1 || c.renderedAgent(1) == null) {
                abortCaptureToQuit("cmddig needs x y z and selected Agent 1 loaded");
                return true;
            }
            int x, y, z;
            try {
                x = Integer.parseInt(v[0]); y = Integer.parseInt(v[1]); z = Integer.parseInt(v[2]);
            } catch (NumberFormatException ex) {
                abortCaptureToQuit("cmddig coordinates are not integers");
                return true;
            }
            if (WorldRec.active == null || mc.theWorld == null || !mc.theWorld.blockExists(x, y, z)) {
                abortCaptureToQuit("cmddig requires WorldRec and a loaded target");
                return true;
            }
            net.minecraft.block.Block block = mc.theWorld.getBlock(x, y, z);
            if (t == 0) {
                if (block != net.minecraft.init.Blocks.stone) {
                    abortCaptureToQuit("cmddig starting target is not stone");
                    return true;
                }
                cmddigAck = 0;
                final int bx = x, by = y, bz = z;
                serverQueue.add(new Runnable() { public void run() {
                    net.minecraft.server.MinecraftServer s = net.minecraft.server.MinecraftServer.getServer();
                    WorldServer w = s == null ? null : s.worldServers[0];
                    Agent agent = Cam.serverAgent(1);
                    if (w == null || agent == null || agent.isDead || agent.worldObj != w
                            || !w.loadedEntityList.contains(agent) || !w.blockExists(bx, by, bz)
                            || w.getBlock(bx, by, bz) != net.minecraft.init.Blocks.stone) {
                        cmddigAck = 2;
                        event("cmddig rejected: Agent 1 or target not ready on server");
                        return;
                    }
                    Orders.designate(new int[] {1}, false, java.util.Collections.singletonList(new int[] {bx, by, bz}), "");
                    cmddigAck = 1;
                    event("cmddig validated Agent 1 entity " + agent.getEntityId() + ", queued one-block designation at " + bx + " " + by + " " + bz);
                }});
            }
            if (cmddigAck == 2) {
                abortCaptureToQuit("cmddig rejected by server");
                return true;
            }
            if (block == net.minecraft.init.Blocks.air) {
                if (cmddigAck != 1) {
                    abortCaptureToQuit("cmddig target changed before server acceptance");
                    return true;
                }
                event("cmddig target became air after " + t + " ticks; verify actor in WorldRec");
                return true;
            }
            if (block != net.minecraft.init.Blocks.stone || t >= 20 * 30) {
                abortCaptureToQuit("cmddig target did not become air within 30 seconds");
                return true;
            }
            return false;
        }
        if (cmd.equals("cmdclicknear")) {   // <block name> [q]: a right click on the nearest such block (seen from above: open to the sky or the air) to the camera's point
            String[] a = arg.split(" ");
            net.minecraft.block.Block want = (net.minecraft.block.Block) net.minecraft.block.Block.blockRegistry.getObject(a[0].contains(":") ? a[0] : "minecraft:" + a[0]);
            Commander c = Commander.instance;
            int cx = MathHelper.floor_double(c.px), cz = MathHelper.floor_double(c.pz), cy = MathHelper.floor_double(c.py);
            int[] best = null;
            double bd = 1e9;
            for (int x = cx - 32; x <= cx + 32; x++)
                for (int z = cz - 32; z <= cz + 32; z++)
                    for (int y = cy - 24; y <= cy + 12; y++) {
                        if (mc.theWorld.getBlock(x, y, z) != want) continue;
                        if (!mc.theWorld.isAirBlock(x, y + 1, z) && !mc.theWorld.isAirBlock(x + 1, y, z) && !mc.theWorld.isAirBlock(x - 1, y, z)
                                && !mc.theWorld.isAirBlock(x, y, z + 1) && !mc.theWorld.isAirBlock(x, y, z - 1)) continue;
                        double d = Math.hypot(x - c.px, z - c.pz) + Math.abs(y - cy) * 0.5;
                        if (d < bd) { bd = d; best = new int[] { x, y, z }; }
                    }
            event("cmdclicknear " + a[0] + ": " + (best == null ? "none" : best[0] + " " + best[1] + " " + best[2]));
            if (best != null) c.orderBlock(best[0], best[1], best[2], 1, a.length > 1);
            return true;
        }
        if (cmd.equals("cmdclicktree")) {   // [q]: a right click on the nearest standing tree (as the crew finds trees) to the camera's point
            Commander c = Commander.instance;
            WorldServer ws = MinecraftServer.getServer().worldServers[0];
            int[] best = null;
            double bd = 1e9;
            for (int[] tr : Crew.trunks(ws, MathHelper.floor_double(c.px), MathHelper.floor_double(c.py), MathHelper.floor_double(c.pz), 32)) {
                double d = Math.hypot(tr[0] - c.px, tr[2] - c.pz);
                if (tr[3] >= 3 && d < bd) { bd = d; best = tr; }
            }
            event("cmdclicktree: " + (best == null ? "none" : best[0] + " " + best[1] + " " + best[2] + " (" + best[3] + " logs)"));
            if (best != null) c.orderBlock(best[0], best[1], best[2], 1, arg.equals("q"));
            return true;
        }
        // the world's recording and its playback, and the film's cameras
        if (cmd.equals("worldrec")) {   // start <name> | stop | wait | waitstop
            final String[] a = arg.split(" ");
            if (a[0].equals("wait") || a[0].equals("waitstop")) {
                boolean ready;
                try { ready = a[0].equals("wait") ? WorldRec.active != null : CaptureStopGate.ready(WorldRec.stopped(), WorldRec.closeError); }
                catch (IllegalStateException ex) { abortToQuit(ex.getMessage()); return true; }
                if (ready) {
                    return true;
                }
                if (t >= 20 * 60) {
                    abortToQuit("WorldRec transition timed out");
                    return true;
                }
                return false;
            }
            serverQueue.add(new Runnable() { public void run() { if (a[0].equals("start")) WorldRec.start(a[1]); else WorldRec.stop(); } });
            return true;
        }
        if (cmd.equals("replay")) {   // <name>: the recording's world opened (from the main menu), the playback ready
            if (!(mc.currentScreen instanceof GuiMainMenu)) { t = -1; return false; }
            Replay.open(arg);
            return true;
        }
        if (cmd.equals("replayplay")) { if (Replay.active != null) { Replay.active.speed = arg.isEmpty() ? 1 : Double.parseDouble(arg); Replay.active.playing = true; } return true; }
        if (cmd.equals("replaymulti")) { // comma-separated heroes, frames per track, UNC output directory
            if (t == 0) {
                String[] args = arg.split(" ", 3), ids = args[0].split(",");
                int[] heroes = new int[ids.length];
                for (int i = 0; i < ids.length; i++) heroes[i] = Integer.parseInt(ids[i]);
                MultiCapture.start(heroes, Integer.parseInt(args[1]), new File(args[2]));
                return false;
            }
            if (MultiCapture.active != null) return false;
            if (MultiCapture.lastError != null) event("replaymulti failed: " + MultiCapture.lastError);
            return true;
        }
        if (cmd.equals("replaytake")) { // agent, frame count, output path; one replay clock for every camera
            String[] a = arg.split(" ", 3);
            if (t == 0) {
                if (Replay.active == null) throw new IllegalStateException("replaytake needs a replay");
                int agent = Integer.parseInt(a[0]), count = Integer.parseInt(a[1]);
                if (count < 1) throw new IllegalArgumentException("positive frame count required");
                if (Replay.active.t + count * takeSpeed / 3.0 >= Replay.active.ticks.size() - 1)
                    throw new IllegalArgumentException("requested take exceeds recorded world timeline");
                if (agent > 0) {
                    Cam c = Cam.get(); c.autoOn = false;
                    Cam.Shot shot = c.planFollow(agent, (count + 2) / 3 + 40);
                    if (shot == null) throw new IllegalStateException("no clear camera for agent " + agent);
                    c.start(shot);
                }
                Recorder.start(a[2]);
                Recorder.instance.maxFrames = count;
                Replay.active.speed = takeSpeed;
                Replay.active.playing = true;
                return false;
            }
            return Recorder.instance == null;
        }
        if (cmd.equals("replayspeed")) { takeSpeed = Double.parseDouble(arg); return true; }   // the next replaytake at this speed (a time-lapse of what was recorded)
        if (cmd.equals("replaypause")) { if (Replay.active != null) Replay.active.playing = false; return true; }
        if (cmd.equals("replayseek")) {   // <seconds>: forward to it (blocks and all applied on the way)
            if (Replay.active != null) Replay.active.t = Math.max(Replay.active.t, Math.min(Replay.active.ticks.size() - 1.001, Double.parseDouble(arg) * 20));
            return true;
        }
        if (cmd.equals("key")) {   // t(ticks) x y z yaw pitch fov: a keyframe of the path
            String[] a = arg.split(" ");
            Cam.get().keys.add(new Cam.Key(Double.parseDouble(a[0]), Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]), Double.parseDouble(a[4]), Double.parseDouble(a[5]), Double.parseDouble(a[6])));
            return true;
        }
        if (cmd.equals("path")) {   // play | rec <file>: the path of keys, until it ends
            if (t == 0) Cam.get().playPath(arg.startsWith("rec ") ? arg.substring(4) : null);
            return t > 2 && Cam.get().shot == null;
        }
        if (cmd.equals("follow")) {   // <agent number> <ticks>
            String[] a = arg.split(" ");
            if (t == 0) { Cam c = Cam.get(); c.autoOn = false; Cam.Shot s = c.planFollow(Integer.parseInt(a[0]), Integer.parseInt(a[1])); if (s != null) c.start(s); else event("follow: no view of agent " + a[0]); }
            return t >= Integer.parseInt(a[1]);
        }
        if (cmd.equals("director")) {   // <ticks>: the action-ranked director films for that long
            Cam c = Cam.get();
            if (t == 0) { c.autoOn = true; c.release(); }
            if (t >= num(arg, 600)) { c.autoOn = false; return true; }
            return false;
        }
        if (cmd.equals("screeninfo")) { event("screen: " + (mc.currentScreen == null ? "none" : mc.currentScreen.getClass().getName()) + ", active " + org.lwjgl.opengl.Display.isActive() + ", commander drawn " + Commander.drawn); return true; }
        // the commander used by a script's hand: a cursor that is drawn, moves and clicks, through the screen's own handling
        if (cmd.equals("fog")) { Fog.on = !arg.equals("off"); if (arg.equals("reset")) FogMap.explored.clear(); return true; }
        if (cmd.equals("difficulty")) {   // n: the game's difficulty for this session (not saved to the options)
            final net.minecraft.world.EnumDifficulty d = net.minecraft.world.EnumDifficulty.getDifficultyEnum(num(arg, 0));
            mc.gameSettings.difficulty = d;
            serverQueue.add(new Runnable() { public void run() { for (WorldServer ws : MinecraftServer.getServer().worldServers) { ws.difficultySetting = d; ws.setAllowedSpawnTypes(true, true); } event("difficulty: " + d); } });
            return true;
        }
        if (cmd.equals("vhand")) { Commander.instance.vOn = arg.equals("on"); if (Commander.instance.vOn) { Commander.instance.vx = mc.displayWidth / 2; Commander.instance.vy = mc.displayHeight / 2; } return true; }
        if (cmd.equals("safe")) { Commander.instance.safeAspect = Float.parseFloat(arg); if (mc.currentScreen != null) mc.currentScreen.setWorldAndResolution(mc, mc.currentScreen.width, mc.currentScreen.height); return true; }
        if (cmd.equals("guiscale")) {
            mc.gameSettings.guiScale = num(arg, 3);
            net.minecraft.client.gui.ScaledResolution r = new net.minecraft.client.gui.ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
            if (mc.currentScreen != null) mc.currentScreen.setWorldAndResolution(mc, r.getScaledWidth(), r.getScaledHeight());
            return true;
        }
        if (cmd.equals("vmove")) {   // fx fy ticks: to a point of the window (fractions, from the top left)
            String[] a = arg.split(" ");
            if (t == 0) { pendingPill = null; Commander.instance.vMove(Double.parseDouble(a[0]) * mc.displayWidth, (1 - Double.parseDouble(a[1])) * mc.displayHeight, Integer.parseInt(a[2])); }
            return t >= Integer.parseInt(a[2]);
        }
        if (cmd.equals("vmoveto")) {   // agent N ticks | tree ticks | block x y z ticks | mob ticks | button NAME ticks | ground dx dz ticks
            String[] a = arg.split(" ");
            int ticks = Integer.parseInt(a[a.length - 1]);
            String pillId = a[0].equals("pill") ? String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length - 1)) : null;
            if (t == 0) {
                pendingPill = null;
                Commander c = Commander.instance;
                float[] s = null;
                if (a[0].equals("agent")) { c.vMoveToAgent(Integer.parseInt(a[1]), ticks); event("vmoveto " + arg); return false; }   // (the aim follows the agent)
                else if (a[0].equals("block")) s = c.toScreen(Integer.parseInt(a[1]) + 0.5, Integer.parseInt(a[2]) + 0.9, Integer.parseInt(a[3]) + 0.5);
                else if (a[0].equals("ground")) {
                    int gx = MathHelper.floor_double(c.px) + Integer.parseInt(a[1]), gz = MathHelper.floor_double(c.pz) + Integer.parseInt(a[2]);
                    int gy = mc.theWorld.getHeightValue(gx, gz);
                    while (gy > 1 && (mc.theWorld.getBlock(gx, gy - 1, gz).getMaterial() == net.minecraft.block.material.Material.leaves || !mc.theWorld.getBlock(gx, gy - 1, gz).getMaterial().isSolid())) gy--;
                    s = c.toScreen(gx + 0.5, gy, gz + 0.5);
                } else if (a[0].equals("tree")) {
                    WorldServer ws = MinecraftServer.getServer().worldServers[0];
                    int[] best = null;
                    double bd = 1e9;
                    for (int[] tr : Crew.trunks(ws, MathHelper.floor_double(c.px), MathHelper.floor_double(c.py), MathHelper.floor_double(c.pz), 32)) {
                        double d = Math.hypot(tr[0] - c.px, tr[2] - c.pz);
                        if (tr[3] >= 3 && d < bd && !Fell.tree(mc.theWorld, tr[0], tr[1], tr[2]).isEmpty()) { bd = d; best = tr; }
                    }
                    if (best != null) s = c.toScreen(best[0] + 0.5, best[1] + best[3] + 0.5, best[2] + 0.5);   // over the crown, where a click from above lands on its leaves
                } else if (a[0].equals("mob")) {
                    double bd = 1e9;
                    for (Object o : mc.theWorld.loadedEntityList)
                        if (o instanceof net.minecraft.entity.monster.IMob) {
                            net.minecraft.entity.Entity en = (net.minecraft.entity.Entity) o;
                            double d = Math.hypot(en.posX - c.px, en.posZ - c.pz);
                            if (d < bd) { bd = d; s = c.toScreen(en.posX, en.boundingBox.minY + en.height * 0.6, en.posZ); }
                        }
                } else if (a[0].equals("pill")) {   // an exact, unique panel ID; never continue to a click if it is absent
                    if (!(mc.currentScreen instanceof Commander.Screen)) { abortToQuit("Commander panel absent for " + arg); return true; }
                    int scale = new net.minecraft.client.gui.ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
                    Object[] hit;
                    try { hit = CaptureHitTarget.unique(new ArrayList<Object[]>(((Commander.Screen) mc.currentScreen).hits), "pill", pillId); }
                    catch (IllegalArgumentException ex) { abortToQuit(ex.getMessage()); return true; }
                    s = new float[] { ((Integer) hit[3] + (Integer) hit[5]) / 2f * scale,
                            mc.displayHeight - ((Integer) hit[4] + (Integer) hit[6]) / 2f * scale };
                    pendingPill = pillId;
                    pendingPillAtMs = System.currentTimeMillis();
                } else if (a[0].equals("button") && mc.currentScreen instanceof Commander.Screen) {
                    int scale = new net.minecraft.client.gui.ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
                    for (Object o : ((Commander.Screen) mc.currentScreen).buttons()) {
                        net.minecraft.client.gui.GuiButton bt = (net.minecraft.client.gui.GuiButton) o;
                        if (bt.displayString.replace("§e", "").startsWith(a[1])) s = new float[] { (bt.xPosition + 14) * scale, mc.displayHeight - (bt.yPosition + 10) * scale };
                    }
                }
                event("vmoveto " + arg + ": " + (s == null ? "nothing on the screen" : (int) s[0] + " " + (int) s[1]));
                if (s != null) c.vMove(s[0], s[1], ticks);
            }
            if (pillId != null) {
                if (!(mc.currentScreen instanceof Commander.Screen) || ((Commander.Screen) mc.currentScreen).routinesOpen) {
                    abortToQuit("Commander pill changed before click: " + pillId); return true;
                }
                Commander c = Commander.instance;
                int scale = new net.minecraft.client.gui.ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
                boolean reached;
                try { reached = CaptureHitTarget.reached(((Commander.Screen) mc.currentScreen).hits, "pill", pillId,
                        c.curX(), c.curY(), scale, mc.displayHeight, c.vT, c.vLen); }
                catch (RuntimeException ex) { abortToQuit(ex.getMessage()); return true; }
                if (!reached && System.currentTimeMillis() - pendingPillAtMs > 5000) {
                    abortToQuit("scripted cursor did not reach pill: " + pillId); return true;
                }
                return t >= ticks && reached;
            }
            return t >= ticks;
        }
        if (cmd.equals("vdown")) {
            if (pendingPill != null) { abortToQuit("scripted pill requires a verified vclick: " + pendingPill); return true; }
            Commander.instance.vButton(num(arg, 0), true); return true;
        }
        if (cmd.equals("vup")) { Commander.instance.vButton(num(arg, 0), false); return true; }
        if (cmd.equals("vclick")) {   // b: pressed, then let go two ticks later
            if (t == 0) {
                if (pendingPill != null) {
                    if (!(mc.currentScreen instanceof Commander.Screen) || ((Commander.Screen) mc.currentScreen).routinesOpen) {
                        abortToQuit("Commander pill changed before click: " + pendingPill); return true;
                    }
                    Commander c = Commander.instance;
                    int scale = new net.minecraft.client.gui.ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
                    boolean reached;
                    try { reached = CaptureHitTarget.reached(((Commander.Screen) mc.currentScreen).hits, "pill", pendingPill,
                            c.curX(), c.curY(), scale, mc.displayHeight, c.vT, c.vLen); }
                    catch (RuntimeException ex) { abortToQuit(ex.getMessage()); return true; }
                    if (!reached || num(arg, 0) != 0) { abortToQuit("scripted click missed pill: " + pendingPill); return true; }
                }
                Commander.instance.vButton(num(arg, 0), true);
            }
            if (t == 2) { Commander.instance.vButton(num(arg, 0), false); pendingPill = null; return true; }
            return false;
        }
        if (cmd.equals("vmod")) {   // ctrl|shift|alt on|off
            String[] a = arg.split(" ");
            boolean v = a[1].equals("on");
            if (a[0].equals("ctrl")) Commander.instance.vCtrl = v; else if (a[0].equals("shift")) Commander.instance.vShift = v; else Commander.instance.vAlt = v;
            return true;
        }
        if (cmd.equals("vtype")) {   // text typed into the commander's chat, a letter a tick, then Enter
            if (!(mc.currentScreen instanceof Commander.Screen)) return true;
            Commander.Screen s = (Commander.Screen) mc.currentScreen;
            if (t == 0) { s.chat.setFocused(true); s.chat.setText(""); }
            if (t < arg.length()) { s.chat.writeText(arg.substring(t, t + 1)); return false; }
            s.typed(org.lwjgl.input.Keyboard.KEY_RETURN);
            return true;
        }
        if (cmd.equals("vwheel")) { Commander.instance.vWheel(num(arg, 1) * 120); return true; }
        if (cmd.equals("vkey")) {   // a key typed into the commander's screen (by its LWJGL name: M, B, K_1...)
            int code = org.lwjgl.input.Keyboard.getKeyIndex(arg.toUpperCase());
            if (mc.currentScreen instanceof Commander.Screen) ((Commander.Screen) mc.currentScreen).typed(code);
            return true;
        }
        if (cmd.equals("cmdpaint")) {   // dig|build here dx dz w d depth [material]: a stroke as the mouse paints it (beside the camera's point, on the ground)
            String[] a = arg.split(" ");
            Commander c = Commander.instance;
            boolean build = a[0].equals("build");
            int x0 = MathHelper.floor_double(c.px) + Integer.parseInt(a[2]), z0 = MathHelper.floor_double(c.pz) + Integer.parseInt(a[3]);
            int y = mc.theWorld.getHeightValue(x0, z0) - 1;
            while (y > 1 && (mc.theWorld.getBlock(x0, y, z0).getMaterial() == net.minecraft.block.material.Material.leaves || !mc.theWorld.getBlock(x0, y, z0).getMaterial().isSolid())) y--;
            if (build) y++;
            c.depth = Integer.parseInt(a[6]);
            if (a.length > 7) for (int i = 0; i < Commander.PAINT_MATERIALS.length; i++) if (Commander.PAINT_MATERIALS[i].equals(a[7])) c.paintMat = i;
            java.util.List<int[]> cells = c.strokeCells(new int[] { x0, y, z0 }, new int[] { x0 + Integer.parseInt(a[4]) - 1, y, z0 + Integer.parseInt(a[5]) - 1 }, build);
            Orders.designate(c.picked(), build, cells, Commander.PAINT_MATERIALS[c.paintMat]);
            event("cmdpaint " + arg + ": " + cells.size() + " blocks from " + x0 + " " + y + " " + z0);
            return true;
        }
        if (cmd.equals("cmdclickchest")) {   // [q]: a right click on the crew's chest
            int[] ch = Crew.instance == null ? null : Crew.instance.chest;
            event("cmdclickchest: " + (ch == null ? "no chest" : ch[0] + " " + ch[1] + " " + ch[2]));
            if (ch != null) Commander.instance.orderBlock(ch[0], ch[1], ch[2], 1, arg.equals("q"));
            return true;
        }
        if (cmd.equals("cmdkey")) {   // a panel button or a key, by its word: stop, ai, guard x y z, craft <item> <n>, smelt <item> <n>, build <0-3> <material 0-1> <turn> x y z
            String[] a = arg.split(" ");
            Commander c = Commander.instance;
            if (a[0].equals("stop")) Orders.give(c.picked(), Orders.Kind.STOP, 0, 0, 0, -1, false);
            else if (a[0].equals("ai")) Orders.give(c.picked(), Orders.Kind.RELEASE, 0, 0, 0, -1, false);
            else if (a[0].equals("guard")) Orders.give(c.picked(), Orders.Kind.GUARD, Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]), -1, false);
            else if (a[0].equals("craft")) Orders.give(c.picked(), Orders.Kind.CRAFT, 0, 0, 0, -1, false, a[1], Integer.parseInt(a[2]));
            else if (a[0].equals("smelt")) Orders.give(new int[] { c.picked()[0] }, Orders.Kind.SMELT, 0, 0, 0, -1, false, a[1], Integer.parseInt(a[2]));
            else if (a[0].equals("build")) {
                c.blueprint = Integer.parseInt(a[1]); c.material = Integer.parseInt(a[2]); c.turn = Integer.parseInt(a[3]);
                int bx, by, bz;
                if (a[4].equals("here")) {   // beside the camera's point, on the ground
                    bx = MathHelper.floor_double(c.px) + Integer.parseInt(a[5]); bz = MathHelper.floor_double(c.pz) + Integer.parseInt(a[6]);
                    by = mc.theWorld.getHeightValue(bx, bz);
                    while (by > 1 && !mc.theWorld.getBlock(bx, by - 1, bz).getMaterial().isSolid() || by > 1 && mc.theWorld.getBlock(bx, by - 1, bz).getMaterial() == net.minecraft.block.material.Material.leaves
                            || by > 1 && mc.theWorld.getBlock(bx, by - 1, bz).getMaterial() == net.minecraft.block.material.Material.wood) by--;   // the ground, not a crown
                } else { bx = Integer.parseInt(a[4]); by = Integer.parseInt(a[5]); bz = Integer.parseInt(a[6]); }
                c.build(new int[] { bx, by, bz });
                c.blueprint = -1;
            }
            return true;
        }
        if (cmd.equals("cmdattack")) {   // a right click on the nearest hostile mob to the picked ones
            net.minecraft.entity.Entity best = null;
            double bd = 1e9;
            for (Object o : mc.theWorld.loadedEntityList)
                if (o instanceof net.minecraft.entity.monster.IMob && o instanceof net.minecraft.entity.EntityLiving) {
                    net.minecraft.entity.Entity en = (net.minecraft.entity.Entity) o;
                    double d = en.getDistanceToEntity(mc.thePlayer);
                    if (d < bd) { bd = d; best = en; }
                }
            event("cmdattack: " + (best == null ? "no mob" : best.getCommandSenderName() + " at " + (int) best.posX + " " + (int) best.posY + " " + (int) best.posZ));
            if (best != null) Commander.instance.order(best, arg.equals("q"));
            return true;
        }
        if (cmd.equals("mark")) { event("mark " + arg); return true; }
        if (cmd.equals("clearchat")) { mc.ingameGUI.getChatGUI().clearChatMessages(); return true; }
        if (cmd.equals("hud")) { mc.gameSettings.hideGUI = arg.equals("off"); return true; }
        if (cmd.equals("view")) { mc.gameSettings.thirdPersonView = num(arg, 0); return true; }
        if (cmd.equals("fov")) { mc.gameSettings.fovSetting = CaptureFov.setting(Double.parseDouble(arg)); return true; }
        if (cmd.equals("cmd")) { mc.thePlayer.sendChatMessage(arg); return true; }
        if (cmd.equals("look")) {   // yaw pitch ticks (yaw relative to now when it starts with ~)
            if (t == 0) {
                String[] a = arg.split(" ");
                lookYaw0 = mc.thePlayer.rotationYaw;
                lookPitch0 = mc.thePlayer.rotationPitch;
                lookYaw1 = a[0].startsWith("~") ? lookYaw0 + Float.parseFloat(a[0].substring(1)) : Float.parseFloat(a[0]);
                lookPitch1 = Float.parseFloat(a[1]);
                lookLen = Integer.parseInt(a[2]);
                lookT = 0;
            }
            return lookT >= lookLen;
        }
        if (cmd.equals("setup")) {
            if (t == 0) { setupRadius = num(arg, 96); final String name = mc.thePlayer.getCommandSenderName(); serverQueue.add(new Runnable() { public void run() { setup(name); } }); }
            return t >= 10;
        }
        if (cmd.equals("time")) {
            final long time = Long.parseLong(arg);
            serverQueue.add(new Runnable() { public void run() {
                for (WorldServer w : MinecraftServer.getServer().worldServers) w.setWorldTime(time);
            } });
            return true;
        }
        if (cmd.equals("ask")) return ask(arg, 60);
        if (cmd.equals("askgo")) {   // ask and close the window at once: the player watches what happens
            boolean d = ask(arg, 0);
            if (step >= 2) { if (mc.currentScreen instanceof GuiGuide) mc.displayGuiScreen(null); return true; }
            return d;
        }
        if (cmd.equals("window")) {   // the guide's window open for a while (the answers in it)
            if (t == 0) { mc.displayGuiScreen(new GuiGuide()); return false; }
            if (t >= num(arg, 40)) { if (mc.currentScreen instanceof GuiGuide) mc.displayGuiScreen(null); return true; }
            return false;
        }
        if (cmd.equals("yes")) {   // when the guide asks to build: "да"
            if (yesState == 0) {
                if (t > 20 * 90) { event("yes: no question came"); return true; }
                if (!said("«да»|\"да\"|да или нет|yes or no")) return false;
                yesState = 1;
                t = 0;
                return false;
            }
            if (yesState == 1) {
                if (t < num(arg, 30)) return false;
                yesState = 2;
                step = 0;
                t = 0;
                return false;
            }
            return ask("да", 10);
        }
        if (cmd.equals("until")) {   // a guide's message matching the pattern (since the last ask)
            if (t > 20 * 420) { event("until: gave up waiting for " + arg); return true; }
            return said(arg);
        }
        if (cmd.equals("idle")) {   // nothing driving, no window, no plan, for some ticks
            boolean busy = Pilot.instance.task != null || mc.currentScreen != null || !Ghosts.all.isEmpty();
            if (busy) t = -1;
            return t >= num(arg, 20);
        }
        if (cmd.equals("screenshot")) {
            net.minecraft.util.ScreenShotHelper.saveScreenshot(mc.mcDataDir, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            return true;
        }
        if (cmd.equals("diag")) {   // what OptiFine's shaders say about themselves
            try {
                Class<?> s = Class.forName("shadersmod.client.Shaders");
                for (Field f : s.getDeclaredFields()) {
                    if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    String n = f.getName().toLowerCase();
                    if (!(n.contains("shaderpack") || n.contains("isinit") || n.contains("currentshader") || n.contains("hasglgen") || n.contains("enabled") || n.contains("shadersdir"))) continue;
                    f.setAccessible(true);
                    event("diag Shaders." + f.getName() + " = " + f.get(null));
                }
            } catch (Throwable e) {
                event("diag: " + e);
            }
            try {
                Class<?> c = Class.forName("Config");
                event("diag Config " + c.getMethod("getVersion").invoke(null));
            } catch (Throwable e) {
                event("diag Config: " + e);
            }
            return true;
        }
        if (cmd.equals("screen")) {
            if (arg.equals("video")) mc.displayGuiScreen(new net.minecraft.client.gui.GuiVideoSettings(null, mc.gameSettings));
            else mc.displayGuiScreen(null);
            return true;
        }
        if (cmd.equals("rec")) { Recorder.start(arg); return true; }
        if (cmd.equals("recstop")) { Recorder.stop(); return true; }
        if (cmd.equals("crew")) {   // an order for the crew straight from the script (the guide's tool does the same)
            String[] a = arg.split(" ");
            Crew.order(mc.thePlayer.getCommandSenderName(), a[0], a.length > 1 ? num(a[1], 0) : 0, Origin.SCRIPT);
            return true;
        }
        if (cmd.equals("shot") || cmd.equals("shots")) {   // the second camera: shot <kind> <len> [args] | shots <kind> <total> <len>
            String[] a = arg.split(" ");
            Cam c = Cam.get();
            boolean loop = cmd.equals("shots");
            int total = Integer.parseInt(a[1]), len = loop ? Integer.parseInt(a[2]) : total;
            if (t == 0) { shotWait = 0; shotDone = false; }
            if (loop && t >= total) return true;
            if (!loop && shotDone && c.done()) return true;
            if (shotDone && !c.done()) return false;
            if (shotDone && loop) shotDone = false;
            Cam.Shot s = null;
            String kind = a[0];
            if (loop && kind.equals("follow") && c.shot instanceof Cam.Follow && ((Cam.Follow) c.shot).lost < 3 && c.time() >= c.shot.len - 1) {   // the same cameraman goes on: no cut
                c.shot.len += len;
                shotDone = true;
                return false;
            }
            int last = c.shot != null && c.shot.name.matches(".*\\d") ? c.shot.name.charAt(c.shot.name.length() - 1) - '0' : 0;
            if (kind.equals("chop")) s = c.planChop(last);
            else if (kind.equals("place")) s = c.planPlace(last);
            else if (kind.equals("follow")) s = c.planFollow(a.length > (loop ? 3 : 2) ? Integer.parseInt(a[loop ? 3 : 2]) : 1 + rnd.nextInt(3), len);
            else if (kind.equals("orbit")) {   // shot orbit <len> <site|chest|table|front|here> R H a0 a1
                double base = 0;
                if (a[2].equals("cheer") && Crew.instance != null && !Crew.instance.agents.isEmpty() && Crew.front != null) {   // the crew in its line, from the side it faces
                    double sx = 0, sy = 0, sz = 0;
                    for (Agent g : Crew.instance.agents) { sx += g.posX; sy += Math.floor(g.posY + 0.01); sz += g.posZ; }
                    int n = Crew.instance.agents.size();
                    ax = sx / n; ay = sy / n; az = sz / n;
                    base = Math.toDegrees(Math.atan2(Crew.front[0] - ax, Crew.front[2] - az));
                } else anchor(a[2]);
                s = c.planOrbit("orbit " + a[2], new Cam.P(ax, ay, az), Double.parseDouble(a[3]), Double.parseDouble(a[4]), base + Double.parseDouble(a[5]), base + Double.parseDouble(a[6]), len);
            }
            else if (kind.equals("crane")) {   // shot crane <len> <anchor> dx0 dy0 dz0 dx1 dy1 dz1 [lx ly lz]: from near to far, eased, looking at the anchor
                anchor(a[2]);
                Cam.P from = new Cam.P(ax + Double.parseDouble(a[3]), ay + Double.parseDouble(a[4]), az + Double.parseDouble(a[5]));
                Cam.P to = new Cam.P(ax + Double.parseDouble(a[6]), ay + Double.parseDouble(a[7]), az + Double.parseDouble(a[8]));
                Cam.P look = a.length > 11 ? new Cam.P(ax + Double.parseDouble(a[9]), ay + Double.parseDouble(a[10]), az + Double.parseDouble(a[11])) : new Cam.P(ax, ay + 1, az);
                Cam.Dolly d = c.new Dolly("crane " + a[2], from, to, look, 0, len);
                for (int up = 0; up < 6 && !c.pathOk(d, look); up++) {   // raise the whole path until it is clear
                    from = new Cam.P(from.x, from.y + 1, from.z);
                    to = new Cam.P(to.x, to.y + 1, to.z);
                    d = c.new Dolly("crane " + a[2], from, to, look, 0, len);
                }
                s = d;
            }
            if (s == null && (kind.equals("chop") || kind.equals("place")) && ++shotWait > 30) s = c.planFollow(1 + rnd.nextInt(3), len);
            if (s == null) { if (++shotWait > 200) { event("shot: none for " + kind); return true; } return false; }
            if (s.len == 50 || loop) s.len = len;
            c.start(s);
            shotDone = true;
            shotWait = 0;
            return false;
        }
        if (cmd.equals("shotoff")) { if (Cam.instance != null) Cam.instance.release(); return true; }
        if (cmd.equals("open")) {   // an existing world by its folder (the next episode goes on in it)
            if (!(mc.currentScreen instanceof GuiMainMenu)) { t = -1; return false; }
            mc.launchIntegratedServer(arg, arg, null);
            event("open world " + arg);
            return true;
        }
        if (cmd.equals("pchopgo")) { pchopBg = true; pchop = null; pstep = -1; return true; }
        if (cmd.equals("pwait")) return !pchopBg || t > 20 * 90;
        if (cmd.equals("pchop")) {   // the player chops the nearest tree, as a player: up to it, the logs bottom up, only what is seen
            if (t == 0) { pchop = trunkNear(); event("pchop: " + (pchop == null ? "no tree" : pchop[0] + " " + pchop[1] + " " + pchop[2])); if (pchop == null) return true; pstep = 0; }
            if (Pilot.instance.task != null) return false;
            if (pstep == 0) {
                com.google.gson.JsonObject m = Pilot.msg("goto");
                m.addProperty("x", pchop[0]); m.addProperty("y", pchop[1]); m.addProperty("z", pchop[2]); m.addProperty("reach", 2.2);
                Pilot.instance.inbox.add(m);
                pstep = 1;
                return false;
            }
            for (int y = pchop[1]; y < pchop[1] + 8; y++) {
                net.minecraft.block.Block b = mc.theWorld.getBlock(pchop[0], y, pchop[2]);
                if (b == net.minecraft.init.Blocks.log || b == net.minecraft.init.Blocks.log2) {
                    com.google.gson.JsonObject m = Pilot.msg("dig");
                    m.addProperty("x", pchop[0]); m.addProperty("y", y); m.addProperty("z", pchop[2]);
                    Pilot.instance.inbox.add(m);
                    return t > 20 * 60;
                }
            }
            return true;
        }
        if (cmd.equals("pwalk")) {   // the player walks to an anchor (mine, site, chest, table)
            if (t == 0) {
                anchor(arg);
                if (arg.equals("mine") && Day1.mine != null) { int[] m = Day1.mine; ax = m[0] + 0.5 - m[3] * 2.5 + (m[3] == 0 ? 1.5 : 0); ay = m[1]; az = m[2] + 0.5 - m[4] * 2.5 + (m[4] == 0 ? 1.5 : 0); }
                com.google.gson.JsonObject m = Pilot.msg("goto");
                m.addProperty("x", (int) Math.floor(ax)); m.addProperty("y", (int) Math.floor(ay)); m.addProperty("z", (int) Math.floor(az)); m.addProperty("reach", 1.0);
                Pilot.instance.inbox.add(m);
                return false;
            }
            return Pilot.instance.task == null && t > 5 || t > 20 * 40;
        }
        if (cmd.equals("ptrack")) {   // N ticks: the player's eyes follow agent N (softly)
            String[] a = arg.split(" ");
            Agent g = Cam.get().clientAgent(Integer.parseInt(a[0]));
            if (g != null) {
                double dx = g.posX - mc.thePlayer.posX, dy = g.posY + 1.5 - (mc.thePlayer.posY), dz = g.posZ - mc.thePlayer.posZ;
                float ty = (float) Math.toDegrees(Math.atan2(-dx, dz)), tp = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
                mc.thePlayer.rotationYaw += MathHelper.wrapAngleTo180_float(ty - mc.thePlayer.rotationYaw) * 0.15f;
                mc.thePlayer.rotationPitch += (tp - mc.thePlayer.rotationPitch) * 0.15f;
            }
            return t >= Integer.parseInt(a[1]);
        }
        if (cmd.equals("pface")) {   // the player turns to the mine's mouth (or the site)
            double[] tgt = arg.equals("mine") && Day1.mine != null ? new double[] { Day1.mine[0] + Day1.mine[3] * 3 + 0.5, Day1.mine[1] - 2, Day1.mine[2] + Day1.mine[4] * 3 + 0.5 } : null;
            if (tgt == null) return true;
            double dx = tgt[0] - mc.thePlayer.posX, dz = tgt[2] - mc.thePlayer.posZ;
            mc.thePlayer.rotationYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            mc.thePlayer.rotationPitch = 25;
            return true;
        }
        if (cmd.equals("crewgate")) { Crew.gated = arg.equals("on"); return true; }
        if (cmd.equals("crewskip")) { for (String k : arg.split(" ")) Crew.skip.add(k); return true; }
        if (cmd.equals("crewgo")) { for (String k : arg.split(" ")) Crew.allowed.add(k); return true; }
        if (cmd.equals("cine")) { cine = arg.equals("on"); return true; }
        if (cmd.equals("crewat")) {   // until a phase whose name starts so is running (or done)
            String[] a = arg.split(" ");
            if (t > 20 * (a.length > 1 ? num(a[1], 600) : 600)) { event("crewat: gave up"); return true; }
            return Crew.started.contains(a[0]);
        }
        if (cmd.equals("crewplan") || cmd.equals("crewplanfallback")) {   // goals (a|b|c) straight, or only if the guide gave none
            if (cmd.equals("crewplanfallback") && t < 20 * 4) return false;
            if (cmd.equals("crewplanfallback") && Compiler.last != null) { event("crewplanfallback: the guide gave goals"); return true; }
            final String goals = arg.replace("|", "\n"), who = mc.thePlayer.getCommandSenderName();
            final Origin o = cmd.equals("crewplan") ? Origin.SCRIPT : Origin.FALLBACK;
            event(cmd + ": " + arg);
            new Thread(() -> event("crewplan: " + Compiler.plan(who, goals, o)), "crewplan").start();
            return true;
        }
        if (cmd.equals("crewfallback")) {   // the orders the guide did not give (a|b|c), in this order
            if (t < 20 * 4) return false;
            java.util.Set<String> had = Crew.instance == null ? new java.util.HashSet<String>() : Crew.instance.ordered;
            event("crewfallback: the guide ordered " + had);
            for (String o : arg.split("\\|")) {
                String[] a = o.trim().split(" ");
                boolean done = false;
                for (String h : had) if (a[0].startsWith(h.substring(0, Math.min(3, h.length())))) done = true;
                if (!done) {
                    event("crewfallback: the script orders " + o.trim());
                    Crew.order(mc.thePlayer.getCommandSenderName(), a[0], a.length > 1 ? num(a[1], 0) : 0, Origin.FALLBACK);
                }
            }
            return true;
        }
        if (cmd.equals("crewwait")) {   // until the crew has finished a phase whose name starts so (or the queue is empty)
            if (t > 20 * num(arg.contains(" ") ? arg.substring(arg.indexOf(' ') + 1) : "", 600)) { event("crewwait: gave up"); return true; }
            String want = arg.contains(" ") ? arg.substring(0, arg.indexOf(' ')) : arg;
            return Crew.finished.contains(want) && t > 5;
        }
        if (cmd.equals("fly")) {
            mc.thePlayer.sendChatMessage("/gamemode 1");
            flying = true;
            return true;
        }
        if (cmd.equals("anchor")) { anchor(arg); return true; }
        if (cmd.equals("campos") || cmd.equals("cam") || cmd.equals("camat") || cmd.equals("orbit") || cmd.equals("chase")) { if (t == 0) rayFrom = null; }
        if (cmd.equals("campos")) {   // dx dy dz yaw pitch, at once (a cut)
            double[] v = nums(arg);
            camMode = 1;
            cx0 = cx1 = ax + v[0]; cy0 = cy1 = ay + v[1]; cz0 = cz1 = az + v[2];
            yaw0 = yaw1 = (float) v[3]; pitch0 = pitch1 = (float) v[4];
            camT = camLen = 1;
            lookAgent = 0;
            lookPoint = null;
            return true;
        }
        if (cmd.equals("cam") || cmd.equals("camat")) {   // cam dx dy dz yaw pitch ticks | camat dx dy dz ticks agent N | camat dx dy dz ticks point px py pz
            String[] a = arg.split(" ");
            if (t == 0) {
                camMode = 1;
                cx0 = mc.thePlayer.posX; cy0 = mc.thePlayer.posY; cz0 = mc.thePlayer.posZ;
                yaw0 = mc.thePlayer.rotationYaw; pitch0 = mc.thePlayer.rotationPitch;
                cx1 = ax + Double.parseDouble(a[0]); cy1 = ay + Double.parseDouble(a[1]); cz1 = az + Double.parseDouble(a[2]);
                lookAgent = 0;
                lookPoint = null;
                if (cmd.equals("cam")) {
                    yaw1 = yaw0 + MathHelper.wrapAngleTo180_float(Float.parseFloat(a[3]) - yaw0);
                    pitch1 = Float.parseFloat(a[4]);
                    camLen = Integer.parseInt(a[5]);
                } else {
                    camLen = Integer.parseInt(a[3]);
                    if (a[4].equals("agent")) lookAgent = Integer.parseInt(a[5]);
                    else lookPoint = new double[] { ax + Double.parseDouble(a[5]), ay + Double.parseDouble(a[6]), az + Double.parseDouble(a[7]) };
                }
                camT = 0;
            }
            return camT >= camLen;
        }
        if (cmd.equals("orbit")) {   // R H a0 a1 ticks [agent N]: around the anchor (or an agent), looking at it
            String[] a = arg.split(" ");
            if (t == 0) {
                camMode = 2;
                orR = Double.parseDouble(a[0]); orH = Double.parseDouble(a[1]); orA0 = Double.parseDouble(a[2]); orA1 = Double.parseDouble(a[3]);
                camLen = Integer.parseInt(a[4]);
                camT = 0;
                lookAgent = a.length > 6 && a[5].equals("agent") ? Integer.parseInt(a[6]) : 0;
            }
            return camT >= camLen;
        }
        if (cmd.equals("chase")) {   // N back up side ticks: behind an agent, following it
            String[] a = arg.split(" ");
            if (t == 0) {
                camMode = 3;
                lookAgent = Integer.parseInt(a[0]);
                chBack = Double.parseDouble(a[1]); chUp = Double.parseDouble(a[2]); chSide = Double.parseDouble(a[3]);
                camLen = Integer.parseInt(a[4]);
                camT = 0;
            }
            return camT >= camLen;
        }
        if (cmd.equals("camoff")) { camMode = 0; return true; }
        if (cmd.equals("chopcam")) {   // total: a still camera beside an agent at a trunk, low, under the leaves; next agent when it moves on
            int total = num(arg, 600);
            if (t == 0 || autoT >= 150 || chopAgent == null || chopAgent.ticksExisted - chopAgent.actAt > 30) chopShot();
            autoT++;
            return t >= total;
        }
        if (cmd.equals("autocam")) {   // total [shot]: cut to whoever of the crew is working, orbit them a little; chase someone if nobody works
            String[] a = arg.split(" ");
            int total = Integer.parseInt(a[0]), shot = a.length > 1 ? Integer.parseInt(a[1]) : 110;
            autoLen = shot;
            if (t == 0 || autoT >= shot || Cam.get().done()) autoShot();
            autoT++;
            return t >= total;
        }
        if (cmd.equals("quit")) {
            if (Commander.someoneThere(60)) {   // someone plays in the window: the script does not close it under them
                if (t % 200 == 0) event("quit waits: someone is using the game (closes a minute after the last input)");
                return false;
            }
            if (!quitQueued) {
                Recorder.stop();
                final MinecraftServer server = mc.getIntegratedServer();
                quitQueued = true;
                if (server == null) quitPrepared = true;
                else {
                    event("quit: waiting for recording close and integrated-server save/flush");
                    serverQueue.add(new Runnable() { public void run() {
                        quitServerThread = Thread.currentThread();
                        try {
                            String result = WorldRec.stop();
                            event("quit: WorldRec close acknowledged: " + result);
                            if (server.worldServers != null && server.worldServers.length > 0)
                                Ledger.save(server.worldServers[0].getSaveHandler().getWorldDirectory());
                            event(Ledger.report());
                        } catch (Exception ex) {
                            event("quit preparation failed: " + ex);
                        } finally {
                            server.initiateShutdown();
                            quitPrepared = true;
                        }
                    } });
                }
            }
            // Wait for the server thread, including its region I/O, instead of relying on isServerRunning.
            if (!quitPrepared || (quitServerThread != null && quitServerThread.isAlive())) {
                if (t % 200 == 0) event("quit: still waiting for server termination; not forcing exit");
                return false;
            }
            event("quit: integrated-server thread terminated");
            event("quit");
            mc.shutdown();
            return true;
        }
        event("unknown command " + cmd);
        return true;
    }

    /** a guide's message since the last question matches */
    boolean said(String regex) {
        Pattern p = Pattern.compile(regex);
        for (int i = seen; i < GuiGuide.history.size(); i++) {
            String[] h = GuiGuide.history.get(i);
            if (h[0] == null && p.matcher(h[1]).find()) {
                event("guide said: " + h[1].replace('\n', ' '));
                seen = i + 1;
                return true;
            }
        }
        return false;
    }

    /** opens the window, types the question letter by letter, sends it, waits for the answer and closes the window */
    boolean ask(String q, int hold) {
        if (step == 0) {   // open
            if (mc.currentScreen != null && !(mc.currentScreen instanceof GuiGuide)) { t = -1; return false; }
            if (!(mc.currentScreen instanceof GuiGuide)) mc.displayGuiScreen(new GuiGuide());
            event("ask open: " + q);
            step = 1;
            t = 0;
            return false;
        }
        GuiGuide g = mc.currentScreen instanceof GuiGuide ? (GuiGuide) mc.currentScreen : null;
        if (step == 1) {   // type, a letter every 2 ticks
            if (g == null) { mc.displayGuiScreen(new GuiGuide()); return false; }
            int n = Math.min(q.length(), t / 2);
            g.input.setText(q.substring(0, n));
            if (n < q.length() || t < 2 * q.length() + 12) return false;
            g.input.setText("");
            seen = GuiGuide.history.size() + 1;
            GuiGuide.ask(q);
            event("asked: " + q);
            step = 2;
            t = 0;
            return false;
        }
        if (step == 2) {   // the answer (or a window that took over); a yes gets none right away
            if (q.equals("да")) GuiGuide.waiting = false;
            if (GuiGuide.waiting && t < 20 * 90) return false;
            event("answered");
            step = 3;
            t = 0;
            return false;
        }
        if (t < hold) return false;
        if (mc.currentScreen instanceof GuiGuide) mc.displayGuiScreen(null);
        return true;
    }

    /** the main menu -> singleplayer -> a new world, its name typed -> into it */
    boolean create(String arg) throws Exception {
        String[] a = arg.split("\\|");
        String name = a[0];
        long seed = a.length > 1 ? Long.parseLong(a[1]) : 0;
        if (step == 0) {
            mc.displayGuiScreen(new GuiSelectWorld(mc.currentScreen));
            event("select world");
            step = 1;
            t = 0;
            return false;
        }
        if (step == 1) {
            if (t < 26) return false;
            mc.displayGuiScreen(new GuiCreateWorld(mc.currentScreen));
            event("create world");
            step = 2;
            t = 0;
            return false;
        }
        if (step == 2) {
            GuiTextField f = null;
            for (Field fd : GuiCreateWorld.class.getDeclaredFields())
                if (fd.getType() == GuiTextField.class) { fd.setAccessible(true); f = (GuiTextField) fd.get(mc.currentScreen); break; }
            int n = Math.max(0, Math.min(name.length(), (t - 14) / 2));
            if (f != null && mc.currentScreen instanceof GuiCreateWorld) f.setText(name.substring(0, n));
            if (t < 14 + 2 * name.length() + 24) return false;
            event("launch world " + name + " seed " + seed);
            Class<?> gt = Class.forName("net.minecraft.world.WorldSettings$GameType");   // NOT_SET, SURVIVAL, CREATIVE, ADVENTURE
            WorldSettings ws = null;
            for (java.lang.reflect.Constructor<?> c : WorldSettings.class.getConstructors())
                if (c.getParameterTypes().length == 5) ws = (WorldSettings) c.newInstance(seed, gt.getEnumConstants()[1], true, false, WorldType.DEFAULT);
            ws.enableCommands();
            mc.displayGuiScreen(null);
            mc.launchIntegratedServer(a.length > 2 ? a[2] : "promo-" + System.currentTimeMillis(), name, ws);
            return true;
        }
        return true;
    }

    // ---- the camera: the player as an invisible flying camera (creative), moved every tick ----------------

    boolean flying;
    volatile boolean cine;   // a clean picture: no hotbar, no hand, but the names over the helpers

    @SubscribeEvent
    public void overlay(net.minecraftforge.client.event.RenderGameOverlayEvent.Pre e) {
        if (cine) e.setCanceled(true);
    }

    @SubscribeEvent
    public void playerName(net.minecraftforge.client.event.RenderLivingEvent.Specials.Pre e) {
        if (cine && e.entity == mc.thePlayer) e.setCanceled(true);   // no "Player" over the player
    }

    @SubscribeEvent
    public void hand(net.minecraftforge.client.event.RenderHandEvent e) {
        if (cine) e.setCanceled(true);
    }
    int camMode, camT, camLen, lookAgent;
    double ax, ay, az, cx0, cy0, cz0, cx1, cy1, cz1, orR, orH, orA0, orA1, chBack, chUp, chSide;
    float yaw0, pitch0, yaw1, pitch1;
    double[] lookPoint;
    double camX, camY, camZ;

    static double[] nums(String s) {
        String[] a = s.trim().split("\\s+");
        double[] v = new double[a.length];
        for (int i = 0; i < a.length; i++) v[i] = Double.parseDouble(a[i]);
        return v;
    }

    static double ease(double x) {
        x = Math.max(0, Math.min(1, x));
        return x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2;
    }

    Agent agent(int n) {
        if (mc.theWorld == null) return null;
        for (Object o : mc.theWorld.loadedEntityList)
            if (o instanceof Agent && ((Agent) o).skin() == n && !((Agent) o).isDead) return (Agent) o;
        return null;
    }

    void anchor(String what) {
        String[] a = what.split(" ");
        if (a[0].equals("mine") && Day1.mine != null) { ax = Day1.mine[0] + 0.5; ay = Day1.mine[1]; az = Day1.mine[2] + 0.5; }
        else if (a[0].equals("player")) { ax = mc.thePlayer.posX; ay = mc.thePlayer.boundingBox.minY; az = mc.thePlayer.posZ; }
        else if (a[0].equals("site") && Crew.site != null) { ax = Crew.site[0] + 2.5; ay = Crew.site[1]; az = Crew.site[2] + 2.5; }
        else if (a[0].equals("chest") && Crew.instance != null && Crew.instance.chest != null) { ax = Crew.instance.chest[0] + 0.5; ay = Crew.instance.chest[1]; az = Crew.instance.chest[2] + 0.5; }
        else if (a[0].equals("table") && Crew.instance != null && Crew.instance.table != null) { ax = Crew.instance.table[0] + 0.5; ay = Crew.instance.table[1]; az = Crew.instance.table[2] + 0.5; }
        else if (a[0].equals("front") && Crew.front != null) { ax = Crew.front[0]; ay = Crew.front[1]; az = Crew.front[2]; }
        else if (a[0].equals("agent")) { Agent g = agent(Integer.parseInt(a[1])); if (g != null) { ax = g.posX; ay = g.boundingBox.minY; az = g.posZ; } }
        else { ax = mc.thePlayer.posX; ay = mc.thePlayer.boundingBox.minY; az = mc.thePlayer.posZ; }
        event("anchor " + what + " = " + (int) ax + " " + (int) ay + " " + (int) az);
    }

    Agent chopAgent;

    /** a still shot of an agent at its tree: from its side, a little below its head, the trunk and the agent in frame */
    void chopShot() {
        autoT = 0;
        if (Crew.instance == null) return;
        List<Agent> all = new ArrayList<Agent>(Crew.instance.agents);
        java.util.Collections.shuffle(all, rnd);
        for (Agent s : all) {
            if (s.ticksExisted - s.actAt > 10 || s.actKind != 1 || (s == chopAgent && all.size() > 1 && rnd.nextInt(3) > 0)) continue;
            double dx = s.posX - (s.actX + 0.5), dz = s.posZ - (s.actZ + 0.5), dl = Math.max(0.3, Math.hypot(dx, dz));
            dx /= dl;
            dz /= dl;
            for (int k = 0; k < 8; k++) {
                double ang = Math.toRadians((k % 2 == 0 ? 1 : -1) * (55 + (k / 2) * 18));
                double cx = dx * Math.cos(ang) - dz * Math.sin(ang), cz = dx * Math.sin(ang) + dz * Math.cos(ang);
                double r = 3.6 + rnd.nextDouble() * 0.8;
                double x = s.posX + cx * r, y = s.posY + 1.0, z = s.posZ + cz * r;
                if (!mc.theWorld.isAirBlock(MathHelper.floor_double(x), MathHelper.floor_double(y), MathHelper.floor_double(z))) continue;
                if (mc.theWorld.rayTraceBlocks(net.minecraft.util.Vec3.createVectorHelper(s.posX, s.posY + 1.5, s.posZ), net.minecraft.util.Vec3.createVectorHelper(x, y, z), false, true, false) != null) continue;
                if (mc.theWorld.rayTraceBlocks(net.minecraft.util.Vec3.createVectorHelper(s.posX, s.posY + 0.8, s.posZ), net.minecraft.util.Vec3.createVectorHelper(x, y, z), false, true, false) != null) continue;
                camMode = 1;
                cx0 = cx1 = x; cy0 = cy1 = y; cz0 = cz1 = z;
                // a slow push in, a tenth of the way
                cx1 = x - cx * r * 0.12; cz1 = z - cz * r * 0.12;
                camT = 0;
                camLen = 150;
                lookAgent = 0;
                lookPoint = new double[] { s.posX * 0.65 + (s.actX + 0.5) * 0.35, s.posY + 1.7, s.posZ * 0.65 + (s.actZ + 0.5) * 0.35 };
                rayFrom = new double[] { s.posX, s.posY + 1.5, s.posZ };
                double lx = lookPoint[0] - x, ly = lookPoint[1] - y, lz = lookPoint[2] - z;
                yaw0 = yaw1 = (float) Math.toDegrees(Math.atan2(-lx, lz));
                pitch0 = pitch1 = (float) -Math.toDegrees(Math.atan2(ly, Math.hypot(lx, lz)));
                chopAgent = s;
                event("chopcam: " + s.getCustomNameTag() + " at " + s.actX + " " + s.actY + " " + s.actZ);
                return;
            }
        }
        if (chopAgent == null) autoShot();
    }
    int autoT, autoLast, shotWait, pstep;
    int[] pchop;

    boolean pchopBg;
    int pt, pwaitUntil;

    /** the player's own tree, in the background while the script films */
    void pchopTick() {
        pt++;
        if (pstep == -1) { pchop = trunkNear(); event("pchop: " + (pchop == null ? "no tree" : pchop[0] + " " + pchop[1] + " " + pchop[2])); if (pchop == null) { pchopBg = false; return; } pstep = 0; pt = 0; }
        if (Pilot.instance.task != null || !Pilot.instance.inbox.isEmpty() || pt < pwaitUntil) return;
        if (pstep == 0) {
            com.google.gson.JsonObject m = Pilot.msg("goto");
            m.addProperty("x", pchop[0]); m.addProperty("y", pchop[1]); m.addProperty("z", pchop[2]); m.addProperty("reach", 2.2);
            Pilot.instance.inbox.add(m);
            pstep = 1;
            pwaitUntil = pt + 4;
            return;
        }
        for (int y = pchop[1]; y < pchop[1] + 8; y++) {
            net.minecraft.block.Block b = mc.theWorld.getBlock(pchop[0], y, pchop[2]);
            if (b == net.minecraft.init.Blocks.log || b == net.minecraft.init.Blocks.log2) {
                com.google.gson.JsonObject m = Pilot.msg("dig");
                m.addProperty("x", pchop[0]); m.addProperty("y", y); m.addProperty("z", pchop[2]);
                Pilot.instance.inbox.add(m);
                pwaitUntil = pt + 4;
                if (pt > 20 * 80) pchopBg = false;
                return;
            }
        }
        event("pchop: done");
        pchopBg = false;
    }

    /** the nearest single trunk to the player that no agent works on */
    int[] trunkNear() {
        int px = MathHelper.floor_double(mc.thePlayer.posX), py = MathHelper.floor_double(mc.thePlayer.boundingBox.minY), pz = MathHelper.floor_double(mc.thePlayer.posZ);
        int[] best = null;
        double bd = 1e9;
        for (int x = px - 14; x <= px + 14; x++)
            for (int z = pz - 14; z <= pz + 14; z++)
                for (int y = py - 3; y <= py + 3; y++) {
                    net.minecraft.block.Block b = mc.theWorld.getBlock(x, y, z);
                    if (b != net.minecraft.init.Blocks.log) continue;
                    if (mc.theWorld.getBlock(x, y - 1, z) != net.minecraft.init.Blocks.grass && mc.theWorld.getBlock(x, y - 1, z) != net.minecraft.init.Blocks.dirt) continue;
                    if (Crew.instance != null && Crew.instance.taken.contains(Crew.key(x, y, z))) continue;
                    double d = Math.hypot(x - px, z - pz);
                    if (d < bd) { bd = d; best = new int[] { x, y, z }; }
                }
        if (best != null && Crew.instance != null) Crew.instance.taken.add(Crew.key(best[0], best[1], best[2]));
        return best;
    }
    boolean shotDone;
    double[] rayFrom;   // the camera keeps a clear line from here (an agent's head), when set
    final java.util.Random rnd = new java.util.Random(7);

    /** a new shot of the crew at work: the agent that works now (another than the last one when it can), from its side */
    void autoShot() {
        autoT = 0;
        // through the one camera (Cam: a body of its own posed every frame, the path checked, speed and turn limited)
        Cam c = Cam.get();
        camMode = 0;
        Agent work = null;
        if (Crew.instance != null)
            for (Agent s : Crew.instance.agents)
                if (s.ticksExisted - s.actAt <= 12 && (work == null || s.skin() != autoLast)) work = s;
        Cam.Shot s = null;
        if (work != null && work.actKind == 1) s = c.planChop(autoLast);
        if (s == null && work != null && work.actKind == 3) s = c.planPlace(autoLast);
        if (s == null && work != null) s = c.planFollow(work.skin(), autoLen);
        if (s == null && Crew.instance != null && !Crew.instance.agents.isEmpty())
            s = c.planFollow(Crew.instance.agents.get(rnd.nextInt(Crew.instance.agents.size())).skin(), autoLen);
        if (s != null) {
            if (s.len < autoLen) s.len = autoLen;
            c.start(s);
            autoLast = work != null ? work.skin() : autoLast;
            event("autocam: " + s.name);
        }
    }

    int autoLen = 110;

    /** the old camera's way of picking a shot (the player moved as the camera): kept for old scripts, not used by autocam */
    void autoShotOld() {
        autoT = 0;
        Agent pick = null;
        int best = -1;
        if (Crew.instance != null)
            for (Agent s : Crew.instance.agents) {
                int since = s.ticksExisted - s.actAt;
                if (since > 12) continue;
                int score = (s.skin() != autoLast ? 10 : 0) + (s.actKind == 1 ? 3 : s.actKind == 3 ? 2 : 1);
                if (score > best) { best = score; pick = s; }
            }
        if (pick == null && Crew.instance != null && !Crew.instance.agents.isEmpty()) {   // nobody works: chase one running
            Agent s = Crew.instance.agents.get(rnd.nextInt(Crew.instance.agents.size()));
            camMode = 3;
            rayFrom = null;
            lookAgent = s.skin();
            chBack = 4.0; chUp = 2.2; chSide = rnd.nextBoolean() ? 1.4 : -1.4;
            camT = 0;
            autoLast = s.skin();
            event("autocam: chase " + s.getCustomNameTag());
            return;
        }
        if (pick == null) return;
        autoLast = pick.skin();
        // the middle between its head and the block, the camera on its side, a small sweep
        double mx = (pick.posX + pick.actX + 0.5) / 2, my = (pick.posY + 1.5 + pick.actY + 0.5) / 2, mz = (pick.posZ + pick.actZ + 0.5) / 2;
        double face = Math.atan2(pick.actX + 0.5 - pick.posX, pick.actZ + 0.5 - pick.posZ);   // angle from the agent to the block
        ax = mx; ay = pick.posY; az = mz;   // the camera a little below the head: under the leaves of a five-log tree
        camMode = 2;
        orR = 4.0 + rnd.nextDouble() * 1.0;
        orH = 1.15 + rnd.nextDouble() * 0.35;   // about eye level: under the leaves, the line stays clear
        double sweep = (rnd.nextBoolean() ? 1 : -1) * (22 + rnd.nextInt(16));
        double pref = face + (rnd.nextBoolean() ? 1 : -1) * Math.toRadians(80 + rnd.nextInt(30));
        orA0 = Math.toDegrees(pref);
        for (int k = 0; k < 12; k++) {   // the first side (from the preferred one) with a clear line all along the sweep
            double a0 = Math.toDegrees(pref) + (k % 2 == 0 ? 1 : -1) * (k / 2 + (k % 2)) * 30;
            if (clear(pick, ax, ay, az, a0) && clear(pick, ax, ay, az, a0 + sweep / 2) && clear(pick, ax, ay, az, a0 + sweep)) { orA0 = a0; break; }
        }
        orA1 = orA0 + sweep;        camLen = 130;
        camT = 0;
        lookAgent = 0;
        lookPoint = new double[] { mx, my, mz };
        rayFrom = new double[] { pick.posX, pick.posY + 1.6, pick.posZ };
        event("autocam: " + pick.getCustomNameTag() + " at " + pick.actX + " " + pick.actY + " " + pick.actZ + " kind " + pick.actKind);
    }
    /** the camera at this angle of the orbit sees the agent's head with nothing between */
    boolean clear(Agent s, double cx, double cy, double cz, double deg) {
        double r = Math.toRadians(deg), x = cx + Math.sin(r) * orR, y = cy + orH, z = cz + Math.cos(r) * orR;
        if (mc.theWorld == null) return true;
        if (!mc.theWorld.isAirBlock(net.minecraft.util.MathHelper.floor_double(x), net.minecraft.util.MathHelper.floor_double(y), net.minecraft.util.MathHelper.floor_double(z))) return false;
        return mc.theWorld.rayTraceBlocks(net.minecraft.util.Vec3.createVectorHelper(s.posX, s.posY + 1.6, s.posZ), net.minecraft.util.Vec3.createVectorHelper(x, y, z), false, true, false) == null;
    }

    void camera() {
        if (mc.thePlayer == null || camMode == 0) return;
        net.minecraft.client.entity.EntityClientPlayerMP p = mc.thePlayer;
        if (flying) {
            p.capabilities.isFlying = true;
            p.noClip = true;
        }
        camT++;
        double x, y, z;
        float yaw, pitch;
        double[] look = null;
        if (lookAgent > 0) {
            Agent g = agent(lookAgent);
            if (g != null) look = new double[] { g.posX, g.boundingBox.minY + 1.3, g.posZ };
        } else if (lookPoint != null) look = lookPoint;
        if (camMode == 1) {
            double k = ease((double) camT / camLen);
            x = cx0 + (cx1 - cx0) * k; y = cy0 + (cy1 - cy0) * k; z = cz0 + (cz1 - cz0) * k;
            yaw = (float) (yaw0 + (yaw1 - yaw0) * k); pitch = (float) (pitch0 + (pitch1 - pitch0) * k);
        } else if (camMode == 2) {
            double k = ease((double) camT / camLen), ang = Math.toRadians(orA0 + (orA1 - orA0) * k);
            double ox = ax, oy = ay, oz = az;
            if (lookAgent > 0 && look != null) { ox = look[0]; oy = look[1] - 1.3; oz = look[2]; }
            x = ox + Math.sin(ang) * orR; y = oy + orH; z = oz + Math.cos(ang) * orR;
            if (look == null) look = new double[] { ox, oy + 1.2, oz };
            yaw = p.rotationYaw; pitch = p.rotationPitch;
        } else {
            Agent g = agent(lookAgent);
            if (g == null) return;
            double gy = Math.toRadians(g.renderYawOffset);
            double tx = g.posX + Math.sin(gy) * chBack + Math.cos(gy) * chSide, tz = g.posZ - Math.cos(gy) * chBack + Math.sin(gy) * chSide, ty = g.boundingBox.minY + chUp;
            if (camT == 1) { camX = p.posX; camY = p.posY; camZ = p.posZ; }
            camX += (tx - camX) * 0.14; camY += (ty - camY) * 0.14; camZ += (tz - camZ) * 0.14;
            x = camX; y = camY; z = camZ;
            yaw = p.rotationYaw; pitch = p.rotationPitch;
        }
        if (look != null) {   // turn toward the target, softly
            double dx = look[0] - x, dy = look[1] - y, dz = look[2] - z;
            float ty = (float) Math.toDegrees(Math.atan2(-dx, dz)), tp = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            float s = camMode == 2 || camT < 3 ? 1f : 0.18f;
            yaw = p.rotationYaw + MathHelper.wrapAngleTo180_float(ty - p.rotationYaw) * s;
            pitch = p.rotationPitch + (tp - p.rotationPitch) * s;
        }
        double[] from = rayFrom != null ? rayFrom : lookAgent > 0 ? look : null;
        if (from != null && mc.theWorld != null) {   // never inside the leaves or behind a trunk: come closer on the line
            net.minecraft.util.MovingObjectPosition hit = mc.theWorld.rayTraceBlocks(net.minecraft.util.Vec3.createVectorHelper(from[0], from[1], from[2]),
                    net.minecraft.util.Vec3.createVectorHelper(x, y, z), false, true, false);
            if (hit != null && hit.hitVec != null) {
                double hx = hit.hitVec.xCoord, hy = hit.hitVec.yCoord, hz = hit.hitVec.zCoord, dx = x - from[0], dy = y - from[1], dz = z - from[2];
                double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (l > 0.01) { x = hx - dx / l * 0.35; y = hy - dy / l * 0.35; z = hz - dz / l * 0.35; }
            }
        }
        p.motionX = p.motionY = p.motionZ = 0;
        p.setPosition(x, y, z);
        p.rotationYaw = yaw;
        p.rotationPitch = pitch;
    }

    /** an eased turn of the head, every tick */
    void look() {
        if (lookT >= lookLen || mc.thePlayer == null) return;
        lookT++;
        float x = (float) lookT / lookLen, k = x < 0.5f ? 4 * x * x * x : 1 - (float) Math.pow(-2 * x + 2, 3) / 2;   // ease in-out cubic
        mc.thePlayer.rotationYaw = lookYaw0 + (lookYaw1 - lookYaw0) * k;
        mc.thePlayer.rotationPitch = lookPitch0 + (lookPitch1 - lookPitch0) * k;
    }

    static int num(String s, int d) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return d;
        }
    }

    // ---- on the integrated server's thread ---------------------------------------------------------

    /** a clear morning, and the player put a few steps from a nice tree, looking at its trunk */
    void setup(String name) {
        setup(name, false);
    }

    void setup(String name, boolean retry) {
        MinecraftServer s = MinecraftServer.getServer();
        WorldServer w = s.worldServers[0];
        EntityPlayerMP p = null;
        for (Object o : w.playerEntities) if (((EntityPlayerMP) o).getCommandSenderName().equals(name)) p = (EntityPlayerMP) o;
        if (p == null) { event("setup: no player"); return; }
        w.getWorldInfo().setRaining(false);
        w.getWorldInfo().setRainTime(20 * 60 * 60);
        w.getWorldInfo().setThundering(false);
        w.getWorldInfo().setThunderTime(20 * 60 * 60);
        int px = MathHelper.floor_double(p.posX), pz = MathHelper.floor_double(p.posZ);
        int[] best = null;
        double bestScore = -1e9;
        int R = setupRadius;
        List<int[]> trunks = new ArrayList<int[]>();   // x, y, z, height
        for (int x = px - R; x <= px + R; x++)
            for (int z = pz - R; z <= pz + R; z++) {
                int top = w.getHeightValue(x, z);
                for (int y = top; y > top - 14 && y > 50; y--) {
                    Block log = w.getBlock(x, y, z);
                    if (log != Blocks.log && log != Blocks.log2) continue;
                    if (w.getBlock(x, y - 1, z) == log) continue;
                    Block under = w.getBlock(x, y - 1, z);
                    if (under != Blocks.grass && under != Blocks.dirt) break;
                    int h = 0;
                    while (w.getBlock(x, y + h, z) == log) h++;
                    Block lid = w.getBlock(x, y + h, z);
                    if (h >= 4 && h <= 7 && (lid == Blocks.leaves || lid == Blocks.leaves2)
                            && w.getBlock(x + 1, y, z) != log && w.getBlock(x - 1, y, z) != log && w.getBlock(x, y, z + 1) != log && w.getBlock(x, y, z - 1) != log)
                        trunks.add(new int[] { x, y, z, h });
                    break;
                }
            }
        for (int[] tr : trunks) {
            int x = tr[0], y = tr[1], z = tr[2];
            int forest = 0, crowd = 0;
            for (int[] o : trunks) {
                double d = Math.sqrt((o[0] - x) * (o[0] - x) + (o[2] - z) * (o[2] - z));
                if (d > 0 && d < 14) forest++;
                if (d > 0 && d < 5) crowd++;
            }
            int[][] dirs = { { 6, 0 }, { -6, 0 }, { 0, 6 }, { 0, -6 }, { 4, 4 }, { -4, 4 }, { 4, -4 }, { -4, -4 } };
            for (int[] d : dirs) {
                int sx = x + d[0], sz = z + d[1];
                if (!stand(w, sx, y, sz)) continue;
                boolean clear = true;   // nothing between the player and the trunk
                for (int k = 1; k < 6; k++) {
                    int cx = sx - d[0] * k / 6, cz = sz - d[1] * k / 6;
                    if (cx == x && cz == z) break;
                    if (!stand(w, cx, y, cz)) clear = false;
                }
                if (!clear) continue;
                int open = 0;
                for (int ox = -4; ox <= 4; ox++)
                    for (int oz = -4; oz <= 4; oz++)
                        if (stand(w, sx + ox, y, sz + oz)) open++;
                double score = open * 1.0 + Math.min(forest, 8) * 3 - crowd * 8 - Math.sqrt((x - px) * (x - px) + (z - pz) * (z - pz)) * 0.05;
                if (score > bestScore) { bestScore = score; best = new int[] { x, y, z, sx, sz, tr[3] }; }
            }
        }
        event("setup: " + trunks.size() + " single trunks within " + R);        if (best == null) {   // no tree around: an oak grows four steps ahead, as from a sapling
            int y = w.getTopSolidOrLiquidBlock(px + 4, pz);
            boolean grown = new net.minecraft.world.gen.feature.WorldGenTrees(true, 5, 0, 0, false).generate(w, w.rand, px + 4, y, pz);
            event("setup: no tree, grew one at " + (px + 4) + " " + y + " " + pz + ": " + grown + ", biome " + w.getBiomeGenForCoords(px, pz).biomeName);
            if (grown && !retry) { setup(name, true); }
            return;
        }
        double dx = best[0] - best[3], dz = best[2] - best[4];
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        p.playerNetServerHandler.setPlayerLocation(best[3] + 0.5, best[1], best[4] + 0.5, yaw, 4);
        w.setSpawnLocation(best[3], best[1], best[4]);
        event("setup: tree at " + best[0] + " " + best[1] + " " + best[2] + " (" + best[5] + " logs), player at " + best[3] + " " + best[1] + " " + best[4]
                + ", score " + bestScore);
    }

    /** two blocks of air on a grass or dirt block */
    static boolean stand(WorldServer w, int x, int y, int z) {
        Block b = w.getBlock(x, y - 1, z);
        return (b == Blocks.grass || b == Blocks.dirt) && (w.isAirBlock(x, y, z) || w.getBlock(x, y, z) == Blocks.tallgrass || w.getBlock(x, y, z) == Blocks.red_flower || w.getBlock(x, y, z) == Blocks.yellow_flower)
                && w.isAirBlock(x, y + 1, z);
    }
}
