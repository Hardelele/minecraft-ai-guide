package hardelele.aiguide.client;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemPickaxe;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;

/**
 * The crew's memory of cases (layer 2): what the reflexes met, what they did and whether it helped, kept with the world
 * (crew-cases.jsonl) and asked again when a reflex meets a situation like one it has met. All of it is cheap enough
 * for the tick: the situation is read from what the body already sensed (no scans of the world), the vector and the
 * search are plain arithmetic in memory, the journal is written and read on threads of their own. No network here:
 * the words of a case are embedded offline (tools/cases.py).
 *
 * For a reflex:
 *   CaseBook.Advice ad = Cases.suggest(a, "stuck-jump", "jump", "side step", "pillar");   // known() or not: its default
 *   Cases.start(a, "stuck-jump", 50, ad.known() ? ad.reaction : "jump");                  // the tick it reacts
 *   Cases.act(a, "place cobblestone 10 62 4");                                             // what the body did (optional)
 *   Cases.finish(a, result, null);                                                         // the reaction ended: one line
 */
public final class Cases {
    private Cases() { }

    private static CaseBook book;
    private static File bookDir;
    private static final Map<Agent, Open> open = new WeakHashMap<Agent, Open>();

    /** a case begun and not ended yet */
    static final class Open {
        final CaseBook.Case c = new CaseBook.Case();
        long at;
    }

    /** this world's memory (read back from its journal the first time; another world, another memory) */
    /** the epochs' learning run keeps its memory of cases out of the world (the world is put back as it was before each epoch): Epochs sets it */
    static volatile File dirOverride;

    static synchronized CaseBook book() {
        File dir = dirOverride != null ? dirOverride : worldDir();
        if (book != null && (dir == null ? bookDir == null : dir.equals(bookDir))) return book;
        if (book != null) book.close();
        bookDir = dir;
        book = new CaseBook(dir == null ? null : new File(dir, CaseBook.NAME));
        final CaseBook b = book;
        if (dir == null) b.load();
        else b.loadLater(new Runnable() {
            public void run() { Events.log("cases: " + b.size() + " cases read" + (b.badLines > 0 ? " (" + b.badLines + " bad lines skipped)" : "")); }
        });
        return book;
    }

    static File worldDir() {
        MinecraftServer s = MinecraftServer.getServer();
        if (s == null || s.worldServers == null || s.worldServers.length == 0 || s.worldServers[0] == null) return null;
        return s.worldServers[0].getSaveHandler().getWorldDirectory();
    }

    // ---- the situation, from what the body sensed -----------------------------------------------------

    /** the agent's situation now: its senses of this tick, its task and the task's target, its card, its bag */
    static CaseVec.Sit sense(Agent a) {
        Body.Senses s = a.body.senses;
        CaseVec.Sit x = new CaseVec.Sit();
        x.health = s.health;
        x.max = s.maxHealth;
        x.hurt = s.hurt;
        x.hurtBy = s.hurtBy == null ? "" : s.hurtBy;
        x.fall = s.fall;
        x.motionY = s.motionY;
        x.onGround = s.onGround;
        x.falling = s.falling;
        x.inWater = s.inWater;
        x.inLava = s.inLava;
        x.burning = s.burning;
        x.underWater = s.underWater;
        x.collided = s.collided;
        x.feet = s.feet == null ? null : s.feet.clone();
        if (s.under != null) {
            Object n = Block.blockRegistry.getNameForObject(s.under);
            x.under = n == null ? "?" : n.toString();
            Material m = s.under.getMaterial();
            x.underLiquid = m.isLiquid();
            x.underSolid = m.blocksMovement();
        }
        if (s.threat != null) { x.threat = name(s.threat); x.threatDist = s.threatDist; }
        x.mobs = s.mobs.size();
        for (EntityLivingBase e : s.mobs) if (Guard.hostile(e)) x.hostiles++;
        List<Crew.Task> chain = chain(a.jobs.peek());
        Crew.Task inner = chain.isEmpty() ? null : chain.get(chain.size() - 1);
        if (inner != null) {
            x.task = name(inner);
            if (inner instanceof Crew.GoTo) x.phase = new String[] { "NAV", "ROUTE", "DIG" }[Math.max(0, Math.min(2, ((Crew.GoTo) inner).state))];
        }
        for (Crew.Task t : chain) if (t instanceof Crew.GoTo) x.still = Math.max(x.still, ((Crew.GoTo) t).stuckFor);
        double[] to = null;
        for (int i = chain.size() - 1; i >= 0 && to == null; i--) to = target(chain.get(i));
        Crew c = Crew.instance;
        TaskBoard.Card k = c == null ? null : c.board.leased.get(a);
        if (k != null) {
            x.card = k.kind + "#" + k.id;
            if (to == null) to = k.at;
        }
        if (to != null) {
            x.target = true;
            x.dx = to[0] - a.posX;
            x.dy = to[1] - (x.feet != null ? x.feet[1] : a.posY);
            x.dz = to[2] - a.posZ;
        }
        x.origin = a.origins.isEmpty() ? "" : a.origins.peek().name();
        for (ItemStack it : a.bag) {
            if (it == null || it.getItem() == null) continue;
            if (it.getItem() == torch) x.torches += it.stackSize;
            else if (it.getItem() instanceof ItemBlock && Block.getBlockFromItem(it.getItem()).getMaterial().isSolid()) x.blocks += it.stackSize;
            if (it.getItem() instanceof ItemPickaxe) x.pickaxe = true;
            if (it.getItem() == Items.water_bucket) x.waterBucket = true;
            if (Agent.damageOf(it) > 2) x.weapon = true;   // more than a bare hand's 1 and a shovel's
        }
        if (x.feet != null) x.light = a.worldObj.getBlockLightValue(x.feet[0], x.feet[1], x.feet[2]);
        return x;
    }

