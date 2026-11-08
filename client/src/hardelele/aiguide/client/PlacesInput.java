package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.WorldServer;
import net.minecraftforge.oredict.OreDictionary;

/**
 * The places net's input for one cube, from what the crew saw, never from blocks nobody saw: each cell's channel
 * (PlacesClasses), unseen where nothing tells. What tells, in this order (the later wins):
 * - the observation memory (Exploration): the blocks remembered, the walkable cells (feet and head free: air);
 * - what the agents' senses met lately (Body.seen);
 * - sight from the points the crew observed from (Exploration's trail, its feet; the eyes 1.6 above): rays as the
 *   training's visibility (places/vis.py: radius 20, through air, water, plants, ladders and vines, lights and fences;
 *   the first other cell is the last seen). The rays read the world as it is now, and only along clear lines from where
 *   an agent stood.
 * The rays run on the server thread a budget of block reads a tick (step), so a cube takes some ticks.
 */
final class PlacesInput {
    static final double RADIUS = 20, EYE = 1.6;
    static final int EYES = 24, DIRS = 4000, S = PlacesNet.SIDE;
    static final float[][] SPHERE = sphere(DIRS);
    static final Map<Block, Integer> channels = new IdentityHashMap<Block, Integer>();   // the server thread only

    final int x0, y0, z0;
    final byte[] cls = new byte[S * S * S];
    final List<double[]> eyes = new ArrayList<double[]>();
    int eye, ray, reads;
    long chunk = Long.MIN_VALUE;
    boolean chunkLoaded;

    /** what the rays read: whether a chunk is there, a cell's channel */
    interface View {
        boolean loaded(int chunkX, int chunkZ);
        int channel(int x, int y, int z);
    }

    static View view(final WorldServer w) {
        return new View() {
            public boolean loaded(int chunkX, int chunkZ) { return w.getChunkProvider().chunkExists(chunkX, chunkZ); }
            public int channel(int x, int y, int z) { return PlacesInput.channel(w.getBlock(x, y, z)); }
        };
    }

    /** a cube of nothing seen yet (below the world other_solid, above it air), sight from these eyes to come */
    PlacesInput(int x0, int y0, int z0, List<double[]> from) {
        this.x0 = x0;
        this.y0 = y0;
        this.z0 = z0;
        for (int x = 0; x < S; x++)
            for (int y = 0; y < S; y++)
                for (int z = 0; z < S; z++) {
                    int wy = y0 + y;
                    cls[index(x, y, z)] = (byte) (wy < 0 ? PlacesClasses.OTHER : wy > 255 ? PlacesClasses.AIR : PlacesClasses.UNSEEN);
                }
        final double cx = x0 + S / 2.0, cy = y0 + S / 2.0, cz = z0 + S / 2.0;
        eyes.addAll(from);
        Collections.sort(eyes, new Comparator<double[]>() {
            public int compare(double[] a, double[] b) { return Double.compare(d2(a, cx, cy, cz), d2(b, cx, cy, cz)); }
        });
        while (eyes.size() > EYES) eyes.remove(eyes.size() - 1);
    }

    /** the cube's input as the crew's memory has it; its rays still to run (step) */
    static PlacesInput gather(WorldServer w, int x0, int y0, int z0) {
        List<int[]> seenAt = new ArrayList<int[]>(), ground = new ArrayList<int[]>(), trail = new ArrayList<int[]>();
        List<String> seenName = new ArrayList<String>();
        Exploration.recall(w, x0, y0, z0, S, (int) Math.ceil(RADIUS), seenAt, seenName, ground, trail);
        List<double[]> from = new ArrayList<double[]>();
        java.util.Set<Long> once = new java.util.HashSet<Long>();
        for (int[] p : trail) if (once.add(Crew.key(p[0], p[1], p[2]))) from.add(new double[] { p[0] + 0.5, p[1] + EYE, p[2] + 0.5 });
        PlacesInput in = new PlacesInput(x0, y0, z0, from);
        for (int i = 0; i < seenAt.size(); i++) {
            int[] p = seenAt.get(i);
            String name = seenName.get(i);
            Block b = Block.getBlockFromName(name);   // a name the registry lacks gives air: then by the name alone
            in.set(p[0], p[1], p[2], b != null && (b.getMaterial() != Material.air || "minecraft:air".equals(name)) ? channel(b) : PlacesClasses.byName(name));
        }
        for (int[] p : ground)
            for (int dy = 0; dy < 2; dy++) {
                int i = in.inside(p[0], p[1] + dy, p[2]);
                if (i >= 0 && in.cls[i] == PlacesClasses.UNSEEN) in.cls[i] = PlacesClasses.AIR;
            }
        Crew c = Crew.instance;
        if (c != null)
            for (Agent a : c.agents) {
                if (a.worldObj != w) continue;
                for (int[] v : a.body.seen.values()) {
                    Block b = Block.getBlockById(v[3]);
                    if (b != null) in.set(v[0], v[1], v[2], channel(b));
                }
            }
        return in;
    }

