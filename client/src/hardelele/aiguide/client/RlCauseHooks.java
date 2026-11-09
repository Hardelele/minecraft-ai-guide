package hardelele.aiguide.client;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.IMerchant;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MathHelper;
import net.minecraft.world.IWorldAccess;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

/**
 * The game's side of the causes (RlCause, docs/rl/CONTRACT-v2.md 2.1) in the learning environment: the world's
 * listener sees every block set (a liquid flowing, fire spreading, a block gone) and an entity born; the hurts and
 * deaths of living things are traced back through their damage source (an agent's hit or arrow, its weapon's mark;
 * lava, fire, water, a block where the victim is: their marks; the burning it carries); an item an agent made carries
 * its decision in its NBT (only things that do not stack, so that a stack never splits by its marks).
 */
public final class RlCauseHooks implements IWorldAccess {
    static final String TAG = "aiguideCause";

    static RlCauseHooks hooks;

    /** the hooks on the world and on the events (once) */
    static void start(World w) {
        if (hooks != null) return;
        hooks = new RlCauseHooks(w);
        w.addWorldAccess(hooks);
        MinecraftForge.EVENT_BUS.register(hooks);
    }

    final World w;
    RlCauseHooks(World w) { this.w = w; }

    // ---- the items' marks -----------------------------------------------------------------------------------------

    /** the decision that made it into the item (things that stack are left as they are) */
    static void tag(ItemStack s, long decision) {
        if (s == null || s.getMaxStackSize() > 1 || decision <= 0) return;
        if (!s.hasTagCompound()) s.setTagCompound(new NBTTagCompound());
        s.getTagCompound().setLong(TAG, decision);
    }

    /** the decision that made it, 0 none */
    static long tagOf(ItemStack s) {
        return s != null && s.hasTagCompound() && s.getTagCompound().hasKey(TAG) ? s.getTagCompound().getLong(TAG) : 0;
    }

    // ---- the world's listener -------------------------------------------------------------------------------------

    @Override
    public void markBlockForUpdate(int x, int y, int z) {
        RlEnv e = RlEnv.instance;
        if (e == null || e.state == RlEnv.State.IDLE) return;
        Object n = Block.blockRegistry.getNameForObject(w.getBlock(x, y, z));
        e.cause.changed(RlCause.key(x, y, z), n == null ? "?" : n.toString(), new long[] { RlCause.key(x + 1, y, z), RlCause.key(x - 1, y, z),
                RlCause.key(x, y + 1, z), RlCause.key(x, y - 1, z), RlCause.key(x, y, z + 1), RlCause.key(x, y, z - 1) });
    }

    @Override
    public void onEntityCreate(Entity en) {
        RlEnv e = RlEnv.instance;
        if (e == null || e.state == RlEnv.State.IDLE) return;
        if (en instanceof EntityAnimal && ((EntityAnimal) en).isChild()) e.born((EntityAnimal) en);
    }

    @Override public void markBlockForRenderUpdate(int x, int y, int z) { }
    @Override public void markBlockRangeForRenderUpdate(int a, int b, int c, int d, int e, int f) { }
    @Override public void playSound(String s, double x, double y, double z, float v, float p) { }
    @Override public void playSoundToNearExcept(EntityPlayer pl, String s, double x, double y, double z, float v, float p) { }
    @Override public void spawnParticle(String s, double x, double y, double z, double a, double b, double c) { }
    @Override public void onEntityDestroy(Entity e) { }
    @Override public void playRecord(String s, int x, int y, int z) { }
    @Override public void broadcastSound(int a, int x, int y, int z, int d) { }
    @Override public void playAuxSFX(EntityPlayer p, int a, int x, int y, int z, int d) { }
    @Override public void destroyBlockPartially(int id, int x, int y, int z, int progress) { }
    @Override public void onStaticEntitiesChanged() { }