    private static final net.minecraft.item.Item torch = net.minecraft.item.Item.getItemFromBlock(Blocks.torch);

    static String name(EntityLivingBase e) {
        if (e instanceof WaveMobs.Mob) {   // a wave's mob is the game's own kind under a class of its own: named as that kind (the reflexes and the fight know it so)
            if (e instanceof net.minecraft.entity.monster.EntityCreeper) return "Creeper";
            if (e instanceof net.minecraft.entity.monster.EntitySkeleton) return "Skeleton";
            if (e instanceof net.minecraft.entity.monster.EntitySpider) return "Spider";
            if (e instanceof net.minecraft.entity.monster.EntityZombie) return "Zombie";
            if (e instanceof net.minecraft.entity.monster.EntityWitch) return "Witch";
        }
        String n = EntityList.getEntityString(e);
        return n != null ? n : e.getClass().getSimpleName();
    }

    static String name(Crew.Task t) {
        String n = t.getClass().getSimpleName();
        if (n.isEmpty()) { n = t.getClass().getName(); n = n.substring(n.lastIndexOf('.') + 1); }
        return n;
    }

    /** the task and the ones it runs now, outermost first (a Seq's step, a Build's placing, a Mine's or a Collect's walk) */
    static List<Crew.Task> chain(Crew.Task t) {
        List<Crew.Task> out = new ArrayList<Crew.Task>();
        for (int depth = 0; t != null && depth < 6; depth++) {
            out.add(t);
            Crew.Task in = null;
            if (t instanceof TaskBoard.Seq) { TaskBoard.Seq q = (TaskBoard.Seq) t; if (q.steps.length > 0) in = q.steps[Math.min(q.i, q.steps.length - 1)]; }
            else if (t instanceof Crew.Build) { Crew.Build b = (Crew.Build) t; in = b.put != null ? b.put : b.fetch; }
            else if (t instanceof Crew.Mine) in = ((Crew.Mine) t).walk;
            else if (t instanceof Crew.Collect) in = ((Crew.Collect) t).walk;
            t = in;
        }
        return out;
    }

    /** where a task is after (a block, a place to go), or null */
    static double[] target(Crew.Task t) {
        if (t instanceof Crew.GoTo) { Crew.GoTo g = (Crew.GoTo) t; return new double[] { g.x, g.y, g.z }; }
        if (t instanceof Skills.PlaceFrom) { Skills.PlaceFrom p = (Skills.PlaceFrom) t; return new double[] { p.x + 0.5, p.y, p.z + 0.5 }; }
        if (t instanceof Crew.Collect) { Crew.Collect c = (Crew.Collect) t; return new double[] { c.x, c.y, c.z }; }
        if (t instanceof Crew.Mine) {
            Crew.Mine m = (Crew.Mine) t;
            if (m.blocks.isEmpty()) return null;
            int[] b = m.blocks.get(Math.min(m.i, m.blocks.size() - 1));
            return new double[] { b[0] + 0.5, b[1], b[2] + 0.5 };
        }
        if (t instanceof Crew.Build) { int[] b = ((Crew.Build) t).cur; return b == null ? null : new double[] { b[0] + 0.5, b[1], b[2] + 0.5 }; }
        return null;
    }

