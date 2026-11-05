package hardelele.aiguide.client;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.IWorldAccess;
import net.minecraft.world.WorldServer;

/**
 * The world's recording, for filming afterwards (Replay): when it starts, the world is saved and copied as it is;
 * then every tick, around the player, every entity's state (where, how it is turned, the arm swinging, what it holds,
 * its health and hurt flash; an agent's work) and every block that changed, the cracks of a block being broken and
 * the sounds. From the copy and this log the session can be played back and filmed from any camera, on any agent.
 * What is not in it: particles, the sky's own weather effects beyond rain on/off, chat and GUI, other dimensions,
 * anything farther than R from the player, and chunks the game did not have loaded.
 */
public class WorldRec implements IWorldAccess {
    static final int VERSION = 1;
    static final double R = 128;
    // records: 1 tick, 2 entity, 3 block, 4 break progress, 5 sound, 6 a string for the table
    static WorldRec active;

    final File dir;
    final DataOutputStream out;
    final WorldServer w;
    final Map<String, Integer> strings = new HashMap<String, Integer>();
    final Set<Long> changed = new LinkedHashSet<Long>();
    final List<Object[]> sounds = new ArrayList<Object[]>(), breaks = new ArrayList<Object[]>();
    int ticks;
    long bytesAtStart;

