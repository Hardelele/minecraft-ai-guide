package hardelele.aiguide.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.world.WorldServer;

/**
 * An agent's mind in the game: a goal in words (and maybe an item to have), and the decisions toward it. At a fork
 * (the goal came, a step ended or failed, the player's orders are over) the state is put together here on the server
 * thread, the model is asked on a thread of its own (the server never waits), and meanwhile the agent stands and the
 * label over its head says it thinks. The call it gives becomes one card of the board, pinned to the agent (origin
 * LLM_PLAN), done through the doer (Doer.begin: its jobs, its reflexes before them) by a skill of MindSkills; how it
 * ended is the next state's last step. No phases, no fixed order of things: what the model asks for is all that is
 * made. Every decision goes to the events (mind: ...) and to crew-minds.jsonl in the world's folder (state, call,
 * outcome, tokens) for training.
 *
 * The player's own character (number 1) gets no goals of the mind: the player is a person, and his autopilot cannot
 * craft or smelt. The model: the server mod's (MindLink, by reflection, the guide's config and key), or a stub.
 */
final class AgentMind {
    /** the minds by the doer's number (the server thread's) */
    static final Map<Integer, AgentMind> minds = new LinkedHashMap<Integer, AgentMind>();
    /** for the client's labels: number -> { what it does now ("thinking", "orders" or a tool), the goal } */
    static final Map<Integer, String[]> shown = new ConcurrentHashMap<Integer, String[]>();
    static volatile Mind.Model model = AgentMind::server;
    static final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "AIGuide-mind");
        t.setDaemon(true);
        return t;
    });

    final int num;
    final Mind mind;
    final Mind.Goal goal;
    WorldServer world;
    Future<String> thinking;
    String body;
    MindState asked;
    long askedMs, retryAt, reflexAt = -1;
    int[] tokens = new int[3];
    long ms;
    Mind.Call call;
    MindSkills.Skill skill;
    TaskBoard.Card card;
    Map<String, Integer> before;
    Defense.Tally fightBefore;   // the agent's fight when its step began
    long began;
    int entity;
    final List<String> events = new ArrayList<String>();

    AgentMind(int num, Mind mind, WorldServer w) {
        this.num = num;
        this.mind = mind;
        this.goal = mind.goal;
        this.world = w;
    }

    // ---- in Crew's tick ----------------------------------------------------------------------------------------

    /** the agent's mind this tick: true when it has the agent (thinking, between steps: no board work for it then) */
    static boolean tick(Crew c, Agent a, WorldServer w) {
        AgentMind m = minds.get(a.skin());
        if (m == null) return false;
        if (m.world != w) { minds.remove(m.num); shown.remove(m.num); return false; }   // another world: its goals are not this one's
        try {
            return m.step(c, a, w);
        } catch (Exception e) {
            Events.log("mind: " + a.who() + " failed: " + e);
            m.mind.end("GAVE_UP", "error: " + e);
            m.close(c, a);
            return false;
        }
    }

    /** its mind's goal is the defence (the guard's watch leaves it be: its own posts and fights) */
    static boolean defends(Agent a) {
        AgentMind m = minds.get(a.skin());
        return m != null && m.goal.defense && !m.mind.over();
    }

    static boolean anyDefense() {
        for (AgentMind m : minds.values()) if (m.goal.defense && !m.mind.over()) return true;
        return false;
    }

    /** an event of the defence (a wave, a breach, a creeper at the wall, an ally hurt) for every mind of defence; one that calls for a decision ends a wait or a fight */
    static void tell(String e, boolean interrupt) {
        for (AgentMind m : minds.values()) {
            if (!m.goal.defense || m.mind.over()) continue;
            if (m.events.size() < 8) m.events.add(e);
            if (interrupt && (m.skill instanceof MindSkills.Wait || m.skill instanceof DefenseSkills.Fighter)) m.skill.interrupt = e;
        }
    }

    /** a reflex has the body (Crew's tick, before the jobs): an event for the mind; it ends a wait */
    static void reflex(Agent a) {
        AgentMind m = minds.get(a.skin());
        if (m == null || a.slot.of == null || a.slot.at == m.reflexAt) return;
        m.reflexAt = a.slot.at;
        String e = "reflex " + a.slot.of.name() + (a.hurtBy != null && a.ticksExisted - a.hurtAt < 40 ? " (hurt by " + a.hurtBy + ", health " + Math.round(a.getHealth()) + ")" : "");
        if (m.events.size() < 6) m.events.add(e);
        if (m.skill instanceof MindSkills.Wait) m.skill.interrupt = e;
    }

    boolean step(Crew c, Agent a, WorldServer w) {
        long now = w.getTotalWorldTime();
        if (card != null) {
            if (skill.result != null) finishStep(c, a, w, skill.reason, skill.detail);
            else if (a.getEntityId() != entity) finishStep(c, a, w, "DIED", "it died and came back at the spawn with an empty bag");
            else if (card.holder != a || card.state != TaskBoard.State.LEASED)
                finishStep(c, a, w, "INTERRUPTED", "the step was taken off it (" + (a.ordered() ? "the player's order" : card.last == Result.TIMEOUT ? "it took longer than its time" : "the board gave it other work") + ")");
            else return false;   // its step goes on: the card's job
        }
        if (mind.over()) { close(c, a); return false; }
        if (!goal.open()) {   // a shared goal another one ended
            mind.end(goal.state, goal.summary);
            close(c, a);
            return false;
        }
        if (a.ordered()) { show("orders"); return false; }   // the player's orders first: the mind waits for them to be over
        if (!a.jobs.isEmpty()) return false;   // something else first (a summon's walk, the way back for its things)
        if (thinking == null) {
            if (now < retryAt) { a.body.stop(); return true; }
            if (recovering(c, a)) return false;   // back for what it dropped when it died first (the board's card), then the next step
            if (RlEnv.on()) {   // the learning environment decides: no model, the policy's action (it stands meanwhile)
                Mind.Call cl = RlEnv.decide(this, a, w);
                if (cl == null) { a.body.stop(); show("thinking"); return true; }
                return act(c, a, w, cl);
            }
            String lim = mind.limit(System.currentTimeMillis());
            if (lim != null) {
                mind.end("GAVE_UP", lim);
                close(c, a);
                return false;
            }
            asked = state(c, a, w);
            events.clear();
            body = mind.request(asked, System.currentTimeMillis());
            askedMs = System.currentTimeMillis();
            final String b = body;
            final Mind.Model md = model;
            if (RlEnv.on()) RlEnv.modelCalls++;   // (never: the environment's minds decide above; counted if it ever happens)
            thinking = pool.submit(() -> md.complete(b));
            show("thinking");
            a.body.stop();
            return true;
        }
        if (!thinking.isDone()) {   // it stands and thinks (looks at the player when near)
            a.body.stop();
            EntityPlayerMP p = c.playerIn(w);
            if (p != null && Crew.dist(a, p.posX, p.posY, p.posZ) < 12) Crew.face(a, p.posX, p.posY + 1.5, p.posZ);
            return true;
        }
        String resp;
        try {
            resp = thinking.get();
        } catch (Exception e) {
            thinking = null;
            mind.errors++;
            mind.status = "IDLE";
            retryAt = now + 20 * 5 * mind.errors;
            Throwable t = e.getCause() != null ? e.getCause() : e;
            Events.log("mind: " + a.who() + " the model did not answer (" + mind.errors + "): " + t);
            return true;
        }
        thinking = null;
        mind.errors = 0;
        ms = System.currentTimeMillis() - askedMs;
        int p0 = mind.promptTokens, c0 = mind.completionTokens, k0 = mind.cachedTokens;
        Mind.Call cl = mind.parse(resp, body.length());
        tokens = new int[] { mind.promptTokens - p0, mind.completionTokens - c0, mind.cachedTokens - k0 };
        String bad = mind.check(cl, asked);
        Events.log("mind: " + a.who() + " think -> " + (cl.tool.isEmpty() ? "?" : cl.brief()) + " (why: " + cut(cl.why, 160) + ")" + " [" + tokens[0] + "+" + tokens[1] + " tokens, " + ms + " ms]");
        if (bad != null) { record(c, a, w, cl, "BAD_CALL", bad, "", 0); return true; }
        return act(c, a, w, cl);
    }

    /**
     * It died and came back (a minute ago at most) and the card to go back for its things is open for it: the board
     * gives that first (Crew.respawn's card; the bag fell where it died and is gone in 5 minutes). Before this the mind
     * held the agent at every decision, so the card was never taken and the weapon was lost with the bag.
     */
    static boolean recovering(Crew c, Agent a) {
        if (a.ticksExisted > 20 * 60) return false;
        for (TaskBoard.Card k : c.board.cards)
            if (k.open() && "recover".equals(k.kind) && k.only != null && TaskBoard.same(k.only, a)) return true;
        return false;
    }

    /** the call done: at once (say, how_to_make, done, give_up, a skill that cannot begin) or as a card of the board */
    boolean act(Crew c, Agent a, WorldServer w, Mind.Call cl) {
        switch (cl.tool) {
            case "done":
                mind.end("DONE", cl.arg("summary"));
                record(c, a, w, cl, "OK", "the goal is reached", "", 0);
                close(c, a);
                return false;
            case "give_up":
                mind.end("GAVE_UP", cl.arg("reason"));
                record(c, a, w, cl, "OK", "given up", "", 0);
                close(c, a);
                return false;
            case "say":
                Crew.note("mind.says", a.who(), cl.arg("text"));
                Events.log("crew: " + a.who() + " says: " + cl.arg("text"));
                record(c, a, w, cl, "OK", "said", "", 0);
                return true;
            case "how_to_make":
                record(c, a, w, cl, "OK", MindSkills.howToMake(cl.arg("item")), "", 0);
                return true;
            case "masked":   // the policy chose an action its mask did not allow: a failed step, nothing done
                record(c, a, w, cl, "MASKED", "the action was not allowed now (its mask was 0)", "", 0);
                return true;
            default:
        }
        final MindSkills.Skill s = MindSkills.make(c, a, w, cl);
        if (s.result != null) { record(c, a, w, cl, s.reason, s.detail, "", 0); return true; }
        skill = s;
        call = cl;
        before = MindSkills.bag(a);
        fightBefore = Defense.of(a.who()).copy();
        began = w.getTotalWorldTime();
        entity = a.getEntityId();
        Origin was = Crew.originNow;
        Crew.originNow = Origin.LLM_PLAN;
        TaskBoard.Card k = c.board.post("mind " + cl.tool, () -> s).pin(a).prio(8).lease(s.lease);
        Crew.originNow = was;
        k.phase = null;   // its own, not a phase's
        card = k;
        c.board.assign(k, a, w, "its mind: " + cl.tool);
        show(cl.tool);
        return false;   // the card's job takes its first step this tick
    }

    /** a step ended: its card closed (the mind decides what comes next, the board does not try it again), the outcome kept */
    void finishStep(Crew c, Agent a, WorldServer w, String result, String detail) {
        TaskBoard.Card k = card;
        card = null;
        if (k.state != TaskBoard.State.DONE) {
            if (k.holder != null) c.board.release(k.holder, k);
            k.state = TaskBoard.State.FAILED;
            if (k.last == null) k.last = Result.GONE;
        }
        if (skill != null && skill.result == null) skill.result = Result.GONE;   // a card job left over (if any) ends at once
        if (call.tool.equals("look_around") && "OK".equals(result)) detail = seen(c, a, w);
        String changes = MindSkills.diff(before, MindSkills.bag(a));
        if (DefenseSkills.isDefense(call.tool))   // an episode of the fight: the ratings' fights tab reads this line (Episodes.FIGHT)
            Events.log("defense: " + a.who() + " " + call.tool + " -> " + (result == null ? "OK" : result) + " (" + Defense.of(a.who()).since(fightBefore).line()
                    + (detail == null || detail.isEmpty() ? "" : "; " + cut(detail, 160)) + ")");
        record(c, a, w, call, result == null ? "OK" : result, detail, changes, (w.getTotalWorldTime() - began) / 20.0);
        skill = null;
        call = null;
    }

    void record(Crew c, Agent a, WorldServer w, Mind.Call cl, String result, String detail, String changes, double sec) {
        Mind.Step s = mind.record(cl, result, detail, changes, sec);
        if (!cl.tool.equals("how_to_make") || !result.equals("OK"))
            Events.log("mind: " + a.who() + " " + cl.tool + " -> " + result + (s.detail.isEmpty() ? "" : " (" + cut(s.detail, 240) + ")") + (changes.isEmpty() ? "" : "; " + changes));
        if (RlEnv.on()) {   // the environment's step: its reward and the next observation's outcome; no journal line (no model, no prompt)
            RlEnv.ended(this, a, cl.tool, result, skill != null ? before : null, MindSkills.bag(a), sec);
            fightBefore = null;
            tokens = new int[3];
            return;
        }
        JsonObject line = mind.logLine(w.getTotalWorldTime(), asked, cl, s, tokens, ms);
        Defense.Tally t = Defense.peek(a.who());
        if (goal.defense || t != null && !t.empty()) {   // the fight: the agent's tally so far and what this step came to
            JsonObject f = new JsonObject();
            f.add("total", (t == null ? new Defense.Tally() : t).json());
            if (fightBefore != null && t != null) f.add("step", t.since(fightBefore).json());
            line.add("combat", f);
        }
        Epochs.step(a, w, cl.tool, result, s.detail, asked == null ? -1 : asked.health, fightBefore != null && t != null ? t.since(fightBefore) : null);   // an episode of the epoch, when one is on
        fightBefore = null;
        write(w, line);
        tokens = new int[3];
    }

    /** the goal is over for this agent: the mind goes, what it cost is told */
    void close(Crew c, Agent a) {
        if (card != null) {
            if (card.holder != null) c.board.release(card.holder, card);
            card.state = TaskBoard.State.FAILED;
            if (card.last == null) card.last = Result.GONE;
            a.cancel("its goal is over");
            card = null;
        }
        if (thinking != null) thinking.cancel(true);
        if (minds.get(num) != this) return;   // closed already
        minds.remove(num);
        c.board.leavePost(a);   // its post is free for the others
        if (mind.status.equals("DONE")) Crew.note("mind.done", a.who(), mind.endReason);
        else if (!mind.endReason.equals("a new goal") && !mind.endReason.equals("stopped by the player")) Crew.note("mind.gaveup", a.who(), mind.endReason);
        shown.remove(num);
        Events.log("mind: " + a.who() + " goal " + mind.status + ": " + cut(mind.endReason, 200) + " (" + mind.steps.size() + " steps, " + mind.requests + " requests, "
                + mind.promptTokens + "+" + mind.completionTokens + " tokens, " + mind.cachedTokens + " cached, " + (System.currentTimeMillis() - mind.startedMs) / 1000 + " s)");
    }

    void show(String what) {
        shown.put(num, new String[] { what, goal.text });
    }

    static String cut(String s, int n) {
        return s == null ? "" : s.length() > n ? s.substring(0, n) + "..." : s;
    }

    static void write(WorldServer w, JsonObject line) {
        try {
            java.io.File f = new java.io.File(w.getSaveHandler().getWorldDirectory(), "crew-minds.jsonl");
            java.nio.file.Files.write(f.toPath(), (line.toString() + "\n").getBytes("UTF-8"), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            Events.log("mind: not written: " + e);
        }
    }

    // ---- the state the model sees ------------------------------------------------------------------------------

    static final Block[] ORES = { Blocks.coal_ore, Blocks.iron_ore, Blocks.gold_ore, Blocks.redstone_ore, Blocks.lapis_ore, Blocks.diamond_ore, Blocks.emerald_ore };
    static final Block[] LOOSE = { Blocks.sand, Blocks.gravel, Blocks.clay };

    MindState state(Crew c, Agent a, WorldServer w) {
        MindState st = new MindState();
        st.agent = a.who();
        st.goal = goal.text;
        if (goal.item != null) {
            st.want = goal.want();
            st.have = have(c, w);
        }
        if (goal.shared()) {
            StringBuilder t = new StringBuilder();
            for (String m : goal.members) if (!m.equals(a.who())) t.append(t.length() > 0 ? ", " : "").append(m);
            st.team = t.toString();
        }
        st.x = (int) Math.floor(a.posX);
        st.y = (int) Math.floor(a.boundingBox.minY);
        st.z = (int) Math.floor(a.posZ);
        st.on = MindSkills.idOf(w.getBlock(st.x, st.y - 1, st.z));
        st.held = a.getHeldItem() == null ? "" : MindSkills.id(a.getHeldItem());
        st.health = a.getHealth();
        st.maxHealth = a.getMaxHealth();
        st.time = time(w);
        st.bag.putAll(MindSkills.bag(a));
        // what the crew has seen near
        List<int[]> trees = MindSkills.trunks(c, a, w, 40, true);
        int shownTrees = 0;
        for (int[] t : trees) {
            if (shownTrees++ >= 5) break;
            Agent by = holder(c, a, t);
            st.near.add("tree (" + MindSkills.idOf(w.getBlock(t[0], t[1], t[2]) == Blocks.log2 ? Blocks.log2 : Blocks.log) + ") at " + t[0] + " " + t[1] + " " + t[2] + ", " + dist(a, t) + " m"
                    + (by != null ? " (reserved by " + by.who() + ")" : ""));
        }
        if (trees.size() > 5) st.near.add(trees.size() + " trees seen within 40 blocks in all");
        seenKind(st, c, a, w, "stone (gives cobblestone)", new Block[] { Blocks.stone, Blocks.cobblestone }, 32, 3);
        for (Block b : ORES) seenKind(st, c, a, w, MindSkills.idOf(b), new Block[] { b }, 48, 1);
        for (Block b : LOOSE) seenKind(st, c, a, w, MindSkills.idOf(b), new Block[] { b }, 32, 1);
        for (Roles.Role r : new Roles.Role[] { Roles.Role.TABLE, Roles.Role.FURNACE, Roles.Role.CHEST }) {
            int[] own = MindSkills.own(c, r);
            for (int[] s : MindSkills.stations(c, a, w, r, 64))
                if (st.stations.size() < 8)
                    st.stations.add((r == Roles.Role.TABLE ? "crafting_table" : r == Roles.Role.FURNACE ? "furnace" : "chest") + " at " + s[0] + " " + s[1] + " " + s[2] + ", " + dist(a, s) + " m"
                            + (own != null && own[0] == s[0] && own[1] == s[1] && own[2] == s[2] ? " (the crew's)" : ""));
        }
        try {   // the places the crew's memory of the world has made out (an observer: may be off)
            for (PlacesSplit.Place p : Places.known()) {
                double d = Math.sqrt(Math.pow(p.center[0] - a.posX, 2) + Math.pow(p.center[1] - a.posY, 2) + Math.pow(p.center[2] - a.posZ, 2));
                if (d <= 48 && st.places.size() < 4 && p.cls > 0 && p.cls < PlacesNet.CHANNELS.length)
                    st.places.add(PlacesNet.CHANNELS[p.cls] + " around " + (int) p.center[0] + " " + (int) p.center[1] + " " + (int) p.center[2] + ", " + Math.round(d) + " m");
            }
        } catch (Throwable ignored) {
            // no places here
        }
        EntityPlayerMP p = c.playerIn(w);
        if (p != null) st.player = p.getCommandSenderName() + " at " + (int) Math.floor(p.posX) + " " + (int) Math.floor(p.boundingBox.minY) + " " + (int) Math.floor(p.posZ) + ", "
                + Math.round(Crew.dist(a, p.posX, p.posY, p.posZ)) + " m";
        for (Doer d : c.doers()) {
            if (d == a || !d.alive()) continue;
            StringBuilder o = new StringBuilder(d.who()).append(" at ").append((int) Math.floor(d.px())).append(' ').append((int) Math.floor(d.py())).append(' ').append((int) Math.floor(d.pz()));
            AgentMind m = minds.get(d.num());
            TaskBoard.Card k = c.board.leased.get(d);
            if (m != null) o.append(", goal \"").append(cut(m.goal.text, 60)).append('"').append(m.call != null ? ", now " + m.call.brief() : m.thinking != null ? ", thinking" : "");
            else if (k != null) o.append(", on ").append(k.kind);
            else o.append(d instanceof PlayerAgent ? " (the player)" : ", idle");
            if (d instanceof Agent && !((Agent) d).bag.isEmpty()) {
                int i = 0;
                o.append(", carries ");
                for (Map.Entry<String, Integer> e : MindSkills.bag((Agent) d).entrySet()) if (i++ < 5) o.append(i > 1 ? ", " : "").append(e.getKey()).append(" x").append(e.getValue());
            }
            st.others.add(o.toString());
        }
        for (Map.Entry<Long, ?> e : c.board.blocks.entrySet()) {
            Object by = e.getValue();
            if (by == a || !(by instanceof Doer) || !((Doer) by).alive() || st.reserved.size() >= 8) continue;
            int[] q = unkey(e.getKey());
            st.reserved.add(q[0] + " " + q[1] + " " + q[2] + " (" + ((Doer) by).who() + ")");
        }
        st.events.addAll(events);
        try {   // the defence's part: the waves, the wall, the posts, the allies (a goal of defence, or a wave on)
            DefenseWorld.fill(st, c, a, w, goal.defense);
        } catch (Exception e) {
            Events.log("mind: the defence's state failed: " + e);
        }
        return st;
    }

    /** the one of the crew that holds this tree (its base) reserved, other than a; or null */
    static Agent holder(Crew c, Agent a, int[] t) {
        Object by = c.board.blocks.get(Crew.key(t[0], t[1], t[2]));
        if (by == null) {   // reserved at the foot under the lowest log seen
            for (int dy = 1; dy <= 8 && by == null; dy++) by = c.board.blocks.get(Crew.key(t[0], t[1] - dy, t[2]));
        }
        return by instanceof Agent && by != a && !((Agent) by).isDead ? (Agent) by : null;
    }

    /** Crew.key back into x y z */
    static int[] unkey(long k) {
        int x = (int) (k >> 38), z = (int) ((k >> 12) & 0x3FFFFFF), y = (int) (k & 0xFFF);
        return new int[] { x, y, (z << 6) >> 6 };
    }

    static long dist(Agent a, int[] p) {
        return Math.round(Crew.dist(a, p[0] + 0.5, p[1], p[2] + 0.5));
    }

    /** a kind of block seen near: how many and the nearest few */
    static void seenKind(MindState st, Crew c, Agent a, WorldServer w, String name, Block[] kinds, double r, int show) {
        List<int[]> l = new ArrayList<int[]>();
        for (Block b : kinds) for (int[] p : Exploration.knownBlocks(w, b)) if (MindSkills.hdist(a, p) <= r) l.add(p);
        if (l.isEmpty()) return;
        MindSkills.nearestFirst(l, a);
        StringBuilder b = new StringBuilder(name).append(": ").append(l.size()).append(" blocks seen, nearest at ");
        for (int i = 0; i < Math.min(show, l.size()); i++) b.append(i > 0 ? "; " : "").append(l.get(i)[0]).append(' ').append(l.get(i)[1]).append(' ').append(l.get(i)[2]).append(", ").append(dist(a, l.get(i))).append(" m");
        st.near.add(b.toString());
    }

    /** what a look round found: the counts the next state shows in full */
    String seen(Crew c, Agent a, WorldServer w) {
        int stone = 0, ores = 0;
        for (Block b : new Block[] { Blocks.stone, Blocks.cobblestone }) for (int[] p : Exploration.knownBlocks(w, b)) if (MindSkills.hdist(a, p) <= 32) stone++;
        for (Block b : ORES) for (int[] p : Exploration.knownBlocks(w, b)) if (MindSkills.hdist(a, p) <= 48) ores++;
        return "seen near now: " + MindSkills.trunks(c, a, w, 40, true).size() + " trees, " + stone + " stone, " + ores + " ore blocks (SEEN NEAR has them)";
    }

    static String time(WorldServer w) {
        long t = w.getWorldTime() % 24000;
        String part = t < 12000 ? "day" : t < 13800 ? "dusk" : t < 22200 ? "night" : "dawn";
        int h = (int) ((t / 1000 + 6) % 24), m = (int) (t % 1000 * 60 / 1000);
        String clock = String.format("%02d:%02d", h, m);
        if (t < 12500) return part + ", " + clock + " (dusk in " + Math.max(1, (12500 - t) / 1200) + " min)";
        if (t >= 22200) return part + ", " + clock;
        return part + ", " + clock + " (day in " + Math.max(1, (24000 - t) / 1200) + " min; mobs are out)";
    }

    /** of the wanted item: the agent's own, or for a shared goal the members' bags and the crew's chest */
    int have(Crew c, WorldServer w) {
        Object[] it = MindSkills.item(goal.item);
        if (it == null) return 0;
        net.minecraft.item.Item item = (net.minecraft.item.Item) it[0];
        int n = 0;
        for (Agent g : c.agents) {
            if (g.isDead) continue;
            boolean member = goal.shared() ? goal.members.contains(g.who()) : g.skin() == num;
            if (member) n += Skills.count(g.bag, item, (Integer) it[1]);
        }
        if (goal.shared() && c.chestInv(w) != null) n += Crew.count(c.chestInv(w), item);
        return n;
    }

    // ---- goals in: the guide's crew_goal, the guide's window ---------------------------------------------------

    /** the guide's crew_goal (any thread): agents ("all" or numbers), the goal's words, the item to have ("stone_pickaxe 1" or empty), agents to call first */
    public static String fromGuide(String player, String agents, String goal, String want, int summon) {
        return set(player, agents, goal, want, summon, false);
    }

    /** the guide's window: /цель [numbers] words (the client's thread); the answer comes to the player as a note */
    static void fromWindow(final String player, final String[] cmd) {
        if (cmd[1].isEmpty()) { Crew.note("mind.usage"); return; }
        new Thread(() -> set(player, cmd[0], cmd[1], "", 0, true), "mind-goal").start();
    }

    static String set(final String player, final String agents, String goal, String want, int summon, final boolean window) {
        final String text = goal == null ? "" : goal.trim();
        if (text.isEmpty()) return "No goal: say what is wanted, in words.";
        final String[] w = Mind.want(want);
        if (want != null && !want.trim().isEmpty() && (w == null || MindSkills.item(w[0]) == null))
            return "Unknown want '" + want + "': ITEM COUNT by the game's id, e.g. stone_pickaxe 1 (or leave it empty).";
        final Crew c = Crew.ensure();
        if (summon > 0 && c.agents.isEmpty()) Crew.add(Math.min(summon, Crew.maxAgents()), null);   // runs on the tick before the goal
        FutureTask<String> t = new FutureTask<String>(() -> setNow(c, player, agents, text, w, window));
        c.queue.add(t);
        try {
            return t.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "Queued; the crew takes the goal on its next tick (" + e.getClass().getSimpleName() + ").";
        }
    }

    static final java.util.regex.Pattern STOP = java.util.regex.Pattern.compile("(?iu)^(stop|стоп|отмена|cancel|none|хватит)[.!]*$");

    /** on the server thread: the goal given to its agents, each with a mind of its own (a shared goal: one goal, many minds) */
    static String setNow(Crew c, String player, String agents, String text, String[] want, boolean window) {
        net.minecraft.server.MinecraftServer s = net.minecraft.server.MinecraftServer.getServer();
        if (s == null || s.worldServers == null) return "No world.";
        WorldServer w = s.worldServers[0];
        if (player != null && !player.isEmpty() && c.player == null) c.player = player;
        List<Agent> who = new ArrayList<Agent>();
        boolean self = false;
        String ag = agents == null ? "" : agents.trim().toLowerCase(java.util.Locale.ROOT);
        if (ag.isEmpty() || ag.startsWith("all") || ag.startsWith("все") || ag.startsWith("всем")) {
            for (Agent a : c.agents) if (!a.isDead) who.add(a);
        } else {
            for (String part : ag.split("[^0-9]+")) {
                if (part.isEmpty()) continue;
                int n = Integer.parseInt(part);
                if (Crew.PLAYER_ONE && n == 1) { self = true; continue; }
                Doer d = c.doer(n);
                if (d instanceof Agent && !who.contains(d)) who.add((Agent) d);
            }
        }
        if (who.isEmpty()) {
            if (window) Crew.note(self ? "mind.self" : "mind.none");
            return self ? "Agent 1 is the player himself: the minds' goals are for the agents (2..5)." : "No agents for the goal: call crew_goal with summon N, or the player adds them (/агент N).";
        }
        StringBuilder names = new StringBuilder();
        for (Agent a : who) names.append(names.length() > 0 ? ", " : "").append(a.who());
        if (STOP.matcher(text).matches()) {
            for (Agent a : who) {
                AgentMind m = minds.get(a.skin());
                if (m == null) continue;
                m.mind.end("GAVE_UP", "stopped by the player");
                m.close(c, a);
            }
            if (window) Crew.note("mind.stopped", names.toString());
            return "Goals cleared: " + names + ".";
        }
        Mind.Goal g = new Mind.Goal(text, want == null ? null : want[0], want == null ? 0 : Integer.parseInt(want[1]));
        for (Agent a : who) g.members.add(a.who());
        Mind.Limits lim = limits();
        if (g.defense) lim.defense();
        long now = System.currentTimeMillis();
        for (Agent a : who) {
            AgentMind old = minds.get(a.skin());
            if (old != null) { old.mind.end("GAVE_UP", "a new goal"); old.close(c, a); }
            c.board.giveBack(a, "a goal of its own");
            a.cancel("a goal of its own");
            if (a.ordered()) { Orders.cancel(c, a); a.ordered(false); a.holding(false); }   // the player gave it a goal: his orders are over
            AgentMind m = new AgentMind(a.skin(), new Mind(a.who(), g, lim, now), w);
            minds.put(a.skin(), m);
            m.show("thinking");
        }
        Events.log("mind: goal for " + names + ": \"" + text + "\"" + (g.item != null ? " (want " + g.want() + ")" : "") + " [limits " + lim.steps + " steps, " + lim.seconds / 60 + " min, " + lim.tokens + " tokens]");
        if (window) Crew.note("mind.set", names.toString(), text);
        return "Goal set for " + names + ": \"" + text + "\"" + (g.item != null ? ", done when " + (g.shared() ? "the crew has " : "it has ") + g.want() : "")
                + ". Each agent decides its own steps by the game's recipes and what it has seen, and shares the work with the others; it tells the player "
                + "when done or if it cannot. crew_goal with goal 'stop' clears it.";
    }

    // ---- the model and the limits: the server mod's ------------------------------------------------------------

    /** one completion through the server mod (its config, its key); an exception when it is not there */
    static String server(String body) throws Exception {
        try {
            java.lang.reflect.Method m = Class.forName("hardelele.aiguide.MindLink").getDeclaredMethod("complete", String.class);
            m.setAccessible(true);
            return (String) m.invoke(null, body);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("no AI Guide server mod here: the minds have no model");
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable t = e.getCause();
            throw t instanceof Exception ? (Exception) t : e;
        }
    }

    /** the config's limits of a goal ([mind] of aiguide.cfg), else the defaults */
    static Mind.Limits limits() {
        Mind.Limits l = new Mind.Limits();
        try {
            java.lang.reflect.Method m = Class.forName("hardelele.aiguide.MindLink").getDeclaredMethod("config");
            m.setAccessible(true);
            JsonObject o = new JsonParser().parse((String) m.invoke(null)).getAsJsonObject();
            if (o.has("steps")) l.steps = o.get("steps").getAsInt();
            if (o.has("minutes")) l.seconds = o.get("minutes").getAsInt() * 60;
            if (o.has("tokens")) l.tokens = o.get("tokens").getAsInt();
        } catch (Throwable ignored) {
            // no server mod: the defaults
        }
        return l;
    }
}
