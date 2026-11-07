package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.BlockFalling;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.world.WorldServer;

/** Observed ore first, otherwise a geometric, reversible staircase. No buried-ore target search. */
final class Prospecting implements Crew.Task {
    static final int SEARCH_TICKS = 20 * 900;
    static final Map<Agent, Prospecting> active = Collections.synchronizedMap(new WeakHashMap<Agent, Prospecting>());
    final Crew crew;
    final Item wanted;
    final Block ore;
    final int amount;
    final Set<Long> triedOre = new HashSet<Long>();
    final Set<Long> triedFrontier = new HashSet<Long>();
    final List<int[]> trail = new ArrayList<int[]>();
    int initial = -1, ticks, heading, steps, failedDirections, salt, mode, returnAt;
    int relocations, frontierMisses, nextFrontierAt;
    int[] anchor, next, target;
    Crew.Task current;
    Result pending;
    volatile String message = "Разведка месторождений";
    volatile int updated;

    Prospecting(Crew crew, Item wanted, int amount) {
        this.crew = crew; this.wanted = wanted; this.amount = Math.max(1, amount);
        ore = wanted == Items.coal ? Blocks.coal_ore : wanted == Day2.IRON_ORE ? Blocks.iron_ore : null;
    }

    static String status(Agent a) {
        Prospecting p = active.get(a);
        return p != null && a.ticksExisted - p.updated <= 30 ? p.message : null;
    }

    void say(Agent a, String text) { message = text; updated = a.ticksExisted; }

    public Result step(Agent a, WorldServer w) {
        if (ore == null) return Result.NO_ITEMS;
        active.put(a, this); updated = a.ticksExisted;
        Exploration.observe(crew, a, w);
        if (initial < 0) {
            initial = Skills.count(a.bag, wanted, -1);
            anchor = Ground.feet(w, a.posX, a.boundingBox.minY, a.posZ);
            if (anchor == null) return Result.NO_PATH;
            anchor = anchor.clone(); trail.add(anchor.clone()); heading = Math.floorMod(a.skin(), 4);
            Events.log("prospect: " + a.getCustomNameTag() + " starts observed search at " + java.util.Arrays.toString(anchor));
        }
        if (pending != null) return returning(a, w);
        if (++ticks > SEARCH_TICKS) beginReturn(Result.TIMEOUT);
        else if (Skills.count(a.bag, wanted, -1) - initial >= amount) beginReturn(Result.OK);
        // An ongoing harvest owns its own tool check; never abandon its falling drops midway.
        else if (current == null && Skills.bestTool(a, ore, 0) == null) beginReturn(Result.NO_TOOL);
        if (pending != null) return returning(a, w);

        if (current != null) {
            Result r = current.step(a, w);
            if (r == Result.RUNNING || r == Result.BUSY) return Result.RUNNING;
            current = null;
            if (r == Result.NO_TOOL) { beginReturn(r); return Result.RUNNING; }
            if (mode == 1) { // ore detour: return to the last stair landing before continuing it
                triedOre.add(Crew.key(target[0], target[1], target[2]));
                mode = 2; current = journey(anchor); say(a, "Возвращается к разведочной лестнице");
            } else if (mode == 2) {
                if (r != Result.OK) { beginReturn(Result.NO_PATH); return Result.RUNNING; }
            } else if (mode == 3) {
                if (r == Result.OK) {
                    anchor = next.clone(); trail.add(anchor.clone()); steps++; failedDirections = 0;
                    Events.log("prospect: step reached " + java.util.Arrays.toString(anchor));
                    // At the bottom, expanding rectangular branches avoid repeatedly walking the same square.
                    if (anchor[1] <= 12 && steps % (16 + 4 * (steps / 32)) == 0) heading = (heading + 1) % 4;
                } else { heading = (heading + 1) % 4; failedDirections++; }
            } else if (mode == 4) {
                int[] reached = r == Result.OK ? Ground.feet(w, a.posX, a.boundingBox.minY, a.posZ) : null;
                if (reached != null && !java.util.Arrays.equals(reached, anchor)) {
                    anchor = reached.clone(); trail.add(anchor.clone()); frontierMisses = 0;
                    failedDirections = canExcavateHere(crew, anchor) ? 0 : 4;
                    Events.log("prospect: frontier reached " + java.util.Arrays.toString(anchor));
                } else { frontierMisses++; failedDirections = 4; }
            }
            return Result.RUNNING;
        }

        // Coordinates enter this ranking exclusively through observations, never world block-volume reads.
        target = nearestKnown(a, w);
        if (target != null) {
            mode = 1; current = new ObservedOre(target);
            Events.log("prospect: observed ore " + java.util.Arrays.toString(target));
            say(a, "Известная жила · Y" + target[1]);
            return Result.RUNNING;
        }
        boolean relocate = !canExcavateHere(crew, anchor) || failedDirections >= 4;
        if (relocate || steps == 0 && salt == 0) {
            if (relocate && (relocations >= 32 || frontierMisses >= 8)) { beginReturn(Result.NO_PATH); return Result.RUNNING; }
            if (ticks < nextFrontierAt) return Result.RUNNING;
            nextFrontierAt = ticks + 20; // allow a new observation sweep; do not flood-search every tick
            int[] frontier = relocationFrontier(a, w, !relocate);
            if (frontier != null) {
                // A rejected flat cave candidate remains available later when leaving a protected base.
                triedFrontier.add(Crew.key(frontier[0], frontier[1], frontier[2]));
                relocations++; mode = 4; current = journey(frontier);
                say(a, relocate ? "Ищет безопасный выход к месту раскопки" : "Разведка прохода · Y" + frontier[1]);
                return Result.RUNNING;
            }
            if (relocate) { frontierMisses++; say(a, "Осматривает выход из защищённой зоны"); return Result.RUNNING; }
        }
        next = nextCell(anchor, heading);
        Events.log("prospect: geometric step " + java.util.Arrays.toString(anchor) + " -> " + java.util.Arrays.toString(next));
        mode = 3; current = new StairStep(anchor.clone(), next.clone());
        say(a, (next[1] < anchor[1] ? "Прокладывает спуск · Y" : "Разведочная штольня · Y") + next[1]);
        return Result.RUNNING;
    }

