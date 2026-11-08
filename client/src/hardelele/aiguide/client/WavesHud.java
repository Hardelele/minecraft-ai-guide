package hardelele.aiguide.client;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import org.lwjgl.opengl.GL11;

/**
 * The waves mode on the screen (Waves.view, read from the integrated server): a strip with the wave, the enemies left,
 * the time to the next one and the score (in the commander under its top bar, UiLayout's "waves" box; in the game at
 * the top, compact); a banner when a wave begins; the run's card when it is over ("Продержались N волн": the waves,
 * the kills by agent, the losses, the time, the best). Drawn with UiKit's pieces, the words aiguide.ui.waves.*.
 */
public class WavesHud {
    static final long BANNER_MS = 3500, CARD_MS = 15000;
    static WavesHud instance;
    final Minecraft mc = Minecraft.getMinecraft();
    final UiKit kit = new UiKit();

    static void start() {
        instance = new WavesHud();
        MinecraftForge.EVENT_BUS.register(instance);
    }

    /** the strip and the card are shown: a run on, or one just over */
    static boolean shown(WaveCycle.View v) {
        return v.active() || v.phase == WaveCycle.Phase.OVER && System.currentTimeMillis() - v.endedMs < CARD_MS;
    }

    /** out of the commander: at the top of the game's screen */
    @SubscribeEvent
    public void overlay(RenderGameOverlayEvent.Post e) {
        if (e.type != RenderGameOverlayEvent.ElementType.ALL) return;
        if (Commander.instance != null && Commander.instance.on || mc.gameSettings.showDebugInfo) return;
        WaveCycle.View v = Waves.view;
        if (!shown(v)) return;
        int w = e.resolution.getScaledWidth(), h = e.resolution.getScaledHeight();
        kit.begin(mc.fontRendererObj, w, h, -1, -1);
        int sw = Math.min(UiLayout.TOAST_MAX, w - 2 * UiLayout.M);
        strip(kit, new UiLayout.Box("waves", (w - sw) / 2, 2, sw, 16), v);
        overlays(kit, w, h);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    static String ui(String key, Object... a) { return UiKit.ui(key, a); }

    /** "1 волну", "3 волны", "5 волн" (the Russian forms; English: one, others) */
    static String wavesWord(int n) {
        int m10 = n % 10, m100 = n % 100;
        String form = m10 == 1 && m100 != 11 ? "one" : m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14) ? "few" : "many";
        return I18n.format("aiguide.ui.waves.n." + form, n);
    }

    static String level(String lv) { return I18n.format("aiguide.crew.waves.level." + lv); }

    /** the strip: the wave, the enemies, the time to the next, the score; the shorter words when the box is narrow */
    static void strip(UiKit kit, UiLayout.Box b, WaveCycle.View v) {
        if (b == null || !shown(v)) return;
        FontRenderer fr = kit.fr;
        String full, small;
        int bg;
        if (v.phase == WaveCycle.Phase.OVER) {
            full = small = ui("waves.over", wavesWord(v.cleared));
            bg = UiKit.PANEL_HI;
        } else if (v.phase == WaveCycle.Phase.PREP) {
            full = ui("waves.prep", level(v.level), v.secondsToNext);
            small = ui("waves.prep.short", v.secondsToNext);
            bg = 0xE0183448;
        } else if (v.phase == WaveCycle.Phase.BREAK) {
            full = ui("waves.break", v.wave + 1, v.secondsToNext, v.cleared, v.kills);
            small = ui("waves.break.short", v.wave + 1, v.secondsToNext);
            bg = 0xE0183448;
        } else {
            full = ui("waves.wave", v.wave, v.enemies(), v.kills, v.losses);
            small = ui("waves.wave.short", v.wave, v.enemies());
            bg = WavePlan.boss(v.wave) ? 0xE0602060 : 0xE0602020;
        }
        if (v.best > 0 && v.phase != WaveCycle.Phase.OVER) full += " · " + ui("waves.best", v.best);
        if (v.paused) { full = ui("waves.paused", full); small = ui("waves.paused", small); bg = 0xE0404040; }
        Ui.round(b.x, b.y, b.x1(), b.y1(), 5, bg);
        kit.icon(UiIcon.WAVES, b.x + 4, b.y + 4, 8, 1f);
        int room = b.w - 19;
        String text = fr.getStringWidth(full) <= room ? full : fr.trimStringToWidth(small, room);
        fr.drawStringWithShadow(text, b.x + 15, b.y + 4, UiKit.TEXT);
    }

