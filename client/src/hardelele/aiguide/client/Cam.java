package hardelele.aiguide.client;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

/**
 * The promo's camera, second version. The player is an invisible flying camera; its pose is set every rendered
 * frame (not every tick) from a shot's continuous path, so nothing steps or jumps. A shot is planned before it starts:
 * its whole path keeps a block of air around the lens and a clear line to what it films (leaves count), else
 * another angle, distance or height is tried, else the shot is not taken. Moving shots that follow an agent check
 * each next step the same way and hold (or rise) instead of passing through anything. Every frame's pose goes to
 * cam-log.txt for the check of the take afterwards.
 */
public class Cam {
    static Cam instance;

    final Minecraft mc = Minecraft.getMinecraft();
    final Random rnd = new Random(11);
    Shot shot;
    int ticks;
    long lastNanos;
    Writer log;
    net.minecraft.client.entity.EntityOtherPlayerMP eye;

    /** back to the player's own eyes */
    void release() {
        mc.gameSettings.fovSetting = fovState.restore(mc.gameSettings.fovSetting);
        shot = null;
        if (mc.thePlayer != null) mc.renderViewEntity = mc.thePlayer;
    }

    static Cam get() {
        if (instance == null) {
            instance = new Cam();
            FMLCommonHandler.instance().bus().register(instance);
            try {
                instance.log = new OutputStreamWriter(new FileOutputStream(new File(Minecraft.getMinecraft().mcDataDir, "cam-log.txt"), true), "UTF-8");
            } catch (Exception e) {
                // no log
            }
        }
        return instance;
    }

    // ---- poses and shots ---------------------------------------------------------------------------

    static final class P {
        double x, y, z;
        P(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
        P lerp(P o, double k) { return new P(x + (o.x - x) * k, y + (o.y - y) * k, z + (o.z - z) * k); }
        double dist(P o) { double a = x - o.x, b = y - o.y, c = z - o.z; return Math.sqrt(a * a + b * b + c * c); }
    }

    abstract class Shot {
        String name;
        int len;
        P pos;             // where the lens is now
        float yaw, pitch;   // where it looks now (eased toward the target)
        boolean started;
        /** the lens at time t (ticks, fractional) */
        abstract P place(double t, double dt);
        /** what it looks at at time t */
        abstract P target(double t);
    }

    static double ease(double x) {
        x = Math.max(0, Math.min(1, x));
        return x * x * (3 - 2 * x);   // smoothstep: soft start, soft stop
    }

    /** a lens going from a to b (eased), looking at a fixed point or an agent */
    class Dolly extends Shot {
        final P a, b;
        final P look;
        final int agent;
        Dolly(String name, P a, P b, P look, int agent, int len) { this.name = name; this.a = a; this.b = b; this.look = look; this.agent = agent; this.len = len; }
        P place(double t, double dt) { return a.lerp(b, ease(t / len)); }
        P target(double t) {
            if (agent > 0) { P e = agentAt(agent); if (e != null) return new P(e.x, e.y + 1.1, e.z); }
            return look;
        }
    }

    /** around a point, at a radius and a height, looking at it */
    class Orbit extends Shot {
        final P c;
        final double r, h, a0, a1;
        Orbit(String name, P c, double r, double h, double a0, double a1, int len) { this.name = name; this.c = c; this.r = r; this.h = h; this.a0 = a0; this.a1 = a1; this.len = len; }
        P place(double t, double dt) {
            double a = Math.toRadians(a0 + (a1 - a0) * ease(t / len));
            return new P(c.x + Math.sin(a) * r, c.y + h, c.z + Math.cos(a) * r);
        }
        P target(double t) { return new P(c.x, c.y + 1.2, c.z); }
    }

