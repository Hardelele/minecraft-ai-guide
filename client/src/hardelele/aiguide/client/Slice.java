package hardelele.aiguide.client;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import java.nio.DoubleBuffer;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.entity.Entity;
import net.minecraft.util.IIcon;
import net.minecraft.util.MathHelper;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/** A horizontal cut through the commander's world. The world itself is never changed. */
public final class Slice {
    static final Slice INSTANCE = new Slice();
    static boolean on, follow = true;
    static int level = 64;
    private final DoubleBuffer plane = BufferUtils.createDoubleBuffer(4);
    private static float savedGamma;
    private static boolean brightened;
    private static String savedShaderPack;

    static void start() { MinecraftForge.EVENT_BUS.register(INSTANCE); }

    static boolean active() {
        return on && Commander.instance != null && Commander.instance.on && Minecraft.getMinecraft().theWorld != null;
    }

    /** Only the observer uses this test; it never changes the agents' ore knowledge. */
    static boolean belowSurface(Agent a) {
        Minecraft mc = Minecraft.getMinecraft();
        if (a == null || mc.theWorld == null) return false;
        int surface = mc.theWorld.getHeightValue(MathHelper.floor_double(a.posX), MathHelper.floor_double(a.posZ));
        return a.boundingBox.maxY + 3 < surface;
    }

    static void adjust(int n) { follow = false; level = Math.max(1, Math.min(255, level + n)); }

    static void enter() {
        on = true;
        updateFollow();
        // OptiFine shader programs can bypass fixed-function clip planes. Temporarily use vanilla rendering.
        try {
            Class<?> shaders = Class.forName("shadersmod.client.Shaders");
            String pack = (String) shaders.getMethod("getShaderPackName").invoke(null);
            if (pack != null && !pack.isEmpty() && !pack.equals("OFF")) {
                shaders.getMethod("setShaderPack", String.class).invoke(null, "OFF");
                savedShaderPack = pack;
                Director.log("slice: shaders off temporarily (" + pack + ")");
            }
        } catch (ClassNotFoundException ex) {
            // OptiFine is optional.
        } catch (Exception ex) { Director.log("slice: shader switch failed: " + ex); }
    }

    static void exit() {
        on = false;
        restore();
        if (savedShaderPack == null) return;
        try {
            Class.forName("shadersmod.client.Shaders").getMethod("setShaderPack", String.class).invoke(null, savedShaderPack);
            Director.log("slice: shaders restored (" + savedShaderPack + ")");
        } catch (Exception ex) { Director.log("slice: shader restore failed: " + ex); }
        savedShaderPack = null;
    }

    static void updateFollow() {
        if (!active() || !follow) return;
        Commander c = Commander.instance;
        for (int id : c.selected) {
            Agent a = Cam.serverAgent(id);
            if (a == null || a.isDead) continue;
            // Keep the agent's entire head visible. A block beginning at this level is removed.
            level = Math.max(1, Math.min(255, (int) Math.ceil(a.boundingBox.maxY + 0.05)));
            return;
        }
    }

    static boolean hasTarget() {
        if (Commander.instance == null) return false;
        for (int id : Commander.instance.selected) if (Cam.serverAgent(id) != null) return true;
        return false;
    }

    static void disable() { GL11.glDisable(GL11.GL_CLIP_PLANE0); }

    static void restore() {
        if (!brightened) return;
        Minecraft.getMinecraft().gameSettings.gammaSetting = savedGamma;
        brightened = false;
    }

    /** Called after the camera is set and before terrain, entities and particles are rendered. */
    @SubscribeEvent
    public void fog(EntityViewRenderEvent.RenderFogEvent e) {
        if (!active()) { disable(); restore(); return; }
        updateFollow();
        Minecraft mc = Minecraft.getMinecraft();
        if (!brightened) { savedGamma = mc.gameSettings.gammaSetting; brightened = true; }
        mc.gameSettings.gammaSetting = 8f;
        Entity eye = mc.renderViewEntity;
        if (eye == null) return;
        double y = eye.lastTickPosY + (eye.posY - eye.lastTickPosY) * e.renderPartialTicks;
        // World vertices are submitted relative to the camera's render origin. Keep y <= level.
        plane.clear();
        plane.put(0).put(-1).put(0).put(level - y).flip();
        GL11.glClipPlane(GL11.GL_CLIP_PLANE0, plane);
        GL11.glEnable(GL11.GL_CLIP_PLANE0);
    }

    /** Expose a textured top face where the chunk mesh omitted an internal block face. */
    @SubscribeEvent
    public void end(RenderWorldLastEvent e) {
        try { if (active()) cap(); }
        finally { disable(); }
    }

    private void cap() {
        Minecraft mc = Minecraft.getMinecraft();
        Commander c = Commander.instance;
        int r = Math.max(72, Math.min(128, (int) (c.sdist * 3.2)));
        int x0 = (int) Math.floor(c.sx) - r, x1 = (int) Math.floor(c.sx) + r;
        int z0 = (int) Math.floor(c.sz) - r, z1 = (int) Math.floor(c.sz) + r;
        int by = level - 1;
        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glTranslated(-RenderManager.renderPosX, -RenderManager.renderPosY, -RenderManager.renderPosZ);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.setBrightness(0xF000F0);  // the cut face is a readable map of the layer
        t.setColorOpaque_F(0.76f, 0.78f, 0.82f);
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            if (!mc.theWorld.blockExists(x, by, z)) continue;
            Block b = mc.theWorld.getBlock(x, by, z);
            if (!b.isOpaqueCube()) continue;
            IIcon icon = b.getIcon(1, mc.theWorld.getBlockMetadata(x, by, z));
            if (icon == null) continue;
            double y = level - 0.002;
            t.addVertexWithUV(x, y, z, icon.getMinU(), icon.getMinV());
            t.addVertexWithUV(x, y, z + 1, icon.getMinU(), icon.getMaxV());
            t.addVertexWithUV(x + 1, y, z + 1, icon.getMaxU(), icon.getMaxV());
            t.addVertexWithUV(x + 1, y, z, icon.getMaxU(), icon.getMinV());
        }
        t.draw();
        GL11.glPopAttrib();
        GL11.glPopMatrix();
    }
}
