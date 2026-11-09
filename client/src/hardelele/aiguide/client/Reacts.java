package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.world.WorldServer;

/**
 * Layer 2 on the game's side: the snapshot of a tick (situation, from what the body already sensed), the reactions
 * (Crew.Tasks that act through the body only, as a player would: a pillar under itself, a step cut, a walk out of the
 * lava, a jump, a hit), the host of an agent's slot (the pause of the task, the case for the journal: Cases), and the
 * one call Crew.tick makes for each agent before its jobs.
 */
final class Reacts {
    private Reacts() { }

    // ---- once a tick for each agent -------------------------------------------------------------------------

    /** layer 2 for an agent this tick: true when a reaction had the body (its jobs get no step) */
    static boolean tick(Crew c, Agent a, WorldServer w) {
        if (a.run == null) a.run = new Run(a);
        Run h = a.run;
        h.w = w;
        h.c = c;
        long now = w.getTotalWorldTime();
        if ((now + a.getEntityId()) % 100 == 0)
            for (Undo u : a.undo.expire(now)) Events.log("crew: " + a.getCustomNameTag() + " undo failed: " + u + " (too old)");
        h.st = situation(a, c, w);
        return a.slot.tick(h.st, h);
    }

    /** what the reflexes choose by: the body's snapshot of this tick and what the agent is busy with (nothing scanned) */
    static Situation situation(Agent a, Crew c, WorldServer w) {
        Body.Senses s = a.body.senses;
        Situation st = a.run != null && a.run.st != null ? a.run.st : new Situation();
        long now = w.getTotalWorldTime();
        st.now = now;
        st.health = s.health;
        st.maxHealth = s.maxHealth;
        st.hurt = s.hurt;
        st.hurtBy = s.hurtBy == null ? "" : s.hurtBy;
        st.fall = s.fall;
        st.motionY = s.motionY;
        st.onGround = s.onGround;
        st.falling = s.falling;
        st.inWater = s.inWater;
        st.inLava = s.inLava;
        st.burning = s.burning;
        st.underWater = s.underWater;
        st.collided = s.collided;
        st.inWall = s.inWall;
        st.air = s.air;
        st.feet = s.feet == null ? st.feet : s.feet;
        st.under = s.under == null ? "" : String.valueOf(Block.blockRegistry.getNameForObject(s.under));
        st.drop = s.drop;
        st.headroom = s.headroom;
        st.pit = s.pit;
        st.inWaterTicks = s.inWaterTicks;
        st.still = s.still;
        st.threat = s.threat == null ? "" : s.threatKind;
        st.threatDist = s.threat == null ? 1e9 : s.threatDist;
        st.threatFuse = s.threatFuse;
        st.blocks = s.blocks;
        st.weapon = s.weapon;
        st.digTool = s.digTool;
        Crew.GoTo g = a.walking;
        st.walking = g != null && a.walkingAt >= now - 1;
        if (st.walking) {
            st.goalX = MathHelper.floor_double(g.x);
            st.goalY = MathHelper.floor_double(g.y + 0.01);
            st.goalZ = MathHelper.floor_double(g.z);
            st.goalFlat = Math.hypot(g.x - a.posX, g.z - a.posZ);
            st.goalDy = st.goalY - st.feet[1];
            st.stuck = g.stuckFor;
            st.walkState = g.state;
            st.digging = g.br != null;
        } else {
            st.goalFlat = 0; st.goalDy = 0; st.stuck = 0; st.digging = false;
        }
        st.failsHere = a.walks.failsAt(Crew.key(st.feet[0], st.feet[1], st.feet[2]), now);
        Crew.Task t = a.jobs.peek();
        st.task = t == null ? "" : Cases.name(t);
        TaskBoard.Card k = c == null ? null : c.board.leased.get(a);
        st.card = k == null ? "" : k.kind + "#" + k.id;
        Origin o = a.origins.peek();
        st.origin = o == null ? "" : o.name();
        boolean declared = a.guardAt >= now - 1;
        st.taskGuard = Math.max(declared ? a.guard : 30, o == Origin.PLAYER && t != null ? 45 : 30);
        st.fighting = declared && a.guard >= 85;
        st.owedDue = a.undo.isEmpty() ? 0 : a.undo.dueCount(live(a));
        return st;
    }

