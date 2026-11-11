package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.world.WorldServer;

/**
 * The mine of contract 3 (docs/rl/CONTRACT-v2.md 3): stairs down from a mouth by the base to the horizons (y 40: iron
 * and coal; y 12: diamonds, gold, redstone), the way back up the same stairs, branch tunnels at a horizon that show
 * the ore in their walls. The stairs are Day1.Steps (a column of three dug a step, a floor of the bag's cobblestone
 * where there is none), turning clockwise every 8 steps (a square spiral: the mine stays inside the arena, 32 blocks
 * of height between a step and the one over it). What was dug is kept in RlWorld.now (the way back, the facts).
 */
final class MineSkills {
    private MineSkills() { }

    static final int TURN = 8;

    static MindSkills.Skill make(Crew c, Agent a, WorldServer w, Mind.Call call) {
        switch (call.tool) {
            case "descend": return descend(c, a, w);
            case "ascend": return ascend(c, a, w);
            case "tunnel": return tunnel(c, a, w, MindSkills.clamp(call.num("length", 12), 2, 32));
            default: return null;
        }
    }

    static int feetY(Agent a) { return (int) Math.floor(a.boundingBox.minY + 0.01); }

    static boolean underground(Crew c, Agent a) { return feetY(a) < RlWorld.now.surfaceY(c) - 4; }

    /** the stairs' bottom: the last cell, else the mouth */
    static int[] bottom(RlWorld m) { return m.stairs.isEmpty() ? m.mouth : m.stairs.get(m.stairs.size() - 1); }

    /** the direction of the stairs' step k (0 the first): the mouth's, turned clockwise every TURN steps */
    static int[] dir(RlWorld m, int k) {
        int dx = m.dx, dz = m.dz;
        for (int t = 0; t < (k / TURN) % 4; t++) { int x = dx; dx = -dz; dz = x; }
        return new int[] { dx, dz };
    }

    /** the next horizon's y under the stairs' bottom, or -1 when the last is reached */
    static int nextHorizon(RlWorld m) {
        int y = bottom(m) == null ? Integer.MAX_VALUE : bottom(m)[1];
        for (int h : RlSpec.HORIZONS) if (y > h) return h;
        return -1;
    }

    // ---- descend ------------------------------------------------------------------------------------------------

    static MindSkills.Skill descend(Crew c, Agent a, WorldServer w) {
        RlWorld m = RlWorld.now;
        if (m.mouth == null) {
            if (c.base == null) return MindSkills.failed("NO_BASE", "no base: no place for a mine's mouth");
            int[] s = Day1.mineSpot(c, w);
            if (s == null) return MindSkills.failed("NO_SPOT", "no place for a mine's mouth near the base (the ground goes on nowhere 10 blocks)");
            m.mouth = new int[] { s[0], s[1], s[2] };
            m.dx = s[3];
            m.dz = s[4];
            Events.log("mine: " + a.who() + " opens the mine at " + s[0] + " " + s[1] + " " + s[2] + " going " + s[3] + " " + s[4]);
        }
        if (nextHorizon(m) < 0 && m.level >= RlSpec.HORIZONS.length) {
            if (feetY(a) <= RlSpec.HORIZONS[RlSpec.HORIZONS.length - 1] + RlSpec.HORIZON_NEAR)
                return MindSkills.failed("AT_BOTTOM", "at the last horizon (y " + RlSpec.HORIZONS[RlSpec.HORIZONS.length - 1] + ") already");
        }
        Descend d = new Descend(c);
        d.lease = 20 * 600;
        return d;
    }

    /** to the mouth, down the stairs dug, then steps on to the next horizon */
    static final class Descend extends MindSkills.Skill {
        final Crew c;
        int state, i, fails, dug, turns;
        Crew.GoTo walk;
        Day1.Steps steps;
        int[] seg;   // the segment being dug: x0 y0 z0 dx dz n
        Descend(Crew c) { this.c = c; }

