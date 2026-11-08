package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

/**
 * The commander's pieces drawn in its flat look (Ui.round: rounded, see-through, no game buttons): a panel, an icon
 * button with its tooltip and key, a tooltip, an agent's card frame, a message. Where they go is UiLayout's; the words
 * are aiguide.ui.* in the lang files. A screen calls begin() each frame, draws, then asks hit() what a click was on.
 */
final class UiKit {
    static final ResourceLocation ICONS = new ResourceLocation("aiguideclient", "textures/gui/icons.png");

    // the colours of the commander's panels (as Commander and FeedbackUi have them)
    static final int PANEL = 0xD0101820, PANEL_HI = 0xF015222E, BUTTON = 0xC0283038, BUTTON_OVER = 0xE0405060, BUTTON_ON = 0xF0407A50,
            BUTTON_OFF = 0x80202830, TIP = 0xF0101418, TEXT = 0xFFFFFF, TEXT_DIM = 0x9AA8B8, KEY = 0xFFE08A,
            WARN = 0xE0503A10, WARN_TEXT = 0xFFE09A, BAD = 0xE0502020, GOOD = 0xE0204030;

    final Minecraft mc = Minecraft.getMinecraft();
    FontRenderer fr;
    int scale = 1, w, h, mx, my;
    final List<Object[]> hits = new ArrayList<Object[]>();   // id, x0, y0, x1, y1
    String tipText, tipKey;                                   // the tooltip of what the mouse is over (drawn last, over all)
    int[] tipAnchor;

    static String ui(String key, Object... a) { return I18n.format("aiguide.ui." + key, a); }

    /** a new frame: the screen's size and scale, the mouse (GUI units) */
    void begin(FontRenderer fr, int w, int h, int mx, int my) {
        this.fr = fr; this.w = w; this.h = h; this.mx = mx; this.my = my;
        scale = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
        hits.clear();
        tipText = null;
    }

    boolean over(int x, int y, int bw, int bh) { return mx >= x && mx < x + bw && my >= y && my < y + bh; }

    /** what a click at x, y was on (the last drawn on top), or null */
    String hit(int x, int y) {
        String id = null;
        for (Object[] o : hits) if (x >= (Integer) o[1] && x < (Integer) o[3] && y >= (Integer) o[2] && y < (Integer) o[4]) id = (String) o[0];
        return id;
    }

    // ---- pieces --------------------------------------------------------------------------------------

    /**
     * a word button of a dialog (the scenarios, the ratings, the share): 15 high, lit when chosen; its hit {id, x0, y0,
     * x1, y1} goes into the dialog's list. Returns where the next one goes (gap after it).
     */
    static int textButton(FontRenderer fr, List<Object[]> hits, String title, String id, int x, int y, int mx, int my, boolean lit, int gap) {
        int w = fr.getStringWidth(title) + 12;
        boolean over = mx >= x && mx < x + w && my >= y && my < y + 15;
        Ui.round(x, y, x + w, y + 15, 5, lit ? BUTTON_ON : over ? 0xFF4B6172 : 0xEE2B3B48);
        fr.drawStringWithShadow(title, x + 6, y + 4, TEXT);
        hits.add(new Object[] { id, x, y, x + w, y + 15 });
        return x + w + gap;
    }

    /** a dialog's close: the cross at its top right corner (14 square), its hit "id" in the dialog's list */
    void closeCross(List<Object[]> dialogHits, String id, int right, int top) {
        int x = right - 18, y = top + 4;
        boolean over = over(x, y, 14, 14);
        Ui.round(x, y, x + 14, y + 14, 3, over ? BUTTON_OVER : BUTTON);
        icon(UiIcon.CLOSE, x + 3, y + 3, 8, 1f);
        dialogHits.add(new Object[] { id, x, y, x + 14, y + 14 });
        if (over) tip(I18n.format(UiIcon.CLOSE.words()), UiIcon.CLOSE.key, x, y, 14, 14);
    }

    void panel(UiLayout.Box b) { panel(b.x, b.y, b.w, b.h, PANEL); }

    void panel(int x, int y, int bw, int bh, int argb) { Ui.round(x, y, x + bw, y + bh, 6, argb); }

