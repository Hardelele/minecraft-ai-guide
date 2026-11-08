package hardelele.aiguide.client;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.world.WorldServer;

/**
 * The player's own character as the crew's number 1. It takes cards from the board as an agent does, but only what the
 * autopilot (Pilot) honestly does with the player's real keys, eyes and buttons: walk, chop a tree, dig a block or a
 * vein (with a tool of the hotbar that takes it), put the carried things into the crew's chest, hit a mob, put a block
 * from the hotbar (TaskBoard.Play). Crafting, smelting, building from the chest, guarding are not the autopilot's: the
 * board gives them to the agents. It works only while the player is in the commander or gave himself to the AI (the
 * panel's switch, its key) - or on his own orders (the commander's right click with him picked, the guide's crew_order
 * for number 1). Any movement key, a fall, lava: the controls are the player's at once, the card goes back (his own
 * orders go: he does it himself now); so does leaving the commander when he did not give himself. Its runner is on the
 * crew's (server) thread; the autopilot on the client's: orders go to it through its inbox, how each ended comes back.
 */
class PlayerAgent implements Doer {
    /** the player gave himself to the AI: he takes the board's cards as an agent does, outside the commander too */
    static volatile boolean givenToAI;

    volatile boolean ordered, holding, alive;
    volatile double x, y, z;
    volatile String nick = "";
    /** the card it is on (read by the commander's panel) */
    volatile TaskBoard.Card card;
    Work work;
    EntityPlayerMP player;
    Crew crew;
    boolean wasCommander;
    int clock;

    public int num() { return 1; }
    public String who() { return "Агент 1 (" + nick + ")"; }
    public boolean alive() { return alive; }
    public boolean ordered() { return ordered; }
    public void ordered(boolean on) { ordered = on; }
    public boolean holding() { return holding; }
    public void holding(boolean on) { holding = on; }
    public double px() { return x; }
    public double py() { return y; }
    public double pz() { return z; }
    public boolean paused() { return false; }   // no reflexes: the player is his own

    static boolean commander() {
        Commander c = Commander.instance;
        return c != null && c.on;
    }

    /** it may take the board's cards now (its own orders it takes always) */
    static boolean eligible() {
        return commander() || givenToAI;
    }

    /** only what the autopilot does, with what the player has at hand */
    public boolean can(TaskBoard.Card k) {
        TaskBoard.Play p = k.play;
        EntityPlayerMP pl = player;
        if (p == null || pl == null) return false;
        boolean mine = k.only == this && k.origin == Origin.PLAYER;
        if (!mine && !eligible()) return false;
        WorldServer w = (WorldServer) pl.worldObj;
        switch (p.kind) {
            case MOVE: case CHOP: case ATTACK: return true;
            case CHOP_MORE: return crew != null && crew.base != null;
            case MINE: case DIG: return harvests(pl, w, p.x, p.y, p.z);
            case DEPOSIT: return crew != null && Roles.is(w, crew.chest, Roles.Role.CHEST);
            case PLACE: return slotOf(pl, Compiler.item(p.item)) >= 0;
            default: return false;
        }
    }

