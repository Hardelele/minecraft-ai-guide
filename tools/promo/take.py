"""One take for the promo: starts the video client (OptiFine + shaders, AI Guide and its client in singleplayer)
with a director script, records its window with ffmpeg (gdigrab by hwnd, NVENC) from the main menu on, stops
when the script quits. Writes <out>.mp4, <out>.log (the game's output) and <out>.json (the wall clock of the
recording start, for lining up the director's events).

    python take.py <game_dir> <script> <out_base> [--timeout 900] [--size 1600x900]
"""
import argparse, ctypes, ctypes.wintypes as wt, json, os, re, subprocess, sys, threading, time
from capture_config import executable, capture_diag_java_options
from league_guard import active_league, capture_stop_reason

MC = os.environ.get("AIGUIDE_MINECRAFT_HOME", r"C:\Users\harde\AppData\Roaming\.minecraft")
VER = os.environ.get("AIGUIDE_MINECRAFT_VERSION", "OptiForgeLiteLoader 1.7.10")
FFMPEG = r"C:\Users\harde\AppData\Local\Microsoft\WinGet\Packages\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe\ffmpeg-8.1-full_build\bin\ffmpeg.exe"

def require_no_active_league():
    try:
        running = active_league()
    except Exception as exc:
        print("Capture launch blocked: cannot verify whether League of Legends is running (" + str(exc) + ").", flush=True)
        raise SystemExit(2)
    if running:
        print("Capture launch blocked: League of Legends.exe is running. Finish the match before starting a capture.", flush=True)
        raise SystemExit(2)


def write_stop_marker(path, reason):
    """Exclusive creation preserves both an earlier request and its original reason."""
    try:
        with open(path, "x", encoding="utf-8") as marker:
            marker.write(reason + "\n")
    except FileExistsError:
        pass  # an external request already owns this marker; never overwrite/remove it


ap = argparse.ArgumentParser()
ap.add_argument("game_dir"); ap.add_argument("script"); ap.add_argument("out")
ap.add_argument("--timeout", type=int, default=900); ap.add_argument("--size", default="1600x900")
ap.add_argument("--norec", action="store_true")
ap.add_argument("--bench-mode", choices=["roleplaywatch"], help="passive observation of manually assigned roles")
ap.add_argument("--bench-out", help="observer JSON report path (local or UNC)")
ap.add_argument("--source", help="exact source identity written into the observer evidence")
ap.add_argument("--bench-minutes", type=int, default=40, help="positive observer timeout in game minutes")
ap.add_argument("--recordings", help="WorldRec directory, including a laptop UNC share")
ap.add_argument("--capture-source", help="source identity for the recording report (does not assert deployed JAR identity)")
ap.add_argument("--capture-diag", action="store_true", help="opt-in multicamera GL/readback diagnostics")
ap.add_argument("--encoder", choices=("h264_nvenc", "libx264"), default="h264_nvenc", help="internal Recorder encoder; no automatic fallback")
ap.add_argument("--ffmpeg", help="FFmpeg executable; otherwise AIGUIDE_FFMPEG, PATH, then legacy path")
ap.add_argument("--hidden", action="store_true",
                help="run the game on a separate hidden desktop: its window can never take the focus (implies --norec)")
ap.add_argument("--noactivate", action="store_true",
                help="the window shown but never activated (it renders for the game's own recorder, the focus stays where it is); no screen capture, no console")
ap.add_argument("--background", action="store_true",
                help="never take focus: the game window starts minimized and not activated, no console (implies --norec)")
ap.add_argument("--nofocus", action="store_true",
                help="started by the Task Scheduler (not by this console): Windows gives the game no right to take the foreground "
                     "(LWJGL asks for it when its window is made); the window shown inactive, the game's own recorder records")
a = ap.parse_args()
try:
    FFMPEG = executable("ffmpeg", a.ffmpeg)
except ValueError as exc:
    ap.error(str(exc))
if a.encoder == "libx264" and not (a.hidden or a.norec or a.background or a.noactivate or a.nofocus):
    ap.error("--encoder libx264 is for internal recording; use --hidden or --norec")
if a.bench_mode and (not a.bench_out or not a.source or a.bench_minutes <= 0):
    ap.error("--bench-mode requires --bench-out, --source and positive --bench-minutes")
if not a.bench_mode and (a.bench_out or a.source):
    ap.error("--bench-out and --source require --bench-mode")
