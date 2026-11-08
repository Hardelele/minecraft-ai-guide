package hardelele.aiguide.client;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The commander's layout (UiLayout) on screens from 320x240 to 960x540 GUI units (854x480 is 427x240 at the game's
 * automatic scale 2, 1600x900 is 533x300 at 3) in every state: nothing off the screen, no panel over another, every
 * button inside its panel and clear of the others, the stop, the plan and the chat never in a "..." menu, the world
 * still seen. Also the tooltip's place, the messages' queue, the atlas's cells. With --dump DIR: the layouts as JSON
 * for tools/ui_mockups.py.
 */
public final class UiLayoutTest {
    static final int[][] SCREENS = { { 320, 240 }, { 427, 240 }, { 480, 270 }, { 533, 300 }, { 640, 360 }, { 800, 450 }, { 960, 540 } };

    /** the states the mock-ups and the checks go through */
    static Map<String, UiLayout.Input> states(int w, int h) {
        Map<String, UiLayout.Input> m = new LinkedHashMap<String, UiLayout.Input>();
        UiLayout.Input i;
        i = in(w, h); i.units = 5; i.toasts = 1; m.put("idle", i);
        i = in(w, h); i.units = 5; i.selected = 1; i.mind = true; m.put("one", i);
        i = in(w, h); i.units = 5; i.selected = 1; i.mind = true; i.wideInventory = true; i.underground = true; m.put("one-bag-cave", i);
        i = in(w, h); i.units = 5; i.selected = 3; i.mind = true; m.put("three", i);
        i = in(w, h); i.units = 6; i.selected = 6; i.mind = true; i.stall = true; i.toasts = 4; m.put("all-stall", i);
        i = in(w, h); i.units = 5; i.selected = 1; i.chatOpen = true; i.toasts = 2; m.put("chat", i);
        i = in(w, h); i.units = 5; i.selected = 1; i.journalOpen = true; i.toasts = 2; i.stall = true; m.put("journal", i);
        i = in(w, h); i.units = 6; i.selected = 1; i.mind = true; i.wideInventory = true; i.underground = true; i.chatOpen = true;
        i.journalOpen = true; i.stall = true; i.toasts = 3; m.put("everything", i);
        i = in(w, h); i.units = 5; i.toasts = 2; i.waves = true; m.put("waves", i);
        i = in(w, h); i.units = 6; i.selected = 1; i.mind = true; i.wideInventory = true; i.underground = true; i.chatOpen = true;
        i.stall = true; i.toasts = 3; i.waves = true; m.put("waves-everything", i);
        return m;
    }

    static UiLayout.Input in(int w, int h) {
        UiLayout.Input i = new UiLayout.Input(w, h);
        i.player = true;
        return i;
    }

    static boolean top(UiLayout.Box b) {
        return b.id.indexOf('.') < 0 || b.id.startsWith("roster.") || b.id.startsWith("toast.");
    }

    static String parent(UiLayout.Box b) {
        if (b.id.startsWith("toggle.")) return "toggles";
        if (b.id.startsWith("bar.")) return "bar";
        if (b.id.startsWith("card.")) return "card";
        return null;
    }

    static int bad;

    static void fail(String s) { bad++; System.out.println("FAIL " + s); }

    static void check(String name, UiLayout.Input in, UiLayout l) {
        String at = in.w + "x" + in.h + " " + name + ": ";
        for (UiLayout.Box b : l.boxes) {
            if (b.x < 0 || b.y < 0 || b.x1() > in.w || b.y1() > in.h) fail(at + b + " is off the screen");
            String p = parent(b);
            if (p != null && (l.get(p) == null || !b.inside(l.get(p)))) fail(at + b + " is not inside " + p);
        }
        List<UiLayout.Box> bs = l.boxes;
        for (int i = 0; i < bs.size(); i++)
            for (int j = i + 1; j < bs.size(); j++) {
                UiLayout.Box a = bs.get(i), b = bs.get(j);
                boolean siblings = top(a) && top(b) || parent(a) != null && parent(a).equals(parent(b));
                if (siblings && a.overlaps(b)) fail(at + a + " overlaps " + b);
            }
        if (l.get("bar.chat") == null && l.get("bar.close") == null) fail(at + "the chat is not on the bar");
        if (in.selected > 0) {
            if (l.get("card.order.stop") == null) fail(at + "no stop on the card");
            if (l.get("card.order.plan") == null) fail(at + "no plan on the card");
            if (l.get("card.head") == null) fail(at + "no card head");
        }
        if (in.stall && l.get("state") == null) fail(at + "the plan's state is not shown");
        if (in.waves && l.get("waves") == null) fail(at + "the waves' strip is not shown");
        if (l.get("bar.waves") == null && (l.get("bar.more") == null || !l.get("bar.more").menu.contains(UiIcon.WAVES))) fail(at + "no waves button, not even in the menu");
        // the world seen: between what hangs from the top and what stands on the bottom
        int topEnd = 0, bottomStart = in.h;
        for (UiLayout.Box b : bs) {
            if (!top(b)) continue;
            boolean low = b.id.equals("bar") || b.id.equals("chat") || b.id.equals("card");
            if (low) bottomStart = Math.min(bottomStart, b.y);
            else topEnd = Math.max(topEnd, b.y1());
        }
        int gap = bottomStart - topEnd;
        double cov = l.covered();
        if (gap < in.h / 5) fail(at + "the world between the panels is " + gap + " of " + in.h);
        if (cov > (in.w < 400 ? 0.6 : 0.5)) fail(at + "panels cover " + Math.round(cov * 100) + "%");   // (320 wide: the bar and the card are most of it)
        System.out.println(String.format("  %-16s %4dx%-4d covered %3d%%  world band %3d (%2d%%)%s", name, in.w, in.h, Math.round(cov * 100), gap,
                gap * 100 / in.h, menus(l)));
    }

