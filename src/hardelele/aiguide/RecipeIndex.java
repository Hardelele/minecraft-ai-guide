package hardelele.aiguide;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.item.crafting.IRecipe;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.oredict.OreDictionary;

/**
 * Every recipe the server knows, as plain data: the crafting table, the furnace, and the
 * machines listed in the config ("recipe sources"). Machine recipes are read by reflection
 * without knowing the mods: a source names a static field or method (and further members,
 * "Class#instance#recipes"); its value is a map (input -> output), an array or a collection
 * of recipe objects, whose getters and fields are sorted into inputs and outputs by their names.
 */
class RecipeIndex {
    static final int WILD = OreDictionary.WILDCARD_VALUE;

    static final class Ingredient {
        final List<ItemStack> options = new ArrayList<ItemStack>();
        String ore;          // the ore dictionary name the options came from
        FluidStack fluid;
        int amount = 1;
    }

    static final class Output {
        ItemStack stack;
        FluidStack fluid;
        String note;   // chance and the like
    }

    static final class Recipe {
        String machine;
        String[] grid;   // shaped crafting: rows of letters, a letter per ingredient index
        final List<Ingredient> in = new ArrayList<Ingredient>();
        final List<Output> out = new ArrayList<Output>();
        final List<String> notes = new ArrayList<String>();
    }

    final List<Recipe> all = new ArrayList<Recipe>();
    final Map<String, List<Recipe>> byOutput = new HashMap<String, List<Recipe>>();
    final Map<String, List<Recipe>> byInput = new HashMap<String, List<Recipe>>();
    /** every stack seen in recipes and the ore dictionary, by key */
    final Map<String, ItemStack> stacks = new LinkedHashMap<String, ItemStack>();
    final Map<Object, String> oreLists = new IdentityHashMap<Object, String>();
    final Lang lang;

    RecipeIndex(Lang lang) {
        this.lang = lang;
    }

    static String key(ItemStack s) {
        return Item.field_150901_e.func_148750_c(s.func_77973_b()) + "@" + s.func_77960_j();
    }

    static String wildKey(ItemStack s) {
        return Item.field_150901_e.func_148750_c(s.func_77973_b()) + "@" + WILD;
    }

    void build(List<String> sources) {
        for (String n : OreDictionary.getOreNames()) {
            List<ItemStack> l = OreDictionary.getOres(n);
            oreLists.put(l, n);
            for (ItemStack s : l) see(s);
        }
        int crafting = 0;
        for (Object o : CraftingManager.func_77594_a().func_77592_b()) {
            Recipe r = crafting((IRecipe) o, "crafting");
            if (r != null) { add(r); crafting++; }
        }
        int furnace = 0;
        for (Object o : FurnaceRecipes.func_77602_a().func_77599_b().entrySet()) {
            Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
            Recipe r = new Recipe();
            r.machine = "furnace";
            collect(e.getKey(), true, r, 0);
            collect(e.getValue(), false, r, 0);
            add(r);
            furnace++;
        }
        AIGuide.LOG.info("recipes: {} crafting, {} furnace", crafting, furnace);
        for (String s : sources) source(s);
        for (Object o : Item.field_150901_e) {
            Item i = (Item) o;
            ItemStack s = new ItemStack(i, 1, 0);
            if (!stacks.containsKey(key(s)) && !i.func_77614_k()) see(s);
        }
        AIGuide.LOG.info("{} recipes, {} items known", all.size(), stacks.size());
    }

