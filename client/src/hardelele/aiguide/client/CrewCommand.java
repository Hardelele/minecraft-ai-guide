package hardelele.aiguide.client;

import java.util.Locale;

/**
 * The crew's words in the guide's window, read here and not sent to the guide: /агент [N] and /crew add [N] call N
 * more agents (1 when not said), /агент убрать K (отпустить K) and /crew remove K let agent K go; /crew remove with no
 * number lets the commander's picked agents go. No game in it (a pure test reads it).
 */
final class CrewCommand {
    static final int ADD = 1, REMOVE = 2, USAGE = 3;

    final int kind, n;

    private CrewCommand(int kind, int n) { this.kind = kind; this.n = n; }

    /** the command in a line, or null when the line is not one of the crew's (a question for the guide) */
    static CrewCommand parse(String line) {
        if (line == null) return null;
        String s = line.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        String rest;
        if (s.equals("/агент") || s.startsWith("/агент ") || s.equals("/агенты") || s.startsWith("/агенты ")) rest = s.substring(s.indexOf(' ') < 0 ? s.length() : s.indexOf(' ')).trim();
        else if (s.equals("/crew") || s.startsWith("/crew ")) {
            rest = s.substring(5).trim();
            if (rest.startsWith("add")) rest = rest.substring(3).trim();
            else if (rest.startsWith("remove")) rest = "убрать " + rest.substring(6).trim();
            else return new CrewCommand(USAGE, 0);
        } else return null;
        rest = rest.trim();
        if (rest.isEmpty()) return new CrewCommand(ADD, 1);
        if (rest.matches("\\d+")) return new CrewCommand(ADD, Math.max(1, Math.min(9, Integer.parseInt(rest))));
        String[] w = rest.split(" ", 2);
        if (w[0].equals("убрать") || w[0].equals("отпустить") || w[0].equals("remove")) {
            String k = w.length > 1 ? w[1].trim() : "";
            if (k.isEmpty()) return new CrewCommand(REMOVE, 0);   // the picked ones
            if (k.matches("\\d+")) return new CrewCommand(REMOVE, Integer.parseInt(k));
        }
        return new CrewCommand(USAGE, 0);
    }
}
