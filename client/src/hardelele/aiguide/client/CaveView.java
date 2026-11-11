package hardelele.aiguide.client;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.util.IIcon;
import net.minecraft.util.MathHelper;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/**
 * The underground view, always on: around every agent that is under the ground the rock turns see-through. Near the agent
 * the shaft and the rock are fully transparent (the cave, its ores and what the agent does show), and with distance the rock
 * gets more and more opaque (a smoothstep, CaveFade) until, at CaveFade.RADIUS, the world is drawn as usual.
 *
 * How it is drawn (the world itself is never changed, nothing here reaches the agents' knowledge): after the world a soft
 * dark disc facing the camera stands in for the removed rock, then the exposed faces of the cave that the agent stands in
 * (the real block textures; only faces already exposed to air, so a sealed ore is never shown), each with an alpha that falls
 * with its distance from the agent, painted far to near without depth writes; then the agent and what stands near it, again.
 * It works the same in the commander, in the plain view and in the film camera, since it hangs on the world's last render.
 * The zone follows the agent smoothly, comes and goes by real time, and the cave is scanned a few hundred cells a tick.
 */
public final class CaveView {
    static final CaveView INSTANCE = new CaveView();
    /** the scan box around the agent: the zone's radius, and the room for the agent to move before a rescan */
    static final int SCAN_R = (int) Math.ceil(CaveFade.RADIUS) + 6, MAX_CELLS = 9000, MAX_FACES = 6000, CELLS_PER_TICK = 400;
    static final int ICONS_PER_FRAME = 600, MAX_ZONES = 4, RESCAN_MOVE = 5, RESCAN_TICKS = 200;
    /** a face seen from behind (its cave on the far side of the wall) is this much fainter */
    static final float BACK = 0.30f;
    /** the viewer's switch (key C): the view itself needs no mode, it is simply on */
    static boolean on = true;

    private static final Map<Integer, Zone> zones = new HashMap<Integer, Zone>();
    private static final ArrayList<Zone> shown = new ArrayList<Zone>();
    private static World zonesWorld;
    private static long lastNanos;
    private static boolean focusOn;
    private static int focusId = -1, focusIron = -1;
    private static final BitSet liveIron = new BitSet();
    private static final FloatBuffer matrix = BufferUtils.createFloatBuffer(16);
    private static final int[] drawnIds = new int[96];
    private static int drawnCount;

    // quad corners of a block's face by side (0 down, 1 up, 2 north, 3 south, 4 west, 5 east), as in the old face()
    private static final int[][][] QUAD = {
        {{0,0,0},{1,0,0},{1,0,1},{0,0,1}}, {{0,1,0},{0,1,1},{1,1,1},{1,1,0}},
        {{0,0,0},{0,1,0},{1,1,0},{1,0,0}}, {{1,0,1},{1,1,1},{0,1,1},{0,0,1}},
        {{0,0,1},{0,1,1},{0,1,0},{0,0,0}}, {{1,0,0},{1,1,0},{1,1,1},{1,0,1}}};
    private static final int[] NX = {0, 0, 0, 0, -1, 1}, NY = {-1, 1, 0, 0, 0, 0}, NZ = {0, 0, -1, 1, 0, 0};
    private static final int RINGS = 10, SEGS = 36;
    private static final float[] RING_CLARITY = new float[RINGS + 1];
    private static final double[] SEG_COS = new double[SEGS + 1], SEG_SIN = new double[SEGS + 1];
    static {
        for (int i = 0; i <= RINGS; i++) RING_CLARITY[i] = (float) CaveFade.clarity((double) i / RINGS, 1);
        for (int j = 0; j <= SEGS; j++) { SEG_COS[j] = Math.cos(Math.PI * 2 * j / SEGS); SEG_SIN[j] = Math.sin(Math.PI * 2 * j / SEGS); }
    }

    /** the exposed faces of one scanned cave, and what is needed to draw them */
    private static final class Mesh {
        final CaveGeometry.Result r;
        final CaveGeometry.Probe probe;
        final int n;
        final IIcon[] icons;
        final Block[] blocks;
        final int[] meta;
        final float[] red, green, blue, alpha;
        final BitSet iron = new BitSet();
        long[] order = new long[0];
        double sortX = 1e9, sortY, sortZ;
        int cached;

