package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.block.BlockDoor;
import net.minecraft.enchantment.EnchantmentData;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.EntityLiving;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemPickaxe;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.WorldServer;

/**
 * The gear of contract 3 (docs/rl/CONTRACT-v2.md 3): armour by material (the piece that adds most to what is worn),
 * shooting from where it stands, the gate's door in the wall and closing it, bookshelves where the enchanting table
 * counts them, an enchanting by the agent's levels as the game's table does it (ContainerEnchantment: the slots'
 * levels by the shelves, the whole level spent). Through the body and the game's rules, as MindSkills' skills.
 */
final class GearSkills {
    private GearSkills() { }

    static final String[] PIECES = { "chestplate", "leggings", "helmet", "boots" };

    static MindSkills.Skill make(Crew c, Agent a, WorldServer w, Mind.Call call) {
        switch (call.tool) {
            case "craft_armor": return craftArmor(c, a, w, call.arg("material"));
            case "shoot": return shoot(c, a, w, MindSkills.clamp(call.num("seconds", 30), 2, 120));
            case "build_gate": return buildGate(c, a, w);
            case "close_gate": return closeGate(c, a, w);
            case "place_shelf": return placeShelf(c, a, w);
            case "enchant": return enchant(c, a, w);
            default: return null;
        }
    }

    // ---- armour -------------------------------------------------------------------------------------------------

    /** the armour's worth in a slot now: what is worn there (the creature's slot 4 - armorType) */
    static int worn(Agent a, int armorType) {
        ItemStack on = a.getEquipmentInSlot(4 - armorType);
        return on != null && on.getItem() instanceof ItemArmor ? ((ItemArmor) on.getItem()).damageReduceAmount : 0;
    }

    /** the piece of the material the bag can craft that adds most to what is worn, its id, or null */
    static String bestPiece(Agent a, String material) {
        String best = null;
        int gain = 0;
        for (String p : PIECES) {
            String id = material + "_" + p;
            Object[] it = MindSkills.item(id);
            if (it == null || !(it[0] instanceof ItemArmor)) continue;
            ItemArmor ar = (ItemArmor) it[0];
            int g = ar.damageReduceAmount - worn(a, ar.armorType);
            if (g <= gain) continue;
            if (MindSkills.doable(a, Skills.recipesFor(ar, (Integer) it[1])) == null) continue;
            best = id;
            gain = g;
        }
        return best;
    }

    static boolean craftable(Agent a, String material) { return bestPiece(a, material) != null; }

    static MindSkills.Skill craftArmor(Crew c, Agent a, WorldServer w, String material) {
        String m = material == null ? "" : material.trim().toLowerCase(java.util.Locale.ROOT);
        if (m.equals("gold")) m = "golden";
        String id = bestPiece(a, m);
        if (id == null) return MindSkills.failed("MISSING", "no piece of " + m + " armour the bag can make that is better than what it wears "
                + "(helmet 5, chestplate 8, leggings 7, boots 4 of the material)");
        return MindSkills.craft(c, a, w, id, 1);
    }

    // ---- shoot --------------------------------------------------------------------------------------------------

    static MindSkills.Skill shoot(Crew c, Agent a, WorldServer w, int seconds) {
        if (DefenseSkills.bow(a) == null) return MindSkills.failed("NO_BOW", "no bow in the bag (bow: 3 sticks, 3 string)");
        if (Skills.count(a.bag, Items.arrow, -1) == 0) return MindSkills.failed("NO_ARROWS", "no arrows in the bag (arrow: flint, stick, feather make 4)");
        if (target(a, w) == null) return MindSkills.failed("NO_ENEMY", "no enemy in sight within 32");
        Shoot s = new Shoot(seconds);
        s.lease = 20 * (seconds + 20);
        return s;
    }

    /** the nearest hostile it sees within 32 */
    @SuppressWarnings("unchecked")
    static EntityLiving target(Agent a, WorldServer w) {
        EntityLiving best = null;
        double bd = 32;
        for (Object o : w.getEntitiesWithinAABB(EntityLiving.class, AxisAlignedBB.getBoundingBox(a.posX - 32, a.posY - 12, a.posZ - 32, a.posX + 32, a.posY + 12, a.posZ + 32))) {
            EntityLiving m = (EntityLiving) o;
            if (m.isDead || m.getHealth() <= 0 || !Guard.hostile(m)) continue;
            double d = a.getDistanceToEntity(m);
            if (d < bd && a.canEntityBeSeen(m)) { bd = d; best = m; }
        }
        return best;
    }

