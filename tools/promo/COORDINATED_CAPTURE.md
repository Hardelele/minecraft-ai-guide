# Coordinated replay capture candidate

Status: this capture port starts from integrated mine+routines gameplay
`4a6f90dae7915403b9ad910623da1d90ff144508`. The client build, pure clock,
CLI rejection and UI timeline controls pass on the desktop. Minecraft was not
launched. GPU output, camera-switch terrain rebuild cost, actual wall-clock
speed and audio suppression still require one coordinated laptop runtime gate.
Do not call this mode validated yet.

The original per-hero path remains the default. Opt in with `multicam.py RECORDING
--name NAME --start SECONDS --seconds DURATION --single-client --capture-source SHA
--expected-jar-sha256 SHA256 --recordings UNC_RECORDINGS --ui LIVE_UI_MP4`. All media and generated scripts stay
under the configured laptop UNC capture root. `--resume --heroes 4` revalidates all
known tracks and renders only missing requested heroes; unknown files are refused.

One hidden Minecraft client opens the replay once. It renders each requested hero
using the normal render loop, holds the world clock through that camera round, then
advances exactly one third of a world tick. Each hero has an independent camera shot
and two PBOs, plus two pooled BGRA buffers feeding a separate encoder. At 1080p with
four tracks this accounts for about 63.3 MiB CPU frames and 63.3 MiB PBO storage,
excluding Minecraft/driver/encoder memory. Backpressure stops offline progress
instead of silently dropping frames. By default four NVENC sessions with one worker
each are requested. `--encoder libx264` explicitly selects software encoding, with
one codec thread per detached track, `veryfast`, CRF19, one lookahead worker and a
bounded eight-frame lookahead. NVENC-specific flags are absent in software mode.
There is no automatic codec fallback and no AMF integration. Unsupported encoder
names are rejected before capture; an unavailable selected encoder fails with
preserved partial evidence. Encoder lookahead/driver allocations are additional
to the CPU frame/PBO storage estimate above.
The orchestrator uses the existing two-CPU affinity/idle-priority helper before
spawning work; children inherit that affinity (the game launcher requests below-normal
priority). This is bounded concurrency, not a measured performance guarantee.

The extra render round drains each camera's last PBO without advancing the world.
Timelines are independently checked for exact frame indices, tick progression,
frame count, rate and geometry. Runtime reports say `encoded_pending_external_validation`;
only the Python checks add verified tracks to the persistent manifest. Failed or
unverified media is retained for review, never automatically deleted or overwritten.
Each attempt records requested source SHA and the verified installed AI Guide JAR hash,
script identity, wall timestamps, runner exit and per-track validation failures.
Selected encoder and resolved FFmpeg/FFprobe paths are recorded for each batch;
the Java report also identifies the encoder actually requested by Recorder.
The Java report identifies the actual recording name, requested interval and counts.

The fifth track remains real live Commander footage aligned by its original world
tick sidecar. Replay cannot reconstruct historical inventory, selection or UI.
The aligner rejects a source timeline with missing or reordered frame indices,
nonfinite ticks, or a requested interval outside the live UI recording (apart
from six-decimal timeline rounding). It will not pad an uncovered interval with
a nearby but different UI state. Within the requested interval, adjacent live UI
frames may be at most two world ticks (100 ms) apart. When `--ui` is supplied,
these checks and an FFprobe count of actual MP4 frames against all sidecar rows
run before any hero client or encoder is started.
The preflight treats `.mp4`, `.timeline.tsv`, `.frames.txt` and `.ffmpeg.txt` as
one output identity, preserving diagnostics left by a failed encoder. Scripted
FOV values are degrees and are converted to Minecraft 1.7.10's option slider.
Each individual fallback attempt also gets a fresh script, runner log and launcher
prefix, so an early failure with no MP4 cannot overwrite its previous evidence.
An absent or ambiguous scripted UI pill, failed WorldRec close, or other script
abort ends the take with a nonzero launcher exit after normal shutdown.
Existing terrain-coverage limitations remain; a complete encoded track is not proof
that distant terrain or an occluded hero was visible. Review the WorldRec coverage
report and representative frames before editing.

The launcher keeps its League preflight and cooperative stop. Scripted clients mute
their own master audio in memory by default (`aiguide.video.mute=false` opts out);
neither the Windows mixer nor a separate user's game is changed. Scripted quit
waits for WorldRec closure and actual server thread termination after region I/O.
Do not force-terminate a client merely because the flush is slow.

