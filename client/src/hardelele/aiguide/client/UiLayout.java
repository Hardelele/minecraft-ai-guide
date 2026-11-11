package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Where the commander's screen puts what (docs/ui/COMMANDER-UI.md), in GUI units, with no game in it: the crew's chips
 * and the view toggles along the top, the plan's state and the messages under them, the picked agents' card at the
 * bottom left with their orders, the bar of what is always there at the bottom. What does not fit goes into a "..."
 * menu, least needed first; the stop and the plan never do. UiLayoutTest checks nothing overlaps from 320 to 960 wide.
 */
final class UiLayout {
    static final int M = 4, BTN = 20, GAP = 2, GROUP_GAP = 6, PAD = 2, BAR_H = BTN + 2 * PAD;
    static final int CHIP_W = 34, CHIP_MIN = 22, CHIP_H = 20, ROW = 12, SLOT = 18, LINE = 10, TOAST_H = 14;
    static final int CARD_MAX = 336, TOAST_MAX = 260;

    /** a box on the screen; menu: what a "..." box holds */
    static final class Box {
        final String id;
        final int x, y, w, h;
        final List<UiIcon> menu = new ArrayList<UiIcon>();

        Box(String id, int x, int y, int w, int h) { this.id = id; this.x = x; this.y = y; this.w = w; this.h = h; }

        int x1() { return x + w; }
        int y1() { return y + h; }
        boolean overlaps(Box o) { return x < o.x1() && o.x < x1() && y < o.y1() && o.y < y1(); }
        boolean inside(Box o) { return x >= o.x && y >= o.y && x1() <= o.x1() && y1() <= o.y1(); }
        @Override public String toString() { return id + " " + x + "," + y + " " + w + "x" + h; }
    }

    /** a button of a bar: its icon, its group (a wider gap between groups), how much it is needed (0 most) */
    static final class Tool {
        final UiIcon icon;
        final int group, need;

        Tool(UiIcon icon, int group, int need) { this.icon = icon; this.group = group; this.need = need; }
    }

    /** what the screen shows now */
    static final class Input {
        int w, h;                 // the screen, GUI units
        int units;                // the crew's chips (the player, number 1, among them)
        int selected;             // picked: 0, 1 or more
        boolean player;           // the player is one of the crew (the "give myself to the AI" button)
        boolean chatOpen;         // writing to the guide
        int toasts;               // messages waiting to be shown
        boolean stall;            // the plan waits for the player's orders
        boolean underground;      // a picked agent under the surface: the cave and layer toggles
        boolean wideInventory;    // the card shows the whole bag
        boolean journalOpen;
        boolean mind;             // agents that choose their own steps (crew/agent-mind): the goal button
        boolean waves;            // a run of the waves mode on (or just over): its strip under the top bar (WavesHud)

        Input(int w, int h) { this.w = w; this.h = h; }
    }

    final List<Box> boxes = new ArrayList<Box>();
    int sw, sh;   // the screen

    Box get(String id) {
        for (Box b : boxes) if (b.id.equals(id)) return b;
        return null;
    }

    List<Box> starting(String prefix) {
        List<Box> out = new ArrayList<Box>();
        for (Box b : boxes) if (b.id.startsWith(prefix)) out.add(b);
        return out;
    }

    Box add(String id, int x, int y, int w, int h) {
        Box b = new Box(id, x, y, w, h);
        boxes.add(b);
        return b;
    }

    // ---- what goes where ------------------------------------------------------------------------------

    static List<Tool> toggles(Input in) {
        List<Tool> t = new ArrayList<Tool>();
        if (in.underground) { t.add(new Tool(UiIcon.CAVES, 0, 2)); }
        t.add(new Tool(UiIcon.FOG, 1, 4));
        t.add(new Tool(UiIcon.PLACES, 1, 5));
        t.add(new Tool(UiIcon.LOG, 2, 1));
        return t;
    }

