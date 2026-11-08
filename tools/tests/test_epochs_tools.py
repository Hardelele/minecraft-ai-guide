"""The epochs' tools without the game: the montage's chart and lessons card (tools/epochs_chart.py) from the run that
EpochsTest leaves in build/test-tmp/epochs/stand-in; the driver (tools/epochs.py): the world's copy, putting it back,
its mark, the command, the end of the run; the words of every reason and karma line in both lang files."""
import contextlib
import csv
import io
import json
import os
import re
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "tools"))
import epochs  # noqa: E402
import epochs_chart  # noqa: E402
from PIL import Image  # noqa: E402

RUN = os.path.join(ROOT, "build", "test-tmp", "epochs", "stand-in")
SRC = os.path.join(ROOT, "client", "src", "hardelele", "aiguide", "client")
LANG = os.path.join(ROOT, "client", "res", "assets", "aiguideclient", "lang")
bad = []


def check(ok, what):
    if not ok:
        bad.append(what)
        print("FAIL", what)


def count(img, rgb):
    return sum(n for n, c in img.getcolors(1 << 24) if c == rgb)


def chart():
    if not os.path.isfile(os.path.join(RUN, "report.json")):
        check(False, "no stand-in run: EpochsTest writes it first (tools/test_client.py runs it before this)")
        return
    out = tempfile.mkdtemp(prefix="epochs-chart-")
    with contextlib.redirect_stdout(io.StringIO()):
        r = epochs_chart.main([RUN, "--out", out, "--size", "960x540", "--frames", "--lessons", "1"])
    check(r == 0, "the chart tool ran")
    for f in ("chart.png", "chart.csv", "frame-001.png", "frame-004.png", "lessons-1.png"):
        check(os.path.isfile(os.path.join(out, f)), f + " made")
    img = Image.open(os.path.join(out, "chart.png")).convert("RGB")
    check(img.size == (960, 540), "the size asked: %s" % (img.size,))
    check(count(img, epochs_chart.LEARN) > 200 and count(img, epochs_chart.CONTROL) > 100, "both tracks drawn in their colours")
    first = Image.open(os.path.join(out, "frame-001.png")).convert("RGB")
    check(count(first, epochs_chart.LEARN) < count(img, epochs_chart.LEARN) / 2, "the first frame: one epoch only")
    with open(os.path.join(RUN, "report.json"), encoding="utf-8") as h:
        rep = json.load(h)
    with open(os.path.join(out, "chart.csv"), encoding="utf-8") as h:
        rows = list(csv.reader(h))
    check(rows[0] == ["epoch", "learn", "control"] and [int(x[1]) for x in rows[1:]] == rep["waves_by_epoch"]["learn"]
          and [int(x[2]) for x in rows[1:]] == rep["waves_by_epoch"]["control"], "the table: the same numbers as the report")
    card = Image.open(os.path.join(out, "lessons-1.png")).convert("RGB")
    check(count(card, epochs_chart.LEARN) > 50, "the lessons' card: a mark a lesson")


