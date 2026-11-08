package hardelele.aiguide.client;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The defence of the base, the part with no game in it: what the waves are now (WaveInfo: the waves mode's, or what
 * the crew sees), the perimeter's plan (a ring of wall round the base with a gate, a line, a moat outside it, the
 * torches' spots, the posts inside), the posts shared out among the defenders, the fight's choice of a target and of
 * what to do this tick (Order), each agent's tally of the fight (kills, damage, deaths, wall built, retreats). The
 * game's side (DefenseWorld, DefenseSkills) asks these with plain values; the tests do the same without a world.
 */
final class Defense {
    private Defense() { }

    /** a guard level of its own for a post's fight: over the mob (75) and creeper (80) reflexes, under a fall (90) and survival */
    static final int POST_GUARD = 82;
    /** health under which a defender falls back (as Guard.LOW), and the health a wounded ally is below (the epochs may tune them: EpochKnobs) */
    static volatile float LOW = 8, WOUNDED = 12;
    /** how far from its post hold_line goes when the model gives no radius (EpochKnobs may tune it) */
    static volatile int LEASH = 4;
    static final double REACH = 3.0, BOW_RANGE = 16, BOW_MIN = 3.5;
    static final int MAX_HEIGHT = 4, MAX_RADIUS = 24;

    // ---- the goal ----------------------------------------------------------------------------------------------

    static final Pattern GOAL = Pattern.compile("(?iu)(защищ|защит|оборон|отбива|отбей|волн|defen[cs]e|defend|protect|waves?\\b)");

    /** a goal of defence ("защищай базу", "defend the base from the waves"): the defence's tools and rules for its mind */
    static boolean isGoal(String text) {
        return text != null && GOAL.matcher(text).find();
    }

    /** the rules added to the mind's for a goal of defence (the same every time: the API's cache takes them) */
    static final String RULES = "\nDefence of the base (this goal): waves of mobs come at the base without end. Between waves get ready; "
            + "during a wave hold your post.\n"
            + "- WAVE says if a wave is on, how many enemies and where, and when the next comes. PERIMETER says if there is a wall, its holes, "
            + "its gate, the posts and who holds them. ALLIES say each one's health and post.\n"
            + "- Between waves: a weapon first (make_weapon sword; a bow and arrows when there is string and feathers), equip it, then the wall "
            + "(build_wall around base, it leaves a gate), its holes repaired (repair_wall), torches (light_area), a moat (dig_moat) if time is left.\n"
            + "- Posts: take_post auto gives you a free one; never take one another holds. Several of you: each its own post, each its own part "
            + "of the wall (the wall's cells are reserved as you build).\n"
            + "- During a wave: hold_line at your post (the body fights by itself there: you choose where, not each hit). Health under half: "
            + "retreat, then eat if you have food. A hole in the wall: repair_wall when the wave is over or nobody else is on it. A wounded ally: "
            + "guard_ally.\n"
            + "- A creeper at the wall: keep away from it; shoot it from afar if you have a bow.\n"
            + "- The waves do not end: do not call done unless the player says stop.";

    // ---- the waves ---------------------------------------------------------------------------------------------

    /** what is known of the waves now: told by the waves mode (known), else only what the crew sees near the base */
    static final class Wave {
        boolean known;             // a waves mode tells it (else: seen)
        boolean on;                // a wave is at the base now
        int number;                // the wave's number (0: none yet)
        int enemies;               // enemies alive now
        int nextIn = -1;           // seconds to the next wave (-1: not known)
        String phase = "";         // the waves mode's: prep, wave, break, over ("" not told)
        boolean paused;            // the run is paused (the player's)
        int cleared;               // waves beaten in this run
        final List<double[]> where = new ArrayList<double[]>();   // enemies' x y z, as told or seen

