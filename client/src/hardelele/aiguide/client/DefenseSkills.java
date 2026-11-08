package hardelele.aiguide.client;

import hardelele.aiguide.client.MindSkills.Skill;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.WorldServer;

/**
 * The defence's skills for an agent's mind (a goal of defence): each a crew task of its own small automaton over the
 * body's honest actions (PlaceFrom puts only what the eyes see go, Crew.Mine digs what it reaches, Guard.strike is a
 * player's hit, an arrow is loosed from a bow of the bag and takes an arrow of it), ending OK or with a reason code and
 * words, as MindSkills' do. Between the waves the model picks them; in a fight hold_line and guard_ally decide each tick
 * by Defense.decide (no model): a target, a hit, a shot, a step back from a creeper, a fall back when hurt. Their guard
 * level (Defense.POST_GUARD 82) keeps the mob and creeper reflexes off the body while they hold; a fall and survival
 * still come first. What it takes is reserved on the board: the wall's cells, the moat's blocks, the posts.
 */
final class DefenseSkills {
    private DefenseSkills() { }

    static boolean isDefense(String tool) {
        for (String s : Mind.DEFENSE) if (s.equals(tool)) return true;
        return false;
    }

    /** the agent's mind is on a fight of the defence now (hold_line, guard_ally) */
    static boolean fighting(Agent a) {
        AgentMind m = AgentMind.minds.get(a.skin());
        return m != null && m.skill instanceof Fighter && m.skill.result == null;
    }

    static MindSkills.Skill make(Crew c, Agent a, WorldServer w, Mind.Call call) {
        DefenseWorld.check(w);
        switch (call.tool) {
            case "build_wall": return buildWall(c, a, w, call.arg("shape"), call.arg("height").isEmpty() ? 2 : call.num("height", 2), call.arg("material"), call.arg("gate"));
            case "repair_wall": return repair(c, a, w);
            case "dig_moat": return moat(c, a, w, MindSkills.clamp(call.num("depth", 1), 1, 2));
            case "light_area": return light(c, a, w, MindSkills.clamp(call.num("radius", 12), 2, Defense.MAX_RADIUS));
            case "make_weapon": return weapon(c, a, w, call.arg("type"), call.num("count", 0));
            case "equip": return equip(a, call.arg("what"));
            case "take_post": return takePost(c, a, w, call.arg("where"));
            case "hold_line": return hold(c, a, w, MindSkills.clamp(call.num("radius", Defense.LEASH), 1, 8), MindSkills.clamp(call.num("seconds", 120), 5, 600));
            case "guard_ally": return guardAlly(c, a, w, call.arg("ally"), MindSkills.clamp(call.num("seconds", 120), 5, 600));
            case "retreat": return retreat(c, a, w, call.arg("where"));
            case "eat": case "heal": return eat(a, MindSkills.clamp(call.num("count", 64), 1, 64));
            default: return MindSkills.failed("UNKNOWN_TOOL", call.tool);
        }
    }

    // ---- the wall: build, repair; torches (one automaton: cells to put a block of the bag into) -------------------

    static Skill buildWall(Crew c, Agent a, WorldServer w, String shape, int height, String material, String gate) {
        if (height < 1 || height > Defense.MAX_HEIGHT) return MindSkills.failed("BAD_ARG", "height 1-" + Defense.MAX_HEIGHT);
        int[] sh = Defense.shape(shape, 8);
        if (sh == null) return MindSkills.failed("BAD_ARG", "shape '" + shape + "': 'around base' (with a radius: 'around base 8') or 'line x1 z1 x2 z2'");
        Map<String, Integer> bag = MindSkills.bag(a);
        String mat = Defense.material(material, bag);
        if (mat == null) return MindSkills.failed("NO_ITEMS", "no blocks for a wall in the bag (cobblestone, planks, dirt, logs...): mine or chop first");
        Object[] it = MindSkills.item(mat);
        if (it == null) return MindSkills.failed("UNKNOWN_ITEM", "'" + material + "': a block's game id (cobblestone, planks, dirt...)");
        Item item = (Item) it[0];
        if (!(item instanceof ItemBlock) || !Block.getBlockFromItem(item).isOpaqueCube() || Block.getBlockFromItem(item) == Blocks.sand || Block.getBlockFromItem(item) == Blocks.gravel)
            return MindSkills.failed("BAD_ARG", mat + " is no wall block (a full block that does not fall: cobblestone, planks, dirt, logs...)");
        Defense.Perimeter p;
        if (sh[0] == 0) {
            int[] o = DefenseWorld.centre(c, w);
            if (o == null) return MindSkills.failed("NO_BASE", "the crew has no base yet (it is where the crew was called): a wall round it cannot be planned; build a line instead");
            int r = MindSkills.clamp(sh[1], 3, Defense.MAX_RADIUS);
            Defense.Perimeter old = DefenseWorld.ring;
            String g = Defense.gateSide(gate);
            if (old != null && old.r == r && old.height == height && (g.isEmpty() || g.equals(old.gate)) && old.cx == o[0] && old.cz == o[2]) p = old;
            else {
                p = Defense.ring(o[0], o[1], o[2], r, height, g.isEmpty() ? (old != null ? old.gate : "north") : g, DefenseWorld.floor(w, o[1]));
                DefenseWorld.ring = p;
                DefenseWorld.moat = null;
                DefenseWorld.planned();
                Events.log("defense: " + a.who() + " plans the wall: " + p.describe() + ", " + p.cells.size() + " cells");
            }
        } else {
            int y0 = (int) Math.floor(a.posY);
            p = Defense.line(sh[1], sh[2], sh[3], sh[4], height, DefenseWorld.floor(w, y0));
            if (p.cells.isEmpty()) return MindSkills.failed("BAD_ARG", "no ground for a line from " + sh[1] + " " + sh[2] + " to " + sh[3] + " " + sh[4]);
            if (Math.hypot(sh[1] - a.posX, sh[2] - a.posZ) > 48) return MindSkills.failed("BAD_ARG", "the line is too far from here (48 blocks at most)");
            DefenseWorld.lines.add(p);
            DefenseWorld.planned();
        }
        if (p.material.isEmpty()) p.material = mat;
        List<int[]> open = new ArrayList<int[]>();
        for (int[] k : p.cells) if (DefenseWorld.open(w, k[0], k[1], k[2])) open.add(k);
        if (open.isEmpty()) return MindSkills.failed("DONE_ALREADY", "the wall stands: all " + p.cells.size() + " cells (" + p.describe() + ")");
        Set<Long> avoid = new HashSet<Long>();
        for (int[] k : p.cells) avoid.add(Crew.key(k[0], k[1], k[2]));
        for (int[] k : p.gateCells) avoid.add(Crew.key(k[0], k[1], k[2]));
        return put(c, a, w, "build", open, item, mat, avoid, p.cells.size());
    }

