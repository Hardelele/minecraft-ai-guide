package hardelele.aiguide.client;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

/**
 * "Share data": the consent screen (what goes into the archive, ticked one by one, with sizes; how it is cleaned), the
 * archive built in the background with its progress, then the folder it is in, and Send only when the config names an
 * address, after a second yes. From the guide's window (a command) and the commander's panel (a pill).
 */
public class GuiShare extends GuiScreen {
    enum Step { PLAN, BUILDING, DONE, CONFIRM, SENDING, SENT, FAILED }

    final GuiScreen back;
    final boolean fromCommander;
    Step step = Step.PLAN;
    int radius = 4;
    ShareBuild plan;
    ShareUpload.Config config;
    ShareUpload.Progress sending;
    String failure;
    final List<Object[]> hits = new ArrayList<Object[]>();

    /** the items in their order on the screen */
    static final String[] ITEMS = { "regions", "cases", "labels", "events", "worldrec", "mods", "machine", "chat" };

    GuiShare(GuiScreen back, boolean fromCommander) {
        this.back = back;
        this.fromCommander = fromCommander;
    }

    /** from the commander (it closes the commander's screen; Close opens it again) or the guide's window */
    static void open(GuiScreen back, boolean fromCommander) {
        net.minecraft.client.Minecraft.getMinecraft().displayGuiScreen(new GuiShare(back, fromCommander));
    }

    static String t(String key, Object... a) { return FeedbackUi.t(key, a); }

    @Override
    public void initGui() {
        if (plan == null) replan();
        config = Feedback.config();
    }

    void replan() {
        try { plan = Feedback.plan(radius, plan); }
        catch (Exception e) { failure = String.valueOf(e); step = Step.FAILED; Director.log("share: no plan: " + e); }
    }

    @Override public boolean doesGuiPauseGame() { return false; }   // the server goes on: it saves the world first

    void leave() {
        if (step == Step.BUILDING && plan != null) plan.cancel = true;
        if (fromCommander && Commander.instance != null && mc.theWorld != null) { mc.displayGuiScreen(null); Commander.instance.open(); }
        else mc.displayGuiScreen(back);
    }

    @Override
    protected void keyTyped(char c, int code) {
        if (code == Keyboard.KEY_ESCAPE) {
            if (step == Step.CONFIRM) step = Step.DONE;
            else leave();
        }
    }

    int x0() { return Math.max(8, (width - 420) / 2); }
    int w() { return Math.min(420, width - 16); }

