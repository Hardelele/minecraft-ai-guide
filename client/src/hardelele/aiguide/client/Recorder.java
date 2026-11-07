package hardelele.aiguide.client;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import java.io.File;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;

/**
 * For the promo only: records what the game draws, from inside, to an ffmpeg process (rawvideo on its stdin,
 * NVENC by default, or explicitly selected software x264). Readback uses two pixel buffers; a writer thread
 * drains the pooled frames in order. Replay advances by source frames and waits for encoder capacity.
 * Nothing else on the screen gets in: the window may be covered.
 */
public class Recorder {
    static final String FFMPEG = System.getProperty("aiguide.video.ffmpeg",
            "C:\\Users\\harde\\AppData\\Local\\Microsoft\\WinGet\\Packages\\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe\\ffmpeg-8.1-full_build\\bin\\ffmpeg.exe");
    static final int FPS = 60;
    static final String ENCODER = System.getProperty("aiguide.video.encoder", "h264_nvenc");

    static void validateEncoder() {
        if (!ENCODER.equals("h264_nvenc") && !ENCODER.equals("libx264"))
            throw new IllegalArgumentException("unsupported capture encoder: " + ENCODER);
    }

    final Minecraft mc = Minecraft.getMinecraft();
    int w, h, size;
    int[] pbo;
    final java.util.concurrent.LinkedBlockingQueue<byte[]> queue = new java.util.concurrent.LinkedBlockingQueue<byte[]>(), pool = new java.util.concurrent.LinkedBlockingQueue<byte[]>();
    final StringBuilder stamps = new StringBuilder();   // the wall clock of each frame of the video
    long lastStamp;
    double lastTick;
    long maxFrames;
    final StringBuilder timeline = new StringBuilder("frame\tworld_tick\twall_ms\n");
    int at;
    boolean primed;
    volatile byte[] latest;
    volatile boolean running;
    Process ff;
    Thread writer;
    long frames, sent, dropped;
    boolean detached, finished;
    volatile String failure;

    static Recorder detached(String path, int count) {
        validateEncoder();
        Recorder r = new Recorder(); r.path = path; r.maxFrames = count; r.detached = true;
        return r;
    }

    /** the stamps so far on disk (a take cut short still has them) */
    void flushStamps() {
        try {
            java.nio.file.Files.write(new File(path + ".frames.txt").toPath(), stamps.toString().getBytes("UTF-8"));
            java.nio.file.Files.write(new File(path + ".timeline.tsv").toPath(), timeline.toString().getBytes("UTF-8"));
        } catch (Exception ex) {
            // later
        }
    }
    String path;

    static Recorder instance;

    static void start(String path) {
        if (instance != null) return;
        validateEncoder();
        instance = new Recorder();
        instance.path = path;
        FMLCommonHandler.instance().bus().register(instance);
    }

    static void stop() {
        if (MultiCapture.active != null) MultiCapture.active.abort("capture stopped before completion");
        if (instance != null) instance.finish();
    }