    /** the jobs that are on: the queue and the reaction running (an undo duty of theirs is not due) */
    static List<Object> live(Agent a) {
        List<Object> l = new ArrayList<Object>(a.jobs);
        if (a.slot.task != null) l.add(a.slot.task);
        return l;
    }

    /** the host of an agent's slot: reactions made and stepped, the task told of the pause, the case for the journal */
    static final class Run implements Reflexes.Slot.Host<Crew.Task> {
        final Agent a;
        WorldServer w;
        Crew c;
        Situation st;
        Run(Agent a) { this.a = a; }

        public Crew.Task react(Reflex r) {
            try {
                return Reacts.react(r, st, a, w, c);
            } catch (Exception ex) {
                Events.log("crew: " + a.getCustomNameTag() + " reflex " + r.name() + " failed to react: " + ex);
                return null;
            }
        }

        public Result step(Crew.Task t) {
            try {
                return t.step(a, w);
            } catch (Exception ex) {
                StringBuilder at = new StringBuilder();
                StackTraceElement[] tr = ex.getStackTrace();
                for (int i = 0; i < Math.min(4, tr.length); i++) at.append(" @ ").append(tr[i].getClassName().replace("hardelele.aiguide.client.", "")).append(':').append(tr[i].getLineNumber());
                Events.log("crew: " + a.getCustomNameTag() + " reaction failed: " + ex + at);
                return Result.BLOCKED;
            }
        }

        public void paused() {
            a.body.stop();
            Crew.Task t = a.jobs.peek();
            if (t instanceof Pausable) ((Pausable) t).paused(a, w);
        }

        public void resumed(Result failed) {
            Crew.Task t = a.jobs.peek();
            if (t instanceof Pausable) ((Pausable) t).resumed(a, w);
        }

        public void began(Reflex r, Crew.Task t) {
            Cases.start(a, r.name(), r.priority(), t instanceof Reaction ? ((Reaction) t).how : r.name());
        }

        public void ended(Reflex r, Crew.Task t, Result res, long ticks, String note) {
            if (note != null) return;   // taken over by a higher one: the journal closes it itself (PUSHED) when that one starts
            Cases.finish(a, res, res.failed() && r.aboutTheWay() && !a.jobs.isEmpty() ? "task" : null);
        }
    }

    // ---- the reactions ---------------------------------------------------------------------------------------

    /** a reaction: a task with the name of what it does (the journal's word for it, a constant per variant) */
    abstract static class Reaction implements Crew.Task {
        final String how;
        int ticks;
        Reaction(String how) { this.how = how; }
        public String toString() { return how; }
    }

    /** of a reflex's variants, the one to do: the memory's advice when it helped, the next one when it did not, else the first */
    static String choose(Agent a, String reflex, String... options) {
        CaseBook.Advice ad = Cases.suggest(a, reflex, options);
        if (!ad.known()) return options[0];
        if (ad.rate >= 0.5 || ad.support < 2) return ad.reaction;
        for (int i = 0; i < options.length; i++) if (options[i].equals(ad.reaction)) return options[(i + 1) % options.length];   // it did not help twice here: the next one
        return options[0];
    }

    static Crew.Task react(Reflex r, Situation st, Agent a, WorldServer w, Crew c) {
        String n = r.name();
        if (n.equals("lava") || n.equals("fire")) return outOfFire(a, w, n.equals("fire"));
        if (n.equals("drown")) return up(a, w);
        if (n.equals("smother")) return new Unbury();
        if (n.equals("fall")) return clutch(a, w, st);
        if (n.equals("creeper")) return flee(a, w);
        if (n.equals("mob")) return mob(a, w, c, st);
        if (n.equals("water")) return shore(a, w, st);
        if (n.equals("undo")) {
            Undo u = a.undo.next(live(a));
            return u == null ? null : new Give(u);
        }
        if (n.equals("escape")) return new Climb(choose(a, n, "pillar", "step"), a, c, st, true);
        if (n.equals("climb")) return new Climb(st.goalFlat <= 1.5 ? choose(a, n, "pillar", "step") : choose(a, n, "step", "pillar"), a, c, st, false);
        if (n.equals("descend")) return new Descend(st);
        if (n.equals("stuck")) return new Unstuck(choose(a, n, "jump", "side step", "back step"), a, st);
        return null;
    }

