package hardelele.aiguide.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.item.ItemStack;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

/**
 * The open world (docs/rl/OPEN-WORLD.md): a mode of the learning environment (RlEnv) with no scenario. reset
 * {"mode":"open"} gives the arena and the crew as a scenario's reset does, then the time goes on (day, night), the
 * waves come at night, and every agent is driven by a player's keys a step (OpenSpec.STEP ticks) at a time (OpenBody)
 * and sees what its eyes saw (OpenEyes). Each answer carries the step's frame of events (rl/events.json: agents,
 * decisions, damage, deaths, the waves' threats, breaches, mob-seconds, kills, scales) for the outcomes' reward on the
 * learner's side (tools/rl/open_reward.py: reward v4's core). RlEnv keeps the connection, the server thread's wait
 * for the policy, the arena and the causes; this is called in place of its scenario's tick, answer and step.
 *
 * Rec: the teacher's recording in a scenario's episode (reset {"record_open": true}): each step of every agent its
 * open-world observation and the label of what its body did (OpenRec), in the answers' info.open.
 */
public final class RlOpen {
    static final int BASE_R = 8, BASE_DY = 6;

    final RlEnv env;
    final Map<Integer, OpenBody> bodies = new HashMap<Integer, OpenBody>();
    final Map<Integer, OpenEyes> eyes = new HashMap<Integer, OpenEyes>();
    final OpenEyes.Ctx ctx = new OpenEyes.Ctx();
    int days, inStep, stepN, night, wavesReached, maxSteps;
    long startTime;
    String level = "normal";
    boolean waves = true, firstStep;
    final List<JsonObject> frame = new ArrayList<JsonObject>();
    final Map<Integer, double[]> threats = new HashMap<Integer, double[]>();   // a wave's mob id -> { since, killed }
    final Set<Integer> breached = new HashSet<Integer>();
    final Map<Integer, Float> hp = new HashMap<Integer, Float>();
    final Map<Integer, Integer> hurtBy = new HashMap<Integer, Integer>();
    final Map<Integer, int[]> tiers = new HashMap<Integer, int[]>();
    final Map<String, Integer> stats = new LinkedHashMap<String, Integer>();
    final Map<Integer, Long> decision = new HashMap<Integer, Long>();
    double insideS;

    RlOpen(RlEnv env) { this.env = env; }

    static boolean wants(JsonObject msg) { return msg.has("mode") && "open".equals(msg.get("mode").getAsString()); }

    static boolean registered;

    void reset(WorldServer w, JsonObject msg) {
        JsonObject m = new JsonObject();
        m.addProperty("scenario", msg.has("scenario") ? msg.get("scenario").getAsString() : "achievements");   // its kit and hunger; its goal and time are not used
        m.addProperty("contract", RlSpec.NAME);
        if (msg.has("seed")) m.add("seed", msg.get("seed"));
        if (msg.has("crew")) m.add("crew", msg.get("crew"));
        if (msg.has("kit")) m.addProperty("extra_kit", msg.get("kit").getAsString());
        env.reset(w, m);
        AgentMind.minds.clear();   // no minds: the policy is the body's only driver
        if (!registered) { MinecraftForge.EVENT_BUS.register(this); registered = true; }
        days = msg.has("days") ? Math.max(1, msg.get("days").getAsInt()) : 1;
        maxSteps = msg.has("max_steps") ? msg.get("max_steps").getAsInt() : 0;
        level = msg.has("waves") ? msg.get("waves").getAsString() : "normal";
        waves = !level.equals("off");
        startTime = msg.has("time") ? msg.get("time").getAsLong() : msg.has("scenario") ? env.sc.time : 0;   // (a scenario's: its time, for a teacher's replay)
        w.getGameRules().setOrCreateGameRule("doDaylightCycle", "true");
        w.setWorldTime(startTime);
        if (Waves.cycle.active()) Waves.stop("rl_open_reset");
        bodies.clear();
        eyes.clear();
        frame.clear();
        threats.clear();
        breached.clear();
        hp.clear();
        hurtBy.clear();
        tiers.clear();
        stats.clear();
        decision.clear();
        insideS = 0;
        inStep = stepN = night = wavesReached = startedNight = 0;
        firstStep = true;
        ctx.base = Crew.instance == null ? null : Crew.instance.base;
        ctx.phase = "none";
        ctx.night = 0;
        Crew c = Crew.instance;
        if (c != null) for (Agent a : c.agents) { attach(a); env.waiting.add(a); }
        if (msg.has("near") && c != null && !c.agents.isEmpty()) near(w, c.agents.get(0), msg.get("near").getAsString());
        agentEvents(c);
    }

