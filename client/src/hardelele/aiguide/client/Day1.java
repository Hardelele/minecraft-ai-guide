package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * The crew's first day on the honest economy: everything an agent has came from a drop, the chest, a craft or a
 * smelt, and everything it puts down or hands over goes out of its bag (Skills, Ledger). Wood in a chain (choppers
 * hand their logs to a carrier), a camp made of the first logs (planks, a crafting table, a chest), wooden pickaxes
 * made at the table and handed to each, a mine going down in steps (stone only with a pickaxe), a furnace of
 * cobblestone with charcoal smelted for torches, stone pickaxes, a gift to the player, a base by a see-through plan
 * built from what the chest holds (its size by the wood there is), and at nightfall the crew at the mine.
 */
class Day1 {
    static final Item LOG = Item.getItemFromBlock(Blocks.log), LOG2 = Item.getItemFromBlock(Blocks.log2), PLANKS = Item.getItemFromBlock(Blocks.planks),
            COBBLE = Item.getItemFromBlock(Blocks.cobblestone), FENCE = Item.getItemFromBlock(Blocks.fence), TORCH = Item.getItemFromBlock(Blocks.torch),
            TABLE = Item.getItemFromBlock(Blocks.crafting_table), CHEST = Item.getItemFromBlock(Blocks.chest), FURNACE = Item.getItemFromBlock(Blocks.furnace);

    // ---- handing things over: a throw, the other one catches ------------------------------------------

