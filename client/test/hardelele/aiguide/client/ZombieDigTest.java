package hardelele.aiguide.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * The digging zombies' rules without the game (ZombieDig, docs/rl/ZOMBIES-DIG.md): the tiers (a zombie's tool and its
 * share by the wave), the time a block takes by its hardness and the tool (a shovel soil, a pickaxe rock; obsidian only
 * a diamond pickaxe, bedrock none), how far an agent is heard, the way to an agent underground over a model of the
 * ground. Checks the built-in numbers against rl/zombie-dig.json. Prints the tables (docs and the simulators' "dig").
 */
public final class ZombieDigTest {
    static int bad;

    static void check(boolean ok, String what) {
        if (!ok) { bad++; System.out.println("FAIL " + what); }
    }

    /** a model of the ground: air from y = 64, soil of the given depth under it, then stone; some cells replaced (obsidian); the digger's tool decides the ticks */
    static final class Ground implements ZombieDig.Terrain {
        final int soil;
        final ZombieDig.Tool tool;
        final ZombieDig.Params p;
        final Map<Long, Float> over = new HashMap<Long, Float>();   // -> hardness; 0 broken
        int obsidianFrom = -1;

        Ground(int soil, ZombieDig.Tool tool, ZombieDig.Params p) { this.soil = soil; this.tool = tool; this.p = p; }

        static long key(int x, int y, int z) { return ((long) (x + 1000) << 40) | ((long) (y + 1000) << 20) | (z + 1000); }

        public float cell(int x, int y, int z) {
            Float o = over.get(key(x, y, z));
            if (o != null && o == 0f) return ZombieDig.PASS;
            if (y >= 64) return ZombieDig.PASS;
            if (y < 1) return ZombieDig.NEVER;
            int t;
            if (o != null && o >= 50) t = ZombieDig.ticks(tool, ZombieDig.Mat.ROCK, 50f, true, 3, p);
            else if (y >= 64 - soil) t = ZombieDig.ticks(tool, ZombieDig.Mat.SOIL, 0.5f, false, 0, p);
            else t = ZombieDig.ticks(tool, ZombieDig.Mat.ROCK, 1.5f, true, 0, p);
            return t < 0 ? ZombieDig.NEVER : t;
        }
    }

    /** seconds a zombie takes from the surface to an agent d blocks under it (the ticks of the breaking plus 0.25 s a block of walking); -1 when it cannot */
    static double dig(Ground g, int d, int fromX, int[] blocks) {
        int fx = fromX, fy = 64, fz = 0, moves = 0;
        double tx = 0.5, ty = 64 - d, tz = 0.5, ticks = 0;
        for (int guard = 0; guard < 600; guard++) {
            ZombieDig.Step s = ZombieDig.plan(g, fx, fy, fz, tx, ty, tz, g.p);
            if (s == null) return Math.hypot(fx - tx, fz - tz) < 1.5 && Math.abs(fy - ty) < 2 ? ticks / 20.0 + 0.25 * moves : -1;
            for (int[] c; (c = s.next(g)) != null; ) {
                ticks += g.cell(c[0], c[1], c[2]);
                g.over.put(Ground.key(c[0], c[1], c[2]), 0f);
                if (blocks != null) blocks[0]++;
            }
            fx = s.x; fy = s.y; fz = s.z;
            moves++;
        }
        return -1;
    }

    static void tiers(ZombieDig.Params p) {
        System.out.println("  wave  zombies 1/3/5/10/20: with a tool  | shoveler digger miner breaker shares");
        for (int n = 1; n <= 12; n++) {
            StringBuilder sh = new StringBuilder();
            double sum = 0;
            for (ZombieDig.Tier t : ZombieDig.TIERS) { sh.append(String.format(" %.2f", t.share(n, p))); sum += t.share(n, p); }
            System.out.println(String.format("  %4d  %d %d %d %d %d  |%s%s", n, ZombieDig.diggers(n, 1, p), ZombieDig.diggers(n, 3, p), ZombieDig.diggers(n, 5, p),
                    ZombieDig.diggers(n, 10, p), ZombieDig.diggers(n, 20, p), sh, WavePlan.boss(n) ? "  boss" : ""));
            check(ZombieDig.diggers(n, 100, p) <= 100 * p.tiersMax + 1, "the tiers' total is capped: " + n);
            if (n < 3) check(ZombieDig.TIERS[2].share(n, p) == 0, "no miners before the 3rd wave");
            if (n < 7) check(ZombieDig.TIERS[3].share(n, p) == 0, "no breakers before the 7th wave");
        }
        check(ZombieDig.diggers(1, 1, p) == 0 && ZombieDig.diggers(1, 2, p) <= 1, "none among one zombie at the first wave");
        check(ZombieDig.diggers(1, 3, p) >= 1, "one among three zombies at least");
        check(ZombieDig.diggers(1, 100, p) <= 25, "the first wave: a few");
        int plain = 0;
        for (int k = 0; k < 40; k++) if (ZombieDig.tier(8, k, 40, p) < 0) plain++;
        check(plain >= 40 * 0.25 - 2, "a quarter of the zombies at least stay plain: " + plain);
        for (int k = 0; k < 20; k++) check(ZombieDig.tier(6, k, 20, p) == ZombieDig.tier(6, k, 20, p), "a tier by number only");
    }

    static void blocks(ZombieDig.Params p) {
        ZombieDig.Mat S = ZombieDig.Mat.SOIL, R = ZombieDig.Mat.ROCK;
        check(ZombieDig.ticks(ZombieDig.Tool.HAND, S, 0.5f, false, 0, p) == 45, "soil by hand: 45 ticks");
        check(ZombieDig.ticks(ZombieDig.Tool.IRON_SHOVEL, S, 0.5f, false, 0, p) == 10, "soil by a shovel: 10 ticks (the minimum)");
        check(ZombieDig.ticks(ZombieDig.Tool.HAND, R, 1.5f, true, 0, p) == -1, "stone by hand: never");
        check(ZombieDig.ticks(ZombieDig.Tool.IRON_SHOVEL, R, 1.5f, true, 0, p) == -1, "stone by a shovel: never");
        check(ZombieDig.ticks(ZombieDig.Tool.WOODEN_PICKAXE, R, 1.5f, true, 0, p) == 68, "stone by a wooden pickaxe: 68");
        check(ZombieDig.ticks(ZombieDig.Tool.STONE_PICKAXE, R, 1.5f, true, 0, p) == 34, "stone by a stone pickaxe: 34");
        check(ZombieDig.ticks(ZombieDig.Tool.IRON_PICKAXE, R, 1.5f, true, 0, p) == 23, "stone by an iron pickaxe: 23");
        check(ZombieDig.ticks(ZombieDig.Tool.WOODEN_PICKAXE, R, 3f, true, 1, p) == -1, "iron ore by a wooden pickaxe: never");
        check(ZombieDig.ticks(ZombieDig.Tool.STONE_PICKAXE, R, 3f, true, 1, p) > 0, "iron ore by a stone pickaxe");
        check(ZombieDig.ticks(ZombieDig.Tool.STONE_PICKAXE, R, 3f, true, 2, p) == -1, "diamond ore by a stone pickaxe: never");
        check(ZombieDig.ticks(ZombieDig.Tool.IRON_PICKAXE, R, 50f, true, 3, p) == -1, "obsidian by an iron pickaxe: never");
        check(ZombieDig.ticks(ZombieDig.Tool.DIAMOND_PICKAXE, R, 50f, true, 3, p) == 563, "obsidian by a diamond pickaxe: 563 ticks");
        check(ZombieDig.ticks(ZombieDig.Tool.DIAMOND_PICKAXE, R, -1f, true, 0, p) == -1, "bedrock by none");
        check(ZombieDig.ticks(ZombieDig.Tool.HAND, ZombieDig.Mat.OTHER, 0.1f, false, 0, p) == p.minTicks, "a soft block takes the minimum");
        check(ZombieDig.mode(null) == ZombieDig.Mode.BELOW && ZombieDig.mode("off") == ZombieDig.Mode.OFF && ZombieDig.mode("all") == ZombieDig.Mode.ALL, "the modes' words");
    }

    static void hearing(ZombieDig.Params p) {
        check(ZombieDig.hear(0, p) == 24 && ZombieDig.hear(20, p) == 24, "24 blocks at once and for 20 s");
        check(Math.abs(ZombieDig.hear(70, p) - 44) < 1e-9, "+0.4 a second after that");
        check(ZombieDig.hear(1000, p) == 48, "up to 48");
    }

    static void ways(ZombieDig.Params p) {
        System.out.println("  seconds to an agent under the ground (a soil layer of 5, then stone; a zombie 10 blocks off); - never");
        System.out.println("  depth   shovel  wood-pick  stone-pick  iron-pick  diamond-pick");
        ZombieDig.Tool[] tools = { ZombieDig.Tool.IRON_SHOVEL, ZombieDig.Tool.WOODEN_PICKAXE, ZombieDig.Tool.STONE_PICKAXE, ZombieDig.Tool.IRON_PICKAXE, ZombieDig.Tool.DIAMOND_PICKAXE };
        double[][] t = new double[3][tools.length];
        int di = 0;
        for (int d : new int[] { 12, 24, 48 }) {
            StringBuilder b = new StringBuilder(String.format("  %5d", d));
            for (int i = 0; i < tools.length; i++) {
                double s = dig(new Ground(5, tools[i], p), d, 10, null);
                t[di][i] = s;
                b.append(s < 0 ? "          -" : String.format(" %10.1f", s));
            }
            System.out.println(b);
            di++;
        }
        for (int i = 0; i < 3; i++) {
            check(t[i][0] < 0, "a shovel does not get through stone: " + i);
            check(t[i][1] > t[i][2] && t[i][2] > t[i][3] && t[i][3] >= t[i][4], "a better pickaxe is not slower: " + i);
        }
        check(t[0][2] < t[1][2] && t[1][2] < t[2][2], "deeper takes longer");
        int[] n = new int[1];
        dig(new Ground(5, ZombieDig.Tool.STONE_PICKAXE, p), 24, 10, n);
        check(n[0] <= 3 * 24 + 30, "no more than ~3 cells a level: " + n[0]);
        // a floor of obsidian across the way: only the diamond pickaxe passes
        for (ZombieDig.Tool tool : new ZombieDig.Tool[] { ZombieDig.Tool.IRON_PICKAXE, ZombieDig.Tool.DIAMOND_PICKAXE }) {
            Ground g = new Ground(5, tool, p);
            for (int x = -40; x <= 40; x++) for (int z = -40; z <= 40; z++) g.over.put(Ground.key(x, 50, z), 50f);
            double s = dig(g, 24, 10, null);
            check(tool == ZombieDig.Tool.IRON_PICKAXE ? s < 0 : s > 0, "an obsidian floor: " + tool + " " + s);
            System.out.println("  with an obsidian floor at 14 blocks, " + tool + ": " + (s < 0 ? "never" : String.format("%.1f s", s)) + " to 24 blocks");
        }
        ZombieDig.Step u = ZombieDig.plan(new Ground(5, ZombieDig.Tool.STONE_PICKAXE, p), 0, 64, 0, 0.5, 40, 0.5, p);
        check(u != null && u.y == 63, "right above: a step down");
        check(ZombieDig.plan(new Ground(5, ZombieDig.Tool.STONE_PICKAXE, p), 0, 40, 0, 0.5, 40, 0.5, p) == null, "there: no step");
        Ground w = new Ground(5, ZombieDig.Tool.STONE_PICKAXE, p);
        w.over.put(Ground.key(1, 64, 0), 0f);
        ZombieDig.Terrain lava = new ZombieDig.Terrain() {
            public float cell(int x, int y, int z) { return x == 1 && z == 0 && y >= 64 ? ZombieDig.LIQUID : ZombieDig.PASS; }
        };
        ZombieDig.Step l = ZombieDig.plan(lava, 0, 64, 0, 10.5, 64, 0.5, p);
        check(l == null || !(l.x == 1 && l.z == 0), "not through a liquid");
    }

    /** the built-in tiers and numbers are rl/zombie-dig.json's */
    static void file(ZombieDig.Params p) throws Exception {
        File f = new File("rl/zombie-dig.json");
        if (!f.isFile()) { System.out.println("  (no rl/zombie-dig.json from here: the file is not checked)"); return; }
        JsonObject o = new JsonParser().parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject q = o.getAsJsonObject("params");
        check(q.get("slow").getAsDouble() == p.slow && q.get("min_ticks").getAsInt() == p.minTicks && q.get("max_ticks").getAsInt() == p.maxTicks, "the file's break numbers");
        check(q.get("boss_mult").getAsDouble() == p.bossMult && q.get("tiers_max").getAsDouble() == p.tiersMax, "the file's boss and cap");
        check(q.get("detect").getAsDouble() == p.detect && q.get("hear_grow").getAsDouble() == p.hearGrow && q.get("hear_grace").getAsDouble() == p.hearGrace
                && q.get("hear_max").getAsDouble() == p.hearMax && q.get("below").getAsInt() == p.below && q.get("max_at_once").getAsInt() == p.max, "the file's hearing numbers");
        JsonObject tools = o.getAsJsonObject("tools");
        for (ZombieDig.Tool t : ZombieDig.Tool.values()) {
            JsonObject j = tools.getAsJsonObject(t.name().toLowerCase());
            check(j != null && j.get("speed").getAsDouble() == t.speed && j.get("level").getAsInt() == t.level, "the file's tool " + t);
        }
        JsonArray a = o.getAsJsonArray("tiers");
        check(a.size() == ZombieDig.TIERS.length, "the file's tiers' number");
        int i = 0;
        for (JsonElement e : a) {
            JsonObject j = e.getAsJsonObject();
            ZombieDig.Tier t = ZombieDig.TIERS[i++];
            check(j.get("name").getAsString().equals(t.name) && j.get("tool").getAsString().equals(t.tool.name().toLowerCase()) && j.get("from").getAsInt() == t.from
                    && j.get("base").getAsDouble() == t.base && j.get("step").getAsDouble() == t.step && j.get("max").getAsDouble() == t.max, "the file's tier " + t.name);
        }
    }

    public static void main(String[] a) throws Exception {
        ZombieDig.Params p = new ZombieDig.Params();
        tiers(p);
        blocks(p);
        hearing(p);
        ways(p);
        file(p);
        System.out.println(bad == 0 ? "ZombieDigTest ok" : "ZombieDigTest FAILED: " + bad);
        System.exit(bad == 0 ? 0 : 1);
    }
}