    /** (the checks': tools/rl/open_check.py) a creature of the game's name 2.5 blocks in front of the agent, on the ground */
    static void near(WorldServer w, Agent a, String name) {
        Entity e = net.minecraft.entity.EntityList.createEntityByName(name, w);
        if (e == null) return;
        double y = Math.toRadians(a.rotationYawHead);
        double x = a.posX - Math.sin(y) * 2.5, z = a.posZ + Math.cos(y) * 2.5;
        e.setLocationAndAngles(x, a.posY, z, a.rotationYawHead + 180, 0);
        w.spawnEntityInWorld(e);
    }

    /** an agent (the one spawned, or one back from death: a new entity) gets its body's driver */
    OpenBody attach(Agent a) {
        OpenBody b = bodies.get(a.skin());
        if (b == null) bodies.put(a.skin(), b = new OpenBody(a.skin()));
        if (a.open != b) {
            a.open = b;
            b.aimed = false;
            b.selected = 0;
            b.resetDig(null);
            a.getNavigator().clearPathEntity();
        }
        if (!eyes.containsKey(a.skin())) eyes.put(a.skin(), new OpenEyes());
        return b;
    }

    void close() {
        Crew c = Crew.instance;
        if (c != null) for (Agent a : c.agents) a.open = null;
        if (Waves.cycle.active()) Waves.stop("rl_open_close");
    }

    // ---- a tick (RlEnv.episodeTick, in place of the scenario's) -----------------------------------------------------

    void tick(WorldServer w) {
        Crew c = Crew.instance;
        if (c == null) return;
        for (Agent a : c.agents) if (!a.isDead && a.open == null) attach(a);
        long tod = w.getWorldTime() % 24000;
        boolean nightNow = tod >= 13000 && tod < 23000;
        if (tod == 13000 || nightNow && night == 0) night++;   // dusk (or a start in the night)
        ctx.night = night;
        if (waves && nightNow && startedNight != night && !Waves.cycle.active() && !env.done) {   // the night's waves, on from the last night's
            startedNight = night;
            Waves.start(level, "rl_open", env.seed * 31 + night);
            Waves.cycle.wave = wavesReached;
        }
        if (!nightNow && Waves.cycle.active()) {
            wavesReached = Math.max(wavesReached, Waves.cycle.wave);
            Waves.stop("rl_dawn");
            for (Map.Entry<Integer, double[]> e : threats.entrySet()) if (e.getValue()[1] == 0) gone(e.getKey(), "dawn");
            threats.clear();
        }
        WaveCycle.Phase ph = Waves.cycle.phase;
        ctx.phase = ph == WaveCycle.Phase.PREP ? "prep" : ph == WaveCycle.Phase.WAVE ? "wave" : ph == WaveCycle.Phase.BREAK ? "break" : "none";
        if (Waves.cycle.active()) wavesReached = Math.max(wavesReached, Waves.cycle.wave);
        if (startedNight > 0 && ph == WaveCycle.Phase.OVER && "lost".equals(Waves.cycle.reason) && !env.done) { env.finish("lost"); stat("lost", 1); }   // (a run of this episode's)
        for (Agent a : c.agents) {   // damage, a tick at a time (the mob that hit last)
            if (a.isDead) continue;
            Float was = hp.get(a.skin());
            float now = a.getHealth();
            if (was != null && now < was - 1e-3) {
                JsonObject o = ev("damage");
                o.addProperty("agent", RlEnv.id(a));
                o.addProperty("hp", round(was - now));
                Integer mob = hurtBy.remove(a.skin());
                if (mob != null) o.addProperty("mob", mob);
                frame.add(o);
            }
            hp.put(a.skin(), now);
        }
        if (env.ticks % 20 == 0) second(c, w);
        if (++inStep >= OpenSpec.STEP) {
            inStep = 0;
            for (Agent a : c.agents) if (!a.isDead) env.waiting.add(a);
            if (w.getWorldTime() - startTime >= (long) days * 24000) env.finish("days");
            if (maxSteps > 0 && stepN >= maxSteps) env.finish("steps");
        }
    }