    /** an icon from the atlas at x, y, size GUI units (16 a button's), see-through by alpha (0..1) */
    void icon(UiIcon icon, int x, int y, int size, float alpha) {
        boolean large = UiIcon.large(scale);
        int[] c = icon.cell(large);
        mc.getTextureManager().bindTexture(ICONS);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_CULL_FACE);   // (as Ui.round: the quads' winding does not matter)
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1f, 1f, 1f, alpha);
        float u0 = c[0] / (float) UiIcon.ATLAS, v0 = c[1] / (float) UiIcon.ATLAS, u1 = (c[0] + c[2]) / (float) UiIcon.ATLAS, v1 = (c[1] + c[2]) / (float) UiIcon.ATLAS;
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.addVertexWithUV(x, y + size, 0, u0, v1);
        t.addVertexWithUV(x + size, y + size, 0, u1, v1);
        t.addVertexWithUV(x + size, y, 0, u1, v0);
        t.addVertexWithUV(x, y, 0, u0, v0);
        t.draw();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /**
     * an icon button (UiLayout.BTN square): lit when on, dimmed when it cannot be used now (the reason in its tooltip);
     * the tooltip (its words and its key) when the mouse is over it. Returns whether the mouse is over it.
     */
    boolean button(String id, UiIcon icon, int x, int y, boolean on, boolean enabled, String key, String why) {
        int s = UiLayout.BTN;
        boolean over = over(x, y, s, s);
        Ui.round(x, y, x + s, y + s, 5, !enabled ? BUTTON_OFF : on ? BUTTON_ON : over ? BUTTON_OVER : BUTTON);
        icon(icon, x + 2, y + 2, 16, enabled ? 1f : 0.45f);
        if (on) Ui.round(x + 4, y + s - 2, x + s - 4, y + s - 1, 0, 0xFFB9FFCE);   // a bar under a toggle that is on
        hits.add(new Object[] { id, x, y, x + s, y + s });
        if (over) tip(I18n.format(icon.words()) + (enabled || why == null ? "" : " - " + why), key == null ? icon.key : key, x, y, s, s);
        return over;
    }

    /** the tooltip of what the mouse is over: drawn by flush(), over everything */
    void tip(String text, String key, int ax, int ay, int aw, int ah) {
        tipText = text; tipKey = key == null || key.isEmpty() ? null : key;
        tipAnchor = new int[] { ax, ay, aw, ah };
    }

    /** the frame's last part: the tooltip (the words, then the key in a box) */
    void flush() {
        if (tipText == null || fr == null) return;
        int kw = tipKey == null ? 0 : fr.getStringWidth(tipKey) + 8;
        int tw = Math.min(w - 2 * UiLayout.M - 8, fr.getStringWidth(tipText) + (kw > 0 ? kw + 6 : 0)), th = 10;
        int[] at = UiLayout.tooltip(tipAnchor[0], tipAnchor[1], tipAnchor[2], tipAnchor[3], tw + 8, th + 6, w, h);
        GL11.glPushMatrix();
        GL11.glTranslatef(0, 0, 300);
        Ui.round(at[0], at[1], at[0] + tw + 8, at[1] + th + 6, 3, TIP);
        String text = fr.trimStringToWidth(tipText, tw - (kw > 0 ? kw + 6 : 0));
        fr.drawStringWithShadow(text, at[0] + 4, at[1] + 4, TEXT);
        if (kw > 0) {
            int kx = at[0] + 4 + fr.getStringWidth(text) + 6;
            Ui.round(kx, at[1] + 2, kx + kw, at[1] + th + 4, 2, 0xFF3A4450);
            fr.drawStringWithShadow(tipKey, kx + 4, at[1] + 4, KEY);
        }
        GL11.glPopMatrix();
    }

    /** a row of a layout's buttons (ids by prefix, "more" opens its menu); state: which are on, which cannot be used */
    void buttons(UiLayout l, String prefix, java.util.Set<UiIcon> on, java.util.Map<UiIcon, String> off) {
        for (UiLayout.Box b : l.starting(prefix)) {
            String name = b.id.substring(prefix.length());
            UiIcon icon = name.equals("more") ? UiIcon.MORE : UiIcon.valueOf(name.toUpperCase(java.util.Locale.ROOT));
            boolean enabled = off == null || !off.containsKey(icon);
            button(b.id, icon, b.x, b.y, on != null && on.contains(icon), enabled, null, enabled ? null : off.get(icon));
        }
    }

    /** an agent's chip: its number in a badge, a health bar, what it does as an icon (think: the agent is choosing) */
    void chip(UiLayout.Box b, String id, int number, float health, UiIcon doing, boolean picked, boolean player, boolean away) {
        Ui.round(b.x, b.y, b.x1(), b.y1(), 4, picked ? 0xE02A5C4A : away ? 0x90101820 : PANEL);
        Ui.round(b.x + 2, b.y + 2, b.x + 13, b.y + 13, 3, player ? 0xFF2F6FA8 : 0xFF3A4450);
        String n = String.valueOf(number);
        fr.drawStringWithShadow(n, b.x + 8 - fr.getStringWidth(n) / 2, b.y + 4, away ? TEXT_DIM : TEXT);
        if (doing != null && b.w >= UiLayout.CHIP_W) icon(doing, b.x + 16, b.y + 1, 16, away ? 0.5f : 1f);
        int bw = b.w - 4;
        Ui.round(b.x + 2, b.y + 16, b.x + 2 + bw, b.y + 18, 0, 0xFF202020);
        Ui.round(b.x + 2, b.y + 16, b.x + 2 + Math.round(bw * Math.max(0, Math.min(1, health))), b.y + 18, 0,
                health > 0.5f ? 0xFF50E070 : health > 0.25f ? 0xFFE0C040 : 0xFFE04040);
        hits.add(new Object[] { id, b.x, b.y, b.x1(), b.y1() });
    }

    /** the card's frame and head: number badge, name, health, and "thinking..." when the agent is choosing its step */
    void cardHead(UiLayout.Box card, UiLayout.Box head, int number, String name, float health, boolean thinking) {
        cardHead(card, head, number, name, health, thinking, null, null, 0);
    }

    /** the same; after: what it does, written after the health (a card with no room for its lines); right: room kept at the right */
    void cardHead(UiLayout.Box card, UiLayout.Box head, int number, String name, float health, boolean thinking, UiIcon afterIcon, String after, int right) {
        Ui.round(card.x, card.y, card.x1(), card.y1(), 7, PANEL);
        Ui.round(head.x, head.y, head.x + 11, head.y + 11, 3, 0xFF3A4450);
        String n = String.valueOf(number);
        fr.drawStringWithShadow(n, head.x + 6 - fr.getStringWidth(n) / 2, head.y + 2, TEXT);
        fr.drawStringWithShadow(name, head.x + 15, head.y + 2, TEXT);
        int hx = head.x + 15 + fr.getStringWidth(name) + 6;
        icon(UiIcon.HEALTH, hx, head.y + 1, 8, 1f);   // (a 16 texel icon at half size)
        Ui.round(hx + 10, head.y + 4, hx + 42, head.y + 7, 0, 0xFF202020);
        Ui.round(hx + 10, head.y + 4, hx + 10 + Math.round(32 * Math.max(0, Math.min(1, health))), head.y + 7, 0, health > 0.5f ? 0xFF50E070 : health > 0.25f ? 0xFFE0C040 : 0xFFE04040);
        int end = head.x1() - right;
        if (thinking) {
            String t = ui("thinking") + "...".substring(0, 1 + (int) (System.currentTimeMillis() / 400 % 3));
            int tx = end - fr.getStringWidth(ui("thinking") + "...") - 12;
            end = tx - 4;
            icon(UiIcon.THINK, tx, head.y + 1, 8, 1f);
            fr.drawStringWithShadow(t, tx + 10, head.y + 2, 0xB8E0FF);
        }
        if (after != null && hx + 48 < end) {
            int ax = hx + 48;
            if (afterIcon != null) { icon(afterIcon, ax, head.y + 1, 8, 1f); ax += 11; }
            fr.drawStringWithShadow(fr.trimStringToWidth(after, Math.max(0, end - ax)), ax, head.y + 2, TEXT_DIM);
        }
    }

    /** a line of the card: an icon, then words trimmed to the box */
    void line(UiLayout.Box b, UiIcon icon, String text, int color) {
        int x = b.x;
        if (icon != null) { icon(icon, x, b.y, 8, 1f); x += 11; }
        fr.drawStringWithShadow(fr.trimStringToWidth(text, b.x1() - x), x, b.y + 1, color);
    }

    /** a message: its icon, its words (two lines at most); a warning or a bad one in their colours */
    void toast(UiLayout.Box b, UiToasts.Toast t) {
        int bg = t.kind == UiToasts.Kind.WARN ? WARN : PANEL_HI;
        Ui.round(b.x, b.y, b.x1(), b.y1(), 5, bg);
        int x = b.x + 4;
        if (t.icon != null) { icon(t.icon, x, b.y + 3, 8, 1f); x += 11; }
        fr.drawStringWithShadow(fr.trimStringToWidth(t.text, b.x1() - x - 4), x, b.y + 3, t.kind == UiToasts.Kind.WARN ? WARN_TEXT : TEXT);
    }

    /** the plan's state line (the plan stands, no places, no guide): an icon, the words and, maybe, its button */
    void state(UiLayout.Box b, UiIcon icon, String text, String buttonId, UiIcon buttonIcon) { state(b, icon, text, buttonId, buttonIcon, true); }

    /** a state line; warn: the plan's trouble (yellow), else a mode the view is in (blue) */
    void state(UiLayout.Box b, UiIcon icon, String text, String buttonId, UiIcon buttonIcon, boolean warn) {
        Ui.round(b.x, b.y, b.x1(), b.y1(), 5, warn ? WARN : 0xE0183448);
        icon(icon, b.x + 4, b.y + 4, 8, 1f);
        int right = buttonId == null ? b.x1() - 4 : b.x1() - 22;
        fr.drawStringWithShadow(fr.trimStringToWidth(text, right - b.x - 16), b.x + 15, b.y + 4, warn ? WARN_TEXT : 0xB8E9FF);
        if (buttonId != null) {
            boolean over = over(b.x1() - 18, b.y + 1, 14, 14);
            Ui.round(b.x1() - 18, b.y + 1, b.x1() - 4, b.y + 15, 3, over ? BUTTON_OVER : BUTTON);
            icon(buttonIcon, b.x1() - 17, b.y + 4, 8, 1f);
            hits.add(new Object[] { buttonId, b.x1() - 18, b.y + 1, b.x1() - 4, b.y + 15 });
            if (over) tip(I18n.format(buttonIcon.words()), buttonIcon.key, b.x1() - 18, b.y + 1, 14, 14);
        }
    }

    /** the "..." menu: a column of its icons and words over the button, each a hit "menu:NAME" */
    void menu(UiLayout.Box more) {
        int rows = more.menu.size(), mw = 0;
        for (UiIcon i : more.menu) mw = Math.max(mw, fr.getStringWidth(I18n.format(i.words())));
        mw += 30;
        int mh = rows * 14 + 4;
        int[] at = UiLayout.tooltip(more.x, more.y, more.w, more.h, mw, mh, w, h);
        Ui.round(at[0], at[1], at[0] + mw, at[1] + mh, 5, PANEL_HI);
        for (int i = 0; i < rows; i++) {
            UiIcon ic = more.menu.get(i);
            int y = at[1] + 2 + i * 14;
            if (over(at[0], y, mw, 14)) Ui.round(at[0] + 2, y, at[0] + mw - 2, y + 14, 3, BUTTON_OVER);
            icon(ic, at[0] + 4, y + 3, 8, 1f);
            fr.drawStringWithShadow(I18n.format(ic.words()), at[0] + 16, y + 3, TEXT);
            if (!ic.key.isEmpty()) fr.drawString(ic.key, at[0] + mw - 6 - fr.getStringWidth(ic.key), y + 3, KEY);
            hits.add(new Object[] { "menu:" + ic.name().toLowerCase(java.util.Locale.ROOT), at[0], y, at[0] + mw, y + 14 });
        }
    }
}