    static Skill repair(Crew c, Agent a, WorldServer w) {
        if (DefenseWorld.walls().isEmpty()) return MindSkills.failed("NO_PERIMETER", "no wall is planned: build_wall first");
        List<int[]> h = DefenseWorld.holes(w);
        if (h.isEmpty()) return MindSkills.failed("NO_HOLES", "the wall is whole");
        Map<String, Integer> bag = MindSkills.bag(a);
        String mat = null;
        for (Defense.Perimeter p : DefenseWorld.walls()) if (!p.material.isEmpty() && Defense.count(bag, p.material) > 0) mat = p.material;   // its own material first
        if (mat == null) mat = Defense.material("any", bag);
        Object[] it = mat == null ? null : MindSkills.item(mat);
        if (it == null) return MindSkills.failed("NO_ITEMS", h.size() + " holes and no block for them in the bag (cobblestone, planks, dirt...)");
        Set<Long> avoid = new HashSet<Long>();
        for (Defense.Perimeter p : DefenseWorld.walls()) {
            for (int[] k : p.cells) avoid.add(Crew.key(k[0], k[1], k[2]));
            for (int[] k : p.gateCells) avoid.add(Crew.key(k[0], k[1], k[2]));
        }
        int total = 0;
        for (Defense.Perimeter p : DefenseWorld.walls()) total += p.cells.size();
        return put(c, a, w, "repair", h, (Item) it[0], mat, avoid, total);
    }

    static Skill light(Crew c, Agent a, WorldServer w, int radius) {
        Item torch = Item.getItemFromBlock(Blocks.torch);
        int have = Skills.count(a.bag, torch, -1);
        if (have == 0) return MindSkills.failed("NO_TORCHES", "no torch in the bag (torch: coal or charcoal and a stick make 4)");
        int[] o = DefenseWorld.centre(c, w);
        if (o == null) o = Ground.feet(w, a.posX, a.posY, a.posZ);
        List<int[]> spots = new ArrayList<int[]>();
        for (int[] s : Defense.lights(DefenseWorld.ring, o[0], o[2], radius, 5, DefenseWorld.floor(w, o[1])))
            if (Crew.soft(w, s[0], s[1], s[2]) && Crew.solid(w, s[0], s[1] - 1, s[2]) && w.getSavedLightValue(EnumSkyBlock.Block, s[0], s[1], s[2]) < 8 && !c.board.reserved(a, s[0], s[1], s[2]))
                spots.add(s);
        if (spots.isEmpty()) return MindSkills.failed("NO_SPOT", "the ground within " + radius + " of the base is lit enough already (or there is no ground to put torches on)");
        return put(c, a, w, "light", spots, torch, "torch", null, spots.size());
    }

    /** cells to put a block of the bag in, nearest first (bottom first for a wall), up to 24 a call, those others reserved left out */
    static Skill put(Crew c, Agent a, WorldServer w, String kind, List<int[]> open, Item item, String mat, Set<Long> avoid, int total) {
        List<int[]> mine = new ArrayList<int[]>();
        Set<String> by = new HashSet<String>();
        for (int[] k : open) {
            if (c.board.reserved(a, k[0], k[1], k[2])) { Object o = c.board.blocks.get(Crew.key(k[0], k[1], k[2])); if (o instanceof Doer) by.add(((Doer) o).who()); continue; }
            mine.add(k);
        }
        if (mine.isEmpty()) return MindSkills.failed("ALL_TAKEN", "the " + open.size() + " cells left are reserved by " + String.join(", ", by) + ": take another part of the work");
        int have = Skills.count(a.bag, item, -1);
        if (have == 0) return MindSkills.failed("NO_ITEMS", "no " + mat + " in the bag; the " + kind + " takes " + mine.size() + " more");
        final Agent me = a;
        Collections.sort(mine, (p, q) -> !kind.equals("light") && p[1] != q[1] ? p[1] - q[1] : Double.compare(Crew.dist(me, p[0] + 0.5, p[1], p[2] + 0.5), Crew.dist(me, q[0] + 0.5, q[1], q[2] + 0.5)));
        List<int[]> pick = new ArrayList<int[]>(mine.subList(0, Math.min(Math.min(24, have), mine.size())));
        for (int[] k : pick) c.board.reserve(a, k[0], k[1], k[2]);
        Put k = new Put(c, kind, pick, item, mat, avoid, total, open.size());
        k.lease = Budget.ticks(Crew.dist(a, pick.get(0)[0], pick.get(0)[1], pick.get(0)[2]) * 1.5 + pick.size() * 3, 0, pick.size() * 2, 0, 0, 60);
        return k;
    }

