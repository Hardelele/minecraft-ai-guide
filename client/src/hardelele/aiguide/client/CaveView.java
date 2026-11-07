package hardelele.aiguide.client;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.util.IIcon;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import org.lwjgl.opengl.GL11;

/** A viewer-only volume of the connected, loaded air around one selected underground agent. */
public final class CaveView {
    static final CaveView INSTANCE = new CaveView();
    private static final int RADIUS = 28, MAX_CELLS = 12000, MAX_FACES = 8000, CELLS_PER_TICK = 400;
    static boolean on;
    static CaveGeometry.Result volume;
    private static World sampledWorld;
    private static int sampledX, sampledY, sampledZ, sampledSkin = -1;
    private static long steppedTick = -1, checkedTick = -1, completedTick = -1;
    private static long lastValidTick = -1;
    private static boolean holdingLast;
    private static CaveGeometry.Scanner scanner;
    private static final List<IIcon> icons = new ArrayList<IIcon>();
    private static final List<Block> faceBlocks = new ArrayList<Block>();
    private static final List<Integer> faceMeta = new ArrayList<Integer>();
    private static final List<Integer> faceOrder = new ArrayList<Integer>();
    private static final BitSet ironFaces = new BitSet();
    private static long scanNanos;
    private static double sortX, sortY, sortZ;
    private static boolean orderDirty;
    private static String savedShaderPack;

    static void start() { MinecraftForge.EVENT_BUS.register(INSTANCE); }

    static boolean active() {
        Commander c = Commander.instance;
        return on && c != null && c.on && c.selected.size() == 1 && Minecraft.getMinecraft().theWorld != null;
    }

    static boolean scanning() { return active() && scanner != null && !scanner.done; }
    static boolean holdingLast() { return active() && holdingLast; }

    static double anchorX() { return sampledX + 0.5; }
    static double anchorZ() { return sampledZ + 0.5; }

    static boolean agentOutside() {
        if (!active() || volume == null || volume.cells == 0) return false;
        Commander c = Commander.instance;
        Agent a = c.renderedAgent(c.selected.iterator().next());
        return a != null && !inside(a, volume, !Slice.belowSurface(a));
    }

    private static boolean inside(Agent a, CaveGeometry.Result g, boolean surfaced) {
        int x = MathHelper.floor_double(a.posX), y = MathHelper.floor_double(a.boundingBox.minY + 0.2), z = MathHelper.floor_double(a.posZ);
        return g != null && (g.contains(x, y, z) || g.contains(x, y + 1, z)
                || surfaced && g.exit && Math.abs(x - g.exitX) <= 2 && Math.abs(z - g.exitZ) <= 2
                && y >= g.exitY && y <= g.exitY + 4);
    }

    static void enter() {
        Commander c = Commander.instance;
        if (c == null || c.selected.size() != 1) return;
        Slice.exit();
        on = true;
        volume = null;
        scanner = null;
        holdingLast = false; lastValidTick = -1; sampledSkin = -1;
        Agent selected = c.renderedAgent(c.selected.iterator().next());
        if (selected != null) {
            sampledWorld = c.mc.theWorld;
            sampledSkin = c.selected.iterator().next();
            sampledX = MathHelper.floor_double(selected.posX);
            sampledY = MathHelper.floor_double(selected.boundingBox.minY + 0.2);
            sampledZ = MathHelper.floor_double(selected.posZ);
        }
        clearFaces();
        c.painting = false; c.paintFrom = null; c.paintTo = null; c.eraseFrom = null; c.eraseTo = null;
        if (c.mc.currentScreen instanceof Commander.Screen) {
            Commander.Screen s = (Commander.Screen) c.mc.currentScreen;
            s.downButton = -1; s.dragging = false; s.selecting = false; s.dragFrom = null;
        }
        c.dist = 45;
        c.pitch = 42;
        // The volume is drawn outside OptiFine's shader programs, as with the one-level slice.
        try {
            Class<?> shaders = Class.forName("shadersmod.client.Shaders");
            String pack = (String) shaders.getMethod("getShaderPackName").invoke(null);
            if (pack != null && !pack.isEmpty() && !pack.equals("OFF")) {
                shaders.getMethod("setShaderPack", String.class).invoke(null, "OFF");
                savedShaderPack = pack;
            }
        } catch (ClassNotFoundException ex) {
            // OptiFine is optional.
        } catch (Exception ex) { Director.log("cave view: shader switch failed: " + ex); }
        Director.log("cave view: on (loaded connected air only)");
    }

