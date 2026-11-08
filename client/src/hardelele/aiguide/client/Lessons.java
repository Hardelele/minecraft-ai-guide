package hardelele.aiguide.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The minds' lessons (the epochs' second channel, as Reflexion does it: in the prompt, not in weights), with no game in
 * it. After an epoch one call of the model reads its summary (EpochScore.summary) and the lessons so far and writes at
 * most PER_EPOCH short ones; they are merged with the ones kept (one like a kept one confirms it and takes its words),
 * the weakest go past CAP (the least confirmed, the oldest), and the rest go into the system prompt of every mind of
 * defence in the next epoch (prompt(): the epoch each came from and what of it). Nothing here runs in a tick.
 */
final class Lessons {
    static final int CAP = 12, PER_EPOCH = 8, MAX_CHARS = 160;
    /** two lessons this alike (shared words over all words) are the same one */
    static final double SAME = 0.5;

    static final class Lesson {
        String text = "", from = "";
        int epoch, seen = 1, first;

        JsonObject json() {
            JsonObject o = new JsonObject();
            o.addProperty("text", text);
            o.addProperty("from", from);
            o.addProperty("epoch", epoch);
            o.addProperty("first", first);
            o.addProperty("seen", seen);
            return o;
        }

        static Lesson fromJson(JsonObject o) {
            Lesson l = new Lesson();
            l.text = CaseVec.str(o, "text");
            l.from = CaseVec.str(o, "from");
            l.epoch = (int) CaseVec.num(o, "epoch", 0);
            l.first = (int) CaseVec.num(o, "first", l.epoch);
            l.seen = (int) CaseVec.num(o, "seen", 1);
            return l;
        }
    }

    final List<Lesson> list = new ArrayList<Lesson>();
    int cap = CAP;

    // ---- merging and pushing out ---------------------------------------------------------------------------

    static Set<String> words(String s) {
        Set<String> w = new HashSet<String>();
        for (String x : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_]+")) if (x.length() > 2) w.add(x.length() > 5 ? x.substring(0, 5) : x);   // a stem: the word's first letters
        return w;
    }

    /** how alike two lessons are: shared stems over all stems (0..1) */
    static double alike(String a, String b) {
        Set<String> x = words(a), y = words(b);
        if (x.isEmpty() || y.isEmpty()) return 0;
        Set<String> u = new HashSet<String>(x);
        u.addAll(y);
        x.retainAll(y);
        return x.size() / (double) u.size();
    }

    /**
     * An epoch's new lessons merged in: one like a kept one (SAME) confirms it (seen + 1, the new words, this epoch);
     * the others are added. Then past the cap the weakest go: the fewest times seen, then the oldest epoch. Returns
     * the lessons pushed out.
     */
    List<Lesson> merge(List<Lesson> fresh, int epoch) {
        int n = 0;
        for (Lesson f : fresh) {
            if (n++ >= PER_EPOCH) break;
            Lesson same = null;
            double best = SAME;
            for (Lesson k : list) {
                double a = alike(k.text, f.text);
                if (a >= best) { best = a; same = k; }
            }
            if (same != null) {
                if (same.epoch != epoch) same.seen++;
                same.text = f.text;
                same.from = f.from;
                same.epoch = epoch;
            } else {
                f.epoch = epoch;
                f.first = epoch;
                f.seen = 1;
                list.add(f);
            }
        }
        List<Lesson> out = new ArrayList<Lesson>();
        while (list.size() > cap) {
            Lesson w = null;
            for (Lesson k : list) if (w == null || k.seen < w.seen || k.seen == w.seen && k.epoch < w.epoch) w = k;
            list.remove(w);
            out.add(w);
        }
        return out;
    }

    /** the block of the system prompt ("" with none): the lessons, the most confirmed first, each with its epoch and source */
    String prompt(String karma) {
        if (list.isEmpty() && (karma == null || karma.isEmpty())) return "";
        StringBuilder b = new StringBuilder("\nLessons of the earlier epochs of this defence (learned from how they went; keep to them, they may be in Russian):\n");
        List<Lesson> l = new ArrayList<Lesson>(list);
        java.util.Collections.sort(l, (p, q) -> p.seen != q.seen ? q.seen - p.seen : q.epoch - p.epoch);
        for (Lesson x : l) b.append("- ").append(x.text).append(" [epoch ").append(x.epoch).append(x.seen > 1 ? ", confirmed " + x.seen + " times" : "")
                .append(x.from.isEmpty() ? "" : ", from: " + x.from).append("]\n");
        if (karma != null && !karma.isEmpty()) b.append("- Last epoch's development (karma): ").append(karma).append('\n');
        return b.toString();
    }