    /**
     * A cameraman with an agent: stays where it stands and turns after it; walks (slowly) only when the agent gets
     * too far or too near, never through anything; when the agent is hidden for a while the shot ends (a cut).
     */
    class Follow extends Shot {
        final int agent;
        final double near, far;
        double lost;
        Follow(String name, int agent, double near, double far, int len) { this.name = name; this.agent = agent; this.near = near; this.far = far; this.len = len; }
        P place(double t, double dt) {
            P e = agentAt(agent);
            if (e == null || pos == null) return pos;
            P head = new P(e.x, e.y + 1.2, e.z);
            double d = Math.hypot(pos.x - e.x, pos.z - e.z);
            P next = pos;
            if (d > far || d < near) {   // keep the distance: along the line to it, at most 3 blocks a second
                double want = (near + far) / 2, k = (d - want) / Math.max(0.1, d);
                double mx = (e.x - pos.x) * k * Math.min(1, 0.06 * dt), mz = (e.z - pos.z) * k * Math.min(1, 0.06 * dt), ml = Math.hypot(mx, mz), lim = 0.27 * dt;
                if (ml > lim) { mx *= lim / ml; mz *= lim / ml; }
                double my = Math.max(-0.05 * dt, Math.min(0.05 * dt, (e.y + 2.4) - pos.y));
                next = new P(pos.x + mx, pos.y + my, pos.z + mz);
            }
            boolean sees = sees(next, head) && !blockedView(next, head);
            if (sees && clear(next)) { lost = Math.max(0, lost - dt); return next; }
            if (!sees(pos, head) || blockedView(pos, head)) lost += dt;
            if (lost > 10) len = Math.min(len, (int) Math.ceil(time()));   // hidden: the shot ends (a cut), the camera does not jump
            return pos;
        }
        P target(double t) { P e = agentAt(agent); return e == null ? null : new P(e.x, e.y + 1.1, e.z); }
    }

    /** a frame mostly filled by something near (leaves, a wall): rays over the view, a quarter hitting within 5 blocks */
    boolean blockedView(P p, P look) {
        double dx = look.x - p.x, dy = look.y - p.y, dz = look.z - p.z, l = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (l < 0.1) return true;
        dx /= l; dy /= l; dz /= l;
        double rx = -dz, rz = dx, rl = Math.max(1e-6, Math.hypot(rx, rz));   // right
        rx /= rl; rz /= rl;
        double ux = -rz * dy, uy = rz * dx - rx * dz, uz = rx * dy;   // up = right x forward
        int hit = 0, n = 0;
        for (int i = -4; i <= 4; i++)
            for (int j = -2; j <= 1; j++) {   // the top row may be a crown overhead: allowed
                double a = Math.tan(Math.toRadians(28)) * i / 4, b = Math.tan(Math.toRadians(16)) * j / 2;
                double vx = dx + rx * a + ux * b, vy = dy + uy * b, vz = dz + rz * a + uz * b, vl = Math.sqrt(vx * vx + vy * vy + vz * vz);
                double reach = Math.min(5, l - 0.5);
                n++;
                if (reach > 0.5 && w().rayTraceBlocks(Vec3.createVectorHelper(p.x, p.y, p.z), Vec3.createVectorHelper(p.x + vx / vl * reach, p.y + vy / vl * reach, p.z + vz / vl * reach), false, true, false) != null) hit++;
            }
        return hit * 3 > n;
    }
    // ---- the world around the lens -----------------------------------------------------------------

    World w() { return mc.theWorld; }

    /** a block that the lens must keep away from: anything but air, grass and flowers */
    boolean firm(int x, int y, int z) {
        Block b = w().getBlock(x, y, z);
        Material m = b.getMaterial();
        return m != Material.air && m != Material.plants && m != Material.vine;
    }

    /** a block of air (or grass) all around the point */
    boolean clear(P p) {
        int x0 = MathHelper.floor_double(p.x - 1), x1 = MathHelper.floor_double(p.x + 1);
        int y0 = MathHelper.floor_double(p.y - 1), y1 = MathHelper.floor_double(p.y + 1);
        int z0 = MathHelper.floor_double(p.z - 1), z1 = MathHelper.floor_double(p.z + 1);
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++) if (firm(x, y, z)) return false;
        return true;
    }

    /** nothing between the lens and the point (leaves count) */
    boolean sees(P from, P to) {
        return w().rayTraceBlocks(Vec3.createVectorHelper(from.x, from.y, from.z), Vec3.createVectorHelper(to.x, to.y, to.z), false, true, false) == null;
    }

    boolean ok(P p, P look) {
        return clear(p) && (look == null || sees(p, look));
    }

    /** a planned path: all of it clear, all of it seeing each of the points */
    boolean pathOk(Shot s, P... looks) {
        for (int i = 0; i <= 12; i++) {
            P p = s.place(s.len * i / 12.0, 1);
            if (!clear(p)) return false;
            for (P l : looks) if (!sees(p, l)) return false;
        }
        return true;
    }

    // ---- agents (client entities, drawn where they are this frame) -------------------------------------

    float partial;

    Agent clientAgent(int n) {
        if (w() == null) return null;
        for (Object o : w().loadedEntityList)
            if (o instanceof Agent && ((Agent) o).skin() == n && !((Agent) o).isDead) return (Agent) o;
        return null;
    }

