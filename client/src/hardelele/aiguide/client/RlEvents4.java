package hardelele.aiguide.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.pathfinding.PathEntity;
import net.minecraft.pathfinding.PathPoint;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.WorldServer;

/**
 * The game's events of the reward v4 that need the world looked at (docs/rl/REWARD-v4.md, rl/events.json):
 * <ul>
 * <li>{@code path_changed}: once a second each hostile going for an agent or the base has its route (the game's path
 * finder, as its navigator would ask) to its goal; when a block the crew put or dug since lies on the last route
 * (within a block of a point of it), the route is asked again: no way through where there was one - {@code blocked};
 * a longer way - {@code detour} with the delay at the mob's own pace; a mob come down into a cell the crew dug -
 * {@code fell}; a mob that has not moved for 3 s beside a block the crew put on its way - {@code stuck}. {@code by}: the
 * decisions that put or dug those cells; {@code on_route}: the cell lay on its route.</li>
 * <li>{@code attack_failed}: an arrow aimed at an agent that stopped in a block before it ({@code cover}, by the
 * decision that put the block) or flew by while the agent was not hurt ({@code moved}); a mob in striking distance
 * with a block on the line to the agent ({@code cover}); under or over the agent with no block between and no blow
 * landed ({@code reach}, by the block it stands on if the crew put it); one that was in reach a second ago, the agent
 * out of it now unhurt ({@code moved}). At most once in 5 s a mob, agent and reason.</li>
 * <li>{@code found}: an agent's first sight of an ore (Exploration's finds); the ore mined later carries it as the
 * scale's {@code res} (the scout's credit).</li>
 * <li>{@code seen}: the agents within 16 whose eyes have a clear ray (no solid block) to the mob or to the block.</li>
 * </ul>
 * Nothing here pays: RlReward4 reads the events.
 */
final class RlEvents4 {
    static final boolean DEBUG = Boolean.getBoolean("aiguide.rl.events4.debug");
    final List<String> trace = new ArrayList<String>();   // (DEBUG: what it looked at, for the stage's answer)
    static final double SEE = 16, REACH = 2.5, AGAIN_S = 5, STILL_S = 3;

    final RlEnv env;
    /** the crew's block changes since the last look: x, y, z, decision, 1 put / 0 dug */
    final List<long[]> changes = new ArrayList<long[]>();
    final Map<Integer, Route> routes = new HashMap<Integer, Route>();
    final Map<String, Double> sent = new HashMap<String, Double>();
    /** mob -> {agent's skin, distance, t} a second ago (the melee's moved) */
    final Map<Integer, double[]> close = new HashMap<Integer, double[]>();
    /** arrow -> {agent's skin, t, shooter, x, y, z, vx, vy, vz} */
    final Map<Integer, double[]> arrows = new HashMap<Integer, double[]>();
    final Set<Integer> ended = new HashSet<Integer>();
    /** found res (a block at a place) -> the scale it gives; mined: agent|key -> its res, for the next scale event */
    final Map<Long, String[]> finds = new HashMap<Long, String[]>();   // the place -> {res, key}
    final Map<String, ArrayDeque<String>> mined = new HashMap<String, ArrayDeque<String>>();
    int pathChanged, attackFailed, found;   // the episode's counts (info)

    RlEvents4(RlEnv env) { this.env = env; }

    static final class Route {
        Set<Long> cells = new HashSet<Long>();
        boolean reaches;
        int len;
        double x, y, z, t, stillSince, pace = 2.5;
        int fy;
        boolean stuckSent;
        Set<Long> fellSent = new HashSet<Long>();
    }

    double now() { return env.ticks / 20.0; }

    // ---- the crew's block changes ------------------------------------------------------------------------------

    void changed(int x, int y, int z, RlCause.Link by, boolean put) {
        if (by == null) return;
        changes.add(new long[] { x, y, z, by.id, put ? 1 : 0 });
        if (changes.size() > 4096) changes.remove(0);
    }

