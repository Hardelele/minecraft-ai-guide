"""Assembles a film from one frame-grid plan (JSON): every shot is an integer frame count, the captions and the
voice sit on the same grid, and the sum of the shots is the film. Nothing here invents a frame: a shot may only
be cut or retimed from its source, and a source shorter than the shot is refused (no frozen padding).

    python assemble_film.py <plan.json> <work_dir> <out.mp4> [--ffmpeg path] [--only-audio]

plan = {
  "fps": 30, "size": [1920, 1080], "music": path, "music_in": seconds, "music_gain_db": -3, "tail_fade": seconds,
  "shots": [{"id", "source", "in": seconds, "frames": n, "speed": 1.0 (source seconds per film second), "note"}],
  "caption_track": path,   # transparent video strip, placed at caption_y
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
    track = plan.get("caption_track")   # one transparent full-frame video on the film's own grid (see film_v10_plan.py)
    v = plan.get("vertical")
    out = os.path.join(work, "picture.mp4")
    if v:   # the whole 16:9 picture in the middle, its own blurred continuation above and below; the end card takes the whole frame
        g = ("[0:v]split[a][b];[a]scale=-2:%d,crop=%d:%d,boxblur=26:2,eq=brightness=-0.17:saturation=0.8[bg];"
             "[b]scale=%d:-2:flags=lanczos[fg];[bg][fg]overlay=0:%d:enable='lt(n,%d)'[p];[p][1:v]overlay=0:0:eof_action=pass:format=auto,format=yuv420p[v]"
             % (v["h"], v["w"], v["h"], v["w"], v["fg_y"], v["card_from"]))
    else:
        g = "[0:v][1:v]overlay=0:%d:eof_action=pass:format=auto,format=yuv420p[v]" % plan.get("caption_y", 800)
    run([ff, "-y", "-v", "error", "-i", base, "-i", track, "-filter_complex", g,
         "-map", "[v]", "-c:v", "libx264", "-preset", "slow", "-crf", "19", "-color_range", "tv", "-r", str(plan["fps"]), out])
    return out


def sound(plan, total, work, ff, fp):
    fps = plan["fps"]; dur = total / fps
    tail = plan.get("tail_fade", 1.2)
    duck = plan.get("duck", 0.4)   # the music's level under the voice, a plain envelope from the voice's own intervals
    cmd = [ff, "-y", "-v", "error", "-ss", str(plan.get("music_in", 0)), "-t", "%.4f" % dur, "-i", plan["music"]]
    env, labels, graph = [], [], []
    for i, v in enumerate(plan.get("voice", [])):
        cmd += ["-i", v["file"]]
        a = v["at"] / fps; b = a + probe(fp, v["file"])
        ms = int(a * 1000)
        graph.append("[%d:a]aresample=48000,aformat=channel_layouts=stereo,volume=%sdB,adelay=%d|%d[v%d]" % (i + 1, v.get("gain_db", 0), ms, ms, i))
        labels.append("[v%d]" % i)
        env.append("clip((t-%.3f)/0.25,0,1)*clip((%.3f-t)/0.45,0,1)" % (a - 0.25, b + 0.45))
    level = "1-%.3f*min(1,%s)" % (1 - duck, "+".join(env)) if env else "1"
    graph.insert(0, "[0:a]aresample=48000,aformat=channel_layouts=stereo,volume=%sdB,volume='%s':eval=frame,afade=t=out:st=%.4f:d=%.4f[md]" % (plan.get("music_gain_db", -3), level, dur - tail, tail))
    if labels:
        graph.append("%samix=inputs=%d:normalize=0:duration=longest[vo]" % ("".join(labels), len(labels)))
        graph.append("[md][vo]amix=inputs=2:normalize=0:duration=first[mix]")
    else:
        graph.append("[md]anull[mix]")
    graph.append("[mix]alimiter=limit=0.84:level=disabled,apad,atrim=0:%.4f[a]" % dur)
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
    wav = sound(plan, total, a.work, ff, fp)
    run([ff, "-y", "-v", "error", "-i", pic, "-i", wav, "-map", "0:v", "-map", "1:a", "-c:v", "copy", "-c:a", "aac", "-b:a", "256k",
         "-movflags", "+faststart", "-t", "%.4f" % (total / plan["fps"]), a.out])
    print(json.dumps({"out": a.out, "frames": total, "seconds": total / plan["fps"]}))


if __name__ == "__main__":
    main()
