package hardelele.aiguide.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.authlib.GameProfile;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.minecraft.block.Block;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemPickaxe;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MathHelper;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

/**
 * The learning environment (docs/rl/CONTRACT-v2.md; contract 1: CONTRACT.md) on a dedicated server started with -Daiguide.rl=PORT: a TCP line
 * protocol a policy drives; the agents' minds take their decisions from it instead of a model (AgentMind asks decide).
 * At a decision point an agent stands; at the end of the tick the agents waiting are sent to the policy in one batch
 * and the server thread waits for its answer: no tick runs while the policy thinks. The reward is put together as it
 * happens (RlReward, the weights of RlSpec / rl/reward.json), each agent's part given at its next decision.
 * -Daiguide.rl.fast=true: the ticks back to back (no sleep of the server's loop) while an episode runs.
 */
public class RlEnv {
    static volatile RlEnv instance;
    /** how many times a mind asked a model since the server started (the environment's minds never should) */
    static volatile int modelCalls;

    static boolean on() { return instance != null; }

    static void start(int port) {
        RlEnv e = new RlEnv(port, Boolean.getBoolean("aiguide.rl.fast"));
        instance = e;
        AgentMind.model = body -> {   // the environment's minds have no model: a call here is a fault, counted and refused
            modelCalls++;
            throw new IllegalStateException("the learning environment's minds take no model");
        };
        FMLCommonHandler.instance().bus().register(e);
        RlEngine.start();
        e.listen();
        Events.log("rl: the environment on port " + port + (e.fast ? ", fast ticks" : "") + "; " + RlSpec.ACTIONS.size() + " actions (" + RlSpec.hash() + "), "
                + RlSpec.FEATURES.size() + " features (" + RlSpec.obsHash() + ")");
    }

    final int port;
    final boolean fast;
    final Map<String, Double> weights;

    RlEnv(int port, boolean fast) {
        this.port = port;
        this.fast = fast;
        JsonObject f = null;
        String rf = System.getProperty("aiguide.rl.reward");
        if (rf != null && !rf.isEmpty())
            try { f = new JsonParser().parse(new String(java.nio.file.Files.readAllBytes(new File(rf).toPath()), StandardCharsets.UTF_8)).getAsJsonObject(); }
            catch (Exception ex) { Events.log("rl: the reward file " + rf + " not read: " + ex + " (the built-in weights)"); }
        weights = RlSpec.rewardWeights(f);
    }

    // ---- the connection: one at a time, a line in is a request (the server thread answers) --------------------

