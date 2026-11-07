package hardelele.aiguide.client;

import java.io.File;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * The crew's books: every item that comes to an agent and every item that leaves it, and how. Things come only
 * from drops picked up, a chest, a craft, a smelt or a hand-over; they leave only by being placed, used up in a
 * craft or a smelt, handed over or put in a chest. Whatever came from nowhere or went nowhere is counted apart,
 * with the place in the code that did it: that is what is still faked. Every action an agent does is also counted
 * by where its task came from (Origin), and every task's end by its Result. Written to ledger.json in the world.
 */
public class Ledger {
    public enum Way {
        // in
        DROP, CHEST_OUT, CRAFTED, SMELTED, HANDED_IN, FROM_NOTHING,
        // out
        PLACED, INGREDIENT, FUEL, HANDED_OUT, CHEST_IN, DROPPED_AT_DEATH, INTO_NOTHING
    }

    static final Map<String, Map<String, Integer>> flows = new TreeMap<String, Map<String, Integer>>();   // way -> item -> n
    static final Map<String, Integer> fakes = new TreeMap<String, Integer>();                          // "site: item" -> n
    static final Map<String, Map<String, Integer>> actions = new TreeMap<String, Map<String, Integer>>(); // origin -> action -> n
    static final Map<String, Integer> results = new TreeMap<String, Integer>();                        // result -> n
    static int dirty;

    static String name(ItemStack s) {
        if (s == null || s.getItem() == null) return "?";
        Object n = Item.itemRegistry.getNameForObject(s.getItem());
        return (n == null ? "?" : n.toString().replace("minecraft:", "")) + (s.getMetadata() != 0 && s.getMetadata() != 32767 ? "@" + s.getMetadata() : "");
    }

    static void add(Map<String, Integer> m, String k, int n) {
        Integer o = m.get(k);
        m.put(k, (o == null ? 0 : o) + n);
    }

    static Map<String, Integer> sub(Map<String, Map<String, Integer>> m, String k) {
        Map<String, Integer> v = m.get(k);
        if (v == null) m.put(k, v = new TreeMap<String, Integer>());
        return v;
    }

    /** an item moved, the honest way (or, with FROM_NOTHING / INTO_NOTHING and a site, the faked way) */
    static synchronized void flow(Way how, ItemStack s, String site) {
        if (s == null || s.stackSize <= 0) return;
        add(sub(flows, how.name()), name(s), s.stackSize);
        if (how == Way.FROM_NOTHING || how == Way.INTO_NOTHING) add(fakes, how.name().toLowerCase() + " @ " + site + ": " + name(s), s.stackSize);
        dirty++;
    }

    static void flow(Way how, ItemStack s) {
        flow(how, s, "");
    }

    /** a world block appeared or went without an item behind it (a chest, a door, a floor block) */
    static void fakeBlock(String site, ItemStack asItem) {
        flow(Way.FROM_NOTHING, asItem, site);
    }

    /** an action of an agent, counted under the origin of the task it does it in */
    static synchronized void act(Agent a, String kind) {
        add(sub(actions, (a == null || a.origin == null ? Origin.SCRIPT : a.origin).name()), kind, 1);
        dirty++;
        if (a != null && a.slot.busy()) Cases.act(a, kind + " " + a.actX + " " + a.actY + " " + a.actZ);   // what the body did in a reaction, for its case
    }

    static synchronized void result(Result r) {
        add(results, r.name(), 1);
    }

    /** the share of actions by origin, in words */
    static synchronized String report() {
        int total = 0;
        Map<String, Integer> byOrigin = new TreeMap<String, Integer>();
        for (Map.Entry<String, Map<String, Integer>> e : actions.entrySet()) {
            int n = 0;
            for (int v : e.getValue().values()) n += v;
            byOrigin.put(e.getKey(), n);
            total += n;
        }
        int fromNothing = 0, intoNothing = 0;
        for (Map.Entry<String, Integer> e : fakes.entrySet()) {
            if (e.getKey().startsWith("from_nothing")) fromNothing += e.getValue();
            else intoNothing += e.getValue();
        }
        StringBuilder b = new StringBuilder("ledger: actions " + total);
        for (Map.Entry<String, Integer> e : byOrigin.entrySet())
            b.append(", ").append(e.getKey()).append(" ").append(total == 0 ? 0 : Math.round(100.0 * e.getValue() / total)).append("%");
        b.append("; items from nothing ").append(fromNothing).append(", into nothing ").append(intoNothing).append("; results ").append(results);
        return b.toString();
    }

    static synchronized void save(File worldDir) {
        try {
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            com.google.gson.Gson g = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
            o.add("flows", g.toJsonTree(flows));
            o.add("fakes", g.toJsonTree(fakes));
            o.add("actions", g.toJsonTree(actions));
            o.add("results", g.toJsonTree(results));
            o.addProperty("summary", report());
            java.nio.file.Files.write(new File(worldDir, "ledger.json").toPath(), g.toJson(o).getBytes("UTF-8"));
            dirty = 0;
        } catch (Exception e) {
            Events.log("ledger: not saved: " + e);
        }
    }
}
