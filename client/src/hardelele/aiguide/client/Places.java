package hardelele.aiguide.client;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

/**
 * The memory of the world, its first part: what places the crew has seen. A cube of 32x32x32 blocks (cube
 * coordinates: the cube cx, cy, cz holds the world cells 32cx..32cx+31 and so on) is put in the queue; on the server
 * thread its input is gathered from what the crew saw (PlacesInput, a budget of block reads a tick), then on a worker
 * thread of its own the places net (PlacesNet) gives each cell its class and the cube is split into places
 * (PlacesSplit). known() gives the places found, the latest of each cube. Each cube's result goes to the events:
 * "places: cube ... -> N мест: room 2, passage 3 ...".
 *
 * Only an observer for now: nothing of the agents' behaviour depends on it. Each agent's cube is looked at now and
 * then (-Daiguide.places.auto=false: only what observe() is asked). -Daiguide.places=false: off altogether. The
 * weights: the jar's /assets/aiguide/places/places.bin, or the file in -Daiguide.places.weights; without them it says
 * so once and does nothing.
 */
public final class Places {
    static final boolean ON = !"false".equalsIgnoreCase(System.getProperty("aiguide.places", "true"));
    static final boolean AUTO = !"false".equalsIgnoreCase(System.getProperty("aiguide.places.auto", "true"));
    static final int S = PlacesNet.SIDE;
    static final int PENDING = 8;          // cubes waiting for their input, at most (more are refused)
    static final int READS = 20000;        // block reads a tick for the rays
    static final int AUTO_EVERY = 400;     // ticks between looks at the agents' cubes
    static final int AGAIN = 20 * 120;     // a cube is looked at again only after this many ticks

    static Places instance;

    final ArrayDeque<int[]> pending = new ArrayDeque<int[]>();
    final ArrayBlockingQueue<Job> jobs = new ArrayBlockingQueue<Job>(2);
    final ConcurrentLinkedQueue<Job> done = new ConcurrentLinkedQueue<Job>();
    final ConcurrentLinkedQueue<String> notes = new ConcurrentLinkedQueue<String>();   // the worker's words, for the events (logged on the server thread)
    final Map<Long, List<PlacesSplit.Place>> byCube = new LinkedHashMap<Long, List<PlacesSplit.Place>>();
    final Map<Long, Long> lookedAt = new HashMap<Long, Long>();
    final Map<Long, byte[]> inputs = new HashMap<Long, byte[]>();   // each cube's input as the net saw it (for the ratings)
    volatile List<PlacesSplit.Place> all = Collections.emptyList();
    volatile boolean broken;
    Job gathering;
    WorldServer world;
    int generation;
    Thread worker;

    /** a cube on its way: the input, then the net's classes and places */
    static final class Job {
        final int[] cube;   // cube coordinates
        final int generation;
        final long startTick;
        PlacesInput input;
        PlacesInput.View view;
        int ticks, seen;
        long ms;
        List<PlacesSplit.Place> places;
        String error;

        Job(int[] cube, int generation, long startTick) { this.cube = cube; this.generation = generation; this.startTick = startTick; }
    }

    static synchronized void start() {
        if (!ON || instance != null) return;
        instance = new Places();
        FMLCommonHandler.instance().bus().register(instance);
        PlaceSource.current = new PlacesSource();   // the rating layer shows these places
    }

    /** the weights in use, a hash of their bytes ("none" until the worker read them) */
    static volatile String weights = "none";

    /** the net's input of the cube whose corner is x0 y0 z0 (world cells), the last time it was looked at; null if none */
    static byte[] input(int x0, int y0, int z0) {
        Places p = instance;
        if (p == null) return null;
        synchronized (p) {
            return p.inputs.get(key(cube(x0), cube(y0), cube(z0)));
        }
    }

    /** put the cube cx, cy, cz (cube coordinates) in the queue; false if off, without weights or the queue is full */
    public static boolean observe(WorldServer w, int cx, int cy, int cz) {
        Places p = instance;
        return p != null && p.queue(w, cx, cy, cz);
    }

    /** the cube coordinates of a world cell */
    static int cube(int v) { return Math.floorDiv(v, S); }

    /** the places found so far (the latest of each cube looked at); a copy nobody changes */
    public static List<PlacesSplit.Place> known() {
        Places p = instance;
        return p == null ? Collections.<PlacesSplit.Place>emptyList() : p.all;
    }

    static long key(int cx, int cy, int cz) { return Crew.key(cx, cy, cz); }

    synchronized boolean queue(WorldServer w, int cx, int cy, int cz) {
        if (broken || w == null) return false;
        if (w != world) reset(w);
        for (int[] q : pending) if (q[0] == cx && q[1] == cy && q[2] == cz) return true;
        if (gathering != null && gathering.cube[0] == cx && gathering.cube[1] == cy && gathering.cube[2] == cz) return true;
        if (pending.size() >= PENDING) return false;
        pending.add(new int[] { cx, cy, cz });
        lookedAt.put(key(cx, cy, cz), w.getTotalWorldTime());
        return true;
    }

