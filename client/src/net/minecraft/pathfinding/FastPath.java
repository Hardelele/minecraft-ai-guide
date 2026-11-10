package net.minecraft.pathfinding;

import java.lang.reflect.Field;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.passive.EntityChicken;
import net.minecraft.init.Blocks;
import net.minecraft.util.MathHelper;
import net.minecraft.world.ChunkCache;
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
 */
public final class FastPath {
    private FastPath() {}

    static final int CHECK = Integer.getInteger("aiguide.rl.engine.check", 0);
    static long searches, checked, mismatched;
    static String lastMismatch = "";

    public static long searches() { return searches; }
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
        Search s = new Search(w, e, (int) (range + 16.0F), MathHelper.floor_double(e.posY + 1.0), openDoors, closedDoors, avoidsWater, canSwim);
        PathEntity p = s.createEntityPathTo(e, target.posX, target.boundingBox.minY, target.posZ, range);
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
        Search s = new Search(w, e, (int) (range + 8.0F), MathHelper.floor_double(e.posY), openDoors, closedDoors, avoidsWater, canSwim);
        PathEntity p = s.createEntityPathTo(e, x + 0.5F, y + 0.5F, z + 0.5F, range);
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