    static List<Tool> bar(Input in) {
        List<Tool> t = new ArrayList<Tool>();
        t.add(new Tool(in.chatOpen ? UiIcon.CLOSE : UiIcon.CHAT, 0, 0));
        t.add(new Tool(UiIcon.SCENARIOS, 1, 3));
        t.add(new Tool(UiIcon.ADD_AGENT, 1, 4));
        if (in.player) t.add(new Tool(UiIcon.SELF_AI, 1, 5));
        t.add(new Tool(UiIcon.WAVES, 1, 7));
        t.add(new Tool(UiIcon.RATE, 2, 6));
        t.add(new Tool(UiIcon.SHARE, 2, 8));
        t.add(new Tool(UiIcon.SETTINGS, 2, 9));
        t.add(new Tool(UiIcon.HELP, 2, 2));
        return t;
    }

    static List<Tool> orders(Input in) {
        List<Tool> t = new ArrayList<Tool>();
        t.add(new Tool(UiIcon.MOVE, 0, 1));
        t.add(new Tool(UiIcon.CHOP, 0, 2));
        t.add(new Tool(UiIcon.MINE, 0, 3));
        t.add(new Tool(UiIcon.ATTACK, 0, 2));
        t.add(new Tool(UiIcon.GUARD, 0, 4));
        t.add(new Tool(UiIcon.DEPOSIT, 0, 5));
        t.add(new Tool(UiIcon.PLACE, 0, 6));
        t.add(new Tool(UiIcon.CRAFT, 1, 8));
        t.add(new Tool(UiIcon.SMELT, 1, 9));
        if (in.mind) t.add(new Tool(UiIcon.GOAL, 2, 3));
        if (in.selected > 1) t.add(new Tool(UiIcon.GATHER, 2, 7));
        t.add(new Tool(UiIcon.DISMISS, 2, 10));
        t.add(new Tool(UiIcon.STOP, 3, 0));
        t.add(new Tool(UiIcon.PLAN, 3, 0));
        return t;
    }

    /** a row's width: buttons, gaps, wider gaps between groups */
    static int width(List<Tool> row) {
        if (row.isEmpty()) return 0;
        int w = row.size() * BTN + (row.size() - 1) * GAP;
        for (int i = 1; i < row.size(); i++) if (row.get(i).group != row.get(i - 1).group) w += GROUP_GAP - GAP;
        return w;
    }

    /** what of a row fits in a width: the least needed go into "..." (the row keeps its order); the rest: the menu */
    static List<Tool> fit(List<Tool> row, int max, List<UiIcon> menu) {
        List<Tool> kept = new ArrayList<Tool>(row);
        if (width(kept) <= max) return kept;
        List<Tool> byNeed = new ArrayList<Tool>(row);
        Collections.sort(byNeed, new Comparator<Tool>() {   // the least needed first (a stable sort: the later of equals first)
            public int compare(Tool a, Tool b) { return b.need - a.need; }
        });
        int i = 0;
        while (width(kept) + GROUP_GAP + BTN > max && i < byNeed.size() && byNeed.get(i).need > 0) {
            kept.remove(byNeed.get(i));
            menu.add(0, byNeed.get(i).icon);
            i++;
        }
        return kept;
    }

    /** a row of buttons from x, y; with a "..." button when something did not fit */
    int row(String prefix, List<Tool> kept, List<UiIcon> menu, int x, int y) {
        for (int i = 0; i < kept.size(); i++) {
            if (i > 0) x += kept.get(i).group != kept.get(i - 1).group ? GROUP_GAP : GAP;
            add(prefix + kept.get(i).icon.name().toLowerCase(java.util.Locale.ROOT), x, y, BTN, BTN);
            x += BTN;
        }
        if (!menu.isEmpty()) {
            x += GROUP_GAP;
            add(prefix + "more", x, y, BTN, BTN).menu.addAll(menu);
            x += BTN;
        }
        return x;
    }

