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
        abstract void start(Crew c, WorldServer w, EntityPlayerMP p);
        boolean done(Crew c) {
            for (Agent a : c.agents) if (!a.jobs.isEmpty()) return false;
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
        else if (w.startsWith("tool") || w.startsWith("craft") || w.startsWith("инстр")) ph = new Tools();
        else if (w.startsWith("stone") || w.startsWith("mine") || w.startsWith("кам") || w.startsWith("шахт")) ph = new Day1.Mine();
        else if (w.startsWith("house") || w.startsWith("build") || w.startsWith("base") || w.startsWith("дом")) ph = new Day1.Base();
        else if (w.startsWith("cheer") || w.startsWith("done") || w.startsWith("рад")) ph = new Cheer();
        else return "Unknown crew task '" + what + "': summon, wood, tools, mine, upgrade, gift, house, gather or cheer.";
        ph.name = w;
        String kind = ph instanceof Summon ? "summon" : ph instanceof Day1.WoodChain ? "wood" : ph instanceof Tools ? "tools" : ph instanceof Day1.Mine ? "mine"
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
        Director.log("crew order: " + w + " " + count);
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
        for (java.util.Iterator<Agent> it = agents.iterator(); it.hasNext(); ) if (it.next().isDead) it.remove();
        if (current != null && current.ticks > current.max) {   // too long: what is carried goes to the chest, the phase ends
            Director.log("crew phase " + current.name + ": out of time");
            for (Agent a : agents) {
                clearJobs(a);
                a.getNavigator().clearPathEntity();
                if (!a.bag.isEmpty() && chest != null) Crew.job(a, new Stash(Item.getItemFromBlock(Blocks.log), 1));
            }
            current.max = Integer.MAX_VALUE;
        }
        if (current != null) {
            current.ticks++;
            if (current.done(this) && current.ticks > 10) {
                Director.log("crew phase done: " + current.name + " in " + current.ticks / 20 + " s");
                status = "done " + current.name;
                finished.add(current.kind);
                current = null;
                save(w);
                Ledger.save(w.getSaveHandler().getWorldDirectory());
                Director.log(Ledger.report());
            }
        }
        while (current == null && !phases.isEmpty() && skip.contains(phases.peek().kind)) {
            Director.log("crew phase skipped: " + phases.poll().name);
        }
        if (current == null && !phases.isEmpty() && p != null && (!gated || allowed.contains(phases.peek().kind))) {
            current = phases.poll();
            Director.log("crew phase: " + current.name);
            status = current.name;
            started.add(current.kind);
            originNow = current.origin;
            current.start(this, w, p);
            originNow = Origin.AUTO_DEFAULT;
        }
        if (!loaded) load(w);
        if (w.getTotalWorldTime() % 40 == 0) adopt(w);
        for (Agent a : agents) {
            Task t = a.jobs.peek();
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
                Director.log("crew task failed: " + ex);
                r = Result.BLOCKED;
            }
            if (r.done()) {
                a.jobs.poll();
                a.origins.poll();
                Ledger.result(r);
                if (r.failed()) Director.log("crew: " + a.getCustomNameTag() + " " + t.getClass().getSimpleName() + " -> " + r);
            }
        }
    }

    /** what the crew knows of its base, kept in the world's folder (the next day goes on from it) */
    void save(WorldServer w) {
        try {
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            com.google.gson.Gson g = new com.google.gson.Gson();
            if (base != null) o.add("base", g.toJsonTree(base));
            if (site != null) o.add("site", g.toJsonTree(site));
            if (chest != null) o.add("chest", g.toJsonTree(chest));
            if (table != null) o.add("table", g.toJsonTree(table));
            if (Day1.mine != null) o.add("mine", g.toJsonTree(Day1.mine));
            if (Day1.furnace != null) o.add("furnace", g.toJsonTree(Day1.furnace));
            o.add("done", g.toJsonTree(new ArrayList<String>(finished)));
            o.addProperty("day", 1);
            java.io.File f = new java.io.File(w.getSaveHandler().getWorldDirectory(), "crew.json");
            java.nio.file.Files.write(f.toPath(), o.toString().getBytes("UTF-8"));
        } catch (Exception e) {
            Director.log("crew: not saved: " + e);
        }
    }

    boolean loaded;

    void load(WorldServer w) {
        loaded = true;
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
            Director.log("crew: the base of the day before is known: " + o);
        } catch (Exception e) {
            Director.log("crew: not loaded: " + e);
        }
    }

    /** the agents the world already has (a saved world: the crew of the day before) */
    void adopt(WorldServer w) {
        for (Object o : w.loadedEntityList)
            if (o instanceof Agent && !agents.contains(o) && !((Agent) o).isDead) {
                agents.add((Agent) o);
                Director.log("crew: " + ((Agent) o).getCustomNameTag() + " is back");
            }
        Collections.sort(agents, new Comparator<Agent>() {
            @Override public int compare(Agent x, Agent y) { return x.skin() - y.skin(); }
        });
    }

    EntityPlayerMP playerIn(WorldServer w) {
        for (Object o : w.playerEntities) {
            EntityPlayerMP p = (EntityPlayerMP) o;
            if (player == null || p.getCommandSenderName().equals(player)) return p;
        }
        return null;
    }

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
        Vec3 from = Vec3.createVectorHelper(a.posX, a.posY + a.getEyeHeight(), a.posZ);
        Vec3 to = Vec3.createVectorHelper(x + 0.5, y + 0.5, z + 0.5);
        MovingObjectPosition hit = w.rayTraceBlocks(from, to, false, true, false);
        if (hit == null) return null;
        if (hit.blockX == x && hit.blockY == y && hit.blockZ == z) return null;
        return new int[] { hit.blockX, hit.blockY, hit.blockZ };
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

    /** walk (run) to a point with the game's path finder */
    static class GoTo implements Task {
        double x, y, z, within;
        int ticks, tries;
        GoTo(double x, double y, double z, double within) { this.x = x; this.y = y; this.z = z; this.within = within; }
        public Result step(Agent a, WorldServer w) {
            ticks++;
            if (dist(a, x, y, z) <= within) { a.getNavigator().clearPathEntity(); return Result.OK; }
            if (ticks % 20 == 1 || a.getNavigator().noPath()) {
                if (!a.getNavigator().tryMoveToXYZ(x, y, z, 1.0D) && ++tries > 12) {   // no way: a step closer by itself
                    Director.log("crew: " + a.getCustomNameTag() + " has no way to " + (int) x + " " + (int) y + " " + (int) z);
                    return Result.NO_PATH;
                }
            }
            if (ticks % 40 == 0) {
                if (Math.abs(a.posX - a.lastX) + Math.abs(a.posZ - a.lastZ) < 0.2) a.stuck++;
                else a.stuck = 0;
                a.lastX = a.posX;
                a.lastZ = a.posZ;
                if (a.stuck > 3) { a.stuck = 0; return Result.NO_PATH; }
            }
            return (ticks > 20 * 40) ? Result.TIMEOUT : Result.RUNNING;
        }
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

    /** break one block as a player: looking at it, the arm swinging, the cracks growing */
    static class Break {
        int x, y, z, ticks;
        double seconds;
        Break(int x, int y, int z, double seconds) { this.x = x; this.y = y; this.z = z; this.seconds = seconds; }
        /** true when the block is gone */
        Result step(Agent a, WorldServer w) {
            Block b = w.getBlock(x, y, z);
            if (b.getMaterial() == Material.air) return Result.OK;
            face(a, x + 0.5, y + 0.5, z + 0.5);
            a.act(x, y, z, 1);
            ticks++;
            if (ticks < 6) return Result.RUNNING;   // turn to it first
            if (ticks % 5 == 0) {
                a.swingItem();
                w.playSoundEffect(x + 0.5, y + 0.5, z + 0.5, b.stepSound.getStepSound(), 0.35F, 0.6F);
            }
            double k = (ticks - 6) / (seconds * 20);
            w.destroyBlockInWorldPartially(a.getEntityId(), x, y, z, Math.min(9, (int) (k * 10)));
            if (k < 1) return Result.RUNNING;
            int meta = w.getBlockMetadata(x, y, z);
            ItemStack held = a.getHeldItem();
            boolean tool = b.getMaterial().isToolNotRequired() || held != null && held.canItemHarvestBlock(b);
            if (!tool) Ledger.flow(Ledger.Way.FROM_NOTHING, new ItemStack(b, 1, b.damageDropped(meta)), "Crew.Break drop without the tool");
            Ledger.act(a, "break " + b.getUnlocalizedName().replace("tile.", ""));
            b.dropBlockAsItem(w, x, y, z, meta, 0);
            w.playAuxSFX(2001, x, y, z, Block.getIdFromBlock(b) + (meta << 12));
            w.setBlockToAir(x, y, z);
            w.destroyBlockInWorldPartially(a.getEntityId(), x, y, z, -1);
            return Result.OK;
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
                if (pick == null || ++tries > 4) return Result.NO_PATH;
                spot = pick[3];
                walk = new GoTo(pick[0] + 0.5, pick[1], pick[2] + 0.5, 0.6);
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
                Ledger.flow(Ledger.Way.DROP, s);
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
                    if (it.isDead || !it.onGround) continue;
                    double d = dist(a, it.posX, it.posY, it.posZ);
                    if (d < best) { best = d; target = it; }
                }
                if (target == null) return (ticks > 40) ? Result.OK : Result.RUNNING;
                walk = new GoTo(target.posX, target.posY, target.posZ, 0.7);
            }
            if (walk.step(a, w) != Result.RUNNING) target = null;
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
            if (chest == null) return Result.GONE;
            if (state == 0) { walk = new GoTo(chest[0] + 0.5, chest[1], chest[2] + 0.5, 2.3); state = 1; }
            if (state == 1) { if (walk.step(a, w) == Result.RUNNING) return Result.RUNNING; state = 2; }
            face(a, chest[0] + 0.5, chest[1] + 0.5, chest[2] + 0.5);
            ticks++;
            IInventory inv = chestInv(w);
            if (ticks == 4) { w.addBlockEvent(chest[0], chest[1], chest[2], Blocks.chest, 1, 1); w.playSoundEffect(chest[0] + 0.5, chest[1] + 0.5, chest[2] + 0.5, "random.chestopen", 0.5F, 1.0F); }
            if (ticks == 12 && inv != null) {
                a.swingItem();
                int kept = 0;
                for (java.util.Iterator<ItemStack> it = a.bag.iterator(); it.hasNext(); ) {
                    ItemStack s = it.next();
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
                a.bag.clear();
                Ledger.act(a, "stash");
                if (kept > 0 && keep != null) a.bag.add(new ItemStack(keep, kept));
                inv.markDirty();
                if (a.getHeldItem() != null && !(a.getHeldItem().getItem() instanceof net.minecraft.item.ItemTool)) a.setCurrentItemOrArmor(0, null);
            }
            if (ticks == 22) { w.addBlockEvent(chest[0], chest[1], chest[2], Blocks.chest, 1, 0); w.playSoundEffect(chest[0] + 0.5, chest[1] + 0.5, chest[2] + 0.5, "random.chestclosed", 0.5F, 1.0F); }
            return (ticks >= 26) ? Result.OK : Result.RUNNING;
        }
    }

    /** put a block: walk near, look at it, swing, it is there */
    static class Place implements Task {
        final int x, y, z, meta;
        final Block block;
        int ticks;
        GoTo walk;
        Place(int x, int y, int z, Block block, int meta) { this.x = x; this.y = y; this.z = z; this.block = block; this.meta = meta; }
        public Result step(Agent a, WorldServer w) {
            if (walk == null) walk = new GoTo(x + 0.5, y, z + 0.5, 2.6);
            if (eyeDist(a, x, y, z) > 3.2 && walk.step(a, w) == Result.RUNNING) return Result.RUNNING;
            face(a, x + 0.5, y + 0.5, z + 0.5);
            if (++ticks < 8) return Result.RUNNING;
            a.swingItem();
            if (!soft(w, x, y, z)) return Result.GONE;
            w.setBlock(x, y, z, block, meta, 3);
            w.playSoundEffect(x + 0.5, y + 0.5, z + 0.5, block.stepSound.getPlaceSound(), 1.0F, 0.8F);
            Ledger.fakeBlock("Crew.Place " + block.getUnlocalizedName().replace("tile.", ""), new ItemStack(block, 1, meta));
            Ledger.act(a, "place");
            return Result.OK;
        }
    }

    /** work at something: swing the arm for a while, a sound now and then, then hold what was made */
    static class Work implements Task {
        final double x, y, z;
        final int len;
        final ItemStack made;
        final String sound;
        int ticks;
        Work(double x, double y, double z, int len, ItemStack made, String sound) { this.x = x; this.y = y; this.z = z; this.len = len; this.made = made; this.sound = sound; }
        public Result step(Agent a, WorldServer w) {
            face(a, x, y, z);
            a.act((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z), 2);
            ticks++;
            if (ticks % 7 == 0) {
                a.swingItem();
                if (sound != null) w.playSoundEffect(x, y, z, sound, 0.5F, 0.9F + w.rand.nextFloat() * 0.3F);
            }
            if (ticks >= len) {
                if (made != null) {
                    a.setCurrentItemOrArmor(0, made.copy());
                    Ledger.flow(Ledger.Way.FROM_NOTHING, made, "Crew.Work made in hand");
                    Ledger.act(a, "craft (pretend)");
                    w.func_147487_a("happyVillager", x, y + 0.6, z, 6, 0.3, 0.2, 0.3, 0.0);
                }
                return Result.OK;
            }
            return Result.RUNNING;
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
            c.placeChest(w);
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
                    if (h >= 4 && h <= 5 && Math.abs(y - cy) <= 6 && w.getBlock(x + 1, y, z) != b && w.getBlock(x - 1, y, z) != b
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

    /** the chest for the crew's things, by the place the crew was called to */
    void placeChest(WorldServer w) {
        if (chest != null) return;
        int[] s = findSite(w);
        int sx = s[0], sy = s[1], sz = s[2];
        site = new int[] { sx, sy, sz };
        // in front of the house's door (the door looks at the base): chest left of the path, table right of it
        int[] d = doorDir();
        int fx = sx + 2 + d[0] * 4, fz = sz + 2 + d[1] * 4;
        chest = new int[] { fx - d[1] * 2, ground(w, fx - d[1] * 2, sy, fz + d[0] * 2), fz + d[0] * 2 };
        table = new int[] { fx + d[1] * 2, ground(w, fx + d[1] * 2, sy, fz - d[0] * 2), fz - d[0] * 2 };
        if (chest[1] < 0) chest[1] = sy;
        if (table[1] < 0) table[1] = sy;
        Agent a = agents.isEmpty() ? null : agents.get(0);
        w.setBlock(chest[0], chest[1], chest[2], Blocks.chest, 0, 3);
        Ledger.fakeBlock("Crew.placeChest", new ItemStack(Blocks.chest));
        w.func_147487_a("cloud", chest[0] + 0.5, chest[1] + 0.5, chest[2] + 0.5, 8, 0.3, 0.3, 0.3, 0.01);
        Director.log("crew: house site " + sx + " " + sy + " " + sz + ", chest " + chest[0] + " " + chest[1] + " " + chest[2] + (a == null ? "" : ""));
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

    /** a crafting table by the chest, then every helper makes its own wooden pickaxe at it */
    static class Tools extends Phase {
        void start(final Crew c, WorldServer w, EntityPlayerMP p) {
            c.placeChest(w);
            if (c.agents.isEmpty()) return;
            final int[] t = c.table;
            Agent first = c.agents.get(0);
            Crew.job(first, new GoTo(t[0] + 0.5, t[1], t[2] + 0.5 + 1.6, 1.2));
            Crew.job(first, new Work(t[0] + 0.5, t[1] + 0.2, t[2] + 0.5, 50, null, "dig.wood"));
            Crew.job(first, new Place(t[0], t[1], t[2], Blocks.crafting_table, 0));
            int k = 0;
            for (Agent a : c.agents) {
                double ang = Math.toRadians(k++ * 360.0 / c.agents.size());
                double sx = t[0] + 0.5 + Math.sin(ang) * 1.7, sz = t[2] + 0.5 + Math.cos(ang) * 1.7;
                if (a != first) Crew.job(a, new Wait(new java.util.concurrent.Callable<Boolean>() {
                    public Boolean call() { return MinecraftServer.getServer().worldServers[0].getBlock(t[0], t[1], t[2]) == Blocks.crafting_table; }
                }));
                Crew.job(a, new GoTo(sx, t[1], sz, 0.9));
                Crew.job(a, new Work(t[0] + 0.5, t[1] + 0.9, t[2] + 0.5, 70 + 16 * k, new ItemStack(Items.wooden_pickaxe), "random.wood_click"));
            }
        }
    }

    /** stone open to the air near the base: every helper digs a few with its pickaxe and brings them */
    static class Stone extends Phase {
        final int perAgent;
        Stone(int perAgent) { this.perAgent = perAgent; max = 20 * 55; }
        void start(Crew c, WorldServer w, EntityPlayerMP p) {
            c.placeChest(w);
            int bx = c.base[0], by = c.base[1], bz = c.base[2];
            List<int[]> stones = new ArrayList<int[]>();
            for (int x = bx - 28; x <= bx + 28; x++)
                for (int z = bz - 28; z <= bz + 28; z++)
                    for (int y = by - 6; y <= by + 10; y++) {
                        if (w.getBlock(x, y, z) != Blocks.stone) continue;
                        if (!w.isAirBlock(x, y + 1, z)) continue;
                        int[] spot = null;   // where one stands beside it: feet on its level or a block lower
                        for (int[] d : new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } })
                            for (int dy = 0; dy >= -1 && spot == null; dy--)
                                if (stand(w, x + d[0], y + dy, z + d[1])) spot = new int[] { x + d[0], y + dy, z + d[1] };
                        if (spot == null) continue;
                        stones.add(new int[] { x, y, z, spot[0], spot[1], spot[2] });
                    }
            Director.log("crew: " + stones.size() + " stone blocks open to the air near the base");
            for (Agent a : c.agents) {
                int[] seed = null;
                double bd = 1e9;
                for (int[] s : stones) {
                    if (c.taken.contains(key(s[0], s[1], s[2]))) continue;
                    double d = Math.hypot(s[0] - bx, s[2] - bz) + Math.hypot(s[0] - a.posX, s[2] - a.posZ) * 0.3;
                    if (d < bd) { bd = d; seed = s; }
                }
                if (seed == null) break;
                List<int[]> mine = new ArrayList<int[]>();
                for (int[] s : stones)
                    if (!c.taken.contains(key(s[0], s[1], s[2])) && Math.hypot(s[0] - seed[0], s[2] - seed[2]) < 3.5 && Math.abs(s[1] - seed[1]) <= 2) mine.add(s);
                final int[] sd = seed;
                Collections.sort(mine, new Comparator<int[]>() {
                    @Override public int compare(int[] x, int[] y) { return Double.compare(Math.hypot(x[0] - sd[0], x[2] - sd[2]), Math.hypot(y[0] - sd[0], y[2] - sd[2])); }
                });
                while (mine.size() > perAgent) mine.remove(mine.size() - 1);
                for (int[] s : mine) c.taken.add(key(s[0], s[1], s[2]));
                Crew.job(a, new Mine(mine));
                Crew.job(a, new Collect(seed[0] + 0.5, seed[1], seed[2] + 0.5, 5));
                Crew.job(a, c.new Stash(null, 0));
            }
        }
    }

    /** dig blocks one after another, each from where it is seen and reached */
    static class Mine implements Task {
        final List<int[]> blocks;
        int i, tries;
        GoTo walk;
        Break br;
        Mine(List<int[]> blocks) { this.blocks = blocks; }
        public Result step(Agent a, WorldServer w) {
            if (i >= blocks.size()) return Result.OK;
            int[] b = blocks.get(i);
            if (br != null) { if (br.step(a, w) != Result.RUNNING) { br = null; } return Result.RUNNING; }
            if (w.getBlock(b[0], b[1], b[2]) != Blocks.stone) { i++; tries = 0; walk = null; return Result.RUNNING; }
            boolean seen = eyeDist(a, b[0], b[1], b[2]) <= REACH && blocker(a, w, b[0], b[1], b[2]) == null;
            if (seen) { br = new Break(b[0], b[1], b[2], 0.75); return Result.RUNNING; }
            if (walk == null) {
                if (++tries > 3) { i++; tries = 0; return Result.RUNNING; }
                walk = b.length > 5 && tries == 1 ? new GoTo(b[3] + 0.5, b[4], b[5] + 0.5, 0.7)
                        : new GoTo(b[0] + 0.5, b[1] + 1, b[2] + 0.5 + (tries == 3 ? 1.5 : 0), 1.8);
            }
            if (walk.step(a, w) != Result.RUNNING) walk = null;
            return Result.RUNNING;
        }
    }

    /** a 5x5 house by a see-through plan: walls of planks with log corners, a door and windows, a roof */
    static class House extends Phase {
        final List<int[]> plan = new ArrayList<int[]>();   // x, y, z, kind (0 planks, 1 log, 2 cobblestone)
        final Set<Long> claimed = new HashSet<Long>();
        int shownAt = -1;
        void start(final Crew c, WorldServer w, EntityPlayerMP p) {
            c.placeChest(w);
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
           Director.log("crew: house plan of " + plan.size() + " blocks at " + x0 + " " + y0 + " " + z0 + (roof == 2 ? " (stone roof)" : ""));
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
            if (Pilot.instance != null) Pilot.instance.inbox.add(m);
            shownAt = ticks;
        }
        boolean done(Crew c) {
            if (!super.done(c)) return false;
            return true;
        }
        /** the next block for an agent: the lowest not done and not claimed, resting on something, nearest */
        int[] next(Agent a, WorldServer w) {
            int low = Integer.MAX_VALUE;
            for (int[] b : plan) if (soft(w, b[0], b[1], b[2]) && b[1] < low) low = b[1];
            if (low == Integer.MAX_VALUE) return null;
            int[] best = null;
            double bd = 1e9;
            for (int[] b : plan) {
                if (b[1] > low + 1 || !soft(w, b[0], b[1], b[2]) || claimed.contains(key(b[0], b[1], b[2]))) continue;
                boolean rests = !soft(w, b[0], b[1] - 1, b[2]) || !soft(w, b[0] + 1, b[1], b[2]) || !soft(w, b[0] - 1, b[1], b[2])
                        || !soft(w, b[0], b[1], b[2] + 1) || !soft(w, b[0], b[1], b[2] - 1);
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
        int ticks, wait;
        Build(House h) { this.h = h; }
        public Result step(Agent a, WorldServer w) {
            if (h.ticks - h.shownAt < 50) { face(a, site[0] + 2.5, site[1] + 1.5, site[2] + 2.5); return Result.RUNNING; }   // everyone looks at the plan first
            if (cur == null) {
                cur = h.next(a, w);
                if (cur == null) {
                    for (int[] b : h.plan) if (soft(w, b[0], b[1], b[2])) { if (++wait > 200) return Result.OK; return Result.RUNNING; }
                    return Result.OK;
                }
                h.claimed.add(key(cur[0], cur[1], cur[2]));
                a.setCurrentItemOrArmor(0, new ItemStack(block(cur[3])));
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
            a.swingItem();
            Block b = block(cur[3]);
            w.setBlock(cur[0], cur[1], cur[2], b, cur[3] == 5 ? 5 : 0, 3);
            w.playSoundEffect(cur[0] + 0.5, cur[1] + 0.5, cur[2] + 0.5, b.stepSound.getPlaceSound(), 0.9F, 0.8F);
            Ledger.act(a, "place");
            IInventory inv = instance.chestInv(w);   // what goes into the walls comes from the chest
            boolean paid = false;
            if (inv != null) {
                if (cur[3] == 2) paid = take(inv, Item.getItemFromBlock(Blocks.cobblestone), 1) > 0;
                else if (cur[3] == 1) paid = take(inv, Item.getItemFromBlock(Blocks.log), 1) > 0;
                else if (w.rand.nextInt(4) == 0 && take(inv, Item.getItemFromBlock(Blocks.log), 1) > 0)   // a log is four planks
                    Ledger.flow(Ledger.Way.INTO_NOTHING, new ItemStack(Blocks.log), "Crew.Build random log for planks");
            }
            if (paid) { Ledger.flow(Ledger.Way.CHEST_OUT, new ItemStack(b)); Ledger.flow(Ledger.Way.PLACED, new ItemStack(b)); }
            else Ledger.fakeBlock("Crew.Build " + b.getUnlocalizedName().replace("tile.", ""), new ItemStack(b));
            h.claimed.remove(key(cur[0], cur[1], cur[2]));
            cur = null;
            return Result.RUNNING;
        }
    }

    /** everyone in front of the house (or the player), jumping */
    static class Cheer extends Phase {
        void start(Crew c, WorldServer w, EntityPlayerMP p) {
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