    /** a tool of the hotbar that gives the block when it is dug (or a block that needs none) */
    static boolean harvests(EntityPlayerMP p, WorldServer w, int x, int y, int z) {
        Block b = w.getBlock(x, y, z);
        if (b.getMaterial().isToolNotRequired()) return true;
        int meta = w.getBlockMetadata(x, y, z);
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.inventory.mainInventory[i];
            if (s != null && (s.canItemHarvestBlock(b) || net.minecraftforge.common.ForgeHooks.canToolHarvestBlock(b, meta, s))) return true;
        }
        return false;
    }

    /** the hotbar slot with this item, or -1 */
    static int slotOf(EntityPlayerMP p, Item it) {
        if (it == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.inventory.mainInventory[i];
            if (s != null && s.getItem() == it && s.stackSize > 0) return i;
        }
        return -1;
    }

    public void begin(TaskBoard b, TaskBoard.Card k) {
        card = k;
        work = new Work(k.play);
        Pilot pi = Pilot.instance;
        if (pi != null) { pi.crewSignal = null; pi.crewDriving = true; }
    }

    /** the card's work stops: the autopilot lets the character go */
    public void cancel(String why) {
        boolean was = card != null || work != null;
        card = null;
        work = null;
        Pilot pi = Pilot.instance;
        if (pi != null && was) {
            JsonObject m = Pilot.msg("stop");
            m.addProperty("by", "crew");
            pi.inbox.add(m);
            pi.crewDriving = false;
        }
    }

    /**
     * The controls back to the player: the card goes back on the board as it was (his own orders go: he does it himself
     * now). A movement key, a fall or lava, the guide leading him: he is not given to the AI any more either.
     */
    void takeBack(Crew c, String why) {
        TaskBoard.Card k = card;
        cancel(why);
        boolean player = why.equals("taken") || why.equals("danger") || why.equals("guide"), was = givenToAI;
        if (player) givenToAI = false;
        if (k == null && !(player && was) && !why.equals("left the commander")) return;   // nothing it had to give back
        if (k != null) {
            if (k.origin == Origin.PLAYER && k.only == this) {
                c.board.release(this, k);
                Orders.cancel(c, this);
            } else c.board.giveBack(this, why);
            Crew.note(why.equals("danger") ? "self.danger" : "self.back");
        } else if (player && was) Crew.note("self.back");
        if (why.equals("left the commander")) Orders.cancel(c, this);   // the commander's orders for him were for while he watched
        Events.log("crew: " + who() + " has the controls back (" + why + ")" + (k == null ? "" : ", " + k + " given back"));
    }

    /** every tick of the crew: where the player is, what took the controls back, the card's work */
    void work(Crew c, WorldServer w, EntityPlayerMP p) {
        crew = c;
        player = p;
        alive = p != null;
        if (p == null) {
            if (card != null) { c.board.giveBack(this, "not in the world"); cancel("gone"); }
            return;
        }
        nick = p.getCommandSenderName();
        x = p.posX;
        y = p.boundingBox.minY;
        z = p.posZ;
        Pilot pi = Pilot.instance;
        String sig = pi == null ? null : pi.crewSignal;
        if (sig != null) {
            pi.crewSignal = null;
            takeBack(c, sig);
        }
        boolean cmd = commander();
        if (wasCommander && !cmd && !givenToAI) takeBack(c, "left the commander");
        wasCommander = cmd;
        TaskBoard.Card k = card;
        if (k != null && (k.state != TaskBoard.State.LEASED || k.holder != this)) { cancel("taken back"); k = null; }   // the board took it back
        if (k == null) {
            if (++clock % 10 == 0) c.board.feedDoer(this, w);
            return;
        }
        Result r;
        try {
            r = work.step(this, c, w, p);
        } catch (Exception ex) {
            Events.log("crew: " + who() + " " + k + " failed: " + ex);
            r = Result.BLOCKED;
        }
        if (work != null && work.interrupted != null) { takeBack(c, work.interrupted); return; }
        if (r.done()) {
            cancel("done");
            c.board.finish(k, this, r);
            Ledger.result(r);
            if (c.current != null && k.phase == c.current) c.current.tally(r);
        }
    }

    /** the logs the player carries */
    static int logs(EntityPlayerMP p) {
        int n = 0;
        for (ItemStack s : p.inventory.mainInventory) if (s != null && (s.getItem() == Day1.LOG || s.getItem() == Day1.LOG2)) n += s.stackSize;
        return n;
    }

    // ---- the work: orders to the autopilot, one at a time --------------------------------------------------

    /** one order to the autopilot, and how it ended */
    static final class Cmd {
        final JsonObject m;
        int limit, id = -1, waited;
        Cmd(JsonObject m, int limit) { this.m = m; this.limit = limit; }
        /** given up sooner (a walk after something that moves: a new way soon) */
        Cmd waitedLimit(int ticks) { limit = ticks; return this; }
        /** null while it runs, else how it ended (ok, failed:why, taken, danger, stopped) */
        String poll() {
            Pilot pi = Pilot.instance;
            if (pi == null) return "failed:no autopilot";
            if (id < 0) { id = pi.order(m); return null; }
            if (pi.endedId == id) return pi.endedHow;
            if (++waited > limit) return "failed:timeout";
            return null;
        }
    }

    static JsonObject at(String t, int x, int y, int z) {
        JsonObject m = Pilot.msg(t);
        m.addProperty("x", x);
        m.addProperty("y", y);
        m.addProperty("z", z);
        return m;
    }

    static Cmd go(int x, int y, int z, double reach) {
        JsonObject m = at("goto", x, y, z);
        m.addProperty("reach", reach);
        return new Cmd(m, 20 * 120);
    }

    static Cmd dig(int x, int y, int z) { return new Cmd(at("dig", x, y, z), 20 * 40); }

    static Cmd look(int x, int y, int z) { return new Cmd(at("look", x, y, z), 20 * 5); }

    static Cmd place(int x, int y, int z, int slot) {
        JsonObject m = at("place", x, y, z);
        m.addProperty("slot", slot);
        return new Cmd(m, 20 * 5);
    }

    static Cmd hit(int entity) {
        JsonObject m = Pilot.msg("hit");
        m.addProperty("e", entity);
        return new Cmd(m, 20 * 5);
    }

    /** what the autopilot's end says, in the crew's results */
    static Result result(String how) {
        if (how.equals("ok")) return Result.OK;
        if (how.contains("way") || how.contains("stuck")) return Result.NO_PATH;
        if (how.contains("sight")) return Result.NOT_VISIBLE;
        if (how.contains("cannot be dug")) return Result.NO_TOOL;
        if (how.contains("reach")) return Result.OUT_OF_REACH;
        if (how.contains("timeout") || how.contains("could not")) return Result.TIMEOUT;
        return Result.BLOCKED;
    }

    /** an end that is not the work's own: the player (or the guide) has the character now */
    static boolean interrupting(String how) {
        return how.equals("taken") || how.equals("danger") || how.equals("stopped");
    }

    static double eyeDist(EntityPlayerMP p, int x, int y, int z) {
        double dx = p.posX - (x + 0.5), dy = p.boundingBox.minY + 1.62 - (y + 0.5), dz = p.posZ - (z + 0.5);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    static int[] feet(EntityPlayerMP p) {
        return new int[] { MathHelper.floor_double(p.posX), MathHelper.floor_double(p.boundingBox.minY + 0.01), MathHelper.floor_double(p.posZ) };
    }

    /** a standing cell next to a block (off it, beside it), or null */
    static int[] beside(WorldServer w, int x, int y, int z) {
        for (int[] d : new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 }, { 1, 1 }, { -1, -1 }, { 1, -1 }, { -1, 1 } })
            for (int dy = 1; dy >= -1; dy--)
                if (Crew.stand(w, x + d[0], y + dy, z + d[1]) && !Crew.hot(w, x + d[0], y + dy, z + d[1])) return new int[] { x + d[0], y + dy, z + d[1] };
        return null;
    }

    /** the card's work as a few orders to the autopilot, by the kind of the card */
    static final class Work {
        final TaskBoard.Play p;
        String interrupted;
        Cmd cur;
        int stage, ticks;
        Digs digs;
        Collect collect;
        Result after;
        Work chop;          // CHOP_MORE: the tree on now
        int[] tree;
        int trees, placedAt;
        Work(TaskBoard.Play p) { this.p = p; }

        /** the order on now: null while it runs, else how it ended (an interrupting end is kept for the doer) */
        String poll() {
            String how = cur.poll();
            if (how == null) return null;
            cur = null;
            if (interrupting(how)) interrupted = how;
            return how;
        }

        Result step(PlayerAgent me, Crew c, WorldServer w, EntityPlayerMP pl) {
            ticks++;
            switch (p.kind) {
                case MOVE: {
                    if (cur == null) cur = go(p.x, p.y, p.z, 2.2);   // onto the block clicked (or right by it)
                    String how = poll();
                    return how == null ? Result.RUNNING : result(how) == Result.OK ? Result.OK : Result.NO_PATH;
                }
                case CHOP: case MINE: case DIG: {
                    if (digs == null) {
                        List<int[]> blocks;
                        if (p.kind == TaskBoard.Play.Kind.CHOP) {
                            blocks = Fell.tree(w, p.x, p.y, p.z);
                            if (blocks.isEmpty()) return Result.GONE;
                        } else if (p.kind == TaskBoard.Play.Kind.MINE) {
                            if (!harvests(pl, w, p.x, p.y, p.z)) return Result.NO_TOOL;
                            blocks = Day2.cluster(w, p.x, p.y, p.z, w.getBlock(p.x, p.y, p.z), new HashSet<Long>());
                            if (blocks.isEmpty()) blocks.add(new int[] { p.x, p.y, p.z });
                        } else {
                            if (w.isAirBlock(p.x, p.y, p.z)) return Result.OK;
                            if (!harvests(pl, w, p.x, p.y, p.z)) return Result.NO_TOOL;
                            blocks = new ArrayList<int[]>();
                            blocks.add(new int[] { p.x, p.y, p.z });
                        }
                        digs = new Digs(blocks, p.kind == TaskBoard.Play.Kind.DIG ? 1 : 0);
                    }
                    if (after == null) {
                        Result r = digs.step(this, w, pl);
                        if (r == Result.RUNNING) return r;
                        after = r;
                        collect = new Collect(p.x, p.y, p.z, p.kind == TaskBoard.Play.Kind.CHOP ? 6 : 4);
                    }
                    Result r = collect.step(this, w, pl);   // what fell is picked up (walked over)
                    return r == Result.RUNNING ? r : after;
                }
                case DEPOSIT: {
                    int[] ch = c.chest;
                    if (!Roles.is(w, ch, Roles.Role.CHEST)) return Result.GONE;
                    if (stage == 0) { cur = go(ch[0], ch[1], ch[2], 3.0); stage = 1; }
                    if (stage == 1) {
                        String how = poll();
                        if (how == null) return Result.RUNNING;
                        if (result(how) != Result.OK && eyeDist(pl, ch[0], ch[1], ch[2]) > 4.5) return Result.NO_PATH;
                        cur = look(ch[0], ch[1], ch[2]);
                        stage = 2;
                    }
                    String how = poll();
                    if (how == null) return Result.RUNNING;
                    if (eyeDist(pl, ch[0], ch[1], ch[2]) > 4.5) return Result.OUT_OF_REACH;
                    return stash(pl, w, ch) ? Result.OK : Result.BLOCKED;
                }
                case ATTACK: {
                    Entity e = w.getEntityByID(p.id);
                    if (e == null || e.isDead) return Result.OK;
                    if (ticks > 20 * 90) return Result.TIMEOUT;
                    if (cur == null) {
                        if (pl.getDistanceToEntity(e) > 3.0) {   // after it (a new way every few seconds: it moves)
                            cur = go(MathHelper.floor_double(e.posX), MathHelper.floor_double(e.boundingBox.minY), MathHelper.floor_double(e.posZ), 2.0);
                            cur.waitedLimit(60);
                        } else if (ticks - stage >= 12) { cur = hit(e.getEntityId()); stage = ticks; }   // a blow about every half second
                        else return Result.RUNNING;
                    }
                    poll();
                    return Result.RUNNING;
                }
                case PLACE: {
                    Item it = Compiler.item(p.item);
                    if (!Crew.soft(w, p.x, p.y, p.z) && !w.getBlock(p.x, p.y, p.z).getMaterial().isReplaceable())
                        return stage >= 3 || it != null && Item.getItemFromBlock(w.getBlock(p.x, p.y, p.z)) == it ? Result.OK : Result.GONE;
                    if (stage == 4) return ticks - placedAt > 10 ? Result.BLOCKED : Result.RUNNING;   // the server has not put it: nothing to put it against
                    if (cur != null) {
                        String how = poll();
                        if (how == null) return Result.RUNNING;
                        if (stage == 3) {
                            if (result(how) != Result.OK) return result(how);
                            stage = 4;   // clicked: the block comes from the server in a tick or two
                            placedAt = ticks;
                            return Result.RUNNING;
                        }
                        if (stage == 1 && result(how) != Result.OK && eyeDist(pl, p.x, p.y, p.z) > 4.5) return Result.NO_PATH;
                    }
                    int slot = slotOf(pl, it);
                    if (slot < 0) return Result.NO_ITEMS;
                    int[] f = feet(pl);
                    if (f[0] == p.x && f[2] == p.z && (f[1] == p.y || f[1] + 1 == p.y)) {   // it stands where the block goes: aside first
                        int[] off = beside(w, p.x, p.y, p.z);
                        if (off == null || stage == 2) return Result.BUSY;
                        cur = go(off[0], off[1] - 1, off[2], 2.15);
                        stage = 2;
                        return Result.RUNNING;
                    }
                    if (eyeDist(pl, p.x, p.y, p.z) > 4.2 && stage < 1) { cur = go(p.x, p.y, p.z, 3.6); stage = 1; return Result.RUNNING; }
                    cur = place(p.x, p.y, p.z, slot);
                    stage = 3;
                    return Result.RUNNING;
                }
                case CHOP_MORE: {   // trees near the base until the crew has the logs (four at most a card), then the logs to the chest
                    if (chop != null) {
                        Result r = chop.step(me, c, w, pl);
                        if (chop.interrupted != null) { interrupted = chop.interrupted; return Result.RUNNING; }
                        if (r == Result.RUNNING) return r;
                        chop = null;
                        if (stage == 8) return Result.OK;   // the logs went to the chest (or as many as it took)
                        c.board.unreserve(tree[0], tree[1], tree[2]);
                        if (r != Result.OK) c.taken.add(Crew.key(tree[0], tree[1], tree[2]));   // could not get at it: not this one again
                        else trees++;
                    }
                    int have = Day1.crewLogs(c, w) + logs(pl);
                    if (have < p.n && trees < 4) {
                        int[] best = null;
                        double bd = 1e9;
                        for (int[] t : c.trees(w)) {
                            if (c.taken.contains(Crew.key(t[0], t[1], t[2])) || c.board.reserved(me, t[0], t[1], t[2])) continue;
                            double d = Math.hypot(t[0] - pl.posX, t[2] - pl.posZ) + Math.hypot(t[0] - c.base[0], t[2] - c.base[2]) * 0.5;
                            if (d < bd) { bd = d; best = t; }
                        }
                        if (best != null) {
                            c.board.reserve(me, best[0], best[1], best[2]);
                            tree = best;
                            chop = new Work(TaskBoard.Play.at(TaskBoard.Play.Kind.CHOP, best[0], best[1], best[2]));
                            return Result.RUNNING;
                        }
                    }
                    if (trees == 0) return Result.GONE;   // no tree it could get at
                    if (!Roles.is(w, c.chest, Roles.Role.CHEST)) return Result.OK;   // no chest yet: the logs stay with the player
                    chop = new Work(TaskBoard.Play.at(TaskBoard.Play.Kind.DEPOSIT, 0, 0, 0));
                    stage = 8;
                    return Result.RUNNING;
                }
                default:
                    return Result.BLOCKED;
            }
        }
    }

    /** blocks dug one after another as the player digs: near enough first, off a block it stands on, each with the autopilot's dig */
    static final class Digs {
        final List<int[]> blocks;
        final int need;
        int i, dug, missed, stage, offs;
        boolean near;
        Digs(List<int[]> blocks, int need) {
            this.blocks = new ArrayList<int[]>(blocks);
            this.need = need;
            Collections.sort(this.blocks, new Comparator<int[]>() {   // the lowest first (a tree from its foot), as a player chops
                @Override public int compare(int[] a, int[] b) { return a[1] - b[1]; }
            });
        }
        Result step(Work wk, WorldServer w, EntityPlayerMP p) {
            if (wk.cur != null) {
                String how = wk.poll();
                if (how == null) return Result.RUNNING;
                if (wk.interrupted != null) return Result.RUNNING;
                if (stage == 2) { if (result(how) == Result.OK) dug++; else missed++; i++; near = false; offs = 0; }
                else if (stage == 1) near = true;   // there (or as near as it got): the dig says the rest
                stage = 0;
                return Result.RUNNING;
            }
            while (i < blocks.size() && (w.isAirBlock(blocks.get(i)[0], blocks.get(i)[1], blocks.get(i)[2]) || w.getBlock(blocks.get(i)[0], blocks.get(i)[1], blocks.get(i)[2]).getMaterial().isLiquid())) i++;
            if (i >= blocks.size()) return dug > 0 || missed == 0 ? Result.OK : Result.BLOCKED;
            int[] b = blocks.get(i);
            int[] f = feet(p);
            if (f[0] == b[0] && f[2] == b[2] && f[1] == b[1] + 1) {   // it stands on it: a player who digs it falls; off it first
                int[] off = beside(w, b[0], b[1], b[2]);
                if (off == null || offs++ >= 2) { missed++; i++; offs = 0; return Result.RUNNING; }
                wk.cur = go(off[0], off[1] - 1, off[2], 2.15);
                stage = 3;
                return Result.RUNNING;
            }
            if (!near && eyeDist(p, b[0], b[1], b[2]) > 4.2) { wk.cur = go(b[0], b[1], b[2], 3.6); stage = 1; return Result.RUNNING; }
            if (eyeDist(p, b[0], b[1], b[2]) > 5.5) { missed++; i++; near = false; return Result.RUNNING; }   // out of a player's reach from anywhere it got to
            wk.cur = dig(b[0], b[1], b[2]);
            stage = 2;
            return Result.RUNNING;
        }
    }

    /** what lies around a point picked up: walked over, one item after another (half a minute at most) */
    static final class Collect {
        final double x, y, z, r;
        final java.util.Set<Integer> tried = new HashSet<Integer>();
        int ticks;
        Collect(double x, double y, double z, double r) { this.x = x + 0.5; this.y = y; this.z = z + 0.5; this.r = r; }
        Result step(Work wk, WorldServer w, EntityPlayerMP p) {
            ticks++;
            if (ticks < 14) return Result.RUNNING;   // the drops fall first
            if (wk.cur != null) { wk.poll(); return Result.RUNNING; }
            if (ticks > 20 * 30) return Result.OK;
            EntityItem best = null;
            double bd = 1e9;
            for (Object o : w.getEntitiesWithinAABB(EntityItem.class, AxisAlignedBB.getBoundingBox(x - r, y - 3, z - r, x + r, y + 8, z + r))) {
                EntityItem it = (EntityItem) o;
                if (it.isDead || !it.onGround || tried.contains(it.getEntityId())) continue;
                double d = p.getDistanceToEntity(it);
                if (d < bd) { bd = d; best = it; }
            }
            if (best == null) return Result.OK;
            tried.add(best.getEntityId());
            if (bd < 1.2) return Result.RUNNING;   // picked up by itself in a moment
            wk.cur = go(MathHelper.floor_double(best.posX), MathHelper.floor_double(best.posY), MathHelper.floor_double(best.posZ), 1.5);
            wk.cur.waitedLimit(20 * 10);
            return Result.RUNNING;
        }
    }

    /**
     * What the player carries into the crew's chest, as a player puts it in by hand (the chest opens, closes): the
     * materials and blocks; tools, weapons, armour, food, torches and anything that does not stack stay with him.
     */
    static boolean stash(EntityPlayerMP p, WorldServer w, int[] ch) {
        net.minecraft.tileentity.TileEntity te = w.getTileEntity(ch[0], ch[1], ch[2]);
        if (!(te instanceof IInventory)) return false;
        IInventory inv = (IInventory) te;
        Block block = w.getBlock(ch[0], ch[1], ch[2]);
        w.addBlockEvent(ch[0], ch[1], ch[2], block, 1, 1);
        w.playSoundEffect(ch[0] + 0.5, ch[1] + 0.5, ch[2] + 0.5, "random.chestopen", 0.5F, 1.0F);
        p.swingItem();
        int moved = 0;
        for (int slot = 0; slot < p.inventory.mainInventory.length; slot++) {
            ItemStack s = p.inventory.mainInventory[slot];
            if (s == null || !s.isStackable() || s.getItem() instanceof net.minecraft.item.ItemFood || s.getItem() == Day1.TORCH) continue;
            ItemStack before = s.copy();
            for (int i = 0; i < inv.getSizeInventory() && s.stackSize > 0; i++) {
                ItemStack c = inv.getStackInSlot(i);
                if (c == null) { inv.setInventorySlotContents(i, s.copy()); s.stackSize = 0; }
                else if (c.getItem() == s.getItem() && c.getMetadata() == s.getMetadata() && ItemStack.areItemStackTagsEqual(c, s) && c.stackSize < c.getMaxStackSize()) {
                    int m = Math.min(s.stackSize, c.getMaxStackSize() - c.stackSize);
                    c.stackSize += m;
                    s.stackSize -= m;
                }
            }
            before.stackSize -= s.stackSize;
            if (before.stackSize > 0) { Ledger.flow(Ledger.Way.CHEST_IN, before); moved += before.stackSize; }
            if (s.stackSize <= 0) p.inventory.mainInventory[slot] = null;
        }
        inv.markDirty();
        p.inventory.markDirty();
        p.inventoryContainer.detectAndSendChanges();
        w.addBlockEvent(ch[0], ch[1], ch[2], block, 1, 0);
        w.playSoundEffect(ch[0] + 0.5, ch[1] + 0.5, ch[2] + 0.5, "random.chestclosed", 0.5F, 1.0F);
        Events.log("crew: the player put " + moved + " things into the chest");
        return true;
    }
}