    JsonObject json() {
        JsonObject o = new JsonObject();
        o.addProperty("cap", cap);
        JsonArray a = new JsonArray();
        for (Lesson l : list) a.add(l.json());
        o.add("lessons", a);
        return o;
    }

    static Lessons fromJson(JsonObject o) {
        Lessons ls = new Lessons();
        if (o == null) return ls;
        if (o.has("cap")) ls.cap = Math.max(1, o.get("cap").getAsInt());
        JsonElement a = o.get("lessons");
        if (a != null && a.isJsonArray()) for (JsonElement e : a.getAsJsonArray()) if (e.isJsonObject()) ls.list.add(Lesson.fromJson(e.getAsJsonObject()));
        return ls;
    }

    // ---- the model's call ----------------------------------------------------------------------------------

    static final String SYSTEM = "You coach a crew of agents in Minecraft 1.7.10 that defend their base from endless waves of mobs. "
            + "Each epoch is one run of waves from the same start. You read how the last epoch went (its numbers, its reward, the crew's "
            + "development, the episodes rated good and bad: a person's ratings weigh three times the rules') and the lessons kept so far, "
            + "and write the lessons the agents' minds will read in the next epoch.\n"
            + "Rules:\n"
            + "- At most " + PER_EPOCH + " lessons, each ONE short sentence in Russian (at most 120 characters), concrete: a skill, a moment, "
            + "a number (e.g. «Между волнами чини стену repair_wall, а не стой у ворот»).\n"
            + "- Keep a kept lesson that still holds by writing it again; leave out one the epoch showed wrong.\n"
            + "- Only the skills the minds have: " + String.join(", ", Mind.skills(true)) + ".\n"
            + "- Mind the development too: gather (chop_tree, mine), make better tools and weapons, keep food; a break spent waiting is lost.\n"
            + "Answer with JSON only: {\"lessons\":[{\"text\":\"...\",\"from\":\"what of the epoch it comes from, a few words\"}]}";

    /** the request's body: no tools, the summary and the lessons kept */
    static String request(String summary, Lessons kept) {
        JsonObject body = new JsonObject();
        JsonArray m = new JsonArray();
        m.add(Mind.message("system", SYSTEM));
        StringBuilder u = new StringBuilder(summary);
        u.append("LESSONS KEPT:");
        if (kept == null || kept.list.isEmpty()) u.append(" none yet\n");
        else {
            u.append('\n');
            for (Lesson l : kept.list) u.append("- ").append(l.text).append(" (epoch ").append(l.epoch).append(", seen ").append(l.seen).append(")\n");
        }
        u.append("Write the lessons for the next epoch.");
        m.add(Mind.message("user", u.toString()));
        body.add("messages", m);
        return body.toString();
    }

    /** what the call cost: prompt, completion, cached tokens (estimated from the characters when the answer has no usage) */
    static int[] usage(String response, int requestChars) {
        try {
            JsonObject r = new JsonParser().parse(response).getAsJsonObject();
            if (r.has("usage") && r.get("usage").isJsonObject()) {
                JsonObject u = r.getAsJsonObject("usage");
                int cached = Mind.intOf(u, "prompt_cache_hit_tokens");
                if (cached == 0 && u.has("prompt_tokens_details") && u.get("prompt_tokens_details").isJsonObject()) cached = Mind.intOf(u.getAsJsonObject("prompt_tokens_details"), "cached_tokens");
                return new int[] { Mind.intOf(u, "prompt_tokens"), Mind.intOf(u, "completion_tokens"), cached, 0 };
            }
        } catch (Exception e) {
            // not JSON: estimated
        }
        return new int[] { requestChars / 4, response == null ? 0 : response.length() / 4, 0, 1 };
    }

    /** the lessons of the model's answer: its JSON (in the content, maybe in a code fence), else its lines starting with "-"; at most PER_EPOCH */
    static List<Lesson> parse(String response) {
        List<Lesson> out = new ArrayList<Lesson>();
        String content = response == null ? "" : response;
        try {
            JsonObject r = new JsonParser().parse(response).getAsJsonObject();
            if (r.has("choices")) {
                JsonObject msg = r.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");
                content = msg.has("content") && !msg.get("content").isJsonNull() ? msg.get("content").getAsString() : "";
            }
        } catch (Exception e) {
            // the answer is the text itself
        }
        int i = content.indexOf('{'), j = content.lastIndexOf('}');
        if (i >= 0 && j > i) {
            try {
                JsonObject o = new JsonParser().parse(content.substring(i, j + 1)).getAsJsonObject();
                JsonElement a = o.get("lessons");
                if (a != null && a.isJsonArray())
                    for (JsonElement e : a.getAsJsonArray()) {
                        Lesson l = new Lesson();
                        if (e.isJsonObject()) { l.text = CaseVec.str(e.getAsJsonObject(), "text"); l.from = CaseVec.str(e.getAsJsonObject(), "from"); }
                        else if (e.isJsonPrimitive()) l.text = e.getAsString();
                        add(out, l);
                    }
            } catch (Exception e) {
                // not JSON: the lines
            }
        }
        if (out.isEmpty())
            for (String line : content.split("\n")) {
                String s = line.trim();
                if (!s.startsWith("-") && !s.matches("^\\d+[.)].*")) continue;
                Lesson l = new Lesson();
                l.text = s.replaceFirst("^(-|\\d+[.)])\\s*", "");
                add(out, l);
            }
        return out;
    }

