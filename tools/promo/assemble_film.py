"""Assembles a film from one frame-grid plan (JSON): every shot is an integer frame count, the captions and the
voice sit on the same grid, and the sum of the shots is the film. Nothing here invents a frame: a shot may only
be cut or retimed from its source, and a source shorter than the shot is refused (no frozen padding).

    python assemble_film.py <plan.json> <work_dir> <out.mp4> [--ffmpeg path] [--only-audio]

plan = {
  "fps": 30, "size": [1920, 1080], "music": path, "music_in": seconds, "music_gain_db": -3, "tail_fade": seconds,
  "shots": [{"id", "source", "in": seconds, "frames": n, "speed": 1.0 (source seconds per film second), "note"}],
  "captions": [{"png": path, "from": frame, "to": frame}],   # PNG strips, placed at caption_y
  "caption_y": 800,
  "voice": [{"file": path, "at": frame, "gain_db": 0}]
}
"""
import argparse, json, os, subprocess, sys


def run(cmd):
    p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8", errors="replace")
    if p.returncode:
        sys.exit("FAILED: " + " ".join(cmd)[:600] + "\n" + p.stderr[-1500:])
    return p.stdout


def probe(ffprobe, path):
    return float(run([ffprobe, "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path]).strip())


def segments(plan, work, ff, fp):
    fps = plan["fps"]; w, h = plan["size"]
    names = []
    for i, s in enumerate(plan["shots"]):
        speed = s.get("speed", 1.0)
        need = s["frames"] / fps * speed
        have = probe(fp, s["source"])
        if s["in"] + need > have + 0.02:
            sys.exit("shot %s needs %.3f s of its source from %.3f, source has %.3f" % (s["id"], need, s["in"], have))
        out = os.path.join(work, "seg_%03d_%s.mp4" % (i, s["id"]))
        vf = "setpts=(PTS-STARTPTS)/%s,fps=%d,scale=%d:%d:force_original_aspect_ratio=decrease:flags=lanczos,pad=%d:%d:(ow-iw)/2:(oh-ih)/2:color=0x0B0D12,setsar=1,format=yuv420p" % (speed, fps, w, h, w, h)
        if s.get("vf"):
            vf = s["vf"] + "," + vf
        run([ff, "-y", "-v", "error", "-ss", "%.4f" % s["in"], "-i", s["source"], "-vf", vf, "-frames:v", str(s["frames"]), "-an",
             "-c:v", "libx264", "-preset", "medium", "-crf", "15", "-r", str(fps), out])
        names.append(out)
    return names


def picture(plan, names, work, ff):
    lst = os.path.join(work, "parts.txt")
    with open(lst, "w", encoding="utf-8", newline="\n") as f:
        for n in names:
            f.write("file '%s'\n" % n.replace("\\", "/"))
    base = os.path.join(work, "base.mp4")
    run([ff, "-y", "-v", "error", "-f", "concat", "-safe", "0", "-i", lst, "-c", "copy", base])
    caps = plan.get("captions", [])
    if not caps:
        return base
    fps = plan["fps"]; y = plan.get("caption_y", 800); fade = 6 / fps
    cmd = [ff, "-y", "-v", "error", "-i", base]
    graph, last = [], "0:v"
    for i, c in enumerate(caps):
        dur = (c["to"] - c["from"]) / fps
        cmd += ["-loop", "1", "-framerate", str(fps), "-t", "%.4f" % dur, "-i", c["png"]]
        start = c["from"] / fps
        graph.append("[%d:v]format=rgba,fade=t=in:st=0:d=%.3f:alpha=1,fade=t=out:st=%.3f:d=%.3f:alpha=1,setpts=PTS-STARTPTS+%.4f/TB[c%d]" % (i + 1, fade, dur - fade, fade, start, i))
        graph.append("[%s][c%d]overlay=(W-w)/2:%d:eof_action=pass:format=auto[v%d]" % (last, i, c.get("y", y), i))
        last = "v%d" % i
    out = os.path.join(work, "picture.mp4")
    cmd += ["-filter_complex", ";".join(graph), "-map", "[%s]" % last, "-c:v", "libx264", "-preset", "slow", "-crf", "17",
            "-pix_fmt", "yuv420p", "-color_range", "tv", "-r", str(fps), out]
    run(cmd)
    return out


def sound(plan, total, work, ff):
    fps = plan["fps"]; dur = total / fps
    tail = plan.get("tail_fade", 1.2)
    cmd = [ff, "-y", "-v", "error", "-ss", str(plan.get("music_in", 0)), "-t", "%.4f" % dur, "-i", plan["music"]]
    graph = ["[0:a]aresample=48000,aformat=channel_layouts=stereo,volume=%sdB[m]" % plan.get("music_gain_db", -3)]
    labels = []
    for i, v in enumerate(plan.get("voice", [])):
        cmd += ["-i", v["file"]]
        ms = int(v["at"] / fps * 1000)
        graph.append("[%d:a]aresample=48000,aformat=channel_layouts=stereo,volume=%sdB,adelay=%d|%d[v%d]" % (i + 1, v.get("gain_db", 0), ms, ms, i))
        labels.append("[v%d]" % i)
    if labels:
        graph.append("%samix=inputs=%d:normalize=0:duration=longest,apad,atrim=0:%.4f,asplit=2[vo][key]" % ("".join(labels), len(labels), dur))
        graph.append("[m][key]sidechaincompress=threshold=0.02:ratio=7:attack=30:release=420:makeup=1[md]")
        graph.append("[md][vo]amix=inputs=2:normalize=0:duration=first[mix]")
    else:
        graph.append("[m]anull[mix]")
    graph.append("[mix]afade=t=out:st=%.4f:d=%.4f,alimiter=limit=0.84:level=disabled,atrim=0:%.4f[a]" % (dur - tail, tail, dur))
    out = os.path.join(work, "sound.wav")
    cmd += ["-filter_complex", ";".join(graph), "-map", "[a]", "-c:a", "pcm_s16le", "-ar", "48000", out]
    run(cmd)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("plan"); ap.add_argument("work"); ap.add_argument("out")
    ap.add_argument("--ffmpeg", default="ffmpeg"); ap.add_argument("--only-audio", action="store_true")
    a = ap.parse_args()
    plan = json.load(open(a.plan, encoding="utf-8"))
    ff = a.ffmpeg
    fp = os.path.join(os.path.dirname(ff), "ffprobe.exe") if os.path.dirname(ff) else "ffprobe"
    os.makedirs(a.work, exist_ok=True)
    total = sum(s["frames"] for s in plan["shots"])
    if a.only_audio:
        pic = os.path.join(a.work, "picture.mp4")
    else:
        pic = picture(plan, segments(plan, a.work, ff, fp), a.work, ff)
    wav = sound(plan, total, a.work, ff)
    run([ff, "-y", "-v", "error", "-i", pic, "-i", wav, "-map", "0:v", "-map", "1:a", "-c:v", "copy", "-c:a", "aac", "-b:a", "256k",
         "-movflags", "+faststart", "-t", "%.4f" % (total / plan["fps"]), a.out])
    print(json.dumps({"out": a.out, "frames": total, "seconds": total / plan["fps"]}))


if __name__ == "__main__":
    main()
