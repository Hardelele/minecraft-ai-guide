"""Makes lib/mc-forge-mcp.jar: the SRG library of setup_lib.py with readable MCP names (getBlock instead of
func_147439_a), for the client mod, and lib/srg2mcp.srg, the mapping build_client.py turns the compiled
client mod back to SRG names with. Run setup_lib.py first. This is what ForgeGradle's deobf/reobf does.
"""
import csv
import io
import os
import shutil
import subprocess
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from setup_lib import CACHE, LIB, fetch, java  # noqa: E402

MCP_STABLE = "https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp_stable/12-1.7.10/mcp_stable-12-1.7.10.zip"
# what Minecraft 1.7.10 itself runs with: the network (both mods) and the keyboard and OpenGL (the client mod)
LIBS = {
    "netty-all-4.0.10.Final.jar": "https://libraries.minecraft.net/io/netty/netty-all/4.0.10.Final/netty-all-4.0.10.Final.jar",
    "lwjgl-2.9.1.jar": "https://libraries.minecraft.net/org/lwjgl/lwjgl/lwjgl/2.9.1/lwjgl-2.9.1.jar",
    "authlib-1.5.21.jar": "https://libraries.minecraft.net/com/mojang/authlib/1.5.21/authlib-1.5.21.jar",
}


def names(z, name):
    with z.open(name) as f:
        return {r["searge"]: r["name"] for r in csv.DictReader(io.TextIOWrapper(f, "utf-8"))}


def main():
    for name, url in LIBS.items():
        shutil.copy(fetch(url, name), os.path.join(LIB, name))
    stable = fetch(MCP_STABLE, "mcp_stable-12-1.7.10.zip")
    with zipfile.ZipFile(stable) as z:
        methods, fields = names(z, "methods.csv"), names(z, "fields.csv")
    out = os.path.join(LIB, "srg2mcp.srg")
    n = 0
    with open(os.path.join(CACHE, "joined.srg"), encoding="utf-8") as src, open(out, "w", encoding="utf-8") as dst:
        for line in src:
            p = line.split()
            if p[0] == "FD:":   # FD: notch/a srgcls/field_1
                cls, f = p[2].rsplit("/", 1)
                if f in fields:
                    dst.write("FD: %s/%s %s/%s\n" % (cls, f, cls, fields[f]))
                    n += 1
            elif p[0] == "MD:":   # MD: notch/a desc srgcls/func_1 srgdesc
                cls, m = p[3].rsplit("/", 1)
                if m in methods:
                    dst.write("MD: %s/%s %s %s/%s %s\n" % (cls, m, p[4], cls, methods[m], p[4]))
                    n += 1
    print(n, "names in", out)
    back = os.path.join(LIB, "mcp2srg.srg")   # the other way, for build_client.py
    with open(out, encoding="utf-8") as src, open(back, "w", encoding="utf-8") as dst:
        for line in src:
            p = line.split()
            if p[0] == "FD:":
                dst.write("FD: %s %s\n" % (p[2], p[1]))
            else:
                dst.write("MD: %s %s %s %s\n" % (p[3], p[4], p[1], p[2]))
    ss = os.path.join(CACHE, "SpecialSource-shaded.jar")
    jar = os.path.join(LIB, "mc-forge-mcp.jar")
    subprocess.check_call([java(), "-jar", ss, "-i", os.path.join(LIB, "mc-forge-srg.jar"), "-o", jar, "-m", out])
    print("made", jar)


if __name__ == "__main__":
    main()
