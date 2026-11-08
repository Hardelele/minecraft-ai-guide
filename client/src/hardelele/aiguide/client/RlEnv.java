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
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemPickaxe;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

/**
 * The learning environment (docs/rl/CONTRACT.md) on a dedicated server started with -Daiguide.rl=PORT: a TCP line
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
        while (state != State.RUN && s.isServerRunning()) {
            Req r = inbox.poll(state == State.IDLE ? 200 : 1000, TimeUnit.MILLISECONDS);
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
        o.addProperty("contract", RlSpec.CONTRACT);
        JsonObject a = new JsonObject();
        a.addProperty("version", RlSpec.ACTIONS_VERSION);
        a.addProperty("hash", RlSpec.hash());
        a.addProperty("n", RlSpec.ACTIONS.size());
        o.add("actions", a);
        JsonObject f = new JsonObject();
        f.addProperty("version", RlSpec.OBS_VERSION);
        f.addProperty("hash", RlSpec.obsHash());
        f.addProperty("dim", RlSpec.FEATURES.size());
        o.add("obs", f);
        JsonObject rw = new JsonObject();
        rw.addProperty("version", RlSpec.REWARD_VERSION);
        o.add("reward", rw);
        JsonArray sc = new JsonArray();
        for (String n : RlSpec.available()) sc.add(new JsonPrimitive(n));
        o.add("scenarios", sc);
        o.addProperty("fast", fast);
        o.addProperty("episodes", episode);
        o.addProperty("model_calls", modelCalls);
        if (arena != null) o.addProperty("arena", arena.x + " " + arena.y + " " + arena.z + " (" + arena.picked + ")");
        return o;
    }

    // ---- an episode -------------------------------------------------------------------------------------------

    RlArena arena;
    RlSpec.Scenario sc;
    RlReward reward;
    long seed;
    int crewSize, episode;
    boolean raw, done;
    String doneReason = "";
    long ticks, resetMs, startNs;
    final Set<Agent> waiting = new LinkedHashSet<Agent>();     // at a decision point this tick
    final Set<Integer> asked = new HashSet<Integer>();          // sent to the policy, its answer awaited
    final Map<Integer, Integer> chosen = new HashMap<Integer, Integer>();   // the policy's action for an agent, by its number
    final Map<Integer, int[]> masks = new HashMap<Integer, int[]>();
    final Map<Integer, Mine> mine = new HashMap<Integer, Mine>();
    final Set<String> itemsSeen = new HashSet<String>();
    int moatDug, labelsAt;
    long labelsOffset;

    /** what the environment keeps of one agent through an episode */
    static final class Mine {
        String outcome = "none";
        int last = -1, repeated;
        double seconds;
        int[] tierMax = new int[3];
        Defense.Tally tally = new Defense.Tally();
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
        long t0 = System.nanoTime();
        sc = s;
        seed = msg.has("seed") ? msg.get("seed").getAsLong() : episode;
        crewSize = Math.max(1, Math.min(RlSpec.MAX_AGENTS, msg.has("crew") ? msg.get("crew").getAsInt() : 1));
        raw = msg.has("raw") && msg.get("raw").getAsBoolean();
        w.disableLevelSaving = true;   // the region files stay as generated: the arena is put back from memory
        if (Waves.cycle.active()) Waves.stop("rl_reset");
        clearCrew();
        if (arena == null) {
            arena = RlArena.make(w);
            Events.log("rl: the arena at " + arena.x + " " + arena.y + " " + arena.z + ", " + arena.chunks.size() + " chunks, " + arena.animals.size() + " animals (" + arena.picked + "), copied in " + arena.snapMs + " ms");
        }
        arena.restore(w);
        Exploration.clear(w);
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
        Mind.Goal g = new Mind.Goal("rl " + s.name + (s.defense ? ": защищай базу" : ""), null, 0);
        for (Agent a : c.agents) g.members.add(a.who());
        Mind.Limits lim = new Mind.Limits();
        lim.steps = lim.seconds = lim.tokens = lim.badCalls = lim.sameFailure = Integer.MAX_VALUE;
        for (Agent a : c.agents) AgentMind.minds.put(a.skin(), new AgentMind(a.skin(), new Mind(a.who(), g, lim, System.currentTimeMillis()), w));
        reward = new RlReward(weights);
        done = false;
        doneReason = "";
        ticks = 0;
        waiting.clear();
        asked.clear();
        chosen.clear();
        masks.clear();
        mine.clear();
        itemsSeen.clear();
        for (Agent a : c.agents) {
            Mine m = mine(a);
            m.tierMax = tiers(a);
            for (ItemStack it : a.bag) itemsSeen.add(kind(it));
        }
        moatDug = 0;
        labels(w, true);
        if (s.waves != null) {
            listenWaves();
            Events.log("rl: waves " + s.waves + ": " + Waves.start(s.waves, "rl", seed));
        }
        episode++;
        resetMs = (System.nanoTime() - t0) / 1000000;
        startNs = System.nanoTime();
        simNs = 0;
        Events.log("rl: episode " + episode + " " + s.name + ", seed " + seed + ", crew " + crewSize + ", reset in " + resetMs + " ms (arena " + arena.restoreMs + " ms)");
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
        for (String[] k : RlSpec.kit(sc.kit)) {
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

    boolean listening;

    void listenWaves() {
        if (listening) return;
        listening = true;
        Waves.listen(new Waves.Listener() {
            public void wave(String what, WaveCycle.View v) {
                if (state == State.IDLE || done || sc == null || sc.waves == null || Crew.instance == null) return;
                WorldServer w = Waves.world();
                if (what.equals("end")) for (Agent a : Crew.instance.agents) reward.add(id(a), "wave_cleared", 1);
                else if (what.equals("start") && v.phase == WaveCycle.Phase.WAVE && w != null) {
                    int holes = 0;
                    for (Defense.Perimeter p : DefenseWorld.walls()) { int st = DefenseWorld.standing(w, p); if (st > 0) holes += p.cells.size() - st; }
                    if (holes > 0) for (Agent a : Crew.instance.agents) reward.add(id(a), "holes_at_wave", holes);
                }
            }
        });
    }

    void episodeTick(WorldServer w) {
        ticks++;
        Crew c = Crew.instance;
        if (c == null) return;
        for (Agent a : c.agents) if (!a.isDead) reward.add(id(a), "time", 1 / 20.0);
        if (ticks % 20 == 0) progress(c, w);
        if (ticks % 100 == 0) labels(w, false);
        double g = goal(c, w);
        if (g >= 1) { finish("goal"); for (Agent a : c.agents) reward.add(id(a), "goal", 1); }
        else if (sc.waves != null && Waves.cycle.phase == WaveCycle.Phase.OVER) finish("lost");
        else if (ticks >= sc.seconds * 20L) finish("time");
    }

    void finish(String why) {
        if (done) return;
        done = true;
        doneReason = why;
        if (Waves.cycle.active()) Waves.stop("rl_" + why);
    }

    /** once a second: what the tallies, the bags and the ground say happened (kills, damage, building, tiers, new things) */
    void progress(Crew c, WorldServer w) {
        Set<String> now = new HashSet<String>();
        for (Agent a : c.agents) {
            if (a.isDead) continue;
            Mine m = mine(a);
            Defense.Tally t = Defense.of(a.who()), d = t.since(m.tally);
            m.tally = t.copy();
            String k = id(a);
            reward.add(k, "kill", d.kills);
            reward.add(k, "damage", d.taken);
            reward.add(k, "built.wall", d.wallBuilt + d.wallRepaired);
            reward.add(k, "built.torch", d.torches);
            reward.add(k, "ate", d.eaten);
            int[] tr = tiers(a);
            for (int i = 0; i < 3; i++) if (tr[i] > m.tierMax[i]) { reward.add(k, "tier_up", tr[i] - m.tierMax[i]); m.tierMax[i] = tr[i]; }
            for (ItemStack it : a.bag) {
                String kd = kind(it);
                if (itemsSeen.add(kd)) reward.add(k, "new_item", 1);
                now.add(kd);
            }
        }
        if (DefenseWorld.moat != null) {
            int dug = 0;
            for (int[] q : DefenseWorld.moat) if (DefenseWorld.open(w, q[0], q[1], q[2])) dug++;
            if (dug > moatDug) {
                List<Agent> by = new ArrayList<Agent>();
                for (Agent a : c.agents) { AgentMind mm = AgentMind.minds.get(a.skin()); if (mm != null && mm.call != null && "dig_moat".equals(mm.call.tool)) by.add(a); }
                if (by.isEmpty()) by.addAll(c.agents);
                for (Agent a : by) reward.add(id(a), "built.moat", (dug - moatDug) / (double) by.size());
            }
            moatDug = Math.max(moatDug, dug);
        }
    }

    /** the agent's best tiers: pickaxe, weapon (sword, bow), armour (in the bag and worn) */
    static int[] tiers(Agent a) {
        int[] t = new int[3];
        List<ItemStack> all = new ArrayList<ItemStack>(a.bag);
        for (int i = 0; i <= 4; i++) if (a.getEquipmentInSlot(i) != null) all.add(a.getEquipmentInSlot(i));
        for (ItemStack s : all) {
            int k = EpochScore.tier(MindSkills.id(s));
            if (s.getItem() instanceof ItemPickaxe) t[0] = Math.max(t[0], k);
            else if (s.getItem() instanceof ItemSword || s.getItem() instanceof ItemBow) t[1] = Math.max(t[1], k);
            else if (s.getItem() instanceof ItemArmor) t[2] = Math.max(t[2], k);
        }
        return t;
    }

    /** an item's kind for "something new": its id without the variant */
    static String kind(ItemStack s) {
        String id = MindSkills.id(s);
        int c = id.indexOf(':');
        return c < 0 ? id : id.substring(0, c);
    }

    /** the scenario's goal: how far (0..1) */
    double goal(Crew c, WorldServer w) {
        if (sc == null) return 0;
        int need = Math.max(1, sc.perAgent * crewSize);
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
                for (Agent a : Crew.instance.agents) if (a.who().equals(who)) reward.add(id(a), "human", rating);
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
        Integer k = e.chosen.remove(a.skin());
        if (k != null) {
            Mine mm = e.mine(a);
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
        String k = id(a);
        e.reward.add(k, "OK".equals(result) ? "skill_ok" : "skill_fail", 1);
        if (before != null && after != null && (tool.equals("chop_tree") || tool.equals("mine") || tool.equals("hunt")))
            for (Map.Entry<String, Integer> x : after.entrySet()) {
                int gain = x.getValue() - (before.containsKey(x.getKey()) ? before.get(x.getKey()) : 0);
                if (gain <= 0) continue;
                String key = gatherKey(x.getKey());
                if (key != null) e.reward.add(k, key, gain);
            }
    }

    static String gatherKey(String id) {
        String b = id.indexOf(':') < 0 ? id : id.substring(0, id.indexOf(':'));
        if (b.equals("log") || b.equals("log2")) return "gathered.log";
        if (b.equals("cobblestone")) return "gathered.cobblestone";
        if (b.equals("coal")) return "gathered.coal";
        if (b.equals("iron_ore")) return "gathered.iron_ore";
        Object[] it = MindSkills.item(id);
        if (it != null && RlSkills.food(new ItemStack((Item) it[0], 1, Math.max(0, (Integer) it[1])))) return "gathered.food";
        return null;
    }

    /** an agent died (Agent.onDeath): in a fight, or by an accident; outside the wall in a wave besides */
    static void died(Agent a, String cause) {
        RlEnv e = instance;
        if (e == null || e.reward == null || e.state == State.IDLE || e.done) return;
        String k = id(a);
        e.reward.add(k, "accident".equals(cause) || "starve".equals(cause) ? "accident" : "death", 1);
        Defense.Perimeter p = DefenseWorld.ring;
        if (Waves.state().phase == WaveCycle.Phase.WAVE && p != null && !p.inside(a.posX, a.posZ)) e.reward.add(k, "out_died", 1);
    }

    // ---- the answer: the observations, the masks, the rewards ---------------------------------------------------

    void answer(WorldServer w) {
        Crew c = Crew.instance;
        JsonObject o = new JsonObject(), obs = new JsonObject(), mask = new JsonObject(), rew = new JsonObject(), comps = new JsonObject(), rawOut = new JsonObject();
        List<Agent> who = new ArrayList<Agent>();
        if (done && c != null) { for (Agent a : c.agents) if (!a.isDead) who.add(a); }
        else who.addAll(waiting);
        Set<String> paid = new HashSet<String>();
        for (Agent a : who) {
            Map<String, Double> r = facts(c, a, w);
            int[] mk = RlSpec.mask(r);
            masks.put(a.skin(), mk);
            obs.add(id(a), floats(RlSpec.vector(r)));
            mask.add(id(a), ints(mk));
            if (raw) rawOut.add(id(a), map(r));
            JsonObject cp = new JsonObject();
            rew.addProperty(id(a), RlReward.round(reward.take(id(a), cp)));
            comps.add(id(a), cp);
            paid.add(id(a));
            if (!done) asked.add(a.skin());
        }
        if (done)   // the dead at the end (between death and coming back) and whatever is left: paid too
            for (String k : new ArrayList<String>(reward.open.keySet())) {
                if (paid.contains(k)) continue;
                JsonObject cp = new JsonObject();
                rew.addProperty(k, RlReward.round(reward.take(k, cp)));
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
        long wall = (System.nanoTime() - startNs) / 1000000;
        double sim = Math.max(1e-6, simNs / 1e9);
        info.addProperty("wall_ms", wall);
        info.addProperty("sim_ms", Math.round(sim * 1000));
        info.addProperty("game_s_per_s", Math.round(ticks / 20.0 / sim * 10) / 10.0);
        info.addProperty("reset_ms", resetMs);
        if (done) {
            info.addProperty("done_reason", doneReason);
            info.add("episode_reward", reward.episodeJson());
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
            if (k < 0 || k >= RlSpec.ACTIONS.size()) { r.conn.send(error("action " + k + " out of 0.." + (RlSpec.ACTIONS.size() - 1))); return; }
            got.put(who, k);
        }
        boolean noop = false;
        for (Map.Entry<Integer, Integer> e : got.entrySet())
            if (e.getValue() == 0) {   // nothing: the episode ends at once with its penalty (the world back at the next reset)
                reward.add(String.valueOf(e.getKey()), "noop", 1);
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
        return facts(c, a, w, sc.name, mine(a), ticks / (20.0 * sc.seconds), goal(c, w));
    }

    /**
     * The raw facts of an agent: the environment's (above) and the game's policy (PolicyMind: a mind's goal as a
     * scenario, its own memo of the last step, its time and progress) from the same code.
     */
    static Map<String, Double> facts(Crew c, Agent a, WorldServer w, String scenario, Mine mm, double timeFrac, double progress) {
        Map<String, Double> r = new LinkedHashMap<String, Double>();
        put(r, "scen_" + scenario.replace(':', '_'), 1);
        int slot = 0;
        for (Agent g : c.agents) { if (g == a) break; slot++; }
        put(r, "agent_" + Math.min(slot, RlSpec.MAX_AGENTS - 1), 1);
        put(r, "n_agents", c.agents.size());
        put(r, "health", a.getHealth());
        put(r, "max_health", a.getMaxHealth());
        put(r, "low_health", Defense.LOW);
        put(r, "armor", a.getTotalArmorValue());
        put(r, "held_tier", a.getHeldItem() == null ? 0 : EpochScore.tier(MindSkills.id(a.getHeldItem())));
        int[] tr = tiers(a);
        put(r, "pick_tier", tr[0]);
        put(r, "weapon_tier", tr[1]);
        double baseD = c.base == null ? 32 : Math.hypot(a.posX - c.base[0] - 0.5, a.posZ - c.base[2] - 0.5);
        put(r, "at_home", baseD < 4 ? 1 : 0);
        long t = w.getWorldTime() % 24000;
        put(r, "day_sin", Math.sin(2 * Math.PI * t / 24000));
        put(r, "day_cos", Math.cos(2 * Math.PI * t / 24000));
        put(r, "night", t >= 13000 && t < 23000 ? 1 : 0);
        put(r, "dusk_in", t < 12500 ? (12500 - t) / 20.0 : 0);
        // the bag
        Map<String, Double> bag = new HashMap<String, Double>();
        double total = 0, stack = 0, fuel = 0;
        for (ItemStack s : a.bag) {
            String k = bagKey(s);
            if (k != null) bag.put(k, (bag.containsKey(k) ? bag.get(k) : 0) + s.stackSize);
            total += s.stackSize;
            if (!(s.getItem() instanceof net.minecraft.item.ItemTool) && !(s.getItem() instanceof ItemSword)) stack += s.stackSize;
            if (net.minecraft.tileentity.TileEntityFurnace.getItemBurnTime(s) > 0) fuel += s.stackSize;
        }
        for (String b : RlSpec.BAG) put(r, "bag_" + b, bag.containsKey(b) ? bag.get(b) : 0);
        put(r, "bag_total", total);
        put(r, "bag_stackables", stack);
        put(r, "fuel", fuel);
        Map<String, Integer> ids = MindSkills.bag(a);
        String mat = Defense.material("any", ids);
        put(r, "wall_blocks", mat == null ? 0 : Defense.count(ids, mat));
        // what the crew has seen near
        List<int[]> trees = MindSkills.trunks(c, a, w, 40, true);
        put(r, "trees_seen", trees.size());
        put(r, "tree_dist", trees.isEmpty() ? 48 : Crew.dist(a, trees.get(0)[0] + 0.5, trees.get(0)[1], trees.get(0)[2] + 0.5));
        put(r, "trees_free", MindSkills.trunks(c, a, w, 48, false).size());
        seen(r, c, a, w, "stone", STONE, 32, 32);
        seen(r, c, a, w, "coal", new Block[] { Blocks.coal_ore }, 48, 48);
        seen(r, c, a, w, "iron", new Block[] { Blocks.iron_ore }, 48, 48);
        put(r, "coal_minable", minable(c, a, w, Blocks.coal_ore));
        put(r, "iron_minable", minable(c, a, w, Blocks.iron_ore));
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
        int close = 0;
        for (EntityLiving m : foes) {
            if (near == null || a.getDistanceToEntity(m) < a.getDistanceToEntity(near)) near = m;
            if (a.getDistanceToEntity(m) <= 8) close++;
        }
        put(r, "enemy_dist", near == null ? 32 : a.getDistanceToEntity(near));
        put(r, "enemy_dx", near == null || c.base == null ? 0 : near.posX - c.base[0] - 0.5);
        put(r, "enemy_dz", near == null || c.base == null ? 0 : near.posZ - c.base[2] - 0.5);
        put(r, "enemies_near", close);
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
        // the last step
        put(r, "last_" + mm.outcome, 1);
        put(r, "repeated", mm.repeated);
        put(r, "last_seconds", mm.seconds);
        if (mm.last >= 0) put(r, "last_action_" + RlSpec.ACTIONS.get(mm.last).id, 1);
        put(r, "time_frac", timeFrac);
        put(r, "goal_progress", progress);
        // the crafts: what the bag can make now, and whether in the hands
        for (String[] k : RlSpec.CRAFTS) {
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
        int n = 0;
        for (int[] p : Exploration.knownBlocks(w, b))
            if (MindSkills.hdist(a, p) <= 32 && Math.abs(p[1] - a.posY) <= 12 && w.getBlock(p[0], p[1], p[2]) == b && !c.board.reserved(a, p[0], p[1], p[2])) n++;
        return n;
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
        int n = 0;
        for (int[] s : Defense.lights(DefenseWorld.ring, o[0], o[2], 12, 5, DefenseWorld.floor(w, o[1])))
            if (Crew.soft(w, s[0], s[1], s[2]) && Crew.solid(w, s[0], s[1] - 1, s[2]) && w.getSavedLightValue(EnumSkyBlock.Block, s[0], s[1], s[2]) < 8 && !c.board.reserved(a, s[0], s[1], s[2])) n++;
        return n;
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
