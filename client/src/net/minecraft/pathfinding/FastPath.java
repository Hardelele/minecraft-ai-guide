package net.minecraft.pathfinding;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.passive.EntityChicken;
import net.minecraft.init.Blocks;
import net.minecraft.util.MathHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.ChunkCache;
import net.minecraft.world.IWorldAccess;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

/**
 * The game's path finder of 1.7.10 (PathFinder, World.getEntityPathToXYZ, PathNavigate.getPathToXYZ) step for step,
 * quicker for the learning engine (docs/rl/ENGINE.md): the same points opened in the same order, the same heap (the
 * game's Path), the same rules of standing (canEntityStandAt as written, the entity's world for the blocks as the game
 * reads them), so the same path; only the lookups differ: a block through the chunks the search touched (an array
 * round the start, not the world's chunk hash map at every look) and the points in an open-addressing map (not
 * IntHashMap, which keeps a HashSet of its keys besides). In this package: PathPoint's fields are package-private.
 * Every chunk the game's ChunkCache would ask for is asked for too (a chunk loaded by a path search stays loaded).
 * -Daiguide.rl.engine.check=N: every Nth search also by the game's own, the two compared point by point (mismatches()).
 * The exact cache (Cache; off with -Daiguide.rl.engine.pathcache=false): a search asked again with all it depends on the
 * same (the start and end cells, the size, the range, the flags, the fall allowed, the entity's own cell and lava) and no
 * block changed since in the chunks it could read (target +- range + 3, start +- 3) gets the same points back; a
 * chunk that may still be decorated is never trusted (decoration sets blocks unannounced). Checked by the same CHECK.
 */
public final class FastPath {
    private FastPath() {}

    static final int CHECK = Integer.getInteger("aiguide.rl.engine.check", 0);
    static final boolean CACHE = !"false".equals(System.getProperty("aiguide.rl.engine.pathcache"));
    static long searches, checked, mismatched, hits, stored, changedDuring, unloaded, unpopulated;
    static String lastMismatch = "";

    public static long searches() { return searches; }
    public static long cacheHits() { return hits; }
    public static long cacheStored() { return stored; }
    public static String cacheMisses() { return "changed during " + changedDuring + ", unloaded " + unloaded + ", unpopulated " + unpopulated; }
    public static long checked() { return checked; }
    public static long mismatches() { return mismatched; }
    public static String lastMismatch() { return lastMismatch; }

    static Field openDoors, swim;

    static boolean flag(PathNavigate n, int which) {
        try {
            if (openDoors == null) {
                openDoors = field("canPassOpenWoodenDoors", "field_75518_j");
                swim = field("canSwim", "field_75517_m");
            }
            return (which == 0 ? openDoors : swim).getBoolean(n);
        } catch (Exception e) {
            throw new IllegalStateException("PathNavigate's flags not readable: " + e);
        }
    }

    static Field field(String mcp, String srg) throws Exception {
        Field f;
        try { f = PathNavigate.class.getDeclaredField(srg); } catch (NoSuchFieldException e) { f = PathNavigate.class.getDeclaredField(mcp); }
        f.setAccessible(true);
        return f;
    }

    /** PathNavigate.tryMoveToXYZ: the path by this finder, set as the game sets it */
    public static boolean tryMoveToXYZ(EntityLiving e, double x, double y, double z, double speed) {
        PathEntity p = pathToXYZ(e, MathHelper.floor_double(x), (int) y, MathHelper.floor_double(z));
        return e.getNavigator().setPath(p, speed);
    }

    /** PathNavigate.getPathToXYZ(x, y, z) */
    public static PathEntity pathToXYZ(EntityLiving e, double x, double y, double z) {
        PathNavigate n = e.getNavigator();
        boolean canSwim = flag(n, 1);
        if (!canNavigate(e, canSwim)) return null;
        return entityPathToXYZ(e.worldObj, e, MathHelper.floor_double(x), (int) y, MathHelper.floor_double(z), n.getPathSearchRange(),
                flag(n, 0), n.getCanBreakDoors(), n.getAvoidsWater(), canSwim);
    }

