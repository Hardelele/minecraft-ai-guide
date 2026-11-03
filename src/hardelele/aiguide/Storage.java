package hardelele.aiguide;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * What lies where around a player: every inventory (chests, barrels, drawers, machines) and
 * every AE2 network within a radius, read on the server thread.
 */
class Storage {
    /** one place that holds things */
    static final class Place {
        String name;
        int x, y, z;
        IInventory inv;   // null for an AE2 network (read only)
        final Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        final Map<String, ItemStack> stacks = new LinkedHashMap<String, ItemStack>();
        double dist;
    }

    final AIGuide mod;

    Storage(AIGuide mod) {
        this.mod = mod;
    }

    /** the places around the player, nearest first (call on the server thread) */
    List<Place> around(EntityPlayerMP p, int radius) {
        World w = p.field_70170_p;
        List<Place> out = new ArrayList<Place>();
        Map<Object, Boolean> grids = new IdentityHashMap<Object, Boolean>();
        for (Object o : new ArrayList<Object>(w.field_147482_g)) {
            TileEntity t = (TileEntity) o;
            double dx = t.field_145851_c + 0.5 - p.field_70165_t, dy = t.field_145848_d + 0.5 - p.field_70163_u, dz = t.field_145849_e + 0.5 - p.field_70161_v;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d > radius || t.func_145837_r()) continue;
            Place pl = null;
            Object grid = ae2Grid(t);
            if (grid != null) {
                if (grids.put(grid, Boolean.TRUE) != null) continue;
                pl = place(w, t, d);
                pl.name = "AE2 network (" + pl.name + ")";
                readAe2(grid, pl);
            } else if (t instanceof IInventory) {
                pl = place(w, t, d);
                pl.inv = (IInventory) t;
                IInventory inv = (IInventory) t;
                for (int i = 0; i < inv.func_70302_i_(); i++) add(pl, inv.func_70301_a(i), -1);
            }
            if (pl != null && !pl.counts.isEmpty()) out.add(pl);
        }
        Collections.sort(out, new Comparator<Place>() {
            @Override public int compare(Place a, Place b) { return Double.compare(a.dist, b.dist); }
        });
        return out;
    }

    Place place(World w, TileEntity t, double d) {
        Place pl = new Place();
        pl.x = t.field_145851_c;
        pl.y = t.field_145848_d;
        pl.z = t.field_145849_e;
        pl.dist = d;
        Block b = w.func_147439_a(pl.x, pl.y, pl.z);
        Item i = Item.func_150898_a(b);
        int meta = w.func_72805_g(pl.x, pl.y, pl.z);
        pl.name = i == null ? b.func_149739_a() : mod.lang.name(new ItemStack(i, 1, i.func_77614_k() ? b.func_149692_a(meta) : 0));
        return pl;
    }

    void add(Place pl, ItemStack s, long count) {
        if (s == null || s.func_77973_b() == null) return;
        String k = RecipeIndex.key(s);
        long n = count >= 0 ? count : s.field_77994_a;
        Integer old = pl.counts.get(k);
        pl.counts.put(k, (int) Math.min(Integer.MAX_VALUE, (old == null ? 0 : old) + n));
        if (!pl.stacks.containsKey(k)) pl.stacks.put(k, s);
    }

    // AE2 through its API, by reflection: host.getGridNode(UNKNOWN).getGrid().getCache(IStorageGrid).getItemInventory().getStorageList()
    static Object ae2Grid(TileEntity t) {
        try {
            Class<?> host = Class.forName("appeng.api.networking.IGridHost");
            if (!host.isInstance(t)) return null;
            Class<?> dir = Class.forName("net.minecraftforge.common.util.ForgeDirection");
            Object unknown = dir.getField("UNKNOWN").get(null);
            Object node = host.getMethod("getGridNode", dir).invoke(t, unknown);
            if (node == null) return null;
            return node.getClass().getMethod("getGrid").invoke(node);
        } catch (Throwable e) {
            return null;
        }
    }

    void readAe2(Object grid, Place pl) {
        try {
            Class<?> sg = Class.forName("appeng.api.networking.storage.IStorageGrid");
            Method getCache = Class.forName("appeng.api.networking.IGrid").getMethod("getCache", Class.class);
            Object storage = getCache.invoke(grid, sg);
            Object monitor = sg.getMethod("getItemInventory").invoke(storage);
            Object list = Class.forName("appeng.api.storage.IMEMonitor").getMethod("getStorageList").invoke(monitor);
            for (Object ae : (Iterable<?>) list) {
                ItemStack s = (ItemStack) ae.getClass().getMethod("getItemStack").invoke(ae);
                long n = (Long) ae.getClass().getMethod("getStackSize").invoke(ae);
                add(pl, s, n);
            }
        } catch (Throwable e) {
            AIGuide.LOG.debug("AE2 network not read: " + e);
        }
    }

    /** where an item is around the player, as text */
    String where(EntityPlayerMP p, ItemStack want, int radius) {
        String k = RecipeIndex.key(want);
        StringBuilder b = new StringBuilder();
        int total = 0;
        for (Place pl : around(p, radius)) {
            Integer n = pl.counts.get(k);
            if (n == null) continue;
            total += n;
            b.append("- ").append(n).append(" in ").append(pl.name).append(" at ").append(pl.x).append(' ').append(pl.y).append(' ')
                    .append(pl.z).append(" (").append((int) pl.dist).append(" blocks away)\n");
        }
        return total == 0 ? "" : b.toString();
    }

    /** how many of each item the player has on them and around them */
    Map<String, Integer> totals(EntityPlayerMP p, int radius, boolean storage) {
        Map<String, Integer> t = new LinkedHashMap<String, Integer>();
        for (ItemStack s : p.field_71071_by.field_70462_a) addTo(t, s, s == null ? 0 : s.field_77994_a);
        if (storage)
            for (Place pl : around(p, radius))
                for (Map.Entry<String, Integer> e : pl.counts.entrySet()) {
                    Integer old = t.get(e.getKey());
                    t.put(e.getKey(), (old == null ? 0 : old) + e.getValue());
                }
        return t;
    }

    static void addTo(Map<String, Integer> t, ItemStack s, int n) {
        if (s == null || s.func_77973_b() == null) return;
        String k = RecipeIndex.key(s);
        Integer old = t.get(k);
        t.put(k, (old == null ? 0 : old) + n);
    }
}
