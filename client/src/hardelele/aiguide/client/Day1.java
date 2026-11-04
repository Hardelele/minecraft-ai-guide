package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.world.WorldServer;

/**
 * The crew's first day, beyond the basics of Crew: work in a chain (choppers hand their logs to a carrier who runs
 * them to the chest), a mine going down in steps (real stone), stone tools and a furnace with charcoal for torches,
 * a gift to the player, a proper base (a house with a door, windows and a stepped roof, a fence with a gate,
 * torches) and, at nightfall, the crew at the mouth of the mine. All of it as the crew's phases.
 */
class Day1 {

    // ---- handing things over: a throw, the other one catches ------------------------------------------

    /** throw the giver's bag (but what it keeps) to someone; the items fly and are caught where they land */
    static void toss(Agent from, double tx, double ty, double tz, WorldServer w, Item keep) {
        from.swingItem();
        double dx = tx - from.posX, dy = ty - (from.posY + 1.3), dz = tz - from.posZ, d = Math.max(0.5, Math.sqrt(dx * dx + dz * dz));
        for (java.util.Iterator<ItemStack> it = from.bag.iterator(); it.hasNext(); ) {
            ItemStack s = it.next();
            if (keep != null && s.getItem() == keep) continue;
            EntityItem e = new EntityItem(w, from.posX, from.posY + 1.3, from.posZ, s.copy());
            e.motionX = dx / d * Math.min(0.42, d * 0.1);
            e.motionZ = dz / d * Math.min(0.42, d * 0.1);
            e.motionY = 0.22 + dy * 0.04;
            e.delayBeforeCanPickup = 12;
            w.spawnEntityInWorld(e);
            it.remove();
        }
        w.playSoundEffect(from.posX, from.posY + 1, from.posZ, "random.pop", 0.4F, 1.3F);
        if (from.getHeldItem() != null && !(from.getHeldItem().getItem() instanceof net.minecraft.item.ItemTool)) from.setCurrentItemOrArmor(0, null);
    }

    /** the agent picks up what lies within reach of its hands */
    @SuppressWarnings("unchecked")
    static int catchNear(Agent a, WorldServer w, double r) {
        int got = 0;
        for (Object o : w.getEntitiesWithinAABB(EntityItem.class, a.boundingBox.expand(r, 1.0, r))) {
            EntityItem it = (EntityItem) o;
            if (it.isDead || it.age < 8) continue;
            ItemStack s = it.getEntityItem();
            a.onItemPickup(it, s.stackSize);
            Crew.give(a.bag, s);
            it.setDead();
            got += s.stackSize;
            if (a.getHeldItem() == null) a.setCurrentItemOrArmor(0, new ItemStack(s.getItem(), 1, s.getMetadata()));
        }
        return got;
    }

    /** the carrier: runs to whichever chopper has the most, takes its logs (thrown over), carries them to the chest */
    static class Courier implements Crew.Task {
        final Crew c;
        final List<Agent> choppers;
        Agent from;
        Crew.GoTo walk;
        Crew.Task stash;
        int state, ticks, idle;
        Courier(Crew c, List<Agent> choppers) { this.c = c; this.choppers = choppers; }
        public boolean step(Agent a, WorldServer w) {
            ticks++;
            if (stash != null) { if (stash.step(a, w)) stash = null; return false; }
            if (state == 0) {   // whom to go to
                from = null;
                int most = 0;
                for (Agent g : choppers) {
                    int n = 0;
                    for (ItemStack s : g.bag) n += s.stackSize;
                    if (n > most) { most = n; from = g; }
                }
                boolean working = false;
                for (Agent g : choppers) if (!g.jobs.isEmpty()) working = true;
                int mine = 0;
                for (ItemStack s : a.bag) mine += s.stackSize;
                if (from == null || most < 3 && working) {
                    if (mine > 0 && (!working || mine >= 12)) { stash = c.new Stash(null, 0); return false; }
                    if (!working) return true;
                    if (++idle % 40 == 0 && !choppers.isEmpty()) {   // wait near the busiest chopper
                        Agent g = choppers.get((idle / 40) % choppers.size());
                        walk = new Crew.GoTo(g.posX + 2, g.posY, g.posZ + 1, 2.5);
                    }
                    if (walk != null && walk.step(a, w)) walk = null;
                    return false;
                }
                walk = new Crew.GoTo(from.posX, from.posY, from.posZ, 3.2);
                state = 1;
                return false;
            }
            if (state == 1) {   // run to it; it throws, the carrier catches
                if (Crew.dist(a, from.posX, from.posY, from.posZ) > 3.4) {
                    if (walk.step(a, w)) walk = new Crew.GoTo(from.posX, from.posY, from.posZ, 3.2);
                    return false;
                }
                a.getNavigator().clearPathEntity();
                Crew.face(a, from.posX, from.posY + 1.4, from.posZ);
                Crew.face(from, a.posX, a.posY + 1.4, a.posZ);
                toss(from, a.posX, a.posY + 1.0, a.posZ, w, null);
                ticks = 0;
                state = 2;
                return false;
            }
            Crew.face(a, from.posX, from.posY + 1.4, from.posZ);
            catchNear(a, w, 1.6);
            if (ticks > 26) {
                catchNear(a, w, 3.0);
                int mine = 0;
                for (ItemStack s : a.bag) mine += s.stackSize;
                if (mine >= 12) stash = c.new Stash(null, 0);
                state = 0;
            }
            return false;
        }
    }