    /** puts a block into each of its cells by PlaceFrom (seen, in reach; a pillar off the plan's cells), the next when one is done or cannot be */
    static final class Put extends Skill {
        final Crew c;
        final String kind, mat;
        final List<int[]> cells;
        final Item item;
        final Set<Long> avoid;
        final int total, openBefore;
        int i, put, failed;
        boolean outOf;
        Skills.PlaceFrom cur;
        String lastWhy = "", lastCode = "";

        Put(Crew c, String kind, List<int[]> cells, Item item, String mat, Set<Long> avoid, int total, int openBefore) {
            this.c = c; this.kind = kind; this.cells = cells; this.item = item; this.mat = mat; this.avoid = avoid; this.total = total; this.openBefore = openBefore;
        }

        Result run(Agent a, WorldServer w) {
            while (i < cells.size()) {
                int[] k = cells.get(i);
                if (cur == null) {
                    if (!Crew.soft(w, k[0], k[1], k[2])) { i++; continue; }   // filled meanwhile
                    if (Skills.count(a.bag, item, -1) == 0) { outOf = true; break; }
                    if (Skills.supports(w, k[0], k[1], k[2], false).isEmpty()) { i++; failed++; lastCode = "BLOCKED"; lastWhy = "nothing to put it against at " + k[0] + " " + k[1] + " " + k[2]; continue; }
                    cur = new Skills.PlaceFrom(k[0], k[1], k[2], item, -1);
                    if (avoid != null) cur.avoid(avoid);
                }
                Result r = cur.step(a, w);
                if (r == Result.RUNNING) return r;
                cur = null;
                i++;
                if (r == Result.OK) {
                    put++;
                    if (kind.equals("build")) Defense.of(a.who()).wallBuilt++;
                    else if (kind.equals("repair")) Defense.of(a.who()).wallRepaired++;
                    else if (kind.equals("light")) Defense.of(a.who()).torches++;
                } else if (r == Result.NO_ITEMS) { outOf = true; break; }
                else if (r != Result.GONE) {
                    failed++;
                    lastCode = r.name();
                    lastWhy = a.why != null ? a.why : r + " at " + k[0] + " " + k[1] + " " + k[2];
                    a.why = null;
                    if (failed >= 6 && put == 0) break;   // nothing goes from here: the mind decides
                }
                return Result.RUNNING;
            }
            String what = kind.equals("light") ? "torches" : "blocks of the wall";
            if (put == 0) {
                if (outOf) { reason = "NO_ITEMS"; detail = "out of " + mat + " before a single one was put"; return Result.NO_ITEMS; }
                reason = lastCode.isEmpty() ? "BLOCKED" : lastCode;
                detail = "no " + what + " could be put (" + failed + " tried; last: " + lastWhy + ")";
                return Result.BLOCKED;
            }
            int stand = 0;
            if (!kind.equals("light")) for (Defense.Perimeter p : DefenseWorld.walls()) stand += DefenseWorld.standing(w, p);
            detail = "put " + put + " " + what + (kind.equals("light") ? "" : "; " + stand + " of " + total + " cells stand now")
                    + (failed > 0 ? "; " + failed + " not put (last: " + lastWhy + ")" : "") + (outOf ? "; out of " + mat : "")
                    + (!kind.equals("light") && stand < total ? ": call again for the rest" : "");
            return Result.OK;
        }
    }

    // ---- the moat --------------------------------------------------------------------------------------------

    static Skill moat(Crew c, Agent a, WorldServer w, int depth) {
        Defense.Perimeter p = DefenseWorld.ring;
        if (p == null) return MindSkills.failed("NO_PERIMETER", "no ring wall planned: build_wall around base first (the moat goes round it)");
        if (DefenseWorld.moat == null) DefenseWorld.moat = Defense.moat(p, 2, DefenseWorld.floor(w, p.cy));   // planned once, from the ground before any digging
        List<int[]> todo = new ArrayList<int[]>();
        int left = 0;
        for (int[] m : DefenseWorld.moat) {
            if (m[3] > depth || DefenseWorld.open(w, m[0], m[1], m[2]) || Ground.liquid(w, m[0], m[1], m[2])) continue;
            left++;
            if (c.board.reserved(a, m[0], m[1], m[2]) || Reacts.standsOn(a, w, m[0], m[1], m[2]) || !Ground.mayDig(c, m[0], m[1], m[2], "gather")) continue;
            todo.add(m);
        }
        if (left == 0) return MindSkills.failed("DONE_ALREADY", "the moat is dug to depth " + depth + " all round");
        if (todo.isEmpty()) return MindSkills.failed("ALL_TAKEN", "the " + left + " blocks left of the moat are reserved by others or under someone's feet");
        final Agent me = a;
        Collections.sort(todo, (u, v) -> u[3] != v[3] ? u[3] - v[3] : Double.compare(Crew.dist(me, u[0] + 0.5, u[1], u[2] + 0.5), Crew.dist(me, v[0] + 0.5, v[1], v[2] + 0.5)));
        List<int[]> pick = new ArrayList<int[]>();
        for (int[] m : todo) {
            if (pick.size() >= 16) break;
            if (!pick.isEmpty() && Math.hypot(m[0] - pick.get(0)[0], m[2] - pick.get(0)[2]) > 8) continue;   // a stretch of it, not all round at once
            pick.add(new int[] { m[0], m[1], m[2] });
        }
        for (int[] m : pick) c.board.reserve(a, m[0], m[1], m[2]);
        final int n = pick.size();
        MindSkills.Skill s = MindSkills.wrap(new TaskBoard.Seq(new Crew.Mine(pick), new Crew.Collect(pick.get(0)[0] + 0.5, pick.get(0)[1], pick.get(0)[2] + 0.5, 6)), r -> {
            if (r == Result.NO_TOOL) return new String[] { "NO_TOOL", "the ground there needs a tool (stone: a pickaxe)" };
            if (r == Result.BLOCKED) return new String[] { "BLOCKED", "could not get at any of the " + n + " blocks picked" };
            return null;
        });
        s.detail = "dug a stretch of " + n + " blocks of the moat (" + (left - n) + " left at depth " + depth + ")";
        s.lease = Budget.ticks(Crew.dist(a, pick.get(0)[0], pick.get(0)[1], pick.get(0)[2]) * 1.5 + n * 3, n * 2, 0, 0, 0, 60);
        return s;
    }

