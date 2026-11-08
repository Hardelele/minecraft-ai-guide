"""Starts, stops and checks the learning environment's servers (docs/rl/CONTRACT.md): here (at most 2, BelowNormal,
-Xmx at most 1.5G, in build/rl/servers/) or on the laptop over ssh (tools/rl/rlctl.ps1 there, by WMI, BelowNormal,
nothing while League of Legends or another Minecraft runs), each a dedicated Forge server with -Daiguide.rl=PORT.

    python tools/rl/launch_envs.py --host laptop deploy           # the mods, the template, rlctl.ps1 to the laptop
    python tools/rl/launch_envs.py --host laptop --n 4 start      # env 26100..26103, then waits until each answers
    python tools/rl/launch_envs.py --host laptop tunnel --n 4     # ssh -L for each port (no firewall rule needed)
    python tools/rl/launch_envs.py --host laptop status
    python tools/rl/launch_envs.py --host laptop --n 4 stop
    python tools/rl/launch_envs.py --host local --n 2 start       # here (the minecraft port is PORT + 1000)
"""
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
LOCAL = os.path.join(ROOT, "build", "rl", "servers")
SHARE = r"\\192.168.1.9\c\Videos\aiguide-promo\rl"
LAPTOP_ROOT = r"C:\Videos\aiguide-promo\rl"
LAPTOP_TEMPLATE_FROM = r"\\192.168.1.9\c\Videos\aiguide-promo\waves\server-5802634"   # read only: the jars and libraries are copied from it
LOCAL_JAVA = r"C:\Program Files\Java\jre-1.8\bin\java.exe"
FORGE = "forge-1.7.10-10.13.4.1614-1.7.10-universal.jar"
MODS = [os.path.join(ROOT, "build", "AIGuide-0.4.jar"), os.path.join(ROOT, "build", "AIGuideClient-0.2.jar")]
LOCAL_MAX, LOCAL_XMX_MB = 2, 1536


def ask(host, port, msg, timeout=5.0):
    with socket.create_connection((host, port), timeout=timeout) as s:
        s.sendall((json.dumps(msg) + "\n").encode("utf-8"))
        f = s.makefile("r", encoding="utf-8")
        line = f.readline()
        try:
            s.sendall(b'{"op":"close"}\n')
        except OSError:
            pass
        return json.loads(line) if line else None


def wait_ready(host, ports, limit=240):
    t0, left = time.time(), set(ports)
    while left and time.time() - t0 < limit:
        for p in sorted(left):
            try:
                r = ask(host, p, {"op": "info"}, 3)
                if r and r.get("ok"):
                    print("env %d ready after %.0f s: %d actions, obs %d" % (p, time.time() - t0, r["actions"]["n"], r["obs"]["dim"]))
                    left.discard(p)
            except OSError:
                pass
        time.sleep(2)
    for p in left:
        print("env %d did not answer in %d s" % (p, limit))
    return not left


def server_properties(port, seed):
    return "\n".join(["online-mode=false", "server-port=%d" % (port + 1000), "server-ip=127.0.0.1", "level-name=world", "level-seed=%s" % seed,
                      "level-type=DEFAULT", "generate-structures=false", "spawn-monsters=true", "spawn-animals=true", "spawn-npcs=false", "difficulty=2",
                      "gamemode=0", "pvp=true", "max-players=1", "view-distance=4", "snooper-enabled=false", "enable-query=false", "enable-rcon=false",
                      "motd=aiguide rl %d" % port]) + "\n"


def xmx_mb(x):
    x = x.upper()
    return int(float(x[:-1]) * 1024) if x.endswith("G") else int(x.rstrip("M"))


# ---- here --------------------------------------------------------------------------------------------------

