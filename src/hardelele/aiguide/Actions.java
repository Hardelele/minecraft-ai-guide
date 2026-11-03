package hardelele.aiguide;

import cpw.mods.fml.common.FMLCommonHandler;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fluids.FluidContainerRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidContainerItem;

/**
 * The assistant's hands, only for what the player could do themselves: bring things from the
 * inventories near them, craft at a crafting table from what they carry, point at a block.
 * Everything runs on the server thread.
 */
class Actions {
    static final Container DUMMY = new Container() {
        @Override public boolean func_75145_c(net.minecraft.entity.player.EntityPlayer p) { return false; }
        @Override public void func_75130_a(net.minecraft.inventory.IInventory i) { }
    };

    static final class Mark {
        WorldServer world;
        double x, y, z;
        long until;
    }

    final AIGuide mod;
    final List<Mark> marks = new ArrayList<Mark>();

    Actions(AIGuide mod) {
        this.mod = mod;
    }

    // ---- fetch ---------------------------------------------------------------------------

    String fetch(EntityPlayerMP p, ItemStack want, int count) {
        String k = RecipeIndex.key(want);
        int got = 0;
        List<String> from = new ArrayList<String>();
        for (Storage.Place pl : mod.storage.around(p, mod.config.fetchRadius)) {
            if (pl.inv == null || !pl.counts.containsKey(k)) continue;
            int before = got;
            for (int i = 0; i < pl.inv.func_70302_i_() && got < count; i++) {
                ItemStack s = pl.inv.func_70301_a(i);
                if (s == null || !RecipeIndex.key(s).equals(k)) continue;
                ItemStack taken = pl.inv.func_70298_a(i, Math.min(count - got, s.field_77994_a));
                if (taken == null) continue;
                int n = taken.field_77994_a;
                p.field_71071_by.func_70441_a(taken);
                if (taken.field_77994_a > 0) {   // the player's inventory is full: put the rest back
                    ItemStack back = pl.inv.func_70301_a(i);
                    if (back == null) pl.inv.func_70299_a(i, taken);
                    else back.field_77994_a += taken.field_77994_a;
                    got += n - taken.field_77994_a;
                    pl.inv.func_70296_d();
                    sync(p);
                    return result(want, got, count, from) + " The player's inventory is full.";
                }
                got += n;
            }
            pl.inv.func_70296_d();
            if (got > before) from.add((got - before) + " from " + pl.name + " at " + pl.x + " " + pl.y + " " + pl.z);
            if (got >= count) break;
        }
        sync(p);
        return result(want, got, count, from);
    }

    String result(ItemStack want, int got, int count, List<String> from) {
        String n = mod.lang.name(want);
        if (got == 0) return "No " + n + " in the inventories within " + mod.config.fetchRadius + " blocks (AE2 networks are not taken from).";
        return "Moved " + got + " of " + count + " " + n + " into the player's inventory: " + String.join("; ", from) + ".";
    }

    static void sync(EntityPlayerMP p) {
        p.field_71070_bA.func_75142_b();
    }

    // ---- craft ---------------------------------------------------------------------------

    String craft(EntityPlayerMP p, ItemStack want, int count) {
        String k = RecipeIndex.key(want);
        List<RecipeIndex.Recipe> recipes = new ArrayList<RecipeIndex.Recipe>();
        for (RecipeIndex.Recipe r : mod.recipes.producing(want)) {
            boolean refill = false;   // recharging or repairing the thing itself is not making it
            for (RecipeIndex.Ingredient g : r.in) for (ItemStack o : g.options) if (RecipeIndex.key(o).equals(k)) refill = true;
            if (r.machine.equals("crafting") && !refill) recipes.add(r);
        }
        if (recipes.isEmpty()) return mod.lang.name(want) + " is not made at a crafting table: use how_to_make for the machine.";
        int made = 0, times = 0;
        String why = "";
        while (made < count && times < 64) {
            boolean ok = false;
            for (RecipeIndex.Recipe r : recipes) {
                int[] slots = new int[9];
                InventoryCrafting g = grid(p, r, slots);
                if (g == null) { why = "the player does not carry everything for it"; continue; }
                ItemStack out = CraftingManager.func_77594_a().func_82787_a(g, p.field_70170_p);
                if (out == null || !RecipeIndex.key(out).equals(k)) { why = "the grid did not give it"; continue; }
                FMLCommonHandler.instance().firePlayerCraftingEvent(p, out, g);
                for (int i = 0; i < 9; i++) {
                    if (slots[i] < 0) continue;
                    ItemStack s = p.field_71071_by.field_70462_a[slots[i]];
                    ItemStack left = containerItem(s);
                    s.field_77994_a--;
                    if (s.field_77994_a <= 0) p.field_71071_by.field_70462_a[slots[i]] = null;
                    if (left != null) give(p, left);
                }
                made += out.field_77994_a;
                give(p, out);
                ok = true;
                break;
            }
            times++;
            if (!ok) break;
        }
        sync(p);
        String n = mod.lang.name(want);
        if (made == 0) return "Could not craft " + n + ": " + why + ". Use plan_crafting to see what is missing.";
        return "Crafted " + made + " " + n + (made < count ? " (wanted " + count + ": " + why + ")" : "") + ".";
    }