    @SubscribeEvent
    public void render(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.END || finished) return;
        try {
            if (pbo == null) begin();
            requireEncoder();
            if (mc.displayWidth != w || mc.displayHeight != h) return;   // the window changed: frames of the old size only
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[at]);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glReadPixels(0, 0, w, h, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, 0L);
            int prev = at ^ 1;
            if (primed) {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[prev]);
                ByteBuffer b = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY, size, null);
                if (b != null) {
                    // A replay may render slower than real time, but must never skip a source tick.
                    byte[] f = freeFrame();
                    if (f == null && Replay.active != null) throw new java.io.IOException("replay encoder stalled; refusing to drop a frame");
                    if (f != null) {
                        b.get(f);
                        queue.put(f);
                        stamps.append(lastStamp).append('\n');
                        timeline.append(frames).append('\t').append(String.format(java.util.Locale.ROOT, "%.6f", lastTick)).append('\t').append(lastStamp).append('\n');
                        frames++;
                    } else {   // the encoder is behind: this frame is lost; marked, never silent
                        dropped++;
                        stamps.append("DROP ").append(lastStamp).append('\n');
                        if (dropped % 30 == 1) Director.log("recorder: " + dropped + " frames dropped (the encoder is behind)");
                    }
                    if (frames % 600 == 0) flushStamps();
                    GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
                }
                lastStamp = System.currentTimeMillis();
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            if (!primed) lastStamp = System.currentTimeMillis();
            lastTick = Replay.active != null ? Replay.active.t : WorldRec.active != null ? WorldRec.active.ticks : -1;
            primed = true;
            at = prev;
            if (maxFrames > 0 && frames >= maxFrames) {
                if (Replay.active != null && !detached) Replay.active.playing = false;
                finish();
            }
        } catch (Exception ex) {
            failure = ex.toString();
            Director.log("recorder: " + ex);
            finish();
        }
    }

    void requireEncoder() {
        CaptureEncoderState.requireHealthy(failure, ff != null && ff.isAlive(), writer != null && writer.isAlive());
    }

    byte[] freeFrame() throws Exception {
        long limit = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Replay.active != null ? 30000 : 200);
        for (;;) {
            requireEncoder();
            byte[] frame = pool.poll(10, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (frame != null) return frame;
            if (System.nanoTime() >= limit) {
                requireEncoder();
                return null;
            }
        }
    }

    void begin() throws Exception {
        validateEncoder();
        w = mc.displayWidth;
        h = mc.displayHeight;
        size = w * h * 4;
        for (int i = 0; i < (detached ? 2 : 24); i++) pool.add(new byte[size]);
        pbo = new int[] { GL15.glGenBuffers(), GL15.glGenBuffers() };
        for (int id : pbo) {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, id);
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, size, GL15.GL_STREAM_READ);
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        List<String> command = new ArrayList<String>(Arrays.asList(FFMPEG, detached ? "-n" : "-y", "-loglevel", "error",
                "-f", "rawvideo", "-pix_fmt", "bgra", "-s", w + "x" + h, "-framerate", String.valueOf(FPS),
                "-i", "-", "-filter_threads", "1", "-vf", CaptureVideoGeometry.filter(), "-c:v", ENCODER));
        if (ENCODER.equals("libx264")) {
            // Four detached tracks share the launcher's two-CPU affinity. No hardware-specific flags.
            command.addAll(Arrays.asList("-threads", detached ? "1" : "2", "-preset", "veryfast", "-crf", "19",
                    "-x264-params", "lookahead-threads=1:rc-lookahead=8:sync-lookahead=0"));
        } else {
            command.addAll(Arrays.asList("-threads", detached ? "1" : "4", "-preset", "p5", "-rc", "vbr", "-cq", "22", "-b:v", "0"));
        }
        command.addAll(Arrays.asList("-pix_fmt", "yuv420p", path));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        pb.redirectOutput(new File(path + ".ffmpeg.txt"));
        ff = pb.start();
        running = true;
        final OutputStream out = ff.getOutputStream();
        final long t0 = System.nanoTime();
        Director.log("recorder: start " + w + "x" + h + " (encoded " + CaptureVideoGeometry.encoded(w) + "x"
                + CaptureVideoGeometry.encoded(h) + ") -> " + path + " encoder=" + ENCODER + " at " + System.currentTimeMillis());
        writer = new Thread(new Runnable() {   // every rendered frame once, in order: the video is locked to the frames, not to the clock
            @Override public void run() {
                try {
                    while (running || !queue.isEmpty()) {
                        byte[] f = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (f == null) continue;
                        out.write(f);
                        sent++;
                        pool.add(f);
                    }
                    out.close();
                } catch (Exception ex) {
                    failure = ex.toString();
                    Director.log("recorder writer: " + ex);
                    try { out.close(); } catch (Exception ignored) { }
                }
            }
        }, "AIGuide-recorder");        writer.setDaemon(true);
        writer.start();
    }

    void finish() {
        if (finished) return;
        finished = true;
        running = false;
        try {
            if (writer != null) writer.join(detached ? 30000 : 5000);
            if (ff != null) {
                if (detached && !ff.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    failure = "encoder did not finish within 30 seconds"; ff.destroy();
                } else if (ff.waitFor() != 0) failure = "encoder exited unsuccessfully";
            }
            if (sent != frames) failure = "encoder sent " + sent + " of " + frames + " frames";
        } catch (Exception ex) {
            failure = ex.toString();
        }
        Director.log("recorder: stop at " + System.currentTimeMillis() + ", " + frames + " frames read, " + sent + " sent");
        flushStamps();
        try {
            java.nio.file.Files.write(new File(path + ".frames.txt").toPath(), stamps.toString().getBytes("UTF-8"));
        } catch (Exception ex) {
            // no stamps
        }
        if (pbo != null) for (int id : pbo) GL15.glDeleteBuffers(id);
        if (!detached) FMLCommonHandler.instance().bus().unregister(this);
        if (instance == this) instance = null;
    }
}
