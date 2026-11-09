"""Writes the contract's files from the code: rl/actions.json, rl/obs.json, rl/reward.json, rl/achievements.json of
contract 2 (RlSpec.java, RlAchievements.java: docs/rl/CONTRACT-v2.md) and rl/v1/*.json of contract 1, frozen
(RlSpec1.java: docs/rl/CONTRACT.md). The classes with no game in them are compiled alone (as tools/test_client.py's
pure tests).

    python tools/rl/gen_spec.py            # writes rl/ and rl/v1/
    python tools/rl/gen_spec.py --check    # fails when rl/ or rl/v1/ is not what the code says
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
RL = [f for f in ["RlSpec.java", "RlSpec1.java", "RlAchievements.java", "RlReward.java"] if f not in PURE]
V2 = ("actions.json", "obs.json", "reward.json", "achievements.json")
V1 = ("actions.json", "obs.json", "reward.json")


def compile_spec():
    shutil.rmtree(OUT, ignore_errors=True)
    os.makedirs(OUT)
    release = []
    ver = subprocess.run([tool("javac"), "-version"], capture_output=True, text=True)
    if "1.8." not in ver.stdout + ver.stderr:
        release = ["--release", "8"]
    subprocess.check_call([tool("javac"), "-encoding", "UTF-8", "-nowarn", "-d", OUT, "-cp", GSON] + release
                          + [os.path.join(SRC, f) for f in PURE + RL])


def run(cls, *args):
    subprocess.check_call([tool("java"), "-Dfile.encoding=UTF-8", "-cp", os.pathsep.join([OUT, GSON]), "hardelele.aiguide.client." + cls] + list(args))


def write(dest2, dest1):
    run("RlSpec", dest2)
    run("RlSpec1", dest1)


def cases(path, n=2000):
    """random raw facts with the mask Java gives (RlSpec.cases), for the check of the Python reading of the rules"""
    run("RlSpec", "cases", path, str(n))


def main():
    compile_spec()
    rl = os.path.join(ROOT, "rl")
    if "--check" in sys.argv:
        tmp = tempfile.mkdtemp()
        write(os.path.join(tmp, "v2"), os.path.join(tmp, "v1"))
        bad = [f for f in V2 if not os.path.isfile(os.path.join(rl, f)) or not filecmp.cmp(os.path.join(tmp, "v2", f), os.path.join(rl, f), shallow=False)]
        bad += ["v1/" + f for f in V1 if not os.path.isfile(os.path.join(rl, "v1", f))
                or not filecmp.cmp(os.path.join(tmp, "v1", f), os.path.join(rl, "v1", f), shallow=False)]
        shutil.rmtree(tmp, ignore_errors=True)
        if bad:
            sys.exit("rl/ differs from RlSpec.java / RlSpec1.java: " + ", ".join(bad) + " (python tools/rl/gen_spec.py)")
        print("rl/ is what RlSpec.java says, rl/v1/ what RlSpec1.java says")
        return
    write(rl, os.path.join(rl, "v1"))


if __name__ == "__main__":
    main()