    static boolean canNavigate(EntityLiving e, boolean canSwim) {
        return e.onGround || canSwim && (e.isInWater() || e.handleLavaMovement())
                || e.isRiding() && e instanceof EntityZombie && e.ridingEntity instanceof EntityChicken;
    }

    /** PathNavigate.getPathToEntityLiving(target) */
    public static PathEntity pathToEntity(EntityLiving e, Entity target) {
        PathNavigate n = e.getNavigator();
        boolean canSwim = flag(n, 1);
        if (!canNavigate(e, canSwim)) return null;
        return entityPathToEntity(e.worldObj, e, target, n.getPathSearchRange(), flag(n, 0), n.getCanBreakDoors(), n.getAvoidsWater(), canSwim);
    }

    /** World.getPathEntityToEntity */
    public static PathEntity entityPathToEntity(World w, Entity e, Entity target, float range, boolean openDoors, boolean closedDoors,
                                                boolean avoidsWater, boolean canSwim) {
        searches++;
        PathEntity p = find(w, e, (int) (range + 16.0F), MathHelper.floor_double(e.posY + 1.0), openDoors, closedDoors, avoidsWater, canSwim,
                target.posX, target.boundingBox.minY, target.posZ, range);
        if (CHECK > 0 && searches % CHECK == 0) {
            checked++;
            String why = differ(p, w.getPathEntityToEntity(e, target, range, openDoors, closedDoors, avoidsWater, canSwim));
            if (why != null) {
                mismatched++;
                lastMismatch = why + " (to entity " + target.getEntityId() + " from " + e.posX + " " + e.posY + " " + e.posZ + ")";
            }
        }
        return p;
    }

    /** World.getEntityPathToXYZ */
    public static PathEntity entityPathToXYZ(World w, Entity e, int x, int y, int z, float range, boolean openDoors, boolean closedDoors,
                                             boolean avoidsWater, boolean canSwim) {
        searches++;
        PathEntity p = find(w, e, (int) (range + 8.0F), MathHelper.floor_double(e.posY), openDoors, closedDoors, avoidsWater, canSwim,
                x + 0.5F, y + 0.5F, z + 0.5F, range);
        if (CHECK > 0 && searches % CHECK == 0) {
            checked++;
            PathEntity g = w.getEntityPathToXYZ(e, x, y, z, range, openDoors, closedDoors, avoidsWater, canSwim);
            String why = differ(p, g);
            if (why != null) {
                mismatched++;
                lastMismatch = why + " (to " + x + " " + y + " " + z + " from " + e.posX + " " + e.posY + " " + e.posZ + ")";
            }
        }
        return p;
    }

