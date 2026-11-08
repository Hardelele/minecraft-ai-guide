package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * What each wave of the waves mode (Waves) brings: a budget of points that grows with the wave's number and the run's
 * level, spent on the mobs unlocked by then. Zombies from the first wave, skeletons from the 3rd, spiders from the
 * 4th, creepers from the 6th; every 5th wave is a boss wave (zombies in armour, a witch from the 10th). Waves 1-3 are
 * meant for 4 agents with wooden or stone swords; past the 10th the mobs get tougher as well as more. No game here
 * (WavesTest checks the curve).
 */
final class WavePlan {
    /** a mob of a wave and what it costs from the budget */
    enum Kind {
        ZOMBIE(1), SKELETON(1.5), SPIDER(1.5), CREEPER(2), ARMORED_ZOMBIE(2.5), WITCH(4);

        final double cost;

        Kind(double cost) { this.cost = cost; }
    }

    /** the run's levels: their words (ru, en) and how much each wave's budget is multiplied by */
    static final String[][] LEVELS = { { "легко", "easy", "0.7" }, { "норма", "normal", "1.0" }, { "сложно", "hard", "1.4" }, { "хаос", "chaos", "2.0" } };

    /** a level's canonical name (easy, normal, hard, chaos) from its word (ru or en, a prefix is enough), null when unknown; "" or null: normal */
    static String level(String word) {
        if (word == null || word.trim().isEmpty()) return "normal";
        String w = word.trim().toLowerCase(Locale.ROOT).replace('ё', 'е');
        for (String[] l : LEVELS) {
            String ru = l[0].replace('ё', 'е');
            if (w.equals(l[1]) || w.equals(ru) || w.length() >= 3 && (ru.startsWith(w) || l[1].startsWith(w))) return l[1];
        }
        if (w.equals("нормально") || w.equals("нормальная") || w.equals("средне") || w.equals("on") || w.equals("true") || w.equals("1")) return "normal";
        if (w.equals("легкая") || w.equals("просто")) return "easy";
        if (w.equals("сложная") || w.equals("тяжело")) return "hard";
        return null;
    }

    /** the budget's multiplier of a level (1 for an unknown one) */
    static double mult(String level) {
        for (String[] l : LEVELS) if (l[1].equals(level)) return Double.parseDouble(l[2]);
        return 1.0;
    }

    static boolean boss(int n) { return n > 0 && n % 5 == 0; }

    /** the wave's points: 3, 5, 7, 10, 13, 15, 19, 22 ... at normal (2 + 1.6 n + 0.12 n^2), at least 1 */
    static int budget(int n, double mult) {
        return Math.max(1, (int) Math.floor(mult * (2 + 1.6 * n + 0.12 * n * n)));
    }

    /** how many of each kind (by Kind's order) wave n brings at a level's multiplier */
    static int[] counts(int n, double mult) {
        int[] c = new int[Kind.values().length];
        double b = budget(n, mult);
        if (boss(n)) {   // the boss part first: armoured zombies (1 a boss wave so far), a witch from the 10th; the rest a smaller ordinary wave
            c[Kind.ARMORED_ZOMBIE.ordinal()] = n / 5;
            c[Kind.WITCH.ordinal()] = n / 10;
            b = Math.max(1, Math.floor(b * 0.7));
        }
        double left = b;
        if (n >= 3) { int k = (int) Math.floor(b * 0.25 / Kind.SKELETON.cost); c[Kind.SKELETON.ordinal()] = k; left -= k * Kind.SKELETON.cost; }
        if (n >= 4) { int k = (int) Math.floor(b * 0.20 / Kind.SPIDER.cost); c[Kind.SPIDER.ordinal()] = k; left -= k * Kind.SPIDER.cost; }
        if (n >= 6) { int k = (int) Math.floor(b * 0.18 / Kind.CREEPER.cost); c[Kind.CREEPER.ordinal()] = k; left -= k * Kind.CREEPER.cost; }
        c[Kind.ZOMBIE.ordinal()] = Math.max(1, (int) Math.floor(left / Kind.ZOMBIE.cost));
        return c;
    }

    static int total(int[] counts) {
        int t = 0;
        for (int k : counts) t += k;
        return t;
    }

    /** the mobs' health multiplier: the same up to the 10th wave, then +6% a wave */
    static double health(int n) { return 1 + Math.max(0, n - 10) * 0.06; }

    /** the order the wave's mobs come in: shuffled, the boss part last (it comes when the wave is in full swing) */
    static List<Kind> queue(int[] counts, Random rnd) {
        List<Kind> plain = new ArrayList<Kind>(), bosses = new ArrayList<Kind>();
        for (Kind k : Kind.values())
            for (int i = 0; i < counts[k.ordinal()]; i++) (k == Kind.ARMORED_ZOMBIE || k == Kind.WITCH ? bosses : plain).add(k);
        Collections.shuffle(plain, rnd);
        int at = plain.size() / 2;   // the boss part half way through
        plain.addAll(at, bosses);
        return plain;
    }

    /** a wave in words for the log: "3 zombie, 1 skeleton" */
    static String describe(int[] counts) {
        StringBuilder s = new StringBuilder();
        for (Kind k : Kind.values()) if (counts[k.ordinal()] > 0) s.append(s.length() > 0 ? ", " : "").append(counts[k.ordinal()]).append(' ').append(k.name().toLowerCase(Locale.ROOT));
        return s.toString();
    }
}