    int startedNight;

    /** once a second: the waves' mobs (threats to the base, breaches, the time they spend inside), the equipment's tiers */
    void second(Crew c, WorldServer w) {
        int[] b = ctx.base;
        double inside = 0;
        Set<Integer> alive = new HashSet<Integer>();
        for (EntityLiving m : new ArrayList<EntityLiving>(Waves.alive)) {
            if (m.isDead) continue;
            int id = m.getEntityId();
            alive.add(id);
            if (!threats.containsKey(id)) {
                threats.put(id, new double[] { env.ticks / 20.0, 0 });
                JsonObject o = ev("threat");
                o.addProperty("mob", id);
                o.addProperty("kind", OpenEyes.kindOf(m));
                o.addProperty("target", "base");
                double dmg = m.getEntityAttribute(SharedMonsterAttributes.attackDamage) == null ? 3 : m.getEntityAttribute(SharedMonsterAttributes.attackDamage).getAttributeValue();
                o.addProperty("dmg", round(dmg));
                long tod = w.getWorldTime() % 24000;
                o.addProperty("stay_s", Math.max(0, (23000 - tod) / 20));
                frame.add(o);
            }
            if (b != null && Math.max(Math.abs(m.posX - b[0] - 0.5), Math.abs(m.posZ - b[2] - 0.5)) < BASE_R && Math.abs(m.posY - b[1]) <= BASE_DY) {
                inside += 1;
                if (breached.add(id)) {
                    JsonObject o = ev("breach");
                    o.addProperty("mob", id);
                    frame.add(o);
                    stat("breaches", 1);
                }
            }
        }
        for (Map.Entry<Integer, double[]> e : new ArrayList<Map.Entry<Integer, double[]>>(threats.entrySet()))
            if (!alive.contains(e.getKey())) { if (e.getValue()[1] == 0) gone(e.getKey(), "gone"); threats.remove(e.getKey()); }
        if (inside > 0) {
            JsonObject o = ev("inside");
            o.addProperty("mob_s", inside);
            frame.add(o);
            insideS += inside;
        }
        for (Agent a : c.agents) {   // the equipment's scale (as v3's): the best pickaxe and weapon by tier / 4, the armour worn / 20
            if (a.isDead) continue;
            int[] now = { RlEnv.pickTier(a), RlEnv.swordTier(a), RlEnv.worn(a) };
            int[] was = tiers.get(a.skin());
            tiers.put(a.skin(), now);
            if (was == null) continue;
            String[] keys = { "equipment.pickaxe", "equipment.weapon", "equipment.armor" };
            double[] div = { 4, 4, 20 };
            for (int i = 0; i < 3; i++) if (now[i] != was[i]) scale(RlEnv.id(a), keys[i], (now[i] - was[i]) / div[i]);
        }
    }

    void gone(int mob, String why) {
        JsonObject o = ev("mob_gone");
        o.addProperty("mob", mob);
        o.addProperty("why", why);
        frame.add(o);
    }

    void scale(String agent, String key, double delta) {
        JsonObject o = ev("scale");
        o.addProperty("agent", agent);
        o.addProperty("key", key);
        o.addProperty("delta", round(delta));
        frame.add(o);
    }

    static JsonObject ev(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        return o;
    }

    static double round(double x) { return Math.round(x * 1e4) / 1e4; }

    void stat(String k, int n) { stats.put(k, (stats.containsKey(k) ? stats.get(k) : 0) + n); }

    // ---- the game's events: who hurt an agent, who killed what ------------------------------------------------------

    @SubscribeEvent
    public void hurt(LivingHurtEvent e) {
        if (env.open != this || !(e.entityLiving instanceof Agent)) return;
        Entity src = e.source.getEntity();
        if (src != null) hurtBy.put(((Agent) e.entityLiving).skin(), src.getEntityId());
    }