    // ---- once a second: routes and blows ----------------------------------------------------------------------

    /** the mob's goal: the agent it goes for, else the base's centre (a wave's mob); null: none */
    static double[] goal(EntityLiving mob, int[] centre, boolean wave) {
        Entity t = mob.getAttackTarget() != null ? mob.getAttackTarget() : mob instanceof EntityCreature ? ((EntityCreature) mob).getEntityToAttack() : null;
        if (t instanceof Agent && !t.isDead) return new double[] { t.posX, t.boundingBox.minY, t.posZ, ((Agent) t).skin() };
        if (wave && centre != null) return new double[] { centre[0] + 0.5, centre[1], centre[2] + 0.5, -1 };
        return null;
    }

    List<EntityLiving> hostiles(Crew c, WorldServer w) {
        Set<EntityLiving> out = new java.util.LinkedHashSet<EntityLiving>(Waves.enemies());
        for (Agent a : c.agents) {
            if (a.isDead) continue;
            for (Object o : w.getEntitiesWithinAABB(EntityLiving.class, a.boundingBox.expand(24, 8, 24))) {
                EntityLiving m = (EntityLiving) o;
                if (m != a && !m.isDead && !(m instanceof Agent) && Guard.hostile(m)) out.add(m);
            }
        }
        return new ArrayList<EntityLiving>(out);
    }

    void second(Crew c, WorldServer w) {
        double t = now();
        int[] centre = DefenseWorld.centre(c, w);
        boolean wave = env.sc != null && env.sc.waves != null;
        List<long[]> ch = new ArrayList<long[]>(changes);
        changes.clear();
        Set<Integer> alive = new HashSet<Integer>();
        for (EntityLiving mob : hostiles(c, w)) {
            alive.add(mob.getEntityId());
            if (DEBUG && trace.size() < 40) trace.add("t " + (float) t + " " + mob.getClass().getSimpleName() + " at " + (float) mob.posX + " " + (float) mob.posY + " " + (float) mob.posZ
                    + " holds " + mob.getHeldItem() + " target " + mob.getAttackTarget() + " agent0 " + (c.agents.isEmpty() ? "" : (float) c.agents.get(0).posX + " " + (float) c.agents.get(0).posY + " " + (float) c.agents.get(0).posZ));
            double[] g = goal(mob, centre, wave && Waves.enemies().contains(mob));
            if (g == null) { routes.remove(mob.getEntityId()); continue; }
            route(c, w, mob, g, ch, t);
            if (g[3] >= 0) {
                Agent tg = agent(c, (int) g[3]);
                if (tg != null && !Waves.enemies().contains(mob) && env.aimed4.add(mob.getEntityId() + "|" + RlEnv.id(tg))) {   // (a wave's mobs: RlEnv.mobs4)
                    JsonObject o = env.ev4("threat");   // a mob outside a wave took an agent for its target
                    o.addProperty("mob", mob.getEntityId());
                    o.addProperty("kind", mob.getClass().getSimpleName());
                    o.addProperty("target", RlEnv.id(tg));
                    o.addProperty("dmg", 3.0);
                }
                blows(c, w, mob, tg, t);
            }
        }
        routes.keySet().retainAll(alive);
        close.keySet().retainAll(alive);
        for (Iterator<Map.Entry<String, Double>> it = sent.entrySet().iterator(); it.hasNext(); ) if (t - it.next().getValue() > 60) it.remove();
    }

    static Agent agent(Crew c, int skin) {
        for (Agent a : c.agents) if (a.skin() == skin) return a;
        return null;
    }