    static String menus(UiLayout l) {
        StringBuilder s = new StringBuilder();
        for (UiLayout.Box b : l.boxes) if (!b.menu.isEmpty()) s.append("  ").append(b.id).append("=").append(b.menu);
        return s.toString();
    }

    static void tooltips() {
        int[] t = UiLayout.tooltip(2, 200, 20, 20, 100, 16, 427, 240);   // a button at the left edge: kept on the screen
        if (t[0] < UiLayout.M || t[1] != 200 - 16 - 3) fail("tooltip at the left edge: " + t[0] + "," + t[1]);
        t = UiLayout.tooltip(410, 200, 20, 20, 100, 16, 427, 240);
        if (t[0] + 100 > 427 - UiLayout.M) fail("tooltip at the right edge: " + t[0]);
        t = UiLayout.tooltip(100, 4, 20, 20, 100, 16, 427, 240);         // a button at the top: under it
        if (t[1] < 24) fail("tooltip at the top is not under the button: " + t[1]);
    }

    static void toasts() {
        UiToasts q = new UiToasts();
        q.add("a", UiToasts.Kind.INFO, null, 0);
        q.add("b", UiToasts.Kind.GUIDE, null, 1000);
        q.add("a", UiToasts.Kind.INFO, null, 2000);   // the same words: one toast, refreshed, on top
        List<UiToasts.Toast> s = q.shown(2500, 3);
        if (s.size() != 2 || !s.get(0).text.equals("a")) fail("toasts: the same words twice " + s.size());
        if (q.shown(7000, 3).size() != 1 || !q.shown(7000, 3).get(0).text.equals("b")) fail("toasts: an info toast outlives its 4 s");
        if (q.live(40000) != 0) fail("toasts: the guide's answer outlives its 30 s");
        for (int i = 0; i < 20; i++) q.add("x" + i, UiToasts.Kind.WARN, UiIcon.WARNING, 50000);
        if (q.all.size() != UiToasts.KEEP || q.shown(50000, 2).size() != 2) fail("toasts: kept " + q.all.size());
        q.add(" ", UiToasts.Kind.INFO, null, 0);
        q.add(null, UiToasts.Kind.INFO, null, 0);
        if (q.all.size() != UiToasts.KEEP) fail("toasts: an empty one was kept");
    }

    static void atlas() {
        List<String> seen = new ArrayList<String>();
        for (UiIcon i : UiIcon.values())
            for (boolean large : new boolean[] { false, true }) {
                int[] c = i.cell(large);
                if (c[0] + c[2] > UiIcon.ATLAS || c[1] + c[2] > UiIcon.ATLAS) fail("icon " + i + " is off the atlas");
                if (!large && c[1] + c[2] > UiIcon.LARGE_Y) fail("icon " + i + "'s small drawing runs into the large band");
                String k = c[0] + "," + c[1];
                if (seen.contains(k)) fail("icon " + i + " shares a cell");
                seen.add(k);
            }
        if (UiIcon.large(3) || !UiIcon.large(2) || !UiIcon.large(4) || UiIcon.large(1)) fail("the drawing for a GUI scale");
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--dump")) {
            File dir = new File(args[1]);
            dir.mkdirs();
            for (int[] s : SCREENS)
                for (Map.Entry<String, UiLayout.Input> e : states(s[0], s[1]).entrySet()) {
                    Writer o = new OutputStreamWriter(new FileOutputStream(new File(dir, s[0] + "x" + s[1] + "-" + e.getKey() + ".json")), "UTF-8");
                    o.write(UiLayout.commander(e.getValue()).json());
                    o.close();
                }
            return;
        }
        for (int[] s : SCREENS)
            for (Map.Entry<String, UiLayout.Input> e : states(s[0], s[1]).entrySet()) check(e.getKey(), e.getValue(), UiLayout.commander(e.getValue()));
        tooltips();
        toasts();
        atlas();
        if (bad > 0) { System.out.println("UiLayoutTest FAIL " + bad); System.exit(1); }
        System.out.println("UiLayoutTest PASS");
    }
}
