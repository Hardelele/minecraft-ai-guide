package hardelele.aiguide.client;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAIAttackOnCollide;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.monster.EntitySkeleton;
import net.minecraft.entity.monster.EntitySpider;
import net.minecraft.entity.monster.EntityWitch;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.World;

/**
 * The waves mode's mobs: the game's own zombie, skeleton, spider, creeper and witch (their looks, sounds, drops and
 * experience), each a class of its own, which is the wave's mark (kept in the world's save with the run and the wave,
 * AIGuideWave). What they have that the game's do not: they are never despawned; a peaceful world does not take them
 * away (the game removes every monster each tick there: setDead is let through only when the mob is really dead, blew
 * up or is taken away by the waves mode); zombies hit any target (the game's only players and villagers, an agent is
 * neither); spiders stay hostile by day; with burn off (-Daiguide.waves.burn=false) the sun does not set them on fire.
 * The waves mode (Waves) gives them their target or the way to the base.
 */
public class WaveMobs {
    /** a wave's mark on its mob */
    static final class Tag {
        int run, wave;
        boolean burn = true, removing, exploded, counted;

        void write(NBTTagCompound t) {
            NBTTagCompound c = new NBTTagCompound();
            c.setInteger("run", run);
            c.setInteger("wave", wave);
            c.setBoolean("burn", burn);
            t.setTag("AIGuideWave", c);
        }

        void read(NBTTagCompound t) {
            if (!t.hasKey("AIGuideWave")) return;
            NBTTagCompound c = t.getCompoundTag("AIGuideWave");
            run = c.getInteger("run");
            wave = c.getInteger("wave");
            burn = !c.hasKey("burn") || c.getBoolean("burn");
        }
    }

    /** a mob of a wave */
    interface Mob { Tag tag(); }

    /** setDead let through: on the client, a mob that is dead or blew up or is taken away; not the peaceful world's removal */
    static boolean spare(EntityLiving e, Tag t) {
        return !e.worldObj.isRemote && e.worldObj.difficultySetting == EnumDifficulty.PEACEFUL && e.getHealth() > 0 && !t.removing && !t.exploded;
    }

    /** the light the mob feels (on the server): never full sun when burn is off (the game sets fire by it, above 0.5) */
    static float light(EntityLiving e, Tag t, float b) {
        return !e.worldObj.isRemote && !t.burn ? Math.min(b, 0.45f) : b;
    }

    /** a mob taken away by the waves mode (not killed: no drops, no experience) */
    static void remove(EntityLiving e) {
        if (e instanceof Mob) ((Mob) e).tag().removing = true;
        e.setDead();
    }

    public static class Zombie extends EntityZombie implements Mob {
        final Tag tag = new Tag();

        public Zombie(World w) {
            super(w);
            tasks.addTask(2, new EntityAIAttackOnCollide(this, 1.0D, false));   // any target (the game's: players, villagers)
        }

        public Tag tag() { return tag; }
        @Override public void setDead() { if (!spare(this, tag)) super.setDead(); }
        @Override public float getBrightness(float f) { return light(this, tag, super.getBrightness(f)); }
        @Override protected boolean canDespawn() { return false; }
        @Override public void writeEntityToNBT(NBTTagCompound t) { super.writeEntityToNBT(t); tag.write(t); }
        @Override public void readEntityFromNBT(NBTTagCompound t) { super.readEntityFromNBT(t); tag.read(t); }
    }

    public static class Skeleton extends EntitySkeleton implements Mob {
        final Tag tag = new Tag();

        public Skeleton(World w) { super(w); }

        public Tag tag() { return tag; }
        @Override public void setDead() { if (!spare(this, tag)) super.setDead(); }
        @Override public float getBrightness(float f) { return light(this, tag, super.getBrightness(f)); }
        @Override protected boolean canDespawn() { return false; }
        @Override public void writeEntityToNBT(NBTTagCompound t) { super.writeEntityToNBT(t); tag.write(t); }
        @Override public void readEntityFromNBT(NBTTagCompound t) { super.readEntityFromNBT(t); tag.read(t); }
    }

    public static class Spider extends EntitySpider implements Mob {
        final Tag tag = new Tag();

        public Spider(World w) { super(w); }