    void route(Crew c, WorldServer w, EntityLiving mob, double[] g, List<long[]> ch, double t) {
        Route r0 = routes.get(mob.getEntityId());
        int fy = MathHelper.floor_double(mob.boundingBox.minY + 0.1);
        if (r0 != null) {   // its pace when it moves; still since when
            double moved = Math.hypot(mob.posX - r0.x, mob.posZ - r0.z);
            double dt = Math.max(1e-3, t - r0.t);
            if (moved > 0.3) r0.stillSince = t;
            if (moved > 0.5) r0.pace = 0.8 * r0.pace + 0.2 * Math.min(6, moved / dt);
        }
        Route r1 = path(mob, g);
        if (r1 == null) {   // not on the ground (falling, swimming): the last route kept, the fall looked at
            if (r0 != null) fell(c, w, mob, r0, fy, t);
            return;
        }
        r1.t = t;
        r1.x = mob.posX;
        r1.y = mob.posY;
        r1.z = mob.posZ;
        r1.fy = fy;
        if (r0 == null) { r1.stillSince = t; routes.put(mob.getEntityId(), r1); return; }
        r1.stillSince = r0.stillSince;
        r1.pace = r0.pace;
        r1.stuckSent = r0.stuckSent && t - r0.stillSince >= STILL_S;
        r1.fellSent = r0.fellSent;
        fell(c, w, mob, r0, fy, t);
        // the crew's changes on its last route
        List<Long> by = new ArrayList<Long>();
        for (long[] k : ch) if (near(r0.cells, (int) k[0], (int) k[1], (int) k[2]) && !by.contains(k[3])) by.add(k[3]);
        if (!by.isEmpty()) {
            String effect = null;
            double delay = 0;
            if (r0.reaches && !r1.reaches) effect = "blocked";
            else if (r0.reaches && r1.reaches) {   // longer than the last route less the way come since
                double left = Math.max(0, r0.len - Math.hypot(mob.posX - r0.x, mob.posZ - r0.z));
                if (r1.len > left + 1) { effect = "detour"; delay = (r1.len - left) / Math.max(0.5, r0.pace); }
            }
            if (effect != null) {
                List<int[]> at = new ArrayList<int[]>();
                for (long[] k : ch) if (by.contains(k[3])) at.add(new int[] { (int) k[0], (int) k[1], (int) k[2] });
                emitPath(c, w, mob, effect, delay, true, by, at);
            }
        }
        // stuck: no step for 3 s beside a block the crew put that lies on its way
        if (!r1.stuckSent && t - r1.stillSince >= STILL_S && !r1.reaches) {
            for (int dx = -1; dx <= 1 && !r1.stuckSent; dx++)
                for (int dz = -1; dz <= 1 && !r1.stuckSent; dz++)
                    for (int dy = 0; dy <= 1 && !r1.stuckSent; dy++) {
                        int x = MathHelper.floor_double(mob.posX) + dx, y = fy + dy, z = MathHelper.floor_double(mob.posZ) + dz;
                        if ((dx == 0 && dz == 0) || !w.getBlock(x, y, z).getMaterial().isSolid()) continue;
                        RlCause.Link l = env.cause.at(RlCause.key(x, y, z));
                        if (l == null) continue;
                        double gx = g[0] - mob.posX, gz = g[2] - mob.posZ;
                        boolean onWay = r0.cells.contains(RlCause.key(x, fy, z)) || dx * gx + dz * gz > 0;
                        List<Long> b = new ArrayList<Long>();
                        b.add(l.id);
                        emitPath(c, w, mob, "stuck", 0, onWay, b, java.util.Collections.singletonList(new int[] { x, y, z }));
                        r1.stuckSent = true;
                    }
        }
        routes.put(mob.getEntityId(), r1);
    }