    @SubscribeEvent
    public void death(LivingDeathEvent e) {
        if (env.open != this || env.state == RlEnv.State.IDLE) return;
        if (e.entityLiving instanceof Agent) {
            Agent a = (Agent) e.entityLiving;
            JsonObject o = ev("death");
            o.addProperty("agent", RlEnv.id(a));
            o.addProperty("cause", e.source.getDamageType());
            int[] b = ctx.base;
            o.addProperty("base_dist", b == null ? 0 : round(Math.hypot(a.posX - b[0] - 0.5, a.posZ - b[2] - 0.5)));
            frame.add(o);
            long tod = a.worldObj.getWorldTime() % 24000;
            stat(tod >= 13000 && tod < 23000 ? "deaths_night" : "deaths_day", 1);
            hp.remove(a.skin());
            return;
        }
        Entity src = e.source.getEntity();
        if (src == null) return;
        Matcher m = Waves.HANDS.matcher(src.getCommandSenderName());
        if (!m.find()) return;
        int id = e.entityLiving.getEntityId();
        double[] t = threats.get(id);
        if (t != null) t[1] = 1;
        JsonObject o = ev("kill");
        o.addProperty("mob", id);
        o.addProperty("agent", m.group(1));
        Long d = decision.get(Integer.parseInt(m.group(1)));
        if (d != null) o.addProperty("decision", d);
        o.addProperty("kind", OpenEyes.kindOf(e.entityLiving));
        frame.add(o);
        stat(Guard.hostile(e.entityLiving) || t != null ? "kills" : "animals_killed", 1);
    }

    // ---- the answer and the step --------------------------------------------------------------------------------------

    void agentEvents(Crew c) {
        if (c == null) return;
        int[] b = ctx.base;
        for (Agent a : c.agents) {
            JsonObject o = ev("agent");
            o.addProperty("agent", RlEnv.id(a));
            o.addProperty("x", round(a.posX));
            o.addProperty("y", round(a.posY));
            o.addProperty("z", round(a.posZ));
            o.addProperty("hp", round(a.getHealth()));
            o.addProperty("alive", !a.isDead && a.getHealth() > 0);
            o.addProperty("base_dist", b == null ? 0 : round(Math.hypot(a.posX - b[0] - 0.5, a.posZ - b[2] - 0.5)));
            frame.add(0, o);
        }
    }

    String phaseNow(WorldServer w) {
        if (Waves.cycle.phase == WaveCycle.Phase.WAVE) return "wave";
        long tod = w.getWorldTime() % 24000;
        return tod >= 13000 && tod < 23000 ? "night" : "day";
    }

