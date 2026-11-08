"""Writes rl/actions.json, rl/obs.json, rl/reward.json from the code (RlSpec.java: the source of the contract's spaces,
docs/rl/CONTRACT.md). The classes with no game in them are compiled alone (as tools/test_client.py's pure tests).

    python tools/rl/gen_spec.py            # writes rl/
    python tools/rl/gen_spec.py --check    # fails when rl/ is not what the code says
"""
import filecmp
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.dirname(HERE))
from build import tool  # noqa: E402
from test_client import PURE, SRC, GSON  # noqa: E402

OUT = os.path.join(ROOT, "build", "rl-spec-classes")
RL = ["RlSpec.java", "RlReward.java"]


def compile_spec():
    shutil.rmtree(OUT, ignore_errors=True)
    os.makedirs(OUT)
    release = []
    ver = subprocess.run([tool("javac"), "-version"], capture_output=True, text=True)
    if "1.8." not in ver.stdout + ver.stderr:
        release = ["--release", "8"]
    subprocess.check_call([tool("javac"), "-encoding", "UTF-8", "-nowarn", "-d", OUT, "-cp", GSON] + release
                          + [os.path.join(SRC, f) for f in PURE + RL])


def write(dest):
    subprocess.check_call([tool("java"), "-Dfile.encoding=UTF-8", "-cp", os.pathsep.join([OUT, GSON]), "hardelele.aiguide.client.RlSpec", dest])


def main():
    compile_spec()
    rl = os.path.join(ROOT, "rl")
    if "--check" in sys.argv:
        tmp = tempfile.mkdtemp()
        write(tmp)
        bad = [f for f in ("actions.json", "obs.json", "reward.json") if not os.path.isfile(os.path.join(rl, f)) or not filecmp.cmp(os.path.join(tmp, f), os.path.join(rl, f), shallow=False)]
        shutil.rmtree(tmp, ignore_errors=True)
        if bad:
            sys.exit("rl/ differs from RlSpec.java: " + ", ".join(bad) + " (python tools/rl/gen_spec.py)")
        print("rl/ is what RlSpec.java says")
        return
    write(rl)


if __name__ == "__main__":
    main()