    /** come down a block or more since the last look into a cell the crew dug (or onto one: the pit's floor) */
    void fell(Crew c, WorldServer w, EntityLiving mob, Route r0, int fy, double t) {
        if (fy >= r0.fy) return;
        int x = MathHelper.floor_double(mob.posX), z = MathHelper.floor_double(mob.posZ);
        for (int y = fy; y <= r0.fy; y++) {
            long k = RlCause.key(x, y, z);
            RlCause.Link d = env.cause.dugAt(k);
            if (d == null || !r0.fellSent.add(k)) continue;
            List<Long> b = new ArrayList<Long>();
            b.add(d.id);
            emitPath(c, w, mob, "fell", 0, near(r0.cells, x, y, z) || r0.cells.isEmpty(), b, java.util.Collections.singletonList(new int[] { x, y, z }));
            return;
        }
    }

    static boolean near(Set<Long> cells, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                for (int dy = -2; dy <= 1; dy++) if (cells.contains(RlCause.key(x + dx, y + dy, z + dz))) return true;
        return false;
    }

    /** the mob's route to its goal as its navigator would find it (null: it cannot ask now, off the ground) */
    static Route path(EntityLiving mob, double[] g) {
        if (!mob.onGround) return null;
        PathEntity p = RlEngine.pathTo(mob, g[0], g[1], g[2]);
        Route r = new Route();
        if (p == null || p.getCurrentPathLength() == 0) { r.reaches = false; r.len = 0; return r; }
        r.len = p.getCurrentPathLength();
        for (int i = 0; i < r.len; i++) {
            PathPoint q = p.getPathPointFromIndex(i);
            r.cells.add(RlCause.key(q.xCoord, q.yCoord, q.zCoord));
        }
        PathPoint e = p.getFinalPathPoint();
        int gx = MathHelper.floor_double(g[0]), gy = MathHelper.floor_double(g[1]), gz = MathHelper.floor_double(g[2]);
        r.reaches = Math.abs(e.xCoord - gx) + Math.abs(e.zCoord - gz) <= 2 && Math.abs(e.yCoord - gy) <= 2;
        return r;
    }

    void blows(Crew c, WorldServer w, EntityLiving mob, Agent a, double t) {
        if (a == null || a.isDead) return;
        double d = Math.hypot(mob.posX - a.posX, mob.posZ - a.posZ), dy = a.boundingBox.minY - mob.boundingBox.minY;
        RlEnv.Mine m = env.mine(a);
        boolean hurt = m.lastHurt >= t - 1.0;
        double[] was = close.get(mob.getEntityId());
        close.put(mob.getEntityId(), new double[] { a.skin(), d, t });
        if (d <= REACH) {
            int[] b = between(w, mob.posX, mob.posY + mob.getEyeHeight(), mob.posZ, a.posX, a.posY + a.height * 0.5, a.posZ);
            if (b != null) {
                RlCause.Link l = env.cause.at(RlCause.key(b[0], b[1], b[2]));
                failed(c, w, mob, a, "cover", l == null ? null : l.id, b, t);
            } else if (d <= 1.5 && Math.abs(dy) >= 1.8 && !hurt) {   // under or over it: the blow does not reach
                int fx = MathHelper.floor_double(a.posX), fz = MathHelper.floor_double(a.posZ), fy = MathHelper.floor_double(a.boundingBox.minY + 0.1);
                RlCause.Link l = dy > 0 ? env.cause.at(RlCause.key(fx, fy - 1, fz)) : null;
                failed(c, w, mob, a, "reach", l == null ? null : l.id, null, t);
            }
        } else if (was != null && (int) was[0] == a.skin() && was[1] <= 2.0 && d > 3.0 && !hurt && t - was[2] <= 1.5) {
            failed(c, w, mob, a, "moved", null, null, t);
        }
    }

    /** the first solid block on the line between two points, or null */
    static int[] between(WorldServer w, double x0, double y0, double z0, double x1, double y1, double z1) {
        MovingObjectPosition h = w.rayTraceBlocks(Vec3.createVectorHelper(x0, y0, z0), Vec3.createVectorHelper(x1, y1, z1));
        if (h == null) return null;   // (rayTraceBlocks: a block or nothing)
        if (!w.getBlock(h.blockX, h.blockY, h.blockZ).getMaterial().isSolid()) return null;
        return new int[] { h.blockX, h.blockY, h.blockZ };
    }