    /** choppers chop and keep what they pick up for the carrier; the carrier runs between them and the chest */
    static class WoodChain extends Crew.Phase {
        final int perAgent;
        WoodChain(int perAgent) { this.perAgent = perAgent; max = 20 * 200; }
        void start(Crew c, WorldServer w, EntityPlayerMP p) {
            if (c.base == null) c.base = new int[] { (int) Math.floor(p.posX), (int) Math.floor(p.boundingBox.minY), (int) Math.floor(p.posZ) };
            c.placeChest(w);
            List<int[]> trees = Crew.trunks(w, c.base[0], c.base[1], c.base[2], 40);
            List<Agent> choppers = new ArrayList<Agent>(c.agents);
            Agent carrier = choppers.size() >= 3 ? choppers.remove(choppers.size() - 1) : null;
            for (Agent a : choppers) {
                double fx = a.posX, fz = a.posZ;
                for (int k = 0; k < perAgent; k++) {
                    int[] best = null;
                    double bd = 1e9;
                    for (int[] t : trees) {
                        if (c.taken.contains(Crew.key(t[0], t[1], t[2]))) continue;
                        double d = Math.hypot(t[0] - fx, t[2] - fz) + Math.hypot(t[0] - c.base[0], t[2] - c.base[2]) * 0.5;
                        if (d < bd) { bd = d; best = t; }
                    }
                    if (best == null) break;
                    c.taken.add(Crew.key(best[0], best[1], best[2]));
                    a.jobs.add(new Crew.Chop(best[0], best[1], best[2]));
                    a.jobs.add(new Crew.Collect(best[0] + 0.5, best[1], best[2] + 0.5, 5));
                    fx = best[0];
                    fz = best[2];
                }
                if (carrier == null) a.jobs.add(c.new Stash(Item.getItemFromBlock(Blocks.log), 1));
            }
            if (carrier != null) carrier.jobs.add(new Courier(c, choppers));
            Director.log("crew: wood chain, " + choppers.size() + " choppers" + (carrier != null ? ", " + carrier.getCustomNameTag() + " carries" : ""));
        }
    }

    // ---- the mine: steps down, two abreast ----------------------------------------------------------------