    /** a search, or its points again from the cache when nothing it depends on changed */
    static PathEntity find(World w, Entity e, int l, int j, boolean openDoors, boolean closedDoors, boolean avoidsWater, boolean canSwim,
                           double x, double y, double z, float range) {
        if (!CACHE || canSwim && e.isInWater()) {   // (the start in water: the game's walk up through it, not cached)
            Search s = new Search(w, e, l, j, openDoors, closedDoors, avoidsWater, canSwim);
            return s.createEntityPathTo(e, x, y, z, range);
        }
        Cache c = cache(w);
        Key k = new Key();
        k.sx = MathHelper.floor_double(e.boundingBox.minX);
        k.sy = MathHelper.floor_double(e.boundingBox.minY + 0.5);
        k.sz = MathHelper.floor_double(e.boundingBox.minZ);
        k.ex = MathHelper.floor_double(x - (double) (e.width / 2.0F));
        k.ey = MathHelper.floor_double(y);
        k.ez = MathHelper.floor_double(z - (double) (e.width / 2.0F));
        k.w = MathHelper.floor_float(e.width + 1.0F);
        k.h = MathHelper.floor_float(e.height + 1.0F);
        k.range = Float.floatToIntBits(range);
        k.flags = (openDoors ? 1 : 0) | (closedDoors ? 2 : 0) | (avoidsWater ? 4 : 0);
        k.fall = e.getMaxFallHeight();
        k.px = MathHelper.floor_double(e.posX);   // canEntityStandAt's rails rule looks at the entity's own cell
        k.py = MathHelper.floor_double(e.posY);
        k.pz = MathHelper.floor_double(e.posZ);
        k.lava = e.handleLavaMovement();
        int r = (int) Math.ceil(range) + 3;
        int ax0 = Math.min(k.ex - r, Math.min(k.sx, k.px) - 3) >> 4, ax1 = Math.max(k.ex + r, Math.max(k.sx, k.px) + 3) >> 4;
        int az0 = Math.min(k.ez - r, Math.min(k.sz, k.pz) - 3) >> 4, az1 = Math.max(k.ez + r, Math.max(k.sz, k.pz) + 3) >> 4;
        Cache.Entry got = c.map.get(k);
        if (got != null && c.unchanged(w, ax0, az0, ax1, az1, got.at)) {
            int i = MathHelper.floor_double(e.posX), kz = MathHelper.floor_double(e.posZ);   // the chunks the game's ChunkCache asks for: asked for
            for (int cx = (i - l) >> 4; cx <= (i + l) >> 4; cx++)
                for (int cz = (kz - l) >> 4; cz <= (kz + l) >> 4; cz++) w.getChunkFromChunkCoords(cx, cz);
            hits++;
            return got.copy();
        }
        long at = c.changes;
        Search s = new Search(w, e, l, j, openDoors, closedDoors, avoidsWater, canSwim);
        PathEntity p = s.createEntityPathTo(e, x, y, z, range);
        if (c.changes != at) changedDuring++;
        else if (c.populated(w, ax0, az0, ax1, az1)) {   // (a chunk the search loaded may have set blocks: then not kept)
            if (c.map.size() >= Cache.MAX) c.map.clear();
            c.map.put(k, new Cache.Entry(p, at));
            stored++;
        }
        return p;
    }

    static final Map<World, Cache> caches = new WeakHashMap<World, Cache>();

    /** blocks set past the world's notices (the arena's restore writes whole chunks): nothing kept of it trusted */
    public static void worldRewritten(World w) {
        Cache c = caches.get(w);
        if (c != null) { c.map.clear(); c.changes++; }
    }

    static Cache cache(World w) {
        Cache c = caches.get(w);
        if (c == null) {
            caches.put(w, c = new Cache());
            w.addWorldAccess(c);
        }
        return c;
    }

    static final class Key {
        int sx, sy, sz, ex, ey, ez, w, h, range, flags, fall, px, py, pz;
        boolean lava;

        @Override public boolean equals(Object o) {
            if (!(o instanceof Key)) return false;
            Key q = (Key) o;
            return sx == q.sx && sy == q.sy && sz == q.sz && ex == q.ex && ey == q.ey && ez == q.ez && w == q.w && h == q.h && range == q.range
                    && flags == q.flags && fall == q.fall && px == q.px && py == q.py && pz == q.pz && lava == q.lava;
        }

        @Override public int hashCode() {
            int r = sx;
            r = r * 31 + sy; r = r * 31 + sz; r = r * 31 + ex; r = r * 31 + ey; r = r * 31 + ez; r = r * 31 + w; r = r * 31 + h;
            r = r * 31 + range; r = r * 31 + flags; r = r * 31 + fall; r = r * 31 + px; r = r * 31 + py; r = r * 31 + pz;
            return r * 31 + (lava ? 1 : 0);
        }
    }

    /**
     * A world's searches kept, and when the blocks of each chunk last changed: the world tells its listeners of every
     * block set with a notice (flag 2), and of a range redrawn. Sets without a notice (flag 4 only: a sapling's growth,
     * leaves' and fire's metadata, a piston's moving block) change nothing a path search reads but the piston's;
     * a chunk being populated sets blocks with none: such chunks are never trusted.
     */
    static final class Cache implements IWorldAccess {
        static final int MAX = 8192;
        final HashMap<Key, Entry> map = new HashMap<Key, Entry>();
        final HashMap<Long, Long> stamp = new HashMap<Long, Long>();
        long changes = 1;

        static final class Entry {
            final int[] pts;   // x, y, z of each point; null: no path
            final long at;