stopfile = os.path.abspath(a.out) + ".stop"
if os.path.lexists(stopfile):
    ap.error("stop marker already exists: " + stopfile + "; use a new capture output path")
a.game_dir = os.path.abspath(a.game_dir)   # the game is started in it: a relative path would be read from inside it
W, H = a.size.split("x")

vdir = os.path.join(MC, "versions", VER)
d = json.load(open(os.path.join(vdir, VER + ".json"), encoding="utf-8"))
cp = []
for l in d["libraries"]:
    if l.get("natives"): continue
    g, ar, v = l["name"].split(":")[:3]
    cp.append(os.path.join(MC, "libraries", g.replace(".", "/"), ar, v, f"{ar}-{v}.jar"))
cp.append(os.path.join(vdir, VER + ".jar"))
args = d["minecraftArguments"]
subs = {"auth_player_name": "Player", "version_name": VER, "game_directory": a.game_dir,
        "assets_root": os.path.join(MC, "assets"), "assets_index_name": d["assets"],
        "auth_uuid": "00000000000000000000000000000000", "auth_access_token": "0", "user_properties": "{}", "user_type": "legacy"}
game_args = args.split()
for k, v in subs.items():
    game_args = [token.replace("${" + k + "}", v) for token in game_args]
java = os.environ.get("AIGUIDE_JAVA", os.path.join(MC, "runtime", "jre-legacy", "windows", "jre-legacy", "bin", "java.exe"))
cmd = [java, "-Xmx3G", "-Xms512M", "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=20", "-Dfile.encoding=UTF-8", "-Daiguide.video=" + os.path.abspath(a.script), "-Daiguide.video.stopfile=" + stopfile, "-Daiguide.client.smooth=true",
       "-Djava.library.path=" + os.path.join(vdir, "natives"), "-cp", os.pathsep.join(cp), d["mainClass"]] + game_args + ["--width", W, "--height", H]
si, flags = None, 0x4000   # below normal (its ffmpeg inherits it)
cmd[1:1] = ["-Daiguide.video.ffmpeg=" + FFMPEG, "-Daiguide.video.encoder=" + a.encoder]
cmd[1:1] = capture_diag_java_options(a.capture_diag)
if a.recordings:
    cmd.insert(1, "-Daiguide.recordings=" + os.path.abspath(a.recordings))
if a.capture_source:
    cmd.insert(1, "-Daiguide.capture.source=" + a.capture_source)
if a.bench_mode:
    cmd[1:1] = ["-Daiguide.bench=" + a.bench_mode,
                "-Daiguide.bench.out=" + os.path.abspath(a.bench_out),
                "-Daiguide.bench.source=" + a.source,
                "-Daiguide.bench.minutes=" + str(a.bench_minutes)]
# Common to direct, scheduled and hidden-desktop JVM launches, before any foreground lock.
require_no_active_league()
locked = False
if not a.hidden and (a.background or a.noactivate or a.nofocus):
    # Windows lets any process take the foreground once nobody has touched the keyboard or mouse for a while (the
    # foreground lock times out), and LWJGL asks for it when it makes its window. Exactly then this process may lock
    # SetForegroundWindow for everyone (LSFW_LOCK) until the window is up; while someone is at the keys, the game could
    # not take it anyway (and the lock call is refused, which is fine).
    locked = bool(ctypes.windll.user32.LockSetForegroundWindow(1))
    print("foreground lock:", "on" if locked else "refused (someone is at the keys: the game cannot take it either)", flush=True)
if a.background or a.noactivate:   # the window comes up inactive (minimized, or shown for the recorder), no console at all
    a.norec = True
    si = subprocess.STARTUPINFO()
    si.dwFlags |= subprocess.STARTF_USESHOWWINDOW
    si.wShowWindow = 4 if a.noactivate else 7   # SW_SHOWNOACTIVATE / SW_SHOWMINNOACTIVE
    flags |= 0x08000000  # CREATE_NO_WINDOW
