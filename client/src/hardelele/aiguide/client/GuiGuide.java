package hardelele.aiguide.client;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.ChatComponentText;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * The guide's own chat window (G by default): the whole conversation with the guide, long questions
 * (they go over the mod's channel, not the 100-character chat), the answers in full, scrolled with the
 * mouse wheel. Esc closes it; the history stays until the game is closed.
 */
public class GuiGuide extends GuiScreen {
    static final int MAX_INPUT = 2000, MAX_HISTORY = 300;

    /** one message: who said it (null for the guide) and the text */
    static final List<String[]> history = new ArrayList<String[]>();
    static boolean waiting;   // a question is out, no answer yet
    static boolean unread;
    static String draft = "";

    GuiTextField input;
    int scroll;   // lines from the bottom

    static boolean ru() {
        String l = net.minecraft.client.Minecraft.getMinecraft().gameSettings.language;
        return l != null && l.startsWith("ru");
    }

    /** a message from the server; chat says whether it went to the normal chat as well */
    static void said(String text, boolean chat) {
        add(null, text);
        waiting = false;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        if (mc.currentScreen instanceof GuiGuide) return;
        unread = true;
        if (!chat && mc.thePlayer != null) {
            String first = text.split("\n")[0];
            if (first.length() > 70) first = first.substring(0, 70) + "...";
            String key = Keyboard.getKeyName(Pilot.window.getKeyCode());
            mc.thePlayer.addChatMessage(new ChatComponentText("§b[" + (ru() ? "Гид" : "Guide") + "]§r " + first + " §7(" + key + ")"));
        }
    }

    static void ask(String q) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        add(mc.thePlayer == null ? "" : mc.thePlayer.getCommandSenderName(), q);
        if (Pilot.instance.server < 2) {   // nobody to ask here
            add(null, "§7" + (Pilot.instance.server < 1
                    ? (ru() ? "Здесь нет гида: на сервере (или в этом мире) не стоит мод AI Guide." : "No guide here: the server (or this world) has no AI Guide mod.")
                    : (ru() ? "Гид на этом сервере старой версии, окна он не знает. Спроси в чате: ? вопрос" : "The guide of this server is older and has no window. Ask in the chat: ? question")));
            return;
        }
        JsonObject m = Pilot.msg("ask");
        m.addProperty("text", q);
        Pilot.instance.send(m);
        waiting = true;
    }

    static void add(String who, String text) {
        history.add(new String[] { who, text });
        while (history.size() > MAX_HISTORY) history.remove(0);
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        input = new GuiTextField(fontRendererObj, left() + 4, height - 26, width - 2 * left() - 8, 14);
        input.setMaxStringLength(MAX_INPUT);
        input.setFocused(true);
        input.setCanLoseFocus(false);
        input.setText(draft);
        unread = false;
    }

    int left() {
        return Math.max(10, (width - 420) / 2);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        draft = input.getText();
    }

    @Override
    public void updateScreen() {
        input.updateCursorCounter();
    }

    @Override
    protected void keyTyped(char c, int key) {
        if (key == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(null);
            return;
        }
        if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
            String q = input.getText().trim();
            if (CrewCommand.parse(q) != null && CrewKeys.instance != null) {   // the crew's command (/агент, /crew add|remove): done here, not asked
                add(mc.thePlayer == null ? "" : mc.thePlayer.getCommandSenderName(), q);
                input.setText("");
                scroll = 0;
                CrewKeys.instance.command(q, Commander.instance == null ? null : Commander.instance.agentsPicked());
                return;
            }
            if (ShareBuild.command(q)) {   // not a question: the share screen (nothing is sent from it unasked)
                input.setText("");
                GuiShare.open(this, false);
                return;
            }
            if (q.isEmpty() || waiting) return;
            input.setText("");
            scroll = 0;
            ask(q);
            return;
        }
        if (key == Keyboard.KEY_PRIOR) { scroll += 5; return; }
        if (key == Keyboard.KEY_NEXT) { scroll = Math.max(0, scroll - 5); return; }
        input.textboxKeyTyped(c, key);
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int d = Mouse.getEventDWheel();
        if (d > 0) scroll += 3;
        else if (d < 0) scroll = Math.max(0, scroll - 3);
    }

    @Override
    protected void mouseClicked(int x, int y, int b) {
        super.mouseClicked(x, y, b);
        input.mouseClicked(x, y, b);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void drawScreen(int mx, int my, float f) {
        int l = left(), r = width - l, top = 22, bottom = height - 32;
        drawRect(l, 4, r, height - 6, 0xC0101418);
        String title = ru() ? "Гид — пиши вопрос, Enter — отправить, колесо — листать, Esc — закрыть"
                : "Guide — type a question, Enter sends, wheel scrolls, Esc closes";
        drawString(fontRendererObj, "§b" + title, l + 6, 9, 0xFFFFFF);
        // the conversation, wrapped, from the bottom up
        List<String> lines = new ArrayList<String>();
        List<Integer> colors = new ArrayList<Integer>();
        String me = ru() ? "Ты" : "You", guide = ru() ? "Гид" : "Guide";
        for (String[] h : history) {
            String head = h[0] == null ? "§b" + guide + ":§r " : "§e" + me + ":§r ";
            boolean first = true;
            for (String para : h[1].replace("\r", "").split("\n")) {
                List<String> w = fontRendererObj.listFormattedStringToWidth((first ? head : "") + para, r - l - 14);
                if (w.isEmpty()) w.add("");
                for (String s : w) {
                    lines.add(s);
                    colors.add(h[0] == null ? 0xFFFFFF : 0xD0D0D0);
                }
                first = false;
            }
            lines.add("");
            colors.add(0);
        }
        if (waiting) {
            lines.add("§7" + (ru() ? "Гид думает" : "The guide is thinking") + "...".substring(0, 1 + (int) (System.currentTimeMillis() / 400 % 3)));
            colors.add(0xA0A0A0);
        }
        if (history.isEmpty() && !waiting) {
            lines.add("§7" + (ru() ? "Спроси что угодно про сборку: что делать дальше, как сделать вещь, куда идут предметы в трубах. "
                    + "Можно попросить принести, скрафтить или построить. /поделиться — собрать архив данных для обучения (сам он никуда не уходит)."
                    : "Ask anything about the modpack. /share builds an archive of data for training (it is not sent by itself)."));
            colors.add(0xA0A0A0);
        }
        int rows = (bottom - top) / 10;
        int max = Math.max(0, lines.size() - rows);
        if (scroll > max) scroll = max;
        int end = lines.size() - scroll, start = Math.max(0, end - rows);
        int y = bottom - (end - start) * 10;
        for (int i = start; i < end; i++, y += 10) drawString(fontRendererObj, lines.get(i), l + 7, y, colors.get(i));
        if (scroll > 0) drawString(fontRendererObj, "§7▼ " + scroll, r - 40, bottom - 10, 0xA0A0A0);
        input.drawTextBox();
        String n = input.getText().length() + "/" + MAX_INPUT;
        drawString(fontRendererObj, "§7" + n, r - 6 - fontRendererObj.getStringWidth(n), height - 38 + 0, 0x808080);
        super.drawScreen(mx, my, f);
    }
}