    // ---- weapons and armour ----------------------------------------------------------------------------------

    static Skill weapon(Crew c, Agent a, WorldServer w, String type, int count) {
        String id = Defense.weapon(type, MindSkills.bag(a));
        if (id == null) return MindSkills.failed("UNKNOWN_WEAPON", "'" + type + "': sword, bow or arrows");
        return MindSkills.craft(c, a, w, id, MindSkills.clamp(count > 0 ? count : id.equals("arrow") ? 4 : 1, 1, 64));
    }

    static Skill equip(Agent a, String what) {
        String t = what == null || what.trim().isEmpty() ? "best" : what.trim().toLowerCase(java.util.Locale.ROOT);
        boolean weapon = t.startsWith("weapon") || t.startsWith("best") || t.startsWith("РѕСЂСѓР¶") || t.endsWith("sword") || t.equals("bow"),
                armor = t.startsWith("armo") || t.startsWith("best") || t.startsWith("Р±СЂРѕРЅ");
        if (!weapon && !armor) return MindSkills.failed("BAD_ARG", "'" + what + "': weapon, armor or best");
        final ItemStack wp = weapon ? (t.equals("bow") ? bow(a) : a.bestWeapon()) : null;
        final List<ItemStack> wear = new ArrayList<ItemStack>();   // the bag's pieces better than what it wears (index: the slot - 1)
        if (armor) {
            for (int slot = 1; slot <= 4; slot++) {   // the creature's slots: 1 boots .. 4 helmet (an armour's type 3 .. 0)
                ItemStack best = null;
                ItemStack on = a.getEquipmentInSlot(slot);
                int bestV = on != null && on.getItem() instanceof ItemArmor ? ((ItemArmor) on.getItem()).damageReduceAmount : 0;
                for (ItemStack s : a.bag)
                    if (s.getItem() instanceof ItemArmor && 4 - ((ItemArmor) s.getItem()).armorType == slot && ((ItemArmor) s.getItem()).damageReduceAmount > bestV) { best = s; bestV = ((ItemArmor) s.getItem()).damageReduceAmount; }
                if (best != null) wear.add(best);
            }
        }
        if (wp == null && wear.isEmpty()) {
            if (weapon && !armor) return MindSkills.failed("NO_WEAPON", t.equals("bow") ? "no bow in the bag (bow: 3 sticks, 3 string)" : "no sword, axe or pickaxe in the bag: make_weapon sword");
            if (armor && !weapon) return MindSkills.failed("NO_ARMOR", "no armour in the bag better than what it wears (leather, iron...: craft it)");
            return MindSkills.failed("NO_WEAPON", "no weapon and no armour in the bag: make_weapon sword");
        }
        Skill s = new Skill() {
            int ticks;
            Result run(Agent ag, WorldServer ww) {
                if (ticks++ == 0) {
                    StringBuilder b = new StringBuilder();
                    if (wp != null && ag.bag.contains(wp)) { ag.body.hold(wp); b.append("holds ").append(MindSkills.id(wp)).append(" (damage ").append(Math.round(Agent.damageOf(wp))).append(')'); }
                    for (ItemStack p : wear) {
                        if (!ag.bag.contains(p)) continue;
                        int slot = 4 - ((ItemArmor) p.getItem()).armorType;
                        ItemStack was = ag.getEquipmentInSlot(slot);
                        ag.bag.remove(p);   // worn, out of the bag (Agent.onDeath puts it back to fall with the bag)
                        ag.setCurrentItemOrArmor(slot, p);
                        if (was != null) ag.bag.add(was);
                        b.append(b.length() > 0 ? ", " : "").append("wears ").append(MindSkills.id(p));
                    }
                    ag.swingItem();
                    detail = b.toString();
                }
                return ticks >= 10 ? Result.OK : Result.RUNNING;
            }
        };
        s.lease = 20 * 30;
        return s;
    }

    static ItemStack bow(Agent a) {
        for (ItemStack s : a.bag) if (s.getItem() == Items.bow) return s;
        return null;
    }

    // ---- posts -----------------------------------------------------------------------------------------------

    static List<Defense.Defender> defenders(Crew c, Agent me) {
        List<Defense.Defender> l = new ArrayList<Defense.Defender>();
        for (Agent g : c.agents)
            if (!g.isDead && (g == me || AgentMind.defends(g)))
                l.add(new Defense.Defender(g.who(), g.posX, g.posZ, g.weapon(), bow(g) != null && Skills.count(g.bag, Items.arrow, -1) > 0 && g.weapon() < 4, g.getHealth()));
        return l;
    }

