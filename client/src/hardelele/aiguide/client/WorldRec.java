package hardelele.aiguide.client;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTSizeTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.IWorldAccess;
import net.minecraft.world.WorldServer;

/**
 * The world's recording, for filming afterwards (Replay): when it starts, the world is saved and copied as it is;
 * then every tick, around the player, every entity's state (where, how it is turned, the arm swinging, what it holds,
 * its health and hurt flash; an agent's work) and every block that changed, the cracks of a block being broken and
 * the sounds. From the copy and this log the session can be played back and filmed from any camera, on any agent.
 * What is not in it: particles, the sky's own weather effects beyond rain on/off, chat and GUI, other dimensions,
 * distant non-crew entities outside both recording radii, and chunks the game did not have loaded.
 * coverage.json reports missing terrain in the initial snapshot; it never silently invents that terrain.
 */
public class WorldRec implements IWorldAccess {
    static final int VERSION = 1;
    static final double R = 128;
    static final double CREW_R = 24;
    // records: 1 tick, 2 entity, 3 block, 4 break progress, 5 sound, 6 a string for the table
    static volatile WorldRec active;
    static volatile boolean closing;
    static volatile String closeError;

    /** Null active alone means capture stopped accepting events, not that its files are closed. */
    static boolean stopped() { return active == null && !closing; }

    final File dir;
    final DataOutputStream out;
    final WorldServer w;
    final Map<String, Integer> strings = new HashMap<String, Integer>();
    final Set<Long> changed = new LinkedHashSet<Long>();
    final List<Object[]> sounds = new ArrayList<Object[]>(), breaks = new ArrayList<Object[]>();
    int ticks;
    long bytesAtStart;
    final Map<String, String> snapshotChunks = new HashMap<String, String>();
    final Map<String, JsonObject> heroChunks = new HashMap<String, JsonObject>();
    final Set<String> unavailableHeroes = new LinkedHashSet<String>();
    boolean coverageTruncated, coverageDirty = true;

