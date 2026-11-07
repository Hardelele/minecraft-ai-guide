package hardelele.aiguide.client;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Opt-in one-client replay capture. GL remains on the normal render thread; no nested renderer. */
public final class MultiCapture {
    static MultiCapture active;
    static String lastError;
    final CaptureClock clock;
    final int[] heroes;
    final Recorder[] sinks;
    final Cam.Shot[] shots;
    final File report;
    final long started = System.currentTimeMillis();

    static void start(int[] heroes, int count, File dir) throws Exception {
        if (active != null || Recorder.instance != null) throw new IllegalStateException("capture already running");
        if (Replay.active == null) throw new IllegalStateException("no replay");
        if (Replay.active.t + count / 3.0 >= Replay.active.ticks.size() - 1)
            throw new IllegalArgumentException("capture exceeds recorded timeline");
        if (!dir.getPath().startsWith("\\\\")) throw new IllegalArgumentException("multicapture output must be UNC");
        if (!dir.isDirectory()) throw new IllegalArgumentException("output directory does not exist");
        lastError = null;
        MultiCapture capture = new MultiCapture(heroes, count, dir);
        capture.writeReport("running", null);
        Replay.active.playing = false;
        Cam.get().autoOn = false;
        active = capture;
        FMLCommonHandler.instance().bus().register(capture);
        Director.log("multicapture: started " + heroes.length + " cameras, " + count + " frames each; world tick " + capture.clock.start);
    }

    MultiCapture(int[] heroes, int count, File dir) throws Exception {
        clock = new CaptureClock(heroes.length, count, Replay.active.t);
        this.heroes = heroes.clone();
        sinks = new Recorder[heroes.length]; shots = new Cam.Shot[heroes.length];
        report = new File(dir, "capture-" + System.currentTimeMillis() + ".json");
        Cam cam = Cam.get();
        for (int i = 0; i < heroes.length; i++) {
            if (heroes[i] < 1 || heroes[i] > 4) throw new IllegalArgumentException("hero must be 1..4");
            for (int j = 0; j < i; j++) if (heroes[i] == heroes[j]) throw new IllegalArgumentException("duplicate hero");
            File video = new File(dir, "hero_" + heroes[i] + ".mp4");
            File existing = CaptureArtifacts.firstExisting(video);
            if (existing != null) throw new IllegalArgumentException("existing track artifact: " + existing);
            // Each hero has independent, reproducible shot planning, including resumed subsets.
            cam.rnd.setSeed(11 + heroes[i]);
            shots[i] = cam.planFollow(heroes[i], (count + 2) / 3 + 40);
            if (shots[i] == null) throw new IllegalStateException("no clear camera for hero " + heroes[i]);
            sinks[i] = Recorder.detached(video.getPath(), count);
        }
    }

    double worldTick() { return clock.tick(); }
    double stepTicks() { return clock.step(); }
    double cameraTicks() { return Math.min(clock.frame + 1, clock.count) / 3.0; }
    double cameraStepTicks() { return clock.cameraStep(); }
    void selectCamera() {
        Cam cam = Cam.get(); cam.shot = shots[clock.slot]; cam.frames = Math.min(clock.frame + 1, clock.count);
        if (cam.shot.target(cameraTicks()) == null) abort("hero " + heroes[clock.slot] + " absent from recorded moment");
        else if (cam.shot instanceof Cam.Follow && ((Cam.Follow) cam.shot).lost > 10)
            abort("hero " + heroes[clock.slot] + " occluded; refusing to finish a stale camera track");
    }

    @SubscribeEvent
    public void render(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || active != this) return;
        Recorder sink = sinks[clock.slot];
        sink.render(event); // this slot's PBO contains only this hero's prior logical frame
        if (sink.failure != null) { abort("hero " + heroes[clock.slot] + ": " + sink.failure); return; }
        if (clock.advance()) {
            for (Recorder r : sinks) if (!r.finished || r.frames != clock.count || r.sent != clock.count) {
                abort("incomplete encoder output"); return;
            }
            finish(null);
        }
    }

    void abort(String reason) { finish(reason); }
    void finish(String error) {
        if (active != this) return;
        active = null; lastError = error;
        Replay.active.playing = false;
        FMLCommonHandler.instance().bus().unregister(this);
        for (Recorder r : sinks) r.finish();
        Cam.get().release();
        try { writeReport(error == null ? "encoded_pending_external_validation" : "partial_failed", error); }
        catch (Exception ex) { lastError = "capture report failed: " + ex; }
        Director.log("multicapture: " + (lastError == null ? "encoded" : lastError) + "; report " + report);
    }

    void writeReport(String state, String error) throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("status", state); root.addProperty("error", error);
        root.addProperty("source", System.getProperty("aiguide.capture.source", "unspecified"));
        root.addProperty("recording", Replay.active.name);
        root.addProperty("encoder", Recorder.ENCODER);
        root.addProperty("ffmpeg", Recorder.FFMPEG);
        root.addProperty("start_tick", clock.start); root.addProperty("frames_per_track", clock.count);
        root.addProperty("started_wall_ms", started); root.addProperty("updated_wall_ms", System.currentTimeMillis());
        root.addProperty("cpu_frame_buffers_per_track", 2); root.addProperty("pbo_buffers_per_track", 2);
        root.addProperty("interface", "genuine live UI must be aligned separately; never reconstructed");
        JsonArray tracks = new JsonArray();
        for (int i = 0; i < heroes.length; i++) {
            Recorder r = sinks[i]; JsonObject tr = new JsonObject();
            tr.addProperty("hero", heroes[i]); tr.addProperty("path", r.path);
            tr.addProperty("read", r.frames); tr.addProperty("sent", r.sent);
            tr.addProperty("error", r.failure); tracks.add(tr);
        }
        root.add("tracks", tracks);
        File temp = new File(report + ".tmp");
        Files.write(temp.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(temp.toPath(), report.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
