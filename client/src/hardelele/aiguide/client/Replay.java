package hardelele.aiguide.client;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * Playing a recorded session back (WorldRec): the world's copy is opened as a world of its own (its server holds
 * still: no mobs spawn, the time stands, its own entities are taken away, the crew does nothing), and what was
 * recorded is shown on the client: every entity as a puppet where it was (eased between ticks), its arm, what it
 * held, its health, its hurt flash and its death; the blocks as they changed, the cracks, the sounds, the time of day.
 * The cameras are the game's own (the commander's, a follow shot on any agent, the action-ranked director, a path of
 * keyframes) and the recorder films them; while it records, the playback goes by video frames (a 60th of a second
 * each), so the film is even whatever the render speed. Forward only (to see a moment again, open it again).
 */
public class Replay {
    static Replay active;

    static final class E {
        int id, type, name = -1, flags, hurt, item = -1, meta, count, act, ax, ay, az;
        double x, y, z;
        float yaw, pitch, head, health;
    }

    static final class T {
        int tick;
        long time;
        boolean rain;
        final Map<Integer, E> ents = new HashMap<Integer, E>();
        final List<int[]> blocks = new ArrayList<int[]>(), breaks = new ArrayList<int[]>();
        final List<Object[]> sounds = new ArrayList<Object[]>();
    }

    final List<T> ticks = new ArrayList<T>();
    final List<String> strings = new ArrayList<String>();
    final Map<Integer, Entity> puppets = new HashMap<Integer, Entity>();
    final Map<Integer, float[]> limbs = new HashMap<Integer, float[]>();   // id -> { swing, amount }
    final Minecraft mc = Minecraft.getMinecraft();
    double t;          // the playback's time, in recorded ticks
    double speed = 1;
    boolean playing;
    int applied = -1;  // the last tick whose blocks and sounds were applied
    long lastNanos;
    final String name;

    Replay(String name) { this.name = name; }

    static File dirOf(String name) {
        return new File(new File(Minecraft.getMinecraft().mcDataDir, "recordings"), name);
    }

