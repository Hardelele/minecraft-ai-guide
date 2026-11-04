package hardelele.aiguide;

import cpw.mods.fml.common.FMLCommonHandler;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.inventory.ContainerWorkbench;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

/**
 * The hands, done the way a player would: the guide's body walks to each chest, opens it, takes what
 * it needs, walks to a crafting table, crafts one at a time, and brings the result to the player.
 * Nothing moves at a distance and nothing happens at once. Everything runs on the server thread.
 */
class Errands {
    static final int CHEST = 0, FROM_PLAYER = 1, TABLE = 2, BRING = 3, PLACE = 4, DIG = 5, POCKET = 6, PICKUP = 7;
    static final int TIMEOUT = 20 * 180, LEAD_TIMEOUT = 20 * 600, IDLE = 20 * 40, WAIT_OFFLINE = 20 * 60;

    /** what to collect: an exact item (fetch) or a recipe's ingredient (craft) */
    static final class Need {
        String key;
        RecipeIndex.Ingredient ing;
        int left;

        boolean matches(ItemStack s) {
            return s != null && (key != null ? RecipeIndex.key(s).equals(key) : Actions.fits(s, ing));
        }
    }

    static final class Stop {
        int kind, x, y, z;
        ItemStack item;   // what is placed there

        Stop(int kind, int x, int y, int z) {
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /** one job: a route of stops and what is carried */
    static final class Errand {
        String player, what;
        ItemStack want;
        int count;
        RecipeIndex.Recipe recipe;
        int times;
        final List<Need> needs = new ArrayList<Need>();
        final LinkedList<Stop> route = new LinkedList<Stop>();
        final List<ItemStack> bag = new ArrayList<ItemStack>();
        final List<Stop> sources = new ArrayList<Stop>();
        int dim, made, step, ticks, wait, askedAt;
        volatile int asked;   // 0 not yet, 1 waiting, 2 yes, 3 no (the chat thread answers)
        boolean open, lead;   // lead: no body, the player walks the route along a trail of sparks (or the autopilot walks them)
        boolean manual, gui, building;   // manual: they took the controls back; gui: the block's window is open
        int phase, since, failed;
        List<Stop> jobs;   // a building plan
        Stop shown;
        String why = "";
    }

    /** one body per player, with its queue of errands */
    static final class Helper {
        String player;
        GuideBody body;
        final java.util.concurrent.ConcurrentLinkedDeque<Errand> queue = new java.util.concurrent.ConcurrentLinkedDeque<Errand>();
        int idle, stuck, offline;
        double best = Double.MAX_VALUE;
        int lastCheck;
    }

    final AIGuide mod;
    final Map<String, Helper> helpers = new java.util.concurrent.ConcurrentHashMap<String, Helper>();

    Errands(AIGuide mod) {
        this.mod = mod;
    }

    // ---- planning (from the tools) ----------------------------------------------------------

    String fetch(EntityPlayerMP p, ItemStack want, int count, boolean lead) {
        Errand e = new Errand();
        e.lead = lead;
        e.player = p.func_70005_c_();
        e.want = want;
        e.count = count;
        Need n = new Need();
        n.key = RecipeIndex.key(want);
        n.left = count;
        e.needs.add(n);
        int found = chests(p, e);
        n.left = count;   // counted again on the way
        String name = mod.lang.name(want);
        if (found == 0) return "No " + name + " in the inventories within " + mod.config.fetchRadius + " blocks (AE2 networks are not taken from).";
        if (!lead) e.route.add(new Stop(BRING, 0, 0, 0));
        e.what = Math.min(found, count) + " " + name;
        return start(p, e, "bring " + e.what + (found < count ? " (only " + found + " are around)" : ""));
    }

    String craft(EntityPlayerMP p, ItemStack want, int count, boolean lead) {
        String k = RecipeIndex.key(want);
        int[] table = table(p);
        boolean pocket = false;   // no table: what fits the 2x2 grid of the player's own inventory is crafted there
        if (table == null) {
            if (lead && mod.link.has(p.func_70005_c_()))
                for (RecipeIndex.Recipe r : mod.recipes.producing(want)) if (r.machine.equals("crafting") && small(r)) pocket = true;
            if (!pocket) return "No crafting table within " + mod.config.fetchRadius + " blocks of the player: the guide crafts at a table, like a player. "
                    + "Only what fits the 2x2 grid of the inventory (planks, sticks, a crafting table) is crafted without one. Ask them to put one down.";
        }
        List<ItemStack> pool = pool(p);
        RecipeIndex.Recipe best = null;
        int bestTimes = 0;
        String missing = "";
        for (RecipeIndex.Recipe r : mod.recipes.producing(want)) {
            boolean refill = false;   // recharging or repairing the thing itself is not making it
            for (RecipeIndex.Ingredient g : r.in) for (ItemStack o : g.options) if (RecipeIndex.key(o).equals(k)) refill = true;
            if (!r.machine.equals("crafting") || refill || r.out.isEmpty() || r.out.get(0).stack == null) continue;
            if (pocket && !small(r)) continue;
            int per = Math.max(1, r.out.get(0).stack.field_77994_a);
            int times = Math.min(64, (count + per - 1) / per);
            StringBuilder lack = new StringBuilder();
            int[] cells = cells(r);
            for (int i = 0; i < r.in.size(); i++) {
                if (cells[i] == 0) continue;
                int have = available(pool, r.in.get(i));
                times = Math.min(times, have / cells[i]);
                if (have < cells[i]) lack.append(lack.length() > 0 ? ", " : "").append(mod.lang.name(r.in.get(i).options.isEmpty() ? want : r.in.get(i).options.get(0)));
            }
            if (times > bestTimes) { best = r; bestTimes = times; }
            if (missing.isEmpty()) missing = lack.toString();
        }
        String name = mod.lang.name(want);
        if (best == null) {
            if (missing.isEmpty()) return name + " is not made at a crafting table: use how_to_make for the machine.";
            return "Cannot craft " + name + ": missing " + missing + " (in the player's inventory and the inventories within " + mod.config.fetchRadius
                    + " blocks). Use plan_crafting to see the whole chain.";
        }
        Errand e = new Errand();
        e.lead = lead;
        e.player = p.func_70005_c_();
        e.want = want;
        e.recipe = best;
        e.times = bestTimes;
        int[] cells = cells(best);
        for (int i = 0; i < best.in.size(); i++) {
            if (cells[i] == 0) continue;
            Need n = new Need();
            n.ing = best.in.get(i);
            n.left = cells[i] * bestTimes;
            e.needs.add(n);
        }
        if (lead)   // what the player carries is not fetched
            for (Need n : e.needs) n.left = Math.max(0, n.left - carried(p, n));
        chests(p, e);
        boolean fromPlayer = false;
        for (Need n : e.needs) if (n.left > 0) fromPlayer = true;
        for (Need n : e.needs) n.left = 0;   // counted again on the way
        for (int i = 0, j = 0; i < best.in.size(); i++) if (cells[i] > 0) e.needs.get(j++).left = cells[i] * bestTimes;
        if (fromPlayer && !lead) e.route.add(new Stop(FROM_PLAYER, 0, 0, 0));
        e.route.add(pocket ? new Stop(POCKET, 0, 0, 0) : new Stop(TABLE, table[0], table[1], table[2]));
        if (!lead) e.route.add(new Stop(BRING, 0, 0, 0));
        int per = Math.max(1, best.out.get(0).stack.field_77994_a);
        e.what = bestTimes * per + " " + name;
        return start(p, e, "craft " + e.what + (bestTimes * per < count ? " (only enough for " + bestTimes * per + ")" : "")
                + (pocket ? " in the 2x2 grid of the player's inventory" : " at the crafting table at " + table[0] + " " + table[1] + " " + table[2]));
    }

    /** the recipe fits the 2x2 grid of the inventory */
    static boolean small(RecipeIndex.Recipe r) {
        if (r.grid != null) {
            if (r.grid.length > 2) return false;
            for (String row : r.grid) if (row.length() > 2) return false;
            return true;
        }
        int n = 0;
        for (RecipeIndex.Ingredient g : r.in) n += g.amount;
        return n <= 4;
    }

    /** the 2x2 grid of the inventory: the autopilot opens the inventory, the grid is laid out and the result taken, one craft a second */
    void leadPocket(Helper h, Errand e, Stop s, WorldServer w, EntityPlayerMP p, boolean auto) {
        if (e.phase == 0) {
            AIGuide.LOG.info("errand for {}: crafting in the inventory after {} s", h.player, e.ticks / 20);
            e.gui = auto;
            if (auto) mod.link.send(p, ClientLink.msg("inv"));
            e.phase = 2;
            e.wait = auto ? 14 : 0;
            return;
        }
        s.x = (int) Math.floor(p.field_70165_t);
        s.y = (int) Math.floor(p.field_70163_u);
        s.z = (int) Math.floor(p.field_70161_v);
        ItemStack[] inv = p.field_71071_by.field_70462_a;
        net.minecraft.inventory.ContainerPlayer c = (net.minecraft.inventory.ContainerPlayer) p.field_71069_bz;
        if (e.phase == 2) {
            if (e.step >= e.times) { e.phase = 4; return; }
            int[] slots = new int[9];
            InventoryCrafting g = Actions.grid(inv, e.recipe, slots);
            if (g == null) { e.why = mod.msg("short"); e.step = e.times; return; }
            for (int i = 0; i < 9; i++) {
                if (slots[i] < 0 || inv[slots[i]] == null) continue;
                int cell = e.recipe.grid == null ? i : (i / 3) * 2 + i % 3;   // 3x3 cell -> 2x2 cell (shapeless: in order)
                ItemStack one = inv[slots[i]].func_77979_a(1);
                if (inv[slots[i]].field_77994_a <= 0) inv[slots[i]] = null;
                c.field_75181_e.func_70299_a(cell, one);
            }
            c.func_75130_a(c.field_75181_e);
            c.func_75142_b();
            e.phase = 3;
            e.wait = 12;
            return;
        }
        if (e.phase == 3) {
            ItemStack r = c.func_75139_a(0).func_75211_c();
            if (r == null || !RecipeIndex.key(r).equals(RecipeIndex.key(e.want))) {
                e.why = mod.msg("nogrid");
                for (int i = 0; i < 4; i++) {   // the grid goes back into the bag
                    ItemStack left = c.field_75181_e.func_70304_b(i);
                    if (left != null) Actions.give(p, left);
                }
                Actions.sync(p);
                e.step = e.times;
                e.phase = 4;
                return;
            }
            int n = r.field_77994_a;
            c.func_75144_a(0, 0, 1, p);   // shift-click on the result, as a player would
            c.func_75142_b();
            e.phase = 2;
            crafted(e, s, w, n);
            return;
        }
        if (e.gui) mod.link.send(p, ClientLink.msg("close"));
        e.route.poll();
        e.phase = 0;
        e.wait = 6;
    }

    static int carried(EntityPlayerMP p, Need n) {
        int have = 0;
        for (ItemStack c : p.field_71071_by.field_70462_a) if (n.matches(c)) have += c.field_77994_a;
        return have;
    }

    /** grid cells each ingredient takes in one craft */
    static int[] cells(RecipeIndex.Recipe r) {
        int[] c = new int[r.in.size()];
        if (r.grid != null) {
            for (String row : r.grid) for (char ch : row.toCharArray()) if (ch != '.' && ch - 'A' < c.length) c[ch - 'A']++;
        } else for (int i = 0; i < c.length; i++) c[i] = r.in.get(i).amount;
        return c;
    }

    /** the chests to visit, nearest first, until the needs are met (the needs are counted down); returns how many were found */
    int chests(EntityPlayerMP p, Errand e) {
        int found = 0;
        for (Storage.Place pl : mod.storage.around(p, mod.config.fetchRadius)) {
            if (pl.inv == null) continue;
            boolean use = false;
            for (int i = 0; i < pl.inv.func_70302_i_(); i++) {
                ItemStack s = pl.inv.func_70301_a(i);
                for (Need n : e.needs)
                    if (n.left > 0 && n.matches(s)) {
                        int take = Math.min(n.left, s.field_77994_a);
                        n.left -= take;
                        found += take;
                        use = true;
                        break;
                    }
            }
            if (use) e.route.add(new Stop(CHEST, pl.x, pl.y, pl.z));
            boolean done = true;
            for (Need n : e.needs) if (n.left > 0) done = false;
            if (done) break;
        }
        return found;
    }

    /** what the player and the inventories near them hold, stack by stack (a filled capsule is not an empty one) */
    List<ItemStack> pool(EntityPlayerMP p) {
        List<ItemStack> t = new ArrayList<ItemStack>();
        for (ItemStack s : p.field_71071_by.field_70462_a) if (s != null) t.add(s);
        for (Storage.Place pl : mod.storage.around(p, mod.config.fetchRadius))
            if (pl.inv != null)
                for (int i = 0; i < pl.inv.func_70302_i_(); i++) if (pl.inv.func_70301_a(i) != null) t.add(pl.inv.func_70301_a(i));
        return t;
    }

    static int available(List<ItemStack> pool, RecipeIndex.Ingredient g) {
        int n = 0;
        for (ItemStack s : pool) if (Actions.fits(s, g)) n += s.field_77994_a;
        return n;
    }

    /** the nearest crafting table to the player */
    int[] table(EntityPlayerMP p) {
        World w = p.field_70170_p;
        int r = mod.config.fetchRadius, px = (int) Math.floor(p.field_70165_t), py = (int) Math.floor(p.field_70163_u), pz = (int) Math.floor(p.field_70161_v);
        int[] best = null;
        double bd = Double.MAX_VALUE;
        for (int x = px - r; x <= px + r; x++)
            for (int y = Math.max(0, py - 8); y <= Math.min(255, py + 8); y++)
                for (int z = pz - r; z <= pz + r; z++) {
                    if (w.func_147439_a(x, y, z) != Blocks.field_150462_ai) continue;
                    double d = p.func_70092_e(x + 0.5, y + 0.5, z + 0.5);
                    if (d < bd) { bd = d; best = new int[] { x, y, z }; }
                }
        return best;
    }

    String start(EntityPlayerMP p, Errand e, String what) {
        Helper h = helpers.get(e.player);
        if (h == null) helpers.put(e.player, h = new Helper());
        h.player = e.player;
        e.dim = p.field_71093_bK;
        int ahead = h.queue.size();
        h.queue.add(e);
        int stops = 0;
        StringBuilder r = new StringBuilder();
        for (Stop s : e.route) {
            if (s.kind == CHEST) stops++;
            r.append(r.length() > 0 ? " -> " : "").append(new String[] { "chest", "player's inventory", "table", "player", "place", "dig", "inventory grid", "pick up" }[s.kind])
                    .append(s.kind == CHEST || s.kind == TABLE ? " " + s.x + " " + s.y + " " + s.z : "");
        }
        AIGuide.LOG.info("errand for {}{}: {}; from {}: {}", e.player, e.lead ? " (lead)" : "", what, at(p.field_70165_t, p.field_70163_u, p.field_70161_v), r);
        if (e.jobs != null)
            return mod.link.has(e.player) ? "The plan is shown to the player as see-through blocks and they are asked; with their yes the autopilot "
                    + "walks them there and builds it. Tell them to look and answer yes or no; do not call this again for it."
                    : "The player has no client mod: the planned blocks are marked with sparks for two minutes for them to build by hand. Tell them so.";
        if (e.lead && mod.link.has(e.player))
            return "The autopilot of the player's client walks them to " + what + ": " + r + " (they can take the controls back with any movement key; "
                    + "then sparks show the way). It opens the chests and the table itself and the guide tells each step in chat. Tell the player; "
                    + "do not call this again for it.";
        if (e.lead)
            return "A trail of sparks leads the player to " + what + ": " + r + ". When they reach a chest, what is needed goes into their inventory"
                    + (e.recipe != null ? "; at the crafting table it is crafted from their inventory" : "")
                    + ". The guide tells each next point in chat. Tell the player to follow the sparks; do not call this again for it.";
        return "The guide's body (a villager named " + mod.config.assistantName + ") is on its way to " + what + ": it walks to "
                + stops + " chest(s)" + (e.recipe != null ? ", the crafting table" : "") + " and then to the player, about "
                + (10 + stops * 6 + e.times) + " s" + (ahead > 0 ? ", after " + ahead + " errand(s) before it" : "")
                + ". It hands the things over itself and says so in chat. Tell the player it is coming; do not call this again for it.";
    }

    // ---- walking (every server tick) --------------------------------------------------------

    void tick() {
        for (Iterator<Helper> it = helpers.values().iterator(); it.hasNext(); ) {
            Helper h = it.next();
            try {
                if (!step(h)) it.remove();
            } catch (Exception ex) {
                AIGuide.LOG.warn("guide errand failed", ex);
                Errand e = h.queue.poll();
                if (e != null) abort(h, e, "error: " + ex);
            }
        }
    }

    /** false when the helper is done and gone */
    boolean step(Helper h) {
        EntityPlayerMP p = AIGuide.player(h.player);
        Errand e = h.queue.peek();
        if (e == null) {
            if (h.body != null && p != null && h.body.field_70170_p == p.field_70170_p && h.idle % 20 == 0 && far(h.body, p.field_70165_t, p.field_70163_u, p.field_70161_v, 4))
                h.body.func_70661_as().func_75497_a(p, 0.6);   // stays around for a while, in case there is more
            if (++h.idle < IDLE && p != null) return true;
            vanish(h);
            return false;
        }
        h.idle = 0;
        if (p == null || p.field_71093_bK != e.dim) {   // offline or in another world: waits a minute
            if (++h.offline > WAIT_OFFLINE) { h.queue.poll(); abort(h, e, mod.msg("left")); }
            return true;
        }
        h.offline = 0;
        if (e.lead) return lead(h, e, p);
        if (h.body == null || h.body.field_70128_L || !h.body.field_70170_p.field_72996_f.contains(h.body) || h.body.field_70170_p != p.field_70170_p) appear(h, p);
        if (++e.ticks > TIMEOUT) { h.queue.poll(); abort(h, e, "it took too long"); return true; }
        if (e.wait > 0) { e.wait--; return true; }
        Stop s = e.route.peek();
        if (s == null) { h.queue.poll(); return true; }
        GuideBody b = h.body;
        WorldServer w = (WorldServer) b.field_70170_p;
        if (s.kind == CHEST || s.kind == TABLE) {
            if (!walk(h, s.x + 0.5, s.y + 0.5, s.z + 0.5, 2.8)) return true;
            b.func_70671_ap().func_75650_a(s.x + 0.5, s.y + 0.5, s.z + 0.5, 30, 30);
            if (s.kind == CHEST) chest(h, e, s, w);
            else bench(h, e, s, w, p);
        } else if (s.kind == FROM_PLAYER) {
            if (!walk(h, p.field_70165_t, p.field_70163_u, p.field_70161_v, 2.5)) return true;
            b.func_70625_a(p, 30, 30);
            ItemStack[] inv = p.field_71071_by.field_70462_a;
            if (e.asked == 0) {   // the player's things are taken only with a yes
                List<String> lack = new ArrayList<String>();
                for (Need n : e.needs) {
                    int have = 0;
                    for (ItemStack c : inv) if (n.matches(c)) have += c.field_77994_a;
                    if (n.left > 0 && have > 0) lack.add(Math.min(n.left, have) + " " + mod.lang.name(firstMatch(inv, n)));
                }
                if (lack.isEmpty()) { e.route.poll(); return true; }
                mod.say(h.player, mod.msg("ask") + " " + String.join(", ", lack) + ". " + mod.msg("askhow"));
                e.asked = 1;
                e.askedAt = e.ticks;
                return true;
            }
            if (e.asked == 1) {
                if (e.ticks - e.askedAt > 20 * 60) e.asked = 3;
                else return true;
            }
            if (e.asked == 3) {
                h.queue.poll();
                stash(h, e);
                mod.say(h.player, mod.msg("declined"));
                return true;
            }
            List<String> took = new ArrayList<String>();
            for (Need n : e.needs)
                for (int i = 0; i < inv.length && n.left > 0; i++)
                    if (n.matches(inv[i])) {
                        ItemStack t = p.field_71071_by.func_70298_a(i, Math.min(n.left, inv[i].field_77994_a));
                        n.left -= t.field_77994_a;
                        took.add(t.field_77994_a + " " + mod.lang.name(t));
                        put(e.bag, t);
                    }
            Actions.sync(p);
            if (!took.isEmpty()) mod.say(h.player, mod.msg("took") + " " + String.join(", ", took));
            e.route.poll();
            e.wait = 10;
        } else {
            if (!walk(h, p.field_70165_t, p.field_70163_u, p.field_70161_v, 2.5)) return true;
            b.func_70625_a(p, 30, 30);
            List<String> gave = new ArrayList<String>();
            for (ItemStack t : merged(e.bag)) {
                gave.add(t.field_77994_a + " " + mod.lang.name(t));
                Actions.give(p, t);
            }
            e.bag.clear();
            Actions.sync(p);
            w.func_72956_a(p, "random.pop", 0.4f, 1.2f);
            String done;
            if (e.recipe != null) done = e.made > 0 ? mod.msg("crafted") + " " + e.made + " " + mod.lang.name(e.want) + "." : mod.msg("notcrafted") + " " + e.why;
            else done = gave.isEmpty() ? mod.msg("nothing") : mod.msg("brought") + " " + String.join(", ", gave) + ".";
            if (e.recipe != null && !gave.isEmpty()) done += " " + mod.msg("handed") + " " + String.join(", ", gave) + ".";
            mod.say(h.player, done);
            AIGuide.LOG.info("errand for {} done in {} s: {}", h.player, e.ticks / 20, done);
            h.queue.poll();
        }
        return true;
    }

    // ---- leading: the player walks, or the client mod's autopilot walks them ----------------------

    /** the client mod drives the player, unless they took the controls back */
    boolean pilot(Helper h, Errand e) {
        return !e.manual && mod.link.has(h.player);
    }

    boolean lead(Helper h, Errand e, EntityPlayerMP p) {
        if (++e.ticks > LEAD_TIMEOUT) { h.queue.poll(); leadDone(h, e, p, mod.msg("toolong")); return true; }
        if (e.wait > 0) { e.wait--; return true; }
        WorldServer w = (WorldServer) p.field_70170_p;
        if (e.jobs != null && !e.building) return plan(h, e, p, w);
        Stop s = e.route.peek();
        if (s == null) { h.queue.poll(); leadDone(h, e, p, null); return true; }
        if (s.kind == POCKET) { leadPocket(h, e, s, w, p, pilot(h, e)); return true; }
        if (s.kind == PICKUP) {   // walk over the dropped things
            if (e.shown != s) {
                e.shown = s;
                e.since = e.ticks;
                JsonObject m = ClientLink.msg("goto");
                m.addProperty("x", s.x);
                m.addProperty("y", s.y);
                m.addProperty("z", s.z);
                m.addProperty("reach", 0.4);
                mod.link.send(p, m);
            }
            double dx = p.field_70165_t - (s.x + 0.5), dz = p.field_70161_v - (s.z + 0.5);
            if (dx * dx + dz * dz < 0.8 && e.ticks - e.since > 30 || e.ticks - e.since > 20 * 12) { e.route.poll(); e.phase = 0; e.wait = 10; }
            return true;
        }
        boolean auto = pilot(h, e), work = s.kind == PLACE || s.kind == DIG;
        double cx = s.x + 0.5, cy = s.y + 0.5, cz = s.z + 0.5, d = Math.sqrt(p.func_70092_e(cx, cy, cz));
        if (work) d = Math.sqrt(p.func_70092_e(cx, cy - p.func_70047_e(), cz));   // a block to dig or place: from the eyes, as the server measures a dig
        if (e.shown != s) {
            e.shown = s;
            e.phase = 0;
            if (!work) mod.say(h.player, mod.msg((auto ? "pilot" : "go") + (s.kind == CHEST ? "chest" : "table")) + " " + s.x + " " + s.y + " " + s.z
                    + " (" + (int) d + " " + mod.msg("blocks") + ")");
            if (auto) {
                JsonObject m = ClientLink.msg("goto");
                m.addProperty("x", s.x);
                m.addProperty("y", s.y);
                m.addProperty("z", s.z);
                m.addProperty("reach", work ? 4.4 : 2.5);
                mod.link.send(p, m);
            }
        }
        if (e.phase == 0 && d > (work ? 5.2 : 3.5)) {
            if (!auto && e.ticks % 10 == 0) trail(w, p, cx, cy, cz);
            return true;
        }
        if (s.kind == CHEST) leadChest(h, e, s, w, p, auto);
        else if (s.kind == TABLE) leadBench(h, e, s, w, p, auto);
        else work(h, e, s, w, p);
        return true;
    }

    void use(EntityPlayerMP p, Stop s) {
        JsonObject m = ClientLink.msg("use");
        m.addProperty("x", s.x);
        m.addProperty("y", s.y);
        m.addProperty("z", s.z);
        mod.link.send(p, m);
    }

    /** a window of a block is open (not the player's own inventory) */
    static boolean open(EntityPlayerMP p) {
        return p.field_71070_bA != null && p.field_71070_bA != p.field_71069_bz;
    }

    /** at the chest: the autopilot opens it (or the lid goes up), what is needed goes to the player */
    void leadChest(Helper h, Errand e, Stop s, WorldServer w, EntityPlayerMP p, boolean auto) {
        TileEntity t = w.func_147438_o(s.x, s.y, s.z);
        IInventory inv = t instanceof IInventory ? (IInventory) t : null;
        if (e.phase == 0) {
            AIGuide.LOG.info("errand for {}: the player is at the chest {} {} {} after {} s", h.player, s.x, s.y, s.z, e.ticks / 20);
            e.gui = false;
            if (auto) { use(p, s); e.phase = 1; e.since = e.ticks; return; }
            if (inv != null) inv.func_70295_k_();
            e.phase = 2;
            e.wait = 12;
            return;
        }
        if (e.phase == 1) {   // waiting for the window
            if (open(p)) { e.gui = true; e.phase = 2; e.wait = 10; }
            else if (e.ticks - e.since > 60) { if (inv != null) inv.func_70295_k_(); e.phase = 2; }
            return;
        }
        if (e.phase == 2) {
            List<ItemStack> got = new ArrayList<ItemStack>();
            if (inv != null) {
                for (Need n : e.needs) {
                    int want = e.recipe != null ? n.left - carried(p, n) : n.left;
                    for (int i = 0; i < inv.func_70302_i_() && want > 0; i++) {
                        ItemStack c = inv.func_70301_a(i);
                        if (!n.matches(c)) continue;
                        ItemStack g = inv.func_70298_a(i, Math.min(want, c.field_77994_a));
                        if (g == null) continue;
                        want -= g.field_77994_a;
                        if (e.recipe == null) n.left -= g.field_77994_a;
                        got.add(g.func_77946_l());
                        Actions.give(p, g);
                    }
                }
                inv.func_70296_d();
            }
            if (open(p)) p.field_71070_bA.func_75142_b();
            Actions.sync(p);
            if (!got.isEmpty()) {
                List<String> l = new ArrayList<String>();
                for (ItemStack g : merged(got)) l.add(g.field_77994_a + " " + mod.lang.name(g));
                mod.say(h.player, mod.msg("gotit") + " " + String.join(", ", l));
            }
            e.phase = 3;
            e.wait = e.gui ? 15 : 8;
            return;
        }
        if (e.gui) mod.link.send(p, ClientLink.msg("close"));
        else if (inv != null) inv.func_70305_f();
        e.route.poll();
        e.phase = 0;
        e.wait = 6;
    }

    /** at the crafting table: the autopilot opens it and the grid is laid out and taken in its window, one craft a second */
    void leadBench(Helper h, Errand e, Stop s, WorldServer w, EntityPlayerMP p, boolean auto) {
        if (w.func_147439_a(s.x, s.y, s.z) != Blocks.field_150462_ai) { e.why = mod.msg("notable"); e.route.poll(); return; }
        if (e.phase == 0) {
            AIGuide.LOG.info("errand for {}: the player is at the table after {} s", h.player, e.ticks / 20);
            e.gui = false;
            if (auto) { use(p, s); e.phase = 1; e.since = e.ticks; }
            else e.phase = 2;
            return;
        }
        if (e.phase == 1) {
            if (p.field_71070_bA instanceof ContainerWorkbench) { e.gui = true; e.phase = 2; e.wait = 10; }
            else if (e.ticks - e.since > 60) e.phase = 2;
            return;
        }
        ItemStack[] inv = p.field_71071_by.field_70462_a;
        if (e.gui && !(p.field_71070_bA instanceof ContainerWorkbench)) e.gui = false;   // they closed it
        if (e.phase == 2) {
            if (e.step >= e.times) { e.phase = 4; return; }
            int[] slots = new int[9];
            InventoryCrafting g = Actions.grid(inv, e.recipe, slots);
            if (g == null) { e.why = mod.msg("short"); e.step = e.times; return; }
            if (e.gui) {   // the grid is laid out in the open window, the result is taken from it
                ContainerWorkbench c = (ContainerWorkbench) p.field_71070_bA;
                for (int i = 0; i < 9; i++) {
                    if (slots[i] < 0 || inv[slots[i]] == null) continue;
                    ItemStack one = inv[slots[i]].func_77979_a(1);
                    if (inv[slots[i]].field_77994_a <= 0) inv[slots[i]] = null;
                    c.field_75162_e.func_70299_a(i, one);
                }
                c.func_75130_a(c.field_75162_e);
                c.func_75142_b();
                e.phase = 3;
                e.wait = 12;
                return;
            }
            ItemStack out = CraftingManager.func_77594_a().func_82787_a(g, w);
            if (out == null || !RecipeIndex.key(out).equals(RecipeIndex.key(e.want))) { e.why = mod.msg("nogrid"); e.step = e.times; return; }
            FMLCommonHandler.instance().firePlayerCraftingEvent(p, out, g);
            for (int i = 0; i < 9; i++) {
                if (slots[i] < 0 || inv[slots[i]] == null) continue;
                ItemStack c = Actions.containerItem(inv[slots[i]]);
                if (--inv[slots[i]].field_77994_a <= 0) inv[slots[i]] = null;
                if (c != null) Actions.give(p, c);
            }
            Actions.give(p, out.func_77946_l());
            Actions.sync(p);
            crafted(e, s, w, out.field_77994_a);
            return;
        }
        if (e.phase == 3) {
            if (!e.gui) { e.phase = 2; return; }
            ContainerWorkbench c = (ContainerWorkbench) p.field_71070_bA;
            ItemStack r = c.func_75139_a(0).func_75211_c();
            if (r == null || !RecipeIndex.key(r).equals(RecipeIndex.key(e.want))) {
                e.why = mod.msg("nogrid");   // what is in the grid goes back to the player when the window closes
                e.step = e.times;
                e.phase = 4;
                return;
            }
            int n = r.field_77994_a;
            c.func_75144_a(0, 0, 1, p);   // shift-click on the result, as a player would
            c.func_75142_b();
            e.phase = 2;
            crafted(e, s, w, n);
            return;
        }
        if (e.gui) mod.link.send(p, ClientLink.msg("close"));
        e.route.poll();
        e.phase = 0;
        e.wait = 6;
    }

    void crafted(Errand e, Stop s, WorldServer w, int n) {
        e.made += n;
        e.step++;
        w.func_72908_a(s.x + 0.5, s.y + 0.5, s.z + 0.5, "dig.wood", 0.6f, 1.0f);
        w.func_147487_a("crit", s.x + 0.5, s.y + 1.1, s.z + 0.5, 6, 0.25, 0.1, 0.25, 0.05);
        e.wait = 20;
    }

    // ---- building: a plan shown as see-through blocks, built by the autopilot after a yes -------

    String build(EntityPlayerMP p, List<int[]> places, List<ItemStack> items, List<int[]> digs) {
        Errand e = new Errand();
        e.lead = true;
        e.player = p.func_70005_c_();
        e.jobs = new ArrayList<Stop>();
        for (int[] d : digs) e.jobs.add(new Stop(DIG, d[0], d[1], d[2]));
        List<Stop> put = new ArrayList<Stop>();
        for (int i = 0; i < places.size(); i++) {
            Stop s = new Stop(PLACE, places.get(i)[0], places.get(i)[1], places.get(i)[2]);
            s.item = items.get(i);
            put.add(s);
        }
        java.util.Collections.sort(put, new java.util.Comparator<Stop>() {   // from the bottom up
            @Override public int compare(Stop a, Stop b) { return a.y - b.y; }
        });
        e.jobs.addAll(put);
        Map<String, Integer> need = new LinkedHashMap<String, Integer>();
        Map<String, ItemStack> what = new LinkedHashMap<String, ItemStack>();
        for (Stop s : put) {
            String k = RecipeIndex.key(s.item);
            Integer o = need.get(k);
            need.put(k, (o == null ? 0 : o) + 1);
            what.put(k, s.item);
        }
        List<String> lack = new ArrayList<String>();
        for (Map.Entry<String, Integer> n : need.entrySet()) {
            int have = 0;
            for (ItemStack c : p.field_71071_by.field_70462_a) if (c != null && RecipeIndex.key(c).equals(n.getKey())) have += c.field_77994_a;
            if (have < n.getValue()) lack.add((n.getValue() - have) + " " + mod.lang.name(what.get(n.getKey())));
        }
        if (!lack.isEmpty()) return "The player does not carry: " + String.join(", ", lack) + ". Get them first (fetch or craft), then build.";
        e.what = put.size() + " block(s) to place, " + digs.size() + " to dig";
        return start(p, e, "show the plan (" + e.what + ") and build it after the player's yes");
    }

    /** the plan is shown and the player is asked; with a yes the jobs become the route */
    boolean plan(Helper h, Errand e, EntityPlayerMP p, WorldServer w) {
        boolean auto = pilot(h, e);
        if (e.asked == 0) {
            if (auto) mod.link.send(p, ghost(e));
            else e.asked = 4;   // nothing to drive: the player builds by the sparks
            int put = 0;
            for (Stop s : e.jobs) if (s.kind == PLACE) put++;
            mod.say(h.player, mod.msg("plan") + " " + put + " " + mod.msg("toplace") + ", " + (e.jobs.size() - put) + " " + mod.msg("todig") + ". "
                    + mod.msg(auto ? "planask" : "planself"));
            if (e.asked == 0) e.asked = 1;
            e.askedAt = e.ticks;
            return true;
        }
        if (e.asked == 4) {
            if (e.ticks % 10 == 0) marks(w, e);
            if (e.ticks - e.askedAt > 20 * 120) h.queue.poll();
            return true;
        }
        if (e.asked == 1) {
            if (!auto && e.ticks % 10 == 0) marks(w, e);
            if (e.ticks - e.askedAt > 20 * 120) e.asked = 3;
            return true;
        }
        if (e.asked == 3) {
            h.queue.poll();
            mod.link.send(p, ClientLink.msg("ghostclear"));
            mod.say(h.player, mod.msg("planno"));
            return true;
        }
        e.building = true;
        e.route.addAll(e.jobs);
        Stop low = null;   // what was dug fell there: the autopilot walks over it to pick it up
        for (Stop s : e.jobs) if (s.kind == DIG && (low == null || s.y < low.y)) low = s;
        if (low != null && pilot(h, e)) e.route.add(new Stop(PICKUP, low.x, low.y, low.z));
        AIGuide.LOG.info("errand for {}: building {}", h.player, e.what);
        return true;
    }

    static JsonObject ghost(Errand e) {
        JsonObject m = ClientLink.msg("ghost");
        JsonArray put = new JsonArray(), dig = new JsonArray();
        for (Stop s : e.jobs) {
            JsonArray a = new JsonArray();
            a.add(new JsonPrimitive(s.x));
            a.add(new JsonPrimitive(s.y));
            a.add(new JsonPrimitive(s.z));
            if (s.kind == PLACE) {
                a.add(new JsonPrimitive(net.minecraft.item.Item.field_150901_e.func_148750_c(s.item.func_77973_b())));
                a.add(new JsonPrimitive(s.item.func_77960_j()));
                put.add(a);
            } else dig.add(a);
        }
        m.add("place", put);
        m.add("dig", dig);
        return m;
    }

    /** without the client mod: sparks over the planned blocks */
    static void marks(WorldServer w, Errand e) {
        for (Stop s : e.jobs)
            w.func_147487_a(s.kind == PLACE ? "happyVillager" : "reddust", s.x + 0.5, s.y + 0.5, s.z + 0.5, 4, 0.3, 0.3, 0.3, 0.0);
    }

    /** one block of the plan: the item goes into the player's hand and the autopilot places it, or digs */
    void work(Helper h, Errand e, Stop s, WorldServer w, EntityPlayerMP p) {
        boolean air = w.func_147437_c(s.x, s.y, s.z);
        if (e.phase == 0) {
            if (s.kind == DIG && air || s.kind == PLACE && !air && !w.func_147439_a(s.x, s.y, s.z).func_149688_o().func_76222_j()) { next(e, s, p); return; }
            if (!pilot(h, e)) { e.why = mod.msg("manual"); next(e, s, p); return; }
            JsonObject m = ClientLink.msg(s.kind == PLACE ? "place" : "dig");
            m.addProperty("x", s.x);
            m.addProperty("y", s.y);
            m.addProperty("z", s.z);
            if (s.kind == PLACE) {
                ItemStack[] inv = p.field_71071_by.field_70462_a;
                int cur = p.field_71071_by.field_70461_c, at = -1;
                for (int i = 0; i < inv.length && at < 0; i++) if (inv[i] != null && RecipeIndex.key(inv[i]).equals(RecipeIndex.key(s.item))) at = i;
                if (at < 0) { e.why = mod.msg("short"); next(e, s, p); return; }
                if (at >= 9) {   // into the hand, as the player would move it
                    ItemStack x = inv[cur];
                    inv[cur] = inv[at];
                    inv[at] = x;
                    at = cur;
                    Actions.sync(p);
                }
                m.addProperty("slot", at);
            }
            mod.link.send(p, m);
            e.phase = 1;
            e.since = e.ticks;
            return;
        }
        boolean done = s.kind == PLACE ? !air : air;
        if (done) { e.made++; next(e, s, p); return; }
        if (e.ticks - e.since > (s.kind == PLACE ? 80 : 20 * 30)) {
            AIGuide.LOG.info("errand for {}: could not {} at {} {} {}", h.player, s.kind == PLACE ? "place" : "dig", s.x, s.y, s.z);
            e.failed++;
            next(e, s, p);
        }
    }

    void next(Errand e, Stop s, EntityPlayerMP p) {
        e.route.poll();
        e.phase = 0;
        e.wait = 4;
    }

    void leadDone(Helper h, Errand e, EntityPlayerMP p, String why) {
        String done;
        if (why != null) done = mod.msg("gaveup") + " " + why;
        else if (e.jobs != null) done = mod.msg("built") + " " + e.made + "/" + e.jobs.size() + (e.failed > 0 ? ", " + mod.msg("notbuilt") + " " + e.failed : "") + ".";
        else if (e.recipe != null) done = e.made > 0 ? mod.msg("crafted") + " " + e.made + " " + mod.lang.name(e.want) + "." : mod.msg("notcrafted") + " " + e.why;
        else done = mod.msg("leaddone");
        if (p != null) {
            mod.link.send(p, ClientLink.msg("stop"));
            if (e.jobs != null) mod.link.send(p, ClientLink.msg("ghostclear"));
        }
        mod.say(h.player, done);
        AIGuide.LOG.info("errand for {} (lead) done in {} s: {}", h.player, e.ticks / 20, done);
    }

    /** from the client mod: the player took the controls back, or the autopilot found no way */
    void fromClient(String player, String t, JsonObject m) {
        Helper h = helpers.get(player);
        Errand e = h == null ? null : h.queue.peek();
        if (e == null || !e.lead || e.manual) return;
        String task = m.has("task") ? m.get("task").getAsString() : "";
        if (t.equals("failed") && (task.equals("place") || task.equals("dig"))) {   // one block did not work: the rest goes on
            AIGuide.LOG.info("errand for {}: {} failed: {}", player, task, m.has("why") ? m.get("why").getAsString() : "");
            Stop s = e.route.peek();
            if (s != null && (s.kind == PLACE || s.kind == DIG) && e.phase == 1) { e.failed++; next(e, s, null); }
            return;
        }
        if (t.equals("failed") && e.jobs != null && e.building) {   // no way to one block of a plan: the rest goes on
            Stop s = e.route.peek();
            if (s != null && (s.kind == PLACE || s.kind == DIG || s.kind == PICKUP)) {
                AIGuide.LOG.info("errand for {}: no way to {} {} {}, skipped", player, s.x, s.y, s.z);
                if (s.kind != PICKUP) e.failed++;
                e.shown = null;
                next(e, s, null);
                return;
            }
        }
        if (t.equals("cancel") || t.equals("failed")) {
            e.manual = true;
            e.shown = null;
            AIGuide.LOG.info("errand for {}: autopilot {}{}", player, t, m.has("why") ? ": " + m.get("why").getAsString() : "");
            mod.say(player, mod.msg(t.equals("cancel") ? "tookback" : "nopath"));
        }
    }

    /** sparks from the player toward the point, and a column over it */
    static void trail(WorldServer w, EntityPlayerMP p, double x, double y, double z) {
        double sx = p.field_70165_t, sy = p.field_70163_u + 1.2, sz = p.field_70161_v;
        double dx = x - sx, dy = y - sy, dz = z - sz, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        for (double t = 1.5; t < Math.min(d - 0.5, 24); t += 1.2)
            w.func_147487_a("happyVillager", sx + dx * t / d, sy + dy * t / d, sz + dz * t / d, 1, 0.05, 0.05, 0.05, 0.0);
        for (int k = 1; k <= 4; k++) w.func_147487_a("witchMagic", x, y + k * 0.6, z, 2, 0.1, 0.1, 0.1, 0.0);
    }

    static ItemStack firstMatch(ItemStack[] inv, Need n) {
        for (ItemStack s : inv) if (n.matches(s)) return s;
        return null;
    }

    /** the body waits for a yes or a no from this player; called on the server thread */
    boolean waiting(String player) {
        Helper h = helpers.get(player);
        Errand e = h == null ? null : h.queue.peek();
        return e != null && e.asked == 1;
    }

    void answer(String player, boolean yes) {
        Helper h = helpers.get(player);
        Errand e = h == null ? null : h.queue.peek();
        if (e != null && e.asked == 1) e.asked = yes ? 2 : 3;
    }

    static final java.util.regex.Pattern YES = java.util.regex.Pattern.compile("(?iu)(да|ага|бери|возьми|давай|можно|ок|ok|yes|y|sure)[.!]*"),
            NO = java.util.regex.Pattern.compile("(?iu)(нет|не надо|не бери|no|n|nope)[.!]*");

    /** a chat line as an answer: TRUE yes, FALSE no, null not an answer */
    static Boolean reply(String line) {
        String t = line.trim();
        return YES.matcher(t).matches() ? Boolean.TRUE : NO.matcher(t).matches() ? Boolean.FALSE : null;
    }

    /** at a chest: open it, take what is needed, close it */
    void chest(Helper h, Errand e, Stop s, WorldServer w) {
        TileEntity t = w.func_147438_o(s.x, s.y, s.z);
        IInventory inv = t instanceof IInventory ? (IInventory) t : null;
        if (!e.open) {
            AIGuide.LOG.info("errand for {}: at the chest {} {} {} after {} s", h.player, s.x, s.y, s.z, e.ticks / 20);
            if (inv != null) inv.func_70295_k_();   // the lid goes up
            e.open = true;
            e.wait = 12;
            return;
        }
        if (inv != null) {
            for (Need n : e.needs)
                for (int i = 0; i < inv.func_70302_i_() && n.left > 0; i++) {
                    ItemStack c = inv.func_70301_a(i);
                    if (!n.matches(c)) continue;
                    ItemStack got = inv.func_70298_a(i, Math.min(n.left, c.field_77994_a));
                    if (got == null) continue;
                    n.left -= got.field_77994_a;
                    put(e.bag, got);
                }
            inv.func_70296_d();
            inv.func_70305_f();   // and down
            e.sources.add(s);
        }
        e.open = false;
        e.route.poll();
        e.wait = 8;
        carrying(h, e);
    }

    /** at the crafting table: one craft a second, from the bag */
    void bench(Helper h, Errand e, Stop s, WorldServer w, EntityPlayerMP p) {
        if (w.func_147439_a(s.x, s.y, s.z) != Blocks.field_150462_ai) { e.why = mod.msg("notable"); e.route.poll(); return; }
        String k = RecipeIndex.key(e.want);
        if (e.step == 0 && e.made == 0 && e.why.isEmpty()) AIGuide.LOG.info("errand for {}: at the table after {} s", h.player, e.ticks / 20);
        if (e.step < e.times) {
            ItemStack[] inv = e.bag.toArray(new ItemStack[0]);
            int[] slots = new int[9];
            InventoryCrafting g = Actions.grid(inv, e.recipe, slots);
            ItemStack out = g == null ? null : CraftingManager.func_77594_a().func_82787_a(g, w);
            if (out == null || !RecipeIndex.key(out).equals(k)) {
                e.why = g == null ? mod.msg("short") : mod.msg("nogrid");
                e.step = e.times;
            } else {
                FMLCommonHandler.instance().firePlayerCraftingEvent(p, out, g);
                List<ItemStack> left = new ArrayList<ItemStack>();
                for (int i = 0; i < 9; i++) {
                    if (slots[i] < 0) continue;
                    ItemStack c = Actions.containerItem(inv[slots[i]]);
                    inv[slots[i]].field_77994_a--;
                    if (c != null) left.add(c);
                }
                e.bag.clear();
                for (ItemStack x : inv) if (x != null && x.field_77994_a > 0) e.bag.add(x);
                for (ItemStack x : left) put(e.bag, x);
                put(e.bag, out.func_77946_l());
                e.made += out.field_77994_a;
                e.step++;
                w.func_72908_a(s.x + 0.5, s.y + 0.5, s.z + 0.5, "dig.wood", 0.6f, 1.0f);
                w.func_147487_a("crit", s.x + 0.5, s.y + 1.1, s.z + 0.5, 6, 0.25, 0.1, 0.25, 0.05);
                carrying(h, e);
                e.wait = 20;
                return;
            }
        }
        e.route.poll();
        e.wait = 6;
    }

    /** walks toward a point; true when within reach. A body stuck for a while steps over (with a puff) */
    boolean walk(Helper h, double x, double y, double z, double reach) {
        GuideBody b = h.body;
        double d = Math.sqrt(b.func_70092_e(x, y, z));
        if (d <= reach) {
            b.func_70661_as().func_75499_g();
            h.best = Double.MAX_VALUE;
            h.stuck = 0;
            return true;
        }
        int t = ++h.lastCheck;
        if (t % 10 == 0 && b.func_70661_as().func_75500_f() || t % 20 == 0) {
            boolean ok = b.func_70661_as().func_75492_a(x, y, z, 0.8);
            if (!ok && t % 40 == 0) AIGuide.LOG.info("errand for {}: no path from {} to {} ({} blocks)", h.player, at(b), at(x, y, z), (int) d);
        }
        if (t % 40 == 0) {
            if (d < h.best - 0.5) { h.best = d; h.stuck = 0; }
            else if (++h.stuck >= 3) {   // 6 s without getting closer: a wall, a hole, a closed room, pipes
                int[] spot = spot(b.field_70170_p, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
                AIGuide.LOG.info("errand for {}: stuck at {}, {} blocks from {}: {}", h.player, at(b), (int) d, at(x, y, z),
                        spot == null ? "no place to stand there" : "steps over to " + spot[0] + " " + spot[1] + " " + spot[2]);
                if (spot != null) {
                    puff(b);
                    b.func_70634_a(spot[0] + 0.5, spot[1], spot[2] + 0.5);
                    puff(b);
                }
                h.stuck = 0;
                h.best = Double.MAX_VALUE;
            }
        }
        return false;
    }

    static String at(GuideBody b) {
        return at(b.field_70165_t, b.field_70163_u, b.field_70161_v);
    }

    static String at(double x, double y, double z) {
        return (int) Math.floor(x) + " " + (int) Math.floor(y) + " " + (int) Math.floor(z);
    }

    static boolean far(GuideBody b, double x, double y, double z, double d) {
        return b.func_70092_e(x, y, z) > d * d;
    }

    /** a place to stand near a point: two blocks of air over something solid */
    static int[] spot(World w, int x, int y, int z) {
        for (int r = 1; r <= 3; r++)
            for (int dy = 1; dy >= -2; dy--)
                for (int dx = -r; dx <= r; dx++)
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                        int a = x + dx, b = y + dy, c = z + dz;
                        if (b < 1 || b > 254) continue;
                        if (w.func_147437_c(a, b, c) && w.func_147437_c(a, b + 1, c) && w.func_147439_a(a, b - 1, c).func_149688_o().func_76220_a())
                            return new int[] { a, b, c };
                    }
        return null;
    }

    void appear(Helper h, EntityPlayerMP p) {
        if (h.body != null && !h.body.field_70128_L) { puff(h.body); h.body.func_70106_y(); }
        GuideBody.register();
        World w = p.field_70170_p;
        int px = (int) Math.floor(p.field_70165_t), py = (int) Math.floor(p.field_70163_u), pz = (int) Math.floor(p.field_70161_v);
        int[] at = spot(w, px, py, pz);
        if (at == null) at = new int[] { px, py, pz };
        GuideBody b = new GuideBody(w, mod.config.assistantName);
        b.func_70012_b(at[0] + 0.5, at[1], at[2] + 0.5, p.field_70177_z + 180, 0);
        w.func_72838_d(b);
        h.body = b;
        h.best = Double.MAX_VALUE;
        h.stuck = 0;
        puff(b);
        w.func_72956_a(b, "mob.villager.yes", 0.6f, 1.0f);
    }

    void vanish(Helper h) {
        if (h.body == null) return;
        puff(h.body);
        h.body.func_70106_y();
        h.body = null;
    }

    static void puff(GuideBody b) {
        ((WorldServer) b.field_70170_p).func_147487_a("cloud", b.field_70165_t, b.field_70163_u + 1, b.field_70161_v, 12, 0.3, 0.6, 0.3, 0.02);
    }

    /** the name over its head says what it carries */
    void carrying(Helper h, Errand e) {
        if (h.body == null) return;
        List<ItemStack> m = merged(e.bag);
        String n = mod.config.assistantName;
        if (!m.isEmpty()) n += " · " + m.get(0).field_77994_a + " " + mod.lang.name(m.get(0)) + (m.size() > 1 ? " +" + (m.size() - 1) : "");
        h.body.func_94058_c(n.length() > 60 ? n.substring(0, 60) : n);
    }

    /** gives up: what it carries goes back where it came from, or to the player, or on the ground */
    void abort(Helper h, Errand e, String why) {
        AIGuide.LOG.info("errand for {} given up: {}", h.player, why);
        stash(h, e);
        mod.say(h.player, mod.msg("gaveup") + " " + why);
    }

    void stash(Helper h, Errand e) {
        if (e.bag.isEmpty()) return;
        World w = h.body != null ? h.body.field_70170_p : null;
        for (ItemStack s : merged(e.bag)) {
            if (w != null)
                for (Stop src : e.sources) {
                    TileEntity t = w.func_147438_o(src.x, src.y, src.z);
                    if (t instanceof IInventory) insert((IInventory) t, s);
                    if (s.field_77994_a <= 0) break;
                }
            if (s.field_77994_a <= 0) continue;
            EntityPlayerMP p = AIGuide.player(h.player);
            if (p != null) Actions.give(p, s);
            else if (h.body != null) h.body.func_70099_a(s, 0.5f);
        }
        e.bag.clear();
    }

    static void insert(IInventory inv, ItemStack s) {
        int max = Math.min(inv.func_70297_j_(), s.func_77976_d());
        for (int pass = 0; pass < 2 && s.field_77994_a > 0; pass++)
            for (int i = 0; i < inv.func_70302_i_() && s.field_77994_a > 0; i++) {
                ItemStack c = inv.func_70301_a(i);
                if (pass == 0 && c != null && c.func_77969_a(s) && ItemStack.func_77970_a(c, s) && c.field_77994_a < max) {
                    int n = Math.min(s.field_77994_a, max - c.field_77994_a);
                    c.field_77994_a += n;
                    s.field_77994_a -= n;
                } else if (pass == 1 && c == null && inv.func_94041_b(i, s)) {
                    ItemStack x = s.func_77946_l();
                    x.field_77994_a = Math.min(s.field_77994_a, max);
                    inv.func_70299_a(i, x);
                    s.field_77994_a -= x.field_77994_a;
                }
            }
        inv.func_70296_d();
    }

    /** on the server's stop: everything carried goes back, the bodies go */
    void stopAll() {
        for (Helper h : helpers.values()) {
            for (Errand e : h.queue) stash(h, e);
            h.queue.clear();
            vanish(h);
        }
        helpers.clear();
    }

    static void put(List<ItemStack> bag, ItemStack s) {
        if (s != null && s.field_77994_a > 0) bag.add(s);
    }

    /** the bag with equal stacks together, for telling and giving */
    static List<ItemStack> merged(List<ItemStack> bag) {
        Map<String, ItemStack> m = new LinkedHashMap<String, ItemStack>();
        List<ItemStack> odd = new ArrayList<ItemStack>();
        for (ItemStack s : bag) {
            if (s.func_77942_o()) { odd.add(s); continue; }   // with NBT: as it is
            ItemStack old = m.get(RecipeIndex.key(s));
            if (old == null) m.put(RecipeIndex.key(s), s.func_77946_l());
            else old.field_77994_a += s.field_77994_a;
        }
        List<ItemStack> out = new ArrayList<ItemStack>(m.values());
        out.addAll(odd);
        return out;
    }
}
