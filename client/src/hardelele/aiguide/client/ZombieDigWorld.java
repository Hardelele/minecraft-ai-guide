package hardelele.aiguide.client;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.entity.EntityLiving;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.world.WorldServer;

/**
 * The digging zombies in the world (the rules are ZombieDig, the design docs/rl/ZOMBIES-DIG.md): a wave's zombie marked
 * a digger (WaveMobs.Tag.digger, a pickaxe in its hand) that hears an agent under the ground it cannot walk to breaks
 * the blocks on its way to it, one after another, as the zombie breaks a door: the block's cracks, its sound, the time
 * by its hardness; then walks the cell cleared. The tunnel stays; the other mobs of the wave go by it with the game's
 * own path finding (Waves.steer gives them the agent as their target within 48 blocks). Runs on the server's thread,
 * ticks of Waves: each digger every 5 ticks. A run's numbers (blocks dug, seconds the crew spent underground in
 * waves, mobs that came up to an agent underground) go to the run's report (stats()).
 */
final class ZombieDigWorld {
    private ZombieDigWorld() { }

    static ZombieDig.Mode mode = ZombieDig.mode(System.getProperty("aiguide.waves.dig"));
    static final ZombieDig.Params P = params();
    static final int EVERY = 5;

    /** a digger's work */
    static final class Work {
        int[] cell;            // the block being broken
        Block block;
        double done;           // 0..1
        int stage = -1, need;
        long lastWork;         // the tick of its last work
        ZombieDig.Step step;   // the step it is on
        long stepSince;
        long pathAsk;          // the tick of its last question "is there a way"
        boolean reachable;
        int crack;
    }

    static final Map<Integer, Work> works = new HashMap<Integer, Work>();
    /** an agent's seconds underground in this wave (its entity id) */
    static final Map<Integer, Integer> under = new HashMap<Integer, Integer>();
    /** (mob id, agent id) pairs that came up to an agent underground, in this run */
    static final Set<Long> reached = new HashSet<Long>();
    static int dugBlocks, underSeconds, diggersSeen, reachedAgents, deepest;
    static final Set<Integer> seen = new HashSet<Integer>();

    static ZombieDig.Params params() {
        ZombieDig.Params p = new ZombieDig.Params();
        p.frac = num("aiguide.waves.dig.frac", p.frac);
        p.step = num("aiguide.waves.dig.step", p.step);
        p.fracMax = num("aiguide.waves.dig.fracmax", p.fracMax);
        p.max = (int) num("aiguide.waves.dig.max", p.max);
        p.detect = num("aiguide.waves.dig.detect", p.detect);
        p.hearGrow = num("aiguide.waves.dig.hear", p.hearGrow);
        p.hearGrace = num("aiguide.waves.dig.grace", p.hearGrace);
        p.hearMax = num("aiguide.waves.dig.hearmax", p.hearMax);
        p.cell = num("aiguide.waves.dig.cell", p.cell);
        p.minTicks = (int) num("aiguide.waves.dig.min", p.minTicks);
        return p;
    }

    static double num(String k, double d) {
        try {
            String v = System.getProperty(k);
            return v == null ? d : Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return d;
        }
    }

    static boolean on() { return mode != ZombieDig.Mode.OFF; }

    // ---- a run's start and end -------------------------------------------------------------------------------------

    /** a run starts: nothing carried over (the cracks of the last one are cleared too) */
    static void reset(WorldServer w) {
        clear(w);
        under.clear();
        reached.clear();
        seen.clear();
        dugBlocks = underSeconds = diggersSeen = reachedAgents = deepest = 0;
    }

    /** a new wave: the agents' time underground (what they are heard by) begins again */
    static void wave() { under.clear(); }

    /** the cracks off the blocks, the works forgotten */
    static void clear(WorldServer w) {
        if (w != null) for (Work k : works.values()) if (k.cell != null) w.destroyBlockInWorldPartially(k.crack, k.cell[0], k.cell[1], k.cell[2], -1);
        works.clear();
    }

    /** a wave's zombie just made: marked a digger by the share (k from 0 of total zombies in the wave) and given a pickaxe */
    static void mark(EntityLiving m, int wave, int k, int total) {
        if (!on() || !(m instanceof WaveMobs.Zombie)) return;
        if (!ZombieDig.digger(wave, k, total, P)) return;
        ((WaveMobs.Zombie) m).tag.digger = true;
        if (m.getEquipmentInSlot(4) == null) m.setCurrentItemOrArmor(0, new ItemStack(Items.iron_pickaxe));
        diggersSeen++;
    }

    /** a digger at work just now (Waves does not take it for a stuck one) */
    static boolean busy(EntityLiving m, long ticks) {
        Work k = works.get(m.getEntityId());
        return k != null && ticks - k.lastWork < 100;
    }