    void answer(WorldServer w) {
        Crew c = Crew.instance;
        agentEvents(c);
        for (JsonObject e : env.events) {   // achievements (RlAch -> RlEnv.achieved) as the scale's steps; novel effects
            String type = e.has("type") ? e.get("type").getAsString() : "";
            if (type.equals("achievement")) {
                String ag = e.get("agent").getAsString(), id = e.get("id").getAsString();
                scale(ag, "achievement.new", 1);
                scale(ag, "achievement.depth", RlAchievements.depth(id));
                JsonObject o = ev("achievement");
                o.addProperty("agent", ag);
                o.addProperty("id", id);
                frame.add(o);
            } else if (type.equals("novel_effect")) {
                JsonObject o = ev("novel");
                o.addProperty("agent", e.get("agent").getAsString());
                frame.add(o);
            }
        }
        env.events.clear();
        env.rewardEvents.clear();
        if (env.reward != null) for (String k : new ArrayList<String>(env.reward.open.keySet())) env.reward.take(k, new JsonObject());   // (the scenario's weights: not this mode's reward)
        JsonObject o = new JsonObject(), obs = new JsonObject(), mask = new JsonObject(), last = new JsonObject();
        o.addProperty("open", true);
        JsonArray ids = new JsonArray();
        if (c != null && !env.done)
            for (Agent a : c.agents) {
                if (a.isDead) continue;
                OpenBody b = attach(a);
                String id = RlEnv.id(a);
                ids.add(new JsonPrimitive(id));
                obs.add(id, eyes.get(a.skin()).observe(a, w, ctx, b, b.selected));
                mask.add(id, RlEnv.ints(OpenBody.mask(a, w)));
                last.addProperty(id, b.last);
            }
        o.add("agents", ids);
        o.add("obs", obs);
        o.add("mask", mask);
        JsonObject fr = new JsonObject();
        fr.addProperty("t", round(env.ticks / 20.0));
        fr.addProperty("dt", round(OpenSpec.STEP / 20.0));
        fr.addProperty("phase", phaseNow(w));
        JsonArray evs = new JsonArray();
        for (JsonObject e : frame) evs.add(e);
        fr.add("events", evs);
        frame.clear();
        o.add("frame", fr);
        o.addProperty("done", env.done);
        JsonObject info = new JsonObject();
        info.addProperty("ticks", env.ticks);
        info.addProperty("step", stepN);
        info.addProperty("episode", env.episode);
        info.addProperty("seed", env.seed);
        info.addProperty("time_of_day", w.getWorldTime() % 24000);
        info.addProperty("night", night);
        info.addProperty("wave", Waves.cycle.active() ? Waves.cycle.wave : 0);
        info.addProperty("waves_reached", wavesReached);
        info.add("last", last);
        long wall = (System.nanoTime() - env.startNs) / 1000000;
        double sim = Math.max(1e-6, env.simNs / 1e9);
        info.addProperty("wall_ms", wall);
        info.addProperty("sim_ms", Math.round(sim * 1000));
        info.addProperty("game_s_per_s", Math.round(env.ticks / 20.0 / sim * 10) / 10.0);
        info.addProperty("reset_ms", env.resetMs);
        if (env.done) {
            info.addProperty("done_reason", env.doneReason);
            JsonObject ach = new JsonObject();
            if (c != null) for (Agent a : c.agents) {
                JsonArray l = new JsonArray();
                for (String x : RlAch.of(a.who())) l.add(new JsonPrimitive(x));
                ach.add(RlEnv.id(a), l);
            }
            info.add("achievements", ach);
            JsonObject st = new JsonObject();
            for (Map.Entry<String, Integer> e : stats.entrySet()) st.addProperty(e.getKey(), e.getValue());
            st.addProperty("mob_seconds", round(insideS));
            JsonObject per = new JsonObject();
            for (OpenBody b : bodies.values()) {
                JsonObject x = new JsonObject();
                x.addProperty("crafted", b.crafted);
                x.addProperty("placed", b.placed);
                x.addProperty("broken", b.broken);
                x.addProperty("hits", b.hits);
                per.add(String.valueOf(b.skin), x);
            }
            st.add("bodies", per);
            info.add("stats", st);
            Events.log("rl open: episode " + env.episode + " done (" + env.doneReason + ") at " + env.ticks / 20 + " game s, " + stepN + " steps, " + st);
        }
        o.add("info", info);
        if (env.replyTo != null) env.replyTo.send(o);
    }

    void step(WorldServer w, RlEnv.Req r) {
        if (env.state != RlEnv.State.WAIT || r.conn != env.replyTo) { r.conn.send(RlEnv.error(env.state == RlEnv.State.IDLE ? "no episode: reset first" : "not waiting for actions now")); return; }
        if (!r.msg.has("actions") || !r.msg.get("actions").isJsonObject()) { r.conn.send(RlEnv.error("step needs actions: {agent: [move, jump, yaw, pitch, act]}")); return; }
        Map<Integer, int[]> got = new HashMap<Integer, int[]>();
        for (Map.Entry<String, JsonElement> e : r.msg.getAsJsonObject("actions").entrySet()) {
            try {
                JsonArray a = e.getValue().getAsJsonArray();
                if (a.size() != OpenSpec.HEADS.length) throw new IllegalArgumentException("heads");
                int[] h = new int[a.size()];
                for (int i = 0; i < h.length; i++) {
                    h[i] = a.get(i).getAsInt();
                    if (h[i] < 0 || h[i] >= OpenSpec.SIZES[i]) throw new IllegalArgumentException(OpenSpec.HEADS[i] + " out of 0.." + (OpenSpec.SIZES[i] - 1));
                }
                got.put(Integer.parseInt(e.getKey()), h);
            } catch (Exception ex) {
                r.conn.send(RlEnv.error("bad action " + e.getKey() + ": " + e.getValue() + " (" + ex.getMessage() + ")"));
                return;
            }
        }
        Crew c = Crew.instance;
        if (c != null)
            for (Agent a : c.agents) {
                if (a.isDead) continue;
                int[] h = got.containsKey(a.skin()) ? got.get(a.skin()) : new int[OpenSpec.HEADS.length];
                if (firstStep) RlAch.decided(a);   // (openInventory: the bag is always open)
                String kind = OpenSpec.actName(h[4]);
                long d = env.cause.decide(RlEnv.id(a), env.ticks, kind).id;
                decision.put(a.skin(), d);
                JsonObject o = ev("decision");
                o.addProperty("agent", RlEnv.id(a));
                o.addProperty("id", d);
                o.addProperty("kind", kind);
                frame.add(o);
                attach(a).begin(a, w, h);
            }
        firstStep = false;
        stepN++;
        inStep = 0;
        env.waiting.clear();
        env.state = RlEnv.State.RUN;
        env.runSince = System.nanoTime();
    }

