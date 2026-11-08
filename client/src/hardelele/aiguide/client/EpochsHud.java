package hardelele.aiguide.client;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.Entity;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import org.lwjgl.opengl.GL11;

/**
 * The epochs of learning on the screen (EpochRun.view, set by Epochs on the integrated server of a singleplayer
 * epoch): a strip under the waves' one, "Эпоха N · рекорд M волн · уроков K" and the karma; over an agent, for a moment,
 * a green or a red mark with the reason when an episode of it was rated (EpochRun.pops); between the epochs a card,
 * "Чему научились": the lessons, big, and the waves by epoch, learning against control. Drawn with UiKit's pieces, the
 * words aiguide.ui.epochs.* (a reason's: aiguide.ui.epochs.why.CODE, a karma's: aiguide.ui.epochs.karma.CODE).
 */
public class EpochsHud {
    static final long POP_MS = 2500, CARD_MS = 25000;
    static EpochsHud instance;
    final Minecraft mc = Minecraft.getMinecraft();
    final UiKit kit = new UiKit();

    static void start() {
        instance = new EpochsHud();
        MinecraftForge.EVENT_BUS.register(instance);
    }

    static String ui(String key, Object... a) { return UiKit.ui(key, a); }

    /** a reason's words ("починил стену до волны"); its code when the lang has none */
    static String why(String code) {
        String k = "aiguide.ui.epochs.why." + code;
        return I18n.format(k).equals(k) ? code : I18n.format(k);
    }

    static String karmaWhy(String code) {
        String k = "aiguide.ui.epochs.karma." + code;
        return I18n.format(k).equals(k) ? code : I18n.format(k);
    }

