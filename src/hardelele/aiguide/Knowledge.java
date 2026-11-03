package hardelele.aiguide;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Guides the server owner writes: Markdown files in config/aiguide/knowledge.
 * The first line ("# Title — what it is about") goes into the assistant's prompt as the index;
 * the assistant reads a whole file when it needs it, and players read them with /guide <topic>.
 */
class Knowledge {
    static final Charset UTF8 = Charset.forName("UTF-8");
    final File dir;
    final Map<String, String> titles = new TreeMap<String, String>();

    Knowledge(File dir) {
        this.dir = dir;
    }

    void load() {
        titles.clear();
        dir.mkdirs();
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files);
        for (File f : files) {
            if (!f.getName().endsWith(".md")) continue;
            String topic = f.getName().substring(0, f.getName().length() - 3);
            String first = "";
            try {
                List<String> lines = Files.readAllLines(f.toPath(), UTF8);
                for (String l : lines) if (!l.trim().isEmpty()) { first = l.replaceFirst("^#+\\s*", "").trim(); break; }
            } catch (Exception e) {
                AIGuide.LOG.warn("not read: " + f + ": " + e);
            }
            titles.put(topic, first);
        }
        AIGuide.LOG.info("{} guides in {}", titles.size(), dir);
    }

    String index() {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : titles.entrySet()) b.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        return b.toString();
    }

    /** the topic a player or the model asked for: by file name, then by title */
    String find(String q) {
        String n = q.trim().toLowerCase();
        if (titles.containsKey(n)) return n;
        for (Map.Entry<String, String> e : titles.entrySet()) if (e.getKey().startsWith(n)) return e.getKey();
        for (Map.Entry<String, String> e : titles.entrySet()) if (e.getValue().toLowerCase().contains(n)) return e.getKey();
        return null;
    }

    String read(String topic) {
        String t = find(topic);
        if (t == null) return null;
        try {
            return new String(Files.readAllBytes(new File(dir, t + ".md").toPath()), UTF8);
        } catch (Exception e) {
            return null;
        }
    }

    /** the lines of a guide that mention any of the words (for a quick look) */
    List<String> grep(String topic, String words) {
        List<String> out = new ArrayList<String>();
        String text = read(topic);
        if (text == null) return out;
        String[] w = words.toLowerCase().split("\\s+");
        for (String l : text.split("\n"))
            for (String x : w) if (x.length() > 2 && l.toLowerCase().contains(x)) { out.add(l); break; }
        return out;
    }
}
