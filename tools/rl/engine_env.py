"""The game's engine as the learner's environment (docs/rl/ENGINE.md): starts and stops the environments here, either
as dedicated servers (one JVM each, `server`) or as arenas in one JVM (`engine`: tools/rl/engine/EngineHost.java,
each arena a Forge server in its own class loader, its RL port the same TCP protocol). Every JVM runs at Idle priority
while League of Legends runs (and refuses more than 2 arenas then), else at BelowNormal.

    python tools/rl/engine_env.py start --kind server --n 2             # ports 26200, 26201
    python tools/rl/engine_env.py start --kind engine --n 8 --xmx 8G    # one JVM, ports 26200..26207
    python tools/rl/engine_env.py status
    python tools/rl/engine_env.py stop

`--opt` (engine and server alike): -Daiguide.rl.engine=true, what the learner never sees switched off (RlEngine.java);
`--jfr FILE` records the JVM with Java Flight Recorder (the profile: tools/rl/engine_prof.py).
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import socket
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
BASE = os.path.join(ROOT, "build", "rl", "servers")
TEMPLATE = os.path.join(BASE, "template")
WORLD = os.path.join(BASE, "world-5802634")   # a generated world (seed 5802634): every environment starts from a copy
JAVA = r"C:\Program Files\Java\jre-1.8\bin\java.exe"
JDK = os.environ.get("JAVA_HOME") or r"C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot"
FORGE = "forge-1.7.10-10.13.4.1614-1.7.10-universal.jar"
MODS = [os.path.join(os.environ.get("AIGUIDE_ENGINE_MODS") or os.path.join(ROOT, "build"), j) for j in ("AIGuide-0.4.jar", "AIGuideClient-0.2.jar")]   # AIGUIDE_ENGINE_MODS: another build's jars
HOST_SRC = os.path.join(HERE, "engine")
HOST_OUT = os.path.join(ROOT, "build", "engine-host")
MARK = "-Daiguide.rl.envdir="   # our JVMs carry it (found again by it)
IDLE, BELOW_NORMAL, NO_WINDOW, GROUP = 0x00000040, 0x00004000, 0x08000000, 0x00000200


def league():
    out = subprocess.run(["tasklist", "/FI", "IMAGENAME eq League of Legends.exe", "/NH"], capture_output=True, text=True).stdout
    return "League of Legends.exe" in out


def ask(port, msg, timeout=5.0, host="127.0.0.1"):
    with socket.create_connection((host, port), timeout=timeout) as s:
        s.sendall((json.dumps(msg) + "\n").encode("utf-8"))
        line = s.makefile("r", encoding="utf-8").readline()
        try:
            s.sendall(b'{"op":"close"}\n')
        except OSError:
            pass
        return json.loads(line) if line else None


def wait_ready(ports, limit=600):
    t0, left = time.time(), set(ports)
    while left and time.time() - t0 < limit:
        for p in sorted(left):
            try:
                r = ask(p, {"op": "info"}, 3)
                if r and r.get("ok"):
                    print("env %d ready after %.0f s" % (p, time.time() - t0), flush=True)
                    left.discard(p)
            except OSError:
                pass
        time.sleep(1)
    return not left


def ours():
    """our JVMs: (pid, command line)"""
    out = subprocess.run(["powershell", "-NoProfile", "-Command",
                          "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | ForEach-Object { $_.ProcessId.ToString() + ' ' + $_.CommandLine }"],
                         capture_output=True, text=True).stdout
    return [(int(l.split()[0]), l) for l in out.splitlines() if MARK in l and BASE.lower() in l.lower()]


def jvm_args(xmx, opt, jfr, extra=()):
    a = [JAVA, "-Xms512M", "-Xmx" + xmx, "-XX:+UseG1GC", "-Dfile.encoding=UTF-8", "-Daiguide.rl.fast=true", "-Daiguide.rl.contract=3.1",
         "-Daiguide.places=false", "-Daiguide.record=false"]
    if opt:
        a.append("-Daiguide.rl.engine=true")
    if CHECK:
        a.append("-Daiguide.rl.engine.check=%d" % CHECK)
    if jfr:
        a += ["-XX:+UnlockCommercialFeatures", "-XX:+FlightRecorder", "-XX:FlightRecorderOptions=stackdepth=512",
              "-XX:StartFlightRecording=delay=%ds,duration=%ds,filename=%s,settings=profile" % (jfr[1], jfr[2], jfr[0])]
    return a + list(extra)


def env_dir(name):
    d = os.path.join(BASE, name)
    if not os.path.isdir(d):
        shutil.copytree(TEMPLATE, d, ignore=shutil.ignore_patterns("mods"))
    os.makedirs(os.path.join(d, "mods"), exist_ok=True)
    for m in MODS:
        shutil.copy2(m, os.path.join(d, "mods"))
    open(os.path.join(d, "eula.txt"), "w").write("eula=true\n")
    return d


def fresh_world(dst):
    if os.path.isdir(dst):
        shutil.rmtree(dst)   # a copy of ours under build/rl/servers (never lib)
    shutil.copytree(WORLD, dst)


def props(mc_port):
    return "\n".join(["online-mode=false", "server-port=%d" % mc_port, "server-ip=127.0.0.1", "level-name=world", "level-seed=5802634",
                      "level-type=DEFAULT", "generate-structures=false", "spawn-monsters=true", "spawn-animals=true", "spawn-npcs=false",
                      "difficulty=2", "gamemode=0", "pvp=true", "max-players=1", "view-distance=4", "snooper-enabled=false",
                      "enable-query=false", "enable-rcon=false", "motd=aiguide engine"]) + "\n"


def flags(n=1):
    if league() and n > 2:
        sys.exit("League of Legends runs: at most 2 arenas (Idle)")
    return (IDLE if league() else BELOW_NORMAL) | NO_WINDOW | GROUP


def start_servers(ports, xmx, opt, jfr):
    procs = []
    for port in ports:
        d = env_dir("srv-%d" % port)
        fresh_world(os.path.join(d, "world"))
        open(os.path.join(d, "server.properties"), "w").write(props(port + 1000))
        args = jvm_args(xmx, opt, (jfr.replace(".jfr", "-%d.jfr" % port),) + JFR_TIMES if jfr else None,
                        ["-Daiguide.rl=%d" % port, MARK + d, "-jar", FORGE, "nogui"])
        p = subprocess.Popen(args, cwd=d, stdout=open(os.path.join(d, "stdout.txt"), "w"), stderr=open(os.path.join(d, "stderr.txt"), "w"),
                             stdin=subprocess.DEVNULL, creationflags=flags(len(ports)))
        print("server %d pid %d in %s" % (port, p.pid, d), flush=True)
        procs.append(p)
    return procs


def build_host():
    os.makedirs(HOST_OUT, exist_ok=True)
    src = [os.path.join(HOST_SRC, "EngineHost.java")]
    subprocess.check_call([os.path.join(JDK, "bin", "javac"), "--release", "8", "-nowarn", "-d", HOST_OUT] + src)


def start_engine(ports, xmx, opt, jfr):
    build_host()
    d = env_dir("engine-%d" % ports[0])   # (a folder of its own: two hosts at once do not share mods/)
    for i, port in enumerate(ports):
        fresh_world(os.path.join(d, "arena-%d" % port, "world"))
    open(os.path.join(d, "server.properties"), "w").write(props(0))
    extra = ["-XX:ReservedCodeCacheSize=1024M", "-XX:MaxMetaspaceSize=4G", "-Dio.netty.eventLoopThreads=1", "-Dlog4j.configurationFile=" + os.path.join(HOST_SRC, "log4j2-engine.xml"),
             MARK + d, "-cp", HOST_OUT, "EngineHost", "--dir", d, "--ports", ",".join(str(p) for p in ports)]
    args = jvm_args(xmx, opt, (jfr,) + JFR_TIMES if jfr else None, extra)
    p = subprocess.Popen(args, cwd=d, stdout=open(os.path.join(d, "stdout.txt"), "w"), stderr=open(os.path.join(d, "stderr.txt"), "w"),
                         stdin=subprocess.DEVNULL, creationflags=flags(len(ports)))
    print("engine pid %d, %d arenas in %s" % (p.pid, len(ports), d), flush=True)
    return [p]


JFR_TIMES = (60, 120)   # (delay, duration) s: set by --jfr-delay/--jfr-seconds
CHECK = 0               # --check N: every Nth fast path search compared with the game's


def stop(ports=None):
    """ours serving these ports (a server by its -Daiguide.rl, the engine by its --ports); all ours when none named"""
    def mine(cmd):
        return not ports or any(("-Daiguide.rl=%d " % p) in cmd or ("--ports " in cmd and str(p) in cmd.split("--ports ")[1].split()[0].split(",")) for p in ports)
    for pid, cmd in [(q, c) for q, c in ours() if mine(c)]:
        for p in (ports or []):
            try:
                ask(p, {"op": "shutdown"}, 3)
            except OSError:
                pass
        for _ in range(20):
            if pid not in [q for q, _ in ours()]:
                break
            time.sleep(1)
        else:
            subprocess.run(["taskkill", "/PID", str(pid), "/F"], capture_output=True)   # ours only: matched by its folder and mark
        print("stopped pid", pid)


def main():
    global JFR_TIMES, CHECK
    ap = argparse.ArgumentParser()
    ap.add_argument("action", choices=["start", "stop", "status"])
    ap.add_argument("--kind", choices=["server", "engine"], default="engine")
    ap.add_argument("--n", type=int, default=1)
    ap.add_argument("--base-port", type=int, default=26200)
    ap.add_argument("--xmx", default="1536M")
    ap.add_argument("--opt", action="store_true", help="-Daiguide.rl.engine=true: the learner-unseen work off")
    ap.add_argument("--jfr", default="", help="a .jfr file: record the JVM(s)")
    ap.add_argument("--jfr-delay", type=int, default=60)
    ap.add_argument("--jfr-seconds", type=int, default=120)
    ap.add_argument("--check", type=int, default=0, help="--opt: every Nth fast path search also by the game's, compared (info.engine)")
    a = ap.parse_args()
    JFR_TIMES = (a.jfr_delay, a.jfr_seconds)
    CHECK = a.check
    ports = [a.base_port + i for i in range(a.n)]
    if a.action == "start":
        if league() and a.n > 2:
            sys.exit("League of Legends runs: at most 2 arenas (Idle)")
        if ours():
            sys.exit("ours already run: stop them first")
        (start_servers if a.kind == "server" else start_engine)(ports, a.xmx, a.opt, a.jfr)
        sys.exit(0 if wait_ready(ports) else 1)
    if a.action == "stop":
        stop(ports)
    else:
        for pid, cmd in ours():
            print(pid, cmd[:200])


if __name__ == "__main__":
    main()