    /** from where it stands: a full draw, a shot, again; the kills counted when a mob it shot dies */
    static final class Shoot extends MindSkills.Skill {
        final int len;
        int t, draw, quiet, shots;
        final Map<EntityLiving, Long> struck = new HashMap<EntityLiving, Long>();
        Shoot(int seconds) { len = seconds * 20; }

        Result run(Agent a, WorldServer w) {
            long now = w.getTotalWorldTime();
            for (java.util.Iterator<Map.Entry<EntityLiving, Long>> it = struck.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<EntityLiving, Long> e = it.next();
                EntityLiving m = e.getKey();
                if (m.isDead || m.getHealth() <= 0) {
                    Defense.of(a.who()).kills++;
                    Ledger.act(a, "kill " + m.getCommandSenderName());
                    Events.log("crew: " + a.who() + " shot dead " + m.getCommandSenderName());
                    it.remove();
                } else if (now - e.getValue() > 100) it.remove();
            }
            if (interrupt != null) { reason = "INTERRUPTED"; detail = interrupt + " after " + shots + " shots"; return Result.BLOCKED; }
            if (++t > len) return end();
            ItemStack bow = DefenseSkills.bow(a);
            if (bow == null) { reason = shots > 0 ? "OK" : "NO_BOW"; return shots > 0 ? end() : Result.NO_ITEMS; }
            if (Skills.count(a.bag, Items.arrow, -1) == 0) {
                if (shots > 0) return end();
                reason = "NO_ARROWS";
                detail = "no arrows left";
                return Result.NO_ITEMS;
            }
            a.guarded(Defense.POST_GUARD);
            EntityLiving tg = t % 4 == 1 || draw > 0 ? target(a, w) : null;
            if (tg == null) {
                draw = 0;
                if (++quiet > 100) {
                    if (shots > 0) return end();
                    reason = "NO_ENEMY";
                    detail = "no enemy in sight for 5 s";
                    return Result.GONE;
                }
                return Result.RUNNING;
            }
            quiet = 0;
            a.body.stop();
            a.body.hold(bow);
            Crew.face(a, tg.posX, tg.posY + tg.getEyeHeight(), tg.posZ);
            a.act((int) Math.floor(tg.posX), (int) Math.floor(tg.posY), (int) Math.floor(tg.posZ), 4);
            if (++draw >= 20 && a.body.facing(tg.posX, tg.posY + tg.getEyeHeight(), tg.posZ, 15)) {   // a full draw, as a player's
                draw = 0;
                if (DefenseSkills.loose(a, w, tg)) { shots++; struck.put(tg, now); }
            }
            return Result.RUNNING;
        }

        Result end() {
            detail = "shot " + shots + " arrows";
            return Result.OK;
        }
    }

    // ---- the gate -----------------------------------------------------------------------------------------------

    /** the gate's lower cell (the opening's bottom), or null */
    static int[] gateCell() {
        Defense.Perimeter p = DefenseWorld.ring;
        if (p == null || p.gateCells.isEmpty()) return null;
        int[] best = null;
        for (int[] g : p.gateCells) if (best == null || g[1] < best[1]) best = g;
        return best;
    }

    static boolean isDoor(WorldServer w, int[] d) { return d != null && w.getBlock(d[0], d[1], d[2]) instanceof BlockDoor; }

    static MindSkills.Skill buildGate(Crew c, Agent a, WorldServer w) {
        int[] g = gateCell();
        if (g == null) return MindSkills.failed("NO_PERIMETER", "no ring wall planned, so no gate: build_wall first");
        if (isDoor(w, g)) { RlWorld.now.door = g.clone(); return MindSkills.failed("DONE_ALREADY", "the gate has its door at " + g[0] + " " + g[1] + " " + g[2]); }
        if (Skills.count(a.bag, Items.wooden_door, -1) == 0) return MindSkills.failed("NO_DOOR", "no wooden_door in the bag (6 planks at a table)");
        final int[] at = g.clone();
        final Crew.Task put = new Skills.PlaceFrom(at[0], at[1], at[2], Items.wooden_door, 0);
        MindSkills.Skill s = new MindSkills.Skill() {
            Result run(Agent ag, WorldServer ww) {
                Result r = put.step(ag, ww);
                if (r == Result.OK) {
                    RlWorld.now.door = at;
                    detail = "a door in the gate at " + at[0] + " " + at[1] + " " + at[2];
                    Events.log("defense: " + ag.who() + " puts a door in the gate at " + at[0] + " " + at[1] + " " + at[2]);
                }
                return r;
            }
        };
        s.lease = Budget.ticks(Crew.dist(a, at[0], at[1], at[2]) * 1.5, 0, 1, 0, 0, 60);
        return s;
    }