    P agentAt(int n) {
        Agent g = clientAgent(n);
        if (g == null) return null;
        return new P(g.prevPosX + (g.posX - g.prevPosX) * partial, g.boundingBox.minY + (g.posY - g.prevPosY) * (partial - 1), g.prevPosZ + (g.posZ - g.prevPosZ) * partial);
    }

    static Agent serverAgent(int n) {
        if (Replay.active != null) {   // a replay: the puppet carries what the recording says the agent did
            for (net.minecraft.entity.Entity e : Replay.active.puppets.values()) if (e instanceof Agent && ((Agent) e).skin() == n) return (Agent) e;
            return null;
        }
        if (Crew.instance == null) return null;
        for (Agent a : Crew.instance.agents) if (a.skin() == n) return a;
        return null;
    }

    // ---- planning ---------------------------------------------------------------------------------------

    /** a still-ish shot of an agent at a tree: from open ground, three-quarter, the trunk and the agent both seen */
    Shot planChop(int prefer) {
        List<Agent> work = new ArrayList<Agent>();
        if (Crew.instance != null)
            for (Agent a : Crew.instance.agents) if (a.actKind == 1 && a.ticksExisted - a.actAt < 8) work.add(a);
        if (work.isEmpty()) return null;
        java.util.Collections.shuffle(work, rnd);
        for (Agent s : work) {
            if (prefer > 0 && s.skin() == prefer && work.size() > 1) continue;   // another than the last one
            double gy = Math.floor(s.posY + 0.01);
            P trunk = new P(s.actX + 0.5, gy + 0.6, s.actZ + 0.5), body = new P(s.posX, gy + 1.0, s.posZ), head = new P(s.posX, gy + 1.6, s.posZ);
            double dx = s.posX - trunk.x, dz = s.posZ - trunk.z, dl = Math.max(0.3, Math.hypot(dx, dz));
            dx /= dl;
            dz /= dl;
            Shot best = null;
            double bestScore = -1e9;
            for (int sign = -1; sign <= 1; sign += 2)
                for (double ang = 35; ang <= 85; ang += 10)
                    for (double dist = 4.8; dist <= 7.2; dist += 1.2)
                        for (double h = 1.8; h <= 3.6; h += 0.9) {
                            double r = Math.toRadians(sign * ang);
                            double cx = dx * Math.cos(r) - dz * Math.sin(r), cz = dx * Math.sin(r) + dz * Math.cos(r);
                            P a = new P(trunk.x + cx * dist, gy + h, trunk.z + cz * dist);
                            // a slow move: sideways a block and a little closer
                            P b = new P(a.x - cz * sign * 1.2 - cx * 0.7, a.y, a.z + cx * sign * 1.2 - cz * 0.7);
                            P look = new P((trunk.x + body.x) / 2, gy + 1.3, (trunk.z + body.z) / 2);
                            Dolly d = new Dolly("chop " + s.getCustomNameTag(), a, b, look, 0, 50);
                            if (!pathOk(d, body, head, trunk) || blockedView(a, look) || blockedView(b, look)) continue;
                            double score = -Math.abs(ang - 55) * 0.05 - Math.abs(dist - 6.0) * 0.4 - Math.abs(h - 2.7) * 0.5 + rnd.nextDouble() * 0.3;
                            if (score > bestScore) { bestScore = score; best = d; }
                        }
            if (best != null) return best;
        }
        return null;
    }

    /**
     * Any work at a block (ore, rock, a station, a block put): from open ground, three-quarter, the block and the agent
     * both seen, a slow move; the same planning as the chop's, for whatever the agent works on.
     */
    Shot planWork(Agent s) {
        double gy = Math.floor(s.posY + 0.01);
        P blk = new P(s.actX + 0.5, s.actY + 0.5, s.actZ + 0.5), body = new P(s.posX, gy + 1.0, s.posZ), head = new P(s.posX, gy + 1.6, s.posZ);
        double dx = s.posX - blk.x, dz = s.posZ - blk.z, dl = Math.max(0.3, Math.hypot(dx, dz));
        dx /= dl;
        dz /= dl;
        Shot best = null;
        double bestScore = -1e9;
        for (int sign = -1; sign <= 1; sign += 2)
            for (double ang = 35; ang <= 115; ang += 10)
                for (double dist = 3.6; dist <= 6.6; dist += 1.0)
                    for (double h = 1.4; h <= 3.2; h += 0.6) {
                        double r = Math.toRadians(sign * ang);
                        double cx = dx * Math.cos(r) - dz * Math.sin(r), cz = dx * Math.sin(r) + dz * Math.cos(r);
                        P a = new P(blk.x + cx * dist, gy + h, blk.z + cz * dist);
                        P b = new P(a.x - cz * sign * 0.9 - cx * 0.5, a.y, a.z + cx * sign * 0.9 - cz * 0.5);
                        P look = new P((blk.x + body.x) / 2, (blk.y + head.y) / 2, (blk.z + body.z) / 2);
                        Dolly d = new Dolly("work " + s.getCustomNameTag() + " " + s.actWhat, a, b, look, 0, 60);
                        P face = new P(blk.x + (a.x - blk.x) * 0.12, blk.y, blk.z + (a.z - blk.z) * 0.12);   // the block's face toward the lens
                        if (!pathOk(d, body, head) || !sees(a, face) || blockedView(a, look) || blockedView(b, look)) continue;
                        double score = -Math.abs(ang - 60) * 0.04 - Math.abs(dist - 4.8) * 0.4 - Math.abs(h - 2.2) * 0.5 + rnd.nextDouble() * 0.3;
                        if (score > bestScore) { bestScore = score; best = d; }
                    }
        return best;
    }

