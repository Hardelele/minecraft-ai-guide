package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.world.WorldServer;

/**
 * The defence in the world (the server thread's): the crew's planned walls (a ring round the base, lines) and the moat,
 * their holes now; the waves now (a source given to Defense.connect, else the waves mode's run: Waves.state(),
 * enemies(), its listener; else the enemies the crew's eyes see near the base); once a second the events the minds of defence hear (a wave began or is over, the wall breached, a
 * creeper at the wall, an ally hurt), the long fights interrupted by the ones that call for a decision; the defence's
 * part of the state a mind is shown (WAVE, PERIMETER, ALLIES). The plans live as long as the game runs (not saved).
 */
final class DefenseWorld {
    private DefenseWorld() { }

    static Defense.Perimeter ring;
    static final List<Defense.Perimeter> lines = new ArrayList<Defense.Perimeter>();
    static List<int[]> moat;              // the ring's moat (x y z depth), planned once from the ground as it was
    static WorldServer world;

    /** a new world (or none): its plans are not this one's */
    static void check(WorldServer w) {
        if (world == w) return;
        world = w;
        ring = null;
        moat = null;
        lines.clear();
        lastHoles = -1;
        lastOn = false;
    }

    /** a wall planned anew: its never-built cells are no breach */
    static void planned() { lastHoles = -1; }

    /** the ground of a column as one stands on it, near the height given */
    static Defense.Floor floor(final WorldServer w, final int y0) {
        return (x, z) -> Crew.ground(w, x, y0, z);
    }

    /** the base's middle (its anchor cell), or null when the crew has none */
    static int[] centre(Crew c, WorldServer w) {
        if (c != null && c.base != null) return Ground.anchor(c, w);
        int[] b = Waves.base();   // no crew base: where the waves go for (the player's place when the run began)
        return b == null ? null : new int[] { b[0], Crew.ground(w, b[0], b[1], b[2]) < 0 ? b[1] : Crew.ground(w, b[0], b[1], b[2]), b[2] };
    }

    static List<Defense.Perimeter> walls() {
        List<Defense.Perimeter> l = new ArrayList<Defense.Perimeter>();
        if (ring != null) l.add(ring);
        l.addAll(lines);
        return l;
    }

    /** a cell a block can be put in (air, plants): open in the wall (water is not, as the body cannot fill it) */
    static boolean open(WorldServer w, int x, int y, int z) {
        return Crew.soft(w, x, y, z);
    }

    /** the planned cells of every wall that are open now (never built, or broken since) */
    static List<int[]> holes(WorldServer w) {
        List<int[]> out = new ArrayList<int[]>();
        for (Defense.Perimeter p : walls()) for (int[] c : p.cells) if (open(w, c[0], c[1], c[2])) out.add(c);
        return out;
    }

    /** the cells of a wall that stand */
    static int standing(WorldServer w, Defense.Perimeter p) {
        int n = 0;
        for (int[] c : p.cells) if (!open(w, c[0], c[1], c[2])) n++;
        return n;
    }

    /** the zones no task digs in: each wall's cells and the ground under them (a box a side of the ring, a box a line) */
    static List<Ground.Zone> zones() {
        List<Ground.Zone> z = new ArrayList<Ground.Zone>();
        for (Defense.Perimeter p : walls()) {
            if (p.cells.isEmpty()) continue;
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
            for (int[] c : p.cells) { lo = Math.min(lo, c[1]); hi = Math.max(hi, c[1]); }
            if (p.ring) {
                int r = p.r;
                z.add(new Ground.Zone("wall", null, p.cx - r, lo - 1, p.cz - r, p.cx + r, hi, p.cz - r));
                z.add(new Ground.Zone("wall", null, p.cx - r, lo - 1, p.cz + r, p.cx + r, hi, p.cz + r));
                z.add(new Ground.Zone("wall", null, p.cx - r, lo - 1, p.cz - r, p.cx - r, hi, p.cz + r));
                z.add(new Ground.Zone("wall", null, p.cx + r, lo - 1, p.cz - r, p.cx + r, hi, p.cz + r));
            } else for (int[] c : p.cells) z.add(new Ground.Zone("wall", null, c[0], c[1] - 1, c[2], c[0], c[1], c[2]));
        }
        return z;
    }

    // ---- the waves -------------------------------------------------------------------------------------------