    // ---- arrows (every other tick) --------------------------------------------------------------------------

    int arrowLooks, arrowEntities;

    void arrows(Crew c, WorldServer w) {
        double t = now();
        arrowLooks++;
        for (Object o : w.loadedEntityList) {
            if (DEBUG && o instanceof EntityArrow && trace.size() < 40) trace.add("entity arrow " + ((EntityArrow) o).getEntityId() + " by " + ((EntityArrow) o).shootingEntity);
            if (!(o instanceof EntityArrow)) continue;
            arrowEntities++;
            EntityArrow ar = (EntityArrow) o;
            if (arrows.containsKey(ar.getEntityId()) || ended.contains(ar.getEntityId()) || ar.isDead || !(ar.shootingEntity instanceof EntityLiving) || ar.shootingEntity instanceof Agent
                    || !Guard.hostile((EntityLiving) ar.shootingEntity)) continue;
            double sp = Math.sqrt(ar.motionX * ar.motionX + ar.motionY * ar.motionY + ar.motionZ * ar.motionZ);
            if (sp < 0.3) continue;   // (in the ground already)
            if (DEBUG && trace.size() < 40) trace.add("arrow " + ar.getEntityId() + " at " + (float) ar.posX + " " + (float) ar.posY + " " + (float) ar.posZ + " v " + (float) ar.motionX + " " + (float) ar.motionY + " " + (float) ar.motionZ);
            for (Agent a : c.agents) {
                if (a.isDead || a.getDistanceToEntity(ar) > 24) continue;
                if (aimed(ar.posX, ar.posY, ar.posZ, ar.motionX, ar.motionY, ar.motionZ, a.boundingBox.expand(0.3, 0.3, 0.3))) {
                    arrows.put(ar.getEntityId(), new double[] { a.skin(), t, ar.shootingEntity.getEntityId(), Double.NaN, Double.NaN, Double.NaN, ar.motionX, ar.motionY, ar.motionZ });   // (its place: from the next look)
                    break;
                }
            }
        }
        for (Iterator<Map.Entry<Integer, double[]>> it = arrows.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, double[]> e = it.next();
            Entity ar = w.getEntityByID(e.getKey());
            double[] v = e.getValue();
            // in a block: it stands still (an arrow stuck keeps the motion of its last step: the motion does not tell)
            boolean ground = ar != null && !ar.isDead && Math.abs(ar.posX - v[3]) + Math.abs(ar.posY - v[4]) + Math.abs(ar.posZ - v[5]) < 1e-3;
            boolean over = ar == null || ar.isDead || ground || t - v[1] > 3;
            if (!over) { v[3] = ar.posX; v[4] = ar.posY; v[5] = ar.posZ; if (Math.abs(ar.motionX) + Math.abs(ar.motionZ) > 0.05) { v[6] = ar.motionX; v[7] = ar.motionY; v[8] = ar.motionZ; } continue; }
            it.remove();
            ended.add(e.getKey());   // (stuck, it keeps its last motion: not taken for a new one)
            Agent a = agent(c, (int) v[0]);
            if (a == null || a.isDead) continue;
            Entity shooter = w.getEntityByID((int) v[2]);
            if (env.mine(a).lastHurt >= v[1]) continue;   // it hit (or something did)
            int[] cover = null;
            if (ground && ar.getDistanceToEntity(a) > 1.0
                    && (a.posX - ar.posX) * v[6] + (a.posZ - ar.posZ) * v[8] > 0) {   // stopped short of it (not gone past, into the ground behind)
                double s = Math.sqrt(v[6] * v[6] + v[7] * v[7] + v[8] * v[8]) + 1e-9;
                int bx = MathHelper.floor_double(ar.posX + v[6] / s * 0.4), by = MathHelper.floor_double(ar.posY + v[7] / s * 0.4), bz = MathHelper.floor_double(ar.posZ + v[8] / s * 0.4);
                if (w.getBlock(bx, by, bz).getMaterial().isSolid() && by >= MathHelper.floor_double(a.boundingBox.minY + 0.1)) cover = new int[] { bx, by, bz };   // (a block it stood up to, not the floor)
            }
            if (DEBUG && trace.size() < 60) trace.add("arrow end t " + (float) t + " ground " + ground + " at " + (ar == null ? "gone" : (float) ar.posX + " " + (float) ar.posY + " " + (float) ar.posZ)
                    + " v " + (float) v[6] + " " + (float) v[7] + " " + (float) v[8] + " agent " + (float) a.posX + " " + (float) a.boundingBox.minY + " " + (float) a.posZ + " cover " + (cover == null ? "-" : cover[0] + " " + cover[1] + " " + cover[2]));
            if (cover != null) {
                RlCause.Link l = env.cause.at(RlCause.key(cover[0], cover[1], cover[2]));
                failed(c, w, shooter, (int) v[2], a, "cover", l == null ? null : l.id, cover, t);
            } else failed(c, w, shooter, (int) v[2], a, "moved", null, null, t);
        }
    }

