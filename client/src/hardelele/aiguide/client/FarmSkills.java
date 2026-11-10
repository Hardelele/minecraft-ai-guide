package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityAgeable;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityChicken;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.passive.EntitySheep;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemHoe;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;

/**
 * The farm's, the trees' and the pasture's skills of contract 3 (docs/rl/CONTRACT-v2.md): till, plant (wheat,
 * saplings), harvest and replant, gather (seeds from grass, saplings from leaves, sugar cane), build a pen, lure an
 * animal into it, breed, slaughter. Each acts through the body as a player does: it walks to the block, looks at its
 * face and the item's own use happens on it (a hoe, seeds, a sapling); breaking is the body's dig, the drops are picked
 * up. Targets come from the crew's memory (Exploration) or what the crew made (RlWorld). Also the environment's growth
 * of the crew's crops, saplings and young (grow) and the facts for the observation and the mask (facts).
 */
final class FarmSkills {
    private FarmSkills() { }

    static final double SEE = 24;

    static MindSkills.Skill make(Crew c, Agent a, WorldServer w, Mind.Call call) {
        int n = call.num("count", 8);
        switch (call.tool) {
            case "till": return till(c, a, w, MindSkills.clamp(n, 1, 16));
            case "plant": return "sapling".equals(call.arg("what")) ? plantSaplings(c, a, w, MindSkills.clamp(call.num("count", 4), 1, 8)) : plantWheat(c, a, w, MindSkills.clamp(n, 1, 16));
            case "harvest": return harvest(c, a, w, MindSkills.clamp(call.num("count", 16), 1, 32), !"no".equals(call.arg("replant")));
            case "gather": return gather(c, a, w, call.arg("what"), MindSkills.clamp(n, 1, 32));
            case "build_pen": return buildPen(c, a, w);
            case "lure": return lure(c, a, w);
            case "breed": return breed(c, a, w);
            case "slaughter": return slaughter(c, a, w);
            default: return null;
        }
    }

    // ---- the bag ---------------------------------------------------------------------------------------------------

    static ItemStack hoe(Agent a) {
        ItemStack best = null;
        for (ItemStack s : a.bag)
            if (s != null && s.getItem() instanceof ItemHoe && (best == null || EpochScore.tier(MindSkills.id(s)) > EpochScore.tier(MindSkills.id(best)))) best = s;
        return best;
    }

    static ItemStack stack(Agent a, Item item) {
        for (ItemStack s : a.bag) if (s != null && s.getItem() == item && s.stackSize > 0) return s;
        return null;
    }

    static int count(Agent a, Item item) { return Skills.count(a.bag, item, -1); }

    /** the bait of an animal's kind: wheat for cows and sheep, seeds for chickens, carrots for pigs */
    static Item bait(Entity e) {
        if (e instanceof EntityCow || e instanceof EntitySheep) return Items.wheat;
        if (e instanceof EntityChicken) return Items.wheat_seeds;
        if (e instanceof EntityPig) return Items.carrot;
        return null;
    }

    // ---- the place: what is not to be dug or built on --------------------------------------------------------------

    /** a column the crew keeps for something else: the wall's ring and gate, the moat, the pen, the stations, the base's middle */
    static boolean kept(Crew c, int x, int z) {
        Defense.Perimeter p = DefenseWorld.ring;
        if (p != null && p.ring && Math.max(Math.abs(x - p.cx), Math.abs(z - p.cz)) == p.r) return true;
        if (DefenseWorld.moat != null) for (int[] q : DefenseWorld.moat) if (q[0] == x && q[2] == z) return true;
        RlWorld rw = RlWorld.now;
        if (rw.pen != null && Math.max(Math.abs(x - rw.pen[0]), Math.abs(z - rw.pen[2])) <= rw.penR + 1) return true;
        for (int[] s : new int[][] { c.chest, c.table, Day1.furnace, rw.enchTable, rw.mouth })
            if (s != null && Math.abs(s[0] - x) <= 1 && Math.abs(s[2] - z) <= 1) return true;
        if (c.base != null && Math.abs(x - c.base[0]) <= 1 && Math.abs(z - c.base[2]) <= 1) return true;
        return false;
    }

    static boolean earth(Block b) { return b == Blocks.grass || b == Blocks.dirt; }

    /** water the crew has seen within r of a column */
    static boolean waterNear(List<int[]> water, int x, int y, int z, int r) {
        for (int[] q : water) if (Math.abs(q[0] - x) <= r && Math.abs(q[2] - z) <= r && Math.abs(q[1] - y) <= 1) return true;
        return false;
    }