if a.nofocus:
    # The foreground may be set only by a process that the foreground's own process started (or that got the last
    # input). A game started from this console inherits that right whenever the console is in front, and LWJGL uses
    # it. Started by the Task Scheduler (svchost), it has no such right at all: the window comes up behind, inactive.
    import zipfile
    a.norec = True
    work = os.path.abspath(a.out) + "_launch"
    os.makedirs(work, exist_ok=True)
    rawlog = os.path.abspath(a.out) + ".raw.log"
    if os.path.exists(rawlog): os.remove(rawlog)
    cpi = cmd.index("-cp")
    pj = os.path.join(work, "classpath.jar")   # the class path in a manifest (the command line stays short)
    with zipfile.ZipFile(pj, "w") as z:
        cls = " ".join("file:///" + j.replace("\\", "/").replace(" ", "%20") for j in cmd[cpi + 1].split(os.pathsep))
        body = "Class-Path: " + cls   # manifest lines at most 72 bytes: continued with a leading space
        out = "Manifest-Version: 1.0\r\n"
        while len(body.encode("utf-8")) > 70:
            cut = 70
            while len(body[:cut].encode("utf-8")) > 70: cut -= 1
            out += body[:cut] + "\r\n"
            body = " " + body[cut:]
        out += body + "\r\n\r\n"
        z.writestr("META-INF/MANIFEST.MF", out)
    gcmd = cmd[:cpi] + ["-cp", pj] + cmd[cpi + 2:]
    bat = os.path.join(work, "run.cmd")
    def q(s): return '"' + s + '"' if (" " in s or "&" in s) and not s.startswith('"') else s
    open(bat, "w", encoding="utf-8").write("@echo off\r\nchcp 65001 >nul\r\ncd /d " + q(a.game_dir) + "\r\nstart \"\" /belownormal /b /wait "
                                          + " ".join(q(x) for x in gcmd) + " > " + q(rawlog) + " 2>&1\r\n")
    vbs = os.path.join(work, "run.vbs")   # no console window either
    open(vbs, "w", encoding="utf-8").write('CreateObject("WScript.Shell").Run "cmd /c ""' + bat + '""", 0, False\r\n')
    tn = "AIGuideTake"
    subprocess.run(["schtasks", "/create", "/tn", tn, "/tr", 'wscript.exe "' + vbs + '"', "/sc", "once", "/st", "00:00", "/f", "/it"], capture_output=True)
    before = set(subprocess.run(["tasklist", "/fi", "imagename eq java.exe", "/fo", "csv", "/nh"], capture_output=True, text=True).stdout.splitlines())
    subprocess.run(["schtasks", "/run", "/tn", tn], capture_output=True)
    pid = None
    for _ in range(100):   # the new java: by its command line (the script's path is in it)
        time.sleep(0.3)
        r = subprocess.run(["powershell", "-NoProfile", "-Command", "Get-CimInstance Win32_Process -Filter \"name='java.exe'\" | ForEach-Object { \"$($_.ProcessId)`t$($_.CommandLine)\" }"],
                           capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
        for l in r.splitlines():
            if "aiguide.video=" + os.path.abspath(a.script) in l:
                pid = int(l.split("\t")[0]); break
        if pid: break
    subprocess.run(["schtasks", "/delete", "/tn", tn, "/f"], capture_output=True)

    class _Proc:   # what the loop below needs of a process
        def __init__(s, pid): s.pid = pid
        def poll(s):
            r = subprocess.run(["tasklist", "/fi", "pid eq %d" % s.pid, "/fo", "csv", "/nh"], capture_output=True, text=True).stdout
            return None if ('"%d"' % s.pid) in r else 0
        def kill(s): subprocess.run(["taskkill", "/pid", str(s.pid), "/f"], capture_output=True)
    if pid is None:
        print("the game did not start from the scheduler", flush=True); sys.exit(3)
    p = _Proc(pid)

    class _Tail:
        def __iter__(self):
            while not os.path.exists(rawlog): time.sleep(0.2)
            with open(rawlog, encoding="utf-8", errors="replace") as f:
                buf = ""
                while True:
                    s = f.read()
                    if s:
                        buf += s
                        while "\n" in buf:
                            line, buf = buf.split("\n", 1)
                            yield line + "\n"
                    elif p.poll() is not None: break
                    else: time.sleep(0.3)
    p.stdout = _Tail()
elif a.hidden:   # on a desktop of its own: whatever the game calls, the desktop someone uses keeps its focus
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import desk
    a.norec = True
    rawlog = os.path.abspath(a.out) + ".raw.log"
    p = desk.spawn(cmd, a.game_dir, rawlog)

    class _Tail:   # the game's output, read from its log as it grows
        def __iter__(self):
            while not os.path.exists(rawlog): time.sleep(0.2)
            with open(rawlog, encoding="utf-8", errors="replace") as f:
                buf = ""
                while True:
                    s = f.read()
                    if s:
                        buf += s
                        while "\n" in buf:
                            line, buf = buf.split("\n", 1)
                            yield line + "\n"
                    elif p.poll() is not None: break
                    else: time.sleep(0.2)
    p.stdout = _Tail()
else:
    p = subprocess.Popen(cmd, cwd=a.game_dir, creationflags=flags, startupinfo=si,
                         stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
print("client pid", p.pid, flush=True)

ctypes.windll.shcore.SetProcessDpiAwareness(2)
user32 = ctypes.windll.user32

def place(h):
    """the window on top, near the top of the screen; returns the client area in screen pixels"""
    r = wt.RECT(); user32.GetWindowRect(h, ctypes.byref(r))
    ww, wh = r.right - r.left, r.bottom - r.top
    sw = user32.GetSystemMetrics(0)
    user32.SetWindowPos(h, -1, max(0, (sw - ww) // 2), 0, 0, 0, 0x0001 | 0x0040)   # HWND_TOPMOST, NOSIZE, SHOWWINDOW
    time.sleep(0.5)
    c = wt.RECT(); user32.GetClientRect(h, ctypes.byref(c))
    pt = wt.POINT(0, 0); user32.ClientToScreen(h, ctypes.byref(pt))
    return pt.x, pt.y, c.right - c.left, c.bottom - c.top
def hwnd_of(pid):
    found = []
    @ctypes.WINFUNCTYPE(wt.BOOL, wt.HWND, wt.LPARAM)
    def cb(h, _):
        o = wt.DWORD(); user32.GetWindowThreadProcessId(h, ctypes.byref(o))
        if o.value == pid and user32.IsWindowVisible(h):
            n = user32.GetWindowTextLengthW(h); b = ctypes.create_unicode_buffer(n + 1); user32.GetWindowTextW(h, b, n + 1)
            if b.value.startswith("Minecraft"): found.append(h)
        return True
    user32.EnumWindows(cb, 0)
    return found[0] if found else None

lines = []; events = {"quit": threading.Event(), "menu": threading.Event(), "launch": threading.Event(), "inworld": threading.Event()}
launch_at = [0.0]
def reader():
    for line in p.stdout:
        line = line.rstrip("\n"); lines.append(line)
        if "[Director]" in line or "AIGuide" in line and ("errand" in line or "ERROR" in line):
            print(line[:300], flush=True)
        if "[Director] menu" in line: events["menu"].set()
        if line.endswith("[Director] quit"): events["quit"].set()   # the quit itself (not "quit waits": someone plays in the window)
        if "[Director] launch world" in line or "[Director] open world" in line: events["launch"].set(); launch_at[0] = time.time()
        if "[Director] inworld" in line: events["inworld"].set()
threading.Thread(target=reader, daemon=True).start()

rec = None; meta = {"stopfile": stopfile, "capture_diag": a.capture_diag}; hung = False
t0 = time.time()
checked = False
next_league_check = 0.0
stop_requested = None
while p.poll() is None:
    now = time.monotonic()
    if stop_requested is None and now >= next_league_check:
        next_league_check = now + 2
        reason = "external stop marker" if os.path.lexists(stopfile) else None
        if reason is None:
            reason = capture_stop_reason()
        if reason is None and events["launch"].is_set() and not events["inworld"].is_set() and time.time() - launch_at[0] > 120:
            hung = True
            reason = "world did not open in two minutes; stopping owned capture"
        if reason is None and time.time() - t0 >= a.timeout:
            reason = "capture timeout; stopping owned capture"
        if reason is not None:
            stop_requested = now
            meta["stop_reason"] = reason; meta["stop_requested_ms"] = int(time.time() * 1000)
            try:
                write_stop_marker(stopfile, reason)
                print("Cooperative capture stop requested: " + reason, flush=True)
            except OSError as exc:
                meta["stop_marker_error"] = str(exc)
                print("Cannot deliver cooperative stop marker: " + str(exc), flush=True)
    if stop_requested is not None and now - stop_requested > 180:
        meta["stop_unconfirmed"] = True
        print("Owned capture has not confirmed shutdown after 180 seconds; no process was killed and no retry will run.", flush=True)
        break
    if a.hidden and not checked and time.time() - t0 > 20:   # read only: where its windows are
        mine = [t for h, t in desk.windows() if t.startswith("Minecraft")]
        if mine:
            checked = True
            print("window on the hidden desktop:", mine, "| on the user's desktop:", len(desk.on_user_desktop(p.pid)), flush=True)
    if (a.background or a.noactivate or a.nofocus) and not checked:   # read only: did the window keep out of the way
        h = hwnd_of(p.pid)
        if h:
            checked = True
            time.sleep(8)   # the window's own start (LWJGL's foreground request comes while it is made)
            fg = user32.GetForegroundWindow()
            print("window", hex(h), "minimized" if user32.IsIconic(h) else "NOT minimized",
                  "TOOK THE FOREGROUND" if fg == h else "not in the foreground", flush=True)
            if locked: user32.LockSetForegroundWindow(2); locked = False   # LSFW_UNLOCK: as it was
    if stop_requested is None and rec is None and events["menu"].is_set() and not a.norec:
        h = hwnd_of(p.pid)
        if h:
            meta["start_ms_before"] = int(time.time() * 1000)
            x, y, cw, ch = place(h); meta["client"] = [x, y, cw, ch]
            rec = subprocess.Popen([FFMPEG, "-y", "-loglevel", "error", "-f", "lavfi", "-i",
                                    f"ddagrab=output_idx=0:framerate=60:draw_mouse=0:offset_x={x}:offset_y={y}:video_size={cw - cw % 2}x{ch - ch % 2},hwdownload,format=bgra",
                                    "-fps_mode", "cfr", "-r", "60", "-c:v", "h264_nvenc", "-preset", "p5", "-rc", "vbr", "-cq", "16", "-pix_fmt", "yuv420p", a.out + ".mp4"],
                                   stdin=subprocess.PIPE)
            meta["start_ms_after"] = int(time.time() * 1000)
            print("recording hwnd", hex(h), flush=True)
    if events["quit"].is_set(): time.sleep(1.5); break
    time.sleep(0.2)
if locked:
    user32.LockSetForegroundWindow(2)
    locked = False
if rec:
    try: rec.communicate(b"q", timeout=30)
    except Exception: rec.kill()
    meta["stop_ms"] = int(time.time() * 1000)
for _ in range(60):   # the game closes itself after its quit; it is killed only if it hangs there (never while it waits for a player)
    if p.poll() is not None or not events["quit"].is_set(): break
    time.sleep(0.5)
if p.poll() is None and events["quit"].is_set():
    if stop_requested is None: p.kill()
    else:
        meta["stop_unconfirmed"] = True
        print("Cooperative shutdown is still saving; leaving the owned client to finish normally.", flush=True)
script_failures = [line for line in lines if "[Director] script aborted:" in line or "[Director] step failed:" in line]
if script_failures:
    meta["script_failure"] = script_failures[-1][:500]
open(a.out + ".log", "w", encoding="utf-8").write("\n".join(lines))
json.dump(meta, open(a.out + ".json", "w"))
print("done", meta, "after", int(time.time() - t0), "s")
if hung and not script_failures and stop_requested is None and not os.path.lexists(stopfile) and int(os.environ.get("TAKE_ATTEMPT", "1")) < 3:   # again, with the half-made world removed
    require_no_active_league()   # a match may have started since the preceding capture
    import shutil
    for l in open(a.script, encoding="utf-8"):
        l = l.strip()
        if l.startswith("create ") and l.count("|") >= 2:
            wd = os.path.join(a.game_dir, "saves", l.split("|")[2].strip())
            if os.path.isdir(wd) and os.path.abspath(wd).startswith(os.path.abspath(os.path.join(a.game_dir, "saves"))): shutil.rmtree(wd)
    os.environ["TAKE_ATTEMPT"] = str(int(os.environ.get("TAKE_ATTEMPT", "1")) + 1)
    print("retry", os.environ["TAKE_ATTEMPT"], flush=True)
    sys.exit(subprocess.call([sys.executable] + sys.argv))
for l in lines:
    if re.search(r"(?i)(FATAL|Exception|shader|OptiFine.*load)", l) and not re.search(r"Update check|VersionChecker", l): print(l[:240])
if script_failures:
    sys.exit(4)
