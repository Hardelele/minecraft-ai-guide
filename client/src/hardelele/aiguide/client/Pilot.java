package hardelele.aiguide.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLEventChannel;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import io.netty.buffer.Unpooled;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.block.Block;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import org.lwjgl.input.Keyboard;

/**
 * The autopilot: drives the player's own character for the AI Guide server mod. The server says where
 * to go and what to do with which block (JSON on the channel "aiguide"); the autopilot finds a way on
 * foot, turns and walks with the movement keys, jumps where the way goes up, and clicks blocks as the
 * player would. Any movement key held by the player stops it and tells the server.
 */
public class Pilot {
    static final String CHANNEL = "aiguide";
    static final Charset UTF8 = Charset.forName("UTF-8");
    static final int[][] SIDE = { { 0, -1, 0 }, { 0, 1, 0 }, { 0, 0, -1 }, { 0, 0, 1 }, { -1, 0, 0 }, { 1, 0, 0 } };

    static Pilot instance;

    final Minecraft mc = Minecraft.getMinecraft();
    final ConcurrentLinkedQueue<JsonObject> inbox = new ConcurrentLinkedQueue<JsonObject>();
    FMLEventChannel channel;

    /** -Daiguide.client.smooth=true: the head turns eased and slower (for a video), else as before */
    static final boolean SMOOTH = Boolean.getBoolean("aiguide.client.smooth");
    static final float EASE = SMOOTH ? 0.22f : 1f, SPIN = SMOOTH ? 11f : 32f;
    static final int AIM_TICKS = SMOOTH ? 40 : 20;

    String task;   // goto, use, place, dig, look, hit; null when idle
    int tx, ty, tz, slot, ticks, side, replans, checkAt;

    // ---- the crew's line (the player's character as its number 1, PlayerAgent): orders in, how each ended out ----
    /** who gave the task on now: "crew", or null (the guide on the server) */
    volatile String by;
    volatile int crewId;
    /** the crew's last task that ended, and how: ok, failed:why, taken (the player moved), danger, stopped */
    volatile int endedId;
    volatile String endedHow = "";
    /** a card of the crew's is being done with the player's character: a movement key takes it back even between tasks */
    volatile boolean crewDriving;
    /** what took the character back from the crew (taken, danger), for it to read once */
    volatile String crewSignal;
    int nextId;

    /** a task from the crew (any thread): its id, to see how it ended */
    synchronized int order(JsonObject m) {
        int id = ++nextId;
        m.addProperty("by", "crew");
        m.addProperty("id", id);
        inbox.add(m);
        return id;
    }

    /** the task on now ended: the crew is told here, the guide over the line (as before) */
    void ended(String how, JsonObject toServer) {
        if (by != null) { endedHow = how; endedId = crewId; by = null; }
        else if (toServer != null) send(toServer);
    }
    double reach, lastX, lastZ;
    List<int[]> path;
    int at;
    boolean pressing, hitStarted;

    /** opens the guide's window */
    static KeyBinding window;
    /** the server mod's version from its hello: 0 no guide there, 1 no window yet, 2 the window */
    volatile int server = -1;

    static void start() {
        window = new KeyBinding("key.aiguide.window", Keyboard.KEY_G, "key.categories.aiguide");
        cpw.mods.fml.client.registry.ClientRegistry.registerKeyBinding(window);
        instance = new Pilot();
        instance.channel = NetworkRegistry.INSTANCE.newEventDrivenChannel(CHANNEL);
        instance.channel.register(instance);
        shareWithServerMod();
        FMLCommonHandler.instance().bus().register(instance);
        MinecraftForge.EVENT_BUS.register(instance);
        MinecraftForge.EVENT_BUS.register(new Ghosts());
        Director.start();
    }