    /** Pure geometry: the next excavation is invariant under all changes to unseen ore. */
    static int[] nextCell(int[] from, int heading) {
        int[][] dirs = { {1, 0}, {0, 1}, {-1, 0}, {0, -1} };
        int[] d = dirs[Math.floorMod(heading, 4)];
        return new int[] { from[0] + d[0], from[1] > 12 ? from[1] - 1 : from[1], from[2] + d[1] };
    }

    /** Only protection policy is queried here: no underground blocks, ore, heights or hazards. */
    static boolean canExcavateHere(Crew c, int[] from) {
        for (int direction = 0; direction < 4; direction++) {
            int[] to = nextCell(from, direction);
            boolean allowed = true;
            for (int y = from[1] + 1; y >= to[1]; y--)
                if (!Ground.mayDig(c, to[0], y, to[2], "gather")) { allowed = false; break; }
            if (allowed) return true;
        }
        return false;
    }

    static boolean acceptFrontier(int[] candidate, int[] from, Set<Long> tried, boolean lowerOnly) {
        return candidate != null && !java.util.Arrays.equals(candidate, from)
                && (!lowerOnly || candidate[1] < from[1])
                && !tried.contains(Crew.key(candidate[0], candidate[1], candidate[2]));
    }

    int[] relocationFrontier(Agent a, WorldServer w, boolean lowerOnly) {
        int[] intermediate = null;
        for (int choice = 0; choice < 8; choice++) {
            int[] candidate = Exploration.frontier(a, w, salt++);
            if (!acceptFrontier(candidate, anchor, triedFrontier, lowerOnly)) continue;
            if (canExcavateHere(crew, candidate)) return candidate;
            if (intermediate == null) intermediate = candidate;
        }
        return intermediate; // a real observed landing advances knowledge even while still inside the base
    }

    int[] nearestKnown(Agent a, WorldServer w) {
        int[] best = null; double distance = Double.MAX_VALUE;
        for (int[] p : Exploration.knownBlocks(w, ore)) {
            if (triedOre.contains(Crew.key(p[0], p[1], p[2])) || !Ground.mayDig(crew, p[0], p[1], p[2], "gather")) continue;
            double d = Crew.dist(a, p[0] + .5, p[1], p[2] + .5);
            if (d < distance) { best = p.clone(); distance = d; }
        }
        return best;
    }

    static Crew.Task journey(int[] p) { return new Crew.GoTo(p[0] + .5, p[1], p[2] + .5, .65).walkOnly(); }

    void beginReturn(Result result) {
        pending = result; current = null; returnAt = trail.size() - 1;
    }

    Result returning(Agent a, WorldServer w) {
        say(a, "Возвращается · добыто " + Math.max(0, Skills.count(a.bag, wanted, -1) - initial) + "/" + amount);
        if (current != null) {
            Result r = current.step(a, w);
            if (r == Result.RUNNING || r == Result.BUSY) return Result.RUNNING;
            current = null;
            if (r != Result.OK) { say(a, "Обратный путь перекрыт · " + pending); return Result.NO_PATH; }
            returnAt--;
        }
        while (returnAt >= 0 && Crew.dist(a, trail.get(returnAt)[0] + .5, trail.get(returnAt)[1], trail.get(returnAt)[2] + .5) < 1) returnAt--;
        if (returnAt < 0) { say(a, pending == Result.OK ? "Руда доставлена к выходу" : "Разведка завершена · " + pending); return pending; }
        current = journey(trail.get(returnAt));
        return Result.RUNNING;
    }

    /** Only local collision/hazard checks; mineral identity never chooses an excavation direction. */
    static boolean safeOpening(WorldServer w, int x, int y, int z) {
        for (int[] d : Skills.SIDE) {
            Block b = w.getBlock(x + d[0], y + d[1], z + d[2]);
            if (b.getMaterial().isLiquid() || b == Blocks.fire || d[1] > 0 && b instanceof BlockFalling) return false;
        }
        return true;
    }