    static void exit() {
        if (on) Director.log("cave view: off");
        on = false;
        volume = null;
        scanner = null;
        holdingLast = false; lastValidTick = -1; sampledSkin = -1;
        clearFaces();
        sampledWorld = null;
        if (savedShaderPack != null) {
            try {
                Class.forName("shadersmod.client.Shaders").getMethod("setShaderPack", String.class).invoke(null, savedShaderPack);
                savedShaderPack = null;
            } catch (Exception ex) { Director.log("cave view: shader restore failed: " + ex); }
        }
    }

    /** No chunk load: only blocks already present in the client's current world are sampled. */
    private static CaveGeometry.Scanner scan(final World w, int x, int y, int z) {
        final CaveGeometry.Probe p = new CaveGeometry.Probe() {
            public int type(int bx, int by, int bz) {
                if (by < 1 || by > 254 || !w.blockExists(bx, by, bz)) return CaveGeometry.UNLOADED;
                return isOpen(w, bx, by, bz) ? CaveGeometry.OPEN : CaveGeometry.SOLID;
            }
            public int surface(int bx, int bz) { return w.getHeightValue(bx, bz); }
        };
        if (p.type(x, y, z) != CaveGeometry.OPEN && p.type(x, y + 1, z) == CaveGeometry.OPEN) y++;
        int high = Math.min(254, Math.max(y + 34, w.getHeightValue(x, z) + 5));
        return new CaveGeometry.Scanner(p, x, y, z, RADIUS, Math.max(1, y - 20), high, MAX_CELLS, MAX_FACES);
    }