    /** another world: what was known of the last one goes */
    void reset(WorldServer w) {
        world = w;
        generation++;
        pending.clear();
        gathering = null;
        jobs.clear();
        byCube.clear();
        lookedAt.clear();
        inputs.clear();
        all = Collections.emptyList();
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        for (String n; (n = notes.poll()) != null; ) Events.log(n);
        if (broken) return;
        MinecraftServer s = MinecraftServer.getServer();
        if (s == null || s.worldServers == null || s.worldServers.length == 0) return;
        WorldServer w = s.worldServers[0];
        synchronized (this) {
            if (w != world) reset(w);
            for (Job j; (j = done.poll()) != null; ) finish(j);
            long now = w.getTotalWorldTime();
            Crew c = Crew.instance;
            if (AUTO && c != null && now % AUTO_EVERY == 0)
                for (Agent a : c.agents) {
                    if (a.isDead || a.worldObj != w) continue;
                    int cx = cube((int) Math.floor(a.posX)), cy = cube((int) Math.floor(a.posY)), cz = cube((int) Math.floor(a.posZ));
                    Long at = lookedAt.get(key(cx, cy, cz));
                    if (at == null || now - at >= AGAIN) queue(w, cx, cy, cz);
                }
            try {
                if (gathering == null && !pending.isEmpty()) {
                    int[] q = pending.poll();
                    gathering = new Job(q, generation, now);
                    gathering.input = PlacesInput.gather(w, q[0] * S, q[1] * S, q[2] * S);
                    gathering.view = PlacesInput.view(w);
                }
                if (gathering != null) {
                    Job j = gathering;
                    j.ticks++;
                    if ((j.input.eye >= j.input.eyes.size() || j.input.step(j.view, READS)) && jobs.offer(j)) {
                        j.seen = j.input.seen();
                        j.view = null;
                        gathering = null;
                        worker();
                    }
                }
            } catch (RuntimeException ex) {   // an observer must not stop the server: the cube is dropped
                Events.log("places: the input of a cube failed: " + ex);
                gathering = null;
            }
        }
    }

    void finish(Job j) {
        if (j.generation != generation) return;
        String at = "cube " + j.cube[0] + "," + j.cube[1] + "," + j.cube[2] + " (" + j.cube[0] * S + "," + j.cube[1] * S + "," + j.cube[2] * S + ")";
        if (j.error != null) {
            Events.log("places: " + at + " failed: " + j.error);
            return;
        }
        byCube.put(key(j.cube[0], j.cube[1], j.cube[2]), j.places);
        inputs.put(key(j.cube[0], j.cube[1], j.cube[2]), j.input.cls.clone());
        List<PlacesSplit.Place> list = new ArrayList<PlacesSplit.Place>();
        for (List<PlacesSplit.Place> l : byCube.values()) list.addAll(l);
        all = Collections.unmodifiableList(list);
        String sum = PlacesSplit.summary(j.places);
        Events.log("places: " + at + " -> " + j.places.size() + " мест" + (sum.isEmpty() ? "" : ": " + sum)
                + " (seen " + Math.round(100.0 * j.seen / (S * S * S)) + "%, " + j.input.eyes.size() + " eyes, input " + j.ticks + " ticks, net " + j.ms + " ms)");
    }

    /** the first 12 hex of the weights' SHA-1 (the file, else the jar's resource) */
    static String hash(String file) {
        try {
            java.io.InputStream in = file != null ? new java.io.FileInputStream(file) : PlacesNet.class.getResourceAsStream(PlacesNet.RESOURCE);
            if (in == null) return "none";
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
                byte[] b = new byte[1 << 16];
                for (int n; (n = in.read(b)) > 0; ) md.update(b, 0, n);
                StringBuilder s = new StringBuilder();
                for (byte x : md.digest()) s.append(String.format("%02x", x));
                return s.substring(0, 12);
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return "none";
        }
    }

    /** the worker thread: the net's weights once, then each cube as it comes */
    void worker() {
        if (worker != null) return;
        worker = new Thread(new Runnable() {
            public void run() {
                PlacesNet net;
                try {
                    String f = System.getProperty("aiguide.places.weights");
                    net = f != null ? PlacesNet.load(new File(f)) : PlacesNet.resource();
                    if (net != null) weights = hash(f);
                } catch (Exception ex) {
                    net = null;
                    notes.add("places: weights not read: " + ex);
                }
                if (net == null) {
                    broken = true;
                    notes.add("places: no weights (" + PlacesNet.RESOURCE + " is not in the jar, -Daiguide.places.weights not given): off");
                    return;
                }
                PlacesNet.Work work = net.work();
                byte[] cls = new byte[PlacesNet.CELLS];
                float[] prob = new float[PlacesNet.CELLS];
                while (true) {
                    Job j;
                    try {
                        j = jobs.take();
                    } catch (InterruptedException ex) {
                        return;
                    }
                    try {
                        long t0 = System.nanoTime();
                        work.forward(j.input.cls);
                        work.classes(cls, prob);
                        j.places = PlacesSplit.split(cls, prob, j.input.cls, work.bottleneck, new int[] { j.cube[0] * S, j.cube[1] * S, j.cube[2] * S });
                        j.ms = (System.nanoTime() - t0) / 1000000;
                    } catch (RuntimeException ex) {
                        j.error = String.valueOf(ex);
                    }
                    done.add(j);
                }
            }
        }, "aiguide-places");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY + 1);
        worker.start();
    }
}