    /** the recipe laid out from the player's inventory, or null if they lack something; slots[] gets the inventory slot of each cell */
    InventoryCrafting grid(EntityPlayerMP p, RecipeIndex.Recipe r, int[] slots) {
        ItemStack[] inv = p.field_71071_by.field_70462_a;
        int[] left = new int[inv.length];
        for (int i = 0; i < inv.length; i++) left[i] = inv[i] == null ? 0 : inv[i].field_77994_a;
        InventoryCrafting g = new InventoryCrafting(DUMMY, 3, 3);
        java.util.Arrays.fill(slots, -1);
        List<Integer> cells = new ArrayList<Integer>();   // grid cell -> ingredient index
        List<Integer> where = new ArrayList<Integer>();
        if (r.grid != null) {
            for (int y = 0; y < r.grid.length; y++)
                for (int x = 0; x < r.grid[y].length(); x++) {
                    char c = r.grid[y].charAt(x);
                    if (c != '.') { cells.add(c - 'A'); where.add(y * 3 + x); }
                }
        } else {
            int at = 0;
            for (int i = 0; i < r.in.size(); i++)
                for (int n = 0; n < r.in.get(i).amount; n++) { cells.add(i); where.add(at++); }
            if (at > 9) return null;
        }
        for (int c = 0; c < cells.size(); c++) {
            RecipeIndex.Ingredient ing = r.in.get(cells.get(c));
            int found = -1;
            for (int i = 0; i < inv.length && found < 0; i++)
                if (left[i] > 0 && fits(inv[i], ing)) found = i;
            if (found < 0) return null;
            left[found]--;
            ItemStack one = inv[found].func_77946_l();
            one.field_77994_a = 1;
            g.func_70299_a(where.get(c), one);
            slots[where.get(c)] = found;
        }
        return g;
    }

    static boolean fits(ItemStack s, RecipeIndex.Ingredient g) {
        if (s == null) return false;
        for (ItemStack o : g.options)
            if (o.func_77973_b() == s.func_77973_b() && (o.func_77960_j() == RecipeIndex.WILD || o.func_77960_j() == s.func_77960_j())) return true;
        if (g.fluid != null) {   // any container with the fluid (the crafting table decides if it takes it)
            FluidStack f = s.func_77973_b() instanceof IFluidContainerItem ? ((IFluidContainerItem) s.func_77973_b()).getFluid(s)
                    : FluidContainerRegistry.getFluidForFilledItem(s);
            return f != null && f.getFluid() == g.fluid.getFluid() && f.amount >= g.fluid.amount;
        }
        return false;
    }

    static Method hasContainer, getContainer;

    /** what stays in the grid after crafting (an empty bucket...), by Forge's Item methods */
    static ItemStack containerItem(ItemStack s) {
        try {
            if (hasContainer == null) {
                hasContainer = net.minecraft.item.Item.class.getMethod("hasContainerItem", ItemStack.class);
                getContainer = net.minecraft.item.Item.class.getMethod("getContainerItem", ItemStack.class);
            }
            if (!(Boolean) hasContainer.invoke(s.func_77973_b(), s)) return null;
            ItemStack c = (ItemStack) getContainer.invoke(s.func_77973_b(), s);
            return c != null && c.func_77984_f() && c.func_77960_j() > c.func_77958_k() ? null : c;
        } catch (Exception e) {
            return null;
        }
    }

    static void give(EntityPlayerMP p, ItemStack s) {
        if (!p.field_71071_by.func_70441_a(s) || s.field_77994_a > 0) p.func_71019_a(s, false);
    }

    // ---- point at a place -------------------------------------------------------------------

    String mark(EntityPlayerMP p, int x, int y, int z, int seconds) {
        Mark m = new Mark();
        m.world = (WorldServer) p.field_70170_p;
        m.x = x + 0.5;
        m.y = y;
        m.z = z + 0.5;
        m.until = System.currentTimeMillis() + Math.max(5, Math.min(120, seconds)) * 1000L;
        synchronized (marks) {
            marks.add(m);
        }
        return "Marked " + x + " " + y + " " + z + " with a column of sparks for " + seconds + " s ("
                + (int) Math.sqrt(p.func_70092_e(m.x, m.y, m.z)) + " blocks from the player).";
    }

    /** every half second from the server tick: sparks over the marked blocks */
    void tick() {
        long now = System.currentTimeMillis();
        synchronized (marks) {
            for (Iterator<Mark> it = marks.iterator(); it.hasNext(); ) {
                Mark m = it.next();
                if (now > m.until) { it.remove(); continue; }
                for (int h = 1; h <= 6; h++) m.world.func_147487_a("happyVillager", m.x, m.y + h * 0.7, m.z, 3, 0.15, 0.2, 0.15, 0.0);
            }
        }
    }
}
