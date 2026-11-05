package hardelele.aiguide.client;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import java.io.File;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;

/**
 * For the promo only: records what the game draws, from inside, to an ffmpeg process (rawvideo on its stdin,
 * NVENC out). Each rendered frame is read back through two pixel buffers (no stall); a writer thread sends
 * the newest frame 60 times a second by the wall clock, so the video keeps real time whatever the frame rate.
 * Nothing else on the screen gets in: the window may be covered.
 */
public class Recorder {
    static final String FFMPEG = System.getProperty("aiguide.video.ffmpeg",
            "C:\\Users\\harde\\AppData\\Local\\Microsoft\\WinGet\\Packages\\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe\\ffmpeg-8.1-full_build\\bin\\ffmpeg.exe");
    static final int FPS = 60;

    final Minecraft mc = Minecraft.getMinecraft();
    int w, h, size;
    int[] pbo;
    final java.util.concurrent.LinkedBlockingQueue<byte[]> queue = new java.util.concurrent.LinkedBlockingQueue<byte[]>(), pool = new java.util.concurrent.LinkedBlockingQueue<byte[]>();
    final StringBuilder stamps = new StringBuilder();   // the wall clock of each frame of the video
    long lastStamp;
    int at;
    boolean primed;
    volatile byte[] latest;
    volatile boolean running;
    Process ff;
    Thread writer;
    long frames, sent, dropped;

    /** the stamps so far on disk (a take cut short still has them) */
    void flushStamps() {
        try {
            java.nio.file.Files.write(new File(path + ".frames.txt").toPath(), stamps.toString().getBytes("UTF-8"));
        } catch (Exception ex) {
            // later
        }
    }
    String path;

    static Recorder instance;

    static void start(String path) {
        if (instance != null) return;
        instance = new Recorder();
        instance.path = path;
        FMLCommonHandler.instance().bus().register(instance);
    }

    static void stop() {
        if (instance != null) instance.finish();
    }

    @SubscribeEvent
    public void render(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        try {
            if (pbo == null) begin();
            if (mc.displayWidth != w || mc.displayHeight != h) return;   // the window changed: frames of the old size only
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[at]);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glReadPixels(0, 0, w, h, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, 0L);
            int prev = at ^ 1;
            if (primed) {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[prev]);
                ByteBuffer b = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY, size, null);
                if (b != null) {
                    byte[] f = pool.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);   // a free buffer (the writer gives them back)
                    if (f != null) {
                        b.get(f);
                        queue.put(f);
                        stamps.append(lastStamp).append('\n');
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
            primed = true;
            at = prev;
        } catch (Exception ex) {
            Director.log("recorder: " + ex);
            finish();
        }
    }

    void begin() throws Exception {
        w = mc.displayWidth;
        h = mc.displayHeight;
        size = w * h * 4;
        for (int i = 0; i < 24; i++) pool.add(new byte[size]);   // room for the encoder to fall behind a little without a frame lost
        pbo = new int[] { GL15.glGenBuffers(), GL15.glGenBuffers() };
        for (int id : pbo) {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, id);
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, size, GL15.GL_STREAM_READ);
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        ProcessBuilder pb = new ProcessBuilder(FFMPEG, "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "bgra", "-s", w + "x" + h, "-framerate",
                String.valueOf(FPS), "-i", "-", "-threads", "4", "-vf", "vflip", "-c:v", "h264_nvenc", "-preset", "p5", "-rc", "vbr", "-cq", "16", "-b:v", "0",
                "-pix_fmt", "yuv420p", path);
        pb.redirectErrorStream(true);
        pb.redirectOutput(new File(path + ".ffmpeg.txt"));
        ff = pb.start();
        running = true;
        final OutputStream out = ff.getOutputStream();
        final long t0 = System.nanoTime();
        Director.log("recorder: start " + w + "x" + h + " -> " + path + " at " + System.currentTimeMillis());
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
                    Director.log("recorder writer: " + ex);
                }
            }
        }, "AIGuide-recorder");        writer.setDaemon(true);
        writer.start();
    }

    void finish() {
        if (!running) return;
        running = false;
        try {
            writer.join(5000);
            ff.waitFor();
        } catch (Exception ex) {
            // gone
        }
        Director.log("recorder: stop at " + System.currentTimeMillis() + ", " + frames + " frames read, " + sent + " sent");
        try {
            java.nio.file.Files.write(new File(path + ".frames.txt").toPath(), stamps.toString().getBytes("UTF-8"));
        } catch (Exception ex) {
            // no stamps
        }
        if (pbo != null) for (int id : pbo) GL15.glDeleteBuffers(id);
        FMLCommonHandler.instance().bus().unregister(this);
        instance = null;
    }
}