    static boolean natural(Block b) {
        return b == Blocks.stone || b == Blocks.dirt || b == Blocks.grass || b == Blocks.sandstone
                || b == Blocks.coal_ore || b == Blocks.iron_ore || b == Blocks.gold_ore || b == Blocks.diamond_ore
                || b == Blocks.redstone_ore || b == Blocks.lit_redstone_ore || b == Blocks.lapis_ore || b == Blocks.emerald_ore;
    }

    final class DigOne implements Crew.Task {
        final int x, y, z;
        final boolean collectDrops;
        Crew.Break breaking;
        Crew.Task collect;
        DigOne(int x, int y, int z) { this(x, y, z, true); }
        DigOne(int x, int y, int z, boolean collectDrops) { this.x = x; this.y = y; this.z = z; this.collectDrops = collectDrops; }
        public Result step(Agent a, WorldServer w) {
            if (collect != null) return collect.step(a, w);
            if (Crew.eyeDist(a, x, y, z) > Crew.REACH || !Exploration.visible(a, w, x, y, z)) return Result.NO_PATH;
            Block b = w.getBlock(x, y, z);
            if (b == Blocks.air) return Result.OK;
            if (!natural(b) || !Ground.mayDig(crew, x, y, z, "gather") || !safeOpening(w, x, y, z)
                    || crew.board.reserved(a, x, y, z)) return Result.BLOCKED;
            if (x == (int) Math.floor(a.posX) && z == (int) Math.floor(a.posZ) && y < a.boundingBox.minY) return Result.BLOCKED;
            if (y < a.boundingBox.minY && !Ground.floor(w, x, y - 1, z, null)) return Result.BLOCKED;
            if (!crew.board.reserve(a, x, y, z)) return Result.BUSY;
            if (breaking == null) breaking = new Crew.Break(x, y, z, 0);
            Result r = breaking.step(a, w);
            if (r != Result.OK) return r;
            Ground.changed(); Exploration.invalidate(w, x, y, z); Exploration.observe(crew, a, w);
            for (int[] d : Skills.SIDE) Exploration.visible(a, w, x + d[0], y + d[1], z + d[2]);
            Skills.lightUp(a, w);
            if (!collectDrops) return Result.OK;
            collect = new Crew.Collect(x + .5, y, z + .5, 2);
            return Result.RUNNING;
        }
    }

    final class ObservedOre implements Crew.Task {
        final int[] at;
        Crew.Task move, dig;
        ObservedOre(int[] at) { this.at = at.clone(); }
        public Result step(Agent a, WorldServer w) {
            if (dig != null) return dig.step(a, w);
            if (Crew.eyeDist(a, at[0], at[1], at[2]) <= Crew.REACH && Exploration.visible(a, w, at[0], at[1], at[2])) {
                if (w.getBlock(at[0], at[1], at[2]) != ore) { Exploration.invalidate(w, at[0], at[1], at[2]); return Result.GONE; }
                dig = new DigOne(at[0], at[1], at[2]); return Result.RUNNING;
            }
            if (move == null) move = new Crew.GoTo(at[0] + .5, at[1], at[2] + .5, 2.5);
            Result r = move.step(a, w);
            return r == Result.OK ? Result.NO_PATH : r;
        }
    }

    final class StairStep implements Crew.Task {
        final int[] from, to;
        int clearY;
        Crew.Task action;
        boolean walking, collecting, settling;
        StairStep(int[] from, int[] to) { this.from = from; this.to = to; clearY = from[1] + 1; }
        public Result step(Agent a, WorldServer w) {
            // A solid landing is never excavated: every descent is one block and can be climbed back.
            Block floor = w.getBlock(to[0], to[1] - 1, to[2]);
            if (!Ground.floor(w, to[0], to[1] - 1, to[2], null) || floor instanceof BlockFalling || floor.getMaterial().isLiquid()) return Result.BLOCKED;
            if (action != null) {
                Result r = action.step(a, w);
                if (r != Result.OK) return r;
                action = null;
                if (settling) return Result.OK;
                if (collecting) { settling = true; action = journey(to); return Result.RUNNING; }
                if (walking) {
                    for (int dy = 0; dy <= 1; dy++) for (int[] d : Skills.SIDE)
                        Exploration.visible(a, w, to[0] + d[0], to[1] + dy + d[1], to[2] + d[2]);
                    collecting = true; action = new Crew.Collect(to[0] + .5, to[1], to[2] + .5, 2); return Result.RUNNING;
                }
                clearY--;
            }
            while (clearY >= to[1] && w.getBlock(to[0], clearY, to[2]) == Blocks.air) clearY--;
            if (clearY >= to[1]) { action = new DigOne(to[0], clearY, to[2], false); return Result.RUNNING; }
            if (!Ground.stand(w, to[0], to[1], to[2], null)) return Result.BLOCKED;
            walking = true; action = journey(to); return Result.RUNNING;
        }
    }
}