        /** "on: wave 3, 5 enemies (north 3, east 2), nearest 9 m from the base" */
        String line(double cx, double cz) {
            StringBuilder b = new StringBuilder();
            String rest = paused ? "paused" : phase.equals("prep") ? "preparation" : phase.equals("over") ? "over (the run has ended)" : known ? "pause" : "none seen";
            if (on) b.append("on: ").append(number > 0 ? "wave " + number + ", " : "").append(enemies).append(enemies == 1 ? " enemy" : " enemies");
            else b.append(rest).append(number > 0 ? " after wave " + number : "").append(enemies > 0 ? ", " + enemies + " enemies still about" : "");
            if (cleared > 0) b.append(", ").append(cleared).append(" cleared");
            if (!where.isEmpty()) {
                b.append(" (").append(sides(where, cx, cz)).append(')');
                double near = 1e9;
                for (double[] p : where) near = Math.min(near, Math.hypot(p[0] - cx, p[2] - cz));
                b.append(", nearest ").append(Math.round(near)).append(" m from the base");
            }
            if (nextIn >= 0 && !on) b.append(", next wave in ").append(nextIn).append(" s");
            else if (nextIn >= 0) b.append(", the one after in ").append(nextIn).append(" s");
            if (!known) b.append(" [no waves mode: only what the crew sees]");
            return b.toString();
        }
    }

    /** what the waves mode tells: a wave's state now (on any thread; it is asked about once a second) */
    interface WaveInfo { Wave now(); }

    /** the waves mode's source (null: none, the crew's own eyes); the mode sets it with connect */
    static volatile WaveInfo source;

    static void connect(WaveInfo w) { source = w; }

    /** the side of the base a point is on: north (-z), south (+z), east (+x), west (-x), or two of them on a diagonal */
    static String side(double x, double z, double cx, double cz) {
        double dx = x - cx, dz = z - cz;
        if (Math.abs(dx) < 1e-6 && Math.abs(dz) < 1e-6) return "centre";
        double ang = Math.toDegrees(Math.atan2(dx, -dz));   // 0 north, 90 east
        if (ang < 0) ang += 360;
        String[] n = { "north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west" };
        return n[(int) Math.floor((ang + 22.5) / 45) % 8];
    }

    /** "north 3, east 2": how many on each side, the most first */
    static String sides(List<double[]> ps, double cx, double cz) {
        final Map<String, Integer> m = new LinkedHashMap<String, Integer>();
        for (double[] p : ps) { String s = side(p[0], p[2], cx, cz); m.put(s, (m.containsKey(s) ? m.get(s) : 0) + 1); }
        List<String> k = new ArrayList<String>(m.keySet());
        Collections.sort(k, (a, b) -> m.get(b) - m.get(a));
        StringBuilder b = new StringBuilder();
        for (String s : k) b.append(b.length() > 0 ? ", " : "").append(s).append(' ').append(m.get(s));
        return b.toString();
    }

    // ---- the perimeter -----------------------------------------------------------------------------------------

    /** where one stands at a column (the feet's y over firm ground), -1 when nowhere (water, a cliff) */
    interface Floor { int y(int x, int z); }

    static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** a plan of wall: a ring round the base (a gate in it, posts inside) or a line; its cells bottom layer first */
    static final class Perimeter {
        final boolean ring;
        final int cx, cy, cz, r, height;
        final String gate;                                          // the gate's side (a ring), or ""
        final List<int[]> cells = new ArrayList<int[]>();           // x y z of the wall (the gate's left open)
        final List<int[]> gateCells = new ArrayList<int[]>();       // the opening: never built
        final List<int[]> posts = new ArrayList<int[]>();           // feet cells inside: the gate's first
        final List<String> postNames = new ArrayList<String>();
        final Set<Long> keys = new HashSet<Long>();
        int gaps;                                                   // columns with no ground for a wall (water, a drop)
        String material = "";

        Perimeter(boolean ring, int cx, int cy, int cz, int r, int height, String gate) {
            this.ring = ring; this.cx = cx; this.cy = cy; this.cz = cz; this.r = r; this.height = height; this.gate = gate;
        }

        boolean isCell(int x, int y, int z) { return keys.contains(key(x, y, z)); }

        /** a point (block coordinates as doubles) inside the ring, off the wall's cells */
        boolean inside(double x, double z) {
            return ring && Math.max(Math.abs(Math.floor(x) - cx), Math.abs(Math.floor(z) - cz)) < r;
        }

        /** a post by its name ("gate", "north", "north-east") or null */
        int[] post(String name) {
            int i = postNames.indexOf(name == null ? "" : name.trim().toLowerCase(Locale.ROOT));
            return i < 0 ? null : posts.get(i);
        }

        String postName(int[] p) {
            for (int i = 0; i < posts.size(); i++) if (posts.get(i)[0] == p[0] && posts.get(i)[2] == p[2]) return postNames.get(i);
            return null;
        }