    static final class Conn {
        final Socket s;
        final Writer out;
        Conn(Socket s) throws Exception { this.s = s; this.out = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8); }
        synchronized void send(JsonObject o) {
            try { out.write(o.toString()); out.write('\n'); out.flush(); } catch (Exception e) { close(); }
        }
        void close() { try { s.close(); } catch (Exception e) { /* gone */ } }
    }

    static final class Req {
        final Conn conn;
        final JsonObject msg;
        Req(Conn conn, JsonObject msg) { this.conn = conn; this.msg = msg; }
    }

    final LinkedBlockingQueue<Req> inbox = new LinkedBlockingQueue<Req>();
    volatile Conn conn;
    ServerSocket server;

    void listen() {
        try {
            server = new ServerSocket(port, 4, InetAddress.getByName(System.getProperty("aiguide.rl.host", "0.0.0.0")));
        } catch (Exception ex) {
            Events.log("rl: port " + port + " not opened: " + ex);
            return;
        }
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket s = server.accept();
                    s.setTcpNoDelay(true);
                    final Conn c = new Conn(s);
                    for (int i = 0; i < 40 && conn != null; i++) Thread.sleep(50);   // the last policy's going may be on its way
                    if (conn != null) {
                        JsonObject e = new JsonObject();
                        e.addProperty("error", "busy: another policy is connected");
                        c.send(e);
                        c.close();
                        continue;
                    }
                    conn = c;
                    Thread r = new Thread(() -> read(c), "AIGuide-rl-read");
                    r.setDaemon(true);
                    r.start();
                } catch (Exception ex) {
                    if (!server.isClosed()) Events.log("rl: accept failed: " + ex);
                }
            }
        }, "AIGuide-rl-accept");
        t.setDaemon(true);
        t.start();
    }

    void read(Conn c) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(c.s.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) {
                if (line.trim().isEmpty()) continue;
                JsonObject o;
                try {
                    o = new JsonParser().parse(line).getAsJsonObject();
                } catch (Exception ex) {
                    c.send(error("not a JSON object: " + ex.getClass().getSimpleName()));
                    continue;
                }
                inbox.add(new Req(c, o));
            }
        } catch (Exception ex) {
            // the policy went
        }
        if (conn == c) conn = null;   // free for the next at once (the server thread drops its episode at _gone)
        JsonObject gone = new JsonObject();
        gone.addProperty("op", "_gone");
        inbox.add(new Req(c, gone));
    }

    static JsonObject error(String why) {
        JsonObject o = new JsonObject();
        o.addProperty("error", why);
        return o;
    }

    // ---- the server thread: the episode's ticks, the answers, the wait for the policy -------------------------

    enum State { IDLE, RUN, WAIT }

    State state = State.IDLE;
    Conn replyTo;
    boolean nested;
    /** the time the ticks of the episode took (the policy's thinking left out): from each RUN to its answer */
    long simNs, runSince;

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        MinecraftServer s = MinecraftServer.getServer();
        if (s == null || s.worldServers == null || s.worldServers.length == 0) return;
        WorldServer w = s.worldServers[0];
        w.resetUpdateEntityTick();   // no player: entities stop moving after a minute unless told
        try {
            if (state == State.RUN) episodeTick(w);
            serve(s, w);
        } catch (Exception ex) {
            StringBuilder at = new StringBuilder();
            for (StackTraceElement st : ex.getStackTrace()) if (at.length() < 400) at.append(" @ ").append(st.getClassName().replace("hardelele.aiguide.client.", "")).append(':').append(st.getLineNumber());
            Events.log("rl failed: " + ex + at);
            if (state != State.IDLE) {
                done = true;
                doneReason = "error";
                if (replyTo != null) replyTo.send(error("the episode failed: " + ex));
                state = State.IDLE;
            }
        }
        if (fast && !nested) {
            // ticks back to back: from here on the server's ticks are run by this loop (the server's own loop, which
            // sleeps to keep 20 a second, gets back only when the server stops; its catch-up after this one long tick
            // is capped by its "can't keep up" rule at 2 s). Between episodes serve waits a fifth of a second a tick.
            nested = true;
            try {
                while (s.isServerRunning()) s.tick();
            } finally {
                nested = false;
            }
        }
    }

    void serve(MinecraftServer s, WorldServer w) throws Exception {
        if (state == State.RUN) {
            for (Req r; (r = inbox.poll()) != null; ) handle(r, s, w);   // (info, close, a new reset while it runs)
            if (state == State.RUN && (done || !waiting.isEmpty())) {
                simNs += System.nanoTime() - runSince;
                answer(w);
                state = done ? State.IDLE : State.WAIT;
            }
        }
        long spinUntil = RlEngine.ON ? System.nanoTime() + RlEngine.SPIN_NS : 0;
        while (state != State.RUN && s.isServerRunning()) {
            Req r = inbox.poll();   // the engine: the next request looked for a while before the thread sleeps (a woken thread is late)
            if (r == null && System.nanoTime() < spinUntil) { Thread.yield(); continue; }
            if (r == null) r = inbox.poll(state == State.IDLE ? 200 : 1000, TimeUnit.MILLISECONDS);
            if (r == null) {
                if (state == State.IDLE) break;   // nothing to do: a tick goes by (the server stays alive, its console too)
                continue;
            }
            handle(r, s, w);
        }
    }

    void handle(Req r, MinecraftServer s, WorldServer w) throws Exception {
        String op = r.msg.has("op") ? r.msg.get("op").getAsString() : "";
        switch (op) {
            case "_gone":
                if (r.conn == conn) conn = null;
                if (r.conn == replyTo) { replyTo = null; if (state != State.IDLE) { Events.log("rl: the policy went; episode " + episode + " dropped"); state = State.IDLE; } }
                return;
            case "info": r.conn.send(info()); return;
            case "audit": {   // the mask against the skill (ended): what the allowed actions came to
                JsonObject o = auditJson();
                if (r.msg.has("reset") && r.msg.get("reset").getAsBoolean()) audit.clear();
                r.conn.send(o);
                return;
            }
            case "reset":
                try {
                    reset(w, r.msg);
                    replyTo = r.conn;
                    state = State.RUN;
                    runSince = System.nanoTime();
                } catch (IllegalArgumentException ex) {
                    state = State.IDLE;
                    r.conn.send(error(ex.getMessage()));
                }
                return;
            case "step": step(w, r); return;
            case "close": {
                JsonObject ok = new JsonObject();
                ok.addProperty("ok", true);
                r.conn.send(ok);
                r.conn.close();
                if (r.conn == replyTo) { replyTo = null; state = State.IDLE; }
                return;
            }
            case "shutdown": {
                JsonObject ok = new JsonObject();
                ok.addProperty("ok", true);
                r.conn.send(ok);
                state = State.IDLE;
                s.initiateShutdown();
                return;
            }
            default: r.conn.send(error("unknown op '" + op + "': info, reset, step, close, shutdown"));
        }
    }

    JsonObject info() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        boolean v3 = defaultSpace == RlSpec.V3;   // the default's (3 unless -Daiguide.rl.contract=3.1): as a learner of it expects
        if (v3) o.addProperty("contract", 3); else o.addProperty("contract", RlSpec.NAME);
        JsonObject a = new JsonObject();
        a.addProperty("version", v3 ? RlSpec3.ACTIONS_VERSION : RlSpec.ACTIONS_VERSION);
        a.addProperty("hash", defaultSpace.hash());
        a.addProperty("n", defaultSpace.actions());
        o.add("actions", a);
        JsonObject f = new JsonObject();
        f.addProperty("version", v3 ? RlSpec3.OBS_VERSION : RlSpec.OBS_VERSION);
        f.addProperty("hash", defaultSpace.obsHash());
        f.addProperty("dim", defaultSpace.features());
        o.add("obs", f);
        JsonObject rw = new JsonObject();
        rw.addProperty("version", v3 ? RlSpec3.REWARD_VERSION : RlSpec.REWARD_VERSION);
        rw.addProperty("hash", v3 ? RlSpec3.rewardHash() : RlSpec.rewardHash());
        o.add("reward", rw);
        JsonObject ach = new JsonObject();
        ach.addProperty("version", v3 ? RlSpec3.ACHIEVEMENTS_VERSION : RlSpec.ACHIEVEMENTS_VERSION);
        ach.addProperty("n", RlAchievements.ALL.size());
        ach.addProperty("supported", RlAchievements.supported().size());
        o.add("achievements", ach);
        JsonObject cs = new JsonObject();   // the contracts it serves (reset "contract": "3" or "3.1")
        for (RlSpec.Space sp : new RlSpec.Space[] { RlSpec.V3, RlSpec.V2 }) {
            JsonObject x = new JsonObject();
            x.addProperty("actions_hash", sp.hash());
            x.addProperty("n", sp.actions());
            x.addProperty("obs_hash", sp.obsHash());
            x.addProperty("dim", sp.features());
            x.addProperty("reward_hash", sp == RlSpec.V3 ? RlSpec3.rewardHash() : RlSpec.rewardHash());
            cs.add(sp.name(), x);
        }
        o.add("contracts", cs);
        JsonArray sc = new JsonArray();
        for (String n : RlSpec.available()) sc.add(new JsonPrimitive(n));
        o.add("scenarios", sc);
        o.addProperty("fast", fast);
        o.addProperty("episodes", episode);
        o.addProperty("model_calls", modelCalls);
        o.add("engine", RlEngine.info());
        if (arena != null) o.addProperty("arena", arena.x + " " + arena.y + " " + arena.z + " (" + arena.picked + ")");
        return o;
    }

    // ---- an episode -------------------------------------------------------------------------------------------

    /** the spaces of the episode (reset's "contract"), and the default: 3 for the learning that runs on it, unless -Daiguide.rl.contract=3.1 */
    static final RlSpec.Space defaultSpace = "3.1".equals(System.getProperty("aiguide.rl.contract", "3").trim()) ? RlSpec.V2 : RlSpec.V3;
    RlSpec.Space space = defaultSpace;
    RlArena arena;
    RlSpec.Scenario sc;
    RlReward reward;
    long seed;
    int crewSize, episode;
    boolean raw, done;
    String doneReason = "", target = "", extraKit = "";
    long ticks, resetMs, startNs;
    final Set<Agent> waiting = new LinkedHashSet<Agent>();     // at a decision point this tick
    final Set<Integer> asked = new HashSet<Integer>();          // sent to the policy, its answer awaited
    final Map<Integer, Integer> chosen = new HashMap<Integer, Integer>();   // the policy's action for an agent, by its number
    final Map<Integer, int[]> masks = new HashMap<Integer, int[]>();
    final Map<Integer, Mine> mine = new HashMap<Integer, Mine>();
    int labelsAt;
    long labelsOffset;
    // the measures of the scales (RlScore) and what they paid with their causes
    RlScore.Track track = new RlScore.Track();
    RlScore.Sustain sustain = new RlScore.Sustain();
    RlScore.Perimeter perimeter = new RlScore.Perimeter();
    RlScore.Stall stall = new RlScore.Stall();
    double stockValue, sources;
    int breadMade, births;
    /** the decisions that changed blocks this second (the causes of a measured change of the world) */
    final List<RlCause.Link> worldChanges = new ArrayList<RlCause.Link>();
    /** the step's events: rewards with their chains (by agent, key, the chain's head), achievements, discoveries */
    final Map<String, JsonObject> rewardEvents = new LinkedHashMap<String, JsonObject>();

    // ---- the reward v4 (docs/rl/REWARD-v4.md): -Daiguide.rl.reward4=true (or a file of rl/reward-v4.json's form) ----
    // the step's reward is RlReward4's from the events below; v3's weights still measure (info.measured)
    static final String REWARD4 = System.getProperty("aiguide.rl.reward4", "");
    RlReward4 r4;
    final List<JsonObject> fev = new ArrayList<JsonObject>();
    final Set<Integer> mobs4 = new HashSet<Integer>(), killed4 = new HashSet<Integer>(), in4 = new HashSet<Integer>();
    final Set<String> aimed4 = new HashSet<String>();
    boolean goal4;
    /** the goals goal() can reach (1): the rest (waves' "none") caps below it, no goal to pay */
    static final java.util.Set<String> PAYS_GOAL = new java.util.HashSet<String>(java.util.Arrays.asList(
        "items", "food", "fortify", "armor", "bow", "mine_trip", "enchant", "achievements", "farm", "pasture"));

    static JsonObject params4() {
        if (REWARD4.isEmpty() || REWARD4.equals("true")) return RlReward4.defaults();
        try { return new JsonParser().parse(new String(java.nio.file.Files.readAllBytes(new File(REWARD4).toPath()), StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (Exception ex) { Events.log("rl: the reward v4 file " + REWARD4 + " not read: " + ex + " (the built-in)"); return RlReward4.defaults(); }
    }

    JsonObject ev4(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        fev.add(o);
        return o;
    }

    String phase4(WorldServer w) {
        if (sc != null && sc.waves != null) {
            WaveCycle.Phase ph = Waves.state().phase;
            if (ph == WaveCycle.Phase.WAVE) return "wave";
            if (ph == WaveCycle.Phase.BREAK) return "break";
            if (ph == WaveCycle.Phase.PREP) return "prep";
        }
        long tm = w == null ? 1000 : w.getWorldTime() % 24000;
        return tm >= 13000 && tm < 23000 ? "night" : "day";
    }

    /** the seconds a mob of the wave would stay in the base once in: the rest of its wave */
    static double stay4() {
        WaveCycle cy = Waves.cycle;
        return Math.max(0, cy.cfg.waveMaxTicks - cy.waveTicks) / 20.0;
    }

    /** a frame for RlReward4: the agents' state and the events since the last; its moves into info.events */
    void flush4(WorldServer w, double dt) {
        if (r4 == null) return;
        Crew c = Crew.instance;
        List<JsonObject> evs = new ArrayList<JsonObject>();
        if (c != null)
            for (Agent a : c.agents) {
                JsonObject o = new JsonObject();
                o.addProperty("type", "agent");
                o.addProperty("agent", id(a));
                o.addProperty("x", a.posX);
                o.addProperty("y", a.posY);
                o.addProperty("z", a.posZ);
                o.addProperty("hp", a.isDead ? 0 : a.getHealth());
                o.addProperty("alive", !a.isDead);
                o.addProperty("base_dist", c.base == null ? 0 : Math.hypot(a.posX - c.base[0] - 0.5, a.posZ - c.base[2] - 0.5));
                evs.add(o);
            }
        evs.addAll(fev);
        fev.clear();
        JsonArray arr = new JsonArray();
        for (JsonObject o : evs) arr.add(o);
        JsonObject fr = new JsonObject();
        fr.addProperty("t", ticks / 20.0);
        fr.addProperty("dt", dt);
        fr.addProperty("phase", phase4(w));
        fr.add("events", arr);
        for (JsonObject m : r4.frame(fr)) { m.addProperty("ticks", ticks); events.add(m); }
    }

    /** once a second: the wave's mobs as threats (to the base from their coming, to an agent when they go for it), the ones in the base, the ones gone */
    void mobs4(Crew c) {
        if (r4 == null || sc.waves == null) return;
        Set<Integer> now = new HashSet<Integer>();
        Defense.Perimeter p = DefenseWorld.ring;
        double ms = 0;
        for (EntityLiving m : Waves.enemies()) {
            int idn = m.getEntityId();
            now.add(idn);
            if (mobs4.add(idn)) {
                JsonObject o = ev4("threat");
                o.addProperty("mob", idn);
                o.addProperty("kind", m.getClass().getSimpleName());
                o.addProperty("target", "base");
                o.addProperty("dmg", 3.0);
                o.addProperty("stay_s", stay4());
            }
            if (m.getAttackTarget() instanceof Agent && aimed4.add(idn + "|" + id((Agent) m.getAttackTarget()))) {
                JsonObject o = ev4("threat");
                o.addProperty("mob", idn);
                o.addProperty("kind", m.getClass().getSimpleName());
                o.addProperty("target", id((Agent) m.getAttackTarget()));
                o.addProperty("dmg", 3.0);
            }
            if (p != null && p.inside(m.posX, m.posZ)) {
                ms += 1;
                if (in4.add(idn)) ev4("breach").addProperty("mob", idn);
            }
        }
        if (ms > 0) ev4("inside").addProperty("mob_s", ms);
        for (Integer idn : new ArrayList<Integer>(mobs4))
            if (!now.contains(idn)) {
                mobs4.remove(idn);
                if (!killed4.remove(idn)) {
                    JsonObject o = ev4("mob_gone");
                    o.addProperty("mob", idn);
                    o.addProperty("why", "despawn");
                }
            }
    }

    /** the base given up or lost: the threats open now count as done */
    void fall4(String why) {
        if (r4 == null) return;
        ev4("fall").addProperty("why", why);
    }
    final List<JsonObject> events = new ArrayList<JsonObject>();
    /** the decisions given to the actions of the last step: agent -> decision_id */
    final JsonObject assigned = new JsonObject();

    /** what the environment keeps of one agent through an episode */
    static final class Mine {
        String outcome = "none", lastTool = "";
        int last = -1, repeated, novel, trips;
        double seconds;
        Defense.Tally tally = new Defense.Tally();
        Map<String, Integer> ores = new HashMap<String, Integer>();
        RlScore.Trip trip = new RlScore.Trip();
        long dangerAt = -1;
        String dangerKind = "";
        List<RlCause.Link> water;
        boolean decided;
        final RlScore.Fails fails = new RlScore.Fails();
    }

    Mine mine(Agent a) {
        Mine m = mine.get(a.skin());
        if (m == null) mine.put(a.skin(), m = new Mine());
        return m;
    }

    static String id(Agent a) { return String.valueOf(a.skin()); }

    void reset(WorldServer w, JsonObject msg) {
        String name = msg.has("scenario") ? msg.get("scenario").getAsString() : "wood";
        RlSpec.Scenario s = RlSpec.scenario(name);
        if (s == null) throw new IllegalArgumentException("unknown scenario '" + name + "': " + RlSpec.available());
        String tg = msg.has("target") ? msg.get("target").getAsString() : "";
        extraKit = msg.has("extra_kit") ? msg.get("extra_kit").getAsString() : "";   // (a test's: more in each agent's kit)
        if (!tg.isEmpty() && (!RlAchievements.ALL.containsKey(tg) || !RlAchievements.ALL.get(tg).supported))
            throw new IllegalArgumentException("unknown or unsupported achievement '" + tg + "': " + RlAchievements.supported());
        String ct = msg.has("contract") ? msg.get("contract").getAsString() : "";
        RlSpec.Space sp = ct.isEmpty() ? defaultSpace : ct.equals("3") ? RlSpec.V3 : ct.equals(RlSpec.NAME) ? RlSpec.V2 : null;
        if (sp == null) throw new IllegalArgumentException("unknown contract '" + ct + "': 3 or " + RlSpec.NAME);
        long t0 = System.nanoTime();
        space = sp;
        sc = s;
        target = tg;
        seed = msg.has("seed") ? msg.get("seed").getAsLong() : episode;
        crewSize = Math.max(1, Math.min(RlSpec.MAX_AGENTS, msg.has("crew") ? msg.get("crew").getAsInt() : 1));
        raw = msg.has("raw") && msg.get("raw").getAsBoolean();
        w.disableLevelSaving = true;   // the region files stay as generated: the arena is put back from memory
        RlEngine.install(MinecraftServer.getServer());
        if (Waves.cycle.active()) Waves.stop("rl_reset");
        clearCrew();
        RlCauseHooks.start(w);
        if (!discoveriesRead) { loadDiscoveries(w); discoveriesRead = true; }
        if (arena == null) {
            arena = RlArena.make(w);
            Events.log("rl: the arena at " + arena.x + " " + arena.y + " " + arena.z + ", " + arena.chunks.size() + " chunks, " + arena.animals.size() + " animals (" + arena.picked + "), copied in " + arena.snapMs + " ms");
        }
        state = State.IDLE;   // (the world's listener ignores the restore's own changes)
        arena.restore(w);
        Exploration.clear(w);
        RlWorld.reset();
        RlAch.reset();
        cause.reset();
        Agent.hungerOn = s.hunger;
        w.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
        w.getGameRules().setOrCreateGameRule("doMobSpawning", "false");
        w.getGameRules().setOrCreateGameRule("keepInventory", "false");
        w.setWorldTime(s.time);
        w.getWorldInfo().setRaining(false);
        w.getWorldInfo().setThundering(false);
        w.getWorldInfo().setRainTime(20 * 3600 * 24);
        w.getWorldInfo().setThunderTime(20 * 3600 * 24);
        Crew c = Crew.ensure();
        c.loaded = true;   // (nothing of the world's folder: the arena is the crew's whole world)
        c.player = "Player";
        c.base = new int[] { arena.x, arena.y, arena.z };
        FakePlayer p = FakePlayerFactory.get(w, new GameProfile(UUID.nameUUIDFromBytes("aiguide-rl-player".getBytes()), "Player"));
        p.setLocationAndAngles(arena.x + 0.5, arena.y, arena.z + 0.5, 0, 0);
        Crew.standIn = p;
        java.util.Random rnd = new java.util.Random(seed);
        for (int i = 0; i < crewSize; i++) spawn(c, w, i, rnd);
        if (!s.chest.isEmpty()) chest(c, w, s.chest);
        if (!s.animals.isEmpty()) animals(w, s.animals, rnd);
        Mind.Goal g = new Mind.Goal("rl " + s.name + (s.defense ? ": защищай базу" : ""), null, 0);
        for (Agent a : c.agents) g.members.add(a.who());
        Mind.Limits lim = new Mind.Limits();
        lim.steps = lim.seconds = lim.tokens = lim.badCalls = lim.sameFailure = Integer.MAX_VALUE;
        for (Agent a : c.agents) AgentMind.minds.put(a.skin(), new AgentMind(a.skin(), new Mind(a.who(), g, lim, System.currentTimeMillis()), w));
        reward = new RlReward(weights, s);
        r4 = REWARD4.isEmpty() ? null : RlReward4.of(params4(), weights, s);
        fev.clear();
        mobs4.clear();
        killed4.clear();
        in4.clear();
        aimed4.clear();
        goal4 = false;
        done = false;
        doneReason = "";
        ticks = 0;
        waiting.clear();
        asked.clear();
        chosen.clear();
        masks.clear();
        mine.clear();
        track = new RlScore.Track();
        sustain = new RlScore.Sustain();
        perimeter = new RlScore.Perimeter();
        stall = new RlScore.Stall();
        stockValue = sources = 0;
        breadMade = births = 0;
        worldChanges.clear();
        rewardEvents.clear();
        events.clear();
        for (Agent a : c.agents) cause.decide(id(a), 0, "spawn");
        state = State.RUN;
        measure(c, w);   // the baselines: nothing paid for the kit, the arena as it is
        labels(w, true);
        if (s.waves != null) {
            listenWaves();
            Events.log("rl: waves " + s.waves + ": " + Waves.start(s.waves, "rl", seed));
        }
        episode++;
        resetMs = (System.nanoTime() - t0) / 1000000;
        startNs = System.nanoTime();
        simNs = 0;
        Events.log("rl: episode " + episode + " " + s.name + ", seed " + seed + ", crew " + crewSize + (target.isEmpty() ? "" : ", target " + target)
                + (s.hunger ? ", hunger" : "") + ", reset in " + resetMs + " ms (arena " + arena.restoreMs + " ms)");
    }

    /** the crew of the episode before gone (its minds, its board, its agents, what it knew of the world) */
    void clearCrew() {
        for (AgentMind m : new ArrayList<AgentMind>(AgentMind.minds.values())) if (m.thinking != null) m.thinking.cancel(true);
        AgentMind.minds.clear();
        AgentMind.shown.clear();
        Crew old = Crew.instance;
        if (old != null) {
            for (Agent a : old.agents) a.setDead();
            try { FMLCommonHandler.instance().bus().unregister(old); } catch (Exception e) { Events.log("rl: the old crew not unregistered: " + e); }
            Crew.instance = null;
        }
        Crew.site = null;
        Crew.front = null;
        Crew.phaseLog.clear();
        Crew.started.clear();
        Crew.finished.clear();
        Crew.notes.clear();
        Day1.mine = null;
        Day1.mineDepth = 0;
        Day1.lastBase = Day1.firstBase = null;
        Day1.furnace = null;
        Ground.fromBase = null;
        Ground.fromBaseAt = -1000;
        Ground.anchorCell = null;
        Ground.passages.clear();
        Ground.mineWay.clear();
        MindSkills.crewUnreached.clear();
        MindSkills.reachCache.clear();
        FogMap.explored.clear();
        DefenseWorld.ring = null;
        DefenseWorld.moat = null;
        DefenseWorld.lines.clear();
        DefenseWorld.lastHoles = -1;
        DefenseWorld.now = null;
        DefenseWorld.atWaveStart.clear();
        DefenseWorld.hurtSaid.clear();
        Defense.clear();
    }

    void spawn(Crew c, WorldServer w, int i, java.util.Random rnd) {
        int[] cell = Crew.safeCell(w, arena.x + rnd.nextInt(3) - 1, arena.y, arena.z + rnd.nextInt(3) - 1, i);
        if (cell == null) cell = new int[] { arena.x, arena.y, arena.z };
        int num = Crew.first() + i;
        Agent a = new Agent(w);
        a.number = num;
        a.setCustomNameTag(Crew.name(num));
        a.setAlwaysRenderNameTag(true);
        a.setLocationAndAngles(cell[0] + 0.5, cell[1], cell[2] + 0.5, rnd.nextFloat() * 360, 0);
        w.spawnEntityInWorld(a);
        a.setHealth(sc.health);
        a.xp.setLevels(sc.xpLevels);
        for (String[] k : RlSpec.kit(sc.kit + (extraKit.isEmpty() ? "" : ", " + extraKit))) {
            Object[] it = MindSkills.item(k[0]);
            if (it == null) { Events.log("rl: no item " + k[0] + " for the kit"); continue; }
            int n = Integer.parseInt(k[1]);
            Item item = (Item) it[0];
            while (n > 0) {
                int put = Math.min(n, item.getItemStackLimit());
                ItemStack st = new ItemStack(item, put, Math.max(0, (Integer) it[1]));
                a.bag.add(st);
                n -= put;
            }
        }
        ItemStack held = a.bestWeapon();
        if (held != null) a.setCurrentItemOrArmor(0, held);
        c.agents.add(a);
        Ledger.act(a, "rl spawn");
    }

    /** the scenario's animals ("Cow 3, Sheep 3"): on the ground 10 to 20 blocks from the base, in sight of nothing yet */
    void animals(WorldServer w, String what, java.util.Random rnd) {
        for (String[] k : RlSpec.kit(what))
            for (int i = 0; i < Integer.parseInt(k[1]); i++) {
                net.minecraft.entity.Entity e = net.minecraft.entity.EntityList.createEntityByName(k[0], w);
                if (e == null) { Events.log("rl: no animal " + k[0]); break; }
                double ang = rnd.nextDouble() * Math.PI * 2, d = 10 + rnd.nextDouble() * 10;
                int x = arena.x + (int) Math.round(Math.cos(ang) * d), z = arena.z + (int) Math.round(Math.sin(ang) * d);
                int y = RlArena.ground(w, x, z);
                e.setLocationAndAngles(x + 0.5, y, z + 0.5, rnd.nextFloat() * 360, 0);
                w.spawnEntityInWorld(e);
            }
    }

    /** the crew's chest beside the base with what the scenario puts in it */
    void chest(Crew c, WorldServer w, String what) {
        int[] at = Crew.safeCell(w, arena.x + 2, arena.y, arena.z + 2, 0);
        if (at == null) return;
        w.setBlock(at[0], at[1], at[2], Blocks.chest);
        TileEntity te = w.getTileEntity(at[0], at[1], at[2]);
        if (!(te instanceof IInventory)) return;
        IInventory inv = (IInventory) te;
        int slot = 0;
        for (String[] k : RlSpec.kit(what)) {
            Object[] it = MindSkills.item(k[0]);
            if (it == null) continue;
            int n = Integer.parseInt(k[1]);
            Item item = (Item) it[0];
            while (n > 0 && slot < inv.getSizeInventory()) {
                int put = Math.min(n, item.getItemStackLimit());
                inv.setInventorySlotContents(slot++, new ItemStack(item, put, Math.max(0, (Integer) it[1])));
                n -= put;
            }
        }
        c.chest = at;
    }

    boolean listening, discoveriesRead;

    void listenWaves() {
        if (listening) return;
        listening = true;
        Waves.listen(new Waves.Listener() {
            public void wave(String what, WaveCycle.View v) {
                if (state == State.IDLE || done || sc == null || sc.waves == null || Crew.instance == null) return;
                WorldServer w = Waves.world();
                if (what.equals("end")) {
                    boolean clean = perimeter.waveEnd();
                    for (Agent a : Crew.instance.agents) {
                        pay(id(a), "perimeter.wave_cleared", 1, null);
                        if (clean) pay(id(a), "perimeter.clean_wave", 1, null);
                    }
                } else if (what.equals("start") && v.phase == WaveCycle.Phase.WAVE && w != null) {
                    int holes = 0;
                    for (Defense.Perimeter p : DefenseWorld.walls()) { int st = DefenseWorld.standing(w, p); if (st > 0) holes += p.cells.size() - st; }
                    if (holes > 0) for (Agent a : Crew.instance.agents) pay(id(a), "perimeter.holes_at_wave", holes, null);
                }
            }
        });
    }

    void episodeTick(WorldServer w) {
        ticks++;
        Crew c = Crew.instance;
        if (c == null) return;
        for (Agent a : c.agents) if (!a.isDead) reward.add(id(a), "time", 1 / 20.0);
        FarmSkills.grow(w, RlSpec.GROW);
        if (ticks % 20 == 0) {
            progress(c, w);
            measure(c, w);
        }
        if (ticks % 100 == 0) labels(w, false);
        double g = goal(c, w);
        if (g >= 1) { finish("goal"); for (Agent a : c.agents) pay(id(a), "goal", 1, null); }
        else if (sc.waves != null && Waves.cycle.phase == WaveCycle.Phase.OVER) finish("lost");
        else if (ticks >= sc.seconds * 20L) finish("time");
    }

    void finish(String why) {
        if (done) return;
        if (why.equals("lost") || why.equals("noop")) fall4(why);
        done = true;
        doneReason = why;
        if (Waves.cycle.active()) Waves.stop("rl_" + why);
    }

    /** once a second: the tallies (their tally of the fight for the observation), the stall, the dangers past */
    void progress(Crew c, WorldServer w) {
        double t = ticks / 20.0;
        for (Agent a : c.agents) {
            Mine m = mine(a);
            if (a.isDead) { m.dangerAt = -1; continue; }
            m.tally = Defense.of(a.who()).copy();
            if (stall.check(id(a), t)) pay(id(a), "stall", 1, null);
            // a danger past: alive, out of the lava and not burning 3 s after, with water put for it: saved
            if (m.dangerAt >= 0 && ticks - m.dangerAt >= 60 && !a.handleLavaMovement() && a.fallDistance < 1) {
                if (!a.isBurning() && m.water != null) effect(a, "agent_saved", m.water, 0, null);
                m.dangerAt = -1;
                m.water = null;
            }
        }
    }

    // ---- the measures of the scales (once a second) -----------------------------------------------------------------

    static final String[] ORES = { "coal", "iron_ore", "gold_ore", "redstone", "diamond", "obsidian" };
    static final String[] ORE_KEYS = { "mine.coal", "mine.iron", "mine.gold", "mine.redstone", "mine.diamond", "mine.obsidian" };

    void measure(Crew c, WorldServer w) {
        boolean base = ticks == 0;   // the reset's: the baselines only
        double t = ticks / 20.0;
        Map<String, Double> stock = new HashMap<String, Double>();
        List<Agent> alive = new ArrayList<Agent>();
        for (Agent a : c.agents) if (!a.isDead) alive.add(a);
        for (Agent a : alive) {
            String k = id(a);
            Mine m = mine(a);
            // equipment
            double[] e = RlScore.equipment(worn(a), swordTier(a), pickTier(a), DefenseSkills.bow(a) != null, Skills.count(a.bag, Items.arrow, -1));
            for (int i = 0; i < e.length; i++) pay(k, RlScore.EQUIPMENT[i], track.delta(k + "|" + RlScore.EQUIPMENT[i], e[i]), null);
            // the mine: a horizon reached, ore got by digging, a trip back
            int fy = MathHelper.floor_double(a.boundingBox.minY);
            boolean under = fy < RlWorld.now.surfaceY(c) - 4;
            int h = under ? RlScore.horizon(fy) : 0;
            pay(k, "mine.depth", track.rise(k + "|horizon", h), null);
            Map<String, Integer> bag = MindSkills.bag(a);
            int oreGain = 0;
            AgentMind mm = AgentMind.minds.get(a.skin());
            String tool = mm != null && mm.call != null ? mm.call.tool : m.lastTool;
            boolean digging = tool.equals("mine") || tool.equals("tunnel") || tool.equals("descend");
            for (int i = 0; i < ORES.length; i++) {
                int n = count(bag, ORES[i]);
                Integer was = m.ores.get(ORES[i]);
                m.ores.put(ORES[i], n);
                if (was != null && n > was && digging) { pay(k, ORE_KEYS[i], n - was, null); oreGain += n - was; }
            }
            if (!base && m.trip.update(h, under, oreGain)) { m.trips++; pay(k, "mine.return", 1, null); }
            // shut in at night or in a wave (3.1: a metric, its weight 0)
            if (space == RlSpec.V2 && !base) {
                Map<String, Double> nb = new HashMap<String, Double>();
                int[] fc = PrimSkills.facing(a);
                long tm = w.getWorldTime() % 24000;
                RlNear.facts(nb, PrimSkills.grid(a, w), MathHelper.floor_double(a.posX), MathHelper.floor_double(a.boundingBox.minY + 0.1), MathHelper.floor_double(a.posZ), fc[0], fc[1],
                        tm >= 13000 && tm < 23000 || Waves.state().phase == WaveCycle.Phase.WAVE);
                if (RlSpec.g(nb, "shelter") > 0.5) reward.add(k, "safety.shelter", 1);
            }
            // enchanted things it has
            pay(k, "enchant.item", track.rise(k + "|enchanted", enchanted(a)), null);
            // its part of the stock
            for (ItemStack s : a.bag) stockAdd(stock, s);
        }
        IInventory inv = c.chestInv(w);
        if (inv != null) for (int i = 0; i < inv.getSizeInventory(); i++) if (inv.getStackInSlot(i) != null) stockAdd(stock, inv.getStackInSlot(i));
        // the crew's: each agent paid the change (the decisions that changed blocks this second as the causes)
        List<RlCause.Link> world = new ArrayList<RlCause.Link>(worldChanges);
        worldChanges.clear();
        stockValue = RlScore.stock(stock);
        double dStock = track.delta("stock", stockValue);
        Defense.Perimeter p = DefenseWorld.ring;
        double wall = 0, moat = moatFrac(w), light = 0, gate = 0;
        if (p != null && !p.cells.isEmpty()) {
            wall = DefenseWorld.standing(w, p) / (double) p.cells.size();
            light = litFrac(c, w);
        }
        int[] dr = RlWorld.now.door;
        if (GearSkills.isDoor(w, dr)) gate = 1;
        double dWall = track.delta("wall", wall), dMoat = track.delta("moat", moat), dLight = track.delta("light", light), dGate = track.delta("gate", gate);
        int[] tb = GearSkills.table(alive.isEmpty() ? null : alive.get(0), w, 64);
        double dTable = track.delta("table", tb != null ? 1 : 0), dShelf = track.delta("shelves", tb == null ? 0 : GearSkills.shelves(w, tb));
        double[] su = sustain.pay();
        Map<String, Double> farm = new HashMap<String, Double>();
        if (!alive.isEmpty()) FarmSkills.facts(farm, c, alive.get(0), w);
        sources = RlScore.sources((int) RlSpec.g(farm, "farm_planted"), (int) RlSpec.g(farm, "pen_adults"), (int) RlSpec.g(farm, "saplings_planted"));
        double dSources = track.delta("sources", sources);
        // the perimeter: the enemies inside the ring this second
        double[] pm = { 0, 0 };
        if (p != null && sc.waves != null) {
            List<Integer> in = new ArrayList<Integer>();
            for (EntityLiving m : Waves.enemies()) if (!m.isDead && p.inside(m.posX, m.posZ)) in.add(m.getEntityId());
            pm = perimeter.second(in, 1);
        }
        if (base) { flush4(w, 0); return; }
        mobs4(c);
        for (Agent a : alive) {
            String k = id(a);
            pay(k, "stock.value", dStock, null);
            pay(k, "safety.wall", dWall, world);
            pay(k, "safety.moat", dMoat, world);
            pay(k, "safety.light", dLight, world);
            pay(k, "safety.gate", dGate, world);
            pay(k, "enchant.table", dTable, world);
            pay(k, "enchant.shelf", dShelf, world);
            pay(k, "sustain.food", su[0], null);
            pay(k, "sustain.wood", su[1], null);
            pay(k, "sustain.source", dSources, world);
            pay(k, "perimeter.mob_seconds", pm[0], null);
            pay(k, "perimeter.breach", pm[1], null);
        }
        flush4(w, 1.0);
    }

    static int count(Map<String, Integer> bag, String base) {
        int n = 0;
        for (Map.Entry<String, Integer> e : bag.entrySet()) {
            String k = e.getKey(), b = k.indexOf(':') < 0 ? k : k.substring(0, k.indexOf(':'));
            if (b.equals(base) || base.equals("redstone") && b.equals("redstone")) n += e.getValue();
        }
        return n;
    }

    /** a stack's part of the stock (RlSpec.STOCK's keys; food in points) */
    static void stockAdd(Map<String, Double> st, ItemStack s) {
        if (s == null) return;
        String id = MindSkills.id(s), b = id.indexOf(':') < 0 ? id : id.substring(0, id.indexOf(':'));
        if (RlSkills.food(s)) { add(st, "food", ((ItemFood) s.getItem()).getHealAmount(s) * s.stackSize); return; }
        if (b.equals("log") || b.equals("log2")) add(st, "log", s.stackSize);
        else if (b.equals("planks")) add(st, "log", s.stackSize / 4.0);
        else add(st, b, s.stackSize);
    }

    static void add(Map<String, Double> m, String k, double v) { m.put(k, (m.containsKey(k) ? m.get(k) : 0) + v); }

    /** the things it has (the bag and what it wears) that carry an enchantment */
    static int enchanted(Agent a) {
        int n = 0;
        for (ItemStack s : a.bag) if (s != null && s.isItemEnchanted()) n++;
        for (int i = 0; i <= 4; i++) { ItemStack s = a.getEquipmentInSlot(i); if (s != null && s.isItemEnchanted() && !a.bag.contains(s)) n++; }
        return n;
    }

    /** the share of the dark spots for torches inside the ring that are lit (8 or more) */
    static double litFrac(Crew c, WorldServer w) {
        int[] o = DefenseWorld.centre(c, w);
        if (o == null || DefenseWorld.ring == null) return 0;
        int all = 0, lit = 0;
        for (int[] s : Defense.lights(DefenseWorld.ring, o[0], o[2], 12, 5, DefenseWorld.floor(w, o[1]))) {
            if (!Crew.soft(w, s[0], s[1], s[2]) || !Crew.solid(w, s[0], s[1] - 1, s[2])) continue;
            all++;
            if (w.getSavedLightValue(EnumSkyBlock.Block, s[0], s[1], s[2]) >= 8) lit++;
        }
        return all == 0 ? 0 : lit / (double) all;
    }

    /** the reward of an agent with the chain of its causes (none given: the agent's decision running now) */
    double pay(String agent, String key, double n, List<RlCause.Link> chain) {
        if (n == 0 || reward == null) return 0;
        if (r4 != null) {   // v4: the outcomes and the scales as events; v3's weights only measure (no reward events of theirs)
            pay4(agent, key, n, chain);
            reward.add(agent, key, n);
            reward.open.remove(agent);
            return 0;
        }
        double p = reward.add(agent, key, n);
        if (p == 0) return 0;
        String comp = RlSpec.component(key);
        if (p > 0 && RlScore.progress(comp)) stall.progress(agent, ticks / 20.0);
        List<RlCause.Link> ch = chain != null && !chain.isEmpty() ? RlCause.chain(chain.toArray(new RlCause.Link[0])) : RlCause.chain(cause.now(agent));
        String head = ch.isEmpty() ? "-" : String.valueOf(ch.get(0).id);
        String ek = agent + "|" + key + "|" + head;
        JsonObject e = rewardEvents.get(ek);
        if (e == null) {
            rewardEvents.put(ek, e = new JsonObject());
            e.addProperty("type", "reward");
            e.addProperty("agent", agent);
            e.addProperty("key", key);
            e.addProperty("points", 0.0);
            e.add("chain", RlCause.json(ch));
        }
        e.addProperty("points", RlReward.round(e.get("points").getAsDouble() + p));
        return p;
    }

    void pay4(String agent, String key, double n, List<RlCause.Link> chain) {
        String comp = RlSpec.component(key);
        if (key.equals("noop")) {   // what the run gives up (reward v4's forfeit): the seconds left, the goal still to pay
            JsonObject o = ev4("noop");
            o.addProperty("agent", agent);
            o.addProperty("left_s", sc == null ? 0.0 : Math.max(0.0, sc.seconds - ticks / 20.0));
            o.addProperty("goal", !goal4 && sc != null && PAYS_GOAL.contains(sc.goal));
        }
        else if (key.equals("goal")) { if (!goal4) { goal4 = true; ev4("goal"); } }
        else if (key.equals("human")) { JsonObject o = ev4("human"); o.addProperty("agent", agent); o.addProperty("rating", n); }
        else if (key.equals("curiosity.novel")) ev4("novel").addProperty("agent", agent);
        else if (RlSpec.isScale(comp) && !comp.equals("perimeter") && !comp.equals("curiosity")) {
            JsonObject o = ev4("scale");
            o.addProperty("agent", agent);
            o.addProperty("key", key);
            o.addProperty("delta", n);
            if (chain != null && !chain.isEmpty()) {
                JsonArray ids = new JsonArray();
                for (RlCause.Link l : chain) if (l != null) ids.add(new JsonPrimitive(l.id));
                o.add("causes", ids);
            }
        }
        // damage, death, kills, breaches, mob-seconds: their own events where they happen; time, stall, skill_*, perimeter.*: not paid
    }

    /** the tiers by the tool's material (wood and gold 1, stone 2, iron 3, diamond 4): the best pickaxe and sword it has */
    static int material(String m) {
        if (m == null) return 0;
        if (m.equals("EMERALD")) return 4;
        if (m.equals("IRON")) return 3;
        if (m.equals("STONE")) return 2;
        return m.equals("WOOD") || m.equals("GOLD") ? 1 : 0;
    }

    /** the armour points it wears (the four armour slots; the game's sum counts an armour piece held in the hand too) */
    static int worn(Agent a) {
        int n = 0;
        for (int i = 1; i <= 4; i++) { ItemStack s = a.getEquipmentInSlot(i); if (s != null && s.getItem() instanceof ItemArmor) n += ((ItemArmor) s.getItem()).damageReduceAmount; }
        return n;
    }

    static List<ItemStack> things(Agent a) {
        List<ItemStack> all = new ArrayList<ItemStack>(a.bag);
        for (int i = 0; i <= 4; i++) { ItemStack s = a.getEquipmentInSlot(i); if (s != null && !all.contains(s)) all.add(s); }
        return all;
    }

    static int pickTier(Agent a) {
        int t = 0;
        for (ItemStack s : things(a)) if (s.getItem() instanceof ItemPickaxe) t = Math.max(t, material(((ItemPickaxe) s.getItem()).getToolMaterialName()));
        return t;
    }

    static int swordTier(Agent a) {
        int t = 0;
        for (ItemStack s : things(a)) if (s.getItem() instanceof ItemSword) t = Math.max(t, material(((ItemSword) s.getItem()).getToolMaterialName()));
        return t;
    }

    static int hoeTier(Agent a) {
        int t = 0;
        for (ItemStack s : things(a)) if (s.getItem() instanceof net.minecraft.item.ItemHoe) t = Math.max(t, material(((net.minecraft.item.ItemHoe) s.getItem()).getMaterialName()));
        return t;
    }

    /** the agent's best tiers: pickaxe, weapon (sword, bow), armour (in the bag and worn) */
    static int[] tiers(Agent a) {
        int[] t = new int[3];
        for (ItemStack s : things(a)) {
            int k = EpochScore.tier(MindSkills.id(s));
            if (s.getItem() instanceof ItemPickaxe) t[0] = Math.max(t[0], k);
            else if (s.getItem() instanceof ItemSword || s.getItem() instanceof ItemBow) t[1] = Math.max(t[1], k);
            else if (s.getItem() instanceof ItemArmor) t[2] = Math.max(t[2], k);
        }
        t[0] = pickTier(a);
        return t;
    }

    /** the scenario's goal: how far (0..1) */
    double goal(Crew c, WorldServer w) {
        if (sc == null) return 0;
        int need = Math.max(1, sc.crewGoal ? sc.perAgent : sc.perAgent * crewSize);
        switch (sc.goal) {
            case "items": {
                Object[] it = MindSkills.item(sc.goalItem);
                if (it == null) return 0;
                int n = 0;
                for (Agent a : c.agents) if (!a.isDead) n += Skills.count(a.bag, (Item) it[0], -1);
                if ((Item) it[0] == Day1.LOG) for (Agent a : c.agents) if (!a.isDead) n += Skills.count(a.bag, Day1.LOG2, -1);
                IInventory inv = c.chestInv(w);
                if (inv != null) n += Crew.count(inv, (Item) it[0]);
                return Math.min(1, n / (double) need);
            }
            case "food": {
                int n = 0;
                for (Agent a : c.agents) if (!a.isDead) for (ItemStack s : a.bag) if (RlSkills.food(s)) n += s.stackSize;
                return Math.min(1, n / (double) need);
            }
            case "fortify": {
                Defense.Perimeter p = DefenseWorld.ring;
                if (p == null || p.cells.isEmpty()) return 0;
                double wall = DefenseWorld.standing(w, p) / (double) p.cells.size();
                double moat = moatFrac(w);
                return wall >= 1 && moat >= 0.9 ? 1 : 0.5 * Math.min(1, wall) + 0.5 * Math.min(1, moat / 0.9) * 0.999;
            }
            case "armor": case "bow": case "mine_trip": case "enchant": case "achievements": {
                double s = 0;
                for (Agent a : c.agents) {
                    double v;
                    if (sc.goal.equals("armor")) v = worn(a) / (double) sc.perAgent;
                    else if (sc.goal.equals("bow")) v = DefenseSkills.bow(a) == null ? 0 : Skills.count(a.bag, Items.arrow, -1) / (double) sc.perAgent;
                    else if (sc.goal.equals("mine_trip")) v = mine(a).trips;
                    else if (sc.goal.equals("enchant")) v = enchanted(a);
                    else v = RlAch.count(a.who()) / (double) sc.perAgent;
                    s += Math.min(1, v);
                }
                return c.agents.isEmpty() ? 0 : s / c.agents.size();
            }
            case "farm": return Math.min(1, breadMade / (double) need);
            case "pasture": return Math.min(1, births / (double) need);
            default:
                return Math.min(0.999, Waves.state().cleared / 10.0);
        }
    }

    static double moatFrac(WorldServer w) {
        if (DefenseWorld.moat == null || DefenseWorld.moat.isEmpty()) return 0;
        int dug = 0, all = 0;
        for (int[] q : DefenseWorld.moat) { if (q[3] > 1) continue; all++; if (DefenseWorld.open(w, q[0], q[1], q[2])) dug++; }
        return all == 0 ? 0 : dug / (double) all;
    }

    /** the person's ratings (the ratings' panel: aiguide-labels/actions.jsonl of the world): new lines about an agent */
    void labels(WorldServer w, boolean skipOld) {
        File f = new File(new File(w.getSaveHandler().getWorldDirectory(), Labels.DIR), Labels.ACTIONS);
        if (!f.isFile()) { if (skipOld) labelsOffset = 0; return; }
        if (skipOld) { labelsOffset = f.length(); return; }
        if (f.length() <= labelsOffset || Crew.instance == null) return;
        try {
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            String tail = new String(b, (int) Math.min(labelsOffset, b.length), (int) Math.max(0, b.length - labelsOffset), StandardCharsets.UTF_8);
            labelsOffset = b.length;
            for (String line : tail.split("\n")) {
                if (line.trim().isEmpty()) continue;
                JsonObject o = new JsonParser().parse(line).getAsJsonObject();
                if (!o.has("target") || !o.get("target").isJsonObject()) continue;
                String who = CaseVec.str(o.getAsJsonObject("target"), "agent");
                int rating = CaseVec.num(o, "rating", 0) > 0 ? 1 : -1;
                for (Agent a : Crew.instance.agents) if (a.who().equals(who)) pay(id(a), "human", rating, null);
            }
        } catch (Exception e) {
            Events.log("rl: the ratings not read: " + e);
        }
    }

    // ---- the minds ask (AgentMind) ----------------------------------------------------------------------------

    /** a mind at a decision point: the policy's action for it if there is one now, else null (it stands and waits) */
    static Mind.Call decide(AgentMind m, Agent a, WorldServer w) {
        RlEnv e = instance;
        if (e == null || e.state == State.IDLE || e.done) return null;
        Mine mm = e.mine(a);
        if (!mm.decided) { mm.decided = true; RlAch.decided(a); }
        Integer k = e.chosen.remove(a.skin());
        if (k != null) {
            mm.last = k;
            int[] mk = e.masks.get(a.skin());
            if (mk != null && mk[k] == 0) {
                Mind.Call c = RlSpec.call(k);
                c.tool = "masked";
                c.why = "policy: " + RlSpec.ACTIONS.get(k).id + " (masked)";
                return c;
            }
            return RlSpec.call(k);
        }
        if (!e.asked.contains(a.skin())) e.waiting.add(a);
        return null;
    }

    /** a mind's step ended (AgentMind.record): its outcome for the next observation, its reward */
    static void ended(AgentMind m, Agent a, String tool, String result, Map<String, Integer> before, Map<String, Integer> after, double seconds) {
        RlEnv e = instance;
        if (e == null || e.reward == null || e.state == State.IDLE) return;
        Mine mm = e.mine(a);
        mm.outcome = RlSpec.outcome(result);
        mm.seconds = seconds;
        mm.repeated = m.mind.repeated();
        mm.lastTool = tool;
        String k = id(a);
        if (mm.last >= 0) {   // the mask against the skill: what an allowed action came to
            int[] mk = e.masks.get(a.skin());
            if (mk != null && mm.last < mk.length && mk[mm.last] == 1) e.audit(mm.last, result, seconds);
        }
        if (mm.last >= 0) {   // (3.1) the memory of failures; the retry's penalty and the change of approach's bonus in a 3.1 episode
            double[] fr = mm.fails.step(mm.last, "OK".equals(result), result);
            if (e.space == RlSpec.V2) { e.pay(k, "retry_fail", fr[0], null); e.pay(k, "switch_ok", fr[1], null); }
        }
        if (!"OK".equals(result)) e.pay(k, "skill_fail", 1, null);
        else if (effect(tool, before, after)) e.pay(k, "skill_ok", 1, null);   // an OK with nothing measurable to show pays nothing (wait, look_around)
        if (before != null && after != null && (tool.equals("craft") || tool.equals("smelt"))) {
            int bread = count(after, "bread") - count(before, "bread");
            if (tool.equals("craft") && bread > 0) e.breadMade += bread;
            int logs = count(before, "log") + count(before, "log2") - count(after, "log") - count(after, "log2");
            if (logs > 0) e.sustain.add(e.ticks / 20.0, RlScore.WOOD_USED, logs);
        }
    }

    /**
     * The mask against the skill ("audit" of the protocol): for each action the mask allowed, what its skill came to,
     * by the result's code ("OK", "NO_ITEMS", …), a failure within a game second apart ("NO_ITEMS@0": the skill did not
     * even start). Kept over the server's life; "audit" with "reset": true clears it.
     */
    final Map<String, Map<String, Integer>> audit = new java.util.TreeMap<String, Map<String, Integer>>();

    void audit(int action, String result, double seconds) {
        String id = action < space.actions() ? RlSpec.ACTIONS.get(action).id : String.valueOf(action);
        String code = result == null ? "?" : result.split("[:\\s]", 2)[0];
        if (!"OK".equals(code) && seconds <= 1.0) code += "@0";
        Map<String, Integer> m = audit.get(id);
        if (m == null) audit.put(id, m = new java.util.TreeMap<String, Integer>());
        m.put(code, (m.containsKey(code) ? m.get(code) : 0) + 1);
    }

    JsonObject auditJson() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        JsonObject by = new JsonObject();
        for (Map.Entry<String, Map<String, Integer>> e : audit.entrySet()) {
            JsonObject x = new JsonObject();
            for (Map.Entry<String, Integer> c : e.getValue().entrySet()) x.addProperty(c.getKey(), c.getValue());
            by.add(e.getKey(), x);
        }
        o.add("audit", by);
        return o;
    }

    /** a step OK that changed something one can check: the bag (contract 2: no pay for an OK of words alone) */
    static boolean effect(String tool, Map<String, Integer> before, Map<String, Integer> after) {
        if (tool.equals("wait") || tool.equals("look_around")) return false;
        return before != null && after != null && !MindSkills.diff(before, after).isEmpty();
    }

    /** an agent died (Agent.onDeath): in a fight, or by an accident (hunger too); outside the wall in a wave besides */
    static void died(Agent a, String cause) {
        RlEnv e = instance;
        if (e == null || e.reward == null || e.state == State.IDLE || e.done) return;
        String k = id(a);
        if (e.r4 != null) {
            JsonObject o = e.ev4("death");
            o.addProperty("agent", k);
            o.addProperty("cause", cause == null ? "mob" : cause);
            Crew c = Crew.instance;
            o.addProperty("base_dist", c == null || c.base == null ? 0 : Math.hypot(a.posX - c.base[0] - 0.5, a.posZ - c.base[2] - 0.5));
        }
        e.pay(k, "accident".equals(cause) || "starve".equals(cause) ? "accident" : "death", 1, null);
        Defense.Perimeter p = DefenseWorld.ring;
        if (Waves.state().phase == WaveCycle.Phase.WAVE && p != null && !p.inside(a.posX, a.posZ)) e.pay(k, "perimeter.out_died", 1, null);
    }

    // ---- the causes (RlCause, RlCauseHooks): what the world and the bodies tell -----------------------------------

    final RlCause cause = new RlCause();

    static RlEnv running() {
        RlEnv e = instance;
        return e == null || e.state == State.IDLE || e.reward == null ? null : e;
    }

    /** orbs an agent picked up */
    static void xpPicked(Agent a, int points) { }

    /** an agent in lava or falling from a height this tick: the start of a danger it may be saved from */
    static void danger(Agent a, String what) {
        RlEnv e = running();
        if (e == null) return;
        Mine m = e.mine(a);
        if (m.dangerAt < 0) { m.dangerAt = e.ticks; m.dangerKind = what; }
    }

    /** the water reflex put water under the agent (its bucket filled by the decision made, 0 unknown): a decision of its own */
    static void reflexWater(Agent a, int x, int y, int z, long made) {
        RlEnv e = running();
        if (e == null) return;
        RlCause.Link r = e.cause.aside(id(a), e.ticks, "reflex:water");
        e.cause.placed(RlCause.key(x, y, z), "water", r);
        Mine m = e.mine(a);
        m.water = RlCause.chain(r.via("block:water"), made > 0 ? e.cause.item(made, "water_bucket") : null);
        e.worldChanges.add(r.via("block:water"));
    }

    /** an agent's decision put a block (a skill's PlaceFrom, a bucket poured, a fire lit): its mark */
    static void placed(Agent a, WorldServer w, int x, int y, int z) {
        Block b = w.getBlock(x, y, z);
        if (b == Blocks.enchanting_table) RlWorld.now.enchTable = new int[] { x, y, z };
        RlEnv e = running();
        if (e == null) return;
        RlCause.Link l = e.cause.current.get(id(a));
        if (l == null) return;
        Object n = Block.blockRegistry.getNameForObject(b);
        e.cause.placed(RlCause.key(x, y, z), n == null ? "?" : n.toString(), l);
        RlCause.Link via = l.via("block:" + RlCause.plain(n == null ? "?" : n.toString()));
        e.worldChanges.add(via);
        Mine m = e.mine(a);
        if (m.dangerAt >= 0 && b.getMaterial() == net.minecraft.block.material.Material.water) m.water = RlCause.chain(via);
    }

    /** an agent broke a block (Body.dig): its mark gone; a log of a tree the crew grew is renewable wood */
    static void broken(Agent a, WorldServer w, int x, int y, int z) {
        long key = RlCause.key(x, y, z);
        RlEnv e = running();
        if (e == null) return;
        e.cause.broken(key);
        RlCause.Link l = e.cause.current.get(id(a));
        if (l != null) e.worldChanges.add(l.via("broke"));
        if (RlWorld.now.grownLogs.remove(key)) e.sustain.add(e.ticks / 20.0, RlScore.WOOD_RENEW, 1);
    }

    /** a thing an agent's decision made (a craft, a bucket filled): it carries the decision (things that do not stack) */
    static void made(Agent a, ItemStack s) {
        RlEnv e = running();
        if (e == null || s == null) return;
        RlCause.Link l = e.cause.current.get(id(a));
        if (l != null) RlCauseHooks.tag(s, l.id);
    }

    /** renewable food or wood got (FarmSkills: a harvest of the crew's farm, the meat of an animal born in its pen) */
    static void renewable(Agent a, int kind, double amount) {
        RlEnv e = running();
        if (e != null) e.sustain.add(e.ticks / 20.0, kind, amount);
    }

    /** food points an agent ate (any food) */
    static void ate(Agent a, double points) {
        RlEnv e = running();
        if (e != null) e.sustain.add(e.ticks / 20.0, RlScore.FOOD_EATEN, points);
    }

    /** levels an agent spent on an enchanting */
    static void enchanted(Agent a, ItemStack s, int levels) {
        RlEnv e = running();
        if (e != null) e.pay(id(a), "enchant.levels", levels, null);
    }

    /** a new achievement of an agent (RlAch): its reward, the event */
    static void achieved(Agent a, String id) {
        RlEnv e = running();
        if (e == null) return;
        String k = id(a);
        e.pay(k, "achievement.new", 1, null);
        e.pay(k, "achievement.depth", RlAchievements.depth(id), null);
        JsonObject o = new JsonObject();
        o.addProperty("type", "achievement");
        o.addProperty("agent", k);
        o.addProperty("id", id);
        o.addProperty("name", RlAchievements.ALL.get(id).ru);
        o.addProperty("ticks", e.ticks);
        e.events.add(o);
    }

    /** an animal born (a child entered the world): in the pen, of animals the crew fed: a birth, breedCow for a cow's feeder */
    void born(net.minecraft.entity.passive.EntityAnimal child) {
        if (!RlWorld.now.inPen(child.posX, child.posZ)) return;
        RlWorld.now.born.add(child.getEntityId());
        births++;
        Set<Integer> feeders = new HashSet<Integer>();
        for (Object o : child.worldObj.getEntitiesWithinAABB(child.getClass(), child.boundingBox.expand(3, 2, 3))) {
            net.minecraft.entity.passive.EntityAnimal p = (net.minecraft.entity.passive.EntityAnimal) o;
            Integer by = RlWorld.now.fedBy.get(p.getEntityId());
            if (p != child && !p.isChild() && by != null) feeders.add(by);
        }
        Crew c = Crew.instance;
        List<RlCause.Link> chain = new ArrayList<RlCause.Link>();
        for (Integer f : feeders) {
            RlCause.Link l = cause.current.get(String.valueOf(f));
            if (l != null) chain.add(l.via("fed"));
            if (c != null && child instanceof net.minecraft.entity.passive.EntityCow) for (Agent a : c.agents) if (a.skin() == f) RlAch.grant(a, "breedCow");
        }
        JsonObject o = new JsonObject();
        o.addProperty("type", "effect");
        o.addProperty("effect", "animal_born");
        o.add("chain", RlCause.json(chain));
        o.addProperty("ticks", ticks);
        events.add(o);
        Events.log("rl: a " + child.getCommandSenderName() + " born in the pen (" + births + ")");
    }

    /** a living thing hurt or killed (or an agent saved), with the chain of its causes: rewards, achievements, discoveries */
    void effect(EntityLivingBase v, String kind, List<RlCause.Link> chain, float amount, DamageSource s) {
        if (state == State.IDLE || reward == null || done) return;
        Crew c = Crew.instance;
        RlCause.Link head = chain.isEmpty() ? null : chain.get(0);
        Agent by = head == null || c == null ? null : agent(c, head.agent);
        for (Object[] n : cause.novel(chain, kind)) {
            JsonObject o = new JsonObject();
            o.addProperty("type", "novel_effect");
            o.addProperty("agent", head == null ? "" : head.agent);
            o.addProperty("cause", (String) n[1]);
            o.addProperty("effect", kind);
            o.addProperty("first_ever", (Boolean) n[2]);
            o.add("chain", RlCause.json(chain));
            o.addProperty("ticks", ticks);
            events.add(o);
            if (by != null) mine(by).novel++;
            if (RlCause.curious(kind) && head != null) pay(head.agent, "curiosity.novel", 1, chain);
            Events.log("rl: discovery " + n[0] + (head == null ? "" : " by agent " + head.agent + " (" + head.kind + ")") + ((Boolean) n[2] ? " — first ever" : ""));
            if ((Boolean) n[2]) discovery((String) n[0], head == null ? "" : head.agent, chain);
        }
        if (r4 != null && kind.equals("mob_killed") && head != null) {
            JsonObject o = ev4("kill");
            o.addProperty("mob", v.getEntityId());
            o.addProperty("agent", head.agent);
            o.addProperty("decision", head.id);
            JsonArray seen = new JsonArray();
            seen.add(new JsonPrimitive(head.agent));
            o.add("seen", seen);
            killed4.add(v.getEntityId());
        }
        if (r4 != null && kind.equals("agent_hurt") && v instanceof Agent) {
            JsonObject o = ev4("damage");
            o.addProperty("agent", id((Agent) v));
            o.addProperty("hp", amount);
            if (s != null && s.getEntity() != null && !(s.getEntity() instanceof Agent)) o.addProperty("mob", s.getEntity().getEntityId());
        }
        if (kind.equals("mob_killed") && head != null) {
            pay(head.agent, "perimeter.kill", 1, chain);
            boolean hit = "hit".equals(head.via) || "shot".equals(head.via);
            if (by != null && hit) {
                RlAch.grant(by, "killEnemy");
                if ("shot".equals(head.via) && v instanceof net.minecraft.entity.monster.EntitySkeleton && Math.hypot(v.posX - by.posX, v.posZ - by.posZ) >= 50) RlAch.grant(by, "snipeSkeleton");
            }
        } else if (kind.equals("mob_hurt") && by != null && amount >= 18 && "hit".equals(head.via)) RlAch.grant(by, "overkill");
        else if (kind.equals("agent_hurt") && v instanceof Agent) {
            Agent a = (Agent) v;
            pay(id(a), "damage", amount, chain);
            Defense.Perimeter p = DefenseWorld.ring;
            if (p != null && sc.waves != null && p.inside(a.posX, a.posZ) && c != null)
                for (Agent g : c.agents) if (!g.isDead) pay(id(g), "perimeter.hurt_inside", amount, chain);
        } else if (kind.equals("agent_saved")) {
            JsonObject o = new JsonObject();
            o.addProperty("type", "effect");
            o.addProperty("effect", "agent_saved");
            o.addProperty("agent", v instanceof Agent ? id((Agent) v) : "");
            o.add("chain", RlCause.json(chain));
            o.addProperty("ticks", ticks);
            events.add(o);
            Events.log("rl: " + v.getCommandSenderName() + " saved by " + RlCause.kinds(chain));
        }
    }

    static Agent agent(Crew c, String id) {
        for (Agent a : c.agents) if (id(a).equals(id)) return a;
        return null;
    }

    /** a kind of link and effect never met before: a line of the discoveries' journal (for the film) */
    void discovery(String what, String who, List<RlCause.Link> chain) {
        try {
            String f = System.getProperty("aiguide.rl.discoveries");
            File file;
            if (f != null && !f.isEmpty()) file = new File(f);
            else {
                WorldServer w = MinecraftServer.getServer().worldServers[0];
                file = new File(new File(w.getSaveHandler().getWorldDirectory(), "aiguide-rl"), "discoveries.jsonl");
            }
            file.getParentFile().mkdirs();
            JsonObject o = new JsonObject();
            o.addProperty("what", what);
            o.addProperty("who", who);
            o.add("chain", RlCause.json(chain));
            o.addProperty("episode", episode);
            o.addProperty("scenario", sc == null ? "" : sc.name);
            o.addProperty("ticks", ticks);
            o.addProperty("time", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new java.util.Date()));
            java.nio.file.Files.write(file.toPath(), (o.toString() + "\n").getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ex) {
            Events.log("rl: the discovery not written: " + ex);
        }
    }

    /** the kinds met before (the journal's), so that first_ever holds across the server's restarts */
    void loadDiscoveries(WorldServer w) {
        try {
            String f = System.getProperty("aiguide.rl.discoveries");
            File file = f != null && !f.isEmpty() ? new File(f) : new File(new File(w.getSaveHandler().getWorldDirectory(), "aiguide-rl"), "discoveries.jsonl");
            if (!file.isFile()) return;
            for (String line : new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).split("\n")) {
                if (line.trim().isEmpty()) continue;
                JsonObject o = new JsonParser().parse(line).getAsJsonObject();
                if (o.has("what")) cause.ever.add(o.get("what").getAsString());
            }
        } catch (Exception ex) {
            Events.log("rl: the discoveries not read: " + ex);
        }
    }

    // ---- the answer: the observations, the masks, the rewards ---------------------------------------------------

    void answer(WorldServer w) {
        Crew c = Crew.instance;
        JsonObject o = new JsonObject(), obs = new JsonObject(), mask = new JsonObject(), rew = new JsonObject(), comps = new JsonObject(), rawOut = new JsonObject();
        List<Agent> who = new ArrayList<Agent>();
        if (done && c != null) { for (Agent a : c.agents) if (!a.isDead) who.add(a); }
        else who.addAll(waiting);
        Set<String> paid = new HashSet<String>();
        flush4(w, 0);
        for (Agent a : who) {
            Map<String, Double> r = facts(c, a, w);
            int[] mk = space.mask(r);
            masks.put(a.skin(), mk);
            obs.add(id(a), floats(space.vector(r)));
            mask.add(id(a), ints(mk));
            if (raw) rawOut.add(id(a), map(r));
            JsonObject cp = new JsonObject();
            rew.addProperty(id(a), RlReward.round(r4 != null ? r4.take(id(a), cp) : reward.take(id(a), cp)));
            comps.add(id(a), cp);
            paid.add(id(a));
            if (!done) asked.add(a.skin());
        }
        if (done)   // the dead at the end (between death and coming back) and whatever is left: paid too
            for (String k : new ArrayList<String>(r4 != null ? r4.open.keySet() : reward.open.keySet())) {
                if (paid.contains(k)) continue;
                JsonObject cp = new JsonObject();
                rew.addProperty(k, RlReward.round(r4 != null ? r4.take(k, cp) : reward.take(k, cp)));
                comps.add(k, cp);
            }
        waiting.clear();
        o.add("obs", obs);
        o.add("mask", mask);
        o.add("reward", rew);
        o.addProperty("done", done);
        JsonObject info = new JsonObject();
        info.add("components", comps);
        info.addProperty("ticks", ticks);
        info.addProperty("episode", episode);
        info.addProperty("scenario", sc.name);
        info.addProperty("seed", seed);
        info.addProperty("goal_progress", RlReward.round(c == null ? 0 : goal(c, w)));
        info.addProperty("model_calls", modelCalls);
        JsonObject as = new JsonObject();
        for (Map.Entry<String, JsonElement> e : assigned.entrySet()) as.add(e.getKey(), e.getValue());
        info.add("assigned", as);
        JsonArray ev = new JsonArray();
        for (JsonObject e : rewardEvents.values()) ev.add(e);
        for (JsonObject e : events) ev.add(e);
        info.add("events", ev);
        rewardEvents.clear();
        events.clear();
        long wall = (System.nanoTime() - startNs) / 1000000;
        double sim = Math.max(1e-6, simNs / 1e9);
        info.addProperty("wall_ms", wall);
        info.addProperty("sim_ms", Math.round(sim * 1000));
        info.addProperty("game_s_per_s", Math.round(ticks / 20.0 / sim * 10) / 10.0);
        info.addProperty("reset_ms", resetMs);
        if (done) {
            info.addProperty("done_reason", doneReason);
            info.add("episode_reward", r4 != null ? r4.episodeJson() : reward.episodeJson());
            info.add("measured", reward.measuredJson());
            JsonObject ach = new JsonObject();
            if (c != null)
                for (Agent a : c.agents) {
                    JsonArray l = new JsonArray();
                    for (String x : RlAch.of(a.who())) l.add(new JsonPrimitive(x));
                    ach.add(id(a), l);
                }
            info.add("achievements", ach);
            Events.log("rl: episode " + episode + " " + sc.name + " done (" + doneReason + ") at " + ticks / 20 + " game s, " + wall + " ms, reward " + reward.episodeJson());
        }
        if (raw) info.add("raw", rawOut);
        o.add("info", info);
        if (replyTo != null) replyTo.send(o);
    }

    void step(WorldServer w, Req r) {
        if (state != State.WAIT || r.conn != replyTo) { r.conn.send(error(state == State.IDLE ? "no episode: reset first" : "not waiting for actions now")); return; }
        if (!r.msg.has("actions") || !r.msg.get("actions").isJsonObject()) { r.conn.send(error("step needs actions: {agent: action}")); return; }
        Map<Integer, Integer> got = new LinkedHashMap<Integer, Integer>();
        for (Map.Entry<String, JsonElement> e : r.msg.getAsJsonObject("actions").entrySet()) {
            int who, k;
            try {
                who = Integer.parseInt(e.getKey());
                k = e.getValue().getAsInt();
            } catch (Exception ex) {
                r.conn.send(error("bad action " + e.getKey() + ": " + e.getValue()));
                return;
            }
            if (!asked.contains(who)) { r.conn.send(error("agent " + who + " was not asked")); return; }
            if (k < 0 || k >= space.actions()) { r.conn.send(error("action " + k + " out of 0.." + (space.actions() - 1))); return; }
            got.put(who, k);
        }
        for (String k : new ArrayList<String>(assignedKeys())) assigned.remove(k);
        for (Map.Entry<Integer, Integer> e : got.entrySet()) {   // each a decision of its own: its id (the chains' and the answer's)
            RlCause.Link l = cause.decide(String.valueOf(e.getKey()), ticks, RlSpec.ACTIONS.get(e.getValue()).id);
            assigned.addProperty(String.valueOf(e.getKey()), l.id);
            if (r4 != null) {
                JsonObject o = ev4("decision");
                o.addProperty("agent", String.valueOf(e.getKey()));
                o.addProperty("id", l.id);
                o.addProperty("kind", l.kind);
            }
        }
        boolean noop = false;
        for (Map.Entry<Integer, Integer> e : got.entrySet())
            if (e.getValue() == 0) {   // nothing: the episode ends at once with its penalty (the world back at the next reset)
                pay(String.valueOf(e.getKey()), "noop", 1, null);
                Mine mm = mine.get(e.getKey());
                if (mm != null) mm.last = 0;
                noop = true;
            }
        asked.clear();   // the ones not answered come back at their next tick
        if (noop) {
            finish("noop");
            answer(w);
            state = State.IDLE;
            return;
        }
        chosen.putAll(got);
        replyTo = r.conn;
        state = State.RUN;
        runSince = System.nanoTime();
    }

    List<String> assignedKeys() {
        List<String> l = new ArrayList<String>();
        for (Map.Entry<String, JsonElement> e : assigned.entrySet()) l.add(e.getKey());
        return l;
    }

    static JsonArray floats(float[] v) {
        JsonArray a = new JsonArray();
        for (float x : v) a.add(new JsonPrimitive(Math.round(x * 1e5) / 1e5));
        return a;
    }

    static JsonArray ints(int[] v) {
        JsonArray a = new JsonArray();
        for (int x : v) a.add(new JsonPrimitive(x));
        return a;
    }

    static JsonObject map(Map<String, Double> r) {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, Double> e : r.entrySet()) o.addProperty(e.getKey(), RlReward.round(e.getValue()));
        return o;
    }

    // ---- the raw facts of an agent (the observation's and the mask's) -------------------------------------------

    static final Block[] STONE = { Blocks.stone, Blocks.cobblestone };

    Map<String, Double> facts(Crew c, Agent a, WorldServer w) {
        Map<String, Double> r = facts(c, a, w, sc.name, mine(a), ticks / (20.0 * sc.seconds), goal(c, w));
        if (space == RlSpec.V3) for (String k : new ArrayList<String>(r.keySet())) if (only31(k)) r.remove(k);   // a contract 3 episode: its facts as they were
        return r;
    }

    /** the facts contract 3.1 added (round the agent: RlNear) */
    static boolean only31(String k) {
        return k.startsWith("nb_") || k.startsWith("fail_run_") || k.startsWith("fail_recent_") || k.startsWith("lastfail_") || k.equals("trees_reachable") || k.equals("walls_around") || k.equals("enclosed") || k.equals("shelter") || k.equals("pillar_ok");
    }

    /**
     * The raw facts of an agent: the environment's (above) and the game's policy (PolicyMind: a mind's goal as a
     * scenario, its own memo of the last step, its time and progress) from the same code; the episode's own (the
     * scales' measures, the achievements' target, the stall) when the environment runs.
     */
    static Map<String, Double> facts(Crew c, Agent a, WorldServer w, String scenario, Mine mm, double timeFrac, double progress) {
        Map<String, Double> r = new LinkedHashMap<String, Double>();
        put(r, "scen_" + scenario.replace(':', '_'), 1);
        RlSpec.Scenario sco = RlSpec.scenario(scenario);
        if (sco != null) for (String s : RlSpec.SCALES) if (sco.scale(s) > 0) put(r, "scale_" + s, 1);
        int slot = 0;
        for (Agent g : c.agents) { if (g == a) break; slot++; }
        put(r, "agent_" + Math.min(slot, RlSpec.MAX_AGENTS - 1), 1);
        put(r, "n_agents", c.agents.size());
        put(r, "health", a.getHealth());
        put(r, "max_health", a.getMaxHealth());
        put(r, "low_health", Defense.LOW);
        put(r, "armor", worn(a));
        put(r, "held_tier", a.getHeldItem() == null ? 0 : EpochScore.tier(MindSkills.id(a.getHeldItem())));
        int[] tr = tiers(a);
        put(r, "pick_tier", tr[0]);
        put(r, "weapon_tier", tr[1]);
        put(r, "sword_tier", swordTier(a));
        put(r, "hoe_tier", hoeTier(a));
        put(r, "hunger_on", Agent.hungerOn ? 1 : 0);
        put(r, "food_level", Agent.hungerOn ? a.hunger.food : 20);
        put(r, "saturation", Agent.hungerOn ? a.hunger.saturation : 5);
        put(r, "xp_level", a.xp.level);
        put(r, "xp_progress", a.xp.progress);
        double baseD = c.base == null ? 32 : Math.hypot(a.posX - c.base[0] - 0.5, a.posZ - c.base[2] - 0.5);
        put(r, "at_home", baseD < 4 ? 1 : 0);
        long t = w.getWorldTime() % 24000;
        put(r, "day_sin", Math.sin(2 * Math.PI * t / 24000));
        put(r, "day_cos", Math.cos(2 * Math.PI * t / 24000));
        put(r, "night", t >= 13000 && t < 23000 ? 1 : 0);
        put(r, "dusk_in", t < 12500 ? (12500 - t) / 20.0 : 0);
        // the bag
        Map<String, Double> bag = new HashMap<String, Double>();
        double total = 0, stack = 0, fuel = 0, food = 0;
        for (ItemStack s : a.bag) {
            String k = bagKey(s);
            if (k != null) add(bag, k, s.stackSize);
            if (MindSkills.id(s).equals("bread")) add(bag, "bread", s.stackSize);
            if (s.getItem() instanceof ItemArmor) add(bag, "armor", s.stackSize);
            if (RlSkills.food(s)) food += s.stackSize;
            total += s.stackSize;
            if (!(s.getItem() instanceof net.minecraft.item.ItemTool) && !(s.getItem() instanceof ItemSword)) stack += s.stackSize;
            if (net.minecraft.tileentity.TileEntityFurnace.getItemBurnTime(s) > 0) fuel += s.stackSize;
        }
        for (String b : RlSpec.BAG) put(r, "bag_" + b, bag.containsKey(b) ? bag.get(b) : 0);
        put(r, "bag_total", total);
        put(r, "bag_stackables", stack);
        put(r, "fuel", fuel);
        put(r, "food_items", food);
        Map<String, Integer> ids = MindSkills.bag(a);
        String mat = Defense.material("any", ids);
        // (mask only) blocks for the wall, if its work would start: some open cell it can put one in (the take of 9 November: 238 build_wall and 316 repair_wall BLOCKED in a row)
        put(r, "wall_blocks", mat == null ? 0 : !DefenseSkills.canPut(c, a, w, mat) ? 0 : Defense.count(ids, mat));
        // what the crew has seen near
        List<int[]> trees = MindSkills.trunks(c, a, w, 40, true);
        put(r, "trees_seen", trees.size());
        put(r, "tree_dist", trees.isEmpty() ? 48 : Crew.dist(a, trees.get(0)[0] + 0.5, trees.get(0)[1], trees.get(0)[2] + 0.5));
        int reach = MindSkills.reachableTrunks(c, a, w, 48).size();   // free, and a walk gets there (not given up)
        put(r, "trees_free", reach);
        put(r, "trees_reachable", reach);
        seen(r, c, a, w, "stone", STONE, 32, 32);
        seen(r, c, a, w, "coal", new Block[] { Blocks.coal_ore }, 48, 48);
        seen(r, c, a, w, "iron", new Block[] { Blocks.iron_ore }, 48, 48);
        seen(r, c, a, w, "gold", new Block[] { Blocks.gold_ore }, 48, 48);
        seen(r, c, a, w, "redstone", new Block[] { Blocks.redstone_ore, Blocks.lit_redstone_ore }, 48, 48);
        seen(r, c, a, w, "diamond", new Block[] { Blocks.diamond_ore }, 48, 48);
        seen(r, c, a, w, "obsidian", new Block[] { Blocks.obsidian }, 48, 48);
        seen(r, c, a, w, "gravel", new Block[] { Blocks.gravel }, 32, 32);
        put(r, "coal_minable", minable(c, a, w, Blocks.coal_ore));
        put(r, "iron_minable", minable(c, a, w, Blocks.iron_ore));
        put(r, "gold_minable", minable(c, a, w, Blocks.gold_ore));
        put(r, "redstone_minable", minable(c, a, w, Blocks.redstone_ore) + minable(c, a, w, Blocks.lit_redstone_ore));
        put(r, "diamond_minable", minable(c, a, w, Blocks.diamond_ore));
        put(r, "obsidian_minable", minable(c, a, w, Blocks.obsidian));
        put(r, "gravel_minable", minable(c, a, w, Blocks.gravel));
        List<EntityLiving> an = RlSkills.animals(a, w, RlSkills.SEE);
        put(r, "animals_seen", an.size());
        put(r, "animal_dist", an.isEmpty() ? 24 : a.getDistanceToEntity(an.get(0)));
        put(r, "frontier", RlSkills.frontier(a, w) == null ? 0 : 1);
        int[] mem = Exploration.size(w);
        put(r, "memory_seen", mem[0]);
        put(r, "memory_ground", mem[1]);
        put(r, "on_ground", a.onGround ? 1 : 0);
        // the stations
        station(r, c, a, w, "table", Roles.Role.TABLE, 24);
        station(r, c, a, w, "furnace", Roles.Role.FURNACE, 24);
        int[] ch = c.chest != null && Roles.is(w, c.chest, Roles.Role.CHEST) ? c.chest : null;
        put(r, "chest_known", ch == null ? 0 : 1);
        put(r, "chest_dist", ch == null ? 48 : Crew.dist(a, ch[0] + 0.5, ch[1], ch[2] + 0.5));
        put(r, "chest_food", RlSkills.foodCount(ch == null ? null : c.chestInv(w)));
        put(r, "base_known", c.base == null ? 0 : 1);
        put(r, "base_dist", baseD);
        // the waves
        WaveCycle.View v = Waves.state();
        put(r, "waves_active", v.active() ? 1 : 0);
        put(r, "wave_on", v.phase == WaveCycle.Phase.WAVE ? 1 : 0);
        put(r, "phase_prep", v.phase == WaveCycle.Phase.PREP ? 1 : 0);
        put(r, "phase_break", v.phase == WaveCycle.Phase.BREAK ? 1 : 0);
        put(r, "wave_num", v.wave);
        put(r, "next_wave_in", Math.max(0, v.secondsToNext));
        List<EntityLiving> foes = Waves.enemies();
        put(r, "enemies", v.active() ? v.enemies() : foes.size());
        EntityLiving near = null;
        int close = 0, visible = 0;
        for (EntityLiving m : foes) {
            if (near == null || a.getDistanceToEntity(m) < a.getDistanceToEntity(near)) near = m;
            if (a.getDistanceToEntity(m) <= 8) close++;
            if (a.getDistanceToEntity(m) <= 32 && a.canEntityBeSeen(m)) visible++;
        }
        put(r, "enemy_dist", near == null ? 32 : a.getDistanceToEntity(near));
        put(r, "enemy_dx", near == null || c.base == null ? 0 : near.posX - c.base[0] - 0.5);
        put(r, "enemy_dz", near == null || c.base == null ? 0 : near.posZ - c.base[2] - 0.5);
        put(r, "enemies_near", close);
        put(r, "enemies_visible", visible);
        // the perimeter
        Defense.Perimeter p = DefenseWorld.ring;
        put(r, "ring_planned", p == null ? 0 : 1);
        if (p != null && !p.cells.isEmpty()) {
            int stand = DefenseWorld.standing(w, p);
            put(r, "wall_frac", stand / (double) p.cells.size());
            put(r, "holes", p.cells.size() - stand);
            put(r, "inside_wall", p.inside(a.posX, a.posZ) ? 1 : 0);
            int free = 0;
            for (String n : p.postNames) { Doer d = c.board.postHolder(n); if (d == null || !d.alive()) free++; }
            put(r, "posts_free", free);
        }
        put(r, "moat_frac", moatFrac(w));
        int torches = 0;
        for (Defense.Tally x : Defense.all().values()) torches += x.torches;
        put(r, "torches_placed", torches);
        String post = c.board.postOf(a);
        put(r, "has_post", post == null ? 0 : 1);
        int[] pa = post == null ? null : DefenseWorld.postAt(post);
        put(r, "post_dist", pa == null ? 0 : Math.hypot(a.posX - pa[0] - 0.5, a.posZ - pa[2] - 0.5));
        put(r, "light_spots", bag.containsKey("torch") ? lightSpots(c, a, w) : 0);
        put(r, "can_equip", canEquip(a) ? 1 : 0);
        put(r, "place_spot", MindSkills.beside(c, a, w) != null ? 1 : 0);
        // contract 3's: the mine, the gear, the farm, the primitives' targets (their skills tell their own facts)
        MineSkills.facts(r, c, a, w);
        GearSkills.facts(r, c, a, w);
        FarmSkills.facts(r, c, a, w);
        PrimSkills.facts(r, c, a, w);
        // the others
        int alive = 0, low = 0;
        double sum = 0, min = 20, nd = 32;
        for (Agent g : c.agents) {
            if (g == a || g.isDead) continue;
            alive++;
            sum += g.getHealth();
            min = Math.min(min, g.getHealth());
            if (g.getHealth() < 10) low++;
            nd = Math.min(nd, a.getDistanceToEntity(g));
        }
        put(r, "allies_alive", alive);
        put(r, "allies_health_mean", alive == 0 ? 20 : sum / alive);
        put(r, "allies_health_min", alive == 0 ? 20 : min);
        put(r, "allies_low", low);
        put(r, "ally_dist", nd);
        // the achievements
        for (String x : RlAchievements.supported()) if (RlAch.has(a.who(), x)) put(r, "ach_" + x, 1);
        put(r, "ach_count", RlAch.count(a.who()));
        put(r, "ach_available", RlAch.available(a.who()));
        // the last step
        put(r, "last_" + mm.outcome, 1);
        put(r, "repeated", mm.repeated);
        put(r, "last_seconds", mm.seconds);
        if (mm.last >= 0) put(r, "last_action_" + RlSpec.ACTIONS.get(mm.last).id, 1);
        put(r, "time_frac", timeFrac);
        put(r, "goal_progress", progress);
        put(r, "novel_count", mm.novel);
        put(r, "trip_ores", mm.trip.ores);
        for (int i = 0; i < RlSpec.ACTIONS.size(); i++) {   // (3.1) the memory of failures
            int run = mm.fails.run(i);
            if (run > 0) put(r, "fail_run_" + RlSpec.ACTIONS.get(i).id, run);
            double rec = mm.fails.recent(i);
            if (rec > 0) put(r, "fail_recent_" + RlSpec.ACTIONS.get(i).id, rec);
        }
        if (!mm.fails.group.isEmpty() && mm.fails.lastFailed >= 0) put(r, "lastfail_" + mm.fails.group, 1);
        // the episode's own measures, when the environment runs
        RlEnv e = running();
        if (e != null) {
            if (!e.target.isEmpty()) put(r, "ach_target_" + e.target, 1);
            put(r, "stall", e.stall.idle(id(a), e.ticks / 20.0));
            put(r, "mobs_inside", e.perimeter.inside.size());
            put(r, "breaches", e.perimeter.waveBreaches);
            put(r, "mob_seconds", e.perimeter.mobSeconds);
            put(r, "clean_waves", e.perimeter.clean);
            double[] win = e.sustain.recent(e.ticks / 20.0, RlSpec.WINDOW_S);
            put(r, "food_renew", win[0]);
            put(r, "food_eaten", win[1]);
            put(r, "wood_renew", win[2]);
            put(r, "wood_used", win[3]);
            put(r, "sources", e.sources);
            put(r, "stock_value", e.stockValue);
        }
        // the crafts: what the bag can make now, and whether in the hands
        for (String[][] list : new String[][][] { RlSpec.CRAFTS, RlSpec.CRAFTS2 })
            for (String[] k : list) {
                Object[] it = MindSkills.item(k[0]);
                if (it == null) continue;
                List<Skills.Laid> rs = Skills.recipesFor((Item) it[0], (Integer) it[1]);
                Skills.Laid l = rs.isEmpty() ? null : MindSkills.doable(a, rs);
                put(r, "craftable_" + k[0], l == null ? 0 : 1);
                put(r, "small_" + k[0], l != null && l.small() ? 1 : 0);
            }
        return r;
    }

    static void put(Map<String, Double> r, String k, double v) { r.put(k, v); }

    /** the bag's key of a stack (RlSpec.BAG), or null */
    static String bagKey(ItemStack s) {
        String id = MindSkills.id(s), b = id.indexOf(':') < 0 ? id : id.substring(0, id.indexOf(':'));
        if (b.equals("log2")) return "log";
        if (b.equals("grass")) return "dirt";
        if (b.equals("reeds")) return "sugar_cane";
        if (RlSkills.food(s)) return RlSkills.raw(s) ? "food_raw" : "food_cooked";
        for (String k : RlSpec.BAG) if (k.equals(b)) return k;
        return null;
    }

    static void seen(Map<String, Double> r, Crew c, Agent a, WorldServer w, String name, Block[] kinds, double within, double none) {
        int n = 0;
        double best = none;
        for (Block b : kinds)
            for (int[] p : Exploration.knownBlocks(w, b)) {
                if (MindSkills.hdist(a, p) > within) continue;
                n++;
                best = Math.min(best, Crew.dist(a, p[0] + 0.5, p[1], p[2] + 0.5));
            }
        put(r, name + "_seen", n);
        put(r, name + "_dist", best);
    }

    /** blocks of the kind seen that mine could pick (as MindSkills.mine looks: within 32, 12 up or down, still there) */
    static int minable(Crew c, Agent a, WorldServer w, Block b) {
        List<int[]> near = new ArrayList<int[]>();
        for (int[] p : Exploration.knownBlocks(w, b))
            if (MindSkills.hdist(a, p) <= 32 && Math.abs(p[1] - a.posY) <= 12 && w.getBlock(p[0], p[1], p[2]) == b && !c.board.reserved(a, p[0], p[1], p[2])) near.add(p);
        if (near.isEmpty() || b == Blocks.stone) return near.size();
        MindSkills.nearestFirst(near, a);   // as mine picks: where it may dig and a way back is left (the first few looked at)
        int n = 0, checks = 0, paths = 0;
        boolean reach = false;
        for (int[] p : near) {
            if (checks++ > 12) break;
            if (Reacts.standsOn(a, w, p[0], p[1], p[2]) || !Ground.mayDig(c, p[0], p[1], p[2], "gather") || !Ground.digLeavesWay(c, w, p[0], p[1], p[2], "gather")) continue;
            int[] spot = MindSkills.mineSpot(w, p);   // and, as mine now asks, a place beside it a walk gets to (the first few searched)
            if (spot == null) continue;
            if (!reach && paths++ < 3) reach = MindSkills.reached(a, w, spot);
            n++;
        }
        return reach ? n : 0;
    }

    static void station(Map<String, Double> r, Crew c, Agent a, WorldServer w, String name, Roles.Role role, double within) {
        int[] s = MindSkills.station(c, a, w, role, 64);
        double d = s == null ? 64 : Crew.dist(a, s[0] + 0.5, s[1], s[2] + 0.5);
        put(r, name + "_known", s != null && d <= 64 ? 1 : 0);
        put(r, name + "_dist", s == null ? within : d);
    }

    /** dark ground round the base light_area would put a torch on */
    static int lightSpots(Crew c, Agent a, WorldServer w) {
        int[] o = DefenseWorld.centre(c, w);
        if (o == null) return 0;
        List<int[]> spots = new ArrayList<int[]>();
        for (int[] s : Defense.lights(DefenseWorld.ring, o[0], o[2], 12, 5, DefenseWorld.floor(w, o[1])))
            if (Crew.soft(w, s[0], s[1], s[2]) && Crew.solid(w, s[0], s[1] - 1, s[2]) && w.getSavedLightValue(EnumSkyBlock.Block, s[0], s[1], s[2]) < 8 && !c.board.reserved(a, s[0], s[1], s[2])) spots.add(s);
        // (mask only) and light_area's work would start: one of the nearest it tries has a place it gets to and sees it from
        final Agent me = a;
        java.util.Collections.sort(spots, (p, q) -> Double.compare(Crew.dist(me, p[0] + 0.5, p[1], p[2] + 0.5), Crew.dist(me, q[0] + 0.5, q[1], q[2] + 0.5)));
        Item torch = Item.getItemFromBlock(Blocks.torch);
        for (int i = 0; i < spots.size() && i < 6; i++) {
            int[] s = spots.get(i);
            if (new Skills.PlaceFrom(s[0], s[1], s[2], torch, -1).plan(a, w) != null) return spots.size();
        }
        return 0;
    }

    /** equip would change something: a better weapon than the one held, or armour better than what is worn */
    static boolean canEquip(Agent a) {
        ItemStack wp = a.bestWeapon();
        if (wp != null && a.getHeldItem() != wp && Agent.damageOf(wp) > Agent.damageOf(a.getHeldItem())) return true;
        for (ItemStack s : a.bag) {
            if (!(s.getItem() instanceof ItemArmor)) continue;
            int slot = 4 - ((ItemArmor) s.getItem()).armorType;
            ItemStack on = a.getEquipmentInSlot(slot);
            int was = on != null && on.getItem() instanceof ItemArmor ? ((ItemArmor) on.getItem()).damageReduceAmount : 0;
            if (((ItemArmor) s.getItem()).damageReduceAmount > was) return true;
        }
        return false;
    }
}