    /** dig down in steps from a point in a direction: each step a column of three, the agent standing a step higher */
    static class Steps implements Crew.Task {
        final int x0, y0, z0, dx, dz, n;
        int k = 1, tries;
        Crew.Break br;
        Crew.GoTo walk;
        Steps(int x0, int y0, int z0, int dx, int dz, int n) { this.x0 = x0; this.y0 = y0; this.z0 = z0; this.dx = dx; this.dz = dz; this.n = n; }
        public boolean step(Agent a, WorldServer w) {
            if (br != null) { if (br.step(a, w)) { br = null; catchNear(a, w, 1.4); } return false; }
            if (k > n) { catchNear(a, w, 2.5); return true; }
            int cx = x0 + dx * k, cz = z0 + dz * k, floor = y0 - k;
            // the blocks of this step, the top first (the agent looks down the stairs)
            for (int y = floor + 2; y >= floor; y--) {
                Block b = w.getBlock(cx, y, cz);
                if (Crew.soft(w, cx, y, cz) || b == Blocks.bedrock) continue;
                if (w.getBlock(cx, y, cz).getMaterial().isLiquid()) continue;
                double reach = Crew.eyeDist(a, cx, y, cz);
                int[] in = Crew.blocker(a, w, cx, y, cz);
                if (reach <= Crew.REACH && (in == null || in[0] == cx && in[2] == cz)) {
                    if (in != null && (in[0] != cx || in[1] != y || in[2] != cz)) { br = new Crew.Break(in[0], in[1], in[2], hard(w, in)); return false; }
                    br = new Crew.Break(cx, y, cz, hard(w, new int[] { cx, y, cz }));
                    return false;
                }
                // not seen from here: stand on the step before
                int px = x0 + dx * (k - 1), pz = z0 + dz * (k - 1), pf = y0 - (k - 1);
                if (walk == null) walk = new Crew.GoTo(px + 0.5, pf, pz + 0.5, 0.6);
                if (walk.step(a, w)) { walk = null; if (++tries > 4) { tries = 0; k++; } }
                return false;
            }
            // the step is open: a solid floor under it, or the stairs end here
            if (Crew.soft(w, cx, floor - 1, cz)) w.setBlock(cx, floor - 1, cz, Blocks.cobblestone, 0, 3);
            k++;
            tries = 0;
            walk = null;
            return false;
        }
        static double hard(WorldServer w, int[] b) {
            Block x = w.getBlock(b[0], b[1], b[2]);
            return x == Blocks.stone || x == Blocks.cobblestone ? 0.7 : x.getMaterial() == net.minecraft.block.material.Material.leaves ? 0.2 : 0.45;
        }
    }

    /** the mouth of the mine: by the base, where the ground goes on for a while in one direction */
    static int[] mineSpot(Crew c, WorldServer w) {
        int bx = c.base[0], by = c.base[1], bz = c.base[2];
        int[][] dirs = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
        int[] best = null;
        double bs = -1e9;
        int[] s = Crew.site;
        for (int ox = -12; ox <= 12; ox += 2)
            for (int oz = -12; oz <= 12; oz += 2) {
                int x = bx + ox, z = bz + oz, y = Crew.ground(w, x, by, z);
                if (y < 0) continue;
                if (s != null && x >= s[0] - 6 && x <= s[0] + 10 && z >= s[2] - 6 && z <= s[2] + 10) continue;   // not under the base
                for (int[] d : dirs) {
                    int good = 0;
                    for (int k = 0; k < 9; k++)
                        for (int side = 0; side <= 1; side++) {
                            int cx = x + d[0] * k + (d[0] == 0 ? side : 0), cz = z + d[1] * k + (d[1] == 0 ? side : 0);
                            if (Crew.stand(w, cx, y, cz)) good++;
                        }
                    double score = good - Math.hypot(ox, oz) * 0.3;
                    if (score > bs) { bs = score; best = new int[] { x, y, z, d[0], d[1] }; }
                }
            }
        return best;
    }

    static int[] mine;   // x, y, z, dx, dz of the mouth (for the camera and the next day)

    static class Mine extends Crew.Phase {
        Mine() { max = 20 * 160; }
        void start(Crew c, WorldServer w, EntityPlayerMP p) {
            c.placeChest(w);
            int[] m = mineSpot(c, w);
            if (m == null) { Director.log("crew: no place for a mine"); return; }
            mine = m;
            Director.log("crew: mine at " + m[0] + " " + m[1] + " " + m[2] + " going " + m[3] + " " + m[4]);
            int k = 0;
            for (Agent a : c.agents) {
                if (k < 2) {   // two dig abreast
                    int sx = m[0] + (m[3] == 0 ? k : 0), sz = m[2] + (m[4] == 0 ? k : 0);
                    a.jobs.add(new Crew.GoTo(sx + 0.5, m[1], sz + 0.5, 0.8));
                    a.jobs.add(new Steps(sx, m[1], sz, m[3], m[4], 8));
                    a.jobs.add(c.new Stash(null, 0));
                } else if (k == 2) {   // one carries what comes up
                    List<Agent> diggers = new ArrayList<Agent>(c.agents.subList(0, 2));
                    a.jobs.add(new Courier(c, diggers));
                } else {   // the rest clear the ground for the house
                    a.jobs.add(new Crew.Work(c.base[0] + 0.5, c.base[1], c.base[2] + 0.5, 20, null, null));
                }
                k++;
            }
        }
    }