    /**
     * Singleplayer with the AI Guide mod in the same game: there is one channel "aiguide" per name, and the one made
     * here (later) took the server side's packets too. The server mod's line is hooked onto it as well.
     */
    static void shareWithServerMod() {
        try {
            cpw.mods.fml.common.ModContainer m = cpw.mods.fml.common.Loader.instance().getIndexedModList().get("aiguide");
            if (m == null || m.getMod() == null) return;
            java.lang.reflect.Field lf = m.getMod().getClass().getDeclaredField("link");
            lf.setAccessible(true);
            Object link = lf.get(m.getMod());
            java.lang.reflect.Field cf = link.getClass().getDeclaredField("channel");
            cf.setAccessible(true);
            cf.set(link, instance.channel);
            instance.channel.register(link);
            System.out.println("[AIGuideClient] the server mod's line shares the channel (singleplayer)");
        } catch (Exception e) {
            System.out.println("[AIGuideClient] no server mod line to share: " + e);
        }
    }

    // ---- the line to the server --------------------------------------------------------------

    @SubscribeEvent
    public void packet(FMLNetworkEvent.ClientCustomPacketEvent e) {
        try {
            inbox.add(new JsonParser().parse(e.packet.payload().toString(UTF8)).getAsJsonObject());
        } catch (Exception ex) {
            // not ours
        }
    }

    @SubscribeEvent
    public void left(FMLNetworkEvent.ClientDisconnectionFromServerEvent e) {
        inbox.add(msg("stop"));
        inbox.add(msg("ghostclear"));
        server = -1;
    }

    void send(JsonObject m) {
        if (mc.thePlayer != null) channel.sendToServer(new FMLProxyPacket(Unpooled.wrappedBuffer(m.toString().getBytes(UTF8)), CHANNEL));
    }

    static JsonObject msg(String t) {
        JsonObject m = new JsonObject();
        m.addProperty("t", t);
        return m;
    }

    static int num(JsonObject m, String k) {
        return m.has(k) ? m.get(k).getAsInt() : 0;
    }

    void handle(JsonObject m) {
        String t = m.has("t") ? m.get("t").getAsString() : "";
        if (t.equals("hello")) {
            server = m.has("v") ? num(m, "v") : 1;
            JsonObject r = msg("hello");
            r.addProperty("v", 2);   // 2: the guide's window
            send(r);
        } else if (t.equals("say")) {
            GuiGuide.said(m.has("text") ? m.get("text").getAsString() : "", !m.has("chat") || m.get("chat").getAsBoolean());
        } else if (t.equals("goto") || t.equals("use") || t.equals("place") || t.equals("dig") || t.equals("look") || t.equals("hit")) {
            if (mc.currentScreen instanceof GuiGuide) mc.displayGuiScreen(null);   // the player watches where they are taken
            if (task != null && by != null) ended("stopped", null);   // the crew's task on now gives way to this one
            stop();
            boolean crew = m.has("by");
            if (!crew && crewDriving) crewSignal = "guide";   // the guide leads the player now: the crew's card goes back
            by = crew ? "crew" : null;
            crewId = crew ? num(m, "id") : 0;
            target = num(m, "e");
            task = t;
            tx = num(m, "x");
            ty = num(m, "y");
            tz = num(m, "z");
            slot = num(m, "slot");
            reach = m.has("reach") ? m.get("reach").getAsDouble() : 2.5;
            ticks = 0;
            replans = 0;
            hitStarted = false;
            behind = null;
            if (t.equals("goto") && !plan()) fail("no way there");
        } else if (t.equals("close")) {
            if (mc.currentScreen != null && !(mc.currentScreen instanceof GuiGuide)) mc.thePlayer.closeScreen();
        } else if (t.equals("inv")) {   // the 2x2 grid of the inventory: the server lays it out
            if (mc.currentScreen instanceof GuiGuide) mc.displayGuiScreen(null);
            stop();
            mc.displayGuiScreen(new net.minecraft.client.gui.inventory.GuiInventory(mc.thePlayer));
        } else if (t.equals("stop")) {
            if (m.has("by")) {   // the crew lets the character go: its own task stops, not the guide's
                crewDriving = false;
                if (task != null && by != null) { ended("stopped", null); stop(); }
            } else {
                if (task != null && by != null) ended("stopped", null);
                stop();
            }
        }
        else if (t.equals("ghost")) Ghosts.set(m);
        else if (t.equals("ghostclear")) Ghosts.clear();
    }

