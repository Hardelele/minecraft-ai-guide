package hardelele.aiguide.client;

import java.io.File;
import java.nio.file.Files;

/**
 * The mod's sending, driven from tools/share-service/tests (the real service and a fake S3 on 127.0.0.1):
 * java ShareProbe <share.json> <archive.zip> <manifest.json>; prints "OK <answer>" or "REFUSED <error> <reason> <retry>".
 */
public class ShareProbe {
    public static void main(String[] a) throws Exception {
        ShareUpload.Config c = ShareUpload.Config.read(new File(a[0]));
        try {
            System.out.println("OK " + ShareUpload.send(c, new File(a[1]), "probe", Files.readAllBytes(new File(a[2]).toPath()), new ShareUpload.Progress()));
        } catch (ShareUpload.Refused e) {
            System.out.println("REFUSED " + e.error + " " + e.reason + " " + e.retryAfter);
            System.exit(3);
        }
    }
}
