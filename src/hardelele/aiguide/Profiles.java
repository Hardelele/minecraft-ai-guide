package hardelele.aiguide;

import java.io.File;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * What the assistant knows about each player: config/aiguide/players/<name>.md.
 * The part above "## Notes" is the player's own ("/guide me ..." or the file itself);
 * the assistant adds dated lines under "## Notes" when it learns something worth keeping.
 */
class Profiles {
    static final String NOTES = "## Notes";
    final File dir;

    Profiles(File dir) {
        this.dir = dir;
        dir.mkdirs();
    }

    File file(String player) {
        return new File(dir, player.replaceAll("[^A-Za-z0-9_\\-]", "_") + ".md");
    }

    synchronized String read(String player) {
        File f = file(player);
        if (!f.isFile()) return "";
        try {
            return new String(Files.readAllBytes(f.toPath()), Knowledge.UTF8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    synchronized void write(String player, String text) {
        try {
            Files.write(file(player).toPath(), (text.trim() + "\n").getBytes(Knowledge.UTF8));
        } catch (Exception e) {
            AIGuide.LOG.warn("profile not saved: " + e);
        }
    }

    String about(String player) {
        String t = read(player);
        int n = t.indexOf(NOTES);
        return (n < 0 ? t : t.substring(0, n)).trim();
    }

    String notes(String player) {
        String t = read(player);
        int n = t.indexOf(NOTES);
        return n < 0 ? "" : t.substring(n + NOTES.length()).trim();
    }

    synchronized void setAbout(String player, String about) {
        String notes = notes(player);
        write(player, about.trim() + (notes.isEmpty() ? "" : "\n\n" + NOTES + "\n" + notes));
    }

    synchronized void addNote(String player, String note, int keep) {
        String[] old = notes(player).isEmpty() ? new String[0] : notes(player).split("\n");
        StringBuilder b = new StringBuilder();
        for (int i = Math.max(0, old.length - keep + 1); i < old.length; i++) b.append(old[i]).append('\n');
        b.append("- ").append(new SimpleDateFormat("yyyy-MM-dd").format(new Date())).append(": ")
                .append(note.replace('\n', ' ').trim());
        String about = about(player);
        write(player, (about.isEmpty() ? "" : about + "\n\n") + NOTES + "\n" + b);
    }
}
