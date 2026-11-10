package hardelele.aiguide.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.WorldServer;

/**
 * What an agent of the open world knows (docs/rl/OPEN-WORLD.md section 3): the voxel cube round its feet from the
 * memory of what its own eyes saw (rays from the eyes, Sight.trace, in the field of view and a few blocks; the cells
 * the body touches always), the living things and the things lying about that it sees now, and its body, bag, aim,
 * the time of day and the way home. Nothing from the crew's memory, nothing the eyes never met.
 */
final class OpenEyes {
    /** the cells seen: key -> { class, tick } (the oldest go past 40 000) */
    final Map<Long, int[]> mem = new LinkedHashMap<Long, int[]>(4096, 0.75F, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, int[]> e) { return size() > 40000; }
    };

    /** the world round the agents: the base, the phase of the night, the night's number */
    static final class Ctx {
        int[] base;
        String phase = "none";   // none, prep, wave, break (the waves' cycle)
        int night;
    }

    static int classOf(WorldServer w, int x, int y, int z) {
        Block b = w.getBlock(x, y, z);
        if (b.getMaterial() == Material.air) return OpenSpec.B_AIR;
        return OpenSpec.block(MindSkills.idOf(b), false, b.getMaterial().isSolid());
    }

    /** the facing's axes on the ground: the side of the world nearest the look (forward x, z; right x, z) */
    static int[] axes(float yaw) {
        int f = ((Math.round(yaw / 90F) % 4) + 4) % 4;
        int[][] fw = { { 0, 1 }, { -1, 0 }, { 0, -1 }, { 1, 0 } };
        return new int[] { fw[f][0], fw[f][1], -fw[f][1], fw[f][0] };
    }

    /** the eyes write what they see this step into the memory; the cube from the memory: classes, seen now */
    String[] cube(Agent a, WorldServer w) {
        long t = w.getTotalWorldTime();
        int fx = MathHelper.floor_double(a.posX), fy = MathHelper.floor_double(a.posY + 0.01), fz = MathHelper.floor_double(a.posZ);
        int[] ax = axes(a.body.yawNow());
        double ex = a.body.eyeX(), ey = a.body.eyeY(), ez = a.body.eyeZ();
        double[] v = a.body.look();
        Sight.Space s = Body.space(w);
        for (int[] d : new int[][] { { 0, -1, 0 }, { 0, 2, 0 }, { 1, 0, 0 }, { -1, 0, 0 }, { 0, 0, 1 }, { 0, 0, -1 }, { 1, 1, 0 }, { -1, 1, 0 }, { 0, 1, 1 }, { 0, 1, -1 } })
            mem.put(Crew.key(fx + d[0], fy + d[1], fz + d[2]), new int[] { classOf(w, fx + d[0], fy + d[1], fz + d[2]), (int) t });   // touched
        StringBuilder cls = new StringBuilder(OpenSpec.VX * OpenSpec.VY * OpenSpec.VZ), now = new StringBuilder(OpenSpec.VX * OpenSpec.VY * OpenSpec.VZ);
        for (int iy = 0; iy < OpenSpec.VY; iy++)
            for (int iz = 0; iz < OpenSpec.VZ; iz++)
                for (int ix = 0; ix < OpenSpec.VX; ix++) {
                    int lx = ix - OpenSpec.R, lz = iz - OpenSpec.R;
                    int x = fx + lx * ax[2] + lz * ax[0], y = fy + OpenSpec.Y0 + iy, z = fz + lx * ax[3] + lz * ax[1];
                    long k = Crew.key(x, y, z);
                    boolean seen = false;
                    double cx = x + 0.5 - ex, cy = y + 0.5 - ey, cz = z + 0.5 - ez, d = Math.sqrt(cx * cx + cy * cy + cz * cz);
                    if (d <= OpenSpec.SEE && (d < 1.5 || (cx * v[0] + cy * v[1] + cz * v[2]) / d >= OpenSpec.SEE_COS)) {
                        int[] hit = Sight.trace(s, ex, ey, ez, x + 0.5, y + 0.5, z + 0.5);
                        if (hit == null || hit[0] == x && hit[1] == y && hit[2] == z) {
                            mem.put(k, new int[] { classOf(w, x, y, z), (int) t });
                            seen = true;
                        }
                    }
                    int[] m = mem.get(k);
                    cls.append((char) ('A' + (m == null ? OpenSpec.B_UNKNOWN : m[0])));
                    now.append(seen ? '1' : '0');
                }
        return new String[] { cls.toString(), now.toString() };
    }

    static String kindOf(Entity e) {
        if (e instanceof Agent) return "agent";
        if (e instanceof EntityItem) return "item";
        String n = EntityList.getEntityString(e);
        return n == null ? e.getClass().getSimpleName() : n;
    }

    /** the things seen now (and touched): nearest first, at most OpenSpec.ENTS, each OpenSpec.ENT_F numbers */
    @SuppressWarnings("unchecked")
    static float[] things(Agent a, WorldServer w) {
        int[] ax = axes(a.body.yawNow());
        double[] v = a.body.look();
        List<Entity> near = new ArrayList<Entity>();
        for (Object o : w.getEntitiesWithinAABBExcludingEntity(a, a.boundingBox.expand(16, 8, 16))) {
            Entity e = (Entity) o;
            if (e.isDead || !(e instanceof EntityLivingBase || e instanceof EntityItem) || e instanceof net.minecraftforge.common.util.FakePlayer) continue;
            near.add(e);
        }
        Collections.sort(near, (p, q) -> Double.compare(a.getDistanceSqToEntity(p), a.getDistanceSqToEntity(q)));
        float[] out = new float[OpenSpec.ENTS * OpenSpec.ENT_F];
        int n = 0;
        for (Entity e : near) {
            if (n >= OpenSpec.ENTS) break;
            double dx = e.posX - a.posX, dy = e.posY - a.posY, dz = e.posZ - a.posZ, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d > 16) break;
            double ey = e.posY + e.height * 0.5 - a.body.eyeY();
            double dd = Math.sqrt(dx * dx + ey * ey + dz * dz);
            if (d > 1.5 && ((dx * v[0] + ey * v[1] + dz * v[2]) / Math.max(1e-6, dd) < OpenSpec.SEE_COS || !a.canEntityBeSeen(e))) continue;
            int o = n * OpenSpec.ENT_F;
            out[o + OpenSpec.kind(kindOf(e))] = 1;
            int f = OpenSpec.KINDS.length;
            out[o + f] = (float) ((dx * ax[2] + dz * ax[3]) / 16);
            out[o + f + 1] = (float) ((dx * ax[0] + dz * ax[1]) / 16);
            out[o + f + 2] = (float) (dy / 16);
            out[o + f + 3] = (float) (d / 16);
            out[o + f + 4] = e instanceof EntityLivingBase ? ((EntityLivingBase) e).getHealth() / Math.max(1F, ((EntityLivingBase) e).getMaxHealth()) : 0;
            out[o + f + 5] = Guard.hostile(e) ? 1 : 0;
            n++;
        }
        return out;
    }

    static final Map<String, Integer> VI = new java.util.HashMap<String, Integer>();
    static { for (int i = 0; i < OpenSpec.VEC.size(); i++) VI.put(OpenSpec.VEC.get(i), i); }

    static void put(float[] v, String k, double x) { Integer i = VI.get(k); if (i != null) v[i] = (float) x; }

    static int itemClass(ItemStack s) { return s == null || s.getItem() == null ? 0 : OpenSpec.item(MindSkills.id(s.getItem(), 0)); }

    /** the body, the aim, the time, the way home, the bag and the hotbar */
    static float[] vector(Agent a, WorldServer w, Ctx c, OpenBody ob, int selected) {
        float[] v = new float[OpenSpec.VEC.size()];
        put(v, "health", a.getHealth() / 20.0);
        put(v, "food", Agent.hungerOn ? a.hunger.food / 20.0 : 1);
        put(v, "saturation", Agent.hungerOn ? a.hunger.saturation / 20.0 : 0.25);
        put(v, "air", a.getAir() / 300.0);
        put(v, "armor", a.getTotalArmorValue() / 20.0);
        put(v, "xp_level", Math.log1p(a.xp.level) / Math.log1p(30));
        put(v, "on_ground", a.onGround ? 1 : 0);
        put(v, "in_water", a.isInWater() ? 1 : 0);
        put(v, "in_lava", a.handleLavaMovement() ? 1 : 0);
        put(v, "burning", a.isBurning() ? 1 : 0);
        put(v, "under_water", a.isInsideOfMaterial(Material.water) ? 1 : 0);
        put(v, "collided", a.isCollidedHorizontally ? 1 : 0);
        put(v, "sneaking", a.isSneaking() ? 1 : 0);
        double yr = Math.toRadians(a.body.yawNow());
        put(v, "yaw_sin", Math.sin(yr));
        put(v, "yaw_cos", Math.cos(yr));
        put(v, "pitch", a.body.pitchNow() / 90.0);
        MovingObjectPosition m = OpenBody.ray(a, Crew.REACH, false);
        if (OpenBody.isBlock(m)) {
            put(v, "aim_" + OpenSpec.BLOCKS[classOf(w, m.blockX, m.blockY, m.blockZ)], 1);
            put(v, "aim_dist", m.hitVec.distanceTo(net.minecraft.util.Vec3.createVectorHelper(a.body.eyeX(), a.body.eyeY(), a.body.eyeZ())) / Crew.REACH);
        } else put(v, "aim_dist", 1);
        put(v, "aim_entity", OpenBody.entityOnRay(a, OpenBody.HIT_REACH) != null ? 1 : 0);
        if (ob != null && ob.digNeed > 0 && ob.digZ != Integer.MIN_VALUE) put(v, "dig_progress", Math.min(1, ob.digTicks / ob.digNeed));
        if (ob != null) put(v, "eating", ob.eatTicks / (double) OpenBody.EAT_TICKS);
        long tod = w.getWorldTime() % 24000;
        double ang = tod / 24000.0 * 2 * Math.PI;
        put(v, "day_sin", Math.sin(ang));
        put(v, "day_cos", Math.cos(ang));
        put(v, "night", tod >= 13000 && tod < 23000 ? 1 : 0);
        put(v, "phase_" + c.phase, 1);
        put(v, "night_n", c.night / 10.0);
        if (c.base != null) {
            int[] ax = axes(a.body.yawNow());
            double dx = c.base[0] + 0.5 - a.posX, dz = c.base[2] + 0.5 - a.posZ;
            put(v, "base_x", clip((dx * ax[2] + dz * ax[3]) / 32));
            put(v, "base_z", clip((dx * ax[0] + dz * ax[1]) / 32));
            put(v, "base_y", clip((c.base[1] - a.posY) / 16));
            put(v, "base_dist", Math.log1p(Math.hypot(dx, dz)) / Math.log1p(64));
        }
        put(v, "held", itemClass(a.getHeldItem()) / (double) OpenSpec.ITEMS.length);
        int[] bag = new int[OpenSpec.ITEMS.length];
        for (ItemStack s : a.bag) bag[itemClass(s)] += s.stackSize;
        for (int i = 1; i < OpenSpec.ITEMS.length; i++) put(v, "bag_" + OpenSpec.ITEMS[i], Math.log1p(bag[i]) / Math.log1p(64));
        for (int i = 0; i < OpenSpec.HOTBAR; i++) {
            ItemStack s = OpenBody.slot(a, i);
            put(v, "hot_n" + (i + 1), s == null ? 0 : Math.log1p(s.stackSize) / Math.log1p(64));
        }
        if (selected >= 0 && selected < OpenSpec.HOTBAR) put(v, "hot_sel" + (selected + 1), 1);
        return v;
    }

    static double clip(double x) { return Math.max(-2, Math.min(2, x)); }

    static JsonArray floats(float[] v) {
        JsonArray j = new JsonArray();
        for (float x : v) j.add(new JsonPrimitive(Math.round(x * 1e4) / 1e4));
        return j;
    }

    /** the whole observation of the step (the protocol's obs.<agent>) */
    JsonObject observe(Agent a, WorldServer w, Ctx c, OpenBody ob, int selected) {
        JsonObject o = new JsonObject();
        String[] cb = cube(a, w);
        o.addProperty("vox", cb[0]);
        o.addProperty("seen", cb[1]);
        o.add("vec", floats(vector(a, w, c, ob, selected)));
        o.add("ent", floats(things(a, w)));
        JsonArray hot = new JsonArray();
        for (int i = 0; i < OpenSpec.HOTBAR; i++) hot.add(new JsonPrimitive(itemClass(OpenBody.slot(a, i))));
        o.add("hot", hot);
        return o;
    }
}
