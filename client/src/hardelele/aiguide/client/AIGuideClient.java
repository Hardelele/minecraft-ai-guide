package hardelele.aiguide.client;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.registry.EntityRegistry;
import cpw.mods.fml.relauncher.Side;

/**
 * AI Guide Client: what the AI Guide server mod cannot do from the server. Its autopilot walks the
 * player's own character where the guide leads them, opens chests and the crafting table, places and
 * digs blocks of a plan; the plan itself is drawn as see-through blocks. Any movement key gives the
 * controls back. Without the server mod it does nothing.
 *
 * Video build: also the guide's crew (Agent, Crew) for singleplayer, the director and the recorder.
 */
@Mod(modid = AIGuideClient.ID, name = "AI Guide Client", version = AIGuideClient.VERSION, acceptableRemoteVersions = "*")
public class AIGuideClient {
    public static final String ID = "aiguideclient", VERSION = "0.2";

    @Mod.Instance(ID)
    public static AIGuideClient instance;

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        EntityRegistry.registerModEntity(Agent.class, "agent", 0, this, 96, 1, true);
        for (int i = 0; i < WaveMobs.TYPES.length; i++)   // the waves mode's mobs (the game's looks: their renderers are found by the class they extend)
            EntityRegistry.registerModEntity((Class<? extends net.minecraft.entity.Entity>) WaveMobs.TYPES[i][0], (String) WaveMobs.TYPES[i][1], 10 + i, this, 80, 3, true);
        Waves.init();
        Exploration.start();
        Places.start();   // an observer: what places the crew has seen (off with -Daiguide.places=false)
        if (e.getSide() != Side.CLIENT) return;   // nothing more to do on a server (the bench starts when the server has)
        ClientSetup.run();   // its own class: a dedicated server never loads the client's classes
    }

    /** a dedicated server started with -Daiguide.bench=...: the headless test bench drives the crew */
    @Mod.EventHandler
    public void serverStarted(cpw.mods.fml.common.event.FMLServerStartedEvent e) {
        if (System.getProperty("aiguide.rl") != null && e.getSide() != Side.CLIENT) RlEnv.start(Integer.parseInt(System.getProperty("aiguide.rl").trim()));   // the learning environment (docs/rl/CONTRACT.md)
        else if (System.getProperty("aiguide.bench") != null) Bench.start();
        else if (e.getSide() == Side.CLIENT) autoRecord();
    }

    /** a singleplayer game is recorded (WorldRec) from the world's start to its end, to be watched and filmed afterwards */
    static final long RECORD_MAX_WORLD = 1L << 30;   // a bigger world is not copied for it
    static boolean recording;

    static void autoRecord() {
        if ("false".equals(System.getProperty("aiguide.record")) || Director.SCRIPT != null) return;   // off, or a script records its own takes
        net.minecraft.server.MinecraftServer s = net.minecraft.server.MinecraftServer.getServer();
        if (s == null || s.worldServers == null || s.worldServers.length == 0) return;
        long size = WorldRec.size(s.worldServers[0].getSaveHandler().getWorldDirectory());
        if (size > RECORD_MAX_WORLD) {
            Events.log("worldrec: the game is not recorded: the world is " + size / 1024 / 1024 + " MB");
            return;
        }
        String name = "play-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
        recording = "ok".equals(WorldRec.start(name));
    }

    @Mod.EventHandler
    public void serverStopping(cpw.mods.fml.common.event.FMLServerStoppingEvent e) {
        Waves.serverStopping();   // a run on is written before the world goes
        if (recording) {
            recording = false;
            WorldRec.stop();
        }
    }
}