        Result run(Agent a, WorldServer w) {
            RlWorld m = RlWorld.now;
            if (state == 0) {   // to the stairs: the mouth, or the nearest stair below it when already in them
                i = nearest(m, a);
                state = 1;
            }
            if (state == 1) {   // down the stairs, a few at a time
                List<int[]> way = new ArrayList<int[]>();
                way.add(m.mouth);
                way.addAll(m.stairs);
                if (i >= way.size() - 1 && Crew.dist(a, way.get(way.size() - 1)[0] + 0.5, way.get(way.size() - 1)[1], way.get(way.size() - 1)[2] + 0.5) <= 1.6) { state = 2; return Result.RUNNING; }
                int j = Math.min(way.size() - 1, i + 3);
                int[] p = way.get(j);
                if (walk == null) walk = new Crew.GoTo(p[0] + 0.5, p[1], p[2] + 0.5, 0.8);
                Result r = walk.step(a, w);
                if (r == Result.RUNNING) return r;
                walk = null;
                if (r != Result.OK && Crew.dist(a, p[0] + 0.5, p[1], p[2] + 0.5) > 1.6) {
                    if (++fails > 3) { reason = "NO_PATH"; detail = "no way down the stairs at " + p[0] + " " + p[1] + " " + p[2]; return Result.NO_PATH; }
                    return Result.RUNNING;
                }
                fails = 0;
                i = j;
                return Result.RUNNING;
            }
            // dig on to the next horizon
            int h = nextHorizon(m);
            int[] b = bottom(m);
            if (h < 0 || b[1] <= h) {
                reached(m, a);
                detail = "at y " + feetY(a) + " (horizon " + m.level + "), " + m.stairs.size() + " steps; dug " + dug + " now";
                return Result.OK;
            }
            if (steps == null) {
                int k = m.stairs.size();
                int[] d = dir(m, k);
                int n = Math.min(TURN - k % TURN, b[1] - h);
                seg = new int[] { b[0], b[1], b[2], d[0], d[1], n };
                steps = new Day1.Steps(b[0], b[1], b[2], d[0], d[1], n);
            }
            Result r = steps.step(a, w);
            if (r == Result.RUNNING) return r;
            int done = steps.k > seg[5] ? seg[5] : steps.k - 1;
            for (int s = 1; s <= done; s++) m.stairs.add(new int[] { seg[0] + seg[3] * s, seg[1] - s, seg[2] + seg[4] * s });
            dug += done;
            steps = null;
            reached(m, a);
            if (r == Result.NO_TOOL) { reason = "NO_TOOL"; detail = "the stairs need a pickaxe for the stone (dug " + dug + ")"; return r; }
            if (done == 0 && turns++ < 3) {   // a cave, water or no floor ahead: the stairs turn (clockwise) and go on from their bottom
                int x = m.dx; m.dx = -m.dz; m.dz = x;
                Events.log("mine: " + a.who() + " turns the stairs at " + b[0] + " " + b[1] + " " + b[2] + " (" + r + ")");
                return Result.RUNNING;
            }
            if (done > 0) turns = 0;
            if (done == 0) {
                reason = dug > 0 ? "OK" : "BLOCKED";
                detail = "the stairs end at y " + bottom(m)[1] + " (no floor to put: no cobblestone in the bag, or nothing to stand on)";
                return dug > 0 ? Result.OK : Result.BLOCKED;
            }
            return Result.RUNNING;
        }
    }

    /** the horizons the stairs' bottom reaches */
    static void reached(RlWorld m, Agent a) {
        int[] b = bottom(m);
        if (b == null) return;
        int lv = 0;
        for (int i = 0; i < RlSpec.HORIZONS.length; i++) if (b[1] <= RlSpec.HORIZONS[i]) lv = i + 1;
        if (lv > m.level) { m.level = lv; Events.log("mine: the stairs reach horizon " + lv + " (y " + b[1] + ")"); }
    }

    /** the index in mouth + stairs of the cell nearest the agent (0: the mouth) */
    static int nearest(RlWorld m, Agent a) {
        int best = 0;
        double bd = Crew.dist(a, m.mouth[0] + 0.5, m.mouth[1], m.mouth[2] + 0.5);
        for (int k = 0; k < m.stairs.size(); k++) {
            int[] p = m.stairs.get(k);
            double d = Crew.dist(a, p[0] + 0.5, p[1], p[2] + 0.5);
            if (d < bd) { bd = d; best = k + 1; }
        }
        return bd < 4 ? best : 0;
    }

    // ---- ascend -------------------------------------------------------------------------------------------------

    static MindSkills.Skill ascend(Crew c, Agent a, WorldServer w) {
        RlWorld m = RlWorld.now;
        if (m.mouth == null || m.stairs.isEmpty()) return MindSkills.failed("NO_MINE", "the crew has no stairs down: descend first");
        if (!underground(c, a)) return MindSkills.failed("ON_SURFACE", "on the surface already");
        Ascend s = new Ascend(nearestAny(m, a));
        s.lease = 20 * 300 + 20 * 4 * Math.max(0, RlWorld.now.surfaceY(c) - feetY(a));   // (a way of its own up: a step a few seconds)
        return s;
    }