    WorldRec(File dir, WorldServer w) throws Exception {
        this.dir = dir;
        this.w = w;
        out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(new File(dir, "session.bin.gz")), 1 << 16), 1 << 16));
        out.writeInt(0x41495245);   // "AIRE"
        out.writeInt(VERSION);
    }

    /** on the server's thread: the world saved and copied, the log started */
    static String start(String name) {
        try {
            if (active != null) return "already recording";
            MinecraftServer s = MinecraftServer.getServer();
            WorldServer w = s.worldServers[0];
            s.getConfigurationManager().saveAllPlayerData();
            w.saveAllChunks(true, null);
            w.flush();
            File src = w.getSaveHandler().getWorldDirectory();
            File dir = new File(home(), name);
            if (dir.exists()) deleteTree(dir);
            dir.mkdirs();
            copyTree(src, new File(dir, "world"));
            WorldRec r = new WorldRec(dir, w);
            w.addWorldAccess(r);
            FMLCommonHandler.instance().bus().register(r);
            active = r;
            Events.log("worldrec: start " + dir + " (world copied: " + size(new File(dir, "world")) / 1024 / 1024 + " MB)");
            return "ok";
        } catch (Exception e) {
            Events.log("worldrec: failed " + e);
            return "failed " + e;
        }
    }

    /** where recordings go: the game's folder (a client), the server's folder (the headless bench), or -Daiguide.recordings */
    static File home() {
        String p = System.getProperty("aiguide.recordings");
        if (p != null) return new File(p);
        if (FMLCommonHandler.instance().getSide().isClient()) return new File(ClientDir.get(), "recordings");
        return new File("recordings").getAbsoluteFile();
    }

    static String stop() {
        WorldRec r = active;
        if (r == null) return "not recording";
        active = null;
        try {
            FMLCommonHandler.instance().bus().unregister(r);
            r.w.removeWorldAccess(r);
            r.out.writeByte(0);
            r.out.close();
            long log = new File(r.dir, "session.bin.gz").length();
            Events.log("worldrec: stop after " + r.ticks + " ticks (" + r.ticks / 20 + " s); log " + log / 1024 + " KB (" + (r.ticks == 0 ? 0 : log * 20 * 3600 / r.ticks / 1024 / 1024)
                    + " MB an hour at this rate), world copy " + size(new File(r.dir, "world")) / 1024 / 1024 + " MB");
        } catch (Exception e) {
            Events.log("worldrec: stop failed " + e);
        }
        return "ok";
    }

    int str(String s) throws Exception {
        Integer i = strings.get(s);
        if (i != null) return i;
        i = strings.size();
        strings.put(s, i);
        out.writeByte(6);
        out.writeShort(i);
        out.writeUTF(s);
        return i;
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || active != this) return;
        try {
            ticks++;
            out.writeByte(1);
            out.writeInt(ticks);
            out.writeLong(w.getWorldTime());
            out.writeBoolean(w.isRaining());
            Entity center = w.playerEntities.isEmpty() ? null : (Entity) w.playerEntities.get(0);
            for (Object o : w.loadedEntityList) {
                Entity en = (Entity) o;
                if (en.isDead && !(en instanceof EntityLivingBase)) continue;
                if (center != null && en.getDistanceSqToEntity(center) > R * R) continue;
                entity(en);
            }
            for (long k : changed) {
                int x = (int) (k >> 38), y = (int) (k << 26 >> 52), z = (int) (k << 38 >> 38);
                Block b = w.getBlock(x, y, z);
                out.writeByte(3);
                out.writeInt(x); out.writeShort(y); out.writeInt(z);
                out.writeShort(Block.getIdFromBlock(b));
                out.writeByte(w.getBlockMetadata(x, y, z));
            }
            changed.clear();
            for (Object[] b : breaks) {
                out.writeByte(4);
                out.writeInt((Integer) b[0]); out.writeInt((Integer) b[1]); out.writeShort((Integer) b[2]); out.writeInt((Integer) b[3]); out.writeByte((Integer) b[4]);
            }
            breaks.clear();
            for (Object[] s : sounds) {
                int si = str((String) s[0]);
                out.writeByte(5);
                out.writeShort(si);
                out.writeFloat(((Double) s[1]).floatValue()); out.writeFloat(((Double) s[2]).floatValue()); out.writeFloat(((Double) s[3]).floatValue());
                out.writeFloat((Float) s[4]); out.writeFloat((Float) s[5]);
            }
            sounds.clear();
        } catch (Exception ex) {
            Events.log("worldrec: write failed, stopped: " + ex);
            stop();
        }
    }

    /** one entity this tick */
    void entity(Entity en) throws Exception {
        String type = en instanceof Agent ? "agent" : en instanceof EntityPlayer ? "player:" + en.getCommandSenderName() : EntityList.getEntityString(en);
        if (type == null) return;
        int ti = str(type), ni = en instanceof Agent ? str(((Agent) en).getCustomNameTag()) : -1;   // the strings first: a record is never cut by another
        out.writeByte(2);
        out.writeInt(en.getEntityId());
        out.writeShort(ti);
        out.writeDouble(en.posX); out.writeDouble(en.boundingBox.minY); out.writeDouble(en.posZ);
        out.writeFloat(en.rotationYaw); out.writeFloat(en.rotationPitch);
        int flags = 0;
        ItemStack held = null;
        float health = 0, head = en.rotationYaw;
        int hurt = 0;
        if (en instanceof EntityLivingBase) {
            EntityLivingBase l = (EntityLivingBase) en;
            if (l.isSwingInProgress && l.swingProgressInt == 0) flags |= 1;   // a swing starts this tick
            held = l.getHeldItem();
            health = l.getHealth();
            hurt = l.hurtTime;
            head = l.rotationYawHead;
        }
        if (en.isSneaking()) flags |= 2;
        if (en.isBurning()) flags |= 4;
        if (en.isDead || en instanceof EntityLivingBase && ((EntityLivingBase) en).getHealth() <= 0) flags |= 8;
        if (en instanceof EntityItem) held = ((EntityItem) en).getEntityItem();
        out.writeFloat(head);
        out.writeByte(flags);
        out.writeFloat(health);
        out.writeByte(hurt);
        item(held);
        if (en instanceof Agent) {   // its name and its work (the camera's choice in the playback)
            Agent a = (Agent) en;
            out.writeShort(ni);
            out.writeByte(a.activity().ordinal());
            out.writeInt(a.actX); out.writeShort(a.actY); out.writeInt(a.actZ);
        }
    }

    void item(ItemStack s) throws Exception {
        if (s == null || s.getItem() == null) { out.writeShort(-1); return; }
        out.writeShort(Item.getIdFromItem(s.getItem()));
        out.writeShort(s.getMetadata());
        out.writeByte(Math.min(127, s.stackSize));
    }

    static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) y & 0xFFF) << 26 | ((long) z & 0x3FFFFFF);
    }

    // ---- what the world tells its listeners -------------------------------------------------------------
    @Override public void markBlockForUpdate(int x, int y, int z) { changed.add(key(x, y, z)); }
    @Override public void markBlockForRenderUpdate(int x, int y, int z) { }
    @Override public void markBlockRangeForRenderUpdate(int a, int b, int c, int d, int e, int f) { }
    @Override public void playSound(String s, double x, double y, double z, float v, float p) { sounds.add(new Object[] { s, x, y, z, v, p }); }
    @Override public void playSoundToNearExcept(EntityPlayer pl, String s, double x, double y, double z, float v, float p) { sounds.add(new Object[] { s, x, y, z, v, p }); }
    @Override public void spawnParticle(String s, double x, double y, double z, double a, double b, double c) { }
    @Override public void onEntityCreate(Entity e) { }
    @Override public void onEntityDestroy(Entity e) { }
    @Override public void playRecord(String s, int x, int y, int z) { }
    @Override public void broadcastSound(int a, int x, int y, int z, int d) { }
    @Override public void playAuxSFX(EntityPlayer p, int a, int x, int y, int z, int d) { }
    @Override public void destroyBlockPartially(int id, int x, int y, int z, int progress) { breaks.add(new Object[] { id, x, y, z, progress }); }
    @Override public void onStaticEntitiesChanged() { }

    // ---- files -----------------------------------------------------------------------------------------
    static void copyTree(File from, File to) throws Exception {
        if (from.isDirectory()) {
            to.mkdirs();
            String[] names = from.list();
            if (names != null) for (String n : names) if (!n.equals("session.lock")) copyTree(new File(from, n), new File(to, n));
        } else java.nio.file.Files.copy(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    static void deleteTree(File f) {
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) deleteTree(c);
        f.delete();
    }

    static long size(File f) {
        if (f.isFile()) return f.length();
        long n = 0;
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) n += size(c);
        return n;
    }
}
