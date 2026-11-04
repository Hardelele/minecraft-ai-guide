package hardelele.aiguide;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * The area around a player in words, from the labels of Semantics: where they are (under cover or outside), the way
 * out, the trees and groves, the ores (exposed or buried), liquids and dangers, what is built; with the distances on
 * foot found by a walk search (steps up one block, drops of up to three, doors open). Call on the server thread.
 */
class Area {
    static final String[] DIRS = { "north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west" };
    final AIGuide mod;
    World w;
    int cx, cy, cz, r, y0, y1;
    final Map<String, String> names = new HashMap<String, String>();
    Map<Long, Integer> dist;
    Map<Long, Long> prev;

    Area(AIGuide mod) {
        this.mod = mod;
    }

    static long pack(int x, int y, int z) {
        return ((long) (x + 30000000) << 36) | ((long) (z + 30000000) << 8) | (y & 255);
    }

    static int[] unpack(long k) {
        return new int[] { (int) (k >>> 36) - 30000000, (int) (k & 255), (int) ((k >>> 8) & 0xFFFFFFF) - 30000000 };
    }

    String dir(int x, int z) {
        int dx = x - cx, dz = z - cz;
        if (dx * dx + dz * dz < 4) return "here";
        double a = (Math.toDegrees(Math.atan2(dx, -dz)) + 360) % 360;
        return DIRS[(int) ((a + 22.5) / 45) % 8];
    }

    String where(int x, int y, int z) {
        int d = (int) Math.round(Math.sqrt((x - cx) * (x - cx) + (z - cz) * (z - cz)));
        int dy = y - cy;
        return x + " " + y + " " + z + " (" + d + " blocks " + dir(x, z) + (dy > 2 ? ", " + dy + " up" : dy < -2 ? ", " + -dy + " down" : "") + ")";
    }

    String name(Block b, int meta) {
        String k = Semantics.key(b, meta);
        String n = names.get(k);
        if (n == null) {
            ItemStack s = Semantics.stack(b, meta);
            n = s == null ? b.func_149739_a() : mod.lang.name(s);
            names.put(k, n);
        }
        return n;
    }

    Block block(int x, int y, int z) {
        return w.func_147439_a(x, y, z);
    }

    boolean stand(int x, int y, int z) {
        if (y < 1 || y > 254) return false;
        Block f = block(x, y, z), h = block(x, y + 1, z), u = block(x, y - 1, z);
        if (!Semantics.passable(f) || !Semantics.passable(h)) return false;
        if (f.func_149688_o() == net.minecraft.block.material.Material.field_151587_i) return false;
        try {
            if (ladder(f)) return true;
        } catch (Throwable e) {
            // a block that wants the climber
        }
        return Semantics.floor(u) || f.func_149688_o() == net.minecraft.block.material.Material.field_151586_h;
    }

    static boolean ladder(Block b) {
        String id = String.valueOf(Block.field_149771_c.func_148750_c(b)).toLowerCase();
        return id.contains("ladder") || id.contains("vine");
    }

    /** a walk search from the player's feet over the area */
    void walk(int sx, int sy, int sz, int max) {
        dist = new HashMap<Long, Integer>();
        prev = new HashMap<Long, Long>();
        int y = sy;
        if (!stand(sx, y, sz)) for (int d : new int[] { 1, -1, 2 }) if (stand(sx, sy + d, sz)) { y = sy + d; break; }
        long s = pack(sx, y, sz);
        dist.put(s, 0);
        ArrayDeque<Long> q = new ArrayDeque<Long>();
        q.add(s);
        int[][] steps = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
        while (!q.isEmpty() && dist.size() < max) {
            long k = q.poll();
            int[] c = unpack(k);
            int d = dist.get(k);
            for (int[] st : steps) {
                int nx = c[0] + st[0], nz = c[2] + st[1];
                if (Math.abs(nx - cx) > r || Math.abs(nz - cz) > r || !w.func_72899_e(nx, c[1], nz)) continue;
                for (int dy : new int[] { 0, 1, -1, -2, -3 }) {
                    int ny = c[1] + dy;
                    if (dy == 1 && !Semantics.passable(block(c[0], c[1] + 2, c[2]))) continue;
                    if (dy < 0 && !Semantics.passable(block(nx, c[1], nz))) break;
                    if (stand(nx, ny, nz)) {
                        long n = pack(nx, ny, nz);
                        if (!dist.containsKey(n)) {
                            dist.put(n, d + 1);
                            prev.put(n, k);
                            q.add(n);
                        }
                        break;
                    }
                }
            }
            try {   // ladders up
                if (ladder(block(c[0], c[1], c[2])) && stand(c[0], c[1] + 1, c[2])) {
                    long n = pack(c[0], c[1] + 1, c[2]);
                    if (!dist.containsKey(n)) { dist.put(n, d + 1); prev.put(n, k); q.add(n); }
                }
            } catch (Throwable e) {
                // no ladder
            }
        }
    }

