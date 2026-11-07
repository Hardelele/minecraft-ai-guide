package hardelele.aiguide.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.WorldServer;

/**
 * The guide's crew: helpers with players' bodies (Agent) that do the first steps of the game for the player,
 * together and at once: come, chop trees and carry the logs to a chest, put down a crafting table and make
 * tools, dig stone, build a small house by a see-through plan. Orders are phases run one after another; in a
 * phase every helper works through its own tasks. They break only what they reach and see: a ray from their
 * eyes must meet the block, and leaves in the way are broken first. Runs on the server thread (here the
 * integrated server: the crew is for singleplayer and the promo).
 */
public class Crew {
    static final String[] NAMES = { "Агент 1", "Агент 2", "Агент 3", "Агент 4", "Агент 5" };
    static final double REACH = 4.6;

    static Crew instance;

    final List<Agent> agents = new ArrayList<Agent>();
    final Deque<Phase> phases = new ArrayDeque<Phase>();
    final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<Runnable>();
    Phase current;
    String player;
    int[] base;   // x, y, z: where the crew was called, the chest and the table go near it
    float baseYaw;
    int[] chest, table;
    static volatile int[] site;   // the house: x0, y0, z0 (for the camera)
    static volatile String status = "";
    static volatile double[] front;   // where the crew looks when it cheers (the camera goes there)
    final Set<Long> taken = new HashSet<Long>();
    final TaskBoard board = new TaskBoard(this);
    static final Set<String> skip = java.util.Collections.synchronizedSet(new HashSet<String>());   // kinds the director leaves out
    static volatile boolean gated;   // the director lets each phase start (for the camera), off in a normal game
    static final Set<String> allowed = java.util.Collections.synchronizedSet(new HashSet<String>());
    static final Set<String> started = java.util.Collections.synchronizedSet(new HashSet<String>()), finished = java.util.Collections.synchronizedSet(new HashSet<String>());
    final Set<String> ordered = java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<String>());   // the kinds of the orders given

    interface Task {
        /** one tick: RUNNING, or how it ended */
        Result step(Agent a, WorldServer w);
    }

    /** where the jobs given now come from (set around an order's phase start; the board sets its own) */
    static Origin originNow = Origin.SCRIPT;

    /** a job for an agent, tagged with where it came from */
    static void job(Agent a, Task t) {
        job(a, t, originNow);
    }

    static void job(Agent a, Task t, Origin o) {
        a.jobs.add(t);
        a.origins.add(o);
    }

    static void clearJobs(Agent a) {
        a.jobs.clear();
        a.origins.clear();
    }

    abstract static class Phase {
        String name, kind = "";
        Origin origin = Origin.SCRIPT;
        int ticks, max = 20 * 150;
        boolean timedOut;
        final java.util.Map<String, Integer> results = new java.util.TreeMap<String, Integer>();   // how its tasks ended

        /** the phase's time limit from the one estimate (Budget), the work shared by the crew */
        void budget(double walk, int dig, int place, int craft, int smelt, int waitTicks) {
            int crew = Math.max(1, instance == null ? 1 : instance.agents.size());
            max = Budget.phase(Budget.ticks(walk, dig, place, craft, smelt, 0), 0, crew) + waitTicks;
        }
        void tally(Result r) { Ledger.add(results, r.name(), 1); }
        abstract void start(Crew c, WorldServer w, EntityPlayerMP p);
        /** done: no card of it left on the board, no agent on a job given outside the board's defaults */
        boolean done(Crew c) {
            if (c.board.pending(this)) return false;
            for (Agent a : c.agents)
                for (Origin o : a.origins) if (o != Origin.AUTO_DEFAULT) return false;
            return true;
        }
    }

    // ---- orders (from the guide's tool or the director), on any thread ---------------------------------

    /** an order for the crew of a player: summon, wood, tools, stone, house, cheer; returns what was planned */
    /** the crew of this game (a saved world brings its agents and its base back) */
    static Crew ensure() {
        if (instance == null) {
            instance = new Crew();
            FMLCommonHandler.instance().bus().register(instance);
        }
        return instance;
    }

    /** an order from the guide's tool (the model): its origin is the plan */
    public static String order(final String player, final String what, final int count) {
        return order(player, what, count, Origin.LLM_PLAN);
    }

    public static String order(final String player, final String what, final int count, Origin origin) {
        ensure();
        final Crew c = instance;
        String w = what.trim().toLowerCase();
        Phase ph;
        if (w.startsWith("summon") || w.startsWith("call") || w.startsWith("позв")) ph = new Summon(Math.max(1, Math.min(5, count <= 0 ? 4 : count)));
        else if (w.startsWith("wood") || w.startsWith("tree") || w.startsWith("дерев")) ph = new Day1.WoodChain(Math.max(1, Math.min(4, count <= 0 ? 3 : count)));
        else if (w.startsWith("upgrade") || w.startsWith("stonetool") || w.startsWith("furnace")) ph = new Day1.Upgrade();
        else if (w.startsWith("gift") || w.startsWith("give")) ph = new Day1.Gift();
        else if (w.startsWith("gather") || w.startsWith("night")) ph = new Day1.Gather();
        else if (w.startsWith("tool") || w.startsWith("craft") || w.startsWith("инстр")) ph = new Day1.Tools();
        else if (w.startsWith("stone") || w.startsWith("mine") || w.startsWith("кам") || w.startsWith("шахт")) ph = new Day1.Mine();
        else if (w.startsWith("house") || w.startsWith("build") || w.startsWith("base") || w.startsWith("дом")) ph = new Day1.Base();
        else if (w.startsWith("cheer") || w.startsWith("done") || w.startsWith("рад")) ph = new Cheer();
        else return "Unknown crew task '" + what + "': summon, wood, tools, mine, upgrade, gift, house, gather or cheer.";
        ph.name = w;
        String kind = ph instanceof Summon ? "summon" : ph instanceof Day1.WoodChain ? "wood" : ph instanceof Day1.Tools ? "tools" : ph instanceof Day1.Mine ? "mine"
                : ph instanceof Day1.Upgrade ? "upgrade" : ph instanceof Day1.Gift ? "gift" : ph instanceof House ? "house" : ph instanceof Day1.Gather ? "gather" : "cheer";
        ph.kind = kind;
        ph.origin = origin;
        if (c.ordered.contains(kind) && !kind.equals("cheer")) return "Already ordered: " + kind + ".";
        c.ordered.add(kind);
        c.queue.add(new Runnable() {
            @Override public void run() {
                c.player = player;
                c.phases.add(ph_(ph));
            }
        });
        Events.log("crew order: " + w + " " + count);
        return "Queued for the crew: " + w + (c.phases.isEmpty() && c.current == null ? " (starts now)" : " (after the orders before it)") + ".";
    }

    static Phase ph_(Phase p) {
        return p;
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        for (Runnable r; (r = queue.poll()) != null; ) r.run();
        MinecraftServer s = MinecraftServer.getServer();
        if (s == null || s.worldServers == null) return;
        WorldServer w = s.worldServers[0];
        EntityPlayerMP p = playerIn(w);
        for (java.util.Iterator<Agent> it = agents.iterator(); it.hasNext(); ) {
            Agent d = it.next();
            if (!d.isDead) continue;
            it.remove();
            if (d.getHealth() <= 0) dead.add(new Object[] { d, w.getTotalWorldTime() });   // died (not unloaded): it comes back as a player does
        }
        respawn(w);
        Guard.watch(this, w, p);
        if (current != null && current.ticks > current.max) {   // too long: what is carried goes to the chest, the phase ends
            Events.log("crew phase " + current.name + ": out of time");
            board.cancel(current);
            for (Agent a : agents) {
                clearJobs(a);
                a.getNavigator().clearPathEntity();
                if (!a.bag.isEmpty() && chest != null) Crew.job(a, new Stash(Item.getItemFromBlock(Blocks.log), 1));
            }
            current.max = Integer.MAX_VALUE;
            current.timedOut = true;
        }
        if (current != null) {
            current.ticks++;
            if (current.done(this) && current.ticks > 10) {
                Events.log("crew phase done: " + current.name + " in " + current.ticks / 20 + " s");
                status = "done " + current.name;
                finished.add(current.kind);
                int gaveUp = 0;
                for (TaskBoard.Card k : board.cards) if (k.phase == current && k.state == TaskBoard.State.FAILED && !k.optional) gaveUp++;
                phaseLog.add(new String[] { current.kind, String.valueOf(current.ticks / 20), String.valueOf(gaveUp), String.valueOf(current.timedOut), current.origin.name(),
                        current.results.toString() });
                try {
                    Compiler.afterPhase(this, current, w, current.timedOut);
                } catch (Exception ex) {
                    Events.log("replan failed: " + ex);
                }
                current = null;
                save(w);
                Ledger.save(w.getSaveHandler().getWorldDirectory());
                Events.log(Ledger.report());
                Events.log(board.summary());
                StringBuilder at = new StringBuilder("crew at:");
                for (Agent a : agents) at.append(' ').append(a.getCustomNameTag()).append(' ').append((int) Math.floor(a.posX)).append(' ').append((int) Math.floor(a.posY)).append(' ').append((int) Math.floor(a.posZ))
                        .append(" on ").append(Ledger.name(new ItemStack(w.getBlock(MathHelper.floor_double(a.posX), MathHelper.floor_double(a.posY) - 1, MathHelper.floor_double(a.posZ))))).append(';');
                Events.log(at.toString());
            }
        }
        while (current == null && !phases.isEmpty() && skip.contains(phases.peek().kind)) {
            Events.log("crew phase skipped: " + phases.poll().name);
        }
        if (current == null && !phases.isEmpty() && p != null && (!gated || allowed.contains(phases.peek().kind))) {
            current = phases.poll();
            Events.log("crew phase: " + current.name);
            status = current.name;
            started.add(current.kind);
            originNow = current.origin;
            current.start(this, w, p);
            originNow = Origin.AUTO_DEFAULT;
        }
        if (!loaded) load(w);
        if (w.getTotalWorldTime() % 40 == 0) adopt(w);
        for (Agent a : agents) Exploration.observe(this, a, w);
        board.tick(w);
        for (Agent a : agents) {
            if (a.aside != null) {   // stepping out of a placement's way comes before anything else
                if (a.aside.step(a, w).done()) a.aside = null;
                continue;
            }
            Task t = a.jobs.peek();
            if (t == null && (w.getTotalWorldTime() + a.skin()) % 10 == 0 && board.feed(a, w)) t = a.jobs.peek();   // idle: the board gives it something (asked twice a second, not every tick)
            else if (t != null && w.getTotalWorldTime() % 20 == a.skin() && !a.origins.isEmpty() && a.origins.peek() == Origin.AUTO_DEFAULT
                    && !board.leased.containsKey(a) && board.preempt(a, w)) t = a.jobs.peek();   // a card waits: the default work stops
            if (t == null) {   // nothing to do: it looks at the player when the player is near
                a.idle++;
                if (p != null && dist(a, p.posX, p.posY, p.posZ) < 20) face(a, p.posX, p.posY + 1.5, p.posZ);
                continue;
            }
            a.idle = 0;
            a.origin = a.origins.isEmpty() ? Origin.SCRIPT : a.origins.peek();
            Result r;
            try {
                r = t.step(a, w);
            } catch (Exception ex) {
                StringBuilder at = new StringBuilder();
                StackTraceElement[] st = ex.getStackTrace();
                for (int i = 0; i < Math.min(4, st.length); i++) at.append(" @ ").append(st[i].getClassName().replace("hardelele.aiguide.client.", "")).append(':').append(st[i].getLineNumber());
                Events.log("crew task failed: " + ex + at);
                r = Result.BLOCKED;
            }
            if (r.done()) {
                a.jobs.poll();
                a.origins.poll();
                Ledger.result(r);
                if (current != null) current.tally(r);
                if (r.failed()) Events.log("crew: " + a.getCustomNameTag() + " " + t.getClass().getSimpleName() + " -> " + r);
            }
        }
    }

    final List<Object[]> dead = new ArrayList<Object[]>();   // { the agent that died, when }

    /**
     * As a player respawns: a second after its death the agent is back at the crew's spawn point (the base's standing
     * cell, else the world's spawn), with full health and an empty bag; its card went back on the board when it died
     * (the board's lease rule); a card to go back for what it dropped is posted for it (the items lie there 5 minutes).
     */
    void respawn(WorldServer w) {
        for (java.util.Iterator<Object[]> it = dead.iterator(); it.hasNext(); ) {
            Object[] o = it.next();
            if (w.getTotalWorldTime() - (Long) o[1] < 20) continue;
            it.remove();
            Agent d = (Agent) o[0];
            int[] at = base != null ? Ground.anchor(this, w) : null;
            if (at == null) { net.minecraft.util.ChunkCoordinates sp = w.getSpawnPoint(); at = new int[] { sp.posX, w.getTopSolidOrLiquidBlock(sp.posX, sp.posZ), sp.posZ }; }
            Agent a = new Agent(w);
            a.number = d.number;
            a.commanded = d.commanded;
            a.setCustomNameTag(d.getCustomNameTag());
            a.setAlwaysRenderNameTag(true);
            a.setLocationAndAngles(at[0] + 0.5, at[1], at[2] + 0.5, d.rotationYaw, 0);
            w.spawnEntityInWorld(a);
            agents.add(a);
            Collections.sort(agents, new Comparator<Agent>() {
                @Override public int compare(Agent x, Agent y) { return x.skin() - y.skin(); }
            });
            Ledger.act(a, "respawn");
            Events.log("crew: " + a.getCustomNameTag() + " respawned at " + at[0] + " " + at[1] + " " + at[2] + " (full health, empty bag)");
            final int[] where = d.diedAt;
            if (where != null) {   // back for its things: a card like any other (its own, while they lie there)
                final Agent me = a;
                Origin was = originNow;
                originNow = Origin.AUTO_DEFAULT;
                TaskBoard.Card rc = board.post("recover", () -> new TaskBoard.Seq(new GoTo(where[0] + 0.5, where[1], where[2] + 0.5, 1.5), new Collect(where[0] + 0.5, where[1], where[2] + 0.5, 4)))
                        .pin(me).prio(6).at(where[0], where[1], where[2]).work(Math.hypot(where[0] - at[0], where[2] - at[2]), 0, 0, 0, 0, 60).optional();
                rc.phase = null;   // its own business, not a phase's
                originNow = was;
            }
        }
    }

    /** what the crew knows of its base, kept in the world's folder (the next day goes on from it) */
    void save(WorldServer w) {
        Exploration.save(w);
        try {
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            com.google.gson.Gson g = new com.google.gson.Gson();
            if (base != null) o.add("base", g.toJsonTree(base));
            if (site != null) o.add("site", g.toJsonTree(site));
            if (chest != null) o.add("chest", g.toJsonTree(chest));
            if (table != null) o.add("table", g.toJsonTree(table));
            if (Day1.mine != null) o.add("mine", g.toJsonTree(Day1.mine));
            if (Day1.furnace != null) o.add("furnace", g.toJsonTree(Day1.furnace));
            if (Day1.lastBase != null) o.addProperty("baseSize", Day1.lastBase.size);
            if (Day1.mineDepth > 0) o.addProperty("mineDepth", Day1.mineDepth);
            o.add("passages", g.toJsonTree(new ArrayList<Long>(Ground.passages)));
            o.add("fog", g.toJsonTree(new ArrayList<Long>(FogMap.explored)));   // what the crew has seen of the map
            o.add("done", g.toJsonTree(new ArrayList<String>(finished)));
            o.addProperty("day", 1);
            java.io.File f = new java.io.File(w.getSaveHandler().getWorldDirectory(), "crew.json");
            java.nio.file.Files.write(f.toPath(), o.toString().getBytes("UTF-8"));
        } catch (Exception e) {
            Events.log("crew: not saved: " + e);
        }
    }

    boolean loaded;

    void load(WorldServer w) {
        loaded = true;
        Exploration.load(w);
        Routines.loaded = false;   // this world's routines, read when first asked for
        try {
            java.io.File f = new java.io.File(w.getSaveHandler().getWorldDirectory(), "crew.json");
            if (!f.isFile()) return;
            com.google.gson.JsonObject o = new com.google.gson.JsonParser().parse(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8")).getAsJsonObject();
            com.google.gson.Gson g = new com.google.gson.Gson();
            if (o.has("base")) base = g.fromJson(o.get("base"), int[].class);
            if (o.has("site")) site = g.fromJson(o.get("site"), int[].class);
            if (o.has("chest")) chest = g.fromJson(o.get("chest"), int[].class);
            if (o.has("table")) table = g.fromJson(o.get("table"), int[].class);
            if (o.has("mine")) Day1.mine = g.fromJson(o.get("mine"), int[].class);
            if (o.has("furnace")) Day1.furnace = g.fromJson(o.get("furnace"), int[].class);
            if (o.has("mineDepth")) Day1.mineDepth = o.get("mineDepth").getAsInt();
            if (o.has("passages")) for (com.google.gson.JsonElement e : o.getAsJsonArray("passages")) Ground.passages.add(e.getAsLong());
            if (o.has("fog")) for (com.google.gson.JsonElement e : o.getAsJsonArray("fog")) FogMap.explored.add(e.getAsLong());
            if (site != null && base != null) {   // the base of the day before: its plan laid again where it stands (its own walls known)
                int size = o.has("baseSize") ? o.get("baseSize").getAsInt() : 2;
                Day1.Base b = new Day1.Base(size);
                b.lay(this, w, size, 0);
                b.size = size;
                Day1.lastBase = b;
            }
            Events.log("crew: the base of the day before is known: " + o);
        } catch (Exception e) {
            Events.log("crew: not loaded: " + e);
        }
    }

    /** the agents the world already has (a saved world: the crew of the day before) */
    void adopt(WorldServer w) {
        for (Object o : w.loadedEntityList)
            if (o instanceof Agent && !agents.contains(o) && !((Agent) o).isDead) {
                agents.add((Agent) o);
                Events.log("crew: " + ((Agent) o).getCustomNameTag() + " is back");
            }
        Collections.sort(agents, new Comparator<Agent>() {
            @Override public int compare(Agent x, Agent y) { return x.skin() - y.skin(); }
        });
    }

    /** on the headless bench: the fake player who stands in for a real one (asks, receives the gift) */
    static volatile EntityPlayerMP standIn;

    EntityPlayerMP playerIn(WorldServer w) {
        for (Object o : w.playerEntities) {
            EntityPlayerMP p = (EntityPlayerMP) o;
            if (player == null || p.getCommandSenderName().equals(player)) return p;
        }
        return standIn;
    }

    /** every phase as it ended: kind, seconds, cards given up (not optional), out of time, origin */
    static final List<String[]> phaseLog = java.util.Collections.synchronizedList(new ArrayList<String[]>());

    // ---- geometry ------------------------------------------------------------------------------------

    static boolean solid(WorldServer w, int x, int y, int z) {
        Block b = w.getBlock(x, y, z);
        return b.getMaterial().isSolid() && b.getMaterial() != Material.leaves;
    }

    static boolean soft(WorldServer w, int x, int y, int z) {   // air, grass, flowers
        Block b = w.getBlock(x, y, z);
        return b.getMaterial() == Material.air || b.getMaterial() == Material.plants || b.getMaterial() == Material.vine || b == Blocks.tallgrass
                || b == Blocks.double_plant || b == Blocks.snow_layer;
    }

    /** a body fits here, on firm ground */
    static boolean stand(WorldServer w, int x, int y, int z) {
        return solid(w, x, y - 1, z) && soft(w, x, y, z) && soft(w, x, y + 1, z) && w.getBlock(x, y - 1, z).getMaterial() != Material.water;
    }

    /** the ground level near a column: where one stands at x z, around y */
    static int ground(WorldServer w, int x, int y, int z) {
        for (int d = 0; d < 6; d++) {
            if (stand(w, x, y + d, z)) return y + d;
            if (stand(w, x, y - d, z)) return y - d;
        }
        return -1;
    }

    static double dist(Agent a, double x, double y, double z) {
        double dx = a.posX - x, dy = a.posY - y, dz = a.posZ - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    static double eyeDist(Agent a, int x, int y, int z) {
        double dx = a.posX - (x + 0.5), dy = a.posY + a.getEyeHeight() - (y + 0.5), dz = a.posZ - (z + 0.5);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    static void face(Agent a, double x, double y, double z) {
        a.getLookHelper().setLookPosition(x, y, z, 30F, 40F);
        float yaw = (float) (Math.atan2(z - a.posZ, x - a.posX) * 180 / Math.PI) - 90F;
        float d = MathHelper.wrapAngleTo180_float(yaw - a.rotationYaw);
        a.rotationYaw += Math.max(-25F, Math.min(25F, d));
    }

    /** what the agent's eyes meet on the way to the block's middle: null when it is the block itself */
    static int[] blocker(Agent a, WorldServer w, int x, int y, int z) {
        // as a player aims: at the middle of the block, else at the middle of any face of it open to the air; in the way only when none is clear
        Vec3 from = Vec3.createVectorHelper(a.posX, a.posY + a.getEyeHeight(), a.posZ);
        int[] first = null;
        for (int k = -1; k < 6; k++) {
            double tx = x + 0.5, ty = y + 0.5, tz = z + 0.5;
            if (k >= 0) {
                int[] d = Skills.SIDE[k];
                if (!w.isAirBlock(x + d[0], y + d[1], z + d[2]) && w.getBlock(x + d[0], y + d[1], z + d[2]).getMaterial().isSolid()) continue;   // a face against a block: not aimed at
                tx += d[0] * 0.49; ty += d[1] * 0.49; tz += d[2] * 0.49;
            }
            MovingObjectPosition hit = w.rayTraceBlocks(Vec3.createVectorHelper(from.xCoord, from.yCoord, from.zCoord), Vec3.createVectorHelper(tx, ty, tz), false, true, false);
            if (hit == null || hit.blockX == x && hit.blockY == y && hit.blockZ == z) return null;
            if (first == null) first = new int[] { hit.blockX, hit.blockY, hit.blockZ };
        }
        return first;
    }

    /** the kinds of a plan's blocks */
    static Block block(int kind) {
        switch (kind) {
            case 1: return Blocks.log;
            case 2: return Blocks.cobblestone;
            case 3: return Blocks.glass;
            case 4: return Blocks.fence;
            case 5: return Blocks.torch;
            default: return Blocks.planks;
        }
    }

    static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    static void give(List<ItemStack> bag, ItemStack s) {
        for (ItemStack b : bag)
            if (b.getItem() == s.getItem() && b.getMetadata() == s.getMetadata() && b.stackSize + s.stackSize <= b.getMaxStackSize()) {
                b.stackSize += s.stackSize;
                return;
            }
        bag.add(s.copy());
    }

    static int count(IInventory inv, Item item) {
        int n = 0;
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s != null && s.getItem() == item) n += s.stackSize;
        }
        return n;
    }

    static int take(IInventory inv, Item item, int n) {
        int got = 0;
        for (int i = 0; i < inv.getSizeInventory() && got < n; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s == null || s.getItem() != item) continue;
            ItemStack t = inv.decrStackSize(i, n - got);
            if (t != null) got += t.stackSize;
        }
        inv.markDirty();
        return got;
    }

    IInventory chestInv(WorldServer w) {
        if (chest == null) return null;
        TileEntity t = w.getTileEntity(chest[0], chest[1], chest[2]);
        return t instanceof IInventory ? (IInventory) t : null;
    }

    // ---- tasks ----------------------------------------------------------------------------------------

    /**
     * Go to a point: one way of getting stuck and out of it for every skill. The game's path finder first; with no
     * progress for a while, the crew's own walk search (Ground) gives a route and the agent follows it waypoint by
     * waypoint; with no route or no progress on it, it digs its way through blocks it may dig (not a protected zone,
     * natural ground or rock, a block its tools take) toward the point; and when that does not get it there either,
     * the task gives up with NO_PATH, which gives its card back to the board.
     */
    static class GoTo implements Task {
        static final int NAV = 0, ROUTE = 1, DIG = 2;
        double x, y, z, within;
        int ticks, state, lastTry = -100, since, digs, ri, routes;
        double best = 1e9;
        List<int[]> route;
        Break br;
        GoTo(double x, double y, double z, double within) { this.x = x; this.y = y; this.z = z; this.within = within; }
        int stuckFor;    // ticks without getting nearer or digging anything (whatever the state)
        boolean noDig;   // a walk only (the player's "go there"): never dig a way, no way is NO_PATH
        boolean shelterEntry;   // the night's one-block door step from an obstacle outside the house
        int[] shelterDoor;
        GoTo walkOnly() { noDig = true; return this; }
        GoTo shelterEntry(int[] door) { shelterEntry = true; shelterDoor = door; return this; }

        public Result step(Agent a, WorldServer w) {
            if (noDig && (state == DIG || br != null || dig != null)) return Result.NO_PATH;
            ticks++;
            double d = dist(a, x, y, z);
            if (d <= within) { a.getNavigator().clearPathEntity(); return Result.OK; }
            if (d < best - 0.4) { best = d; since = 0; stuckFor = 0; } else { since++; stuckFor++; }   // progress: nearer by a bit
            if (br != null) {
                Result r = br.step(a, w);
                if (r == Result.RUNNING) return r;
                br = null;
                Ground.changed();
                Day1.catchNear(a, w, 2.0);
                Skills.lightUp(a, w);   // a way dug into the dark gets its torches
                since = 0;
                stuckFor = 0;   // a block dug on the way is progress
                if (dig == null) { state = ROUTE; route = null; }   // through the gap: a route again (on a dig route: on along it)
            }
            // out of time only by no progress (nearer, or a block dug on the way) for a minute; a long way that goes on is not a failure
            // (the states' own clock, since, starts again at each change of state; this one only at real progress: a walk round the states that gets nowhere ends)
            if (since > 20 * 60 || stuckFor > 20 * 60 || ticks > 20 * 900) return Result.TIMEOUT;
            switch (state) {
                case NAV:
                    if (ticks % 20 == 1 || a.getNavigator().noPath() && ticks - lastTry >= 10) {
                        lastTry = ticks;
                        a.getNavigator().tryMoveToXYZ(x, y, z, 1.0D);
                    }
                    if (since > 20 * 4) { state = ROUTE; route = null; since = 0; }
                    return Result.RUNNING;
                case ROUTE:
                    if (route == null) {
                        if (++routes > 6) { if (noDig) return Result.NO_PATH; state = DIG; return Result.RUNNING; }
                        Ground.Flood f = Ground.flood(w, Ground.feet(w, a.posX, a.posY, a.posZ), 48, null);
                        int[] to = f.near(MathHelper.floor_double(x), MathHelper.floor_double(y), MathHelper.floor_double(z), Math.max(1.5, within + 0.5));
                        if (to == null) { if (noDig) return Result.NO_PATH; state = DIG; return Result.RUNNING; }   // no walk there at all (a walk only: no way)
                        route = f.route(to);
                        ri = 0;
                    }
                    if (ri >= route.size()) { state = NAV; route = null; since = 0; return Result.RUNNING; }
                    int[] p = route.get(ri);
                    boolean woodenDoor = Roles.is(w, p[0], p[1], p[2], Roles.Role.DOOR) && w.getBlock(p[0], p[1], p[2]).getMaterial() == Material.wood;
                    if (woodenDoor) {   // a closed door on the way: opened by hand, as a player does
                        int lower = (w.getBlockMetadata(p[0], p[1], p[2]) & 8) != 0 ? p[1] - 1 : p[1];
                        if ((w.getBlockMetadata(p[0], lower, p[2]) & 4) == 0 && dist(a, p[0] + 0.5, p[1], p[2] + 0.5) < 2.5) {
                            face(a, p[0] + 0.5, p[1] + 1, p[2] + 0.5);
                            w.getBlock(p[0], lower, p[2]).onBlockActivated(w, p[0], lower, p[2], Skills.hands(a, w), 0, 0.5F, 0.5F, 0.5F);
                            a.swingItem();
                        }
                    }
                    if (Math.abs(a.posX - (p[0] + 0.5)) < 0.45 && Math.abs(a.posZ - (p[2] + 0.5)) < 0.45 && Math.abs(a.posY - p[1]) < 1.1) { ri++; return Result.RUNNING; }
                    a.getMoveHelper().setMoveTo(p[0] + 0.5, p[1], p[2] + 0.5, 1.0D);   // the waypoint next to it: straight there
                    boolean doorDrop = ShelterRoute.descendThroughDoor(shelterEntry, woodenDoor, p[1], a.posY);
                    if (p[1] > a.posY + 0.5 || a.isCollidedHorizontally && !doorDrop || a.isInWater()) a.getJumpHelper().setJumping();
                    if (since > 20 * 5) { route = null; since = 0; }   // no progress on it: a new route (or digging)
                    return Result.RUNNING;
                default:   // DIG: the cheapest way through what may be dug (an opening of its own house only when nothing else goes)
                    if (dig == null) {
                        final Agent ag = a;
                        final WorldServer ww = w;
                        dig = Ground.digRoute(w, Ground.feet(w, a.posX, a.posY, a.posZ), MathHelper.floor_double(x), MathHelper.floor_double(y), MathHelper.floor_double(z),
                                Math.max(1.5, within + 0.5), (bx, by, bz) -> {
                                    if (shelterEntry) {
                                        if (ShelterRoute.oneOpening(bx, by, bz, shelterDoor) && instance != null
                                                && Ground.mayDig(instance, bx, by, bz, "exit") && Ground.ownWall(instance, bx, by, bz)
                                                && Skills.breakTicks(ag, ww, bx, by, bz) >= 0) return 30;
                                        return -1; // no second roof/wall break to make a shelter shot look successful
                                    }
                                    if (Ground.passages.contains(key(bx, by, bz))) return 1;   // a passage opened (or being opened) by the crew: everyone's way
                                    if (mayDigThrough(ag, ww, bx, by, bz)) return 4;
                                    if (instance != null && Ground.mayDig(instance, bx, by, bz, "exit") && Ground.ownWall(instance, bx, by, bz)
                                            && Skills.breakTicks(ag, ww, bx, by, bz) >= 0) return 30;   // its own wall: dear, the last resort (a walk round to a passage is cheaper)
                                    return -1;
                                }, 24);
                        di = 1;
                        if (dig != null)   // the openings this way makes in its own wall are the crew's passages from now on (others take them too)
                            for (int i = 1; i < dig.size(); i++) {
                                int[] n0 = dig.get(i);
                                for (int dy = 0; dy <= 2; dy++)
                                    if (instance != null && !Ground.passable(w, n0[0], n0[1] + dy, n0[2], null) && Ground.ownWall(instance, n0[0], n0[1] + dy, n0[2])
                                            && Ground.passages.add(key(n0[0], n0[1] + dy, n0[2])))
                                        Events.log("crew: " + a.getCustomNameTag() + " will open a passage in the house wall at " + n0[0] + " " + (n0[1] + dy) + " " + n0[2] + " (no other way)");
                            }
                        if (dig == null || digs >= 48) {
                            Events.log("crew: " + a.getCustomNameTag() + " has no way to " + (int) x + " " + (int) y + " " + (int) z + " from " + (int) a.posX + " " + (int) a.posY + " " + (int) a.posZ);
                            return Result.NO_PATH;
                        }
                    }
                    if (di >= dig.size()) { state = NAV; dig = null; since = 0; return Result.RUNNING; }
                    int[] n = dig.get(di), cur = dig.get(di - 1);
                    int up = n[1] - cur[1];
                    int[][] need = up > 0 ? new int[][] { { cur[0], cur[1] + 2, cur[2] }, { n[0], n[1] + 1, n[2] }, { n[0], n[1], n[2] } }
                            : up < 0 ? new int[][] { { n[0], n[1] + 2, n[2] }, { n[0], n[1] + 1, n[2] }, { n[0], n[1], n[2] } }
                            : new int[][] { { n[0], n[1] + 1, n[2] }, { n[0], n[1], n[2] } };
                    for (int[] c : need) {
                        if (Ground.passable(w, c[0], c[1], c[2], null)) continue;
                        if (shelterEntry && !ShelterRoute.oneOpening(c[0], c[1], c[2], shelterDoor)) return Result.NO_PATH;
                        if (!mayDigThrough(a, w, c[0], c[1], c[2])) {   // not natural: a passage of the crew's in its own wall (planned with the route)
                            if (!Ground.passages.contains(key(c[0], c[1], c[2])) || instance == null || !Ground.ownWall(instance, c[0], c[1], c[2])) { dig = null; state = ROUTE; route = null; return Result.RUNNING; }
                            Events.log("crew: " + a.getCustomNameTag() + " opens a passage in the house wall at " + c[0] + " " + c[1] + " " + c[2]);
                        }
                        br = new Break(c[0], c[1], c[2], 0);
                        digs++;
                        return Result.RUNNING;
                    }
                    if (Math.abs(a.posX - (n[0] + 0.5)) < 0.45 && Math.abs(a.posZ - (n[2] + 0.5)) < 0.45 && Math.abs(a.posY - n[1]) < 1.1) { di++; return Result.RUNNING; }
                    a.getMoveHelper().setMoveTo(n[0] + 0.5, n[1], n[2] + 0.5, 1.0D);
                    boolean doorDropDig = ShelterRoute.descendThroughDoor(shelterEntry,
                            Roles.is(w, n[0], n[1], n[2], Roles.Role.DOOR), n[1], a.posY);
                    if (up > 0 || a.isCollidedHorizontally && !doorDropDig || a.isInWater()) a.getJumpHelper().setJumping();
                    if (since > 20 * 6) { dig = null; since = 0; }   // stuck on the way: the route again
                    return Result.RUNNING;
            }
        }

        List<int[]> dig;
        int di;

        int wallDigs;

        /** natural ground or rock, outside every protected zone, and a block the agent's tools take */
        static boolean mayDigThrough(Agent a, WorldServer w, int x, int y, int z) {
            Material m = w.getBlock(x, y, z).getMaterial();
            boolean natural = m == Material.rock || m == Material.ground || m == Material.grass || m == Material.sand || m == Material.clay
                    || m == Material.leaves || m == Material.plants || m == Material.vine || m == Material.snow || m == Material.craftedSnow;
            return natural && instance != null && Ground.mayDig(instance, x, y, z, "way") && Skills.breakTicks(a, w, x, y, z) >= 0;
        }
    }

    /** an agent standing where a block is to go moves to the nearest free spot beside it (not over the cell) */
    static void stepAside(Agent a, int x, int y, int z) {
        if (a.aside != null) return;
        WorldServer w = (WorldServer) a.worldObj;
        int[] f = Ground.feet(w, a.posX, a.posY, a.posZ);
        int[] best = null;
        double bd = 1e9;
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++)
                for (int dy = -1; dy <= 1; dy++) {
                    int cx = f[0] + dx, cy = f[1] + dy, cz = f[2] + dz;
                    if (cx == x && cz == z) continue;   // not the cell, not over or under it
                    if (Math.abs(cx - x) + Math.abs(cz - z) < 2 || !Ground.stand(w, cx, cy, cz, null)) continue;
                    double d = Math.abs(dx) + Math.abs(dz) + Math.abs(dy) * 0.5;
                    if (d < bd) { bd = d; best = new int[] { cx, cy, cz }; }
                }
        if (best == null) return;
        a.getNavigator().clearPathEntity();
        a.aside = new GoTo(best[0] + 0.5, best[1], best[2] + 0.5, 0.35);
    }

    /** look at a point for a while */
    static class Look implements Task {
        double x, y, z;
        int ticks, len;
        Look(double x, double y, double z, int len) { this.x = x; this.y = y; this.z = z; this.len = len; }
        public Result step(Agent a, WorldServer w) {
            face(a, x, y, z);
            return (++ticks >= len) ? Result.OK : Result.RUNNING;
        }
    }

    /**
     * break one block as a player: the best tool of the bag in hand, looking at it, the arm swinging, the cracks
     * growing for as long as the game says that tool takes, then the game's own harvest (BreakEvent, drops, wear).
     * A block that gives nothing without the right tool (stone without a pickaxe) is not broken: NO_TOOL.
     */
    static class Break {
        int x, y, z, ticks, need = -2;
        Break(int x, int y, int z, double seconds) { this.x = x; this.y = y; this.z = z; }
        Result step(Agent a, WorldServer w) {
            Block b = w.getBlock(x, y, z);
            if (b.getMaterial() == Material.air) return Result.OK;
            face(a, x + 0.5, y + 0.5, z + 0.5);
            a.act(x, y, z, 1);
            if (need == -2) {   // the tool for it, and how long
                int meta = w.getBlockMetadata(x, y, z);
                ItemStack tool = Skills.bestTool(a, b, meta);
                ItemStack held = a.getHeldItem();
                if (tool != null && tool != held) a.setCurrentItemOrArmor(0, tool);
                else if (tool == null && held != null && !held.isStackable() && a.bag.contains(held)) a.setCurrentItemOrArmor(0, null);   // a tool that does not help: bare hands
                need = Skills.breakTicks(a, w, x, y, z);
                if (need < 0) return Result.NO_TOOL;
            }
            ticks++;
            if (ticks < 6) return Result.RUNNING;   // turn to it first
            if (ticks % 5 == 0) {
                a.swingItem();
                w.playSoundEffect(x + 0.5, y + 0.5, z + 0.5, b.stepSound.getStepSound(), 0.35F, 0.6F);
            }
            double k = (ticks - 6) / (double) need;
            w.destroyBlockInWorldPartially(a.getEntityId(), x, y, z, Math.min(9, (int) (k * 10)));
            if (k < 1) return Result.RUNNING;
            w.destroyBlockInWorldPartially(a.getEntityId(), x, y, z, -1);
            Ledger.act(a, "break " + b.getUnlocalizedName().replace("tile.", ""));
            return Skills.harvest(a, w, x, y, z) ? Result.OK : Result.BLOCKED;
        }
    }
    /** a tree: from a spot beside the trunk, the logs bottom up, each only when seen; leaves in the way first */
    static class Chop implements Task {
        final int tx, ty, tz;
        int state, spot, tries;
        GoTo walk;
        Break br;
        Chop(int tx, int ty, int tz) { this.tx = tx; this.ty = ty; this.tz = tz; }
        public Result step(Agent a, WorldServer w) {
            if (state == 0) {   // a spot beside the trunk, the nearest free one
                int[][] around = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 }, { 1, 1 }, { -1, 1 }, { 1, -1 }, { -1, -1 }, { 2, 0 }, { -2, 0 }, { 0, 2 }, { 0, -2 } };
                double best = 1e9;
                int[] pick = null;
                for (int i = 0; i < around.length; i++) {
                    if (i == spot - 1) continue;   // the one that did not work
                    int sx = tx + around[i][0], sz = tz + around[i][1], sy = ground(w, sx, ty, sz);
                    if (sy < 0 || Math.abs(sy - ty) > 1) continue;
                    double d = dist(a, sx + 0.5, sy, sz + 0.5) + (i >= 8 ? 3 : 0);
                    if (d < best) { best = d; pick = new int[] { sx, sy, sz, i + 1 }; }
                }
                if (pick == null)   // no free spot beside it (a bush: leaves at head height): one with ground under it, the walk digs the leaves (the stuck machine)
                    for (int i = 0; i < 8; i++) {
                        if (i == spot - 1) continue;
                        int sx = tx + around[i][0], sz = tz + around[i][1];
                        if (!solid(w, sx, ty - 1, sz) || solid(w, sx, ty, sz) && w.getBlock(sx, ty, sz).getMaterial() != Material.leaves) continue;
                        double d = dist(a, sx + 0.5, ty, sz + 0.5);
                        if (d < best) { best = d; pick = new int[] { sx, ty, sz, i + 1, 1 }; }
                    }
                if (pick == null || ++tries > 4) return Result.NO_PATH;
                spot = pick[3];
                // a spot in the leaves: near enough to it (the trunk then in reach), the leaves in the way broken from there
                walk = new GoTo(pick[0] + 0.5, pick[1], pick[2] + 0.5, pick.length > 4 ? 2.2 : 0.6);
                state = 1;
                return Result.RUNNING;
            }
            if (state == 1) {
                if (walk.step(a, w) == Result.RUNNING) return Result.RUNNING;
                state = 2;
                return Result.RUNNING;
            }
            if (br != null) {   // breaking something
                if (br.step(a, w) != Result.RUNNING) br = null;
                return Result.RUNNING;
            }
            int y = -1;   // the lowest log left of this trunk
            for (int k = ty; k < ty + 12; k++) {
                Block b = w.getBlock(tx, k, tz);
                if (b == Blocks.log || b == Blocks.log2) { y = k; break; }
            }
            if (y < 0) return Result.OK;
            if (eyeDist(a, tx, y, tz) > REACH) { state = 0; return Result.RUNNING; }   // out of reach from here
            int[] in = blocker(a, w, tx, y, tz);
            if (in != null) {
                Block b = w.getBlock(in[0], in[1], in[2]);
                Material m = b.getMaterial();
                if (m == Material.leaves || m == Material.plants || m == Material.vine) { br = new Break(in[0], in[1], in[2], 0.2); return Result.RUNNING; }
                state = 0;   // something firm in the way: another side
                return Result.RUNNING;
            }
            br = new Break(tx, y, tz, 0.9);
            return Result.RUNNING;
        }
    }

    /** pick up what lies around a point: walk over each item */
    static class Collect implements Task {
        final double x, y, z, r;
        int ticks;
        GoTo walk;
        EntityItem target;
        final java.util.Set<Integer> unreachable = new java.util.HashSet<Integer>();
        Collect(double x, double y, double z, double r) { this.x = x; this.y = y; this.z = z; this.r = r; }
        @SuppressWarnings("unchecked")
        public Result step(Agent a, WorldServer w) {
            ticks++;
            if (ticks < 14) return Result.RUNNING;   // the drops fall first
            for (Object o : w.getEntitiesWithinAABB(EntityItem.class, a.boundingBox.expand(1.3, 0.8, 1.3))) {
                EntityItem it = (EntityItem) o;
                if (it.isDead || it.delayBeforeCanPickup > 0) continue;
                ItemStack s = it.getEntityItem();
                a.onItemPickup(it, s.stackSize);
                give(a.bag, s);
                Ledger.flow(Day1.HANDED.containsKey(it) ? Ledger.Way.HANDED_IN : Ledger.Way.DROP, s);
                Ledger.act(a, "pick up");
                it.setDead();
                if (a.getHeldItem() == null || a.getHeldItem().getItem() == s.getItem() || !(a.getHeldItem().getItem() instanceof net.minecraft.item.ItemTool))
                    a.setCurrentItemOrArmor(0, new ItemStack(s.getItem(), 1, s.getMetadata()));
            }
            if (target == null || target.isDead) {
                target = null;
                double best = 1e9;
                for (Object o : w.getEntitiesWithinAABB(EntityItem.class, AxisAlignedBB.getBoundingBox(x - r, y - 3, z - r, x + r, y + 8, z + r))) {
                    EntityItem it = (EntityItem) o;
                    if (it.isDead || !it.onGround || unreachable.contains(it.getEntityId())) continue;   // (one it found no way to: not again)
                    double d = dist(a, it.posX, it.posY, it.posZ);
                    if (d < best) { best = d; target = it; }
                }
                if (target == null) return (ticks > 40) ? Result.OK : Result.RUNNING;
                walk = new GoTo(target.posX, target.posY, target.posZ, 0.7);
            }
            Result wr = walk.step(a, w);
            if (wr != Result.RUNNING) { if (wr.failed() && target != null) unreachable.add(target.getEntityId()); target = null; }
            return (ticks > 20 * 30) ? Result.OK : Result.RUNNING;
        }
    }

    /** carry the bag to the chest: open it, put everything in (but what to keep), close it */
    class Stash implements Task {
        final Item keep;
        final int keepN;
        int state, ticks;
        GoTo walk;
        Stash(Item keep, int keepN) { this.keep = keep; this.keepN = keepN; }
        public Result step(Agent a, WorldServer w) {
            if (!Roles.is(w, chest, Roles.Role.CHEST)) return Result.GONE;
            if (state == 0) { walk = new GoTo(chest[0] + 0.5, chest[1], chest[2] + 0.5, 2.3); state = 1; }
            if (state == 1) { if (walk.step(a, w) == Result.RUNNING) return Result.RUNNING; state = 2; }
            face(a, chest[0] + 0.5, chest[1] + 0.5, chest[2] + 0.5);
            ticks++;
            IInventory inv = chestInv(w);
            if (ticks == 4) { w.addBlockEvent(chest[0], chest[1], chest[2], w.getBlock(chest[0], chest[1], chest[2]), 1, 1); w.playSoundEffect(chest[0] + 0.5, chest[1] + 0.5, chest[2] + 0.5, "random.chestopen", 0.5F, 1.0F); }
            if (ticks == 12 && inv != null) {
                a.swingItem();
                int kept = 0;
                for (java.util.Iterator<ItemStack> it = a.bag.iterator(); it.hasNext(); ) {
                    ItemStack s = it.next();
                    if (s.getItem() instanceof net.minecraft.item.ItemTool) continue;   // tools stay with the agent
                    if (s.getItem() == keep && kept < keepN) {
                        int k = Math.min(keepN - kept, s.stackSize);
                        kept += k;
                        s.stackSize -= k;
                        if (s.stackSize <= 0) { it.remove(); continue; }
                    }
                    ItemStack before = s.copy();
                    for (int i = 0; i < inv.getSizeInventory() && s.stackSize > 0; i++) {
                        ItemStack c = inv.getStackInSlot(i);
                        if (c == null) { inv.setInventorySlotContents(i, s.copy()); s.stackSize = 0; }
                        else if (c.getItem() == s.getItem() && c.getMetadata() == s.getMetadata() && c.stackSize < c.getMaxStackSize()) {
                            int m = Math.min(s.stackSize, c.getMaxStackSize() - c.stackSize);
                            c.stackSize += m;
                            s.stackSize -= m;
                        }
                    }
                    before.stackSize -= s.stackSize;
                    Ledger.flow(Ledger.Way.CHEST_IN, before);
                    if (s.stackSize > 0) Ledger.flow(Ledger.Way.INTO_NOTHING, s, "Crew.Stash chest full");
                    it.remove();
                }
                Ledger.act(a, "stash");
                if (kept > 0 && keep != null) a.bag.add(new ItemStack(keep, kept));
                inv.markDirty();
                if (a.getHeldItem() != null && !(a.getHeldItem().getItem() instanceof net.minecraft.item.ItemTool)) a.setCurrentItemOrArmor(0, null);
            }
            if (ticks == 22) { w.addBlockEvent(chest[0], chest[1], chest[2], w.getBlock(chest[0], chest[1], chest[2]), 1, 0); w.playSoundEffect(chest[0] + 0.5, chest[1] + 0.5, chest[2] + 0.5, "random.chestclosed", 0.5F, 1.0F); }
            return (ticks >= 26) ? Result.OK : Result.RUNNING;
        }
    }

    /** jump and wave for a while, looking at a point */
    static class Celebrate implements Task {
        final double x, y, z;
        int ticks;
        final int len, phase;
        Celebrate(double x, double y, double z, int len, int phase) { this.x = x; this.y = y; this.z = z; this.len = len; this.phase = phase; }
        public Result step(Agent a, WorldServer w) {
            face(a, x, y, z);
            ticks++;
            if ((ticks + phase) % 16 == 0 && a.onGround) a.getJumpHelper().setJumping();
            if ((ticks + phase) % 9 == 0) a.swingItem();
            return (ticks >= len) ? Result.OK : Result.RUNNING;
        }
    }

    /** wait until a condition */
    static class Wait implements Task {
        final java.util.concurrent.Callable<Boolean> until;
        int ticks;
        Wait(java.util.concurrent.Callable<Boolean> until) { this.until = until; }
        public Result step(Agent a, WorldServer w) {
            try {
                return (until.call() || ++ticks > 20 * 60) ? Result.OK : Result.RUNNING;
            } catch (Exception e) {
                return Result.OK;
            }
        }
    }

    // ---- phases ------------------------------------------------------------------------------------------

    /** the crew comes running from behind the player and lines up in front of them */
    static class Summon extends Phase {
        final int n;
        Summon(int n) { this.n = n; }
        void start(Crew c, WorldServer w, EntityPlayerMP p) {
            int px = MathHelper.floor_double(p.posX), py = MathHelper.floor_double(p.boundingBox.minY), pz = MathHelper.floor_double(p.posZ);
            c.base = new int[] { px, py, pz };
            c.baseYaw = p.rotationYaw;
            double fx = -Math.sin(Math.toRadians(p.rotationYaw)), fz = Math.cos(Math.toRadians(p.rotationYaw));
            for (int i = c.agents.size(); i < n; i++) {
                double ang = Math.toRadians(p.rotationYaw + 180 + (i - (n - 1) / 2.0) * 22);
                int sx = MathHelper.floor_double(p.posX - Math.sin(ang) * 12), sz = MathHelper.floor_double(p.posZ + Math.cos(ang) * 12);
                int sy = ground(w, sx, py, sz);
                if (sy < 0) { sx = px; sz = pz; sy = py; }
                Agent a = new Agent(w);
                a.number = i + 1;
                a.setCustomNameTag(NAMES[i]);
                a.setAlwaysRenderNameTag(true);
                a.setLocationAndAngles(sx + 0.5, sy, sz + 0.5, p.rotationYaw, 0);
                w.spawnEntityInWorld(a);
                w.func_147487_a("cloud", sx + 0.5, sy + 1, sz + 0.5, 20, 0.3, 0.6, 0.3, 0.02);
                c.agents.add(a);
            }
            int k = 0;
            for (Agent a : c.agents) {   // an arc in front of the player, facing them
                double off = (k++ - (c.agents.size() - 1) / 2.0) * 1.6;
                double tx = p.posX + fx * 3.2 + fz * -off, tz = p.posZ + fz * 3.2 + fx * off;
                int gy = ground(w, MathHelper.floor_double(tx), py, MathHelper.floor_double(tz));
                Crew.job(a, new GoTo(tx, gy < 0 ? py : gy, tz, 0.8));
                Crew.job(a, new Look(p.posX, p.posY + 1.2, p.posZ, 30 + 6 * k));
            }
            w.playSoundEffect(p.posX, p.posY, p.posZ, "random.levelup", 0.4F, 1.4F);
        }
    }

    /** every helper chops its own trees and carries the logs to the chest (a few kept for its tools) */
    static class Wood extends Phase {
        final int perAgent;
        Wood(int perAgent) { this.perAgent = perAgent; }
        void start(Crew c, WorldServer w, EntityPlayerMP p) {
            if (c.base == null) c.base = new int[] { (int) Math.floor(p.posX), (int) Math.floor(p.boundingBox.minY), (int) Math.floor(p.posZ) };
            c.planSite(w);
            List<int[]> trees = trunks(w, c.base[0], c.base[1], c.base[2], 36);
            for (Agent a : c.agents) {
                final double ax = a.posX, az = a.posZ;
                List<int[]> mine = new ArrayList<int[]>();
                for (int k = 0; k < perAgent; k++) {
                    int[] best = null;
                    double bd = 1e9;
                    double fx = mine.isEmpty() ? ax : mine.get(mine.size() - 1)[0], fz = mine.isEmpty() ? az : mine.get(mine.size() - 1)[2];
                    for (int[] t : trees) {
                        if (c.taken.contains(key(t[0], t[1], t[2]))) continue;
                        double d = Math.hypot(t[0] - fx, t[2] - fz);
                        if (d < bd) { bd = d; best = t; }
                    }
                    if (best == null) break;
                    c.taken.add(key(best[0], best[1], best[2]));
                    mine.add(best);
                }
                for (int[] t : mine) {
                    Crew.job(a, new Chop(t[0], t[1], t[2]));
                    Crew.job(a, new Collect(t[0] + 0.5, t[1], t[2] + 0.5, 5));
                }
                Crew.job(a, c.new Stash(Item.getItemFromBlock(Blocks.log), 1));
            }
        }
    }

    /** single trunks of 4-5 logs on grass or dirt, nearest first (all of them reachable from the ground) */
    static List<int[]> trunks(WorldServer w, final int cx, int cy, final int cz, int r) {
        List<int[]> out = new ArrayList<int[]>();
        for (int x = cx - r; x <= cx + r; x++)
            for (int z = cz - r; z <= cz + r; z++) {
                int top = w.getHeightValue(x, z);
                for (int y = top; y > top - 14 && y > 4; y--) {
                    Block b = w.getBlock(x, y, z);
                    if (b != Blocks.log && b != Blocks.log2) continue;
                    if (w.getBlock(x, y - 1, z) == b) continue;
                    Block under = w.getBlock(x, y - 1, z);
                    if (under != Blocks.grass && under != Blocks.dirt) break;
                    int h = 0;
                    while (w.getBlock(x, y + h, z) == b) h++;
                    if (h >= 4 && h <= 7 && Math.abs(y - cy) <= 6 && w.getBlock(x + 1, y, z) != b && w.getBlock(x - 1, y, z) != b
                            && w.getBlock(x, y, z + 1) != b && w.getBlock(x, y, z - 1) != b && w.getBlock(x, y + h + 1, z) != b)
                        out.add(new int[] { x, y, z, h });
                    break;
                }
            }
        Collections.sort(out, new Comparator<int[]>() {
            @Override public int compare(int[] a, int[] b) { return Double.compare(Math.hypot(a[0] - cx, a[2] - cz) + (a[3] == 5 ? 0 : 25), Math.hypot(b[0] - cx, b[2] - cz) + (b[3] == 5 ? 0 : 25)); }
        });
        return out;
    }

    /** the trunks the crew may chop, around the base */
    List<int[]> trees(WorldServer w) {
        return trunks(w, base[0], base[1], base[2], 40);
    }

    /** where the house, the chest and the crafting table go (planned only: the blocks are made and put by agents) */
    void planSite(WorldServer w) {
        if (chest != null) return;
        int[] s = findSite(w);
        int sx = s[0], sy = s[1], sz = s[2];
        site = new int[] { sx, sy, sz };
        // in front of the house's door (the door looks at the base): chest left of the path, table right of it
        int[] d = doorDir();
        int fx = sx + 2 + d[0] * 4, fz = sz + 2 + d[1] * 4;
        chest = station(w, fx - d[1] * 2, sy, fz + d[0] * 2, null);
        table = station(w, fx + d[1] * 2, sy, fz - d[0] * 2, chest);
        Events.log("crew: house site " + sx + " " + sy + " " + sz + ", chest at " + chest[0] + " " + chest[1] + " " + chest[2] + ", table at " + table[0] + " " + table[1] + " " + table[2]);
    }

    /**
     * A cell for a station (a chest, a table) near where it is meant to go: something firm to stand it on, and a spot
     * beside it from which it is reached on foot from the base; the nearest such one (not the other station's cell)
     */
    int[] station(WorldServer w, int x, int y, int z, int[] not) {
        Ground.Flood f = Ground.baseFlood(this, w);
        int[] best = null;
        double bd = 1e9;
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++)
                for (int dy = -3; dy <= 3; dy++) {
                    int cx = x + dx, cy = y + dy, cz = z + dz;
                    if (not != null && not[0] == cx && not[2] == cz) continue;
                    if (!soft(w, cx, cy, cz) || !solid(w, cx, cy - 1, cz)) continue;   // stands on something firm
                    if (f != null && f.near(cx, cy, cz, REACH) == null) continue;      // reached on foot from the base
                    double dd = Math.abs(dx) + Math.abs(dz) + Math.abs(dy) * 1.5;
                    if (dd < bd) { bd = dd; best = new int[] { cx, cy, cz }; }
                }
        if (best == null) { int gy = ground(w, x, y, z); best = new int[] { x, gy < 0 ? y : gy, z }; }
        return best;
    }

    /** the house's door looks from the site to the base */
    int[] doorDir() {
        int[] s = site;
        double dx = base[0] - (s[0] + 2), dz = base[2] - (s[2] + 2);
        if (Math.abs(dx) > Math.abs(dz)) return new int[] { dx > 0 ? 1 : -1, 0 };
        return new int[] { 0, dz > 0 ? 1 : -1 };
    }

    /** a flat 9x9 piece of ground near the base, clear above, for a 5x5 house with room around it */
    int[] findSite(WorldServer w) {
        int bx = base[0], by = base[1], bz = base[2];
        double fx = -Math.sin(Math.toRadians(baseYaw)), fz = Math.cos(Math.toRadians(baseYaw));
        int[] best = null;
        double bs = -1e9;
        for (int ox = -14; ox <= 14; ox++)
            for (int oz = -14; oz <= 14; oz++) {
                int x0 = bx + ox, z0 = bz + oz;
                int y0 = ground(w, x0 + 2, by, z0 + 2);
                if (y0 < 0) continue;
                int bad = 0;
                for (int x = x0 - 2; x <= x0 + 6 && bad < 6; x++)
                    for (int z = z0 - 2; z <= z0 + 6 && bad < 6; z++) {
                        boolean in = x >= x0 && x <= x0 + 4 && z >= z0 && z <= z0 + 4;
                        if (!stand(w, x, y0, z)) bad += in ? 3 : 1;
                        else if (in && !soft(w, x, y0 + 2, z)) bad += 3;
                        if (in && !(soft(w, x, y0 + 3, z) && soft(w, x, y0 + 4, z))) bad += 3;
                    }
                if (bad >= 6) continue;
                double cx = x0 + 2 - bx, cz = z0 + 2 - bz, d = Math.hypot(cx, cz);
                double ahead = (cx * fx + cz * fz) / Math.max(1, d);   // in front of where the player looked
                double score = -bad * 4 - Math.abs(d - 9) * 1.5 + ahead * 6;
                if (score > bs) { bs = score; best = new int[] { x0, y0, z0 }; }
            }
        if (best == null) best = new int[] { bx + 4, by, bz + 4 };
        return best;
    }

    /** stone open to the air near the base, a few together near the agent, not reserved by another (reserved for it) */
    static List<int[]> openStone(Crew c, WorldServer w, Agent a, int n) {
        int bx = c.base[0], by = c.base[1], bz = c.base[2];
        List<int[]> stones = new ArrayList<int[]>();
        for (int x = bx - 24; x <= bx + 24; x++)
            for (int z = bz - 24; z <= bz + 24; z++)
                for (int y = by - 6; y <= by + 10; y++) {
                    if (w.getBlock(x, y, z) != Blocks.stone || !w.isAirBlock(x, y + 1, z) || c.board.reserved(a, x, y, z)) continue;
                    if (!Ground.mayDig(c, x, y, z, "gather")) continue;   // the protected zones (base, stations, the mine's stairs and its way)
                    int[] spot = null;
                    for (int[] d : new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } })
                        for (int dy = 0; dy >= -1 && spot == null; dy--)
                            if (stand(w, x + d[0], y + dy, z + d[1])) spot = new int[] { x + d[0], y + dy, z + d[1] };
                    if (spot != null) stones.add(new int[] { x, y, z, spot[0], spot[1], spot[2] });
                }
        final double ax = a.posX, az = a.posZ;
        Collections.sort(stones, new Comparator<int[]>() {
            @Override public int compare(int[] x, int[] y) { return Double.compare(Math.hypot(x[0] - ax, x[2] - az), Math.hypot(y[0] - ax, y[2] - az)); }
        });
        List<int[]> out = new ArrayList<int[]>();
        Ground.Flood from = Ground.flood(w, Ground.feet(w, a.posX, a.posY, a.posZ), 32, null);
        int checks = 0;
        for (int[] s : stones) {
            if (out.size() >= n || checks > 24) break;
            if (!out.isEmpty() && Math.hypot(s[0] - out.get(0)[0], s[2] - out.get(0)[2]) > 3.5) continue;
            if (from.near(s[0], s[1], s[2], REACH) == null) continue;   // not reachable from where it is
            checks++;
            if (!Ground.digLeavesWay(c, w, s[0], s[1], s[2], "gather")) continue;   // the dig would leave a pit with no way back
            c.board.reserve(a, s[0], s[1], s[2]);
            out.add(s);
        }
        return out;
    }

    /** dig blocks one after another, each from where it is seen and reached */
    static class Mine implements Task {
        interface TargetGuard { Result beforeBreak(Agent a, WorldServer w, int x, int y, int z); }
        final List<int[]> blocks;
        final TargetGuard targetGuard;
        int i, tries, mined, missed;
        GoTo walk;
        Break br;
        boolean onTarget;
        Mine(List<int[]> blocks) { this(blocks, null); }
        Mine(List<int[]> blocks, TargetGuard targetGuard) { this.blocks = blocks; this.targetGuard = targetGuard; }
        Result beforeBreak(Agent a, WorldServer w, int x, int y, int z) {
            return targetGuard == null ? null : targetGuard.beforeBreak(a, w, x, y, z);
        }
        GoTo approach(int[] b) {
            GoTo to = b.length > 5 && tries == 1 ? new GoTo(b[3] + 0.5, b[4], b[5] + 0.5, 0.7)
                    : new GoTo(b[0] + 0.5, b[1] + 1, b[2] + 0.5 + (tries == 3 ? 1.5 : 0), 1.8);
            return targetGuard == null ? to : to.walkOnly();
        }
        public Result step(Agent a, WorldServer w) {
            if (i >= blocks.size()) return mined > 0 || missed == 0 ? Result.OK : Result.BLOCKED;   // nothing got at: not a success
            int[] b = blocks.get(i);
            if (br != null) {
                Result gate = beforeBreak(a, w, br.x, br.y, br.z);
                if (gate != null) {
                    if (a != null && w != null) w.destroyBlockInWorldPartially(a.getEntityId(), br.x, br.y, br.z, -1);
                    br = null; // remove a stale crack overlay when a live target changes mid-swing
                    return gate;
                }
                Result r = br.step(a, w);
                if (r == Result.RUNNING) return r;
                br = null;
                if (onTarget && r == Result.OK) mined++;
                if (r == Result.NO_TOOL && onTarget) return Result.NO_TOOL;
                Ground.changed();
                Skills.lightUp(a, w);
                return Result.RUNNING;
            }
            if (soft(w, b[0], b[1], b[2]) || w.getBlock(b[0], b[1], b[2]).getMaterial().isLiquid()) { i++; tries = 0; walk = null; return Result.RUNNING; }   // gone already
            int[] in = blocker(a, w, b[0], b[1], b[2]);
            if (eyeDist(a, b[0], b[1], b[2]) <= REACH) {
                if (in == null) {
                    Result gate = beforeBreak(a, w, b[0], b[1], b[2]);
                    if (gate != null) return gate;
                    br = new Break(b[0], b[1], b[2], 0.75); onTarget = true; return Result.RUNNING;
                }
                if (GoTo.mayDigThrough(a, w, in[0], in[1], in[2])) {
                    Result gate = beforeBreak(a, w, in[0], in[1], in[2]);
                    if (gate != null) return gate;
                    br = new Break(in[0], in[1], in[2], 0); onTarget = false; return Result.RUNNING;
                }   // rock in the way: through it
            }
            if (walk == null) {
                if (++tries > 3) { i++; tries = 0; missed++; return Result.RUNNING; }   // could not get at it
                walk = approach(b);
            }
            if (walk.step(a, w) != Result.RUNNING) walk = null;
            return Result.RUNNING;
        }
    }

    /** a 5x5 house by a see-through plan: walls of planks with log corners, a door and windows, a roof */
    static class House extends Phase {
        final List<int[]> plan = new ArrayList<int[]>();   // x, y, z, kind (0 planks, 1 log, 2 cobblestone)
        final Set<Long> claimed = new HashSet<Long>();
        final Retry retry = new Retry();   // the crew's one retry policy, for the blocks of this plan
        boolean skipping(int x, int y, int z) {
            return !retry.allowed(key(x, y, z), ticks);
        }

        /** a block that could not be put: the retry policy decides when again, and when it is given up (said once) */
        void fail(int[] b, Agent a) {
            if (retry.failed(key(b[0], b[1], b[2]), ticks))
                Events.log("crew: the block at " + b[0] + " " + b[1] + " " + b[2] + " cannot be put (" + Retry.GIVE_UP + " tries): left for now");
        }
        final Set<Integer> missing = new HashSet<Integer>();   // kinds the chest ran out of
        int shownAt = -1;
        void start(final Crew c, WorldServer w, EntityPlayerMP p) {
            c.planSite(w);
            int[] s = site;
            int x0 = s[0], y0 = s[1], z0 = s[2];
            int[] d = c.doorDir();
            IInventory inv = c.chestInv(w);
            int stone = inv == null ? 0 : count(inv, Item.getItemFromBlock(Blocks.cobblestone));
            for (int y = y0; y <= y0 + 2; y++)
                for (int x = x0; x <= x0 + 4; x++)
                    for (int z = z0; z <= z0 + 4; z++) {
                        boolean edge = x == x0 || x == x0 + 4 || z == z0 || z == z0 + 4;
                        if (!edge) continue;
                        boolean corner = (x == x0 || x == x0 + 4) && (z == z0 || z == z0 + 4);
                        int lx = x - x0 - 2, lz = z - z0 - 2;   // -2..2 from the middle
                        boolean door = (d[0] != 0 ? lx == 2 * d[0] && lz == 0 : lz == 2 * d[1] && lx == 0) && y <= y0 + 1;
                        boolean window = y == y0 + 1 && (d[0] != 0 ? lz != 0 && Math.abs(lz) == 2 && lx == 0 : lx != 0 && Math.abs(lx) == 2 && lz == 0);
                        if (door || window) continue;
                        plan.add(new int[] { x, y, z, corner ? 1 : 0 });
                    }
            int roof = stone >= 20 ? 2 : 0;
            for (int x = x0 - 1; x <= x0 + 5; x++)
                for (int z = z0 - 1; z <= z0 + 5; z++) plan.add(new int[] { x, y0 + 3, z, roof });
            for (int x = x0 + 1; x <= x0 + 3; x++)
                for (int z = z0 + 1; z <= z0 + 3; z++) plan.add(new int[] { x, y0 + 4, z, 0 });
            plan.add(new int[] { x0 + 2, y0 + 5, z0 + 2, 1 });
            show(c, w);
           Events.log("crew: house plan of " + plan.size() + " blocks at " + x0 + " " + y0 + " " + z0 + (roof == 2 ? " (stone roof)" : ""));
            for (Agent a : c.agents) Crew.job(a, new Build(this));
        }
        /** the see-through plan for the camera (the client draws it) */
        void show(Crew c, WorldServer w) {
            JsonObject m = new JsonObject();
            m.addProperty("t", "ghost");
            JsonArray put = new JsonArray();
            for (int[] b : plan) {
                JsonArray a = new JsonArray();
                a.add(new JsonPrimitive(b[0]));
                a.add(new JsonPrimitive(b[1]));
                a.add(new JsonPrimitive(b[2]));
                a.add(new JsonPrimitive(Item.itemRegistry.getNameForObject(Item.getItemFromBlock(block(b[3])))));
                a.add(new JsonPrimitive(b[3] == 5 ? 5 : 0));
                put.add(a);
            }
            m.add("place", put);
            m.add("dig", new JsonArray());
            Events.ghost(m);
            shownAt = ticks;
        }
        boolean done(Crew c) {
            if (!super.done(c)) return false;
            return true;
        }
        /** the next block for an agent: the lowest not done and not claimed, resting on something, nearest */
        int[] next(Agent a, WorldServer w) {
            int low = Integer.MAX_VALUE;
            for (int[] b : plan)
                if (soft(w, b[0], b[1], b[2]) && b[1] < low && !Ground.passages.contains(key(b[0], b[1], b[2])) && !skipping(b[0], b[1], b[2]) && !(missing.contains(b[3]) && Build.item(a, b[3]) == null)) low = b[1];
            if (low == Integer.MAX_VALUE) return null;
            int[] best = null;
            double bd = 1e9;
            for (int[] b : plan) {
                if (Ground.passages.contains(key(b[0], b[1], b[2]))) continue;   // a way opened through the wall stays open
                if (b[1] > low + 1 || !soft(w, b[0], b[1], b[2]) || claimed.contains(key(b[0], b[1], b[2]))) continue;
                if (missing.contains(b[3]) && Build.item(a, b[3]) == null || skipping(b[0], b[1], b[2])) continue;
                boolean rests = !Skills.supports(w, b[0], b[1], b[2], false).isEmpty();   // the placing's own rule
                if (!rests) continue;
                double dd = dist(a, b[0] + 0.5, b[1], b[2] + 0.5) * 1.5 + (b[1] - low) * 4;
                if (dd < bd) { bd = dd; best = b; }
            }
            return best;
        }
    }

    /** inside the walls of the house (a builder must not shut itself in) */
    static boolean inside(Agent a) {
        int[] s = site;
        return s != null && a.posX > s[0] - 1 && a.posX < s[0] + 6 && a.posZ > s[2] - 1 && a.posZ < s[2] + 6;
    }

    /** an agent's part of the house: take a block of the plan, walk to a spot outside the walls, put it */
    static class Build implements Task {
        final House h;
        int[] cur;
        GoTo walk;
        Task fetch;
        int ticks, wait, fetchKind;
        Build(House h) { this.h = h; }
        /** the stack in the bag for a kind of block of the plan (any wood for a log), or null */
        static ItemStack item(Agent a, int kind) {
            Item want = Item.getItemFromBlock(block(kind));
            for (ItemStack s : a.bag)
                if (s.getItem() == want || kind == 1 && s.getItem() == Item.getItemFromBlock(Blocks.log2)) return s;
            return null;
        }
        public Result step(Agent a, WorldServer w) {
            if (h.ticks - h.shownAt < 50) { face(a, site[0] + 2.5, site[1] + 1.5, site[2] + 2.5); return Result.RUNNING; }   // everyone looks at the plan first
            if (fetch != null) {   // to the chest for more of what the walls take
                Result r = fetch.step(a, w);
                if (r == Result.RUNNING) return r;
                fetch = null;
                if (r == Result.NO_ITEMS) h.missing.add(fetchKind);   // the chest is out of it (for everyone)
                else if (r != Result.OK) return r;                   // this agent cannot get there: its card, not the house, fails
                return Result.RUNNING;
            }
            if (cur == null) {
                cur = h.next(a, w);
                if (cur == null) {
                    boolean left = false, possible = false;   // blocks left at all; blocks left this agent could still put
                    for (int[] b : h.plan) {
                        if (!soft(w, b[0], b[1], b[2]) || Ground.passages.contains(key(b[0], b[1], b[2]))) continue;
                        left = true;
                        if (!h.missing.contains(b[3]) || item(a, b[3]) != null) possible = true;
                    }
                    if (!left) return Result.OK;
                    if (!possible) return Result.NO_ITEMS;   // all that is left needs what neither it nor the chest has
                    if (++wait > 20 * 30) return Result.OK;   // the rest waits on others (a layer below, a cell someone stands in)
                    return Result.RUNNING;
                }
                wait = 0;
                if (item(a, cur[3]) == null) {   // not in the bag: a load from the chest (or this kind is out)
                    fetchKind = cur[3];
                    int want = 0;
                    for (int[] b : h.plan) if (b[3] == cur[3] && soft(w, b[0], b[1], b[2])) want++;
                    fetch = new Day1.Take(instance, cur[3] == 1 ? Day1.LOG : Item.getItemFromBlock(block(cur[3])), Math.max(1, Math.min(16, want / Math.max(1, instance.agents.size()) + 1)));
                    cur = null;
                    return Result.RUNNING;
                }
                h.claimed.add(key(cur[0], cur[1], cur[2]));
                a.setCurrentItemOrArmor(0, item(a, cur[3]));
                // a spot outside the house, toward the agent, on the ground
                int[] s = site;
                double cx = s[0] + 2.5, cz = s[2] + 2.5, dx = cur[0] + 0.5 - cx, dz = cur[2] + 0.5 - cz, dl = Math.max(0.5, Math.hypot(dx, dz));
                double out = 4.3 - Math.min(2.0, dl * 0.0);
                double tx = cx + dx / dl * out, tz = cz + dz / dl * out;
                if (dl < 1.0) { tx = a.posX; tz = a.posZ; }
                int gy = ground(w, MathHelper.floor_double(tx), s[1], MathHelper.floor_double(tz));
                walk = new GoTo(tx, gy < 0 ? s[1] : gy, tz, 1.4);
                if (eyeDist(a, cur[0], cur[1], cur[2]) <= 6.0 && !inside(a)) walk = null;   // in reach from here: no walk
                ticks = 0;
            }
            if (!soft(w, cur[0], cur[1], cur[2])) { h.claimed.remove(key(cur[0], cur[1], cur[2])); cur = null; return Result.RUNNING; }
            double reach = 6.4;
            if (eyeDist(a, cur[0], cur[1], cur[2]) > reach && walk != null) {
                if (walk.step(a, w) != Result.RUNNING) {   // arrived and still far: closer
                    walk = new GoTo(cur[0] + 0.5, a.posY, cur[2] + 0.5, 2.0);
                }
                return Result.RUNNING;
            }
            a.getNavigator().clearPathEntity();
            face(a, cur[0] + 0.5, cur[1] + 0.5, cur[2] + 0.5);
            a.act(cur[0], cur[1], cur[2], 3);
            if (++ticks < 4) return Result.RUNNING;
            ItemStack in = item(a, cur[3]);   // what is put comes out of the bag (Skills: the right item, its own place sound)
            Result r = in == null ? Result.NO_ITEMS : Skills.placeFromBag(a, w, cur[0], cur[1], cur[2], in.getItem(), in.getMetadata());
            if (r == Result.BUSY) h.retry.postpone(key(cur[0], cur[1], cur[2]), h.ticks, 60);   // someone moves out of the cell: another block meanwhile
            else if (r != Result.OK && r != Result.BLOCKED) Events.log("crew: " + a.getCustomNameTag() + " build " + Ledger.name(in) + " at " + cur[0] + " " + cur[1] + " " + cur[2] + " -> " + r);
            if (r == Result.BLOCKED) h.fail(cur, a);   // nothing to put it against: later, later still, then not in this phase
            h.claimed.remove(key(cur[0], cur[1], cur[2]));
            cur = null;
            return Result.RUNNING;
        }
    }

    /** everyone in front of the house (or the player), jumping */
    static class Cheer extends Phase {
        void start(Crew c, WorldServer w, EntityPlayerMP p) {
            c.board.goals.clear();
            int[] s = site;
            double fx, fz, lx, ly, lz;
            if (s != null) {
                int[] d = c.doorDir();
                fx = s[0] + 2.5 + d[0] * 4.5;
                fz = s[2] + 2.5 + d[1] * 4.5;
                lx = fx + d[0] * 6;
                lz = fz + d[1] * 6;
                ly = s[1] + 1.5;
            } else {
                fx = p.posX;
                fz = p.posZ;
                lx = p.posX;
                lz = p.posZ;
                ly = p.posY;
            }
            front = new double[] { lx, ly, lz };
            int k = 0, n = c.agents.size();
            int[] d = s != null ? c.doorDir() : new int[] { 1, 0 };
            for (Agent a : c.agents) {
                double off = (k - (n - 1) / 2.0) * 1.5;
                double tx = fx + d[1] * off, tz = fz + d[0] * off;
                int gy = ground(w, MathHelper.floor_double(tx), s != null ? s[1] : (int) p.posY, MathHelper.floor_double(tz));
                Crew.job(a, new GoTo(tx, gy < 0 ? (s != null ? s[1] : p.posY) : gy, tz, 0.8));
                Crew.job(a, new Celebrate(lx, ly, lz, 20 * 8, k * 4));
                k++;
            }
        }
    }
}