    // ---- stone tools, the furnace, charcoal and torches ---------------------------------------------------

    /** take things from the chest: walk there, the lid up, the hand full */
    static class Take implements Crew.Task {
        final Crew c;
        final Item item;
        final int n;
        int state, ticks;
        Crew.GoTo walk;
        Take(Crew c, Item item, int n) { this.c = c; this.item = item; this.n = n; }
        public boolean step(Agent a, WorldServer w) {
            if (c.chest == null) return true;
            if (state == 0) { walk = new Crew.GoTo(c.chest[0] + 0.5, c.chest[1], c.chest[2] + 0.5, 2.3); state = 1; }
            if (state == 1) { if (!walk.step(a, w)) return false; state = 2; }
            Crew.face(a, c.chest[0] + 0.5, c.chest[1] + 0.5, c.chest[2] + 0.5);
            ticks++;
            if (ticks == 3) w.addBlockEvent(c.chest[0], c.chest[1], c.chest[2], Blocks.chest, 1, 1);
            if (ticks == 9) {
                IInventory inv = c.chestInv(w);
                int got = inv == null ? 0 : Crew.take(inv, item, n);
                if (got > 0) { Crew.give(a.bag, new ItemStack(item, got)); a.setCurrentItemOrArmor(0, new ItemStack(item, 1)); }
                a.swingItem();
            }
            if (ticks == 16) w.addBlockEvent(c.chest[0], c.chest[1], c.chest[2], Blocks.chest, 1, 0);
            return ticks >= 18;
        }
    }

    static int[] furnace;

    static class Upgrade extends Crew.Phase {
        Upgrade() { max = 20 * 90; }
        void start(final Crew c, WorldServer w, EntityPlayerMP p) {
            c.placeChest(w);
            final int[] t = c.table;
            if (t == null || c.agents.isEmpty()) return;
            // the furnace beside the table, lit by the one who builds it
            int[] d = c.doorDir();
            furnace = new int[] { t[0] + d[1] * -2, t[1], t[2] + d[0] * -2 };
            int fy = Crew.ground(w, furnace[0], t[1], furnace[2]);
            if (fy >= 0) furnace[1] = fy;
            Agent smith = c.agents.get(c.agents.size() - 1);
            smith.jobs.add(new Take(c, Item.getItemFromBlock(Blocks.cobblestone), 8));
            smith.jobs.add(new Crew.Work(t[0] + 0.5, t[1] + 0.9, t[2] + 0.5, 40, null, "random.wood_click"));
            smith.jobs.add(new Crew.Place(furnace[0], furnace[1], furnace[2], Blocks.lit_furnace, 2));
            smith.jobs.add(new Crew.Work(furnace[0] + 0.5, furnace[1] + 0.6, furnace[2] + 0.5, 70, new ItemStack(Blocks.torch, 1), "fire.fire"));
            int k = 0;
            for (Agent a : c.agents) {
                if (a == smith) continue;
                double ang = Math.toRadians(k++ * 120 + 30);
                a.jobs.add(new Take(c, Item.getItemFromBlock(Blocks.cobblestone), 3));
                a.jobs.add(new Crew.GoTo(t[0] + 0.5 + Math.sin(ang) * 1.7, t[1], t[2] + 0.5 + Math.cos(ang) * 1.7, 0.9));
                a.jobs.add(new Crew.Work(t[0] + 0.5, t[1] + 0.9, t[2] + 0.5, 60 + 12 * k, new ItemStack(Items.stone_pickaxe), "random.anvil_land"));
            }
        }
    }

    /** someone runs to the player with a new tool and throws it to them, looking at them */
    static class Gift extends Crew.Phase {
        void start(Crew c, final WorldServer w, final EntityPlayerMP p) {
            if (c.agents.isEmpty()) return;
            Agent a = c.agents.get(Math.min(1, c.agents.size() - 1));
            a.jobs.add(new Crew.Task() {
                int state, ticks;
                Crew.GoTo walk;
                public boolean step(Agent g, WorldServer ww) {
                    if (state == 0) { walk = new Crew.GoTo(p.posX, p.boundingBox.minY, p.posZ, 2.4); state = 1; }
                    if (state == 1) {
                        if (Crew.dist(g, p.posX, p.boundingBox.minY, p.posZ) > 2.6) { if (walk.step(g, ww)) walk = new Crew.GoTo(p.posX, p.boundingBox.minY, p.posZ, 2.4); return false; }
                        state = 2;
                    }
                    g.getNavigator().clearPathEntity();
                    Crew.face(g, p.posX, p.posY + p.getEyeHeight(), p.posZ);
                    if (++ticks == 14) {
                        g.bag.clear();
                        g.bag.add(new ItemStack(Items.stone_pickaxe));
                        g.bag.add(new ItemStack(Blocks.torch, 8));
                        toss(g, p.posX, p.posY + 1.0, p.posZ, ww, null);
                        g.setCurrentItemOrArmor(0, new ItemStack(Items.stone_pickaxe));
                    }
                    return ticks > 40;
                }
            });
            for (Agent g : c.agents) if (g != a) g.jobs.add(new Crew.Look(p.posX, p.posY + 1.5, p.posZ, 50));
        }
    }

