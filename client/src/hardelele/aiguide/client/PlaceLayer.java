package hardelele.aiguide.client;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.BlockBush;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockFence;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockLadder;
import net.minecraft.block.BlockLeavesBase;
import net.minecraft.block.BlockLog;
import net.minecraft.block.BlockReed;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.BlockTrapDoor;
import net.minecraft.block.BlockVine;
import net.minecraft.block.BlockWall;
import net.minecraft.block.material.Material;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.init.Blocks;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import org.lwjgl.opengl.GL11;

/**
 * The commander's layer of recognised places (L or the "Places" pill): each place the model knows (PlaceSource) as a
 * see-through box coloured by its class, outlined, with its class and confidence over it. A click on a place opens the
 * rating menu (FeedbackUi). Also the input cube read from the world when the source has none (places/CONTRACT.md's
 * 16 channels, by Material and the block's kind: near enough, the contract allows noise).
 */
final class PlaceLayer {
    static boolean on;
    static PlaceView hovered, chosen;

    /** a colour per class (the contract's order) */
    static final int[] COLORS = { 0x9E9E9E, 0x6EC6FF, 0xFFD54F, 0x81C784, 0xBA68C8, 0xFF8A65, 0xF06292, 0xBCAAA4 };

    static void start() { MinecraftForge.EVENT_BUS.register(new PlaceLayer()); }

    static boolean shown() { return on && Commander.instance != null && Commander.instance.on; }

    static int color(int cls) { return cls >= 0 && cls < COLORS.length ? COLORS[cls] : 0xFFFFFF; }

