package hardelele.aiguide.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;

/**
 * A situation of an agent as plain numbers, with no game in it: what the body sensed on a tick (Body.Senses), where
 * its task's target is from its feet, what task and what part of it, how long without getting anywhere, what is in the
 * bag. Its vector is the fast level's "embedding": a fixed row of numbers each in 0..1, made in a few microseconds,
 * the same for the same situation, so that a reflex can find the cases most like the one it is in (CaseBook) with no
 * network on the tick. The words of it (text) are for the slow level: the offline tool embeds them with a model.
 */
final class CaseVec {
    /** what is known of the situation (the fields of the journal's "situation") */
    static final class Sit {
        float health = 20, max = 20;
        boolean hurt;
        String hurtBy = "";        // the damage type (fall, inFire, lava, inWall, drown...) or the mob's name
        float fall;
        double motionY;
        boolean onGround = true, falling, inWater, inLava, burning, underWater, collided;
        int[] feet;
        String under = "";         // the block under the feet ("minecraft:air")
        boolean underSolid;        // it holds the feet up
        boolean underLiquid;
        String threat = "";        // the nearest hostile mob it sees ("Creeper"), "" none
        double threatDist = 1e9;
        int mobs, hostiles;        // living things it sees, of them hostile
        boolean target;            // the task has a place it is after
        double dx, dy, dz;         // that place from the feet
        String task = "";          // the task's class ("GoTo", "Scaffold"), "" no task
        String phase = "";         // what part of it ("DIG" for a GoTo digging its way)
        String card = "";          // the board's card ("build#41")
        String origin = "";        // where the task came from (Origin)
        int still;                 // ticks without getting nearer (GoTo.stuckFor), 0 unknown
        int blocks, torches;       // solid blocks and torches in the bag
        boolean pickaxe, weapon, waterBucket;
        int light = -1;            // the light at the feet, 0..15 (-1 unknown)
    }

    /** the vector's components, in order (the offline tool and the journal's readers use the same names) */
    static final String[] NAMES = {
        "health", "hurt", "hurt_fall", "hurt_fire", "hurt_wall_drown", "hurt_mob",
        "fall", "sinking", "on_ground", "falling", "in_water", "in_lava", "burning", "under_water", "collided",
        "under_air", "under_liquid", "under_solid",
        "target", "target_dy", "target_up2", "target_down2", "target_far",
        "threat", "threat_near", "threat_creeper", "mobs",
        "still",
        "task_none", "task_walk", "task_dig_way", "task_build", "task_dig", "task_fight", "task_other",
        "bag_blocks", "bag_torches", "bag_pickaxe", "bag_weapon", "bag_water",
        "dark", "underground", "by_player"
    };
    static final int DIM = NAMES.length;

    /**
     * How much each component counts in the distance (CaseBook): what threatens the body most, then where the target
     * is and what the task is, the bag least. A component with weight 3 that differs fully moves a case further than
     * three with weight 1 that differ a little.
     */
    static final double[] WEIGHTS = new double[DIM];
    static {
        java.util.Arrays.fill(WEIGHTS, 1.0);
        for (String n : new String[] { "in_lava", "burning", "under_water", "falling", "threat_near", "threat_creeper", "hurt_fire", "hurt_wall_drown" }) WEIGHTS[index(n)] = 3.0;
        for (String n : new String[] { "target_dy", "target_up2", "target_down2", "in_water", "under_air", "collided", "still", "fall" }) WEIGHTS[index(n)] = 2.0;
        for (String n : new String[] { "bag_torches", "bag_pickaxe", "bag_weapon", "bag_water", "mobs", "dark", "underground", "by_player" }) WEIGHTS[index(n)] = 0.5;
    }

    static int index(String name) {
        for (int i = 0; i < DIM; i++) if (NAMES[i].equals(name)) return i;
        throw new IllegalArgumentException(name);
    }