    // ---- the teacher's recording ------------------------------------------------------------------------------------------

    /** in a scenario's episode: each agent's open-world steps, each its observation at the start and the label of what its body did */
    static final class Rec {
        final RlEnv env;
        final Map<Integer, OpenEyes> eyes = new HashMap<Integer, OpenEyes>();
        final Map<Integer, OpenSpec.Labeler> labs = new HashMap<Integer, OpenSpec.Labeler>();
        final Map<Integer, Object[]> start = new HashMap<Integer, Object[]>();   // skin -> { obs, mask, yaw, pitch, x, y, z, held, jumpedAt-at-start }
        final List<JsonObject> out = new ArrayList<JsonObject>();
        final OpenEyes.Ctx ctx = new OpenEyes.Ctx();
        int inStep, steps;

        /**
         * DAgger by perturbation (DART): with "perturb" p a step an agent's body is taken from the macro for "perturb_steps"
         * steps and driven by random keys (the legs, the head: a stumble, a look away); then the macro goes on from where
         * the body is, and its labels there are the teacher's in states its own play would not reach (the learner's
         * kind of states: a foot off the path, the aim off the log). The perturbed steps are recorded unrep (no BC).
         */
        final double perturb;
        final int perturbSteps;
        final java.util.Random rnd;
        final Map<Integer, Integer> perturbing = new HashMap<Integer, Integer>();
        final Map<Integer, OpenBody> pbody = new HashMap<Integer, OpenBody>();

        Rec(RlEnv env, JsonObject msg) {
            this.env = env;
            OpenRec.on = true;
            OpenRec.accs.clear();
            perturb = msg.has("perturb") ? msg.get("perturb").getAsDouble() : 0;
            perturbSteps = msg.has("perturb_steps") ? msg.get("perturb_steps").getAsInt() : 8;
            rnd = new java.util.Random(env.seed * 7 + 1);
        }

        /** at a step's start: a perturbation begins, goes on (new random keys now and then) or ends (the macro has the body again) */
        void perturbStep(Agent a, WorldServer w) {
            Integer left = perturbing.get(a.skin());
            if (left == null && perturb > 0 && rnd.nextDouble() < perturb) left = perturbSteps;
            if (left == null) return;
            if (left <= 0) { perturbing.remove(a.skin()); a.open = null; return; }
            perturbing.put(a.skin(), left - 1);
            OpenBody b = pbody.get(a.skin());
            if (b == null) pbody.put(a.skin(), b = new OpenBody(a.skin()));
            if (a.open != b) { a.open = b; b.aimed = false; b.selected = Math.max(0, a.bag.indexOf(a.getHeldItem())); a.getNavigator().clearPathEntity(); }
            if (left == perturbSteps || rnd.nextDouble() < 0.4) {
                int[] h = { rnd.nextInt(OpenSpec.MOVES.length), rnd.nextDouble() < 0.15 ? 1 : 0, 2 + rnd.nextInt(4), rnd.nextDouble() < 0.7 ? 0 : 1 + rnd.nextInt(4), 0 };
                b.begin(a, w, h);
            } else b.begin(a, w, new int[] { b.move, b.jump, 0, 0, 0 });
        }

        static final String[] UNREP = { "put:", "take:", "smelt:", "equip:", "enchant:", "lure:", "breed:", "shoot:", "place:bookshelf" };

