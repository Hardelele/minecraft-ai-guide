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
    static volatile int drawn;   // frames the commander's screen was drawn (a check)

    /**
     * The film's cameras from the commander: K a keyframe where the view is now (3 s after the one before), P plays the
     * path, Shift+P records it, C clears the keys, F follows the first picked agent, G the director by action on/off.
     */
    void filmKey(int code, boolean shift) {
        Cam c = Cam.get();
        if (code == Keyboard.KEY_K && camAt != null) {
            double t = c.keys.isEmpty() ? 0 : c.keys.get(c.keys.size() - 1).t + 60;
            c.keys.add(new Cam.Key(t, camAt[0], camAt[1], camAt[2], camAt[3], camAt[4], 70 + mc.gameSettings.fovSetting * 40));
            Director.log("path: key " + c.keys.size() + " at " + (int) t + " ticks");
        } else if (code == Keyboard.KEY_P) {
            c.playPath(shift ? new java.io.File(mc.mcDataDir, "path-" + System.currentTimeMillis() + ".mp4").getAbsolutePath() : null);
        } else if (code == Keyboard.KEY_C) { c.keys.clear(); c.release(); Director.log("path: keys cleared"); }
        else if (code == Keyboard.KEY_F && !selected.isEmpty()) {
            c.autoOn = false;
            Cam.Shot s = c.planFollow(selected.iterator().next(), 20 * 600);
            if (s != null) c.start(s);
        } else if (code == Keyboard.KEY_G) {
            c.autoOn = !c.autoOn;
            if (!c.autoOn) c.release();
            Director.log("autocam by action: " + (c.autoOn ? "on" : "off"));
        }
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
        if (active && mc.currentScreen instanceof Screen) {
            if (Keyboard.isKeyDown(Keyboard.KEY_W)) { mxp += fx; mzp += fz; }
            if (Keyboard.isKeyDown(Keyboard.KEY_S)) { mxp -= fx; mzp -= fz; }
            if (Keyboard.isKeyDown(Keyboard.KEY_A)) { mxp += fz; mzp -= fx; }
            if (Keyboard.isKeyDown(Keyboard.KEY_D)) { mxp -= fz; mzp += fx; }
            if (Keyboard.isKeyDown(Keyboard.KEY_Q)) yaw -= 90 * dt;
            if (Keyboard.isKeyDown(Keyboard.KEY_E)) yaw += 90 * dt;
            int mx = Mouse.getX(), my = Mouse.getY(), m = 4;
            if (Mouse.isInsideWindow()) {
                if (mx < m) { mxp += fz; mzp -= fx; }
                if (mx > mc.displayWidth - m) { mxp -= fz; mzp += fx; }
                if (my < m) { mxp -= fx; mzp -= fz; }
                if (my > mc.displayHeight - m) { mxp += fx; mzp += fz; }
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
            ring(t, x, y, z, 0.7, 0.3f, 1f, 0.4f, 0.9f);
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
        Object o = pick(Mouse.getX(), Mouse.getY());
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

    // ---- the screen: the mouse free, the panel ---------------------------------------------------------

    static final String[][] CRAFTS = { { "wooden_pickaxe", "1" }, { "stone_pickaxe", "1" }, { "iron_pickaxe", "1" }, { "iron_sword", "1" }, { "torch", "8" },
            { "furnace", "1" }, { "crafting_table", "1" }, { "chest", "1" } };
    static final String[][] SMELTS = { { "iron_ore", "1" }, { "iron_ore", "4" }, { "iron_ore", "8" } };

    class Screen extends GuiScreen {
        int downX, downY, downButton = -1;
        boolean dragging;
        long lastClick;
        int lastClickSkin = -1;
        int menu;          // 0 none, 1 craft, 2 smelt, 3 build
        boolean guardPick;  // the next left click on the ground: where the picked ones stand guard
        static final int PANEL = 46, ROW = 22;

        @Override public boolean doesGuiPauseGame() { return false; }

        @Override public void onGuiClosed() { close(); }

        @Override
        public void initGui() {
            buttonList.clear();
            String[] main = { "Стоп", "ИИ", "Охрана", "Крафт", "Плавка", "Строить" };
            int bw = 52, x = width - main.length * (bw + 2) - 2;
            for (int i = 0; i < main.length; i++) buttonList.add(new net.minecraft.client.gui.GuiButton(i, x + i * (bw + 2), height - 22, bw, 20, main[i]));
            int y = height - PANEL - ROW;
            if (menu == 1) for (int i = 0; i < CRAFTS.length; i++) buttonList.add(new net.minecraft.client.gui.GuiButton(100 + i, 4 + i * 102, y, 100, 20, shown(CRAFTS[i][0]) + (CRAFTS[i][1].equals("1") ? "" : " ×" + CRAFTS[i][1])));
            if (menu == 2) for (int i = 0; i < SMELTS.length; i++) buttonList.add(new net.minecraft.client.gui.GuiButton(200 + i, 4 + i * 102, y, 100, 20, shown(SMELTS[i][0]) + " ×" + SMELTS[i][1]));
            if (menu == 3) {
                for (int i = 0; i < BLUEPRINTS.length; i++) buttonList.add(new net.minecraft.client.gui.GuiButton(300 + i, 4 + i * 82, y, 80, 20, (blueprint == i ? "> " : "") + BLUEPRINTS[i]));
                buttonList.add(new net.minecraft.client.gui.GuiButton(310, 4 + BLUEPRINTS.length * 82, y, 80, 20, shown(MATERIALS[material])));
                buttonList.add(new net.minecraft.client.gui.GuiButton(311, 4 + (BLUEPRINTS.length + 1) * 82, y, 60, 20, "повернуть"));
            }
        }

        /** the item's name as the game shows it in the player's language (its lang files), never its id */
        String shown(String item) {
            net.minecraft.item.Item it = Compiler.item(item);
            if (it == null) { net.minecraft.block.Block b = net.minecraft.block.Block.getBlockFromName(item); if (b != null) it = net.minecraft.item.Item.getItemFromBlock(b); }
            if (it == null) return item;
            String n = new ItemStack(it).getDisplayName();
            return fontRendererObj.getStringWidth(n) > 92 ? fontRendererObj.trimStringToWidth(n, 86) + "…" : n;
        }

        /** a card's kind in words for the player */
        String doingRu(String kind) {
            String k = kind.replace("order ", "");
            String[][] m = { { "move", "идёт" }, { "chop", "рубит" }, { "mine", "копает" }, { "attack", "атакует" }, { "deposit", "несёт в сундук" }, { "guard", "охраняет" },
                    { "craft", "крафтит" }, { "smelt", "плавит" }, { "place", "строит" }, { "ore", "добывает руду" }, { "smelt", "плавит" }, { "make", "крафтит" }, { "inside", "в дом" },
                    { "light", "ставит факелы" }, { "recover", "за своими вещами" }, { "camp", "лагерь" }, { "carry", "носит" }, { "torches", "факелы" }, { "dig", "копает" } };
            for (String[] p : m) if (k.equals(p[0])) return (kind.startsWith("order ") ? "приказ: " : "") + p[1];
            return k;
        }

        String short_(String item) {
            return item.replace("wooden_", "дер. ").replace("stone_", "кам. ").replace("iron_", "жел. ").replace("pickaxe", "кирка").replace("sword", "меч")
                    .replace("torch", "факелы").replace("furnace", "печь").replace("crafting_table", "верстак").replace("chest", "сундук");
        }

        @Override
        protected void actionPerformed(net.minecraft.client.gui.GuiButton b) {
            int id = b.id;
            if (id == 0 && !selected.isEmpty()) Orders.give(picked(), Orders.Kind.STOP, 0, 0, 0, -1, false);
            else if (id == 1 && !selected.isEmpty()) Orders.give(picked(), Orders.Kind.RELEASE, 0, 0, 0, -1, false);
            else if (id == 2) { guardPick = true; menu = 0; blueprint = -1; }
            else if (id >= 3 && id <= 5) { menu = menu == id - 2 ? 0 : id - 2; if (menu != 3) blueprint = -1; }
            else if (id >= 100 && id < 200 && !selected.isEmpty()) Orders.give(picked(), Orders.Kind.CRAFT, 0, 0, 0, -1, Keyboard.isKeyDown(Keyboard.KEY_LSHIFT), CRAFTS[id - 100][0], Integer.parseInt(CRAFTS[id - 100][1]));
            else if (id >= 200 && id < 300 && !selected.isEmpty()) Orders.give(new int[] { picked()[0] }, Orders.Kind.SMELT, 0, 0, 0, -1, Keyboard.isKeyDown(Keyboard.KEY_LSHIFT), SMELTS[id - 200][0], Integer.parseInt(SMELTS[id - 200][1]));
            else if (id >= 300 && id < 310) blueprint = blueprint == id - 300 ? -1 : id - 300;
            else if (id == 310) material = (material + 1) % MATERIALS.length;
            else if (id == 311) turn = (turn + 1) & 3;
            initGui();
        }

        @Override
        protected void keyTyped(char c, int code) {
            lastInput = System.currentTimeMillis();
            if (code == Keyboard.KEY_ESCAPE && (blueprint >= 0 || guardPick || menu != 0)) { blueprint = -1; guardPick = false; menu = 0; initGui(); return; }
            if (code == Keyboard.KEY_ESCAPE || code == key.getKeyCode()) { mc.displayGuiScreen(null); return; }
            boolean ctrl = Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
            if (code >= Keyboard.KEY_1 && code <= Keyboard.KEY_4) {
                int g = code - Keyboard.KEY_1 + 1;
                if (ctrl) groups.put(g, new LinkedHashSet<Integer>(selected));
                else if (groups.containsKey(g)) { selected.clear(); selected.addAll(groups.get(g)); }
                return;
            }
            if (code == Keyboard.KEY_Z && blueprint >= 0) turn = (turn + 1) & 3;
            if (code == Keyboard.KEY_K || code == Keyboard.KEY_P || code == Keyboard.KEY_C || code == Keyboard.KEY_F || code == Keyboard.KEY_G)
                filmKey(code, Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT));
            if (code == Keyboard.KEY_V && Cam.instance != null) { Cam.instance.autoOn = false; Cam.instance.release(); }   // back to the commander's own view
            if (code == Keyboard.KEY_H && !selected.isEmpty()) Orders.give(picked(), Orders.Kind.STOP, 0, 0, 0, -1, false);       // hold
            if (code == Keyboard.KEY_R && !selected.isEmpty()) Orders.give(picked(), Orders.Kind.RELEASE, 0, 0, 0, -1, false);    // back to the AI
        }

        @Override
        public void handleMouseInput() {
            if (!Display.isActive()) return;   // the window is not the player's now: nothing from the mouse
            lastInput = System.currentTimeMillis();
            int scale = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
            int b = Mouse.getEventButton(), mx = Mouse.getEventX(), my = Mouse.getEventY();
            int guiY = (mc.displayHeight - my) / scale;
            if (guiY >= height - PANEL - (menu != 0 ? ROW : 0) && downButton < 0) { super.handleMouseInput(); return; }   // the panel: its buttons
            int wheel = Mouse.getEventDWheel();
            if (wheel != 0) dist = Math.max(6, Math.min(70, dist * (wheel > 0 ? 0.88 : 1.14)));
            boolean shift = Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
            if (b == -1) {   // a move
                if (downButton == 0 && (Math.abs(mx - downX) > 6 || Math.abs(my - downY) > 6)) dragging = true;
                if (downButton == 2) yaw += Mouse.getEventDX() * 0.4;
                return;
            }
            if (Mouse.getEventButtonState()) {   // pressed
                downButton = b; downX = mx; downY = my; dragging = false;
                if (b == 1) {
                    if (blueprint >= 0 || guardPick) { blueprint = -1; guardPick = false; initGui(); }   // a right click puts the tool away
                    else order(pick(mx, my), shift);
                }
                return;
            }
            if (b == 0 && downButton == 0) {   // released: a box, or a click
                if (blueprint >= 0 && !dragging) { build(buildAt()); if (!shift) { blueprint = -1; initGui(); } }
                else if (guardPick && !dragging) {
                    Object o = pick(mx, my);
                    if (o instanceof MovingObjectPosition && !selected.isEmpty()) {
                        MovingObjectPosition m = (MovingObjectPosition) o;
                        Orders.give(picked(), Orders.Kind.GUARD, m.blockX, m.blockY, m.blockZ, -1, shift);
                        mark(m.blockX + 0.5, m.blockY + 1, m.blockZ + 0.5, 0xFF8040);
                    }
                    guardPick = false;
                } else if (dragging) selectBox(downX, downY, mx, my, shift);
                else {
                    Object o = pick(mx, my);
                    if (o instanceof Agent) {
                        int s = ((Agent) o).skin();
                        if (s == lastClickSkin && System.currentTimeMillis() - lastClick < 350) { for (Agent a : clientAgents()) selected.add(a.skin()); }
                        else select(s, shift);
                        lastClickSkin = s;
                        lastClick = System.currentTimeMillis();
                    } else if (!shift) selected.clear();
                }
            }
            downButton = -1;
            dragging = false;
        }

        @Override
        public void drawScreen(int gmx, int gmy, float pt) {
            drawn++;
            int scale = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
            for (Agent a : clientAgents()) {   // names and health over every agent
                float[] s = toScreen(a.posX, a.boundingBox.maxY + 0.45, a.posZ);
                if (s == null) continue;
                int x = (int) (s[0] / scale), y = (int) ((mc.displayHeight - s[1]) / scale);
                boolean sel = selected.contains(a.skin());
                float hp = a.getHealth() / a.getMaxHealth();
                Gui.drawRect(x - 13, y, x + 13, y + 3, 0xC0000000);
                Gui.drawRect(x - 12, y + 1, x - 12 + (int) (24 * hp), y + 2, hp > 0.5 ? 0xFF40E060 : hp > 0.25 ? 0xFFE0C040 : 0xFFE04040);
                String n = a.getCustomNameTag();
                fontRendererObj.drawStringWithShadow(n, x - fontRendererObj.getStringWidth(n) / 2, y - 10, sel ? 0x80FF80 : 0xFFFFFF);
            }
            if (dragging && downButton == 0) {   // the box being dragged
                int x0 = downX / scale, y0 = (mc.displayHeight - downY) / scale, x1 = Mouse.getX() / scale, y1 = (mc.displayHeight - Mouse.getY()) / scale;
                Gui.drawRect(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1), 0x3040FF60);
            }
            String tool = blueprint >= 0 ? "Строить: " + BLUEPRINTS[blueprint] + " (" + shown(MATERIALS[material]) + ")  ЛКМ: поставить, Shift: ещё, Z: повернуть, ПКМ/Esc: отмена"
                    : guardPick ? "Охрана: ЛКМ по земле, где стоять" : null;
            if (tool != null) fontRendererObj.drawStringWithShadow(tool, 4, 4, 0xFFE080);
            panel();
            super.drawScreen(gmx, gmy, pt);
        }

        /** the bottom: the picked agents, each with its health, what it does (its card) and what it carries */
        void panel() {
            int y0 = height - PANEL;
            Gui.drawRect(0, y0, width, height, 0xB0101418);
            fontRendererObj.drawStringWithShadow("WASD/край  колесо  Q/E  ЛКМ: выбрать/рамка  ПКМ: приказ (Shift: очередь)  Ctrl+1-4/1-4  H: стоп  R: ИИ  |  K: ключ  P/Shift+P: путь/запись  C  F: следить  G: режиссёр  V: вид  F7",
                    4, y0 + 2, 0x909090);
            int x = 4;
            if (selected.isEmpty()) fontRendererObj.drawStringWithShadow("Выдели агентов: клик по агенту, рамка мышью, двойной клик — все", 4, y0 + 18, 0xFFE080);
            for (int s : selected) {
                Agent a = Cam.serverAgent(s);
                if (a == null) continue;
                TaskBoard.Card k = Crew.instance == null ? null : Crew.instance.board.leased.get(a);
                String doing = k != null ? doingRu(k.kind) : a.commanded ? "ждёт приказа" : "ИИ";
                StringBuilder bag = new StringBuilder();
                try {
                    for (ItemStack it : new ArrayList<ItemStack>(a.bag)) { if (bag.length() > 34) { bag.append("…"); break; } bag.append(bag.length() > 0 ? ", " : "").append(it.getDisplayName()).append(it.stackSize > 1 ? " ×" + it.stackSize : ""); }
                } catch (Exception ex) {
                    // read from the server's thread: the next frame shows it
                }
                fontRendererObj.drawStringWithShadow(a.getCustomNameTag() + "  " + Math.round(a.getHealth()) + "♥  " + doing, x, y0 + 13, 0xFFFFFF);
                fontRendererObj.drawStringWithShadow(bag.length() == 0 ? "(пусто)" : bag.toString(), x, y0 + 24, 0xB0B0B0);
                x += Math.max(140, fontRendererObj.getStringWidth(bag.toString()) + 12);
                if (x > width - 340) break;
            }
        }
    }
}
