package hardelele.aiguide.client;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** A failed encoder's diagnostic files alone must block a same-name retry. */
public final class CaptureArtifactsProbe {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory(Paths.get("build"), "capture-artifacts-");
        File video = dir.resolve("hero_1.mp4").toFile();
        try {
            if (CaptureArtifacts.firstExisting(video) != null) throw new AssertionError("empty path rejected");
            for (String suffix : new String[] { ".ffmpeg.txt", ".frames.txt", ".timeline.tsv", "" }) {
                Path artifact = Paths.get(video.getPath() + suffix);
                Files.write(artifact, new byte[] { 1 });
                if (!artifact.toFile().equals(CaptureArtifacts.firstExisting(video)))
                    throw new AssertionError("sidecar retry did not preserve " + suffix);
                Files.delete(artifact);
            }
            System.out.println("CaptureArtifacts: every output collision rejected");
        } finally {
            for (String suffix : new String[] { ".ffmpeg.txt", ".frames.txt", ".timeline.tsv", "" })
                Files.deleteIfExists(Paths.get(video.getPath() + suffix));
            Files.deleteIfExists(dir);
        }
    }
}
