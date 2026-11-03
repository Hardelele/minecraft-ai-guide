package hardelele.aiguide.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.util.IIcon;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import org.lwjgl.opengl.GL11;

/**
 * A plan of the guide, drawn into the world: blocks to place as see-through copies of themselves, blocks
 * to dig as red see-through boxes. A ghost goes away when the world matches it.
 */
public class Ghosts {
    static final class Ghost {
        int x, y, z, meta;
        Block block;   // null: to dig
        boolean item;   // placed by an item that is not a block (a pipe): drawn as glass, done when anything is there
    }

    static final List<Ghost> all = new ArrayList<Ghost>();

    static synchronized void set(JsonObject m) {
        all.clear();
        if (m.has("place"))
            for (JsonElement e : m.getAsJsonArray("place")) {
                JsonArray a = e.getAsJsonArray();
                Ghost g = at(a);
                Object o = Item.itemRegistry.getObject(a.get(3).getAsString());
                Block b = o instanceof Item ? Block.getBlockFromItem((Item) o) : null;
                g.item = b == null || b == Blocks.air;
                g.block = g.item ? Blocks.glass : b;
                g.meta = a.size() > 4 ? a.get(4).getAsInt() : 0;
                all.add(g);
            }
        if (m.has("dig"))
            for (JsonElement e : m.getAsJsonArray("dig")) all.add(at(e.getAsJsonArray()));
    }

    static Ghost at(JsonArray a) {
        Ghost g = new Ghost();
        g.x = a.get(0).getAsInt();
        g.y = a.get(1).getAsInt();
        g.z = a.get(2).getAsInt();
        return g;
    }

    static synchronized void clear() {
        all.clear();
    }

    @SubscribeEvent
    public void render(RenderWorldLastEvent e) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) return;
        synchronized (Ghosts.class) {
            if (all.isEmpty()) return;
            World w = mc.theWorld;
            for (Iterator<Ghost> it = all.iterator(); it.hasNext(); ) {   // done ones go
                Ghost g = it.next();
                boolean air = w.isAirBlock(g.x, g.y, g.z);
                if (g.block == null ? air : g.item ? !air : w.getBlock(g.x, g.y, g.z) == g.block) it.remove();
            }
            GL11.glPushMatrix();
            GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glTranslated(-RenderManager.renderPosX, -RenderManager.renderPosY, -RenderManager.renderPosZ);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDepthMask(false);
            mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            Tessellator t = Tessellator.instance;
            t.startDrawingQuads();
            for (Ghost g : all) if (g.block != null) cube(t, g, 0.45f);
            t.draw();
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            t.startDrawingQuads();
            for (Ghost g : all) if (g.block == null) box(t, g.x - 0.005, g.y - 0.005, g.z - 0.005, g.x + 1.005, g.y + 1.005, g.z + 1.005, 1f, 0.2f, 0.2f, 0.3f);
            t.draw();
            GL11.glLineWidth(2f);
            t.startDrawing(GL11.GL_LINES);
            for (Ghost g : all) {
                float r = g.block == null ? 1f : 0.3f, gr = g.block == null ? 0.3f : 0.8f, b = g.block == null ? 0.3f : 1f;
                lines(t, g.x, g.y, g.z, r, gr, b);
            }
            t.draw();
            GL11.glPopAttrib();
            GL11.glPopMatrix();
        }
    }

    /** the block's own faces, see-through */
    static void cube(Tessellator t, Ghost g, float alpha) {
        t.setColorRGBA_F(0.85f, 0.95f, 1f, alpha);
        double x0 = g.x + 0.02, y0 = g.y + 0.02, z0 = g.z + 0.02, x1 = g.x + 0.98, y1 = g.y + 0.98, z1 = g.z + 0.98;
        for (int s = 0; s < 6; s++) {
            IIcon i;
            try {
                i = g.block.getIcon(s, g.meta);
            } catch (Exception e) {
                i = null;
            }
            if (i == null) i = Blocks.glass.getIcon(s, 0);
            double u0 = i.getMinU(), u1 = i.getMaxU(), v0 = i.getMinV(), v1 = i.getMaxV();
            switch (s) {
                case 0: quad(t, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, u0, u1, v0, v1); break;
                case 1: quad(t, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, u0, u1, v0, v1); break;
                case 2: quad(t, x1, y1, z0, x0, y1, z0, x0, y0, z0, x1, y0, z0, u0, u1, v0, v1); break;
                case 3: quad(t, x0, y1, z1, x1, y1, z1, x1, y0, z1, x0, y0, z1, u0, u1, v0, v1); break;
                case 4: quad(t, x0, y1, z0, x0, y1, z1, x0, y0, z1, x0, y0, z0, u0, u1, v0, v1); break;
                default: quad(t, x1, y1, z1, x1, y1, z0, x1, y0, z0, x1, y0, z1, u0, u1, v0, v1); break;
            }
        }
    }

    static void quad(Tessellator t, double ax, double ay, double az, double bx, double by, double bz, double cx, double cy, double cz,
                     double dx, double dy, double dz, double u0, double u1, double v0, double v1) {
        t.addVertexWithUV(ax, ay, az, u0, v0);
        t.addVertexWithUV(bx, by, bz, u1, v0);
        t.addVertexWithUV(cx, cy, cz, u1, v1);
        t.addVertexWithUV(dx, dy, dz, u0, v1);
    }

    static void box(Tessellator t, double x0, double y0, double z0, double x1, double y1, double z1, float r, float g, float b, float a) {
        t.setColorRGBA_F(r, g, b, a);
        double[][] f = {
            { x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1 }, { x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0 },
            { x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0 }, { x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1 },
            { x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0 }, { x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1 } };
        for (double[] q : f)
            for (int i = 0; i < 12; i += 3) t.addVertex(q[i], q[i + 1], q[i + 2]);
    }

    static void lines(Tessellator t, int x, int y, int z, float r, float g, float b) {
        t.setColorRGBA_F(r, g, b, 0.9f);
        double[][] c = { { 0, 0, 0 }, { 1, 0, 0 }, { 1, 0, 1 }, { 0, 0, 1 }, { 0, 1, 0 }, { 1, 1, 0 }, { 1, 1, 1 }, { 0, 1, 1 } };
        int[][] e = { { 0, 1 }, { 1, 2 }, { 2, 3 }, { 3, 0 }, { 4, 5 }, { 5, 6 }, { 6, 7 }, { 7, 4 }, { 0, 4 }, { 1, 5 }, { 2, 6 }, { 3, 7 } };
        for (int[] l : e) {
            t.addVertex(x + c[l[0]][0], y + c[l[0]][1], z + c[l[0]][2]);
            t.addVertex(x + c[l[1]][0], y + c[l[1]][1], z + c[l[1]][2]);
        }
    }
}