    /** the nearest cell to stand in within r (across) that the test likes, nearest first; null when none */
    interface CellTest { boolean ok(int x, int y, int z); }

    static int[] nearest(Agent a, WorldServer w, int r, CellTest t) {
        int[] f = Ground.feet(w, a.posX, a.posY, a.posZ);
        int[] best = null;
        double bd = 1e9;
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                for (int dy = -2; dy <= 2; dy++) {
                    int x = f[0] + dx, y = f[1] + dy, z = f[2] + dz;
                    if (!t.ok(x, y, z)) continue;
                    double d = Math.abs(dx) + Math.abs(dz) + Math.abs(dy) * 1.5;
                    if (d < bd) { bd = d; best = new int[] { x, y, z }; }
                }
        return best;
    }

    static boolean hot(WorldServer w, int x, int y, int z) {
        Material m = w.getBlock(x, y, z).getMaterial();
        return m == Material.lava || m == Material.fire;
    }

    /** a dry cell to stand in: a floor, no water, no fire or lava in it or under it */
    static boolean dry(WorldServer w, int x, int y, int z) {
        return Crew.stand(w, x, y, z) && !hot(w, x, y, z) && !hot(w, x, y + 1, z) && !hot(w, x, y - 1, z) && !Ground.liquid(w, x, y, z);
    }

    /** out of the lava or the fire: into water within 4 when burning, else onto the nearest dry cell */
    static Crew.Task outOfFire(final Agent a, final WorldServer w, boolean fire) {
        int[] to = null;
        String how = "to a dry cell";
        if (fire) {
            to = nearest(a, w, 4, (x, y, z) -> w.getBlock(x, y, z).getMaterial() == Material.water && Ground.passable(w, x, y + 1, z, null));
            if (to != null) how = "into water";
        }
        if (to == null) to = nearest(a, w, 4, (x, y, z) -> dry(w, x, y, z));
        if (to == null) return null;
        return new Walk(how, to, 60, false, ag -> !ag.handleLavaMovement() && (!ag.isBurning() || ag.isInWater()), Result.BLOCKED);
    }

    /** out of breath under water: up (the jump swims), toward the nearest cell with air over the water */
    static Crew.Task up(final Agent a, final WorldServer w) {
        int[] to = nearest(a, w, 4, (x, y, z) -> Crew.stand(w, x, y, z) || Ground.liquid(w, x, y - 1, z) && w.getBlock(x, y, z).getMaterial() == Material.air);
        if (to == null) { int[] f = Ground.feet(w, a.posX, a.posY, a.posZ); to = new int[] { f[0], f[1] + 3, f[2] }; }
        return new Walk("swim up", to, 100, true, ag -> !ag.isInsideOfMaterial(Material.water), Result.BLOCKED);
    }

    /** in water getting nowhere: onto the nearest dry cell (the side toward the goal when it is as near) */
    static Crew.Task shore(final Agent a, final WorldServer w, final Situation st) {
        int[] to = nearest(a, w, 5, (x, y, z) -> dry(w, x, y, z) && Crew.solid(w, x, y - 1, z));
        if (to == null) return null;
        return new Walk("to the shore", to, 80, true, ag -> ag.onGround && !ag.isInWater(), Result.NO_PATH);
    }

    /** falling: a block under its feet against a wall beside (as a player clutches), or toward water below */
    static Crew.Task clutch(Agent a, WorldServer w, Situation st) {
        int fx = MathHelper.floor_double(a.posX), fy = MathHelper.floor_double(a.posY), fz = MathHelper.floor_double(a.posZ);
        double[] water = null;
        double bd = 1e9;
        for (int dx = -3; dx <= 3; dx++)   // once, when it begins to fall: the water under it, if any
            for (int dz = -3; dz <= 3; dz++)
                for (int y = fy - 1; y > fy - 24 && y > 0; y--) {
                    Block b = w.getBlock(fx + dx, y, fz + dz);
                    if (b.getMaterial() == Material.water) {
                        double d = dx * dx + dz * dz;
                        if (d < bd) { bd = d; water = new double[] { fx + dx + 0.5, y, fz + dz + 0.5 }; }
                        break;
                    }
                    if (!Ground.passable(w, fx + dx, y, fz + dz, null)) break;
                }
        if (water == null && st.blocks == 0) return null;
        return new Clutch(water != null && bd > 0 ? "toward water" : "block under itself", water);
    }