    /**
     * Under the ground (a tunnel, the mine): the lens in the air of the tunnel itself, one to four blocks from the agent,
     * in a cell of air with air above or below it (no wall at the lens), seeing the agent's head and the face of the
     * block it works on; it stays there and turns after the agent; when the agent is out of its sight a while, a cut.
     * The light is the crew's own torches.
     */
    class Tunnel extends Shot {
        final int agent;
        double lost;
        Tunnel(String name, int agent, P at, int len) { this.name = name; this.agent = agent; this.pos = at; this.len = len; }
        P place(double t, double dt) {
            P e = agentAt(agent);
            if (e == null) return pos;
            if (!sees(pos, new P(e.x, e.y + 1.5, e.z))) lost += dt; else lost = Math.max(0, lost - dt);
            if (lost > 10) len = Math.min(len, (int) Math.ceil(time()));
            return pos;
        }
        P target(double t) {
            P e = agentAt(agent);
            if (e == null) return null;
            Agent s = serverAgent(agent);
            if (s != null && s.ticksExisted - s.actAt < 20) return new P((e.x + s.actX + 0.5) / 2, (e.y + 1.4 + s.actY + 0.5) / 2, (e.z + s.actZ + 0.5) / 2);
            return new P(e.x, e.y + 1.3, e.z);
        }
    }

    /** the agent is under the ground: no sky over it, rock around */
    boolean underground(Agent s) {
        int x = MathHelper.floor_double(s.posX), y = MathHelper.floor_double(s.posY + 1.5), z = MathHelper.floor_double(s.posZ);
        return !w().canBlockSeeTheSky(x, y, z);
    }

    Shot planTunnel(Agent s) {
        P head = new P(s.posX, Math.floor(s.posY + 0.01) + 1.5, s.posZ);
        P blk = new P(s.actX + 0.5, s.actY + 0.5, s.actZ + 0.5);
        int ax = MathHelper.floor_double(s.posX), ay = MathHelper.floor_double(s.posY + 0.01), az = MathHelper.floor_double(s.posZ);
        P best = null;
        double bs = -1e9;
        for (int x = ax - 4; x <= ax + 4; x++)
            for (int z = az - 4; z <= az + 4; z++)
                for (int y = ay - 2; y <= ay + 3; y++) {
                    if (firm(x, y, z) || firm(x, y + 1, z) && firm(x, y - 1, z)) continue;   // a cell of air, open above or below
                    P p = new P(x + 0.5, y + 0.55, z + 0.5);
                    double d = p.dist(head);
                    if (d < 1.4 || d > 4.6) continue;
                    if (!sees(p, head)) continue;
                    P face = new P(blk.x + (p.x - blk.x) * 0.55 / Math.max(0.55, p.dist(blk)), blk.y + (p.y - blk.y) * 0.55 / Math.max(0.55, p.dist(blk)), blk.z + (p.z - blk.z) * 0.55 / Math.max(0.55, p.dist(blk)));
                    boolean blockSeen = s.ticksExisted - s.actAt < 20 && sees(p, face);
                    double score = (blockSeen ? 3 : 0) - Math.abs(d - 2.8) - Math.abs(p.y - head.y) * 0.4 + rnd.nextDouble() * 0.2;
                    if (score > bs) { bs = score; best = p; }
                }
        return best == null ? null : new Tunnel("tunnel " + s.getCustomNameTag() + " " + s.actWhat, s.skin(), best, 100);
    }

    // ---- the director by action: the agent doing the most of a work action, the shot its work wants ---------

    boolean autoOn;
    int autoSubject = -1, autoLow, autoLen = 110;
    Agent.Act autoAct = Agent.Act.IDLE;