    /** the stair nearest the agent, however far (it climbs from there) */
    static int nearestAny(RlWorld m, Agent a) {
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int k = 0; k < m.stairs.size(); k++) {
            int[] p = m.stairs.get(k);
            double d = Crew.dist(a, p[0] + 0.5, p[1], p[2] + 0.5);
            if (d < bd) { bd = d; best = k; }
        }
        return best + 1;   // (index in mouth + stairs)
    }

    /** up the stairs, three at a time, the mouth last; no way to them (a pit, a pillar, a cave): steps dug up to the surface over itself */
    static final class Ascend extends MindSkills.Skill {
        int i, fails, climbs;
        Crew.GoTo walk;
        StepsUp up, out;
        Ascend(int from) { i = from; }

        Result run(Agent a, WorldServer w) {
            RlWorld m = RlWorld.now;
            Crew c = Crew.instance;
            if (out != null) {   // its own way up: done when it is out from under the ground
                Result r = out.step(a, w);
                if (r == Result.RUNNING) return r;
                out = null;
                if (c == null || !underground(c, a)) { detail = "dug its way up to the surface at " + (int) Math.floor(a.posX) + " " + feetY(a) + " " + (int) Math.floor(a.posZ); return Result.OK; }
                if (r == Result.NO_TOOL || r == Result.NO_ITEMS) { reason = r.name(); detail = "no way to the stairs, and the way up needs " + (r == Result.NO_TOOL ? "a pickaxe" : "blocks"); return r; }
                reason = "NO_PATH"; detail = "no way to the stairs, nor dug up to the surface (" + r + ")"; return Result.NO_PATH;
            }
            List<int[]> way = new ArrayList<int[]>();
            way.add(m.mouth);
            way.addAll(m.stairs);
            if (i >= way.size()) i = way.size() - 1;
            int j = Math.max(0, i - 3);
            int[] p = way.get(j);
            if (up != null) {   // off the stairs below them (a cave, a fall): steps dug up towards them first
                Result r = up.step(a, w);
                if (r == Result.RUNNING) return r;
                up = null;
                fails = 0;
            }
            if (walk == null) walk = new Crew.GoTo(p[0] + 0.5, p[1], p[2] + 0.5, j == 0 ? 1.2 : 0.8);
            Result r = walk.step(a, w);
            if (r == Result.RUNNING) return r;
            walk = null;
            if (r != Result.OK && Crew.dist(a, p[0] + 0.5, p[1], p[2] + 0.5) > 1.8) {
                if (++fails > 3) {
                    int[] near = way.get(nearestAny(m, a) - 1 < 0 ? 0 : Math.min(way.size() - 1, nearestAny(m, a)));
                    if (climbs++ < 3 && near[1] > Math.floor(a.boundingBox.minY) + 1) { up = new StepsUp(near, 24); return Result.RUNNING; }
                    if (c != null) {   // the stairs out of reach: a staircase of its own, dug up to the surface (a player out of a hole)
                        int s = RlWorld.now.surfaceY(c), fy = feetY(a);
                        out = new StepsUp(new int[] { (int) Math.floor(a.posX), s, (int) Math.floor(a.posZ) }, s - fy + 8);
                        return Result.RUNNING;
                    }
                    reason = "NO_PATH"; detail = "no way up the stairs at " + p[0] + " " + p[1] + " " + p[2]; return Result.NO_PATH;
                }
                return Result.RUNNING;
            }
            fails = 0;
            i = j;
            if (j == 0) { detail = "at the mouth " + p[0] + " " + p[1] + " " + p[2]; return Result.OK; }
            return Result.RUNNING;
        }
    }

    /**
     * Steps dug up towards a cell above (a player out of a cave): each step the column ahead (towards it) cleared two
     * high over a floor (put from the bag where there is none), the head's room over its own cell, then a step up onto
     * it; until its feet are a block under the cell's or the steps are spent. Lava ahead stops it.
     */
    static final class StepsUp implements Crew.Task {
        final int[] to;
        int left, tries;
        Crew.Break br;
        Skills.PlaceFrom put;
        Crew.GoTo walk;
        StepsUp(int[] to, int n) { this.to = to; this.left = n; }

        public Result step(Agent a, WorldServer w) {
            if (br != null) { Result r = br.step(a, w); if (r == Result.RUNNING) return r; br = null; if (r == Result.NO_TOOL) return r; if (r.failed() && ++tries > 6) return Result.BLOCKED; return Result.RUNNING; }
            if (put != null) { Result r = put.step(a, w); if (r == Result.RUNNING) return r; put = null; if (r.failed() && ++tries > 6) return Result.BLOCKED; return Result.RUNNING; }
            if (walk != null) { Result r = walk.step(a, w); if (r == Result.RUNNING) return r; walk = null; left--; tries = 0; return Result.RUNNING; }
            int fx = net.minecraft.util.MathHelper.floor_double(a.posX), fy = net.minecraft.util.MathHelper.floor_double(a.boundingBox.minY), fz = net.minecraft.util.MathHelper.floor_double(a.posZ);
            if (fy >= to[1] - 1 || left <= 0) return Result.OK;
            int ddx = to[0] - fx, ddz = to[2] - fz;
            int dx = Math.abs(ddx) >= Math.abs(ddz) ? Integer.signum(ddx) : 0, dz = dx == 0 ? Integer.signum(ddz) : 0;
            if (dx == 0 && dz == 0) dx = 1;   // right under it: up beside it
            int cx = fx + dx, cz = fz + dz;
            int[][] clear = { { fx, fy + 2, fz }, { cx, fy + 1, cz }, { cx, fy + 2, cz }, { cx, fy + 3, cz } };
            for (int[] q : clear) {
                net.minecraft.block.Block b = w.getBlock(q[0], q[1], q[2]);
                if (b.getMaterial() == net.minecraft.block.material.Material.lava) return Result.BLOCKED;
                if (!Crew.soft(w, q[0], q[1], q[2]) && !b.getMaterial().isLiquid()) { br = new Crew.Break(q[0], q[1], q[2], 0); return Result.RUNNING; }
            }
            if (Crew.soft(w, cx, fy, cz)) {   // no floor ahead: one from the bag
                net.minecraft.item.ItemStack s = Skills.Scaffold.block(a, null);
                if (s == null) return Result.NO_ITEMS;
                put = new Skills.PlaceFrom(cx, fy, cz, s.getItem(), s.getMetadata());
                return Result.RUNNING;
            }
            walk = new Crew.GoTo(cx + 0.5, fy + 1, cz + 0.5, 0.5);
            return Result.RUNNING;
        }
    }

    // ---- tunnel -------------------------------------------------------------------------------------------------

    static MindSkills.Skill tunnel(Crew c, Agent a, WorldServer w, int len) {
        RlWorld m = RlWorld.now;
        if (m.mouth == null || m.stairs.isEmpty()) return MindSkills.failed("NO_MINE", "the crew has no stairs down: descend first");
        int[] b = bottom(m);
        if (!RlScore.atHorizon(feetY(a))) return MindSkills.failed("NOT_AT_HORIZON", "at y " + feetY(a) + ": no horizon here (y 40, 12): descend first");
        int[] d = dir(m, Math.max(0, m.stairs.size() - 1));
        int t = m.tunnels.size() % 3;   // left, right, ahead of the stairs' last direction
        int dx = t == 0 ? d[1] : t == 1 ? -d[1] : d[0], dz = t == 0 ? -d[0] : t == 1 ? d[0] : d[1];
        if (Math.abs(feetY(a) - b[1]) > RlSpec.HORIZON_NEAR || Crew.dist(a, b[0] + 0.5, b[1], b[2] + 0.5) > 12) {   // off the stairs (a cave): from where it stands, the way it looks
            double yaw = Math.toRadians(a.rotationYawHead), lx = -Math.sin(yaw), lz = Math.cos(yaw);
            dx = Math.abs(lx) >= Math.abs(lz) ? (lx > 0 ? 1 : -1) : 0;
            dz = dx == 0 ? (lz > 0 ? 1 : -1) : 0;
            b = new int[] { (int) Math.floor(a.posX), feetY(a), (int) Math.floor(a.posZ) };
        }
        Tunnel k = new Tunnel(c, b, dx, dz, len);
        k.lease = Budget.ticks(Crew.dist(a, b[0] + 0.5, b[1], b[2] + 0.5) * 1.5 + len, len * 4, 2, 0, 0, 60);
        return k;
    }

    /** a 1x2 tunnel from the stairs' bottom, a column at a time; the walls looked at as it goes, a torch where dark */
    static final class Tunnel extends MindSkills.Skill {
        final Crew c;
        final int[] from;
        final int dx, dz, len;
        final List<int[]> cells = new ArrayList<int[]>();
        int col = 1, fails;
        Crew.GoTo walk;
        Crew.Mine mine;
        boolean there, kept;
        Tunnel(Crew c, int[] from, int dx, int dz, int len) { this.c = c; this.from = from; this.dx = dx; this.dz = dz; this.len = len; }

        Result run(Agent a, WorldServer w) {
            if (!there) {
                if (walk == null) walk = new Crew.GoTo(from[0] + 0.5, from[1], from[2] + 0.5, 0.8);
                Result r = walk.step(a, w);
                if (r == Result.RUNNING) return r;
                walk = null;
                if (r != Result.OK && Crew.dist(a, from[0] + 0.5, from[1], from[2] + 0.5) > 2) { reason = "NO_PATH"; detail = "no way to the stairs' bottom"; return Result.NO_PATH; }
                there = true;
            }
            if (!kept) { RlWorld.now.tunnels.add(cells); kept = true; }
            if (col > len) return end(a, w);
            int x = from[0] + dx * col, y = from[1], z = from[2] + dz * col;
            if (mine == null) {
                List<int[]> l = new ArrayList<int[]>();
                for (int yy = y + 1; yy >= y; yy--)
                    if (!Crew.soft(w, x, yy, z) && !w.getBlock(x, yy, z).getMaterial().isLiquid() && w.getBlock(x, yy, z) != net.minecraft.init.Blocks.bedrock) l.add(new int[] { x, yy, z });
                if (w.getBlock(x, y, z).getMaterial().isLiquid() || w.getBlock(x, y + 1, z).getMaterial().isLiquid()) { detail = "a liquid ahead at " + x + " " + y + " " + z + ": stopped"; return end(a, w); }
                if (l.isEmpty()) { step(a, w, x, y, z); return Result.RUNNING; }
                mine = new Crew.Mine(l);
            }
            Result r = mine.step(a, w);
            if (r == Result.RUNNING) return r;
            mine = null;
            if (r == Result.NO_TOOL) { reason = "NO_TOOL"; detail = "the tunnel's stone needs a pickaxe"; return r; }
            if (r != Result.OK && ++fails > 2) { detail = "could not dig on at " + x + " " + y + " " + z + " (" + r + ")"; return end(a, w); }
            if (r == Result.OK) step(a, w, x, y, z);
            return Result.RUNNING;
        }

        void step(Agent a, WorldServer w, int x, int y, int z) {
            cells.add(new int[] { x, y, z });
            col++;
            fails = 0;
            a.body.walkTo(x + 0.5, y, z + 0.5, 1.0);
            if (col % 3 == 0) Exploration.observe(c, a, w);
            if (col % 5 == 0) Skills.lightUp(a, w);
        }

        Result end(Agent a, WorldServer w) {
            Exploration.observe(c, a, w);
            if (cells.isEmpty()) { if (reason == null) reason = "BLOCKED"; if (detail.isEmpty()) detail = "nothing dug"; return Result.BLOCKED; }
            detail = (detail.isEmpty() ? "" : detail + "; ") + "a tunnel of " + cells.size() + " from " + from[0] + " " + from[1] + " " + from[2];
            return Result.OK;
        }
    }

    // ---- the facts ----------------------------------------------------------------------------------------------

    static void facts(Map<String, Double> r, Crew c, Agent a, WorldServer w) {
        RlWorld m = RlWorld.now;
        int y = feetY(a), s = m.surfaceY(c);
        r.put("underground", y < s - 4 ? 1.0 : 0.0);
        r.put("y_rel", (double) (y - s));
        r.put("depth_level", (double) RlScore.horizon(y));
        r.put("at_horizon", RlScore.atHorizon(y) ? 1.0 : 0.0);
        r.put("mine_known", m.mouth != null ? 1.0 : 0.0);
        r.put("mine_level", (double) m.level);
        r.put("mine_dist", m.mouth == null ? 64.0 : Math.min(64, Crew.dist(a, m.mouth[0] + 0.5, m.mouth[1], m.mouth[2] + 0.5)));
        r.put("tunnels", (double) m.tunnels.size());
    }
}
