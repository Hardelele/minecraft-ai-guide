package hardelele.aiguide.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

/**
 * A whole tree down: every log of it (the trunk and the branches, all the logs that touch each other, corners too),
 * the lowest first, each from a spot it can be reached from; a log no spot reaches is reached as a player would, on
 * a pillar of blocks from the bag put under itself, the pillar taken down after. When it is over, what is left of
 * the tree is looked for again (nothing of it may hang in the air); what cannot be got at is told (NO_PATH).
 * Leaves are left to decay as the game decays them.
 */
class Fell implements Crew.Task {
    /** the logs of the tree a block belongs to (a log, or leaves of it), or empty: not a tree (a house's logs are not one) */
    static List<int[]> tree(World w, int x, int y, int z) {
        List<int[]> out = new ArrayList<int[]>();
        int[] start = null;
        if (isLog(w.getBlock(x, y, z))) start = new int[] { x, y, z };
        else if (w.getBlock(x, y, z).getMaterial() == Material.leaves) {   // leaves: the nearest log through the leaves
            ArrayDeque<int[]> q = new ArrayDeque<int[]>();
            Set<Long> seen = new java.util.HashSet<Long>();
            q.add(new int[] { x, y, z, 0 });
            seen.add(Crew.key(x, y, z));
            while (!q.isEmpty() && start == null) {
                int[] c = q.poll();
                for (int[] d : Skills.SIDE) {
                    int nx = c[0] + d[0], ny = c[1] + d[1], nz = c[2] + d[2];
                    if (!seen.add(Crew.key(nx, ny, nz))) continue;
                    Block b = w.getBlock(nx, ny, nz);
                    if (isLog(b)) { start = new int[] { nx, ny, nz }; break; }
                    if (b.getMaterial() == Material.leaves && c[3] < 6) q.add(new int[] { nx, ny, nz, c[3] + 1 });
                }
            }
        }
        if (start == null) return out;
        ArrayDeque<int[]> q = new ArrayDeque<int[]>();
        Set<Long> seen = new java.util.HashSet<Long>();
        q.add(start);
        seen.add(Crew.key(start[0], start[1], start[2]));
        int leaves = 0;
        while (!q.isEmpty() && out.size() < 160) {
            int[] c = q.poll();
            out.add(c);
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        int nx = c[0] + dx, ny = c[1] + dy, nz = c[2] + dz;
                        Block b = w.getBlock(nx, ny, nz);
                        if (b.getMaterial() == Material.leaves && (w.getBlockMetadata(nx, ny, nz) & 4) == 0 && Math.abs(dx) + Math.abs(dy) + Math.abs(dz) == 1) leaves++;
                        if (!isLog(b) || !seen.add(Crew.key(nx, ny, nz))) continue;
                        q.add(new int[] { nx, ny, nz });
                    }
        }
        if (leaves < 2) out.clear();   // logs without the game's own leaves: built, not grown
        return out;
    }

    static boolean isLog(Block b) {
        return Orders.isLog(b);
    }

    final List<int[]> logs;
    Crew.Task cur;
    int[] working;
    int cut, gaveUp;
    final Set<Long> unreachable = new LinkedHashSet<Long>();

    Fell(List<int[]> logs) { this.logs = logs; }

    public Result step(Agent a, WorldServer w) {
        if (cur != null) {
            Result r = cur.step(a, w);
            if (r == Result.RUNNING) return r;
            cur = null;
            if (working != null && isLog(w.getBlock(working[0], working[1], working[2]))) unreachable.add(Crew.key(working[0], working[1], working[2]));
            else if (working != null) cut++;
            working = null;
            return Result.RUNNING;
        }
        // the lowest log left (of the tree as it was, and anything that still touches it: nothing may hang)
        int[] next = null;
        int left = 0;
        for (int[] l : logs) {
            if (!isLog(w.getBlock(l[0], l[1], l[2]))) continue;
            left++;
            if (unreachable.contains(Crew.key(l[0], l[1], l[2]))) continue;
            if (instance(w) != null && !Ground.mayDig(Crew.instance, l[0], l[1], l[2], "gather")) { unreachable.add(Crew.key(l[0], l[1], l[2])); continue; }
            if (next == null || l[1] < next[1] || l[1] == next[1] && Crew.dist(a, l[0], l[1], l[2]) < Crew.dist(a, next[0], next[1], next[2])) next = l;
        }
        if (left == 0) { Events.log("crew: " + a.getCustomNameTag() + " felled a tree (" + cut + " logs)"); return Result.OK; }
        if (next == null) { Events.log("crew: " + a.getCustomNameTag() + " cannot get at " + left + " logs of the tree"); return cut > 0 ? Result.BLOCKED : Result.NO_PATH; }
        working = next;
        int[] spot = spot(a, w, next);
        if (spot != null) cur = new TaskBoard.Seq(new Crew.GoTo(spot[0] + 0.5, spot[1], spot[2] + 0.5, 0.45), new Cut(next[0], next[1], next[2]));
        else cur = new Pillar(next);   // nothing reaches it from the ground: up on blocks, as a player
        return Result.RUNNING;
    }

    static Crew instance(WorldServer w) { return Crew.instance; }

    /** a cell to stand on from which the log is in reach (the eyes 1.62 over the feet), one the agent can walk to */
    static int[] spot(Agent a, WorldServer w, int[] l) {
        Ground.Flood f = Ground.flood(w, Ground.feet(w, a.posX, a.posY, a.posZ), 48, null);
        int[] best = null;
        double bd = 1e9;
        for (int dx = -4; dx <= 4; dx++)
            for (int dz = -4; dz <= 4; dz++)
                for (int dy = -6; dy <= 1; dy++) {
                    int x = l[0] + dx, y = l[1] + dy, z = l[2] + dz;
                    if (!f.has(x, y, z)) continue;
                    double ex = x + 0.5 - (l[0] + 0.5), ey = y + 1.62 - (l[1] + 0.5), ez = z + 0.5 - (l[2] + 0.5);
                    if (Math.sqrt(ex * ex + ey * ey + ez * ez) > Crew.REACH - 0.3) continue;
                    double d = Math.abs(a.posX - x) + Math.abs(a.posZ - z) + Math.abs(a.posY - y) * 2;
                    if (d < bd) { bd = d; best = new int[] { x, y, z }; }
                }
        return best;
    }

    /** one block cut from where it stands: leaves in the way broken first (they are soft), turned to it, the game's own breaking */
    static class Cut implements Crew.Task {
        final int x, y, z;
        Crew.Break br;
        int tries;
        Cut(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        public Result step(Agent a, WorldServer w) {
            if (br != null) { Result r = br.step(a, w); if (r == Result.RUNNING) return r; br = null; }
            if (w.getBlock(x, y, z).getMaterial() == Material.air) return Result.OK;
            if (Crew.eyeDist(a, x, y, z) > Crew.REACH + 0.2 || ++tries > 12) return Result.NO_PATH;
            int[] in = Crew.blocker(a, w, x, y, z);
            if (in != null) {
                Material m = w.getBlock(in[0], in[1], in[2]).getMaterial();
                if (m == Material.leaves || m == Material.plants || m == Material.vine || isLog(w.getBlock(in[0], in[1], in[2]))) { br = new Crew.Break(in[0], in[1], in[2], 0); return Result.RUNNING; }
                return Result.BLOCKED;
            }
            br = new Crew.Break(x, y, z, 0.9);
            return Result.RUNNING;
        }
    }

    /**
     * Up on a pillar to a block out of reach: beside its column, it jumps and puts a block from the bag under its feet,
     * again, until the block is in reach; cuts it; then digs its pillar back down under itself (the blocks back in the
     * bag). The climbing and the taking down are the crew's one pillar (Skills.Scaffold).
     */
    static class Pillar implements Crew.Task {
        final int[] target;
        Skills.Scaffold up;
        Pillar(int[] target) { this.target = target; }

        public Result step(final Agent a, WorldServer w) {
            if (up == null) {   // the foot of the pillar: the nearest free ground beside the column under the target
                int[] best = null;
                double bd = 1e9;
                for (int dx = -2; dx <= 2; dx++)
                    for (int dz = -2; dz <= 2; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        int x = target[0] + dx, z = target[2] + dz, y = Crew.ground(w, x, target[1] - 3, z);
                        if (y < 0 || y > target[1]) continue;
                        double d = Math.abs(dx) + Math.abs(dz) + (target[1] - y) * 0.1;
                        if (d < bd) { bd = d; best = new int[] { x, y, z }; }
                    }
                if (best == null) return Result.NO_PATH;
                if (Skills.Scaffold.block(a, null) == null) return Result.NO_ITEMS;   // nothing to build a pillar of
                Events.log("crew: " + a.getCustomNameTag() + " builds a pillar at " + best[0] + " " + best[1] + " " + best[2] + " to reach " + target[0] + " " + target[1] + " " + target[2]);
                up = new Skills.Scaffold(best, 12, ag -> Crew.eyeDist(ag, target[0], target[1], target[2]) <= Crew.REACH - 0.3, new Cut(target[0], target[1], target[2]), null);
            }
            Result r = up.step(a, w);
            return r == Result.RUNNING ? r : Result.OK;   // the log is looked at again by the felling
        }
    }
}