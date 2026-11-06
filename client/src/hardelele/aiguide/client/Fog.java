package hardelele.aiguide.client;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import org.lwjgl.opengl.GL11;

/**
 * The fog of war of the commander's view, as in a strategy game. Every 4x4-block column is unexplored (dark), explored
 * but not seen now (dimmed: its blocks as they are, but no living thing shown there) or seen. What sees: each agent
 * (20 blocks round, through walls: a radius, not a line of sight), the player (16) and the base (12, always). An agent
 * walking out reveals the map; what was seen stays explored (FogMap, kept with the base). Only the commander's view
 * has it, drawn as one shaded sheet over the ground (soft at the edges, a few thousand vertices a frame); a replay
 * makes the same fog from the agents' recorded places.
 */
public class Fog {
    static Fog instance;
    static boolean on = true;
    static final int AGENT = 20, PLAYER = 16, BASE = 12;
    final Minecraft mc = Minecraft.getMinecraft();
    final Set<Long> visible = new HashSet<Long>();

    static void start() {
        instance = new Fog();
        FMLCommonHandler.instance().bus().register(instance);
        MinecraftForge.EVENT_BUS.register(instance);
    }

    /** 0 unexplored, 1 explored, 2 seen now */
    int state(int cx, int cz) {
        long k = FogMap.key(cx, cz);
        return visible.contains(k) ? 2 : FogMap.explored.contains(k) ? 1 : 0;
    }

    int stateAt(double x, double z) { return state(FogMap.cell(x), FogMap.cell(z)); }

    boolean active() { return on && Commander.instance != null && Commander.instance.on; }

    void see(double x, double z, int r) {
        int c0x = FogMap.cell(x - r), c1x = FogMap.cell(x + r), c0z = FogMap.cell(z - r), c1z = FogMap.cell(z + r);
        for (int cx = c0x; cx <= c1x; cx++)
            for (int cz = c0z; cz <= c1z; cz++) {
                double dx = cx * FogMap.CELL + 2 - x, dz = cz * FogMap.CELL + 2 - z;
                if (dx * dx + dz * dz <= (r + 2) * (r + 2)) visible.add(FogMap.key(cx, cz));
            }
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent e) {   // who sees what, twenty times a second (the map is explored even when the commander is off)
        if (e.phase != TickEvent.Phase.END || mc.theWorld == null) return;
        visible.clear();
        for (Object o : mc.theWorld.loadedEntityList) if (o instanceof Agent && !((Agent) o).isDead) see(((Entity) o).posX, ((Entity) o).posZ, AGENT);
        if (mc.thePlayer != null && Replay.active == null) see(mc.thePlayer.posX, mc.thePlayer.posZ, PLAYER);
        Crew c = Crew.instance;
        if (c != null && c.base != null) see(c.base[0] + 0.5, c.base[2] + 0.5, BASE);
        if (Crew.site != null) see(Crew.site[0] + 2.5, Crew.site[2] + 2.5, BASE);
        FogMap.explored.addAll(visible);
    }

    /** a living thing in a cell not seen now is not shown (the crew and the player always are) */
    @SubscribeEvent
    public void hide(RenderLivingEvent.Pre e) {
        if (!active() || e.entity instanceof Agent || e.entity instanceof EntityPlayer) return;
        if (stateAt(e.entity.posX, e.entity.posZ) != 2) e.setCanceled(true);
    }

    static float shade(int s) { return s == 0 ? 0.88f : s == 1 ? 0.42f : 0f; }

    @SubscribeEvent
    public void render(RenderWorldLastEvent e) {
        if (!active() || mc.theWorld == null) return;
        Commander cm = Commander.instance;
        if (System.currentTimeMillis() - heightsAt > 1000) { heights.clear(); heightsAt = System.currentTimeMillis(); }   // the ground changes: heights read again each second
        int pcx = FogMap.cell(cm.sx), pcz = FogMap.cell(cm.sz), R = Math.max(16, (int) (cm.sdist * 1.6 / FogMap.CELL) + 8);
        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glTranslated(-RenderManager.renderPosX, -RenderManager.renderPosY, -RenderManager.renderPosZ);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDepthMask(false);
        GL11.glShadeModel(GL11.GL_SMOOTH);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        for (int cx = pcx - R; cx <= pcx + R; cx++)
            for (int cz = pcz - R; cz <= pcz + R; cz++) {
                int s = state(cx, cz);
                // the corners' shade: the mean of the four cells round each corner (soft edges)
                float a00 = corner(cx, cz), a10 = corner(cx + 1, cz), a11 = corner(cx + 1, cz + 1), a01 = corner(cx, cz + 1);
                if (s == 2 && a00 + a10 + a11 + a01 == 0) continue;
                int x0 = cx * FogMap.CELL, z0 = cz * FogMap.CELL;
                double y00 = cornerY(cx, cz), y10 = cornerY(cx + 1, cz), y11 = cornerY(cx + 1, cz + 1), y01 = cornerY(cx, cz + 1);
                fogVertex(t, x0, y00, z0, a00);
                fogVertex(t, x0, y01, z0 + FogMap.CELL, a01);
                fogVertex(t, x0 + FogMap.CELL, y11, z0 + FogMap.CELL, a11);
                fogVertex(t, x0 + FogMap.CELL, y10, z0, a10);
            }
        t.draw();
        GL11.glDepthMask(true);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glPopAttrib();
        GL11.glPopMatrix();
    }

    /** a corner of the sheet: the shade's colour goes from a dim grey (explored) to a deep blue-grey haze (unexplored) */
    static void fogVertex(Tessellator t, double x, double y, double z, float a) {
        float k = Math.min(1, a / 0.9f);   // 0 seen .. 1 unexplored
        t.setColorRGBA_F(0.16f - 0.08f * k, 0.18f - 0.08f * k, 0.22f - 0.06f * k, a);
        t.addVertex(x, y, z);
    }

    final java.util.Map<Long, Integer> heights = new java.util.HashMap<Long, Integer>();
    long heightsAt;

    /** the height of a cell (its highest column of the four sampled, the crowns counted), kept a second */
    int cellTop(int cx, int cz) {
        long k = FogMap.key(cx, cz);
        Integer h = heights.get(k);
        if (h == null) {
            int x0 = cx * FogMap.CELL, z0 = cz * FogMap.CELL, m = 0;
            for (int dx = 0; dx < FogMap.CELL; dx += 2) for (int dz = 0; dz < FogMap.CELL; dz += 2) m = Math.max(m, top(x0 + dx + 1, z0 + dz + 1));
            heights.put(k, h = m);
        }
        return h;
    }

    /** a corner's height: the highest of the four cells that meet there (the sheet is whole: no gaps between cells) */
    double cornerY(int cx, int cz) {
        return Math.max(Math.max(cellTop(cx - 1, cz - 1), cellTop(cx, cz - 1)), Math.max(cellTop(cx - 1, cz), cellTop(cx, cz))) + 0.25;
    }

    /** the shade at a cell corner: the four cells that meet there */
    float corner(int cx, int cz) {
        return (shade(state(cx - 1, cz - 1)) + shade(state(cx, cz - 1)) + shade(state(cx - 1, cz)) + shade(state(cx, cz))) / 4;
    }

    /** the top of a column (the crowns count: the fog lies over everything there) */
    int top(int x, int z) {
        return mc.theWorld.getHeightValue(x, z);
    }

    /** may the commander's clicks reach this block: only where the map is explored (else only "go there") */
    static boolean known(int x, int z) {
        return !on || FogMap.seen(x, z);
    }
}