    static boolean open(WorldServer w, int[] d) {
        if (!isDoor(w, d)) return false;
        int m = w.getBlockMetadata(d[0], d[1], d[2]);   // the lower half's bit 4: open (BlockDoor's own reading)
        if ((m & 8) != 0) m = w.getBlockMetadata(d[0], d[1] - 1, d[2]);
        return (m & 4) != 0;
    }

    static MindSkills.Skill closeGate(Crew c, Agent a, WorldServer w) {
        final int[] d = RlWorld.now.door;
        if (!isDoor(w, d)) return MindSkills.failed("NO_GATE", "no door in the gate");
        if (!open(w, d)) return MindSkills.failed("DONE_ALREADY", "the gate's door is shut");
        MindSkills.Skill s = new MindSkills.Skill() {
            Crew.GoTo walk;
            Result run(Agent ag, WorldServer ww) {
                if (!isDoor(ww, d)) { reason = "NO_GATE"; detail = "the door is gone"; return Result.GONE; }
                if (!open(ww, d)) { detail = "the gate is shut"; return Result.OK; }
                if (Crew.dist(ag, d[0] + 0.5, d[1], d[2] + 0.5) > 3) {
                    if (walk == null) walk = new Crew.GoTo(d[0] + 0.5, d[1], d[2] + 0.5, 2.5);
                    Result r = walk.step(ag, ww);
                    if (r == Result.RUNNING) return r;
                    walk = null;
                    if (r != Result.OK && Crew.dist(ag, d[0] + 0.5, d[1], d[2] + 0.5) > 3.5) { reason = "NO_PATH"; detail = "no way to the gate"; return r; }
                }
                Result r = ag.body.activate(ww, d[0], d[1], d[2]);
                if (r == Result.RUNNING) return r;
                if (r != Result.OK) { reason = r.name(); detail = "could not reach the door's handle"; return r; }
                detail = open(ww, d) ? "the door would not shut" : "the gate is shut";
                return open(ww, d) ? Result.BLOCKED : Result.OK;
            }
        };
        s.lease = Budget.ticks(Crew.dist(a, d[0], d[1], d[2]) * 1.5, 0, 0, 0, 0, 40);
        return s;
    }

    // ---- the enchanting table and its shelves -------------------------------------------------------------------

    /** the crew's enchanting table, or one seen within r (standing) */
    static int[] table(Agent a, WorldServer w, double r) {
        int[] t = RlWorld.now.enchTable;
        if (t != null && w.getBlock(t[0], t[1], t[2]) == Blocks.enchanting_table) return t;
        int[] best = null;
        double bd = r;
        for (int[] p : Exploration.knownBlocks(w, Blocks.enchanting_table)) {
            if (w.getBlock(p[0], p[1], p[2]) != Blocks.enchanting_table) continue;
            double d = Crew.dist(a, p[0] + 0.5, p[1], p[2] + 0.5);
            if (d <= bd) { bd = d; best = p; }
        }
        if (best != null && RlWorld.now.enchTable == null) RlWorld.now.enchTable = best.clone();
        return best;
    }

    /** the cells the table would count a shelf in (ContainerEnchantment.onCraftMatrixChanged), each once; with the air between */
    static Set<Long> shelfCells(WorldServer w, int[] t) {
        Set<Long> s = new LinkedHashSet<Long>();
        int x = t[0], y = t[1], z = t[2];
        for (int j = -1; j <= 1; j++)
            for (int k = -1; k <= 1; k++) {
                if (j == 0 && k == 0 || !w.isAirBlock(x + k, y, z + j) || !w.isAirBlock(x + k, y + 1, z + j)) continue;
                for (int dy = 0; dy <= 1; dy++) {
                    s.add(RlWorld.key(x + k * 2, y + dy, z + j * 2));
                    if (k != 0 && j != 0) {
                        s.add(RlWorld.key(x + k * 2, y + dy, z + j));
                        s.add(RlWorld.key(x + k, y + dy, z + j * 2));
                    }
                }
            }
        return s;
    }

