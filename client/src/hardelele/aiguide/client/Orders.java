package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.init.Blocks;
import net.minecraft.world.WorldServer;

/**
 * The player's orders to the crew (the commander's right click, the guide's "take that hill"): each becomes a card on
 * the crew's one board, pinned to the agent it is for, of the player's origin and above everything else; the agent
 * is taken off what it did at once (that card goes back to the board), or, queued (Shift), does it after its orders
 * before. An agent given an order is the player's unit (commanded): it takes the player's orders only, until given
 * back to the AI. The skills are the crew's own (walk, chop, mine, fight, stash): nothing is done another way.
 */
class Orders {
    enum Kind { MOVE, CHOP, MINE, ATTACK, DEPOSIT, GUARD, CRAFT, SMELT, PLACE, STOP, RELEASE }

    static final double PRIO = 20;
    /** each agent's (by its number) last order card: a queued order goes after it */
    static final Map<Integer, TaskBoard.Card> last = new HashMap<Integer, TaskBoard.Card>();

    /** from any thread: the order goes to the crew's tick */
    static void give(final int[] agents, final Kind kind, final int x, final int y, final int z, final int entityId, final boolean queue) {
        give(agents, kind, x, y, z, entityId, queue, null, 0);
    }

    /** with a thing and how many (craft it, smelt it, put it there) */
    static void give(final int[] agents, final Kind kind, final int x, final int y, final int z, final int entityId, final boolean queue, final String item, final int n) {
        final Crew c = Crew.ensure();
        c.queue.add(new Runnable() {
            public void run() {
                net.minecraft.server.MinecraftServer s = net.minecraft.server.MinecraftServer.getServer();
                if (s == null) return;
                WorldServer w = s.worldServers[0];
                for (int n : agents) {
                    Agent a = null;
                    for (Agent g : c.agents) if (g.skin() == n && !g.isDead) a = g;
                    if (a == null) continue;
                    try {
                        apply(c, w, a, kind, x, y, z, entityId, queue, item, n);
                    } catch (Exception ex) {
                        Events.log("order failed: " + ex);
                    }
                }
            }
        });
    }

    static void apply(Crew c, WorldServer w, final Agent a, Kind kind, int x, int y, int z, int entityId, boolean queue, String item, int n) {
        if (kind == Kind.RELEASE || kind == Kind.STOP) {   // its orders go; stop: it stands (still the player's), release: back to the AI
            cancel(c, a);
            Crew.clearJobs(a);
            a.getNavigator().clearPathEntity();
            TaskBoard.Card held = c.board.leased.get(a);
            if (held != null) { c.board.release(a, held); held.state = TaskBoard.State.OPEN; }
            a.commanded = kind == Kind.STOP;
            Events.log("order: " + a.getCustomNameTag() + (kind == Kind.STOP ? " stops (holds)" : " is back with the AI"));
            return;
        }
        if (!queue) cancel(c, a);
        final Crew.Task t = task(c, w, a, kind, x, y, z, entityId, item, n);
        if (t == null) return;
        Origin was = Crew.originNow;
        Crew.originNow = Origin.PLAYER;
        TaskBoard.Card k = c.board.post("order " + kind.name().toLowerCase(), () -> t).pin(a).prio(PRIO).lease(20 * 600);
        Crew.originNow = was;
        k.phase = null;   // the player's, not a phase's
        TaskBoard.Card before = last.get(a.skin());
        if (queue && before != null && before.open()) k.after(before);
        last.put(a.skin(), k);
        a.commanded = true;
        Events.log("order: " + a.getCustomNameTag() + " " + kind + " " + x + " " + y + " " + z + (entityId >= 0 ? " #" + entityId : "") + (queue ? " (queued)" : ""));
        if (!queue || before == null || !before.open()) c.board.assign(k, a, w, "has an order");
    }

    /** the agent's open player orders go (given up: the player changed them) */
    static void cancel(Crew c, Agent a) {
        for (TaskBoard.Card k : c.board.cards)
            if (k.origin == Origin.PLAYER && k.only != null && TaskBoard.same(k.only, a) && k.open()) {
                if (k.holder != null) c.board.release(k.holder, k);
                k.state = TaskBoard.State.FAILED;
                k.last = Result.GONE;
            }
        last.remove(a.skin());
    }

