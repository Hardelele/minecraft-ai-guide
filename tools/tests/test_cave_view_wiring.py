"""The underground view is always on and has no one-layer cut: Slice is gone from the sources, CaveView is registered once
and hangs on the world's last render (so it works in the commander, the plain view and the film camera), and the commander
does not make it depend on its own mode. Checked in the sources, no game needed."""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
C = ROOT / "client/src/hardelele/aiguide/client"


def problems():
    out = []
    if (C / "Slice.java").exists():
        out.append("Slice.java is back")
    for f in sorted(C.glob("*.java")):
        text = f.read_text(encoding="utf-8")
        if re.search(r"\bSlice\.", text):
            out.append(f"{f.name}: still calls Slice")
    cave = (C / "CaveView.java").read_text(encoding="utf-8")
    cmd = (C / "Commander.java").read_text(encoding="utf-8")
    setup = (C / "ClientSetup.java").read_text(encoding="utf-8")
    if "CaveView.start();" not in setup:
        out.append("CaveView is not registered")
    if "public void end(RenderWorldLastEvent" not in cave:
        out.append("CaveView does not draw on the world's last render")
    # the view must not be gated on the commander: the commander's flag only weights which agent is the chosen one
    if re.search(r"if \(!(?:Commander\.instance\.on|c\.on)\) return", cave):
        out.append("CaveView is gated on the commander")
    for needle in ("CaveFade.underground(", "CaveFade.clarity(", "CaveFade.approach(", "CaveFade.followWeight("):
        if needle not in cave:
            out.append(f"CaveView does not use {needle}")
    if "shadersmod" in cave:
        out.append("CaveView switches the shader pack again")
    if "CaveView.active()" in cmd or "CaveView.volume" in cmd:
        out.append("the commander still reads the old map mode of CaveView")
    return out


if __name__ == "__main__":
    p = problems()
    for line in p:
        print("FAIL", line)
    print("test_cave_view_wiring " + ("FAIL" if p else "PASS"))
    sys.exit(1 if p else 0)