    /** the shelves the table counts, as the game counts them (a cell seen from two sides counts twice), at most 15 */
    static int shelves(WorldServer w, int[] t) {
        if (t == null) return 0;
        int l = 0, x = t[0], y = t[1], z = t[2];
        for (int j = -1; j <= 1; j++)
            for (int k = -1; k <= 1; k++) {
                if (j == 0 && k == 0 || !w.isAirBlock(x + k, y, z + j) || !w.isAirBlock(x + k, y + 1, z + j)) continue;
                for (int dy = 0; dy <= 1; dy++) {
                    if (w.getBlock(x + k * 2, y + dy, z + j * 2) == Blocks.bookshelf) l++;
                    if (k != 0 && j != 0) {
                        if (w.getBlock(x + k * 2, y + dy, z + j) == Blocks.bookshelf) l++;
                        if (w.getBlock(x + k, y + dy, z + j * 2) == Blocks.bookshelf) l++;
                    }
                }
            }
        return Math.min(15, l);
    }

    /** free cells (air with something firm below or beside to put against) where a shelf would count, the table's level first */
    static List<int[]> spots(WorldServer w, int[] t) {
        List<int[]> l = new ArrayList<int[]>();
        if (t == null) return l;
        for (int pass = 0; pass < 2; pass++)
            for (long k : shelfCells(w, t)) {
                int[] p = RlWorld.unkey(k);
                if (p[1] != t[1] + pass || !w.isAirBlock(p[0], p[1], p[2]) || Skills.supports(w, p[0], p[1], p[2], false).isEmpty()) continue;
                l.add(p);
            }
        return l;
    }

    static int shelfSpots(WorldServer w, int[] t) { return spots(w, t).size(); }

    static MindSkills.Skill placeShelf(Crew c, Agent a, WorldServer w) {
        Item shelf = Item.getItemFromBlock(Blocks.bookshelf);
        if (Skills.count(a.bag, shelf, -1) == 0) return MindSkills.failed("NO_SHELF", "no bookshelf in the bag (6 planks, 3 books at a table)");
        int[] t = table(a, w, 16);
        if (t == null) return MindSkills.failed("NO_ENCHANTER", "no enchanting table known within 16");
        List<int[]> sp = spots(w, t);
        if (sp.isEmpty()) return MindSkills.failed("NO_SPOT", "no free place round the table where a shelf would count (2 away, the cell between empty)");
        MindSkills.nearestFirst(sp, a);
        final int[] p = sp.get(0);
        final int[] tt = t;
        final Crew.Task put = new Skills.PlaceFrom(p[0], p[1], p[2], shelf, 0);
        MindSkills.Skill s = new MindSkills.Skill() {
            Result run(Agent ag, WorldServer ww) {
                Result r = put.step(ag, ww);
                if (r == Result.OK) detail = "a shelf at " + p[0] + " " + p[1] + " " + p[2] + "; the table counts " + shelves(ww, tt);
                return r;
            }
        };
        s.lease = Budget.ticks(Crew.dist(a, p[0], p[1], p[2]) * 1.5, 0, 1, 0, 0, 40);
        return s;
    }

    // ---- enchant ------------------------------------------------------------------------------------------------

    /** the things it has (bag and worn) that the table can enchant: tools, weapons, armour, a bow, none enchanted */
    static List<ItemStack> enchantables(Agent a) {
        List<ItemStack> l = new ArrayList<ItemStack>();
        for (ItemStack s : a.bag) if (s != null && s.isItemEnchantable()) l.add(s);
        for (int i = 1; i <= 4; i++) { ItemStack s = a.getEquipmentInSlot(i); if (s != null && s.isItemEnchantable()) l.add(s); }
        return l;
    }

    static int rank(ItemStack s) {
        Item i = s.getItem();
        return i instanceof ItemSword ? 0 : i instanceof ItemPickaxe ? 1 : i instanceof ItemArmor ? 2 : i instanceof ItemBow ? 3 : 4;
    }

    static ItemStack best(Agent a) {
        ItemStack b = null;
        for (ItemStack s : enchantables(a)) if (b == null || rank(s) < rank(b) || rank(s) == rank(b) && s.getItem().getItemEnchantability() > b.getItem().getItemEnchantability()) b = s;
        return b;
    }

    static MindSkills.Skill enchant(Crew c, Agent a, WorldServer w) {
        int[] t = table(a, w, 24);
        if (t == null) return MindSkills.failed("NO_ENCHANTER", "no enchanting table known within 24");
        if (best(a) == null) return MindSkills.failed("NOTHING_TO_ENCHANT", "nothing in the bag or worn the table can enchant (tools, weapons, armour, a bow, not enchanted yet)");
        if (a.xp.level < 1) return MindSkills.failed("NO_XP", "no level of experience (orbs: ore mined, mobs killed, smelting, breeding)");
        Enchant e = new Enchant(t);
        e.lease = Budget.ticks(Crew.dist(a, t[0], t[1], t[2]) * 1.5, 0, 0, 0, 0, 80);
        return e;
    }

