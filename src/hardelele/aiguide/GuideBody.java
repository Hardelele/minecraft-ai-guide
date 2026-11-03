package hardelele.aiguide;

import java.lang.reflect.Field;
import java.util.Map;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAIOpenDoor;
import net.minecraft.entity.ai.EntityAISwimming;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.DamageSource;
import net.minecraft.world.World;

/**
 * The assistant's body: a villager (players see it with nothing installed) that walks to the chests
 * and the crafting table and back. It does nothing on its own: Errands moves it. It cannot be hurt,
 * does not trade and is never saved with the world.
 */
class GuideBody extends EntityVillager {
    static boolean registered;

    /** the client draws it as a villager: the spawn packet carries the villager's id */
    @SuppressWarnings("unchecked")
    static void register() {
        if (registered) return;
        registered = true;
        try {
            for (String n : new String[] { "field_75624_e", "classToIDMapping" })
                try {
                    Field f = EntityList.class.getDeclaredField(n);
                    f.setAccessible(true);
                    ((Map<Class<?>, Integer>) f.get(null)).put(GuideBody.class, 120);
                    return;
                } catch (NoSuchFieldException e) {
                    // the other name
                }
        } catch (Exception e) {
            AIGuide.LOG.warn("the guide's body is not registered: " + e);
        }
    }

    GuideBody(World w, String name) {
        super(w, 1);   // a librarian
        try {   // none of the villager's own errands
            Field f = net.minecraft.entity.ai.EntityAITasks.class.getDeclaredField("field_75782_a");
            f.setAccessible(true);
            ((java.util.List<?>) f.get(field_70714_bg)).clear();
        } catch (Exception e) {
            AIGuide.LOG.warn("the guide's body keeps the villager's errands: " + e);
        }
        field_70714_bg.func_75776_a(0, new EntityAISwimming(this));
        field_70714_bg.func_75776_a(1, new EntityAIOpenDoor(this, true));
        func_110148_a(SharedMonsterAttributes.field_111265_b).func_111128_a(48);
        func_94058_c(name);
        func_94061_f(true);
    }

    @Override public boolean func_70097_a(DamageSource s, float amount) { return false; }   // attackEntityFrom
    @Override public boolean func_70085_c(EntityPlayer p) { return true; }                 // interact: no trading
    @Override public boolean func_70039_c(NBTTagCompound t) { return false; }              // writeToNBTOptional: never saved
    @Override protected boolean func_70692_ba() { return false; }                          // canDespawn
}