    /** away from a creeper: 8 blocks straight from it, by the path finder */
    static Crew.Task flee(Agent a, WorldServer w) {
        final EntityLivingBase m = a.body.senses.threat;
        if (m == null) return null;
        double[] p = Moves.away(a.posX, a.posZ, m.posX, m.posZ, 8);
        int gy = Crew.ground(w, MathHelper.floor_double(p[0]), MathHelper.floor_double(a.posY), MathHelper.floor_double(p[1]));
        Walk run = new Walk("run off", new int[] { MathHelper.floor_double(p[0]), gy < 0 ? MathHelper.floor_double(a.posY) : gy, MathHelper.floor_double(p[1]) }, 100, false,
                ag -> m.isDead || ag.getDistanceToEntity(m) > 7.5, Result.BLOCKED);
        run.nav = true;
        return run;
    }

    /** a mob at arm's length: hit back when armed and well (the guard's own fight, for this one mob), else back to the house or the others */
    static Crew.Task mob(Agent a, WorldServer w, Crew c, Situation st) {
        final EntityLivingBase m = a.body.senses.threat;
        if (!(m instanceof EntityLiving)) return null;
        if (st.weapon >= 4 && st.health >= 12) return new Hit((EntityLiving) m);
        int[] to = null;
        if (Crew.site != null) to = new int[] { Crew.site[0] + 2, Crew.site[1], Crew.site[2] + 2 };
        if (c != null) {   // nearer: one of its own (the guard comes to them all)
            double bd = to == null ? 1e9 : Crew.dist(a, to[0] + 0.5, to[1], to[2] + 0.5);
            for (Agent o : c.agents)
                if (o != a && !o.isDead && o.getDistanceToEntity(m) > 6 && Crew.dist(a, o.posX, o.posY, o.posZ) < bd) {
                    bd = Crew.dist(a, o.posX, o.posY, o.posZ);
                    to = Ground.feet(w, o.posX, o.posY, o.posZ);
                }
        }
        java.util.List<int[]> pref = new java.util.ArrayList<int[]>();
        if (to != null) pref.add(to);
        final Agent ag0 = a;
        to = Reach.fallBack(a.posX, a.posY, a.posZ, m.posX, m.posZ, pref, p -> MindSkills.reached(ag0, w, p));   // a cell a path goes to
        if (to == null) return new Hit((EntityLiving) m);   // nowhere to fall back: it hits back, not stands under the blows
        Walk back = new Walk("fall back", to, 200, false, ag -> m.isDead || ag.getDistanceToEntity(m) > 10, Result.BLOCKED);
        back.nav = true;
        back.progress = new Reach.Progress(40);   // no nearer in 2 s: over (the next reflex may hit back)
        Defense.of(a.who()).retreats++;
        return back;
    }

    /** a walk to a cell with an end condition: straight there (jumping as needed), or by the path finder for a longer way */
    static final class Walk extends Reaction {
        final int[] to;
        final int max;
        final boolean swim;
        final Skills.Scaffold.Until until;
        final Result fail;
        boolean nav;
        Reach.Progress progress;
        Walk(String how, int[] to, int max, boolean swim, Skills.Scaffold.Until until, Result fail) {
            super(how); this.to = to; this.max = max; this.swim = swim; this.until = until; this.fail = fail;
        }
        public Result step(Agent a, WorldServer w) {
            if (until.ok(a)) { a.body.stop(); return Result.OK; }
            if (++ticks > max) { a.body.stop(); return fail; }
            double x = to[0] + 0.5, y = to[1], z = to[2] + 0.5;
            if (progress != null && progress.stuck(Crew.dist(a, x, y, z))) { a.body.stop(); return fail; }
            if (nav) { if (ticks % 10 == 1 || a.body.pathless()) a.body.walkTo(x, y, z, 1.25); }
            else a.body.stepTo(x, y, z, 1.2);
            if (swim || a.isInWater() || a.handleLavaMovement() || a.isCollidedHorizontally || y > a.posY + 0.5) a.body.jump();
            if (!nav && Crew.dist(a, x, y, z) < 0.5) { a.body.stop(); return until.ok(a) ? Result.OK : fail; }
            return Result.RUNNING;
        }
    }