    /** to the table, the best thing, the dearest slot its levels allow: the game's levels by the shelves, the game's enchantments */
    static final class Enchant extends MindSkills.Skill {
        final int[] t;
        Crew.GoTo walk;
        int ticks;
        Enchant(int[] t) { this.t = t; }

        Result run(Agent a, WorldServer w) {
            if (w.getBlock(t[0], t[1], t[2]) != Blocks.enchanting_table) { reason = "NO_ENCHANTER"; detail = "the table is gone"; return Result.GONE; }
            if (Crew.dist(a, t[0] + 0.5, t[1], t[2] + 0.5) > 3) {
                if (walk == null) walk = new Crew.GoTo(t[0] + 0.5, t[1], t[2] + 0.5, 2.5);
                Result r = walk.step(a, w);
                if (r == Result.RUNNING) return r;
                walk = null;
                if (r != Result.OK && Crew.dist(a, t[0] + 0.5, t[1], t[2] + 0.5) > 3.5) { reason = "NO_PATH"; detail = "no way to the enchanting table"; return r; }
            }
            Crew.face(a, t[0] + 0.5, t[1] + 0.8, t[2] + 0.5);
            a.act(t[0], t[1], t[2], 2);
            if (++ticks % 8 == 0) a.swingItem();
            if (ticks < 40) return Result.RUNNING;
            ItemStack s = best(a);
            if (s == null) { reason = "NOTHING_TO_ENCHANT"; detail = "nothing to enchant any more"; return Result.GONE; }
            Random rnd = new Random(w.rand.nextLong());
            int shelves = shelves(w, t), lv = 0;
            int[] slots = new int[3];
            for (int i = 0; i < 3; i++) {
                slots[i] = EnchantmentHelper.calcItemStackEnchantability(rnd, i, shelves, s);
                if (slots[i] > 0 && slots[i] <= a.xp.level) lv = Math.max(lv, slots[i]);
            }
            if (lv == 0) {
                reason = "NO_XP";
                detail = "the table asks " + slots[0] + "/" + slots[1] + "/" + slots[2] + " levels (" + shelves + " shelves); it has " + a.xp.level;
                return Result.NO_ITEMS;
            }
            @SuppressWarnings("unchecked")
            List<EnchantmentData> list = EnchantmentHelper.buildEnchantmentList(rnd, s, lv);
            if (list == null || list.isEmpty()) { reason = "NOTHING_TO_ENCHANT"; detail = "the table found no enchantment for " + MindSkills.id(s) + " at " + lv; return Result.BLOCKED; }
            a.xp.spend(lv);
            StringBuilder b = new StringBuilder();
            for (EnchantmentData d : list) {
                s.addEnchantment(d.enchantmentobj, d.enchantmentLevel);
                b.append(b.length() > 0 ? ", " : "").append(d.enchantmentobj.getTranslatedName(d.enchantmentLevel));
            }
            w.playSoundEffect(t[0] + 0.5, t[1] + 0.5, t[2] + 0.5, "random.levelup", 0.6F, 1.2F);
            Ledger.act(a, "enchant " + MindSkills.id(s));
            Events.log("crew: " + a.who() + " enchants " + MindSkills.id(s) + " for " + lv + " levels (" + shelves + " shelves): " + b);
            RlEnv.enchanted(a, s, lv);
            detail = "enchanted " + MindSkills.id(s) + " for " + lv + " levels: " + b + "; " + a.xp.level + " levels left";
            return Result.OK;
        }
    }

    // ---- the facts ----------------------------------------------------------------------------------------------

    static void facts(Map<String, Double> r, Crew c, Agent a, WorldServer w) {
        for (String m : RlSpec.ARMOR) r.put("armor_craftable_" + m, craftable(a, m) ? 1.0 : 0.0);
        int[] t = table(a, w, 64);
        r.put("ench_table_known", t != null ? 1.0 : 0.0);
        r.put("ench_table_dist", t == null ? 24.0 : Crew.dist(a, t[0] + 0.5, t[1], t[2] + 0.5));
        int sh = shelves(w, t);
        r.put("bookshelves", (double) sh);
        r.put("shelf_spots", (double) (t == null ? 0 : shelfSpots(w, t)));
        r.put("shelves_total", (double) (sh + Skills.count(a.bag, Item.getItemFromBlock(Blocks.bookshelf), -1)));
        r.put("enchantable", (double) enchantables(a).size());
        int[] d = RlWorld.now.door;
        r.put("door_placed", isDoor(w, d) ? 1.0 : 0.0);
        r.put("gate_open", open(w, d) ? 1.0 : 0.0);
    }
}
