"""Writes rl/open.json, the open world's spaces (docs/rl/OPEN-WORLD.md), from OpenSpec.java (no game in it: compiled
alone, as tools/test_client.py's pure tests), and reads it for the Python side.

    python tools/rl/open_spec.py            # writes rl/open.json
    python tools/rl/open_spec.py --check    # fails when rl/open.json is not what OpenSpec.java says
"""
from __future__ import annotations

import filecmp
import json
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
SPEC = os.path.join(ROOT, "rl", "open.json")
OUT = os.path.join(ROOT, "build", "open-spec-classes")


def compile_spec():
    sys.path.insert(0, os.path.dirname(HERE))
    from build import tool  # noqa: E402
    from test_client import SRC, GSON  # noqa: E402
    shutil.rmtree(OUT, ignore_errors=True)
    os.makedirs(OUT)
    ver = subprocess.run([tool("javac"), "-version"], capture_output=True, text=True)
    release = [] if "1.8." in ver.stdout + ver.stderr else ["--release", "8"]
    subprocess.check_call([tool("javac"), "-encoding", "UTF-8", "-nowarn", "-d", OUT, "-cp", GSON] + release + [os.path.join(SRC, "OpenSpec.java")])
    return lambda dest: subprocess.check_call([tool("java"), "-Dfile.encoding=UTF-8", "-cp", os.pathsep.join([OUT, GSON]), "hardelele.aiguide.client.OpenSpec", dest])


def load(path: str = SPEC) -> dict:
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def main():
    run = compile_spec()
    if "--check" in sys.argv:
        tmp = tempfile.mkdtemp()
        f = os.path.join(tmp, "open.json")
        run(f)
        same = os.path.isfile(SPEC) and filecmp.cmp(f, SPEC, shallow=False)
        shutil.rmtree(tmp, ignore_errors=True)
        if not same:
            sys.exit("rl/open.json differs from OpenSpec.java (python tools/rl/open_spec.py)")
        print("rl/open.json is what OpenSpec.java says")
        return
    run(SPEC)


if __name__ == "__main__":
    main()