    /** falling: each tick a block under its feet if a face to put it against is seen, and/or the legs toward the water */
    static final class Clutch extends Reaction {
        final double[] water;
        Clutch(String how, double[] water) { super(how); this.water = water; }
        public Result step(Agent a, WorldServer w) {
            if (a.onGround || a.isInWater()) return Result.OK;
            if (++ticks > 80) return Result.BLOCKED;
            if (water != null) a.body.stepTo(water[0], water[1], water[2], 1.0);
            ItemStack s = Skills.Scaffold.block(a, null);
            int fx = MathHelper.floor_double(a.posX), cy = MathHelper.floor_double(a.posY) - 1, fz = MathHelper.floor_double(a.posZ);
            if (s != null && Crew.soft(w, fx, cy, fz)) a.body.place(w, fx, cy, fz, s.getItem(), s.getMetadata());   // only what the eyes see: a wall beside the shaft
            return Result.RUNNING;
        }
    }

    /** buried (sand, gravel) or walled in: the block at the head out, then the one at the feet, then what falls from over it */
    static final class Unbury extends Reaction {
        Unbury() { super("dig out"); }
        public Result step(Agent a, WorldServer w) {
            if (++ticks > 120) return Result.BLOCKED;
            int x = MathHelper.floor_double(a.posX), z = MathHelper.floor_double(a.posZ), feet = MathHelper.floor_double(a.posY + 0.01), head = MathHelper.floor_double(a.posY + a.getEyeHeight());
            int[] cell = null;
            for (int y : new int[] { head, feet, head + 1 }) {
                Block b = w.getBlock(x, y, z);
                if (y == head + 1 && b != Blocks.sand && b != Blocks.gravel) continue;   // over the head only what would fall in
                if (!Ground.passable(w, x, y, z, null) && b != Blocks.bedrock) { cell = new int[] { x, y, z }; break; }
            }
            if (cell == null) return a.isEntityInsideOpaqueBlock() ? Result.RUNNING : Result.OK;
            Result r = a.body.dig(w, cell[0], cell[1], cell[2]);
            if (r == Result.OK) { Day1.catchNear(a, w, 1.5); Ground.changed(); }
            return r == Result.NO_TOOL || r == Result.NOT_VISIBLE || r == Result.OUT_OF_REACH ? r : Result.RUNNING;
        }
    }

    /** the mob that came at it: the guard's fight for this one, for at most 20 s and while it stays near */
    static final class Hit extends Reaction {
        final EntityLiving m;
        final Guard.Fight f;
        Hit(EntityLiving m) { super("hit back"); this.m = m; f = new Guard.Fight(m); }
        public Result step(Agent a, WorldServer w) {
            if (m.isDead && f.loot == null && ticks > 0 || ++ticks > 400 || a.getDistanceToEntity(m) > 12) return Result.OK;
            return f.step(a, w);   // a kill: its loot picked up, then OK
        }
    }

    /** may a reaction dig this block on its way up or down: natural ground or rock outside the zones (its own wall only to get out) */
    static Moves.Dig digRule(final Agent a, final WorldServer w, final Crew c, final boolean out) {
        return (x, y, z) -> Crew.GoTo.mayDigThrough(a, w, x, y, z)
                || out && c != null && Ground.mayDig(c, x, y, z, "exit") && Ground.ownWall(c, x, y, z) && Skills.breakTicks(a, w, x, y, z) >= 0;
    }

    /** dig the plan's cells one by one, then step onto its cell; null when on it, else the result to end with */
    abstract static class Mover extends Reaction {
        Moves.Plan plan;
        int di, stepTicks, done;
        Mover(String how) { super(how); }