    /** the run's numbers for its report: {dug blocks, seconds underground, diggers, agents reached, deepest agent (blocks under the ground)} */
    static int[] stats() { return new int[] { dugBlocks, underSeconds, diggersSeen, reachedAgents, deepest }; }

    // ---- the tick ---------------------------------------------------------------------------------------------------

    /** each tick of a wave (not paused): once a second the agents' time underground; each digger every EVERY ticks */
    static void tick(WorldServer w, long ticks, List<EntityLiving> alive, Crew crew, int wave) {
        if (crew == null) return;
        if (ticks % 20 == 0) {
            for (Agent a : crew.agents) {
                if (a.isDead || a.getHealth() <= 0 || a.worldObj != w) continue;
                int depth = depth(w, a);
                if (depth >= P.below + 1) {
                    Integer t = under.get(a.getEntityId());
                    under.put(a.getEntityId(), t == null ? 1 : t + 1);
                    underSeconds++;
                    deepest = Math.max(deepest, depth);
                } else under.remove(a.getEntityId());
            }
            if (ticks % 40 == 0) contact(alive, crew, w);
        }
        if (!on()) return;
        for (Iterator<Map.Entry<Integer, Work>> it = works.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Work> e = it.next();
            Work k = e.getValue();
            if (ticks - k.lastWork > 200 || w.getEntityByID(e.getKey()) == null) {
                if (k.cell != null) w.destroyBlockInWorldPartially(k.crack, k.cell[0], k.cell[1], k.cell[2], -1);
                it.remove();
            }
        }
        for (EntityLiving m : alive) {
            if (m.isDead || m.getHealth() <= 0 || !(m instanceof WaveMobs.Zombie) || !((WaveMobs.Zombie) m).tag.digger) continue;
            if ((m.getEntityId() + ticks) % EVERY != 0) continue;
            try {
                work(w, ticks, m, crew);
            } catch (RuntimeException ex) {
                Events.log("waves: a digger failed: " + ex);
            }
        }
    }

    /** blocks between an agent's feet and the ground above it (the top solid block of its column), 0 when on it or above */
    static int depth(WorldServer w, Agent a) {
        int x = MathHelper.floor_double(a.posX), z = MathHelper.floor_double(a.posZ);
        if (!w.blockExists(x, 64, z)) return 0;
        int top = w.getTopSolidOrLiquidBlock(x, z);
        return Math.max(0, top - MathHelper.floor_double(a.boundingBox.minY));
    }

    /** mobs that came up to an agent under the ground (within 2.5 blocks), counted once a pair in a run */
    static void contact(List<EntityLiving> alive, Crew crew, WorldServer w) {
        for (Agent a : crew.agents) {
            if (a.isDead || !under.containsKey(a.getEntityId())) continue;
            for (EntityLiving m : alive)
                if (!m.isDead && m.getDistanceToEntity(a) < 2.5 && reached.add(((long) m.getEntityId() << 32) | a.getEntityId())) reachedAgents++;
        }
    }

    /** the target of a digger: the nearest agent it hears (the straight distance within hear(t) of the agent's time underground), under the ground (mode below) */
    static Agent target(EntityLiving m, Crew crew, WorldServer w) {
        Agent best = null;
        double bd = Double.MAX_VALUE;
        double feet = m.boundingBox.minY;
        for (Agent a : crew.agents) {
            if (a.isDead || a.getHealth() <= 0 || a.worldObj != m.worldObj) continue;
            Integer t = under.get(a.getEntityId());
            boolean low = a.boundingBox.minY <= feet - P.below;
            if (mode == ZombieDig.Mode.BELOW && (!low || t == null)) continue;
            double r = ZombieDig.hear(t == null ? 0 : t, P);
            double d = m.getDistanceToEntity(a);
            if (d <= r && d < bd) { bd = d; best = a; }
        }
        return best;
    }

    static final ZombieDig.Terrain terrain(final WorldServer w) {
        return new ZombieDig.Terrain() {
            public float cell(int x, int y, int z) {
                if (y < 1 || y > 250) return ZombieDig.NEVER;
                if (!w.blockExists(x, y, z)) return ZombieDig.NEVER;
                Block b = w.getBlock(x, y, z);
                if (b.getMaterial().isLiquid()) return ZombieDig.LIQUID;
                if (!b.getMaterial().isSolid()) return ZombieDig.PASS;
                float h = b.getBlockHardness(w, x, y, z);
                return h < 0 ? ZombieDig.NEVER : Math.max(0.05f, h);
            }
        };
    }