    // ---- the base: a house with a stepped roof, a door, windows; a fence with a gate; torches --------------

    static class Base extends Crew.House {
        Base() { max = 20 * 260; }
        void start(final Crew c, WorldServer w, EntityPlayerMP p) {
            c.placeChest(w);
            int[] s = Crew.site;
            int x0 = s[0] - 1, y0 = s[1], z0 = s[2] - 1;   // 7x7 from the 5x5 site
            int[] d = c.doorDir();
            IInventory inv = c.chestInv(w);
            int stone = inv == null ? 0 : Crew.count(inv, Item.getItemFromBlock(Blocks.cobblestone));
            int cx = x0 + 3, cz = z0 + 3;
            for (int y = y0; y <= y0 + 3; y++)
                for (int x = x0; x <= x0 + 6; x++)
                    for (int z = z0; z <= z0 + 6; z++) {
                        boolean edge = x == x0 || x == x0 + 6 || z == z0 || z == z0 + 6;
                        if (!edge) continue;
                        boolean corner = (x == x0 || x == x0 + 6) && (z == z0 || z == z0 + 6);
                        int lx = x - cx, lz = z - cz;
                        boolean front = d[0] != 0 ? lx == 3 * d[0] : lz == 3 * d[1];
                        boolean door = front && (d[0] != 0 ? lz == 0 : lx == 0) && y <= y0 + 1;
                        boolean side = !front && (d[0] != 0 ? Math.abs(lz) == 3 || lx == -3 * d[0] : Math.abs(lx) == 3 || lz == -3 * d[1]);
                        boolean window = (y == y0 + 1 || y == y0 + 2) && !corner && (Math.abs(lx) == 1 && Math.abs(lz) == 3 || Math.abs(lz) == 1 && Math.abs(lx) == 3) && !front;
                        if (door) continue;
                        if (window) { plan.add(new int[] { x, y, z, 4 }); continue; }   // fence as window bars
                        plan.add(new int[] { x, y, z, corner ? 1 : y == y0 && stone >= 24 ? 2 : 0 });
                    }
            for (int x = x0 - 1; x <= x0 + 7; x++)
                for (int z = z0 - 1; z <= z0 + 7; z++) plan.add(new int[] { x, y0 + 4, z, 0 });
            for (int x = x0 + 1; x <= x0 + 5; x++)
                for (int z = z0 + 1; z <= z0 + 5; z++) plan.add(new int[] { x, y0 + 5, z, 0 });
            plan.add(new int[] { cx, y0 + 6, cz, 1 });
            // the fence around the base, a gate in front of the door, torches on its corners and by the gate
            int fx0 = x0 - 5, fx1 = x0 + 11, fz0 = z0 - 5, fz1 = z0 + 11;
            for (int x = fx0; x <= fx1; x++)
                for (int z = fz0; z <= fz1; z++) {
                    if (x != fx0 && x != fx1 && z != fz0 && z != fz1) continue;
                    int lx = x - cx, lz = z - cz;
                    boolean gate = d[0] != 0 ? lx * d[0] > 0 && x == (d[0] > 0 ? fx1 : fx0) && Math.abs(lz) <= 1
                            : lz * d[1] > 0 && z == (d[1] > 0 ? fz1 : fz0) && Math.abs(lx) <= 1;
                    if (gate) continue;
                    int gy = Crew.ground(w, x, y0, z);
                    if (gy < 0 || Math.abs(gy - y0) > 2) continue;
                    if (Day1.mine != null && Math.abs(x - mine[0]) <= 2 && Math.abs(z - mine[2]) <= 2) continue;
                    plan.add(new int[] { x, gy, z, 4 });
                    boolean corner = (x == fx0 || x == fx1) && (z == fz0 || z == fz1);
                    boolean byGate = d[0] != 0 ? Math.abs(lz) == 2 && x == (d[0] > 0 ? fx1 : fx0) : Math.abs(lx) == 2 && z == (d[1] > 0 ? fz1 : fz0);
                    if (corner || byGate || (x - fx0 + z - fz0) % 8 == 0) plan.add(new int[] { x, gy + 1, z, 5 });
                }
            // torches either side of the door, on the wall's outside (standing on the ground)
            int ddx = d[0], ddz = d[1];
            int[] door = { cx + 3 * ddx, y0, cz + 3 * ddz };
            plan.add(new int[] { door[0] + ddx + (ddz != 0 ? 1 : 0), y0, door[2] + ddz + (ddx != 0 ? 1 : 0), 5 });
            plan.add(new int[] { door[0] + ddx - (ddz != 0 ? 1 : 0), y0, door[2] + ddz - (ddx != 0 ? 1 : 0), 5 });
            doorAt = door;
            show(c, w);
            for (Agent a : c.agents) a.jobs.add(new Crew.Build(this));
            final Base self = this;
            c.agents.get(0).jobs.add(new Crew.Task() {   // the door last, by the first agent
                public boolean step(Agent a, WorldServer ww) {
                    int[] dd = self.doorAt;
                    if (!Crew.soft(ww, dd[0], dd[1], dd[2])) return true;
                    int meta = self.doorMeta(c);
                    Crew.face(a, dd[0] + 0.5, dd[1] + 1, dd[2] + 0.5);
                    a.swingItem();
                    ww.setBlock(dd[0], dd[1], dd[2], Blocks.wooden_door, meta, 2);
                    ww.setBlock(dd[0], dd[1] + 1, dd[2], Blocks.wooden_door, 8, 2);
                    ww.playSoundEffect(dd[0] + 0.5, dd[1] + 0.5, dd[2] + 0.5, "dig.wood", 1F, 0.8F);
                    return true;
                }
            });
            Director.log("crew: base plan of " + plan.size() + " blocks" + (stone >= 24 ? " (stone footing)" : ""));
        }
        int[] doorAt;
        int doorMeta(Crew c) {   // door facing: 0 west(-x) 1 north(-z) 2 east(+x) 3 south(+z) in 1.7.10 terms of where it opens from
            int[] d = c.doorDir();
            if (d[0] > 0) return 2;
            if (d[0] < 0) return 0;
            if (d[1] > 0) return 3;
            return 1;
        }
    }