    /** the agents there are (by number) */
    List<Integer> skins() {
        List<Integer> out = new ArrayList<Integer>();
        if (w() == null) return out;
        for (Object o : w().loadedEntityList) if (o instanceof Agent && !((Agent) o).isDead && !out.contains(((Agent) o).skin())) out.add(((Agent) o).skin());
        return out;
    }

    /**
     * The next shot: whoever does the most of a work action now (a fight, ore, a tree, digging, building, a station,
     * then carrying or walking; standing about never), another than the last when it is as busy; filmed the way its
     * work wants (a tunnel shot under the ground). Nobody doing anything: a slow look round the base.
     */
    Shot autoShot(int avoid) {
        Agent best = null;
        int bs = 0;
        for (int n : skins()) {
            Agent s = serverAgent(n);
            if (s == null) continue;
            Agent.Act act = s.activity();
            if (act.rank <= 0) continue;
            int score = act.rank * 10 + (n != avoid ? 3 : 0);
            if (score > bs) { bs = score; best = s; }
        }
        Shot sh = null;
        if (best != null) {
            Agent.Act act = best.activity();
            if (act == Agent.Act.FIGHT) { Follow f = new Follow("fight " + best.getCustomNameTag(), best.skin(), 3.4, 6.0, 80); sh = planFollowAs(f, best.skin()); }
            else if ((act == Agent.Act.ORE || act == Agent.Act.DIG) && underground(best)) sh = planTunnel(best);
            else if (act == Agent.Act.CHOP) sh = planChop(avoid);
            if (sh == null && act.work()) sh = planWork(best);
            if (sh == null) sh = planFollow(best.skin(), autoLen);
            autoSubject = best.skin();
            autoAct = act;
        }
        if (sh == null) {   // nobody at work: the base from above, slowly round
            int[] s = Crew.site;
            P c = s != null ? new P(s[0] + 2.5, s[1], s[2] + 2.5) : mc.thePlayer == null ? null : new P(mc.thePlayer.posX, mc.thePlayer.boundingBox.minY, mc.thePlayer.posZ);
            if (c != null) sh = planOrbit("wide", c, 14, 8, rnd.nextDouble() * 360, rnd.nextDouble() * 360 + 30, autoLen);
            autoSubject = -1;
            autoAct = Agent.Act.IDLE;
        }
        autoLow = 0;
        return sh;
    }

    Shot planFollowAs(Follow f, int agent) {
        P e = agentAt(agent);
        if (e == null) return null;
        P head = new P(e.x, e.y + 1.2, e.z);
        for (int k = 0; k < 16; k++) {
            double a = Math.toRadians(k * 22.5 + rnd.nextInt(20));
            for (double h = 1.8; h <= 4.2; h += 1.2) {
                P p = new P(e.x + Math.sin(a) * 4.5, e.y + h, e.z + Math.cos(a) * 4.5);
                if (ok(p, head) && !blockedView(p, head)) { f.pos = p; return f; }
            }
        }
        return null;
    }

    /** the subject's work is over (for a second and a half), or a fight began elsewhere: time for a cut */
    boolean subjectEnded() {
        if (shot == null) return true;
        Agent s = autoSubject < 0 ? null : serverAgent(autoSubject);
        if (s != null && s.activity().rank < autoAct.rank) autoLow++; else autoLow = 0;
        if (autoLow > 30 && time() > 40) return true;
        if (autoAct != Agent.Act.FIGHT && time() > 20)
            for (int n : skins()) { Agent o = serverAgent(n); if (o != null && o.activity() == Agent.Act.FIGHT) return true; }
        return autoSubject < 0 && time() > 60 && autoShotPossible();
    }

    boolean autoShotPossible() {
        for (int n : skins()) { Agent o = serverAgent(n); if (o != null && o.activity().rank > 0) return true; }
        return false;
    }