    /**
     * the arrow's flight as the game moves it (EntityArrow.onUpdate: the step, then x0.99 and 0.05 down a tick) meets
     * the box within 60 ticks, blocks left out (an arrow a block will stop is aimed all the same)
     */
    static boolean aimed(double x, double y, double z, double vx, double vy, double vz, net.minecraft.util.AxisAlignedBB b) {
        for (int t = 0; t < 60; t++) {
            if (RlEnv.aimed(x, y, z, vx, vy, vz, b) && segment(x, y, z, vx, vy, vz, b)) return true;
            x += vx; y += vy; z += vz;
            vx *= 0.99; vy = vy * 0.99 - 0.05; vz *= 0.99;
            if (y < b.minY - 8) return false;
        }
        return false;
    }

    /** the tick's straight step from p by v meets the box (the slab test on [0, 1]) */
    static boolean segment(double px, double py, double pz, double vx, double vy, double vz, net.minecraft.util.AxisAlignedBB b) {
        double t0 = 0, t1 = 1;
        double[][] s = { { px, vx, b.minX, b.maxX }, { py, vy, b.minY, b.maxY }, { pz, vz, b.minZ, b.maxZ } };
        for (double[] k : s) {
            if (Math.abs(k[1]) < 1e-9) { if (k[0] < k[2] || k[0] > k[3]) return false; continue; }
            double a = (k[2] - k[0]) / k[1], c = (k[3] - k[0]) / k[1];
            t0 = Math.max(t0, Math.min(a, c));
            t1 = Math.min(t1, Math.max(a, c));
            if (t0 > t1) return false;
        }
        return true;
    }

    // ---- the events ---------------------------------------------------------------------------------------------

    void failed(Crew c, WorldServer w, EntityLiving mob, Agent a, String reason, Long by, int[] block, double t) {
        failed(c, w, mob, mob.getEntityId(), a, reason, by, block, t);
    }

    void failed(Crew c, WorldServer w, Entity mob, int mobId, Agent a, String reason, Long by, int[] block, double t) {
        String k = mobId + "|" + a.skin() + "|" + reason;
        Double last = sent.get(k);
        if (last != null && t - last < AGAIN_S) return;
        sent.put(k, t);
        JsonObject o = env.ev4("attack_failed");
        o.addProperty("mob", mobId);
        o.addProperty("target", RlEnv.id(a));
        o.addProperty("reason", reason);
        if (by != null) {
            JsonArray b = new JsonArray();
            b.add(new JsonPrimitive(by));
            o.add("by", b);
        }
        // the cover: those who see the block; the others: those who see the mob (the target among them if it does)
        o.add("seen", block != null ? seenBlock(c, w, block) : seenMob(c, w, mob));
        attackFailed++;
    }

