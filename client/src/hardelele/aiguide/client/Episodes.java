package hardelele.aiguide.client;

import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the agents did lately, for the person to rate (no game classes): the reflexes' cases (the memory of cases) and
 * the cards finished on the board (the crew's event lines), newest first. The crew's lines are also kept here, the
 * last few thousand, for the share archive (no chat in them: the guide's conversation is apart, GuiGuide.history).
 */
final class Episodes {
    private Episodes() { }

    static final int KEEP = 4000;
    private static final ArrayDeque<String[]> lines = new ArrayDeque<String[]>();   // { wall ms, line }

    /** one episode: a reflex's case or a card's end */
    static final class Ep {
        String type = "case";          // "case" or "card"
        long t = -1, ms = -1;          // world tick (a case), wall clock (a card's line)
        String agent = "", reflex = "", reaction = "", result = "", escalated, card = "";
        int ticks;
        int[] at;                      // where (a case: the agent's feet), null: not known

        /** what a rating refers to: a case by its tick, agent and reflex (the journal's line), a card by its line */
        JsonObject ref() {
            JsonObject o = new JsonObject();
            o.addProperty("type", type);
            if ("case".equals(type)) {
                o.addProperty("t", t);
                o.addProperty("agent", agent);
                o.addProperty("reflex", reflex);
                o.addProperty("reaction", reaction);
                o.addProperty("result", result);
                o.addProperty("ticks", ticks);
                if (escalated != null) o.addProperty("escalated", escalated);
            } else {
                o.addProperty("ms", ms);
                o.addProperty("agent", agent);
                o.addProperty("card", card);
                o.addProperty("result", result);
            }
            if (at != null) o.add("at", Labels.ints(at));
            return o;
        }

        String key() { return "case".equals(type) ? "c:" + t + ":" + agent + ":" + reflex : "k:" + ms + ":" + agent + ":" + card; }

        boolean ok() { return "OK".equals(result) && (escalated == null || escalated.isEmpty()); }
    }

    static Ep of(CaseBook.Case c) {
        Ep e = new Ep();
        e.t = c.t;
        e.agent = c.agent;
        e.reflex = c.reflex;
        e.reaction = c.reaction;
        e.result = c.result;
        e.escalated = c.escalated;
        e.ticks = c.ticks;
        if (c.sit != null && c.sit.feet != null) e.at = c.sit.feet.clone();
        return e;
    }

    /** "board: <card> by <agent> -> <result>" (TaskBoard's line when a holder is through with its card) */
    static final Pattern CARD = Pattern.compile("^board: (.+) by (.+?) -> ([A-Z_]+)(,.*)?$");

    static Ep card(long ms, String line) {
        Matcher m = CARD.matcher(line);
        if (!m.matches()) return null;
        Ep e = new Ep();
        e.type = "card";
        e.ms = ms;
        e.card = m.group(1);
        e.agent = m.group(2);
        e.result = m.group(3);
        return e;
    }

    static void event(long ms, String line) {
        if (line == null) return;
        synchronized (lines) {
            lines.addLast(new String[] { String.valueOf(ms), line });
            while (lines.size() > KEEP) lines.removeFirst();
        }
    }

    /** the kept lines, oldest first, as "ms<TAB>line" */
    static List<String> lines() {
        List<String> out = new ArrayList<String>();
        synchronized (lines) { for (String[] l : lines) out.add(l[0] + "\t" + l[1]); }
        return out;
    }

    static void clear() { synchronized (lines) { lines.clear(); } }

    /** the last n of these cases (oldest first, as the memory keeps them), newest first */
    static List<Ep> cases(List<CaseBook.Case> cases, int n) {
        List<Ep> out = new ArrayList<Ep>();
        if (cases != null) for (int i = cases.size() - 1; i >= 0 && out.size() < n; i--) out.add(of(cases.get(i)));
        return Collections.unmodifiableList(out);
    }

    /** the last n cards ended on the board (from the kept lines), newest first */
    static List<Ep> cards(int n) {
        List<Ep> out = new ArrayList<Ep>();
        List<String[]> l;
        synchronized (lines) { l = new ArrayList<String[]>(lines); }
        for (int i = l.size() - 1; i >= 0 && out.size() < n; i--) {
            Ep e = card(Long.parseLong(l.get(i)[0]), l.get(i)[1]);
            if (e != null) out.add(e);
        }
        return Collections.unmodifiableList(out);
    }
}
