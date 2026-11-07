package hardelele.aiguide.client;

import java.io.File;

/** Every file a recorder may leave for one output path must be preserved on retry. */
final class CaptureArtifacts {
    private static final String[] SUFFIXES = { "", ".timeline.tsv", ".frames.txt", ".ffmpeg.txt" };
    private CaptureArtifacts() { }

    static File firstExisting(File video) {
        for (String suffix : SUFFIXES) {
            File artifact = new File(video.getPath() + suffix);
            if (artifact.exists()) return artifact;
        }
        return null;
    }
}
