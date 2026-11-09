package hardelele.aiguide.client;

import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MathHelper;

/**
 * An agent's body between its tasks, each tick on the server (Agent.onLivingUpdate): its hunger as a player's when
 * the agents get hungry (else fed: a point healed every 4 s); the experience orbs about it, drawn to it and picked up
 * as a player's are; the reflex of water under itself when it is in lava or falls from a height with a water bucket
 * in its bag (a player's "bucket clutch": the water is put by the game's own rule, the bucket left empty).
 */
final class AgentLife {
    private AgentLife() { }

    static final double ORB_REACH = 8;
    static final int REFLEX_COOL = 40;

    static void tick(Agent a) {
        hunger(a);
        if (a.ticksExisted % 2 == 0) orbs(a);
        reflex(a);
    }

    static void hunger(Agent a) {
        if (!Agent.hungerOn) {   // no hunger: fed, a point every 4 s
            if (a.getHealth() < a.getMaxHealth() && a.ticksExisted % 80 == 0) a.heal(1.0F);
            return;
        }
        double dx = a.posX - a.prevPosX, dy = a.posY - a.prevPosY, dz = a.posZ - a.prevPosZ;
        a.running = Math.hypot(dx, dz) > Hunger.RUN_SPEED;
        if (a.ridingEntity == null) a.hunger.moved(dx, dy, dz, a.isInsideOfMaterial(Material.water), a.isInWater(), a.onGround);
        int r = a.hunger.tick(a.getHealth(), a.getMaxHealth(), 0);
        if (r == Hunger.HEAL) a.heal(1.0F);
        else if (r == Hunger.STARVE) {
            a.attackEntityFrom(DamageSource.starve, 1.0F);
            if (a.hunger.starvedDamage % 5 == 1) Events.log("hunger: " + a.getCustomNameTag() + " starves (" + a.hunger.line() + ", health " + Math.round(a.getHealth()) + ")");
        }
    }

    /** the orbs within 8 drift to it (EntityXPOrb's pull, the player's), those that touch it are its */
    @SuppressWarnings("unchecked")
    static void orbs(Agent a) {
        List<EntityXPOrb> l = a.worldObj.getEntitiesWithinAABB(EntityXPOrb.class, a.boundingBox.expand(ORB_REACH, ORB_REACH / 2, ORB_REACH));
        for (EntityXPOrb o : l) {
            if (o.isDead) continue;
            double dx = a.posX - o.posX, dy = a.posY + a.getEyeHeight() / 2.0 - o.posY, dz = a.posZ - o.posZ;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz) / ORB_REACH;
            if (d < 1) {
                double pull = (1 - d) * (1 - d);
                o.motionX += dx / (d * ORB_REACH + 1e-3) * pull * 0.1;
                o.motionY += dy / (d * ORB_REACH + 1e-3) * pull * 0.1;
                o.motionZ += dz / (d * ORB_REACH + 1e-3) * pull * 0.1;
            }
            if (o.boundingBox.intersectsWith(a.boundingBox.expand(1.0, 0.5, 1.0)) && o.ticksExisted > 2) {
                a.xp.add(o.getXpValue());
                a.worldObj.playSoundAtEntity(a, "random.orb", 0.1F, 0.5F * ((a.getRNG().nextFloat() - a.getRNG().nextFloat()) * 0.7F + 1.8F));
                o.setDead();
                RlEnv.xpPicked(a, o.getXpValue());
            }
        }
    }

    // ---- the water reflex ------------------------------------------------------------------------------------------

    static ItemStack waterBucket(Agent a) {
        for (ItemStack s : a.bag) if (s != null && s.getItem() == Items.water_bucket) return s;
        return null;
    }

    /** in lava, or falling from 5 or more with the ground 2 or less under it: water at the feet (or just over the ground) */
    static void reflex(Agent a) {
        boolean lava = a.handleLavaMovement();
        boolean falling = a.fallDistance >= 5 && a.motionY < -0.3;
        if (lava || falling) RlEnv.danger(a, lava ? "lava" : "fall");
        if (!lava && !falling) return;
        if (a.ticksExisted - a.reflexAt < REFLEX_COOL) return;
        ItemStack b = waterBucket(a);
        if (b == null) return;
        int x = MathHelper.floor_double(a.posX), z = MathHelper.floor_double(a.posZ), y = MathHelper.floor_double(a.boundingBox.minY);
        if (falling && !lava) {   // the ground under it: water on it when it is near
            int g = -1;
            for (int k = 0; k <= 3 && g < 0; k++) if (a.worldObj.getBlock(x, y - k - 1, z).getMaterial().isSolid()) g = y - k;
            if (g < 0 || y - g > 2) return;
            y = g;
        }
        Block at = a.worldObj.getBlock(x, y, z);
        if (at.getMaterial().isSolid()) return;   // (as ItemBucket.tryPlaceContainedLiquid: only into what is not solid)
        a.reflexAt = a.ticksExisted;
        a.worldObj.setBlock(x, y, z, Blocks.flowing_water, 0, 3);
        a.worldObj.playSoundEffect(x + 0.5, y + 0.5, z + 0.5, "random.splash", 0.4F, 1.0F);
        a.swingItem();
        long made = RlCauseHooks.tagOf(b);
        a.bag.remove(b);
        a.bag.add(new ItemStack(Items.bucket));
        if (lava) a.extinguish();
        Ledger.act(a, "water under itself (" + (lava ? "lava" : "a fall") + ")");
        Events.log("crew: " + a.getCustomNameTag() + " puts water under itself (" + (lava ? "in lava" : "falling " + Math.round(a.fallDistance)) + ") at " + x + " " + y + " " + z);
        RlEnv.reflexWater(a, x, y, z, made);
    }
}