        Mesh(CaveGeometry.Result r, CaveGeometry.Probe probe) {
            this.r = r; this.probe = probe; n = r.faces.size();
            icons = new IIcon[n]; blocks = new Block[n]; meta = new int[n];
            red = new float[n]; green = new float[n]; blue = new float[n]; alpha = new float[n];
        }

        boolean ready() { return cached >= n; }

        /** the textures and colours of the next faces; spread over frames so a big cave never costs one */
        void cache(World w, int budget) {
            for (int k = 0; k < budget && cached < n; k++, cached++) {
                CaveGeometry.Face f = r.faces.get(cached);
                boolean here = w.blockExists(f.x, f.y, f.z);
                Block b = here ? w.getBlock(f.x, f.y, f.z) : Blocks.stone;
                IIcon icon = b.getIcon(w, f.x, f.y, f.z, f.side);
                if (icon == null) icon = Blocks.stone.getIcon(f.side, 0);
                icons[cached] = icon; blocks[cached] = b; meta[cached] = here ? w.getBlockMetadata(f.x, f.y, f.z) : 0;
                float level = r.maxLevel <= r.minLevel ? 0.5f : Math.max(0f, Math.min(1f, (float) (f.y - r.minLevel) / (r.maxLevel - r.minLevel)));
                if (b == Blocks.iron_ore) { iron.set(cached); red[cached] = green[cached] = blue[cached] = alpha[cached] = 1f; }   // the real exposed iron
                else if (f.side == 1) { red[cached] = 0.78f + 0.16f * level; green[cached] = 0.64f + 0.16f * level; blue[cached] = 0.45f + 0.15f * level; alpha[cached] = 0.88f; }   // floors: warm, so stairs read as levels
                else if (f.side == 0) { red[cached] = 0.42f + 0.13f * level; green[cached] = 0.52f + 0.13f * level; blue[cached] = 0.61f + 0.11f * level; alpha[cached] = 0.55f; }   // ceilings: cool, fainter
                else {
                    float side = f.side == 2 || f.side == 4 ? 0.88f : 1f;
                    red[cached] = (0.66f + 0.19f * level) * side; green[cached] = (0.72f + 0.17f * level) * side; blue[cached] = (0.77f + 0.15f * level) * side;
                    alpha[cached] = 0.92f;
                }
            }
        }

        /** far to near from the camera; the keys are packed with the face's number, so there is nothing to box */
        void sort(double x, double y, double z) {
            if (Math.abs(x - sortX) + Math.abs(y - sortY) + Math.abs(z - sortZ) < 1.0 && order.length == n) return;
            if (order.length != n) order = new long[n];
            for (int i = 0; i < n; i++) {
                CaveGeometry.Face f = r.faces.get(i);
                double dx = f.x + 0.5 - x, dy = f.y + 0.5 - y, dz = f.z + 0.5 - z;
                long key = (long) ((dx * dx + dy * dy + dz * dz) * 16);   // squared distance, to 1/16
                order[i] = (Long.MAX_VALUE >> 24) - Math.min(key, Long.MAX_VALUE >> 24) << 24 | i;   // larger distance first
            }
            Arrays.sort(order);
            sortX = x; sortY = y; sortZ = z;
        }
    }

    /** one agent's view: its smoothed centre, its fade, the cave around it */
    private static final class Zone {
        final int id;
        Agent agent;
        boolean under, want, placed;
        double progress, emphasis = 1, x, y, z;
        Mesh live, building;
        CaveGeometry.Scanner scanner;
        CaveGeometry.Probe pendingProbe;
        int seedX, seedY, seedZ;
        long steppedTick = -1, completedTick = -1;
        Zone(int id) { this.id = id; }
        void drop() { live = null; building = null; scanner = null; }
    }

    static void start() { MinecraftForge.EVENT_BUS.register(INSTANCE); }

    // ---- asking about an agent ------------------------------------------------------------------------

    /** blocks of rock over the agent's head: the surface (the top of the height map) above its top */
    static double depth(World w, Entity a) {
        return w.getHeightValue(MathHelper.floor_double(a.posX), MathHelper.floor_double(a.posZ)) - a.boundingBox.maxY;
    }

