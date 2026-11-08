package hardelele.aiguide.client;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import org.lwjgl.input.Keyboard;

/**
 * The crew's keys and words on the client: give yourself to the AI (O), one more agent (=, in the commander at the
 * cursor); the crew's commands in the guide's window (CrewCommand); what the crew says to the player (Crew.note) in
 * the chat, the guide's window or the commander's panel; and, on entering a world, AI Guide's keys that another
 * binding holds too (told, with a free key to take instead: nothing is changed by itself).
 */
public class CrewKeys {
    static KeyBinding self, add;
    static CrewKeys instance;
    final Minecraft mc = Minecraft.getMinecraft();
    Object checkedWorld;

    static void start() {
        self = new KeyBinding("key.aiguide.selfai", Keyboard.KEY_O, "key.categories.aiguide");
        add = new KeyBinding("key.aiguide.addagent", Keyboard.KEY_EQUALS, "key.categories.aiguide");
        ClientRegistry.registerKeyBinding(self);
        ClientRegistry.registerKeyBinding(add);
        instance = new CrewKeys();
        FMLCommonHandler.instance().bus().register(instance);
    }

    static String tr(String key, Object... a) { return I18n.format("aiguide.crew." + key, a); }

    /** the crew is there: a singleplayer world (the integrated server runs it) */
    boolean crewHere() {
        return mc.isSingleplayer() && mc.getIntegratedServer() != null;
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (mc.theWorld == null || mc.thePlayer == null) { checkedWorld = null; return; }
        if (checkedWorld != mc.theWorld) { checkedWorld = mc.theWorld; conflicts(); }
        if (mc.currentScreen == null) {   // in the game itself (the commander takes its keys in its own screen)
            if (self.isPressed()) toggleSelf();
            if (add.isPressed()) addAgents(1, null);
        }
        for (String[] n; (n = Crew.notes.poll()) != null; ) {
            if (n[0].equals("stall") && mc.currentScreen instanceof Commander.Screen) continue;   // the commander shows the plan's state itself, as long as it lasts
            Object[] args = new Object[n.length - 1];
            for (int i = 1; i < n.length; i++) args[i - 1] = words(n[i]);
            String text = tr(n[0], args);
            boolean warn = WARN.contains(n[0]) || n[0].startsWith("remove.");
            if (n[0].startsWith("waves.") && mc.currentScreen instanceof Commander.Screen && Commander.instance != null)   // the waves' own icon on the commander's messages
                Commander.instance.toasts.add(text, warn ? UiToasts.Kind.WARN : UiToasts.Kind.INFO, UiIcon.WAVES, System.currentTimeMillis());
            else say(text, warn);
        }
    }

    /** a note's argument: "@key,key" is words of the lang files (the server sends keys, the client says them), else as it is */
    static String words(String a) {
        if (a == null || !a.startsWith("@")) return a;
        StringBuilder s = new StringBuilder();
        for (String k : a.substring(1).split(",")) s.append(s.length() > 0 ? ", " : "").append(I18n.format(k));
        return s.toString();
    }

    /** the notes said as warnings (yellow, longer) on the commander's screen */
    static final java.util.Set<String> WARN = new java.util.HashSet<String>(java.util.Arrays.asList("full", "nospot", "stall", "nocrew", "mind.none", "mind.self", "mind.gaveup", "self.danger", "self.cannot",
            "waves.start", "waves.boss", "waves.loss", "waves.over"));

    void say(String text) { say(text, false); }

    /** a word to the player: a message on the commander's screen when it is open, in the guide's window when that is, else in the chat */
    void say(String text, boolean warn) {
        if (mc.currentScreen instanceof Commander.Screen) ((Commander.Screen) mc.currentScreen).toast(text, warn);
        else if (mc.currentScreen instanceof GuiGuide) GuiGuide.add(null, "§7" + text);
        else if (mc.thePlayer != null) mc.thePlayer.addChatMessage(new ChatComponentText("§b[" + tr("tag") + "]§r " + text));
    }

    void toggleSelf() {
        if (!crewHere() || !Crew.PLAYER_ONE) { say(tr("nocrew")); return; }
        Crew.ensure();
        PlayerAgent.givenToAI = !PlayerAgent.givenToAI;
        say(PlayerAgent.givenToAI ? tr("self.given", keyName(self)) : tr("self.back"));
        Events.log("crew: the player " + (PlayerAgent.givenToAI ? "gives himself to the AI" : "takes his controls back"));
    }

