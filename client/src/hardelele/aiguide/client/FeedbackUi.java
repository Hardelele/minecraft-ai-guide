package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import org.lwjgl.input.Keyboard;

/**
 * The commander's rating dialogs, drawn over its screen in its flat look: a recognised place's menu (right, another
 * class, no place / wrong border, a comment) and "What the agents did" (the last reflex cases and finished cards,
 * good or bad with a reason, the camera to where it happened). Words from the lang files (aiguide.fb.*).
 */
final class FeedbackUi {
    enum View { NONE, PLACE, ACTIONS }

    View view = View.NONE;
    final List<Object[]> hits = new ArrayList<Object[]>();   // id, x0, y0, x1, y1
    final UiKit kit = new UiKit();                            // the commander's pieces (the dialog's close)

    // the place's menu
    PlaceView place;
    String answer;
    int label = -1;
    GuiTextField comment;

    // what the agents did
    int tab;   // 0 reflexes' cases, 1 cards, 2 the defence's fights
    int offset;
    List<Episodes.Ep> eps = new ArrayList<Episodes.Ep>();
    Episodes.Ep picked;
    int rating;
    String reason;
    final Map<String, Integer> rated = new HashMap<String, Integer>();   // this session's ratings (shown on the rows)
    static final int ROWS = 7;

    static String t(String key, Object... a) { return I18n.format("aiguide.fb." + key, a); }

    static String className(int cls) { return cls >= 0 && cls < PlaceView.CLASSES.length ? t("class." + PlaceView.CLASSES[cls]) : "?"; }

    boolean open() { return view != View.NONE; }

    void close() { view = View.NONE; PlaceLayer.chosen = null; picked = null; if (comment != null) comment.setFocused(false); }

    void openPlace(PlaceView p) {
        view = View.PLACE;
        place = p;
        PlaceLayer.chosen = p;
        answer = null;
        label = -1;
        if (comment != null) { comment.setText(""); comment.setFocused(false); }
    }

    void openActions() {
        view = View.ACTIONS;
        offset = 0;
        picked = null;
        refresh();
    }

    void refresh() { eps = tab == 0 ? Episodes.cases(Feedback.recentCases(200), 200) : tab == 1 ? Episodes.cards(200) : Episodes.fights(200); }

    /** what was written: a message of the commander's (its one place for them) */
    void say(String s) {
        if (Commander.instance != null) Commander.instance.toasts.add(s, UiToasts.Kind.INFO, UiIcon.RATE, System.currentTimeMillis());
    }

    // ---- drawing ------------------------------------------------------------------------------------

    int button(FontRenderer fr, String title, String id, int x, int y, int mx, int my, boolean lit) {
        return UiKit.textButton(fr, hits, title, id, x, y, mx, my, lit, 4);
    }

    final int[] fieldAt = new int[3];   // where the comment's field was made (it is made again when the dialog moves)

    GuiTextField field(FontRenderer fr, int x, int y, int w) {
        if (comment == null || fieldAt[0] != x || fieldAt[1] != y || fieldAt[2] != w) {
            fieldAt[0] = x; fieldAt[1] = y; fieldAt[2] = w;
            String keep = comment == null ? "" : comment.getText();
            boolean focus = comment != null && comment.isFocused();
            comment = new GuiTextField(fr, x, y, w, 14);
            comment.setMaxStringLength(200);
            comment.setText(keep);
            comment.setFocused(focus);
        }
        return comment;
    }

    /** the open dialog, in the band x0..x0+w of a screen this high */
    void draw(FontRenderer fr, int x0, int w, int height, int mx, int my) {
        hits.clear();
        kit.begin(fr, x0 + w, height, mx, my);
        Gui.drawRect(0, 0, 4000, height, 0x70000000);
        if (view == View.PLACE) drawPlace(fr, x0 + 4, w - 8, height, mx, my);
        else if (view == View.ACTIONS) drawActions(fr, x0 + 4, w - 8, height, mx, my);
        kit.flush();
    }

