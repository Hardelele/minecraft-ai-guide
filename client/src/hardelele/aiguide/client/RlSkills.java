package hardelele.aiguide.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.passive.EntityChicken;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.passive.EntitySheep;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.WorldServer;

/**
 * The skills the learning environment's actions need that the minds had none of: hunt (an animal it sees, a player's
 * hits, its drops picked up), the frontier (where the crew has not looked yet: Exploration's own choice), the food of
 * the bag or the chest by kind. As MindSkills' do they end OK or with a reason code and words.
 */
final class RlSkills {
    private RlSkills() { }

    static final double SEE = 24;

    /** the farm animals near it that it sees, nearest first */
    @SuppressWarnings("unchecked")
    static List<EntityLiving> animals(Agent a, WorldServer w, double r) {
        List<EntityLiving> out = new ArrayList<EntityLiving>();
        for (Object o : w.getEntitiesWithinAABB(EntityLiving.class, AxisAlignedBB.getBoundingBox(a.posX - r, a.posY - 8, a.posZ - r, a.posX + r, a.posY + 8, a.posZ + r))) {
            EntityLiving e = (EntityLiving) o;
            if (!(e instanceof EntityCow || e instanceof EntityPig || e instanceof EntitySheep || e instanceof EntityChicken) || e.isDead || e.getHealth() <= 0) continue;
            if (a.getDistanceToEntity(e) > r || !a.canEntityBeSeen(e)) continue;
            out.add(e);
        }
        final Agent me = a;
        java.util.Collections.sort(out, (p, q) -> Double.compare(me.getDistanceToEntity(p), me.getDistanceToEntity(q)));
        return out;
    }

    static MindSkills.Skill hunt(Crew c, Agent a, WorldServer w) {
        List<EntityLiving> l = animals(a, w, SEE);
        if (l.isEmpty()) return MindSkills.failed("NO_ANIMAL", "no cow, pig, sheep or chicken in sight within " + (int) SEE + " blocks: look_around or go somewhere else");
        Hunt h = new Hunt(l.get(0));
        h.lease = 20 * 60;
        return h;
    }

    /** to the animal, hits as a player's (Guard.strike) until it dies, its drops picked up; 40 s at most */
    static final class Hunt extends MindSkills.Skill {
        final EntityLiving prey;
        Crew.Collect pick;
        int t, cool;
        Hunt(EntityLiving prey) { this.prey = prey; }

        Result run(Agent a, WorldServer w) {
            if (pick != null) {
                Result r = pick.step(a, w);
                if (r == Result.RUNNING) return r;
                detail = "killed " + prey.getCommandSenderName();
                return Result.OK;
            }
            if (prey.isDead || prey.getHealth() <= 0) {
                Ledger.act(a, "kill " + prey.getCommandSenderName());
                pick = new Crew.Collect(prey.posX, prey.posY, prey.posZ, 4);
                return Result.RUNNING;
            }
            if (++t > 20 * 40) { reason = "TIMEOUT"; detail = "could not catch " + prey.getCommandSenderName() + " in 40 s"; return Result.TIMEOUT; }
            if (a.getDistanceToEntity(prey) > SEE + 8) { reason = "GONE"; detail = prey.getCommandSenderName() + " ran off"; return Result.GONE; }
            if (cool > 0) cool--;
            ItemStack wp = a.bestWeapon();
            if (wp != null && a.getHeldItem() != wp) a.body.hold(wp);
            double d = a.getDistanceToEntity(prey);
            if (d > 2.6) {
                if (t % 8 == 1 || a.body.pathless()) a.body.walkTo(prey, 1.2);
                Crew.face(a, prey.posX, prey.posY + prey.getEyeHeight() * 0.8, prey.posZ);
                return Result.RUNNING;
            }
            a.body.stop();
            Crew.face(a, prey.posX, prey.posY + prey.getEyeHeight() * 0.8, prey.posZ);
            a.act((int) Math.floor(prey.posX), (int) Math.floor(prey.posY), (int) Math.floor(prey.posZ), 4);
            if (cool == 0 && a.canEntityBeSeen(prey) && a.body.facing(prey.posX, prey.posY + prey.getEyeHeight() * 0.8, prey.posZ, 30)) {
                cool = 11;
                Guard.strike(a, w, prey);
            }
            return Result.RUNNING;
        }
    }

    /** where the crew has not looked yet (Exploration's frontier of the ground it saw), or null */
    static int[] frontier(Agent a, WorldServer w) {
        return Exploration.frontier(a, w, a.skin() * 7 + (int) (w.getTotalWorldTime() / 400));
    }

    static boolean food(ItemStack s) {
        if (s == null || !(s.getItem() instanceof ItemFood)) return false;
        String id = MindSkills.id(s);
        return !id.startsWith("rotten_flesh") && !id.startsWith("spider_eye") && !id.startsWith("poisonous_potato");
    }

    /** raw: a food the furnace makes into another food (raw meat, fish, a potato) */
    static boolean raw(ItemStack s) {
        if (!food(s)) return false;
        ItemStack out = FurnaceRecipes.instance().getSmeltingResult(s);
        return out != null && out.getItem() instanceof ItemFood;
    }

    /** the id of a raw food of the bag, or null */
    static String rawFood(Agent a) {
        for (ItemStack s : a.bag) if (raw(s)) return MindSkills.id(s);
        return null;
    }

    /** the id of a food in the chest, or null */
    static String foodIn(IInventory inv) {
        if (inv == null) return null;
        for (int i = 0; i < inv.getSizeInventory(); i++) if (food(inv.getStackInSlot(i))) return MindSkills.id(inv.getStackInSlot(i));
        return null;
    }

    static int foodCount(IInventory inv) {
        int n = 0;
        if (inv == null) return 0;
        for (int i = 0; i < inv.getSizeInventory(); i++) if (food(inv.getStackInSlot(i))) n += inv.getStackInSlot(i).stackSize;
        return n;
    }
}
