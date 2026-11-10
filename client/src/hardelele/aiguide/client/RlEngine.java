package hardelele.aiguide.client;

import com.google.gson.JsonObject;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import java.lang.reflect.Field;
import net.minecraft.entity.EntityLiving;
import net.minecraft.pathfinding.FastNavigate;
import net.minecraft.pathfinding.FastPath;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraft.pathfinding.PathEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.FastLongMap;
import net.minecraft.util.LongHashMap;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.ChunkProviderServer;

/**
 * The learning engine's switches (-Daiguide.rl.engine=true; docs/rl/ENGINE.md): what makes the game quicker for the
 * learner without changing what happens in it. Now: the path searches of the agents (walks, the reach of a tree for
 * the mask and the observation) and of the waves' mobs sent home by the mod go through FastPath, the game's path finder
 * step for step with quicker lookups (the same path; -Daiguide.rl.engine.check=N compares every Nth with the game's).
 * The mobs' and animals' own AI (EntityAI*) asks their navigators: each living entity joining a world gets a
 * FastNavigate in the same state (start), whose searches are FastPath's too. And each world's loaded chunks
 * are kept in FastLongMap (install), the same map with a hash that does not collide along the diagonals. Waiting for the
 * policy, the server thread looks for its answer SPIN_NS before it sleeps (RlEnv.serve): a sleeping thread wakes late.
 */
public final class RlEngine {
    private RlEngine() {}

    static final boolean ON = Boolean.getBoolean("aiguide.rl.engine");
    /** how long the server thread looks for the policy's answer before it sleeps (-Daiguide.rl.engine.spin_us, 3000) */
    static final long SPIN_NS = 1000L * Integer.getInteger("aiguide.rl.engine.spin_us", 3000);

    /** PathNavigate.getPathToXYZ */
    static PathEntity pathTo(EntityLiving e, double x, double y, double z) {
        return ON ? FastPath.pathToXYZ(e, x, y, z) : e.getNavigator().getPathToXYZ(x, y, z);
    }

    /** PathNavigate.tryMoveToXYZ */
    static boolean moveTo(EntityLiving e, double x, double y, double z, double speed) {
        return ON ? FastPath.tryMoveToXYZ(e, x, y, z, speed) : e.getNavigator().tryMoveToXYZ(x, y, z, speed);
    }

    /** World.getEntityPathToXYZ */
    static PathEntity path(World w, EntityLiving e, int x, int y, int z, float range, boolean openDoors, boolean closedDoors, boolean avoidsWater, boolean canSwim) {
        return ON ? FastPath.entityPathToXYZ(w, e, x, y, z, range, openDoors, closedDoors, avoidsWater, canSwim)
                : w.getEntityPathToXYZ(e, x, y, z, range, openDoors, closedDoors, avoidsWater, canSwim);
    }

    static int maps, navigators;

    /** at the environment's start: the living entities' navigators replaced as they join a world */
    static void start() {
        if (ON) MinecraftForge.EVENT_BUS.register(new RlEngine.Joins());
    }

    public static final class Joins {
        @SubscribeEvent
        public void join(EntityJoinWorldEvent e) {
            if (!e.world.isRemote && e.entity instanceof EntityLiving && FastNavigate.install((EntityLiving) e.entity)) navigators++;
        }
    }

    /** each world's chunk provider keeps its loaded chunks in a FastLongMap (the same entries; the game's hash of a chunk key collides) */
    static void install(MinecraftServer s) {
        if (!ON || s == null || s.worldServers == null) return;
        for (WorldServer w : s.worldServers) {
            if (w == null) continue;
            try {
                Field f;
                try { f = ChunkProviderServer.class.getDeclaredField("field_73244_f"); } catch (NoSuchFieldException e) { f = ChunkProviderServer.class.getDeclaredField("id2ChunkMap"); }
                f.setAccessible(true);
                Object old = f.get(w.theChunkProviderServer);
                if (old instanceof FastLongMap) continue;
                f.set(w.theChunkProviderServer, FastLongMap.copyOf((LongHashMap) old));
                maps++;
                Events.log("rl engine: the chunk map of dimension " + w.provider.dimensionId + " now FastLongMap (" + ((LongHashMap) f.get(w.theChunkProviderServer)).getNumHashElements() + " chunks)");
            } catch (Exception e) {
                Events.log("rl engine: the chunk map not replaced: " + e);
            }
        }
    }

    /** for the environment's info: on, the searches, those checked against the game's and how many differed */
    static JsonObject info() {
        JsonObject o = new JsonObject();
        o.addProperty("on", ON);
        o.addProperty("chunk_maps", maps);
        o.addProperty("navigators", navigators);
        o.addProperty("path_searches", FastPath.searches());
        o.addProperty("path_checked", FastPath.checked());
        o.addProperty("path_mismatches", FastPath.mismatches());
        if (FastPath.mismatches() > 0) o.addProperty("path_last_mismatch", FastPath.lastMismatch());
        return o;
    }
}