    static Skill takePost(Crew c, Agent a, WorldServer w, String where) {
        String t = where == null || where.trim().isEmpty() ? "auto" : where.trim().toLowerCase(java.util.Locale.ROOT);
        int[] at = MindSkills.coords(t);
        String key;
        if (at != null) key = at[0] + " " + at[1] + " " + at[2];
        else {
            Defense.Perimeter p = DefenseWorld.ring;
            if (p == null || p.posts.isEmpty()) return MindSkills.failed("NO_PERIMETER", "no ring wall planned, so no posts: build_wall around base first, or give 'x y z'");
            if (t.equals("auto") || t.equals("free") || t.equals("any")) {
                Map<String, String> held = new HashMap<String, String>();
                for (Map.Entry<String, Doer> e : c.board.posts.entrySet()) if (e.getValue().alive()) held.put(e.getKey(), e.getValue().who());
                key = Defense.postFor(p, a.who(), defenders(c, a), held);
                if (key == null) return MindSkills.failed("POST_TAKEN", "every post is held (" + held + ")");
            } else {
                key = t;
                if (p.post(key) == null) return MindSkills.failed("UNKNOWN_POST", "'" + where + "': the posts are " + String.join(", ", p.postNames) + ", or 'x y z'");
            }
            at = p.post(key);
        }
        Doer by = c.board.postHolder(key);
        if (by != null && by != a) return MindSkills.failed("POST_TAKEN", key + " is held by " + by.who() + ": take another (auto)");
        c.board.takePost(key, a);
        final String k = key;
        final int[] cell = at;
        Skill s = new Skill() {
            Crew.GoTo walk = new Crew.GoTo(cell[0] + 0.5, cell[1], cell[2] + 0.5, 0.9);
            Result run(Agent ag, WorldServer ww) {
                Result r = walk.step(ag, ww);
                if (r == Result.RUNNING) return r;
                if (r != Result.OK && Crew.dist(ag, cell[0] + 0.5, cell[1], cell[2] + 0.5) > 2.5) {
                    c.board.leavePost(ag);
                    reason = "NO_PATH";
                    detail = "no way to the post " + k + " at " + cell[0] + " " + cell[1] + " " + cell[2] + " (let go)";
                    return Result.NO_PATH;
                }
                int[] o = DefenseWorld.centre(c, ww);
                detail = "at the post " + k + " (" + cell[0] + " " + cell[1] + " " + cell[2] + ")" + (o != null ? ", facing " + Defense.side(cell[0] + 0.5, cell[2] + 0.5, o[0] + 0.5, o[2] + 0.5) : "");
                return Result.OK;
            }
        };
        s.lease = Budget.ticks(Crew.dist(a, at[0] + 0.5, at[1], at[2] + 0.5) * 1.5, 0, 0, 0, 0, 20);
        return s;
    }

    static Skill retreat(Crew c, Agent a, WorldServer w, String where) {
        int[] o = DefenseWorld.centre(c, w);
        if (o == null) return MindSkills.failed("NO_HOME", "the crew has no base to fall back to");
        String t = where == null ? "" : where.trim().toLowerCase(java.util.Locale.ROOT);
        double[] to = { o[0] + 0.5, o[1], o[2] + 0.5 };
        Defense.Perimeter p = DefenseWorld.ring;
        if ((t.startsWith("wall") || t.startsWith("СЃС‚РµРЅ")) && p != null) {   // just behind the wall on its own side, not the middle
            double[] q = { a.posX, o[1], a.posZ };
            double lim = p.r - 2.0;
            q[0] = Math.max(p.cx + 0.5 - lim, Math.min(p.cx + 0.5 + lim, q[0]));
            q[2] = Math.max(p.cz + 0.5 - lim, Math.min(p.cz + 0.5 + lim, q[2]));
            int gy = Crew.ground(w, (int) Math.floor(q[0]), o[1], (int) Math.floor(q[2]));
            if (gy >= 0) to = new double[] { Math.floor(q[0]) + 0.5, gy, Math.floor(q[2]) + 0.5 };
        }
        final double[] dest = to;
        Defense.of(a.who()).retreats++;
        Ledger.act(a, "fall back");
        Skill s = MindSkills.wrap(new Crew.GoTo(dest[0], dest[1], dest[2], 1.5), r -> r == Result.NO_PATH ? new String[] { "NO_PATH", "no way inside from here" } : null);
        s.detail = "inside at " + (int) Math.floor(dest[0]) + " " + (int) dest[1] + " " + (int) Math.floor(dest[2]);
        s.lease = Budget.ticks(Crew.dist(a, dest[0], dest[1], dest[2]) * 1.5, 0, 0, 0, 0, 20);
        return s;
    }

    // ---- food --------------------------------------------------------------------------------------------------