    @SubscribeEvent
    public void autoTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !autoOn || w() == null) return;
        if (shot == null || done() || subjectEnded()) {
            Shot s = autoShot(autoSubject);
            if (s != null) { if (s.len < autoLen && !(s instanceof Tunnel)) s.len = autoLen; start(s); Director.log("autocam: " + s.name + " (" + autoAct + ")"); }
        }
    }

    // ---- a path of keyframes (position, turn, tilt, field of view, time): played as a spline ----------------

    static final class Key {
        double t, x, y, z, yaw, pitch, fov;
        Key(double t, double x, double y, double z, double yaw, double pitch, double fov) { this.t = t; this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch; this.fov = fov; }
    }

    final List<Key> keys = new ArrayList<Key>();
    final CaptureFovState fovState = new CaptureFovState();
    String pathRec;   // a file the path is recorded to (the recorder stops when the path ends)

    /** a Catmull-Rom spline through the keys (the turn unwrapped), its time the keys' own */
    class KeyPath extends Shot {
        final List<Key> k;
        KeyPath(List<Key> keys) { this.k = new ArrayList<Key>(keys); name = "path of " + k.size() + " keys"; len = (int) Math.ceil(k.get(k.size() - 1).t); }
        double[] at(double t) {
            int i = 0;
            while (i < k.size() - 2 && k.get(i + 1).t <= t) i++;
            Key p1 = k.get(i), p2 = k.get(Math.min(k.size() - 1, i + 1)), p0 = k.get(Math.max(0, i - 1)), p3 = k.get(Math.min(k.size() - 1, i + 2));
            double u = p2.t == p1.t ? 0 : Math.max(0, Math.min(1, (t - p1.t) / (p2.t - p1.t)));
            double y1 = p1.yaw, y2 = y1 + MathHelper.wrapAngleTo180_double(p2.yaw - y1), y0 = y1 - MathHelper.wrapAngleTo180_double(y1 - p0.yaw), y3 = y2 + MathHelper.wrapAngleTo180_double(p3.yaw - p2.yaw);
            return new double[] { cr(p0.x, p1.x, p2.x, p3.x, u), cr(p0.y, p1.y, p2.y, p3.y, u), cr(p0.z, p1.z, p2.z, p3.z, u), cr(y0, y1, y2, y3, u), cr(p0.pitch, p1.pitch, p2.pitch, p3.pitch, u),
                    cr(p0.fov, p1.fov, p2.fov, p3.fov, u) };
        }
        P place(double t, double dt) { double[] a = at(t); return new P(a[0], a[1], a[2]); }
        P target(double t) {
            double[] a = at(t);
            double cy = Math.cos(Math.toRadians(a[4]));
            return new P(a[0] - Math.sin(Math.toRadians(a[3])) * cy * 10, a[1] - Math.sin(Math.toRadians(a[4])) * 10, a[2] + Math.cos(Math.toRadians(a[3])) * cy * 10);
        }
    }

    static double cr(double p0, double p1, double p2, double p3, double u) {
        return 0.5 * (2 * p1 + (-p0 + p2) * u + (2 * p0 - 5 * p1 + 4 * p2 - p3) * u * u + (-p0 + 3 * p1 - 3 * p2 + p3) * u * u * u);
    }

    void playPath(String recordTo) {
        if (keys.size() < 2) { Director.log("path: needs two keys at least"); return; }
        autoOn = false;
        start(new KeyPath(keys));
        if (recordTo != null) { pathRec = recordTo; Recorder.start(recordTo); }
    }

    // ---- what is on the screen, every frame (for the cut: which agent, doing what, where in the picture) -----

    Writer actLog;

    void logActions(P lens, float yaw, float pitch) {
        try {
            if (actLog == null) actLog = new OutputStreamWriter(new FileOutputStream(new File(mc.mcDataDir, "act-log.txt"), true), "UTF-8");
            double vf = Math.toRadians(CaptureFov.degrees(mc.gameSettings.fovSetting) / 2), hf = Math.atan(Math.tan(vf) * mc.displayWidth / Math.max(1, mc.displayHeight));
            double cyw = Math.cos(Math.toRadians(yaw)), syw = Math.sin(Math.toRadians(yaw)), cp = Math.cos(Math.toRadians(pitch)), sp = Math.sin(Math.toRadians(pitch));
            double fx = -syw * cp, fy = -sp, fz = cyw * cp, rx = -cyw, rz = -syw;           // forward, right
            double ux = 0 * fz - rz * fy, uy = rz * fx - rx * fz, uz = rx * fy - 0 * fx;     // up = right x forward
            StringBuilder b = new StringBuilder();
            b.append(System.currentTimeMillis()).append('\t').append(autoSubject).append('\t').append(autoAct).append('\t').append(shot == null ? "-" : shot.name);
            for (int n : skins()) {
                P e = agentAt(n);
                Agent s = serverAgent(n);
                if (e == null) continue;
                double vx = e.x - lens.x, vy = e.y + 1.0 - lens.y, vz = e.z - lens.z;
                double cz = vx * fx + vy * fy + vz * fz, cx = vx * rx + vz * rz, cyy = vx * ux + vy * uy + vz * uz;
                boolean in = cz > 0.3 && Math.abs(cx / cz) <= Math.tan(hf) && Math.abs(cyy / cz) <= Math.tan(vf);
                boolean vis = in && sees(lens, new P(e.x, e.y + 1.0, e.z));
                b.append('\t').append(n).append(':').append(s == null ? "?" : s.activity().name()).append(':').append(vis ? 1 : 0)
                        .append(':').append(String.format(java.util.Locale.ROOT, "%.3f:%.3f:%.1f", in ? cx / cz / Math.tan(hf) : 9, in ? cyy / cz / Math.tan(vf) : 9, Math.sqrt(vx * vx + vy * vy + vz * vz)));
            }
            actLog.write(b.append('\n').toString());
        } catch (Exception ex) {
            // no log
        }
    }

    /** an agent putting a block of the house: from outside, the block and the agent seen */
    Shot planPlace(int prefer) {
        if (Crew.instance == null || Crew.site == null) return null;
        int[] s = Crew.site;
        P c = new P(s[0] + 2.5, s[1], s[2] + 2.5);
        List<Agent> work = new ArrayList<Agent>();
        for (Agent a : Crew.instance.agents) if (a.actKind == 3 && a.ticksExisted - a.actAt < 8) work.add(a);
        java.util.Collections.shuffle(work, rnd);
        for (Agent g : work) {
            if (prefer > 0 && g.skin() == prefer && work.size() > 1) continue;
            P body = new P(g.posX, Math.floor(g.posY + 0.01) + 1.0, g.posZ), block = new P(g.actX + 0.5, g.actY + 0.5, g.actZ + 0.5);
            double base = Math.atan2(g.posX - c.x, g.posZ - c.z);
            for (int k = 0; k < 10; k++) {
                double a0 = Math.toDegrees(base) + (k % 2 == 0 ? 1 : -1) * (k / 2) * 20;
                double r = 10.5 + rnd.nextDouble() * 2, h = 1.8 + rnd.nextDouble() * 1.2;   // under the roof's plan, outside the fence
                Orbit o = new Orbit("place " + g.getCustomNameTag(), c, r, h, a0, a0 + (rnd.nextBoolean() ? 12 : -12), 50);
                if (pathOk(o, body, block) && !blockedView(o.place(0, 1), block) && !blockedView(o.place(o.len, 1), block)) return o;
            }
        }
        return null;
    }

    /** around a point: the radius kept, the height raised until the whole path is clear */
    Shot planOrbit(String name, P c, double r, double h, double a0, double a1, int len) {
        for (double up = 0; up <= 6; up += 1) {
            Orbit o = new Orbit(name, c, r, h + up, a0, a1, len);
            if (pathOk(o, new P(c.x, c.y + 1.2, c.z))) return o;
        }
        return null;
    }

    Shot planFollow(int agent, int len) {
        P e = agentAt(agent);
        if (e == null) return null;
        P head = new P(e.x, e.y + 1.2, e.z);
        Follow f = new Follow("follow " + agent, agent, 3.8, 7.0, len);
        int k0 = rnd.nextInt(16);
        for (int k = 0; k < 16; k++) {
            double a = Math.toRadians((k + k0) * 22.5);
            for (double h = 2.2; h <= 4.6; h += 1.2) {
                P p = new P(e.x + Math.sin(a) * 5.2, e.y + h, e.z + Math.cos(a) * 5.2);
                if (ok(p, head) && !blockedView(p, head)) {
                    f.pos = p;
                    return f;
                }
            }
        }
        // A worker in a trench may have no clear low angle. Keep it observable from above.
        for (double h = 5.8; h <= 14; h += 2.4)
            for (double radius : new double[] { 2.4, 4.0, 7.0 })
                for (int k = 0; k < 16; k++) {
                    double angle = Math.toRadians(k * 22.5);
                    P p = new P(e.x + Math.sin(angle) * radius, e.y + h, e.z + Math.cos(angle) * radius);
                    if (ok(p, head) && !blockedView(p, head)) { f.pos = p; return f; }
                }
        return null;
    }
    int frames;

    void start(Shot s) {
        mc.gameSettings.fovSetting = fovState.restore(mc.gameSettings.fovSetting);
        shot = s;
        ticks = 0;
        frames = 0;
        s.started = false;
        lastNanos = 0;
        Director.log("cam: " + s.name + " for " + s.len + " ticks");
    }

    // ---- every frame and every tick -------------------------------------------------------------------

    @SubscribeEvent(priority = cpw.mods.fml.common.eventhandler.EventPriority.LOW)
    public void frame(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.START || shot == null || mc.thePlayer == null || w() == null) return;
        partial = e.renderTickTime;
        long now = System.nanoTime();
        double dt;
        double t;
        if (MultiCapture.active != null) {
            dt = MultiCapture.active.cameraStepTicks();
            t = MultiCapture.active.cameraTicks();
        } else if (Recorder.instance != null) {   // recording: the camera's time goes by video frames (a 60th of a second each), so it is even in the video
            dt = lastNanos == 0 ? 0 : 20.0 / 60;
            frames++;
            t = Math.min(shot.len, frames * 20.0 / 60);
        } else {
            dt = lastNanos == 0 ? 0 : Math.min(3, (now - lastNanos) / 5e7);   // in ticks
            t = Math.min(shot.len, ticks + partial);
        }
        lastNanos = now;
        P p = shot.place(t, dt);
        if (p == null) p = shot.pos;
        if (p == null) return;
        if (shot instanceof KeyPath) {   // a path is played as it was keyed: its own speed, turn and field of view
            double[] k = ((KeyPath) shot).at(t);
            shot.yaw = (float) k[3];
            shot.pitch = (float) k[4];
            mc.gameSettings.fovSetting = fovState.key(mc.gameSettings.fovSetting, k[5]);
            shot.started = true;
            shot.pos = p;
            pose(p);
            return;
        }
        if (shot.started && shot.pos != null && dt > 0) {   // never faster than 4 blocks a second (a cut is a new shot, not a move)
            double d = p.dist(shot.pos), lim = 0.3 * dt;
            if (d > lim) p = shot.pos.lerp(p, lim / d);
        }
        P look = shot.target(t);
        float ty = shot.yaw, tp = shot.pitch;
        if (look != null) {
            double dx = look.x - p.x, dy = look.y - p.y, dz = look.z - p.z;
            ty = (float) Math.toDegrees(Math.atan2(-dx, dz));
            tp = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        }
        if (!shot.started) {
            shot.yaw = ty;
            shot.pitch = tp;
            shot.started = true;
        } else {   // the aim eases toward the target, the same whatever the frame rate
            double k = 1 - Math.exp(-dt / 8.0), lim = 3.0 * dt;   // at most 60 degrees a second
            float dy = MathHelper.wrapAngleTo180_float(ty - shot.yaw) * (float) k, dp = (tp - shot.pitch) * (float) k;
            shot.yaw += Math.max(-lim, Math.min(lim, dy));
            shot.pitch += Math.max(-lim, Math.min(lim, dp));
        }
        shot.pos = p;
        pose(p);
    }

    /** the view: a body of its own at p, turned as the shot is (the player stays where it is and is seen in the picture) */
    void pose(P p) {
        if (eye == null || eye.worldObj != w()) {
            eye = new net.minecraft.client.entity.EntityOtherPlayerMP(w(), new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "cam"));
            eye.setInvisible(true);
        }
        double ey = p.y - 1.62;   // the view of a body like this is 1.62 over its feet
        eye.setPosition(p.x, ey, p.z);
        eye.prevPosX = eye.lastTickPosX = p.x;
        eye.prevPosY = eye.lastTickPosY = ey;
        eye.prevPosZ = eye.lastTickPosZ = p.z;
        eye.rotationYaw = eye.prevRotationYaw = shot.yaw;
        eye.rotationPitch = eye.prevRotationPitch = shot.pitch;
        eye.rotationYawHead = eye.prevRotationYawHead = shot.yaw;
        mc.renderViewEntity = eye;
        if (log != null)
            try {
                log.write(System.currentTimeMillis() + "\t" + String.format(java.util.Locale.ROOT, "%.3f\t%.3f\t%.3f\t%.2f\t%.2f\t%s\n", p.x, p.y, p.z, shot.yaw, shot.pitch,
                        !clear(p) ? "BLOCKED" : "ok"));
            } catch (Exception ex) {
                // no log
            }
        if (Recorder.instance != null) logActions(p, shot.yaw, shot.pitch);   // what each recorded frame shows
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END || shot == null) return;
        ticks++;
        if (shot instanceof KeyPath && done()) {   // a path's end: its field of view back, its recording stopped
            if (pathRec != null) { Recorder.stop(); Director.log("path: recorded " + pathRec); pathRec = null; }
            release();
        }
        try {
            if (log != null) log.flush();
            if (actLog != null) actLog.flush();
        } catch (Exception ex) {
            // no log
        }
    }

    /** the shot's own time in ticks: by video frames while recording */
    double time() {
        if (MultiCapture.active != null) return MultiCapture.active.cameraTicks();
        return Recorder.instance != null ? frames * 20.0 / 60 : ticks;
    }

    boolean done() {
        return shot == null || (Recorder.instance != null ? frames * 20.0 / 60 : ticks) >= shot.len;
    }
}