        /** one tick of the plan: RUNNING; OK when on its cell; else why not */
        Result move(Agent a, WorldServer w) {
            if (di < plan.dig.size()) {
                int[] c = plan.dig.get(di);
                Block was = w.getBlock(c[0], c[1], c[2]);
                int meta = w.getBlockMetadata(c[0], c[1], c[2]);
                boolean wall = Crew.instance != null && Ground.ownWall(Crew.instance, c[0], c[1], c[2]);
                Result r = a.body.dig(w, c[0], c[1], c[2]);
                if (r == Result.RUNNING) return r;
                if (r != Result.OK) return r;
                if (wall) a.owe(Undo.Kind.RESTORE, c[0], c[1], c[2], Block.getIdFromBlock(was), meta, "dug to get out");   // its own wall: put back when the job is over
                Ground.changed();
                di++;
                return Result.RUNNING;
            }
            int[] to = plan.to;
            if (Math.abs(a.posX - (to[0] + 0.5)) < 0.35 && Math.abs(a.posZ - (to[2] + 0.5)) < 0.35 && Math.abs(a.posY - to[1]) < 0.3 && a.onGround) {
                Day1.catchNear(a, w, 2.0);
                return Result.OK;
            }
            a.body.stepTo(to[0] + 0.5, to[1], to[2] + 0.5, 0.8);
            if (to[1] > a.posY + 0.5 || a.isCollidedHorizontally) a.body.jump();
            return ++stepTicks > 50 ? Result.BLOCKED : Result.RUNNING;
        }
    }

    /**
     * Up toward its walk's goal (or out of a pit): one block at a time, a step cut into the wall in front or a pillar
     * of one under itself (left standing: it is owed to the job, taken down when the job is over), until it is as high
     * as the goal, or out (a cell beside it to stand in), or 5 blocks up.
     */
    static final class Climb extends Mover {
        final boolean out, pillarFirst;
        final double gx, gz;
        final int gy;
        final long spot;
        final Moves.Dig dig;
        Skills.Scaffold up;
        int[] exit;
        Climb(String how, Agent a, Crew c, Situation st, boolean out) {
            super(how);
            this.out = out;
            this.pillarFirst = how.equals("pillar");
            WorldServer w = (WorldServer) a.worldObj;
            if (st.walking) { gx = st.goalX + 0.5; gy = st.goalY; gz = st.goalZ + 0.5; }
            else if (c != null && c.base != null) { int[] b = Ground.anchor(c, w); gx = b[0] + 0.5; gy = b[1]; gz = b[2] + 0.5; }
            else { gx = a.posX; gy = st.feet[1] + 5; gz = a.posZ; }
            spot = Crew.key(st.feet[0], st.feet[1], st.feet[2]);
            dig = digRule(a, w, c, out);
        }
        public Result step(Agent a, WorldServer w) {
            if (++ticks > 20 * 40) return done > 0 ? Result.OK : Result.TIMEOUT;
            if (up != null) {   // a pillar block under itself
                Result r = up.step(a, w);
                if (r == Result.RUNNING) return r;
                up = null;
                if (r.failed()) return done > 0 ? Result.OK : r;
                done++;
                return Result.RUNNING;
            }
            if (plan != null) {
                Result r = move(a, w);
                if (r == Result.RUNNING) return r;
                boolean wasExit = plan.kind == Moves.Plan.WALK;
                plan = null;
                if (r != Result.OK) return done > 0 ? Result.OK : Result.NO_PATH;
                done++;
                if (wasExit) { a.walks.left(spot); return Result.OK; }
                return Result.RUNNING;
            }
            int[] f = Ground.feet(w, a.posX, a.posY, a.posZ);
            if (!a.onGround) return Result.RUNNING;
            Sight.Space s = Body.space(w);
            if (out && done > 0 && (exit = Moves.exit(s, f[0], f[1], f[2])) != null) {   // a cell beside to stand in: out onto it
                plan = new Moves.Plan(Moves.Plan.WALK);
                plan.to = exit;
                di = 0;
                stepTicks = 0;
                return Result.RUNNING;
            }
            if (!out && f[1] >= gy || done >= (out ? 12 : 5)) return done > 0 ? Result.OK : Result.NO_PATH;
            int[] dir = Moves.toward(f[0], f[2], gx, gz);
            Moves.Plan p = Moves.up(s, dig, f[0], f[1], f[2], dir, Skills.Scaffold.blocks(a, null) > 0, pillarFirst || Math.hypot(gx - a.posX, gz - a.posZ) < 1);
            if (out && p.kind == Moves.Plan.NONE)   // out of a pit: any side will do
                for (int[] d : new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) {
                    p = Moves.up(s, dig, f[0], f[1], f[2], d, Skills.Scaffold.blocks(a, null) > 0, false);
                    if (p.kind != Moves.Plan.NONE) break;
                }
            if (p.kind == Moves.Plan.NONE) {
                Events.log("crew: " + a.getCustomNameTag() + " cannot climb at " + f[0] + " " + f[1] + " " + f[2] + ": " + p.why);
                return done > 0 ? Result.OK : Result.NO_PATH;
            }
            if (p.kind == Moves.Plan.PILLAR) {
                up = new Skills.Scaffold(f, 1, null, null, null).keep();
                return Result.RUNNING;
            }
            plan = p;
            di = 0;
            stepTicks = 0;
            return Result.RUNNING;
        }
    }

