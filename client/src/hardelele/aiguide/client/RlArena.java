package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/**
 * The learning environment's arena: a piece of the world round a centre with trees, open stone and ore, its chunks
 * copied in memory once (blocks, light, height maps, tile entities, the farm animals) and put back at each episode's
 * reset without a restart: every entity but the players taken away, each chunk's sections and tile entities replaced
 * by the copy's, the animals made again from theirs. The world is never saved meanwhile (WorldServer's
 * disableLevelSaving), so the region files stay as generated. The centre: -Daiguide.rl.at=x,z, else the best place
 * near the spawn by what the ground there has (a setup's choice, not anything an agent is told).
 */
final class RlArena {
    final int x, y, z, radius;
    final List<Snap> chunks = new ArrayList<Snap>();
    final List<NBTTagCompound> animals = new ArrayList<NBTTagCompound>();
    String picked = "";
    long snapMs, restoreMs;

    static final class Snap {
        int cx, cz;
        int[] height, rain;
        Section[] sections = new Section[16];
        final List<NBTTagCompound> tiles = new ArrayList<NBTTagCompound>();
    }

    static final class Section {
        int yBase;
        byte[] lsb, msb, meta, block, sky;
    }

    RlArena(int x, int y, int z, int radius) { this.x = x; this.y = y; this.z = z; this.radius = radius; }

    /** the arena of this world: its centre found (or given), its chunks loaded and copied */
    static RlArena make(WorldServer w) {
        int r = Math.max(2, Math.min(7, Integer.getInteger("aiguide.rl.chunks", 5)));
        String at = System.getProperty("aiguide.rl.at");
        int[] c;
        String how;
        if (at != null && at.matches("-?\\d+\\s*,\\s*-?\\d+")) {
            String[] p = at.split(",");
            int cx = Integer.parseInt(p[0].trim()), cz = Integer.parseInt(p[1].trim());
            loadAround(w, cx, cz, r);
            c = new int[] { cx, ground(w, cx, cz), cz };
            how = "given";
        } else {
            net.minecraft.util.ChunkCoordinates sp = w.getSpawnPoint();
            loadAround(w, sp.posX, sp.posZ, 5);
            Object[] best = pick(w, sp.posX, sp.posZ);
            c = (int[]) best[0];
            how = (String) best[1];
        }
        w.setSpawnLocation(c[0], c[1], c[2]);   // near the spawn the chunks stay loaded without a player
        loadAround(w, c[0], c[2], r);
        RlArena a = new RlArena(c[0], c[1], c[2], r);
        a.picked = how;
        a.snapshot(w);
        return a;
    }

    static void loadAround(WorldServer w, int x, int z, int r) {
        for (int cx = (x >> 4) - r; cx <= (x >> 4) + r; cx++)
            for (int cz = (z >> 4) - r; cz <= (z >> 4) + r; cz++) w.theChunkProviderServer.loadChunk(cx, cz);
    }

    /** the standing height of a column (the first air over firm ground from the top) */
    static int ground(WorldServer w, int x, int z) {
        int top = w.getTopSolidOrLiquidBlock(x, z);
        int g = Crew.ground(w, x, top, z);
        return g < 0 ? top : g;
    }

    /**
     * The best centre within 48 of the spawn: the trees, the open stone, coal and iron within 24 of it (open: next
     * to air, above y 45), dry firm ground in the middle. { centre, how it was scored }.
     */
    static Object[] pick(WorldServer w, int sx, int sz) {
        int R = 72, n = 2 * R + 1;
        int[][] cnt = new int[4][n * n];
        boolean[][] wet = new boolean[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) {
                int x = sx - R + i, z = sz - R + j;
                int top = w.getHeightValue(x, z);
                Block t = w.getBlock(x, top - 1, z);
                wet[i][j] = t.getMaterial().isLiquid();
                boolean log = false;
                int stone = 0, coal = 0, iron = 0;
                for (int y = Math.min(250, top + 1); y >= Math.max(45, top - 20); y--) {
                    Block b = w.getBlock(x, y, z);
                    if (b == Blocks.log || b == Blocks.log2) log = true;
                    else if ((b == Blocks.stone || b == Blocks.coal_ore || b == Blocks.iron_ore) && open(w, x, y, z)) {
                        if (b == Blocks.stone) stone++;
                        else if (b == Blocks.coal_ore) coal++;
                        else iron++;
                    }
                }
                cnt[0][i * n + j] = log ? 1 : 0;
                cnt[1][i * n + j] = stone;
                cnt[2][i * n + j] = coal;
                cnt[3][i * n + j] = iron;
            }
        double best = -1e9;
        int[] at = { sx, ground(w, sx, sz), sz };
        String how = "spawn";
        for (int dx = -48; dx <= 48; dx += 6)
            for (int dz = -48; dz <= 48; dz += 6) {
                int ci = R + dx, cj = R + dz;
                if (wet[ci][cj]) continue;
                int[] k = new int[4];
                for (int i = Math.max(0, ci - 24); i <= Math.min(n - 1, ci + 24); i++)
                    for (int j = Math.max(0, cj - 24); j <= Math.min(n - 1, cj + 24); j++) {
                        if ((i - ci) * (i - ci) + (j - cj) * (j - cj) > 24 * 24) continue;
                        for (int q = 0; q < 4; q++) k[q] += cnt[q][i * n + j];
                    }
                // each kind counts up to enough of it: trees most (every scenario starts with wood), then iron, stone, coal
                double s = 3 * Math.min(1, k[0] / 40.0) + Math.min(1, k[1] / 150.0) + Math.min(1, k[2] / 8.0) + 2 * Math.min(1, k[3] / 4.0) - Math.hypot(dx, dz) * 0.002;
                if (s > best) {
                    best = s;
                    int x = sx + dx, z = sz + dz;
                    at = new int[] { x, ground(w, x, z), z };
                    how = "picked: " + k[0] + " tree columns, " + k[1] + " open stone, " + k[2] + " open coal, " + k[3] + " open iron within 24, score " + Math.round(s * 100) / 100.0;
                }
            }
        return new Object[] { at, how };
    }