def driver():
    server = tempfile.mkdtemp(prefix="epochs-server-")
    with open(os.path.join(server, "server.properties"), "w") as h:
        h.write("level-name=bench\n")
    world = os.path.join(server, "bench")
    os.makedirs(os.path.join(world, "region"))
    for f, s in (("level.dat", "base"), ("session.lock", "lock"), ("region/r.0.0.mca", "r")):
        with open(os.path.join(world, f), "w") as h:
            h.write(s)
    with contextlib.redirect_stdout(io.StringIO()) as o:
        r = epochs.main(["--server", server, "--run", "t", "--epochs", "2", "--model", "stub", "--dry-run", "--", "-Dx=1"])
    out = o.getvalue()
    check(r == 0, "the dry run")
    base = os.path.join(server, "epochs", "t", "world-base")
    check(os.path.isfile(os.path.join(base, "level.dat")) and not os.path.exists(os.path.join(base, "session.lock")), "the copy made once, without session.lock")
    with open(os.path.join(world, "level.dat"), "w") as h:
        h.write("changed by an epoch")
    open(os.path.join(world, "junk"), "w").close()
    epochs.restore(server, "t")
    with open(os.path.join(world, "level.dat")) as h:
        check(h.read() == "base", "put back: the copy's level.dat")
    check(not os.path.exists(os.path.join(world, "junk")), "put back: what the epoch made is gone")
    with open(os.path.join(world, "aiguide-epoch.json"), encoding="utf-8") as h:
        m = json.load(h)
    check(m["used"] is False and m["run"] == "t", "the mark: fresh, the run's")
    check("-Daiguide.bench=epochs:normal" in out and "-Daiguide.epochs.run=t" in out and "-Daiguide.epochs.model=stub" in out and "-Dx=1" in out
          and out.rstrip().endswith("nogui"), "the command: the bench's mode, the run, the stand-in, the extra args")
    st = {"learn": {"done": 2}, "control": {"done": 1}}
    check(not epochs.finished(st, 2, True) and epochs.finished(st, 2, False), "through when each track has its epochs")
    with open(os.path.join(server, "epochs", "t", "state.json"), "w", encoding="utf-8") as h:
        json.dump({"learn": {"done": 2}, "control": {"done": 2}}, h)
    with contextlib.redirect_stdout(io.StringIO()) as o:
        r = epochs.main(["--server", server, "--run", "t", "--epochs", "2"])
    check(r == 0 and "is through" in o.getvalue(), "a run through: nothing started")


def lang(name):
    out = {}
    with open(os.path.join(LANG, name), encoding="utf-8") as h:
        for line in h:
            if "=" in line and not line.startswith("#"):
                k, v = line.rstrip("\n").split("=", 1)
                out[k] = v
    return out


def words():
    src = open(os.path.join(SRC, "EpochScore.java"), encoding="utf-8").read()
    rules = re.findall(r'\{ "([a-z_]+)", "-?1", "', src)
    karma = set(re.findall(r'if \(code\.equals\("([a-z_]+)"\)\) return', src))   # karmaText: every karma code's words
    check(len(rules) >= 19 and len(karma) >= 8, "the codes found in EpochScore.java: %d rules, %d karma" % (len(rules), len(karma)))
    reasons = re.findall(r'"([a-z_]+)"', open(os.path.join(SRC, "Labels.java"), encoding="utf-8").read().split("REASONS = {", 1)[1].split("}", 1)[0])
    for name in ("ru_RU.lang", "en_US.lang"):
        d = lang(name)
        for c in rules + reasons + ["human"]:
            check("aiguide.ui.epochs.why." + c in d, "%s: aiguide.ui.epochs.why.%s" % (name, c))
        for c in karma:
            check("aiguide.ui.epochs.karma." + c in d, "%s: aiguide.ui.epochs.karma.%s" % (name, c))


def wiring():
    """the hooks the epochs hang on, in the game's sources"""
    def has(f, *needles):
        s = open(os.path.join(SRC, f), encoding="utf-8").read()
        for n in needles:
            check(n in s, "%s: %s" % (f, n))
    has("AgentMind.java", "Epochs.step(a, w, cl.tool, result")
    has("Cases.java", "Epochs.reflexCase(a, c)", "dirOverride != null ? dirOverride : worldDir()")
    has("Agent.java", "Defense.died(who(), s.getDamageType())", "Epochs.died(this")
    has("Mind.java", 'm.add(message("system", system(goal.defense)))', "SYSTEM + Defense.RULES + (l == null ? \"\" : l)")
    has("Bench.java", 'mode.equals("epochs")', "Epochs.begin(w, wavesLevel())", "Epochs.close(w)", "Epochs.done()", "Waves.start(wavesLevel(), epochs() ? \"epochs\" : \"bench\", seed)")
    has("DefenseSkills.java", 'call.num("radius", Defense.LEASH)', "Defense.of(ag.who()).eaten++", "Defense.of(a.who()).torches++")
    has("ClientSetup.java", "EpochsHud.start()")
    has("Commander.java", "EpochsHud.strip(kit", "EpochsHud.card(kit")


def main():
    chart()
    driver()
    words()
    wiring()
    print("test_epochs_tools " + ("PASS" if not bad else "FAIL (%d)" % len(bad)))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