    static void work(WorldServer w, long ticks, EntityLiving m, Crew crew) {
        Agent tg = target(m, crew, w);
        Work k = works.get(m.getEntityId());
        if (tg == null) {
            if (k != null) release(w, m, k);
            return;
        }
        if (m.getDistanceToEntity(tg) < 2.2) return;   // there: it hits
        if (k == null) {
            int digging = 0;
            for (Work o : works.values()) if (o.cell != null) digging++;
            if (digging >= P.max) return;
            k = new Work();
            k.crack = -2000 - m.getEntityId();
            works.put(m.getEntityId(), k);
        }
        // is there a way (the game's path finding), asked every second at most: then it walks, the game's own
        if (ticks - k.pathAsk >= 20) {
            k.pathAsk = ticks;
            net.minecraft.pathfinding.PathEntity pe = RlEngine.pathTo(m, tg.posX, tg.boundingBox.minY, tg.posZ);
            k.reachable = pe != null && pe.getFinalPathPoint() != null
                    && Math.abs(pe.getFinalPathPoint().xCoord - tg.posX) < 2.5 && Math.abs(pe.getFinalPathPoint().yCoord - tg.boundingBox.minY) < 2.5
                    && Math.abs(pe.getFinalPathPoint().zCoord - tg.posZ) < 2.5;
        }
        if (k.reachable) { release(w, m, k); return; }
        k.lastWork = ticks;
        ZombieDig.Terrain t = terrain(w);
        int fx = MathHelper.floor_double(m.posX), fy = MathHelper.floor_double(m.boundingBox.minY + 0.01), fz = MathHelper.floor_double(m.posZ);
        // a step in hand: finished when the zombie's feet are in its cell; else broken, then walked into
        if (k.step != null && fx == k.step.x && fy == k.step.y && fz == k.step.z) k.step = null;
        if (k.step != null && ticks - k.stepSince > 20 * 8) k.step = null;   // it did not get there: plan again
        if (k.step == null) {
            k.step = ZombieDig.plan(t, fx, fy, fz, tg.posX, tg.boundingBox.minY, tg.posZ, P);
            k.stepSince = ticks;
            if (k.step == null) return;   // no way to dig: it goes as a plain zombie
        }
        int[] c = k.step.next(t);
        if (c == null) {   // open: walk into it
            if ((ticks / EVERY) % 4 == 0) RlEngine.moveTo(m, k.step.x + 0.5, k.step.y, k.step.z + 0.5, 1.0);
            if (k.cell != null) { w.destroyBlockInWorldPartially(k.crack, k.cell[0], k.cell[1], k.cell[2], -1); k.cell = null; }
            return;
        }
        if (k.cell == null || k.cell[0] != c[0] || k.cell[1] != c[1] || k.cell[2] != c[2]) {
            if (k.cell != null) w.destroyBlockInWorldPartially(k.crack, k.cell[0], k.cell[1], k.cell[2], -1);
            k.cell = c;
            k.block = w.getBlock(c[0], c[1], c[2]);
            k.done = 0;
            k.stage = -1;
            k.need = ZombieDig.ticks(k.block.getBlockHardness(w, c[0], c[1], c[2]), P);
        }
        if (k.need < 0) { k.step = null; return; }
        m.getLookHelper().setLookPosition(c[0] + 0.5, c[1] + 0.5, c[2] + 0.5, 30F, 30F);
        m.getNavigator().clearPathEntity();
        k.done += (double) EVERY / k.need;
        if ((ticks / EVERY) % 3 == 0) m.swingItem();
        if ((ticks / EVERY) % 3 == 1) w.playSoundEffect(c[0] + 0.5, c[1] + 0.5, c[2] + 0.5, k.block.stepSound.getStepSound(), 0.8F, 0.6F);
        int stage = Math.min(9, (int) (k.done * 10));
        if (stage != k.stage) { k.stage = stage; w.destroyBlockInWorldPartially(k.crack, c[0], c[1], c[2], stage); }
        if (k.done < 1) return;
        w.destroyBlockInWorldPartially(k.crack, c[0], c[1], c[2], -1);
        w.breakBlock(c[0], c[1], c[2], false);
        dugBlocks++;
        if (dugBlocks <= 3 || dugBlocks % 50 == 0) Events.log("waves: dig: " + m.getCommandSenderName() + " broke " + MindSkills.idOf(k.block) + " at " + c[0] + " " + c[1] + " " + c[2] + " (block " + dugBlocks + " of the run)");
        k.cell = null;
    }

    static void release(WorldServer w, EntityLiving m, Work k) {
        if (k.cell != null) w.destroyBlockInWorldPartially(k.crack, k.cell[0], k.cell[1], k.cell[2], -1);
        works.remove(m.getEntityId());
    }

    /** the run's numbers in a line for the events */
    static String line() {
        return "dig " + mode.name().toLowerCase(java.util.Locale.ROOT) + ": " + dugBlocks + " blocks dug, " + diggersSeen + " diggers, crew underground " + underSeconds + " s, "
                + reachedAgents + " mob-agent contacts underground, deepest " + deepest;
    }
}
