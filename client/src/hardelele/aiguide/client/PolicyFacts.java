package hardelele.aiguide.client;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.entity.EntityLiving;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.item.ItemTool;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraft.world.WorldServer;

/**
 * The raw facts of an agent for the policy in the game (RlSpec's names: RlSpec.vector and RlSpec.mask read them), for
 * a mind's goal outside the learning environment. An ADAPTER until rl/env is merged: it gathers what RlEnv.facts
 * gathers, from the same calls where this branch has them; the facts of rl/env's new skills are 0 here (frontier,
 * animals: go_to:frontier and hunt stay masked), light_spots is "a torch in the bag". Once rl/env is in, this gives
 * way to RlEnv's own facts (one code for the environment and the game; docs/rl/POLICY.md).
 */
final class PolicyFacts {
    private PolicyFacts() { }

    static final Block[] STONE = { Blocks.stone, Blocks.cobblestone };
    static final String[] RAW_FOOD = { "beef", "porkchop", "chicken", "fish", "rotten_flesh", "spider_eye", "mutton", "rabbit" };

    /** the episode's part: what a mind knows of its goal and its last step */
    static final class Memo {
        String scenario = "wood";
        int last = -1;            // RlSpec's action index of the last step, -1 none
        String outcome = "none";  // RlSpec.outcome of its end
        int repeated;
        double seconds, timeFrac, progress;
    }