        /** "ring r 8 round 10 64 -3, height 2 of cobblestone, gate north at 10 -11" */
        String describe() {
            if (!ring) return "line of " + cells.size() + " cells, height " + height;
            int[] g = gateCells.isEmpty() ? null : gateCells.get(0);
            return "ring of radius " + r + " round " + cx + " " + cy + " " + cz + ", height " + height + (material.isEmpty() ? "" : " of " + material)
                    + (g != null ? ", gate " + gate + " at " + g[0] + " " + g[1] + " " + g[2] : "") + (gaps > 0 ? ", " + gaps + " columns with no ground (left open)" : "");
        }
    }

    static final String[] GATES = { "north", "east", "south", "west" };

    /** a gate's side from words, "" when it is none of them */
    static String gateSide(String s) {
        String t = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("n") || t.startsWith("с")) return "north";
        if (t.startsWith("s") || t.startsWith("ю")) return "south";
        if (t.startsWith("e") || t.startsWith("в")) return "east";
        if (t.startsWith("w") || t.startsWith("з")) return "west";
        return "";
    }

    /**
     * A ring of wall at Chebyshev radius r round cx cz, height h over the ground of each column, with a gate one wide in
     * the middle of the gate's side (left open, all its height), posts one cell inside: the gate's, the middles of the
     * other sides, the corners. Cells bottom layer first, each layer round from the gate.
     */
    static Perimeter ring(int cx, int cy, int cz, int r, int h, String gate, Floor f) {
        String g = gateSide(gate).isEmpty() ? "north" : gateSide(gate);
        Perimeter p = new Perimeter(true, cx, cy, cz, r, h, g);
        int gx = g.equals("east") ? cx + r : g.equals("west") ? cx - r : cx, gz = g.equals("north") ? cz - r : g.equals("south") ? cz + r : cz;
        List<int[]> round = new ArrayList<int[]>();   // the ring's columns, clockwise from the north-west corner
        for (int x = cx - r; x < cx + r; x++) round.add(new int[] { x, cz - r });
        for (int z = cz - r; z < cz + r; z++) round.add(new int[] { cx + r, z });
        for (int x = cx + r; x > cx - r; x--) round.add(new int[] { x, cz + r });
        for (int z = cz + r; z > cz - r; z--) round.add(new int[] { cx - r, z });
        int start = 0;
        for (int i = 0; i < round.size(); i++) if (round.get(i)[0] == gx && round.get(i)[1] == gz) start = i;
        List<int[]> cols = new ArrayList<int[]>();
        for (int i = 0; i < round.size(); i++) cols.add(round.get((start + i) % round.size()));
        int[] ys = new int[cols.size()];
        for (int i = 0; i < cols.size(); i++) {
            ys[i] = f.y(cols.get(i)[0], cols.get(i)[1]);
            if (ys[i] < 0 && !(cols.get(i)[0] == gx && cols.get(i)[1] == gz)) p.gaps++;
        }
        for (int layer = 0; layer < h; layer++)
            for (int i = 0; i < cols.size(); i++) {
                int[] c = cols.get(i);
                if (ys[i] < 0) continue;
                int[] cell = { c[0], ys[i] + layer, c[1] };
                if (c[0] == gx && c[1] == gz) { p.gateCells.add(cell); continue; }
                p.cells.add(cell);
                p.keys.add(key(cell[0], cell[1], cell[2]));
            }
        // the posts: the gate's, the other sides' middles, the corners (one cell in from the wall)
        int in = r - 1;
        addPost(p, "gate", gx == cx ? cx : gx > cx ? cx + in : cx - in, gz == cz ? cz : gz > cz ? cz + in : cz - in, f);
        for (String s : GATES) {
            if (s.equals(g)) continue;
            int px = s.equals("east") ? cx + in : s.equals("west") ? cx - in : cx, pz = s.equals("north") ? cz - in : s.equals("south") ? cz + in : cz;
            addPost(p, s, px, pz, f);
        }
        addPost(p, "north-east", cx + in, cz - in, f);
        addPost(p, "south-east", cx + in, cz + in, f);
        addPost(p, "south-west", cx - in, cz + in, f);
        addPost(p, "north-west", cx - in, cz - in, f);
        return p;
    }

    static void addPost(Perimeter p, String name, int x, int z, Floor f) {
        int y = f.y(x, z);
        if (y < 0 || p.r < 2) return;
        p.posts.add(new int[] { x, y, z });
        p.postNames.add(name);
    }

    /** a straight wall from one column to another (Bresenham), height h over each column's ground; no gate, no posts */
    static Perimeter line(int x1, int z1, int x2, int z2, int h, Floor f) {
        Perimeter p = new Perimeter(false, (x1 + x2) / 2, 0, (z1 + z2) / 2, 0, h, "");
        List<int[]> cols = new ArrayList<int[]>();
        int dx = Math.abs(x2 - x1), dz = Math.abs(z2 - z1), sx = x1 < x2 ? 1 : -1, sz = z1 < z2 ? 1 : -1, err = dx - dz, x = x1, z = z1;
        while (true) {
            cols.add(new int[] { x, z });
            if (x == x2 && z == z2) break;
            int e2 = 2 * err;
            if (e2 > -dz) { err -= dz; x += sx; }
            if (e2 < dx) { err += dx; z += sz; }
        }
        int[] ys = new int[cols.size()];
        for (int i = 0; i < cols.size(); i++) { ys[i] = f.y(cols.get(i)[0], cols.get(i)[1]); if (ys[i] < 0) p.gaps++; }
        for (int layer = 0; layer < h; layer++)
            for (int i = 0; i < cols.size(); i++) {
                if (ys[i] < 0) continue;
                int[] cell = { cols.get(i)[0], ys[i] + layer, cols.get(i)[1] };
                p.cells.add(cell);
                p.keys.add(key(cell[0], cell[1], cell[2]));
            }
        return p;
    }

    /** the moat: a ring two cells out from the wall, depth blocks of ground under each column dug (top first), the gate's way left; x y z and the depth of each */
    static List<int[]> moat(Perimeter p, int depth, Floor f) {
        List<int[]> out = new ArrayList<int[]>();
        if (!p.ring) return out;
        int m = p.r + 2;
        int gx = p.gate.equals("east") ? p.cx + m : p.gate.equals("west") ? p.cx - m : p.cx, gz = p.gate.equals("north") ? p.cz - m : p.gate.equals("south") ? p.cz + m : p.cz;
        for (int d = 1; d <= depth; d++)
            for (int x = p.cx - m; x <= p.cx + m; x++)
                for (int z = p.cz - m; z <= p.cz + m; z++) {
                    if (Math.max(Math.abs(x - p.cx), Math.abs(z - p.cz)) != m) continue;
                    if (x == gx && z == gz) continue;   // the way out of the gate stays ground
                    int y = f.y(x, z);
                    if (y < 0) continue;
                    out.add(new int[] { x, y - d, z, d });
                }
        return out;
    }

    /** the torches' spots within radius of the centre: every spacing blocks, off the wall, the gate, the moat and the posts */
    static List<int[]> lights(Perimeter p, int cx, int cz, int radius, int spacing, Floor f) {
        List<int[]> out = new ArrayList<int[]>();
        Set<Long> skip = new HashSet<Long>();
        if (p != null) {
            for (int[] c : p.posts) skip.add(key(c[0], 0, c[2]));
            for (int[] c : p.gateCells) skip.add(key(c[0], 0, c[2]));
            for (int[] c : p.cells) skip.add(key(c[0], 0, c[2]));
        }
        for (int dx = -radius; dx <= radius; dx += spacing)
            for (int dz = -radius; dz <= radius; dz += spacing) {
                int x = cx + dx, z = cz + dz;
                if (dx == 0 && dz == 0) continue;   // the base's own middle (its stations)
                if (skip.contains(key(x, 0, z))) continue;
                if (p != null && p.ring && Math.max(Math.abs(x - p.cx), Math.abs(z - p.cz)) == p.r + 2) continue;   // the moat
                int y = f.y(x, z);
                if (y >= 0) out.add(new int[] { x, y, z });
            }
        return out;
    }

    // ---- the posts shared out ----------------------------------------------------------------------------------

    /** one who may hold a post */
    static final class Defender {
        final String name;
        final double x, z, weapon;
        final boolean ranged;
        final float health;
        Defender(String name, double x, double z, double weapon, boolean ranged, float health) {
            this.name = name; this.x = x; this.z = z; this.weapon = weapon; this.ranged = ranged; this.health = health;
        }
    }

    /**
     * The posts shared out, none held twice: who holds one keeps it; of the rest the best armed melee takes the gate,
     * the others (the best armed first) the nearest free post. Returns post name -> defender name.
     */
    static Map<String, String> assign(Perimeter p, List<Defender> ds, Map<String, String> held) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        Set<String> who = new HashSet<String>();
        for (Defender d : ds) who.add(d.name);
        Set<String> placed = new HashSet<String>();
        if (held != null)
            for (Map.Entry<String, String> e : held.entrySet())
                if (p.post(e.getKey()) != null && who.contains(e.getValue()) && placed.add(e.getValue())) out.put(e.getKey(), e.getValue());
        List<Defender> free = new ArrayList<Defender>();
        for (Defender d : ds) if (!placed.contains(d.name)) free.add(d);
        Collections.sort(free, (a, b) -> a.weapon != b.weapon ? Double.compare(b.weapon, a.weapon) : a.name.compareTo(b.name));
        if (p.post("gate") != null && !out.containsKey("gate"))
            for (Defender d : free) if (!d.ranged) { out.put("gate", d.name); placed.add(d.name); break; }
        for (Defender d : free) {
            if (placed.contains(d.name)) continue;
            String best = null;
            double bd = 1e9;
            for (int i = 0; i < p.posts.size(); i++) {
                String n = p.postNames.get(i);
                if (out.containsKey(n)) continue;
                int[] q = p.posts.get(i);
                double dd = Math.hypot(q[0] + 0.5 - d.x, q[2] + 0.5 - d.z);
                if (dd < bd) { bd = dd; best = n; }
            }
            if (best == null) break;   // more defenders than posts
            out.put(best, d.name);
            placed.add(d.name);
        }
        return out;
    }

    /** the post that falls to this one (null: none free) */
    static String postFor(Perimeter p, String me, List<Defender> ds, Map<String, String> held) {
        for (Map.Entry<String, String> e : assign(p, ds, held).entrySet()) if (e.getValue().equals(me)) return e.getKey();
        return null;
    }

    // ---- the fight: a target and what to do this tick ----------------------------------------------------------

    /** an enemy as the fighter sees it */
    static final class Foe {
        final int id;
        final String kind;           // "Zombie", "Creeper", "Skeleton" ...
        final double x, y, z;
        final float health;
        int fuse;                    // a creeper's (above 0: it hisses)
        String targets = "";         // whom it goes for (an agent's name, the player's) or ""
        Foe(int id, String kind, double x, double y, double z, float health) { this.id = id; this.kind = kind; this.x = x; this.y = y; this.z = z; this.health = health; }
        boolean creeper() { return kind.equals("Creeper"); }
    }

    /** the fighter: where it is, its health and weapon, its post and how far it may leave it */
    static final class Me {
        String name = "";
        double x, y, z;
        float health = 20;
        double weapon = 1;           // the bag's best melee damage
        boolean bow;                 // a bow and arrows in the bag
        double[] post;               // x y z (null: where it stands)
        double leash = 4;
    }

    /** an ally near: its name, place and health */
    static final class Ally {
        final String name;
        final double x, z;
        final float health;
        Ally(String name, double x, double z, float health) { this.name = name; this.x = x; this.z = z; this.health = health; }
    }

    /** what to do this tick: ATTACK (hit it), SHOOT (a bow), CLOSE (walk to move, then hit), AVOID (walk to move, away from a creeper), HOLD (at move), RETREAT */
    static final class Order {
        final String act;
        final Foe target;
        final double[] move;
        final String why;
        Order(String act, Foe target, double[] move, String why) { this.act = act; this.target = target; this.move = move; this.why = why; }
        public String toString() { return act + (target != null ? " " + target.kind + "#" + target.id : "") + " (" + why + ")"; }
    }

    static double dist(double x1, double z1, double x2, double z2) { return Math.hypot(x1 - x2, z1 - z2); }

    /**
     * A point brought within the leash of the post and, for a post inside the ring, inside the wall (one cell in): the
     * fighter never walks out of its sector nor out through the gate.
     */
    static double[] clamp(double x, double z, double[] post, double leash, Perimeter p) {
        double dx = x - post[0], dz = z - post[2], d = Math.hypot(dx, dz);
        if (d > leash) { x = post[0] + dx / d * leash; z = post[2] + dz / d * leash; }
        if (p != null && p.ring && p.inside(post[0], post[2])) {
            double lim = p.r - 1.0, ox = p.cx + 0.5, oz = p.cz + 0.5;
            x = Math.max(ox - lim, Math.min(ox + lim, x));
            z = Math.max(oz - lim, Math.min(oz + lim, z));
        }
        return new double[] { x, post[1], z };
    }

    /**
     * The fight's choice this tick, deterministic: fall back when hurt under LOW; away from a hissing creeper (or any
     * within 3 for a melee fighter); else the best target among the enemies near the post (a creeper only from afar
     * with a bow, never in melee): nearest the base, then nearest itself, those going for a wounded ally (or itself)
     * and those a hit kills first; shot when it has a bow and the target is not at arm's length, hit in reach, else
     * walked at as far as the leash and the wall let it (HOLD there when that is not near enough).
     */
    static Order decide(Me me, List<Foe> foes, List<Ally> allies, Perimeter p, double baseX, double baseZ) {
        double[] post = me.post != null ? me.post : new double[] { me.x, me.y, me.z };
        if (me.health < LOW) return new Order("RETREAT", null, new double[] { baseX, me.y, baseZ }, "health " + Math.round(me.health) + " under " + Math.round(LOW));
        for (Foe f : foes) {
            double d = dist(f.x, f.z, me.x, me.z);
            if (f.creeper() && (f.fuse > 0 && d < 6 || d < 3 && !me.bow || d < 2.5)) {
                double ax = me.x - f.x, az = me.z - f.z, n = Math.max(1e-3, Math.hypot(ax, az));
                double[] to = clamp(me.x + ax / n * 6, me.z + az / n * 6, post, Math.max(me.leash, 6), p);
                return new Order("AVOID", f, to, (f.fuse > 0 ? "a hissing creeper " : "a creeper ") + Math.round(d) + " m away");
            }
        }
        java.util.Set<String> wounded = new java.util.HashSet<String>();
        if (allies != null) for (Ally a : allies) if (a.health < WOUNDED) wounded.add(a.name);
        Foe best = null;
        double bs = 1e9;
        for (Foe f : foes) {
            double fromPost = dist(f.x, f.z, post[0], post[2]), fromMe = dist(f.x, f.z, me.x, me.z);
            boolean shootable = me.bow && fromMe <= BOW_RANGE;
            if (f.creeper() && !(me.bow && fromMe >= 4)) continue;   // never a creeper in melee
            if (!shootable && fromPost > me.leash + REACH) continue;  // out of its sector
            double s = dist(f.x, f.z, baseX, baseZ) + fromMe * 0.5;
            if (wounded.contains(f.targets)) s -= 6;
            if (f.targets.equals(me.name)) s -= 3;
            if (f.health <= me.weapon) s -= 3;
            if (f.creeper()) s -= 100;   // a creeper with a bow: before it reaches the wall
            if (s < bs || s == bs && best != null && f.id < best.id) { bs = s; best = f; }
        }
        if (best == null) {
            boolean there = dist(me.x, me.z, post[0], post[2]) <= 1.5;
            return new Order("HOLD", null, post, there ? "no enemy in its sector" : "back to its post");
        }
        double d = dist(best.x, best.z, me.x, me.z);
        if (me.bow && d > BOW_MIN && d <= BOW_RANGE) return new Order("SHOOT", best, null, "bow at " + Math.round(d) + " m");
        if (d <= REACH - 0.4) return new Order("ATTACK", best, null, "in reach");
        double[] to = clamp(best.x, best.z, post, me.leash, p);
        if (dist(to[0], to[2], best.x, best.z) > REACH - 0.4) return new Order("HOLD", best, to, "it is out of its sector or beyond the wall: waits for it");
        return new Order("CLOSE", best, to, "at it, " + Math.round(d) + " m");
    }

    // ---- the tally of the fight --------------------------------------------------------------------------------

    /** one agent's fight in numbers: what the training reads (crew-minds.jsonl, crew-cases.jsonl) and the ratings show */
    static final class Tally {
        int kills, deaths, retreats, wallBuilt, wallRepaired, shots, hits;
        // the deaths by their cause (of deaths: starved, accidents; the rest are the fight's), the torches put, the food eaten
        int starved, accidents, torches, eaten;
        double dealt, taken;

        Tally copy() {
            Tally t = new Tally();
            t.kills = kills; t.deaths = deaths; t.retreats = retreats; t.wallBuilt = wallBuilt; t.wallRepaired = wallRepaired; t.shots = shots; t.hits = hits;
            t.starved = starved; t.accidents = accidents; t.torches = torches; t.eaten = eaten;
            t.dealt = dealt; t.taken = taken;
            return t;
        }

        /** what came of it since an earlier copy */
        Tally since(Tally was) {
            Tally t = copy();
            if (was == null) return t;
            t.kills -= was.kills; t.deaths -= was.deaths; t.retreats -= was.retreats; t.wallBuilt -= was.wallBuilt; t.wallRepaired -= was.wallRepaired;
            t.shots -= was.shots; t.hits -= was.hits; t.dealt -= was.dealt; t.taken -= was.taken;
            t.starved -= was.starved; t.accidents -= was.accidents; t.torches -= was.torches; t.eaten -= was.eaten;
            return t;
        }

        /** the deaths in a fight (not by hunger, not by an accident) */
        int fightDeaths() { return Math.max(0, deaths - starved - accidents); }

        void add(Tally o) {
            if (o == null) return;
            kills += o.kills; deaths += o.deaths; retreats += o.retreats; wallBuilt += o.wallBuilt; wallRepaired += o.wallRepaired; shots += o.shots; hits += o.hits;
            starved += o.starved; accidents += o.accidents; torches += o.torches; eaten += o.eaten; dealt += o.dealt; taken += o.taken;
        }

        static Tally fromJson(JsonObject o) {
            Tally t = new Tally();
            if (o == null) return t;
            t.kills = (int) CaseVec.num(o, "kills", 0); t.deaths = (int) CaseVec.num(o, "deaths", 0); t.retreats = (int) CaseVec.num(o, "retreats", 0);
            t.wallBuilt = (int) CaseVec.num(o, "wallBuilt", 0); t.wallRepaired = (int) CaseVec.num(o, "wallRepaired", 0);
            t.shots = (int) CaseVec.num(o, "shots", 0); t.hits = (int) CaseVec.num(o, "hits", 0);
            t.starved = (int) CaseVec.num(o, "starved", 0); t.accidents = (int) CaseVec.num(o, "accidents", 0);
            t.torches = (int) CaseVec.num(o, "torches", 0); t.eaten = (int) CaseVec.num(o, "eaten", 0);
            t.dealt = CaseVec.num(o, "dealt", 0); t.taken = CaseVec.num(o, "taken", 0);
            return t;
        }

        boolean empty() {
            return kills == 0 && deaths == 0 && retreats == 0 && wallBuilt == 0 && wallRepaired == 0 && shots == 0 && hits == 0 && dealt == 0 && taken == 0
                    && torches == 0 && eaten == 0;
        }

        JsonObject json() {
            JsonObject o = new JsonObject();
            o.addProperty("kills", kills);
            o.addProperty("dealt", Math.round(dealt * 10) / 10.0);
            o.addProperty("taken", Math.round(taken * 10) / 10.0);
            o.addProperty("deaths", deaths);
            o.addProperty("wallBuilt", wallBuilt);
            o.addProperty("wallRepaired", wallRepaired);
            o.addProperty("retreats", retreats);
            o.addProperty("hits", hits);
            o.addProperty("shots", shots);
            // the siege's numbers (hunger, accidents) and the work round the base: written only when there are any
            if (starved > 0) o.addProperty("starved", starved);
            if (accidents > 0) o.addProperty("accidents", accidents);
            if (torches > 0) o.addProperty("torches", torches);
            if (eaten > 0) o.addProperty("eaten", eaten);
            return o;
        }

        /** "kills 2, dealt 18, taken 4, deaths 0, wall +6/repaired 2, retreats 1" */
        String line() {
            return "kills " + kills + ", dealt " + Math.round(dealt) + ", taken " + Math.round(taken) + ", deaths " + deaths + ", wall +" + wallBuilt
                    + "/repaired " + wallRepaired + ", retreats " + retreats + (shots > 0 ? ", shots " + shots : "");
        }
    }

    private static final Map<String, Tally> tallies = new LinkedHashMap<String, Tally>();

    /** the agent's tally (made when first asked) */
    static synchronized Tally of(String agent) {
        Tally t = tallies.get(agent);
        if (t == null) tallies.put(agent, t = new Tally());
        return t;
    }

    static synchronized Tally peek(String agent) {
        Tally t = tallies.get(agent);
        return t == null ? null : t.copy();
    }

    static synchronized void clear() { tallies.clear(); }

    /** every agent's tally (copies) */
    static synchronized Map<String, Tally> all() {
        Map<String, Tally> m = new LinkedHashMap<String, Tally>();
        for (Map.Entry<String, Tally> e : tallies.entrySet()) m.put(e.getKey(), e.getValue().copy());
        return m;
    }

    /** a death's cause from the game's damage type: "starve" (hunger), "accident" (a fall, water, lava, fire, a wall, a cactus, the void), else "fight" */
    static String cause(String damageType) {
        String t = damageType == null ? "" : damageType;
        if (t.equals("starve")) return "starve";
        if (t.equals("fall") || t.equals("drown") || t.equals("lava") || t.equals("inFire") || t.equals("onFire") || t.equals("inWall") || t.equals("cactus")
                || t.equals("outOfWorld") || t.equals("fallingBlock") || t.equals("anvil")) return "accident";
        return "fight";
    }

    /** an agent died of this damage type: its tally's deaths, and the cause's own count */
    static synchronized String died(String agent, String damageType) {
        Tally t = of(agent);
        t.deaths++;
        String c = cause(damageType);
        if (c.equals("starve")) t.starved++;
        else if (c.equals("accident")) t.accidents++;
        return c;
    }

    // ---- arguments in words ------------------------------------------------------------------------------------

    /** a wall's shape: { "ring", radius } or { "line", x1, z1, x2, z2 }; null when not read */
    static int[] shape(String s, int defRadius) {
        String t = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
        Matcher n = Pattern.compile("-?\\d+").matcher(t);
        List<Integer> nums = new ArrayList<Integer>();
        while (n.find()) nums.add(Integer.parseInt(n.group()));
        if (t.startsWith("line") || t.startsWith("линия") || t.contains(" to ") || nums.size() >= 4) {
            if (nums.size() == 6) return new int[] { 1, nums.get(0), nums.get(2), nums.get(3), nums.get(5) };   // x y z to x y z: the y's left out
            if (nums.size() == 4) return new int[] { 1, nums.get(0), nums.get(1), nums.get(2), nums.get(3) };
            return null;
        }
        if (t.isEmpty() || t.contains("base") || t.contains("баз") || t.contains("ring") || t.contains("around") || t.contains("вокруг") || t.contains("дом") || t.contains("home"))
            return new int[] { 0, nums.isEmpty() ? defRadius : nums.get(0) };
        return null;
    }

    /** the weapon to craft for a kind ("sword", "bow", "arrows", or an item id), by what the bag has (ids -> counts) */
    static String weapon(String kind, Map<String, Integer> bag) {
        String t = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace("minecraft:", "");
        if (t.isEmpty() || t.equals("sword") || t.equals("меч") || t.equals("best")) {
            if (n(bag, "diamond") >= 2) return "diamond_sword";
            if (n(bag, "iron_ingot") >= 2) return "iron_sword";
            if (n(bag, "cobblestone") >= 2) return "stone_sword";
            return "wooden_sword";
        }
        if (t.equals("bow") || t.equals("лук")) return "bow";
        if (t.startsWith("arrow") || t.startsWith("стрел")) return "arrow";
        if (t.endsWith("_sword") || t.equals("bow") || t.equals("arrow")) return t;
        return null;
    }

    static int n(Map<String, Integer> bag, String k) {
        Integer v = bag == null ? null : bag.get(k);
        return v == null ? 0 : v;
    }

    /** the blocks a wall may be built of, the best first; and the material the bag has most of for "any" */
    static final String[] WALL = { "cobblestone", "stonebrick", "stone", "brick_block", "planks", "log", "log2", "dirt", "sandstone", "netherrack" };

    static String material(String want, Map<String, Integer> bag) {
        String t = want == null ? "" : want.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "");
        if (!t.isEmpty() && !t.equals("any") && !t.equals("best") && !t.equals("любой")) return t;
        String best = null;
        int bn = 0;
        for (String m : WALL) {
            int c = 0;
            for (Map.Entry<String, Integer> e : bag.entrySet()) if (e.getKey().equals(m) || e.getKey().startsWith(m + ":")) c += e.getValue();
            if (c > bn) { bn = c; best = m; }
        }
        return best;
    }

    static int count(Map<String, Integer> bag, String material) {
        int c = 0;
        for (Map.Entry<String, Integer> e : bag.entrySet()) if (e.getKey().equals(material) || e.getKey().startsWith(material + ":")) c += e.getValue();
        return c;
    }
}
