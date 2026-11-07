"""Render four synchronized hero tracks on a hidden Windows desktop.

The original Commander capture remains the fifth track. New captures have a
.timeline.tsv sidecar so an editor can align interface frames by world tick.
"""
import argparse
import csv
import json
import math
import os
import time
from fractions import Fraction
from pathlib import Path
import subprocess
import sys
from capture_impact import low_impact
from capture_config import executable, existing_capture_artifacts, fresh_attempt_prefix, verified_runtime_jars

ROOT = Path(os.environ.get("AIGUIDE_CAPTURE_ROOT", os.environ.get("AIGUIDE_MEDIA_ROOT", r"C:\Users\harde\Videos\aiguide-promo")))
MEDIA = Path(os.environ.get("AIGUIDE_MEDIA_ROOT", r"\\192.168.1.9\c\Videos\aiguide-promo"))
FP = r"C:\Users\harde\AppData\Local\Microsoft\WinGet\Packages\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe\ffmpeg-8.1-full_build\bin\ffprobe.exe"


def validate_track(video, frames, start_tick, clock=None, geometry=(1920, 1080)):
    """Validate bytes and timeline again; an old manifest alone is not completion evidence."""
    if not video.is_file():
        raise FileNotFoundError(video)
    with Path(str(video) + ".timeline.tsv").open() as f:
        rows = list(csv.DictReader(f, delimiter="\t"))
    ticks = [float(row["world_tick"]) for row in rows]
    if len(ticks) != frames or any(int(row["frame"]) != i for i, row in enumerate(rows)):
        raise RuntimeError(f"{video.name}: incorrect timeline frame count/index")
    if any(not math.isfinite(t) or abs(t - (start_tick + (i + 1) / 3)) > .00001 for i, t in enumerate(ticks)):
        raise RuntimeError(f"{video.name}: wrong start, dropped or discontinuous replay frame")
    if clock is not None and ticks != clock:
        raise RuntimeError(f"{video.name}: timeline differs from the other tracks")
    stream = json.loads(subprocess.check_output([
        FP, "-v", "error", "-threads", "2", "-count_frames", "-select_streams", "v:0",
        "-show_entries", "stream=nb_read_frames,width,height,r_frame_rate", "-of", "json", str(video)]))["streams"][0]
    if int(stream["nb_read_frames"]) != frames or Fraction(stream["r_frame_rate"]) != 60:
        raise RuntimeError(f"{video.name}: incorrect encoded frame count/rate")
    if geometry is not None and (stream["width"], stream["height"]) != geometry:
        raise RuntimeError(f"{video.name}: incorrect frame dimensions")
    return ticks, {"path": str(video), "first_tick": ticks[0], "last_tick": ticks[-1], **stream}


def save_manifest(path, manifest):
    temp = path.with_suffix(".json.tmp")
    temp.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    temp.replace(path)


ap = argparse.ArgumentParser()
ap.add_argument("recording")
ap.add_argument("--start", type=float, default=0)
ap.add_argument("--seconds", type=float, default=8)
ap.add_argument("--name", required=True)
ap.add_argument("--game", default="game-codex-test")
ap.add_argument("--ui", help="live Commander MP4 with Recorder .timeline.tsv")
ap.add_argument("--recordings", help="WorldRec directory used by the live take")
ap.add_argument("--heroes", nargs="+", type=int, choices=range(1, 5), default=[1, 2, 3, 4])
ap.add_argument("--resume", action="store_true", help="verify and reuse known tracks; never overwrite unknown existing video")
ap.add_argument("--single-client", action="store_true", help="experimental coordinated cameras in one hidden replay client; requires matching runtime")
ap.add_argument("--capture-source", help="exact source commit used to build the deployed multicapture runtime")
ap.add_argument("--expected-jar-sha256", help="SHA256 of the approved client JAR installed in the selected game")
ap.add_argument("--expected-server-jar-sha256", help="SHA256 of the approved server JAR installed in the selected game")
ap.add_argument("--encoder", choices=("h264_nvenc", "libx264"), default="h264_nvenc", help="hero encoder; explicit choice, no automatic fallback")
ap.add_argument("--ffmpeg", help="FFmpeg executable; otherwise AIGUIDE_FFMPEG, PATH, legacy fallback")
ap.add_argument("--ffprobe", help="FFprobe executable; otherwise AIGUIDE_FFPROBE, FFmpeg sibling, PATH, legacy fallback")
a = ap.parse_args()
if a.single_client and not a.capture_source:
    ap.error("--single-client requires --capture-source; verify the deployed runtime before use")
if a.single_client and not a.expected_jar_sha256:
    ap.error("--single-client requires --expected-jar-sha256; verify the installed JAR before use")
if a.single_client and not a.expected_server_jar_sha256:
    ap.error("--single-client requires --expected-server-jar-sha256; verify the installed JAR before use")
if a.single_client and not str(MEDIA).startswith("\\\\"):
    ap.error("single-client capture requires a UNC AIGUIDE_MEDIA_ROOT (the laptop's own share is allowed)")