    static void add(List<Lesson> out, Lesson l) {
        l.text = l.text == null ? "" : l.text.replaceAll("\\s+", " ").trim();
        if (l.text.isEmpty() || out.size() >= PER_EPOCH) return;
        if (l.text.length() > MAX_CHARS) l.text = l.text.substring(0, MAX_CHARS - 3) + "...";
        if (l.from == null) l.from = "";
        if (l.from.length() > 60) l.from = l.from.substring(0, 60);
        out.add(l);
    }

    // ---- the stand-in model (tests, a bench without a key) -------------------------------------------------

    /** what the stand-in writes for a bad reason of the summary (the same every time: the tests and a bench without a model) */
    static final String[][] STUB = {
            { "out_died", "Во время волны не выходи за стену: держи пост внутри (hold_line, radius 3)." },
            { "died", "При здоровье ниже половины отходи (retreat) и ешь (eat), не дерись до конца." },
            { "died_after", "Если ранен, сначала retreat, потом eat, и только потом снова hold_line." },
            { "starved", "Держи еду в запасе: между волнами добывай и ешь (eat)." },
            { "accident", "Не копай под собой и обходи воду и лаву у базы." },
            { "idle_in_wave", "Во время волны не жди и не осматривайся: встань на пост и держи линию (hold_line)." },
            { "hurt_no_kill", "Сначала оружие и броня (make_weapon, equip), потом пост." },
            { "bad_call", "Вызывай ровно один навык с нужными аргументами." },
            { "retreat_early", "Не отходи со здоровым телом: отход только при здоровье ниже 10." },
            { "reflex_failed", "Не стой на краю и в воде во время боя: держись ровной земли у поста." },
    };

    /** the karma's minus reasons the stand-in also writes a lesson for */
    static final String[][] STUB_KARMA = {
            { "idle", "Перерыв между волнами — время добывать: chop_tree, mine, лучше инструменты." },
            { "no_food", "Добудь еду до третьей волны и держи её в сумке." },
            { "no_tools", "Сделай каменное оружие и кирку до первой волны (make_weapon sword)." },
    };

    /** a model that writes lessons from the summary's bad reasons by STUB, the same for the same summary (no network) */
    static Mind.Model stub() {
        return new Mind.Model() {
            public String complete(String body) {
                String text = body;
                try {
                    JsonArray m = new JsonParser().parse(body).getAsJsonObject().getAsJsonArray("messages");
                    text = m.get(m.size() - 1).getAsJsonObject().get("content").getAsString();
                } catch (Exception e) {
                    // the body as it is
                }
                JsonArray ls = new JsonArray();
                for (String line : text.split("\n")) {
                    if (line.startsWith("- BAD "))
                        for (String[] s : STUB) if (line.startsWith("- BAD " + s[0] + " ")) ls.add(stubLesson(s[1], s[0]));
                    if (line.startsWith("KARMA"))
                        for (String[] s : STUB_KARMA) if (line.contains(EpochScore.karmaText(s[0]))) ls.add(stubLesson(s[1], "karma " + s[0]));
                }
                JsonObject content = new JsonObject();
                JsonArray cut = new JsonArray();
                for (int i = 0; i < Math.min(PER_EPOCH, ls.size()); i++) cut.add(ls.get(i));
                content.add("lessons", cut);
                JsonObject msg = new JsonObject();
                msg.addProperty("role", "assistant");
                msg.addProperty("content", content.toString());
                JsonObject choice = new JsonObject();
                choice.add("message", msg);
                JsonArray choices = new JsonArray();
                choices.add(choice);
                JsonObject r = new JsonObject();
                r.add("choices", choices);
                JsonObject u = new JsonObject();
                u.addProperty("prompt_tokens", body.length() / 4);
                u.addProperty("completion_tokens", content.toString().length() / 4);
                r.add("usage", u);
                r.addProperty("model", "stub");
                return r.toString();
            }
        };
    }

    static JsonObject stubLesson(String text, String from) {
        JsonObject o = new JsonObject();
        o.addProperty("text", text);
        o.addProperty("from", from);
        return o;
    }
}