    static int rowWidth(List<Tool> kept, List<UiIcon> menu) { return width(kept) + (menu.isEmpty() ? 0 : GROUP_GAP + BTN); }

    /** the commander's screen for this state */
    static UiLayout commander(Input in) {
        UiLayout l = new UiLayout();
        int w = in.w, h = in.h;
        l.sw = w; l.sh = h;
        // the top right: the view's toggles (the journal's always; caves and layer only for an agent under the surface)
        List<UiIcon> tMenu = new ArrayList<UiIcon>();
        List<Tool> tKept = fit(toggles(in), Math.max(BTN, w / 3), tMenu);
        int tw = rowWidth(tKept, tMenu) + 2 * PAD;
        l.add("toggles", w - M - tw, M, tw, BAR_H);
        l.row("toggle.", tKept, tMenu, w - M - tw + PAD, M + PAD);
        // the top left: a chip a unit (number, health, what it does); narrower when many, then "+N"
        int avail = w - 2 * M - tw - 6;
        int chip = in.units == 0 ? CHIP_W : Math.max(CHIP_MIN, Math.min(CHIP_W, (avail + GAP) / in.units - GAP));
        int shown = Math.min(in.units, Math.max(0, (avail + GAP) / (chip + GAP)));
        if (shown < in.units) shown = Math.max(0, (avail - CHIP_MIN) / (chip + GAP));   // room for the "+N" chip
        int x = M;
        for (int i = 0; i < shown; i++) { l.add("roster." + (i + 1), x, M + PAD, chip, CHIP_H); x += chip + GAP; }
        if (shown < in.units) l.add("roster.more", x, M + PAD, CHIP_MIN, CHIP_H);
        int top = M + BAR_H + 4;
        // the right column under the toggles: the journal, when open (on a narrow screen it covers the messages' room)
        // under them, centred: the plan's state, then the messages; the journal open takes the messages' place
        // (it is the same messages, kept): on a short screen with a card, one line of them
        boolean shortScreen = h < 300;
        int areaW = w - 2 * M, sw = Math.min(TOAST_MAX, areaW), sx = M + (areaW - sw) / 2, y = top;
        if (in.waves) { l.add("waves", sx, y, sw, 16); y += 16 + 4; }
        if (in.stall) { l.add("state", sx, y, sw, 16); y += 16 + 4; }
        if (in.journalOpen) {
            int lines = shortScreen ? 3 : 5;
            l.add("journal", sx, y, sw, lines * LINE + 8);
            y += lines * LINE + 8 + 4;
        } else {
            int maxToasts = !shortScreen ? 3 : in.selected == 0 ? 2 : in.stall ? 0 : 1;
            if (in.waves && shortScreen) maxToasts = Math.max(0, maxToasts - 1);   // (the strip took a line's room)
            for (int i = 0; i < Math.min(in.toasts, maxToasts); i++) { l.add("toast." + i, sx, y, sw, TOAST_H); y += TOAST_H + 2; }
            if (in.toasts > maxToasts && maxToasts > 0) { l.add("toast.more", sx, y, sw, 8); y += 10; }
        }
        int topEnd = y;
        // the bottom: the bar of what is always there
        List<UiIcon> bMenu = new ArrayList<UiIcon>();
        List<Tool> bKept = fit(bar(in), w - 2 * M - 2 * PAD, bMenu);
        int bw = rowWidth(bKept, bMenu) + 2 * PAD, barY = h - M - BAR_H;
        Box bar = l.add("bar", (w - bw) / 2, barY, bw, BAR_H);
        l.row("bar.", bKept, bMenu, bar.x + PAD, barY + PAD);
        int bottom = barY - 4;
        if (in.chatOpen) { l.add("chat", M, bottom - 16, w - 2 * M, 16); bottom -= 16 + 4; }
        // the picked: a card above the bar, its orders along its foot
        if (in.selected > 0) {
            int cw = Math.min(CARD_MAX, w - 2 * M);
            List<UiIcon> oMenu = new ArrayList<UiIcon>();
            List<Tool> oKept = fit(orders(in), cw - 2 * PAD - 4, oMenu);
            int head = 6 + ROW;
            int body;
            int rows = Math.min(shortScreen ? 3 : 5, in.selected);
            if (in.selected == 1) body = 2 * LINE + 2 + SLOT * (in.wideInventory ? (shortScreen ? 2 : 3) : 1);
            else body = rows * ROW + (in.selected > rows ? 8 : 0);
            // too tall for the world to be seen (a fifth of the screen kept): no bag, then fewer rows, then the head only
            int room = bottom - (topEnd + h / 5);
            if (in.selected == 1 && head + body + 4 + BAR_H + 2 > room) body = 2 * LINE + 2;
            if (in.selected > 1) while (body > ROW && head + body + 4 + BAR_H + 2 > room) body = (body > rows * ROW ? rows * ROW : body - ROW);
            if (head + body + 4 + BAR_H + 2 > room) body = 0;
            int ch = head + body + 4 + BAR_H + 2;
            int cy = bottom - ch;
            l.add("card", M, cy, cw, ch);
            l.add("card.head", M + 4, cy + 4, cw - 8, ROW);
            if (body == 0) { /* the head only: what it does goes after the name */ }
            else if (in.selected == 1) {
                l.add("card.doing", M + 6, cy + head, cw - 12, LINE);
                l.add("card.goal", M + 6, cy + head + LINE + 2, cw - 12, LINE);
                if (body > 2 * LINE + 2) l.add("card.bag", M + 6, cy + head + 2 * LINE + 2, cw - 12, body - 2 * LINE - 2);
            } else l.add("card.rows", M + 6, cy + head, cw - 12, body);
            int ow = rowWidth(oKept, oMenu);
            l.row("card.order.", oKept, oMenu, M + (cw - ow) / 2, cy + ch - BAR_H + PAD - 2);
        }
        return l;
    }