    /**
     * Eats from the bag as a player does (the food in the hand, 1.6 s a bite), until healed or out of food. The agents
     * have no hunger (they count as fed): a bite heals them by its food points, the game's number for it.
     */
    static Skill eat(Agent a, final int most) {
        if (a.getHealth() >= a.getMaxHealth()) return MindSkills.failed("FULL_HEALTH", "health " + Math.round(a.getHealth()) + " of " + Math.round(a.getMaxHealth()) + ": nothing to heal");
        if (food(a) == null) return MindSkills.failed("NO_FOOD", "no food in the bag (bread, apples, cooked meat; raw meat is cooked in a furnace: smelt)");
        Skill s = new Skill() {
            int ticks, eaten;
            float from = -1;
            Result run(Agent ag, WorldServer w) {
                if (from < 0) from = ag.getHealth();
                ItemStack f = food(ag);
                if (f == null || eaten >= most || ag.getHealth() >= ag.getMaxHealth()) return end(ag);
                ag.body.stop();
                ag.body.hold(f);
                if (++ticks % 4 == 0) { ag.swingItem(); w.playSoundEffect(ag.posX, ag.posY + 1, ag.posZ, "random.eat", 0.5F, 1.0F); }
                if (ticks < 32) return Result.RUNNING;
                ticks = 0;
                int heal = ((ItemFood) f.getItem()).getHealAmount(f);
                Item it = f.getItem();
                Skills.take(ag, it, f.getMetadata(), 1, Ledger.Way.INGREDIENT);
                if (it == Items.mushroom_stew) Skills.put(ag, new ItemStack(Items.bowl), Ledger.Way.CRAFTED);
                ag.heal(heal);
                eaten++;
                Defense.of(ag.who()).eaten++;
                w.playSoundEffect(ag.posX, ag.posY + 1, ag.posZ, "random.burp", 0.5F, 1.0F);
                Ledger.act(ag, "eat " + MindSkills.id(it, 0));
                return Result.RUNNING;
            }
            Result end(Agent ag) {
                if (eaten == 0) { reason = "NO_FOOD"; detail = "no food left in the bag"; return Result.NO_ITEMS; }
                detail = "ate " + eaten + ", health " + Math.round(from) + " -> " + Math.round(ag.getHealth());
                ag.body.hold(ag.bestWeapon());
                return Result.OK;
            }
        };
        s.lease = 20 * 60;
        return s;
    }

    /** the food of the bag that heals most (null: none) */
    static ItemStack food(Agent a) {
        ItemStack best = null;
        for (ItemStack s : a.bag)
            if (s.getItem() instanceof ItemFood && s.getItem() != Items.rotten_flesh && s.getItem() != Items.spider_eye && s.getItem() != Items.poisonous_potato
                    && (best == null || ((ItemFood) s.getItem()).getHealAmount(s) > ((ItemFood) best.getItem()).getHealAmount(best))) best = s;
        return best;
    }

    // ---- the fight: hold the line at a post, keep by an ally -------------------------------------------------

    static Skill hold(Crew c, Agent a, WorldServer w, int radius, int seconds) {
        String key = c.board.postOf(a);
        int[] p = key == null ? null : DefenseWorld.postAt(key);
        Hold h = new Hold(c, a, p == null ? Ground.feet(w, a.posX, a.posY, a.posZ) : p, key, radius, seconds);
        if (p == null) h.note = "no post: it holds where it stands; ";
        return h;
    }

    static Skill guardAlly(Crew c, Agent a, WorldServer w, String ally, int seconds) {
        String t = ally == null ? "" : ally.trim().toLowerCase(java.util.Locale.ROOT);
        Doer d = null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(t);
        if (t.startsWith("player") || t.startsWith("РёРіСЂРѕРє") || t.equals("me")) d = c.doer(1);
        else if (m.find()) d = c.doer(Integer.parseInt(m.group(1)));
        else return MindSkills.failed("BAD_ARG", "'" + ally + "': agent N, or player");
        if (d == null || !d.alive()) return MindSkills.failed("GONE", "no " + ally + " in the crew now");
        if (d == a) return MindSkills.failed("BAD_ARG", "that is you: hold_line instead");
        return new Escort(c, a, d, seconds);
    }

    /**
     * A fight of the defence, tick by tick (no model): the enemies near its centre (a post, an ally) that it sees, the
     * choice of Defense.decide, done by the body: a hit (Guard.strike), an arrow, a step at it or away from a creeper,
     * back to the centre; under LOW health a fall back inside, the step ending LOW_HEALTH. Its guard level keeps the mob
     * and creeper reflexes off it. Kills and the damage of its arrows go to the tally when they are seen to land.
     */
    abstract static class Fighter extends Skill {
        final Crew c;
        final int len;
        double leash;
        int t, cool, draw, quiet, backTicks;
        boolean retreating;
        Crew.GoTo back;
        String note = "";
        List<EntityLiving> ents = new ArrayList<EntityLiving>();
        final Map<EntityLiving, float[]> struck = new HashMap<EntityLiving, float[]>();   // mob -> { tick of its last hit or shot, health then, a shot's still to be counted }
        Defense.Tally start;

        Fighter(Crew c, int seconds, double leash) { this.c = c; this.len = seconds * 20; this.leash = leash; lease = len + 20 * 40; }

        /** the centre it fights round (x y z), null when it is gone */
        abstract double[] centre(Agent a, WorldServer w);
        abstract String where();