    WorldRec(File dir, WorldServer w) throws Exception {
        this.dir = dir;
        this.w = w;
        out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(new File(dir, "session.bin.gz")), 1 << 16), 1 << 16));
        out.writeInt(0x41495245);   // "AIRE"
        out.writeInt(VERSION);
        writeCoverage(false);
    }

    /** on the server's thread: the world saved and copied, the log started */
    static String start(String name) {
        try {
            if (active != null || closing) return "already recording or closing";
            closeError = null;
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
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(r);
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
        closing = true;
        active = null;
        try {
            FMLCommonHandler.instance().bus().unregister(r);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(r);
            r.w.removeWorldAccess(r);
            r.out.writeByte(0);
            r.out.close();
            r.writeCoverage(true);
            long log = new File(r.dir, "session.bin.gz").length();
            Events.log("worldrec: stop after " + r.ticks + " ticks (" + r.ticks / 20 + " s); log " + log / 1024 + " KB (" + (r.ticks == 0 ? 0 : log * 20 * 3600 / r.ticks / 1024 / 1024)
                    + " MB an hour at this rate), world copy " + size(new File(r.dir, "world")) / 1024 / 1024 + " MB");
        } catch (Exception e) {
            closeError = e.toString();
            Events.log("worldrec: stop failed " + e);
            try { r.out.close(); } catch (Exception ignored) { }
        } finally {
            closing = false; // volatile publication only after gzip/footer and coverage close complete
        }
        return closeError == null ? "ok" : "failed " + closeError;
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
            int[] base = Crew.instance == null ? null : Crew.instance.base;   // no player (the headless bench): around the crew's base
            List<Agent> heroes = new ArrayList<Agent>();
            Crew crew = Crew.instance;
            if (crew != null) for (Agent a : crew.agents) if (!a.isDead && a.worldObj == w) {
                if (w.getEntityByID(a.getEntityId()) == a && w.loadedEntityList.contains(a)) heroes.add(a);
                else { unavailableHeroes.add(a.getUniqueID().toString()); coverageDirty = true; }
            }
            for (Object o : w.loadedEntityList) {
                Entity en = (Entity) o;
                if (en.isDead && !(en instanceof EntityLivingBase)) continue;
                if (!includeEntity(en, center, base, heroes)) continue;
                if (en instanceof Agent && !en.isDead) cover((Agent) en);
                entity(en);
            }
            if (ticks % 100 == 0 && coverageDirty) writeCoverage(false);
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

    static boolean includeEntity(Entity en, Entity center, int[] base, List<Agent> heroes) {
        if (en instanceof Agent) return true; // record remote bodies and death frames, independently of camera/player
        if (center != null && en.getDistanceSqToEntity(center) <= R * R) return true;
        if (center == null && (base == null || en.getDistanceSq(base[0], base[1], base[2]) <= 64 * 64)) return true;
        for (Agent hero : heroes) if (en.getDistanceSqToEntity(hero) <= CREW_R * CREW_R) return true;
        return false;
    }

    void cover(Agent a) {
        int cx = ((int) Math.floor(a.posX)) >> 4, cz = ((int) Math.floor(a.posZ)) >> 4;
        String coordinate = cx + "," + cz, key = a.getUniqueID() + ":" + coordinate;
        JsonObject entry = heroChunks.get(key);
        if (entry == null) {
            if (heroChunks.size() >= 8192) { coverageTruncated = true; coverageDirty = true; return; }
            String state = snapshotChunks.get(coordinate);
            if (state == null) {
                state = snapshotChunk(new File(dir, "world"), cx, cz);
                snapshotChunks.put(coordinate, state);
            }
            entry = new JsonObject(); entry.addProperty("uuid", a.getUniqueID().toString()); entry.addProperty("skin", a.skin());
            entry.addProperty("chunk_x", cx); entry.addProperty("chunk_z", cz); entry.addProperty("first_tick", ticks);
            entry.addProperty("initial_snapshot", state); heroChunks.put(key, entry);
            if (!state.equals("present")) Events.log("worldrec: terrain coverage " + state + " hero " + a.skin() + " chunk " + coordinate);
        }
        entry.addProperty("last_tick", ticks); coverageDirty = true;
    }

    void writeCoverage(boolean finished) throws Exception {
        JsonObject report = new JsonObject(); report.addProperty("version", 1); report.addProperty("finished", finished);
        report.addProperty("ticks", ticks); report.addProperty("entity_scope", "all loaded agents; player radius128; loaded entities within24 of live crew");
        report.addProperty("terrain_scope", "initial snapshot, current hero chunk only; camera surroundings and later terrain generation are not verified");
        report.addProperty("full_camera_terrain_coverage_verified", false);
        report.addProperty("coverage_limit_reached", coverageTruncated);
        JsonArray chunks = new JsonArray(), missing = new JsonArray(); boolean present = !heroChunks.isEmpty() && !coverageTruncated;
        for (JsonObject entry : heroChunks.values()) { chunks.add(entry); if (!entry.get("initial_snapshot").getAsString().equals("present")) present = false; }
        for (String uuid : unavailableHeroes) missing.add(new com.google.gson.JsonPrimitive(uuid));
        report.addProperty("all_observed_hero_chunks_present", present);
        report.addProperty("all_live_crew_entities_available", unavailableHeroes.isEmpty());
        report.add("hero_chunks", chunks); report.add("heroes_missing_from_loaded_world", missing);
        File temp = new File(dir, "coverage.json.tmp");
        Files.write(temp.toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(report).getBytes(StandardCharsets.UTF_8));
        Files.move(temp.toPath(), new File(dir, "coverage.json").toPath(), StandardCopyOption.REPLACE_EXISTING);
        coverageDirty = false;
    }

    /** Read only the copied region. Never creates a region file, loads live chunks or instantiates saved entities. */
    static String snapshotChunk(File world, int cx, int cz) {
        final int cap = 8 * 1024 * 1024;
        File region = new File(new File(world, "region"), "r." + (cx >> 5) + "." + (cz >> 5) + ".mca");
        if (!region.isFile()) return "missing_region";
        try (RandomAccessFile file = new RandomAccessFile(region, "r")) {
            if (file.length() < 8192) return "invalid_region_header";
            file.seek(4L * ((cx & 31) + ((cz & 31) << 5)));
            int location = file.readInt(), sector = location >>> 8, sectors = location & 255;
            if (location == 0) return "missing_chunk";
            if (sector < 2 || sectors == 0 || ((long) sector + sectors) * 4096 > file.length()) return "invalid_chunk_location";
            file.seek((long) sector * 4096); int length = file.readInt(), compression = file.readUnsignedByte();
            if (length <= 1 || length > sectors * 4096 - 4 || length > cap) return "invalid_chunk_length";
            byte[] bytes = new byte[length - 1]; file.readFully(bytes);
            if (compression != 1 && compression != 2) return "unsupported_compression";
            try (InputStream in = compression == 1 ? new GZIPInputStream(new ByteArrayInputStream(bytes)) : new InflaterInputStream(new ByteArrayInputStream(bytes))) {
                ByteArrayOutputStream raw = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) != -1) { if (raw.size() + n > cap) return "chunk_nbt_too_large"; raw.write(buf, 0, n); }
                NBTTagCompound root = CompressedStreamTools.func_152456_a(new DataInputStream(new ByteArrayInputStream(raw.toByteArray())), new NBTSizeTracker(cap));
                if (!root.hasKey("Level", 10)) return "invalid_chunk_nbt";
                NBTTagCompound level = root.getCompoundTag("Level");
                if (!level.hasKey("xPos", 3) || !level.hasKey("zPos", 3) || level.getInteger("xPos") != cx || level.getInteger("zPos") != cz) return "chunk_coordinates_mismatch";
                if (!level.hasKey("Sections", 9) || !level.getBoolean("TerrainPopulated")) return "incomplete_terrain";
                net.minecraft.nbt.NBTTagList sections = (net.minecraft.nbt.NBTTagList) level.getTag("Sections");
                if (sections.tagCount() > 16 || sections.tagCount() > 0 && sections.getTagType() != 10) return "invalid_terrain_sections";
                Set<Integer> heights = new LinkedHashSet<Integer>();
                for (int i = 0; i < sections.tagCount(); i++) {
                    NBTTagCompound section = sections.getCompoundTagAt(i); int sy = section.getByte("Y") & 255;
                    if (!section.hasKey("Y", 1) || sy > 15 || !heights.add(sy) || section.getByteArray("Blocks").length != 4096
                            || section.getByteArray("Data").length != 2048 || section.hasKey("Add") && section.getByteArray("Add").length != 2048)
                        return "invalid_terrain_sections";
                }
                return "present";
            }
        } catch (Exception ex) { return "unreadable_chunk_" + ex.getClass().getSimpleName(); }
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

    /**
     * A block broken or put by anyone with hands (players, the agents' hands): also known this way. The world tells its
     * listeners of a change only in chunks a player keeps ticking (a headless server has none): breaking and placing
     * are the changes that matter, and these events come everywhere.
     */
    @SubscribeEvent
    public void broken(net.minecraftforge.event.world.BlockEvent.BreakEvent e) {
        if (active == this && e.world == w) changed.add(key(e.x, e.y, e.z));
    }

    @SubscribeEvent
    public void placed(net.minecraftforge.event.world.BlockEvent.PlaceEvent e) {
        if (active == this && e.world == w) changed.add(key(e.x, e.y, e.z));
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
