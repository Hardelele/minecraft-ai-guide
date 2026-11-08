package hardelele.aiguide.client;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.IMob;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import org.lwjgl.BufferUtils;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

/**
 * Commander mode: the game seen from above as a strategy game, the crew as units. A key (F7) turns it on: the view
 * leaves the player (who stays where he is) for a camera over the ground (WASD or the screen's edge to pan, the wheel
 * to zoom, Q/E or the middle button to turn), the mouse is free. Left click picks an agent, a drag a box of them,
 * Shift adds, a double click takes them all, Ctrl+1..4 makes a group, 1..4 calls it back. Right click orders the
 * picked ones what the click means: a tree is chopped, ore mined, the crew's chest gets their bags, a mob is fought,
 * anywhere else they go; Shift queues the order after the ones before. Every order is a card on the crew's one board
 * (Orders): the same skills, the same books, nothing done another way. The ones not picked go on with the guide's plan.
 */
public class Commander {
    static Commander instance;
    final Minecraft mc = Minecraft.getMinecraft();
    final KeyBinding key = new KeyBinding("key.aiguide.commander", Keyboard.KEY_F7, "AI Guide");

    // the camera: a point on the ground it looks at, how far, the turn and the tilt (wanted and as shown, eased)
    double px, py, pz, dist = 22, yaw = 0, pitch = 56;
    double sx, sy, sz, sdist = 22, syaw = 0;
    volatile boolean on;   // (read by the crew's thread: the player's character takes cards while the commander is on)
    boolean placed;
    long lastNanos;
    net.minecraft.client.entity.EntityOtherPlayerMP eye;
    boolean hideWas;
    double[] camAt;   // the view as it is: x y z, turn, tilt
    int edgeX, edgeY, lastMx, lastMy;   // the screen's edge the mouse is at (or went out over)
    boolean lastIn;
    static volatile int drawn;   // frames the commander's screen was drawn (a check)

    /**
     * The film's cameras from the commander: K a keyframe where the view is now (3 s after the one before), P plays the
     * path, Shift+P records it, C clears the keys, F follows the first picked agent, G the director by action on/off.
     */
    void filmKey(int code, boolean shift) {
        Cam c = Cam.get();
        if (code == Keyboard.KEY_K && shift) { c.keys.clear(); c.release(); Director.log("path: keys cleared"); }
        else if (code == Keyboard.KEY_K && camAt != null) {
            double t = c.keys.isEmpty() ? 0 : c.keys.get(c.keys.size() - 1).t + 60;
            c.keys.add(new Cam.Key(t, camAt[0], camAt[1], camAt[2], camAt[3], camAt[4], CaptureFov.degrees(mc.gameSettings.fovSetting)));
            Director.log("path: key " + c.keys.size() + " at " + (int) t + " ticks");
        } else if (code == Keyboard.KEY_P) {
            c.playPath(shift ? new java.io.File(mc.mcDataDir, "path-" + System.currentTimeMillis() + ".mp4").getAbsolutePath() : null);
        } else if (code == Keyboard.KEY_F && !selected.isEmpty()) {
            c.autoOn = false;
            Cam.Shot s = c.planFollow(selected.iterator().next(), 20 * 600);
            if (s != null) c.start(s);
        } else if (code == Keyboard.KEY_N) {
            c.autoOn = !c.autoOn;
            if (!c.autoOn) c.release();
            Director.log("autocam by action: " + (c.autoOn ? "on" : "off"));
        }
    }

    // ---- the hand on the mouse: a person's, or a script's that moves a cursor shown in the picture and clicks it --
    volatile boolean vOn;             // a script's hand (the person's mouse is not read then)
    double vx = 400, vy = 300;        // its cursor, window pixels from the bottom left
    double vFromX, vFromY, vToX, vToY;
    int vT, vLen;
    boolean vShift, vCtrl, vAlt;
    final boolean[] vDown = new boolean[3];
    long clickAt;                     // a ring on the cursor when it clicks
    int clickX, clickY;
    float safeAspect;                 // > 0: the panel, the buttons and the labels kept in a centred band of this width:height (9:16 for a phone)

