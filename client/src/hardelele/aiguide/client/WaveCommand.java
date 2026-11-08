package hardelele.aiguide.client;

import java.util.Locale;

/**
 * The waves mode's words in the guide's window, read here and not sent to the guide: /волны старт [легко|норма|сложно|хаос]
 * (/waves start [easy|normal|hard|chaos]) starts a run, /волны стоп (/waves stop) ends it and takes its mobs away,
 * /волны пауза (/waves pause) pauses it or goes on. No game in it (WavesTest reads it).
 */
final class WaveCommand {
    static final int START = 1, STOP = 2, PAUSE = 3, USAGE = 4;

    final int kind;
    final String level;   // START: easy, normal, hard or chaos

    private WaveCommand(int kind, String level) { this.kind = kind; this.level = level; }

    /** the command in a line, or null when the line is not the waves' (a question for the guide, another command) */
    static WaveCommand parse(String line) {
        if (line == null) return null;
        String s = line.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        String rest;
        if (s.equals("/волны") || s.startsWith("/волны ")) rest = s.substring(6).trim();
        else if (s.equals("/waves") || s.startsWith("/waves ")) rest = s.substring(6).trim();
        else return null;
        String[] w = rest.isEmpty() ? new String[0] : rest.split(" ");
        if (w.length == 0) return new WaveCommand(USAGE, null);
        String verb = w[0];
        if (verb.equals("старт") || verb.equals("start") || verb.equals("начать") || verb.equals("go")) {
            String level = WavePlan.level(w.length > 1 ? w[1] : null);
            return level == null || w.length > 2 ? new WaveCommand(USAGE, null) : new WaveCommand(START, level);
        }
        if (w.length > 1) return new WaveCommand(USAGE, null);
        if (verb.equals("стоп") || verb.equals("stop") || verb.equals("хватит")) return new WaveCommand(STOP, null);
        if (verb.equals("пауза") || verb.equals("pause") || verb.equals("дальше") || verb.equals("resume")) return new WaveCommand(PAUSE, null);
        return new WaveCommand(USAGE, null);
    }
}
