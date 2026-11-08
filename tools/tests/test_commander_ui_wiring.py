"""The commander's screen is drawn from UiLayout and UiKit (docs/ui/COMMANDER-UI.md, stage 2), checked in the sources:
every button the layout can show has an action in Commander.Screen.pill (by the same id a director's script clicks:
the panel's hits keep {"pill", id, ...}), the old panel is gone, the crew's notes and the ratings' words go to the one
queue of messages, and over an agent's head the commander shows no words of its mind (its chip and its card do)."""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "client" / "src" / "hardelele" / "aiguide" / "client"


def body(text, start):
    i = text.index(start)
    depth, j = 0, text.index("{", i)
    while True:
        c = text[j]
        depth += c == "{"
        depth -= c == "}"
        if depth == 0:
            return text[i:j + 1]
        j += 1


def errors():
    out = []
    cmd = (SRC / "Commander.java").read_text(encoding="utf-8")
    layout = (SRC / "UiLayout.java").read_text(encoding="utf-8")
    icons = set()
    for fn in ("toggles", "bar", "orders"):
        icons |= set(re.findall(r"UiIcon\.([A-Z_]+)", body(layout, "static List<Tool> %s(" % fn)))
    pill_of = body(cmd, "static String pillOf(UiIcon i)")
    special = dict(re.findall(r"case ([A-Z_]+): return \"([a-z]+)\";", pill_of))
    actions = body(cmd, "void pill(String what)")
    for icon in sorted(icons):
        pid = special.get(icon, icon.lower())
        if 'what.equals("%s")' % pid not in actions:
            out.append("the %s button (pill %s) has no action in Commander.Screen.pill" % (icon, pid))
    draw = body(cmd, "public void drawScreen(int gmx, int gmy, float pt)")
    if "UiLayout.commander(" not in draw:
        out.append("drawScreen does not lay the screen out with UiLayout")
    if not re.search(r'menu \? "menu" : "pill"', draw):
        out.append("drawScreen does not keep the buttons as {\"pill\", id} hits for the director")
    for old in ("void viewBar(", "void cavePanel(", "int panelH(", "void panel(int gmx", "localReply"):
        if old in cmd:
            out.append("the old panel's %s is still in Commander" % old)
    keys = (SRC / "CrewKeys.java").read_text(encoding="utf-8")
    if "localReply" in keys or ".toast(text, warn)" not in keys:
        out.append("CrewKeys does not say its notes as the commander's messages")
    fb = (SRC / "FeedbackUi.java").read_text(encoding="utf-8")
    if "toasts.add(" not in body(fb, "void say(String s)"):
        out.append("FeedbackUi's words do not go to the commander's messages")
    render = (SRC / "RenderAgent.java").read_text(encoding="utf-8")
    if "Commander.instance.on) return;" not in body(render, "protected void passSpecialRender("):
        out.append("RenderAgent writes the mind's words over the head in the commander too")
    return out


def main():
    bad = errors()
    for b in bad:
        print("FAIL", b)
    print("test_commander_ui_wiring", "PASS" if not bad else "FAIL")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
