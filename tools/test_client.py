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
PURE = ["Sight.java", "CaseVec.java", "CaseBook.java", "Events.java", "Result.java", "Retry.java", "Situation.java", "Reflex.java", "Reflexes.java", "Undo.java", "Moves.java"]   # the classes with no game in them (Gson aside)
TESTS = ["hardelele.aiguide.client.SightTest", "hardelele.aiguide.client.CaseTest", "hardelele.aiguide.client.ReflexesTest"]
GAME_TESTS = ["CaveGeometryTest", "CaveRetentionTest", "RoutineIntentTest", "RoutineLayoutTest", "RoutineSnapshotTest", "ShelterRouteTest"]   # against the built client
GSON = os.path.join(ROOT, "lib", "gson-2.2.4.jar")
GAME_OUT = os.path.join(ROOT, "build", "test-game")
PKG = os.path.join(TEST, "hardelele", "aiguide", "client")


def main():
    shutil.rmtree(OUT, ignore_errors=True)
    os.makedirs(OUT)
    sources = [os.path.join(SRC, f) for f in PURE] + [os.path.join(PKG, t.rsplit(".", 1)[1] + ".java") for t in TESTS]
    release = []
    ver = subprocess.run([tool("javac"), "-version"], capture_output=True, text=True)
    if "1.8." not in ver.stdout + ver.stderr:
        release = ["--release", "8"]
    subprocess.check_call([tool("javac"), "-encoding", "UTF-8", "-nowarn", "-d", OUT, "-cp", GSON] + release + sources)
    bad = 0
    for t in TESTS:
        bad |= subprocess.call([tool("java"), "-Dfile.encoding=UTF-8", "-Daiguide.test.dir=" + os.path.join(ROOT, "build", "test-tmp"), "-cp", os.pathsep.join([OUT, GSON]), t])
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
    sys.exit(bad)


if __name__ == "__main__":
    main()
