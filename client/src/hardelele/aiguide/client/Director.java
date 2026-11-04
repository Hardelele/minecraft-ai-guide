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
    static final Charset UTF8 = Charset.forName("UTF-8");

    final Minecraft mc = Minecraft.getMinecraft();
    final List<String> lines = new ArrayList<String>();
    final ConcurrentLinkedQueue<Runnable> serverQueue = new ConcurrentLinkedQueue<Runnable>();
    int pc, t, step, seen, logged, yesState, setupRadius = 96;
    String typed;
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

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
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
        if (cmd.equals("mark")) { event("mark " + arg); return true; }
        if (cmd.equals("clearchat")) { mc.ingameGUI.getChatGUI().clearChatMessages(); return true; }
        if (cmd.equals("hud")) { mc.gameSettings.hideGUI = arg.equals("off"); return true; }
        if (cmd.equals("view")) { mc.gameSettings.thirdPersonView = num(arg, 0); return true; }
        if (cmd.equals("fov")) { mc.gameSettings.fovSetting = Float.parseFloat(arg); return true; }
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
            if (t == 0 || autoT >= shot) autoShot();
            autoT++;
            return t >= total;
        }        if (cmd.equals("quit")) {
            Recorder.stop();
            try {   // the crew's books, for the measure of what was real
                net.minecraft.server.MinecraftServer s = net.minecraft.server.MinecraftServer.getServer();
                if (s != null && s.worldServers != null) Ledger.save(s.worldServers[0].getSaveHandler().getWorldDirectory());
                event(Ledger.report());
            } catch (Exception e) {
                event("ledger: " + e);
            }
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