    static int skyLight(World w, Entity a) {
        int y = Math.max(0, Math.min(255, MathHelper.floor_double(a.boundingBox.minY + a.height * 0.5)));
        return w.getSavedLightValue(EnumSkyBlock.Sky, MathHelper.floor_double(a.posX), y, MathHelper.floor_double(a.posZ));
    }

    /** the same test as the view's, without its memory: a fresh look, for a place that has no zone yet */
    static boolean belowSurface(Agent a) {
        World w = Minecraft.getMinecraft().theWorld;
        return a != null && w != null && CaveFade.underground(false, depth(w, a), skyLight(w, a));
    }

    /** whether this agent is underground by the view's own state (with its hysteresis) */
    static boolean underground(Agent a) {
        Zone z = a == null ? null : zones.get(a.getEntityId());
        return z != null ? z.under : belowSurface(a);
    }

    /** a zone that is clear enough to see into, for the picking ray and the camera */
    static boolean showing(Agent a) {
        Zone z = a == null ? null : zones.get(a.getEntityId());
        return on && z != null && z.progress > 0.6;
    }

    /**
     * Where along the ray (from p0 to p1) the visible zone begins, so a click lands on the cave the person sees and not on the
     * ground above it. Returns the share of the ray (0 if the start is already inside, or no zone is on the way), as a start.
     */
    static double rayStart(double x0, double y0, double z0, double x1, double y1, double z1) {
        if (!on) return 0;
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0, len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6) return 0;
        double best = 0;
        boolean found = false;
        for (Zone z : zones.values()) {
            if (z.progress < 0.6 || z.live == null) continue;
            double r = CaveFade.radius(z.progress, z.emphasis) * 0.8;
            double ox = x0 - z.x, oy = y0 - z.y, oz = z0 - z.z;
            double b = (ox * dx + oy * dy + oz * dz) / len, c = ox * ox + oy * oy + oz * oz - r * r;
            double disc = b * b - c;
            if (disc < 0) continue;
            double t = -b - Math.sqrt(disc);   // where the ray enters the ball, in blocks along it
            if (-b + Math.sqrt(disc) < 0) continue;   // the ball is behind
            double share = Math.max(0, Math.min(1, t / len));
            if (!found || share < best) { best = share; found = true; }
        }
        return best;
    }

    // ---- the close look at an agent (key F): the camera on it and its nearest iron -----------------------

    static boolean focused() { return focusOn; }

    static boolean toggleFocus(Agent a) {
        if (focusOn) { resetFocus(); return true; }
        Zone z = a == null ? null : zones.get(a.getEntityId());
        if (z == null || !z.under || z.live == null || z.progress < 0.6) return false;
        focusOn = true; focusId = a.getEntityId();
        return true;
    }

    static void resetFocus() { focusOn = false; focusId = -1; focusIron = -1; }

    /** Only cached exposed faces of the mapped cave may influence the camera. */
    static double[] focusPoint(Agent a) {
        if (!focusOn) return null;
        Zone z = a == null ? null : zones.get(a.getEntityId());
        if (z == null || a.getEntityId() != focusId || !z.under || z.live == null || !z.live.ready()) { if (focusOn) resetFocus(); return null; }
        Mesh m = z.live;
        double ax = a.posX, ay = a.boundingBox.minY + 1.0, az = a.posZ;
        World w = Minecraft.getMinecraft().theWorld;
        liveIron.clear();
        for (int i = m.iron.nextSetBit(0); i >= 0 && i < m.n; i = m.iron.nextSetBit(i + 1))
            if (currentIronFace(w, m, m.r.faces.get(i))) liveIron.set(i);
        focusIron = CaveGeometry.nearestFace(m.r.faces, liveIron, ax, ay, az, 3.0);
        if (focusIron < 0) return new double[] { ax, ay, az };
        CaveGeometry.Face ore = m.r.faces.get(focusIron);
        return new double[] { (ax + ore.x + 0.5) * 0.5, (ay + ore.y + 0.5) * 0.5, (az + ore.z + 0.5) * 0.5 };
    }

    private static boolean currentIronFace(World w, Mesh m, CaveGeometry.Face f) {
        if (w == null || !w.blockExists(f.x, f.y, f.z) || w.getBlock(f.x, f.y, f.z) != Blocks.iron_ore) return false;
        int nx = f.x + NX[f.side], ny = f.y + NY[f.side], nz = f.z + NZ[f.side];
        return w.blockExists(nx, ny, nz) && m.r.contains(nx, ny, nz) && isOpen(w, nx, ny, nz);
    }

    static void exit() {
        zones.clear(); shown.clear(); resetFocus();
        zonesWorld = null;
    }

    // ---- the scan ----------------------------------------------------------------------------------------

    /** No chunk load: only blocks already present in the client's current world are sampled. */
    private static CaveGeometry.Scanner scan(final World w, int x, int y, int z, CaveGeometry.Probe[] probeOut) {
        final CaveGeometry.Probe p = new CaveGeometry.Probe() {
            public int type(int bx, int by, int bz) {
                if (by < 1 || by > 254 || !w.blockExists(bx, by, bz)) return CaveGeometry.UNLOADED;
                return isOpen(w, bx, by, bz) ? CaveGeometry.OPEN : CaveGeometry.SOLID;
            }
            public int surface(int bx, int bz) { return w.getHeightValue(bx, bz); }
        };
        probeOut[0] = p;
        if (p.type(x, y, z) != CaveGeometry.OPEN && p.type(x, y + 1, z) == CaveGeometry.OPEN) y++;
        return new CaveGeometry.Scanner(p, x, y, z, SCAN_R, Math.max(1, y - SCAN_R), Math.min(254, y + SCAN_R), MAX_CELLS, MAX_FACES);
    }

    private static boolean isOpen(World w, int x, int y, int z) {
        Block b = w.getBlock(x, y, z);
        return !b.getMaterial().isLiquid() && (b.isPassable(w, x, y, z) || b.getCollisionBoundingBoxFromPool(w, x, y, z) == null);
    }

    /** a solid put or a wall taken out near the agent: the old picture is wrong, scan again */
    private static boolean nearbyChanged(World w, Mesh m, int ax, int ay, int az) {
        if (m.r.nearbyOpenChanged(m.probe, ax, ay, az, 4)) return true;
        for (int i = 0; i < m.cached; i++) {
            CaveGeometry.Face f = m.r.faces.get(i);
            if (Math.abs(f.x - ax) > 6 || Math.abs(f.y - ay) > 6 || Math.abs(f.z - az) > 6) continue;
            if (!w.blockExists(f.x, f.y, f.z) || w.getBlock(f.x, f.y, f.z) != m.blocks[i] || w.getBlockMetadata(f.x, f.y, f.z) != m.meta[i]) return true;
        }
        return false;
    }

    /** once a world tick for one zone: the cave scan, a few hundred cells at a time */
    private static void tickZone(Zone z, World w, long tick) {
        z.steppedTick = tick;
        Agent a = z.agent;
        if (a == null) return;
        int ax = MathHelper.floor_double(a.posX), ay = MathHelper.floor_double(a.boundingBox.minY + 0.2), az = MathHelper.floor_double(a.posZ);
        if (z.scanner == null) {
            boolean first = z.live == null;
            boolean moved = !first && (Math.abs(ax - z.seedX) > RESCAN_MOVE || Math.abs(az - z.seedZ) > RESCAN_MOVE || Math.abs(ay - z.seedY) > RESCAN_MOVE);
            boolean old = !first && tick - z.completedTick >= RESCAN_TICKS;
            if (first || moved || old || z.live != null && z.live.ready() && nearbyChanged(w, z.live, ax, ay, az)) {
                z.seedX = ax; z.seedY = ay; z.seedZ = az;
                CaveGeometry.Probe[] p = new CaveGeometry.Probe[1];
                z.scanner = scan(w, ax, ay, az, p);
                z.building = null;
                z.pendingProbe = p[0];
            }
        }
        if (z.scanner != null && !z.scanner.done) z.scanner.step(CELLS_PER_TICK);
        if (z.scanner != null && z.scanner.done && z.building == null) {
            z.completedTick = tick;
            if (z.scanner.out.cells > 0) z.building = new Mesh(z.scanner.out, z.pendingProbe);   // an empty result (the agent in water) keeps the last picture
            else z.scanner = null;
        }
    }

    // ---- the frame ------------------------------------------------------------------------------------

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void end(RenderWorldLastEvent e) {
        Minecraft mc = Minecraft.getMinecraft();
        World w = mc.theWorld;
        if (w == null || mc.renderViewEntity == null) { if (!zones.isEmpty()) exit(); return; }
        if (zonesWorld != w) { exit(); zonesWorld = w; }
        long now = System.nanoTime();
        double dt = lastNanos == 0 ? 0 : Math.min(0.1, (now - lastNanos) / 1e9);
        if (Recorder.instance != null) dt = lastNanos == 0 ? 0 : 1 / 60.0;   // recording: by video frames
        lastNanos = now;
        float pt = e.partialTicks;
        double camX = RenderManager.renderPosX, camY = RenderManager.renderPosY, camZ = RenderManager.renderPosZ;
        update(w, pt, dt, camX, camY, camZ);
        if (shown.isEmpty()) return;
        draw(mc, w, pt, camX, camY, camZ);
    }

    /** the agents' states, the fades and the scans; allocates only when a zone is born or a scan starts */
    private static void update(World w, float pt, double dt, double camX, double camY, double camZ) {
        for (Zone z : zones.values()) z.agent = null;
        java.util.List<?> all = w.loadedEntityList;
        Commander c = Commander.instance;
        boolean sel = c != null && c.on && !c.selected.isEmpty();
        for (int i = 0, n = all.size(); i < n; i++) {
            Object o = all.get(i);
            if (!(o instanceof Agent)) continue;
            Agent a = (Agent) o;
            if (a.isDead) continue;
            Zone z = zones.get(a.getEntityId());
            if (z == null) { z = new Zone(a.getEntityId()); zones.put(z.id, z); }
            z.agent = a;
            z.under = on && CaveFade.underground(z.under, depth(w, a), skyLight(w, a));
            // the middle of the body, between ticks
            double tx = a.lastTickPosX + (a.posX - a.lastTickPosX) * pt, ty = a.lastTickPosY + (a.posY - a.lastTickPosY) * pt + a.height * 0.5, tz = a.lastTickPosZ + (a.posZ - a.lastTickPosZ) * pt;
            if (!z.placed) { z.x = tx; z.y = ty; z.z = tz; z.placed = true; }
            else {
                double k = CaveFade.followWeight(dt, CaveFade.FOLLOW);
                z.x += (tx - z.x) * k; z.y += (ty - z.y) * k; z.z += (tz - z.z) * k;
            }
            boolean chosen = !sel || c.selected.contains(a.skin());
            z.emphasis = CaveFade.approach(z.emphasis, chosen, dt, 0.4, 0.4);
        }
        // the zones that may be shown: the chosen and the nearest ones, a few
        shown.clear();
        for (Zone z : zones.values()) if (z.under && z.agent != null) shown.add(z);
        for (int i = 1; i < shown.size(); i++) {   // insertion sort by priority (a few items): chosen first, then the nearest to the camera
            Zone z = shown.get(i);
            double kz = priority(z, c, camX, camY, camZ);
            int j = i - 1;
            while (j >= 0 && priority(shown.get(j), c, camX, camY, camZ) > kz) { shown.set(j + 1, shown.get(j)); j--; }
            shown.set(j + 1, z);
        }
        for (int i = 0; i < shown.size(); i++) shown.get(i).want = i < MAX_ZONES;
        java.util.Iterator<Zone> it = zones.values().iterator();
        while (it.hasNext()) {
            Zone z = it.next();
            if (!(z.under && z.agent != null && z.want)) z.want = false;
            z.progress = CaveFade.approach(z.progress, z.want, dt, CaveFade.FADE_IN, CaveFade.FADE_OUT);
            if (z.want) {
                long tick = w.getTotalWorldTime();
                if (z.steppedTick != tick) tickZone(z, w, tick);
                if (z.building != null) {
                    z.building.cache(w, ICONS_PER_FRAME);
                    if (z.building.ready()) { z.live = z.building; z.building = null; z.scanner = null; z.live.sortX = 1e9; }
                }
            } else if (z.progress <= 0) {
                if (!z.under) z.drop();
                if (z.agent == null) { if (focusId == z.id) resetFocus(); it.remove(); }
            }
        }
        shown.clear();
        for (Zone z : zones.values()) if (z.progress > 0.002 && z.live != null && z.agent != null) shown.add(z);
        for (int i = 1; i < shown.size(); i++) {   // far to near: a nearer zone's backdrop veils a farther one's faces, not the reverse
            Zone z = shown.get(i);
            double kz = dist2(z, camX, camY, camZ);
            int j = i - 1;
            while (j >= 0 && dist2(shown.get(j), camX, camY, camZ) < kz) { shown.set(j + 1, shown.get(j)); j--; }
            shown.set(j + 1, z);
        }
    }

    private static double dist2(Zone z, double x, double y, double zc) {
        double dx = z.x - x, dy = z.y - y, dz = z.z - zc;
        return dx * dx + dy * dy + dz * dz;
    }

    private static double priority(Zone z, Commander c, double x, double y, double zc) {
        boolean chosen = c != null && c.on && z.agent != null && c.selected.contains(z.agent.skin());
        return dist2(z, x, y, zc) - (chosen ? 1e9 : 0);
    }

    private static void draw(Minecraft mc, World w, float pt, double camX, double camY, double camZ) {
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_LIGHTING_BIT | GL11.GL_TEXTURE_BIT | GL11.GL_FOG_BIT | GL11.GL_LINE_BIT | GL11.GL_CURRENT_BIT);
        GL11.glPushMatrix();
        try {
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            // No depth test: the cave is the window and goes over the nearer ground; its own order is far to near.
            // And no clear of the depth buffer, so a shader pack's later passes keep what they read from it.
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glTranslated(-RenderManager.renderPosX, -RenderManager.renderPosY, -RenderManager.renderPosZ);
            matrix.clear();
            GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, matrix);
            for (int i = 0; i < shown.size(); i++) {
                Zone z = shown.get(i);
                double radius = CaveFade.radius(z.progress, z.emphasis), scale = CaveFade.clarityScale(z.progress, z.emphasis);
                if (radius < 0.3 || scale < 0.004) continue;
                double cd = Math.sqrt(dist2(z, camX, camY, camZ));
                backdrop(z, radius, scale * CaveFade.viewerScale(cd, radius) * CaveFade.BACKDROP);
                faces(mc, z, radius, scale, camX, camY, camZ);
                marker(z.agent, pt, scale);
            }
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
        // What stands in a zone is drawn again, over the ground that hid it. Never against the depth buffer, which still has the ground.
        drawnCount = 0;
        GL11.glPushAttrib(GL11.GL_DEPTH_BUFFER_BIT);
        try {
            GL11.glDepthFunc(GL11.GL_ALWAYS);
            for (int i = 0; i < shown.size(); i++) redrawInside(mc, w, shown.get(i), pt);
        } finally {
            GL11.glPopAttrib();
        }
    }

    /** a soft dark disc where the rock was, clear at the agent's heart and gone at the radius */
    private static void backdrop(Zone z, double radius, double top) {
        if (top < 0.004) return;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        double rx = matrix.get(0), ry = matrix.get(4), rz = matrix.get(8), ux = matrix.get(1), uy = matrix.get(5), uz = matrix.get(9);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        for (int i = 0; i < RINGS; i++) {
            double r0 = radius * i / RINGS, r1 = radius * (i + 1) / RINGS;
            float a0 = (float) (RING_CLARITY[i] * top), a1 = (float) (RING_CLARITY[i + 1] * top);
            for (int j = 0; j < SEGS; j++) {
                double c0 = SEG_COS[j], s0 = SEG_SIN[j], c1 = SEG_COS[j + 1], s1 = SEG_SIN[j + 1];
                t.setColorRGBA_F(0.07f, 0.085f, 0.10f, a0);
                t.addVertex(z.x + (rx * c0 + ux * s0) * r0, z.y + (ry * c0 + uy * s0) * r0, z.z + (rz * c0 + uz * s0) * r0);
                t.addVertex(z.x + (rx * c1 + ux * s1) * r0, z.y + (ry * c1 + uy * s1) * r0, z.z + (rz * c1 + uz * s1) * r0);
                t.setColorRGBA_F(0.07f, 0.085f, 0.10f, a1);
                t.addVertex(z.x + (rx * c1 + ux * s1) * r1, z.y + (ry * c1 + uy * s1) * r1, z.z + (rz * c1 + uz * s1) * r1);
                t.addVertex(z.x + (rx * c0 + ux * s0) * r1, z.y + (ry * c0 + uy * s0) * r1, z.z + (rz * c0 + uz * s0) * r1);
            }
        }
        t.draw();
    }

    /** the cave's exposed faces, each fading with its distance from the agent */
    private static void faces(Minecraft mc, Zone z, double radius, double scale, double camX, double camY, double camZ) {
        Mesh m = z.live;
        if (m == null || m.n == 0) return;
        m.sort(camX, camY, camZ);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.setBrightness(0xF000F0);
        for (int k = 0; k < m.n; k++) {   // sorted ascending by (max - distance): the farthest first
            int i = (int) (m.order[k] & 0xFFFFFF);
            CaveGeometry.Face f = m.r.faces.get(i);
            double dx = f.x + 0.5 - z.x, dy = f.y + 0.5 - z.y, dz = f.z + 0.5 - z.z;
            // a face's corners are within 0.87 of its centre, so this skips only faces wholly beyond the radius
            double lim = radius + 0.9;
            if (dx * dx + dy * dy + dz * dz > lim * lim) continue;
            if (i >= m.cached) continue;
            IIcon icon = m.icons[i];
            if (icon == null) continue;
            int s = f.side;
            double toCam = (camX - (f.x + 0.5)) * NX[s] + (camY - (f.y + 0.5)) * NY[s] + (camZ - (f.z + 0.5)) * NZ[s];
            float base = (float) scale * m.alpha[i] * (toCam >= 0 ? 1f : BACK);
            if (base < 0.004f) continue;
            float u0 = icon.getMinU(), u1 = icon.getMaxU(), v0 = icon.getMinV(), v1 = icon.getMaxV();
            int[][] q = QUAD[s];
            for (int v = 0; v < 4; v++) {
                double px = f.x + q[v][0], py = f.y + q[v][1], pz = f.z + q[v][2];
                double ex = px - z.x, ey = py - z.y, ez = pz - z.z;
                float a = base * (float) CaveFade.clarity(Math.sqrt(ex * ex + ey * ey + ez * ez), radius);
                t.setColorRGBA_F(m.red[i], m.green[i], m.blue[i], a);
                t.addVertexWithUV(px, py, pz, (v == 0 || v == 1) ? u0 : u1, (v == 0 || v == 3) ? v0 : v1);
            }
        }
        t.draw();
    }

    /** where the agent is: a ring on the floor under it and a line up its height */
    private static void marker(Agent a, float pt, double scale) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        double x = a.lastTickPosX + (a.posX - a.lastTickPosX) * pt, y = a.lastTickPosY + (a.posY - a.lastTickPosY) * pt, z = a.lastTickPosZ + (a.posZ - a.lastTickPosZ) * pt;
        GL11.glLineWidth(2.5f);
        GL11.glColor4f(0.32f, 1f, 0.64f, (float) (0.85 * scale));
        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int j = 0; j < 20; j++) GL11.glVertex3d(x + SEG_COS[j * 2 % SEGS] * 0.6, y + 0.05, z + SEG_SIN[j * 2 % SEGS] * 0.6);
        GL11.glEnd();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    private static void redrawInside(Minecraft mc, World w, Zone z, float pt) {
        double radius = CaveFade.radius(z.progress, z.emphasis);
        if (z.progress < 0.5 || radius < 1) return;
        if (z.agent != null && drawn(z.agent)) { /* already drawn for a nearer zone */ } else if (z.agent != null) {
            RenderManager.instance.renderEntityStatic(z.agent, pt, false);
            note(z.agent);
        }
        double lim = radius * 0.75, lim2 = lim * lim;
        java.util.List<?> all = w.loadedEntityList;
        for (int i = 0, n = all.size(); i < n && drawnCount < drawnIds.length; i++) {
            Object o = all.get(i);
            if (!(o instanceof Entity) || o == mc.renderViewEntity || o == z.agent) continue;
            Entity en = (Entity) o;
            double dx = en.posX - z.x, dy = en.posY - z.y, dz = en.posZ - z.z;
            if (dx * dx + dy * dy + dz * dz > lim2 || drawn(en)) continue;
            RenderManager.instance.renderEntityStatic(en, pt, false);
            note(en);
        }
    }

    private static boolean drawn(Entity e) {
        for (int i = 0; i < drawnCount; i++) if (drawnIds[i] == e.getEntityId()) return true;
        return false;
    }

    private static void note(Entity e) { if (drawnCount < drawnIds.length) drawnIds[drawnCount++] = e.getEntityId(); }
}
