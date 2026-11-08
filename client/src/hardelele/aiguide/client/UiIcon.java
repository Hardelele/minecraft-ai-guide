package hardelele.aiguide.client;

/**
 * The commander's icons, in the atlas's order (textures/gui/icons.png, drawn by tools/ui_icons.py: the same names in
 * the same order, tools/tests/test_ui_icons.py checks it). Each has its words (aiguide.ui.icon.NAME, the tooltip) and
 * the key shown with it by default ("" none; a rebindable key is shown from its KeyBinding instead). No game here.
 */
enum UiIcon {
    MOVE("M"), CHOP("B"), MINE(""), ATTACK("X"), GUARD("G"), DEPOSIT(""), CRAFT(""), SMELT(""), PLACE(""), STOP("H"), PLAN("R"), HOLD(""),
    ADD_AGENT("="), DISMISS("Delete"), SELF_AI("O"), GOAL(""), THINK(""),
    PLACES("L"), RATE("J"), SHARE(""), FOG("Y"), CAVES("C"), LAYER("U"),
    SCENARIOS(""), CHAT("T"), SETTINGS(""), MORE(""), GATHER(""), INVENTORY("I"), WARNING(""), OFFLINE(""), LOG(""), HELP("F1"),
    CLOSE("Esc"), HEALTH(""), LMB(""), RMB(""), WAVES(""), GOOD(""), BAD(""), EPOCH("");

    static final int ATLAS = 256, SMALL = 16, LARGE = 32, LARGE_Y = 64;

    final String key;

    UiIcon(String key) { this.key = key; }

    /** the lang key of its tooltip */
    String words() { return "aiguide.ui.icon." + name().toLowerCase(java.util.Locale.ROOT); }

    /** where it is in the atlas: x, y, size (pixels); large: the 32x32 drawing */
    int[] cell(boolean large) {
        int i = ordinal();
        return large ? new int[] { (i % 8) * LARGE, LARGE_Y + (i / 8) * LARGE, LARGE } : new int[] { (i % 16) * SMALL, (i / 16) * SMALL, SMALL };
    }

    /**
     * which drawing to use at a GUI scale: the 32x32 one when a 16-unit icon is an even number of screen pixels per
     * texel from it (scale 2, 4: 1 or 2 pixels a texel, no uneven pixels), the 16x16 one at odd scales (1, 3: whole pixels)
     */
    static boolean large(int guiScale) { return guiScale >= 2 && guiScale % 2 == 0; }
}