    int button(String title, String id, int x, int y, int mx, int my, boolean lit) {
        int w = fontRendererObj.getStringWidth(title) + 12;
        boolean over = mx >= x && mx < x + w && my >= y && my < y + 15;
        Ui.round(x, y, x + w, y + 15, 5, lit ? 0xF0407A50 : over ? 0xFF4B6172 : 0xEE2B3B48);
        fontRendererObj.drawStringWithShadow(title, x + 6, y + 4, 0xFFFFFF);
        hits.add(new Object[] { id, x, y, x + w, y + 15 });
        return x + w + 5;
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        hits.clear();
        Gui.drawRect(0, 0, width, height, 0xB0000000);
        int x = x0(), w = w(), y = 10;
        Ui.round(x, y - 4, x + w, height - 6, 8, 0xF015222E);
        fontRendererObj.drawStringWithShadow(t("share.title"), x + 12, y + 4, 0xFFFFFF);
        y += 18;
        for (Object l : fontRendererObj.listFormattedStringToWidth(t("share.intro"), w - 24)) { fontRendererObj.drawString((String) l, x + 12, y, 0xB9CBD8); y += 10; }
        y += 4;
        if (plan != null && (step == Step.PLAN || step == Step.FAILED && plan.result == null)) {   // (a failed send keeps its archive's screen)
            for (String id : ITEMS) {
                ShareBuild.Item it = null;
                for (ShareBuild.Item i : plan.items) if (i.id.equals(id)) it = i;
                if (it == null) continue;
                boolean over = mx >= x + 10 && mx < x + w - 10 && my >= y && my < y + 15;
                Ui.round(x + 10, y, x + w - 10, y + 15, 3, over ? 0xA0304452 : 0x8023323E);
                Ui.round(x + 14, y + 3, x + 23, y + 12, 2, it.on ? 0xFF50C878 : 0xFF3A4A56);
                if (it.on) Ui.round(x + 16, y + 5, x + 21, y + 10, 1, 0xFFFFFFFF);
                String label = id.equals("regions") ? t("share.item.regions", radius) : t("share.item." + id);
                String size = it.parts.isEmpty() || it.size() == 0 ? t("share.empty") : ShareBuild.human(it.size());
                int sw = fontRendererObj.getStringWidth(size);
                int right = x + w - 16 - sw - (id.equals("regions") ? 42 : 0);
                fontRendererObj.drawStringWithShadow(fontRendererObj.trimStringToWidth(label, right - x - 32), x + 28, y + 4, it.on ? 0xFFFFFF : 0xA0B0BC);
                fontRendererObj.drawString(size, x + w - 16 - sw - (id.equals("regions") ? 42 : 0), y + 4, 0x9EB0BD);
                hits.add(new Object[] { "tick:" + id, x + 10, y, right, y + 15 });
                if (id.equals("regions")) {
                    button("-", "less", x + w - 52, y, mx, my, false);
                    button("+", "more", x + w - 30, y, mx, my, false);
                }
                y += 17;
            }
            y += 3;
            for (Object l : fontRendererObj.listFormattedStringToWidth(t("share.clean"), w - 24)) { fontRendererObj.drawString((String) l, x + 12, y, 0x93AAB9); y += 10; }
            y += 4;
            fontRendererObj.drawStringWithShadow(t("share.total", ShareBuild.human(plan.size())), x + 12, y, 0xFFE8A0);
            y += 14;
            if (step == Step.FAILED) { fontRendererObj.drawStringWithShadow(fontRendererObj.trimStringToWidth(t("share.failed", failure), w - 24), x + 12, y, 0xFF9090); y += 12; }
            int bx = button(t("share.build"), "build", x + 12, y, mx, my, true);
            button(t("cancel"), "back", bx, y, mx, my, false);
            return;
        }
        if (step == Step.BUILDING) {
            if (plan.finished) {
                if (plan.error != null) { failure = plan.error; step = Step.FAILED; }
                else { step = Step.DONE; Director.log("share: archive " + plan.result.getName() + " " + plan.result.length() + " bytes"); }
            }
            double f = plan.total <= 0 ? 0 : Math.min(0.99, plan.done / (double) plan.total);
            fontRendererObj.drawStringWithShadow("save".equals(plan.status) ? t("share.saving") : t("share.building", (int) Math.round(f * 100)), x + 12, y, 0xFFFFFF);
            y += 14;
            Ui.round(x + 12, y, x + w - 12, y + 8, 3, 0xFF23323E);
            Ui.round(x + 12, y, x + 12 + (int) ((w - 24) * f), y + 8, 3, 0xFF50C878);
            y += 14;
            fontRendererObj.drawString(fontRendererObj.trimStringToWidth(String.valueOf(plan.status), w - 24), x + 12, y, 0x93AAB9);
            y += 16;
            button(t("share.stop"), "back", x + 12, y, mx, my, false);
            return;
        }
        File zip = plan == null ? null : plan.result;
        if (zip != null) {
            fontRendererObj.drawStringWithShadow(fontRendererObj.trimStringToWidth(t("share.done", zip.getName(), ShareBuild.human(zip.length())), w - 24), x + 12, y, 0xC8FFD0);
            y += 12;
            fontRendererObj.drawString(fontRendererObj.trimStringToWidth(t("share.where"), w - 24), x + 12, y, 0x93AAB9);
            y += 16;
        }
        if (step == Step.CONFIRM) {
            String host = ShareUpload.host(config);
            for (Object l : fontRendererObj.listFormattedStringToWidth(t("share.confirm", ShareBuild.human(zip.length()), host), w - 24)) { fontRendererObj.drawStringWithShadow((String) l, x + 12, y, 0xFFE8A0); y += 10; }
            y += 6;
            int bx = button(t("share.yes"), "yes", x + 12, y, mx, my, true);
            button(t("share.no"), "no", bx, y, mx, my, false);
            return;
        }
        if (step == Step.SENDING) {
            if (sending.finished) step = sending.error == null ? Step.SENT : Step.FAILED;
            if (sending.error != null) failure = sending.error;
            double f = sending.total <= 0 ? 0 : sending.sent / (double) sending.total;
            fontRendererObj.drawStringWithShadow(t("share.sending", (int) Math.round(f * 100)), x + 12, y, 0xFFFFFF);
            y += 14;
            Ui.round(x + 12, y, x + w - 12, y + 8, 3, 0xFF23323E);
            Ui.round(x + 12, y, x + 12 + (int) ((w - 24) * f), y + 8, 3, 0xFF50A0FF);
            return;
        }
        if (step == Step.SENT) { fontRendererObj.drawStringWithShadow(fontRendererObj.trimStringToWidth(t("share.sent", sending.answer), w - 24), x + 12, y, 0xC8FFD0); y += 14; }
        if (step == Step.FAILED) { fontRendererObj.drawStringWithShadow(fontRendererObj.trimStringToWidth(t("share.failed", failure), w - 24), x + 12, y, 0xFF9090); y += 14; }
        int bx = button(t("share.folder"), "folder", x + 12, y, mx, my, false);
        if (config != null && config.enabled() && zip != null && step != Step.SENT) {
            bx = ShareUpload.host(config) != null ? button(t("share.send"), "send", bx, y, mx, my, false) : bx;
        }
        button(t("close"), "back", bx, y, mx, my, false);
    }