        Result run(Agent a, WorldServer w) {
            long now = w.getTotalWorldTime();
            if (start == null) {
                start = Defense.of(a.who()).copy();
                if (a.getHealth() < Defense.LOW) {   // too hurt to begin: said at once (it used to walk back inside and fail LOW_HEALTH every time)
                    reason = "LOW_HEALTH";
                    detail = note + "health " + Math.round(a.getHealth()) + " is under " + Math.round(Defense.LOW) + ": not fit to fight; eat or retreat first (it heals 1 every 4 s)";
                    return Result.BLOCKED;
                }
            }
            if (interrupt != null) { a.body.stop(); reason = "INTERRUPTED"; detail = note + interrupt + " after " + t / 20 + " s; " + tally(a); return Result.BLOCKED; }
            double[] post = centre(a, w);
            if (post == null) { reason = "GONE"; detail = note + where() + " is gone; " + tally(a); return Result.GONE; }
            a.guarded(Defense.POST_GUARD);
            account(a, now);
            if (retreating) {
                Result r = back.step(a, w);
                if (r == Result.RUNNING && ++backTicks < 20 * 25) return r;
                reason = "LOW_HEALTH";
                detail = note + "fell back inside at health " + Math.round(a.getHealth()) + "; " + tally(a);
                return Result.BLOCKED;
            }
            if (++t > len) { detail = note + "held " + where() + " " + len / 20 + " s; " + tally(a); return Result.OK; }
            if (cool > 0) cool--;
            if (t % 4 == 1) ents = foes(a, w, post);
            Map<Integer, EntityLiving> byId = new HashMap<Integer, EntityLiving>();
            List<Defense.Foe> foes = new ArrayList<Defense.Foe>();
            for (EntityLiving e : ents) {
                if (e.isDead || e.getHealth() <= 0) continue;
                Defense.Foe f = new Defense.Foe(e.getEntityId(), Cases.name(e), e.posX, e.posY, e.posZ, e.getHealth());
                if (e instanceof EntityCreeper) f.fuse = ((EntityCreeper) e).getCreeperState();
                EntityLivingBase at = e.getAttackTarget();
                if (at == null && e instanceof EntityCreature && ((EntityCreature) e).getEntityToAttack() instanceof EntityLivingBase) at = (EntityLivingBase) ((EntityCreature) e).getEntityToAttack();
                if (at instanceof Agent) f.targets = ((Agent) at).who();
                else if (at != null) f.targets = at.getCommandSenderName();
                foes.add(f);
                byId.put(f.id, e);
            }
            Defense.Wave v = DefenseWorld.now;
            if (foes.isEmpty()) {
                quiet++;
                if (quiet > 100 && v != null && v.known && !v.on) { detail = note + "the wave is over; " + tally(a); return Result.OK; }
                if (quiet > 600 && (v == null || !v.known)) { detail = note + "no enemy for 30 s; " + tally(a); return Result.OK; }
            } else quiet = 0;
            Defense.Me me = new Defense.Me();
            me.name = a.who();
            me.x = a.posX; me.y = a.posY; me.z = a.posZ;
            me.health = a.getHealth();
            me.weapon = a.weapon();
            me.bow = bow(a) != null && Skills.count(a.bag, Items.arrow, -1) > 0;
            me.post = post;
            me.leash = leash;
            List<Defense.Ally> allies = new ArrayList<Defense.Ally>();
            for (Agent g : c.agents) if (g != a && !g.isDead) allies.add(new Defense.Ally(g.who(), g.posX, g.posZ, g.getHealth()));
            int[] o = DefenseWorld.centre(c, w);
            double bx = o != null ? o[0] + 0.5 : post[0], bz = o != null ? o[2] + 0.5 : post[2];
            Defense.Order ord = Defense.decide(me, foes, allies, ringFor(post), bx, bz);
            EntityLiving tg = ord.target == null ? null : byId.get(ord.target.id);
            if (!ord.act.equals("SHOOT")) draw = 0;
            switch (ord.act) {
                case "RETREAT":
                    retreating = true;
                    Defense.of(a.who()).retreats++;
                    Ledger.act(a, "fall back");
                    Events.log("crew: " + a.who() + " falls back inside (health " + Math.round(a.getHealth()) + ")");
                    back = new Crew.GoTo(ord.move[0], o != null ? o[1] : a.posY, ord.move[2], 1.5);
                    return Result.RUNNING;
                case "AVOID":
                    if (t % 6 == 1 || a.body.pathless()) a.body.walkTo(ord.move[0], ord.move[1], ord.move[2], 1.25);
                    if (tg != null) Crew.face(a, tg.posX, tg.posY + 1, tg.posZ);
                    return Result.RUNNING;
                case "ATTACK":
                    holdWeapon(a);
                    a.body.stop();
                    Crew.face(a, tg.posX, tg.posY + tg.getEyeHeight() * 0.8, tg.posZ);
                    a.act((int) Math.floor(tg.posX), (int) Math.floor(tg.posY), (int) Math.floor(tg.posZ), 4);
                    if (cool == 0 && a.canEntityBeSeen(tg) && a.body.facing(tg.posX, tg.posY + tg.getEyeHeight() * 0.8, tg.posZ, 25)) {
                        cool = 11;
                        struck.put(tg, new float[] { now, tg.getHealth(), 0 });
                        Guard.strike(a, w, tg);
                    }
                    return Result.RUNNING;
                case "SHOOT": {
                    ItemStack b = bow(a);
                    a.body.hold(b);
                    a.body.stop();
                    Crew.face(a, tg.posX, tg.posY + tg.getEyeHeight(), tg.posZ);
                    a.act((int) Math.floor(tg.posX), (int) Math.floor(tg.posY), (int) Math.floor(tg.posZ), 4);
                    if (!a.canEntityBeSeen(tg)) { draw = 0; return Result.RUNNING; }
                    if (++draw >= 20 && a.body.facing(tg.posX, tg.posY + tg.getEyeHeight(), tg.posZ, 15)) {   // a full draw, as a player's
                        draw = 0;
                        if (loose(a, w, tg)) struck.put(tg, new float[] { now, tg.getHealth(), 1 });
                    }
                    return Result.RUNNING;
                }
                case "CLOSE":
                    holdWeapon(a);
                    if (t % 8 == 1 || a.body.pathless()) a.body.walkTo(ord.move[0], ord.move[1], ord.move[2], 1.15);
                    if (tg != null) Crew.face(a, tg.posX, tg.posY + tg.getEyeHeight() * 0.8, tg.posZ);
                    return Result.RUNNING;
                default:   // HOLD: at the point it may be at, looking at the enemy or out from the base
                    double d = Math.hypot(a.posX - ord.move[0], a.posZ - ord.move[2]);
                    if (d > 1.2) { if (t % 10 == 1 || a.body.pathless()) a.body.walkTo(ord.move[0], ord.move[1], ord.move[2], 1.0); }
                    else {
                        a.body.stop();
                        if (tg != null) Crew.face(a, tg.posX, tg.posY + 1.5, tg.posZ);
                        else if (o != null && t % 20 == 1) Crew.face(a, post[0] * 2 - bx, post[1] + 1.5, post[2] * 2 - bz);
                    }
                    return Result.RUNNING;
            }
        }