    /** what the order is in the crew's skills */
    static Crew.Task task(Crew c, WorldServer w, Agent a, Kind kind, int x, int y, int z, int entityId, String item, int n) {
        switch (kind) {
            case MOVE: {
                int gy = Crew.ground(w, x, y + 1, z);
                return new Crew.GoTo(x + 0.5, gy < 0 ? y + 1 : gy, z + 0.5, 0.8);
            }
            case CHOP: {   // the trunk's lowest log, the tree down, what fell picked up
                int by = y;
                while (by > 0 && isLog(w.getBlock(x, by - 1, z))) by--;
                return new TaskBoard.Seq(new Crew.Chop(x, by, z), new Crew.Collect(x + 0.5, by, z + 0.5, 5)).lenient();
            }
            case MINE: {   // the clicked block's vein (one kind of block, touching), or the block alone
                Block b = w.getBlock(x, y, z);
                List<int[]> blocks = Day2.cluster(w, x, y, z, b, new HashSet<Long>());
                if (blocks.isEmpty()) blocks = new ArrayList<int[]>(java.util.Arrays.asList(new int[] { x, y, z }));
                return new TaskBoard.Seq(new Crew.GoTo(x + 0.5, y, z + 0.5, Crew.REACH - 0.6), new Crew.Mine(blocks), new Crew.Collect(x + 0.5, y, z + 0.5, 5));
            }
            case ATTACK: {
                Entity e = w.getEntityByID(entityId);
                if (!(e instanceof EntityLiving)) return null;
                return new Guard.Fight((EntityLiving) e);
            }
            case DEPOSIT:
                return c.new Stash(null, 0);
            case GUARD:   // stand at that spot and fight off what comes, until the order is taken back
                return new Guard.Fight(new double[] { x + 0.5, y + 1, z + 0.5 }, true);
            case CRAFT: {   // the parts from the chest (what is not in the bag), then made (at the table when the recipe is 3x3)
                net.minecraft.item.Item it = Compiler.item(item);
                if (it == null) return null;
                final net.minecraft.item.Item made = it;
                final int many = n;
                return new Crew.Task() {   // worked out when the card starts (what the bag and the chest have then)
                    Crew.Task t;
                    public Result step(Agent ag, WorldServer ww) {
                        if (t == null) {
                            List<Crew.Task> s = new ArrayList<Crew.Task>();
                            makePlan(c, ww, ag, made, many, 0, s, new java.util.HashMap<net.minecraft.item.Item, Integer>());
                            t = new TaskBoard.Seq(s.toArray(new Crew.Task[0]));
                        }
                        return t.step(ag, ww);
                    }
                };
            }
            case SMELT: {   // n of it (ore) from the chest into the crew's furnace with the best fuel there, the result to the chest
                net.minecraft.item.Item in = Compiler.item(item);
                if (in == null || Day1.furnace == null) return null;
                net.minecraft.item.Item fuel = Day2.fuel(c, w, n);
                return new TaskBoard.Seq(new Day1.Take(c, in, n), new Day1.Take(c, fuel, Day2.fuelFor(fuel, n)), new Skills.Smelt(in, -1, n, fuel), c.new Stash(null, 0));
            }
            case PLACE: {   // one block of a blueprint: from the bag, else from the chest, else made (planks from logs); worked out when the card starts
                final net.minecraft.item.Item it = Compiler.item(item);
                if (it == null) return null;
                final int px = x, py = y, pz = z;
                return new Crew.Task() {
                    Crew.Task t;
                    public Result step(Agent ag, WorldServer ww) {
                        if (t == null) {
                            List<Crew.Task> s = new ArrayList<Crew.Task>();
                            makePlan(c, ww, ag, it, 1, 0, s, new java.util.HashMap<net.minecraft.item.Item, Integer>());
                            s.add(new Skills.PlaceFrom(px, py, pz, it, -1));
                            t = new TaskBoard.Seq(s.toArray(new Crew.Task[0]));
                        }
                        return t.step(ag, ww);
                    }
                };
            }
            default:
                return null;
        }
    }

