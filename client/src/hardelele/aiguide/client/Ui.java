package hardelele.aiguide.client;

import net.minecraft.client.renderer.Tessellator;
import org.lwjgl.opengl.GL11;

/** the commander's flat look: rounded, see-through panels drawn with GL (no game buttons) */
class Ui {
    /** a filled rectangle with rounded corners, the colour as ARGB */
    static void round(int x0, int y0, int x1, int y1, int r, int argb) {
        if (x1 <= x0 || y1 <= y0) return;
        r = Math.max(0, Math.min(r, Math.min((x1 - x0) / 2, (y1 - y0) / 2)));
        float a = (argb >>> 24) / 255f, cr = (argb >> 16 & 255) / 255f, cg = (argb >> 8 & 255) / 255f, cb = (argb & 255) / 255f;
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        Tessellator t = Tessellator.instance;
        t.startDrawing(GL11.GL_TRIANGLE_FAN);
        t.setColorRGBA_F(cr, cg, cb, a);
        t.addVertex((x0 + x1) / 2.0, (y0 + y1) / 2.0, 0);
        int seg = 6;
        double[][] c = { { x1 - r, y0 + r, -90 }, { x1 - r, y1 - r, 0 }, { x0 + r, y1 - r, 90 }, { x0 + r, y0 + r, 180 } };   // corners, clockwise from the top right
        for (double[] k : c)
            for (int i = 0; i <= seg; i++) {
                double ang = Math.toRadians(k[2] + 90.0 * i / seg);
                t.addVertex(k[0] + Math.cos(ang) * r, k[1] + Math.sin(ang) * r, 0);
            }
        t.addVertex(x1 - r + Math.cos(Math.toRadians(-90)) * r, y0 + r + Math.sin(Math.toRadians(-90)) * r, 0);
        t.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }
}