    /** the banner of a wave just begun, and the run's card when it is over (both centred, over the world) */
    static void overlays(UiKit kit, int w, int h) {
        WaveCycle.View v = Waves.view;
        long now = System.currentTimeMillis();
        FontRenderer fr = kit.fr;
        if (v.phase == WaveCycle.Phase.WAVE && now - v.waveStartedMs < BANNER_MS) {
            String t = ui(WavePlan.boss(v.wave) ? "waves.banner.boss" : "waves.banner", v.wave);
            float a = Math.min(1f, (BANNER_MS - (now - v.waveStartedMs)) / 700f);
            int color = (Math.max(8, (int) (a * 255)) << 24) | (WavePlan.boss(v.wave) ? 0xFF80FF : 0xFF5050);
            GL11.glPushMatrix();
            GL11.glTranslatef(w / 2f, h / 3f, 0);
            GL11.glScalef(3f, 3f, 1f);
            GL11.glEnable(GL11.GL_BLEND);
            fr.drawStringWithShadow(t, -fr.getStringWidth(t) / 2, -4, color);
            GL11.glPopMatrix();
            String s = ui("waves.banner.sub", v.enemies());
            fr.drawStringWithShadow(s, w / 2 - fr.getStringWidth(s) / 2, h / 3 + 12, (Math.max(8, (int) (a * 255)) << 24) | 0xFFFFFF);
        }
        if (v.phase == WaveCycle.Phase.OVER && now - v.endedMs < CARD_MS) card(kit, w, h, v);
    }

    /** "Продержались N волн": the run's card */
    static void card(UiKit kit, int w, int h, WaveCycle.View v) {
        FontRenderer fr = kit.fr;
        java.util.List<String> lines = new java.util.ArrayList<String>();
        lines.add("lost".equals(v.reason) ? ui("waves.card.lost", v.wave) : "stop".equals(v.reason) ? ui("waves.card.stop") : ui("waves.card.end"));
        lines.add(ui("waves.card.kills", v.kills) + "   " + ui("waves.card.losses", v.losses));
        lines.add(ui("waves.card.time", v.seconds / 60, String.format("%02d", v.seconds % 60)) + "   " + ui("waves.card.best", Math.max(v.best, v.cleared)));
        int shown = 0;
        for (String[] k : v.killers) {
            if (k[0].startsWith("world:") || k[0].equals("mob") || shown++ >= 5) continue;
            lines.add(ui("waves.card.killer", k[0], k[1]));
        }
        String title = ui("waves.card.title", wavesWord(v.cleared));
        int cw = Math.min(w - 2 * UiLayout.M, Math.max(200, fr.getStringWidth(title) * 2 + 24));
        for (String l : lines) cw = Math.min(w - 2 * UiLayout.M, Math.max(cw, fr.getStringWidth(l) + 24));
        int ch = 34 + lines.size() * 11 + 6, x = (w - cw) / 2, y = Math.max(UiLayout.M, (h - ch) / 2 - h / 10);
        GL11.glPushMatrix();
        GL11.glTranslatef(0, 0, 200);
        Ui.round(x, y, x + cw, y + ch, 8, 0xF0101820);
        Ui.round(x, y, x + cw, y + 3, 0, "lost".equals(v.reason) ? 0xFFE04040 : 0xFF50E070);
        kit.icon(UiIcon.WAVES, x + 8, y + 9, 16, 1f);
        GL11.glPushMatrix();
        GL11.glTranslatef(x + 30, y + 10, 0);
        GL11.glScalef(2f, 2f, 1f);
        fr.drawStringWithShadow(fr.trimStringToWidth(title, (cw - 38) / 2), 0, 0, 0xFFE08A);
        GL11.glPopMatrix();
        for (int i = 0; i < lines.size(); i++)
            fr.drawStringWithShadow(fr.trimStringToWidth(lines.get(i), cw - 16), x + 10, y + 34 + i * 11, i == 0 ? UiKit.TEXT : UiKit.TEXT_DIM);
        GL11.glPopMatrix();
    }
}
