package hardelele.aiguide.client;

import java.util.List;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.material.Material;
import net.minecraft.entity.EntityLiving;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.world.WorldServer;

/**
 * The primitives of contract 3 (docs/rl/CONTRACT-v2.md 3.1): a thing of a hand slot (a block, water, lava, a torch;
 * an empty bucket, flint and steel) put or used at one of six fixed target slots (in front, its own cell, in front one
 * down, the wall's nearest hole, the nearest moat cell, at the nearest enemy), a hit at a mob or an animal, a door
 * opened or shut. Each is the body's act as a player's: it walks into reach, looks, and the game's rule decides
 * (a bucket pours only into what is not solid, as ItemBucket; a bucket fills only from a source). What it puts carries
 * its decision (RlEnv.placed); a bucket it fills carries it too (RlCauseHooks.tag).
 */
final class PrimSkills {
    private PrimSkills() { }

    static final double REACH = 4.0;

    /** a target slot: its cell, or null when there is none now */
    static int[] target(Crew c, Agent a, WorldServer w, String t) {
        int fx = MathHelper.floor_double(a.posX), fy = MathHelper.floor_double(a.boundingBox.minY), fz = MathHelper.floor_double(a.posZ);
        double yaw = Math.toRadians(a.rotationYawHead);
        double lx = -Math.sin(yaw), lz = Math.cos(yaw);
        int dx = Math.abs(lx) >= Math.abs(lz) ? (lx > 0 ? 1 : -1) : 0, dz = dx == 0 ? (lz > 0 ? 1 : -1) : 0;
        switch (t) {
            case "front": return new int[] { fx + dx, fy, fz + dz };
            case "below": return new int[] { fx, fy, fz };
            case "front_down": return new int[] { fx + dx, fy - 1, fz + dz };
            case "hole": return nearest(a, DefenseWorld.holes(w), 24);
            case "moat": return DefenseWorld.moat == null ? null : nearest(a, DefenseWorld.moat, 24);
            case "mob": {
                EntityLiving m = enemy(a, w, 16);
                return m == null ? null : new int[] { MathHelper.floor_double(m.posX), MathHelper.floor_double(m.boundingBox.minY), MathHelper.floor_double(m.posZ) };
            }
            default: return null;
        }
    }

    static int[] nearest(Agent a, List<int[]> cells, double within) {
        int[] best = null;
        double bd = within;
        for (int[] q : cells) {
            double d = Crew.dist(a, q[0] + 0.5, q[1], q[2] + 0.5);
            if (d <= bd) { bd = d; best = new int[] { q[0], q[1], q[2] }; }
        }
        return best;
    }

    /** the nearest hostile it sees within r */
    @SuppressWarnings("unchecked")
    static EntityLiving enemy(Agent a, WorldServer w, double r) {
        EntityLiving best = null;
        for (Object o : w.getEntitiesWithinAABB(EntityLiving.class, AxisAlignedBB.getBoundingBox(a.posX - r, a.posY - 8, a.posZ - r, a.posX + r, a.posY + 8, a.posZ + r))) {
            EntityLiving m = (EntityLiving) o;
            if (!Guard.hostile(m) || m.isDead || m.getHealth() <= 0 || a.getDistanceToEntity(m) > r || !a.canEntityBeSeen(m)) continue;
            if (best == null || a.getDistanceToEntity(m) < a.getDistanceToEntity(best)) best = m;
        }
        return best;
    }

    /** free to put into: air, a plant, a flowing liquid (not a source: a bucket's water would only be the same) */
    static boolean free(WorldServer w, int[] p) {
        Block b = w.getBlock(p[0], p[1], p[2]);
        if (b.getMaterial().isLiquid()) return w.getBlockMetadata(p[0], p[1], p[2]) != 0;
        return Crew.soft(w, p[0], p[1], p[2]);
    }

    static boolean water(WorldServer w, int[] p) { return w.getBlock(p[0], p[1], p[2]).getMaterial() == Material.water; }
    static boolean lava(WorldServer w, int[] p) { return w.getBlock(p[0], p[1], p[2]).getMaterial() == Material.lava; }
    static boolean source(WorldServer w, int[] p) { return w.getBlock(p[0], p[1], p[2]).getMaterial().isLiquid() && w.getBlockMetadata(p[0], p[1], p[2]) == 0; }