    // "Class#member#member=name key|label key|fluid:in"
    void source(String spec) {
        String machine = spec;
        try {
            String[] p = spec.split("=", 2);
            String[] chain = p[0].trim().split("#");
            String[] m = p.length > 1 ? p[1].split("\\|") : new String[] { chain[0] };
            machine = lang.key(m[0].trim()) + (m.length > 1 && !m[1].startsWith("fluid:") ? " (" + lang.key(m[1].trim()) + ")" : "");
            Boolean fluidIn = null;
            for (String x : m) if (x.trim().startsWith("fluid:")) fluidIn = x.trim().endsWith("in");
            Object v = Class.forName(chain[0].trim());
            for (int i = 1; i < chain.length; i++) v = member(v, chain[i].trim());
            if (!(v instanceof Map || v instanceof Collection || (v != null && v.getClass().isArray()))) {
                Method g = v.getClass().getMethod("getRecipes");
                v = g.invoke(v);
            }
            int before = all.size();
            if (v instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                    Recipe r = new Recipe();
                    r.machine = machine;
                    collect(e.getKey(), true, r, 0);
                    collect(e.getValue(), false, r, 0);
                    add(r);
                }
            } else {
                Collection<?> c = v instanceof Collection ? (Collection<?>) v : arrayList(v);
                for (Object o : c) {
                    Recipe r = o instanceof IRecipe ? crafting((IRecipe) o, machine) : null;
                    if (r == null) {
                        r = new Recipe();
                        r.machine = machine;
                        describe(o, r, null, fluidIn, 0);
                    }
                    add(r);
                }
            }
            AIGuide.LOG.info("recipes: {} from {}", all.size() - before, machine);
        } catch (Throwable t) {
            AIGuide.LOG.info("recipe source skipped ({}): {}", spec, t.toString());
        }
    }

    static List<Object> arrayList(Object a) {
        List<Object> l = new ArrayList<Object>();
        int n = java.lang.reflect.Array.getLength(a);
        for (int i = 0; i < n; i++) l.add(java.lang.reflect.Array.get(a, i));
        return l;
    }

    /** a field or a no-argument method of a class (static) or an object */
    static Object member(Object on, String name) throws Exception {
        Class<?> c = on instanceof Class ? (Class<?>) on : on.getClass();
        Object target = on instanceof Class ? null : on;
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                // a method then
            }
            try {
                Method m = k.getDeclaredMethod(name);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (NoSuchMethodException e) {
                // the superclass then
            }
        }
        throw new NoSuchFieldException(c.getName() + "#" + name);
    }

    void add(Recipe r) {
        if (r.out.isEmpty() || r.in.isEmpty()) return;
        all.add(r);
        for (Output o : r.out) {
            if (o.stack != null) index(byOutput, key(o.stack), r);
            if (o.fluid != null) index(byOutput, fluidKey(o.fluid.getFluid()), r);
        }
        for (Ingredient g : r.in) {
            for (ItemStack s : g.options) index(byInput, key(s), r);
            if (g.fluid != null) index(byInput, fluidKey(g.fluid.getFluid()), r);
        }
    }

    static String fluidKey(net.minecraftforge.fluids.Fluid f) {
        return "fluid:" + f.getName();
    }

    List<Recipe> producing(String key) {
        List<Recipe> l = byOutput.get(key);
        return l == null ? new ArrayList<Recipe>() : l;
    }

    List<Recipe> using(String key) {
        List<Recipe> l = byInput.get(key);
        return l == null ? new ArrayList<Recipe>() : l;
    }

    static void index(Map<String, List<Recipe>> m, String k, Recipe r) {
        List<Recipe> l = m.get(k);
        if (l == null) m.put(k, l = new ArrayList<Recipe>());
        if (!l.contains(r)) l.add(r);
    }

    void see(ItemStack s) {
        if (s == null || s.func_77973_b() == null) return;
        // a new stack rather than setItemDamage on a copy: IC2's electric armor complains about that
        ItemStack c = s.func_77960_j() == WILD ? new ItemStack(s.func_77973_b(), 1, 0) : s.func_77946_l();
        c.field_77994_a = 1;
        String k = key(c);
        if (!stacks.containsKey(k)) stacks.put(k, c);
    }

    List<Recipe> producing(ItemStack s) {
        List<Recipe> l = byOutput.get(key(s));
        return l == null ? new ArrayList<Recipe>() : l;
    }

    List<Recipe> using(ItemStack s) {
        List<Recipe> out = new ArrayList<Recipe>();
        List<Recipe> a = byInput.get(key(s)), b = byInput.get(wildKey(s));
        if (a != null) out.addAll(a);
        if (b != null) for (Recipe r : b) if (!out.contains(r)) out.add(r);
        return out;
    }

    // ---- crafting recipes of any kind -------------------------------------------------

    Recipe crafting(IRecipe ir, String machine) {
        Object inner = firstOf(ir, "getWrappedRecipe", "getRecipe");   // a wrapper for NEI (Thermal Expansion's machines)
        if (inner instanceof IRecipe && inner != ir) return crafting((IRecipe) inner, machine);
        Recipe r = new Recipe();
        r.machine = machine;
        ItemStack result = ir.func_77571_b();
        if (result == null) return null;
        Output o = new Output();
        o.stack = result;
        see(result);
        r.out.add(o);
        Object[] cells = null;
        int w = 0, h = 0;
        try {
            Object in = firstOf(ir, "field_77574_d", "field_77579_b", "getInput", "input", "recipeItems");
            if (in instanceof Collection) cells = ((Collection<?>) in).toArray();
            else if (in instanceof Object[]) cells = (Object[]) in;
            Object ww = firstOf(ir, "field_77576_b", "width", "getWidth", "inputWidth");
            Object hh = firstOf(ir, "field_77577_c", "height", "getHeight", "inputHeight");
            if (ww instanceof Integer && hh instanceof Integer) { w = (Integer) ww; h = (Integer) hh; }
        } catch (Exception e) {
            return null;
        }
        if (cells == null) return null;
        cells = ic2Shape(ir, cells, w, h);
        // shaped: a cell per grid square
        boolean shaped = w > 0 && h > 0 && cells.length == w * h;
        List<String> letters = new ArrayList<String>();
        StringBuilder[] rows = shaped ? new StringBuilder[h] : null;
        for (int i = 0; shaped && i < h; i++) rows[i] = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            Ingredient g = ingredient(cells[i]);
            char c = ' ';
            if (g != null) {
                int at = indexOf(r.in, g);
                if (at < 0) { r.in.add(g); at = r.in.size() - 1; }
                else if (!shaped) r.in.get(at).amount++;
                c = (char) ('A' + at);
            }
            if (shaped) rows[i / w].append(c == ' ' ? '.' : c);
        }
        if (shaped) {
            r.grid = new String[h];
            for (int i = 0; i < h; i++) r.grid[i] = rows[i].toString();
            for (int i = 0; i < r.in.size(); i++) {   // count each ingredient over the grid
                int n = 0;
                for (String row : r.grid) for (char ch : row.toCharArray()) if (ch == 'A' + i) n++;
                r.in.get(i).amount = n;
            }
        }
        return r;
    }

    /**
     * IC2's AdvRecipe lists only the filled squares; its masks say where they are: a bit per square
     * of the 3x3 grid, the top left one highest, a mask per place the shape fits in. The biggest
     * mask is the shape in the top left corner.
     */
    static Object[] ic2Shape(IRecipe ir, Object[] cells, int w, int h) {
        Object m = firstOf(ir, "masks");
        if (!(m instanceof int[]) || w <= 0 || h <= 0 || cells.length == w * h) return cells;
        int mask = 0;
        for (int x : (int[]) m) mask = Math.max(mask, x);
        Object[] grid = new Object[w * h];
        int next = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if ((mask >> (8 - (y * 3 + x)) & 1) != 0 && next < cells.length) grid[y * w + x] = cells[next++];
        return next == cells.length ? grid : cells;
    }

    static int indexOf(List<Ingredient> l, Ingredient g) {
        for (int i = 0; i < l.size(); i++) {
            Ingredient o = l.get(i);
            if (o.ore != null ? o.ore.equals(g.ore) : g.ore == null && o.options.size() == g.options.size()
                    && !o.options.isEmpty() && key(o.options.get(0)).equals(key(g.options.get(0)))) return i;
        }
        return -1;
    }

    static Object firstOf(Object o, String... names) {
        for (String n : names)
            try {
                return member(o, n);
            } catch (Exception e) {
                // next name
            }
        return null;
    }

    /** one crafting cell: an item, an ore name, a list of choices, IC2 and AE2 inputs */
    Ingredient ingredient(Object o) {
        if (o == null) return null;
        Ingredient g = new Ingredient();
        if (o instanceof ItemStack) g.options.add((ItemStack) o);
        else if (o instanceof Item) g.options.add(new ItemStack((Item) o));
        else if (o instanceof Block) g.options.add(new ItemStack((Block) o));
        else if (o instanceof String) {
            g.ore = (String) o;
            g.options.addAll(OreDictionary.getOres((String) o));
        } else if (o instanceof Collection || o instanceof ItemStack[]) {
            Collection<?> c = o instanceof Collection ? (Collection<?>) o : arrayList(o);
            g.ore = oreLists.get(o);
            for (Object x : c) if (x instanceof ItemStack) g.options.add((ItemStack) x);
        } else {
            Object ore = firstOf(o, "input");   // IC2 RecipeInputOreDict keeps its name there
            if (ore instanceof String) g.ore = (String) ore;
            Object amount = firstOf(o, "getAmount", "getQty");
            if (amount instanceof Integer) g.amount = (Integer) amount;
            Object l = firstOf(o, "getInputs", "getItemStackSet");
            if (l instanceof Collection) for (Object x : (Collection<?>) l) { if (x instanceof ItemStack) g.options.add((ItemStack) x); }
            else if (l instanceof ItemStack[]) for (ItemStack x : (ItemStack[]) l) g.options.add(x);
            Object fluid = firstOf(o, "fluid");   // IC2 RecipeInputFluidContainer
            if (fluid instanceof net.minecraftforge.fluids.Fluid) {
                g.fluid = new FluidStack((net.minecraftforge.fluids.Fluid) fluid, g.amount);   // its amount is in mB
                g.amount = 1;
            }
        }
        if (g.options.isEmpty() && g.fluid == null) return null;
        for (ItemStack s : g.options) see(s);
        return g;
    }

    // ---- machine recipes: whatever the objects look like ---------------------------------

    void collect(Object v, boolean in, Recipe r, int depth) {
        if (v == null || depth > 3) return;
        if (v instanceof ItemStack || v instanceof Item || v instanceof Block) {
            ItemStack s = v instanceof ItemStack ? (ItemStack) v : v instanceof Item ? new ItemStack((Item) v) : new ItemStack((Block) v);
            if (s.func_77973_b() == null) return;
            see(s);
            if (in) {
                Ingredient g = new Ingredient();
                g.options.add(s);
                g.amount = Math.max(1, s.field_77994_a);
                r.in.add(g);
            } else {
                Output o = new Output();
                o.stack = s;
                r.out.add(o);
            }
        } else if (v instanceof FluidStack) {
            if (((FluidStack) v).getFluid() == null) return;
            if (in) {
                Ingredient g = new Ingredient();
                g.fluid = (FluidStack) v;
                r.in.add(g);
            } else {
                Output o = new Output();
                o.fluid = (FluidStack) v;
                r.out.add(o);
            }
        } else if (v instanceof Map.Entry) {   // Railcraft's rock crusher: stack -> chance
            Map.Entry<?, ?> e = (Map.Entry<?, ?>) v;
            int before = r.out.size();
            collect(e.getKey(), in, r, depth + 1);
            if (e.getValue() instanceof Number && r.out.size() > before)
                r.out.get(r.out.size() - 1).note = "chance " + Math.round(((Number) e.getValue()).floatValue() * 100) + "%";
        } else if (v instanceof Collection || v.getClass().isArray()) {
            if (in && !(v instanceof Object[] && !(v instanceof ItemStack[]))) {
                Ingredient g = ingredient(v);
                if (g != null) { r.in.add(g); return; }
            }
            for (Object o : v instanceof Collection ? (Collection<?>) v : arrayList(v)) collect(o, in, r, depth + 1);
        } else if (v.getClass().getName().startsWith("java.") || v instanceof Number || v instanceof String) {
            // nothing to show
        } else if (in && (firstOf(v, "getInputs") != null && firstOf(v, "getAmount") != null)) {
            Ingredient g = ingredient(v);   // an IC2 recipe input
            if (g != null) r.in.add(g);
        } else {
            describe(v, r, in, null, depth + 1);
        }
    }

    /** sorts an object's getters and fields into inputs, outputs and notes by their names */
    void describe(Object o, Recipe r, Boolean in, Boolean fluidIn, int depth) {
        if (o == null || depth > 3) return;
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        for (Method m : o.getClass().getMethods()) {
            String n = m.getName();
            if (m.getParameterTypes().length != 0 || Modifier.isStatic(m.getModifiers()) || m.getDeclaringClass() == Object.class
                    || !(n.startsWith("get") || n.startsWith("is")) || n.equals("getClass") || m.getReturnType() == void.class) continue;
            try {
                values.put(n.startsWith("get") ? n.substring(3) : n.substring(2), m.invoke(o));
            } catch (Throwable t) {
                // skip it
            }
        }
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass())
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || values.containsKey(cap(f.getName()))) continue;
                try {
                    f.setAccessible(true);
                    values.put(cap(f.getName()), f.get(o));
                } catch (Throwable t) {
                    // skip it
                }
            }
        for (Map.Entry<String, Object> e : values.entrySet()) {
            String n = e.getKey().toLowerCase();
            Object v = e.getValue();
            if (v instanceof Number || v instanceof Boolean) {
                if (n.contains("energy") || n.contains("chance") || n.contains("time") || n.contains("power") || n.contains("cost"))
                    r.notes.add(n + " " + v);
                continue;
            }
            Boolean role = n.contains("output") || n.contains("result") || n.contains("product") || n.equals("out") ? Boolean.FALSE
                    : n.contains("input") || n.contains("ingredient") || n.contains("additive") || n.equals("in") ? Boolean.TRUE
                    : n.contains("fluid") && fluidIn != null ? fluidIn
                    : in;
            if (role == null) continue;
            collect(v, role, r, depth);
        }
    }

    static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
