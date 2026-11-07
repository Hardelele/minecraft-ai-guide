"""Runs the client mod's checks that need no game (client/test): the classes they test are compiled alone, from
client/src, without Minecraft on the class path, into build/test-classes."""
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
PURE = ["Sight.java", "Result.java", "Retry.java", "Situation.java", "Reflex.java", "Reflexes.java", "Undo.java", "Moves.java"]   # the classes with no game in them
TESTS = ["hardelele.aiguide.client.SightTest", "hardelele.aiguide.client.ReflexesTest"]


def main():
    shutil.rmtree(OUT, ignore_errors=True)
    os.makedirs(OUT)
    sources = [os.path.join(SRC, f) for f in PURE] + [os.path.join(d, f) for d, _, fs in os.walk(TEST) for f in fs if f.endswith(".java")]
    javac = [tool("javac"), "-encoding", "UTF-8", "-nowarn", "-d", OUT]
    ver = subprocess.run([tool("javac"), "-version"], capture_output=True, text=True)
    if "1.8." not in ver.stdout + ver.stderr:
        javac += ["--release", "8"]
    subprocess.check_call(javac + sources)
    bad = 0
    for t in TESTS:
        bad |= subprocess.call([tool("java"), "-cp", OUT, t])
    sys.exit(bad)


if __name__ == "__main__":
    main()