    /** a tooltip of tw x th for a box at ax, ay (aw x ah): above it, centred, kept on the screen; below when no room above */
    static int[] tooltip(int ax, int ay, int aw, int ah, int tw, int th, int w, int h) {
        int x = Math.max(M, Math.min(w - M - tw, ax + aw / 2 - tw / 2));
        int y = ay - th - 3;
        if (y < M) y = Math.min(h - M - th, ay + ah + 3);
        return new int[] { x, y };
    }

    /** the share of the screen the panels cover (a measure of how much of the world is left to see) */
    double covered() {
        boolean[][] c = new boolean[sh][sw];
        long n = 0;
        for (Box b : boxes) {
            if (b.id.indexOf('.') >= 0 && !b.id.startsWith("roster.") && !b.id.startsWith("toast.")) continue;   // parts of a panel
            for (int yy = Math.max(0, b.y); yy < Math.min(sh, b.y1()); yy++)
                for (int xx = Math.max(0, b.x); xx < Math.min(sw, b.x1()); xx++) if (!c[yy][xx]) { c[yy][xx] = true; n++; }
        }
        return n / (double) (sw * sh);
    }

    /** the boxes as JSON (tools/ui_mockups.py draws the mock-ups from it) */
    String json() {
        StringBuilder s = new StringBuilder("[");
        for (int i = 0; i < boxes.size(); i++) {
            Box b = boxes.get(i);
            s.append(i > 0 ? ",\n" : "\n").append("{\"id\":\"").append(b.id).append("\",\"x\":").append(b.x).append(",\"y\":").append(b.y)
                    .append(",\"w\":").append(b.w).append(",\"h\":").append(b.h).append(",\"menu\":[");
            for (int j = 0; j < b.menu.size(); j++) s.append(j > 0 ? "," : "").append('"').append(b.menu.get(j).name().toLowerCase(java.util.Locale.ROOT)).append('"');
            s.append("]}");
        }
        return s.append("\n]").toString();
    }
}
