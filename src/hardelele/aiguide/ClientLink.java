package hardelele.aiguide;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.network.FMLEventChannel;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import io.netty.buffer.Unpooled;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * The line to the client mod (AI Guide Client), for the players who have it: the autopilot that walks
 * the player's own character and does what the guide asks, and the see-through blocks of a plan.
 * JSON messages on the channel "aiguide". Players without the client mod get the sparks instead.
 */
public class ClientLink {
    static final String CHANNEL = "aiguide";
    static final Charset UTF8 = Charset.forName("UTF-8");

    final AIGuide mod;
    FMLEventChannel channel;
    /** players whose client answered the hello, with its version */
    final Map<String, Integer> pilots = new ConcurrentHashMap<String, Integer>();

    ClientLink(AIGuide mod) {
        this.mod = mod;
    }

    void start() {
        channel = NetworkRegistry.INSTANCE.newEventDrivenChannel(CHANNEL);
        channel.register(this);
        FMLCommonHandler.instance().bus().register(this);
    }

    boolean has(String player) {
        return pilots.containsKey(player);
    }

    void send(EntityPlayerMP p, JsonObject m) {
        if (channel == null || p == null || p.field_71135_a == null) return;
        channel.sendTo(new FMLProxyPacket(Unpooled.wrappedBuffer(m.toString().getBytes(UTF8)), CHANNEL), p);
    }

    static JsonObject msg(String type) {
        JsonObject m = new JsonObject();
        m.addProperty("t", type);
        return m;
    }

    @SubscribeEvent
    public void login(PlayerEvent.PlayerLoggedInEvent e) {
        pilots.remove(e.player.func_70005_c_());
        JsonObject hello = msg("hello");
        hello.addProperty("v", 2);   // 2: questions from the guide's window
        if (e.player instanceof EntityPlayerMP) send((EntityPlayerMP) e.player, hello);
    }

    @SubscribeEvent
    public void logout(PlayerEvent.PlayerLoggedOutEvent e) {
        pilots.remove(e.player.func_70005_c_());
        windowed.remove(e.player.func_70005_c_());
    }

    /** from a client, on the network thread: handled on the server thread */
    @SubscribeEvent
    public void packet(FMLNetworkEvent.ServerCustomPacketEvent e) {
        final EntityPlayerMP p = ((net.minecraft.network.NetHandlerPlayServer) e.handler).field_147369_b;
        final JsonObject m;
        try {
            m = new JsonParser().parse(e.packet.payload().toString(UTF8)).getAsJsonObject();
        } catch (Exception ex) {
            return;
        }
        mod.mainQueue.add(new Runnable() {
            @Override public void run() {
                received(p, m);
            }
        });
    }

    void received(EntityPlayerMP p, JsonObject m) {
        String t = m.has("t") ? m.get("t").getAsString() : "";
        String name = p.func_70005_c_();
        if (t.equals("hello")) {
            pilots.put(name, m.has("v") ? m.get("v").getAsInt() : 1);
            AIGuide.LOG.info("{} has the client mod (v{})", name, pilots.get(name));
            if (name.equals(System.getProperty(AIGuide.ID + ".pilottest"))) SelfTest.pilot(mod, p);
        } else if (t.equals("ask")) {   // from the guide's window: no 100-character limit of the chat
            String q = m.has("text") ? m.get("text").getAsString().trim() : "";
            if (q.length() > 2000) q = q.substring(0, 2000);
            if (q.isEmpty()) return;
            windowed.add(name);
            Boolean yes = Errands.reply(q);
            if (yes != null && mod.errands.waiting(name)) mod.errands.answer(name, yes);
            else if (!mod.assistant.ask(name, q)) mod.say(name, mod.msg("busy"));
        } else mod.errands.fromClient(name, t, m);
    }

    /** players who talk to the guide in its window: its messages go there and not into the chat */
    final java.util.Set<String> windowed = java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** a guide's message for the window (version 2 of the client mod); true when the chat need not show it */
    boolean window(EntityPlayerMP p, String text) {
        String name = p.func_70005_c_();
        Integer v = pilots.get(name);
        if (v == null || v < 2) return false;
        boolean only = windowed.contains(name);
        JsonObject m = msg("say");
        m.addProperty("text", text);
        m.addProperty("chat", !only);
        send(p, m);
        return only;
    }
}