    /** grass or dirt with air over it near the base that a hoe may till: { x, y (the block), z }, the best first */
    static List<int[]> tillSpots(Crew c, Agent a, WorldServer w, int most) {
        List<int[]> out = new ArrayList<int[]>();
        if (c.base == null) return out;
        final List<int[]> water = new ArrayList<int[]>(Exploration.knownBlocks(w, Blocks.water));
        water.addAll(Exploration.knownBlocks(w, Blocks.flowing_water));
        final RlWorld rw = RlWorld.now;
        final Map<int[], Double> score = new HashMap<int[], Double>();
        for (int dx = -10; dx <= 10; dx++)
            for (int dz = -10; dz <= 10; dz++) {
                int x = c.base[0] + dx, z = c.base[2] + dz;
                int g = Crew.ground(w, x, c.base[1], z);
                if (g < 0 || kept(c, x, z)) continue;
                int y = g - 1;
                if (!earth(w.getBlock(x, y, z)) || !w.isAirBlock(x, g, z) || rw.farm.contains(RlWorld.key(x, y, z))) continue;
                if (w.getBlock(x, g - 2, z).getMaterial().isLiquid()) continue;
                double s = Math.hypot(dx, dz) * 0.1;
                if (waterNear(water, x, y, z, 4)) s -= 5;
                for (int[] d : new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } })
                    if (rw.farm.contains(RlWorld.key(x + d[0], y, z + d[1]))) s -= 2;
                int[] p = { x, y, z };
                score.put(p, s);
                out.add(p);
            }
        Collections.sort(out, (p, q) -> Double.compare(score.get(p), score.get(q)));
        return out.size() > most ? new ArrayList<int[]>(out.subList(0, most)) : out;
    }

    /** grass or dirt near the base with room for a tree: 5 of air over it, 3 from any other sapling of the crew */
    static List<int[]> saplingSpots(Crew c, Agent a, WorldServer w, int most) {
        List<int[]> out = new ArrayList<int[]>();
        if (c.base == null) return out;
        RlWorld rw = RlWorld.now;
        for (int dx = -12; dx <= 12 && out.size() < most * 4; dx++)
            for (int dz = -12; dz <= 12 && out.size() < most * 4; dz++) {
                if (Math.abs(dx) + Math.abs(dz) < 4) continue;
                int x = c.base[0] + dx, z = c.base[2] + dz;
                int g = Crew.ground(w, x, c.base[1], z);
                if (g < 0 || kept(c, x, z) || !earth(w.getBlock(x, g - 1, z))) continue;
                if (rw.farm.contains(RlWorld.key(x, g - 1, z))) continue;
                boolean room = true;
                for (int k = 0; k < 5 && room; k++) room = w.isAirBlock(x, g + k, z);
                if (!room) continue;
                boolean apart = true;
                for (long s : rw.saplings) { int[] q = RlWorld.unkey(s); if (Math.abs(q[0] - x) < 3 && Math.abs(q[2] - z) < 3) apart = false; }
                for (int[] q : out) if (Math.abs(q[0] - x) < 3 && Math.abs(q[2] - z) < 3) apart = false;
                if (apart) out.add(new int[] { x, g - 1, z });
            }
        final Agent me = a;
        Collections.sort(out, (p, q) -> Double.compare(Crew.dist(me, p[0] + 0.5, p[1], p[2] + 0.5), Crew.dist(me, q[0] + 0.5, q[1], q[2] + 0.5)));
        return out.size() > most ? new ArrayList<int[]>(out.subList(0, most)) : out;
    }

    // ---- the body's use of an item on a block's top face -------------------------------------------------------------

    /**
     * The right button with an item on the top of a block, as a player: to a cell from which the block's top is in
     * reach, the item in the hand, the eyes on the face, then the item's own use (ItemHoe, ItemSeeds, a sapling's
     * ItemBlock) by the agent's hands; one of a stack used up goes from the bag, a tool takes its wear.
     */
    static final class UseOn implements Crew.Task {
        final int x, y, z;
        final Item item;
        Crew.GoTo walk;
        int t, walks;
        UseOn(int x, int y, int z, Item item) { this.x = x; this.y = y; this.z = z; this.item = item; }

        public Result step(Agent a, WorldServer w) {
            double ex = a.posX - (x + 0.5), ey = a.posY + a.getEyeHeight() - (y + 1.0), ez = a.posZ - (z + 0.5);
            if (Math.sqrt(ex * ex + ey * ey + ez * ez) > Crew.REACH - 0.4) {
                if (walk == null) {
                    if (++walks > 3) return Result.NO_PATH;
                    walk = new Crew.GoTo(x + 0.5, y + 1, z + 0.5, 2.0);
                }
                Result r = walk.step(a, w);
                if (r == Result.RUNNING) return r;
                walk = null;
                return Result.RUNNING;
            }
            ItemStack s = stack(a, item);
            if (s == null && item == Items.wooden_hoe) s = hoe(a);
            if (s == null) return Result.NO_ITEMS;
            a.body.stop();
            a.body.hold(s);
            a.body.lookAt(x + 0.5, y + 1.0, z + 0.5);
            a.act(x, y, z, 3);
            if (!a.body.facing(x + 0.5, y + 1.0, z + 0.5, 25) && ++t < 20) return Result.RUNNING;
            FakePlayer p = Skills.hands(a, w);
            boolean ok;
            if (s.isStackable()) {   // seeds, a sapling: one of the stack into the world
                ItemStack one = s.copy();
                one.stackSize = 1;
                p.inventory.mainInventory[0] = one;
                ok = one.tryPlaceItemIntoWorld(p, w, x, y, z, 1, 0.5F, 1.0F, 0.5F) && one.stackSize == 0;
                if (ok) Skills.take(a, s.getItem(), s.getMetadata(), 1, Ledger.Way.PLACED);
            } else {   // a hoe: its own wear
                p.inventory.mainInventory[0] = s;
                ok = s.tryPlaceItemIntoWorld(p, w, x, y, z, 1, 0.5F, 1.0F, 0.5F);
                if (s.stackSize <= 0) { a.bag.remove(s); a.body.hold(null); Ledger.flow(Ledger.Way.INGREDIENT, s, "worn out"); }
            }
            p.inventory.mainInventory[0] = null;
            a.swingItem();
            if (ok) { Ground.changed(); Ledger.act(a, "use " + MindSkills.id(s)); }
            return ok ? Result.OK : Result.BLOCKED;
        }
    }

    /** a block broken from where the eyes reach it: to it first, then the body's dig */
    static final class BreakAt implements Crew.Task {
        final int x, y, z;
        Crew.GoTo walk;
        Crew.Break br;
        int walks;
        BreakAt(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }

        public Result step(Agent a, WorldServer w) {
            if (w.isAirBlock(x, y, z)) return Result.OK;
            if (Crew.eyeDist(a, x, y, z) > Crew.REACH - 0.4 && br == null) {
                if (walk == null) {
                    if (++walks > 3) return Result.NO_PATH;
                    walk = new Crew.GoTo(x + 0.5, y, z + 0.5, 2.0);
                }
                Result r = walk.step(a, w);
                if (r == Result.RUNNING) return r;
                walk = null;
                return Result.RUNNING;
            }
            if (br == null) br = new Crew.Break(x, y, z, 0.2);
            Result r = br.step(a, w);
            if (r == Result.RUNNING) return r;
            br = null;
            if ((r == Result.NOT_VISIBLE || r == Result.OUT_OF_REACH) && walks <= 3) { walk = new Crew.GoTo(x + 0.5, y, z + 0.5, 1.2); walks++; return Result.RUNNING; }
            return r;
        }
    }

    /** one sub-task after another on targets; OK when any was done, else the failure of the last */
    abstract static class Each extends MindSkills.Skill {
        final List<int[]> targets;
        final int want;
        int i, done, fails;
        Crew.Task cur;
        Result last = Result.GONE;
        Each(List<int[]> targets, int want) { this.targets = targets; this.want = want; }

        abstract Crew.Task task(Agent a, WorldServer w, int[] t);
        void ok(Agent a, WorldServer w, int[] t) { }
        String what() { return "done"; }

        Result run(Agent a, WorldServer w) {
            if (cur == null) {
                if (done >= want || i >= targets.size()) return finish();
                cur = task(a, w, targets.get(i));
                if (cur == null) { i++; return Result.RUNNING; }
            }
            Result r = cur.step(a, w);
            if (r == Result.RUNNING) return r;
            int[] t = targets.get(i++);
            cur = null;
            if (r == Result.OK) { done++; ok(a, w, t); }
            else { fails++; last = r; }
            return Result.RUNNING;
        }

        Result finish() {
            if (done == 0) {
                if (reason == null) { reason = last == Result.NO_PATH ? "NO_PATH" : last.name(); detail = "none " + what() + " of " + targets.size() + " tried"; }
                return last == Result.OK ? Result.BLOCKED : last;
            }
            detail = (detail.isEmpty() ? "" : detail + "; ") + done + " " + what() + (fails > 0 ? ", " + fails + " failed" : "");
            return Result.OK;
        }
    }

    // ---- till, plant ------------------------------------------------------------------------------------------------

    static MindSkills.Skill till(Crew c, Agent a, WorldServer w, int n) {
        if (hoe(a) == null) return MindSkills.failed("NO_HOE", "no hoe in the bag (wooden_hoe: 2 planks, 2 sticks at a table)");
        if (c.base == null) return MindSkills.failed("NO_BASE", "the crew has no base");
        List<int[]> l = tillSpots(c, a, w, n + 4);
        if (l.isEmpty()) return MindSkills.failed("NO_SPOT", "no grass or dirt with air over it near the base to till");
        Each e = new Each(l, n) {
            Crew.Task task(Agent ag, WorldServer ww, int[] t) {
                if (!earth(ww.getBlock(t[0], t[1], t[2])) || !ww.isAirBlock(t[0], t[1] + 1, t[2])) return null;
                return new UseOn(t[0], t[1], t[2], hoe(ag) == null ? Items.wooden_hoe : hoe(ag).getItem());
            }
            void ok(Agent ag, WorldServer ww, int[] t) {
                if (ww.getBlock(t[0], t[1], t[2]) == Blocks.farmland) RlWorld.now.farm.add(RlWorld.key(t[0], t[1], t[2]));
            }
            String what() { return "farmland tilled"; }
        };
        e.lease = Budget.ticks(20, 0, n, 0, 0, 40) + 20 * 8 * n;
        return e;
    }

    /** the crew's farmland with air over it: { x, y, z } of the farmland, nearest first */
    static List<int[]> emptyFarm(Agent a, WorldServer w) {
        List<int[]> l = new ArrayList<int[]>();
        for (long k : RlWorld.now.farm) {
            int[] p = RlWorld.unkey(k);
            if (w.getBlock(p[0], p[1], p[2]) == Blocks.farmland && w.isAirBlock(p[0], p[1] + 1, p[2])) l.add(p);
        }
        MindSkills.nearestFirst(l, a);
        return l;
    }

    static MindSkills.Skill plantWheat(Crew c, Agent a, WorldServer w, int n) {
        if (count(a, Items.wheat_seeds) == 0) return MindSkills.failed("NO_SEEDS", "no wheat_seeds in the bag (gather:seeds from tall grass, or a harvest)");
        if (RlWorld.now.farm.isEmpty()) return MindSkills.failed("NO_FARM", "the crew has tilled no farmland: till first");
        List<int[]> l = emptyFarm(a, w);
        if (l.isEmpty()) return MindSkills.failed("DONE_ALREADY", "no empty farmland: all of it is sown");
        Each e = new Each(l, n) {
            Crew.Task task(Agent ag, WorldServer ww, int[] t) {
                if (count(ag, Items.wheat_seeds) == 0 || ww.getBlock(t[0], t[1], t[2]) != Blocks.farmland || !ww.isAirBlock(t[0], t[1] + 1, t[2])) return null;
                return new UseOn(t[0], t[1], t[2], Items.wheat_seeds);
            }
            String what() { return "sown"; }
        };
        e.lease = 20 * (20 + 8 * n);
        return e;
    }

    static MindSkills.Skill plantSaplings(Crew c, Agent a, WorldServer w, int n) {
        final Item sap = Item.getItemFromBlock(Blocks.sapling);
        if (count(a, sap) == 0) return MindSkills.failed("NO_SAPLING", "no sapling in the bag (gather:saplings from leaves)");
        if (c.base == null) return MindSkills.failed("NO_BASE", "the crew has no base");
        List<int[]> l = saplingSpots(c, a, w, n + 2);
        if (l.isEmpty()) return MindSkills.failed("NO_SPOT", "no grass with room for a tree near the base");
        Each e = new Each(l, n) {
            Crew.Task task(Agent ag, WorldServer ww, int[] t) {
                if (count(ag, sap) == 0 || !earth(ww.getBlock(t[0], t[1], t[2])) || !ww.isAirBlock(t[0], t[1] + 1, t[2])) return null;
                return new UseOn(t[0], t[1], t[2], sap);
            }
            void ok(Agent ag, WorldServer ww, int[] t) {
                if (ww.getBlock(t[0], t[1] + 1, t[2]) == Blocks.sapling) RlWorld.now.saplings.add(RlWorld.key(t[0], t[1] + 1, t[2]));
            }
            String what() { return "saplings planted"; }
        };
        e.lease = 20 * (20 + 10 * n);
        return e;
    }

    // ---- harvest ----------------------------------------------------------------------------------------------------

    static MindSkills.Skill harvest(Crew c, Agent a, WorldServer w, int n, final boolean replant) {
        List<int[]> ripe = new ArrayList<int[]>();
        for (long k : RlWorld.now.farm) {
            int[] p = RlWorld.unkey(k);
            if (w.getBlock(p[0], p[1] + 1, p[2]) == Blocks.wheat && w.getBlockMetadata(p[0], p[1] + 1, p[2]) >= 7) ripe.add(p);
        }
        if (ripe.isEmpty()) return MindSkills.failed("NOT_RIPE", "no ripe wheat on the crew's farm yet (it grows: wait or do something else)");
        MindSkills.nearestFirst(ripe, a);
        final int wheat0 = count(a, Items.wheat);
        Each e = new Each(ripe, n) {
            Crew.Task task(Agent ag, WorldServer ww, final int[] t) {
                if (ww.getBlock(t[0], t[1] + 1, t[2]) != Blocks.wheat || ww.getBlockMetadata(t[0], t[1] + 1, t[2]) < 7) return null;
                return new Crew.Task() {   // break the wheat, pick up, sow again
                    final BreakAt br = new BreakAt(t[0], t[1] + 1, t[2]);
                    final Crew.Collect pick = new Crew.Collect(t[0] + 0.5, t[1] + 1, t[2] + 0.5, 3);
                    UseOn sow;
                    int state;
                    public Result step(Agent g, WorldServer x) {
                        if (state == 0) {
                            Result r = br.step(g, x);
                            if (r == Result.RUNNING) return r;
                            if (r != Result.OK) return r;
                            state = 1;
                        }
                        if (state == 1) {
                            if (pick.step(g, x) == Result.RUNNING) return Result.RUNNING;
                            state = 2;
                        }
                        if (!replant || count(g, Items.wheat_seeds) == 0 || x.getBlock(t[0], t[1], t[2]) != Blocks.farmland || !x.isAirBlock(t[0], t[1] + 1, t[2])) return Result.OK;
                        if (sow == null) sow = new UseOn(t[0], t[1], t[2], Items.wheat_seeds);
                        Result r = sow.step(g, x);
                        return r == Result.RUNNING ? r : Result.OK;   // the harvest is done even if the sowing failed
                    }
                };
            }
            Result finish() {
                Result r = super.finish();
                int got = count(lastAgent, Items.wheat) - wheat0;
                if (got > 0) RlEnv.renewable(lastAgent, RlScore.FOOD_RENEW, got * 5.0 / 3);
                if (r == Result.OK) detail += ", +" + Math.max(0, got) + " wheat";
                return r;
            }
            Agent lastAgent;
            Result run(Agent ag, WorldServer ww) { lastAgent = ag; return super.run(ag, ww); }
            String what() { return "wheat harvested"; }
        };
        e.lease = 20 * (20 + 10 * Math.min(n, ripe.size()));
        return e;
    }

    // ---- gather -----------------------------------------------------------------------------------------------------

    static List<int[]> seen(Agent a, WorldServer w, Block[] kinds, double r) {
        List<int[]> l = new ArrayList<int[]>();
        for (Block b : kinds) for (int[] p : Exploration.knownBlocks(w, b)) if (MindSkills.hdist(a, p) <= r) l.add(p);
        return l;
    }

    static MindSkills.Skill gather(Crew c, Agent a, WorldServer w, String what, int n) {
        String t = what == null ? "" : what.trim();
        List<int[]> l = new ArrayList<int[]>();
        if (t.equals("seeds")) {
            for (int[] p : seen(a, w, new Block[] { Blocks.tallgrass }, SEE)) if (w.getBlock(p[0], p[1], p[2]) == Blocks.tallgrass) l.add(p);
        } else if (t.equals("saplings")) {
            for (int[] p : seen(a, w, new Block[] { Blocks.leaves, Blocks.leaves2 }, SEE)) {
                Block b = w.getBlock(p[0], p[1], p[2]);
                if (b != Blocks.leaves && b != Blocks.leaves2) continue;
                int g = Crew.ground(w, p[0], p[1], p[2]);   // in reach from the ground under or beside it
                boolean low = false;
                for (int dy = 1; dy <= 5 && !low; dy++)
                    for (int[] d : new int[][] { { 0, 0 }, { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } })
                        if (Crew.stand(w, p[0] + d[0], p[1] - dy, p[2] + d[1])) { low = true; break; }
                if (low || g >= 0 && p[1] - g <= 4) l.add(p);
            }
        } else if (t.equals("sugar_cane")) {
            for (int[] p : seen(a, w, new Block[] { Blocks.reeds }, SEE))   // not the bottom one: it grows again
                if (w.getBlock(p[0], p[1], p[2]) == Blocks.reeds && w.getBlock(p[0], p[1] - 1, p[2]) == Blocks.reeds && w.getBlock(p[0], p[1] - 2, p[2]) != Blocks.reeds) l.add(p);
        } else return MindSkills.failed("BAD_ARG", "'" + what + "': seeds, saplings or sugar_cane");
        if (l.isEmpty()) return MindSkills.failed("NOT_SEEN", "no " + (t.equals("seeds") ? "tall grass" : t.equals("saplings") ? "leaves within reach" : "sugar cane two high")
                + " the crew has seen near: look_around or go elsewhere");
        MindSkills.nearestFirst(l, a);
        final String kind = t;
        Each e = new Each(l, n) {
            Crew.Task task(Agent ag, WorldServer ww, final int[] p) {
                Block b = ww.getBlock(p[0], p[1], p[2]);
                if (kind.equals("seeds") ? b != Blocks.tallgrass : kind.equals("saplings") ? b != Blocks.leaves && b != Blocks.leaves2 : b != Blocks.reeds) return null;
                return new TaskBoard.Seq(new BreakAt(p[0], p[1], p[2]), new Crew.Collect(p[0] + 0.5, p[1], p[2] + 0.5, 4));
            }
            String what() { return kind.equals("seeds") ? "grass cut" : kind.equals("saplings") ? "leaves broken" : "cane cut"; }
        };
        e.lease = 20 * (20 + 6 * Math.min(n, l.size()));
        return e;
    }

    // ---- the pen ------------------------------------------------------------------------------------------------------

    /** the fence ring's cells round a centre (Chebyshev r + 1), the gate's first */
    static List<int[]> penRing(int cx, int y, int cz, int r, int[] gate) {
        List<int[]> l = new ArrayList<int[]>();
        int R = r + 1;
        for (int x = cx - R; x <= cx + R; x++)
            for (int z = cz - R; z <= cz + R; z++)
                if (Math.max(Math.abs(x - cx), Math.abs(z - cz)) == R && !(gate != null && x == gate[0] && z == gate[2])) l.add(new int[] { x, y, z });
        return l;
    }

    /** a flat dry place for a 5 x 5 pen outside the wall and the moat, near the base: { cx, y, cz } */
    static int[] penSpot(Crew c, Agent a, WorldServer w, int r) {
        int[] best = null;
        double bd = 1e9;
        Defense.Perimeter p = DefenseWorld.ring;
        int keep = p != null && p.ring ? p.r + 3 : 4;
        for (int dx = -20; dx <= 20; dx++)
            for (int dz = -20; dz <= 20; dz++) {
                int cx = c.base[0] + dx, cz = c.base[2] + dz;
                if (Math.max(Math.abs(dx), Math.abs(dz)) < keep + r + 1) continue;
                int y = Crew.ground(w, cx, c.base[1], cz);
                if (y < 0) continue;
                boolean ok = true;
                for (int x = cx - r - 1; x <= cx + r + 1 && ok; x++)
                    for (int z = cz - r - 1; z <= cz + r + 1 && ok; z++) {
                        if (!Crew.stand(w, x, y, z) || w.getBlock(x, y - 1, z).getMaterial().isLiquid()) ok = false;
                        else if (kept(c, x, z) && RlWorld.now.pen == null) ok = !(DefenseWorld.ring != null && Math.max(Math.abs(x - DefenseWorld.ring.cx), Math.abs(z - DefenseWorld.ring.cz)) == DefenseWorld.ring.r);
                        if (ok && DefenseWorld.moat != null) for (int[] q : DefenseWorld.moat) if (q[0] == x && q[2] == z) ok = false;
                        if (ok && RlWorld.now.farm.contains(RlWorld.key(x, y - 1, z))) ok = false;
                    }
                if (!ok) continue;
                double d = Math.hypot(dx, dz);
                if (d < bd) { bd = d; best = new int[] { cx, y, cz }; }
            }
        return best;
    }

    static MindSkills.Skill buildPen(final Crew c, Agent a, WorldServer w) {
        final RlWorld rw = RlWorld.now;
        if (rw.pen != null && penStanding(w) >= 12) return MindSkills.failed("DONE_ALREADY", "the pen stands at " + rw.pen[0] + " " + rw.pen[1] + " " + rw.pen[2]);
        if (c.base == null) return MindSkills.failed("NO_BASE", "the crew has no base");
        final Item fence = Item.getItemFromBlock(Blocks.fence), gateItem = Item.getItemFromBlock(Blocks.fence_gate);
        if (count(a, fence) < 15 || count(a, gateItem) == 0)
            return MindSkills.failed("NO_FENCES", "a pen takes 15 fences and a fence_gate; the bag has " + count(a, fence) + " and " + count(a, gateItem));
        final int r = 1;
        final int[] at = rw.pen != null ? rw.pen : penSpot(c, a, w, r);
        if (at == null) return MindSkills.failed("NO_SPOT", "no flat dry ground 5 x 5 near the base outside the wall");
        // the gate: in the middle of the side that looks at the base
        int ddx = c.base[0] - at[0], ddz = c.base[2] - at[2];
        final int[] gate = Math.abs(ddx) >= Math.abs(ddz) ? new int[] { at[0] + Integer.signum(ddx) * (r + 1), at[1], at[2] } : new int[] { at[0], at[1], at[2] + Integer.signum(ddz) * (r + 1) };
        rw.pen = at;
        rw.penR = r;
        rw.penGate = gate;
        List<int[]> cells = penRing(at[0], at[1], at[2], r, gate);
        cells.add(gate);
        Each e = new Each(cells, cells.size()) {
            Crew.Task task(Agent ag, WorldServer ww, int[] t) {
                if (!Crew.soft(ww, t[0], t[1], t[2])) return null;
                boolean g = t == gate;
                return new Skills.PlaceFrom(t[0], t[1], t[2], g ? gateItem : fence, 0).pillar(0);
            }
            String what() { return "fences put"; }
        };
        e.lease = 20 * (30 + 8 * cells.size());
        return e;
    }

    /** the pen's fences and gate standing (of 16) */
    static int penStanding(WorldServer w) {
        RlWorld rw = RlWorld.now;
        if (rw.pen == null) return 0;
        int n = 0;
        for (int[] q : penRing(rw.pen[0], rw.pen[1], rw.pen[2], rw.penR, null)) {
            Block b = w.getBlock(q[0], q[1], q[2]);
            if (b == Blocks.fence || b == Blocks.fence_gate) n++;
        }
        return n;
    }

    static boolean gateOpen(WorldServer w) {
        int[] g = RlWorld.now.penGate;
        return g != null && w.getBlock(g[0], g[1], g[2]) == Blocks.fence_gate && BlockFenceGate.isFenceGateOpen(w.getBlockMetadata(g[0], g[1], g[2]));
    }

    /** the animals in the pen (inside its fence), the adults or all */
    @SuppressWarnings("unchecked")
    static List<EntityAnimal> inPen(WorldServer w, boolean adults) {
        List<EntityAnimal> l = new ArrayList<EntityAnimal>();
        RlWorld rw = RlWorld.now;
        if (rw.pen == null) return l;
        int R = rw.penR + 2;
        for (Object o : w.getEntitiesWithinAABB(EntityAnimal.class, AxisAlignedBB.getBoundingBox(rw.pen[0] - R, rw.pen[1] - 2, rw.pen[2] - R, rw.pen[0] + R + 1, rw.pen[1] + 3, rw.pen[2] + R + 1))) {
            EntityAnimal e = (EntityAnimal) o;
            if (e.isDead || e.getHealth() <= 0 || !rw.inPen(e.posX, e.posZ)) continue;
            if (adults && e.isChild()) continue;
            l.add(e);
        }
        return l;
    }

    /** the gate's cell just outside the pen and just inside */
    static int[] outside(int sign) {
        RlWorld rw = RlWorld.now;
        int[] g = rw.penGate, p = rw.pen;
        int dx = Integer.signum(g[0] - p[0]), dz = Integer.signum(g[2] - p[2]);
        return new int[] { g[0] + dx * sign, g[1], g[2] + dz * sign };
    }

    static MindSkills.Skill lure(final Crew c, Agent a, WorldServer w) {
        final RlWorld rw = RlWorld.now;
        if (rw.pen == null || rw.penGate == null) return MindSkills.failed("NO_PEN", "the crew has no pen: build_pen first");
        EntityLiving prey = null;
        for (EntityLiving e : RlSkills.animals(a, w, SEE)) {
            Item b = bait(e);
            if (b == null || count(a, b) == 0 || rw.inPen(e.posX, e.posZ) || e instanceof EntityAgeable && ((EntityAgeable) e).isChild()) continue;
            prey = e;
            break;
        }
        if (prey == null) {
            boolean any = !RlSkills.animals(a, w, SEE).isEmpty();
            return any ? MindSkills.failed("NO_BAIT", "no bait in the bag for the animals in sight (wheat: cows, sheep; seeds: chickens; carrots: pigs)")
                    : MindSkills.failed("NO_ANIMAL", "no animal outside the pen in sight");
        }
        Lure l = new Lure(prey, bait(prey));
        l.lease = 20 * 105;
        return l;
    }

    /**
     * To the animal with its bait in the hand, back to the pen and in through the gate: the animal follows the bait as
     * it follows a player's (EntityAITempt: the game tempts only players, so here for the agent); once it is in, the
     * bait away, out of the pen, the gate shut behind.
     */
    static final class Lure extends MindSkills.Skill {
        final EntityLiving prey;
        final Item bait;
        int t, state, opens, waitIn;
        Crew.GoTo walk;
        Lure(EntityLiving prey, Item bait) { this.prey = prey; this.bait = bait; }

        Result run(Agent a, WorldServer w) {
            RlWorld rw = RlWorld.now;
            if (++t > 20 * 90) { reason = "TIMEOUT"; detail = "the " + prey.getCommandSenderName() + " did not come into the pen in 90 s (state " + state + ")"; return Result.TIMEOUT; }
            if (prey.isDead || rw.pen == null) { reason = "GONE"; detail = "the animal or the pen is gone"; return Result.GONE; }
            ItemStack s = stack(a, bait);
            if (s == null) { reason = "NO_BAIT"; detail = "no " + MindSkills.id(new ItemStack(bait)) + " left"; return Result.NO_ITEMS; }
            boolean holding = state < 3;
            if (holding) {
                a.body.hold(s);
                if (a.getDistanceToEntity(prey) <= 10 && a.getDistanceToEntity(prey) > 2.0 && t % 2 == 0) {   // the tempting, as the game's for a player (EntityAITempt: each tick, to 2.5 of it)
                    prey.getNavigator().tryMoveToEntityLiving(a, 1.25);
                    prey.getLookHelper().setLookPositionWithEntity(a, 30.0F, (float) prey.getVerticalFaceSpeed());
                }
            }
            int[] out = outside(1), in = outside(-1), g = rw.penGate;
            switch (state) {
                case 0:   // to the animal
                    if (a.getDistanceToEntity(prey) <= 3.5) { state = 1; walk = null; return Result.RUNNING; }
                    if (t % 10 == 1 || a.body.pathless()) a.body.walkTo(prey, 1.0);
                    if (a.getDistanceToEntity(prey) > SEE + 8) { reason = "GONE"; detail = prey.getCommandSenderName() + " ran off"; return Result.GONE; }
                    return Result.RUNNING;
                case 1:   // back to the gate, slowly enough that it follows
                    if (walk == null) walk = new Crew.GoTo(out[0] + 0.5, out[1], out[2] + 0.5, 0.8);
                    if (a.getDistanceToEntity(prey) > 7) { a.body.stop(); if (t % 20 == 0) walk = null; state = prey.getDistanceToEntity(a) > 12 ? 0 : 1; return Result.RUNNING; }
                    Result r = walk.step(a, w);
                    if (r == Result.RUNNING) return r;
                    walk = null;
                    if (!gateOpen(w)) {   // open it
                        if (++opens > 5) { reason = "BLOCKED"; detail = "the gate of the pen would not open"; return Result.BLOCKED; }
                        Result o = a.body.activate(w, g[0], g[1], g[2]);
                        return o == Result.RUNNING || o == Result.OK ? Result.RUNNING : o;
                    }
                    state = 2;
                    return Result.RUNNING;
                case 2:   // in, to the far side; wait for it inside
                    if (walk == null) walk = new Crew.GoTo(rw.pen[0] * 2 - in[0] + 0.5, in[1], rw.pen[2] * 2 - in[2] + 0.5, 0.8);
                    if (walk.step(a, w) == Result.RUNNING && !rw.inPen(a.posX, a.posZ)) return Result.RUNNING;
                    if (!rw.inPen(prey.posX, prey.posZ)) {
                        if (a.getDistanceToEntity(prey) > 12) { state = 0; walk = null; }
                        else if (++waitIn > 20 * 15) { state = 1; walk = null; waitIn = 0; }   // stuck at the fence: out to the gate and lead it again
                        return Result.RUNNING;
                    }
                    a.body.hold(a.bestWeapon());   // the bait away: it stops following
                    state = 3;
                    walk = null;
                    return Result.RUNNING;
                case 3:   // out
                    if (walk == null) walk = new Crew.GoTo(out[0] + 0.5, out[1], out[2] + 0.5, 0.6);
                    Result r3 = walk.step(a, w);
                    if (r3 == Result.RUNNING) return r3;
                    walk = null;
                    state = 4;
                    return Result.RUNNING;
                default:   // the gate shut behind
                    if (gateOpen(w)) {
                        if (++opens > 8) { reason = "BLOCKED"; detail = "the gate would not shut"; return Result.BLOCKED; }
                        Result o = a.body.activate(w, g[0], g[1], g[2]);
                        return o == Result.RUNNING || o == Result.OK ? Result.RUNNING : o;
                    }
                    if (!rw.inPen(prey.posX, prey.posZ)) { state = 0; return Result.RUNNING; }   // it got out meanwhile
                    detail = prey.getCommandSenderName() + " led into the pen (" + inPen(w, false).size() + " in it now)";
                    Ledger.act(a, "lure " + prey.getCommandSenderName());
                    return Result.OK;
            }
        }
    }

    // ---- breed, slaughter ---------------------------------------------------------------------------------------------

    /** the adults of the pen that may breed now (grown, not in love, no cooldown), by kind */
    static Map<Class<?>, List<EntityAnimal>> pairs(WorldServer w) {
        Map<Class<?>, List<EntityAnimal>> m = new HashMap<Class<?>, List<EntityAnimal>>();
        for (EntityAnimal e : inPen(w, true)) {
            if (e.getGrowingAge() != 0 || e.isInLove() || bait(e) == null) continue;
            List<EntityAnimal> l = m.get(e.getClass());
            if (l == null) m.put(e.getClass(), l = new ArrayList<EntityAnimal>());
            l.add(e);
        }
        return m;
    }

    static MindSkills.Skill breed(Crew c, Agent a, WorldServer w) {
        if (RlWorld.now.pen == null) return MindSkills.failed("NO_PEN", "the crew has no pen");
        List<EntityAnimal> two = null;
        boolean anyPair = false;
        for (List<EntityAnimal> l : pairs(w).values()) {
            if (l.size() < 2) continue;
            anyPair = true;
            if (count(a, bait(l.get(0))) >= 2) { two = l.subList(0, 2); break; }
        }
        if (two == null) return anyPair ? MindSkills.failed("NO_BAIT", "two of a kind in the pen, but not two of their bait in the bag")
                : MindSkills.failed("NO_MATE", "no two grown animals of one kind in the pen that may breed now");
        final List<EntityAnimal> pair = new ArrayList<EntityAnimal>(two);
        MindSkills.Skill s = new MindSkills.Skill() {
            int i, t;
            Result run(Agent ag, WorldServer ww) {
                if (++t > 20 * 40) { reason = "TIMEOUT"; detail = "could not feed both in 40 s"; return Result.TIMEOUT; }
                if (i >= pair.size()) { detail = "fed two " + pair.get(0).getCommandSenderName() + ": in love"; return Result.OK; }
                EntityAnimal e = pair.get(i);
                if (e.isDead) { reason = "GONE"; detail = "the animal is gone"; return Result.GONE; }
                Item b = bait(e);
                ItemStack st = stack(ag, b);
                if (st == null) { reason = "NO_BAIT"; detail = "the bait ran out"; return Result.NO_ITEMS; }
                ag.body.hold(st);
                if (ag.getDistanceToEntity(e) > 3.0) {
                    if (t % 10 == 1 || ag.body.pathless()) ag.body.walkTo(e, 1.0);
                    return Result.RUNNING;
                }
                ag.body.stop();
                Crew.face(ag, e.posX, e.posY + e.getEyeHeight(), e.posZ);
                if (!ag.body.facing(e.posX, e.posY + e.getEyeHeight(), e.posZ, 30) && t % 20 != 0) return Result.RUNNING;
                Skills.take(ag, b, -1, 1, Ledger.Way.INGREDIENT);
                e.setInLove(null);   // as EntityAnimal.interact does for a player (no player: no stat)
                RlWorld.now.fedBy.put(e.getEntityId(), ag.skin());
                ag.swingItem();
                Ledger.act(ag, "feed " + e.getCommandSenderName());
                i++;
                return Result.RUNNING;
            }
        };
        s.lease = 20 * 50;
        return s;
    }

    static MindSkills.Skill slaughter(Crew c, Agent a, WorldServer w) {
        if (RlWorld.now.pen == null) return MindSkills.failed("NO_PEN", "the crew has no pen");
        List<EntityAnimal> adults = inPen(w, true);
        if (adults.size() < 3) return MindSkills.failed("NO_ANIMAL", "the pen has " + adults.size() + " grown animals: two are kept for breeding");
        EntityAnimal prey = null;
        for (EntityAnimal e : adults) if (RlWorld.now.born.contains(e.getEntityId())) { prey = e; break; }   // the pen's own first
        if (prey == null) prey = adults.get(0);
        Slaughter s = new Slaughter(prey);
        s.lease = 20 * 60;
        return s;
    }

    /** to the animal, a player's hits until it dies, its drops picked up; the meat of one born in the pen is renewable food */
    static final class Slaughter extends MindSkills.Skill {
        final EntityAnimal prey;
        final boolean born;
        Crew.Collect pick;
        int t, cool;
        double food0 = -1;
        Slaughter(EntityAnimal prey) { this.prey = prey; this.born = RlWorld.now.born.contains(prey.getEntityId()); }

        static double foodPoints(Agent a) {
            double n = 0;
            for (ItemStack s : a.bag) if (RlSkills.food(s)) n += ((ItemFood) s.getItem()).getHealAmount(s) * s.stackSize;
            return n;
        }

        Result run(Agent a, WorldServer w) {
            if (food0 < 0) food0 = foodPoints(a);
            if (pick != null) {
                if (pick.step(a, w) == Result.RUNNING) return Result.RUNNING;
                double got = foodPoints(a) - food0;
                if (born && got > 0) RlEnv.renewable(a, RlScore.FOOD_RENEW, got);
                detail = "killed " + prey.getCommandSenderName() + (got > 0 ? ", +" + Math.round(got) + " food points" : "") + (born ? " (born in the pen)" : "");
                return Result.OK;
            }
            if (prey.isDead || prey.getHealth() <= 0) {
                Ledger.act(a, "kill " + prey.getCommandSenderName());
                pick = new Crew.Collect(prey.posX, prey.posY, prey.posZ, 4);
                return Result.RUNNING;
            }
            if (++t > 20 * 40) { reason = "TIMEOUT"; detail = "could not kill " + prey.getCommandSenderName() + " in 40 s"; return Result.TIMEOUT; }
            if (cool > 0) cool--;
            ItemStack wp = a.bestWeapon();
            if (wp != null && a.getHeldItem() != wp) a.body.hold(wp);
            double d = a.getDistanceToEntity(prey);
            if (d > 2.6) {
                if (t % 8 == 1 || a.body.pathless()) a.body.walkTo(prey, 1.1);
                Crew.face(a, prey.posX, prey.posY + prey.getEyeHeight() * 0.8, prey.posZ);
                return Result.RUNNING;
            }
            a.body.stop();
            Crew.face(a, prey.posX, prey.posY + prey.getEyeHeight() * 0.8, prey.posZ);
            a.act((int) Math.floor(prey.posX), (int) Math.floor(prey.posY), (int) Math.floor(prey.posZ), 4);
            if (cool == 0 && a.canEntityBeSeen(prey) && a.body.facing(prey.posX, prey.posY + prey.getEyeHeight() * 0.8, prey.posZ, 30)) {
                cool = 11;
                Guard.strike(a, w, prey);
            }
            return Result.RUNNING;
        }
    }

    // ---- the environment's growth of the crew's crops, saplings and young ---------------------------------------------

    /**
     * Each tick: the crew's wheat and saplings k - 1 more chances of the game's random tick (a block is ticked about
     * 3 / 4096 a tick: so k times as fast), its young k - 1 more ticks of growing up. A sapling that became a tree moves
     * to grown, its trunk's logs to grownLogs (their wood is renewable).
     */
    static void grow(WorldServer w, int k) {
        if (k <= 1) return;
        RlWorld rw = RlWorld.now;
        Random rnd = w.rand;
        double p = (k - 1) * 3.0 / 4096;
        for (long key : rw.farm) {
            if (rnd.nextDouble() >= p) continue;
            int[] q = RlWorld.unkey(key);
            Block b = w.getBlock(q[0], q[1] + 1, q[2]);
            if (b == Blocks.wheat) b.updateTick(w, q[0], q[1] + 1, q[2], rnd);
        }
        List<Long> done = null;
        for (long key : rw.saplings) {
            int[] q = RlWorld.unkey(key);
            Block b = w.getBlock(q[0], q[1], q[2]);
            if (b == Blocks.sapling) {
                if (rnd.nextDouble() < p) b.updateTick(w, q[0], q[1], q[2], rnd);
            } else if (b == Blocks.log || b == Blocks.log2) {
                if (done == null) done = new ArrayList<Long>();
                done.add(key);
                for (int dy = 0; dy < 8; dy++) {
                    Block t = w.getBlock(q[0], q[1] + dy, q[2]);
                    if (t != Blocks.log && t != Blocks.log2) break;
                    rw.grownLogs.add(RlWorld.key(q[0], q[1] + dy, q[2]));
                }
            }
        }
        if (done != null) for (Long key : done) { rw.saplings.remove(key); rw.grown.add(key); }
        for (Integer id : new ArrayList<Integer>(rw.born)) {
            Entity e = w.getEntityByID(id);
            if (!(e instanceof EntityAgeable) || e.isDead) continue;
            EntityAgeable g = (EntityAgeable) e;
            if (g.getGrowingAge() < 0) g.setGrowingAge(Math.min(0, g.getGrowingAge() + (k - 1)));
        }
    }

    // ---- the facts --------------------------------------------------------------------------------------------------

    static void put(Map<String, Double> r, String k, double v) { r.put(k, v); }

    static void facts(Map<String, Double> r, Crew c, Agent a, WorldServer w) {
        RlWorld rw = RlWorld.now;
        int plots = 0, planted = 0, ripe = 0, empty = 0;
        for (long k : rw.farm) {
            int[] q = RlWorld.unkey(k);
            if (w.getBlock(q[0], q[1], q[2]) != Blocks.farmland) continue;
            plots++;
            Block up = w.getBlock(q[0], q[1] + 1, q[2]);
            if (up == Blocks.wheat) { planted++; if (w.getBlockMetadata(q[0], q[1] + 1, q[2]) >= 7) ripe++; }
            else if (w.isAirBlock(q[0], q[1] + 1, q[2])) empty++;
        }
        put(r, "farm_plots", plots);
        put(r, "farm_planted", planted);
        put(r, "farm_ripe", ripe);
        put(r, "farm_empty", empty);
        ItemStack h = hoe(a);
        int hoes = 0;
        for (ItemStack s : a.bag) if (s != null && s.getItem() instanceof ItemHoe) hoes += s.stackSize;
        put(r, "hoes", hoes);
        put(r, "hoe_tier", h == null ? 0 : EpochScore.tier(MindSkills.id(h)));
        put(r, "till_spots", c.base == null || h == null && plots >= 16 ? 0 : tillSpots(c, a, w, 16).size());
        int sap = 0;
        for (long k : rw.saplings) { int[] q = RlWorld.unkey(k); if (w.getBlock(q[0], q[1], q[2]) == Blocks.sapling) sap++; }
        put(r, "saplings_planted", sap);
        put(r, "trees_grown", rw.grown.size());
        put(r, "sapling_spots", c.base == null || count(a, Item.getItemFromBlock(Blocks.sapling)) == 0 ? 0 : saplingSpots(c, a, w, 8).size());
        boolean pen = rw.pen != null && penStanding(w) >= 12;
        put(r, "pen_built", pen ? 1 : 0);
        int animals = 0, adults = 0, babies = 0, pairs = 0;
        if (rw.pen != null) {
            for (EntityAnimal e : inPen(w, false)) { animals++; if (e.isChild()) babies++; else adults++; }
            for (List<EntityAnimal> l : pairs(w).values()) if (l.size() >= 2 && count(a, bait(l.get(0))) >= 2) pairs++;
        }
        put(r, "pen_animals", animals);
        put(r, "pen_adults", adults);
        put(r, "pen_babies", babies);
        put(r, "pen_pairs", pairs);
        put(r, "pen_dist", rw.pen == null ? 32 : Math.hypot(a.posX - rw.pen[0] - 0.5, a.posZ - rw.pen[2] - 0.5));
        put(r, "lure_food", count(a, Items.wheat) + count(a, Items.wheat_seeds) + count(a, Items.carrot));
        int targets = 0;   // animals in sight, grown, outside the pen, whose bait it has (what lure takes)
        for (net.minecraft.entity.EntityLiving e : RlSkills.animals(a, w, SEE)) {
            Item b = bait(e);
            if (b != null && count(a, b) > 0 && !rw.inPen(e.posX, e.posZ) && !(e instanceof EntityAgeable && ((EntityAgeable) e).isChild())) targets++;
        }
        put(r, "lure_targets", targets);
        put(r, "grass_seen", seen(a, w, new Block[] { Blocks.tallgrass }, 32).size());
        put(r, "leaves_seen", seen(a, w, new Block[] { Blocks.leaves, Blocks.leaves2 }, 32).size());
        put(r, "cane_seen", seen(a, w, new Block[] { Blocks.reeds }, 32).size());
    }

    static Set<Long> keys(List<int[]> l) {
        Set<Long> s = new HashSet<Long>();
        for (int[] p : l) s.add(RlWorld.key(p[0], p[1], p[2]));
        return s;
    }
}
