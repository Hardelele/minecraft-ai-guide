package hardelele.aiguide.client;

import com.google.gson.JsonObject;

/**
 * Where the crew tells what happens, without knowing who listens: in the video client the director (its events file,
 * the camera), on a server the server's log and the bench. The crew's code uses only this, so the same code runs in
 * singleplayer, on the headless bench and later in the main server mod.
 */
public class Events {
    public interface Sink { void log(String line); }
    public interface Ghost { void show(JsonObject plan); }

    /** where lines go: the server log by default */
    public static volatile Sink sink = new Sink() {
        public void log(String line) { System.out.println("[Crew] " + line); }
    };
    /** the see-through plan of a build (the client draws it; nobody on a server) */
    public static volatile Ghost ghost = null;
    /** a second listener (the bench keeps every line) */
    public static volatile Sink tap = null;

    static void log(String line) {
        sink.log(line);
        Sink t = tap;
        if (t != null) t.log(line);
    }

    static void ghost(JsonObject plan) {
        Ghost g = ghost;
        if (g != null) g.show(plan);
    }
}