    void emitPath(Crew c, WorldServer w, EntityLiving mob, String effect, double delay, boolean onRoute, List<Long> by, List<int[]> at) {
        JsonObject o = env.ev4("path_changed");
        o.addProperty("mob", mob.getEntityId());
        o.addProperty("effect", effect);
        o.addProperty("delay_s", Math.round(delay * 100) / 100.0);
        o.addProperty("on_route", onRoute);
        JsonArray b = new JsonArray();
        for (Long d : by) b.add(new JsonPrimitive(d));
        o.add("by", b);
        JsonArray s = seenMob(c, w, mob);
        Set<String> have = new HashSet<String>();
        for (int i = 0; i < s.size(); i++) have.add(s.get(i).getAsString());
        for (int[] p : at) {
            JsonArray sb = seenBlock(c, w, p);
            for (int i = 0; i < sb.size(); i++) if (have.add(sb.get(i).getAsString())) s.add(sb.get(i));
        }
        o.add("seen", s);
        pathChanged++;
    }

    /** an agent's first sight of an ore: its find (res: the block at its place) */
    void found(Agent a, int x, int y, int z, String block, String key, RlCause.Link decision) {
        if (key == null || decision == null) return;
        String res = block.replace("minecraft:", "") + "@" + x + "," + y + "," + z;
        long pos = RlCause.key(x, y, z);
        if (finds.containsKey(pos)) return;
        finds.put(pos, new String[] { res, key });
        JsonObject o = env.ev4("found");
        o.addProperty("agent", RlEnv.id(a));
        o.addProperty("decision", decision.id);
        o.addProperty("res", res);
        o.addProperty("key", key);
        o.addProperty("amount", 1.0);
        found++;
    }

    /** a found ore broken: the agent's next gain of its scale is from it */
    void minedAt(Agent a, long pos) {
        String[] f = finds.get(pos);
        if (f == null) return;
        String k = RlEnv.id(a) + "|" + f[1];
        ArrayDeque<String> q = mined.get(k);
        if (q == null) mined.put(k, q = new ArrayDeque<String>());
        q.add(f[0]);
    }

    /** the res a scale's gain comes from (a found ore this agent broke), or null */
    String resFor(String agent, String key) {
        ArrayDeque<String> q = mined.get(agent + "|" + key);
        return q == null || q.isEmpty() ? null : q.poll();
    }

    // ---- seen: Sight's ray ------------------------------------------------------------------------------------

    static boolean clear(WorldServer w, Agent a, double x, double y, double z, int[] block) {
        double ex = a.posX, ey = a.posY + a.getEyeHeight(), ez = a.posZ;
        if (Math.sqrt((x - ex) * (x - ex) + (y - ey) * (y - ey) + (z - ez) * (z - ez)) > SEE) return false;
        MovingObjectPosition h = w.rayTraceBlocks(Vec3.createVectorHelper(ex, ey, ez), Vec3.createVectorHelper(x, y, z));
        if (h == null) return true;
        return block != null && h.blockX == block[0] && h.blockY == block[1] && h.blockZ == block[2];
    }

    static JsonArray seenMob(Crew c, WorldServer w, Entity mob) {
        JsonArray s = new JsonArray();
        if (mob == null) return s;
        for (Agent a : c.agents) if (!a.isDead && clear(w, a, mob.posX, mob.posY + mob.getEyeHeight(), mob.posZ, null)) s.add(new JsonPrimitive(RlEnv.id(a)));
        return s;
    }

    static JsonArray seenBlock(Crew c, WorldServer w, int[] p) {
        JsonArray s = new JsonArray();
        for (Agent a : c.agents) if (!a.isDead && clear(w, a, p[0] + 0.5, p[1] + 0.5, p[2] + 0.5, p)) s.add(new JsonPrimitive(RlEnv.id(a)));
        return s;
    }
}