    /** throw the giver's bag (but what it keeps) to someone; the items fly and are caught where they land */
    static void toss(Agent from, double tx, double ty, double tz, WorldServer w, Item keep) {
        from.swingItem();
        double dx = tx - from.posX, dy = ty - (from.posY + 1.3), dz = tz - from.posZ, d = Math.max(0.5, Math.sqrt(dx * dx + dz * dz));
        for (java.util.Iterator<ItemStack> it = from.bag.iterator(); it.hasNext(); ) {
            ItemStack s = it.next();
            if (keep != null && s.getItem() == keep) continue;
            if (!s.isStackable() && s == from.getHeldItem() && keep == null && from.bag.size() > 1) { }   // a tool goes too when asked
            Ledger.flow(Ledger.Way.HANDED_OUT, s);
            Ledger.act(from, "hand over");
            EntityItem e = new EntityItem(w, from.posX, from.posY + 1.3, from.posZ, s.copy());
            e.motionX = dx / d * Math.min(0.42, d * 0.1);
            e.motionZ = dz / d * Math.min(0.42, d * 0.1);
            e.motionY = 0.22 + dy * 0.04;
            e.delayBeforeCanPickup = 12;
            w.spawnEntityInWorld(e);
            if (s == from.getHeldItem()) from.setCurrentItemOrArmor(0, null);
            it.remove();
        }
        w.playSoundEffect(from.posX, from.posY + 1, from.posZ, "random.pop", 0.4F, 1.3F);
        if (from.getHeldItem() != null && !from.bag.contains(from.getHeldItem())) from.setCurrentItemOrArmor(0, null);
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
            Skills.put(a, s, Ledger.Way.DROP);
            Ledger.act(a, "pick up");
            it.setDead();
            got += s.stackSize;
            if (a.getHeldItem() == null) a.setCurrentItemOrArmor(0, Skills.lastOf(a, s.getItem()));
        }
        return got;
    }

    /** a line an agent says: to the events (there are no speech bubbles yet) */
    static void say(Agent a, String line) {
        Director.log("crew: " + a.getCustomNameTag() + " says: " + line);
    }

    static int logs(List<ItemStack> bag) {
        return Skills.count(bag, LOG, -1) + Skills.count(bag, LOG2, -1);
    }

    /** logs the crew has: the chest's and every bag's */
    static int crewLogs(Crew c, WorldServer w) {
        int n = 0;
        IInventory inv = c.chestInv(w);
        if (inv != null) n += Crew.count(inv, LOG) + Crew.count(inv, LOG2);
        for (Agent a : c.agents) n += logs(a.bag);
        return n;
    }

    /** the carrier: runs to whichever worker has the most, takes it (thrown over), carries it to the chest */
    static class Courier implements Crew.Task {
        final Crew c;
        final List<Agent> workers;
        Agent from;
        Crew.GoTo walk;
        Crew.Task stash;
        int state, ticks, idle;
        Courier(Crew c, List<Agent> workers) { this.c = c; this.workers = workers; }
        static int load(Agent g) {
            int n = 0;
            for (ItemStack s : g.bag) if (s.isStackable()) n += s.stackSize;
            return n;
        }
        public Result step(Agent a, WorldServer w) {
            ticks++;
            if (stash != null) { if (stash.step(a, w).done()) stash = null; return Result.RUNNING; }
            if (state == 0) {   // whom to go to
                from = null;
                int most = 0;
                for (Agent g : workers) { int n = load(g); if (n > most) { most = n; from = g; } }
                boolean working = false;
                for (Agent g : workers) if (!g.jobs.isEmpty()) working = true;
                int mine = load(a);
                if (from == null || most < 3 && working) {
                    if (mine > 0 && (!working || mine >= 12)) { stash = c.new Stash(null, 0); return Result.RUNNING; }
                    if (!working) return Result.OK;
                    if (++idle % 40 == 0 && !workers.isEmpty()) {   // wait near one of them
                        Agent g = workers.get((idle / 40) % workers.size());
                        walk = new Crew.GoTo(g.posX + 2, g.posY, g.posZ + 1, 2.5);
                    }
                    if (walk != null && walk.step(a, w).done()) walk = null;
                    return Result.RUNNING;
                }
                walk = new Crew.GoTo(from.posX, from.posY, from.posZ, 3.2);
                state = 1;
                return Result.RUNNING;
            }
            if (state == 1) {   // run to it; it throws its load (not its tools), the carrier catches
                if (Crew.dist(a, from.posX, from.posY, from.posZ) > 3.4) {
                    if (walk.step(a, w).done()) walk = new Crew.GoTo(from.posX, from.posY, from.posZ, 3.2);
                    if (ticks > 20 * 40) { state = 0; ticks = 0; }
                    return Result.RUNNING;
                }
                a.getNavigator().clearPathEntity();
                Crew.face(a, from.posX, from.posY + 1.4, from.posZ);
                Crew.face(from, a.posX, a.posY + 1.4, a.posZ);
                List<ItemStack> tools = new ArrayList<ItemStack>();
                for (ItemStack s : from.bag) if (!s.isStackable()) tools.add(s);
                from.bag.removeAll(tools);
                toss(from, a.posX, a.posY + 1.0, a.posZ, w, null);
                from.bag.addAll(tools);
                if (from.getHeldItem() == null && !tools.isEmpty()) from.setCurrentItemOrArmor(0, tools.get(0));
                say(from, "Лови, " + a.getCustomNameTag() + "!");
                ticks = 0;
                state = 2;
                return Result.RUNNING;
            }
            Crew.face(a, from.posX, from.posY + 1.4, from.posZ);
            catchNear(a, w, 1.6);
            if (ticks > 26) {
                catchNear(a, w, 3.0);
                if (load(a) >= 12) { stash = c.new Stash(null, 0); say(a, "Несу в сундук"); }
                state = 0;
            }
            return Result.RUNNING;
        }
    }

    /** chop trees until the crew has the logs it needs (or the trees near run out): one tree after another */
    static class ChopMore implements Crew.Task {
        final Crew c;
        final int target;
        final boolean own;   // count only the agent's own bag (logs for its own craft), not the crew's
        Crew.Task cur;
        int done;
        ChopMore(Crew c, int target) { this(c, target, false); }
        ChopMore(Crew c, int target, boolean own) { this.c = c; this.target = target; this.own = own; }
        public Result step(Agent a, WorldServer w) {
            if (cur != null) {
                Result r = cur.step(a, w);
                if (r == Result.RUNNING) return r;
                cur = cur instanceof Crew.Chop ? new Crew.Collect(((Crew.Chop) cur).tx + 0.5, ((Crew.Chop) cur).ty, ((Crew.Chop) cur).tz + 0.5, 5) : null;
                return Result.RUNNING;
            }
            if ((own ? logs(a.bag) : crewLogs(c, w)) >= target) return Result.OK;
            int[] best = null;
            double bd = 1e9;
            for (int[] t : c.trees(w)) {
                if (c.taken.contains(Crew.key(t[0], t[1], t[2]))) continue;
                double d = Math.hypot(t[0] - a.posX, t[2] - a.posZ) + Math.hypot(t[0] - c.base[0], t[2] - c.base[2]) * 0.5;
                if (d < bd) { bd = d; best = t; }
            }
            if (best == null) return done > 0 ? Result.OK : Result.GONE;
            c.taken.add(Crew.key(best[0], best[1], best[2]));
            cur = new Crew.Chop(best[0], best[1], best[2]);
            done++;
            say(a, done == 1 ? "Беру дерево" : "Ещё одно");
            return Result.RUNNING;
        }
    }

    /** the camp from the first logs: planks, a crafting table and a chest, made and put down where the base will be */
    static class Camp implements Crew.Task {
        final Crew c;
        final List<Crew.Task> steps = new ArrayList<Crew.Task>();
        int i;
        Camp(Crew c) { this.c = c; }
        public Result step(Agent a, WorldServer w) {
            if (steps.isEmpty()) {
                int[] t = c.table, k = c.chest;
                boolean table = w.getBlock(t[0], t[1], t[2]) == Blocks.crafting_table, chest = w.getBlock(k[0], k[1], k[2]) == Blocks.chest;
                if (table && chest) return Result.OK;
                if (logs(a.bag) < 3) return Result.NO_ITEMS;
                say(a, "Ставлю лагерь: верстак и сундук");
                steps.add(new Skills.Craft(PLANKS, -1, 12));
                if (!table) { steps.add(new Skills.Craft(TABLE, -1, 1)); steps.add(new Skills.PlaceFrom(t[0], t[1], t[2], TABLE, -1)); }
                if (!chest) { steps.add(new Skills.Craft(CHEST, -1, 1)); steps.add(new Skills.PlaceFrom(k[0], k[1], k[2], CHEST, -1)); }
            }
            if (i >= steps.size()) return Result.OK;
            Result r = steps.get(i).step(a, w);
            if (r == Result.RUNNING) return r;
            if (r.failed()) return r;
            i++;
            return Result.RUNNING;
        }
    }

    static class WoodChain extends Crew.Phase {
        final int target;
        WoodChain(int perAgent) { this.target = Base.woodNeeded(); max = 20 * 300; }
        void start(Crew c, WorldServer w, EntityPlayerMP p) {
            if (c.base == null) c.base = new int[] { (int) Math.floor(p.posX), (int) Math.floor(p.boundingBox.minY), (int) Math.floor(p.posZ) };
            c.planSite(w);
            List<Agent> choppers = new ArrayList<Agent>(c.agents);
            Agent carrier = choppers.size() >= 3 ? choppers.remove(choppers.size() - 1) : null;
            Agent camper = choppers.isEmpty() ? null : choppers.get(0);
            for (Agent a : choppers) {
                if (a == camper) {   // the first tree's logs make the camp, then on with the wood
                    Crew.job(a, new ChopMore(c, 3, true));
                    Crew.job(a, new Camp(c));
                }
                Crew.job(a, new ChopMore(c, target));
                if (carrier == null || a == camper) Crew.job(a, c.new Stash(null, 0));
            }
            List<Agent> carried = new ArrayList<Agent>(choppers);
            carried.remove(camper);   // its logs are the camp's: the carrier leaves it alone
            if (carrier != null) Crew.job(carrier, new Courier(c, carried.isEmpty() ? choppers : carried));
            Director.log("crew: wood chain for " + target + " logs, " + choppers.size() + " choppers" + (carrier != null ? ", " + carrier.getCustomNameTag() + " carries" : ""));
        }
    }

    // ---- tools: made at the table and handed round ------------------------------------------------------

    /** the crafter takes logs, makes planks and sticks, a wooden pickaxe for each and sticks to spare, hands them round */
    static class Tools extends Crew.Phase {
        Tools() { max = 20 * 150; }
        void start(final Crew c, WorldServer w, EntityPlayerMP p) {
            c.planSite(w);
            if (c.agents.isEmpty()) return;
            int n = c.agents.size();
            Agent crafter = c.agents.get(0);
            // a pickaxe is 3 planks and 2 sticks; 2 planks make 4 sticks; to spare: the stone pickaxes' (n + the player's) and 4 for torches
            int sticks = 2 * n + 2 * (n + 1) + 4, planks = 3 * n + (sticks + 3) / 4 * 2;
            Crew.job(crafter, new Take(c, LOG, (planks + 3) / 4));
            Crew.job(crafter, new Skills.Craft(PLANKS, -1, planks));
            Crew.job(crafter, new Skills.Craft(Items.stick, -1, sticks));
            Crew.job(crafter, new Skills.Craft(Items.wooden_pickaxe, -1, n));
            for (Agent a : c.agents)
                if (a != crafter) Crew.job(crafter, new Skills.Give(a, Items.wooden_pickaxe, 1));
            Crew.job(crafter, c.new Stash(Items.wooden_pickaxe, 1));   // the spare sticks go to the chest
            say(crafter, "Сделаю всем кирки");
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
        public Result step(Agent a, WorldServer w) {
            if (br != null) {
                Result r = br.step(a, w);
                if (r == Result.RUNNING) return r;
                br = null;
                if (r == Result.NO_TOOL) { say(a, "Нечем копать камень"); return Result.NO_TOOL; }
                catchNear(a, w, 1.4);
                return Result.RUNNING;
            }
            if (k > n) { catchNear(a, w, 2.5); return Result.OK; }
            int cx = x0 + dx * k, cz = z0 + dz * k, floor = y0 - k;
            for (int y = floor + 2; y >= floor; y--) {   // the blocks of this step, the top first
                Block b = w.getBlock(cx, y, cz);
                if (Crew.soft(w, cx, y, cz) || b == Blocks.bedrock || b.getMaterial().isLiquid()) continue;
                double reach = Crew.eyeDist(a, cx, y, cz);
                int[] in = Crew.blocker(a, w, cx, y, cz);
                if (reach <= Crew.REACH && (in == null || in[0] == cx && in[2] == cz)) {
                    br = in != null && (in[0] != cx || in[1] != y || in[2] != cz) ? new Crew.Break(in[0], in[1], in[2], 0) : new Crew.Break(cx, y, cz, 0);
                    return Result.RUNNING;
                }
                int px = x0 + dx * (k - 1), pz = z0 + dz * (k - 1), pf = y0 - (k - 1);   // not seen from here: stand on the step before
                if (walk == null) walk = new Crew.GoTo(px + 0.5, pf, pz + 0.5, 0.6);
                if (walk.step(a, w).done()) { walk = null; if (++tries > 4) { tries = 0; k++; } }
                return Result.RUNNING;
            }
            // the step is open: a floor under it, from the agent's own cobblestone (or dirt), or the stairs end here
            if (Crew.soft(w, cx, floor - 1, cz)) {
                Item fill = Skills.count(a.bag, COBBLE, -1) > 0 ? COBBLE : Skills.count(a.bag, Item.getItemFromBlock(Blocks.dirt), -1) > 0 ? Item.getItemFromBlock(Blocks.dirt) : null;
                if (fill == null || Skills.placeFromBag(a, w, cx, floor - 1, cz, fill, -1) != Result.OK) { catchNear(a, w, 2.5); return Result.OK; }
            }
            k++;
            tries = 0;
            walk = null;
            return Result.RUNNING;
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

    static boolean hasPickaxe(Agent a) {
        for (ItemStack s : a.bag) if (s.getItem() instanceof net.minecraft.item.ItemPickaxe) return true;
        return false;
    }

    static class Mine extends Crew.Phase {
        Mine() { max = 20 * 200; }
        void start(Crew c, WorldServer w, EntityPlayerMP p) {
            c.planSite(w);
            int[] m = mine != null ? mine : mineSpot(c, w);
            if (m == null) { Director.log("crew: no place for a mine"); return; }
            mine = m;
            Director.log("crew: mine at " + m[0] + " " + m[1] + " " + m[2] + " going " + m[3] + " " + m[4]);
            List<Agent> diggers = new ArrayList<Agent>();
            for (Agent a : c.agents) if (hasPickaxe(a) && diggers.size() < 2) diggers.add(a);
            int k = 0;
            for (Agent a : diggers) {   // two dig abreast
                int sx = m[0] + (m[3] == 0 ? k : 0), sz = m[2] + (m[4] == 0 ? k : 0);
                Crew.job(a, new Crew.GoTo(sx + 0.5, m[1], sz + 0.5, 0.8));
                Crew.job(a, new Steps(sx, m[1], sz, m[3], m[4], 8));
                Crew.job(a, c.new Stash(Items.wooden_pickaxe, 1));
                say(a, k == 0 ? "Копаю шахту" : "И я с тобой");
                k++;
            }
            int left = 0;
            for (Agent a : c.agents) {
                if (diggers.contains(a)) continue;
                if (left++ == 0 && !diggers.isEmpty()) Crew.job(a, new Courier(c, diggers));   // one carries what comes up
                else { Crew.job(a, new ChopMore(c, Base.woodNeeded())); Crew.job(a, c.new Stash(Items.wooden_pickaxe, 1)); }   // the rest: more wood
            }
        }
    }

    // ---- stone tools, the furnace, charcoal and torches ---------------------------------------------------

    /** take things from the chest: walk there, the lid up, the hand full */
    static class Take implements Crew.Task {
        final Crew c;
        final Item item;
        final int n;
        int state, ticks, got;
        Crew.GoTo walk;
        Take(Crew c, Item item, int n) { this.c = c; this.item = item; this.n = n; }
        public Result step(Agent a, WorldServer w) {
            if (c.chest == null || c.chestInv(w) == null) return Result.GONE;
            if (n <= 0) return Result.OK;
            if (state == 0) { walk = new Crew.GoTo(c.chest[0] + 0.5, c.chest[1], c.chest[2] + 0.5, 2.3); state = 1; }
            if (state == 1) {
                Result r = walk.step(a, w);
                if (r == Result.RUNNING) return r;
                if (r != Result.OK && Crew.dist(a, c.chest[0] + 0.5, c.chest[1], c.chest[2] + 0.5) > 3.2) return r;
                state = 2;
            }
            Crew.face(a, c.chest[0] + 0.5, c.chest[1] + 0.5, c.chest[2] + 0.5);
            ticks++;
            if (ticks == 3) w.addBlockEvent(c.chest[0], c.chest[1], c.chest[2], Blocks.chest, 1, 1);
            if (ticks == 9) {
                IInventory inv = c.chestInv(w);
                if (item == LOG) {   // any wood
                    got = takeAny(inv, a, LOG, n);
                    got += takeAny(inv, a, LOG2, n - got);
                } else got = takeAny(inv, a, item, n);
                if (got > 0) Ledger.act(a, "take from chest");
                a.swingItem();
            }
            if (ticks == 16) w.addBlockEvent(c.chest[0], c.chest[1], c.chest[2], Blocks.chest, 1, 0);
            return ticks >= 18 ? (got > 0 ? Result.OK : Result.NO_ITEMS) : Result.RUNNING;
        }
        static int takeAny(IInventory inv, Agent a, Item item, int n) {
            int got = 0;
            for (int i = 0; i < inv.getSizeInventory() && got < n; i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (s == null || s.getItem() != item) continue;
                ItemStack t = inv.decrStackSize(i, n - got);
                if (t == null) continue;
                got += t.stackSize;
                Skills.put(a, t, Ledger.Way.CHEST_OUT);
            }
            inv.markDirty();
            return got;
        }
    }

    static int[] furnace;

    /**
     * The smith: cobblestone from the chest, a furnace made at the table and put down, logs in it with planks for fuel,
     * charcoal out, torches of charcoal and sticks; a stone pickaxe for the player. The others: a stone pickaxe each.
     */
    static class Upgrade extends Crew.Phase {
        Upgrade() { max = 20 * 180; }
        void start(final Crew c, WorldServer w, EntityPlayerMP p) {
            c.planSite(w);
            final int[] t = c.table;
            if (t == null || c.agents.isEmpty()) return;
            int[] d = c.doorDir();
            if (furnace == null) {
                furnace = new int[] { t[0] + d[1] * -2, t[1], t[2] + d[0] * -2 };
                int fy = Crew.ground(w, furnace[0], t[1], furnace[2]);
                if (fy >= 0) furnace[1] = fy;
            }
            Agent smith = c.agents.get(c.agents.size() - 1);
            Crew.job(smith, new Take(c, COBBLE, 8 + 3));
            Crew.job(smith, new Take(c, Items.stick, 2 + 4));
            Crew.job(smith, new Skills.Craft(FURNACE, -1, 1));
            Crew.job(smith, new Skills.PlaceFrom(furnace[0], furnace[1], furnace[2], FURNACE, -1));
            Crew.job(smith, new Take(c, LOG, 6));
            Crew.job(smith, new Skills.Craft(PLANKS, -1, 8));   // the fuel: 8 planks burn 12 items
            Crew.job(smith, new Skills.Smelt(LOG, -1, 4, PLANKS));
            Crew.job(smith, new Skills.Craft(TORCH, -1, 16));
            Crew.job(smith, new Skills.Craft(Items.stone_pickaxe, -1, 1));   // for the player
            say(smith, "Сложу печь и нажгу угля на факелы");
            int k = 0;
            for (Agent a : c.agents) {
                if (a == smith) continue;
                double ang = Math.toRadians(k++ * 120 + 30);
                Crew.job(a, new Take(c, COBBLE, 3));
                Crew.job(a, new Take(c, Items.stick, 2));
                Crew.job(a, new Crew.GoTo(t[0] + 0.5 + Math.sin(ang) * 1.7, t[1], t[2] + 0.5 + Math.cos(ang) * 1.7, 0.9));
                Crew.job(a, new Skills.Craft(Items.stone_pickaxe, -1, 1));
            }
        }
    }

    /** the smith brings the player the stone pickaxe it made, and torches */
    static class Gift extends Crew.Phase {
        void start(Crew c, final WorldServer w, final EntityPlayerMP p) {
            if (c.agents.isEmpty()) return;
            Agent smith = c.agents.get(c.agents.size() - 1);
            say(smith, "Это тебе!");
            Crew.job(smith, new Skills.Give(Items.stone_pickaxe, 1));
            Crew.job(smith, new Skills.Give(TORCH, 8));
            for (Agent g : c.agents) if (g != smith) Crew.job(g, new Crew.Look(p.posX, p.posY + 1.5, p.posZ, 50));
        }
    }

    // ---- the base: a house with a stepped roof, a door, windows; a fence with a gate; torches --------------

    /** the planks, logs, cobblestone, fences, torches and door a plan takes */
    static Map<String, Integer> bill(List<int[]> plan, boolean door) {
        Map<String, Integer> m = new LinkedHashMap<String, Integer>();
        for (int[] b : plan) Ledger.add(m, new String[] { "planks", "log", "cobblestone", "glass", "fence", "torch" }[b[3]], 1);
        if (door) Ledger.add(m, "door", 1);
        return m;
    }

    /** logs a bill takes, made into planks, sticks, fences and the door (torches need charcoal, counted apart) */
    static int logsFor(Map<String, Integer> m) {
        int planks = get(m, "planks") + get(m, "fence") * 3 / 2 + get(m, "door") * 6 + (get(m, "torch") + 3) / 4 / 2;
        return get(m, "log") + (planks + 3) / 4;
    }

    static int get(Map<String, Integer> m, String k) {
        Integer v = m.get(k);
        return v == null ? 0 : v;
    }

    /** the wood the day's base takes, with its tools (for the wood chain's target) */
    static int woodNeeded() {
        return 75 + 12;
    }

    static class Base extends Crew.House {
        int[] doorAt;
        Base() { max = 20 * 360; }

        static int woodNeeded() {
            return Day1.woodNeeded();
        }

        /** the plan of a size: 2 the full base (house, fence, torches), 1 the house only, 0 a small hut */
        void lay(Crew c, WorldServer w, int size, int stone) {
            plan.clear();
            int[] s = Crew.site;
            int half = size == 0 ? 2 : 3;
            int x0 = s[0] + 2 - half, y0 = s[1], z0 = s[2] + 2 - half, side = 2 * half;
            int[] d = c.doorDir();
            int cx = x0 + half, cz = z0 + half;
            for (int y = y0; y <= y0 + (size == 0 ? 2 : 3); y++)
                for (int x = x0; x <= x0 + side; x++)
                    for (int z = z0; z <= z0 + side; z++) {
                        if (!(x == x0 || x == x0 + side || z == z0 || z == z0 + side)) continue;
                        boolean corner = (x == x0 || x == x0 + side) && (z == z0 || z == z0 + side);
                        int lx = x - cx, lz = z - cz;
                        boolean front = d[0] != 0 ? lx == half * d[0] : lz == half * d[1];
                        boolean door = front && (d[0] != 0 ? lz == 0 : lx == 0) && y <= y0 + 1;
                        boolean window = (y == y0 + 1 || size > 0 && y == y0 + 2) && !corner && !front
                                && (Math.abs(lx) == 1 && Math.abs(lz) == half || Math.abs(lz) == 1 && Math.abs(lx) == half);
                        if (door) continue;
                        if (window) { plan.add(new int[] { x, y, z, 4 }); continue; }
                        plan.add(new int[] { x, y, z, corner ? 1 : y == y0 && stone >= 2 * side * 2 ? 2 : 0 });
                    }
            int ry = y0 + (size == 0 ? 3 : 4);
            for (int x = x0 - 1; x <= x0 + side + 1; x++)
                for (int z = z0 - 1; z <= z0 + side + 1; z++) plan.add(new int[] { x, ry, z, 0 });
            for (int x = x0 + 1; x <= x0 + side - 1; x++)
                for (int z = z0 + 1; z <= z0 + side - 1; z++) plan.add(new int[] { x, ry + 1, z, 0 });
            plan.add(new int[] { cx, ry + 2, cz, 1 });
            doorAt = new int[] { cx + half * d[0], y0, cz + half * d[1] };
            plan.add(new int[] { doorAt[0] + d[0] + (d[1] != 0 ? 1 : 0), y0, doorAt[2] + d[1] + (d[0] != 0 ? 1 : 0), 5 });   // torches either side of the door
            plan.add(new int[] { doorAt[0] + d[0] - (d[1] != 0 ? 1 : 0), y0, doorAt[2] + d[1] - (d[0] != 0 ? 1 : 0), 5 });
            if (size < 2) return;
            int fx0 = x0 - 5, fx1 = x0 + side + 5, fz0 = z0 - 5, fz1 = z0 + side + 5;   // the fence, a gate before the door
            for (int x = fx0; x <= fx1; x++)
                for (int z = fz0; z <= fz1; z++) {
                    if (x != fx0 && x != fx1 && z != fz0 && z != fz1) continue;
                    int lx = x - cx, lz = z - cz;
                    boolean gate = d[0] != 0 ? lx * d[0] > 0 && x == (d[0] > 0 ? fx1 : fx0) && Math.abs(lz) <= 1
                            : lz * d[1] > 0 && z == (d[1] > 0 ? fz1 : fz0) && Math.abs(lx) <= 1;
                    if (gate) continue;
                    int gy = Crew.ground(w, x, y0, z);
                    if (gy < 0 || Math.abs(gy - y0) > 2) continue;
                    if (mine != null && Math.abs(x - mine[0]) <= 2 && Math.abs(z - mine[2]) <= 2) continue;
                    plan.add(new int[] { x, gy, z, 4 });
                    boolean corner = (x == fx0 || x == fx1) && (z == fz0 || z == fz1);
                    boolean byGate = d[0] != 0 ? Math.abs(lz) == 2 && x == (d[0] > 0 ? fx1 : fx0) : Math.abs(lx) == 2 && z == (d[1] > 0 ? fz1 : fz0);
                    if (corner || byGate) plan.add(new int[] { x, gy + 1, z, 5 });
                }
        }

        void start(final Crew c, WorldServer w, EntityPlayerMP p) {
            c.planSite(w);
            IInventory inv = c.chestInv(w);
            int stone = inv == null ? 0 : Crew.count(inv, COBBLE);
            int wood = crewLogs(c, w), torches = 0;
            if (inv != null) torches = Crew.count(inv, TORCH);
            for (Agent a : c.agents) torches += Skills.count(a.bag, TORCH, -1);
            int size = 2;   // the biggest the wood allows
            for (; size > 0; size--) {
                lay(c, w, size, stone);
                if (logsFor(bill(plan, true)) <= wood) break;
            }
            if (size == 0) lay(c, w, 0, stone);
            Map<String, Integer> b = bill(plan, true);
            Director.log("crew: base of size " + size + ", " + plan.size() + " blocks, bill " + b + ", wood " + wood + " logs (needs " + logsFor(b) + "), torches " + torches);
            show(c, w);
            Agent crafter = c.agents.get(0);
            Crew.job(crafter, new Prepare(c, b));   // the materials made at the table, into the chest
            for (Agent a : c.agents) Crew.job(a, new Crew.Build(this));
            final Base self = this;
            Crew.job(crafter, new Crew.Task() {   // the door last, by the crafter (from its bag)
                Crew.Task t;
                public Result step(Agent a, WorldServer ww) {
                    if (t == null) {
                        if (Skills.count(a.bag, Items.wooden_door, -1) == 0) {
                            Take take = new Take(c, Items.wooden_door, 1);
                            t = take;
                        } else t = new Skills.PlaceFrom(self.doorAt[0], self.doorAt[1], self.doorAt[2], Items.wooden_door, -1);
                    }
                    Result r = t.step(a, ww);
                    if (r == Result.RUNNING) return r;
                    if (t instanceof Take && r == Result.OK) { t = null; return Result.RUNNING; }
                    return r;
                }
            });
            say(crafter, size == 2 ? "Дерева хватает на дом с оградой" : size == 1 ? "Хватит на дом, ограду потом" : "Дерева мало: строим хижину");
        }
    }

    /** the crafter makes what a bill needs from the chest's logs (and the torches from what charcoal there is), into the chest */
    static class Prepare implements Crew.Task {
        final Crew c;
        final Map<String, Integer> bill;
        final List<Crew.Task> steps = new ArrayList<Crew.Task>();
        int i;
        Prepare(Crew c, Map<String, Integer> bill) { this.c = c; this.bill = bill; }
        public Result step(Agent a, WorldServer w) {
            if (steps.isEmpty()) {
                int fences = get(bill, "fence"), doors = get(bill, "door");
                int sticks = (fences + 1) / 2 * 6;   // 6 sticks make 2 fences (rounded up to a whole craft)
                int planks = get(bill, "planks") + (sticks + 3) / 4 * 2 + doors * 6;
                steps.add(new Take(c, LOG, (planks + 3) / 4));
                steps.add(new Skills.Craft(PLANKS, -1, planks));
                if (sticks > 0) steps.add(new Skills.Craft(Items.stick, -1, sticks));
                if (fences > 0) steps.add(new Skills.Craft(FENCE, -1, fences));
                if (doors > 0) steps.add(new Skills.Craft(Items.wooden_door, -1, doors));
                steps.add(c.new Stash(null, 0));
                say(a, "Нарежу досок, забор и дверь");
            }
            if (i >= steps.size()) return Result.OK;
            Result r = steps.get(i).step(a, w);
            if (r == Result.RUNNING) return r;
            if (r.failed()) Director.log("crew: prepare step " + i + " -> " + r);
            i++;
            return Result.RUNNING;
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
            Agent torcher = null;
            for (Agent a : c.agents) if (Skills.count(a.bag, TORCH, -1) > 0) torcher = a;
            for (Agent a : c.agents) {
                double ang = Math.toRadians(-60 + 120.0 * k / Math.max(1, n - 1)) + Math.atan2(-m[3], -m[4]);
                double tx = m[0] + 0.5 - m[3] * 1.5 + Math.sin(ang) * 2.2, tz = m[2] + 0.5 - m[4] * 1.5 + Math.cos(ang) * 2.2;
                int gy = Crew.ground(w, MathHelper.floor_double(tx), m[1], MathHelper.floor_double(tz));
                Crew.job(a, new Crew.GoTo(tx, gy < 0 ? m[1] : gy, tz, 0.7));
                if (a == torcher) Crew.job(a, new Skills.PlaceFrom(m[0] - m[3] + (m[3] == 0 ? -1 : 0), m[1], m[2] - m[4] + (m[4] == 0 ? -1 : 0), TORCH, -1));
                Crew.job(a, new Crew.Look(mx, m[1] - 2.5, mz, 20 * 30));
                k++;
            }
            if (torcher != null) say(torcher, "Посвечу у входа");
        }
    }

    static volatile double[] front;
}