    static double clip(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
    static double b(boolean v) { return v ? 1 : 0; }

    static boolean hurtFire(String by) { return by.equals("inFire") || by.equals("onFire") || by.equals("lava"); }
    static boolean hurtWall(String by) { return by.equals("inWall") || by.equals("drown"); }
    static boolean damageType(String by) {
        return by.isEmpty() || by.equals("fall") || hurtFire(by) || hurtWall(by) || by.equals("cactus") || by.equals("outOfWorld") || by.equals("generic")
                || by.equals("starve") || by.equals("magic") || by.equals("wither") || by.equals("anvil") || by.equals("fallingBlock");
    }

    /** the task's kind as the vector knows it */
    static String kind(String task, String phase) {
        if (task == null || task.isEmpty()) return "task_none";
        if (task.equals("GoTo") || task.equals("Collect") || task.equals("Stash") || task.equals("Take")) return "DIG".equals(phase) ? "task_dig_way" : "task_walk";
        if (task.equals("Build") || task.equals("PlaceFrom") || task.equals("Scaffold") || task.equals("Pillar") || task.equals("Steps") || task.equals("Light")) return "task_build";
        if (task.equals("Mine") || task.equals("Fell") || task.equals("Break") || task.equals("Gather")) return "task_dig";
        if (task.equals("Fight")) return "task_fight";
        return "task_other";
    }

    /** the situation's vector: DIM numbers, each in 0..1 */
    static double[] vector(Sit s) {
        double[] v = new double[DIM];
        int i = 0;
        String by = s.hurtBy == null ? "" : s.hurtBy;
        v[i++] = clip(s.max > 0 ? s.health / s.max : 1);
        v[i++] = b(s.hurt);
        v[i++] = b(s.hurt && by.equals("fall"));
        v[i++] = b(s.hurt && hurtFire(by));
        v[i++] = b(s.hurt && hurtWall(by));
        v[i++] = b(s.hurt && !damageType(by));
        v[i++] = clip(s.fall / 8.0);
        v[i++] = clip(-s.motionY);
        v[i++] = b(s.onGround);
        v[i++] = b(s.falling);
        v[i++] = b(s.inWater);
        v[i++] = b(s.inLava);
        v[i++] = b(s.burning);
        v[i++] = b(s.underWater);
        v[i++] = b(s.collided);
        v[i++] = b(!s.underSolid && !s.underLiquid);
        v[i++] = b(s.underLiquid);
        v[i++] = b(s.underSolid);
        v[i++] = b(s.target);
        v[i++] = s.target ? clip(0.5 + s.dy / 8.0) : 0.5;   // 0.5 level, 1 four and more up, 0 four and more down
        v[i++] = b(s.target && s.dy >= 2);
        v[i++] = b(s.target && s.dy <= -2);
        v[i++] = s.target ? clip(Math.sqrt(s.dx * s.dx + s.dz * s.dz) / 16.0) : 0;
        boolean threat = s.threat != null && !s.threat.isEmpty() && s.threatDist < 1e8;
        v[i++] = b(threat);
        v[i++] = threat ? clip(1 - s.threatDist / 16.0) : 0;
        v[i++] = b(threat && s.threat.toLowerCase(Locale.ROOT).contains("creeper"));
        v[i++] = clip(s.mobs / 6.0);
        v[i++] = clip(s.still / 600.0);   // half a minute and more: as stuck as it gets
        String k = kind(s.task, s.phase);
        for (String n : new String[] { "task_none", "task_walk", "task_dig_way", "task_build", "task_dig", "task_fight", "task_other" }) v[i++] = b(k.equals(n));
        v[i++] = clip(s.blocks / 16.0);
        v[i++] = b(s.torches > 0);
        v[i++] = b(s.pickaxe);
        v[i++] = b(s.weapon);
        v[i++] = b(s.waterBucket);
        v[i++] = s.light < 0 ? 0 : clip((7 - s.light) / 7.0);   // 0 lit enough, 1 pitch dark
        v[i++] = s.feet != null && s.feet[1] < 60 ? 1 : 0;
        v[i++] = b("PLAYER".equals(s.origin));
        return v;
    }

    /** the situation in a few words (the key the slow level embeds): "fall 3.2, health 14/20, Creeper 3 m, on Scaffold" */
    static String text(Sit s) {
        StringBuilder b = new StringBuilder();
        String by = s.hurtBy == null ? "" : s.hurtBy;
        if (s.inLava) add(b, "in lava");
        if (s.burning) add(b, "burning");
        if (s.underWater) add(b, "head under water");
        else if (s.inWater) add(b, "in water");
        if (s.falling) add(b, "falling " + f1(s.fall));
        else if (s.fall > 0.5) add(b, "fall " + f1(s.fall));
        if (s.hurt) add(b, "hurt by " + (by.isEmpty() ? "something" : by));
        add(b, "health " + Math.round(s.health) + "/" + Math.round(s.max));
        if (!s.onGround && !s.inWater) add(b, "in the air");
        add(b, s.underSolid ? "on " + shortName(s.under) : s.underLiquid ? "over " + shortName(s.under) : "nothing underfoot");
        if (s.collided) add(b, "against a wall");
        if (s.threat != null && !s.threat.isEmpty() && s.threatDist < 1e8) add(b, s.threat + " " + Math.round(s.threatDist) + " m");
        else if (s.mobs > 0) add(b, s.mobs + " mobs seen");
        if (s.target) {
            long up = Math.round(s.dy), far = Math.round(Math.sqrt(s.dx * s.dx + s.dz * s.dz));
            add(b, "target " + (up > 0 ? up + " up" : up < 0 ? -up + " down" : "level") + ", " + far + " away");
        }
        if (s.task != null && !s.task.isEmpty()) add(b, "on " + s.task + (s.phase != null && !s.phase.isEmpty() ? " " + s.phase : ""));
        else add(b, "no task");
        if (s.still >= 20) add(b, "stuck " + (s.still / 20) + " s");
        add(b, s.blocks + " blocks" + (s.torches > 0 ? ", torches" : "") + (s.pickaxe ? ", pickaxe" : "") + (s.weapon ? ", weapon" : "") + (s.waterBucket ? ", water bucket" : ""));
        if (s.light >= 0 && s.light < 7) add(b, "dark");
        if (s.feet != null && s.feet[1] < 60) add(b, "underground");
        return b.toString();
    }

    static void add(StringBuilder b, String s) { if (b.length() > 0) b.append(", "); b.append(s); }
    static String f1(double v) { return String.format(Locale.ROOT, "%.1f", v); }
    static String shortName(String n) { return n == null ? "?" : n.replace("minecraft:", ""); }

    // ---- the journal's form ---------------------------------------------------------------------------

    static JsonObject toJson(Sit s) {
        JsonObject o = new JsonObject();
        o.addProperty("health", s.health);
        o.addProperty("max", s.max);
        o.addProperty("hurt", s.hurt);
        o.addProperty("hurtBy", s.hurtBy == null ? "" : s.hurtBy);
        o.addProperty("fall", round(s.fall));
        o.addProperty("motionY", round(s.motionY));
        o.addProperty("onGround", s.onGround);
        o.addProperty("falling", s.falling);
        o.addProperty("inWater", s.inWater);
        o.addProperty("inLava", s.inLava);
        o.addProperty("burning", s.burning);
        o.addProperty("underWater", s.underWater);
        o.addProperty("collided", s.collided);
        if (s.feet != null) { JsonArray f = new JsonArray(); for (int c : s.feet) f.add(new com.google.gson.JsonPrimitive(c)); o.add("feet", f); }
        o.addProperty("under", s.under == null ? "" : s.under);
        o.addProperty("underSolid", s.underSolid);
        o.addProperty("underLiquid", s.underLiquid);
        o.addProperty("threat", s.threat == null ? "" : s.threat);
        o.addProperty("threatDist", s.threatDist >= 1e8 ? -1 : round(s.threatDist));
        o.addProperty("mobs", s.mobs);
        o.addProperty("hostiles", s.hostiles);
        if (s.target) {
            JsonArray t = new JsonArray();
            for (double c : new double[] { s.dx, s.dy, s.dz }) t.add(new com.google.gson.JsonPrimitive(round(c)));
            o.add("target", t);
        }
        o.addProperty("task", s.task == null ? "" : s.task);
        o.addProperty("phase", s.phase == null ? "" : s.phase);
        o.addProperty("card", s.card == null ? "" : s.card);
        o.addProperty("origin", s.origin == null ? "" : s.origin);
        o.addProperty("still", s.still);
        o.addProperty("blocks", s.blocks);
        o.addProperty("torches", s.torches);
        o.addProperty("pickaxe", s.pickaxe);
        o.addProperty("weapon", s.weapon);
        o.addProperty("waterBucket", s.waterBucket);
        o.addProperty("light", s.light);
        return o;
    }

    static double round(double v) { return Math.round(v * 100) / 100.0; }

    static Sit fromJson(JsonObject o) {
        Sit s = new Sit();
        s.health = (float) num(o, "health", 20);
        s.max = (float) num(o, "max", 20);
        s.hurt = bool(o, "hurt");
        s.hurtBy = str(o, "hurtBy");
        s.fall = (float) num(o, "fall", 0);
        s.motionY = num(o, "motionY", 0);
        s.onGround = !o.has("onGround") || bool(o, "onGround");
        s.falling = bool(o, "falling");
        s.inWater = bool(o, "inWater");
        s.inLava = bool(o, "inLava");
        s.burning = bool(o, "burning");
        s.underWater = bool(o, "underWater");
        s.collided = bool(o, "collided");
        JsonElement f = o.get("feet");
        if (f != null && f.isJsonArray() && f.getAsJsonArray().size() == 3) s.feet = new int[] { f.getAsJsonArray().get(0).getAsInt(), f.getAsJsonArray().get(1).getAsInt(), f.getAsJsonArray().get(2).getAsInt() };
        s.under = str(o, "under");
        if (o.has("underSolid")) { s.underSolid = bool(o, "underSolid"); s.underLiquid = bool(o, "underLiquid"); }
        else {   // the contract's first form: only the block's name
            String u = s.under;
            s.underLiquid = u.contains("water") || u.contains("lava");
            s.underSolid = !u.isEmpty() && !u.endsWith("air") && !s.underLiquid;
        }
        s.threat = str(o, "threat");
        double td = num(o, "threatDist", -1);
        s.threatDist = td < 0 ? 1e9 : td;
        s.mobs = (int) num(o, "mobs", s.threat.isEmpty() ? 0 : 1);
        s.hostiles = (int) num(o, "hostiles", s.threat.isEmpty() ? 0 : 1);
        JsonElement t = o.get("target");
        if (t != null && t.isJsonArray() && t.getAsJsonArray().size() == 3) {
            s.target = true;
            s.dx = t.getAsJsonArray().get(0).getAsDouble();
            s.dy = t.getAsJsonArray().get(1).getAsDouble();
            s.dz = t.getAsJsonArray().get(2).getAsDouble();
        }
        s.task = str(o, "task");
        s.phase = str(o, "phase");
        s.card = str(o, "card");
        s.origin = str(o, "origin");
        s.still = (int) num(o, "still", 0);
        s.blocks = (int) num(o, "blocks", 0);
        s.torches = (int) num(o, "torches", 0);
        s.pickaxe = bool(o, "pickaxe");
        s.weapon = bool(o, "weapon");
        s.waterBucket = bool(o, "waterBucket");
        s.light = (int) num(o, "light", -1);
        return s;
    }

    static double num(JsonObject o, String k, double d) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? d : e.getAsDouble();
    }

    static boolean bool(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }

    static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }
}