try:
    capture_ffmpeg = executable("ffmpeg", a.ffmpeg)
    FP = executable("ffprobe", a.ffprobe, capture_ffmpeg)
except ValueError as exc:
    ap.error(str(exc))
low_impact()  # bound this orchestrator and inherited decoder/encoder work to two CPUs
if any(c not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-" for c in a.name + a.recording):
    ap.error("recording and name must be simple directory names")
if not math.isfinite(a.start) or not math.isfinite(a.seconds) or a.start < 0 or a.seconds <= 0 or round(a.seconds * 60) < 1:
    ap.error("invalid interval")
frames = round(a.seconds * 60)
runtime_jars = []
if a.single_client:
    try:
        runtime_jars = verified_runtime_jars(ROOT / a.game, a.expected_jar_sha256, a.expected_server_jar_sha256)
    except (OSError, ValueError) as exc:
        ap.error(str(exc))
if a.ui:
    # Reject an unusable fifth track before spending time rendering four hero videos.
    import align_interface
    source = Path(a.ui)
    if not source.is_file():
        ap.error("live Commander UI source does not exist: " + str(source))
    expected_ticks = [a.start * 20 + (i + 1) / 3 for i in range(frames)]
    try:
        _, source_frames = align_interface.inspect_source_timeline(str(source) + ".timeline.tsv", expected_ticks)
        align_interface.validate_source_video_frames(source, source_frames, FP)
    except (OSError, KeyError, ValueError, subprocess.CalledProcessError) as exc:
        ap.error("live Commander UI source is unusable: " + str(exc))
dest = MEDIA / "captures" / a.name
manifest_path = dest / "manifest.json"
manifest = {"recording": a.recording, "requested_start_tick": a.start * 20,
            "fps": 60, "frames": frames, "hidden": True, "tracks": [],
            "capture_source": a.capture_source if a.single_client else None,
            "client_sha256": a.expected_jar_sha256.lower() if a.single_client else None,
            "server_sha256": a.expected_server_jar_sha256.lower() if a.single_client else None,
            "interface": "Use the live Commander video and its world_tick timeline; replay omits inventory and UI history."}
if manifest_path.exists():
    if not a.resume:
        ap.error("manifest exists; use --resume to validate and reuse it, or choose a new --name")
    existing = json.loads(manifest_path.read_text(encoding="utf-8"))
    if any(existing.get(key) != manifest[key] for key in ("recording", "requested_start_tick", "fps", "frames", "hidden", "capture_source", "client_sha256", "server_sha256")):
        ap.error("existing manifest recording, interval, capture mode or runtime identity differs")
    if not isinstance(existing.get("tracks"), list):
        ap.error("existing manifest has no track list")
    manifest = existing  # preserve other tracks and additional evidence fields
elif a.resume:
    ap.error("--resume requires an existing manifest; untracked video cannot be assumed valid")
dest.mkdir(exist_ok=True, parents=True)
clock = None
known = {}
for track in manifest["tracks"]:
    hero = track.get("hero")
    if type(hero) is not int or hero not in range(1, 5) or hero in known:
        ap.error("invalid or duplicate hero in existing manifest")
    video = dest / f"hero_{hero}.mp4"
    if Path(track.get("path", "")).resolve() != video.resolve():
        ap.error(f"hero {hero}: manifest points outside its expected destination")
    known[hero] = track

# Preflight every existing output before starting even one new client.
for hero in range(1, 5):
    video = dest / f"hero_{hero}.mp4"
    if hero not in known and existing_capture_artifacts(video):
        ap.error(f"hero {hero}: existing untracked output; refusing to overwrite or delete it")
interface = manifest.get("interface")
interface_video = dest / "interface.mp4"
if isinstance(interface, dict):
    if Path(interface.get("path", "")).resolve() != interface_video.resolve():
        ap.error("interface manifest points outside its expected destination")
elif existing_capture_artifacts(interface_video):
    ap.error("existing untracked interface output; refusing to overwrite or delete it")
if isinstance(interface, dict) and a.ui and Path(interface.get("source", "")).resolve() != Path(a.ui).resolve():
    ap.error("existing interface uses a different live source")

for hero, track in sorted(known.items()):
    clock, checked = validate_track(dest / f"hero_{hero}.mp4", frames, a.start * 20, clock)
    if any(track.get(k) != checked[k] for k in ("first_tick", "last_tick", "width", "height")) or int(track.get("nb_read_frames", -1)) != frames:
        ap.error(f"hero {hero}: existing manifest disagrees with the actual file")
    print(f"hero {hero}: existing {frames} frames verified; reusing", flush=True)
if isinstance(interface, dict):
    interface_clock, _ = validate_track(interface_video, frames, a.start * 20, clock, geometry=None)
    if interface.get("frames") != frames:
        ap.error("existing interface manifest disagrees with actual frame count")
    if clock is None:
        clock = interface_clock

missing = [hero for hero in dict.fromkeys(a.heroes) if hero not in known]
if a.single_client and missing:
    # A batch produces independently finalized tracks. Preserve verified ones even if another fails.
    run_id = str(time.time_ns())
    script = dest / f"coordinated_{run_id}.txt"
    script.write_text(f"""menu 40
replay {a.recording}
inworld 70
replaypause
replayseek {a.start}
wait 40
cine on
fov 70
replaymulti {','.join(map(str, missing))} {frames} {dest}
quit
""", encoding="utf-8")
    attempt = {"mode": "single_client_round_robin", "heroes": missing,
               "source": a.capture_source, "expected_jar_sha256": a.expected_jar_sha256.lower(),
               "expected_server_jar_sha256": a.expected_server_jar_sha256.lower(),
               "runtime_jars": runtime_jars, "script": str(script),
               "encoder": a.encoder, "ffmpeg": capture_ffmpeg, "ffprobe": FP,
               "started_wall_ns": time.time_ns(), "validated_heroes": [], "failures": {}}
    manifest.setdefault("attempts", []).append(attempt)
    manifest["status"] = "capture_running"
    save_manifest(manifest_path, manifest)
    with (dest / f"coordinated_{run_id}_runner.log").open("w", encoding="utf-8") as log:
        result = subprocess.run([sys.executable, str(Path(__file__).with_name("take.py")),
            str(ROOT / a.game), str(script), str(dest / f"coordinated_{run_id}_launch"),
            "--hidden", "--capture-source", a.capture_source,
            "--encoder", a.encoder, "--ffmpeg", capture_ffmpeg,
            "--timeout", str(max(240, int(a.seconds * len(missing) * 8 + 180))),
            "--size", "1920x1080"] + (["--recordings", a.recordings] if a.recordings else []),
            cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
    attempt["runner_exit"] = result.returncode
    attempt["finished_wall_ns"] = time.time_ns()
    for hero in missing:
        try:
            clock, checked = validate_track(dest / f"hero_{hero}.mp4", frames, a.start * 20, clock)
            track = {"hero": hero, **checked}
            known[hero] = track
            manifest["tracks"].append(track)
            attempt["validated_heroes"].append(hero)
        except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError) as exc:
            attempt["failures"][str(hero)] = str(exc)
    manifest["tracks"].sort(key=lambda item: item["hero"])
    manifest["status"] = "partial_failed" if result.returncode or attempt["failures"] else "partial_interface_pending"
    save_manifest(manifest_path, manifest)
    if result.returncode or attempt["failures"]:
        raise SystemExit("Coordinated capture incomplete; verified tracks retained in manifest; inspect unverified files before retry")

for hero in dict.fromkeys(a.heroes):
    if hero in known:
        continue
    video = dest / f"hero_{hero}.mp4"
    attempt = fresh_attempt_prefix(dest, hero)
    script = Path(str(attempt) + ".txt")
    with script.open("x", encoding="utf-8") as script_file:
        script_file.write(f"""menu 40
replay {a.recording}
inworld 70
replaypause
replayseek {a.start}
wait 40
cine on
fov 70
replaytake {hero} {frames} {video}
quit
""")
    print(f"hero {hero}: hidden render", flush=True)
    with Path(str(attempt) + "_runner.log").open("x", encoding="utf-8") as log:
        subprocess.run([sys.executable, str(Path(__file__).with_name("take.py")),
                        str(ROOT / a.game), str(script), str(attempt) + "_launch",
                        "--hidden", "--timeout", str(max(240, int(a.seconds * 8 + 120))),
                        "--encoder", a.encoder, "--ffmpeg", capture_ffmpeg,
                        "--size", "1920x1080"] + (["--recordings", a.recordings] if a.recordings else []), cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True)
    clock, checked = validate_track(video, frames, a.start * 20, clock)
    track = {"hero": hero, **checked}
    known[hero] = track
    manifest["tracks"].append(track)
    manifest["tracks"].sort(key=lambda item: item["hero"])
    manifest["status"] = "partial_interface_pending" if len(known) == 4 else "partial_hero_tracks_pending"
    save_manifest(manifest_path, manifest)
    print(f"hero {hero}: {frames} frames verified", flush=True)
if a.ui and not isinstance(interface, dict):
    import align_interface
    align_interface.FF = capture_ffmpeg
    source = Path(a.ui).resolve()
    probe = json.loads(subprocess.check_output([FP, "-v", "error", "-select_streams", "v:0", "-show_entries",
                                               "stream=width,height", "-of", "json", str(source)]))["streams"][0]
    manifest["interface"] = align_interface.align(source, dest / "interface.mp4", clock, probe["width"], probe["height"])
    validate_track(dest / "interface.mp4", frames, a.start * 20, clock, (probe["width"], probe["height"]))
if clock is not None:
    manifest["status"] = "complete" if len(known) == 4 and isinstance(manifest.get("interface"), dict) else "partial_interface_pending" if len(known) == 4 else "partial_hero_tracks_pending"
    save_manifest(manifest_path, manifest)
print(dest / "manifest.json", flush=True)