    static boolean door(Block b) { return b instanceof BlockDoor || b instanceof BlockFenceGate; }

    /** the raw facts of the target slots (and the hand's blocks, the buckets) */
    static void facts(Map<String, Double> r, Crew c, Agent a, WorldServer w) {
        for (String t : RlSpec.TARGETS) {
            int[] p = target(c, a, w, t);
            r.put("tgt_" + t, p == null ? 0.0 : 1.0);
            r.put("tgt_" + t + "_dist", p == null ? 16 : Crew.dist(a, p[0] + 0.5, p[1], p[2] + 0.5));
            if (p == null) continue;
            r.put("tgt_" + t + "_free", free(w, p) ? 1.0 : 0.0);
            r.put("tgt_" + t + "_water", water(w, p) ? 1.0 : 0.0);
            r.put("tgt_" + t + "_lava", lava(w, p) ? 1.0 : 0.0);
            r.put("tgt_" + t + "_water_src", water(w, p) && source(w, p) ? 1.0 : 0.0);
            r.put("tgt_" + t + "_lava_src", lava(w, p) && source(w, p) ? 1.0 : 0.0);
        }
        int[] f = target(c, a, w, "front");
        r.put("tgt_front_door", door(w.getBlock(f[0], f[1], f[2])) || door(w.getBlock(f[0], f[1] + 1, f[2])) ? 1.0 : 0.0);
        Map<String, Integer> bag = MindSkills.bag(a);
        String mat = Defense.material("any", bag);
        r.put("slot_block", mat == null ? 0.0 : Defense.count(bag, mat));
        r.put("buckets", (double) Skills.count(a.bag, Items.bucket, -1) + Skills.count(a.bag, Items.water_bucket, -1) + Skills.count(a.bag, Items.lava_bucket, -1));
    }

    static MindSkills.Skill make(Crew c, Agent a, WorldServer w, Mind.Call call) {
        switch (call.tool) {
            case "place_at": return place(c, a, w, call.arg("slot"), call.arg("target"));
            case "use_at": return use(c, a, w, call.arg("slot"), call.arg("target"));
            case "attack": return attack(c, a, w, call.arg("target"));
            case "open": return open(c, a, w, call.arg("target"));
            default: return null;
        }
    }

    // ---- place ------------------------------------------------------------------------------------------------------

    static MindSkills.Skill place(Crew c, Agent a, WorldServer w, String slot, String t) {
        int[] p = target(c, a, w, t);
        if (p == null) return MindSkills.failed("NOT_SEEN", "no " + t + " target now");
        if (!free(w, p)) return MindSkills.failed("OCCUPIED", t + " at " + p[0] + " " + p[1] + " " + p[2] + " is not free");
        if (slot.equals("water") || slot.equals("lava")) {
            Item full = slot.equals("water") ? Items.water_bucket : Items.lava_bucket;
            if (Skills.count(a.bag, full, -1) == 0) return MindSkills.failed("NO_ITEMS", "no " + MindSkills.id(full, 0) + " in the bag");
            return new Pour(p, full);
        }
        Item item;
        int meta = 0;
        if (slot.equals("torch")) item = Item.getItemFromBlock(Blocks.torch);
        else {
            String mat = Defense.material("any", MindSkills.bag(a));
            Object[] it = mat == null ? null : MindSkills.item(mat);
            if (it == null) return MindSkills.failed("NO_ITEMS", "no block in the bag to put");
            item = (Item) it[0];
            meta = Math.max(0, (Integer) it[1]);
        }
        if (Skills.count(a.bag, item, -1) == 0) return MindSkills.failed("NO_ITEMS", "no " + MindSkills.id(item, meta) + " in the bag");
        final int[] at = p;
        final Crew.Task put = new Skills.PlaceFrom(p[0], p[1], p[2], item, meta);
        MindSkills.Skill s = new MindSkills.Skill() {
            Result run(Agent ag, WorldServer ww) {
                Result r = put.step(ag, ww);
                if (r == Result.OK) detail = "put at " + at[0] + " " + at[1] + " " + at[2];
                return r;
            }
        };
        s.lease = 20 * 30;
        return s;
    }

