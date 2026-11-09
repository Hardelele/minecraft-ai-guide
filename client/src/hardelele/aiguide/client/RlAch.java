package hardelele.aiguide.client;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The agents' achievements in the game (RlAchievements' tree, docs/rl/CONTRACT-v2.md 7): an agent is no player, the
 * game gives it none, so the game's conditions are repeated here from what an agent's steps did (what it crafted,
 * smelted, picked up: the bag before and after) and from what the causes tell (a kill by its hit or arrow, a calf of
 * the cows it fed, a hit of 18). Only with the parent got (the game's rule). A new one is logged, shown as the game's
 * toast in a game with a window, and paid in the learning environment (RlEnv.achieved).
 */
final class RlAch {
    private RlAch() { }

    static final Map<String, Set<String>> got = new HashMap<String, Set<String>>();

    static synchronized void reset() { got.clear(); }

    static synchronized Set<String> of(String agent) {
        Set<String> s = got.get(agent);
        if (s == null) got.put(agent, s = new LinkedHashSet<String>());
        return s;
    }

    /** the achievement for the agent if it may get it now (its parent got, it not); true when new */
    static boolean grant(Agent a, String id) {
        synchronized (RlAch.class) {
            Set<String> s = of(a.who());
            if (!RlAchievements.canGet(s, id) || !RlAchievements.ALL.get(id).supported) return false;
            s.add(id);
        }
        RlAchievements.Ach x = RlAchievements.ALL.get(id);
        Events.log("achievement: " + a.who() + " got " + id + " «" + x.ru + "» (depth " + RlAchievements.depth(id) + ")");
        AchievementToast.show(a.who(), id);
        RlEnv.achieved(a, id);
        return true;
    }

    /** at a decision: openInventory (the agent's bag is always open) */
    static void decided(Agent a) { grant(a, "openInventory"); }

    /** what a step changed in the bag: crafted, smelted, picked up */
    static void step(Agent a, String tool, Map<String, Integer> before, Map<String, Integer> after) {
        if (before == null || after == null) return;
        for (Map.Entry<String, Integer> e : after.entrySet()) {
            int gain = e.getValue() - (before.containsKey(e.getKey()) ? before.get(e.getKey()) : 0);
            if (gain <= 0) continue;
            String id = e.getKey();
            int c = id.indexOf(':');
            if (c >= 0) id = id.substring(0, c);
            if (tool.equals("craft") || tool.equals("craft_armor")) crafted(a, id);
            if (tool.equals("smelt") && id.equals("iron_ingot")) grant(a, "acquireIron");
            picked(a, id);
        }
    }

    /** the game's crafting achievements (SlotCrafting.onCrafting) */
    static void crafted(Agent a, String id) {
        if (id.equals("crafting_table")) grant(a, "buildWorkBench");
        if (id.endsWith("_pickaxe")) { grant(a, "buildPickaxe"); if (!id.startsWith("wooden_")) grant(a, "buildBetterPickaxe"); }
        if (id.equals("furnace")) grant(a, "buildFurnace");
        if (id.endsWith("_hoe")) grant(a, "buildHoe");
        if (id.equals("bread")) grant(a, "makeBread");
        if (id.endsWith("_sword")) grant(a, "buildSword");
        if (id.equals("enchanting_table")) grant(a, "enchantments");
        if (id.equals("bookshelf")) grant(a, "bookcase");
    }

    /** the game's pickup achievements (EntityItem.onCollideWithPlayer) */
    static void picked(Agent a, String id) {
        if (id.equals("log") || id.equals("log2")) grant(a, "mineWood");
        if (id.equals("leather")) grant(a, "killCow");
        if (id.equals("diamond")) grant(a, "diamonds");
    }

    static synchronized int count(String agent) { Set<String> s = got.get(agent); return s == null ? 0 : s.size(); }

    static synchronized boolean has(String agent, String id) { Set<String> s = got.get(agent); return s != null && s.contains(id); }

    static synchronized int available(String agent) { return RlAchievements.available(of(agent)).size(); }
}
