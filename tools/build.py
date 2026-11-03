"""Builds AIGuide-<version>.jar from src/ and res/ against lib/ (run tools/setup_lib.py once first).

Needs a JDK: 8, or a newer one (then the classes are compiled with --release 8).
"""
import os
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LIB = os.path.join(ROOT, "lib")
OUT = os.path.join(ROOT, "build", "classes")


def tool(name):
    home = os.environ.get("JAVA_HOME")
    exe = name + (".exe" if os.name == "nt" else "")
    return os.path.join(home, "bin", exe) if home else exe


def main():
    version = re.search(r'VERSION = "([^"]+)"', open(os.path.join(ROOT, "src", "hardelele", "aiguide", "AIGuide.java"), encoding="utf8").read()).group(1)
    jars = [os.path.join(LIB, j) for j in ("mc-forge-srg.jar", "gson-2.2.4.jar", "guava-17.0.jar", "log4j-api-2.0-beta9.jar")]
    if not all(os.path.isfile(j) for j in jars):
        sys.exit("lib/ is not ready: run tools/setup_lib.py")
    shutil.rmtree(OUT, ignore_errors=True)
    os.makedirs(OUT)
    sources = [os.path.join(d, f) for d, _, fs in os.walk(os.path.join(ROOT, "src")) for f in fs if f.endswith(".java")]
    javac = [tool("javac"), "-encoding", "UTF-8", "-nowarn", "-d", OUT, "-cp", os.pathsep.join(jars)]
    ver = subprocess.run([tool("javac"), "-version"], capture_output=True, text=True)
    if not re.search(r"\b1\.8\.", ver.stdout + ver.stderr):
        javac += ["--release", "8"]
    subprocess.check_call(javac + sources)
    target = os.path.join(ROOT, "build", "AIGuide-%s.jar" % version)
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as z:
        for base in (OUT, os.path.join(ROOT, "res")):
            for d, _, fs in os.walk(base):
                for f in fs:
                    p = os.path.join(d, f)
                    z.write(p, os.path.relpath(p, base).replace(os.sep, "/"))
    print("built:", target)


if __name__ == "__main__":
    main()
