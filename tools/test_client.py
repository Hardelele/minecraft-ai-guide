"""Runs the client mod's checks that need no game (client/test). The pure ones: the classes they test are compiled
alone, from client/src, without Minecraft on the class path, into build/test-classes. The rest are compiled against
the built client (build/client-classes, from tools/build_client.py) and the libraries, into build/test-game."""
import os
import shutil
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build import tool  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "client", "src", "hardelele", "aiguide", "client")
TEST = os.path.join(ROOT, "client", "test")
OUT = os.path.join(ROOT, "build", "test-classes")
PURE = ["Sight.java", "CaseVec.java", "CaseBook.java", "Events.java", "Result.java", "Retry.java", "Situation.java", "Reflex.java", "Reflexes.java", "Undo.java", "Moves.java",
        "PlacesNet.java", "PlacesKernels.java", "PlacesClasses.java", "PlacesSplit.java",
        "PlaceView.java", "PlaceSource.java", "Labels.java", "Episodes.java", "Scrub.java", "ShareBuild.java", "ShareUpload.java", "CrewCommand.java",
        "UiIcon.java", "UiLayout.java", "UiToasts.java", "Mind.java", "MindState.java", "MindShare.java", "Defense.java",
        "WavePlan.java", "WaveRing.java", "WaveCycle.java", "WaveReport.java", "WaveCommand.java",
        "EpochScore.java", "Lessons.java", "EpochKnobs.java", "EpochRun.java", "RlSpec.java", "RlSpec1.java", "RlSpec3.java", "RlAchievements.java", "RlScore.java", "RlCause.java", "RlNear.java", "Reach.java", "Xp.java", "Hunger.java", "RlReward.java", "PolicyNet.java"]   # the classes with no game in them (Gson aside)
TESTS = ["hardelele.aiguide.client.SightTest", "hardelele.aiguide.client.CaseTest", "hardelele.aiguide.client.ReflexesTest",
         "hardelele.aiguide.client.PlacesSplitTest", "hardelele.aiguide.client.PlacesNetTest", "hardelele.aiguide.client.FeedbackTest",
         "hardelele.aiguide.client.CrewCommandTest", "hardelele.aiguide.client.UiLayoutTest", "hardelele.aiguide.client.MindTest",
         "hardelele.aiguide.client.WavesTest", "hardelele.aiguide.client.DefenseTest", "hardelele.aiguide.client.EpochsTest",
         "hardelele.aiguide.client.RlTest", "hardelele.aiguide.client.PolicyTest"]
GAME_TESTS = ["CaveGeometryTest", "CaveRetentionTest", "RoutineIntentTest", "RoutineLayoutTest", "RoutineSnapshotTest", "ShelterRouteTest", "PlacesInputTest", "BoardOrdersTest", "MindGameTest", "DefenseGameTest"]   # against the built client
HELPERS = ["ShareProbe.java"]   # compiled with the pure tests, run by tools/share-service's tests
PY_TESTS = ["tests/test_rl_policy.py", "tests/test_labels.py", "tests/test_feedback_lang.py", "tests/test_crew_lang.py", "tests/test_ui_icons.py", "tests/test_commander_ui_wiring.py", "tests/test_waves_wiring.py", "tests/test_epochs_tools.py", "tests/test_rl_env.py", "tests/test_rl_spec2.py",   # the ratings' offline tool, the words, the commander's icons
            "share-service/tests/test_sigv4.py", "share-service/tests/test_service.py"]   # the share service (and the mod's sending through it)
GSON = os.path.join(ROOT, "lib", "gson-2.2.4.jar")
GAME_OUT = os.path.join(ROOT, "build", "test-game")
PKG = os.path.join(TEST, "hardelele", "aiguide", "client")
# the places net's weights and its check against PyTorch (places/CONTRACT.md): in the tree once the model's branch is
# merged, else taken from that branch (places/model) into build/places
PLACES_FILES = [("res/assets/aiguide/places/places.bin", "places.bin"), ("places/places-test.bin", "places-test.bin")]


def places_files():
    out = []
    for path, name in PLACES_FILES:
        f = os.path.join(ROOT, *path.split("/"))
        if not os.path.isfile(f):
            f = os.path.join(ROOT, "build", "places", name)
            os.makedirs(os.path.dirname(f), exist_ok=True)
            r = subprocess.run(["git", "show", "places/model:" + path], cwd=ROOT, capture_output=True)
            if r.returncode == 0:
                with open(f, "wb") as o:
                    o.write(r.stdout)
        out.append(f)
    return out


def main():
    shutil.rmtree(OUT, ignore_errors=True)
    os.makedirs(OUT)
    sources = [os.path.join(SRC, f) for f in PURE] + [os.path.join(PKG, t.rsplit(".", 1)[1] + ".java") for t in TESTS] + [os.path.join(PKG, f) for f in HELPERS]
    release = []
    ver = subprocess.run([tool("javac"), "-version"], capture_output=True, text=True)
    if "1.8." not in ver.stdout + ver.stderr:
        release = ["--release", "8"]
    subprocess.check_call([tool("javac"), "-encoding", "UTF-8", "-nowarn", "-d", OUT, "-cp", GSON] + release + sources)
    bad = 0
    weights, check = places_files()
    # the simulator's raw facts with spec.py's vector and mask, for PolicyTest's check of RlSpec against them
    subprocess.call([sys.executable, os.path.join(ROOT, "tools", "tests", "test_rl_policy.py"), "--cases-only"])
    for t in TESTS:
        bad |= subprocess.call([tool("java"), "-Dfile.encoding=UTF-8", "-Daiguide.test.dir=" + os.path.join(ROOT, "build", "test-tmp"),
                                "-Daiguide.places.weights=" + weights, "-Daiguide.places.test=" + check,
                                "-Daiguide.policy.weights=" + os.path.join(ROOT, "res", "assets", "aiguide", "rl", "policy.bin"),
                                "-Daiguide.policy.test=" + os.path.join(ROOT, "rl", "policy-test.bin"),
                                "-Daiguide.rl.cases=" + os.path.join(ROOT, "build", "rl", "obs-mask-cases.json"), "-cp", os.pathsep.join([OUT, GSON]), t])
    client = os.path.join(ROOT, "build", "client-classes")
    if not os.path.isdir(client):
        print("no build/client-classes: run tools/build_client.py first")
        sys.exit(1)
    shutil.rmtree(GAME_OUT, ignore_errors=True)
    os.makedirs(GAME_OUT)
    libs = [os.path.join(ROOT, "lib", f) for f in sorted(os.listdir(os.path.join(ROOT, "lib"))) if f.endswith(".jar")]
    cp = os.pathsep.join([client] + libs)
    subprocess.check_call([tool("javac"), "-encoding", "UTF-8", "-nowarn", "-d", GAME_OUT, "-cp", cp] + release + [os.path.join(PKG, t + ".java") for t in GAME_TESTS])
    for t in GAME_TESTS:
        r = subprocess.call([tool("java"), "-Dfile.encoding=UTF-8", "-cp", os.pathsep.join([GAME_OUT, cp]), "hardelele.aiguide.client." + t])
        print(("ok   " if r == 0 else "FAIL ") + t)
        bad |= r
    for t in PY_TESTS:   # after the Java ones: test_labels.py also reads what FeedbackTest wrote
        r = subprocess.call([sys.executable, os.path.join(ROOT, "tools", t)])
        print(("ok   " if r == 0 else "FAIL ") + t)
        bad |= r
    sys.exit(bad)


if __name__ == "__main__":
    main()
