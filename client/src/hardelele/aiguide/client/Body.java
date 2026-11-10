package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.WorldServer;

/**
 * An agent's body: the one way it acts on the world, with a player's limits. The head turns at a limited speed and
 * the eyes look along it; the hands break (the left button) and put (the right button) only what the ray from the
 * eyes along the look meets in a player's reach, a block put against the face that ray meets; legs walk, jump and
 * sneak; one thing is held. What it can do is told by a Result: NOT_VISIBLE, OUT_OF_REACH and the like are for the
 * task to go and stand elsewhere. Its senses are a snapshot each tick, cheap: health and hurt, falling, water, lava,
 * fire, the mobs it sees (in its field of view, nothing in between), and the blocks a few rays a tick met lately.
 * Under it the crew's honest economy stays as it was (Skills: the hands' fake player, onItemUse, BreakEvent, Ledger).
 */
class Body {
    static final float YAW_SPEED = 30F, PITCH_SPEED = 40F, BODY_SPEED = 25F;   // degrees a tick (the crew's old face())
    static final int LOOK_FOR = 30;      // ticks a hand waits for the eyes to come onto its target
    static final double SIGHT = 32;      // how far the eyes' rays go for the senses
    static final double FOV = Math.cos(Math.toRadians(60));   // half of the field of view

    final Agent a;
    float yaw, pitch;               // where the eyes look (the head's yaw, the pitch)
    private float yaw0, pitch0, body0;
    private long turnAt = -10, droveAt = -10;

    Body(Agent a) { this.a = a; }

    long now() { return a.worldObj.getTotalWorldTime(); }

    // ---- the head and the eyes -------------------------------------------------------------------------

    double eyeX() { return a.posX; }
    double eyeY() { return a.posY + a.getEyeHeight(); }
    double eyeZ() { return a.posZ; }

    /** the turn of this tick starts from where the head was (when the body did not drive it, from where the game left it) */
    private void startTurn() {
        long t = now();
        if (t == turnAt) return;
        if (droveAt < t - 1) { yaw = a.rotationYawHead; pitch = a.rotationPitch; }
        yaw0 = yaw;
        pitch0 = pitch;
        body0 = a.rotationYaw;
        turnAt = t;
    }

    /** turn the eyes toward a point, as far as a tick allows (called again in the same tick: from where the tick began); true when on it */
    boolean lookAt(double x, double y, double z) {
        startTurn();
        double dx = x - eyeX(), dy = y - eyeY(), dz = z - eyeZ();
        float ty = (float) (Math.atan2(dz, dx) * 180 / Math.PI) - 90F;
        float tp = (float) (-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180 / Math.PI);
        yaw = yaw0 + clamp(MathHelper.wrapAngleTo180_float(ty - yaw0), YAW_SPEED);
        pitch = clamp(pitch0 + clamp(tp - pitch0, PITCH_SPEED), 90F);
        a.rotationYaw = body0 + clamp(MathHelper.wrapAngleTo180_float(yaw - body0), BODY_SPEED);
        droveAt = now();
        apply();
        return Math.abs(MathHelper.wrapAngleTo180_float(ty - yaw)) < 0.5F && Math.abs(tp - pitch) < 0.5F;
    }

    static float clamp(float v, float m) { return Math.max(-m, Math.min(m, v)); }

