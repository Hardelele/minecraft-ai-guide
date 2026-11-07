"""Align a live Commander capture to a replay's world-tick timeline.

Keeps the whole interface frame. Repeats the nearest captured frame when the
live game rendered more slowly; never invents UI/recipe/inventory content.
"""
import bisect
import csv
import json
import math
from pathlib import Path
import subprocess
from capture_impact import low_impact

FF = r"C:\Users\harde\AppData\Local\Microsoft\WinGet\Packages\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe\ffmpeg-8.1-full_build\bin\ffmpeg.exe"
MAX_LIVE_UI_GAP_TICKS = 2.0  # 100 ms at 20 TPS; larger holds hide missing interface events.


def inspect_source_timeline(timeline, ticks):
    """Return chosen source frames and sidecar count after validating UI continuity."""
    if not ticks or any(not math.isfinite(t) for t in ticks):
        raise ValueError("requested replay timeline is empty or nonfinite")
    with Path(timeline).open() as f:
        rows = [(int(r["frame"]), float(r["world_tick"])) for r in csv.DictReader(f, delimiter="\t")]
    source_frame_count = len(rows)
    if any(i != frame for i, (frame, _) in enumerate(rows)):
        raise ValueError("interface frame indices are missing, duplicated or unordered")
    if any(not math.isfinite(t) for _, t in rows):
        raise ValueError("interface requires one finite monotonic WorldRec session")
    rows = [(i, t) for i, t in rows if t >= 0]
    times = [t for _, t in rows]
    if not times or any(b < a for a, b in zip(times, times[1:])):
        raise ValueError("interface requires one finite monotonic WorldRec session")
    # Recorder writes six decimals; tolerate only that rounding, not a missing UI second.
    if ticks[0] < times[0] - 0.000001 or ticks[-1] > times[-1] + 0.000001:
        raise ValueError("interface does not cover the requested replay interval")
    if any(b - a > MAX_LIVE_UI_GAP_TICKS + 0.000001 and a < ticks[-1] and b > ticks[0]
           for a, b in zip(times, times[1:])):
        raise ValueError("live Commander UI has a world-tick gap inside the requested interval")
    chosen = []
    for t in ticks:
        k = min(len(times) - 1, bisect.bisect_left(times, t))
        if k > 0 and abs(times[k-1] - t) <= abs(times[k] - t):
            k -= 1
        chosen.append(rows[k][0])
    return chosen, source_frame_count


def source_frames_for_ticks(timeline, ticks):
    return inspect_source_timeline(timeline, ticks)[0]


def validate_source_video_frames(source, timeline_frames, ffprobe):
    """Reject sidecars that describe frames absent from the actual live MP4."""
    raw = subprocess.check_output([ffprobe, "-v", "error", "-threads", "2", "-count_frames",
                                   "-select_streams", "v:0", "-show_entries", "stream=nb_read_frames",
                                   "-of", "json", str(source)])
    streams = json.loads(raw).get("streams", [])
    try:
        actual = int(streams[0]["nb_read_frames"])
    except (IndexError, KeyError, TypeError, ValueError) as exc:
        raise ValueError("live Commander MP4 has no countable video frames") from exc
    if actual != timeline_frames:
        raise ValueError(f"live Commander MP4 has {actual} frames but timeline has {timeline_frames}")
    return actual


def align(source, output, ticks, width, height):
    low_impact()
    source, output = Path(source), Path(output)
    if output.exists() or Path(str(output) + ".timeline.tsv").exists():
        raise FileExistsError(f"Refusing to overwrite existing interface output: {output}")
    chosen = source_frames_for_ticks(str(source) + ".timeline.tsv", ticks)
    first, last = chosen[0], chosen[-1]
    decoder = subprocess.Popen([FF, "-v", "error", "-threads", "2", "-filter_threads", "1", "-i", str(source), "-vf",
                                f"select='between(n,{first},{last})'", "-fps_mode", "passthrough",
                                "-frames:v", str(last - first + 1), "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], stdout=subprocess.PIPE)
    encoder = subprocess.Popen([FF, "-n", "-v", "error", "-threads", "2", "-filter_threads", "1", "-f", "rawvideo", "-pix_fmt", "rgb24",
                                "-s", f"{width}x{height}", "-r", "60", "-i", "-", "-an",
                                "-c:v", "libx264", "-threads", "2", "-preset", "veryfast", "-crf", "19",
                                "-frames:v", str(len(chosen)), "-pix_fmt", "yuv420p", str(output)], stdin=subprocess.PIPE)
    size = width * height * 3
    cursor = first - 1
    data = None
    try:
        for index in chosen:
            while cursor < index:
                data = decoder.stdout.read(size)
                if len(data) != size:
                    raise RuntimeError("interface decode ended before requested frame")
                cursor += 1
            encoder.stdin.write(data)
        encoder.stdin.close()
        if encoder.wait() != 0 or decoder.wait() != 0:
            raise RuntimeError("interface encode/decode failed")
    finally:
        for p in (decoder, encoder):
            if p.poll() is None:
                p.kill()
    with Path(str(output) + ".timeline.tsv").open("w", newline="") as f:
        writer = csv.writer(f, delimiter="\t")
        writer.writerow(["frame", "world_tick", "source_frame"])
        writer.writerows((i, t, j) for i, (t, j) in enumerate(zip(ticks, chosen)))
    return {"path": str(output), "source": str(source), "frames": len(chosen),
            "repeated_frames": len(chosen) - len(set(chosen)), "source_frame_first": first, "source_frame_last": last}
