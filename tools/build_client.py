"""Builds AIGuideClient-<version>.jar from client/src and client/res (run tools/setup_lib.py and
tools/setup_mcp.py once first). The client mod is written with readable MCP names, compiled against
lib/mc-forge-mcp.jar and renamed back to SRG names (what Forge runs) with SpecialSource."""
import os
import re
import shutil
import subprocess
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build import tool  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LIB = os.path.join(ROOT, "lib")
SRC = os.path.join(ROOT, "client", "src")
RES = os.path.join(ROOT, "client", "res")
OUT = os.path.join(ROOT, "build", "client-classes")
PLACES = os.path.join(ROOT, "res", "assets", "aiguide", "places", "places.bin")
POLICY = os.path.join(ROOT, "res", "assets", "aiguide", "rl", "policy.bin")


def main():
    jars = [os.path.join(LIB, j) for j in ("mc-forge-mcp.jar", "gson-2.2.4.jar", "guava-17.0.jar", "log4j-api-2.0-beta9.jar",
                                           "netty-all-4.0.10.Final.jar", "lwjgl-2.9.1.jar", "authlib-1.5.21.jar")]
    shutil.rmtree(OUT, ignore_errors=True)
    os.makedirs(OUT)
    sources = [os.path.join(d, f) for d, _, fs in os.walk(SRC) for f in fs if f.endswith(".java")]
    javac = [tool("javac"), "-encoding", "UTF-8", "-nowarn", "-d", OUT, "-cp", os.pathsep.join(jars)]
    if subprocess.run([tool("javac"), "-version"], capture_output=True, text=True).stderr.find("1.8") < 0 and \
            subprocess.run([tool("javac"), "-version"], capture_output=True, text=True).stdout.find("1.8") < 0:
        javac += ["--release", "8"]
    subprocess.check_call(javac + sources)
    main_src = open(os.path.join(SRC, "hardelele", "aiguide", "client", "AIGuideClient.java"), encoding="utf8").read()
    version = re.search(r'VERSION = "([^"]+)"', main_src).group(1)
    mcp = os.path.join(ROOT, "build", "client-mcp.jar")
    with zipfile.ZipFile(mcp, "w", zipfile.ZIP_DEFLATED) as z:
        for d, _, fs in os.walk(OUT):
            for f in fs:
                p = os.path.join(d, f)
                z.write(p, os.path.relpath(p, OUT).replace(os.sep, "/"))
    target = os.path.join(ROOT, "build", "AIGuideClient-%s.jar" % version)
    ss = os.path.join(LIB, "download", "SpecialSource-shaded.jar")
    # --live: the library's classes on the class path tell what inherits what (a call on WorldClient is World's method)
    subprocess.check_call([tool("java"), "-cp", os.pathsep.join([ss] + jars), "net.md_5.specialsource.SpecialSource", "--live",
                           "-i", mcp, "-o", target, "-m", os.path.join(LIB, "mcp2srg.srg")])
    with zipfile.ZipFile(target, "a") as z:
        for d, _, fs in os.walk(RES):
            for f in fs:
                p = os.path.join(d, f)
                z.write(p, os.path.relpath(p, RES).replace(os.sep, "/"))
        # the places net's weights (places/CONTRACT.md: res/assets/aiguide/places/places.bin, the model's branch puts them there)
        if os.path.isfile(PLACES):
            z.write(PLACES, "assets/aiguide/places/places.bin", compress_type=zipfile.ZIP_DEFLATED)
        else:
            print("no", PLACES, "- the jar has no places weights (Places stays off unless -Daiguide.places.weights is given)")
        # the crew policy's weights (tools/rl/export_policy.py): the minds' default brain
        if os.path.isfile(POLICY):
            z.write(POLICY, "assets/aiguide/rl/policy.bin", compress_type=zipfile.ZIP_DEFLATED)
        else:
            print("no", POLICY, "- the jar has no policy weights (the minds need -Daiguide.policy.weights or -Daiguide.mind=llm)")
    print("built:", target)


if __name__ == "__main__":
    main()
