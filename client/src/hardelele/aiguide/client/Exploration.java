package hardelele.aiguide.client;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;

/** Server-side observations, not a survey of blocks hidden inside the world. */
public final class Exploration {
    private Exploration() { }
    static final int RANGE = 16, PERIOD = 20, LIMIT = 8192, TRAIL_LIMIT = 2048;
    private static final Map<WorldServer, Memory> worlds = new WeakHashMap<WorldServer, Memory>();
    private static boolean started;

    static synchronized void start() {
        if (started) return;
        MinecraftForge.EVENT_BUS.register(new Hooks());
        started = true;
    }
    public static final class Hooks {
        @SubscribeEvent public void saveWorld(WorldEvent.Save event) {
            if (event.world instanceof WorldServer) save((WorldServer) event.world);
        }
        @SubscribeEvent public void unloadWorld(WorldEvent.Unload event) {
            if (!(event.world instanceof WorldServer)) return;
            WorldServer w = (WorldServer) event.world;
            synchronized (Exploration.class) { save(w); worlds.remove(w); }
        }
    }

    static final class Pos {
        final int x, y, z;
        Pos(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        int[] array() { return new int[] { x, y, z }; }
        public int hashCode() { return (x * 31 + y) * 31 + z; }
        public boolean equals(Object o) { if (!(o instanceof Pos)) return false; Pos p = (Pos) o; return x == p.x && y == p.y && z == p.z; }
    }
    static final class Hit {
        final Pos p; final int face;
        Hit(int x, int y, int z, int face) { p = new Pos(x, y, z); this.face = face; }
    }
    /** Small test seam: all block reads occur after the ray has exposed that surface. */
    interface View {
        Hit ray(double x, double y, double z, double tx, double ty, double tz);
        /** Selection surfaces include plants; collision rays still establish safe solid ground. */
        default Hit surfaceRay(double x, double y, double z, double tx, double ty, double tz) { return ray(x, y, z, tx, ty, tz); }
        boolean loaded(double x, double z, double tx, double tz);
        String block(Pos p);
        boolean floor(Pos p);
        boolean air(Pos p);
        default boolean passable(Pos p) { return air(p); }
    }
    static final class Memory {
        final LinkedHashMap<Pos, String> seen = new LinkedHashMap<Pos, String>();
        final LinkedHashSet<Pos> ground = new LinkedHashSet<Pos>();
        final LinkedHashSet<Pos> trail = new LinkedHashSet<Pos>();
        final Map<Integer, Long> next = new HashMap<Integer, Long>();
        int sweep;
    }
    private static View view(final WorldServer w) {
        return new View() {
            public boolean loaded(double x, double z, double tx, double tz) {
                int x0 = ((int) Math.floor(Math.min(x, tx))) >> 4, x1 = ((int) Math.floor(Math.max(x, tx))) >> 4;
                int z0 = ((int) Math.floor(Math.min(z, tz))) >> 4, z1 = ((int) Math.floor(Math.max(z, tz))) >> 4;
                for (int cx = x0; cx <= x1; cx++) for (int cz = z0; cz <= z1; cz++)
                    if (!w.getChunkProvider().chunkExists(cx, cz)) return false;
                return true;
            }
            public Hit ray(double x, double y, double z, double tx, double ty, double tz) {
                MovingObjectPosition h = w.rayTraceBlocks(Vec3.createVectorHelper(x, y, z), Vec3.createVectorHelper(tx, ty, tz), false, true, false);
                return h == null ? null : new Hit(h.blockX, h.blockY, h.blockZ, h.sideHit);
            }
            public Hit surfaceRay(double x, double y, double z, double tx, double ty, double tz) {
                MovingObjectPosition h = w.rayTraceBlocks(Vec3.createVectorHelper(x, y, z), Vec3.createVectorHelper(tx, ty, tz), false, false, false);
                return h == null ? null : new Hit(h.blockX, h.blockY, h.blockZ, h.sideHit);
            }
            public String block(Pos p) { return Block.blockRegistry.getNameForObject(w.getBlock(p.x, p.y, p.z)); }
            public boolean floor(Pos p) { return w.getBlock(p.x, p.y, p.z).isNormalCube(); }
            public boolean air(Pos p) { return w.isAirBlock(p.x, p.y, p.z); }
            public boolean passable(Pos p) {
                Block b = w.getBlock(p.x, p.y, p.z);
                return w.isAirBlock(p.x, p.y, p.z) || b instanceof net.minecraft.block.BlockBush;
            }
        };
    }
    private static Memory memory(WorldServer w) {
        Memory m = worlds.get(w);
        if (m == null) { load(w); m = worlds.get(w); }
        return m;
    }
    static synchronized void observe(Crew c, Agent a, WorldServer w) {
        if (a == null || w == null || a.worldObj != w || a.isDead || !a.onGround) return;
        Memory m = memory(w);
        long now = w.getTotalWorldTime();
        Long due = m.next.get(a.getEntityId());
        if (due != null && now < due && due - now <= PERIOD) return;
        if (m.next.size() >= 128 && !m.next.containsKey(a.getEntityId())) m.next.clear();
        m.next.put(a.getEntityId(), now + PERIOD);
        observer = a;
        try { scan(m, view(w), a.posX, a.posY + a.getEyeHeight(), a.posZ, (int) Math.floor(a.boundingBox.minY)); } finally { observer = null; }
    }

