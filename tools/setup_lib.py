"""Downloads what AI Guide compiles against into lib/ (Python 3, Java on PATH or in JAVA_HOME).

The mod is compiled against Minecraft 1.7.10 and Forge 10.13.4.1614 with SRG names (func_..., field_...),
which is what Forge mods run against. No ForgeGradle: the vanilla client and the Forge universal jar are
merged and renamed from Mojang's names to SRG names with SpecialSource and the MCP mappings.
Nothing downloaded here is part of the mod or of this repository.
"""
import hashlib
import io
import json
import os
import shutil
import subprocess
import sys
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LIB = os.path.join(ROOT, "lib")
CACHE = os.path.join(LIB, "download")
FORGE = "https://maven.minecraftforge.net/net/minecraftforge/forge/1.7.10-10.13.4.1614-1.7.10/forge-1.7.10-10.13.4.1614-1.7.10-universal.jar"
MCP_SRG = "https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp/1.7.10/mcp-1.7.10-srg.zip"
SPECIAL_SOURCE = "https://repo1.maven.org/maven2/net/md-5/SpecialSource/1.11.0/SpecialSource-1.11.0-shaded.jar"
MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
MAVEN = {
    "gson-2.2.4.jar": "https://repo1.maven.org/maven2/com/google/code/gson/gson/2.2.4/gson-2.2.4.jar",
    "guava-17.0.jar": "https://repo1.maven.org/maven2/com/google/guava/guava/17.0/guava-17.0.jar",
    "log4j-api-2.0-beta9.jar": "https://repo1.maven.org/maven2/org/apache/logging/log4j/log4j-api/2.0-beta9/log4j-api-2.0-beta9.jar",
}


def get(url):
    # some mirrors turn away Python's default user agent
    return urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "aiguide-build/1.0"}), timeout=120)


def fetch(url, name, sha1=None):
    path = os.path.join(CACHE, name)
    if not os.path.isfile(path):
        print("downloading", url)
        with get(url) as r:
            data = r.read()
        if sha1 and hashlib.sha1(data).hexdigest() != sha1:
            sys.exit("checksum mismatch: " + url)
        with open(path, "wb") as f:
            f.write(data)
    return path


def java():
    home = os.environ.get("JAVA_HOME")
    exe = "java.exe" if os.name == "nt" else "java"
    return os.path.join(home, "bin", exe) if home else exe


def main():
    os.makedirs(CACHE, exist_ok=True)
    versions = json.load(get(MANIFEST))["versions"]
    meta_url = next(v["url"] for v in versions if v["id"] == "1.7.10")
    client = json.load(get(meta_url))["downloads"]["client"]
    client_jar = fetch(client["url"], "client-1.7.10.jar", client["sha1"])
    forge_jar = fetch(FORGE, "forge-universal.jar")
    srg_zip = fetch(MCP_SRG, "mcp-1.7.10-srg.zip")
    ss = fetch(SPECIAL_SOURCE, "SpecialSource-shaded.jar")
    for name, url in MAVEN.items():
        shutil.copy(fetch(url, name), os.path.join(LIB, name))

    srg = os.path.join(CACHE, "joined.srg")
    with zipfile.ZipFile(srg_zip) as z, open(srg, "wb") as f:
        f.write(z.read("joined.srg"))

    merged = os.path.join(CACHE, "merged.jar")   # the client and Forge, without signatures
    seen = set()
    with zipfile.ZipFile(merged, "w", zipfile.ZIP_DEFLATED) as out:
        for jar in (forge_jar, client_jar):
            with zipfile.ZipFile(jar) as z:
                for e in z.infolist():
                    n = e.filename
                    if n in seen or n.startswith("META-INF/") or not n.endswith(".class"):
                        continue
                    seen.add(n)
                    out.writestr(n, z.read(n))

    target = os.path.join(LIB, "mc-forge-srg.jar")
    subprocess.check_call([java(), "-jar", ss, "--in-jar", merged, "--out-jar", target, "--srg-in", srg, "--quiet"])
    print("ready:", target)


if __name__ == "__main__":
    main()