    /** the waves now: the source given to Defense.connect, else the waves mode (Waves) when a run is on, else the enemies the crew sees */
    static Defense.Wave wave(Crew c, WorldServer w) {
        Defense.WaveInfo src = Defense.source;
        if (src != null) {
            try {
                Defense.Wave v = src.now();
                if (v != null) { v.known = true; return v; }
            } catch (Exception e) {
                Events.log("defense: the waves source did not answer: " + e);
            }
        }
        if (Waves.active() || Waves.state().phase == WaveCycle.Phase.OVER) return fromWaves(Waves.state(), Waves.enemies());
        return seen(c, w);
    }

    /** the waves mode's run as the defence reads it (its view and its mobs alive; the server's thread) */
    static Defense.Wave fromWaves(WaveCycle.View s, List<EntityLiving> mobs) {
        Defense.Wave v = new Defense.Wave();
        v.known = true;
        v.phase = s.phase.name().toLowerCase(java.util.Locale.ROOT);
        v.paused = s.paused;
        v.on = s.phase == WaveCycle.Phase.WAVE && !s.paused;
        v.number = s.wave;
        v.cleared = s.cleared;
        v.enemies = s.enemies();
        v.nextIn = s.secondsToNext > 0 ? s.secondsToNext : -1;
        if (mobs != null) for (EntityLiving m : mobs) v.where.add(new double[] { m.posX, m.posY, m.posZ });
        return v;
    }

    /** the waves mode tells its run's turns at once (start, end, timeout, pause, resume, over): the minds of defence hear them */
    static boolean listening, lastFromWaves;

    static void listen() {
        if (listening) return;
        listening = true;
        Waves.listen(new Waves.Listener() {
            public void wave(String what, WaveCycle.View v) {
                if (!AgentMind.anyDefense()) return;
                Crew c = Crew.instance;
                if (what.equals("start")) {
                    atWaveStart.clear();
                    if (c != null) for (Agent a : c.agents) atWaveStart.put(a.who(), Defense.of(a.who()).copy());
                    AgentMind.tell("wave " + v.wave + " began: " + v.enemies() + " enemies", true);
                } else if (what.equals("end") || what.equals("timeout")) {
                    StringBuilder b = new StringBuilder();
                    if (c != null) for (Agent a : c.agents) b.append(b.length() > 0 ? "; " : "").append(a.who()).append(": ").append(Defense.of(a.who()).since(atWaveStart.get(a.who())).line());
                    Events.log("defense: wave " + v.wave + " " + (what.equals("end") ? "cleared" : "timed out") + " [" + b + "]");
                    AgentMind.tell("wave " + v.wave + (what.equals("end") ? " is beaten" : " ran out of time") + (v.secondsToNext > 0 ? ", the next in " + v.secondsToNext + " s" : ""), false);
                } else if (what.equals("over")) AgentMind.tell("the waves are over (" + v.cleared + " cleared" + (v.reason != null ? ", " + v.reason : "") + ")", true);
                else AgentMind.tell("the waves " + (what.equals("pause") ? "are paused" : "go on"), false);
            }
        });
    }

    /** no waves mode: the hostile mobs the agents see now (their senses), a wave being on while there are any */
    static Defense.Wave seen(Crew c, WorldServer w) {
        Defense.Wave v = new Defense.Wave();
        for (EntityLiving m : seenFoes(c)) { v.enemies++; v.where.add(new double[] { m.posX, m.posY, m.posZ }); }
        v.on = v.enemies > 0;
        return v;
    }

    /** the hostile mobs any agent's eyes see now (each once) */
    static List<EntityLiving> seenFoes(Crew c) {
        Map<Integer, EntityLiving> m = new LinkedHashMap<Integer, EntityLiving>();
        if (c != null)
            for (Agent a : c.agents)
                if (!a.isDead) for (EntityLivingBase e : a.body.senses.mobs) if (e instanceof EntityLiving && Guard.hostile(e)) m.put(e.getEntityId(), (EntityLiving) e);
        return new ArrayList<EntityLiving>(m.values());
    }

    // ---- once a second: what the minds of defence hear ---------------------------------------------------------

    static int lastHoles;
    static boolean lastOn;
    static int lastNumber;
    static long lastBreach = -1000, lastCreeper = -1000;
    static final Map<String, Long> hurtSaid = new HashMap<String, Long>();
    static Defense.Wave now;   // the last one read (for the state and the fights)
    static final Map<String, Defense.Tally> atWaveStart = new HashMap<String, Defense.Tally>();