    private static boolean isOpen(World w, int x, int y, int z) {
        Block b = w.getBlock(x, y, z);
        return !b.getMaterial().isLiquid() && (b.isPassable(w, x, y, z) || b.getCollisionBoundingBoxFromPool(w, x, y, z) == null);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void end(RenderWorldLastEvent e) {
        if (!active()) return;
        Commander c = Commander.instance;
        Agent a = c.renderedAgent(c.selected.iterator().next());
        Minecraft mc = Minecraft.getMinecraft();
        if (a == null || a.isDead || mc.renderViewEntity == null) { exit(); return; }
        int skin = c.selected.iterator().next();
        int ax = MathHelper.floor_double(a.posX), ay = MathHelper.floor_double(a.boundingBox.minY + 0.2), az = MathHelper.floor_double(a.posZ);
        long tick = mc.theWorld.getTotalWorldTime();
        boolean surfaced = !Slice.belowSurface(a);
        if (surfaced && (scanner == null || sampledWorld != mc.theWorld || volume == null)) { exit(); return; }
        // Expire a held shell on the exact world tick, even between 40-tick rescan attempts.
        if (holdingLast && !CaveRetention.hold(sampledWorld == mc.theWorld, sampledSkin == skin,
                volume == null ? 0 : volume.cells, lastValidTick, tick)) {
            volume = scanner.out;
            clearFaces(); holdingLast = false;
        }
        boolean changed = !surfaced && sampledWorld == mc.theWorld && checkedTick != tick && nearbyBlockChanged(mc.theWorld, ax, ay, az);
        checkedTick = tick;
        // The selected air component is a map, not a camera probe at the agent's feet.
        // Walking into a separate water-side pocket must not replace the route to the exit.
        if (scanner == null || sampledWorld != mc.theWorld || sampledSkin != skin
                || !surfaced && scanner.done && (changed || tick - completedTick >= 40)) {
            boolean newMap = scanner == null || sampledWorld != mc.theWorld || sampledSkin != skin;
            if (newMap) {
                sampledX = ax; sampledY = ay; sampledZ = az;
                volume = null; clearFaces(); lastValidTick = -1; holdingLast = false;
            }
            Director.log("cave view: scan seed " + sampledX + " " + sampledY + " " + sampledZ
                    + (newMap ? " new" : " refresh"));
            scanner = scan(mc.theWorld, sampledX, sampledY, sampledZ);
            scanNanos = 0; steppedTick = -1;
            if (volume == null) { volume = scanner.out; clearFaces(); }
            sampledWorld = mc.theWorld;
            sampledSkin = skin;
            if (scanner.done) finishScan(mc.theWorld, skin, tick);
        }
        if (!scanner.done && steppedTick != tick) {
            long start = System.nanoTime();
            scanner.step(CELLS_PER_TICK);
            scanNanos += System.nanoTime() - start;
            steppedTick = tick;
            if (scanner.done) finishScan(mc.theWorld, skin, tick);
        }
        cacheIcons(mc.theWorld);
        boolean selectedInside = inside(a, volume, surfaced);
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_LIGHTING_BIT | GL11.GL_TEXTURE_BIT | GL11.GL_FOG_BIT | GL11.GL_LINE_BIT);
        GL11.glPushMatrix();
        try {
            GL11.glClearColor(0.075f, 0.09f, 0.11f, 1f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDepthMask(false); // each level remains visible through the nearer shell
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glTranslated(-RenderManager.renderPosX, -RenderManager.renderPosY, -RenderManager.renderPosZ);
            mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
            sortFaces(mc.renderViewEntity.posX, mc.renderViewEntity.posY + 1.62, mc.renderViewEntity.posZ);
            drawFaces(); // far to near; faint ceilings and translucent floors keep lower levels visible
            GL11.glDepthMask(true);
            markers(a, selectedInside);
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
        // Re-draw every loaded entity in this connected component after clearing the world.
        if (selectedInside) RenderManager.instance.renderEntityStatic(a, e.partialTicks, false);
        int rendered = selectedInside ? 1 : 0;
        for (Object o : mc.theWorld.loadedEntityList) {
            if (!(o instanceof Entity) || o == mc.renderViewEntity || o == a) continue;
            Entity entity = (Entity) o;
            int ex = MathHelper.floor_double(entity.posX), ey = MathHelper.floor_double(entity.boundingBox.minY), ez = MathHelper.floor_double(entity.posZ);
            if (volume == null || !volume.contains(ex, ey, ez) && !volume.contains(ex, ey + 1, ez)) continue;
            RenderManager.instance.renderEntityStatic(entity, e.partialTicks, false);
            if (++rendered >= 48) break;
        }
    }

    private static void clearFaces() {
        icons.clear(); faceBlocks.clear(); faceMeta.clear(); faceOrder.clear(); ironFaces.clear(); orderDirty = true;
    }

    private static void finishScan(World w, int skin, long tick) {
        completedTick = tick;
        if (scanner.out.cells > 0) {
            volume = scanner.out; clearFaces(); lastValidTick = tick; holdingLast = false;
        } else if (CaveRetention.hold(sampledWorld == w, sampledSkin == skin,
                volume == null ? 0 : volume.cells, lastValidTick, tick)) holdingLast = true;
        else { volume = scanner.out; clearFaces(); holdingLast = false; }
        Director.log("cave view: " + scanner.out.cells + " cells, " + scanner.out.faces.size() + " faces, "
                + (scanNanos / 1000000) + " ms scan" + (scanner.out.limited() ? ", bounded" : ""));
    }

    private static boolean nearbyBlockChanged(World w, int ax, int ay, int az) {
        if (volume == null) return false;
        // Detect a newly placed solid or fluid in previously connected air as well as
        // a removed wall. Farther edits are picked up by the periodic bounded refresh.
        if (scanner != null && volume.nearbyOpenChanged(scanner.p, ax, ay, az, 4)) return true;
        for (int n = 0; n < faceBlocks.size(); n++) {
            CaveGeometry.Face f = volume.faces.get(n);
            if (Math.abs(f.x - ax) > 6 || Math.abs(f.y - ay) > 6 || Math.abs(f.z - az) > 6) continue;
            if (!w.blockExists(f.x, f.y, f.z) || w.getBlock(f.x, f.y, f.z) != faceBlocks.get(n)
                    || w.getBlockMetadata(f.x, f.y, f.z) != faceMeta.get(n)) return true;
        }
        return false;
    }

    private static void cacheIcons(World w) {
        for (int n = icons.size(); n < volume.faces.size(); n++) {
            CaveGeometry.Face f = volume.faces.get(n);
            Block b = w.blockExists(f.x, f.y, f.z) ? w.getBlock(f.x, f.y, f.z) : Blocks.stone;
            IIcon icon = b.getIcon(w, f.x, f.y, f.z, f.side);
            if (icon == null) icon = Blocks.stone.getIcon(f.side, 0);
            icons.add(icon);
            faceBlocks.add(b);
            faceMeta.add(w.blockExists(f.x, f.y, f.z) ? w.getBlockMetadata(f.x, f.y, f.z) : 0);
            faceOrder.add(n);
            orderDirty = true;
            if (b == Blocks.iron_ore) ironFaces.set(n);
        }
    }

    private static void sortFaces(final double x, final double y, final double z) {
        if (!orderDirty && Math.abs(x - sortX) + Math.abs(y - sortY) + Math.abs(z - sortZ) < 1.0) return;
        Collections.sort(faceOrder, new Comparator<Integer>() {
            public int compare(Integer ia, Integer ib) {
                CaveGeometry.Face a = volume.faces.get(ia), b = volume.faces.get(ib);
                double da = square(a.x + 0.5 - x) + square(a.y + 0.5 - y) + square(a.z + 0.5 - z);
                double db = square(b.x + 0.5 - x) + square(b.y + 0.5 - y) + square(b.z + 0.5 - z);
                return Double.compare(db, da);
            }
        });
        sortX = x; sortY = y; sortZ = z; orderDirty = false;
    }

    private static double square(double x) { return x * x; }

    private static void drawFaces() {
        if (volume == null || volume.faces.isEmpty()) return;
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.setBrightness(0xF000F0);
        for (int n : faceOrder) {
            CaveGeometry.Face f = volume.faces.get(n);
            IIcon icon = icons.get(n);
            if (icon == null) continue;
            float level = volume.maxLevel <= volume.minLevel ? 0.5f
                    : Math.max(0f, Math.min(1f, (float) (f.y - volume.minLevel) / (volume.maxLevel - volume.minLevel)));
            float r, g, b, alpha;
            if (ironFaces.get(n)) { r = g = b = alpha = 1f; } // actual exposed iron texture, never sealed ore
            else if (f.side == 1) { // floor: warm and fairly solid, so stairs read as separate levels
                r = 0.78f + 0.16f * level; g = 0.64f + 0.16f * level; b = 0.45f + 0.15f * level; alpha = 0.72f;
            } else if (f.side == 0) { // ceiling: cool, faint enough to see the lower passage
                r = 0.42f + 0.13f * level; g = 0.52f + 0.13f * level; b = 0.61f + 0.11f * level; alpha = 0.24f;
            } else { // walls: directional light, with a nearer shell peeled back
                float side = f.side == 2 || f.side == 4 ? 0.88f : 1f;
                r = (0.66f + 0.19f * level) * side;
                g = (0.72f + 0.17f * level) * side;
                b = (0.77f + 0.15f * level) * side;
                double near = square(f.x + 0.5 - sortX) + square(f.y + 0.5 - sortY) + square(f.z + 0.5 - sortZ);
                alpha = near < 18 * 18 ? 0.46f : 0.78f;
            }
            t.setColorRGBA_F(r, g, b, alpha);
            face(t, f, icon);
        }
        t.draw();
    }

    private static void face(Tessellator t, CaveGeometry.Face f, IIcon i) {
        double x = f.x, y = f.y, z = f.z;
        double u0 = i.getMinU(), u1 = i.getMaxU(), v0 = i.getMinV(), v1 = i.getMaxV();
        switch (f.side) {
        case 0: quad(t, x,y,z, x+1,y,z, x+1,y,z+1, x,y,z+1, u0,u1,v0,v1); break;
        case 1: quad(t, x,y+1,z, x,y+1,z+1, x+1,y+1,z+1, x+1,y+1,z, u0,u1,v0,v1); break;
        case 2: quad(t, x,y,z, x,y+1,z, x+1,y+1,z, x+1,y,z, u0,u1,v0,v1); break;
        case 3: quad(t, x+1,y,z+1, x+1,y+1,z+1, x,y+1,z+1, x,y,z+1, u0,u1,v0,v1); break;
        case 4: quad(t, x,y,z+1, x,y+1,z+1, x,y+1,z, x,y,z, u0,u1,v0,v1); break;
        case 5: quad(t, x+1,y,z, x+1,y+1,z, x+1,y+1,z+1, x+1,y,z+1, u0,u1,v0,v1); break;
        default: break;
        }
    }

    private static void quad(Tessellator t, double ax,double ay,double az, double bx,double by,double bz,
                             double cx,double cy,double cz, double dx,double dy,double dz,
                             double u0,double u1,double v0,double v1) {
        t.addVertexWithUV(ax,ay,az,u0,v0);
        t.addVertexWithUV(bx,by,bz,u0,v1);
        t.addVertexWithUV(cx,cy,cz,u1,v1);
        t.addVertexWithUV(dx,dy,dz,u1,v0);
    }

    private static void markers(Agent a, boolean selectedInside) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glLineWidth(3f);
        GL11.glBegin(GL11.GL_LINES);
        if (selectedInside) {
            GL11.glColor4f(0.32f, 1f, 0.64f, 1f);
            GL11.glVertex3d(a.posX, a.boundingBox.minY, a.posZ);
            GL11.glVertex3d(a.posX, a.boundingBox.minY + 2.3, a.posZ);
        }
        if (volume != null && volume.exit) {
            GL11.glColor4f(1f, 0.78f, 0.20f, 1f);
            GL11.glVertex3d(volume.exitX + 0.5, volume.exitY, volume.exitZ + 0.5);
            GL11.glVertex3d(volume.exitX + 0.5, volume.exitY + 3, volume.exitZ + 0.5);
        }
        GL11.glEnd();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }
}