    /** the nearest cell on foot from which a block at x y z can be reached (mined, chopped) */
    long reach(int x, int y, int z) {
        long best = -1;
        int bd = Integer.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = -3; dy <= 1; dy++) {
                    Integer d = dist.get(pack(x + dx, y + dy, z + dz));
                    if (d != null && d < bd) { bd = d; best = pack(x + dx, y + dy, z + dz); }
                }
        return best;
    }

    String route(long k) {
        List<int[]> r = new ArrayList<int[]>();
        for (Long c = k; c != null; c = prev.get(c)) r.add(unpack(c));
        Collections.reverse(r);
        List<int[]> keep = new ArrayList<int[]>();
        for (int i = 0; i < r.size(); i++) {
            if (i == 0 || i == r.size() - 1) { keep.add(r.get(i)); continue; }
            int[] a = r.get(i - 1), b = r.get(i), c = r.get(i + 1);
            boolean turn = (b[0] - a[0]) != (c[0] - b[0]) || (b[2] - a[2]) != (c[2] - b[2]);
            int[] last = keep.get(keep.size() - 1);
            if (turn && Math.abs(b[0] - last[0]) + Math.abs(b[2] - last[2]) + Math.abs(b[1] - last[1]) >= 5) keep.add(b);
        }
        while (keep.size() > 9) keep.remove(keep.size() / 2);
        StringBuilder s = new StringBuilder();
        for (int[] c : keep) s.append(s.length() > 0 ? " -> " : "").append(c[0]).append(' ').append(c[1]).append(' ').append(c[2]);
        return s.toString();
    }

    String onFoot(long k) {
        if (k < 0) return "no walking way found (dig, or it is outside the walk search)";
        return "on foot " + dist.get(k) + " steps: " + route(k);
    }

    static class Ore {
        String name;
        int count, exposed;
        int[] nearest, nearestExposed;
        double nd = 1e9, ned = 1e9;
    }

    static class Tree {
        final List<int[]> cols = new ArrayList<int[]>();   // x, z, ymin, ymax
        final Map<String, Integer> kinds = new LinkedHashMap<String, Integer>();
        boolean leaves;
        int x, y, z;
    }

    static <K> void inc(Map<K, Integer> m, K k, int n) {
        Integer o = m.get(k);
        m.put(k, (o == null ? 0 : o) + n);
    }

    static String top(Map<String, Integer> m, int n) {
        List<Map.Entry<String, Integer>> l = new ArrayList<Map.Entry<String, Integer>>(m.entrySet());
        Collections.sort(l, new Comparator<Map.Entry<String, Integer>>() {
            @Override public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) { return b.getValue() - a.getValue(); }
        });
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < l.size() && i < n; i++) s.append(i > 0 ? ", " : "").append(l.get(i).getKey()).append(" x").append(l.get(i).getValue());
        if (l.size() > n) s.append(", +").append(l.size() - n).append(" more kinds");
        return s.toString();
    }

    double d3(int x, int y, int z) {
        return Math.sqrt((double) (x - cx) * (x - cx) + (double) (y - cy) * (y - cy) + (double) (z - cz) * (z - cz));
    }

    /** focus: all, brief, trees, ores, danger, built, exits, or a block name to find */
    String describe(EntityPlayerMP p, int[] at, int radius, String focus) {
        w = p.field_70170_p;
        cx = at[0]; cy = at[1]; cz = at[2]; r = radius;
        y0 = Math.max(1, cy - 24); y1 = Math.min(254, cy + 48);
        String f = focus == null ? "all" : focus.trim().toLowerCase();
        if (f.isEmpty()) f = "all";
        boolean all = f.equals("all") || f.equals("brief");
        boolean known = all || f.matches("trees?|wood|ores?|dangers?|built|machines|exits?|terrain");
        Semantics sem = mod.semantics;
        Map<Long, String[]> woodCols = new LinkedHashMap<Long, String[]>();   // column -> name, ymin, ymax
        Map<String, Ore> ores = new LinkedHashMap<String, Ore>();
        Map<String, Integer> surface = new HashMap<String, Integer>(), dangers = new LinkedHashMap<String, Integer>(), unclear = new HashMap<String, Integer>();
        Map<String, int[]> dangerNear = new HashMap<String, int[]>();
        int[] water = null;
        double wd = 1e9;
        int waterN = 0, columns = 0;
        List<int[]> found = new ArrayList<int[]>();
        Map<String, Integer> foundNames = new LinkedHashMap<String, Integer>();
        for (int x = cx - r; x <= cx + r; x++)
            for (int z = cz - r; z <= cz + r; z++) {
                if (!w.func_72899_e(x, 64, z)) continue;
                columns++;
                boolean surf = false;
                int hy = w.func_72976_f(x, z);
                for (int y = y1; y >= y0; y--) {
                    Block b = block(x, y, z);
                    if (b.func_149688_o() == net.minecraft.block.material.Material.field_151579_a) continue;
                    int meta = w.func_72805_g(x, y, z);
                    String l = sem.labels(b, meta);
                    if (!surf && y < hy && !l.contains("leaves") && !l.contains("wood") && !l.contains("plant") && !l.equals("decor")) {
                        surf = true;
                        inc(surface, name(b, meta), 1);
                    }
                    if (!known) {
                        String n = name(b, meta);
                        if (n.toLowerCase().contains(f) || Semantics.key(b, meta).toLowerCase().contains(f)) {
                            found.add(new int[] { x, y, z });
                            inc(foundNames, n, 1);
                        }
                        continue;
                    }
                    if (l.contains("wood") && !l.contains("machine")) {
                        long k = pack(x, 0, z);
                        String[] c = woodCols.get(k);
                        if (c == null) woodCols.put(k, new String[] { name(b, meta), "" + y, "" + y });
                        else c[1] = "" + y;
                    }
                    if (l.contains("ore")) {
                        String n = name(b, meta);
                        Ore o = ores.get(n);
                        if (o == null) { ores.put(n, o = new Ore()); o.name = n; }
                        o.count++;
                        double d = d3(x, y, z);
                        if (d < o.nd) { o.nd = d; o.nearest = new int[] { x, y, z }; }
                        boolean exp = false;
                        for (int[] s : new int[][] { { 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 }, { 0, 0, 1 }, { 0, 0, -1 } })
                            if (Semantics.passable(block(x + s[0], y + s[1], z + s[2]))) { exp = true; break; }
                        if (exp) {
                            o.exposed++;
                            if (d < o.ned) { o.ned = d; o.nearestExposed = new int[] { x, y, z }; }
                        }
                    }
                    if (l.contains("danger")) {
                        String n = name(b, meta);
                        if (n.equals(b.func_149739_a()) || n.startsWith("tile.")) n = String.valueOf(Block.field_149771_c.func_148750_c(b));
                        if (n.contains("lava") || n.contains("Lava")) n = "lava";
                        inc(dangers, n, 1);
                        int[] old = dangerNear.get(n);
                        if (old == null || d3(x, y, z) < d3(old[0], old[1], old[2])) dangerNear.put(n, new int[] { x, y, z });
                    }
                    if (l.contains("water")) {
                        waterN++;
                        double d = d3(x, y, z);
                        if (d < wd) { wd = d; water = new int[] { x, y, z }; }
                    }
                    if (l.equals("?")) inc(unclear, name(b, meta), 1);
                }
            }
        walk((int) Math.floor(p.field_70165_t), (int) Math.floor(p.field_70163_u), (int) Math.floor(p.field_70161_v), 120000);
        StringBuilder out = new StringBuilder();
        int px = (int) Math.floor(p.field_70165_t), py = (int) Math.floor(p.field_70163_u), pz = (int) Math.floor(p.field_70161_v);
        boolean sky = w.func_72937_j(px, py + 1, pz);
        String biome;
        try {
            biome = w.func_72807_a(px, pz).field_76791_y;
        } catch (Throwable e) {
            biome = "?";
        }
        int ground = w.func_72976_f(px, pz);
        out.append("Area within ").append(r).append(" blocks of ").append(cx).append(' ').append(cy).append(' ').append(cz).append(" (y ").append(y0).append("..")
                .append(y1).append("; north is -z, east is +x), biome ").append(biome).append(".\n");
        out.append(sky ? "The player stands in the open (sky above).\n"
                : "The player is under cover (no sky above): inside a building, a hill or underground; the ground surface over them is at y " + ground + ".\n");
        long exit = -1;
        if (!sky && (all || f.startsWith("exit") || f.startsWith("tree") || f.equals("wood"))) {
            int bd = Integer.MAX_VALUE;
            for (Map.Entry<Long, Integer> e : dist.entrySet()) {
                int[] c = unpack(e.getKey());
                if (e.getValue() < bd && w.func_72937_j(c[0], c[1] + 1, c[2])) { bd = e.getValue(); exit = e.getKey(); }
            }
            out.append("Way out to the open sky: ").append(exit < 0 ? "none found on foot within the area (dig up)" : onFoot(exit)).append(".\n");
        }
        out.append("Walk search: ").append(dist.size()).append(" places reachable on foot within the area.\n");
        if (!known) {
            Collections.sort(found, new Comparator<int[]>() {
                @Override public int compare(int[] a, int[] b) { return Double.compare(d3(a[0], a[1], a[2]), d3(b[0], b[1], b[2])); }
            });
            if (found.isEmpty()) return out.append("No block named like '").append(focus).append("' within the area.").toString();
            out.append(found.size()).append(" blocks named like '").append(focus).append("': ").append(top(foundNames, 6)).append(". Nearest:\n");
            for (int i = 0; i < found.size() && i < 8; i++) {
                int[] c = found.get(i);
                long k = reach(c[0], c[1], c[2]);
                out.append("- ").append(name(block(c[0], c[1], c[2]), w.func_72805_g(c[0], c[1], c[2]))).append(" at ").append(where(c[0], c[1], c[2]))
                        .append(k < 0 ? ", no walking way to it" : ", on foot " + dist.get(k) + " steps").append('\n');
            }
            if (!found.isEmpty()) {
                int[] c = found.get(0);
                long k = reach(c[0], c[1], c[2]);
                if (k >= 0) out.append("Route to the nearest: ").append(route(k)).append('\n');
            }
            return out.toString();
        }
        int total = 0;
        for (int v : surface.values()) total += v;
        if (all || f.equals("terrain")) {
            out.append("Ground surface (top blocks of ").append(total).append(" columns): ");
            List<Map.Entry<String, Integer>> l = new ArrayList<Map.Entry<String, Integer>>(surface.entrySet());
            Collections.sort(l, new Comparator<Map.Entry<String, Integer>>() {
                @Override public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) { return b.getValue() - a.getValue(); }
            });
            for (int i = 0; i < l.size() && i < 5; i++)
                out.append(i > 0 ? ", " : "").append(l.get(i).getKey()).append(' ').append(100 * l.get(i).getValue() / Math.max(1, total)).append('%');
            out.append(".\n");
        }
        if (all || f.startsWith("tree") || f.equals("wood")) trees(out, woodCols, f.equals("brief") ? 2 : 6);
        if (all || f.startsWith("ore")) {
            List<Ore> l = new ArrayList<Ore>(ores.values());
            Collections.sort(l, new Comparator<Ore>() {
                @Override public int compare(Ore a, Ore b) { return Double.compare(Math.min(a.ned, a.nd + 8), Math.min(b.ned, b.nd + 8)); }
            });
            if (l.isEmpty()) out.append("Ores: none within the area.\n");
            else {
                out.append("Ores (exposed = touching air, seen from a cave or tunnel; buried = inside rock, dig to it):\n");
                for (int i = 0; i < l.size() && i < (f.equals("brief") ? 4 : 12); i++) {
                    Ore o = l.get(i);
                    out.append("- ").append(o.name).append(": ").append(o.count).append(" blocks, ").append(o.exposed).append(" exposed");
                    if (o.nearestExposed != null) {
                        int[] c = o.nearestExposed;
                        long k = reach(c[0], c[1], c[2]);
                        out.append("; nearest exposed ").append(where(c[0], c[1], c[2])).append(k < 0 ? ", no walking way" : ", on foot " + dist.get(k) + " steps");
                    }
                    if (o.nearest != null && (o.nearestExposed == null || o.nd + 3 < o.ned))
                        out.append("; nearest buried ").append(where(o.nearest[0], o.nearest[1], o.nearest[2]));
                    out.append('\n');
                }
            }
        }
        if (all || f.startsWith("danger")) {
            out.append("Liquids and dangers: ");
            if (dangers.isEmpty()) out.append("no lava, fire or spawners");
            int i = 0;
            for (Map.Entry<String, Integer> e : dangers.entrySet()) {
                int[] c = dangerNear.get(e.getKey());
                out.append(i++ > 0 ? "; " : "").append(e.getKey()).append(" x").append(e.getValue()).append(", nearest ").append(where(c[0], c[1], c[2]));
            }
            out.append(water == null ? "; no water" : "; water x" + waterN + ", nearest " + where(water[0], water[1], water[2])).append(".\n");
        }
        if (all || f.equals("built") || f.equals("machines")) built(out, p, f.equals("brief") ? 8 : 20);
        if (!unclear.isEmpty() && !f.equals("brief")) out.append("Blocks without a label: ").append(top(unclear, 8)).append(".\n");
        return out.toString();
    }

    void trees(StringBuilder out, Map<Long, String[]> woodCols, int max) {
        List<Tree> trees = new ArrayList<Tree>();
        Map<Long, Tree> of = new HashMap<Long, Tree>();
        for (Map.Entry<Long, String[]> e : woodCols.entrySet()) {
            int[] c = unpack(e.getKey());
            Tree t = null;
            for (int dx = -1; dx <= 1 && t == null; dx++)
                for (int dz = -1; dz <= 1 && t == null; dz++) t = of.get(pack(c[0] + dx, 0, c[2] + dz));
            if (t == null) { t = new Tree(); trees.add(t); }
            int ymin = Integer.parseInt(e.getValue()[1]), ymax = Integer.parseInt(e.getValue()[2]);
            t.cols.add(new int[] { c[0], c[2], ymin, ymax });
            inc(t.kinds, e.getValue()[0], ymax - ymin + 1);
            of.put(e.getKey(), t);
        }
        // a tree has leaves over it; logs without leaves are someone's build
        List<Tree> real = new ArrayList<Tree>();
        int builds = 0;
        for (Tree t : trees) {
            int[] c = t.cols.get(0);
            t.x = c[0]; t.z = c[1]; t.y = c[2];
            for (int[] k : t.cols) if (k[3] > c[3]) c = k;
            for (int dx = -2; dx <= 2 && !t.leaves; dx++)
                for (int dz = -2; dz <= 2 && !t.leaves; dz++)
                    for (int dy = -1; dy <= 2 && !t.leaves; dy++)
                        if (mod.semantics.labels(block(c[0] + dx, c[3] + dy, c[1] + dz), w.func_72805_g(c[0] + dx, c[3] + dy, c[1] + dz)).contains("leaves")) t.leaves = true;
            if (t.leaves) real.add(t);
            else builds++;
        }
        if (real.isEmpty()) {
            out.append("Trees: none within ").append(r).append(" blocks").append(builds > 0 ? " (" + builds + " log columns without leaves: builds)" : "").append(".\n");
            return;
        }
        // groves: trees within 8 blocks of each other
        List<List<Tree>> groves = new ArrayList<List<Tree>>();
        for (Tree t : real) {
            List<Tree> into = null;
            for (List<Tree> g : groves) {
                for (Tree o : g) if (Math.abs(o.x - t.x) <= 8 && Math.abs(o.z - t.z) <= 8) { into = g; break; }
                if (into != null) break;
            }
            if (into == null) groves.add(into = new ArrayList<Tree>());
            into.add(t);
        }
        final Map<List<Tree>, Long> best = new HashMap<List<Tree>, Long>();
        final Map<List<Tree>, Double> near = new HashMap<List<Tree>, Double>();
        for (List<Tree> g : groves) {
            long bk = -1;
            double nd = 1e9;
            for (Tree t : g) {
                long k = reach(t.x, t.y, t.z);
                if (k >= 0 && (bk < 0 || dist.get(k) < dist.get(bk))) bk = k;
                nd = Math.min(nd, d3(t.x, t.y, t.z));
            }
            best.put(g, bk);
            near.put(g, nd);
        }
        Collections.sort(groves, new Comparator<List<Tree>>() {
            @Override public int compare(List<Tree> a, List<Tree> b) {
                long ka = best.get(a), kb = best.get(b);
                double va = ka < 0 ? 1000 + near.get(a) : dist.get(ka), vb = kb < 0 ? 1000 + near.get(b) : dist.get(kb);
                return Double.compare(va, vb);
            }
        });
        Map<String, Integer> kinds = new LinkedHashMap<String, Integer>();
        for (Tree t : real) inc(kinds, t.kinds.keySet().iterator().next(), 1);
        out.append("Trees: ").append(real.size()).append(" (by wood: ").append(top(kinds, 4)).append(") in ").append(groves.size())
                .append(" groups, nearest on foot first:\n");
        for (int i = 0; i < groves.size() && i < max; i++) {
            List<Tree> g = groves.get(i);
            Map<String, Integer> k = new LinkedHashMap<String, Integer>();
            int sx = 0, sy = 0, sz = 0;
            for (Tree t : g) { inc(k, t.kinds.keySet().iterator().next(), 1); sx += t.x; sy += t.y; sz += t.z; }
            int n = g.size();
            out.append("- ").append(n == 1 ? "a lone tree" : "a grove of " + n + " trees").append(" (").append(top(k, 3)).append(") around ")
                    .append(where(sx / n, sy / n, sz / n)).append("; ").append(onFoot(best.get(g))).append('\n');
        }
        if (builds > 0) out.append("(").append(builds).append(" more log columns without leaves: parts of builds.)\n");
    }

    void built(StringBuilder out, EntityPlayerMP p, int max) {
        Map<String, Integer> machines = new LinkedHashMap<String, Integer>(), storage = new LinkedHashMap<String, Integer>(), pipes = new LinkedHashMap<String, Integer>();
        Map<String, int[]> at = new HashMap<String, int[]>();
        int rr = Math.min(r, 24);
        for (Object o : new ArrayList<Object>(w.field_147482_g)) {
            TileEntity t = (TileEntity) o;
            if (t.func_145837_r() || Math.abs(t.field_145851_c - cx) > rr || Math.abs(t.field_145849_e - cz) > rr || Math.abs(t.field_145848_d - cy) > 16) continue;
            Block b = block(t.field_145851_c, t.field_145848_d, t.field_145849_e);
            int meta = w.func_72805_g(t.field_145851_c, t.field_145848_d, t.field_145849_e);
            String n = name(b, meta);
            String cls = t.getClass().getName().toLowerCase();
            Map<String, Integer> into = cls.matches(".*(pipe|cable|duct|conduit|wire).*") ? pipes
                    : t instanceof net.minecraft.inventory.IInventory && cls.matches(".*(chest|barrel|drawer|crate|storage|hopper).*") ? storage : machines;
            if (cls.contains("mobspawner")) n = "monster spawner (danger)";
            inc(into, n, 1);
            int[] old = at.get(n);
            if (old == null || d3(t.field_145851_c, t.field_145848_d, t.field_145849_e) < d3(old[0], old[1], old[2]))
                at.put(n, new int[] { t.field_145851_c, t.field_145848_d, t.field_145849_e });
        }
        out.append("Built within ").append(rr).append(" blocks (blocks with tile entities; a multiblock machine counts each block):\n");
        out.append("- machines: ").append(machines.isEmpty() ? "none" : withPlace(machines, at, max)).append('\n');
        out.append("- storage: ").append(storage.isEmpty() ? "none" : withPlace(storage, at, 6)).append('\n');
        out.append("- pipes and cables: ").append(pipes.isEmpty() ? "none" : top(pipes, 6)).append('\n');
    }

    String withPlace(final Map<String, Integer> m, Map<String, int[]> at, int n) {
        List<Map.Entry<String, Integer>> l = new ArrayList<Map.Entry<String, Integer>>(m.entrySet());
        Collections.sort(l, new Comparator<Map.Entry<String, Integer>>() {
            @Override public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) { return b.getValue() - a.getValue(); }
        });
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < l.size() && i < n; i++) {
            int[] c = at.get(l.get(i).getKey());
            s.append(i > 0 ? ", " : "").append(l.get(i).getKey()).append(" x").append(l.get(i).getValue()).append(" (nearest ").append(c[0]).append(' ').append(c[1])
                    .append(' ').append(c[2]).append(')');
        }
        if (l.size() > n) s.append(", +").append(l.size() - n).append(" more kinds");
        return s.toString();
    }
}