    /** "1 волна", "3 волны", "5 волн" (the record's: the nominative forms; English: one, others) */
    static String waves(int n) {
        int m10 = n % 10, m100 = n % 100;
        String form = m10 == 1 && m100 != 11 ? "one" : m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14) ? "few" : "many";
        return I18n.format("aiguide.ui.epochs.n." + form, n);
    }

    static boolean shown(EpochRun.View v) {
        return v.on || v.closedMs > 0 && System.currentTimeMillis() - v.closedMs < CARD_MS;
    }

    @SubscribeEvent
    public void overlay(RenderGameOverlayEvent.Post e) {
        if (e.type != RenderGameOverlayEvent.ElementType.ALL) return;
        if (Commander.instance != null && Commander.instance.on || mc.gameSettings.showDebugInfo) return;
        EpochRun.View v = EpochRun.view;
        if (!shown(v)) return;
        int w = e.resolution.getScaledWidth(), h = e.resolution.getScaledHeight();
        kit.begin(mc.fontRendererObj, w, h, -1, -1);
        int sw = Math.min(UiLayout.TOAST_MAX, w - 2 * UiLayout.M);
        strip(kit, new UiLayout.Box("epochs", (w - sw) / 2, WavesHud.shown(Waves.view) ? 20 : 2, sw, 16), v);
        card(kit, w, h, v);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** the strip: the epoch, the best, the lessons; the karma at the right (its worst reason when it is below zero) */
    static void strip(UiKit kit, UiLayout.Box b, EpochRun.View v) {
        if (b == null || !shown(v)) return;
        FontRenderer fr = kit.fr;
        String head = v.track.equals(EpochRun.CONTROL) ? ui("epochs.strip.control", v.epoch, waves(v.best))
                : ui("epochs.strip", v.epoch, waves(v.best), v.lessons);
        String karma = v.karmaWorst != null && v.karma < 0 ? ui("epochs.karma.why", EpochScore.fmt(v.karma), karmaWhy(v.karmaWorst))
                : v.epoch > 1 || v.karma != 0 ? ui("epochs.karma", EpochScore.fmt(v.karma)) : "";
        Ui.round(b.x, b.y, b.x1(), b.y1(), 5, v.track.equals(EpochRun.CONTROL) ? 0xE0303840 : 0xE0183448);
        kit.icon(UiIcon.EPOCH, b.x + 4, b.y + 4, 8, 1f);
        int room = b.w - 19;
        String full = karma.isEmpty() ? head : head + " · " + karma;
        String text = fr.getStringWidth(full) <= room ? full : fr.trimStringToWidth(head, room);
        fr.drawStringWithShadow(text, b.x + 15, b.y + 4, UiKit.TEXT);
    }

    /**
     * Between the epochs (after the waves' own card): "Эпоха N: продержались M волн", "Чему научились" with up to three
     * lessons (one and a half times the size), the karma, and the waves by epoch: learning's bars beside control's.
     */
    static void card(UiKit kit, int w, int h, EpochRun.View v) {
        long now = System.currentTimeMillis();
        if (v.on || v.closedMs <= 0 || now - v.closedMs >= CARD_MS) return;
        if (Waves.view.phase == WaveCycle.Phase.OVER && now - Waves.view.endedMs < WavesHud.CARD_MS) return;   // the waves' card first
        FontRenderer fr = kit.fr;
        int cw = Math.min(w - 2 * UiLayout.M, 340), x = (w - cw) / 2;
        List<String> top = v.top;
        int lessonsH = 0;
        for (String l : top) lessonsH += 15 * Math.max(1, fr.listFormattedStringToWidth(l, (int) ((cw - 30) / 1.5)).size()) + 3;
        if (top.isEmpty()) lessonsH = 14;
        int chartH = 64, ch = 30 + 14 + lessonsH + 14 + chartH + 16, y = Math.max(UiLayout.M, (h - ch) / 2);
        GL11.glPushMatrix();
        GL11.glTranslatef(0, 0, 200);
        Ui.round(x, y, x + cw, y + ch, 8, 0xF0101820);
        Ui.round(x, y, x + cw, y + 3, 0, 0xFF50A0E0);
        kit.icon(UiIcon.EPOCH, x + 8, y + 8, 16, 1f);
        String title = ui(v.track.equals(EpochRun.CONTROL) ? "epochs.card.title.control" : "epochs.card.title", v.epoch, WavesHud.wavesWord(v.cleared));
        fr.drawStringWithShadow(fr.trimStringToWidth(title, cw - 40), x + 30, y + 12, 0xFFE08A);
        int yy = y + 30;
        fr.drawStringWithShadow(ui("epochs.card.learned"), x + 10, yy, UiKit.TEXT_DIM);
        yy += 14;
        if (top.isEmpty()) { fr.drawStringWithShadow(ui(v.track.equals(EpochRun.CONTROL) ? "epochs.card.control" : "epochs.card.none"), x + 14, yy, UiKit.TEXT); yy += 14; }
        for (String l : top) {
            kit.icon(UiIcon.GOOD, x + 10, yy + 2, 8, 1f);
            GL11.glPushMatrix();
            GL11.glTranslatef(x + 22, yy, 0);
            GL11.glScalef(1.5f, 1.5f, 1f);
            int n = 0;
            for (Object o : fr.listFormattedStringToWidth(l, (int) ((cw - 30) / 1.5))) fr.drawStringWithShadow((String) o, 0, 10 * n++, UiKit.TEXT);
            GL11.glPopMatrix();
            yy += 15 * Math.max(1, n) + 3;
        }
        String karma = v.karmaWorst != null && v.karma < 0 ? ui("epochs.karma.why", EpochScore.fmt(v.karma), karmaWhy(v.karmaWorst)) : ui("epochs.karma", EpochScore.fmt(v.karma));
        fr.drawStringWithShadow(fr.trimStringToWidth(karma, cw - 20), x + 10, yy, v.karma < 0 ? 0xFF9A9A : 0x9AFFB0);
        yy += 14;
        chart(kit, x + 10, yy, cw - 20, chartH, v.learn, v.control);
        GL11.glPopMatrix();
    }

    /** the waves cleared by epoch: learning (green) and control (grey) side by side, the axis from 0 to the most */
    static void chart(UiKit kit, int x, int y, int w, int h, List<Integer> learn, List<Integer> control) {
        FontRenderer fr = kit.fr;
        fr.drawStringWithShadow(ui("epochs.card.chart"), x, y, UiKit.TEXT_DIM);
        String lg = ui("epochs.card.learn"), cg = ui("epochs.card.control.legend");
        int lx = x + w - fr.getStringWidth(lg) - fr.getStringWidth(cg) - 34;
        Ui.round(lx, y + 1, lx + 7, y + 8, 1, 0xFF3987E5);
        fr.drawStringWithShadow(lg, lx + 10, y, UiKit.TEXT);
        int cx = lx + 10 + fr.getStringWidth(lg) + 8;
        Ui.round(cx, y + 1, cx + 7, y + 8, 1, 0xFFD95926);
        fr.drawStringWithShadow(cg, cx + 10, y, UiKit.TEXT);
        int n = Math.max(learn.size(), control.size());
        if (n == 0) return;
        int most = 1;
        for (int v : learn) most = Math.max(most, v);
        for (int v : control) most = Math.max(most, v);
        int top = y + 12, base = y + h - 2, slot = Math.max(4, w / n), bw = Math.max(1, (slot - 2) / 2);
        Ui.round(x, base, x + w, base + 1, 0, 0xFF405060);
        for (int i = 0; i < n; i++) {
            int sx = x + i * slot;
            if (sx + slot > x + w + 1) break;
            if (i < learn.size()) { int bh = (base - top) * learn.get(i) / most; Ui.round(sx, base - bh, sx + bw, base, 0, 0xFF3987E5); }
            if (i < control.size()) { int bh = (base - top) * control.get(i) / most; Ui.round(sx + bw, base - bh, sx + 2 * bw, base, 0, 0xFFD95926); }
        }
    }

    /** over an agent, for a moment: the green or red mark of an episode just rated, and its reason */
    @SubscribeEvent
    public void world(RenderWorldLastEvent e) {
        if (mc.theWorld == null || EpochRun.pops.isEmpty()) return;
        long now = System.currentTimeMillis();
        java.util.Map<Integer, Integer> stack = new java.util.HashMap<Integer, Integer>();
        kit.fr = mc.fontRendererObj;
        for (EpochRun.Pop p : EpochRun.pops) {
            long age = now - p.ms;
            if (age < 0 || age > POP_MS) continue;
            Entity en = mc.theWorld.getEntityByID(p.entity);
            if (en == null) continue;
            int k = stack.containsKey(p.entity) ? stack.get(p.entity) + 1 : 0;
            stack.put(p.entity, k);
            float t = e.partialTicks, rise = age / (float) POP_MS;
            double x = en.lastTickPosX + (en.posX - en.lastTickPosX) * t - RenderManager.renderPosX;
            double y = en.lastTickPosY + (en.posY - en.lastTickPosY) * t - RenderManager.renderPosY + en.height + 0.9 + rise * 0.5 + k * 0.45;
            double z = en.lastTickPosZ + (en.posZ - en.lastTickPosZ) * t - RenderManager.renderPosZ;
            float alpha = Math.min(1f, (POP_MS - age) / 600f);
            GL11.glPushMatrix();
            GL11.glTranslated(x, y, z);
            GL11.glRotatef(-RenderManager.instance.playerViewY, 0, 1, 0);
            GL11.glRotatef(RenderManager.instance.playerViewX, 1, 0, 0);
            GL11.glScalef(-0.03f, -0.03f, 0.03f);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            kit.icon(p.rating > 0 ? UiIcon.GOOD : UiIcon.BAD, -8, -8, 16, alpha);
            String s = why(p.why);
            int a = Math.max(8, (int) (alpha * 255)) << 24;
            kit.fr.drawStringWithShadow(s, -kit.fr.getStringWidth(s) / 2, 10, a | (p.rating > 0 ? 0x9AFFB0 : 0xFF9A9A));
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            GL11.glEnable(GL11.GL_LIGHTING);
            GL11.glColor4f(1f, 1f, 1f, 1f);
            GL11.glPopMatrix();
        }
    }
}