def local_ours():
    """our servers here: (pid, port) of java processes started with -Daiguide.rl.dir under build/rl/servers"""
    out = subprocess.run(["powershell", "-NoProfile", "-Command",
                          "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | ForEach-Object { $_.ProcessId.ToString() + ' ' + $_.CommandLine }"],
                         capture_output=True, text=True).stdout
    res = []
    for line in out.splitlines():
        if "-Daiguide.rl=" in line and LOCAL.replace("/", "\\").lower() in line.lower().replace("/", "\\"):
            pid = int(line.split()[0])
            port = int(line.split("-Daiguide.rl=")[1].split()[0])
            res.append((pid, port))
    return res


def local_start(ports, xmx, fast, seed):
    if len(ports) + len(local_ours()) > LOCAL_MAX:
        sys.exit("at most %d servers here (the owner's limit); %d run now" % (LOCAL_MAX, len(local_ours())))
    if xmx_mb(xmx) > LOCAL_XMX_MB:
        sys.exit("-Xmx at most 1.5G here")
    template = os.path.join(LOCAL, "template")
    if not os.path.isfile(os.path.join(template, FORGE)):
        sys.exit("no %s: copy Forge, the server jar and libraries/ into it (see docs/rl/RUN.md)" % template)
    running = {p for _, p in local_ours()}
    for port in ports:
        if port in running:
            print("env %d already runs" % port)
            continue
        d = os.path.join(LOCAL, "env-%d" % port)
        if not os.path.isdir(d):
            shutil.copytree(template, d)
        os.makedirs(os.path.join(d, "mods"), exist_ok=True)
        for m in MODS:
            shutil.copy2(m, os.path.join(d, "mods"))
        open(os.path.join(d, "eula.txt"), "w").write("eula=true\n")
        open(os.path.join(d, "server.properties"), "w").write(server_properties(port, seed))
        args = [LOCAL_JAVA, "-Xms256M", "-Xmx" + xmx, "-XX:+UseG1GC", "-Dfile.encoding=UTF-8", "-Daiguide.rl=%d" % port, "-Daiguide.rl.fast=%s" % str(fast).lower(),
                "-Daiguide.rl.dir=" + d, "-Daiguide.places=false", "-Daiguide.record=false", "-jar", FORGE, "nogui"]
        flags = 0x00004000 | 0x00000200 | 0x08000000   # BELOW_NORMAL_PRIORITY_CLASS | CREATE_NEW_PROCESS_GROUP | CREATE_NO_WINDOW
        p = subprocess.Popen(args, cwd=d, stdout=open(os.path.join(d, "stdout.txt"), "w"), stderr=open(os.path.join(d, "stderr.txt"), "w"),
                             stdin=subprocess.DEVNULL, creationflags=flags)
        print("started env %d (minecraft port %d) pid %d in %s" % (port, port + 1000, p.pid, d))
    return wait_ready("127.0.0.1", ports)


def local_stop(ports):
    for pid, port in local_ours():
        if ports and port not in ports:
            continue
        try:
            ask("127.0.0.1", port, {"op": "shutdown"}, 5)
        except OSError:
            pass
        for _ in range(30):
            if pid not in [q for q, _ in local_ours()]:
                break
            time.sleep(1)
        else:
            subprocess.run(["taskkill", "/PID", str(pid), "/F"], capture_output=True)   # ours only: matched by its folder
        print("stopped env %d pid %d" % (port, pid))


def local_status():
    for pid, port in local_ours():
        try:
            r = ask("127.0.0.1", port, {"op": "info"}, 3)
        except OSError as e:
            r = {"error": str(e)}
        print("env %d pid %d: %s" % (port, pid, json.dumps(r)))


# ---- the laptop ----------------------------------------------------------------------------------------------

def ssh(cmd, check=True):
    r = subprocess.run(["ssh", "-o", "BatchMode=yes", "laptop", cmd], capture_output=True, text=True, encoding="cp866", errors="replace")
    out = "\n".join(l for l in (r.stdout + r.stderr).splitlines() if "post-quantum" not in l and "store now" not in l and "openssh.com/pq" not in l)
    if check and r.returncode not in (0, 2):
        sys.exit("ssh failed (%d): %s" % (r.returncode, out))
    return r.returncode, out