    /**
     * n of an item for this agent: what the bag has is used, what the chest has is taken, the rest is made from its parts
     * by the game's recipe (planks from logs, sticks from planks...), each part the same way, a few levels down.
     */
    static void makePlan(Crew c, WorldServer w, Agent a, net.minecraft.item.Item it, int n, int depth, List<Crew.Task> s, Map<net.minecraft.item.Item, Integer> have) {
        if (!have.containsKey(it)) have.put(it, Skills.count(a.bag, it, -1) * 1000 + TaskBoard.chestCount(c.chestInv(w), it));   // bag*1000 + chest
        int v = have.get(it), bag = v / 1000, chest = v % 1000;
        int fromBag = Math.min(bag, n), fromChest = Math.min(chest, n - fromBag), rest = n - fromBag - fromChest;
        have.put(it, (bag - fromBag) * 1000 + chest - fromChest);
        if (fromChest > 0) s.add(new Day1.Take(c, it, fromChest));
        if (rest <= 0) return;
        List<Skills.Laid> rs = depth > 3 ? new ArrayList<Skills.Laid>() : Skills.recipesFor(it, -1);
        if (rs.isEmpty()) { s.add(new Day1.Take(c, it, rest)); return; }   // nothing makes it: the chest's (fails honestly when it is not there)
        Skills.Laid l = rs.get(0);
        for (Skills.Laid x : rs) if (Compiler.simple(x)) { l = x; break; }
        int crafts = (rest + l.out.stackSize - 1) / l.out.stackSize;
        Map<net.minecraft.item.Item, Integer> parts = new java.util.LinkedHashMap<net.minecraft.item.Item, Integer>();
        for (List<net.minecraft.item.ItemStack> cell : l.cells) if (cell != null) Compiler.add(parts, cell.get(0).getItem(), crafts);
        for (Map.Entry<net.minecraft.item.Item, Integer> e : parts.entrySet()) makePlan(c, w, a, e.getKey(), e.getValue(), depth + 1, s, have);
        s.add(new Skills.Craft(it, -1, crafts * l.out.stackSize));
    }

    /** the guide's crew_order tool: the same orders as the commander's clicks (the agents by number, a place) */
    static String fromGuide(String agents, String order, int x, int y, int z) {
        Crew c = Crew.ensure();
        List<Integer> who = new ArrayList<Integer>();
        if (agents == null || agents.trim().isEmpty() || agents.trim().equalsIgnoreCase("all")) { for (Agent a : new ArrayList<Agent>(c.agents)) who.add(a.skin()); }
        else for (String p : agents.split("[,\\s]+")) if (p.matches("\\d+")) who.add(Integer.parseInt(p));
        if (who.isEmpty()) return "No such agents (the crew: " + c.agents.size() + ").";
        int[] ws = new int[who.size()];
        for (int i = 0; i < ws.length; i++) ws[i] = who.get(i);
        String o = order == null ? "" : order.trim().toLowerCase();
        Kind k = o.startsWith("move") || o.startsWith("go") ? Kind.MOVE : o.startsWith("chop") ? Kind.CHOP : o.startsWith("mine") ? Kind.MINE : o.startsWith("attack") ? Kind.ATTACK
                : o.startsWith("guard") ? Kind.GUARD : o.startsWith("stop") ? Kind.STOP : o.startsWith("release") ? Kind.RELEASE : null;
        if (k == null) return "Unknown order '" + order + "': move, chop, mine, attack, guard, stop, release.";
        int id = -1;
        if (k == Kind.ATTACK) {   // the nearest hostile mob to the place
            WorldServer w = net.minecraft.server.MinecraftServer.getServer().worldServers[0];
            double bd = 24;
            for (Object e : w.loadedEntityList)
                if (Guard.hostile((Entity) e)) {
                    double d = ((Entity) e).getDistance(x + 0.5, y, z + 0.5);
                    if (d < bd) { bd = d; id = ((Entity) e).getEntityId(); }
                }
            if (id < 0) return "No hostile mob within 24 blocks of " + x + " " + y + " " + z + ".";
        }
        give(ws, k, x, y, z, id, false);
        return "Ordered: " + who + " " + k.name().toLowerCase() + (k == Kind.STOP || k == Kind.RELEASE ? "" : " at " + x + " " + y + " " + z) + ".";
    }

    static boolean isLog(Block b) {
        return b == Blocks.log || b == Blocks.log2 || b.getMaterial() == Material.wood && String.valueOf(Block.blockRegistry.getNameForObject(b)).contains("log");
    }

    /** what a right click on this block means: a tree is chopped, ore mined, the crew's chest gets the bag, else go there */
    static Kind meaning(net.minecraft.world.World w, int[] chest, int x, int y, int z) {
        Block b = w.getBlock(x, y, z);
        if (isLog(b)) return Kind.CHOP;
        if (chest != null && chest[0] == x && chest[1] == y && chest[2] == z) return Kind.DEPOSIT;
        String n = Block.blockRegistry.getNameForObject(b);
        if (n != null && n.contains("_ore")) return Kind.MINE;
        return Kind.MOVE;
    }
}