        void tick(WorldServer w) {
            Crew c = Crew.instance;
            if (c == null) return;
            ctx.base = c.base;
            WaveCycle.Phase ph = Waves.cycle.phase;
            ctx.phase = ph == WaveCycle.Phase.PREP ? "prep" : ph == WaveCycle.Phase.WAVE ? "wave" : ph == WaveCycle.Phase.BREAK ? "break" : "none";
            if (inStep++ % OpenSpec.STEP != 0) return;
            // a step's end and the next one's start at the same tick: the label of the 4 ticks gone, then what is seen now
            for (Agent a : c.agents) {
                Object[] s = start.remove(a.skin());
                if (s != null && !a.isDead) label(a, s);
            }
            steps++;
            for (Agent a : c.agents) {
                if (a.isDead) continue;
                OpenEyes e = eyes.get(a.skin());
                if (e == null) eyes.put(a.skin(), e = new OpenEyes());
                int sel = a.bag.indexOf(a.getHeldItem());
                JsonObject ob = e.observe(a, w, ctx, null, sel < 0 ? a.bag.size() : sel);
                start.put(a.skin(), new Object[] { ob, RlEnv.ints(OpenBody.mask(a, w)), a.body.yawNow(), a.body.pitchNow(), a.posX, a.posY, a.posZ, a.getHeldItem(), a.jumpedAt });
                OpenRec.acc(a.skin()).clear();
                boolean was = perturbing.containsKey(a.skin());
                perturbStep(a, w);
                if (was || perturbing.containsKey(a.skin())) OpenRec.acc(a.skin()).unrep = true;   // (the random keys' step: not the teacher's)
            }
        }

        void label(Agent a, Object[] s) {
            OpenSpec.Labeler lab = labs.get(a.skin());
            if (lab == null) labs.put(a.skin(), lab = new OpenSpec.Labeler());
            float dy = net.minecraft.util.MathHelper.wrapAngleTo180_float(a.body.yawNow() - (Float) s[2]), dp = a.body.pitchNow() - (Float) s[3];
            int[] turn = lab.turn(dy, dp);
            int move = lab.move(a.posX - (Double) s[4], a.posZ - (Double) s[6], (Float) s[2]);
            int jump = a.jumpedAt > (Integer) s[8] ? 1 : a.isSneaking() ? 2 : 0;
            OpenRec.Acc acc = OpenRec.acc(a.skin());
            int slot = -1;
            boolean unrep = acc.unrep;
            if (a.getHeldItem() != s[7]) {   // the hand changed: a slot of the hotbar, or bare hands as an empty slot
                int i = a.getHeldItem() == null ? a.bag.size() : a.bag.indexOf(a.getHeldItem());
                if (i >= 0 && i < OpenSpec.HOTBAR) slot = i; else unrep = true;
            }
            int act = OpenSpec.Labeler.act(acc.recipe, acc.furnace, acc.used, acc.attacked, slot, acc.dropped);
            RlEnv.Mine mm = env.mine(a);
            String macro = mm.last >= 0 && mm.last < RlSpec.ACTIONS.size() ? RlSpec.ACTIONS.get(mm.last).id : "";
            for (String u : UNREP) if (macro.startsWith(u)) unrep = true;
            JsonObject f = new JsonObject();
            f.addProperty("agent", RlEnv.id(a));
            f.addProperty("t", round(env.ticks / 20.0));
            f.add("obs", (JsonObject) s[0]);
            f.add("mask", (JsonArray) s[1]);
            f.add("act", RlEnv.ints(new int[] { move, jump, turn[0], turn[1], act }));
            f.addProperty("unrep", unrep);
            f.addProperty("macro", macro);
            f.add("pos", RlEnv.floats(new float[] { (float) (double) (Double) s[4], (float) (double) (Double) s[5], (float) (double) (Double) s[6] }));   // (where the step began: for the checks, not seen)
            if (!acc.what.isEmpty()) f.addProperty("what", acc.what);
            out.add(f);
        }

        JsonArray drain() {
            JsonArray a = new JsonArray();
            for (JsonObject e : out) a.add(e);
            out.clear();
            return a;
        }
    }
}