    void addAgents(int n, double[] at) {
        if (!crewHere()) { say(tr("nocrew")); return; }
        Crew.add(n, at);
    }

    /** a line of the guide's window that is the crew's command (true: done here, not asked) */
    boolean command(String line, int[] picked) {
        CrewCommand c = CrewCommand.parse(line);
        if (c == null) return false;
        if (c.kind == CrewCommand.USAGE) { say(tr("usage")); return true; }
        if (!crewHere()) { say(tr("nocrew")); return true; }
        if (c.kind == CrewCommand.ADD) addAgents(c.n, null);
        else if (c.n > 0) Crew.dismiss(new int[] { c.n });
        else if (picked != null && picked.length > 0) Crew.dismiss(picked);
        else say(tr("remove.which"));
        return true;
    }

    /** a line of the guide's window that is the waves mode's command (true: done here, not asked) */
    boolean waves(String line) {
        WaveCommand c = WaveCommand.parse(line);
        if (c == null) return false;
        if (c.kind == WaveCommand.USAGE) { say(tr("waves.usage")); return true; }
        if (!crewHere()) { say(tr("nocrew")); return true; }
        Waves.command(c);
        return true;
    }

    static String keyName(KeyBinding k) {
        return k == null || k.getKeyCode() == 0 ? "-" : k.getKeyCode() < 0 ? "Mouse " + (k.getKeyCode() + 101) : Keyboard.getKeyName(k.getKeyCode());
    }

    /** AI Guide's keys (the guide's window, the commander, the crew's) that another binding of the game or a mod holds too */
    void conflicts() {
        List<KeyBinding> ours = new ArrayList<KeyBinding>();
        if (Pilot.window != null) ours.add(Pilot.window);
        if (Commander.instance != null) ours.add(Commander.instance.key);
        if (self != null) ours.add(self);
        if (add != null) ours.add(add);
        KeyBinding[] all = mc.gameSettings.keyBindings;
        List<Integer> offered = new ArrayList<Integer>();
        for (KeyBinding k : ours) {
            List<KeyBinding> other = clashes(k, all, ours);
            if (other.isEmpty()) continue;
            KeyBinding o = other.get(0);
            int free = freeKey(all, offered);
            offered.add(free);
            String msg = tr("key.conflict", keyName(k), I18n.format(k.getKeyDescription()), I18n.format(o.getKeyCategory()), I18n.format(o.getKeyDescription()),
                    free == 0 ? "-" : Keyboard.getKeyName(free));
            say(msg);
            Events.log("keys: " + k.getKeyDescription() + " on " + keyName(k) + " is held by " + o.getKeyDescription() + " (" + o.getKeyCategory() + ")" + (free == 0 ? "" : "; free: " + Keyboard.getKeyName(free)));
        }
    }

    /** the bindings, not AI Guide's own, on the same key as this one (none for an unbound key) */
    static List<KeyBinding> clashes(KeyBinding k, KeyBinding[] all, List<KeyBinding> ours) {
        List<KeyBinding> out = new ArrayList<KeyBinding>();
        if (k.getKeyCode() == 0) return out;
        for (KeyBinding o : all) if (o != k && !ours.contains(o) && o.getKeyCode() == k.getKeyCode()) out.add(o);
        return out;
    }

    /** a key no binding has, from a few that are easy to reach (0 when all are taken) */
    static int freeKey(KeyBinding[] all, List<Integer> offered) {
        int[] tryKeys = { Keyboard.KEY_H, Keyboard.KEY_J, Keyboard.KEY_U, Keyboard.KEY_Y, Keyboard.KEY_N, Keyboard.KEY_K, Keyboard.KEY_V, Keyboard.KEY_B, Keyboard.KEY_Z,
                Keyboard.KEY_X, Keyboard.KEY_I, Keyboard.KEY_P, Keyboard.KEY_F6, Keyboard.KEY_F8, Keyboard.KEY_F9, Keyboard.KEY_F10, Keyboard.KEY_INSERT, Keyboard.KEY_HOME };
        for (int c : tryKeys) {
            boolean used = offered.contains(c);
            for (KeyBinding o : all) if (o.getKeyCode() == c) used = true;
            if (!used) return c;
        }
        return 0;
    }
}