    void drawPlace(FontRenderer fr, int x, int w, int height, int mx, int my) {
        int h = 150, y = Math.max(8, (height - h) / 2);
        Ui.round(x, y, x + w, y + h, 8, 0xF015222E);
        int c = PlaceLayer.color(place.cls());
        Ui.round(x + 10, y + 9, x + 18, y + 17, 2, 0xFF000000 | c);
        int[] b = place.box();
        fr.drawStringWithShadow(fr.trimStringToWidth(t("place.title", className(place.cls()), Math.round(place.confidence() * 100)), w - 100), x + 22, y + 9, 0xFFFFFF);
        fr.drawString(fr.trimStringToWidth(t("place.box", b[0], b[1], b[2], b[3], b[4], b[5], b[3] - b[0] + 1, b[4] - b[1] + 1, b[5] - b[2] + 1), w - 24), x + 12, y + 22, 0x9EB0BD);
        kit.closeCross(hits, "close", x + w, y);
        int yy = y + 36;
        int bx = button(fr, t("place.correct"), "correct", x + 12, yy, mx, my, Labels.CORRECT.equals(answer));
        button(fr, t("place.not"), "not", bx, yy, mx, my, Labels.NOT_PLACE.equals(answer));
        yy += 20;
        fr.drawString(t("place.other"), x + 12, yy + 4, 0xB8CBD8);
        bx = x + 12 + fr.getStringWidth(t("place.other")) + 6;
        for (int i = 0; i < PlaceView.CLASSES.length; i++) {
            if (i == place.cls()) continue;
            String n = className(i);
            if (bx + fr.getStringWidth(n) + 12 > x + w - 8) { bx = x + 12; yy += 18; }
            bx = button(fr, n, "cls:" + i, bx, yy, mx, my, Labels.RELABEL.equals(answer) && label == i);
        }
        yy += 22;
        fr.drawString(t("comment"), x + 12, yy + 3, 0xB8CBD8);
        int fx = x + 16 + fr.getStringWidth(t("comment"));
        GuiTextField f = field(fr, fx, yy, x + w - 12 - fx);
        f.drawTextBox();
        hits.add(new Object[] { "comment", fx, yy, x + w - 12, yy + 14 });
        yy += 22;
        if (answer != null) button(fr, t("save"), "save", x + 12, yy, mx, my, true);
        else fr.drawString(t("place.choose"), x + 12, yy + 4, 0x93AAB9);
    }