    static Map<String, Double> of(Crew c, Agent a, WorldServer w, Memo mm) {
        Map<String, Double> r = new LinkedHashMap<String, Double>();
        put(r, "scen_" + mm.scenario.replace(':', '_'), 1);
        int slot = 0;
        for (Agent g : c.agents) { if (g == a) break; slot++; }
        put(r, "agent_" + Math.min(slot, RlSpec.MAX_AGENTS - 1), 1);
        put(r, "n_agents", c.agents.size());
        put(r, "health", a.getHealth());
        put(r, "max_health", a.getMaxHealth());
        put(r, "low_health", Defense.LOW);
        put(r, "armor", a.getTotalArmorValue());
        put(r, "held_tier", a.getHeldItem() == null ? 0 : EpochScore.tier(MindSkills.id(a.getHeldItem())));
        int pick = 0, weapon = 0;
        for (ItemStack s : a.bag) {
            if (s == null || s.getItem() == null) continue;
            String id = MindSkills.id(s);
            if (id.endsWith("_pickaxe")) pick = Math.max(pick, EpochScore.tier(id));
            if (s.getItem() instanceof ItemSword) weapon = Math.max(weapon, EpochScore.tier(id));
        }
        put(r, "pick_tier", pick);
        put(r, "weapon_tier", weapon);
        double baseD = c.base == null ? 32 : Math.hypot(a.posX - c.base[0] - 0.5, a.posZ - c.base[2] - 0.5);
        put(r, "at_home", baseD < 4 ? 1 : 0);
        long t = w.getWorldTime() % 24000;
        put(r, "day_sin", Math.sin(2 * Math.PI * t / 24000));
        put(r, "day_cos", Math.cos(2 * Math.PI * t / 24000));
        put(r, "night", t >= 13000 && t < 23000 ? 1 : 0);
        put(r, "dusk_in", t < 12500 ? (12500 - t) / 20.0 : 0);
        Map<String, Double> bag = new HashMap<String, Double>();
        double total = 0, stack = 0, fuel = 0;
        for (ItemStack s : a.bag) {
            if (s == null || s.getItem() == null) continue;
            String k = bagKey(s);
            if (k != null) bag.put(k, (bag.containsKey(k) ? bag.get(k) : 0) + s.stackSize);
            total += s.stackSize;
            if (!(s.getItem() instanceof ItemTool) && !(s.getItem() instanceof ItemSword)) stack += s.stackSize;
            if (TileEntityFurnace.getItemBurnTime(s) > 0) fuel += s.stackSize;
        }
        for (String b : RlSpec.BAG) put(r, "bag_" + b, bag.containsKey(b) ? bag.get(b) : 0);
        put(r, "bag_total", total);
        put(r, "bag_stackables", stack);
        put(r, "fuel", fuel);
        Map<String, Integer> ids = MindSkills.bag(a);
        String mat = Defense.material("any", ids);
        put(r, "wall_blocks", mat == null ? 0 : Defense.count(ids, mat));
        List<int[]> trees = MindSkills.trunks(c, a, w, 40, true);
        put(r, "trees_seen", trees.size());
        put(r, "tree_dist", trees.isEmpty() ? 48 : Crew.dist(a, trees.get(0)[0] + 0.5, trees.get(0)[1], trees.get(0)[2] + 0.5));
        put(r, "trees_free", MindSkills.trunks(c, a, w, 48, false).size());
        seen(r, a, w, "stone", STONE, 32, 32);
        seen(r, a, w, "coal", new Block[] { Blocks.coal_ore }, 48, 48);
        seen(r, a, w, "iron", new Block[] { Blocks.iron_ore }, 48, 48);
        put(r, "coal_minable", minable(c, a, w, Blocks.coal_ore));
        put(r, "iron_minable", minable(c, a, w, Blocks.iron_ore));
        put(r, "animals_seen", 0);   // no hunt skill before rl/env
        put(r, "animal_dist", 24);
        put(r, "frontier", 0);       // no go_to:frontier before rl/env
        station(r, c, a, w, "table", Roles.Role.TABLE, 24);
        station(r, c, a, w, "furnace", Roles.Role.FURNACE, 24);
        int[] ch = c.chest != null && Roles.is(w, c.chest, Roles.Role.CHEST) ? c.chest : null;
        put(r, "chest_known", ch == null ? 0 : 1);
        put(r, "chest_dist", ch == null ? 48 : Crew.dist(a, ch[0] + 0.5, ch[1], ch[2] + 0.5));
        put(r, "chest_food", ch == null ? 0 : food(c.chestInv(w)));
        put(r, "base_known", c.base == null ? 0 : 1);
        put(r, "base_dist", baseD);
        WaveCycle.View v = Waves.state();
        put(r, "waves_active", v.active() ? 1 : 0);
        put(r, "wave_on", v.phase == WaveCycle.Phase.WAVE ? 1 : 0);
        put(r, "phase_prep", v.phase == WaveCycle.Phase.PREP ? 1 : 0);
        put(r, "phase_break", v.phase == WaveCycle.Phase.BREAK ? 1 : 0);
        put(r, "wave_num", v.wave);
        put(r, "next_wave_in", Math.max(0, v.secondsToNext));
        List<EntityLiving> foes = Waves.enemies();
        put(r, "enemies", v.active() ? v.enemies() : foes.size());
        EntityLiving near = null;
        int close = 0;
        for (EntityLiving m : foes) {
            if (near == null || a.getDistanceToEntity(m) < a.getDistanceToEntity(near)) near = m;
            if (a.getDistanceToEntity(m) <= 8) close++;
        }
        put(r, "enemy_dist", near == null ? 32 : a.getDistanceToEntity(near));
        put(r, "enemy_dx", near == null || c.base == null ? 0 : near.posX - c.base[0] - 0.5);
        put(r, "enemy_dz", near == null || c.base == null ? 0 : near.posZ - c.base[2] - 0.5);
        put(r, "enemies_near", close);
        Defense.Perimeter p = DefenseWorld.ring;
        put(r, "ring_planned", p == null ? 0 : 1);
        if (p != null && !p.cells.isEmpty()) {
            int stand = DefenseWorld.standing(w, p);
            put(r, "wall_frac", stand / (double) p.cells.size());
            put(r, "holes", p.cells.size() - stand);
            put(r, "inside_wall", p.inside(a.posX, a.posZ) ? 1 : 0);
            int free = 0;
            for (String n : p.postNames) { Doer d = c.board.postHolder(n); if (d == null || !d.alive()) free++; }
            put(r, "posts_free", free);
        }
        List<int[]> moat = DefenseWorld.moat;
        if (moat != null && !moat.isEmpty()) {
            int dug = 0;
            for (int[] m : moat) if (DefenseWorld.open(w, m[0], m[1], m[2])) dug++;
            put(r, "moat_frac", dug / (double) moat.size());
        }
        int torches = 0;
        for (Defense.Tally x : Defense.all().values()) torches += x.torches;
        put(r, "torches_placed", torches);
        String post = c.board.postOf(a);
        put(r, "has_post", post == null ? 0 : 1);
        int[] pa = post == null ? null : DefenseWorld.postAt(post);
        put(r, "post_dist", pa == null ? 0 : Math.hypot(a.posX - pa[0] - 0.5, a.posZ - pa[2] - 0.5));
        put(r, "light_spots", bag.containsKey("torch") && c.base != null ? 1 : 0);
        ItemStack held = a.getHeldItem(), best = a.bestWeapon();
        put(r, "can_equip", best != null && (held == null || EpochScore.tier(MindSkills.id(best)) > EpochScore.tier(MindSkills.id(held))) ? 1 : 0);
        int alive = 0, low = 0;
        double sum = 0, min = 20, nd = 32;
        for (Agent g : c.agents) {
            if (g == a || g.isDead) continue;
            alive++;
            sum += g.getHealth();
            min = Math.min(min, g.getHealth());
            if (g.getHealth() < 10) low++;
            nd = Math.min(nd, a.getDistanceToEntity(g));
        }
        put(r, "allies_alive", alive);
        put(r, "allies_health_mean", alive == 0 ? 20 : sum / alive);
        put(r, "allies_health_min", alive == 0 ? 20 : min);
        put(r, "allies_low", low);
        put(r, "ally_dist", nd);
        put(r, "last_" + mm.outcome, 1);
        put(r, "repeated", mm.repeated);
        put(r, "last_seconds", mm.seconds);
        if (mm.last >= 0) put(r, "last_action_" + RlSpec.ACTIONS.get(mm.last).id, 1);
        put(r, "time_frac", mm.timeFrac);
        put(r, "goal_progress", mm.progress);
        for (String[] k : RlSpec.CRAFTS) {
            Object[] it = MindSkills.item(k[0]);
            if (it == null) continue;
            List<Skills.Laid> rs = Skills.recipesFor((Item) it[0], (Integer) it[1]);
            Skills.Laid l = rs.isEmpty() ? null : MindSkills.doable(a, rs);
            put(r, "craftable_" + k[0], l == null ? 0 : 1);
            put(r, "small_" + k[0], l != null && l.small() ? 1 : 0);
        }
        return r;
    }