    /** (3.1) who looks now, and who hears of a block seen for the first time (RlEnv: what a scout found) */
    static Agent observer;
    interface Finds { void found(Agent a, int x, int y, int z, String block); }
    static Finds finds;
    static void scan(Memory m, View v, double x, double eyeY, double z, int feetY) {
        Pos feet = new Pos((int) Math.floor(x), feetY, (int) Math.floor(z));
        m.trail.remove(feet); m.trail.add(feet); trim(m.trail, TRAIL_LIMIT);
        // Nearby floor samples establish a connected walkable surface, including one-block stairs.
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++)
            if (Math.abs(dx) + Math.abs(dz) <= 2) for (int dy = -1; dy <= 1; dy++)
                cast(m, v, x, eyeY, z, feet.x + dx + .5, feetY + dy - .5, feet.z + dz + .5);
        // Distributed directions rotate between observations; only the FIRST hit becomes knowledge.
        int phase = m.sweep++;
        for (int i = 0; i < 72; i++) {
            double vy = 1 - 2 * (i + .5) / 72, radius = Math.sqrt(1 - vy * vy);
            double angle = i * 2.399963229728653 + phase * .37;
            cast(m, v, x, eyeY, z, x + RANGE * radius * Math.cos(angle), eyeY + RANGE * vy, z + RANGE * radius * Math.sin(angle));
        }
        // Re-check a bounded rotating sample of remembered surfaces. Occlusion preserves a memory;
        // a clear ray through its old position removes it. Never inspect it through a new wall.
        List<Pos> old = new ArrayList<Pos>(m.seen.keySet());
        for (int i = 0; i < Math.min(16, old.size()); i++) {
            Pos p = old.get(Math.floorMod(phase * 16 + i, old.size()));
            look(m, v, x, eyeY, z, p);
        }
    }
    private static void cast(Memory m, View v, double x, double y, double z, double tx, double ty, double tz) {
        if (ty < 0 || ty >= 256 || !v.loaded(x, z, tx, tz)) return;
        Hit surface = v.surfaceRay(x, y, z, tx, ty, tz);
        if (surface != null) {
            remember(m, surface.p, v.block(surface.p));
            if (!v.passable(surface.p)) m.ground.remove(surface.p);
        }
        Hit h = v.ray(x, y, z, tx, ty, tz);
        if (h == null) return;
        remember(m, h.p, v.block(h.p));
        if (h.face != 1 || !v.floor(h.p)) return;
        Pos feet = new Pos(h.p.x, h.p.y + 1, h.p.z), head = new Pos(h.p.x, h.p.y + 2, h.p.z);
        if (head.y >= 256) return;
        // Collision rays expose the spaces before reading them. Non-solid plants permit walking;
        // fences, low roofs and liquids remain excluded, and surfaceRay remembers the plants themselves.
        if (v.ray(x, y, z, feet.x + .5, feet.y + .5, feet.z + .5) == null &&
            v.ray(x, y, z, head.x + .5, head.y + .5, head.z + .5) == null && v.passable(feet) && v.passable(head)) {
            m.ground.remove(feet); m.ground.add(feet); trim(m.ground, LIMIT);
        } else m.ground.remove(feet);
    }
    private static void remember(Memory m, Pos p, String block) {
        boolean fresh = !m.seen.containsKey(p);
        if (fresh && observer != null && finds != null && block != null) finds.found(observer, p.x, p.y, p.z, block);
        m.seen.remove(p);
        if (block != null && !block.equals("minecraft:air")) m.seen.put(p, block);
        while (m.seen.size() > LIMIT) m.seen.remove(m.seen.keySet().iterator().next());
    }
    static boolean look(Memory m, View v, double x, double y, double z, Pos p) {
        double dx = p.x + .5 - x, dy = p.y + .5 - y, dz = p.z + .5 - z;
        if (p.y < 0 || p.y >= 256 || dx * dx + dy * dy + dz * dz > RANGE * RANGE || !v.loaded(x, z, p.x + .5, p.z + .5)) return false;
        double[][] points = { {.5,.5,.5}, {.001,.5,.5}, {.999,.5,.5}, {.5,.001,.5}, {.5,.999,.5}, {.5,.5,.001}, {.5,.5,.999} };
        boolean clear = false;
        // A neighbour can hide a block's centre while its near face is plainly exposed (stair mining).
        for (double[] point : points) {
            Hit h = v.surfaceRay(x, y, z, p.x + point[0], p.y + point[1], p.z + point[2]);
            if (h == null) { clear = true; continue; }
            if (!h.p.equals(p)) continue;
            remember(m, p, v.block(p));
            return true;
        }
        if (clear) forget(m, p);
        return false;
    }
    static synchronized boolean visible(Agent a, WorldServer w, int x, int y, int z) {
        return a != null && w != null && a.worldObj == w && look(memory(w), view(w), a.posX, a.posY + a.getEyeHeight(), a.posZ, new Pos(x, y, z));
    }
    /** Last observed block types, intentionally NOT refreshed by a hidden getBlock scan. */
    static synchronized List<int[]> knownBlocks(WorldServer w, Block block) {
        List<int[]> out = new ArrayList<int[]>();
        String name = Block.blockRegistry.getNameForObject(block);
        for (Map.Entry<Pos, String> e : memory(w).seen.entrySet()) if (e.getValue().equals(name)) out.add(e.getKey().array());
        return out;
    }
    /** For the places' input: the remembered blocks and walkable cells inside a box (side cells from x0, y0, z0),
     * and the observation points (feet) at most reach from it. Read only. */
    static synchronized void recall(WorldServer w, int x0, int y0, int z0, int side, int reach,
            List<int[]> seenAt, List<String> seenName, List<int[]> ground, List<int[]> trail) {
        Memory m = memory(w);
        for (Map.Entry<Pos, String> e : m.seen.entrySet()) {
            Pos p = e.getKey();
            if (p.x >= x0 && p.x < x0 + side && p.y >= y0 && p.y < y0 + side && p.z >= z0 && p.z < z0 + side) { seenAt.add(p.array()); seenName.add(e.getValue()); }
        }
        for (Pos p : m.ground) if (p.x >= x0 && p.x < x0 + side && p.y >= y0 && p.y < y0 + side && p.z >= z0 && p.z < z0 + side) ground.add(p.array());
        for (Pos p : m.trail)
            if (p.x >= x0 - reach && p.x < x0 + side + reach && p.y >= y0 - reach && p.y < y0 + side + reach && p.z >= z0 - reach && p.z < z0 + side + reach) trail.add(p.array());
    }
    /** Candidate sources from remembered types, not guaranteed quantities or a query of remote chunks.
     * Metadata-sensitive crops, chance drops and unfamiliar mod blocks need an explicit observation model. */
    static synchronized List<int[]> knownResources(WorldServer w, Item item) {
        List<int[]> out = new ArrayList<int[]>();
        String wanted = Item.itemRegistry.getNameForObject(item);
        for (Map.Entry<Pos, String> e : memory(w).seen.entrySet())
            if (wanted != null && wanted.equals(resource(e.getValue()))) out.add(e.getKey().array());
        return out;
    }
    static String resource(String block) {
        if (block.equals("minecraft:stone")) return "minecraft:cobblestone";
        if (block.equals("minecraft:grass") || block.equals("minecraft:mycelium") || block.equals("minecraft:farmland")) return "minecraft:dirt";
        if (block.equals("minecraft:clay")) return "minecraft:clay_ball";
        if (block.equals("minecraft:reeds")) return "minecraft:reeds";
        if (block.equals("minecraft:melon_block")) return "minecraft:melon";
        if (Arrays.asList("minecraft:log", "minecraft:log2", "minecraft:cobblestone", "minecraft:dirt", "minecraft:sand",
                "minecraft:sandstone", "minecraft:obsidian", "minecraft:planks", "minecraft:pumpkin", "minecraft:cactus",
                "minecraft:brown_mushroom", "minecraft:red_mushroom").contains(block)) return block;
        return null;
    }
    private static void forget(Memory m, Pos p) {
        m.seen.remove(p); m.ground.remove(new Pos(p.x, p.y + 1, p.z));
        m.ground.remove(p); m.ground.remove(new Pos(p.x, p.y - 1, p.z));
    }
    static synchronized void invalidate(WorldServer w, int x, int y, int z) { forget(memory(w), new Pos(x, y, z)); }

