package hardelele.aiguide;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.item.ItemStack;

/**
 * "Can I make it?": unfolds an item into its recipes down to what the player has (on them and in
 * the storage around them) or has to gather, and lists the steps in the order to do them.
 * Of the ways to make each thing it picks the one whose inputs are most at hand.
 */
class Planner {
    static final int MAX_DEPTH = 8, MAX_STEPS = 30;
    final AIGuide mod;
    final Map<String, Integer> avail;
    final Map<String, Integer> used = new LinkedHashMap<String, Integer>();
    final Map<String, Integer> missing = new LinkedHashMap<String, Integer>();
    final Map<String, Integer> fluids = new LinkedHashMap<String, Integer>();
    final List<String> steps = new ArrayList<String>();
    final Set<String> visiting = new HashSet<String>();

    Planner(AIGuide mod, Map<String, Integer> avail) {
        this.mod = mod;
        this.avail = new LinkedHashMap<String, Integer>(avail);
    }

    String plan(ItemStack want, int count) {
        need(want, count, 0);
        StringBuilder b = new StringBuilder("Plan for " + mod.lang.name(want) + " x" + count + " (counting the player's inventory and the storage around):\n");
        if (!used.isEmpty()) b.append("Take what is at hand: ").append(names(used)).append('\n');
        if (!steps.isEmpty()) {
            b.append("Steps, in order:\n");
            for (int i = 0; i < steps.size() && i < MAX_STEPS; i++) b.append(i + 1).append(". ").append(steps.get(i)).append('\n');
            if (steps.size() > MAX_STEPS) b.append("... and ").append(steps.size() - MAX_STEPS).append(" more steps\n");
        }
        if (!fluids.isEmpty()) {
            b.append("Fluids needed: ");
            boolean first = true;
            for (Map.Entry<String, Integer> e : fluids.entrySet()) {
                b.append(first ? "" : ", ").append(e.getKey()).append(' ').append(e.getValue()).append(" mB");
                first = false;
            }
            b.append('\n');
        }
        b.append(missing.isEmpty() ? "Nothing is missing: everything can be made from what is at hand.\n"
                : "Missing, to gather or mine (no recipe, or not enough): " + names(missing) + "\n");
        return b.toString();
    }

    String names(Map<String, Integer> m) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            ItemStack s = mod.recipes.stacks.get(e.getKey());
            if (b.length() > 0) b.append(", ");
            b.append(s == null ? e.getKey() : mod.lang.name(s)).append(" x").append(e.getValue());
        }
        return b.toString();
    }

    void need(ItemStack s, int n, int depth) {
        if (s.func_77960_j() == RecipeIndex.WILD) s = new ItemStack(s.func_77973_b(), 1, 0);   // "any kind": the plain one
        String k = RecipeIndex.key(s);
        int have = avail.containsKey(k) ? avail.get(k) : 0;
        int take = Math.min(have, n);
        if (take > 0) {
            avail.put(k, have - take);
            used.put(k, (used.containsKey(k) ? used.get(k) : 0) + take);
        }
        int rest = n - take;
        if (rest <= 0) return;
        RecipeIndex.Recipe r = depth >= MAX_DEPTH || visiting.contains(k) ? null : best(s, k);
        if (r == null) {
            missing.put(k, (missing.containsKey(k) ? missing.get(k) : 0) + rest);
            return;
        }
        int per = 1;
        for (RecipeIndex.Output o : r.out)
            if (o.stack != null && RecipeIndex.key(o.stack).equals(k) && o.note == null) per = Math.max(1, o.stack.field_77994_a);
        int batches = (rest + per - 1) / per;
        visiting.add(k);
        for (RecipeIndex.Ingredient g : r.in) {
            if (g.options.isEmpty()) {
                if (g.fluid != null) {
                    String f = mod.tools.fluid(g.fluid).replaceAll(" \\d+ mB$", "");
                    fluids.put(f, (fluids.containsKey(f) ? fluids.get(f) : 0) + g.fluid.amount * batches);
                }
                continue;
            }
            need(pick(g), g.amount * batches, depth + 1);
        }
        visiting.remove(k);
        int extra = per * batches - rest;
        if (extra > 0) avail.put(k, (avail.containsKey(k) ? avail.get(k) : 0) + extra);
        steps.add(mod.tools.machine(r.machine) + ": " + (batches > 1 ? batches + " times " : "") + mod.tools.text(r).replaceFirst("^- [^:]+: ", ""));
    }

    /** the option of an ingredient the player has most of */
    ItemStack pick(RecipeIndex.Ingredient g) {
        ItemStack best = g.options.get(0);
        int most = -1;
        for (ItemStack o : g.options) {
            Integer n = avail.get(RecipeIndex.key(o));
            if (n != null && n > most) { most = n; best = o; }
        }
        return best;
    }

    /** the way to make it whose inputs are most at hand; the crafting table and the furnace win ties */
    RecipeIndex.Recipe best(ItemStack s, String k) {
        RecipeIndex.Recipe best = null;
        double bestScore = -1;
        for (RecipeIndex.Recipe r : mod.recipes.producing(s)) {
            boolean loop = false;
            int have = 0, all = 0;
            for (RecipeIndex.Ingredient g : r.in) {
                all++;
                for (ItemStack o : g.options) {
                    if (RecipeIndex.key(o).equals(k)) loop = true;
                    Integer n = avail.get(RecipeIndex.key(o));
                    if (n != null && n >= g.amount) { have++; break; }
                }
            }
            if (loop || all == 0) continue;
            double score = (double) have / all + (r.machine.equals("crafting") ? 0.02 : r.machine.equals("furnace") ? 0.01 : 0) - all * 0.001;
            if (score > bestScore) { bestScore = score; best = r; }
        }
        return best;
    }
}
