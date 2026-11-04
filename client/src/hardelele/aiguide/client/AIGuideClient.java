package hardelele.aiguide.client;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.relauncher.Side;

/**
 * AI Guide Client: what the AI Guide server mod cannot do from the server. Its autopilot walks the
 * player's own character where the guide leads them, opens chests and the crafting table, places and
 * digs blocks of a plan; the plan itself is drawn as see-through blocks. Any movement key gives the
 * controls back. Without the server mod it does nothing.
 */
@Mod(modid = AIGuideClient.ID, name = "AI Guide Client", version = AIGuideClient.VERSION, acceptableRemoteVersions = "*")
public class AIGuideClient {
    public static final String ID = "aiguideclient", VERSION = "0.2";

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        if (e.getSide() != Side.CLIENT) return;   // nothing to do on a server
        Pilot.start();
    }
}