    // ---- hurts and deaths -----------------------------------------------------------------------------------------

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void hurt(LivingHurtEvent ev) {
        RlEnv e = RlEnv.instance;
        if (e == null || e.state == RlEnv.State.IDLE || ev.entityLiving.worldObj.isRemote || ev.isCanceled()) return;
        List<RlCause.Link> chain = chain(e.cause, ev.source, ev.entityLiving);
        e.effect(ev.entityLiving, kind(ev.entityLiving, false), chain, ev.ammount, ev.source);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void death(LivingDeathEvent ev) {
        RlEnv e = RlEnv.instance;
        if (e == null || e.state == RlEnv.State.IDLE || ev.entityLiving.worldObj.isRemote || ev.isCanceled()) return;
        List<RlCause.Link> chain = chain(e.cause, ev.source, ev.entityLiving);
        e.effect(ev.entityLiving, kind(ev.entityLiving, true), chain, 0, ev.source);
        e.cause.burning.remove(ev.entityLiving.getEntityId());
    }

    /** what the effect is: on an agent, a mob, an animal; a hurt or a death */
    static String kind(EntityLivingBase v, boolean dead) {
        String who = v instanceof Agent ? "agent" : v instanceof IMob ? "mob" : v instanceof EntityAnimal ? "animal" : v instanceof IMerchant ? "villager" : "other";
        return who + (dead ? "_killed" : "_hurt");
    }

    /** the agent whose hit this is: the agent, its hands (FakePlayer "[Agent N]"), the shooter of its arrow */
    static Agent agentOf(Entity src) {
        if (src instanceof Agent) return (Agent) src;
        if (src instanceof EntityPlayer && Crew.instance != null) {
            String n = src.getCommandSenderName();
            if (n != null && n.startsWith("[Agent "))
                for (Agent a : Crew.instance.agents) if (n.equals("[Agent " + a.skin() + "]")) return a;
        }
        return null;
    }

    /** the chain of a damage: the hit (and the weapon's maker), or the marks of the lava, fire, water, block it is in, or of its burning */
    static List<RlCause.Link> chain(RlCause c, DamageSource s, EntityLivingBase v) {
        List<RlCause.Link> l = new ArrayList<RlCause.Link>();
        Agent a = agentOf(s.getEntity());
        if (a != null) {
            l.add(c.now(RlEnv.id(a)) == null ? null : c.now(RlEnv.id(a)).via(s.isProjectile() ? "shot" : "hit"));
            ItemStack held = a.getHeldItem();
            long t = tagOf(held);
            if (t > 0) l.add(c.item(t, MindSkills.id(held)));
            if (s.isProjectile()) { long b = tagOf(DefenseSkills.bow(a)); if (b > 0) l.add(c.item(b, "bow")); }
        } else {
            String type = s.getDamageType();
            if (type.equals("lava") || type.equals("inFire") || type.equals("drown") || type.equals("inWall") || type.equals("cactus")) {
                RlCause.Link m = markIn(c, v, type);
                l.add(m);
                if (m != null && (type.equals("lava") || type.equals("inFire"))) c.burning.put(v.getEntityId(), m);
            } else if (type.equals("onFire")) l.add(c.burning.get(v.getEntityId()));
        }
        return RlCause.chain(l.toArray(new RlCause.Link[0]));
    }

    /** the mark of the block (of the kind that hurts so) the victim is in: lava or fire round its body, water at its eyes, a block at its head */
    static RlCause.Link markIn(RlCause c, EntityLivingBase v, String type) {
        int x0 = MathHelper.floor_double(v.boundingBox.minX), x1 = MathHelper.floor_double(v.boundingBox.maxX);
        int y0 = MathHelper.floor_double(v.boundingBox.minY) - 1, y1 = MathHelper.floor_double(v.boundingBox.maxY);
        int z0 = MathHelper.floor_double(v.boundingBox.minZ), z1 = MathHelper.floor_double(v.boundingBox.maxZ);
        if (type.equals("drown") || type.equals("inWall")) {
            int y = MathHelper.floor_double(v.posY + v.getEyeHeight());
            return c.at(RlCause.key(MathHelper.floor_double(v.posX), y, MathHelper.floor_double(v.posZ)));
        }
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++) {
                    RlCause.Link m = c.at(RlCause.key(x, y, z));
                    if (m != null) return m;
                }
        return null;
    }
}