    @SubscribeEvent
    public void world(RenderWorldLastEvent e) {
        if (!shown()) return;
        List<PlaceView> ps = PlaceSource.places();
        if (ps.isEmpty()) return;
        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_LINE_BIT);
        GL11.glTranslated(-RenderManager.renderPosX, -RenderManager.renderPosY, -RenderManager.renderPosZ);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDepthMask(false);
        Tessellator t = Tessellator.instance;
        for (PlaceView p : ps) {
            int[] b = p.box();
            if (b == null || b.length < 6) continue;
            int c = color(p.cls());
            boolean hot = same(p, hovered) || same(p, chosen);
            float r = (c >> 16 & 255) / 255f, g = (c >> 8 & 255) / 255f, bl = (c & 255) / 255f;
            t.startDrawingQuads();
            t.setColorRGBA_F(r, g, bl, hot ? 0.26f : 0.12f);
            faces(t, b[0] + 0.03, b[1] + 0.03, b[2] + 0.03, b[3] + 0.97, b[4] + 0.97, b[5] + 0.97);
            t.draw();
        }
        GL11.glDisable(GL11.GL_DEPTH_TEST);   // the outlines through the rock: a place underground is still seen
        for (PlaceView p : ps) {
            int[] b = p.box();
            if (b == null || b.length < 6) continue;
            int c = color(p.cls());
            boolean hot = same(p, hovered) || same(p, chosen);
            GL11.glLineWidth(hot ? 3f : 1.5f);
            Commander.outline(t, b[0], b[1], b[2], b[3] + 1, b[4] + 1, b[5] + 1, (c >> 16 & 255) / 255f, (c >> 8 & 255) / 255f, (c & 255) / 255f, hot ? 1f : 0.7f);
        }
        GL11.glDepthMask(true);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glPopAttrib();
        GL11.glPopMatrix();
    }

    static boolean same(PlaceView a, PlaceView b) { return a != null && b != null && String.valueOf(a.id()).equals(String.valueOf(b.id())); }

    static void faces(Tessellator t, double x0, double y0, double z0, double x1, double y1, double z1) {
        t.addVertex(x0, y1, z0); t.addVertex(x0, y1, z1); t.addVertex(x1, y1, z1); t.addVertex(x1, y1, z0);
        t.addVertex(x0, y0, z0); t.addVertex(x1, y0, z0); t.addVertex(x1, y0, z1); t.addVertex(x0, y0, z1);
        t.addVertex(x0, y0, z0); t.addVertex(x0, y1, z0); t.addVertex(x1, y1, z0); t.addVertex(x1, y0, z0);
        t.addVertex(x0, y0, z1); t.addVertex(x1, y0, z1); t.addVertex(x1, y1, z1); t.addVertex(x0, y1, z1);
        t.addVertex(x0, y0, z0); t.addVertex(x0, y0, z1); t.addVertex(x0, y1, z1); t.addVertex(x0, y1, z0);
        t.addVertex(x1, y0, z0); t.addVertex(x1, y1, z0); t.addVertex(x1, y1, z1); t.addVertex(x1, y0, z1);
    }

    /** the place nearest along the ray (its box entered first), or null */
    static PlaceView hit(Vec3[] ray) {
        if (ray == null || !shown()) return null;
        PlaceView best = null;
        double bd = Double.MAX_VALUE;
        for (PlaceView p : PlaceSource.places()) {
            int[] b = p.box();
            if (b == null || b.length < 6) continue;
            AxisAlignedBB bb = AxisAlignedBB.getBoundingBox(b[0], b[1], b[2], b[3] + 1, b[4] + 1, b[5] + 1);
            double d;
            if (bb.isVecInside(ray[0])) d = 0;
            else {
                MovingObjectPosition m = bb.calculateIntercept(ray[0], ray[1]);
                if (m == null) continue;
                d = m.hitVec.distanceTo(ray[0]);
            }
            // a place inside a bigger one: the smaller wins when both are entered at once
            d += 1e-6 * (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1);
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    /** each place's class and confidence over its box (2D, the commander's screen) */
    static void labels(Commander c, FontRenderer fr, int scale, int displayHeight) {
        if (!shown()) return;
        for (PlaceView p : PlaceSource.places()) {
            int[] b = p.box();
            if (b == null || b.length < 6) continue;
            float[] s = c.toScreen((b[0] + b[3] + 1) / 2.0, b[4] + 1.2, (b[2] + b[5] + 1) / 2.0);
            if (s == null) continue;
            String text = FeedbackUi.className(p.cls()) + " " + Math.round(p.confidence() * 100) + "%";
            int w = fr.getStringWidth(text), x = (int) (s[0] / scale) - w / 2, y = (int) ((displayHeight - s[1]) / scale);
            int col = color(p.cls());
            boolean hot = same(p, hovered) || same(p, chosen);
            Ui.round(x - 4, y - 2, x + w + 4, y + 10, 4, hot ? 0xE0101820 : 0xA0101820);
            Ui.round(x - 4, y + 9, x + w + 4, y + 10, 0, 0xFF000000 | col);
            fr.drawStringWithShadow(text, x, y, hot ? 0xFFFFFF : 0xFF000000 | col);
        }
    }

    // ---- the input cube, read from the world ------------------------------------------------------------

    /** where the cube of a place starts: the source's, else the box's centre less 16 */
    static int[] origin(PlaceView p) {
        int[] c = p.cube();
        if (c != null && c.length >= 3) return new int[] { c[0], c[1], c[2] };
        int[] b = p.box();
        return new int[] { Math.floorDiv(b[0] + b[3], 2) - 16, Math.floorDiv(b[1] + b[4], 2) - 16, Math.floorDiv(b[2] + b[5], 2) - 16 };
    }

    /** the 32^3 cube at this origin as the model's input: a channel per cell, [x][y][z] */
    static byte[] read(World w, int[] o) {
        byte[] out = new byte[Labels.CELLS];
        for (int x = 0; x < Labels.SIDE; x++)
            for (int y = 0; y < Labels.SIDE; y++)
                for (int z = 0; z < Labels.SIDE; z++) out[Labels.index(x, y, z)] = (byte) channel(w, o[0] + x, o[1] + y, o[2] + z);
        return out;
    }

    /** places/CONTRACT.md's channel of one block */
    static int channel(World w, int x, int y, int z) {
        if (y < 0) return 15;
        if (y > 255) return 0;
        if (!w.getChunkProvider().chunkExists(x >> 4, z >> 4)) return 1;
        if (!FogMap.explored.isEmpty() && !FogMap.seen(x, z)) return 1;   // the crew never saw this column
        Block b = w.getBlock(x, y, z);
        Material m = b.getMaterial();
        if (m == Material.air) return 0;
        if (m == Material.water) return 2;
        if (m == Material.lava || m == Material.fire) return 3;
        if (b instanceof BlockDoor || b instanceof BlockTrapDoor || b instanceof BlockFenceGate) return 10;
        if (b instanceof BlockFence || b instanceof BlockWall || b == Blocks.iron_bars) return 11;
        if (b instanceof BlockLadder || b instanceof BlockVine) return 12;
        if (b.hasTileEntity()) return 14;   // chests, furnaces, machines (a torch has none)
        if (b.getLightValue() > 0 && !b.isOpaqueCube()) return 13;
        Object name = Block.blockRegistry.getNameForObject(b);
        String n = name == null ? "" : name.toString();
        String low = n.toLowerCase(java.util.Locale.ROOT);
        if (low.endsWith("_ore") || low.contains(":ore") || low.contains("ore_")) return 6;
        if (b instanceof BlockLog || b instanceof BlockLeavesBase || m == Material.leaves) return 7;
        if (m == Material.plants || m == Material.vine || m == Material.cactus || m == Material.gourd || b instanceof BlockBush || b instanceof BlockReed) return 8;
        if (b.getCollisionBoundingBoxFromPool(w, x, y, z) == null) return 0;   // a carpet, a rail, a sign, a web...
        if (b == Blocks.cobblestone || b == Blocks.mossy_cobblestone) return 9;
        if (m == Material.ground || m == Material.grass || m == Material.sand || m == Material.clay || m == Material.craftedSnow || m == Material.snow
                || m == Material.ice || m == Material.packedIce || b == Blocks.netherrack || b == Blocks.soul_sand || b == Blocks.end_stone
                || b == Blocks.farmland || b == Blocks.mycelium) return 4;
        if (b == Blocks.stone || b == Blocks.sandstone || b == Blocks.obsidian || b == Blocks.bedrock || b == Blocks.hardened_clay || b == Blocks.stained_hardened_clay) return 5;
        if (m == Material.rock && !n.startsWith("minecraft:")) return 5;   // a mod's natural stone, most likely
        if (m == Material.rock || m == Material.wood || m == Material.glass || m == Material.cloth || m == Material.iron || m == Material.redstoneLight
                || b instanceof BlockSlab || b instanceof BlockStairs || b instanceof BlockBed || b == Blocks.glowstone || b == Blocks.bookshelf) return 9;
        return 15;
    }
}
