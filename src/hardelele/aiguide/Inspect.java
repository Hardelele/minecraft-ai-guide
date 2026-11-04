package hardelele.aiguide;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

/**
 * A close look at the blocks with tile entities around a point: pipes, ducts, conduits, machines, chests.
 * For each: the neighbours it touches, what it holds and its saved data (filters, modes, modules, the
 * items travelling in a pipe), read from what the block itself writes to the save, so it works for any mod.
 * Item compounds in the data are shown by their names. Call on the server thread.
 */
class Inspect {
    static final String[] SIDE = { "down", "up", "north", "south", "west", "east" };
    static final int PER_BLOCK = 700;

    final AIGuide mod;

    Inspect(AIGuide mod) {
        this.mod = mod;
    }

    String around(EntityPlayerMP p, int[] at, int radius, int max) {
        final World w = p.field_70170_p;
        final int cx = at[0], cy = at[1], cz = at[2];
        List<TileEntity> tiles = new ArrayList<TileEntity>();
        for (Object o : new ArrayList<Object>(w.field_147482_g)) {
            TileEntity t = (TileEntity) o;
            if (t.func_145837_r() || Math.abs(t.field_145851_c - cx) > radius || Math.abs(t.field_145848_d - cy) > radius
                    || Math.abs(t.field_145849_e - cz) > radius) continue;
            tiles.add(t);
        }
        Collections.sort(tiles, new Comparator<TileEntity>() {
            @Override public int compare(TileEntity a, TileEntity b) { return d(a) - d(b); }
            int d(TileEntity t) {
                int x = t.field_145851_c - cx, y = t.field_145848_d - cy, z = t.field_145849_e - cz;
                return x * x + y * y + z * z;
            }
        });
        StringBuilder b = new StringBuilder();
        b.append(tiles.size()).append(" blocks with tile entities within ").append(radius).append(" of ").append(cx).append(' ').append(cy)
                .append(' ').append(cz).append(", nearest first. Sides in the data are often numbers: 0 down, 1 up, 2 north (-z), "
                + "3 south (+z), 4 west (-x), 5 east (+x); meta often stores a facing. Data is what the block saves: read the key names.\n");
        int shown = 0;
        for (TileEntity t : tiles) {
            String one = describe(w, t);
            if (b.length() + one.length() > max) {
                b.append("... ").append(tiles.size() - shown).append(" more (ask with a smaller radius or another point)\n");
                break;
            }
            b.append(one);
            shown++;
        }
        return b.toString();
    }

    String describe(World w, TileEntity t) {
        int x = t.field_145851_c, y = t.field_145848_d, z = t.field_145849_e;
        StringBuilder b = new StringBuilder("- ").append(name(w, x, y, z)).append(" at ").append(x).append(' ').append(y).append(' ').append(z)
                .append(" (meta ").append(w.func_72805_g(x, y, z)).append(")\n");
        StringBuilder n = new StringBuilder();
        for (int s = 0; s < 6; s++) {
            ForgeDirection d = ForgeDirection.getOrientation(s);
            int nx = x + d.offsetX, ny = y + d.offsetY, nz = z + d.offsetZ;
            TileEntity o = w.func_147438_o(nx, ny, nz);
            if (o == null || o.func_145837_r()) continue;
            Boolean c = connected(t, d);
            if (c != null && !c) continue;
            n.append(n.length() > 0 ? ", " : "").append(SIDE[s]).append(": ").append(name(w, nx, ny, nz));
        }
        if (n.length() > 0) b.append("  touches ").append(n).append('\n');
        if (t instanceof IInventory) {
            IInventory inv = (IInventory) t;
            StringBuilder h = new StringBuilder();
            int used = 0;
            for (int i = 0; i < inv.func_70302_i_(); i++) {
                ItemStack s = inv.func_70301_a(i);
                if (s == null || s.func_77973_b() == null) continue;
                if (++used <= 8) h.append(h.length() > 0 ? ", " : "").append(stack(s));
            }
            b.append("  holds ").append(used == 0 ? "nothing" : h + (used > 8 ? ", +" + (used - 8) + " more stacks" : ""))
                    .append(" (").append(inv.func_70302_i_()).append(" slots)\n");
        }
        try {
            NBTTagCompound tag = new NBTTagCompound();
            t.func_145841_b(tag);
            StringBuilder d = new StringBuilder();
            nbt(d, tag, 0);
            String s = d.toString();
            if (s.length() > PER_BLOCK) s = s.substring(0, PER_BLOCK) + "...";
            if (s.length() > 2) b.append("  data ").append(s).append('\n');
        } catch (Throwable e) {
            b.append("  data not readable: ").append(e).append('\n');
        }
        return b.toString();
    }

