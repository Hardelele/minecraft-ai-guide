package hardelele.aiguide.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * crew already knows runs it at once unless it asks to manage a definition: the request and the routines' descriptions are compared by embeddings (an
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
    static File loadedFile;
    static String loadError;

    static File file() {
        MinecraftServer s = MinecraftServer.getServer();
        return s == null || s.worldServers == null || s.worldServers.length == 0 || s.worldServers[0] == null
                ? null : new File(s.worldServers[0].getSaveHandler().getWorldDirectory(), "routines.json");
    }

    static synchronized void load() {
        File f = file();
        if (f == null) { loaded = false; loadedFile = null; loadError = null; all.clear(); return; }
        if (loaded && f.equals(loadedFile)) return;
        loaded = true;
        loadedFile = f;
        loadError = null;
        all.clear();
        try {
            all.addAll(read(f));
        } catch (Exception e) {
            loadError = "Не удалось прочитать routines.json; файл не изменён.";
            Events.log("routines: not loaded: " + e);
        }
    }

    static synchronized boolean save() {
        try {
            File f = file();
            if (f == null || !f.equals(loadedFile) || loadError != null) return false;
            write(f, all);
            return true;
        } catch (Exception e) {
            Events.log("routines: not saved: " + e);
            return false;
        }
    }

    static List<Routine> read(File f) throws Exception {
        List<Routine> out = new ArrayList<Routine>();
        if (!f.isFile()) return out;
        java.util.Set<String> names = new java.util.HashSet<String>();
        JsonArray a = new JsonParser().parse(new String(Files.readAllBytes(f.toPath()), "UTF-8")).getAsJsonArray();
        for (JsonElement e : a) {
            Routine r = new Gson().fromJson(e, Routine.class);
            if (r == null || r.name == null || r.name.trim().isEmpty() || r.target == null
                    || !(r.target.equals("tree") || r.target.matches("(?:ore|block)\\s+\\S+"))
                    || r.radius < 4 || r.radius > 64 || r.untilCount < 0
                    || r.untilItem == null && r.untilCount != 0
                    || r.untilItem != null && (r.untilItem.trim().isEmpty() || r.untilCount < 1)
                    || !names.add(r.name.trim().toLowerCase(java.util.Locale.ROOT)))
                throw new IllegalStateException("Invalid routine definition in " + f.getName());
            out.add(r);
        }
        return out;
    }

    static void write(File f, List<Routine> definitions) throws Exception {
        Path backup = new File(f.getParentFile(), "routines.json.bak").toPath();
        if (f.isFile() && !Files.exists(backup)) Files.copy(f.toPath(), backup);
        Path tmp = new File(f.getParentFile(), "routines.json.tmp").toPath();
        Files.write(tmp, new Gson().toJson(definitions).getBytes("UTF-8"));
        try { Files.move(tmp, f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException ex) { Files.move(tmp, f.toPath(), StandardCopyOption.REPLACE_EXISTING); }
    }

    static synchronized List<String> names() {
        load();
        List<String> out = new ArrayList<String>();
        for (Routine r : all) out.add(r.name);
        return out;
    }

    static synchronized Routine named(String n) {
        load();
        if (n == null) return null;
        for (Routine r : all) if (r.name.equalsIgnoreCase(n.trim())) return r;
        return null;
    }

    static Routine copy(Routine from) {
        if (from == null) return null;
        Routine r = new Routine();
        r.name = from.name; r.description = from.description; r.target = from.target;
        r.untilItem = from.untilItem; r.untilCount = from.untilCount;
        r.radius = from.radius; r.deposit = from.deposit;
        r.vec = from.vec == null ? null : from.vec.clone();
        return r;
    }

    static synchronized Routine definition(String name) { return copy(named(name)); }
    static synchronized String error() { load(); return loadError; }

    /** A draft replaces a definition, never the object already held by a running Loop. */
    static synchronized String put(String original, String name, String description, String target, String until, int radius, boolean deposit) {
        load();
        if (loadError != null) return loadError;
        if (name == null || name.trim().isEmpty() || name.trim().length() > 48) return "Имя: от 1 до 48 символов.";
        if (target == null) return "Цель: tree, ore NAME или block NAME.";
        String t = target.trim().toLowerCase(java.util.Locale.ROOT);
        if (!(t.equals("tree") || t.startsWith("ore ") || t.startsWith("block "))) return "Цель: tree, ore NAME или block NAME.";
        if (!t.equals("tree") && Block.getBlockFromName(t.substring(t.indexOf(' ') + 1)) == null) return "Нет такого блока: " + t;
        if (radius < 4 || radius > 64) return "Радиус: от 4 до 64 блоков.";
        Routine old = original == null ? null : named(original);
        if (original != null && old == null) return "Сценарий уже удалён: " + original;
        Routine collision = named(name);
        if (collision != null && collision != old) return "Имя уже занято: " + name;
        Routine r = new Routine();
        r.name = name.trim(); r.description = description == null || description.trim().isEmpty() ? r.name : description.trim();
        r.target = t; r.radius = radius; r.deposit = deposit;
        if (until != null && !until.trim().isEmpty()) {
            String[] parts = until.trim().split("\\s+");
            if (parts.length != 2 || !parts[1].matches("\\d+") || Compiler.item(parts[0]) == null) return "Условие: ITEM COUNT, например log 64.";
            try { r.untilCount = Integer.parseInt(parts[1]); }
            catch (NumberFormatException ex) { return "Слишком большое количество."; }
            if (r.untilCount < 1) return "Количество должно быть больше нуля.";
            r.untilItem = parts[0];
        }
        int at = old == null ? -1 : all.indexOf(old);
        if (at < 0) all.add(r); else all.set(at, r);
        if (!save()) { if (at < 0) all.remove(r); else all.set(at, old); return "Не удалось сохранить routines.json."; }
        Events.log("routines: saved " + r.name + ": " + r.target + (r.untilItem != null ? " until " + r.untilCount + " " + r.untilItem : ""));
        final File worldFile = loadedFile;
        new Thread(new Runnable() { public void run() {
            double[] vec = Embed.vector(r.name + ". " + r.description);
            synchronized (Routines.class) {
                if (vec != null && worldFile.equals(loadedFile) && all.contains(r)) { r.vec = vec; save(); }
            }
        } }, "routine-embed").start();
        return null;
    }

    static synchronized String forget(String name) {
        load();
        if (loadError != null) return loadError;
        Routine r = named(name);
        if (r == null) return "Нет сценария: " + name;
        int at = all.indexOf(r);
        all.remove(at);
        if (!save()) { all.add(at, r); return "Не удалось сохранить routines.json."; }
        Events.log("routines: forgotten " + name);
        return null;
    }

    /** the guide's crew_routine tool: save (a new one, or changed), run (for agents), list, forget */
    public static String fromGuide(String action, String name, String description, String target, String until, String agents) {
        load();
        if (loadError != null) return loadError;
        String a = action == null ? "" : action.trim().toLowerCase();
        if (a.startsWith("list")) return all.isEmpty() ? "No routines yet." : "Routines: " + names();
        if (a.startsWith("forget")) { String error = forget(name); return error == null ? "Forgotten: " + name + ". Already running agents continue until stopped." : error; }
        if (a.startsWith("save")) {
            Routine previous = named(name);
            String error = put(previous == null ? null : name, name, description, target, until,
                    previous == null ? 24 : previous.radius, previous == null || previous.deposit);
            if (error != null) return error;
            Routine r = named(name);
            return "Saved routine '" + r.name + "' without starting it (open Scenarios in Commander or request run separately). Already running agents keep the previous definition until stopped.";
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
        final Routine scheduled = definition(name);
        if (scheduled == null) return;
        c.queue.add(new Runnable() {
            public void run() {
                final Routine r = scheduled;
                WorldServer w = MinecraftServer.getServer().worldServers[0];
                for (int n : agents)
                    for (Agent a : c.agents)
                        if (a.skin() == n && !a.isDead) {
                            Origin was = Crew.originNow;
                            Crew.originNow = Origin.PLAYER;
                            TaskBoard.Card k = c.board.post("routine " + r.name, () -> new Loop(copy(r))).pin(a).prio(Orders.PRIO).lease(20 * 3600);
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
