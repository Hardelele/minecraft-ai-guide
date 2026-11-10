package hardelele.aiguide.client;

import java.util.Random;
import net.minecraft.util.FastLongMap;
import net.minecraft.util.LongHashMap;
import net.minecraft.world.ChunkCoordIntPair;

/** The learning engine's chunk map (docs/rl/ENGINE.md): FastLongMap answers as the game's LongHashMap does, op by op. */
public final class EngineGameTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    public static void main(String[] args) throws Exception {
        Random r = new Random(7);
        LongHashMap game = new LongHashMap();
        FastLongMap fast = new FastLongMap();
        // chunk keys of a square round a spawn (the game's hash collides along its diagonals), adds and removes mixed
        for (int op = 0; op < 200000; op++) {
            int x = r.nextInt(64) - 32, z = r.nextInt(64) - 32;
            long k = op % 7 == 0 ? r.nextLong() : ChunkCoordIntPair.chunkXZ2Int(x, z);
            int what = r.nextInt(10);
            if (what < 5) {
                Object v = Integer.valueOf(op);
                game.add(k, v);
                fast.add(k, v);
            } else if (what < 8) {
                check(game.remove(k) == fast.remove(k), "remove " + k + " at op " + op);
            }
            check(game.getValueByKey(k) == fast.getValueByKey(k), "get " + k + " at op " + op);
            check(game.containsItem(k) == fast.containsItem(k), "contains " + k + " at op " + op);
            check(game.getNumHashElements() == fast.getNumHashElements(), "size at op " + op + ": " + game.getNumHashElements() + " vs " + fast.getNumHashElements());
        }
        for (int x = -40; x <= 40; x++)
            for (int z = -40; z <= 40; z++) {
                long k = ChunkCoordIntPair.chunkXZ2Int(x, z);
                check(game.getValueByKey(k) == fast.getValueByKey(k), "sweep " + x + " " + z);
            }
        // a copy holds what the game's map holds
        FastLongMap copy = FastLongMap.copyOf(game);
        check(copy.getNumHashElements() == game.getNumHashElements(), "copy size");
        for (int x = -40; x <= 40; x++)
            for (int z = -40; z <= 40; z++) {
                long k = ChunkCoordIntPair.chunkXZ2Int(x, z);
                check(game.getValueByKey(k) == copy.getValueByKey(k), "copy " + x + " " + z);
            }
        System.out.println("EngineGameTest PASS: FastLongMap = LongHashMap over 200000 mixed ops, a 81x81 sweep and a copy (" + game.getNumHashElements() + " keys)");
    }
}