    static void watch(Crew c, WorldServer w) {
        long t = w.getTotalWorldTime();
        if (t % 20 != 7) return;
        check(w);
        if (!AgentMind.anyDefense()) { now = null; return; }
        Defense.Wave v = wave(c, w);
        now = v;
        int[] o = centre(c, w);
        double cx = o != null ? o[0] + 0.5 : 0, cz = o != null ? o[2] + 0.5 : 0;
        listen();
        boolean told = Defense.source == null && (Waves.active() || lastFromWaves);   // the mode's own turns come by its listener
        lastFromWaves = Defense.source == null && Waves.active();
        if (!told && v.on && !lastOn) {
            atWaveStart.clear();
            for (Agent a : c.agents) atWaveStart.put(a.who(), Defense.of(a.who()).copy());
            String e = "a wave began: " + v.line(cx, cz);
            Events.log("waves: " + e);
            AgentMind.tell(e, true);
        } else if (!told && !v.on && lastOn) {
            StringBuilder b = new StringBuilder();
            for (Agent a : c.agents) {
                Defense.Tally d = Defense.of(a.who()).since(atWaveStart.get(a.who()));
                b.append(b.length() > 0 ? "; " : "").append(a.who()).append(": ").append(d.line());
            }
            String e = "the wave is over" + (lastNumber > 0 ? " (wave " + lastNumber + ")" : "") + (v.nextIn >= 0 ? ", the next in " + v.nextIn + " s" : "");
            Events.log("waves: " + e + " [" + b + "]");
            AgentMind.tell(e, false);
        }
        lastOn = v.on;
        if (v.number > 0) lastNumber = v.number;
        if (!walls().isEmpty()) {   // more open cells than a second ago: broken (a plan changed sets lastHoles to -1: no breach then)
            List<int[]> h = holes(w);
            if (lastHoles >= 0 && h.size() > lastHoles && t - lastBreach > 200) {
                int[] n = h.get(0);
                String e = "the wall is breached: " + h.size() + " holes now (" + (h.size() - lastHoles) + " new), one at " + n[0] + " " + n[1] + " " + n[2]
                        + (o != null ? " (" + Defense.side(n[0] + 0.5, n[2] + 0.5, cx, cz) + ")" : "");
                Events.log("waves: " + e);
                AgentMind.tell(e, true);
                lastBreach = t;
            }
            lastHoles = h.size();
        }
        if (ring != null)
            for (EntityLiving m : seenFoes(c)) {
                if (!(m instanceof EntityCreeper)) continue;
                double d = Math.max(Math.abs(Math.floor(m.posX) - ring.cx), Math.abs(Math.floor(m.posZ) - ring.cz));
                if (d >= ring.r && d <= ring.r + 3 && t - lastCreeper > 200) {
                    String e = "a creeper at the wall: " + (int) Math.floor(m.posX) + " " + (int) Math.floor(m.posY) + " " + (int) Math.floor(m.posZ) + " (" + Defense.side(m.posX, m.posZ, cx, cz) + ")";
                    Events.log("waves: " + e);
                    AgentMind.tell(e, true);
                    lastCreeper = t;
                }
            }
        for (Agent a : c.agents) {
            if (a.isDead || a.getHealth() >= Defense.WOUNDED) continue;
            Long said = hurtSaid.get(a.who());
            if (said != null && t - said < 300) continue;
            hurtSaid.put(a.who(), t);
            AgentMind.tell(a.who() + " is hurt: health " + Math.round(a.getHealth()) + " at " + (int) Math.floor(a.posX) + " " + (int) Math.floor(a.posY) + " " + (int) Math.floor(a.posZ), false);
        }
    }

    // ---- the defence's part of the state a mind is shown --------------------------------------------------------