    @Override
    protected void mouseClicked(int mx, int my, int b) {
        if (b != 0) return;
        String id = null;
        for (Object[] h : hits) if (mx >= (Integer) h[1] && mx < (Integer) h[3] && my >= (Integer) h[2] && my < (Integer) h[4]) id = (String) h[0];
        if (id == null) return;
        if (id.equals("back")) { leave(); return; }
        if (id.startsWith("tick:")) { for (ShareBuild.Item i : plan.items) if (i.id.equals(id.substring(5))) i.on = !i.on; return; }
        if (id.equals("less") || id.equals("more")) {
            int k = 0;
            for (int i = 0; i < Feedback.RADII.length; i++) if (Feedback.RADII[i] == radius) k = i;
            k = Math.max(0, Math.min(Feedback.RADII.length - 1, k + (id.equals("more") ? 1 : -1)));
            radius = Feedback.RADII[k];
            replan();
            return;
        }
        if (id.equals("build")) {
            if (step == Step.FAILED) { step = Step.PLAN; failure = null; replan(); }
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
            plan.start(Feedback.shareDir(), stamp, ShareBuild.newId(), Feedback.consent(plan, radius), Feedback.saveFirst());
            step = Step.BUILDING;
            return;
        }
        if (id.equals("folder")) { Feedback.openFolder(Feedback.shareDir()); return; }
        if (id.equals("send")) { config = Feedback.config(); if (config.enabled() && ShareUpload.host(config) != null) step = Step.CONFIRM; return; }
        if (id.equals("no")) { step = Step.DONE; return; }
        if (id.equals("yes") && plan.result != null) {
            byte[] m = plan.manifest == null ? new byte[0] : plan.manifest.toString().getBytes(StandardCharsets.UTF_8);
            sending = ShareUpload.start(config, plan.result, plan.manifest == null ? "" : plan.manifest.get("id").getAsString(), m);
            Director.log("share: sending " + plan.result.getName() + " to " + ShareUpload.host(config));
            step = Step.SENDING;
        }
    }
}