    static double d2(double[] p, double x, double y, double z) { return (p[0] - x) * (p[0] - x) + (p[1] - y) * (p[1] - y) + (p[2] - z) * (p[2] - z); }

    static int index(int x, int y, int z) { return (x * S + y) * S + z; }

    /** the cell's index if the world cell is in the cube, else -1 */
    int inside(int wx, int wy, int wz) {
        int x = wx - x0, y = wy - y0, z = wz - z0;
        return x < 0 || y < 0 || z < 0 || x >= S || y >= S || z >= S ? -1 : index(x, y, z);
    }

    void set(int wx, int wy, int wz, int ch) {
        int i = inside(wx, wy, wz);
        if (i >= 0 && wy >= 0 && wy <= 255) cls[i] = (byte) ch;
    }

    /** the rays of a few more eyes, up to about budget block reads; true when all are done */
    boolean step(View w, int budget) {
        int until = reads + budget;
        chunk = Long.MIN_VALUE;   // a chunk may have gone since the last tick (a read there would load it)
        while (eye < eyes.size()) {
            double[] e = eyes.get(eye);
            while (ray < DIRS) {
                float[] d = SPHERE[ray++];
                cast(w, e[0], e[1], e[2], d[0], d[1], d[2]);
                if (reads >= until) return false;
            }
            ray = 0;
            eye++;
        }
        return true;
    }

    /** one ray through the cells (a 3D DDA), each cell seen until the first one sight does not go through */
    void cast(View w, double px, double py, double pz, double dx, double dy, double dz) {
        int x = (int) Math.floor(px), y = (int) Math.floor(py), z = (int) Math.floor(pz);
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double tdx = dx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx), tdy = dy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy), tdz = dz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
        double tx = dx == 0 ? Double.MAX_VALUE : (dx > 0 ? x + 1 - px : px - x) * tdx;
        double ty = dy == 0 ? Double.MAX_VALUE : (dy > 0 ? y + 1 - py : py - y) * tdy;
        double tz = dz == 0 ? Double.MAX_VALUE : (dz > 0 ? z + 1 - pz : pz - z) * tdz;
        double t = 0;
        while (t <= RADIUS) {
            if (y < 0 || y > 255) return;
            long k = ((long) (x >> 4) << 32) ^ ((z >> 4) & 0xffffffffL);
            if (k != chunk) { chunk = k; chunkLoaded = w.loaded(x >> 4, z >> 4); }
            if (!chunkLoaded) return;
            reads++;
            int ch = w.channel(x, y, z);
            int i = inside(x, y, z);
            if (i >= 0) cls[i] = (byte) ch;
            if (!PlacesClasses.SEE_THROUGH[ch]) return;
            if (tx < ty && tx < tz) { t = tx; tx += tdx; x += sx; }
            else if (ty < tz) { t = ty; ty += tdy; y += sy; }
            else { t = tz; tz += tdz; z += sz; }
        }
    }

    /** how many cells of the cube something saw */
    int seen() {
        int n = 0;
        for (byte c : cls) if (c != PlacesClasses.UNSEEN) n++;
        return n;
    }

    /** a block's channel: its name, and for a modded block the name rules do not know, its material and ore names */
    static int channel(Block b) {
        Integer c = channels.get(b);
        if (c == null) {
            String name = Block.blockRegistry.getNameForObject(b);
            int v;
            try {
                Material m = b.getMaterial();
                v = PlacesClasses.channel(name, m.isLiquid(), m == Material.lava, m.blocksMovement(), ore(b));
            } catch (RuntimeException e) {
                v = PlacesClasses.byName(name);
            }
            channels.put(b, c = v);
        }
        return c;
    }

    static boolean ore(Block b) {
        Item i = Item.getItemFromBlock(b);
        if (i == null) return false;
        try {
            for (int id : OreDictionary.getOreIDs(new ItemStack(i))) if (OreDictionary.getOreName(id).startsWith("ore")) return true;
        } catch (RuntimeException e) {
            // a broken stack
        }
        return false;
    }

    /** n directions spread over the sphere (as the training's: a Fibonacci sphere) */
    static float[][] sphere(int n) {
        float[][] d = new float[n][];
        for (int k = 0; k < n; k++) {
            double i = k + 0.5, phi = Math.acos(1 - 2 * i / n), th = Math.PI * (1 + Math.sqrt(5)) * i;
            d[k] = new float[] { (float) (Math.cos(th) * Math.sin(phi)), (float) Math.cos(phi), (float) (Math.sin(th) * Math.sin(phi)) };
        }
        return d;
    }
}