def rlctl(action, ports=None, xmx="1200M", fast=True, seed="5802634"):
    cmd = ("powershell -NoProfile -ExecutionPolicy Bypass -File %s\\rlctl.ps1 -Action %s -Root %s -Xmx %s -Fast %s -Seed %s"
           % (LAPTOP_ROOT, action, LAPTOP_ROOT, xmx, str(fast).lower(), seed))
    if ports:
        cmd += " -Ports " + ",".join(str(p) for p in ports)
    return ssh(cmd)


def laptop_deploy():
    os.makedirs(os.path.join(SHARE, "mods"), exist_ok=True)
    for m in MODS:
        shutil.copy2(m, os.path.join(SHARE, "mods"))
    shutil.copy2(os.path.join(HERE, "rlctl.ps1"), SHARE)
    t = os.path.join(SHARE, "template")
    if not os.path.isfile(os.path.join(t, FORGE)):
        os.makedirs(os.path.join(t, "config"), exist_ok=True)
        for f in (FORGE, "minecraft_server.1.7.10.jar"):
            shutil.copy2(os.path.join(LAPTOP_TEMPLATE_FROM, f), t)
        shutil.copytree(os.path.join(LAPTOP_TEMPLATE_FROM, "libraries"), os.path.join(t, "libraries"), dirs_exist_ok=True)
        for f in ("forge.cfg", "forgeChunkLoading.cfg"):
            shutil.copy2(os.path.join(LAPTOP_TEMPLATE_FROM, "config", f), os.path.join(t, "config"))
    # each server's mods are refreshed at its start (rlctl copies mods\*.jar)
    print("deployed to", SHARE)


def tunnel(ports):
    args = ["ssh", "-o", "BatchMode=yes", "-o", "ExitOnForwardFailure=yes", "-N"]
    for p in ports:
        args += ["-L", "%d:127.0.0.1:%d" % (p, p)]
    args.append("laptop")
    p = subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=0x08000000 if os.name == "nt" else 0)
    time.sleep(3)
    print("tunnel pid %d: %s -> laptop" % (p.pid, ", ".join(str(x) for x in ports)))
    return p


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("action", choices=["deploy", "start", "stop", "status", "tunnel", "busy"])
    ap.add_argument("--host", choices=["local", "laptop"], default="laptop")
    ap.add_argument("--n", type=int, default=1)
    ap.add_argument("--base-port", type=int, default=26100)
    ap.add_argument("--xmx", default="1200M")
    ap.add_argument("--no-fast", action="store_true")
    ap.add_argument("--seed", default="5802634")
    a = ap.parse_args()
    ports = [a.base_port + i for i in range(a.n)]
    fast = not a.no_fast
    if a.host == "local":
        if a.action == "start":
            sys.exit(0 if local_start(ports, a.xmx, fast, a.seed) else 1)
        if a.action == "stop":
            local_stop(ports)
        elif a.action == "status":
            local_status()
        else:
            sys.exit("%s is for the laptop" % a.action)
        return
    if a.action == "deploy":
        laptop_deploy()
    elif a.action == "busy":
        print(rlctl("busy")[1])
    elif a.action == "status":
        print(rlctl("status")[1])
    elif a.action == "stop":
        print(rlctl("stop", ports)[1])
    elif a.action == "start":
        code, out = rlctl("start", ports, a.xmx, fast, a.seed)
        print(out)
        if code == 2:
            sys.exit(2)
        t = tunnel(ports)
        ok = wait_ready("127.0.0.1", ports)
        t.terminate()
        sys.exit(0 if ok else 1)
    elif a.action == "tunnel":
        t = tunnel(ports)
        print("Ctrl+C ends it")
        try:
            t.wait()
        except KeyboardInterrupt:
            t.terminate()


if __name__ == "__main__":
    main()