    /** a bucket poured into a cell in reach that the eyes see (ItemBucket.tryPlaceContainedLiquid: not into what is solid) */
    static final class Pour extends MindSkills.Skill {
        final int[] p;
        final Item full;
        Crew.GoTo walk;
        int t;
        Pour(int[] p, Item full) { this.p = p; this.full = full; lease = 20 * 30; }

        Result run(Agent a, WorldServer w) {
            if (++t > 20 * 25) { reason = "NO_PATH"; detail = "could not get within reach of " + p[0] + " " + p[1] + " " + p[2]; return Result.NO_PATH; }
            double d = Crew.eyeDist(a, p[0], p[1], p[2]);
            if (d > REACH) {
                if (walk == null) walk = new Crew.GoTo(p[0] + 0.5, p[1], p[2] + 0.5, 2.5);
                Result r = walk.step(a, w);
                if (r == Result.RUNNING) return r;
                walk = null;
                if (Crew.eyeDist(a, p[0], p[1], p[2]) > REACH) { reason = "NO_PATH"; detail = "no way to within reach"; return Result.NO_PATH; }
            }
            a.body.stop();
            Crew.face(a, p[0] + 0.5, p[1] + 0.2, p[2] + 0.5);
            if (!free(w, p)) { reason = "OCCUPIED"; detail = "the cell is taken now"; return Result.BLOCKED; }
            ItemStack b = null;
            for (ItemStack s : a.bag) if (s.getItem() == full) { b = s; break; }
            if (b == null) { reason = "NO_ITEMS"; detail = "the bucket is gone"; return Result.NO_ITEMS; }
            a.body.hold(b);
            a.swingItem();
            w.setBlock(p[0], p[1], p[2], full == Items.water_bucket ? Blocks.flowing_water : Blocks.flowing_lava, 0, 3);
            a.bag.remove(b);
            a.bag.add(new ItemStack(Items.bucket));
            a.body.hold(Skills.bestHeld(a));
            RlEnv.placed(a, w, p[0], p[1], p[2]);
            Ledger.act(a, "pour " + MindSkills.id(full, 0));
            detail = "poured " + (full == Items.water_bucket ? "water" : "lava") + " at " + p[0] + " " + p[1] + " " + p[2];
            return Result.OK;
        }
    }

    // ---- use: a bucket filled, a fire lit ---------------------------------------------------------------------------