        public Tag tag() { return tag; }
        @Override public void setDead() { if (!spare(this, tag)) super.setDead(); }
        @Override protected boolean canDespawn() { return false; }
        /** hostile by day too: the crew or the player nearest to it (the game's spider: a player, at night) */
        @Override protected Entity findPlayerToAttack() { return Waves.nearestTarget(this, Waves.SEEK); }
        @Override public void writeEntityToNBT(NBTTagCompound t) { super.writeEntityToNBT(t); tag.write(t); }
        @Override public void readEntityFromNBT(NBTTagCompound t) { super.readEntityFromNBT(t); tag.read(t); }
    }

    public static class Creeper extends EntityCreeper implements Mob {
        final Tag tag = new Tag();

        public Creeper(World w) { super(w); }

        public Tag tag() { return tag; }
        @Override public void setDead() { if (!spare(this, tag)) super.setDead(); }
        @Override protected boolean canDespawn() { return false; }
        @Override public void writeEntityToNBT(NBTTagCompound t) { super.writeEntityToNBT(t); tag.write(t); }
        @Override public void readEntityFromNBT(NBTTagCompound t) { super.readEntityFromNBT(t); tag.read(t); }
    }

    public static class Witch extends EntityWitch implements Mob {
        final Tag tag = new Tag();

        public Witch(World w) { super(w); }

        public Tag tag() { return tag; }
        @Override public void setDead() { if (!spare(this, tag)) super.setDead(); }
        @Override protected boolean canDespawn() { return false; }
        @Override public void writeEntityToNBT(NBTTagCompound t) { super.writeEntityToNBT(t); tag.write(t); }
        @Override public void readEntityFromNBT(NBTTagCompound t) { super.readEntityFromNBT(t); tag.read(t); }
    }

    /** the classes as the mod registers them (AIGuideClient: ids from 10, after the agent's 0) */
    static final Object[][] TYPES = { { Zombie.class, "wave_zombie" }, { Skeleton.class, "wave_skeleton" }, { Spider.class, "wave_spider" },
            { Creeper.class, "wave_creeper" }, { Witch.class, "wave_witch" } };

    /** a new mob of a kind for wave n of run r (not in the world yet): armed, armoured on boss waves, tougher after the 10th */
    static EntityLiving make(WavePlan.Kind kind, World w, int run, int n, boolean burn) {
        EntityLiving e;
        switch (kind) {
            case SKELETON:
                Skeleton s = new Skeleton(w);
                s.setCurrentItemOrArmor(0, new ItemStack(Items.bow));
                s.setCombatTask();   // the bow's attack (the game does it when it spawns one with its egg)
                e = s;
                break;
            case SPIDER: e = new Spider(w); break;
            case CREEPER: e = new Creeper(w); break;
            case WITCH: e = new Witch(w); break;
            case ARMORED_ZOMBIE:
                Zombie a = new Zombie(w);
                boolean late = n >= 15;
                a.setCurrentItemOrArmor(0, new ItemStack(late ? Items.diamond_sword : Items.iron_sword));
                a.setCurrentItemOrArmor(4, new ItemStack(late ? Items.diamond_helmet : Items.iron_helmet));
                a.setCurrentItemOrArmor(3, new ItemStack(Items.iron_chestplate));
                if (n >= 10) a.setCurrentItemOrArmor(2, new ItemStack(Items.iron_leggings));
                a.getEntityAttribute(SharedMonsterAttributes.knockbackResistance).setBaseValue(0.5);
                e = a;
                break;
            default: e = new Zombie(w);
        }
        Tag t = ((Mob) e).tag();
        t.run = run;
        t.wave = n;
        t.burn = burn;
        e.enablePersistence();
        e.getEntityAttribute(SharedMonsterAttributes.followRange).setBaseValue(Waves.SEEK);   // they see the crew from the ring
        double hp = e.getEntityAttribute(SharedMonsterAttributes.maxHealth).getBaseValue() * WavePlan.health(n) * (kind == WavePlan.Kind.ARMORED_ZOMBIE ? 1.5 : 1);
        e.getEntityAttribute(SharedMonsterAttributes.maxHealth).setBaseValue(hp);
        e.setHealth((float) hp);
        return e;
    }
}