    // ---- what the reflexes call -----------------------------------------------------------------------

    /**
     * What the memory advises this agent's reflex now: of these reactions (none: any it tried), the one that helped
     * most often in situations like this one. Advice.known() false (too few cases, too unlike, or they did not help,
     * or the journal is still being read): the reflex does its default. Costs one pass over the cases in memory (a few
     * thousand at most): call it when the reflex fires, not in wants().
     */
    public static CaseBook.Advice suggest(Agent a, String reflex, String... options) {
        try {
            return book().suggest(CaseVec.vector(sense(a)), reflex, options);
        } catch (Exception e) {
            return CaseBook.Advice.UNKNOWN;
        }
    }

    /** a reaction begins (the tick the reflex took the body): the situation is read now, the case is written when it ends */
    public static void start(Agent a, String reflex, int priority, String reaction) {
        Open o = new Open();
        o.at = a.worldObj.getTotalWorldTime();
        o.c.t = o.at;
        o.c.agent = a.getCustomNameTag();
        o.c.reflex = reflex;
        o.c.priority = priority;
        o.c.reaction = reaction == null ? "" : reaction;
        try { o.c.sit = sense(a); } catch (Exception e) { Events.log("cases: no situation: " + e); }
        synchronized (open) {
            Open was = open.put(a, o);
            if (was != null) end(a, was, "PUSHED", "reflex");   // a higher reflex took the body from it
        }
    }

    /** what the body did in the reaction (a short line: "place cobblestone 10 62 4"); at most 16 kept */
    public static void act(Agent a, String action) {
        synchronized (open) {
            Open o = open.get(a);
            if (o != null && o.c.actions.size() < 16) o.c.actions.add(action);
        }
    }

    /** the reaction ended with this result; escalated: null, "task" (the task ends with it), "guide" */
    public static void finish(Agent a, Result r, String escalated) {
        Open o;
        synchronized (open) { o = open.remove(a); }
        if (o == null) return;
        end(a, o, r == null ? "" : r.name(), escalated);
    }

    /** the agent died or left: a reaction it had open ends so */
    public static void drop(Agent a, String why) {
        Open o;
        synchronized (open) { o = open.remove(a); }
        if (o != null) end(a, o, why, "task");
    }

    /** one case at once, its situation read beforehand (a reaction of a single tick, or one recorded by hand) */
    public static void record(Agent a, String reflex, int priority, CaseVec.Sit at, String reaction, List<String> actions, Result r, int ticks, String escalated) {
        CaseBook.Case c = new CaseBook.Case();
        c.t = a.worldObj.getTotalWorldTime() - ticks;
        c.agent = a.getCustomNameTag();
        c.reflex = reflex;
        c.priority = priority;
        c.sit = at != null ? at : sense(a);
        c.reaction = reaction == null ? "" : reaction;
        if (actions != null) c.actions.addAll(actions);
        c.result = r == null ? "" : r.name();
        c.ticks = ticks;
        c.after = after(a);
        c.escalated = escalated;
        save(a, c);
    }

    private static void end(Agent a, Open o, String result, String escalated) {
        o.c.result = result;
        o.c.ticks = (int) (a.worldObj.getTotalWorldTime() - o.at);
        o.c.after = after(a);
        o.c.escalated = escalated;
        save(a, o.c);
    }

    private static void save(Agent a, CaseBook.Case c) {
        try {
            book().add(c);
        } catch (Exception e) {
            Events.log("cases: not kept: " + e);
        }
        Epochs.reflexCase(a, c);   // an episode of the epoch, when one is on
        Events.log("reflex: " + c.agent + " " + c.reflex + " " + c.reaction + " -> " + c.result + " in " + c.ticks + " ticks" + (c.escalated != null ? ", up to the " + c.escalated : ""));
    }

    /** a few senses after the reaction (to see what it changed) */
    static com.google.gson.JsonObject after(Agent a) {
        Body.Senses s = a.body.senses;
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("health", a.getHealth());
        o.addProperty("fall", CaseVec.round(a.fallDistance));
        o.addProperty("onGround", a.onGround);
        o.addProperty("inWater", s.inWater);
        o.addProperty("inLava", s.inLava);
        o.addProperty("burning", s.burning);
        Defense.Tally t = Defense.peek(a.getCustomNameTag());   // the agent's fight so far (for training: what the case came to in the fight)
        if (t != null && !t.empty()) o.add("combat", t.json());
        return o;
    }
}