    /** the session's log, read into memory */
    void load() throws Exception {
        DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(new File(dirOf(name), "session.bin.gz")), 1 << 16), 1 << 16));
        if (in.readInt() != 0x41495245) throw new Exception("not a recording");
        in.readInt();
        T cur = null;
        try {
            for (; ; ) {
                int r = in.readByte();
                if (r == 0) break;
                if (r == 6) { int i = in.readShort(); String s = in.readUTF(); while (strings.size() <= i) strings.add(null); strings.set(i, s); }
                else if (r == 1) { cur = new T(); cur.tick = in.readInt(); cur.time = in.readLong(); cur.rain = in.readBoolean(); ticks.add(cur); }
                else if (r == 2) {
                    E e = new E();
                    e.id = in.readInt(); e.type = in.readShort();
                    e.x = in.readDouble(); e.y = in.readDouble(); e.z = in.readDouble();
                    e.yaw = in.readFloat(); e.pitch = in.readFloat(); e.head = in.readFloat();
                    e.flags = in.readByte(); e.health = in.readFloat(); e.hurt = in.readByte();
                    e.item = in.readShort();
                    if (e.item >= 0) { e.meta = in.readShort(); e.count = in.readByte(); }
                    if (strings.get(e.type).equals("agent")) { e.name = in.readShort(); e.act = in.readByte(); e.ax = in.readInt(); e.ay = in.readShort(); e.az = in.readInt(); }
                    cur.ents.put(e.id, e);
                } else if (r == 3) cur.blocks.add(new int[] { in.readInt(), in.readShort(), in.readInt(), in.readShort(), in.readByte() & 255 });
                else if (r == 4) cur.breaks.add(new int[] { in.readInt(), in.readInt(), in.readShort(), in.readInt(), in.readByte() });
                else if (r == 5) cur.sounds.add(new Object[] { in.readShort(), in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat() });
                else throw new Exception("bad record " + r);
            }
        } catch (EOFException eof) {
            // a log cut short (the game closed while recording): what is there plays
        }
        in.close();
    }

    /** the director's "replay <name>": the copy as a world of its own, opened; the log read */
    static String open(String name) {
        try {
            File src = new File(dirOf(name), "world");
            if (!src.isDirectory()) return "no recording " + name;
            String world = "replay-" + name;
            File dst = new File(new File(Minecraft.getMinecraft().mcDataDir, "saves"), world);
            if (dst.exists()) WorldRec.deleteTree(dst);
            WorldRec.copyTree(src, dst);
            Replay r = new Replay(name);
            r.load();
            active = r;
            FMLCommonHandler.instance().bus().register(r);
            Minecraft.getMinecraft().launchIntegratedServer(world, world, null);
            Director.log("replay: " + name + ", " + r.ticks.size() + " ticks (" + r.ticks.size() / 20 + " s)");
            return "ok";
        } catch (Exception e) {
            Director.log("replay: failed " + e);
            return "failed " + e;
        }
    }

    boolean frozen;

    /** the replay world's server holds still: its own entities go (the puppets stand for them), nothing spawns, time stands */
    @SubscribeEvent
    public void server(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || active != this) return;
        net.minecraft.world.WorldServer w = net.minecraft.server.MinecraftServer.getServer().worldServers[0];
        if (!frozen) {
            w.getGameRules().setOrCreateGameRule("doMobSpawning", "false");
            w.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
            w.getGameRules().setOrCreateGameRule("doFireTick", "false");
            w.getGameRules().setOrCreateGameRule("mobGriefing", "false");
            frozen = true;
        }
        for (Object o : new ArrayList<Object>(w.loadedEntityList))
            if (!(o instanceof net.minecraft.entity.player.EntityPlayer)) ((Entity) o).setDead();
        if (Crew.instance != null) Crew.instance.agents.clear();
        for (Object o : w.playerEntities) {   // the player: an invisible flying camera of its own (it is in the recording already)
            net.minecraft.entity.player.EntityPlayerMP p = (net.minecraft.entity.player.EntityPlayerMP) o;
            if (!p.capabilities.allowFlying) { p.capabilities.allowFlying = true; p.capabilities.isFlying = true; p.capabilities.disableDamage = true; p.sendPlayerAbilities(); }
            p.setInvisible(true);
        }
    }

    // ---- every frame: the clock, the puppets -----------------------------------------------------------

    @SubscribeEvent
    public void frame(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.START || active != this || mc.theWorld == null || ticks.isEmpty()) return;
        long now = System.nanoTime();
        double dt = Recorder.instance != null ? 1.0 / 3 : lastNanos == 0 ? 0 : Math.min(0.25, (now - lastNanos) / 5e7);   // in ticks: a video frame is a third of a tick
        lastNanos = now;
        if (playing) t = Math.min(ticks.size() - 1.001, t + dt * speed);
        int i = (int) Math.floor(t);
        double f = t - i;
        while (applied < i) world(ticks.get(++applied));
        T a = ticks.get(i), b = ticks.get(Math.min(ticks.size() - 1, i + 1));
        mc.theWorld.setWorldTime(a.time + (long) ((b.time - a.time) * f));
        mc.theWorld.setRainStrength(a.rain ? 1 : 0);
        for (java.util.Iterator<Map.Entry<Integer, Entity>> it = puppets.entrySet().iterator(); it.hasNext(); ) {   // gone from the record: gone
            Map.Entry<Integer, Entity> en = it.next();
            if (!a.ents.containsKey(en.getKey())) { mc.theWorld.removeEntityFromWorld(en.getValue().getEntityId()); limbs.remove(en.getKey()); it.remove(); }
        }
        for (E ea : a.ents.values()) {
            E eb = b.ents.get(ea.id);
            if (eb == null) eb = ea;
            Entity p = puppets.get(ea.id);
            if (p == null) { p = puppet(ea); if (p == null) continue; }
            pose(p, ea, eb, f, dt * (playing ? speed : 0));
        }
    }

    /** what changed in the world at this tick: blocks, cracks, sounds */
    void world(T k) {
        for (int[] bl : k.blocks) mc.theWorld.setBlock(bl[0], bl[1], bl[2], Block.getBlockById(bl[3]), bl[4], 3);
        for (int[] br : k.breaks) mc.renderGlobal.destroyBlockPartially(br[0], br[1], br[2], br[3], br[4]);
        if (Recorder.instance == null && playing)
            for (Object[] s : k.sounds) mc.theWorld.playSound((Float) s[1], (Float) s[2], (Float) s[3], strings.get((Short) s[0]), (Float) s[4], (Float) s[5], false);
    }

    Entity puppet(E e) {
        String type = strings.get(e.type);
        Entity p;
        if (type.equals("agent")) {
            Agent a = new Agent(mc.theWorld);
            a.setCustomNameTag(strings.get(e.name));
            a.setAlwaysRenderNameTag(true);
            p = a;
        } else if (type.startsWith("player:")) {
            String n = type.substring(7);
            p = new net.minecraft.client.entity.EntityOtherPlayerMP(mc.theWorld, new com.mojang.authlib.GameProfile(java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + n).getBytes()), n));
        } else if (type.equals("Item")) {
            p = new EntityItem(mc.theWorld, e.x, e.y, e.z, e.item >= 0 && Item.getItemById(e.item) != null ? new ItemStack(Item.getItemById(e.item), Math.max(1, e.count), e.meta) : new ItemStack(Block.getBlockById(1)));
            ((EntityItem) p).delayBeforeCanPickup = 1 << 20;
        } else p = EntityList.createEntityByName(type, mc.theWorld);
        if (p == null) return null;
        p.setPosition(e.x, e.y + p.yOffset, e.z);
        mc.theWorld.addEntityToWorld(-100000 - e.id, p);
        puppets.put(e.id, p);
        return p;
    }

    static float lerpAngle(float a, float b, double f) {
        return a + net.minecraft.util.MathHelper.wrapAngleTo180_float(b - a) * (float) f;
    }

    /** a puppet where its entity was, eased between two ticks; its arm, hand, health, hurt flash and walk */
    void pose(Entity p, E a, E b, double f, double dtTicks) {
        double x = a.x + (b.x - a.x) * f, y = a.y + (b.y - a.y) * f + p.yOffset, z = a.z + (b.z - a.z) * f;
        p.setPosition(x, y, z);
        p.prevPosX = p.lastTickPosX = x;
        p.prevPosY = p.lastTickPosY = y;
        p.prevPosZ = p.lastTickPosZ = z;
        p.motionX = p.motionY = p.motionZ = 0;
        p.rotationYaw = p.prevRotationYaw = lerpAngle(a.yaw, b.yaw, f);
        p.rotationPitch = p.prevRotationPitch = a.pitch + (b.pitch - a.pitch) * (float) f;
        if (p instanceof EntityItem && a.item >= 0 && Item.getItemById(a.item) != null) ((EntityItem) p).setEntityItemStack(new ItemStack(Item.getItemById(a.item), Math.max(1, a.count), a.meta));
        if (!(p instanceof EntityLivingBase)) return;
        EntityLivingBase l = (EntityLivingBase) p;
        l.rotationYawHead = l.prevRotationYawHead = lerpAngle(a.head, b.head, f);
        l.renderYawOffset = l.prevRenderYawOffset = l.rotationYaw;
        float[] lm = limbs.get(a.id);
        if (lm == null) limbs.put(a.id, lm = new float[2]);
        double sp = Math.hypot(b.x - a.x, b.z - a.z) * 4;   // as the game counts a walk: a tick's way, times four
        lm[1] += (Math.min(1, sp) - lm[1]) * Math.min(1, 0.4 * dtTicks * 3);
        lm[0] += lm[1] * dtTicks;
        l.limbSwingAmount = l.prevLimbSwingAmount = lm[1];
        l.limbSwing = lm[0];
        if ((a.flags & 1) != 0 && f < 0.34 && !l.isSwingInProgress) l.swingItem();
        ItemStack held = a.item >= 0 && Item.getItemById(a.item) != null ? new ItemStack(Item.getItemById(a.item), Math.max(1, a.count), a.meta) : null;
        ItemStack now = l.getHeldItem();
        if (held == null ? now != null : now == null || now.getItem() != held.getItem() || now.getMetadata() != held.getMetadata()) l.setCurrentItemOrArmor(0, held);
        if (a.health > 0) l.setHealth(a.health);
        else if (l.getHealth() > 0) l.setHealth(0);   // its death: the game's own fall over
        l.hurtTime = a.hurt;
        if (a.hurt > 0) l.maxHurtTime = 10;
        if (p instanceof Agent) {   // its work, for the director's choice of what to film
            Agent ag = (Agent) p;
            ag.replayAct = Agent.Act.values()[Math.max(0, Math.min(Agent.Act.values().length - 1, a.act))];
            ag.actX = a.ax; ag.actY = a.ay; ag.actZ = a.az;
            ag.actAt = ag.ticksExisted;
            ag.actWhat = ag.replayAct;
        }
    }
}