    /** down toward the walk's goal: steps cut down (or a walk down a drop of up to three), never a jump down a cliff */
    static final class Descend extends Mover {
        final double gx, gz;
        final int gy;
        Descend(Situation st) { super("steps down"); gx = st.goalX + 0.5; gy = st.goalY; gz = st.goalZ + 0.5; }
        public Result step(Agent a, WorldServer w) {
            if (++ticks > 20 * 40) return done > 0 ? Result.OK : Result.TIMEOUT;
            if (plan != null) {
                Result r = move(a, w);
                if (r == Result.RUNNING) return r;
                plan = null;
                if (r != Result.OK) return done > 0 ? Result.OK : Result.NO_PATH;
                done++;
                return Result.RUNNING;
            }
            if (!a.onGround) return Result.RUNNING;
            int[] f = Ground.feet(w, a.posX, a.posY, a.posZ);
            if (f[1] <= gy || done >= 6) return done > 0 ? Result.OK : Result.NO_PATH;
            Moves.Plan p = Moves.down(Body.space(w), digRule(a, w, Crew.instance, false), f[0], f[1], f[2], Moves.toward(f[0], f[2], gx, gz));
            if (p.kind == Moves.Plan.NONE) {
                Events.log("crew: " + a.getCustomNameTag() + " cannot go down at " + f[0] + " " + f[1] + " " + f[2] + ": " + p.why);
                return done > 0 ? Result.OK : Result.NO_PATH;
            }
            plan = p;
            di = 0;
            stepTicks = 0;
            return Result.RUNNING;
        }
    }

    /**
     * Stuck on the level: by turns (each at most half a second), a jump toward the goal, a step to the side (left, then
     * right), a step back, a soft block out of the way at the head; over when it has moved a block. The variant it
     * begins with is the memory's choice.
     */
    static final class Unstuck extends Reaction {
        static final String[] ORDER = { "jump", "side step", "back step", "soft block" };
        final double sx, sz, gx, gz;
        int phase, at;
        Unstuck(String how, Agent a, Situation st) {
            super(how);
            sx = a.posX; sz = a.posZ;
            gx = st.goalX + 0.5; gz = st.goalZ + 0.5;
            for (int i = 0; i < ORDER.length; i++) if (ORDER[i].equals(how)) phase = i;
        }
        public Result step(Agent a, WorldServer w) {
            ticks++;
            if (Math.hypot(a.posX - sx, a.posZ - sz) >= 1.0) { a.body.stop(); return Result.OK; }
            if (ticks - at > 10) { at = ticks; phase++; if (phase - indexOf(how) >= ORDER.length) return Result.NO_PATH; }
            int ph = phase % ORDER.length;
            double dx = gx - a.posX, dz = gz - a.posZ, d = Math.max(1e-3, Math.hypot(dx, dz));
            dx /= d; dz /= d;
            String what = ORDER[ph];
            if (what.equals("jump")) { a.body.stepTo(a.posX + dx * 2, a.posY, a.posZ + dz * 2, 1.0); if (a.onGround) a.body.jump(); }
            else if (what.equals("side step")) { double side = (ticks - at) < 5 ? 1 : -1; a.body.stepTo(a.posX - dz * 1.2 * side, a.posY, a.posZ + dx * 1.2 * side, 1.0); }
            else if (what.equals("back step")) a.body.stepTo(a.posX - dx * 1.5, a.posY, a.posZ - dz * 1.5, 1.0);
            else {   // something soft in front at the feet or the head (leaves, a bush): out of the way
                int fx = MathHelper.floor_double(a.posX + dx * 0.8), fz = MathHelper.floor_double(a.posZ + dz * 0.8), fy = MathHelper.floor_double(a.posY + 0.01);
                for (int y = fy + 1; y >= fy; y--) {
                    Material m = w.getBlock(fx, y, fz).getMaterial();
                    if (m == Material.leaves || m == Material.plants || m == Material.vine || m == Material.web || m == Material.cactus) { a.body.dig(w, fx, y, fz); break; }
                }
            }
            return Result.RUNNING;
        }
        static int indexOf(String how) { for (int i = 0; i < ORDER.length; i++) if (ORDER[i].equals(how)) return i; return 0; }
    }

