package hardelele.aiguide.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The defence without the game: the wall's plan round the base with its gate and posts, a line, the moat, the
 * torches' spots; the posts shared out with none held twice; the fight's choice of a target and of what to do (a
 * creeper never in melee, a wounded ally's enemy first, inside the wall, a fall back when hurt); the tally; the
 * reflexes' priorities against a post's guard; the mind's tools and checks for a goal of defence; and a stub model
 * between the waves (no weapon: a sword; a hole in the wall: repaired; hurt: a fall back), answering only when the
 * request has what it expects.
 */
public final class DefenseTest {
    static int checks;

    static void ok(boolean b, String what) {
        checks++;
        if (!b) throw new AssertionError(what);
    }

    static final Defense.Floor FLAT = (x, z) -> 64;

    static int cheb(int[] c, int cx, int cz) { return Math.max(Math.abs(c[0] - cx), Math.abs(c[2] - cz)); }

    // ---- the wall's plan ---------------------------------------------------------------------------------------

    static void ring() {
        Defense.Perimeter p = Defense.ring(10, 64, -3, 4, 2, "north", FLAT);
        ok(p.ring && p.r == 4 && p.height == 2 && p.gate.equals("north"), "a ring of radius 4, height 2, gate north");
        ok(p.cells.size() == (8 * 4 - 1) * 2, "31 columns of the ring (the gate's left out) times 2: " + p.cells.size());
        ok(p.gateCells.size() == 2, "the gate open its whole height: " + p.gateCells.size());
        for (int[] g : p.gateCells) ok(g[0] == 10 && g[2] == -7 && !p.isCell(g[0], g[1], g[2]), "the gate in the middle of the north side, not a cell: " + Arrays.toString(g));
        Set<Long> seen = new HashSet<Long>();
        for (int[] c : p.cells) {
            ok(cheb(c, 10, -3) == 4, "every cell on the ring: " + Arrays.toString(c));
            ok(seen.add(Defense.key(c[0], c[1], c[2])), "no cell twice");
        }
        for (int i = 0; i < 31; i++) ok(p.cells.get(i)[1] == 64, "the bottom layer first");
        ok(p.cells.get(31)[1] == 65, "then the second");
        ok(Math.abs(p.cells.get(0)[0] - 10) <= 1 && p.cells.get(0)[2] == -7, "a layer goes round from the gate: " + Arrays.toString(p.cells.get(0)));
        ok(p.posts.size() == 8 && p.postNames.get(0).equals("gate"), "eight posts, the gate's first: " + p.postNames);
        ok(Arrays.equals(p.post("gate"), new int[] { 10, 64, -6 }), "the gate's post one cell in from it: " + Arrays.toString(p.post("gate")));
        ok(!p.postNames.contains("north") && p.postNames.contains("south") && p.postNames.contains("north-east"), "the gate's side has the gate's post only");
        for (int[] q : p.posts) ok(cheb(q, 10, -3) == 3 && p.inside(q[0] + 0.5, q[2] + 0.5), "every post inside, one cell from the wall: " + Arrays.toString(q));
        ok(p.inside(10.5, -2.5) && !p.inside(10.5, -6.9 - 0.2) && !p.inside(14.5, -3), "inside: off the wall's cells");
        ok(p.describe().contains("gate north at 10 64 -7"), "described: " + p.describe());
        // uneven ground: a column over water is left open (a gap), the others follow the ground
        Defense.Floor bumpy = (x, z) -> x == 6 && z == -3 ? -1 : x > 10 ? 65 : 64;
        Defense.Perimeter b = Defense.ring(10, 64, -3, 4, 1, "south", bumpy);
        ok(b.gaps == 1 && b.cells.size() == 31 - 1, "one column with no ground: a gap, no cell: " + b.gaps + " " + b.cells.size());
        for (int[] c : b.cells) ok(c[1] == (c[0] > 10 ? 65 : 64), "the wall stands on its column's ground");
        ok(b.gateCells.get(0)[2] == 1 && b.post("gate")[2] == 0, "a south gate");
    }

    static void line() {
        Defense.Perimeter p = Defense.line(0, 0, 5, 2, 1, FLAT);
        ok(!p.ring && p.cells.size() == 6 && p.posts.isEmpty() && p.gateCells.isEmpty(), "a line: 6 columns, no gate, no posts: " + p.cells.size());
        for (int i = 1; i < p.cells.size(); i++) ok(cheb(p.cells.get(i), p.cells.get(i - 1)[0], p.cells.get(i - 1)[2]) == 1, "the line has no gaps");
        ok(Arrays.equals(p.cells.get(0), new int[] { 0, 64, 0 }) && Arrays.equals(p.cells.get(5), new int[] { 5, 64, 2 }), "from one end to the other");
    }

    static void moatAndLights() {
        Defense.Perimeter p = Defense.ring(0, 64, 0, 4, 2, "east", FLAT);
        List<int[]> m = Defense.moat(p, 2, FLAT);
        ok(m.size() == (8 * 6 - 1) * 2, "the moat two cells out, the gate's way left: " + m.size());
        for (int[] c : m) {
            ok(cheb(c, 0, 0) == 6 && (c[1] == 63 || c[1] == 62) && c[3] == 64 - c[1], "under the ground of the ring at 6: " + Arrays.toString(c));
            ok(!(c[0] == 6 && c[2] == 0), "the gate's way is ground");
        }
        ok(m.get(0)[3] == 1 && m.get(m.size() - 1)[3] == 2, "the top first");
        List<int[]> l = Defense.lights(p, 0, 0, 12, 5, FLAT);
        ok(!l.isEmpty(), "torches' spots: " + l.size());
        for (int[] s : l) {
            ok(!p.isCell(s[0], 64, s[2]) && !p.isCell(s[0], 65, s[2]) && cheb(s, 0, 0) != 6, "no torch on the wall or the moat: " + Arrays.toString(s));
            for (int[] q : p.posts) ok(!(q[0] == s[0] && q[2] == s[2]), "no torch on a post");
        }
    }

    // ---- the posts shared out ----------------------------------------------------------------------------------

    static void posts() {
        Defense.Perimeter p = Defense.ring(0, 64, 0, 6, 2, "north", FLAT);
        List<Defense.Defender> ds = new ArrayList<Defense.Defender>();
        ds.add(new Defense.Defender("Агент 2", 0, 0, 5, false, 20));    // a stone sword
        ds.add(new Defense.Defender("Агент 3", 4, 4, 7, true, 20));     // the best armed, but a bow
        ds.add(new Defense.Defender("Агент 4", -4, 4, 1, false, 20));   // bare hands
        Map<String, String> a = Defense.assign(p, ds, null);
        ok(a.size() == 3 && new HashSet<String>(a.values()).size() == 3, "each its own post: " + a);
        ok("Агент 2".equals(a.get("gate")), "the gate to the best armed in melee (not the archer): " + a);
        ok("south-east".equals(Defense.postFor(p, "Агент 3", ds, null)), "the archer to the free post nearest it: " + a);
        Map<String, String> held = new LinkedHashMap<String, String>();
        held.put("gate", "Агент 4");
        a = Defense.assign(p, ds, held);
        ok("Агент 4".equals(a.get("gate")) && !"gate".equals(Defense.postFor(p, "Агент 2", ds, held)), "a post held stays its holder's: " + a);
        held.put("west", "Агент 9");   // one not among the defenders (dead, gone): its post is free again
        a = Defense.assign(p, ds, held);
        ok(!a.containsValue("Агент 9"), "a post of one gone is free: " + a);
        // more defenders than posts
        List<Defense.Defender> many = new ArrayList<Defense.Defender>();
        for (int i = 0; i < 12; i++) many.add(new Defense.Defender("A" + i, i, 0, 1 + i % 3, false, 20));
        a = Defense.assign(p, many, null);
        ok(a.size() == p.posts.size() && new HashSet<String>(a.values()).size() == a.size(), "no more posts than there are, none twice: " + a.size());
    }

    // ---- the fight ---------------------------------------------------------------------------------------------

    static Defense.Me me(double x, double z) {
        Defense.Me m = new Defense.Me();
        m.name = "Агент 2";
        m.x = x; m.y = 64; m.z = z;
        m.health = 20;
        m.weapon = 5;
        m.post = new double[] { 0.5, 64, -4.5 };   // the gate's post of a ring of radius 6 round 0 0 (gate north)
        m.leash = 4;
        return m;
    }

    static Defense.Foe foe(int id, String kind, double x, double z) { return new Defense.Foe(id, kind, x, 64, z, 20); }

    static void fight() {
        Defense.Perimeter p = Defense.ring(0, 64, 0, 6, 2, "north", FLAT);
        List<Defense.Foe> f = new ArrayList<Defense.Foe>();
        List<Defense.Ally> allies = new ArrayList<Defense.Ally>();
        // nearest the base first, though another is nearer itself
        f.add(foe(1, "Zombie", 3.5, -5.5));
        f.add(foe(2, "Zombie", 0.5, -2.5));
        Defense.Order o = Defense.decide(me(1.5, -4.5), f, allies, p, 0.5, 0.5);
        ok(o.target != null && o.target.id == 2 && o.act.equals("ATTACK"), "the one nearest the base, in reach: " + o);
        // one going for a wounded ally comes before
        allies.add(new Defense.Ally("Агент 3", 4, -5, 6));
        f.get(0).targets = "Агент 3";
        o = Defense.decide(me(1.5, -4.5), f, allies, p, 0.5, 0.5);
        ok(o.target.id == 1, "the enemy of a wounded ally first: " + o);
        // never a creeper in melee: one creeper alone, not near: it waits for it
        f.clear();
        allies.clear();
        f.add(foe(3, "Creeper", 0.5, -9.5));
        o = Defense.decide(me(0.5, -4.5), f, allies, p, 0.5, 0.5);
        ok(o.act.equals("HOLD") && o.target == null, "a creeper is not fought in melee: " + o);
        // hissing near: away from it, still inside the wall and within the post's leash
        f.get(0).fuse = 1;
        Defense.Foe c = new Defense.Foe(3, "Creeper", 0.5, 64, -6.0, 20);
        c.fuse = 1;
        f.set(0, c);
        o = Defense.decide(me(0.5, -4.5), f, allies, p, 0.5, 0.5);
        ok(o.act.equals("AVOID") && p.inside(o.move[0], o.move[2]) && o.move[2] > -4.5, "away from a hissing creeper, inside: " + o + " " + Arrays.toString(o.move));
        // with a bow: the creeper from afar, before it reaches the wall
        Defense.Me archer = me(0.5, -4.5);
        archer.bow = true;
        f.clear();
        f.add(foe(4, "Zombie", 2.5, -8.5));
        f.add(foe(5, "Creeper", -1.5, -10.5));
        o = Defense.decide(archer, f, allies, p, 0.5, 0.5);
        ok(o.act.equals("SHOOT") && o.target.id == 5, "a bow shoots the creeper first: " + o);
        // a zombie outside the wall out of reach: it does not go out for it (HOLD inside)
        f.clear();
        f.add(foe(6, "Zombie", 4.5, -8.5));
        o = Defense.decide(me(0.5, -4.5), f, allies, p, 0.5, 0.5);
        ok(o.act.equals("HOLD") && p.inside(o.move[0], o.move[2]), "not out of the wall for one beyond it: " + o + " " + Arrays.toString(o.move));
        // one inside, a few blocks off: at it
        f.clear();
        f.add(foe(7, "Zombie", 3.5, -2.5));
        o = Defense.decide(me(0.5, -4.5), f, allies, p, 0.5, 0.5);
        ok(o.act.equals("CLOSE") && Math.hypot(o.move[0] - 0.5, o.move[2] + 4.5) <= 4.0001, "at it, within its leash: " + o);
        // one out of its sector (far from the post) is not its own
        f.clear();
        f.add(foe(8, "Zombie", 0.5, 4.5));
        o = Defense.decide(me(0.5, -4.5), f, allies, p, 0.5, 0.5);
        ok(o.act.equals("HOLD") && o.target == null, "another sector's enemy is the other post's: " + o);
        // hurt: back inside
        Defense.Me hurt = me(0.5, -4.5);
        hurt.health = 6;
        f.add(foe(9, "Zombie", 0.5, -3.5));
        o = Defense.decide(hurt, f, allies, p, 0.5, 0.5);
        ok(o.act.equals("RETREAT") && o.move[0] == 0.5 && o.move[2] == 0.5, "under 8: back to the base: " + o);
        // the clamp: within the leash, and inside the ring
        double[] q = Defense.clamp(40, -40, new double[] { 0.5, 64, -4.5 }, 4, p);
        ok(Math.hypot(q[0] - 0.5, q[2] + 4.5) <= 4.0001 && p.inside(q[0], q[2]), "a far point brought in: " + Arrays.toString(q));
        q = Defense.clamp(40, -40, new double[] { 0.5, 64, -20 }, 100, p);   // a post outside the ring: the wall does not hold it in
        ok(q[0] == 40 && q[2] == -40, "outside the ring only the leash: " + Arrays.toString(q));
    }

    static void tally() {
        Defense.clear();
        Defense.Tally t = Defense.of("Агент 2");
        Defense.Tally was = t.copy();
        t.kills += 2; t.dealt += 17.6; t.taken += 4; t.wallBuilt += 6; t.retreats++;
        Defense.Tally d = Defense.of("Агент 2").since(was);
        ok(d.kills == 2 && d.wallBuilt == 6 && Math.abs(d.dealt - 17.6) < 1e-9, "what a step came to");
        ok(d.line().equals("kills 2, dealt 18, taken 4, deaths 0, wall +6/repaired 0, retreats 1"), "in a line: " + d.line());
        JsonObject j = d.json();
        ok(j.get("kills").getAsInt() == 2 && j.get("dealt").getAsDouble() == 17.6 && j.has("wallRepaired"), "as JSON: " + j);
        ok(Defense.peek("Агент 3") == null && new Defense.Tally().empty() && !d.empty(), "none, empty");
    }

    static void words() {
        ok(Arrays.equals(Defense.shape("around base", 8), new int[] { 0, 8 }) && Arrays.equals(Defense.shape("вокруг базы 10", 8), new int[] { 0, 10 }), "a ring by words");
        ok(Arrays.equals(Defense.shape("line 1 2 10 2", 8), new int[] { 1, 1, 2, 10, 2 }) && Arrays.equals(Defense.shape("5 64 -3 to 12 64 -3", 8), new int[] { 1, 5, -3, 12, -3 }), "a line by words");
        ok(Defense.shape("a tower", 8) == null, "an unknown shape");
        Map<String, Integer> bag = new LinkedHashMap<String, Integer>();
        ok(Defense.weapon("sword", bag).equals("wooden_sword"), "no stone: a wooden sword");
        bag.put("cobblestone", 12);
        ok(Defense.weapon("меч", bag).equals("stone_sword"), "cobblestone: a stone sword");
        bag.put("iron_ingot", 2);
        ok(Defense.weapon("best", bag).equals("iron_sword") && Defense.weapon("bow", bag).equals("bow") && Defense.weapon("arrows", bag).equals("arrow") && Defense.weapon("axe", bag) == null, "iron, a bow, arrows, not an axe");
        bag.put("dirt", 30);
        bag.put("planks:2", 20);
        ok(Defense.material("any", bag).equals("dirt") && Defense.material("planks", bag).equals("planks") && Defense.count(bag, "planks") == 20, "the material the bag has most of");
        ok(Defense.material("", new LinkedHashMap<String, Integer>()) == null, "nothing to build with");
        ok(Defense.side(0.5, -9, 0.5, 0.5).equals("north") && Defense.side(9, 0.5, 0.5, 0.5).equals("east") && Defense.side(8, 8, 0.5, 0.5).equals("south-east"), "the sides");
        ok(Defense.isGoal("защищай базу") && Defense.isGoal("Defend the base from the waves") && Defense.isGoal("отбивай волны") && !Defense.isGoal("сделай каменную кирку"), "a goal of defence by its words");
        Defense.Wave v = new Defense.Wave();
        v.known = true; v.on = true; v.number = 3; v.enemies = 3; v.nextIn = 40;
        v.where.add(new double[] { 0.5, 64, -12 }); v.where.add(new double[] { 1, 64, -10 }); v.where.add(new double[] { 12, 64, 0.5 });
        String l = v.line(0.5, 0.5);
        ok(l.startsWith("on: wave 3, 3 enemies (north 2, east 1), nearest 11 m from the base") && l.contains("the one after in 40 s"), "a wave in words: " + l);
        v.on = false; v.enemies = 0; v.where.clear();
        ok(v.line(0, 0).equals("pause after wave 3, next wave in 40 s"), "a pause: " + v.line(0, 0));
    }

    /** the reflexes against a post's guard: the mob and creeper ones leave a post's fight be, a fall and survival do not */
    static void priorities() {
        Situation st = new Situation();
        st.threat = "Creeper";
        st.threatDist = 3;
        st.taskGuard = 30;
        ok(Reflexes.pick(st) == Reflexes.CREEPER, "a creeper near, no post: the reflex runs");
        st.taskGuard = Defense.POST_GUARD;
        ok(Reflexes.pick(st) == null, "at a post (guard 82) the creeper reflex leaves the fight be");
        st.threat = "Zombie";
        st.threatDist = 2;
        ok(Reflexes.pick(st) == null, "nor the mob reflex");
        st.falling = true; st.fall = 4; st.drop = 3; st.onGround = false;
        ok(Reflexes.pick(st) == Reflexes.FALL, "a fall comes first all the same");
        st.inLava = true;
        ok(Reflexes.pick(st) == Reflexes.LAVA, "and lava");
        ok(Defense.POST_GUARD > Reflexes.CREEPER.priority() && Defense.POST_GUARD > Reflexes.MOB.priority() && Defense.POST_GUARD < Reflexes.FALL.priority(), "the guard level between them");
    }

    // ---- the mind of defence -----------------------------------------------------------------------------------

    static void mindTools() {
        Mind.Goal g = new Mind.Goal("защищай базу", null, 0);
        ok(g.defense, "a goal of defence");
        Mind m = new Mind("Агент 2", g, new Mind.Limits().defense(), 0);
        ok(m.lim.steps >= 300 && m.lim.seconds >= 3 * 3600, "a defence's limits: more steps and time");
        ok(Mind.tools(true).size() == Mind.skills(true).length && Mind.tools(false).size() == Mind.SKILLS.length, "a tool for each skill");
        MindState st = new MindState();
        ok(m.check(m.parse(MindTest.answer("build_wall", "{\"shape\":\"around base\",\"height\":2,\"why\":\"x\"}", 1, 1, 0), 1), st) == null, "build_wall for a defence");
        ok(m.check(m.parse(MindTest.answer("build_wall", "{\"height\":9,\"why\":\"x\"}", 1, 1, 0), 1), st).startsWith("BAD_ARG: build_wall height"), "too high");
        ok(m.check(m.parse(MindTest.answer("hold_line", "{\"radius\":20,\"why\":\"x\"}", 1, 1, 0), 1), st).startsWith("BAD_ARG"), "a leash too long");
        ok(m.check(m.parse(MindTest.answer("guard_ally", "{\"why\":\"x\"}", 1, 1, 0), 1), st).startsWith("MISSING_ARG"), "guard whom");
        Mind.Call heal = m.parse(MindTest.answer("heal", "{\"why\":\"hurt\"}", 1, 1, 0), 1);
        ok(heal.tool.equals("eat") && m.check(heal, st) == null, "heal is eat");
        st.waveOn = true;
        ok(m.check(m.parse(MindTest.answer("done", "{\"summary\":\"всё\"}", 1, 1, 0), 1), st).startsWith("NOT_DONE: a wave is on"), "no done in a wave");
        Mind other = MindTest.fresh();
        ok(other.check(other.parse(MindTest.answer("build_wall", "{\"why\":\"x\"}", 1, 1, 0), 1), new MindState()).startsWith("UNKNOWN_TOOL"), "no defence tools for a goal of things");
        String body = m.request(new MindState(), 0);
        JsonObject b = new JsonParser().parse(body).getAsJsonObject();
        ok(b.getAsJsonArray("tools").size() == Mind.skills(true).length && b.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString().contains("Defence of the base"), "the request has the defence's tools and rules");
        String plain = other.request(new MindState(), 0);
        ok(!plain.contains("Defence of the base") && !plain.contains("hold_line"), "a goal of things does not see them");
    }

    /** a small world between waves: the bag, a wall with holes, health, a wave to come */
    static final class Sim {
        final Map<String, Integer> bag = new LinkedHashMap<String, Integer>();
        float health = 20;
        int holes = 3;
        boolean waveOn;
        String post = "";
        int wave = 2;

        int n(String k) { return bag.containsKey(k) ? bag.get(k) : 0; }
        void add(String k, int d) { int v = n(k) + d; if (v <= 0) bag.remove(k); else bag.put(k, v); }

        MindState state(Mind.Goal g) {
            MindState s = new MindState();
            s.agent = "Агент 2";
            s.goal = g.text;
            s.x = 0; s.y = 64; s.z = 0;
            s.health = health;
            s.held = n("stone_sword") > 0 ? "stone_sword" : "";
            s.time = "night, 23:10 (day in 6 min; mobs are out)";
            s.bag.putAll(bag);
            s.wave = waveOn ? "on: wave " + wave + ", 4 enemies (north 3, west 1), nearest 9 m from the base" : "pause after wave " + (wave - 1) + ", next wave in 45 s";
            s.waveOn = waveOn;
            s.post = post;
            s.perimeter.add("wall: ring of radius 6 round 0 64 0, height 2 of cobblestone, gate north at 0 64 -6; " + (94 - holes) + " of 94 cells stand"
                    + (holes > 0 ? ", " + holes + " open, nearest at 6 64 2 (east)" : ""));
            s.perimeter.add("posts: gate at 0 64 -5 (" + (post.isEmpty() ? "free" : "yours") + "); south at 0 64 5 (Агент 3); east at 5 64 0 (free)");
            s.allies.add("Агент 3 at 0 64 5, health 18/20, weapon wooden_sword, post south, now hold_line 4 120");
            return s;
        }

        String[] run(Mind.Call c) {
            String t = c.tool;
            if (t.equals("make_weapon")) {
                String id = Defense.weapon(c.arg("type"), bag);
                if (n("stick") < 1) return new String[] { "MISSING", "one craft of " + id + " takes 2 cobblestone, 1 stick; the bag lacks 1 stick (has 0)" };
                add("cobblestone", -2); add("stick", -1); add(id, 1);
                return new String[] { "OK", "made 1 " + id };
            }
            if (t.equals("take")) { add(c.arg("item"), c.num("count", 1)); return new String[] { "OK", "took " + c.num("count", 1) + " " + c.arg("item") }; }
            if (t.equals("equip")) return n("stone_sword") > 0 ? new String[] { "OK", "holds stone_sword (damage 6)" } : new String[] { "NO_WEAPON", "no sword, axe or pickaxe in the bag: make_weapon sword" };
            if (t.equals("repair_wall")) {
                if (holes == 0) return new String[] { "NO_HOLES", "the wall is whole" };
                int k = Math.min(holes, n("cobblestone"));
                add("cobblestone", -k);
                holes -= k;
                waveOn = true;   // the wave comes while it repairs
                return new String[] { "OK", "put " + k + " blocks of the wall; " + (94 - holes) + " of 94 cells stand now" };
            }
            if (t.equals("take_post")) { post = "gate at 0 64 -5"; return new String[] { "OK", "at the post gate (0 64 -5), facing north" }; }
            if (t.equals("hold_line")) { health = 6; return new String[] { "LOW_HEALTH", "fell back inside at health 6; kills 2, dealt 26, taken 14, deaths 0, wall +0/repaired 0, retreats 1" }; }
            if (t.equals("retreat")) return new String[] { "OK", "inside at 0 64 0" };
            if (t.equals("eat")) return n("bread") > 0 ? new String[] { "OK", "ate 1" } : new String[] { "NO_FOOD", "no food in the bag (bread, apples, cooked meat; raw meat is cooked in a furnace: smelt)" };
            if (t.equals("wait")) { health = Math.min(20, health + 4); return new String[] { "OK", "" }; }
            return new String[] { "BLOCKED", "the simulation does not do " + t };
        }
    }

    /** the stub model of MindTest, for a goal of defence: the tools of the defence declared too */
    static final class Script implements Mind.Model {
        final List<String[]> steps = new ArrayList<String[]>();
        final List<String> asked = new ArrayList<String>();
        int i;
        Script then(String expect, String tool, String args) { steps.add(new String[] { expect, tool, args }); return this; }
        public String complete(String body) {
            JsonObject b = new JsonParser().parse(body).getAsJsonObject();
            ok(b.getAsJsonArray("tools").size() == Mind.skills(true).length, "every request of a defence declares its tools");
            String user = b.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
            asked.add(user);
            if (i >= steps.size()) throw new AssertionError("the model was asked more than the script has:\n" + user);
            String[] s = steps.get(i++);
            if (s[0] != null && !user.contains(s[0])) throw new AssertionError("step " + i + " (" + s[1] + "): the request lacks '" + s[0] + "':\n" + user);
            return MindTest.answer(s[1], s[2], 2600, 60, 2000);
        }
    }

    static void betweenWaves() throws Exception {
        Mind.Goal g = new Mind.Goal("защищай базу от волн", null, 0);
        g.members.add("Агент 2");
        g.members.add("Агент 3");
        Mind m = new Mind("Агент 2", g, new Mind.Limits().defense(), 0);
        Sim sim = new Sim();
        sim.add("cobblestone", 10);
        Script model = new Script()
                .then("holding nothing", "make_weapon", "{\"type\":\"sword\",\"why\":\"no weapon before the wave\"}")
                .then("MISSING: one craft of stone_sword", "take", "{\"chest\":\"crew\",\"item\":\"stick\",\"count\":2,\"why\":\"a stick for the sword\"}")
                .then("+2 stick", "make_weapon", "{\"type\":\"sword\",\"why\":\"now with the stick\"}")
                .then("+1 stone_sword", "equip", "{\"what\":\"weapon\",\"why\":\"in the hand\"}")
                .then("3 open, nearest at 6 64 2", "repair_wall", "{\"why\":\"holes in the wall before the wave\"}")
                .then("WAVE: on: wave 2", "take_post", "{\"where\":\"auto\",\"why\":\"the wave is here\"}")
                .then("your post gate", "hold_line", "{\"radius\":4,\"seconds\":120,\"why\":\"hold the gate\"}")
                .then("LOW_HEALTH", "retreat", "{\"where\":\"home\",\"why\":\"health 6\"}")
                .then("health 6/20", "eat", "{\"why\":\"heal\"}")
                .then("NO_FOOD", "done", "{\"summary\":\"отбились\"}")
                .then("NOT_DONE: a wave is on", "wait", "{\"seconds\":20,\"why\":\"heal by resting inside\"}");
        while (model.i < model.steps.size()) {
            MindState st = sim.state(g);
            String body = m.request(st, 1000);
            Mind.Call c = m.parse(model.complete(body), body.length());
            String bad = m.check(c, st);
            if (bad != null) { m.record(c, "BAD_CALL", bad, "", 0); continue; }
            Map<String, Integer> before = new LinkedHashMap<String, Integer>(sim.bag);
            String[] out = sim.run(c);
            m.record(c, out[0], out[1], MindTest.diff(before, sim.bag), 4);
        }
        ok(!m.over(), "the defence goes on: " + m.status + " " + m.endReason);
        ok(sim.n("stone_sword") == 1 && sim.holes == 0 && sim.health > 6, "a sword made, the wall whole, healing: " + sim.bag + " holes " + sim.holes + " health " + sim.health);
        List<String> tools = new ArrayList<String>();
        for (Mind.Step s : m.steps) tools.add(s.call.tool);
        ok(tools.indexOf("make_weapon") < tools.indexOf("repair_wall") && tools.indexOf("repair_wall") < tools.indexOf("hold_line") && tools.indexOf("hold_line") < tools.indexOf("retreat"),
                "no weapon -> a sword; a hole -> repaired; hurt -> back: " + tools);
        ok(model.asked.get(7).contains("hold_line 4 120 -> LOW_HEALTH: fell back inside at health 6"), "the fight's end reaches the model with its reason:\n" + model.asked.get(7));
        ok(model.asked.get(0).contains("WAVE: pause after wave 1, next wave in 45 s") && model.asked.get(0).contains("PERIMETER:") && model.asked.get(0).contains("ALLIES:"), "the wave, the wall, the allies in the state:\n" + model.asked.get(0));
        ok(model.asked.get(0).contains("Агент 3 at 0 64 5, health 18/20"), "an ally's health in the state");
        JsonObject json = sim.state(g).json();
        ok(json.has("wave") && json.getAsJsonArray("perimeter").size() == 2 && json.getAsJsonArray("allies").size() == 1, "the defence's state as JSON: " + json);
        System.out.println("between the waves: " + m.steps.size() + " steps");
        for (Mind.Step s : m.steps) System.out.println("  " + s.line());
    }

    /** a state with no defence in it stays as it was (no WAVE, no PERIMETER) */
    static void plainState() {
        MindState s = new MindState();
        s.goal = "x";
        String r = s.render();
        ok(!r.contains("WAVE") && !r.contains("PERIMETER") && !r.contains("ALLIES") && !s.json().has("wave"), "no defence: no defence in the state");
    }

    /** the ratings' fights: the defence's line read back as an episode */
    static void episodes() {
        Episodes.Ep e = Episodes.fight(1234, "defense: Агент 2 hold_line -> LOW_HEALTH (kills 2, dealt 26, taken 14, deaths 0, wall +0/repaired 0, retreats 1; fell back)");
        ok(e != null && e.type.equals("fight") && e.agent.equals("Агент 2") && e.card.equals("hold_line") && e.result.equals("LOW_HEALTH") && e.detail.startsWith("kills 2"), "a fight's line read");
        ok(e.key().startsWith("f:1234:") && e.ref().get("detail").getAsString().contains("dealt 26") && !e.ok(), "its key and reference");
        ok(Episodes.fight(1, "defense: Агент 2 plans the wall: ring of radius 8") == null && Episodes.card(1, "defense: Агент 2 hold_line -> OK (x)") == null, "other lines are not fights, nor cards");
        Episodes.clear();
        Episodes.event(10, "defense: Агент 3 build_wall -> OK (kills 0, dealt 0, taken 0, deaths 0, wall +12/repaired 0, retreats 0)");
        Episodes.event(11, "board: #4 mind craft by Агент 3 -> OK");
        ok(Episodes.fights(10).size() == 1 && Episodes.cards(10).size() == 1, "the fights tab and the cards tab apart");
        Episodes.clear();
    }

    public static void main(String[] args) throws Exception {
        ring();
        line();
        moatAndLights();
        posts();
        fight();
        tally();
        words();
        priorities();
        mindTools();
        plainState();
        episodes();
        betweenWaves();
        System.out.println("DefenseTest PASS (" + checks + " checks)");
    }
}