    /** each agent's chunk when its memory was last looked over (recheck: a new chunk, a wider look) */
    private static final Map<Integer, Long> lookedFrom = new HashMap<Integer, Long>();

    /**
     * The remembered blocks of these kinds looked at again from the agent's eyes (#1053: a felled tree stayed in the
     * memory and the crew walked to it): look() refreshes one that is seen and forgets one a clear ray passes through.
     * Those within near of the agent; all within RANGE when it has come into another chunk; at most max looks. Honest:
     * only what the eyes reach, nothing through a wall. The places forgotten are returned.
     */
    static synchronized List<int[]> recheck(Agent a, WorldServer w, Set<String> kinds, double near, int max) {
        if (a == null || w == null || a.worldObj != w) return new ArrayList<int[]>();
        long chunk = ((long) (MathHelper.floor_double(a.posX) >> 4) << 32) ^ ((MathHelper.floor_double(a.posZ) >> 4) & 0xffffffffL);
        Long was = lookedFrom.put(a.getEntityId(), chunk);
        return recheck(memory(w), view(w), a.posX, a.posY + a.getEyeHeight(), a.posZ, was == null || was != chunk, kinds, near, max);
    }

    /** recheck from eyes at ex ey ez: within near, or within RANGE after a change of chunk */
    static List<int[]> recheck(Memory m, View v, final double ex, final double ey, final double ez, boolean newChunk, Set<String> kinds, double near, int max) {
        List<int[]> gone = new ArrayList<int[]>();
        double r = newChunk ? RANGE : near;
        List<Pos> cand = new ArrayList<Pos>();
        for (Map.Entry<Pos, String> e : m.seen.entrySet()) {
            if (!kinds.contains(e.getValue())) continue;
            Pos p = e.getKey();
            double dx = p.x + .5 - ex, dy = p.y + .5 - ey, dz = p.z + .5 - ez;
            if (dx * dx + dy * dy + dz * dz <= r * r) cand.add(p);
        }
        Collections.sort(cand, (p, q) -> Double.compare((p.x - ex) * (p.x - ex) + (p.z - ez) * (p.z - ez), (q.x - ex) * (q.x - ex) + (q.z - ez) * (q.z - ez)));
        for (int i = 0; i < cand.size() && i < max; i++) {
            Pos p = cand.get(i);
            look(m, v, ex, ey, ez, p);
            if (!m.seen.containsKey(p)) gone.add(p.array());
        }
        return gone;
    }
    /** how much is remembered: { blocks seen, walkable cells, footprints } (a check of the memory, not of the world) */
    static synchronized int[] size(WorldServer w) { Memory m = memory(w); return new int[] { m.seen.size(), m.ground.size(), m.trail.size() }; }
    /** Nothing seen yet (a learning environment's new episode: the arena put back, the crew new). */
    static synchronized void clear(WorldServer w) { worlds.put(w, new Memory()); lookedFrom.clear(); }
    static synchronized List<int[]> breadcrumbs(WorldServer w) {
        List<int[]> out = new ArrayList<int[]>(); for (Pos p : memory(w).trail) out.add(p.array()); return out;
    }
    static synchronized int[] frontier(Agent a, WorldServer w, int salt) {
        return frontier(a, w, salt, 0, 0);
    }
    static synchronized int[] frontier(Agent a, WorldServer w, int salt, double dx, double dz) {
        return frontier(a, w, salt, dx, dz, null);
    }
    static synchronized int[] frontier(Agent a, WorldServer w, int salt, double dx, double dz, Set<Long> excluded) {
        return frontier(a, w, salt, dx, dz, excluded, false);
    }
    static synchronized int[] frontier(Agent a, WorldServer w, int salt, double dx, double dz, Set<Long> excluded, boolean rememberedRegion) {
        if (a == null || a.worldObj != w) return null;
        observe(Crew.instance, a, w);
        Pos p = choose(memory(w), new Pos((int) Math.floor(a.posX), (int) Math.floor(a.boundingBox.minY), (int) Math.floor(a.posZ)), salt, dx, dz, excluded, rememberedRegion);
        return p == null ? null : p.array();
    }
    /** Only a connected chain of OBSERVED ground leads to a frontier; no unseen height-map probes. */
    static Pos choose(Memory m, Pos start, int salt) {
        return choose(m, start, salt, 0, 0);
    }
    static Pos choose(Memory m, Pos start, int salt, double headingX, double headingZ) {
        return choose(m, start, salt, headingX, headingZ, null);
    }
    static Pos choose(Memory m, Pos start, int salt, double headingX, double headingZ, Set<Long> excluded) {
        return choose(m, start, salt, headingX, headingZ, excluded, false);
    }
    /** Expedition planning may cross remembered ground; sensing remains limited by RANGE. */
    static Pos choose(Memory m, Pos start, int salt, double headingX, double headingZ, Set<Long> excluded, boolean rememberedRegion) {
        List<Pos> candidates = ranked(m, start, headingX, headingZ, excluded, rememberedRegion);
        return candidates.isEmpty() ? null : candidates.get(Math.floorMod(salt, candidates.size()));
    }
    static synchronized List<int[]> frontiers(Agent a, WorldServer w) {
        if (a == null || a.worldObj != w) return Collections.emptyList();
        observe(Crew.instance, a, w);
        Pos start = new Pos((int) Math.floor(a.posX), (int) Math.floor(a.boundingBox.minY), (int) Math.floor(a.posZ));
        List<int[]> result = new ArrayList<int[]>();
        for (Pos p : ranked(memory(w), start, 0, 0, null, false)) result.add(p.array());
        return result;
    }
    static List<Pos> ranked(Memory m, Pos start, double headingX, double headingZ, Set<Long> excluded, boolean rememberedRegion) {
        ArrayDeque<Pos> q = new ArrayDeque<Pos>(); Set<Pos> visited = new HashSet<Pos>();
        q.add(start); visited.add(start);
        final Map<Pos, Double> scores = new HashMap<Pos, Double>();
        final Set<Pos> forward = new HashSet<Pos>();
        Map<Long, List<Pos>> trail = new HashMap<Long, List<Pos>>();
        for (Pos p : m.trail) {
            long key = trailKey(Math.floorDiv(p.x, 4), Math.floorDiv(p.z, 4));
            List<Pos> cells = trail.get(key);
            if (cells == null) { cells = new ArrayList<Pos>(); trail.put(key, cells); }
            cells.add(p);
        }
        List<Pos> candidates = new ArrayList<Pos>();
        int[][] dirs = { {1,0}, {-1,0}, {0,1}, {0,-1} };
        while (!q.isEmpty() && visited.size() <= LIMIT) {
            Pos p = q.remove(); int neighbours = 0;
            for (int[] d : dirs) {
                boolean connected = false;
                for (int dy = -1; dy <= 1; dy++) {
                    Pos n = new Pos(p.x + d[0], p.y + dy, p.z + d[1]);
                    if (!m.ground.contains(n)) continue;
                    connected = true;
                    if (visited.add(n)) q.add(n);
                }
                if (connected) neighbours++;
            }
            double distance = Math.hypot(p.x - start.x, p.z - start.z);
            if (distance < 2 || (!rememberedRegion && distance > RANGE) || neighbours >= 4 || !m.ground.contains(p)) continue;
            if (excluded != null && excluded.contains(Crew.key(p.x, p.y, p.z))) continue;
            double novel = trailDistance(trail, p);
            if (novel < 1.5) continue; // moving beside the same footprints is not a new survey
            // Keep an expedition's chosen bearing across local visibility windows. Otherwise a new
            // unwalked patch behind the actor wins repeatedly and spends the whole trip near home.
            double ahead = (p.x - start.x) * headingX + (p.z - start.z) * headingZ;
            if (ahead >= 1) forward.add(p);
            scores.put(p, distance + Math.min(8, novel) * 12 + ahead * 8);
            candidates.add(p);
        }
        Collections.sort(candidates, new Comparator<Pos>() { public int compare(Pos a, Pos b) {
            if (forward.contains(a) != forward.contains(b)) return forward.contains(a) ? -1 : 1;
            int byScore = Double.compare(scores.get(b), scores.get(a));
            if (byScore != 0) return byScore;
            if (a.x != b.x) return Integer.compare(a.x, b.x);
            if (a.y != b.y) return Integer.compare(a.y, b.y);
            return Integer.compare(a.z, b.z);
        } });
        return candidates;
    }
    static long trailKey(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
    static double trailDistance(Map<Long, List<Pos>> trail, Pos p) {
        double nearest = 8;
        int bx = Math.floorDiv(p.x, 4), bz = Math.floorDiv(p.z, 4);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            List<Pos> cells = trail.get(trailKey(bx + dx, bz + dz));
            if (cells == null) continue;
            for (Pos old : cells) {
                if (Math.abs(p.y - old.y) > 3) continue;
                double distance = Math.hypot(p.x - old.x, p.z - old.z);
                if (distance < nearest) nearest = distance;
            }
        }
        return nearest;
    }
    private static <T> void trim(Set<T> entries, int limit) { while (entries.size() > limit) entries.remove(entries.iterator().next()); }
    private static File file(WorldServer w) { return new File(w.getSaveHandler().getWorldDirectory(), "aiguide-observed-" + w.provider.dimensionId + "-" + Long.toHexString(w.getSeed()) + ".dat"); }
    static synchronized void load(WorldServer w) {
        Memory m = new Memory();
        File f = file(w);
        if (f.isFile()) try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) { read(m, in); }
        catch (IOException ex) { System.err.println("AIGuide observation memory not loaded: " + ex.getMessage()); m = new Memory(); }
        worlds.put(w, m);
    }
    static synchronized void save(WorldServer w) {
        Memory m = worlds.get(w); if (m == null) return;
        File f = file(w), tmp = new File(f.getPath() + ".tmp");
        try {
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) { write(m, out); }
            try { Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException ex) { System.err.println("AIGuide observation memory not saved: " + ex.getMessage()); }
    }
    static void write(Memory m, DataOutputStream out) throws IOException {
        out.writeInt(0x41494731); out.writeInt(m.seen.size());
        for (Map.Entry<Pos, String> e : m.seen.entrySet()) { writePos(out, e.getKey()); out.writeUTF(e.getValue()); }
        writeSet(out, m.ground); writeSet(out, m.trail);
    }
    static void read(Memory m, DataInputStream in) throws IOException {
        if (in.readInt() != 0x41494731) throw new IOException("Unknown observation format");
        int n = count(in, LIMIT); for (int i = 0; i < n; i++) m.seen.put(readPos(in), in.readUTF());
        n = count(in, LIMIT); for (int i = 0; i < n; i++) m.ground.add(readPos(in));
        n = count(in, TRAIL_LIMIT); for (int i = 0; i < n; i++) m.trail.add(readPos(in));
    }
    private static int count(DataInputStream in, int max) throws IOException { int n = in.readInt(); if (n < 0 || n > max) throw new IOException("Invalid observation count"); return n; }
    private static Pos readPos(DataInputStream in) throws IOException { int x = in.readInt(), y = in.readInt(), z = in.readInt(); if (y < 0 || y >= 256) throw new IOException("Invalid observation height"); return new Pos(x, y, z); }
    private static void writePos(DataOutputStream out, Pos p) throws IOException { out.writeInt(p.x); out.writeInt(p.y); out.writeInt(p.z); }
    private static void writeSet(DataOutputStream out, Set<Pos> set) throws IOException { out.writeInt(set.size()); for (Pos p : set) writePos(out, p); }
}
