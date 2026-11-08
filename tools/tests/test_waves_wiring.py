"""The waves mode's wiring, checked in the sources (no game): the mod registers the waves' mobs and starts the mode on
both sides, writes a run when the server stops, the guide's window and the commander's chat take /волны, the HUD is
started; every wave mob is spared by a peaceful world and never despawned; the words the mode builds from parts (the
levels, the sides, the plural of "waves", the mobs' names, the HUD's) are in both lang files with the same placeholders;
the API the crew's defence reads (ARCHITECTURE-LAYERS.md, "Режим волн") keeps its signatures."""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "client" / "src" / "hardelele" / "aiguide" / "client"
LANG = ROOT / "client" / "res" / "assets" / "aiguideclient" / "lang"


def lang(name):
    out = {}
    for line in (LANG / name).read_text(encoding="utf-8").split("\n"):
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            out[k] = v
    return out


def src(name):
    return (SRC / name).read_text(encoding="utf-8")


def errors():
    out = []
    ru, en = lang("ru_RU.lang"), lang("en_US.lang")
    keys = set()
    plan = src("WavePlan.java")
    for lv in re.findall(r'\{ "[^"]+", "([a-z]+)", "[0-9.]+" \}', plan):
        keys.add("aiguide.crew.waves.level." + lv)
    if len([k for k in keys if ".level." in k]) != 4:
        out.append("the four levels are not found in WavePlan.LEVELS")
    keys |= {"aiguide.crew.waves.side.%d" % i for i in range(8)}
    keys |= {"aiguide.ui.waves.n." + f for f in ("one", "few", "many")}
    mobs = src("WaveMobs.java")
    names = re.findall(r'\.class, "(wave_[a-z]+)" \}', mobs)
    if len(names) != 5:
        out.append("WaveMobs.TYPES: %s" % names)
    keys |= {"entity.aiguideclient.%s.name" % n for n in names}
    hud = src("WavesHud.java")
    keys |= {"aiguide.ui." + k for k in re.findall(r'"(waves\.[a-z.]+)"', hud) if not k.endswith(".")}
    for k in sorted(keys):
        for name, d in (("ru_RU", ru), ("en_US", en)):
            if k not in d:
                out.append("%s: no %s" % (name, k))
    for k in sorted(k for k in keys if k in ru and k in en):
        if re.findall(r"%[sd%]", ru[k]) != re.findall(r"%[sd%]", en[k]):
            out.append("%s: placeholders differ" % k)
    # every wave mob: spared by a peaceful world, never despawned, its mark saved
    for cls in re.findall(r"public static class (\w+) extends \w+ implements Mob", mobs):
        body = mobs.split("public static class %s " % cls, 1)[1].split("public static class", 1)[0]
        for need in ("public void setDead() { if (!spare(this, tag)) super.setDead(); }", "protected boolean canDespawn() { return false; }", "tag.write(t)", "tag.read(t)"):
            if need not in body:
                out.append("WaveMobs.%s lacks %s" % (cls, need))
    main = src("AIGuideClient.java")
    for need in ("Waves.init();", "WaveMobs.TYPES", "Waves.serverStopping();"):
        if need not in main:
            out.append("AIGuideClient lacks " + need)
    if "WavesHud.start();" not in src("ClientSetup.java"):
        out.append("ClientSetup does not start the waves' HUD")
    if "WaveCommand.parse(q)" not in src("GuiGuide.java") or "WaveCommand.parse(q)" not in src("Commander.java"):
        out.append("the guide's window or the commander's chat does not take /волны")
    if 'what.equals("waves")' not in src("Commander.java"):
        out.append("the commander's waves button has no action")
    bench = src("Bench.java")
    if "void wavesTick(" not in bench or 'mode.startsWith("waves:")' not in bench:
        out.append("the bench has no waves mode")
    waves = src("Waves.java")
    for sig in ("static WaveCycle.View state()", "static int next()", "static List<EntityLiving> enemies()", "static boolean active()", "static int[] base()",
                "static String brief()", "static EntityLiving nearestEnemy(double x, double y, double z)", "static void listen(Listener l)",
                "interface Listener { void wave(String what, WaveCycle.View v); }"):
        if sig not in waves:
            out.append("Waves's API changed: " + sig)
    return out


def main():
    bad = errors()
    for b in bad:
        print("FAIL", b)
    print("test_waves_wiring", "PASS" if not bad else "FAIL")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
