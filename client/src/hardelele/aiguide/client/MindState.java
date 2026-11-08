package hardelele.aiguide.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What an agent's mind is shown at a fork, in plain fields (filled from the game by AgentMind, by hand in a test):
 * the goal and how far it is, the body, the bag, what the crew has SEEN near (never a scan of the hidden world), the
 * stations, the places, the others and what they hold reserved, the events of the reflexes, the last steps with their
 * outcomes, the limits left. render() is the text the model reads; json() the same for the minds' log.
 */
final class MindState {
    String agent = "", goal = "", want = "", team = "";
    int have = -1;                 // of the wanted item: its own (alone) or the crew's (shared)
    int x, y, z;
    String on = "", held = "", time = "", player = "";
    float health = 20, maxHealth = 20;
    final Map<String, Integer> bag = new LinkedHashMap<String, Integer>();
    final List<String> near = new ArrayList<String>(), stations = new ArrayList<String>(), places = new ArrayList<String>(),
            others = new ArrayList<String>(), reserved = new ArrayList<String>(), events = new ArrayList<String>();
    // the defence's (a goal of defence, or a wave on): the waves, the wall and its posts, the allies' health and posts
    String wave = "", wearing = "", post = "";
    boolean waveOn;
    final List<String> perimeter = new ArrayList<String>(), allies = new ArrayList<String>();
    // set by Mind.request
    final List<Mind.Step> recent = new ArrayList<Mind.Step>();
    int firstStep = 1, stepsLeft, minutesLeft, tokensLeft, repeated;

    String render() {
        StringBuilder b = new StringBuilder();
        b.append("GOAL: ").append(goal).append('\n');
        if (!want.isEmpty()) b.append("GOAL CHECK: wanted ").append(want).append(", ").append(team.isEmpty() ? "you have " : "the crew has ").append(have).append('\n');
        if (!team.isEmpty()) b.append("SHARED WITH: ").append(team).append('\n');
        b.append("YOU: ").append(agent).append(" at ").append(x).append(' ').append(y).append(' ').append(z);
        if (!on.isEmpty()) b.append(" on ").append(on);
        b.append(", health ").append(Math.round(health)).append('/').append(Math.round(maxHealth));
        b.append(", holding ").append(held.isEmpty() ? "nothing" : held);
        if (!wearing.isEmpty()) b.append(", wearing ").append(wearing);
        if (!post.isEmpty()) b.append(", your post ").append(post);
        b.append('\n');
        if (!time.isEmpty()) b.append("TIME: ").append(time).append('\n');
        if (!wave.isEmpty()) b.append("WAVE: ").append(wave).append('\n');
        b.append("BAG: ");
        if (bag.isEmpty()) b.append("empty");
        else {
            int i = 0;
            for (Map.Entry<String, Integer> e : bag.entrySet()) b.append(i++ > 0 ? ", " : "").append(e.getKey()).append(" x").append(e.getValue());
        }
        b.append('\n');
        list(b, "SEEN NEAR (only what the crew has seen)", near, "nothing yet: look_around");
        list(b, "STATIONS", stations, "none known");
        if (!places.isEmpty()) list(b, "PLACES", places, "");
        if (!player.isEmpty()) b.append("PLAYER: ").append(player).append('\n');
        if (!others.isEmpty()) list(b, "OTHERS", others, "");
        if (!wave.isEmpty()) {
            list(b, "PERIMETER", perimeter, "no wall planned: build_wall around base");
            if (!allies.isEmpty()) list(b, "ALLIES", allies, "");
        }
        if (!reserved.isEmpty()) list(b, "RESERVED BY OTHERS (do not take)", reserved, "");
        if (!events.isEmpty()) list(b, "EVENTS SINCE YOUR LAST STEP", events, "");
        b.append("YOUR LAST STEPS:");
        if (recent.isEmpty()) b.append(" none yet\n");
        else {
            b.append('\n');
            int n = firstStep;
            for (Mind.Step s : recent) b.append(n++).append(". ").append(s.line()).append('\n');
        }
        if (repeated >= 2) b.append("NOTE: the last step failed the same way ").append(repeated).append(" times: do something else.\n");
        b.append("LIMITS LEFT: ").append(stepsLeft).append(" steps, ").append(minutesLeft).append(" min, ").append(tokensLeft).append(" tokens.\n");
        b.append("Call one tool.");
        return b.toString();
    }

    static void list(StringBuilder b, String head, List<String> l, String empty) {
        b.append(head).append(':');
        if (l.isEmpty()) { b.append(' ').append(empty).append('\n'); return; }
        b.append('\n');
        for (String s : l) b.append("- ").append(s).append('\n');
    }

    JsonObject json() {
        JsonObject o = new JsonObject();
        o.addProperty("agent", agent);
        o.addProperty("at", x + " " + y + " " + z);
        o.addProperty("on", on);
        o.addProperty("health", health);
        o.addProperty("held", held);
        o.addProperty("time", time);
        JsonObject g = new JsonObject();
        for (Map.Entry<String, Integer> e : bag.entrySet()) g.addProperty(e.getKey(), e.getValue());
        o.add("bag", g);
        if (!want.isEmpty()) { o.addProperty("want", want); o.addProperty("have", have); }
        o.add("near", arr(near));
        o.add("stations", arr(stations));
        o.add("places", arr(places));
        o.add("others", arr(others));
        o.add("reserved", arr(reserved));
        o.add("events", arr(events));
        if (!wave.isEmpty()) {
            o.addProperty("wave", wave);
            o.addProperty("waveOn", waveOn);
            if (!wearing.isEmpty()) o.addProperty("wearing", wearing);
            if (!post.isEmpty()) o.addProperty("post", post);
            o.add("perimeter", arr(perimeter));
            o.add("allies", arr(allies));
        }
        return o;
    }

    static JsonArray arr(List<String> l) {
        JsonArray a = new JsonArray();
        for (String s : l) a.add(new com.google.gson.JsonPrimitive(s));
        return a;
    }
}