    /** nightfall: the crew comes to the mouth of the mine and looks down into it; one puts a torch there */
    static class Gather extends Crew.Phase {
        Gather() { max = 20 * 40; }
        void start(Crew c, WorldServer w, EntityPlayerMP p) {
            int[] m = mine;
            if (m == null) { new Crew.Cheer().start(c, w, p); return; }
            int k = 0, n = c.agents.size();
            double mx = m[0] + 0.5 + m[3] * 2.5 + (m[3] == 0 ? 0.5 : 0), mz = m[2] + 0.5 + m[4] * 2.5 + (m[4] == 0 ? 0.5 : 0);
            front = new double[] { mx, m[1] - 2, mz };
            Crew.front = front;
            for (Agent a : c.agents) {
                double ang = Math.toRadians(-60 + 120.0 * k / Math.max(1, n - 1)) + Math.atan2(-m[3], -m[4]);
                double tx = m[0] + 0.5 - m[3] * 1.5 + Math.sin(ang) * 2.2, tz = m[2] + 0.5 - m[4] * 1.5 + Math.cos(ang) * 2.2;
                int gy = Crew.ground(w, MathHelper.floor_double(tx), m[1], MathHelper.floor_double(tz));
                a.jobs.add(new Crew.GoTo(tx, gy < 0 ? m[1] : gy, tz, 0.7));
                if (k == 0) a.jobs.add(new Crew.Place(m[0] - m[3] + (m[3] == 0 ? -1 : 0), m[1], m[2] - m[4] + (m[4] == 0 ? -1 : 0), Blocks.torch, 5));
                a.jobs.add(new Crew.Look(mx, m[1] - 2.5, mz, 20 * 30));
                k++;
            }
        }
    }

    static volatile double[] front;
}