    void drawActions(FontRenderer fr, int x, int w, int height, int mx, int my) {
        int rowH = 16, h = 52 + ROWS * rowH + (picked != null ? 62 : 0), y = Math.max(8, (height - h) / 2);
        Ui.round(x, y, x + w, y + h, 8, 0xF015222E);
        fr.drawStringWithShadow(t("actions.title"), x + 12, y + 9, 0xFFFFFF);
        kit.closeCross(hits, "close", x + w, y);
        int bx = button(fr, t("actions.reflexes"), "tab:0", x + 12, y + 22, mx, my, tab == 0);
        bx = button(fr, t("actions.cards"), "tab:1", bx, y + 22, mx, my, tab == 1);
        button(fr, t("actions.fights"), "tab:2", bx, y + 22, mx, my, tab == 2);
        int yy = y + 42;
        offset = Math.max(0, Math.min(offset, Math.max(0, eps.size() - ROWS)));
        if (eps.isEmpty()) fr.drawString(t(tab == 0 ? "actions.none" : tab == 1 ? "actions.nocards" : "actions.nofights"), x + 12, yy + 4, 0xB9CBD8);
        for (int i = offset; i < eps.size() && i < offset + ROWS; i++) {
            Episodes.Ep e = eps.get(i);
            boolean sel = picked != null && picked.key().equals(e.key());
            boolean over = mx >= x + 8 && mx < x + w - 8 && my >= yy && my < yy + rowH - 1;
            Ui.round(x + 8, yy, x + w - 8, yy + rowH - 1, 3, sel ? 0xD0446270 : over ? 0xA0304452 : 0x8023323E);
            Integer r = rated.get(e.key());
            String mark = r == null ? "" : r > 0 ? "§a+ " : "§c- ";
            String what = e.type.equals("case") ? t("actions.case", e.agent, e.reflex, e.reaction, result(e), e.ticks)
                    : e.type.equals("fight") ? t("actions.fight", e.agent, e.card, result(e), e.detail)
                    : t("actions.card", e.agent, e.card, result(e));
            String when = e.type.equals("case") ? t("actions.tick", e.t) : new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(e.ms));
            int ww = fr.getStringWidth(when);
            fr.drawStringWithShadow(fr.trimStringToWidth(mark + what, w - 30 - ww), x + 12, yy + 4, e.ok() ? 0xDFFFE6 : 0xFFE0C8);
            fr.drawString(when, x + w - 12 - ww, yy + 4, 0x93AAB9);
            hits.add(new Object[] { "row:" + i, x + 8, yy, x + w - 8, yy + rowH - 1 });
            yy += rowH;
        }
        if (eps.size() > ROWS) fr.drawString(t("actions.wheel", offset + 1, Math.min(eps.size(), offset + ROWS), eps.size()), x + 12, y + 44 + ROWS * rowH, 0xA4B8C8);
        if (picked == null) return;
        yy = y + 56 + ROWS * rowH;
        bx = button(fr, t("actions.good"), "good", x + 12, yy, mx, my, rating > 0);
        bx = button(fr, t("actions.bad"), "bad", bx, yy, mx, my, rating < 0);
        if (picked.at != null || !picked.type.equals("case")) bx = button(fr, t("actions.goto"), "goto", bx + 8, yy, mx, my, false);
        yy += 18;
        bx = x + 12;
        for (String rs : Labels.REASONS) {
            String n = t("reason." + rs);
            if (bx + fr.getStringWidth(n) + 12 > x + w - 8) { bx = x + 12; yy += 18; }
            bx = button(fr, n, "reason:" + rs, bx, yy, mx, my, rs.equals(reason));
        }
        yy += 20;
        fr.drawString(t("comment"), x + 12, yy + 3, 0xB8CBD8);
        int fx = x + 16 + fr.getStringWidth(t("comment"));
        int saveW = fr.getStringWidth(t("save")) + 16;
        int fw = x + w - 16 - saveW - fx;
        GuiTextField f = field(fr, fx, yy, fw);
        f.drawTextBox();
        hits.add(new Object[] { "comment", fx, yy, fx + fw, yy + 14 });
        if (rating != 0) button(fr, t("save"), "save", x + w - 8 - saveW, yy - 1, mx, my, true);
    }

    static String result(Episodes.Ep e) {
        String k = "result." + (e.result == null || e.result.isEmpty() ? "none" : e.result.toLowerCase(java.util.Locale.ROOT));
        String s = t(k);
        return s.equals("aiguide.fb." + k) ? e.result : s;   // a result with no words for it: as it is
    }

    // ---- input --------------------------------------------------------------------------------------

    /** a press of the left button at gui x, y */
    void click(Commander c, int gx, int gy) {
        if (comment != null) comment.mouseClicked(gx, gy, 0);
        String id = null;
        for (Object[] h : hits) if (gx >= (Integer) h[1] && gx < (Integer) h[3] && gy >= (Integer) h[2] && gy < (Integer) h[4]) id = (String) h[0];
        if (id == null || id.equals("comment")) return;
        if (id.equals("close")) { close(); return; }
        if (view == View.PLACE) placeAction(id);
        else actionsAction(c, id);
    }

    void placeAction(String id) {
        if (id.equals("correct")) { answer = Labels.CORRECT; label = place.cls(); }
        else if (id.equals("not")) { answer = Labels.NOT_PLACE; label = -1; }
        else if (id.startsWith("cls:")) { answer = Labels.RELABEL; label = Integer.parseInt(id.substring(4)); }
        else if (id.equals("save") && answer != null) {
            Feedback.ratePlace(place, answer, label, comment == null ? "" : comment.getText());
            say(t("saved.place"));
            close();
        }
    }

    void actionsAction(Commander c, String id) {
        if (id.startsWith("tab:")) { tab = Integer.parseInt(id.substring(4)); offset = 0; picked = null; refresh(); return; }
        if (id.startsWith("row:")) {
            int i = Integer.parseInt(id.substring(4));
            if (i < eps.size()) {
                picked = eps.get(i);
                Integer r = rated.get(picked.key());
                rating = r == null ? 0 : r;
                reason = null;
                if (comment != null) comment.setText("");
            }
            return;
        }
        if (picked == null) return;
        if (id.equals("good")) { rating = 1; if (reason == null) reason = "right"; }
        else if (id.equals("bad")) { rating = -1; if ("right".equals(reason)) reason = null; }
        else if (id.startsWith("reason:")) {
            reason = id.substring(7);
            if (rating == 0) rating = reason.equals("right") || reason.equals("fast") ? 1 : -1;
        } else if (id.equals("goto")) goTo(c, picked);
        else if (id.equals("save") && rating != 0) {
            Feedback.rateAction(picked, rating, reason, comment == null ? "" : comment.getText());
            rated.put(picked.key(), rating);
            say(t("saved.action"));
            picked = null;
        }
    }

    /** the camera to where the episode was (a card: where its agent is now) */
    static void goTo(Commander c, Episodes.Ep e) {
        double[] at = null;
        if (e.at != null) at = new double[] { e.at[0] + 0.5, e.at[1], e.at[2] + 0.5 };
        else for (Agent a : c.clientAgents()) if (a.getCustomNameTag().equals(e.agent)) at = new double[] { a.posX, a.boundingBox.minY, a.posZ };
        if (at == null) return;
        c.look(at[0], at[2], c.yaw, Math.min(c.dist, 22));
        c.py = c.sy = at[1];
        c.mark(at[0], at[1] + 0.05, at[2], e.ok() ? 0x40FF60 : 0xFF6040);
    }

    void wheel(int d) {
        if (view == View.ACTIONS && d != 0) offset = Math.max(0, offset + (d < 0 ? 1 : -1));
    }

    /** a key while a dialog is open (Esc: back, then closed); true when it was the dialog's */
    boolean key(char ch, int code) {
        if (comment != null && comment.isFocused()) {
            if (code == Keyboard.KEY_ESCAPE || code == Keyboard.KEY_RETURN) comment.setFocused(false);
            else comment.textboxKeyTyped(ch, code);
            return true;
        }
        if (code == Keyboard.KEY_ESCAPE) {
            if (view == View.ACTIONS && picked != null) picked = null;
            else close();
            return true;
        }
        if (view == View.ACTIONS && code == Keyboard.KEY_J) { close(); return true; }
        return true;   // the commander's keys wait while a dialog is open
    }
}