    static MindSkills.Skill use(Crew c, Agent a, WorldServer w, String slot, String t) {
        final int[] p = target(c, a, w, t);
        if (p == null) return MindSkills.failed("NOT_SEEN", "no " + t + " target now");
        if (slot.equals("bucket")) {
            if (Skills.count(a.bag, Items.bucket, -1) == 0) return MindSkills.failed("NO_ITEMS", "no empty bucket in the bag");
            if (!source(w, p)) return MindSkills.failed("GONE", "no source of water or lava at " + t);
        } else if (slot.equals("fire")) {
            if (Skills.count(a.bag, Items.flint_and_steel, -1) == 0) return MindSkills.failed("NO_ITEMS", "no flint and steel in the bag");
            if (!free(w, p) || !w.getBlock(p[0], p[1] - 1, p[2]).isNormalCube()) return MindSkills.failed("OCCUPIED", "fire needs a free cell over a full block");
        } else return MindSkills.failed("BAD_ARG", slot);
        final boolean bucket = slot.equals("bucket");
        MindSkills.Skill s = new MindSkills.Skill() {
            Crew.GoTo walk;
            int t;
            Result run(Agent ag, WorldServer ww) {
                if (++t > 20 * 25) { reason = "NO_PATH"; return Result.NO_PATH; }
                if (Crew.eyeDist(ag, p[0], p[1], p[2]) > REACH) {
                    if (walk == null) walk = new Crew.GoTo(p[0] + 0.5, p[1], p[2] + 0.5, 2.5);
                    Result r = walk.step(ag, ww);
                    if (r == Result.RUNNING) return r;
                    walk = null;
                    if (Crew.eyeDist(ag, p[0], p[1], p[2]) > REACH) { reason = "NO_PATH"; detail = "no way to within reach"; return Result.NO_PATH; }
                }
                ag.body.stop();
                Crew.face(ag, p[0] + 0.5, p[1] + 0.5, p[2] + 0.5);
                ag.swingItem();
                if (bucket) {
                    if (!source(ww, p)) { reason = "GONE"; detail = "the source is gone"; return Result.GONE; }
                    boolean isWater = water(ww, p);
                    ItemStack empty = null;
                    for (ItemStack x : ag.bag) if (x.getItem() == Items.bucket) { empty = x; break; }
                    if (empty == null) { reason = "NO_ITEMS"; return Result.NO_ITEMS; }
                    ww.setBlockToAir(p[0], p[1], p[2]);
                    Skills.take(ag, Items.bucket, -1, 1, Ledger.Way.INGREDIENT);
                    ItemStack full = new ItemStack(isWater ? Items.water_bucket : Items.lava_bucket);
                    RlEnv.made(ag, full);
                    ag.bag.add(full);
                    detail = "filled a bucket with " + (isWater ? "water" : "lava");
                    return Result.OK;
                }
                if (!free(ww, p)) { reason = "OCCUPIED"; return Result.BLOCKED; }
                ItemStack fs = null;
                for (ItemStack x : ag.bag) if (x.getItem() == Items.flint_and_steel) { fs = x; break; }
                if (fs == null) { reason = "NO_ITEMS"; return Result.NO_ITEMS; }
                ag.body.hold(fs);
                ww.playSoundEffect(p[0] + 0.5, p[1] + 0.5, p[2] + 0.5, "fire.ignite", 1.0F, ag.getRNG().nextFloat() * 0.4F + 0.8F);
                ww.setBlock(p[0], p[1], p[2], Blocks.fire);
                fs.damageItem(1, ag);
                if (fs.stackSize <= 0) ag.bag.remove(fs);
                ag.body.hold(Skills.bestHeld(ag));
                RlEnv.placed(ag, ww, p[0], p[1], p[2]);
                detail = "lit a fire at " + p[0] + " " + p[1] + " " + p[2];
                return Result.OK;
            }
        };
        s.lease = 20 * 30;
        return s;
    }

    // ---- attack, open -----------------------------------------------------------------------------------------------

    static MindSkills.Skill attack(Crew c, Agent a, WorldServer w, String t) {
        EntityLiving e;
        if (t.equals("animal")) {
            List<EntityLiving> l = RlSkills.animals(a, w, RlSkills.SEE);
            e = l.isEmpty() ? null : l.get(0);
        } else e = enemy(a, w, 16);
        if (e == null) return MindSkills.failed(t.equals("animal") ? "NO_ANIMAL" : "NO_ENEMY", "no " + t + " in sight");
        RlSkills.Hunt h = new RlSkills.Hunt(e);
        h.lease = 20 * 50;
        return h;
    }

    static MindSkills.Skill open(Crew c, Agent a, WorldServer w, String t) {
        int[] p;
        if (t.equals("gate")) p = RlWorld.now.door;
        else {
            int[] f = target(c, a, w, "front");
            p = door(w.getBlock(f[0], f[1], f[2])) ? f : door(w.getBlock(f[0], f[1] + 1, f[2])) ? new int[] { f[0], f[1] + 1, f[2] } : null;
        }
        if (p == null || !door(w.getBlock(p[0], p[1], p[2]))) return MindSkills.failed("NO_GATE", "no door or fence gate there");
        final int[] at = p;
        MindSkills.Skill s = new MindSkills.Skill() {
            Crew.GoTo walk;
            int t;
            Result run(Agent ag, WorldServer ww) {
                if (++t > 20 * 25) { reason = "NO_PATH"; return Result.NO_PATH; }
                if (Crew.eyeDist(ag, at[0], at[1], at[2]) > REACH) {
                    if (walk == null) walk = new Crew.GoTo(at[0] + 0.5, at[1], at[2] + 0.5, 2.5);
                    Result r = walk.step(ag, ww);
                    if (r == Result.RUNNING) return r;
                    walk = null;
                }
                Result r = ag.body.activate(ww, at[0], at[1], at[2]);
                if (r == Result.OK) detail = "used the door at " + at[0] + " " + at[1] + " " + at[2];
                return r;
            }
        };
        s.lease = 20 * 30;
        return s;
    }
}