    static void put(Map<String, Double> r, String k, double v) { r.put(k, v); }

    /** the bag's key of a stack (RlSpec.BAG), or null (RlEnv.bagKey) */
    static String bagKey(ItemStack s) {
        String id = MindSkills.id(s), b = id.indexOf(':') < 0 ? id : id.substring(0, id.indexOf(':'));
        if (b.equals("log2")) return "log";
        if (b.equals("grass")) return "dirt";
        if (s.getItem() instanceof ItemFood) {
            for (String f : RAW_FOOD) if (f.equals(b)) return "food_raw";
            return "food_cooked";
        }
        for (String k : RlSpec.BAG) if (k.equals(b)) return k;
        return null;
    }

    static int food(net.minecraft.inventory.IInventory inv) {
        if (inv == null) return 0;
        int n = 0;
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s != null && s.getItem() instanceof ItemFood) n += s.stackSize;
        }
        return n;
    }

    static void seen(Map<String, Double> r, Agent a, WorldServer w, String name, Block[] kinds, double within, double none) {
        int n = 0;
        double best = none;
        for (Block b : kinds)
            for (int[] p : Exploration.knownBlocks(w, b)) {
                if (MindSkills.hdist(a, p) > within) continue;
                n++;
                best = Math.min(best, Crew.dist(a, p[0] + 0.5, p[1], p[2] + 0.5));
            }
        put(r, name + "_seen", n);
        put(r, name + "_dist", best);
    }

    static int minable(Crew c, Agent a, WorldServer w, Block b) {
        int n = 0;
        for (int[] p : Exploration.knownBlocks(w, b))
            if (MindSkills.hdist(a, p) <= 32 && Math.abs(p[1] - a.posY) <= 12 && w.getBlock(p[0], p[1], p[2]) == b) n++;
        return n;
    }

    static void station(Map<String, Double> r, Crew c, Agent a, WorldServer w, String name, Roles.Role role, double within) {
        int[] s = MindSkills.station(c, a, w, role, 64);
        double d = s == null ? 64 : Crew.dist(a, s[0] + 0.5, s[1], s[2] + 0.5);
        put(r, name + "_known", s != null && d <= 64 ? 1 : 0);
        put(r, name + "_dist", s == null ? within : d);
    }
}