    /** a BuildCraft-style pipe says which sides it is connected to; null when the block does not tell */
    static Boolean connected(TileEntity t, ForgeDirection d) {
        try {
            Method m = t.getClass().getMethod("isPipeConnected", ForgeDirection.class);
            return (Boolean) m.invoke(t, d);
        } catch (Throwable e) {
            return null;
        }
    }

    /** the block's name the way the player sees it: the pick-block stack names pipes and conduits by their kind */
    String name(World w, int x, int y, int z) {
        Block bl = w.func_147439_a(x, y, z);
        try {
            Method m = Block.class.getMethod("getPickBlock", net.minecraft.util.MovingObjectPosition.class, World.class, int.class, int.class, int.class);
            ItemStack s = (ItemStack) m.invoke(bl, new net.minecraft.util.MovingObjectPosition(x, y, z, 1,
                    net.minecraft.util.Vec3.func_72443_a(x + 0.5, y + 1, z + 0.5)), w, x, y, z);
            if (s != null && s.func_77973_b() != null) return mod.lang.name(s);
        } catch (Throwable e) {
            // some blocks want a real hit; fall back to the block's item
        }
        Item i = Item.func_150898_a(bl);
        return i == null ? bl.func_149739_a() : mod.lang.name(new ItemStack(i, 1, i.func_77614_k() ? bl.func_149692_a(w.func_72805_g(x, y, z)) : 0));
    }

    String stack(ItemStack s) {
        return mod.lang.name(s) + (s.field_77994_a > 1 ? " x" + s.field_77994_a : "");
    }

    /** the saved data in short: item compounds by name, coordinates and long arrays left out */
    void nbt(StringBuilder b, NBTBase tag, int depth) {
        if (b.length() > PER_BLOCK) return;
        if (tag instanceof NBTTagCompound) {
            NBTTagCompound c = (NBTTagCompound) tag;
            if (c.func_74764_b("id") && c.func_74764_b("Count") && c.func_150297_b("id", 99)) {
                ItemStack s = ItemStack.func_77949_a(c);
                b.append(s == null || s.func_77973_b() == null ? "unknown item" : stack(s));
                return;
            }
            if (depth > 5) { b.append("{...}"); return; }
            b.append('{');
            boolean first = true;
            for (Object k : new ArrayList<Object>(c.func_150296_c())) {
                String key = (String) k;
                NBTBase v = c.func_74781_a(key);
                if (depth == 0 && (key.equals("x") || key.equals("y") || key.equals("z") || key.equals("ForgeData"))) continue;
                if (key.matches("[xyz]|pos[XYZ]?") && v.func_74732_a() >= 5 && v.func_74732_a() <= 6) continue;   // a traveller's exact position
                if (key.matches(".*\\[\\d+\\]") && v.toString().matches("0(\\.0)?[bsLfd]?")) continue;   // zero entries of a saved array
                if (v instanceof NBTTagList && ((NBTTagList) v).func_74745_c() == 0) continue;
                if (!first) b.append(", ");
                first = false;
                b.append(key).append('=');
                nbt(b, v, depth + 1);
            }
            b.append('}');
        } else if (tag instanceof NBTTagList) {
            NBTTagList l = (NBTTagList) tag;
            if (l.func_150303_d() != 10) {
                String s = l.toString();
                b.append(s.length() > 80 ? s.substring(0, 80) + "...]" : s);
                return;
            }
            b.append('[');
            for (int i = 0; i < l.func_74745_c(); i++) {
                if (b.length() > PER_BLOCK) break;
                if (i > 0) b.append(", ");
                nbt(b, l.func_150305_b(i), depth + 1);
            }
            b.append(']');
        } else {
            String s = tag.toString();
            b.append(s.length() > 80 ? s.substring(0, 80) + "..." : s);
        }
    }
}
