package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.List;

/**
 * The commander's one place for messages (the guide's answer, the crew's notes, a rating saved, a routine started):
 * the newest on top, a few at a time, each gone after its time; the same words again only refresh the old one. No
 * game here: the time is given (UiToastsTest in UiLayoutTest).
 */
final class UiToasts {
    enum Kind {
        INFO(4000), WARN(8000), GUIDE(30000);

        final long ms;

        Kind(long ms) { this.ms = ms; }
    }

    static final class Toast {
        final String text;
        final Kind kind;
        final UiIcon icon;
        long at;

        Toast(String text, Kind kind, UiIcon icon, long at) { this.text = text; this.kind = kind; this.icon = icon; this.at = at; }

        boolean live(long now) { return now - at < kind.ms; }
    }

    static final int KEEP = 8;
    final List<Toast> all = new ArrayList<Toast>();   // the newest first

    synchronized void add(String text, Kind kind, UiIcon icon, long now) {
        if (text == null || text.trim().isEmpty()) return;
        for (int i = 0; i < all.size(); i++)
            if (all.get(i).text.equals(text)) { Toast t = all.remove(i); t.at = now; all.add(0, t); return; }
        all.add(0, new Toast(text, kind, icon, now));
        while (all.size() > KEEP) all.remove(all.size() - 1);
    }

    /** what is shown now, the newest first, at most n */
    synchronized List<Toast> shown(long now, int n) {
        List<Toast> out = new ArrayList<Toast>();
        for (Toast t : all) if (t.live(now) && out.size() < n) out.add(t);
        return out;
    }

    /** how many are live (more than are shown: a "+N" line) */
    synchronized int live(long now) {
        int n = 0;
        for (Toast t : all) if (t.live(now)) n++;
        return n;
    }
}