    /**
     * A duty given back (Undo): a block put for a while (a pillar, a step) dug away, from the top down; a block dug for a
     * while put back from the bag. What it could not do is tried again later, given up on the third failure.
     */
    static final class Give extends Reaction {
        final Undo u;
        Crew.GoTo walk;
        Skills.PlaceFrom put;
        boolean walked;
        Give(Undo u) { super(u.kind == Undo.Kind.REMOVE ? "take away" : "put back"); this.u = u; }
        public Result step(Agent a, WorldServer w) {
            Result r = run(a, w);
            if (r == Result.RUNNING) return r;
            if (r == Result.OK) { a.undo.paid(u.x, u.y, u.z); Ledger.act(a, "undo " + u.why); }
            else if (a.undo.failed(u, w.getTotalWorldTime())) Events.log("crew: " + a.getCustomNameTag() + " undo failed: " + u + " (" + r + ")");
            return r;
        }
        Result run(Agent a, WorldServer w) {
            if (++ticks > 20 * 60) return Result.TIMEOUT;
            if (walk != null) {
                Result r = walk.step(a, w);
                if (r == Result.RUNNING) return r;
                walk = null;
                walked = true;
            }
            if (u.kind == Undo.Kind.RESTORE) {
                if (!Crew.soft(w, u.x, u.y, u.z)) return Result.OK;   // something is there again
                Block b = Block.getBlockById(u.block);
                Item it = Item.getItemFromBlock(b);
                if (it == null || Skills.count(a.bag, it, -1) == 0) return Result.NO_ITEMS;   // nothing to put back
                if (put == null) put = new Skills.PlaceFrom(u.x, u.y, u.z, it, -1).pillar(0);
                return put.step(a, w);
            }
            if (w.getBlock(u.x, u.y, u.z).getMaterial() == Material.air) return Result.OK;   // gone already
            int[] f = Ground.feet(w, a.posX, a.posY, a.posZ);
            boolean onIt = f[0] == u.x && f[2] == u.z && f[1] - 1 == u.y;
            if (onIt && !a.onGround) return Result.RUNNING;
            if (!onIt && Crew.eyeDist(a, u.x, u.y, u.z) > Crew.REACH - 0.3) {
                if (walked) return Result.OUT_OF_REACH;
                walk = new Crew.GoTo(u.x + 0.5, u.y, u.z + 0.5, 2.5).walkOnly();
                return Result.RUNNING;
            }
            Result r = a.body.dig(w, u.x, u.y, u.z);   // the block it stands on (its pillar's top) as Scaffold takes it down
            if (r == Result.OK) { Day1.catchNear(a, w, 2.0); Ground.changed(); }
            if ((r == Result.NOT_VISIBLE || r == Result.OUT_OF_REACH) && !walked) { walk = new Crew.GoTo(u.x + 0.5, u.y, u.z + 0.5, 1.8).walkOnly(); return Result.RUNNING; }
            return r;
        }
    }

    // ---- the floor under the feet (no digging of what it stands on) --------------------------------------------

    /** the block is what the agent stands on (under the middle of its feet): a player who digs it falls */
    static boolean standsOn(Agent a, WorldServer w, int x, int y, int z) {
        return MathHelper.floor_double(a.posX) == x && MathHelper.floor_double(a.posZ) == z && Math.abs(a.boundingBox.minY - (y + 1)) < 0.2 && a.onGround;
    }

    /** a cell beside it to stand in, not over that block's column, from which the block is still in reach (null: none) */
    static int[] offCell(Agent a, final WorldServer w, final int x, final int y, final int z) {
        return nearest(a, w, 2, (cx, cy, cz) -> !(cx == x && cz == z) && Crew.stand(w, cx, cy, cz)
                && Math.sqrt(Math.pow(cx + 0.5 - (x + 0.5), 2) + Math.pow(cy + 1.62 - (y + 0.5), 2) + Math.pow(cz + 0.5 - (z + 0.5), 2)) <= Crew.REACH - 0.3);
    }
}