    int curX() { return vOn ? (int) vx : Mouse.getX(); }
    int curY() { return vOn ? (int) vy : Mouse.getY(); }
    boolean curIn() { return vOn || Mouse.isInsideWindow(); }
    boolean shiftDown() { return vOn ? vShift : Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT); }
    boolean ctrlDown() { return vOn ? vCtrl : Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL); }
    boolean altDown() { return vOn ? vAlt : Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU); }

    /** the script's hand goes to a window point (pixels from the bottom left) over some ticks, eased */
    void vMove(double x, double y, int ticks) {
        vFromX = vx; vFromY = vy; vToX = x; vToY = y; vT = 0; vLen = Math.max(1, ticks); vTrack = 0;
    }

    int vTrack;   // an agent the hand's aim follows (its number), 0 none

    /** the hand goes to an agent and stays on it as it walks, until the next move */
    void vMoveToAgent(int skin, int ticks) {
        for (Agent g : clientAgents()) if (g.skin() == skin) { float[] s = toScreen(g.posX, g.boundingBox.minY + 1.0, g.posZ); if (s != null) vMove(s[0], s[1], ticks); }
        vTrack = skin;
    }

    /** a button of the script's hand pressed or let go where the cursor is (through the screen's own handling) */
    void vButton(int b, boolean down) {
        vDown[b] = down;
        if (down) { clickAt = System.currentTimeMillis(); clickX = (int) vx; clickY = (int) vy; }
        if (mc.currentScreen instanceof Screen) ((Screen) mc.currentScreen).input(b, down, (int) vx, (int) vy, 0, 0);
    }

    void vWheel(int n) {
        if (mc.currentScreen instanceof Screen) ((Screen) mc.currentScreen).input(-1, false, (int) vx, (int) vy, n, 0);
    }

    @SubscribeEvent
    public void hand(TickEvent.RenderTickEvent e) {   // the script's cursor moves a little every frame (by video frames while recording)
        if (e.phase != TickEvent.Phase.START || !vOn || vT >= vLen && vTrack == 0) return;
        vT = Math.min(vLen, vT + 1);
        if (vTrack > 0) for (Agent g : clientAgents()) if (g.skin() == vTrack) {   // aiming at an agent that walks: the aim goes with it
            float[] s = toScreen(g.lastTickPosX + (g.posX - g.lastTickPosX) * e.renderTickTime, g.boundingBox.minY + 1.0, g.lastTickPosZ + (g.posZ - g.lastTickPosZ) * e.renderTickTime);
            if (s != null) { vToX = s[0]; vToY = s[1]; }
        }
        double k = vT / (double) vLen, f = k * k * (3 - 2 * k);
        vx = vFromX + (vToX - vFromX) * f;
        vy = vFromY + (vToY - vFromY) * f;
        if (mc.currentScreen instanceof Screen) ((Screen) mc.currentScreen).input(-1, false, (int) vx, (int) vy, 0, 0);   // a move (a drag, when a button is held)
    }

    /** when a person last used the commander (a script's quit waits until nobody has for a minute) */
    static volatile long lastInput;

    /** someone at the game: keys or the mouse in its window within the last s seconds (the commander's, or the game's own) */
    static boolean someoneThere(int s) {
        if (System.currentTimeMillis() - lastInput < s * 1000L) return true;
        return Display.isActive() && (Mouse.isButtonDown(0) || Mouse.isButtonDown(1) || Keyboard.getEventKeyState());
    }

    // the picked ones (by agent number) and the groups
    final Set<Integer> selected = new LinkedHashSet<Integer>();
    int viewedSkin = -1;
    boolean viewedUnderground;
    final Map<Integer, Set<Integer>> groups = new HashMap<Integer, Set<Integer>>();
    // the last frame's view (for picking from the mouse, and labels)
    final FloatBuffer model = BufferUtils.createFloatBuffer(16), proj = BufferUtils.createFloatBuffer(16);
    final IntBuffer view = BufferUtils.createIntBuffer(16);
    double rx, ry, rz;   // where the frame's coordinates start (the camera's render origin)
    boolean haveView;
    // the order marker: where the last order went, when
    double[] mark;
    long markAt;
    int markColor;
    // the person's ratings: a recognised place's menu, what the agents did (L: the places' layer, J: the agents' episodes)
    final FeedbackUi fb = new FeedbackUi();
    // every message to the person (the guide's answers, the crew's notes, a rating saved): one queue, its journal
    final UiToasts toasts = new UiToasts();
    // the guide in this game: its mod's classes are here (a singleplayer world with AI Guide); false after it failed to answer
    static volatile Boolean guideThere;

    /** the guide can be asked from here (a singleplayer world with the guide's mod, and it has not failed) */
    boolean guideHere() {
        if (guideThere == null) {
            try { Class.forName("hardelele.aiguide.Tools"); guideThere = Boolean.TRUE; } catch (Throwable t) { guideThere = Boolean.FALSE; }
        }
        return guideThere && mc.isSingleplayer();
    }

    /** a button's id: what the panel's hits call it (a director's script clicks it by that: "vmoveto pill stop 20") */
    static String pillOf(UiIcon i) {
        switch (i) {
            case PLAN: return "ai";
            case ADD_AGENT: return "add";
            case SELF_AI: return "self";
            case SCENARIOS: return "routines";
            case RATE: return "actions";
            case CLOSE: return "chatclose";
            case LOG: return "journal";
            case INVENTORY: return "inv";
            default: return i.name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    static void start() {
        instance = new Commander();
        ClientRegistry.registerKeyBinding(instance.key);
        FMLCommonHandler.instance().bus().register(instance);
        MinecraftForge.EVENT_BUS.register(instance);
    }

    @SubscribeEvent
    public void keys(InputEvent.KeyInputEvent e) {
        if (key.isPressed() && mc.theWorld != null && mc.currentScreen == null) open();
    }

    void open() {
        if (on) return;
        on = true;
        if (!placed && mc.thePlayer != null) {   // first time: over the player
            px = sx = mc.thePlayer.posX;
            pz = sz = mc.thePlayer.posZ;
            py = sy = mc.thePlayer.boundingBox.minY;
            yaw = syaw = mc.thePlayer.rotationYaw;
            placed = true;
        }
        hideWas = mc.gameSettings.hideGUI;
        mc.gameSettings.hideGUI = true;
        if (Cam.instance != null) Cam.instance.release();
        mc.displayGuiScreen(new Screen());
        Director.log("commander: on");
    }

    void close() {
        if (!on) return;
        on = false;
        fb.close();
        Slice.exit();
        CaveView.exit();
        viewedSkin = -1;
        mc.gameSettings.hideGUI = hideWas;
        if (mc.thePlayer != null) mc.renderViewEntity = mc.thePlayer;
        Director.log("commander: off");
    }

    /** the player's own HUD (hotbar, hearts, crosshair) is not the commander's: the panel shows the crew */
    @SubscribeEvent
    public void hud(net.minecraftforge.client.event.RenderGameOverlayEvent.Pre e) {
        // each element, not ALL: cancelling ALL skips the switch to 2D drawing, and then the commander's own screen (the panel) is not seen
        net.minecraftforge.client.event.RenderGameOverlayEvent.ElementType t = e.type;
        if (on && t != net.minecraftforge.client.event.RenderGameOverlayEvent.ElementType.ALL && t != net.minecraftforge.client.event.RenderGameOverlayEvent.ElementType.CHAT) e.setCanceled(true);
    }

    // ---- the camera, every frame --------------------------------------------------------------------

    @SubscribeEvent
    public void frame(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.START || !on || mc.theWorld == null || mc.thePlayer == null) return;
        if (Cam.instance != null && Cam.instance.shot != null) { Slice.exit(); CaveView.exit(); viewedSkin = -1; return; }   // film camera owns this frame

        // One chosen hero is a camera target, including when rock hides it from the surface.
        Agent viewed = selected.size() == 1 ? renderedAgent(selected.iterator().next()) : null;
        double viewLimit = Math.max(8, mc.gameSettings.renderDistanceChunks * 16 - 28);
        if (viewed != null && Math.hypot(viewed.posX - mc.thePlayer.posX, viewed.posZ - mc.thePlayer.posZ) > viewLimit) viewed = null;
        int skin = viewed == null || viewed.isDead ? -1 : viewed.skin();
        if (skin != viewedSkin) {
            // A new selection must not inherit the previous agent's cave volume.
            if (CaveView.on) CaveView.exit();
            viewedSkin = skin;
            if (viewed != null) {
                px = sx = viewed.posX;
                pz = sz = viewed.posZ;
                py = sy = viewed.boundingBox.minY + 0.6;
                yaw = syaw = viewed.rotationYaw;
                boolean underground = Slice.belowSurface(viewed);
                viewedUnderground = underground;
                if (underground && !CaveView.on) {
                    Slice.follow = true;
                    Slice.enter();
                    dist = sdist = 11;
                    pitch = 52;
                } else if (!underground) {
                    Slice.exit();
                    CaveView.exit();
                    dist = sdist = 22;
                    pitch = 56;
                }
            } else { Slice.exit(); CaveView.exit(); viewedUnderground = false; }
        }
        if (viewed == null) { if (Slice.on) Slice.exit(); if (CaveView.on) CaveView.exit(); }
        if (viewed != null) {
            boolean underground = Slice.belowSurface(viewed);
            if (underground != viewedUnderground) {
                viewedUnderground = underground;
                if (underground) { if (!CaveView.on) { Slice.follow = true; Slice.enter(); dist = 11; pitch = 52; } }
                else {
                    Slice.exit();
                    // Keep the cave volume while this same agent reaches its upper opening.
                    // The person explicitly leaves the overview with C or selects another agent.
                    if (!CaveView.on) { dist = 22; pitch = 56; }
                }
            }
        }
        long now = System.nanoTime();
        double dt = lastNanos == 0 ? 0 : Math.min(0.1, (now - lastNanos) / 1e9);
        if (Recorder.instance != null) dt = lastNanos == 0 ? 0 : 1 / 60.0;   // recording: by video frames
        lastNanos = now;
        boolean active = Display.isActive();   // keys and the screen's edge only when the window is the player's (never from behind)
        double fx = -Math.sin(Math.toRadians(yaw)), fz = Math.cos(Math.toRadians(yaw));   // forward on the ground
        double speed = dist * 1.1 * dt, mxp = 0, mzp = 0;
        boolean typing = mc.currentScreen instanceof Screen && ((Screen) mc.currentScreen).chat != null && ((Screen) mc.currentScreen).chat.isFocused();
        if (active && mc.currentScreen instanceof Screen && !vOn && !typing) {
            if (Keyboard.isKeyDown(Keyboard.KEY_W)) { mxp += fx; mzp += fz; }
            if (Keyboard.isKeyDown(Keyboard.KEY_S)) { mxp -= fx; mzp -= fz; }
            if (Keyboard.isKeyDown(Keyboard.KEY_A)) { mxp += fz; mzp -= fx; }
            if (Keyboard.isKeyDown(Keyboard.KEY_D)) { mxp -= fz; mzp += fx; }
            if (Keyboard.isKeyDown(Keyboard.KEY_Q)) yaw -= 90 * dt;
            if (Keyboard.isKeyDown(Keyboard.KEY_E)) yaw += 90 * dt;
            // the screen's edge: a band of 12 pixels; the mouse gone out over an edge keeps the camera going that way
            int mx = curX(), my = curY(), m = 12;
            boolean in = curIn();
            if (in) { edgeX = mx < m ? -1 : mx > mc.displayWidth - m ? 1 : 0; edgeY = my < m ? -1 : my > mc.displayHeight - m ? 1 : 0; }
            else if (lastIn) {   // it has just left: over the edge it was nearest to
                edgeX = lastMx < mc.displayWidth / 6 ? -1 : lastMx > mc.displayWidth * 5 / 6 ? 1 : 0;
                edgeY = lastMy < mc.displayHeight / 6 ? -1 : lastMy > mc.displayHeight * 5 / 6 ? 1 : 0;
            }
            if (in) { lastMx = mx; lastMy = my; }
            lastIn = in;
            boolean dragging = vOn ? vDown[0] || vDown[1] : Mouse.isButtonDown(0) || Mouse.isButtonDown(1);   // not while painting or ordering with a drag near the edge... unless it is outside
            if (!dragging || !in) {
                if (edgeX < 0) { mxp += fz; mzp -= fx; }
                if (edgeX > 0) { mxp -= fz; mzp += fx; }
                if (edgeY < 0) { mxp -= fx; mzp -= fz; }
                if (edgeY > 0) { mxp += fx; mzp += fz; }
            }
        }
        px += mxp * speed;
        pz += mzp * speed;
        if (CaveView.active()) {
            // The selected component is a persistent map. Agent movement cannot change
            // its center or silently replace it with a disconnected air pocket.
            double[] focus = CaveView.focusPoint(viewed);
            if (focus != null) { px = focus[0]; py = focus[1]; pz = focus[2]; }
            else {
                if (CaveView.focused()) { CaveView.resetFocus(); dist = 45; pitch = 42; }
                px = CaveView.anchorX();
                pz = CaveView.anchorZ();
                CaveGeometry.Result g = CaveView.volume;
                py = g != null && g.cells > 0 ? (g.minLevel + g.maxLevel) * 0.5 : viewed.boundingBox.minY + 0.6;
            }
        } else if (viewed != null && Slice.active()) {
            px = viewed.posX;
            pz = viewed.posZ;
            py = Slice.follow ? viewed.boundingBox.minY + 0.6 : Slice.level - 1;
        } else {
            int gy = mc.theWorld.getHeightValue(MathHelper.floor_double(px), MathHelper.floor_double(pz));
            if (gy > 0) py = gy;
        }
        double far = Math.hypot(px - mc.thePlayer.posX, pz - mc.thePlayer.posZ), lim = mc.gameSettings.renderDistanceChunks * 16 - 24;   // within the world drawn around the player
        if (far > lim) { px = mc.thePlayer.posX + (px - mc.thePlayer.posX) * lim / far; pz = mc.thePlayer.posZ + (pz - mc.thePlayer.posZ) * lim / far; }
        double k = dt <= 0 ? 1 : 1 - Math.exp(-dt * 8);   // eased toward what is wanted, the same at any frame rate
        sx += (px - sx) * k; sy += (py - sy) * k; sz += (pz - sz) * k; sdist += (dist - sdist) * k; syaw += (yaw - syaw) * k;
        double cy = Math.cos(Math.toRadians(pitch)), sp = Math.sin(Math.toRadians(pitch));
        double ffx = -Math.sin(Math.toRadians(syaw)), ffz = Math.cos(Math.toRadians(syaw));
        double ex = sx - ffx * sdist * cy, ey = sy + sdist * sp, ez = sz - ffz * sdist * cy;
        if (eye == null || eye.worldObj != mc.theWorld) {
            eye = new net.minecraft.client.entity.EntityOtherPlayerMP(mc.theWorld, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "commander"));
            eye.setInvisible(true);
        }
        double feet = ey - 1.62;
        camAt = new double[] { ex, ey, ez, syaw, pitch };
        eye.setPosition(ex, feet, ez);
        eye.prevPosX = eye.lastTickPosX = ex;
        eye.prevPosY = eye.lastTickPosY = feet;
        eye.prevPosZ = eye.lastTickPosZ = ez;
        eye.rotationYaw = eye.prevRotationYaw = eye.rotationYawHead = eye.prevRotationYawHead = (float) syaw;
        eye.rotationPitch = eye.prevRotationPitch = (float) pitch;
        mc.renderViewEntity = eye;
    }

    // ---- the world's overlay: rings under the picked, the order's mark; the view kept for picking ------

    @SubscribeEvent
    public void world(RenderWorldLastEvent e) {
        if (!on) return;
        model.clear(); proj.clear(); view.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, model);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, proj);
        GL11.glGetInteger(GL11.GL_VIEWPORT, view);
        rx = RenderManager.renderPosX; ry = RenderManager.renderPosY; rz = RenderManager.renderPosZ;
        haveView = true;
        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_LINE_BIT);
        GL11.glTranslated(-rx, -ry, -rz);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glLineWidth(3f);
        Tessellator t = Tessellator.instance;
        float pt = e.partialTicks;
        List<EntityLivingBase> units = new ArrayList<EntityLivingBase>(clientAgents());
        if (selfUnit()) units.add(mc.thePlayer);
        for (EntityLivingBase a : units) {
            if (!selected.contains(unitOf(a))) continue;
            double x = a.lastTickPosX + (a.posX - a.lastTickPosX) * pt, z = a.lastTickPosZ + (a.posZ - a.lastTickPosZ) * pt;
            double y = a.lastTickPosY + (a.posY - a.lastTickPosY) * pt - a.yOffset + 0.05;   // (the player's posY is his eyes': yOffset takes it down)
            double pulse = 0.7 + 0.06 * Math.sin(System.currentTimeMillis() / 180.0);
            ring(t, x, y, z, pulse, 0.35f, 1f, 0.5f, 0.95f);
            ring(t, x, y, z, pulse + 0.12, 0.35f, 1f, 0.5f, 0.35f);
        }
        if (mark != null && System.currentTimeMillis() - markAt < 700) {
            float f = 1 - (System.currentTimeMillis() - markAt) / 700f;
            ring(t, mark[0], mark[1] + 0.05, mark[2], 0.3 + 0.6 * (1 - f), ((markColor >> 16) & 255) / 255f, ((markColor >> 8) & 255) / 255f, (markColor & 255) / 255f, f);
        }
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glPopAttrib();
        GL11.glPopMatrix();
    }

    static void ring(Tessellator t, double x, double y, double z, double r, float cr, float cg, float cb, float ca) {
        t.startDrawing(GL11.GL_LINE_LOOP);
        t.setColorRGBA_F(cr, cg, cb, ca);
        for (int i = 0; i < 24; i++) {
            double a = Math.PI * 2 * i / 24;
            t.addVertex(x + Math.sin(a) * r, y, z + Math.cos(a) * r);
        }
        t.draw();
    }

    List<Agent> clientAgents() {
        List<Agent> out = new ArrayList<Agent>();
        if (mc.theWorld == null) return out;
        for (Object o : mc.theWorld.loadedEntityList) if (o instanceof Agent && !((Agent) o).isDead) out.add((Agent) o);
        return out;
    }

    Agent renderedAgent(int skin) {
        for (Agent a : clientAgents()) if (a.skin() == skin) return a;
        return null;
    }

    // ---- the player's own character as the crew's number 1 (PlayerAgent) ----

    /** the player is one of the crew here (a singleplayer world, not a video script) */
    boolean selfUnit() {
        return Crew.PLAYER_ONE && mc.isSingleplayer() && mc.thePlayer != null;
    }

    /** the crew's number of what was clicked: an agent's, 1 for the player himself, -1 for anything else */
    int unitOf(Object o) {
        if (o instanceof Agent) return ((Agent) o).skin();
        if (o != null && o == mc.thePlayer && selfUnit()) return 1;
        return -1;
    }

    /** the player as the commander names him: his name with his number */
    String selfLabel() {
        return CrewKeys.tr("self.label", mc.thePlayer == null ? "" : mc.thePlayer.getCommandSenderName());
    }

    /** what the player's character does for the crew now */
    String selfDoing() {
        PlayerAgent me = me();
        TaskBoard.Card k = me == null ? null : me.card;
        if (k != null) return CrewKeys.tr("self.doing", k.kind.replace("order ", "").replace("paint ", ""));
        return PlayerAgent.givenToAI ? CrewKeys.tr("self.ai") : CrewKeys.tr("self.free");
    }

    static PlayerAgent me() {
        Crew c = Crew.instance;
        if (c == null) return null;
        for (Doer d : c.extra) if (d instanceof PlayerAgent) return (PlayerAgent) d;
        return null;
    }

    /** the picked agents, not the player (whom one cannot let go) */
    int[] agentsPicked() {
        List<Integer> l = new ArrayList<Integer>();
        for (int s : selected) if (!(s == 1 && selfUnit())) l.add(s);
        int[] out = new int[l.size()];
        for (int i = 0; i < out.length; i++) out[i] = l.get(i);
        return out;
    }

    /** one more agent: at the point under the cursor (on the block's top; from the panel's button: where the camera looks), else by the player */
    void addAgent(boolean atCursor) {
        Object o = atCursor && curIn() ? pick(curX(), curY()) : null;
        double[] at = atCursor ? null : new double[] { px, py, pz };
        if (o instanceof MovingObjectPosition) { MovingObjectPosition m = (MovingObjectPosition) o; at = new double[] { m.blockX + 0.5, m.blockY + 1, m.blockZ + 0.5 }; }
        if (CrewKeys.instance != null) CrewKeys.instance.addAgents(1, at);
    }

    /** the picked agents let go (the player stays) */
    void dismissPicked() {
        int[] who = agentsPicked();
        if (who.length == 0) { if (CrewKeys.instance != null) CrewKeys.instance.say(CrewKeys.tr("remove.which")); return; }
        Crew.dismiss(who);
        for (int s : who) selected.remove(s);
    }

    // ---- from the screen to the world and back ---------------------------------------------------------

    /** P x M (column-major, as OpenGL keeps them) */
    float[] pm() {
        float[] p = new float[16], m = new float[16], r = new float[16];
        for (int i = 0; i < 16; i++) { p[i] = proj.get(i); m[i] = model.get(i); }
        for (int c = 0; c < 4; c++)
            for (int rr = 0; rr < 4; rr++) {
                float v = 0;
                for (int k = 0; k < 4; k++) v += p[k * 4 + rr] * m[c * 4 + k];
                r[c * 4 + rr] = v;
            }
        return r;
    }

    /** a world point on the window (pixels from the bottom left), or null when behind the camera */
    float[] toScreen(double x, double y, double z) {
        if (!haveView) return null;
        float[] a = pm();
        double vx = x - rx, vy = y - ry, vz = z - rz;
        double cx = a[0] * vx + a[4] * vy + a[8] * vz + a[12], cy = a[1] * vx + a[5] * vy + a[9] * vz + a[13];
        double cz = a[2] * vx + a[6] * vy + a[10] * vz + a[14], cw = a[3] * vx + a[7] * vy + a[11] * vz + a[15];
        if (cw <= 0.01) return null;
        double nx = cx / cw, ny = cy / cw, nz = cz / cw;
        if (nz < -1 || nz > 1) return null;
        return new float[] { (float) (view.get(0) + (nx + 1) / 2 * view.get(2)), (float) (view.get(1) + (ny + 1) / 2 * view.get(3)) };
    }

    /** the inverse of a 4x4 (column-major) matrix */
    static double[] inverse(float[] m) {
        double[] a = new double[16], inv = new double[16];
        for (int i = 0; i < 16; i++) a[i] = m[i];
        inv[0] = a[5] * a[10] * a[15] - a[5] * a[11] * a[14] - a[9] * a[6] * a[15] + a[9] * a[7] * a[14] + a[13] * a[6] * a[11] - a[13] * a[7] * a[10];
        inv[4] = -a[4] * a[10] * a[15] + a[4] * a[11] * a[14] + a[8] * a[6] * a[15] - a[8] * a[7] * a[14] - a[12] * a[6] * a[11] + a[12] * a[7] * a[10];
        inv[8] = a[4] * a[9] * a[15] - a[4] * a[11] * a[13] - a[8] * a[5] * a[15] + a[8] * a[7] * a[13] + a[12] * a[5] * a[11] - a[12] * a[7] * a[9];
        inv[12] = -a[4] * a[9] * a[14] + a[4] * a[10] * a[13] + a[8] * a[5] * a[14] - a[8] * a[6] * a[13] - a[12] * a[5] * a[10] + a[12] * a[6] * a[9];
        inv[1] = -a[1] * a[10] * a[15] + a[1] * a[11] * a[14] + a[9] * a[2] * a[15] - a[9] * a[3] * a[14] - a[13] * a[2] * a[11] + a[13] * a[3] * a[10];
        inv[5] = a[0] * a[10] * a[15] - a[0] * a[11] * a[14] - a[8] * a[2] * a[15] + a[8] * a[3] * a[14] + a[12] * a[2] * a[11] - a[12] * a[3] * a[10];
        inv[9] = -a[0] * a[9] * a[15] + a[0] * a[11] * a[13] + a[8] * a[1] * a[15] - a[8] * a[3] * a[13] - a[12] * a[1] * a[11] + a[12] * a[3] * a[9];
        inv[13] = a[0] * a[9] * a[14] - a[0] * a[10] * a[13] - a[8] * a[1] * a[14] + a[8] * a[2] * a[13] + a[12] * a[1] * a[10] - a[12] * a[2] * a[9];
        inv[2] = a[1] * a[6] * a[15] - a[1] * a[7] * a[14] - a[5] * a[2] * a[15] + a[5] * a[3] * a[14] + a[13] * a[2] * a[7] - a[13] * a[3] * a[6];
        inv[6] = -a[0] * a[6] * a[15] + a[0] * a[7] * a[14] + a[4] * a[2] * a[15] - a[4] * a[3] * a[14] - a[12] * a[2] * a[7] + a[12] * a[3] * a[6];
        inv[10] = a[0] * a[5] * a[15] - a[0] * a[7] * a[13] - a[4] * a[1] * a[15] + a[4] * a[3] * a[13] + a[12] * a[1] * a[7] - a[12] * a[3] * a[5];
        inv[14] = -a[0] * a[5] * a[14] + a[0] * a[6] * a[13] + a[4] * a[1] * a[14] - a[4] * a[2] * a[13] - a[12] * a[1] * a[6] + a[12] * a[2] * a[5];
        inv[3] = -a[1] * a[6] * a[11] + a[1] * a[7] * a[10] + a[5] * a[2] * a[11] - a[5] * a[3] * a[10] - a[9] * a[2] * a[7] + a[9] * a[3] * a[6];
        inv[7] = a[0] * a[6] * a[11] - a[0] * a[7] * a[10] - a[4] * a[2] * a[11] + a[4] * a[3] * a[10] + a[8] * a[2] * a[7] - a[8] * a[3] * a[6];
        inv[11] = -a[0] * a[5] * a[11] + a[0] * a[7] * a[9] + a[4] * a[1] * a[11] - a[4] * a[3] * a[9] - a[8] * a[1] * a[7] + a[8] * a[3] * a[5];
        inv[15] = a[0] * a[5] * a[10] - a[0] * a[6] * a[9] - a[4] * a[1] * a[10] + a[4] * a[2] * a[9] + a[8] * a[1] * a[6] - a[8] * a[2] * a[5];
        double det = a[0] * inv[0] + a[1] * inv[4] + a[2] * inv[8] + a[3] * inv[12];
        if (Math.abs(det) < 1e-12) return null;
        for (int i = 0; i < 16; i++) inv[i] /= det;
        return inv;
    }

    /** a window point at a depth (-1 near, 1 far) back to the frame's coordinates */
    static double[] unproject(double[] inv, double nx, double ny, double nz) {
        double x = inv[0] * nx + inv[4] * ny + inv[8] * nz + inv[12], y = inv[1] * nx + inv[5] * ny + inv[9] * nz + inv[13];
        double z = inv[2] * nx + inv[6] * ny + inv[10] * nz + inv[14], w = inv[3] * nx + inv[7] * ny + inv[11] * nz + inv[15];
        return new double[] { x / w, y / w, z / w };
    }

    /** the ray under the mouse: from, to (world) */
    Vec3[] ray(int mx, int my) {
        if (!haveView) return null;
        double[] inv = inverse(pm());
        if (inv == null) return null;
        double nx = (mx - view.get(0)) * 2.0 / view.get(2) - 1, ny = (my - view.get(1)) * 2.0 / view.get(3) - 1;
        double[] a = unproject(inv, nx, ny, -1), b = unproject(inv, nx, ny, 1);
        Vec3 from = Vec3.createVectorHelper(a[0] + rx, a[1] + ry, a[2] + rz);
        Vec3 dir = Vec3.createVectorHelper(b[0] - a[0], b[1] - a[1], b[2] - a[2]).normalize();
        Vec3 to = from.addVector(dir.xCoord * 200, dir.yCoord * 200, dir.zCoord * 200);
        return new Vec3[] { from, to };
    }

    /** what is under the mouse: a living thing (nearest along the ray), else a block */
    Object pick(int mx, int my) {
        Vec3[] r = ray(mx, my);
        if (r == null) return null;
        if (Slice.active()) {
            // Picking must start beneath the roof hidden by the observer's cut plane.
            double dy = r[1].yCoord - r[0].yCoord;
            if (r[0].yCoord >= Slice.level && dy < -1e-6) {
                double t = (r[0].yCoord - Slice.level + 0.001) / -dy;
                r[0] = Vec3.createVectorHelper(r[0].xCoord + (r[1].xCoord - r[0].xCoord) * t,
                        Slice.level - 0.001, r[0].zCoord + (r[1].zCoord - r[0].zCoord) * t);
            }
        }
        MovingObjectPosition hit = mc.theWorld.rayTraceBlocks(Vec3.createVectorHelper(r[0].xCoord, r[0].yCoord, r[0].zCoord), Vec3.createVectorHelper(r[1].xCoord, r[1].yCoord, r[1].zCoord), false, true, false);
        double limit = hit == null ? 1e9 : hit.hitVec.distanceTo(r[0]);
        Entity best = null;
        double bd = limit;
        for (Object o : mc.theWorld.loadedEntityList) {
            if (!(o instanceof EntityLivingBase) || o == mc.thePlayer && !selfUnit() || o == eye) continue;   // the player himself: the crew's number 1
            Entity en = (Entity) o;
            if (Slice.active() && en.boundingBox.minY >= Slice.level) continue;
            if (Fog.instance != null && Fog.instance.active() && !(en instanceof Agent) && Fog.instance.stateAt(en.posX, en.posZ) != 2) continue;   // hidden in the fog
            AxisAlignedBB bb = en.boundingBox.expand(0.35, 0.2, 0.35);
            MovingObjectPosition m = bb.calculateIntercept(r[0], r[1]);
            if (m == null) continue;
            double d = m.hitVec.distanceTo(r[0]);
            if (d < bd) { bd = d; best = en; }
        }
        if (best != null) return best;
        return hit;   // (blocks only: the trace returns a block or nothing)
    }

    // ---- picking agents and giving orders (the screen's clicks and the director's lines both come here) --

    void select(int skin, boolean add) {
        if (!add) selected.clear();
        selected.add(skin);
    }

    void selectBox(int x0, int y0, int x1, int y1, boolean add) {
        if (!add) selected.clear();
        List<EntityLivingBase> units = new ArrayList<EntityLivingBase>(clientAgents());
        if (selfUnit()) units.add(mc.thePlayer);
        for (EntityLivingBase a : units) {
            float[] s = toScreen(a.posX, a.boundingBox.minY + 0.9, a.posZ);
            if (s != null && s[0] >= Math.min(x0, x1) && s[0] <= Math.max(x0, x1) && s[1] >= Math.min(y0, y1) && s[1] <= Math.max(y0, y1)) selected.add(unitOf(a));
        }
    }

    /** every unit there is: the agents in sight and the player (a double click) */
    void selectAll() {
        for (Agent a : clientAgents()) selected.add(a.skin());
        if (selfUnit()) selected.add(1);
    }

    int[] picked() {
        int[] out = new int[selected.size()];
        int i = 0;
        for (int s : selected) out[i++] = s;
        return out;
    }

    /** the right click: what is under it, ordered to the picked ones */
    void order(Object target, boolean queue) {
        if (selected.isEmpty() || target == null) return;
        if (target instanceof Entity) {
            Entity en = (Entity) target;
            if (en instanceof IMob && en instanceof EntityLiving) {
                Orders.give(picked(), Orders.Kind.ATTACK, (int) Math.floor(en.posX), (int) Math.floor(en.posY), (int) Math.floor(en.posZ), en.getEntityId(), queue);
                mark(en.posX, en.boundingBox.minY, en.posZ, 0xFF4040);
            } else {   // a friend: go to it
                orderBlock((int) Math.floor(en.posX), (int) Math.floor(en.boundingBox.minY) - 1, (int) Math.floor(en.posZ), 1, queue);
            }
            return;
        }
        MovingObjectPosition m = (MovingObjectPosition) target;
        if (Fog.instance != null && Fog.instance.active() && !Fog.known(m.blockX, m.blockZ)) {   // the unexplored: only a scout's walk there
            Orders.give(picked(), Orders.Kind.MOVE, m.blockX, m.blockY, m.blockZ, -1, queue);
            mark(m.blockX + 0.5, m.blockY + 1, m.blockZ + 0.5, 0x80C0FF);
            return;
        }
        orderBlock(m.blockX, m.blockY, m.blockZ, m.sideHit, queue);
    }

    /** a click on this block (from its side): what it means, to the picked ones */
    void orderBlock(int x, int y, int z, int side, boolean queue) {
        if (selected.isEmpty()) return;
        Orders.Kind k = Orders.meaning(mc.theWorld, Crew.instance == null ? null : Crew.instance.chest, x, y, z);
        if (k == Orders.Kind.MOVE && side != 1) {   // a side of a block: the ground in front of it
            int[][] d = { { 0, -1, 0 }, { 0, 1, 0 }, { 0, 0, -1 }, { 0, 0, 1 }, { -1, 0, 0 }, { 1, 0, 0 } };
            x += d[side][0];
            z += d[side][2];
            y = Math.max(1, mc.theWorld.getHeightValue(x, z) - 1);
        }
        Orders.give(picked(), k, x, y, z, -1, queue);
        mark(x + 0.5, y + 1, z + 0.5, k == Orders.Kind.MOVE ? 0x40FF60 : k == Orders.Kind.DEPOSIT ? 0x40A0FF : 0xFFC040);
    }

    void mark(double x, double y, double z, int color) {
        mark = new double[] { x, y, z };
        markAt = System.currentTimeMillis();
        markColor = color;
    }

    /** the director's way in (a script, a test): the camera at a point, looking from a turn, at a distance */
    void look(double x, double z, double turn, double d) {
        px = sx = x; pz = sz = z; yaw = syaw = turn; dist = sdist = d;
        placed = true;
    }

    private void centerSurfaceView() {
        Agent a = renderedAgent(viewedSkin);
        if (a != null) {
            px = sx = a.posX;
            pz = sz = a.posZ;
            py = sy = a.boundingBox.minY + 0.6;
        }
        dist = sdist = 22;
        pitch = 56;
    }

    // ---- blueprints: what can be built, as cells over the ground (x across, y up, z deep), the door left open --

    static final String[] BLUEPRINTS = { "стена 5", "хижина 5x5", "башня 3x3", "столб" };
    static final String[] MATERIALS = { "planks", "cobblestone" };
    int blueprint = -1, material, turn;   // -1: not building

    /** the cells of a blueprint at a corner, turned by quarter turns, lowest first (each stands on the one under it) */
    static List<int[]> cells(int bp, int x0, int y0, int z0, int turn) {
        List<int[]> out = new ArrayList<int[]>();
        int w = bp == 0 ? 5 : bp == 1 ? 5 : bp == 2 ? 3 : 1, d = bp == 0 ? 1 : w, h = bp == 0 ? 2 : bp == 1 ? 3 : bp == 2 ? 5 : 4;
        for (int y = 0; y < h; y++)
            for (int i = 0; i < w; i++)
                for (int j = 0; j < d; j++) {
                    boolean ring = i == 0 || j == 0 || i == w - 1 || j == d - 1;
                    if (!ring) continue;
                    if (bp == 1 && j == 0 && i == w / 2 && y < 2) continue;   // the hut's door
                    int a = i, b = j;
                    for (int t = 0; t < (turn & 3); t++) { int c = a; a = -b; b = c; }
                    out.add(new int[] { x0 + a, y0 + y, z0 + b });
                }
        return out;
    }

    /** where the blueprint goes: on the ground under the mouse */
    int[] buildAt() {
        Object o = pick(curX(), curY());
        if (!(o instanceof MovingObjectPosition)) return null;
        MovingObjectPosition m = (MovingObjectPosition) o;
        return new int[] { m.blockX, m.blockY + 1, m.blockZ };
    }

    /** the blueprint's blocks to the picked ones: each a queued place order, shared round the crew, the lowest first */
    void build(int[] at) {
        if (selected.isEmpty() || at == null) return;
        int[] who = picked();
        int k = 0;
        for (int[] c : cells(blueprint, at[0], at[1], at[2], turn)) {
            if (!mc.theWorld.isAirBlock(c[0], c[1], c[2])) continue;
            Orders.give(new int[] { who[k++ % who.length] }, Orders.Kind.PLACE, c[0], c[1], c[2], -1, true, MATERIALS[material], 1);
        }
        mark(at[0] + 0.5, at[1], at[2] + 0.5, 0xFFC040);
        Director.log("commander: build " + BLUEPRINTS[blueprint] + " of " + MATERIALS[material] + " at " + at[0] + " " + at[1] + " " + at[2] + " turned " + turn + ", " + k + " blocks");
    }

    @SubscribeEvent
    public void ghost(RenderWorldLastEvent e) {   // the blueprint under the mouse, see-through: green where it can go, red where something is
        if (!on || blueprint < 0 || !haveView) return;
        int[] at = buildAt();
        if (at == null) return;
        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glTranslated(-rx, -ry, -rz);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDepthMask(false);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        for (int[] c : cells(blueprint, at[0], at[1], at[2], turn)) {
            boolean free = mc.theWorld.isAirBlock(c[0], c[1], c[2]) || mc.theWorld.getBlock(c[0], c[1], c[2]).getMaterial().isReplaceable();
            t.setColorRGBA_F(free ? 0.3f : 1f, free ? 1f : 0.3f, 0.35f, 0.35f);
            double x0 = c[0] + 0.02, y0 = c[1] + 0.02, z0 = c[2] + 0.02, x1 = c[0] + 0.98, y1 = c[1] + 0.98, z1 = c[2] + 0.98;
            t.addVertex(x0, y1, z0); t.addVertex(x0, y1, z1); t.addVertex(x1, y1, z1); t.addVertex(x1, y1, z0);
            t.addVertex(x0, y0, z0); t.addVertex(x1, y0, z0); t.addVertex(x1, y0, z1); t.addVertex(x0, y0, z1);
            t.addVertex(x0, y0, z0); t.addVertex(x0, y1, z0); t.addVertex(x1, y1, z0); t.addVertex(x1, y0, z0);
            t.addVertex(x0, y0, z1); t.addVertex(x1, y0, z1); t.addVertex(x1, y1, z1); t.addVertex(x0, y1, z1);
            t.addVertex(x0, y0, z0); t.addVertex(x0, y0, z1); t.addVertex(x0, y1, z1); t.addVertex(x0, y1, z0);
            t.addVertex(x1, y0, z0); t.addVertex(x1, y1, z0); t.addVertex(x1, y1, z1); t.addVertex(x1, y0, z1);
        }
        t.draw();
        GL11.glDepthMask(true);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glPopAttrib();
        GL11.glPopMatrix();
    }

    // ---- pointing at the world: what is under the mouse, what a click would do, the painted designations ----------

    static final String[] PAINT_MATERIALS = { "planks", "cobblestone", "dirt", "log", "glass" };
    int paintMat, depth = 1;
    // a stroke being painted: from, to (blocks), build or dig, the height it lies at
    int[] paintFrom, paintTo;
    boolean painting, paintBuild;
    // an erase stroke (right button dragged)
    int[] eraseFrom, eraseTo;
    // an armed order (from the command card or its key): the next left click is it
    enum Armed { NONE, MOVE, CHOP, ATTACK, GUARD }
    Armed armed = Armed.NONE;
    // under the mouse now
    Object hover;
    List<int[]> hoverBlocks = new ArrayList<int[]>();
    String hoverLabel = "";
    String hoverL, hoverR;   // what the left and the right button would do (drawn by the mouse's icons)

    /** the blocks of a vein: the same block touching, up to 32 */
    List<int[]> vein(int x, int y, int z) {
        List<int[]> out = new ArrayList<int[]>();
        net.minecraft.block.Block b = mc.theWorld.getBlock(x, y, z);
        java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<int[]>();
        java.util.Set<Long> seen = new java.util.HashSet<Long>();
        q.add(new int[] { x, y, z });
        seen.add(Crew.key(x, y, z));
        while (!q.isEmpty() && out.size() < 32) {
            int[] c = q.poll();
            out.add(c);
            for (int[] d : Skills.SIDE) {
                int nx = c[0] + d[0], ny = c[1] + d[1], nz = c[2] + d[2];
                if (mc.theWorld.getBlock(nx, ny, nz) == b && seen.add(Crew.key(nx, ny, nz))) q.add(new int[] { nx, ny, nz });
            }
        }
        return out;
    }

    static boolean isOre(net.minecraft.block.Block b) {
        String n = net.minecraft.block.Block.blockRegistry.getNameForObject(b);
        return n != null && n.contains("_ore");
    }

    String nameOf(net.minecraft.block.Block b, int meta) {
        net.minecraft.item.Item it = net.minecraft.item.Item.getItemFromBlock(b);
        return it == null ? b.getLocalizedName() : new ItemStack(it, 1, b.damageDropped(meta)).getDisplayName();
    }

    /** what is under the mouse, its outline and what a click would do (every frame) */
    void hoverNow() {
        hoverBlocks.clear();
        hover = curIn() ? pick(curX(), curY()) : null;
        boolean alt = altDown();
        boolean ctrl = ctrlDown();
        String rmb = null, lmb = null;
        if (hover instanceof Entity) {
            Entity en = (Entity) hover;
            AxisAlignedBB bb = en.boundingBox;
            hoverBlocks.add(new int[] { Integer.MIN_VALUE });   // an entity: its box is drawn from hoverEntity
            if (en instanceof Agent) { lmb = "выбрать " + ((Agent) en).getCustomNameTag(); rmb = "идти к нему"; }
            else if (unitOf(en) == 1) { lmb = CrewKeys.tr("self.pick", selfLabel()); rmb = "идти к нему"; }
            else if (en instanceof IMob) { rmb = "атаковать: " + en.getCommandSenderName(); lmb = armed == Armed.ATTACK ? "атаковать" : null; }
            else rmb = "идти сюда";
        } else if (hover instanceof MovingObjectPosition && Fog.instance != null && Fog.instance.active() && !Fog.known(((MovingObjectPosition) hover).blockX, ((MovingObjectPosition) hover).blockZ)) {
            rmb = "разведать (идти туда)";   // the unexplored: nothing known there but the way
        } else if (hover instanceof MovingObjectPosition) {
            MovingObjectPosition m = (MovingObjectPosition) hover;
            net.minecraft.block.Block b = mc.theWorld.getBlock(m.blockX, m.blockY, m.blockZ);
            List<int[]> tree = (Orders.isLog(b) || b.getMaterial() == net.minecraft.block.material.Material.leaves) ? Fell.tree(mc.theWorld, m.blockX, m.blockY, m.blockZ) : new ArrayList<int[]>();
            int[] ch = Crew.instance == null ? null : Crew.instance.chest;
            if (alt) { hoverBlocks.add(new int[] { m.blockX, m.blockY, m.blockZ }); rmb = "только идти: встать сюда"; }
            else if (!tree.isEmpty()) { hoverBlocks.addAll(tree); rmb = "рубить дерево (" + tree.size() + " бр.)"; }
            else if (isOre(b)) { List<int[]> v = vein(m.blockX, m.blockY, m.blockZ); hoverBlocks.addAll(v); rmb = "копать: " + nameOf(b, mc.theWorld.getBlockMetadata(m.blockX, m.blockY, m.blockZ)) + " ×" + v.size(); }
            else if (ch != null && ch[0] == m.blockX && ch[1] == m.blockY && ch[2] == m.blockZ) { hoverBlocks.add(new int[] { m.blockX, m.blockY, m.blockZ }); rmb = "сложить в сундук"; }
            else { hoverBlocks.add(new int[] { m.blockX, m.blockY, m.blockZ }); rmb = "идти сюда"; }
            lmb = ctrl ? "строить: " + materialName() : "копать: " + nameOf(b, mc.theWorld.getBlockMetadata(m.blockX, m.blockY, m.blockZ));
        }
        if (armed != Armed.NONE) lmb = armed == Armed.MOVE ? "идти (только идти)" : armed == Armed.CHOP ? "рубить дерево" : armed == Armed.ATTACK ? "атаковать" : "охранять это место";
        StringBuilder l = new StringBuilder();
        if (lmb != null) l.append("ЛКМ: ").append(lmb);
        if (rmb != null && !selected.isEmpty() && armed == Armed.NONE) l.append(l.length() > 0 ? "   " : "").append("ПКМ: ").append(rmb);
        hoverLabel = l.toString();
        hoverL = lmb;
        hoverR = rmb != null && !selected.isEmpty() && armed == Armed.NONE ? rmb : null;
        PlaceLayer.hovered = null;
        if (PlaceLayer.shown() && armed == Armed.NONE && !(hover instanceof Agent) && curIn()) {   // a place under the mouse: the left click rates it
            PlaceView p = PlaceLayer.hit(ray(curX(), curY()));
            PlaceLayer.hovered = p;
            if (p != null) hoverL = hoverR = null;
            if (p != null) hoverLabel = FeedbackUi.t("place.hover", FeedbackUi.className(p.cls()), Math.round(p.confidence() * 100));
        }
    }

    String materialName() {
        net.minecraft.item.Item it = Compiler.item(PAINT_MATERIALS[paintMat]);
        return it == null ? PAINT_MATERIALS[paintMat] : new ItemStack(it).getDisplayName();
    }

    /** where the ray under the mouse meets the level y + 0.5 (a stroke keeps its height), as a block */
    int[] onLevel(int mx, int my, int y) {
        Vec3[] r = ray(mx, my);
        if (r == null) return null;
        double dy = r[1].yCoord - r[0].yCoord;
        if (Math.abs(dy) < 1e-6) return null;
        double t = (y + 0.5 - r[0].yCoord) / dy;
        if (t < 0) return null;
        return new int[] { MathHelper.floor_double(r[0].xCoord + (r[1].xCoord - r[0].xCoord) * t), y, MathHelper.floor_double(r[0].zCoord + (r[1].zCoord - r[0].zCoord) * t) };
    }

    /** the blocks a stroke takes: the rectangle from-to, as deep (dig: down) or as high (build: up) as the depth; only what would change */
    List<int[]> strokeCells(int[] a, int[] b, boolean build) {
        List<int[]> out = new ArrayList<int[]>();
        if (a == null || b == null) return out;
        int x0 = Math.min(a[0], b[0]), x1 = Math.max(a[0], b[0]), z0 = Math.min(a[2], b[2]), z1 = Math.max(a[2], b[2]);
        if ((x1 - x0 + 1) * (z1 - z0 + 1) * depth > 4096) return out;   // too big a stroke: nothing (a smaller one)
        for (int k = 0; k < depth; k++) {
            int y = build ? a[1] + k : a[1] - k;
            for (int x = x0; x <= x1; x++)
                for (int z = z0; z <= z1; z++) {
                    net.minecraft.block.Block bl = mc.theWorld.getBlock(x, y, z);
                    if (Fog.instance != null && Fog.instance.active() && !Fog.known(x, z)) continue;   // not in the unexplored
                    boolean empty = bl.getMaterial() == net.minecraft.block.material.Material.air || bl.getMaterial().isReplaceable();
                    if (build ? empty : !empty && !bl.getMaterial().isLiquid()) out.add(new int[] { x, y, z });
                }
        }
        return out;
    }

    @SubscribeEvent
    public void pointing(RenderWorldLastEvent e) {   // the outline under the mouse, the stroke, the painted designations, the queued orders' lines
        if (!on || !haveView) return;
        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_LINE_BIT);
        GL11.glTranslated(-rx, -ry, -rz);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDepthMask(false);
        Tessellator t = Tessellator.instance;
        // the painted designations, still to do (red: dig, blue: build; brighter when someone is on it)
        List<int[]> pd = Orders.painted();
        if (!pd.isEmpty()) {
            t.startDrawingQuads();
            for (int[] c : pd) box(t, c[0], c[1], c[2], c[3] == 1 ? 0.3f : 1f, c[3] == 1 ? 0.6f : 0.35f, c[3] == 1 ? 1f : 0.3f, c[4] == 1 ? 0.45f : 0.25f);
            t.draw();
        }
        // the stroke being painted
        if (painting && paintFrom != null) {
            t.startDrawingQuads();
            for (int[] c : strokeCells(paintFrom, paintTo, paintBuild)) box(t, c[0], c[1], c[2], paintBuild ? 0.3f : 1f, paintBuild ? 0.7f : 0.45f, paintBuild ? 1f : 0.2f, 0.4f);
            t.draw();
        }
        if (eraseFrom != null && eraseTo != null) {
            t.startDrawingQuads();
            int x0 = Math.min(eraseFrom[0], eraseTo[0]), x1 = Math.max(eraseFrom[0], eraseTo[0]), z0 = Math.min(eraseFrom[2], eraseTo[2]), z1 = Math.max(eraseFrom[2], eraseTo[2]);
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) box(t, x, eraseFrom[1], z, 0.6f, 0.6f, 0.6f, 0.25f);
            t.draw();
        }
        // the outline of what is under the mouse
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glLineWidth(2f);
        if (hover instanceof Entity) {
            AxisAlignedBB bb = ((Entity) hover).boundingBox;
            boolean mob = hover instanceof IMob;
            outline(t, bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ, mob ? 1f : 0.4f, mob ? 0.3f : 1f, 0.3f, 0.9f);
        } else for (int[] c : hoverBlocks) if (c.length == 3) outline(t, c[0], c[1], c[2], c[0] + 1, c[1] + 1, c[2] + 1, 1f, 1f, 0.4f, 0.8f);
        // each picked agent: a line through the places of its orders, in their order
        if (Crew.instance != null && !selected.isEmpty()) {
            try {
                for (Agent ca : clientAgents()) {
                    if (!selected.contains(ca.skin())) continue;
                    t.startDrawing(GL11.GL_LINE_STRIP);
                    t.setColorRGBA_F(0.4f, 1f, 0.5f, 0.8f);
                    t.addVertex(ca.posX, ca.boundingBox.minY + 0.1, ca.posZ);
                    Agent sa = Cam.serverAgent(ca.skin());
                    TaskBoard.Card held = sa == null ? null : Crew.instance.board.leased.get(sa);
                    if (held != null && held.at != null) t.addVertex(held.at[0], held.at[1] + 0.1, held.at[2]);
                    for (TaskBoard.Card k : new ArrayList<TaskBoard.Card>(Crew.instance.board.cards))
                        if (k != held && k.open() && k.at != null && k.only != null && sa != null && TaskBoard.same(k.only, sa) && k.origin == Origin.PLAYER) t.addVertex(k.at[0], k.at[1] + 0.1, k.at[2]);
                    t.draw();
                }
            } catch (Exception ex) {
                // the board changed meanwhile: the next frame draws it
            }
        }
        GL11.glDepthMask(true);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glPopAttrib();
        GL11.glPopMatrix();
    }

    static void box(Tessellator t, int bx, int by, int bz, float r, float g, float b, float a) {
        t.setColorRGBA_F(r, g, b, a);
        double x0 = bx + 0.02, y0 = by + 0.02, z0 = bz + 0.02, x1 = bx + 0.98, y1 = by + 0.98, z1 = bz + 0.98;
        t.addVertex(x0, y1, z0); t.addVertex(x0, y1, z1); t.addVertex(x1, y1, z1); t.addVertex(x1, y1, z0);
        t.addVertex(x0, y0, z0); t.addVertex(x1, y0, z0); t.addVertex(x1, y0, z1); t.addVertex(x0, y0, z1);
        t.addVertex(x0, y0, z0); t.addVertex(x0, y1, z0); t.addVertex(x1, y1, z0); t.addVertex(x1, y0, z0);
        t.addVertex(x0, y0, z1); t.addVertex(x1, y0, z1); t.addVertex(x1, y1, z1); t.addVertex(x0, y1, z1);
        t.addVertex(x0, y0, z0); t.addVertex(x0, y0, z1); t.addVertex(x0, y1, z1); t.addVertex(x0, y1, z0);
        t.addVertex(x1, y0, z0); t.addVertex(x1, y1, z0); t.addVertex(x1, y1, z1); t.addVertex(x1, y0, z1);
    }

    static void outline(Tessellator t, double x0, double y0, double z0, double x1, double y1, double z1, float r, float g, float b, float a) {
        t.startDrawing(GL11.GL_LINES);
        t.setColorRGBA_F(r, g, b, a);
        double[][] c = { { x0, y0, z0 }, { x1, y0, z0 }, { x1, y0, z1 }, { x0, y0, z1 }, { x0, y1, z0 }, { x1, y1, z0 }, { x1, y1, z1 }, { x0, y1, z1 } };
        int[][] e = { { 0, 1 }, { 1, 2 }, { 2, 3 }, { 3, 0 }, { 4, 5 }, { 5, 6 }, { 6, 7 }, { 7, 4 }, { 0, 4 }, { 1, 5 }, { 2, 6 }, { 3, 7 } };
        for (int[] k : e) { t.addVertex(c[k[0]][0], c[k[0]][1], c[k[0]][2]); t.addVertex(c[k[1]][0], c[k[1]][1], c[k[1]][2]); }
        t.draw();
    }

    // ---- the screen: the mouse free, the panel ---------------------------------------------------------

    static final String[][] CRAFTS = { { "wooden_pickaxe", "1" }, { "stone_pickaxe", "1" }, { "iron_pickaxe", "1" }, { "iron_sword", "1" }, { "torch", "8" },
            { "furnace", "1" }, { "crafting_table", "1" }, { "chest", "1" } };
    static final String[][] SMELTS = { { "iron_ore", "1" }, { "iron_ore", "4" }, { "iron_ore", "8" } };

    static final class RoutineLayout {
        static boolean compact(int h) { return h < 294; }
        static int dialogH(int h) { return Math.min(278, h - 16); }
        static int top(int h) { return Math.max(8, (h - dialogH(h)) / 2); }
        static int fieldY(int h, int i) { return top(h) + (compact(h) ? 30 + i * 22 : 36 + i * 30); }
        static int bottomY(int h) { return top(h) + dialogH(h) - (compact(h) ? 25 : 34); }
        static int rows(int h) { return compact(h) ? 5 : 6; }
    }

    class Screen extends GuiScreen {
        int downX, downY, downButton = -1;
        boolean dragging, selecting;
        long lastClick;
        int lastClickSkin = -1;
        static final int ROW = 22;
        net.minecraft.client.gui.GuiTextField chat;
        String sentText = "";
        boolean fullInv;          // the one picked agent's whole inventory, large
        // what the panel drew last frame, for clicks: kind, agent, slot, box
        final List<Object[]> hits = new ArrayList<Object[]>();
        Object[] dragFrom;        // an item picked up from an agent's inventory (to hand it to another)
        final int[] chatAt = new int[3];
        boolean routinesOpen, routineEditing, routineDeleting, draftDeposit;
        String routineSelected, routineOriginal, routineError;
        int routineOffset;
        final List<Object[]> routineHits = new ArrayList<Object[]>();
        net.minecraft.client.gui.GuiTextField[] routineFields;

        /** the band the interface keeps to: x from, x to (gui units) */
        int bandX0() { return safeAspect <= 0 ? 0 : Math.max(0, (int) (width - height * safeAspect) / 2); }
        int bandX1() { return safeAspect <= 0 ? width : Math.min(width, (int) (width + height * safeAspect) / 2); }
        int bandW() { return Math.min(bandX1() - bandX0(), 420); }
        int px0() { return (bandX0() + bandX1()) / 2 - bandW() / 2; }

        List<Agent> selAgents() {
            List<Agent> out = new ArrayList<Agent>();
            for (int s : selected) { Agent a = Cam.serverAgent(s); if (a != null) out.add(a); }
            return out;
        }

        @Override public boolean doesGuiPauseGame() { return false; }

        @Override public void onGuiClosed() { Keyboard.enableRepeatEvents(false); close(); }

        @Override
        public void initGui() {
            buttonList.clear();
            Keyboard.enableRepeatEvents(true);
            String keep = chat == null ? "" : chat.getText();
            boolean focus = chat != null && chat.isFocused();
            chat = new net.minecraft.client.gui.GuiTextField(fontRendererObj, px0() + 12, 0, bandW() - 24, 12);
            chat.setMaxStringLength(240);
            chat.setEnableBackgroundDrawing(false);
            chat.setText(keep);
            chat.setFocused(focus);
        }

        final UiKit kit = new UiKit();
        UiLayout lay;              // this frame's layout (UiLayout), in the band
        int ox;                    // the band's left edge (a phone's 9:16 band for a video; else 0)
        String openMenu;           // a "..." menu open: "bar", "card", "toggles"
        String popup;              // the craft's or the smelt's list open
        boolean helpOpen, journalOpen;
        int guideSeen = GuiGuide.history.size();   // the guide's words up to here are shown already
        boolean waitingGuide;      // a question is out, no answer yet
        long sentAtMs;

        /** to the guide: what was written, and who it is for (the picked agents, or the whole crew); a goal (/цель) or a crew command (/агент) is done here */
        void send(final String text) {
            if (text.trim().isEmpty()) return;
            final String q = text.trim();
            final String player = mc.thePlayer == null ? "Player" : mc.thePlayer.getCommandSenderName();
            String[] goal = Mind.command(q);
            if (goal != null && CrewKeys.instance != null) { AgentMind.fromWindow(player, goal); return; }
            if (CrewCommand.parse(q) != null && CrewKeys.instance != null) { CrewKeys.instance.command(q, agentsPicked()); return; }
            if (WaveCommand.parse(q) != null && CrewKeys.instance != null) { CrewKeys.instance.waves(q); return; }
            StringBuilder who = new StringBuilder();
            for (int s : selected) who.append(who.length() > 0 ? "," : "").append(s);
            final String ask = (who.length() > 0 ? "[из режима командира; выбраны агенты " + who + (selected.contains(1) && selfUnit() ? " (1 — это сам игрок, его персонаж ведёт автопилот: только идти, рубить, копать, сложить в сундук, бить, ставить)" : "")
                    + " — приказы только им: crew_order с agents=" + who + " (действие в месте) или цель crew_goal с agents=" + who + " (чего добиться: агент сам решает шаги)] "
                    : "[из режима командира; вся команда] ")
                    + "[ответ игроку — коротко и его словами: без названий инструментов, параметров и координат; остановить — кнопка «Стоп» на панели или клавиша H; вернуть под план — «По плану» или R] "
                    + q;
            sentText = q;
            sentAtMs = System.currentTimeMillis();
            guideSeen = GuiGuide.history.size();
            final boolean here = guideHere();
            waitingGuide = here;
            final int[] who2 = picked();
            new Thread(new Runnable() {
                public void run() {
                    try {
                        Routines.Routine known = RoutineIntent.quickRun(text) ? Routines.match(q, 0.80) : null;
                        if (known != null) {
                            int[] ws = who2;
                            if (ws.length == 0) { List<Integer> all = new ArrayList<Integer>(); for (Agent g : clientAgents()) all.add(g.skin()); ws = new int[all.size()]; for (int i = 0; i < ws.length; i++) ws[i] = all.get(i); }
                            Routines.run(ws, known.name);
                            waitingGuide = false;
                            toast("Рутина «" + known.name + "»: " + known.description, false);
                            return;
                        }
                        if (!here) { toast(UiKit.ui("state.noguide"), true); return; }
                        java.lang.reflect.Method m = Class.forName("hardelele.aiguide.Tools").getDeclaredMethod("crewAsk", String.class, String.class);
                        m.setAccessible(true);
                        Director.log("commander: asked the guide: " + q + " -> " + m.invoke(null, player, ask));
                    } catch (Throwable ex) {
                        Director.log("commander: the guide is not there: " + ex);
                        guideThere = Boolean.FALSE;
                        waitingGuide = false;
                        toast(UiKit.ui("state.noguide"), true);
                    }
                }
            }, "commander-ask").start();
        }

        String doingRu(String kind) {
            if (kind.startsWith("routine ")) return "рутина «" + kind.substring(8) + "»";
            String k = kind.replace("order ", "").replace("paint ", "");
            String[][] m = { { "move", "идёт" }, { "chop", "рубит" }, { "mine", "копает" }, { "attack", "атакует" }, { "deposit", "несёт в сундук" }, { "guard", "охраняет" },
                    { "craft", "крафтит" }, { "smelt", "плавит" }, { "place", "строит" }, { "ore", "добывает руду" }, { "make", "крафтит" }, { "inside", "в дом" },
                    { "light", "ставит факелы" }, { "recover", "за своими вещами" }, { "camp", "лагерь" }, { "carry", "носит" }, { "torches", "факелы" }, { "dig", "копает" },
                    { "build", "строит" }, { "give", "передаёт" } };
            for (String[] p : m) if (k.equals(p[0])) return p[1];
            return k;
        }

        String doing(Agent a) {
            String m = MindUi.doing(a.skin());   // its own mind: what that has it do
            if (m != null) return m;
            TaskBoard.Card k = Crew.instance == null ? null : Crew.instance.board.leased.get(a);
            return k != null ? doingRu(k.kind) : a.commanded ? "ждёт приказа" : "по плану";
        }

        /** an armed order (M go, B chop, X attack, G guard, or its button on the card): the next left click is it */
        void arm(Armed a) { armed = armed == a ? Armed.NONE : a; blueprint = -1; }

        @Override
        protected void keyTyped(char c, int code) {
            lastInput = vOn ? lastInput : System.currentTimeMillis();
            if (fb.open()) { fb.key(c, code); return; }
            if (routinesOpen) { routineKey(c, code); return; }
            if (chat.isFocused()) {   // writing to the guide: the keys are the text's
                if (code == Keyboard.KEY_RETURN) { send(chat.getText()); chat.setText(""); chat.setFocused(false); }
                else if (code == Keyboard.KEY_ESCAPE) chat.setFocused(false);
                else chat.textboxKeyTyped(c, code);
                return;
            }
            if (code == Keyboard.KEY_F1) { helpOpen = !helpOpen; return; }
            if (code == Keyboard.KEY_ESCAPE && (helpOpen || openMenu != null || popup != null)) { helpOpen = false; openMenu = null; popup = null; return; }
            if (code == Keyboard.KEY_RETURN || code == Keyboard.KEY_T) { chat.setFocused(true); return; }
            if (code == Keyboard.KEY_ESCAPE && (blueprint >= 0 || armed != Armed.NONE || painting || fullInv)) { blueprint = -1; armed = Armed.NONE; painting = false; fullInv = false; return; }
            if (code == Keyboard.KEY_ESCAPE || code == key.getKeyCode()) { mc.displayGuiScreen(null); return; }
            boolean ctrl = ctrlDown(), shift = shiftDown();
            if (code >= Keyboard.KEY_1 && code <= Keyboard.KEY_4) {
                int g = code - Keyboard.KEY_1 + 1;
                if (ctrl) groups.put(g, new LinkedHashSet<Integer>(selected));
                else if (groups.containsKey(g)) { selected.clear(); selected.addAll(groups.get(g)); }
                return;
            }
            if (code == Keyboard.KEY_Y) Fog.on = !Fog.on;
            if (code == Keyboard.KEY_L) { togglePlaces(); return; }
            if (code == Keyboard.KEY_J) { fb.openActions(); return; }
            if (code == Keyboard.KEY_U && viewedSkin > 0) {
                if (CaveView.on) {
                    CaveView.exit();
                    if (!viewedUnderground) centerSurfaceView();
                    else { dist = 22; pitch = 56; }
                }
                else if (Slice.on) Slice.exit();
                else if (viewedUnderground) { Slice.follow = true; Slice.enter(); }
            }
            if (code == Keyboard.KEY_C && viewedSkin > 0 && (viewedUnderground || CaveView.on)) {
                if (CaveView.on) {
                    CaveView.exit();
                    if (viewedUnderground) { Slice.follow = true; Slice.enter(); dist = 11; pitch = 52; }
                    else centerSurfaceView();
                }
                else CaveView.enter();
            }
            if (Slice.on && code == Keyboard.KEY_HOME) { Slice.follow = true; Slice.updateFollow(); }
            if (Slice.on && code == Keyboard.KEY_PRIOR) Slice.adjust(1);
            if (Slice.on && code == Keyboard.KEY_NEXT) Slice.adjust(-1);
            if (code == Keyboard.KEY_M) arm(Armed.MOVE);
            if (code == Keyboard.KEY_B) arm(Armed.CHOP);
            if (code == Keyboard.KEY_X) arm(Armed.ATTACK);
            if (code == Keyboard.KEY_G) arm(Armed.GUARD);
            if (code == Keyboard.KEY_I && selected.size() == 1) fullInv = !fullInv;
            if (code == Keyboard.KEY_F && CaveView.active()) {
                if (CaveView.toggleFocus()) {
                    dist = CaveView.focused() ? 9 : 45;
                    pitch = CaveView.focused() ? 35 : 42;
                    Director.log("cave view: focus " + (CaveView.focused() ? "agent" : "map"));
                } else Director.log("cave view: focus unavailable outside mapped component");
                return;
            }
            if (code == Keyboard.KEY_K || code == Keyboard.KEY_P || code == Keyboard.KEY_F || code == Keyboard.KEY_N) filmKey(code, shift);
            if (code == Keyboard.KEY_V && Cam.instance != null) { Cam.instance.autoOn = false; Cam.instance.release(); }
            if (code == Keyboard.KEY_H && !selected.isEmpty()) Orders.give(picked(), Orders.Kind.STOP, 0, 0, 0, -1, false);
            if (code == Keyboard.KEY_R && !selected.isEmpty()) Orders.give(picked(), Orders.Kind.RELEASE, 0, 0, 0, -1, false);
            if (CrewKeys.add != null && code == CrewKeys.add.getKeyCode()) addAgent(true);   // one more agent, at the cursor
            if (code == Keyboard.KEY_DELETE) dismissPicked();                             // the picked agents let go
            if (CrewKeys.self != null && code == CrewKeys.self.getKeyCode() && CrewKeys.instance != null) CrewKeys.instance.toggleSelf();
        }

        /** an armed order at the click: go there only, chop the tree, attack the mob, guard the place */
        void armedClick(Object o, boolean shift) {
            if (selected.isEmpty() || o == null) return;
            if (armed == Armed.ATTACK && o instanceof Entity && o instanceof IMob) order(o, shift);
            else if (o instanceof MovingObjectPosition) {
                MovingObjectPosition m = (MovingObjectPosition) o;
                if (armed == Armed.MOVE) { Orders.give(picked(), Orders.Kind.MOVE, m.blockX, m.blockY, m.blockZ, -1, shift); mark(m.blockX + 0.5, m.blockY + 1, m.blockZ + 0.5, 0x40FF60); }
                else if (armed == Armed.CHOP) { Orders.give(picked(), Orders.Kind.CHOP, m.blockX, m.blockY, m.blockZ, -1, shift); mark(m.blockX + 0.5, m.blockY + 1, m.blockZ + 0.5, 0xFFC040); }
                else if (armed == Armed.GUARD) { Orders.give(picked(), Orders.Kind.GUARD, m.blockX, m.blockY, m.blockZ, -1, shift); mark(m.blockX + 0.5, m.blockY + 1, m.blockZ + 0.5, 0xFF8040); }
            }
            if (!shift) armed = Armed.NONE;
        }

        @Override
        public void handleMouseInput() {
            if (!Display.isActive() || vOn) return;   // the window is not the player's now (or a script's hand is on it)
            lastInput = System.currentTimeMillis();
            input(Mouse.getEventButton(), Mouse.getEventButtonState(), Mouse.getEventX(), Mouse.getEventY(), Mouse.getEventDWheel(), Mouse.getEventDX());
        }

        /** a click on the interface: a button, a menu's line, a chip, the chat, an item; an item dragged onto another agent: handed over */
        boolean panelInput(int b, boolean state, int gx, int gy) {
            Object[] h = null;
            for (Object[] x : hits) if (gx >= (Integer) x[3] && gx < (Integer) x[5] && gy >= (Integer) x[4] && gy < (Integer) x[6]) h = x;
            if (state) {
                chat.setFocused(h != null && h[0].equals("chat"));
                if (helpOpen) { helpOpen = false; return true; }
                String id = h == null ? "" : String.valueOf(h[1]);
                if (!(h != null && (h[0].equals("menu") || id.startsWith("more:")))) openMenu = null;
                if (!(h != null && (id.equals("craft") || id.equals("smelt") || popup != null && id.startsWith(popup + ":")))) popup = null;
                if (h == null || b != 0) return true;
                if (h[0].equals("view")) { select((Integer) h[1], shiftDown()); fullInv = false; return true; }
                if (h[0].equals("item")) { dragFrom = h; return true; }
                if (h[0].equals("menu")) { openMenu = null; pill(pillOf(UiIcon.valueOf(id.toUpperCase(java.util.Locale.ROOT)))); return true; }
                if (h[0].equals("pill")) pill(id);
                return true;
            }
            if (b == 0 && dragFrom != null) {   // let go: on the same agent: work with it; on another agent: hand it over
                int from = (Integer) dragFrom[1], slot = (Integer) dragFrom[2];
                if (h != null && (h[0].equals("item") || h[0].equals("agent") || h[0].equals("view")) && (Integer) h[1] != from) {
                    Agent a = Cam.serverAgent(from);
                    if (a != null && slot < a.bag.size()) {
                        ItemStack s = a.bag.get(slot);
                        Object name = net.minecraft.item.Item.itemRegistry.getNameForObject(s.getItem());
                        Orders.give(new int[] { from }, Orders.Kind.GIVE, 0, 0, 0, (Integer) h[1], false, String.valueOf(name), s.stackSize);
                        Director.log("commander: " + a.getCustomNameTag() + " hands " + s.stackSize + " " + Ledger.name(s) + " to agent " + h[1]);
                    }
                } else if (h != null && h[0].equals("item")) Orders.give(new int[] { from }, Orders.Kind.HOLD, 0, 0, 0, -1, false, null, slot);
                dragFrom = null;
            }
            return true;
        }

        void togglePlaces() {
            PlaceLayer.on = !PlaceLayer.on;
            if (PlaceLayer.on && PlaceSource.current == null) toast(FeedbackUi.t("places.nosource"), true);
            else if (PlaceLayer.on && PlaceSource.places().isEmpty()) toast(FeedbackUi.t("places.none"), false);
        }

        /** a button by its id (the same for a click, a menu's line and a director's script) */
        void pill(String what) {
            if (what.startsWith("more:")) { String m = what.substring(5); openMenu = m.equals(openMenu) ? null : m; return; }
            if (what.startsWith("craft:") || what.startsWith("smelt:")) {   // a line of the craft's or the smelt's list
                String[] p = what.split(":");
                popup = null;
                if (selected.isEmpty()) return;
                boolean craft = p[0].equals("craft");
                Orders.give(craft ? picked() : new int[] { picked()[0] }, craft ? Orders.Kind.CRAFT : Orders.Kind.SMELT, 0, 0, 0, -1, false, p[1], Integer.parseInt(p[2]));
                return;
            }
            if (what.equals("routines")) { openRoutines(); return; }
            if (what.equals("places")) { togglePlaces(); return; }
            if (what.equals("actions")) { fb.openActions(); return; }
            if (what.equals("share")) { GuiShare.open(null, true); return; }
            if (what.equals("chat")) { chat.setFocused(true); return; }
            if (what.equals("chatclose")) { chat.setFocused(false); return; }
            if (what.equals("help")) { helpOpen = !helpOpen; return; }
            if (what.equals("helpclose")) { helpOpen = false; return; }
            if (what.equals("journal") || what.equals("journal-more")) { journalOpen = !journalOpen; return; }
            if (what.equals("settings")) { mc.displayGuiScreen(new net.minecraft.client.gui.GuiControls(null, mc.gameSettings)); return; }
            if (what.equals("fog")) { Fog.on = !Fog.on; return; }
            if (what.equals("caves")) { typed(Keyboard.KEY_C); return; }
            if (what.equals("layer")) { typed(Keyboard.KEY_U); return; }
            if (what.equals("stall-ai")) {   // the plan stands: everyone back under the plan
                List<Integer> all = units();
                int[] who = new int[all.size()];
                for (int i = 0; i < who.length; i++) who[i] = all.get(i);
                if (who.length > 0) Orders.give(who, Orders.Kind.RELEASE, 0, 0, 0, -1, false);
                return;
            }
            if (what.startsWith("routine:")) {
                if (selected.isEmpty()) { toast("Сначала выбери агентов для сценария.", true); return; }
                Routines.run(picked(), what.substring(8));
                toast("Сценарий «" + what.substring(8) + "» запущен для выбранных агентов.", false);
                return;
            }
            if (what.equals("add")) { addAgent(false); return; }
            if (what.equals("dismiss")) { dismissPicked(); return; }
            if (what.equals("self")) { if (CrewKeys.instance != null) CrewKeys.instance.toggleSelf(); return; }
            if (what.equals("waves")) {   // no run: one at the normal level; a run: a pause or on; Shift: stopped
                if (CrewKeys.instance == null || !CrewKeys.instance.crewHere()) { toast(CrewKeys.tr("nocrew"), true); return; }
                Waves.button(shiftDown());
                return;
            }
            if (selected.isEmpty()) return;   // the rest are the picked ones' orders
            if (what.equals("move")) arm(Armed.MOVE);
            else if (what.equals("chop")) arm(Armed.CHOP);
            else if (what.equals("attack")) arm(Armed.ATTACK);
            else if (what.equals("guard")) arm(Armed.GUARD);
            else if (what.equals("mine")) toast(UiKit.ui("hint.mine"), false);
            else if (what.equals("place")) toast(UiKit.ui("hint.place", materialName()), false);
            else if (what.equals("deposit")) {
                int[] ch = Crew.instance == null ? null : Crew.instance.chest;
                if (ch == null) { toast(UiKit.ui("card.nochest"), true); return; }
                Orders.give(picked(), Orders.Kind.DEPOSIT, ch[0], ch[1], ch[2], -1, shiftDown());
                mark(ch[0] + 0.5, ch[1] + 1, ch[2] + 0.5, 0x40A0FF);
            }
            else if (what.equals("craft") || what.equals("smelt")) popup = what.equals(popup) ? null : what;
            else if (what.equals("goal")) {   // the chat with "/цель N ": the person writes what to reach
                StringBuilder n = new StringBuilder();
                for (int s : agentsPicked()) n.append(s).append(' ');
                chat.setFocused(true);
                chat.setText("/цель " + n);
            }
            else if (what.equals("inv")) fullInv = !fullInv;
            else if (what.equals("stop")) Orders.give(picked(), Orders.Kind.STOP, 0, 0, 0, -1, false);
            else if (what.equals("ai")) Orders.give(picked(), Orders.Kind.RELEASE, 0, 0, 0, -1, false);
            else if (what.equals("gather")) {   // the others to the first picked
                List<Agent> sel = selAgents();
                if (sel.size() > 1) {
                    Agent a = sel.get(0);
                    int[] rest = new int[sel.size() - 1];
                    for (int i = 1; i < sel.size(); i++) rest[i - 1] = sel.get(i).skin();
                    Orders.give(rest, Orders.Kind.MOVE, (int) Math.floor(a.posX), (int) Math.floor(a.posY) - 1, (int) Math.floor(a.posZ), -1, false);
                }
            }
        }

        void openRoutines() {
            routinesOpen = true; routineEditing = routineDeleting = false;
            routineError = null; routineOffset = 0;
            chat.setFocused(false);
            routineError = Routines.error();
            List<String> names = Routines.names();
            if (routineSelected == null || !names.contains(routineSelected)) routineSelected = names.isEmpty() ? null : names.get(0);
        }

        boolean routineCompact() { return RoutineLayout.compact(height); }
        int routineH() { return RoutineLayout.dialogH(height); }
        int routineX() { return px0() + 4; }
        int routineY() { return RoutineLayout.top(height); }
        int routineW() { return bandW() - 8; }
        int routineFieldY(int i) { return RoutineLayout.fieldY(height, i); }
        int routineBottomY() { return RoutineLayout.bottomY(height); }
        int routineRows() { return RoutineLayout.rows(height); }

        void beginRoutineEdit(boolean fresh) {
            Routines.Routine r = fresh ? null : Routines.definition(routineSelected);
            if (!fresh && r == null) { routineError = "Сценарий уже удалён."; return; }
            routineOriginal = fresh ? null : r.name;
            routineEditing = true; routineDeleting = false; routineError = null;
            draftDeposit = r == null || r.deposit;
            String[] values = r == null ? new String[] { "", "", "tree", "", "24" }
                    : new String[] { r.name, r.description, r.target, r.untilItem == null ? "" : r.untilItem + " " + r.untilCount, String.valueOf(r.radius) };
            routineFields = new net.minecraft.client.gui.GuiTextField[5];
            for (int i = 0; i < routineFields.length; i++) {
                routineFields[i] = new net.minecraft.client.gui.GuiTextField(fontRendererObj, routineX() + 112, routineFieldY(i), routineW() - 124, routineCompact() ? 14 : 16);
                routineFields[i].setMaxStringLength(i == 1 ? 200 : 80);
                routineFields[i].setText(values[i] == null ? "" : values[i]);
            }
            routineFields[0].setFocused(true);
        }

        void routineKey(char c, int code) {
            if (code == Keyboard.KEY_ESCAPE) {
                if (routineEditing || routineDeleting) { routineEditing = routineDeleting = false; routineError = null; }
                else routinesOpen = false;
                return;
            }
            if (!routineEditing) return;
            if (code == Keyboard.KEY_TAB) {
                for (int i = 0; i < routineFields.length; i++) if (routineFields[i].isFocused()) {
                    routineFields[i].setFocused(false);
                    routineFields[(i + 1) % routineFields.length].setFocused(true);
                    return;
                }
                routineFields[0].setFocused(true);
                return;
            }
            for (net.minecraft.client.gui.GuiTextField field : routineFields) field.textboxKeyTyped(c, code);
        }

        void routineInput(int b, boolean state, int gx, int gy, int wheel) {
            if (wheel != 0 && !routineEditing && !routineDeleting) {
                int max = Math.max(0, Routines.names().size() - routineRows());
                routineOffset = Math.max(0, Math.min(max, routineOffset + (wheel < 0 ? 1 : -1)));
            }
            if (b != 0 || !state) return;
            if (routineEditing) for (net.minecraft.client.gui.GuiTextField field : routineFields) field.mouseClicked(gx, gy, b);
            for (Object[] h : routineHits) if (gx >= (Integer) h[1] && gx < (Integer) h[3] && gy >= (Integer) h[2] && gy < (Integer) h[4]) {
                routineAction((String) h[0]);
                return;
            }
        }

        void routineAction(String id) {
            if (id.equals("close")) { routinesOpen = false; return; }
            if (id.equals("cancel")) { routineEditing = routineDeleting = false; routineError = null; return; }
            if (id.equals("new")) { beginRoutineEdit(true); return; }
            if (id.equals("edit")) { beginRoutineEdit(false); return; }
            if (id.equals("delete")) { if (routineSelected != null) { routineDeleting = true; routineError = null; } return; }
            if (id.equals("backDelete")) { routineDeleting = false; return; }
            if (id.equals("confirmDelete")) {
                String error = Routines.forget(routineSelected);
                if (error != null) { routineError = error; return; }
                List<String> names = Routines.names();
                routineSelected = names.isEmpty() ? null : names.get(0);
                routineDeleting = false;
                routineError = "Удалено. Уже запущенные задания продолжаются до «Стоп».";
                return;
            }
            if (id.equals("deposit")) { draftDeposit = !draftDeposit; return; }
            if (id.equals("save")) {
                int radius;
                try { radius = Integer.parseInt(routineFields[4].getText().trim()); }
                catch (NumberFormatException ex) { routineError = "Радиус: число от 4 до 64."; return; }
                String name = routineFields[0].getText().trim();
                String error = Routines.put(routineOriginal, name, routineFields[1].getText(), routineFields[2].getText(), routineFields[3].getText(), radius, draftDeposit);
                if (error != null) { routineError = error; return; }
                routineSelected = name; routineEditing = false;
                routineError = "Сохранено. Уже запущенные задания продолжают старую версию до «Стоп».";
                return;
            }
            if (id.equals("run")) {
                if (routineSelected == null) { routineError = "Сначала создай сценарий."; return; }
                if (selected.isEmpty()) { routineError = "Сначала выбери агентов на карте."; return; }
                Routines.run(picked(), routineSelected);
                routineError = "Запущено для выбранных агентов: " + selected;
                return;
            }
            if (id.startsWith("select:")) { routineSelected = id.substring(7); routineError = null; }
        }

        int routineButton(String title, String id, int x, int y, int mx, int my) {
            return UiKit.textButton(fontRendererObj, routineHits, title, id, x, y, mx, my, false, 5);
        }

        void routineDialog(int mx, int my) {
            Gui.drawRect(0, 0, width, height, 0xA0000000);
            int x = routineX(), y = routineY(), w = routineW();
            Ui.round(x, y, x + w, y + routineH(), 8, 0xF015222E);
            routineHits.clear();
            fontRendererObj.drawStringWithShadow(routineEditing ? (routineOriginal == null ? "Новый сценарий" : "Правка сценария") : "Сценарии мира", x + 12, y + 10, 0xFFFFFF);
            kit.begin(fontRendererObj, width, height, mx, my);
            kit.closeCross(routineHits, "close", x + w, y);
            if (routineEditing) {
                String[] labels = { "Имя", "Описание", "Цель", "До запаса", "Радиус" };
                for (int i = 0; i < labels.length; i++) {
                    int yy = routineFieldY(i);
                    fontRendererObj.drawString(labels[i], x + 12, yy + 4, 0xB8CBD8);
                    routineFields[i].drawTextBox();
                }
                routineButton(draftDeposit ? "Сносить в сундук: да" : "Сносить в сундук: нет", "deposit", x + 12, y + (routineCompact() ? 141 : 191), mx, my);
                fontRendererObj.drawString("Цель: tree / ore iron_ore / block stone", x + 12, y + (routineCompact() ? 160 : 213), 0x9EB0BD);
                routineButton("Сохранить", "save", x + 12, routineBottomY(), mx, my);
                routineButton("Отмена", "cancel", x + 100, routineBottomY(), mx, my);
            } else {
                List<String> names = Routines.names();
                int rows = routineRows();
                routineOffset = Math.min(routineOffset, Math.max(0, names.size() - rows));
                for (int i = routineOffset; i < names.size() && i < routineOffset + rows; i++) {
                    String n = names.get(i);
                    int yy = y + (routineCompact() ? 26 + (i - routineOffset) * 16 : 29 + (i - routineOffset) * 18);
                    int rh = routineCompact() ? 15 : 17;
                    Ui.round(x + 10, yy, x + w - 10, yy + rh, 3, n.equals(routineSelected) ? 0xD0446270 : 0x8023323E);
                    fontRendererObj.drawStringWithShadow(fontRendererObj.trimStringToWidth(n, w - 34), x + 16, yy + 3, 0xFFFFFF);
                    routineHits.add(new Object[] { "select:" + n, x + 10, yy, x + w - 10, yy + rh });
                }
                if (names.size() > rows) fontRendererObj.drawString("Колесо: список  " + (routineOffset + 1) + "–" + Math.min(names.size(), routineOffset + rows) + " / " + names.size(), x + 12, y + (routineCompact() ? 110 : 140), 0xA4B8C8);
                Routines.Routine r = Routines.definition(routineSelected);
                if (r != null) {
                    fontRendererObj.drawStringWithShadow(fontRendererObj.trimStringToWidth("Цель: " + r.target + "   радиус: " + r.radius + "   сундук: " + (r.deposit ? "да" : "нет"), w - 24), x + 12, y + (routineCompact() ? 126 : 160), 0xDFE8EF);
                    fontRendererObj.drawStringWithShadow("До запаса: " + (r.untilItem == null ? "без условия" : r.untilItem + " " + r.untilCount), x + 12, y + (routineCompact() ? 139 : 175), 0xB9CBD8);
                    List<?> lines = fontRendererObj.listFormattedStringToWidth(r.description == null ? "" : r.description, w - 24);
                    for (int i = 0; i < Math.min(routineCompact() ? 1 : 2, lines.size()); i++) fontRendererObj.drawString((String) lines.get(i), x + 12, y + (routineCompact() ? 152 : 190) + i * 10, 0xB9CBD8);
                } else fontRendererObj.drawString("Пока нет сохранённых сценариев.", x + 12, y + (routineCompact() ? 126 : 163), 0xB9CBD8);
                if (routineDeleting) {
                    if (routineError == null) fontRendererObj.drawStringWithShadow(fontRendererObj.trimStringToWidth("Удалить «" + routineSelected + "»? Запущенные задания продолжатся.", w - 24), x + 12, y + (routineCompact() ? 169 : 215), 0xFFE09A);
                    routineButton("Удалить", "confirmDelete", x + 12, routineBottomY(), mx, my);
                    routineButton("Отмена", "backDelete", x + 94, routineBottomY(), mx, my);
                } else {
                    if (routineError == null) fontRendererObj.drawString("Правка не меняет уже запущенные задания.", x + 12, y + (routineCompact() ? 169 : 215), 0x93AAB9);
                    int bx = x + 12;
                    bx = routineButton("Новый", "new", bx, routineBottomY(), mx, my);
                    if (r != null) {
                        bx = routineButton("Править", "edit", bx, routineBottomY(), mx, my);
                        bx = routineButton("Удалить", "delete", bx, routineBottomY(), mx, my);
                        routineButton("Запустить", "run", bx, routineBottomY(), mx, my);
                    }
                }
            }
            if (routineError != null) fontRendererObj.drawStringWithShadow(fontRendererObj.trimStringToWidth(routineError, w - 24), x + 12, y + (routineCompact() ? 169 : 228), 0xFFE0A0);
        }

        /** one mouse event (b -1: a move or the wheel), the person's or the script's: the same handling */
        void input(int b, boolean state, int mx, int my, int wheel, int dx) {
            int scale = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
            int gx = mx / scale, gy = (mc.displayHeight - my) / scale;
            if (fb.open()) {
                if (wheel != 0) fb.wheel(wheel);
                if (b == 0 && state) fb.click(Commander.this, gx, gy);
                if (b >= 0 && !state) downButton = -1;
                return;
            }
            if (routinesOpen) { routineInput(b, state, gx, gy, wheel); return; }
            if (b >= 0 && downButton < 0 && overUi(gx, gy)) { panelInput(b, state, gx, gy); return; }
            // The floating cave geometry is for observation. Vanilla block picking still sees
            // the uncut world, so world clicks must not turn its apparent faces into orders.
            if (CaveView.active() && b >= 0) {
                if (!state) { downButton = -1; selecting = false; dragging = false; dragFrom = null; painting = false; eraseFrom = null; }
                return;
            }
            if (b >= 0 && !state && dragFrom != null) { panelInput(b, false, gx, gy); return; }   // an item let go over the world: nothing
            boolean shift = shiftDown();
            boolean ctrl = ctrlDown();
            boolean alt = altDown();
            if (wheel != 0) {
                if (alt && Slice.active()) Slice.adjust(wheel > 0 ? 1 : -1);
                else if (shift) depth = Math.max(1, Math.min(8, depth + (wheel > 0 ? 1 : -1)));
                else if (ctrl) paintMat = (paintMat + (wheel > 0 ? 1 : PAINT_MATERIALS.length - 1)) % PAINT_MATERIALS.length;
                else dist = Math.max(6, Math.min(70, dist * (wheel > 0 ? 0.88 : 1.14)));
            }
            if (b == -1) {   // a move
                if (downButton >= 0 && (Math.abs(mx - downX) > 6 || Math.abs(my - downY) > 6)) dragging = true;
                if (downButton == 2) yaw += dx * 0.4;
                if (painting && paintFrom != null) { int[] to = onLevel(mx, my, paintLevel()); if (to != null) paintTo = new int[] { to[0], paintFrom[1], to[2] }; }
                if (downButton == 1 && dragging && eraseFrom != null) { int[] to = onLevel(mx, my, eraseFrom[1]); if (to != null) eraseTo = to; }
                return;
            }
            if (b < 0) return;
            if (state) {   // pressed
                chat.setFocused(false);
                downButton = b; downX = mx; downY = my; dragging = false;
                if (b == 0) {
                    Object o = pick(mx, my);
                    if (armed != Armed.NONE) { armedClick(o, shift); downButton = -1; return; }
                    if (unitOf(o) < 0 && PlaceLayer.shown()) {   // the places' layer on: a click on a place rates it (an agent is still picked)
                        PlaceView p = PlaceLayer.hit(ray(mx, my));
                        if (p != null) { fb.openPlace(p); downButton = -1; return; }
                    }
                    if (unitOf(o) > 0) { selecting = true; return; }   // an agent, or the player himself
                    if (o instanceof MovingObjectPosition) {   // a stroke: dig (or, with Ctrl, build on top of what is clicked)
                        MovingObjectPosition m = (MovingObjectPosition) o;
                        paintBuild = ctrl;
                        if (paintBuild) {
                            int[][] d = { { 0, -1, 0 }, { 0, 1, 0 }, { 0, 0, -1 }, { 0, 0, 1 }, { -1, 0, 0 }, { 1, 0, 0 } };
                            paintFrom = new int[] { m.blockX + d[m.sideHit][0], m.blockY + d[m.sideHit][1], m.blockZ + d[m.sideHit][2] };
                        } else paintFrom = new int[] { m.blockX, m.blockY, m.blockZ };
                        paintTo = paintFrom.clone();
                        painting = true;
                    } else selecting = true;
                } else if (b == 1) {
                    Object o = pick(mx, my);
                    if (o instanceof MovingObjectPosition) { MovingObjectPosition m = (MovingObjectPosition) o; eraseFrom = new int[] { m.blockX, m.blockY, m.blockZ }; eraseTo = null; }
                }
                return;
            }
            // released
            if (b == 0 && downButton == 0) {
                if (painting) {
                    List<int[]> cells = strokeCells(paintFrom, paintTo, paintBuild);
                    if (!cells.isEmpty()) {
                        String mat = buildMaterial();
                        Orders.designate(picked(), paintBuild, cells, mat);
                        mark(paintTo[0] + 0.5, paintTo[1] + 1, paintTo[2] + 0.5, paintBuild ? 0x4090FF : 0xFF6040);
                        Director.log("commander: " + (paintBuild ? "build " + mat : "dig") + " " + cells.size() + " blocks" + (selected.isEmpty() ? " (anyone)" : " (picked)"));
                    }
                    painting = false;
                    paintFrom = paintTo = null;
                } else if (selecting && dragging) selectBox(downX, downY, mx, my, shift);
                else if (selecting) {
                    Object o = pick(mx, my);
                    if (unitOf(o) > 0) {
                        int s = unitOf(o);
                        if (s == lastClickSkin && System.currentTimeMillis() - lastClick < 350) selectAll();
                        else select(s, shift);
                        lastClickSkin = s;
                        lastClick = System.currentTimeMillis();
                    } else if (!shift) selected.clear();
                    fullInv = fullInv && selected.size() == 1;
                }
                selecting = false;
            } else if (b == 1 && downButton == 1) {
                if (dragging && eraseFrom != null && eraseTo != null) Orders.erase(eraseFrom[0], eraseFrom[1] - 8, eraseFrom[2], eraseTo[0], eraseFrom[1] + 8, eraseTo[2]);
                else if (armed != Armed.NONE) armed = Armed.NONE;
                else if (alt) {   // only walk: onto the top of that block
                    Object o = pick(mx, my);
                    if (o instanceof MovingObjectPosition && !selected.isEmpty()) {
                        MovingObjectPosition m = (MovingObjectPosition) o;
                        Orders.give(picked(), Orders.Kind.MOVE, m.blockX, m.blockY, m.blockZ, -1, shift);
                        mark(m.blockX + 0.5, m.blockY + 1, m.blockZ + 0.5, 0x40FF60);
                    }
                } else order(pick(mx, my), shift);
                eraseFrom = eraseTo = null;
            }
            downButton = -1;
            dragging = false;
        }

        /** what a build stroke is made of: the one picked agent's chosen block, else the material of the wheel */
        String buildMaterial() {
            List<Agent> sel = selAgents();
            if (sel.size() == 1) {
                ItemStack p = sel.get(0).preferred;
                if (p != null && p.getItem() instanceof net.minecraft.item.ItemBlock) return String.valueOf(net.minecraft.item.Item.itemRegistry.getNameForObject(p.getItem()));
            }
            return PAINT_MATERIALS[paintMat];
        }

        int paintLevel() { return paintFrom == null ? 0 : paintFrom[1]; }

        int guiYOf(int gmy) { return gmy; }

        List<?> buttons() { return buttonList; }

        /** a key typed by a script's hand (the same handling as a person's) */
        void typed(int code) { keyTyped((char) 0, code); }

        @Override
        public void drawScreen(int gmx, int gmy, float pt) {
            drawn++;
            if (CaveView.active()) { hoverLabel = null; hoverL = hoverR = null; } else hoverNow();
            int scale = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
            if (vOn) { gmx = curX() / scale; gmy = (mc.displayHeight - curY()) / scale; }
            pollGuide();
            kit.begin(fontRendererObj, width, height, gmx, gmy);
            worldLabels(scale);
            PlaceLayer.labels(Commander.this, fontRendererObj, scale, mc.displayHeight);
            if (CaveView.active() && !CaveView.focused() && CaveView.liveExit()) {
                CaveGeometry.Result g = CaveView.volume;
                float[] s = toScreen(g.exitX + 0.5, g.exitY + 1.0, g.exitZ + 0.5);
                if (s != null && s[0] >= 0 && s[0] <= mc.displayWidth && s[1] >= 0 && s[1] <= mc.displayHeight) {
                    String text = "ОТКРЫТЫЙ ВЕРХ Y" + g.exitY;
                    int tw = fontRendererObj.getStringWidth(text);
                    int x = Math.max(2, Math.min(width - tw - 8, (int) (s[0] / scale) - tw / 2));
                    int y = Math.max(32, Math.min(height - 42, (int) ((mc.displayHeight - s[1]) / scale) + 4));
                    Ui.round(x - 4, y - 3, x + tw + 4, y + 10, 4, 0xD0203020);
                    fontRendererObj.drawStringWithShadow(text, x, y, 0xBFFF8B);
                }
            }
            if (dragging && selecting) {   // the box being dragged
                int x0 = downX / scale, y0 = (mc.displayHeight - downY) / scale, x1 = curX() / scale, y1 = (mc.displayHeight - curY()) / scale;
                Ui.round(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1), 2, 0x3050FF70);
            }
            // the interface: laid out by UiLayout in the band (the whole width, or a phone's 9:16 band for a video)
            ox = bandX0();
            int bw = bandX1() - bandX0();
            List<Object[]> fresh = new ArrayList<Object[]>();
            kit.begin(fontRendererObj, bw, height, gmx - ox, gmy);
            lay = UiLayout.commander(layoutInput(bw));
            GL11.glPushMatrix();
            GL11.glTranslatef(ox, 0, 0);
            topBar(fresh);
            WavesHud.strip(kit, lay.get("waves"), Waves.view);
            UiLayout.Box wb = lay.get("waves");   // an epoch on: its strip just under the waves' one
            if (wb != null && EpochsHud.shown(EpochRun.view)) EpochsHud.strip(kit, new UiLayout.Box("epochs", wb.x, wb.y1() + 2, wb.w, 16), EpochRun.view);
            messages(fresh);
            if (lay.get("card") != null) card(fresh);
            bottomBar(fresh);
            WavesHud.overlays(kit, bw, height);
            EpochsHud.card(kit, bw, height, EpochRun.view);
            if (openMenu != null) {
                UiLayout.Box m = lay.get(openMenu.equals("toggles") ? "toggle.more" : openMenu + ".more");
                if (m == null && openMenu.equals("card")) m = lay.get("card.order.more");
                if (m != null && !m.menu.isEmpty()) kit.menu(m); else openMenu = null;
            }
            if (popup != null) popup();
            if (helpOpen) help();
            GL11.glPopMatrix();
            for (Object[] o : kit.hits) {   // the kit's buttons as the panel's hits: {"pill", id, ...} (the director clicks them by id)
                String id = (String) o[0];
                boolean menu = id.startsWith("menu:");
                fresh.add(new Object[] { menu ? "menu" : "pill", menu ? id.substring(5) : id, 0, (Integer) o[1] + ox, (Integer) o[2], (Integer) o[3] + ox, (Integer) o[4] });
            }
            hits.clear();
            hits.addAll(fresh);
            cursorLabel(gmx, gmy);
            if (routinesOpen) routineDialog(gmx, gmy);
            if (fb.open()) fb.draw(fontRendererObj, px0(), bandW(), height, gmx, gmy);
            if (dragFrom != null) {   // the item being carried with the mouse
                Agent a = Cam.serverAgent((Integer) dragFrom[1]);
                int slot = (Integer) dragFrom[2];
                if (a != null && slot < a.bag.size()) icon(a.bag.get(slot), gmx - 8, gmy - 8);
            }
            GL11.glPushMatrix();
            GL11.glTranslatef(ox, 0, 0);
            if (!routinesOpen && !fb.open()) kit.flush();
            GL11.glPopMatrix();
            if (vOn || Recorder.instance != null) cursor(gmx, gmy, scale);
        }

        // ---- what the interface shows: the crew, the plan's state, the guide --------------------------------

        /** the crew's numbers in the order of their chips: the player (1) first, then the agents */
        List<Integer> units() {
            List<Integer> out = new ArrayList<Integer>();
            if (selfUnit()) out.add(1);
            List<Agent> roster = new ArrayList<Agent>();
            if (Crew.instance != null) for (Agent a : Crew.instance.agents) if (!a.isDead) roster.add(a);
            if (roster.isEmpty()) roster = clientAgents();
            for (Agent a : roster) if (!out.contains(a.skin())) out.add(a.skin());
            return out;
        }

        boolean me(int n) { return n == 1 && selfUnit(); }

        float health(int n) {
            EntityLivingBase e = me(n) ? mc.thePlayer : Cam.serverAgent(n);
            if (e == null) e = renderedAgent(n);
            return e == null || e.getMaxHealth() <= 0 ? 0 : e.getHealth() / e.getMaxHealth();
        }

        String name(int n) {
            if (me(n)) return selfLabel();
            Agent a = Cam.serverAgent(n);
            return a == null ? "Агент " + n : a.getCustomNameTag();
        }

        String doingOf(int n) {
            if (me(n)) return selfDoing();
            Agent a = Cam.serverAgent(n);
            return a == null ? "" : doing(a);
        }

        boolean thinking(int n) {
            String[] s = AgentMind.shown.get(n);
            return s != null && "thinking".equals(s[0]);
        }

        String goal(int n) {
            String[] s = AgentMind.shown.get(n);
            return s == null || s.length < 2 ? null : s[1];
        }

        /** what it does, as an icon: its mind's step, else its board card's kind */
        UiIcon doingIcon(int n) {
            String[] s = AgentMind.shown.get(n);
            if (s != null) {
                String k = s[0];
                if (k.equals("thinking")) return UiIcon.THINK;
                if (k.equals("chop_tree")) return UiIcon.CHOP;
                if (k.equals("mine")) return UiIcon.MINE;
                if (k.equals("craft")) return UiIcon.CRAFT;
                if (k.equals("smelt")) return UiIcon.SMELT;
                if (k.equals("place")) return UiIcon.PLACE;
                if (k.equals("put") || k.equals("take")) return UiIcon.DEPOSIT;
                if (k.equals("go_to") || k.equals("look_around")) return UiIcon.MOVE;
                if (k.equals("give")) return UiIcon.GATHER;
                return UiIcon.GOAL;
            }
            if (me(n)) return PlayerAgent.givenToAI ? UiIcon.SELF_AI : null;
            Agent a = Cam.serverAgent(n);
            TaskBoard.Card c = a == null || Crew.instance == null ? null : Crew.instance.board.leased.get(a);
            if (c == null) return a != null && a.commanded ? UiIcon.STOP : UiIcon.PLAN;
            if (c.kind.startsWith("routine ")) return UiIcon.SCENARIOS;
            String k = c.kind.replace("order ", "").replace("paint ", "");
            if (k.equals("chop")) return UiIcon.CHOP;
            if (k.equals("mine") || k.equals("ore") || k.equals("dig")) return UiIcon.MINE;
            if (k.equals("attack")) return UiIcon.ATTACK;
            if (k.equals("guard")) return UiIcon.GUARD;
            if (k.equals("deposit") || k.equals("carry")) return UiIcon.DEPOSIT;
            if (k.equals("craft") || k.equals("make")) return UiIcon.CRAFT;
            if (k.equals("smelt")) return UiIcon.SMELT;
            if (k.equals("place") || k.equals("build") || k.equals("light") || k.equals("torches")) return UiIcon.PLACE;
            if (k.equals("give")) return UiIcon.GATHER;
            return UiIcon.MOVE;
        }

        /** a picked agent too far to be drawn (the camera cannot go to it) */
        boolean away(int n) {
            if (me(n)) return false;
            Agent a = renderedAgent(n);
            if (a == null) return true;
            double lim = Math.max(8, mc.gameSettings.renderDistanceChunks * 16 - 28);
            return Math.hypot(a.posX - mc.thePlayer.posX, a.posZ - mc.thePlayer.posZ) > lim;
        }

        boolean stalled() { return Crew.instance != null && Crew.instance.board.stalled; }

        /** the view's mode in a line (the cave's map, the one layer), or null */
        String mode() {
            if (CaveView.active()) {
                CaveGeometry.Result g = CaveView.volume;
                boolean exitLive = CaveView.liveExit();
                String top = exitLive ? " · открытый верх ↑" : g != null && g.exit ? " · верх не подтверждён" : " · верх не проверен";
                return CaveView.holdingLast() ? "ЧАСТЬ: прежняя карта; обновление ждёт"
                        : g == null || CaveView.scanning() ? "Пещера: обновляю карту…" : g.cells == 0 ? "Пещера: нет доступной полости"
                        : g.limited() ? "ЧАСТЬ: Y" + g.minLevel + "–" + g.maxLevel + top
                        : "Полость Y" + g.minLevel + "–" + g.maxLevel + (g.exit ? top : " · верх не найден");
            }
            if (Slice.active()) return "Только один слой: Y=" + Slice.level + (Slice.follow ? " · за агентом" : " · вручную");
            return null;
        }

        UiLayout.Input layoutInput(int w) {
            UiLayout.Input in = new UiLayout.Input(w, height);
            in.units = units().size();
            in.selected = selected.size();
            in.player = selfUnit();
            in.chatOpen = chat.isFocused();
            in.toasts = toasts.live(System.currentTimeMillis());
            in.stall = stalled() || mode() != null;
            in.underground = viewedSkin > 0 && (viewedUnderground || CaveView.on || Slice.on);
            in.wideInventory = fullInv;
            in.journalOpen = journalOpen;
            in.mind = true;
            in.waves = WavesHud.shown(Waves.view);
            return in;
        }

        /** a message to the person: the commander's one place for them (the journal keeps it) */
        void toast(String text, boolean warn) {
            toasts.add(text, warn ? UiToasts.Kind.WARN : UiToasts.Kind.INFO, warn ? UiIcon.WARNING : UiIcon.LOG, System.currentTimeMillis());
        }

        /** the guide's new words since the last frame: a message each (30 s, then the journal) */
        void pollGuide() {
            int n = GuiGuide.history.size();
            if (guideSeen > n) guideSeen = n;
            for (int i = guideSeen; i < n; i++) {
                String[] h = GuiGuide.history.get(i);
                if (h[0] != null) continue;
                toasts.add(h[1].replace('\n', ' ').replaceAll("§.", ""), UiToasts.Kind.GUIDE, UiIcon.CHAT, System.currentTimeMillis());
                waitingGuide = false;
            }
            guideSeen = n;
            if (waitingGuide && System.currentTimeMillis() - sentAtMs > 120000) waitingGuide = false;
        }

        // ---- the pieces --------------------------------------------------------------------------------

        /** over each unit: its number and its health; its mind thinking: a small cloud over the number */
        void worldLabels(int scale) {
            List<EntityLivingBase> units = new ArrayList<EntityLivingBase>(clientAgents());
            if (selfUnit() && !CaveView.active()) units.add(mc.thePlayer);
            for (EntityLivingBase a : units) {
                if (Slice.active() && a.boundingBox.minY >= Slice.level) continue;
                if (CaveView.active() && !CaveView.containsEntity(a)) continue;
                float[] s = toScreen(a.posX, a.boundingBox.maxY + 0.45, a.posZ);
                if (s == null) continue;
                int n = unitOf(a);
                int x = (int) (s[0] / scale), y = (int) ((mc.displayHeight - s[1]) / scale);
                boolean sel = selected.contains(n);
                String t = String.valueOf(n);
                int tw = fontRendererObj.getStringWidth(t);
                Ui.round(x - tw / 2 - 4, y - 12, x + tw / 2 + 5, y - 1, 3, me(n) ? 0xE02F6FA8 : sel ? 0xE0204828 : 0xC0181C24);
                fontRendererObj.drawStringWithShadow(t, x - tw / 2 + 1, y - 10, sel ? 0x90FF90 : 0xFFFFFF);
                float hp = a.getMaxHealth() <= 0 ? 0 : a.getHealth() / a.getMaxHealth();
                Ui.round(x - 8, y, x + 8, y + 2, 0, 0xFF202020);
                Ui.round(x - 8, y, x - 8 + Math.round(16 * hp), y + 2, 0, hp > 0.5 ? 0xFF50E070 : hp > 0.25 ? 0xFFE0C040 : 0xFFE04040);
                if (thinking(n)) kit.icon(UiIcon.THINK, x - 4, y - 22, 8, 0.6f + 0.4f * (float) Math.abs(Math.sin(System.currentTimeMillis() / 300.0)));
            }
        }

        void topBar(List<Object[]> fresh) {
            UiLayout.Box tg = lay.get("toggles");
            if (tg != null) kit.panel(tg);
            List<Integer> units = units();
            List<UiLayout.Box> chips = lay.starting("roster.");
            for (UiLayout.Box b : chips) {
                if (b.id.equals("roster.more")) {
                    kit.panel(b.x, b.y, b.w, b.h, UiKit.PANEL);
                    String t = "+" + (units.size() - chips.size() + 1);
                    fontRendererObj.drawStringWithShadow(t, b.x + b.w / 2 - fontRendererObj.getStringWidth(t) / 2, b.y + 6, UiKit.TEXT);
                    continue;
                }
                int i = Integer.parseInt(b.id.substring(7)) - 1;
                if (i >= units.size()) continue;
                int n = units.get(i);
                boolean away = away(n);
                kit.chip(b, "chip:" + n, n, health(n), doingIcon(n), selected.contains(n), me(n), away);
                kit.hits.remove(kit.hits.size() - 1);   // (a chip is a "view" hit, not a pill)
                fresh.add(new Object[] { "view", n, -1, b.x + ox, b.y, b.x1() + ox, b.y1() });
                if (kit.over(b.x, b.y, b.w, b.h)) kit.tip(name(n) + " · " + (away ? UiKit.ui("state.away") : doingOf(n)), null, b.x, b.y, b.w, b.h);
            }
            for (UiLayout.Box b : lay.starting("toggle.")) {
                String name = b.id.substring(7);
                if (name.equals("more")) { kit.button("more:toggles", UiIcon.MORE, b.x, b.y, "toggles".equals(openMenu), true, null, null); continue; }
                UiIcon ic = UiIcon.valueOf(name.toUpperCase(java.util.Locale.ROOT));
                boolean on = ic == UiIcon.FOG ? Fog.on : ic == UiIcon.PLACES ? PlaceLayer.on : ic == UiIcon.CAVES ? CaveView.on : ic == UiIcon.LAYER ? Slice.on : ic == UiIcon.LOG && journalOpen;
                String why = ic == UiIcon.PLACES && placesMissing() ? UiKit.ui("state.noplaces") : null;
                kit.button(pillOf(ic), ic, b.x, b.y, on, why == null, null, why);
            }
        }

        boolean placesMissing() { return PlaceSource.current == null || PlaceSource.places().isEmpty(); }

        void messages(List<Object[]> fresh) {
            UiLayout.Box st = lay.get("state");
            if (st != null) {
                if (stalled()) kit.state(st, UiIcon.WARNING, UiKit.ui("state.stall"), "stall-ai", UiIcon.PLAN, true);
                else if (mode() != null) kit.state(st, CaveView.active() ? UiIcon.CAVES : UiIcon.LAYER, mode(), null, null, false);
            }
            long now = System.currentTimeMillis();
            List<UiLayout.Box> boxes = lay.starting("toast.");
            List<UiToasts.Toast> shown = toasts.shown(now, boxes.size());
            int k = 0;
            for (UiLayout.Box b : boxes) {
                if (b.id.equals("toast.more")) {
                    String t = UiKit.ui("toast.more", Math.max(1, toasts.live(now) - shown.size()));
                    fontRendererObj.drawStringWithShadow(t, b.x + b.w / 2 - fontRendererObj.getStringWidth(t) / 2, b.y, UiKit.TEXT_DIM);
                    kit.hits.add(new Object[] { "journal-more", b.x, b.y, b.x1(), b.y1() });
                } else if (k < shown.size()) kit.toast(b, shown.get(k++));
            }
            UiLayout.Box j = lay.get("journal");
            if (j != null) {
                Ui.round(j.x, j.y, j.x1(), j.y1(), 5, UiKit.PANEL_HI);
                List<UiToasts.Toast> all;
                synchronized (toasts) { all = new ArrayList<UiToasts.Toast>(toasts.all); }
                int rows = (j.h - 8) / 10;
                if (all.isEmpty()) fontRendererObj.drawString(UiKit.ui("journal.empty"), j.x + 6, j.y + 5, UiKit.TEXT_DIM);
                for (int i = 0; i < Math.min(rows, all.size()); i++) {
                    UiToasts.Toast t = all.get(i);
                    int y = j.y + 4 + i * 10;
                    if (t.icon != null) kit.icon(t.icon, j.x + 4, y, 8, 1f);
                    String when = new java.text.SimpleDateFormat("HH:mm").format(new java.util.Date(t.at)) + "  ";
                    fontRendererObj.drawString(when, j.x + 15, y + 1, UiKit.TEXT_DIM);
                    int tx = j.x + 15 + fontRendererObj.getStringWidth(when);
                    fontRendererObj.drawStringWithShadow(fontRendererObj.trimStringToWidth(t.text, j.x1() - tx - 4), tx, y + 1, t.kind == UiToasts.Kind.WARN ? UiKit.WARN_TEXT : UiKit.TEXT);
                }
                fresh.add(new Object[] { "journalBox", 0, 0, j.x + ox, j.y, j.x1() + ox, j.y1() });
            }
        }

        void bottomBar(List<Object[]> fresh) {
            UiLayout.Box bar = lay.get("bar");
            kit.panel(bar);
            boolean here = guideHere();
            for (UiLayout.Box b : lay.starting("bar.")) {
                String name = b.id.substring(4);
                if (name.equals("more")) { kit.button("more:bar", UiIcon.MORE, b.x, b.y, "bar".equals(openMenu), true, null, null); continue; }
                UiIcon ic = UiIcon.valueOf(name.toUpperCase(java.util.Locale.ROOT));
                if (ic == UiIcon.CHAT) {   // no guide: crossed out, and why; a question out: the cloud
                    UiIcon shown = !here ? UiIcon.OFFLINE : waitingGuide ? UiIcon.THINK : UiIcon.CHAT;
                    kit.button("chat", shown, b.x, b.y, false, true, "T", null);
                    if (!here && kit.over(b.x, b.y, b.w, b.h)) kit.tip(UiKit.ui("state.noguide"), null, b.x, b.y, b.w, b.h);
                    else if (waitingGuide && kit.over(b.x, b.y, b.w, b.h)) kit.tip(UiKit.ui("guide.waiting"), "T", b.x, b.y, b.w, b.h);
                    continue;
                }
                String key = ic == UiIcon.ADD_AGENT && CrewKeys.add != null ? Keyboard.getKeyName(CrewKeys.add.getKeyCode())
                        : ic == UiIcon.SELF_AI && CrewKeys.self != null ? Keyboard.getKeyName(CrewKeys.self.getKeyCode()) : null;
                boolean on = ic == UiIcon.SELF_AI && PlayerAgent.givenToAI || ic == UiIcon.HELP && helpOpen || ic == UiIcon.WAVES && Waves.view.active();
                kit.button(pillOf(ic), ic, b.x, b.y, on, true, key, null);
            }
            UiLayout.Box c = lay.get("chat");
            if (c != null) {
                Ui.round(c.x, c.y, c.x1(), c.y1(), 7, 0xF0202C3A);
                kit.icon(here ? UiIcon.CHAT : UiIcon.OFFLINE, c.x + 4, c.y + 4, 8, 1f);
                if (chatAt[0] != c.x + 15 + ox || chatAt[1] != c.y + 4 || chatAt[2] != c.w - 19) {   // the field made again where it is now (its text kept)
                    String keep = chat.getText();
                    boolean focus = chat.isFocused();
                    chat = new net.minecraft.client.gui.GuiTextField(fontRendererObj, c.x + 15 + ox, c.y + 4, c.w - 19, 12);
                    chat.setMaxStringLength(240);
                    chat.setEnableBackgroundDrawing(false);
                    chat.setText(keep);
                    chat.setFocused(focus);
                    chatAt[0] = c.x + 15 + ox; chatAt[1] = c.y + 4; chatAt[2] = c.w - 19;
                }
                GL11.glPushMatrix();
                GL11.glTranslatef(-ox, 0, 0);   // (the field keeps screen coordinates)
                if (chat.getText().isEmpty()) fontRendererObj.drawString(here ? UiKit.ui("chat.hint") : UiKit.ui("state.noguide"), c.x + 15 + ox, c.y + 4, 0x708090);
                else chat.drawTextBox();
                GL11.glPopMatrix();
                fresh.add(new Object[] { "chat", 0, 0, c.x + ox, c.y, c.x1() + ox, c.y1() });
            }
        }

        void card(List<Object[]> fresh) {
            UiLayout.Box card = lay.get("card"), head = lay.get("card.head");
            List<Integer> sel = new ArrayList<Integer>(selected);
            int n = sel.get(0);
            boolean body = lay.get("card.doing") != null || lay.get("card.rows") != null;
            if (sel.size() == 1) {
                Agent a = me(n) ? null : Cam.serverAgent(n);
                kit.cardHead(card, head, n, name(n), health(n), thinking(n), body ? null : doingIcon(n), body ? null : doingOf(n), a != null ? 16 : 0);
                if (a != null) {   // the whole bag (I)
                    int bx = head.x1() - 13, by = head.y - 1;
                    boolean over = kit.over(bx, by, 13, 13);
                    Ui.round(bx, by, bx + 13, by + 13, 3, fullInv ? UiKit.BUTTON_ON : over ? UiKit.BUTTON_OVER : UiKit.BUTTON);
                    kit.icon(UiIcon.INVENTORY, bx + 2, by + 2, 8, 1f);
                    kit.hits.add(new Object[] { "inv", bx, by, bx + 13, by + 13 });
                    if (over) kit.tip(net.minecraft.client.resources.I18n.format(UiIcon.INVENTORY.words()), "I", bx, by, 13, 13);
                }
                UiLayout.Box d = lay.get("card.doing");
                if (d != null) {
                    boolean far = away(n);
                    kit.line(d, far ? UiIcon.WARNING : doingIcon(n), far ? UiKit.ui("state.away") : UiKit.ui("card.doing", doingOf(n)), far ? UiKit.WARN_TEXT : UiKit.TEXT);
                }
                UiLayout.Box g = lay.get("card.goal");
                if (g != null) {
                    String goal = goal(n);
                    kit.line(g, UiIcon.GOAL, goal == null ? UiKit.ui("card.nogoal") : UiKit.ui("card.goal", goal), goal == null ? UiKit.TEXT_DIM : 0xB8E0FF);
                }
                UiLayout.Box bag = lay.get("card.bag");
                if (bag != null && a != null) bag(a, bag, fresh);
            } else {
                kit.cardHead(card, head, sel.size(), UiKit.ui("card.picked", sel.size()), 1f, false);
                UiLayout.Box rows = lay.get("card.rows");
                if (rows != null) {
                    int fit = rows.h / UiLayout.ROW;
                    if (sel.size() > fit) fit = Math.max(1, (rows.h - 8) / UiLayout.ROW);
                    for (int i = 0; i < Math.min(fit, sel.size()); i++) {
                        int u = sel.get(i), y = rows.y + i * UiLayout.ROW;
                        boolean over = kit.over(rows.x - 2, y - 1, rows.w + 4, UiLayout.ROW);
                        if (over && dragFrom != null) Ui.round(rows.x - 2, y - 1, rows.x1() + 2, y + UiLayout.ROW - 1, 3, 0x6040A0FF);
                        Ui.round(rows.x, y, rows.x + 9, y + 9, 2, me(u) ? 0xFF2F6FA8 : 0xFF3A4450);
                        String t = String.valueOf(u);
                        fontRendererObj.drawStringWithShadow(t, rows.x + 5 - fontRendererObj.getStringWidth(t) / 2, y + 1, UiKit.TEXT);
                        float hp = health(u);
                        Ui.round(rows.x + 12, y + 3, rows.x + 36, y + 6, 0, 0xFF202020);
                        Ui.round(rows.x + 12, y + 3, rows.x + 12 + Math.round(24 * hp), y + 6, 0, hp > 0.5f ? 0xFF50E070 : hp > 0.25f ? 0xFFE0C040 : 0xFFE04040);
                        UiIcon ic = doingIcon(u);
                        if (ic != null) kit.icon(ic, rows.x + 40, y, 8, 1f);
                        String d = (me(u) ? name(u) + ": " : "") + doingOf(u);
                        fontRendererObj.drawStringWithShadow(fontRendererObj.trimStringToWidth(d, rows.w - 52), rows.x + 51, y + 1, UiKit.TEXT);
                        fresh.add(new Object[] { "agent", u, -1, rows.x - 2 + ox, y - 1, rows.x1() + 2 + ox, y + UiLayout.ROW - 1 });
                    }
                    if (sel.size() > fit) fontRendererObj.drawString(UiKit.ui("card.more", sel.size() - fit), rows.x + 51, rows.y1() - 8, UiKit.TEXT_DIM);
                }
            }
            for (UiLayout.Box b : lay.starting("card.order.")) {
                String name = b.id.substring(11);
                if (name.equals("more")) { kit.button("more:card", UiIcon.MORE, b.x, b.y, "card".equals(openMenu), true, null, null); continue; }
                UiIcon ic = UiIcon.valueOf(name.toUpperCase(java.util.Locale.ROOT));
                boolean on = ic == UiIcon.MOVE && armed == Armed.MOVE || ic == UiIcon.CHOP && armed == Armed.CHOP || ic == UiIcon.ATTACK && armed == Armed.ATTACK
                        || ic == UiIcon.GUARD && armed == Armed.GUARD || ic == UiIcon.CRAFT && "craft".equals(popup) || ic == UiIcon.SMELT && "smelt".equals(popup);
                String why = ic == UiIcon.DEPOSIT && (Crew.instance == null || Crew.instance.chest == null) ? UiKit.ui("card.nochest") : null;
                kit.button(pillOf(ic), ic, b.x, b.y, on, why == null, null, why);
            }
        }

        /** the one picked agent's bag: a click on an item: it works with it; dragged onto another agent: handed over */
        void bag(Agent a, UiLayout.Box box, List<Object[]> fresh) {
            List<ItemStack> bag;
            try { bag = new ArrayList<ItemStack>(a.bag); } catch (Exception ex) { bag = new ArrayList<ItemStack>(); }
            int per = Math.max(1, box.w / UiLayout.SLOT), rows = box.h / UiLayout.SLOT;
            for (int i = 0; i < per * rows; i++) {
                int cx = box.x + (i % per) * UiLayout.SLOT, cy = box.y + (i / per) * UiLayout.SLOT;
                ItemStack s = i < bag.size() ? bag.get(i) : null;
                boolean held = s != null && (s == a.getHeldItem() || s == a.preferred);
                boolean over = kit.over(cx, cy, 17, 17);
                Ui.round(cx, cy, cx + 17, cy + 17, 3, held ? 0xE0607830 : over ? 0xC0405060 : 0x90283038);
                if (s == null) continue;
                icon(s, cx + 1, cy + 1);
                fresh.add(new Object[] { "item", a.skin(), i, cx + ox, cy, cx + 17 + ox, cy + 17 });
                if (over && dragFrom == null) kit.tip(s.getDisplayName() + (held ? "  " + UiKit.ui("bag.held") : "  " + UiKit.ui("bag.hint")), null, cx, cy, 17, 17);
            }
        }

        /** the craft's or the smelt's list over its button */
        void popup() {
            UiLayout.Box anchor = lay.get("card.order." + popup);
            if (anchor == null) anchor = lay.get("card.order.more");
            if (anchor == null || selected.isEmpty()) { popup = null; return; }
            String[][] list = popup.equals("craft") ? CRAFTS : SMELTS;
            int mw = 0;
            for (String[] e : list) mw = Math.max(mw, fontRendererObj.getStringWidth(shownName(e[0]) + " ×" + e[1]));
            mw += 16;
            int mh = list.length * 12 + 4;
            int[] at = UiLayout.tooltip(anchor.x, anchor.y, anchor.w, anchor.h, mw, mh, lay.sw, lay.sh);
            Ui.round(at[0], at[1], at[0] + mw, at[1] + mh, 5, UiKit.PANEL_HI);
            for (int i = 0; i < list.length; i++) {
                int y = at[1] + 2 + i * 12;
                if (kit.over(at[0], y, mw, 12)) Ui.round(at[0] + 2, y, at[0] + mw - 2, y + 12, 3, UiKit.BUTTON_OVER);
                fontRendererObj.drawStringWithShadow(shownName(list[i][0]) + " ×" + list[i][1], at[0] + 8, y + 2, UiKit.TEXT);
                kit.hits.add(new Object[] { popup + ":" + list[i][0] + ":" + list[i][1], at[0], y, at[0] + mw, y + 12 });
            }
        }

        /** F1: every key of the commander, by what it is for */
        void help() {
            String[] lines = { UiKit.ui("help.camera"), UiKit.ui("help.select"), UiKit.ui("help.groups"), UiKit.ui("help.orders"), UiKit.ui("help.queue"),
                    UiKit.ui("help.dig"), UiKit.ui("help.wheel"), UiKit.ui("help.stop"), UiKit.ui("help.bag"), UiKit.ui("help.crew"), UiKit.ui("help.view"),
                    UiKit.ui("help.cave"), UiKit.ui("help.chat"), UiKit.ui("help.film"), UiKit.ui("help.close") };
            int w = Math.min(lay.sw - 16, 380), h = Math.min(lay.sh - 16, 24 + lines.length * 11), x = (lay.sw - w) / 2, y = (lay.sh - h) / 2;
            GL11.glPushMatrix();
            GL11.glTranslatef(0, 0, 200);
            Ui.round(x, y, x + w, y + h, 8, 0xF815222E);
            kit.icon(UiIcon.HELP, x + 8, y + 7, 8, 1f);
            fontRendererObj.drawStringWithShadow(UiKit.ui("help.title"), x + 20, y + 8, UiKit.TEXT);
            kit.closeCross(kit.hits, "helpclose", x + w, y);
            for (int i = 0; i < lines.length && 24 + (i + 1) * 11 <= h; i++)
                fontRendererObj.drawStringWithShadow(fontRendererObj.trimStringToWidth(lines[i], w - 16), x + 8, y + 24 + i * 11, 0xDFE8EF);
            GL11.glPopMatrix();
        }

        /** what a click would do, by the cursor: the mouse's buttons as icons */
        void cursorLabel(int gmx, int gmy) {
            if (CaveView.active() || dragFrom != null || overUi(gmx, gmy)) return;
            String l = null, r = null, plain = null;
            if (painting) plain = (paintBuild ? "Строить: " + shownName(buildMaterial()) : "Копать") + " ×" + strokeCells(paintFrom, paintTo, paintBuild).size() + (depth > 1 ? "  глубина " + depth : "");
            else if (armed != Armed.NONE) l = armed == Armed.MOVE ? "идти сюда" : armed == Armed.CHOP ? "рубить" : armed == Armed.ATTACK ? "атаковать" : "охранять";
            else if (hoverL != null || hoverR != null) { l = hoverL; r = hoverR; }
            else plain = hoverLabel;
            if (l == null && r == null && (plain == null || plain.isEmpty())) return;
            int w = plain != null ? fontRendererObj.getStringWidth(plain)
                    : (l != null ? 11 + fontRendererObj.getStringWidth(l) : 0) + (r != null ? (l != null ? 8 : 0) + 11 + fontRendererObj.getStringWidth(r) : 0);
            int lx = Math.max(bandX0() + 2, Math.min(bandX1() - w - 6, gmx + 14)), ly = gmy + 14;   // clear of the arrow
            Ui.round(lx - 4, ly - 3, lx + w + 4, ly + 10, 3, 0xC0101418);
            if (plain != null) { fontRendererObj.drawStringWithShadow(plain, lx, ly, 0xFFFFE8); return; }
            int x = lx;
            if (l != null) { kit.icon(UiIcon.LMB, x, ly - 1, 8, 1f); fontRendererObj.drawStringWithShadow(l, x + 11, ly, 0xFFFFE8); x += 11 + fontRendererObj.getStringWidth(l) + 8; }
            if (r != null) { kit.icon(UiIcon.RMB, x, ly - 1, 8, 1f); fontRendererObj.drawStringWithShadow(r, x + 11, ly, 0xFFFFE8); }
        }

        /** the mouse over the interface (last frame's boxes, an open menu or the help) */
        boolean overUi(int gx, int gy) {
            if (helpOpen || openMenu != null || popup != null) return true;
            for (Object[] x : hits) if (gx >= (Integer) x[3] && gx < (Integer) x[5] && gy >= (Integer) x[4] && gy < (Integer) x[6]) return true;
            if (lay == null) return false;
            for (UiLayout.Box b : lay.boxes)
                if ((b.id.equals("bar") || b.id.equals("card") || b.id.equals("toggles") || b.id.equals("state") || b.id.equals("chat") || b.id.equals("journal")
                        || b.id.startsWith("toast.") || b.id.startsWith("roster.")) && gx >= b.x + ox && gx < b.x1() + ox && gy >= b.y && gy < b.y1()) return true;
            return false;
        }

        String shownName(String item) {
            net.minecraft.item.Item it = Compiler.item(item);
            if (it == null) { net.minecraft.block.Block b = net.minecraft.block.Block.getBlockFromName(item); if (b != null) it = net.minecraft.item.Item.getItemFromBlock(b); }
            return it == null ? item : new ItemStack(it).getDisplayName();
        }

        void icon(ItemStack s, int x, int y) {
            if (s == null) return;
            GL11.glPushAttrib(GL11.GL_ENABLE_BIT);
            net.minecraft.client.renderer.RenderHelper.enableGUIStandardItemLighting();
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL12_RESCALE);
            itemRender.renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), s, x, y);
            itemRender.renderItemOverlayIntoGUI(fontRendererObj, mc.getTextureManager(), s, x, y);
            net.minecraft.client.renderer.RenderHelper.disableStandardItemLighting();
            GL11.glPopAttrib();
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
        }

        static final int GL12_RESCALE = 0x803A;   // GL_RESCALE_NORMAL

        /** the mouse's arrow drawn into the picture (the system's own is not in a recording), and a ring when it clicks */
        void cursor(int gx, int gy, int scale) {
            float u = 1f / scale * 2;
            GL11.glPushMatrix();
            GL11.glTranslatef(gx, gy, 300);
            GL11.glScalef(u * 1.5f, u * 1.5f, 1);
            int[] rows = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 6, 3 };
            for (int r = 0; r < rows.length; r++) Gui.drawRect(-1, r - 1, rows[r] + 1, r + 2, 0xFF000000);
            for (int r = 0; r < rows.length; r++) Gui.drawRect(0, r, rows[r], r + 1, 0xFFFFFFFF);
            GL11.glPopMatrix();
            long since = System.currentTimeMillis() - clickAt;
            if (since < 400) {
                int cx = clickX / scale, cy = (mc.displayHeight - clickY) / scale, r = 3 + (int) (since / 40);
                int c = (int) (255 * (1 - since / 400f)) << 24 | 0xFFE060;
                Gui.drawRect(cx - r, cy - r, cx + r, cy - r + 1, c); Gui.drawRect(cx - r, cy + r - 1, cx + r, cy + r, c);
                Gui.drawRect(cx - r, cy - r, cx - r + 1, cy + r, c); Gui.drawRect(cx + r - 1, cy - r, cx + r, cy + r, c);
            }
        }
    }
}