        /** the ring when the centre is inside it (the fight stays in), else none */
        static Defense.Perimeter ringFor(double[] post) {
            Defense.Perimeter p = DefenseWorld.ring;
            return p != null && p.inside(post[0], post[2]) ? p : null;
        }

        void holdWeapon(Agent a) {
            ItemStack wp = a.bestWeapon();
            if (wp != null && a.getHeldItem() != wp) a.body.hold(wp);
        }

        /** the kills (a mob it struck within 3 s, dead now) and its arrows' damage (the mob's health 1.5 s after a shot) into the tally */
        void account(Agent a, long now) {
            Defense.Tally tl = Defense.of(a.who());
            for (java.util.Iterator<Map.Entry<EntityLiving, float[]>> it = struck.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<EntityLiving, float[]> e = it.next();
                EntityLiving m = e.getKey();
                float[] s = e.getValue();
                boolean dead = m.isDead || m.getHealth() <= 0;
                if (s[2] > 0 && (dead || now - s[0] >= 30)) { tl.dealt += Math.max(0, s[1] - Math.max(0, m.getHealth())); s[2] = 0; s[1] = Math.max(0, m.getHealth()); }
                if (dead) {
                    tl.kills++;
                    Ledger.act(a, "kill " + m.getCommandSenderName());
                    Events.log("crew: " + a.who() + " killed " + m.getCommandSenderName() + " (" + where() + ")");
                    it.remove();
                } else if (now - s[0] > 60 && s[2] == 0) it.remove();
            }
        }

        String tally(Agent a) { return Defense.of(a.who()).since(start).line(); }

        /** the hostile mobs near the centre it sees (or that are close enough to be felt) */
        @SuppressWarnings("unchecked")
        List<EntityLiving> foes(Agent a, WorldServer w, double[] p) {
            List<EntityLiving> out = new ArrayList<EntityLiving>();
            double r = Defense.BOW_RANGE;
            for (Object o : w.getEntitiesWithinAABB(EntityLiving.class, AxisAlignedBB.getBoundingBox(p[0] - r, p[1] - 6, p[2] - r, p[0] + r, p[1] + 6, p[2] + r))) {
                EntityLiving m = (EntityLiving) o;
                if (!Guard.hostile(m)) continue;
                double d = a.getDistanceToEntity(m);
                if (d > 20 || d > 6 && !a.canEntityBeSeen(m)) continue;
                out.add(m);
            }
            return out;
        }
    }

    /** an arrow loosed at the mob from a bow of the bag, an arrow of the bag gone (as a player's full draw); false when there is none */
    static boolean loose(Agent a, WorldServer w, EntityLiving target) {
        ItemStack b = bow(a);
        if (b == null || Skills.take(a, Items.arrow, -1, 1, Ledger.Way.INGREDIENT) == 0) return false;
        EntityArrow ar = new EntityArrow(w, a, target, 3.0F, 1.0F);
        ar.canBePickedUp = 1;
        w.spawnEntityInWorld(ar);
        w.playSoundEffect(a.posX, a.posY + 1.5, a.posZ, "random.bow", 1.0F, 1.0F / (a.getRNG().nextFloat() * 0.4F + 1.2F) + 0.5F);
        a.swingItem();
        b.damageItem(1, a);
        if (b.stackSize <= 0) { a.bag.remove(b); a.body.hold(null); Ledger.flow(Ledger.Way.INGREDIENT, b, "worn out"); }
        Defense.of(a.who()).shots++;
        Ledger.act(a, "shoot " + target.getCommandSenderName());
        return true;
    }

    /** hold_line: at its post (or where it stood), within radius of it, inside the wall */
    static final class Hold extends Fighter {
        final double[] at;
        final String key;
        Hold(Crew c, Agent a, int[] p, String key, int radius, int seconds) {
            super(c, seconds, radius);
            this.at = new double[] { p[0] + 0.5, p[1], p[2] + 0.5 };
            this.key = key;
        }
        double[] centre(Agent a, WorldServer w) { return at; }
        String where() { return key != null ? "the post " + key : "its place"; }
    }

    /** guard_ally: by the ally, fighting what comes at it (inside the wall while the ally is) */
    static final class Escort extends Fighter {
        final Doer ally;
        Escort(Crew c, Agent a, Doer ally, int seconds) { super(c, seconds, 6); this.ally = ally; }
        double[] centre(Agent a, WorldServer w) { return ally.alive() ? new double[] { ally.px(), ally.py(), ally.pz() } : null; }
        String where() { return ally.who(); }
    }
}