    static boolean open(WorldServer w, int x, int y, int z) {
        return w.isAirBlock(x + 1, y, z) || w.isAirBlock(x - 1, y, z) || w.isAirBlock(x, y + 1, z) || w.isAirBlock(x, y - 1, z) || w.isAirBlock(x, y, z + 1) || w.isAirBlock(x, y, z - 1);
    }

    boolean inside(double px, double pz) {
        int cx = ((int) Math.floor(px)) >> 4, cz = ((int) Math.floor(pz)) >> 4;
        return Math.abs(cx - (x >> 4)) <= radius && Math.abs(cz - (z >> 4)) <= radius;
    }

    void snapshot(WorldServer w) {
        long t0 = System.nanoTime();
        chunks.clear();
        animals.clear();
        for (int cx = (x >> 4) - radius; cx <= (x >> 4) + radius; cx++)
            for (int cz = (z >> 4) - radius; cz <= (z >> 4) + radius; cz++) {
                Chunk ch = w.getChunkFromChunkCoords(cx, cz);
                Snap s = new Snap();
                s.cx = cx;
                s.cz = cz;
                s.height = ch.heightMap.clone();
                s.rain = ch.precipitationHeightMap.clone();
                ExtendedBlockStorage[] st = ch.getBlockStorageArray();
                for (int i = 0; i < st.length && i < 16; i++) {
                    if (st[i] == null) continue;
                    Section q = new Section();
                    q.yBase = st[i].getYLocation();
                    q.lsb = st[i].getBlockLSBArray().clone();
                    q.msb = st[i].getBlockMSBArray() == null ? null : st[i].getBlockMSBArray().data.clone();
                    q.meta = st[i].getMetadataArray().data.clone();
                    q.block = st[i].getBlocklightArray().data.clone();
                    q.sky = st[i].getSkylightArray() == null ? null : st[i].getSkylightArray().data.clone();
                    s.sections[i] = q;
                }
                for (Object o : ch.chunkTileEntityMap.values()) {
                    NBTTagCompound t = new NBTTagCompound();
                    ((TileEntity) o).writeToNBT(t);
                    s.tiles.add(t);
                }
                chunks.add(s);
            }
        for (Object o : new ArrayList<Object>(w.loadedEntityList)) {
            if (!(o instanceof EntityAnimal) || !inside(((Entity) o).posX, ((Entity) o).posZ)) continue;
            NBTTagCompound t = new NBTTagCompound();
            if (((Entity) o).writeToNBTOptional(t)) animals.add(t);
        }
        snapMs = (System.nanoTime() - t0) / 1000000;
    }

    /** the arena as it was copied: entities away, blocks, light, tile entities and animals back; the ms it took */
    long restore(WorldServer w) {
        long t0 = System.nanoTime();
        for (Object o : new ArrayList<Object>(w.loadedEntityList)) if (!(o instanceof EntityPlayer)) w.removeEntity((Entity) o);
        boolean sky = !w.provider.hasNoSky;
        for (Snap s : chunks) {
            Chunk ch = w.getChunkFromChunkCoords(s.cx, s.cz);
            for (Object o : new ArrayList<Object>(ch.chunkTileEntityMap.values())) {
                TileEntity t = (TileEntity) o;
                w.removeTileEntity(t.xCoord, t.yCoord, t.zCoord);
            }
            ch.chunkTileEntityMap.clear();
            ExtendedBlockStorage[] st = new ExtendedBlockStorage[16];
            for (int i = 0; i < 16; i++) {
                Section q = s.sections[i];
                if (q == null) continue;
                ExtendedBlockStorage e = new ExtendedBlockStorage(q.yBase, sky);
                e.setBlockLSBArray(q.lsb.clone());
                if (q.msb != null) e.setBlockMSBArray(new NibbleArray(q.msb.clone(), 4));
                e.setBlockMetadataArray(new NibbleArray(q.meta.clone(), 4));
                e.setBlocklightArray(new NibbleArray(q.block.clone(), 4));
                if (q.sky != null) e.setSkylightArray(new NibbleArray(q.sky.clone(), 4));
                e.removeInvalidBlocks();
                st[i] = e;
            }
            ch.setStorageArrays(st);
            System.arraycopy(s.height, 0, ch.heightMap, 0, Math.min(s.height.length, ch.heightMap.length));
            System.arraycopy(s.rain, 0, ch.precipitationHeightMap, 0, Math.min(s.rain.length, ch.precipitationHeightMap.length));
            ch.isModified = true;
            for (NBTTagCompound t : s.tiles) {
                TileEntity te = TileEntity.createAndLoadEntity(t);
                if (te != null) w.setTileEntity(te.xCoord, te.yCoord, te.zCoord, te);
            }
        }
        for (NBTTagCompound t : animals) {
            Entity e = EntityList.createEntityFromNBT((NBTTagCompound) t.copy(), w);
            if (e != null) w.spawnEntityInWorld(e);
        }
        restoreMs = (System.nanoTime() - t0) / 1000000;
        return restoreMs;
    }
}
