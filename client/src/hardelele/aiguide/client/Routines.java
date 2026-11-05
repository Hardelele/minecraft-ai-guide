package hardelele.aiguide.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

/**
 * The crew's routines: a behaviour taught once in words (the guide turns "find trees near and chop them, logs to the
 * chest" into a routine) and run again by a click: a loop of find a target (a tree, a vein of an ore, a kind of block)
 * near the agent, do the skill for it, bring what it got to the chest, again, until the chest holds as much as was
 * asked or nothing is left near. Kept with the world (routines.json). A request in the chat that means a routine the
 * crew already knows runs it at once: the request and the routines' descriptions are compared by embeddings (an
 * OpenAI model, through the proxy; the vectors of the descriptions kept with them).
 */
public class Routines {
    static class Routine {
        String name, description, target;   // target: tree | ore <block> | block <block>
        String untilItem;
        int untilCount, radius = 24;
        boolean deposit = true;
        double[] vec;
    }

    static final List<Routine> all = new ArrayList<Routine>();
    static boolean loaded;

    static File file() {
        MinecraftServer s = MinecraftServer.getServer();
        return s == null ? null : new File(s.worldServers[0].getSaveHandler().getWorldDirectory(), "routines.json");
    }

    static synchronized void load() {
        if (loaded) return;
        loaded = true;
        all.clear();
        try {
            File f = file();
            if (f == null || !f.isFile()) return;
            JsonArray a = new JsonParser().parse(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8")).getAsJsonArray();
            for (JsonElement e : a) all.add(new Gson().fromJson(e, Routine.class));
        } catch (Exception e) {
            Events.log("routines: not loaded: " + e);
        }
    }

    static synchronized void save() {
        try {
            File f = file();
            if (f != null) java.nio.file.Files.write(f.toPath(), new Gson().toJson(all).getBytes("UTF-8"));
        } catch (Exception e) {
            Events.log("routines: not saved: " + e);
        }
    }

    static synchronized List<String> names() {
        load();
        List<String> out = new ArrayList<String>();
        for (Routine r : all) out.add(r.name);
        return out;
    }

    static synchronized Routine named(String n) {
        load();
        for (Routine r : all) if (r.name.equalsIgnoreCase(n.trim())) return r;
        return null;
    }

    /** the guide's crew_routine tool: save (a new one, or changed), run (for agents), list, forget */
    public static String fromGuide(String action, String name, String description, String target, String until, String agents) {
        load();
        String a = action == null ? "" : action.trim().toLowerCase();
        if (a.startsWith("list")) return all.isEmpty() ? "No routines yet." : "Routines: " + names();
        if (a.startsWith("forget")) { Routine r = named(name); if (r == null) return "No routine " + name; synchronized (Routines.class) { all.remove(r); } save(); return "Forgotten: " + name; }
        if (a.startsWith("save")) {
            if (name == null || name.trim().isEmpty() || target == null || target.trim().isEmpty()) return "A routine needs a name and a target (tree | ore NAME | block NAME).";
            String t = target.trim().toLowerCase();
            if (!(t.equals("tree") || t.startsWith("ore ") || t.startsWith("block "))) return "Target: tree, ore NAME (iron_ore...) or block NAME (stone, sand...).";
            if (!t.equals("tree") && Block.getBlockFromName(t.substring(t.indexOf(' ') + 1)) == null) return "No such block: " + t;
            Routine r = named(name);
            if (r == null) { r = new Routine(); synchronized (Routines.class) { all.add(r); } }
            r.name = name.trim();
            r.description = description == null || description.trim().isEmpty() ? name.trim() : description.trim();
            r.target = t;
            r.untilItem = null; r.untilCount = 0;
            if (until != null && until.trim().matches("\\S+\\s+\\d+")) {   // "log 64": until the chest has that many
                String[] u = until.trim().split("\\s+");
                if (Compiler.item(u[0]) != null) { r.untilItem = u[0]; r.untilCount = Integer.parseInt(u[1]); }
            }
            r.vec = null;
            final Routine rr = r;
            new Thread(new Runnable() { public void run() { rr.vec = Embed.vector(rr.name + ". " + rr.description); save(); } }, "routine-embed").start();
            save();
            Events.log("routines: saved " + r.name + ": " + r.target + (r.untilItem != null ? " until " + r.untilCount + " " + r.untilItem : ""));
            if (agents != null && !agents.trim().isEmpty()) return "Saved routine '" + r.name + "'. " + runFor(r, agents);
            return "Saved routine '" + r.name + "' (it shows on the commander's routine bar).";
        }
        if (a.startsWith("run")) {
            Routine r = named(name);
            if (r == null) return "No routine '" + name + "'. Routines: " + names();
            return runFor(r, agents);
        }
        return "Actions: save, run, list, forget.";
    }

    static String runFor(Routine r, String agents) {
        Crew c = Crew.ensure();
        List<Integer> who = new ArrayList<Integer>();
        if (agents == null || agents.trim().isEmpty() || agents.trim().equalsIgnoreCase("all")) { for (Agent g : new ArrayList<Agent>(c.agents)) who.add(g.skin()); }
        else for (String p : agents.split("[,\\s]+")) if (p.matches("\\d+")) who.add(Integer.parseInt(p));
        int[] ws = new int[who.size()];
        for (int i = 0; i < ws.length; i++) ws[i] = who.get(i);
        run(ws, r.name);
        return "Running '" + r.name + "' for agents " + who + ".";
    }

    /** a routine for these agents: a card each (the player's, pinned), running until it is done or stopped */
    static void run(final int[] agents, final String name) {
        final Crew c = Crew.ensure();
        c.queue.add(new Runnable() {
            public void run() {
                final Routine r = named(name);
                if (r == null) return;
                WorldServer w = MinecraftServer.getServer().worldServers[0];
                for (int n : agents)
                    for (Agent a : c.agents)
                        if (a.skin() == n && !a.isDead) {
                            Origin was = Crew.originNow;
                            Crew.originNow = Origin.PLAYER;
                            TaskBoard.Card k = c.board.post("routine " + r.name, () -> new Loop(r)).pin(a).prio(Orders.PRIO).lease(20 * 3600);
                            Crew.originNow = was;
                            k.phase = null;
                            Orders.cancel(c, a);
                            a.commanded = true;
                            c.board.assign(k, a, w, "runs " + r.name);
                        }
                Events.log("routines: " + r.name + " for agents " + java.util.Arrays.toString(agents));
            }
        });
    }

    /** the loop: the nearest target, its skill, to the chest, again */
    static class Loop implements Crew.Task {
        final Routine r;
        Crew.Task cur;
        int rounds, empty;
        Loop(Routine r) { this.r = r; }
        public Result step(Agent a, WorldServer w) {
            if (cur != null) {
                Result res = cur.step(a, w);
                if (res == Result.RUNNING) return res;
                cur = null;
                rounds++;
                return Result.RUNNING;
            }
            Crew c = Crew.instance;
            if (r.untilItem != null && c != null && TaskBoard.chestCount(c.chestInv(w), Compiler.item(r.untilItem)) >= r.untilCount) {
                Events.log("routines: " + a.getCustomNameTag() + " done with " + r.name + " (" + r.untilCount + " " + r.untilItem + " in the chest)");
                return Result.OK;
            }
            Crew.Task skill = find(a, w);
            if (skill == null) { Events.log("routines: " + a.getCustomNameTag() + " finds nothing more for " + r.name + " within " + r.radius); return rounds > 0 ? Result.OK : Result.NO_ITEMS; }
            cur = r.deposit && c != null && c.chest != null ? new TaskBoard.Seq(skill, c.new Stash(null, 0)).lenient() : skill;
            return Result.RUNNING;
        }

        /** the nearest target not taken by another: a tree (all of it), a vein, a block */
        Crew.Task find(Agent a, WorldServer w) {
            int ax = (int) Math.floor(a.posX), ay = (int) Math.floor(a.posY), az = (int) Math.floor(a.posZ);
            Crew c = Crew.instance;
            if (r.target.equals("tree")) {
                int[] best = null;
                double bd = 1e9;
                for (int[] t : Crew.trunks(w, ax, ay, az, r.radius)) {
                    if (c != null && c.board.reserved(a, t[0], t[1], t[2])) continue;
                    double d = Math.hypot(t[0] - ax, t[2] - az);
                    if (d < bd && !Fell.tree(w, t[0], t[1], t[2]).isEmpty()) { bd = d; best = t; }
                }
                if (best == null) return null;
                if (c != null) c.board.reserve(a, best[0], best[1], best[2]);
                List<int[]> logs = Fell.tree(w, best[0], best[1], best[2]);
                return new TaskBoard.Seq(new Fell(logs), new Crew.Collect(best[0] + 0.5, best[1], best[2] + 0.5, 6)).lenient();
            }
            Block want = Block.getBlockFromName(r.target.substring(r.target.indexOf(' ') + 1));
            if (want == null) return null;
            int[] best = null;
            double bd = 1e9;
            for (int x = ax - r.radius; x <= ax + r.radius; x++)
                for (int z = az - r.radius; z <= az + r.radius; z++)
                    for (int y = Math.max(2, ay - 12); y <= ay + 8; y++) {
                        if (w.getBlock(x, y, z) != want || !Day2.open(w, x, y, z)) continue;   // one that can be got at (a face open to the air)
                        if (c != null && (!Ground.mayDig(c, x, y, z, "gather") || c.board.reserved(a, x, y, z))) continue;
                        double d = Math.abs(x - ax) + Math.abs(z - az) + Math.abs(y - ay) * 1.5;
                        if (d < bd) { bd = d; best = new int[] { x, y, z }; }
                    }
            if (best == null) return null;
            if (c != null) c.board.reserve(a, best[0], best[1], best[2]);
            List<int[]> blocks = r.target.startsWith("ore ") ? Day2.cluster(w, best[0], best[1], best[2], want, new java.util.HashSet<Long>()) : new ArrayList<int[]>(java.util.Arrays.asList(best));
            return new TaskBoard.Seq(new Crew.Mine(blocks), new Crew.Collect(best[0] + 0.5, best[1], best[2] + 0.5, 5)).lenient();
        }
    }

    /** the routine a request means, by the embeddings of its words and the routines' descriptions; null below the threshold */
    static Routine match(String text, double threshold) {
        load();
        double[] q = Embed.vector(text);
        if (q == null) return null;
        Routine best = null;
        double bs = threshold;
        synchronized (Routines.class) {
            for (Routine r : all) {
                if (r.vec == null) continue;
                double s = Embed.cos(q, r.vec);
                if (s >= bs) { bs = s; best = r; }
            }
        }
        if (best != null) Events.log("routines: '" + text + "' is '" + best.name + "' (" + String.format(java.util.Locale.ROOT, "%.2f", bs) + ")");
        return best;
    }

    /** embeddings: OpenAI text-embedding-3-small through the local proxy; the key from config/aiguide/openai.key (never printed) */
    static class Embed {
        static double[] vector(String text) {
            try {
                File kf = new File(new File(new File(System.getProperty("aiguide.config", "config")).getAbsoluteFile(), "aiguide"), "openai.key");
                if (!kf.isFile()) return null;
                String key = new String(java.nio.file.Files.readAllBytes(kf.toPath()), "UTF-8").trim();
                java.net.Proxy proxy = new java.net.Proxy(java.net.Proxy.Type.HTTP, new java.net.InetSocketAddress("127.0.0.1", 3128));
                java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL("https://api.openai.com/v1/embeddings").openConnection(proxy);
                h.setRequestMethod("POST");
                h.setConnectTimeout(8000);
                h.setReadTimeout(15000);
                h.setDoOutput(true);
                h.setRequestProperty("Authorization", "Bearer " + key);
                h.setRequestProperty("Content-Type", "application/json");
                JsonObject body = new JsonObject();
                body.addProperty("model", "text-embedding-3-small");
                body.addProperty("input", text);
                h.getOutputStream().write(body.toString().getBytes("UTF-8"));
                if (h.getResponseCode() != 200) { Events.log("routines: embeddings answer " + h.getResponseCode()); return null; }
                JsonArray v = new JsonParser().parse(new java.io.InputStreamReader(h.getInputStream(), "UTF-8")).getAsJsonObject().getAsJsonArray("data").get(0).getAsJsonObject().getAsJsonArray("embedding");
                double[] out = new double[v.size()];
                for (int i = 0; i < out.length; i++) out[i] = v.get(i).getAsDouble();
                return out;
            } catch (Exception e) {
                Events.log("routines: no embeddings: " + e.getClass().getSimpleName());
                return null;
            }
        }

        static double cos(double[] a, double[] b) {
            double d = 0, na = 0, nb = 0;
            for (int i = 0; i < Math.min(a.length, b.length); i++) { d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
            return d / Math.max(1e-12, Math.sqrt(na * nb));
        }
    }
}
