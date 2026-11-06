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
    boolean on, placed;
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
            c.keys.add(new Cam.Key(t, camAt[0], camAt[1], camAt[2], camAt[3], camAt[4], 70 + mc.gameSettings.fovSetting * 40));
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
        vFromX = vx; vFromY = vy; vToX = x; vToY = y; vT = 0; vLen = Math.max(1, ticks);
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
        if (e.phase != TickEvent.Phase.START || !vOn || vT >= vLen) return;
        vT = Math.min(vLen, vT + (Recorder.instance != null ? 1 : 1));
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
        if (Cam.instance != null && Cam.instance.shot != null) return;   // a camera of the film is on (a follow shot, the director, a path): it poses the view
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
        double far = Math.hypot(px - mc.thePlayer.posX, pz - mc.thePlayer.posZ), lim = mc.gameSettings.renderDistanceChunks * 16 - 24;   // within the world drawn around the player
        if (far > lim) { px = mc.thePlayer.posX + (px - mc.thePlayer.posX) * lim / far; pz = mc.thePlayer.posZ + (pz - mc.thePlayer.posZ) * lim / far; }
        int gy = mc.theWorld.getHeightValue(MathHelper.floor_double(px), MathHelper.floor_double(pz));
        if (gy > 0) py = gy;
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
        for (Agent a : clientAgents()) {
            if (!selected.contains(a.skin())) continue;
            double x = a.lastTickPosX + (a.posX - a.lastTickPosX) * pt, y = a.lastTickPosY + (a.posY - a.lastTickPosY) * pt - a.yOffset + 0.05, z = a.lastTickPosZ + (a.posZ - a.lastTickPosZ) * pt;
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
        MovingObjectPosition hit = mc.theWorld.rayTraceBlocks(Vec3.createVectorHelper(r[0].xCoord, r[0].yCoord, r[0].zCoord), Vec3.createVectorHelper(r[1].xCoord, r[1].yCoord, r[1].zCoord), false, true, false);
        double limit = hit == null ? 1e9 : hit.hitVec.distanceTo(r[0]);
        Entity best = null;
        double bd = limit;
        for (Object o : mc.theWorld.loadedEntityList) {
            if (!(o instanceof EntityLivingBase) || o == mc.thePlayer || o == eye) continue;
            Entity en = (Entity) o;
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
        for (Agent a : clientAgents()) {
            float[] s = toScreen(a.posX, a.boundingBox.minY + 0.9, a.posZ);
            if (s != null && s[0] >= Math.min(x0, x1) && s[0] <= Math.max(x0, x1) && s[1] >= Math.min(y0, y1) && s[1] <= Math.max(y0, y1)) selected.add(a.skin());
        }
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
    // the command card: name, key (shown), id
    static final String[][] CARD = { { "Идти", "M" }, { "Рубить", "B" }, { "Атака", "X" }, { "Охрана", "G" }, { "Стоп", "H" }, { "ИИ", "R" }, { "Крафт", "" }, { "Плавка", "" }, { "Чертёж", "" } };

    class Screen extends GuiScreen {
        int downX, downY, downButton = -1;
        boolean dragging, selecting;
        long lastClick;
        int lastClickSkin = -1;
        int menu;   // (kept for the narrow-band arithmetic: no menus now)
        static final int ROW = 22;
        net.minecraft.client.gui.GuiTextField chat;
        int sentAt = -1;          // the guide's history size when the player last wrote
        String sentText = "";
        boolean fullInv;          // the one picked agent's whole inventory, large
        // what the panel drew last frame, for clicks: kind, agent, slot, box
        final List<Object[]> hits = new ArrayList<Object[]>();
        Object[] dragFrom;        // an item picked up from an agent's inventory (to hand it to another)
        float anim;               // the panel's ease in
        float shownH = 40;        // the panel's height as shown (eased toward the wanted one)
        final int[] chatAt = new int[3];

        /** the band the interface keeps to: x from, x to (gui units) */
        int bandX0() { return safeAspect <= 0 ? 0 : Math.max(0, (int) (width - height * safeAspect) / 2); }
        int bandX1() { return safeAspect <= 0 ? width : Math.min(width, (int) (width + height * safeAspect) / 2); }
        int bandW() { return Math.min(bandX1() - bandX0(), 420); }
        int px0() { return (bandX0() + bandX1()) / 2 - bandW() / 2; }

        /** the panel's height for what is shown now */
        List<String> routineCache = new ArrayList<String>();
        long routineAt;

        List<String> routineNames() {   // read from the world's file now and then, not every frame
            if (System.currentTimeMillis() - routineAt > 2000) { routineAt = System.currentTimeMillis(); try { routineCache = Routines.names(); } catch (Exception e) { routineCache = new ArrayList<String>(); } }
            return routineCache;
        }

        int panelH() {
            int h = 8 + 16 + (reply() != null ? 22 : 0) + 6 + (routineNames().isEmpty() ? 0 : 18);   // the chat, the guide's reply, the routines
            List<Agent> sel = selAgents();
            if (sel.size() == 1) h += 14 + (fullInv ? 4 * 20 : 2 * 20) + 18;
            else if (sel.size() > 1) h += sel.size() * 20 + 18;
            else h += 12;
            return h;
        }

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

        volatile String localReply;   // an answer made here (a known routine run), not by the guide

        /** the guide's last answer since the player wrote (shown for half a minute) */
        String reply() {
            if (localReply != null) return localReply;
            if (sentAt < 0) return null;
            String r = null;
            for (int i = sentAt; i < GuiGuide.history.size(); i++) { String[] h = GuiGuide.history.get(i); if (h[0] == null) r = h[1]; }
            return r;
        }

        /** to the guide: what was written, and who it is for (the picked agents, or the whole crew) */
        void send(final String text) {
            if (text.trim().isEmpty()) return;
            StringBuilder who = new StringBuilder();
            for (int s : selected) who.append(who.length() > 0 ? "," : "").append(s);
            final String ask = (who.length() > 0 ? "[из режима командира; выбраны агенты " + who + " — приказы только им: crew_order с agents=" + who + " или цели crew_plan] "
                    : "[из режима командира; вся команда] ") + text.trim();
            sentAt = GuiGuide.history.size();
            localReply = null;
            sentText = text.trim();
            final String player = mc.thePlayer == null ? "Player" : mc.thePlayer.getCommandSenderName();
            final int[] who2 = picked();
            new Thread(new Runnable() {
                public void run() {
                    try {
                        Routines.Routine known = Routines.match(text.trim(), 0.80);   // a routine the crew already knows: at once, without asking
                        if (known != null) {
                            int[] ws = who2;
                            if (ws.length == 0) { List<Integer> all = new ArrayList<Integer>(); for (Agent g : clientAgents()) all.add(g.skin()); ws = new int[all.size()]; for (int i = 0; i < ws.length; i++) ws[i] = all.get(i); }
                            Routines.run(ws, known.name);
                            localReply = "Рутина «" + known.name + "»: " + known.description;
                            return;
                        }
                        java.lang.reflect.Method m = Class.forName("hardelele.aiguide.Tools").getDeclaredMethod("crewAsk", String.class, String.class);
                        m.setAccessible(true);
                        Director.log("commander: asked the guide: " + text.trim() + " -> " + m.invoke(null, player, ask));
                    } catch (Exception ex) {
                        Director.log("commander: the guide is not there: " + ex);
                    }
                }
            }, "commander-ask").start();
        }

        String doingRu(String kind) {
            String k = kind.replace("order ", "").replace("paint ", "");
            String[][] m = { { "move", "идёт" }, { "chop", "рубит" }, { "mine", "копает" }, { "attack", "атакует" }, { "deposit", "несёт в сундук" }, { "guard", "охраняет" },
                    { "craft", "крафтит" }, { "smelt", "плавит" }, { "place", "строит" }, { "ore", "добывает руду" }, { "make", "крафтит" }, { "inside", "в дом" },
                    { "light", "ставит факелы" }, { "recover", "за своими вещами" }, { "camp", "лагерь" }, { "carry", "носит" }, { "torches", "факелы" }, { "dig", "копает" },
                    { "build", "строит" }, { "give", "передаёт" } };
            for (String[] p : m) if (k.equals(p[0])) return p[1];
            return k;
        }

        String doing(Agent a) {
            TaskBoard.Card k = Crew.instance == null ? null : Crew.instance.board.leased.get(a);
            return k != null ? doingRu(k.kind) : a.commanded ? "ждёт приказа" : "по плану";
        }

        /** the armed-order keys stay (M go, B chop, X attack, G guard), with no buttons for them */
        void arm(Armed a) { armed = armed == a ? Armed.NONE : a; blueprint = -1; }

        @Override
        protected void keyTyped(char c, int code) {
            lastInput = vOn ? lastInput : System.currentTimeMillis();
            if (chat.isFocused()) {   // writing to the guide: the keys are the text's
                if (code == Keyboard.KEY_RETURN) { send(chat.getText()); chat.setText(""); chat.setFocused(false); }
                else if (code == Keyboard.KEY_ESCAPE) chat.setFocused(false);
                else chat.textboxKeyTyped(c, code);
                return;
            }
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
            if (code == Keyboard.KEY_M) arm(Armed.MOVE);
            if (code == Keyboard.KEY_B) arm(Armed.CHOP);
            if (code == Keyboard.KEY_X) arm(Armed.ATTACK);
            if (code == Keyboard.KEY_G) arm(Armed.GUARD);
            if (code == Keyboard.KEY_I && selected.size() == 1) fullInv = !fullInv;
            if (code == Keyboard.KEY_K || code == Keyboard.KEY_P || code == Keyboard.KEY_F || code == Keyboard.KEY_N) filmKey(code, shift);
            if (code == Keyboard.KEY_V && Cam.instance != null) { Cam.instance.autoOn = false; Cam.instance.release(); }
            if (code == Keyboard.KEY_H && !selected.isEmpty()) Orders.give(picked(), Orders.Kind.STOP, 0, 0, 0, -1, false);
            if (code == Keyboard.KEY_R && !selected.isEmpty()) Orders.give(picked(), Orders.Kind.RELEASE, 0, 0, 0, -1, false);
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

        boolean onPanel(int my, int scale) {
            int guiY = (mc.displayHeight - my) / scale;
            return guiY >= height - panelH() - 4;
        }

        /** a click on the panel: the chat field, an item, a pill; an item dragged onto another agent: handed over */
        boolean panelInput(int b, boolean state, int gx, int gy) {
            Object[] h = null;
            for (Object[] x : hits) if (gx >= (Integer) x[3] && gx < (Integer) x[5] && gy >= (Integer) x[4] && gy < (Integer) x[6]) h = x;
            if (state) {
                chat.setFocused(h != null && h[0].equals("chat"));
                if (h == null) return true;
                if (h[0].equals("item") && b == 0) { dragFrom = h; return true; }
                if (h[0].equals("pill")) pill((String) h[1]);
                return true;
            }
            if (b == 0 && dragFrom != null) {   // let go: on the same agent: work with it; on another agent: hand it over
                int from = (Integer) dragFrom[1], slot = (Integer) dragFrom[2];
                if (h != null && (h[0].equals("item") || h[0].equals("agent")) && (Integer) h[1] != from) {
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

        void pill(String what) {
            if (what.startsWith("routine:")) {   // a routine for the picked agents (none picked: the whole crew)
                int[] ws = picked();
                if (ws.length == 0) { List<Integer> all = new ArrayList<Integer>(); for (Agent g : clientAgents()) all.add(g.skin()); ws = new int[all.size()]; for (int i = 0; i < ws.length; i++) ws[i] = all.get(i); }
                Routines.run(ws, what.substring(8));
                localReply = "Рутина «" + what.substring(8) + "» запущена";
                sentAt = GuiGuide.history.size();
                return;
            }
            if (what.equals("inv")) fullInv = !fullInv;
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

        /** one mouse event (b -1: a move or the wheel), the person's or the script's: the same handling */
        void input(int b, boolean state, int mx, int my, int wheel, int dx) {
            int scale = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
            int gx = mx / scale, gy = (mc.displayHeight - my) / scale;
            if (b >= 0 && downButton < 0 && onPanel(my, scale) && gx >= px0() && gx < px0() + bandW()) { panelInput(b, state, gx, gy); return; }
            if (b >= 0 && !state && dragFrom != null) { panelInput(b, false, gx, gy); return; }   // an item let go over the world: nothing
            boolean shift = shiftDown();
            boolean ctrl = ctrlDown();
            boolean alt = altDown();
            if (wheel != 0) {
                if (shift) depth = Math.max(1, Math.min(8, depth + (wheel > 0 ? 1 : -1)));
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
                    if (o instanceof Agent) { selecting = true; return; }
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
                    if (o instanceof Agent) {
                        int s = ((Agent) o).skin();
                        if (s == lastClickSkin && System.currentTimeMillis() - lastClick < 350) { for (Agent a : clientAgents()) selected.add(a.skin()); }
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
            hoverNow();
            int scale = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
            if (vOn) { gmx = curX() / scale; gmy = (mc.displayHeight - curY()) / scale; }
            for (Agent a : clientAgents()) {   // names and health over every agent
                float[] s = toScreen(a.posX, a.boundingBox.maxY + 0.45, a.posZ);
                if (s == null) continue;
                int x = (int) (s[0] / scale), y = (int) ((mc.displayHeight - s[1]) / scale);
                boolean sel = selected.contains(a.skin());
                float hp = a.getHealth() / a.getMaxHealth();
                Ui.round(x - 14, y - 1, x + 14, y + 4, 2, 0xA0000000);
                Gui.drawRect(x - 12, y + 1, x - 12 + (int) (24 * hp), y + 2, hp > 0.5 ? 0xFF50E070 : hp > 0.25 ? 0xFFE0C040 : 0xFFE04040);
                String n = a.getCustomNameTag();
                int nw = fontRendererObj.getStringWidth(n);
                Ui.round(x - nw / 2 - 4, y - 12, x + nw / 2 + 4, y - 1, 4, sel ? 0xB0204828 : 0x90101418);   // a chip under the name
                fontRendererObj.drawStringWithShadow(n, x - nw / 2, y - 10, sel ? 0x90FF90 : 0xFFFFFF);
            }
            if (dragging && selecting) {   // the box being dragged
                int x0 = downX / scale, y0 = (mc.displayHeight - downY) / scale, x1 = curX() / scale, y1 = (mc.displayHeight - curY()) / scale;
                Ui.round(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1), 2, 0x3050FF70);
            }
            String label = painting ? (paintBuild ? "Строить: " + shownName(buildMaterial()) : "Копать") + " ×" + strokeCells(paintFrom, paintTo, paintBuild).size() + (depth > 1 ? "  глубина " + depth : "")
                    : armed != Armed.NONE ? "ЛКМ: " + (armed == Armed.MOVE ? "идти сюда" : armed == Armed.CHOP ? "рубить" : armed == Armed.ATTACK ? "атаковать" : "охранять") : hoverLabel;
            int top = height - panelH() - 4;
            if (label != null && !label.isEmpty() && gmy < top && dragFrom == null) {
                int lw = fontRendererObj.getStringWidth(label);
                int lx = Math.max(bandX0() + 2, Math.min(bandX1() - lw - 6, gmx + 10)), ly = gmy + 12;
                Ui.round(lx - 4, ly - 3, lx + lw + 4, ly + 10, 3, 0xC0101418);
                fontRendererObj.drawStringWithShadow(label, lx, ly, 0xFFFFE8);
            }
            GL11.glPushMatrix();
            GL11.glTranslatef(0, (1 - anim) * 30, 0);   // the panel comes up when the commander opens
            panel(gmx, gmy);
            GL11.glPopMatrix();
            if (dragFrom != null) {   // the item being carried with the mouse
                Agent a = Cam.serverAgent((Integer) dragFrom[1]);
                int slot = (Integer) dragFrom[2];
                if (a != null && slot < a.bag.size()) icon(a.bag.get(slot), gmx - 8, gmy - 8);
            }
            if (vOn || Recorder.instance != null) cursor(gmx, gmy, scale);
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

        void hit(String kind, int agent, int slot, int x0, int y0, int x1, int y1, Object extra) {
            hits.add(new Object[] { kind, extra != null ? extra : agent, slot, x0, y0, x1, y1 });
        }

        /** a pill: a small rounded button of the panel */
        int pill(String text, String id, int x, int y, int gmx, int gmy) {
            int w = fontRendererObj.getStringWidth(text) + 12;
            boolean over = gmx >= x && gmx < x + w && gmy >= y && gmy < y + 14;
            Ui.round(x, y, x + w, y + 14, 6, over ? 0xE0405060 : 0xC0283038);
            fontRendererObj.drawStringWithShadow(text, x + 6, y + 3, over ? 0xFFFFFF : 0xD8E0E8);
            hits.add(new Object[] { "pill", id, 0, x, y, x + w, y + 14 });
            return x + w + 4;
        }

        /** the bottom: the chat with the guide; the picked agents (one: its inventory to choose what it works with; several: each one's line, items carried between them) */
        void panel(int gmx, int gmy) {
            hits.clear();
            int x0 = px0(), x1 = x0 + bandW(), h = panelH();
            anim += (1 - anim) * 0.25f;
            shownH += (h - shownH) * 0.3f;   // a height that changes is eased, not jumped
            int y0 = height - (int) shownH - 4;
            Ui.round(x0 + 4, y0, x1 - 4, height - 4, 8, 0xD0101820);
            int y = y0 + 6;
            // the chat
            Ui.round(x0 + 8, y, x1 - 8, y + 16, 7, chat.isFocused() ? 0xF0202C3A : 0xC0182028);
            if (chatAt[0] != x0 + 14 || chatAt[1] != y + 4 || chatAt[2] != x1 - x0 - 28) {   // the panel moved: the field made again where it is (its text kept)
                String keep = chat.getText();
                boolean focus = chat.isFocused();
                chat = new net.minecraft.client.gui.GuiTextField(fontRendererObj, x0 + 14, y + 4, x1 - x0 - 28, 12);
                chat.setMaxStringLength(240);
                chat.setEnableBackgroundDrawing(false);
                chat.setText(keep);
                chat.setFocused(focus);
                chatAt[0] = x0 + 14; chatAt[1] = y + 4; chatAt[2] = x1 - x0 - 28;
            }
            if (chat.getText().isEmpty() && !chat.isFocused()) fontRendererObj.drawString("Скажи команде (Enter)…", x0 + 14, y + 4, 0x708090);
            else chat.drawTextBox();
            hits.add(new Object[] { "chat", 0, 0, x0 + 8, y, x1 - 8, y + 16 });
            y += 20;
            String r = reply();
            if (r != null) {
                List<?> lines = fontRendererObj.listFormattedStringToWidth(r.replace('\n', ' '), x1 - x0 - 24);
                for (int i = 0; i < Math.min(2, lines.size()); i++) fontRendererObj.drawStringWithShadow((String) lines.get(i) + (i == 1 && lines.size() > 2 ? "…" : ""), x0 + 12, y + i * 10, 0xB8E0FF);
                y += 22;
            } else if (sentAt >= 0 && sentText.length() > 0) { fontRendererObj.drawStringWithShadow("…", x0 + 12, y, 0x708090); }
            y += 2;
            List<String> rs = routineNames();
            if (!rs.isEmpty()) {
                int rx = x0 + 12;
                for (String n : rs) { if (rx > x1 - 60) break; rx = pill("\u21bb " + n, "routine:" + n, rx, y, gmx, gmy); }
                y += 18;
            }
            List<Agent> sel = selAgents();
            if (sel.isEmpty()) {
                fontRendererObj.drawStringWithShadow("ЛКМ — копать, Ctrl — строить, клик по агенту — выбрать", x0 + 12, y, 0x9AA8B8);
                return;
            }
            if (sel.size() == 1) {   // one agent: its inventory; a click on an item: it works with it; drag onto someone: hand it over
                Agent a = sel.get(0);
                fontRendererObj.drawStringWithShadow(a.getCustomNameTag() + "  " + Math.round(a.getHealth()) + "♥  " + doing(a), x0 + 12, y, 0xFFFFFF);
                y += 12;
                List<ItemStack> bag;
                try { bag = new ArrayList<ItemStack>(a.bag); } catch (Exception ex) { bag = new ArrayList<ItemStack>(); }
                int per = Math.max(4, (x1 - x0 - 24) / 20), rows = fullInv ? 4 : 2;
                for (int i = 0; i < per * rows; i++) {
                    int cx = x0 + 12 + (i % per) * 20, cy = y + (i / per) * 20;
                    ItemStack s = i < bag.size() ? bag.get(i) : null;
                    boolean held = s != null && (s == a.getHeldItem() || s == a.preferred);
                    boolean over = gmx >= cx && gmx < cx + 18 && gmy >= cy && gmy < cy + 18;
                    Ui.round(cx, cy, cx + 18, cy + 18, 3, held ? 0xE0607830 : over ? 0xC0405060 : 0x90283038);
                    if (s != null) { icon(s, cx + 1, cy + 1); hit("item", a.skin(), i, cx, cy, cx + 18, cy + 18, null); }
                    if (s != null && over && dragFrom == null) tip(s.getDisplayName() + (held ? "  (в руке)" : "  — клик: работать этим"), gmx, gmy);
                }
                y += rows * 20 + 2;
                int px = x0 + 12;
                px = pill(fullInv ? "Свернуть" : "Весь инвентарь", "inv", px, y, gmx, gmy);
                px = pill("Стоп", "stop", px, y, gmx, gmy);
                pill("По плану", "ai", px, y, gmx, gmy);
                return;
            }
            for (Agent a : sel) {   // several: a line each (name, health, what it does, what it carries); drag an item onto another's line: handed over
                boolean over = gmx >= x0 + 8 && gmx < x1 - 8 && gmy >= y && gmy < y + 18;
                if (over && dragFrom != null) Ui.round(x0 + 8, y - 1, x1 - 8, y + 19, 4, 0x6040A0FF);
                String n = a.getCustomNameTag().replace("Агент ", "А") + " " + Math.round(a.getHealth()) + "♥ " + doing(a);
                fontRendererObj.drawStringWithShadow(fontRendererObj.trimStringToWidth(n, 108), x0 + 12, y + 5, 0xFFFFFF);
                hit("agent", a.skin(), -1, x0 + 8, y, x1 - 8, y + 18, null);
                List<ItemStack> bag;
                try { bag = new ArrayList<ItemStack>(a.bag); } catch (Exception ex) { bag = new ArrayList<ItemStack>(); }
                int ix = x0 + 124;
                for (int i = 0; i < bag.size() && ix < x1 - 26; i++) {
                    ItemStack s = bag.get(i);
                    boolean held = s == a.getHeldItem() || s == a.preferred;
                    if (held) Ui.round(ix - 1, y - 1, ix + 17, y + 17, 3, 0xC0607830);
                    icon(s, ix, y);
                    hit("item", a.skin(), i, ix, y, ix + 18, y + 18, null);
                    if (gmx >= ix && gmx < ix + 18 && gmy >= y && gmy < y + 18 && dragFrom == null) tip(s.getDisplayName() + " — перетащи к другому: передать", gmx, gmy);
                    ix += 19;
                }
                y += 20;
            }
            int px = x0 + 12;
            px = pill("Собраться", "gather", px, y, gmx, gmy);
            px = pill("Стоп", "stop", px, y, gmx, gmy);
            pill("По плану", "ai", px, y, gmx, gmy);
        }

        void tip(String t, int gmx, int gmy) {
            int w = fontRendererObj.getStringWidth(t);
            int tx = Math.max(bandX0() + 2, Math.min(bandX1() - w - 6, gmx - w / 2)), ty = gmy - 16;
            Ui.round(tx - 4, ty - 3, tx + w + 4, ty + 10, 3, 0xF0101418);
            fontRendererObj.drawStringWithShadow(t, tx, ty, 0xFFFFFF);
        }

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
