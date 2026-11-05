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

    static float shade(int s) { return s == 0 ? 0.94f : s == 1 ? 0.5f : 0f; }

    @SubscribeEvent
    public void render(RenderWorldLastEvent e) {
        if (!active() || mc.theWorld == null) return;
        Commander cm = Commander.instance;
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
                double y = top(x0 + 2, z0 + 2) + 0.12;
                float g = s == 0 ? 0.02f : 0.12f;
                t.setColorRGBA_F(g, g, g + 0.02f, a00); t.addVertex(x0, y, z0);
                t.setColorRGBA_F(g, g, g + 0.02f, a01); t.addVertex(x0, y, z0 + FogMap.CELL);
                t.setColorRGBA_F(g, g, g + 0.02f, a11); t.addVertex(x0 + FogMap.CELL, y, z0 + FogMap.CELL);
                t.setColorRGBA_F(g, g, g + 0.02f, a10); t.addVertex(x0 + FogMap.CELL, y, z0);
            }
        t.draw();
        GL11.glDepthMask(true);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glPopAttrib();
        GL11.glPopMatrix();
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