    static void fill(MindState st, Crew c, Agent a, WorldServer w, boolean defense) {
        check(w);
        Defense.Wave v = now != null ? now : wave(c, w);
        if (!defense && !(v.known && v.on)) return;   // a goal of things hears of a wave only when the waves mode says one is on
        int[] o = centre(c, w);
        double cx = o != null ? o[0] + 0.5 : a.posX, cz = o != null ? o[2] + 0.5 : a.posZ;
        st.wave = v.line(cx, cz);
        st.waveOn = v.on;
        st.wearing = wearing(a);
        String mine = c.board.postOf(a);
        if (mine != null) { int[] p = postAt(mine); st.post = mine + (p != null && !mine.matches(".*\\d.*") ? " at " + p[0] + " " + p[1] + " " + p[2] : ""); }
        if (o == null) st.perimeter.add("the crew has no base yet: no wall can be planned round it");
        if (ring != null) {
            List<int[]> h = new ArrayList<int[]>();
            for (int[] k : ring.cells) if (open(w, k[0], k[1], k[2])) h.add(k);
            StringBuilder b = new StringBuilder("wall: ").append(ring.describe()).append("; ").append(ring.cells.size() - h.size()).append(" of ").append(ring.cells.size()).append(" cells stand");
            if (!h.isEmpty()) {
                MindSkills.nearestFirst(h, a);
                b.append(", ").append(h.size()).append(" open, nearest at ").append(h.get(0)[0]).append(' ').append(h.get(0)[1]).append(' ').append(h.get(0)[2])
                        .append(" (").append(Defense.side(h.get(0)[0] + 0.5, h.get(0)[2] + 0.5, cx, cz)).append(')');
            }
            st.perimeter.add(b.toString());
            StringBuilder ps = new StringBuilder("posts: ");
            for (int i = 0; i < ring.posts.size(); i++) {
                int[] p = ring.posts.get(i);
                Doer by = c.board.postHolder(ring.postNames.get(i));
                ps.append(i > 0 ? "; " : "").append(ring.postNames.get(i)).append(" at ").append(p[0]).append(' ').append(p[1]).append(' ').append(p[2])
                        .append(by == null ? " (free)" : by == a ? " (yours)" : " (" + by.who() + ")");
            }
            st.perimeter.add(ps.toString());
            if (moat != null) {
                int dug = 0;
                for (int[] m : moat) if (open(w, m[0], m[1], m[2])) dug++;
                st.perimeter.add("moat: " + dug + " of " + moat.size() + " blocks dug");
            }
        } else if (o != null) st.perimeter.add("no ring wall planned yet (build_wall around base)");
        for (Defense.Perimeter p : lines) st.perimeter.add("line wall of " + p.cells.size() + " cells: " + standing(w, p) + " stand");
        for (Map.Entry<String, Doer> e : c.board.posts.entrySet())
            if (e.getKey().matches("-?\\d+ -?\\d+ -?\\d+") && e.getValue().alive()) st.perimeter.add("post at " + e.getKey() + " (" + (e.getValue() == a ? "yours" : e.getValue().who()) + ")");
        for (Doer d : c.doers()) {
            if (d == a || !d.alive()) continue;
            StringBuilder b = new StringBuilder(d.who()).append(" at ").append((int) Math.floor(d.px())).append(' ').append((int) Math.floor(d.py())).append(' ').append((int) Math.floor(d.pz()));
            if (d instanceof Agent) {
                Agent g = (Agent) d;
                b.append(", health ").append(Math.round(g.getHealth())).append('/').append(Math.round(g.getMaxHealth()));
                ItemStack wp = g.bestWeapon();
                b.append(", weapon ").append(wp == null ? "none" : MindSkills.id(wp));
                if (Skills.count(g.bag, net.minecraft.init.Items.bow, -1) > 0) b.append(", bow and ").append(Skills.count(g.bag, net.minecraft.init.Items.arrow, -1)).append(" arrows");
            } else b.append(" (the player)");
            String p = c.board.postOf(d);
            b.append(p != null ? ", post " + p : ", no post");
            AgentMind m = AgentMind.minds.get(d.num());
            if (m != null && m.call != null) b.append(", now ").append(m.call.brief());
            st.allies.add(b.toString());
        }
    }

    /** a post's cell by its key: a name of the ring's, or "x y z" */
    static int[] postAt(String key) {
        int[] p = MindSkills.coords(key);
        if (p != null) return p;
        return ring == null ? null : ring.post(key);
    }

    static String wearing(Agent a) {
        StringBuilder b = new StringBuilder();
        for (int i = 4; i >= 1; i--) {
            ItemStack s = a.getEquipmentInSlot(i);
            if (s != null && s.getItem() instanceof ItemArmor) b.append(b.length() > 0 ? ", " : "").append(MindSkills.id(s));
        }
        return b.toString();
    }
}
