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
        if (e.getSide() != Side.CLIENT) return;   // nothing more to do on a server
        cpw.mods.fml.client.registry.RenderingRegistry.registerEntityRenderingHandler(Agent.class, new RenderAgent());
        Pilot.start();
    }
}