Suggested authorized gate: 1 second, four nearby heroes, known terrain, genuine UI
covering the same interval. Check four 60-frame tracks with identical timelines,
visible hero identity per view, first/last frame, no repeated wrong camera or black
terrain, quiet hidden client, clean server termination, and fresh region-NBT readback.
Then interrupt a separate short take and verify partial manifest preservation;
resume only missing tracks and compare clocks. Measure wall duration against the
same four individual takes only when that measurement is worth its GPU cost.

## Laptop software gate (commands to run there only)

First integrate the capture commits into the approved source revision and build it
on the laptop with the existing 256MiB/two-CPU build constraint. Do not deploy the
old-base candidate JAR over newer gameplay fixes. Deploy only into a separate
`game-capture-pipeline-test`, with the matching server mod, OptiFine, and small
config/options. Minecraft libraries, version JSON/JAR, assets and Windows natives
must exist locally. Java8 x64 is the known runtime; Java25 may build the Java8
target but has not been validated as the Forge1.7.10 runtime.

Keep `take.py`, `desk.py`, `multicam.py`, `align_interface.py`, `capture_config.py`
and `capture_impact.py` together; the last helper bounds launcher CPU and priority.
No video copying is required: existing recordings and live UI stay on the laptop.
Replay creates a separate working world under the dedicated client's saves folder.
Use the logged-in user's Windows session: hidden-desktop OpenGL under SSH/session0
has not been established. Keep League preflight/cooperative stop enabled.

Set `$repo`, `$game`, `$minecraft`, `$java8`, `$recordings` and `$sourceSha` to the
actual approved laptop paths/revision; set `$expectedJarSha256` to the SHA256 of the
approved client JAR and `$liveUi` to the genuine Commander MP4 whose `.timeline.tsv`
covers the requested interval. These are required inputs, not guessed paths.
The single-client preflight rejects a missing, duplicate or mismatched AIGuide JAR
before it creates the capture output directory. The operator still must establish
that the approved JAR was built from `$sourceSha`; the flag alone is metadata.
The binaries are resolved without launching them: explicit flags, then environment,
then PATH (FFprobe also checks FFmpeg's sibling), then the legacy PC path. An invalid
explicit/env path is an error instead of silently falling back.

```powershell
$env:AIGUIDE_MEDIA_ROOT = '\\192.168.1.9\c\Videos\aiguide-promo'
$env:AIGUIDE_CAPTURE_ROOT = $env:AIGUIDE_MEDIA_ROOT
$env:AIGUIDE_MINECRAFT_HOME = $minecraft
$env:AIGUIDE_JAVA = $java8
$env:AIGUIDE_FFMPEG = (Get-Command ffmpeg.exe).Source
$env:AIGUIDE_FFPROBE = (Get-Command ffprobe.exe).Source

# Static syntax check, on the laptop; does not launch the game or an encoder.
python -m py_compile "$repo\tools\promo\capture_config.py" "$repo\tools\promo\capture_impact.py" "$repo\tools\promo\desk.py" "$repo\tools\promo\take.py" "$repo\tools\promo\multicam.py" "$repo\tools\promo\align_interface.py"

# Negative controls: each must exit 2 BEFORE launching a client. Neither tests hardware.
python "$repo\tools\promo\multicam.py" colony_v4_fixed --name rejected_codec --encoder h264_amf
python "$repo\tools\promo\multicam.py" colony_v4_fixed --name rejected_path --ffmpeg 'X:\missing\ffmpeg.exe'

# Only after the authorized runtime window, using a NEW unused output name:
python "$repo\tools\promo\multicam.py" colony_v4_fixed --name laptop_software_gate_1s --start 40 --seconds 1 --heroes 1 2 3 4 --single-client --encoder libx264 --capture-source $sourceSha --expected-jar-sha256 $expectedJarSha256 --game $game --recordings $recordings --ui $liveUi
```

Expected: each track has60 frames at1920x1080/60fps; all timeline arrays are identical,
from800.333333 through820.000000. Inspect representative frames for four distinct
heroes and valid terrain. Read runner/Java/FFmpeg logs for codec errors, actual
`encoder=libx264`, clean server thread termination and actual wall duration. The
orchestrator's successful validation establishes frame/clock structure, not visual
quality or improved performance. A 1-second shot still needs full client startup.

If a live UI source already covers the gate interval, supply `--ui $liveUi` on the
first invocation so timeline and MP4 counts are checked before hero rendering.
Otherwise the four-camera gate remains partial. After that gate passes, align the
genuine UI by resuming the same manifest with `--ui $liveUi`; keep all other
interval/game/recording arguments identical. The UI
aligner always uses libx264 and the resolved FFmpeg binary. It does not reconstruct UI.
Four simultaneous software streams may be slower than real time at two-CPU affinity;
that slows offline progress rather than authorizing dropped frames. Performance and
Java/GPU/driver compatibility remain unmeasured until the laptop gate runs.