    /** the eyes look within so many degrees of a point (a hit on a mob wants the eyes on it, as a player's click does) */
    boolean facing(double x, double y, double z, double degrees) {
        double dx = x - eyeX(), dy = y - eyeY(), dz = z - eyeZ(), d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d < 1e-6) return true;
        double[] v = look();
        return (dx * v[0] + dy * v[1] + dz * v[2]) / d >= Math.cos(Math.toRadians(degrees));
    }

    /** the head shows what the eyes do: now, and the game's own look helper keeps it so on the next tick */
    private void apply() {
        a.rotationYawHead = yaw;
        a.rotationPitch = pitch;
        double[] v = look();
        a.getLookHelper().setLookPosition(eyeX() + v[0] * 8, eyeY() + v[1] * 8, eyeZ() + v[2] * 8, 180F, 180F);
    }

    /** the direction the eyes look, a unit vector */
    double[] look() {
        double y = Math.toRadians(yawNow()), p = Math.toRadians(pitchNow());
        return new double[] { -Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p) };
    }

    float yawNow() { return droveAt >= now() - 1 ? yaw : a.rotationYawHead; }
    float pitchNow() { return droveAt >= now() - 1 ? pitch : a.rotationPitch; }

    /** what the ray from the eyes along the look meets within a distance (blocks with no box, grass, torches, are looked through) */
    MovingObjectPosition ray(double len) {
        double[] v = look();
        Vec3 from = Vec3.createVectorHelper(eyeX(), eyeY(), eyeZ());
        Vec3 to = Vec3.createVectorHelper(eyeX() + v[0] * len, eyeY() + v[1] * len, eyeZ() + v[2] * len);
        MovingObjectPosition m = a.worldObj.rayTraceBlocks(from, to, false, true, false);
        return m;   // the world's block ray: a block, or null
    }

    /** what is under the aim now, in reach */
    MovingObjectPosition aim() {
        return ray(Crew.REACH);
    }

    boolean aimingAt(int x, int y, int z) {
        MovingObjectPosition m = aim();
        return m != null && m.blockX == x && m.blockY == y && m.blockZ == z;
    }

    /** the aim on a block with no box (wheat, grass, a sapling, a torch): a ray that stops at what has a selection box, as a player's click */
    boolean aimingAtPlant(int x, int y, int z) {
        double[] v = look();
        Vec3 from = Vec3.createVectorHelper(eyeX(), eyeY(), eyeZ());
        Vec3 to = Vec3.createVectorHelper(eyeX() + v[0] * Crew.REACH, eyeY() + v[1] * Crew.REACH, eyeZ() + v[2] * Crew.REACH);
        MovingObjectPosition m = a.worldObj.rayTraceBlocks(from, to, false, false, false);
        return m != null && m.blockX == x && m.blockY == y && m.blockZ == z;
    }

    boolean aimingAt(int x, int y, int z, int face) {
        MovingObjectPosition m = aim();
        return m != null && m.blockX == x && m.blockY == y && m.blockZ == z && m.sideHit == face;
    }

    /**
     * Where on a block the eyes can see it from where they are: its middle, else the middle of a face open to the air,
     * whichever a ray from the eyes reaches first meeting the block itself; null when something is in the way of all.
     */
    double[] sightPoint(WorldServer w, int x, int y, int z) {
        Vec3 from = Vec3.createVectorHelper(eyeX(), eyeY(), eyeZ());
        Block blk = w.getBlock(x, y, z);
        blk.setBlockBoundsBasedOnState(w, x, y, z);
        double mid = (blk.getBlockBoundsMinY() + blk.getBlockBoundsMaxY()) / 2;   // a low plant's middle (wheat: an eighth up)
        for (int k = -1; k < 6; k++) {
            double tx = x + 0.5, ty = y + (k < 0 ? mid : 0.5), tz = z + 0.5;
            if (k >= 0) {
                int[] d = Skills.SIDE[k];
                if (!w.isAirBlock(x + d[0], y + d[1], z + d[2]) && w.getBlock(x + d[0], y + d[1], z + d[2]).getMaterial().isSolid()) continue;
                tx += d[0] * 0.49; ty += d[1] * 0.49; tz += d[2] * 0.49;
            }
            MovingObjectPosition hit = w.rayTraceBlocks(Vec3.createVectorHelper(from.xCoord, from.yCoord, from.zCoord), Vec3.createVectorHelper(tx, ty, tz), false, true, false);
            if (hit == null || hit.blockX == x && hit.blockY == y && hit.blockZ == z) return new double[] { tx, ty, tz };
        }
        return null;
    }

    /** the world as the eyes and the feet know it (for Sight): what stops a ray, where a body fits, what one stands on */
    static Sight.Space space(final WorldServer w) {
        return new Sight.Space() {
            public boolean solid(int x, int y, int z) {
                Block b = w.getBlock(x, y, z);
                return b.getMaterial() != Material.air && b.getCollisionBoundingBoxFromPool(w, x, y, z) != null && b.canStopRayTrace(w.getBlockMetadata(x, y, z), false);
            }
            public boolean free(int x, int y, int z) { return Ground.passable(w, x, y, z, null); }
            public boolean floor(int x, int y, int z) { return Ground.floor(w, x, y, z, null); }
        };
    }

    // ---- the hands --------------------------------------------------------------------------------------

    /** what is in the hand (from the bag; null: bare hands) */
    void hold(ItemStack s) {
        if (a.getHeldItem() != s) a.setCurrentItemOrArmor(0, s);
    }

    private int digX, digY, digZ = Integer.MIN_VALUE, digTicks, digNeed, digWait;

    /**
     * The left button held on a block, one tick of it: the eyes turn onto it, the best tool of the bag in the hand,
     * the cracks grow while the aim stays on it for as long as the game says that tool takes, then the game's own
     * harvest. Another block, or the aim off it, starts it over (as a player's). OK when it is gone.
     */
    Result dig(WorldServer w, int x, int y, int z) {
        Block b = w.getBlock(x, y, z);
        if (b.getMaterial() == Material.air) { digZ = Integer.MIN_VALUE; return Result.OK; }
        if (digX != x || digY != y || digZ != z) {   // a new block: the tool for it, and how long
            digX = x; digY = y; digZ = z;
            digTicks = 0; digWait = 0;
            ItemStack tool = Skills.bestTool(a, b, w.getBlockMetadata(x, y, z));
            ItemStack held = a.getHeldItem();
            if (tool != null) hold(tool);
            else if (held != null && !held.isStackable() && a.bag.contains(held)) hold(null);   // a tool that does not help: bare hands
            digNeed = Skills.breakTicks(a, w, x, y, z);
        }
        if (digNeed < 0) { digZ = Integer.MIN_VALUE; return Result.NO_TOOL; }
        a.act(x, y, z, 1);
        double[] p = sightPoint(w, x, y, z);
        if (p == null) {   // nothing of it seen from here (it may be on the move: a little while)
            if (++digWait > LOOK_FOR / 3) { reset(w); return Result.NOT_VISIBLE; }
            return Result.RUNNING;
        }
        double dx = p[0] - eyeX(), dy = p[1] - eyeY(), dz = p[2] - eyeZ();
        if (Math.sqrt(dx * dx + dy * dy + dz * dz) > Crew.REACH) { reset(w); return Result.OUT_OF_REACH; }
        lookAt(p[0], p[1], p[2]);
        boolean plant = b.getCollisionBoundingBoxFromPool(w, x, y, z) == null;
        if (!(aimingAt(x, y, z) || plant && aimingAtPlant(x, y, z))) {   // the eyes are not on it yet: the cracks wait
            if (++digWait > LOOK_FOR) { reset(w); return Result.NOT_VISIBLE; }
            return Result.RUNNING;
        }
        digWait = 0;
        digTicks++;
        if (digTicks % 5 == 1) {
            a.swingItem();
            w.playSoundEffect(x + 0.5, y + 0.5, z + 0.5, b.stepSound.getStepSound(), 0.35F, 0.6F);
        }
        double k = digTicks / (double) digNeed;
        w.destroyBlockInWorldPartially(a.getEntityId(), x, y, z, Math.min(9, (int) (k * 10)));
        if (k < 1) return Result.RUNNING;
        reset(w);
        Ledger.act(a, "break " + b.getUnlocalizedName().replace("tile.", ""));
        boolean got = Skills.harvest(a, w, x, y, z);
        if (got) {
            if (Agent.hungerOn) a.hunger.exhaust(Hunger.BREAK);   // a block broken costs food as a player's
            RlEnv.broken(a, w, x, y, z);
        }
        return got ? Result.OK : Result.BLOCKED;
    }

    private void reset(WorldServer w) {
        if (digZ != Integer.MIN_VALUE) w.destroyBlockInWorldPartially(a.getEntityId(), digX, digY, digZ, -1);
        digZ = Integer.MIN_VALUE;
    }

    private int placeWait;

    /** the stack of the bag that is this item (or null) */
    ItemStack stack(Item item, int meta) {
        for (ItemStack s : a.bag) if (Skills.same(s, item, meta)) return s;
        return null;
    }

    /**
     * The right button with an item of the bag, one tick of it: a block at x y z, put against a face of a firm
     * neighbour that the eyes see from here in reach (the one under the aim first); the eyes turn onto that face and
     * the item's own use happens on the point the aim meets. NOT_VISIBLE / OUT_OF_REACH: no such face from here (the
     * task goes and stands where one is, Sight finds where); BLOCKED: nothing to put it against, or the use failed.
     */
    /** a face to put a block in x y z against, seen from the eyes within reach (place's own test, from where it stands) */
    boolean seesFace(WorldServer w, int x, int y, int z) {
        Sight.Space s = space(w);
        for (int[] c : Skills.supports(w, x, y, z, false))
            if (Sight.seeFace(s, eyeX(), eyeY(), eyeZ(), c[0], c[1], c[2], c[3], Crew.REACH) != null) return true;
        return false;
    }

    /** a cell a block can go into: soft (air, plants) or a flowing liquid (replaced as a player's block replaces it) */
    static boolean takes(WorldServer w, int x, int y, int z) {
        if (Crew.soft(w, x, y, z)) return true;
        return w.getBlock(x, y, z).getMaterial().isLiquid() && w.getBlockMetadata(x, y, z) != 0;
    }

    Result place(WorldServer w, int x, int y, int z, Item item, int meta) {
        if (!takes(w, x, y, z)) { placeWait = 0; return Result.GONE; }
        ItemStack in = stack(item, meta);
        if (in == null) { placeWait = 0; return Result.NO_ITEMS; }
        boolean door = item instanceof net.minecraft.item.ItemDoor;
        List<int[]> sup = Skills.supports(w, x, y, z, door);
        if (sup.isEmpty()) { placeWait = 0; return Result.BLOCKED; }
        int[] use = null;
        double[] at = null;
        MovingObjectPosition m = aim();
        for (int[] c : sup)
            if (m != null && m.blockX == c[0] && m.blockY == c[1] && m.blockZ == c[2] && m.sideHit == c[3]) { use = c; at = new double[] { m.hitVec.xCoord, m.hitVec.yCoord, m.hitVec.zCoord }; }
        if (use == null) {
            Sight.Space s = space(w);
            for (int[] c : sup) {
                double[] p = Sight.seeFace(s, eyeX(), eyeY(), eyeZ(), c[0], c[1], c[2], c[3], Crew.REACH);
                if (p != null) { use = c; at = p; break; }
            }
        }
        if (use == null) {
            placeWait = 0;
            for (int[] c : sup) if (Sight.faceDist(eyeX(), eyeY(), eyeZ(), c[0], c[1], c[2]) <= Crew.REACH) return Result.NOT_VISIBLE;
            return Result.OUT_OF_REACH;
        }
        hold(in);
        a.act(x, y, z, 3);
        lookAt(at[0], at[1], at[2]);
        if (!aimingAt(use[0], use[1], use[2], use[3])) {   // turning onto it
            if (++placeWait > LOOK_FOR) { placeWait = 0; return Result.NOT_VISIBLE; }
            return Result.RUNNING;
        }
        placeWait = 0;
        Result free = Skills.clearCell(a, w, x, y, z, door ? 2 : 1);
        if (free != Result.OK) return free;
        MovingObjectPosition hit = aim();
        Result r = Skills.use(a, w, x, y, z, in, use, (float) (hit.hitVec.xCoord - use[0]), (float) (hit.hitVec.yCoord - use[1]), (float) (hit.hitVec.zCoord - use[2]));
        if (r == Result.OK && !a.bag.contains(in)) hold(Skills.bestHeld(a));   // the last one went: a tool in the hand again
        if (r == Result.OK) RlEnv.placed(a, w, x, y, z);   // its decision's mark on the block (the causes)
        return r;
    }

    /**
     * The right button on a block (a door): the eyes onto a point of it seen from here in reach, then the block's own
     * use by the hands. RUNNING while the head turns.
     */
    Result activate(WorldServer w, int x, int y, int z) {
        double[] p = sightPoint(w, x, y, z);
        if (p == null) return Result.NOT_VISIBLE;
        double dx = p[0] - eyeX(), dy = p[1] - eyeY(), dz = p[2] - eyeZ();
        if (Math.sqrt(dx * dx + dy * dy + dz * dz) > Crew.REACH) return Result.OUT_OF_REACH;
        if (!lookAt(p[0], p[1], p[2])) return Result.RUNNING;
        MovingObjectPosition m = aim();
        int side = m != null && m.blockX == x && m.blockY == y && m.blockZ == z ? m.sideHit : 0;
        w.getBlock(x, y, z).onBlockActivated(w, x, y, z, Skills.hands(a, w), side, (float) (p[0] - x), (float) (p[1] - y), (float) (p[2] - z));
        a.swingItem();
        return Result.OK;
    }

    /** a face of these supports the eyes see from here in reach: { support, aim point } or null */
    Object[] sees(WorldServer w, List<int[]> supports) {
        Sight.Space s = space(w);
        for (int[] c : supports) {
            double[] p = Sight.seeFace(s, eyeX(), eyeY(), eyeZ(), c[0], c[1], c[2], c[3], Crew.REACH);
            if (p != null) return new Object[] { c, p };
        }
        return null;
    }

    // ---- the legs ---------------------------------------------------------------------------------------

    /** walk there by the game's path finder */
    boolean walkTo(double x, double y, double z, double speed) { return RlEngine.moveTo(a, x, y, z, speed); }

    boolean walkTo(Entity e, double speed) { return a.getNavigator().tryMoveToEntityLiving(e, speed); }

    /** a step straight toward a point (the next cell of a route) */
    void stepTo(double x, double y, double z, double speed) { a.getMoveHelper().setMoveTo(x, y, z, speed); }

    void jump() { a.getJumpHelper().setJumping(); }

    void stop() { a.getNavigator().clearPathEntity(); }

    boolean pathless() { return a.getNavigator().noPath(); }

    /** sneaking: slower, and not over an edge (the game's own rule for a sneaking player is not in a creature: it only shows) */
    void sneak(boolean on) { a.setSneaking(on); }

    // ---- the senses -------------------------------------------------------------------------------------

    /** what the body knows this tick */
    static final class Senses {
        long at;
        float health, maxHealth;
        boolean hurt;              // hurt since the last tick
        String hurtBy = "";
        float fall;                // the fall so far
        double motionY;
        boolean onGround, falling, inWater, inLava, burning, underWater, collided;
        int[] feet;
        Block under;
        final List<EntityLivingBase> mobs = new ArrayList<EntityLivingBase>();   // living things it sees, nearest first
        EntityLivingBase threat;   // the nearest hostile one it sees
        double threatDist = 1e9;
        String threatKind = "";    // its kind, as the game names it ("Creeper", "Zombie")
        int threatFuse;            // a creeper's: above 0 it hisses
        // for the reflexes (layer 2), each a few lookups at most
        boolean inWall;            // the eyes inside a block (buried)
        int air = 300;             // breath
        int drop;                  // free cells under the feet while off the ground, up to 6
        boolean headroom = true;   // the cell over the head free
        boolean pit;               // walled in at the feet and the head on all four sides (every 10 ticks)
        int inWaterTicks;          // in water this long
        int still;                 // ticks it has not moved a third of a block (counted every 20)
        int blocks;                // blocks of the bag for a pillar (every 10 ticks)
        double weapon = 1;         // the bag's best attack damage (every 10 ticks)
        boolean digTool;           // a shovel or a pickaxe in the bag
    }

    final Senses senses = new Senses();
    private int lastHurt = -1000, ray;
    private double stillX, stillY, stillZ;

    /** the blocks the senses' rays met lately: key -> x, y, z, block id, meta, face, tick (at most 1024, the oldest go) */
    final Map<Long, int[]> seen = new LinkedHashMap<Long, int[]>(256, 0.75F, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, int[]> e) { return size() > 1024; }
    };

    static final float[][] FIELD = new float[88][];
    static {
        int i = 0;
        for (int py = -35; py <= 35; py += 10) for (int yy = -50; yy <= 50; yy += 10) FIELD[i++] = new float[] { yy, py };
    }
    static final int RAYS = 6;   // rays a tick: the whole field in about 15 ticks

    /** this tick's snapshot: the cheap things every tick, a few rays, the mobs every fourth tick */
    void sense(WorldServer w) {
        Senses s = senses;
        long t = now();
        s.at = t;
        s.health = a.getHealth();
        s.maxHealth = a.getMaxHealth();
        s.hurt = a.hurtAt > lastHurt;
        if (s.hurt) s.hurtBy = a.hurtBy == null ? "" : a.hurtBy;
        lastHurt = a.hurtAt;
        s.fall = a.fallDistance;
        s.motionY = a.motionY;
        s.onGround = a.onGround;
        s.falling = !a.onGround && a.motionY < -0.2 && a.fallDistance > 1.0F;
        s.inWater = a.isInWater();
        s.inLava = a.handleLavaMovement();
        s.burning = a.isBurning();
        s.underWater = a.isInsideOfMaterial(Material.water);
        s.collided = a.isCollidedHorizontally;
        s.feet = Ground.feet(w, a.posX, a.posY, a.posZ);
        s.under = w.getBlock(s.feet[0], s.feet[1] - 1, s.feet[2]);
        s.inWall = a.isEntityInsideOpaqueBlock();
        s.air = a.getAir();
        s.inWaterTicks = s.inWater ? s.inWaterTicks + 1 : 0;
        int bx = MathHelper.floor_double(a.posX), by = MathHelper.floor_double(a.posY), bz = MathHelper.floor_double(a.posZ);
        s.drop = 0;
        if (!a.onGround) while (s.drop < 6 && Ground.passable(w, bx, by - 1 - s.drop, bz, null) && !Ground.liquid(w, bx, by - 1 - s.drop, bz)) s.drop++;
        s.headroom = Ground.passable(w, s.feet[0], s.feet[1] + 2, s.feet[2], null);
        long slow = t + a.getEntityId();
        if (slow % 10 == 0) {
            s.pit = Moves.pit(space(w), s.feet[0], s.feet[1], s.feet[2]);
            s.blocks = Skills.Scaffold.blocks(a, null);
            s.weapon = a.weapon();
            s.digTool = false;
            for (net.minecraft.item.ItemStack it : a.bag) if (it.getItem() instanceof net.minecraft.item.ItemPickaxe || it.getItem() instanceof net.minecraft.item.ItemSpade) s.digTool = true;
        }
        if (slow % 20 == 0) {
            if (Math.abs(a.posX - stillX) + Math.abs(a.posY - stillY) + Math.abs(a.posZ - stillZ) < 0.33) s.still += 20;
            else { s.still = 0; stillX = a.posX; stillY = a.posY; stillZ = a.posZ; }
        }
        if (s.threat != null) {   // the one it saw, followed every tick (it is looked for every fourth)
            if (s.threat.isDead) { s.threat = null; s.threatDist = 1e9; s.threatFuse = 0; }
            else {
                s.threatDist = a.getDistanceToEntity(s.threat);
                s.threatFuse = s.threat instanceof net.minecraft.entity.monster.EntityCreeper ? ((net.minecraft.entity.monster.EntityCreeper) s.threat).getCreeperState() : 0;
            }
        }
        float y0 = yawNow(), p0 = pitchNow();
        Vec3 from = Vec3.createVectorHelper(eyeX(), eyeY(), eyeZ());
        for (int k = 0; k < RAYS; k++) {
            float[] f = FIELD[(ray++ * 37) % FIELD.length];
            double yr = Math.toRadians(y0 + f[0]), pr = Math.toRadians(Math.max(-90, Math.min(90, p0 + f[1])));
            double vx = -Math.sin(yr) * Math.cos(pr), vy = -Math.sin(pr), vz = Math.cos(yr) * Math.cos(pr);
            MovingObjectPosition m = w.rayTraceBlocks(Vec3.createVectorHelper(from.xCoord, from.yCoord, from.zCoord),
                    Vec3.createVectorHelper(from.xCoord + vx * SIGHT, from.yCoord + vy * SIGHT, from.zCoord + vz * SIGHT), false, true, false);
            if (m == null) continue;
            Block b = w.getBlock(m.blockX, m.blockY, m.blockZ);
            seen.put(Crew.key(m.blockX, m.blockY, m.blockZ), new int[] { m.blockX, m.blockY, m.blockZ, Block.getIdFromBlock(b), w.getBlockMetadata(m.blockX, m.blockY, m.blockZ), m.sideHit, (int) t });
        }
        if ((t + a.getEntityId()) % 4 == 0) watch(w);
    }

    @SuppressWarnings("unchecked")
    private void watch(WorldServer w) {
        Senses s = senses;
        s.mobs.clear();
        s.threat = null;
        s.threatDist = 1e9;
        List<Entity> near = w.getEntitiesWithinAABBExcludingEntity(a, a.boundingBox.expand(16, 8, 16));
        List<EntityLivingBase> live = new ArrayList<EntityLivingBase>();
        for (Entity e : near) if (e instanceof EntityLivingBase && !e.isDead) live.add((EntityLivingBase) e);
        java.util.Collections.sort(live, (p, q) -> Double.compare(a.getDistanceSqToEntity(p), a.getDistanceSqToEntity(q)));
        double[] v = look();
        int checks = 0;
        for (EntityLivingBase e : live) {
            if (checks++ >= 12) break;
            double dx = e.posX - eyeX(), dy = e.posY + e.getEyeHeight() - eyeY(), dz = e.posZ - eyeZ(), d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d > 16) break;
            if (d > 1.5 && (dx * v[0] + dy * v[1] + dz * v[2]) / d < FOV) continue;   // out of the field of view (one touching it is felt)
            if (!a.canEntityBeSeen(e)) continue;
            s.mobs.add(e);
            if (s.threat == null && Guard.hostile(e)) { s.threat = e; s.threatDist = d; s.threatKind = Cases.name(e); }
        }
    }

    /** a block the senses met lately (within so many ticks): x, y, z, id, meta, face, tick; or null */
    int[] saw(int x, int y, int z, int within) {
        int[] v = seen.get(Crew.key(x, y, z));
        return v != null && now() - v[6] <= within ? v : null;
    }
}