    // ---- every tick ----------------------------------------------------------------------------

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (mc.thePlayer == null || mc.theWorld == null) {
            inbox.clear();
            if (task != null && by != null) ended("stopped", null);
            task = null;
            pressing = false;
            crewDriving = false;
            return;
        }
        for (JsonObject m; (m = inbox.poll()) != null; ) handle(m);
        if (mc.currentScreen == null && window.isPressed()) mc.displayGuiScreen(new GuiGuide());
        shots();
        if (task == null && !crewDriving) {
            if (PlayerAgent.givenToAI && taken()) crewSignal = "taken";   // given to the AI, between cards: a movement key takes him back too
            return;
        }
        String bad = danger();
        if (bad != null) {   // a fall, lava, fire: the autopilot lets go at once, the controls are the player's
            Events.log("pilot: lets go of the controls: " + bad);
            boolean crew = by != null || crewDriving;
            stop();
            if (crew) { crewSignal = "danger"; ended("danger", null); }
            else { JsonObject f = msg("failed"); f.addProperty("why", "danger: " + bad); f.addProperty("task", ""); send(f); }
            return;
        }
        if (taken()) {   // the player moves themselves: the controls are theirs
            if (crewDriving) crewSignal = "taken";
            if (task != null) {
                stop();
                ended("taken", msg("cancel"));
            }
            return;
        }
        if (task == null) return;
        ticks++;
        if (task.equals("goto")) walk();
        else if (task.equals("use") || task.equals("place")) click();
        else if (task.equals("dig")) dig();
        else if (task.equals("look")) look();
        else if (task.equals("hit")) hit();
    }

    /** what makes driving the character dangerous now: falling further than a planned drop, lava or fire on or around it */
    String danger() {
        EntityClientPlayerMP p = mc.thePlayer;
        if (p.capabilities.isFlying || p.capabilities.disableDamage) return null;   // creative: nothing hurts
        if (p.fallDistance > 3.6f) return "falling";
        if (p.handleLavaMovement()) return "in lava";
        if (p.isBurning()) return "burning";
        int[] f = feet();
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                for (int dy = -1; dy <= 1; dy++) {
                    net.minecraft.block.material.Material m = mc.theWorld.getBlock(f[0] + dx, f[1] + dy, f[2] + dz).getMaterial();
                    if (m == net.minecraft.block.material.Material.lava) return "lava next to it";
                }
        return null;
    }

    /** turn to a point (a chest to put things in): done when looking at it */
    void look() {
        if (aim(tx + 0.5, ty + 0.5, tz + 0.5) || ticks >= AIM_TICKS) {
            task = null;
            ended("ok", null);
        }
    }

    int target;   // the entity a hit is for

    /** one blow at an entity in reach, as the attack button does: the eyes on it, then the game's own attack */
    void hit() {
        EntityClientPlayerMP p = mc.thePlayer;
        net.minecraft.entity.Entity e = mc.theWorld.getEntityByID(target);
        if (e == null || e.isDead) { task = null; ended("ok", null); return; }
        boolean on = aim(e.posX, e.boundingBox.minY + e.height * 0.6, e.posZ);
        if (!on && ticks < AIM_TICKS) return;
        if (p.getDistanceToEntity(e) > 3.4) { fail("out of reach"); return; }
        mc.playerController.attackEntity(p, e);
        p.swingItem();
        task = null;
        ended("ok", null);
    }

    /** a movement key held on the keyboard itself (not by the autopilot) */
    boolean taken() {
        if (mc.currentScreen != null) return false;
        int[] keys = { mc.gameSettings.keyBindForward.getKeyCode(), mc.gameSettings.keyBindBack.getKeyCode(), mc.gameSettings.keyBindLeft.getKeyCode(),
                mc.gameSettings.keyBindRight.getKeyCode(), mc.gameSettings.keyBindJump.getKeyCode(), mc.gameSettings.keyBindSneak.getKeyCode() };
        for (int k : keys)
            if (k > 0 && Keyboard.isKeyDown(k)) return true;
        return false;
    }

    void stop() {
        if (task != null && task.equals("dig") && hitStarted) mc.getNetHandler().addToSendQueue(new C07PacketPlayerDigging(1, tx, ty, tz, 0));
        task = null;
        path = null;
        keys(false, false);
    }

    void fail(String why) {
        String was = task;
        stop();
        JsonObject m = msg("failed");
        m.addProperty("why", why);
        m.addProperty("task", was == null ? "" : was);
        ended("failed:" + why, m);
    }

    void keys(boolean forward, boolean jump) {
        if (!forward && !jump && !pressing) return;
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), forward);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), jump);
        pressing = forward || jump;
    }

    // ---- walking -----------------------------------------------------------------------------

    int[] feet() {
        EntityClientPlayerMP p = mc.thePlayer;
        return new int[] { MathHelper.floor_double(p.posX), MathHelper.floor_double(p.boundingBox.minY + 0.01), MathHelper.floor_double(p.posZ) };
    }

    boolean plan() {
        path = Paths.find(mc.theWorld, feet(), tx, ty, tz, reach);
        at = 0;
        checkAt = ticks + 40;
        lastX = mc.thePlayer.posX;
        lastZ = mc.thePlayer.posZ;
        return path != null;
    }

    void walk() {
        EntityClientPlayerMP p = mc.thePlayer;
        if (mc.currentScreen != null && !(mc.currentScreen instanceof Commander.Screen)) { keys(false, false); return; }   // a window is open: wait (not the commander's: it watches from above)
        if (path == null || at >= path.size()) {
            keys(false, false);
            if (Paths.near(feet(), tx, ty, tz, reach + 0.5)) {
                task = null;
                ended("ok", msg("arrived"));
            } else if (++replans > 3 || !plan()) fail("lost the way");
            return;
        }
        int[] n = path.get(at);
        double cx = n[0] + 0.5, cz = n[2] + 0.5, dx = cx - p.posX, dz = cz - p.posZ, d = Math.sqrt(dx * dx + dz * dz);
        int fy = feet()[1];
        if (d < (at == path.size() - 1 ? 0.3 : 0.45) && Math.abs(fy - n[1]) <= 1) {
            at++;
            return;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float diff = MathHelper.wrapAngleTo180_float(yaw - p.rotationYaw);
        p.rotationYaw += Math.max(-SPIN, Math.min(SPIN, diff * EASE));   // eased, like a hand on the mouse
        p.rotationPitch += Math.max(-4, Math.min(4, (15 - p.rotationPitch) * EASE));   // eyes a little down, at the way
        boolean up = n[1] > fy || (p.isCollidedHorizontally && p.onGround) || p.isInWater();
        keys(Math.abs(diff) < 50, up);
        if (ticks >= checkAt) {   // no progress in two seconds: look for the way again
            if (Math.abs(p.posX - lastX) + Math.abs(p.posZ - lastZ) < 0.3) {
                keys(false, false);
                if (++replans > 3 || !plan()) fail("stuck");
                return;
            }
            checkAt = ticks + 40;
            lastX = p.posX;
            lastZ = p.posZ;
        }
    }

    // ---- turning to a point --------------------------------------------------------------------

    /** turns the head toward a point a little each tick; true when looking at it */
    boolean aim(double x, double y, double z) {
        EntityClientPlayerMP p = mc.thePlayer;
        double dx = x - p.posX, dy = y - (p.boundingBox.minY + p.getEyeHeight()), dz = z - p.posZ;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        float dyaw = MathHelper.wrapAngleTo180_float(yaw - p.rotationYaw), dpitch = pitch - p.rotationPitch;
        p.rotationYaw += Math.max(-SPIN, Math.min(SPIN, dyaw * EASE));
        p.rotationPitch += Math.max(-SPIN, Math.min(SPIN, dpitch * EASE));
        return Math.abs(dyaw) < 4 && Math.abs(dpitch) < 4;
    }

    /** the side of a block that faces the player's eyes */
    int facing(int x, int y, int z) {
        EntityClientPlayerMP p = mc.thePlayer;
        double dx = p.posX - (x + 0.5), dy = p.boundingBox.minY + p.getEyeHeight() - (y + 0.5), dz = p.posZ - (z + 0.5);
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ay >= ax && ay >= az) return dy > 0 ? 1 : 0;
        if (ax >= az) return dx > 0 ? 5 : 4;
        return dz > 0 ? 3 : 2;
    }

    // ---- using, placing, digging ---------------------------------------------------------------

    /** a right click: on the block itself (use), or on a block next to the planned one (place) */
    void click() {
        World w = mc.theWorld;
        EntityClientPlayerMP p = mc.thePlayer;
        int x = tx, y = ty, z = tz, s;
        if (task.equals("place")) {
            if (ticks == 1) p.inventory.currentItem = Math.max(0, Math.min(8, slot));
            int[] on = support(w, tx, ty, tz);
            if (on == null) { fail("nothing to place it against"); return; }
            x = on[0];
            y = on[1];
            z = on[2];
            s = on[3];
        } else s = facing(x, y, z);
        double hx = x + 0.5 + SIDE[s][0] * 0.5, hy = y + 0.5 + SIDE[s][1] * 0.5, hz = z + 0.5 + SIDE[s][2] * 0.5;
        if (!aim(hx, hy, hz) && ticks < AIM_TICKS) return;
        if (mc.playerController.onPlayerRightClick(p, w, p.getHeldItem(), x, y, z, s, Vec3.createVectorHelper(hx - x, hy - y, hz - z)))
            p.swingItem();
        task = null;
        ended("ok", null);
    }

    /** a solid block next to the place, and the side of it that faces the place */
    static int[] support(World w, int x, int y, int z) {
        int[] order = { 0, 2, 3, 4, 5, 1 };   // below first, then the sides, then above
        for (int i : order) {
            int nx = x + SIDE[i][0], ny = y + SIDE[i][1], nz = z + SIDE[i][2];
            if (!Paths.solid(w, nx, ny, nz)) continue;
            return new int[] { nx, ny, nz, i ^ 1 };   // the side of the neighbour that looks back at the place
        }
        return null;
    }

    float progress;
    int[] behind;   // the block the autopilot digs after the leaves in its way

    /**
     * Digs as the player does: the best tool of the hotbar in hand, "started digging" to the server, the
     * cracks growing for as long as the block takes with that tool, then "done digging" (the server checks
     * the time). Not through the controller, which drops a dig that the attack button does not hold.
     */
    void dig() {
        World w = mc.theWorld;
        EntityClientPlayerMP p = mc.thePlayer;
        int s = facing(tx, ty, tz);
        if (w.isAirBlock(tx, ty, tz)) {
            w.destroyBlockInWorldPartially(p.getEntityId(), tx, ty, tz, -1);
            if (behind != null) {   // the leaves in the way are gone: back to the block itself
                tx = behind[0];
                ty = behind[1];
                tz = behind[2];
                behind = null;
                hitStarted = false;
                ticks = 0;
                return;
            }
            task = null;
            ended("ok", null);
            return;
        }
        if (ticks > 20 * 30) { fail("could not dig it"); return; }
        boolean looking = aim(tx + 0.5 + SIDE[s][0] * 0.5, ty + 0.5 + SIDE[s][1] * 0.5, tz + 0.5 + SIDE[s][2] * 0.5);
        if (!looking && ticks < AIM_TICKS) return;
        Block b = w.getBlock(tx, ty, tz);
        if (!hitStarted && looking) {   // only what the eyes meet: leaves in the way go first, anything else firm is a fail
            net.minecraft.util.MovingObjectPosition hit = w.rayTraceBlocks(Vec3.createVectorHelper(p.posX, p.boundingBox.minY + p.getEyeHeight(), p.posZ),
                    Vec3.createVectorHelper(tx + 0.5, ty + 0.5, tz + 0.5), false, true, false);
            if (hit != null && (hit.blockX != tx || hit.blockY != ty || hit.blockZ != tz)) {
                Block in = w.getBlock(hit.blockX, hit.blockY, hit.blockZ);
                if (in.getMaterial() == net.minecraft.block.material.Material.leaves && behind == null) {
                    behind = new int[] { tx, ty, tz };
                    tx = hit.blockX;
                    ty = hit.blockY;
                    tz = hit.blockZ;
                    ticks = 0;
                    return;
                }
                if (in.getMaterial() != net.minecraft.block.material.Material.leaves) { fail("not in sight"); return; }
            }
        }
        if (!hitStarted) {
            int best = p.inventory.currentItem;
            float speed = -1;
            for (int i = 0; i < 9; i++) {   // the hotbar slot that digs it fastest
                p.inventory.currentItem = i;
                float h = b.getPlayerRelativeBlockHardness(p, w, tx, ty, tz);
                if (h > speed + 1e-6) { speed = h; best = i; }
            }
            p.inventory.currentItem = best;
            mc.getNetHandler().addToSendQueue(new C09PacketHeldItemChange(best));
            if (speed <= 0) { fail("cannot be dug"); return; }
            mc.getNetHandler().addToSendQueue(new C07PacketPlayerDigging(0, tx, ty, tz, s));
            hitStarted = true;
            progress = 0;
        }
        progress += b.getPlayerRelativeBlockHardness(p, w, tx, ty, tz);
        p.swingItem();
        w.destroyBlockInWorldPartially(p.getEntityId(), tx, ty, tz, (int) (Math.min(1, progress) * 10) - 1);
        if (progress >= 1.0f) {
            mc.getNetHandler().addToSendQueue(new C07PacketPlayerDigging(2, tx, ty, tz, s));
            hitStarted = false;
            progress = -100;   // waits for the server to take the block away
        }
    }

    // ---- -Daiguide.client.shots=true: screenshots while it works, to see it from outside -----------

    static final boolean SHOTS = Boolean.getBoolean("aiguide.client.shots");
    int clock, taken;

    static final String ASK = System.getProperty("aiguide.client.ask");   // a question asked in the window once in the world
    int askAt = -1, answered;

    void shots() {
        if (ASK != null && server >= 0) {
            ++clock;
            if (askAt < 0 && clock > 100) {
                askAt = clock;
                mc.displayGuiScreen(new GuiGuide());
                GuiGuide.ask(ASK.replace('_', ' '));
            } else if (askAt > 0 && GuiGuide.waiting) answered = clock;
            else if (askAt > 0 && askAt < 1 << 30 && !GuiGuide.waiting && clock > answered + 40) {
                net.minecraft.util.ScreenShotHelper.saveScreenshot(mc.mcDataDir, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
                System.out.println("[AIGuideClient] window shot, history " + GuiGuide.history.size());
                askAt = 1 << 30;   // once
            }
        }
        if (!SHOTS || ++clock % 60 != 0 || taken >= 20 || task == null && Ghosts.all.isEmpty()) return;
        taken++;
        net.minecraft.util.ScreenShotHelper.saveScreenshot(mc.mcDataDir, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        System.out.println("[AIGuideClient] shot " + taken + ": task " + task + ", ghosts " + Ghosts.all.size() + ", at "
                + (int) mc.thePlayer.posX + " " + (int) mc.thePlayer.boundingBox.minY + " " + (int) mc.thePlayer.posZ);
    }

    // ---- a line on the screen while it drives ------------------------------------------------------

    @SubscribeEvent
    public void overlay(RenderGameOverlayEvent.Text e) {
        if (crewDriving) { e.left.add("§b" + CrewKeys.tr("overlay")); return; }   // the crew's card: the player's character is its number 1
        if (task == null) return;
        boolean ru = mc.gameSettings.language != null && mc.gameSettings.language.startsWith("ru");
        e.left.add("§b" + (ru ? "[Гид] ведёт тебя — WASD, чтобы взять управление" : "[Guide] is driving — WASD to take the controls"));
    }
}