            Entry(PathEntity p, long at) {
                this.at = at;
                if (p == null) { pts = null; return; }
                pts = new int[p.getCurrentPathLength() * 3];
                for (int i = 0; i < p.getCurrentPathLength(); i++) {
                    PathPoint q = p.getPathPointFromIndex(i);
                    pts[3 * i] = q.xCoord;
                    pts[3 * i + 1] = q.yCoord;
                    pts[3 * i + 2] = q.zCoord;
                }
            }

            PathEntity copy() {
                if (pts == null) return null;
                PathPoint[] a = new PathPoint[pts.length / 3];
                for (int i = 0; i < a.length; i++) a[i] = new PathPoint(pts[3 * i], pts[3 * i + 1], pts[3 * i + 2]);
                return new PathEntity(a);
            }
        }

        static long ck(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xffffffffL); }

        void changed(int x, int z) { stamp.put(ck(x >> 4, z >> 4), ++changes); }

        boolean unchanged(World w, int cx0, int cz0, int cx1, int cz1, long at) {
            for (int cx = cx0; cx <= cx1; cx++)
                for (int cz = cz0; cz <= cz1; cz++) {
                    Long t = stamp.get(ck(cx, cz));
                    if (t != null && t > at) return false;
                }
            for (int cx = cx0; cx <= cx1; cx++)   // (unset: a chunk loaded anew since, its sets meanwhile untold)
                for (int cz = cz0; cz <= cz1; cz++)
                    if (!w.getChunkProvider().chunkExists(cx, cz) || !w.getChunkFromChunkCoords(cx, cz).func_150802_k()) return false;   // (isPopulated)
            return true;
        }

        /**
         * No block of these chunks can change unannounced. The world tells its listeners of a block set (flag 2) only in
         * a chunk that isPopulated: decorated, "light populated" and "ticked" (both set by the world's tick of a chunk
         * near a player: the arenas have none, so they stay false and nothing is told). So: each chunk loaded, it and its
         * three neighbours at -x, -z decorated (a chunk's decoration writes into its +x/+z neighbours: once those four are
         * done nothing decorates it any more); and its two flags set here, so its sets from now on are told. They gate
         * nothing else on a server without players (the chunks and block changes sent to players; the edge light fixed
         * when a player's tick reaches the chunk, which never comes in an arena; the light itself is not touched).
         */
        boolean populated(World w, int cx0, int cz0, int cx1, int cz1) {
            for (int cx = cx0 - 1; cx <= cx1; cx++)
                for (int cz = cz0 - 1; cz <= cz1; cz++) {
                    if (!w.getChunkProvider().chunkExists(cx, cz)) { unloaded++; return false; }
                    Chunk ch = w.getChunkFromChunkCoords(cx, cz);
                    if (!ch.isTerrainPopulated) { unpopulated++; return false; }
                }
            for (int cx = cx0; cx <= cx1; cx++)
                for (int cz = cz0; cz <= cz1; cz++) {
                    Chunk ch = w.getChunkFromChunkCoords(cx, cz);
                    ch.field_150815_m = true;
                    ch.isLightPopulated = true;
                }
            return true;
        }

