package hardelele.aiguide.client;

import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

/** Opt-in read-only render probe at the end of a logical camera frame. Never changes the capture input. */
final class CaptureRenderProbe {
    private CaptureRenderProbe() { }

    static void sample(MultiCapture capture, Recorder sink, long renderNs) {
        if (!Boolean.getBoolean("aiguide.capture.diag")) return;
        int frame = capture.clock.frame;
        if (frame != 0 && frame != 29 && frame != capture.clock.count - 1) return;
        Minecraft mc = Minecraft.getMinecraft();
        int readFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int readBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        int mainFbo = mc.getFramebuffer() == null ? -1 : mc.getFramebuffer().framebufferObject;
        String current = "error", main = "unavailable";
        try {
            // Recorder has unbound its pixel-pack buffer. Explicitly sample the target it just read.
            current = patch(sink.w, sink.h).toString();
            if (mainFbo >= 0 && mainFbo != readFbo) {
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, mainFbo);
                GL11.glReadBuffer(mainFbo == 0 ? GL11.GL_BACK : GL30.GL_COLOR_ATTACHMENT0);
                main = patch(sink.w, sink.h).toString();
            } else main = current;
        } catch (Exception ex) {
            main = "probe-error:" + ex.getClass().getSimpleName();
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
            GL11.glReadBuffer(readBuffer);
        }
        Cam cam = Cam.get();
        boolean cameraChunk = false, baseChunk = false;
        if (mc.theWorld != null && cam.eye != null) {
            cameraChunk = mc.theWorld.getChunkProvider().chunkExists((int) Math.floor(cam.eye.posX) >> 4,
                    (int) Math.floor(cam.eye.posZ) >> 4);
            baseChunk = mc.theWorld.getChunkProvider().chunkExists(-139 >> 4, 173 >> 4);
        }
        Director.log("capturediag: hero=" + capture.heroes[capture.clock.slot] + " frame=" + (frame + 1)
                + " tick=" + capture.clock.tick() + " eye=" + (cam.eye == null ? "none" :
                String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", cam.eye.posX, cam.eye.posY, cam.eye.posZ))
                + " chunk=" + cameraChunk + " baseChunk=" + baseChunk
                + " readFbo=" + readFbo + " mainFbo=" + mainFbo + " readBuffer=" + readBuffer
                + " pixels=" + current + " mainPixels=" + main
                + " renderers=" + (mc.renderGlobal == null ? "none" : mc.renderGlobal.getDebugInfoRenders())
                + " queue=" + sink.queue.size() + " pool=" + sink.pool.size() + " sent=" + sink.sent
                + " renderMs=" + (renderNs / 1000000.0));
    }

    private static CapturePixelStats patch(int width, int height) {
        int w = Math.min(16, width), h = Math.min(16, height);
        ByteBuffer pixels = BufferUtils.createByteBuffer(w * h * 4);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels((width - w) / 2, (height - h) / 2, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        return new CapturePixelStats(pixels);
    }
}