        @Override public void markBlockForUpdate(int x, int y, int z) { changed(x, z); }
        @Override public void markBlockForRenderUpdate(int x, int y, int z) { changed(x, z); }
        @Override public void markBlockRangeForRenderUpdate(int x1, int y1, int z1, int x2, int y2, int z2) {
            for (int cx = Math.min(x1, x2) >> 4; cx <= Math.max(x1, x2) >> 4; cx++)
                for (int cz = Math.min(z1, z2) >> 4; cz <= Math.max(z1, z2) >> 4; cz++) stamp.put(ck(cx, cz), ++changes);
        }
        @Override public void playSound(String s, double x, double y, double z, float v, float p) {}
        @Override public void playSoundToNearExcept(EntityPlayer e, String s, double x, double y, double z, float v, float p) {}
        @Override public void spawnParticle(String s, double x, double y, double z, double a, double b, double c) {}
        @Override public void onEntityCreate(Entity e) {}
        @Override public void onEntityDestroy(Entity e) {}
        @Override public void playRecord(String s, int x, int y, int z) {}
        @Override public void broadcastSound(int t, int x, int y, int z, int d) {}
        @Override public void playAuxSFX(EntityPlayer e, int t, int x, int y, int z, int d) {}
        @Override public void destroyBlockPartially(int id, int x, int y, int z, int p) {}
        @Override public void onStaticEntitiesChanged() {}
    }

    static String differ(PathEntity a, PathEntity b) {
        if (a == null || b == null) return a == b ? null : "one null: fast " + (a == null ? "null" : a.getCurrentPathLength()) + ", game " + (b == null ? "null" : b.getCurrentPathLength());
        if (a.getCurrentPathLength() != b.getCurrentPathLength()) return "lengths " + a.getCurrentPathLength() + " vs " + b.getCurrentPathLength();
        for (int i = 0; i < a.getCurrentPathLength(); i++) {
            PathPoint p = a.getPathPointFromIndex(i), q = b.getPathPointFromIndex(i);
            if (p.xCoord != q.xCoord || p.yCoord != q.yCoord || p.zCoord != q.zCoord) return "point " + i + ": " + p + " vs " + q;
        }
        return null;
    }

    /** one search: PathFinder's state and its methods, the lookups replaced */
    static final class Search {
        final World world;
        final Path path = new Path();
        final PathPoint[] pathOptions = new PathPoint[32];
        final boolean canPassOpenWoodenDoors, canPassClosedWoodenDoor, canEntitySwim;
        boolean avoidsWater;
        final ChunkCache worldMap;   // only for the start in water, as the game's (its region asked for in any case)
        // the chunks round the start: [dx][dz] from cx0, cz0
        final int cx0, cz0, cw;
        final Chunk[] chunks;
        // the points: open addressing by PathPoint.makeHash
        int[] keys = new int[1024];
        PathPoint[] vals = new PathPoint[1024];
        int size;

        /** l: the half side of the game's ChunkCache round the entity (range + 8 to a point, + 16 to an entity), j: its middle's y */
        Search(World w, Entity e, int l, int j, boolean openDoors, boolean closedDoors, boolean avoidsWater, boolean canSwim) {
            world = w;
            canPassOpenWoodenDoors = openDoors;
            canPassClosedWoodenDoor = closedDoors;
            this.avoidsWater = avoidsWater;
            canEntitySwim = canSwim;
            int i = MathHelper.floor_double(e.posX), k = MathHelper.floor_double(e.posZ);
            int x0 = i - l, z0 = k - l, x1 = i + l, z1 = k + l;
            if (canSwim && e.isInWater()) worldMap = new ChunkCache(w, x0, j - l, z0, x1, j + l, z1, 0);
            else {
                worldMap = null;
                for (int cx = x0 >> 4; cx <= x1 >> 4; cx++)   // what ChunkCache's constructor asks the world for
                    for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) w.getChunkFromChunkCoords(cx, cz);
            }
            int r = (l >> 4) + 3;
            cx0 = (i >> 4) - r;
            cz0 = (k >> 4) - r;
            cw = 2 * r + 1;
            chunks = new Chunk[cw * cw];
        }

        /** World.getBlock: the same chunk, the same answer */
        Block block(int x, int y, int z) {
            if (x < -30000000 || z < -30000000 || x >= 30000000 || z >= 30000000 || y < 0 || y >= 256) return Blocks.air;
            int dx = (x >> 4) - cx0, dz = (z >> 4) - cz0;
            if (dx < 0 || dz < 0 || dx >= cw || dz >= cw) return world.getBlock(x, y, z);
            Chunk c = chunks[dx * cw + dz];
            if (c == null) chunks[dx * cw + dz] = c = world.getChunkFromChunkCoords(x >> 4, z >> 4);
            return c.getBlock(x & 15, y, z & 15);
        }

        PathEntity createEntityPathTo(Entity entityIn, double x, double y, double z, float maxDistance) {
            path.clearPath();
            boolean flag = avoidsWater;
            int i = MathHelper.floor_double(entityIn.boundingBox.minY + 0.5);
            if (canEntitySwim && entityIn.isInWater()) {
                i = (int) entityIn.boundingBox.minY;
                Block b = worldMap.getBlock(MathHelper.floor_double(entityIn.posX), i, MathHelper.floor_double(entityIn.posZ));
                while (b == Blocks.flowing_water || b == Blocks.water) b = worldMap.getBlock(MathHelper.floor_double(entityIn.posX), ++i, MathHelper.floor_double(entityIn.posZ));
                flag = avoidsWater;
                avoidsWater = false;
            } else i = MathHelper.floor_double(entityIn.boundingBox.minY + 0.5);
            PathPoint start = openPoint(MathHelper.floor_double(entityIn.boundingBox.minX), i, MathHelper.floor_double(entityIn.boundingBox.minZ));
            PathPoint end = openPoint(MathHelper.floor_double(x - (double) (entityIn.width / 2.0F)), MathHelper.floor_double(y),
                    MathHelper.floor_double(z - (double) (entityIn.width / 2.0F)));
            PathPoint size = new PathPoint(MathHelper.floor_float(entityIn.width + 1.0F), MathHelper.floor_float(entityIn.height + 1.0F),
                    MathHelper.floor_float(entityIn.width + 1.0F));
            PathEntity p = addToPath(entityIn, start, end, size, maxDistance);
            avoidsWater = flag;
            return p;
        }

        PathEntity addToPath(Entity entityIn, PathPoint start, PathPoint target, PathPoint size, float maxDistance) {
            start.totalPathDistance = 0.0F;
            start.distanceToNext = start.distanceToSquared(target);
            start.distanceToTarget = start.distanceToNext;
            path.clearPath();
            path.addPoint(start);
            PathPoint best = start;
            while (!path.isPathEmpty()) {
                PathPoint p1 = path.dequeue();
                if (p1.equals(target)) return createEntityPath(start, target);
                if (p1.distanceToSquared(target) < best.distanceToSquared(target)) best = p1;
                p1.visited = true;
                int n = findPathOptions(entityIn, p1, size, target, maxDistance);
                for (int j = 0; j < n; j++) {
                    PathPoint p2 = pathOptions[j];
                    float f = p1.totalPathDistance + p1.distanceToSquared(p2);
                    if (!p2.isAssigned() || f < p2.totalPathDistance) {
                        p2.previous = p1;
                        p2.totalPathDistance = f;
                        p2.distanceToNext = p2.distanceToSquared(target);
                        if (p2.isAssigned()) path.changeDistance(p2, p2.totalPathDistance + p2.distanceToNext);
                        else {
                            p2.distanceToTarget = p2.totalPathDistance + p2.distanceToNext;
                            path.addPoint(p2);
                        }
                    }
                }
            }
            return best == start ? null : createEntityPath(start, best);
        }

        int findPathOptions(Entity entityIn, PathPoint p, PathPoint size, PathPoint target, float maxDistance) {
            int i = 0;
            int step = canEntityStandAt(entityIn, p.xCoord, p.yCoord + 1, p.zCoord, size) == 1 ? 1 : 0;
            PathPoint a = getSafePoint(entityIn, p.xCoord, p.yCoord, p.zCoord + 1, size, step);
            PathPoint b = getSafePoint(entityIn, p.xCoord - 1, p.yCoord, p.zCoord, size, step);
            PathPoint c = getSafePoint(entityIn, p.xCoord + 1, p.yCoord, p.zCoord, size, step);
            PathPoint d = getSafePoint(entityIn, p.xCoord, p.yCoord, p.zCoord - 1, size, step);
            if (a != null && !a.visited && a.distanceTo(target) < maxDistance) pathOptions[i++] = a;
            if (b != null && !b.visited && b.distanceTo(target) < maxDistance) pathOptions[i++] = b;
            if (c != null && !c.visited && c.distanceTo(target) < maxDistance) pathOptions[i++] = c;
            if (d != null && !d.visited && d.distanceTo(target) < maxDistance) pathOptions[i++] = d;
            return i;
        }

        PathPoint getSafePoint(Entity entityIn, int x, int y, int z, PathPoint size, int stepHeight) {
            PathPoint p = null;
            int i = canEntityStandAt(entityIn, x, y, z, size);
            if (i == 2) return openPoint(x, y, z);
            if (i == 1) p = openPoint(x, y, z);
            if (p == null && stepHeight > 0 && i != -3 && i != -4 && canEntityStandAt(entityIn, x, y + stepHeight, z, size) == 1) {
                p = openPoint(x, y + stepHeight, z);
                y += stepHeight;
            }
            if (p != null) {
                int j = 0, k = 0;
                while (y > 0) {
                    k = canEntityStandAt(entityIn, x, y - 1, z, size);
                    if (avoidsWater && k == -1) return null;
                    if (k != 1) break;
                    if (j++ >= entityIn.getMaxFallHeight()) return null;
                    if (--y > 0) p = openPoint(x, y, z);
                }
                if (k == -2) return null;
            }
            return p;
        }

        PathPoint openPoint(int x, int y, int z) {
            int h = PathPoint.makeHash(x, y, z);
            int m = keys.length - 1, at = mix(h) & m;
            while (vals[at] != null) {
                if (keys[at] == h) return vals[at];
                at = (at + 1) & m;
            }
            PathPoint p = new PathPoint(x, y, z);
            keys[at] = h;
            vals[at] = p;
            if (++size * 2 > keys.length) grow();
            return p;
        }

        static int mix(int h) {
            h *= 0x9E3779B9;
            return h ^ (h >>> 16);
        }

        void grow() {
            int[] ok = keys;
            PathPoint[] ov = vals;
            keys = new int[ok.length * 2];
            vals = new PathPoint[ok.length * 2];
            int m = keys.length - 1;
            for (int i = 0; i < ok.length; i++) {
                if (ov[i] == null) continue;
                int at = mix(ok[i]) & m;
                while (vals[at] != null) at = (at + 1) & m;
                keys[at] = ok[i];
                vals[at] = ov[i];
            }
        }

        /** PathFinder.canEntityStandAt as written (the entity's world for the blocks: here through the chunks round the start) */
        int canEntityStandAt(Entity e, int x, int y, int z, PathPoint size) {
            boolean flag = false;
            for (int i = x; i < x + size.xCoord; i++)
                for (int j = y; j < y + size.yCoord; j++)
                    for (int k = z; k < z + size.zCoord; k++) {
                        Block b = block(i, j, k);
                        if (b.getMaterial() == Material.air) continue;
                        if (b == Blocks.trapdoor) flag = true;
                        else if (b != Blocks.flowing_water && b != Blocks.water) {
                            if (!canPassOpenWoodenDoors && b == Blocks.wooden_door) return 0;
                        } else {
                            if (avoidsWater) return -1;
                            flag = true;
                        }
                        int l = b.getRenderType();
                        if (l == 9) {
                            int i1 = MathHelper.floor_double(e.posX), j1 = MathHelper.floor_double(e.posY), k1 = MathHelper.floor_double(e.posZ);
                            if (block(i1, j1, k1).getRenderType() != 9 && block(i1, j1 - 1, k1).getRenderType() != 9) return -3;
                        } else if (!b.isPassable(e.worldObj, i, j, k) && (!canPassClosedWoodenDoor || b != Blocks.wooden_door)) {
                            if (l == 11 || b == Blocks.fence_gate || l == 32) return -3;
                            if (b == Blocks.trapdoor) return -4;
                            if (b.getMaterial() != Material.lava) return 0;
                            if (!e.handleLavaMovement()) return -2;
                        }
                    }
            return flag ? 2 : 1;
        }

        static PathEntity createEntityPath(PathPoint start, PathPoint end) {
            int i = 1;
            for (PathPoint p = end; p.previous != null; p = p.previous) i++;
            PathPoint[] a = new PathPoint[i];
            PathPoint p = end;
            i--;
            for (a[i] = end; p.previous != null; a[i] = p) {
                p = p.previous;
                i--;
            }
            return new PathEntity(a);
        }
    }
}
