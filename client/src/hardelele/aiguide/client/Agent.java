package hardelele.aiguide.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAISwimming;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.DamageSource;
import net.minecraft.world.World;

/**
 * A helper of the guide's crew: a body with a player's model and skin and a name over its head. It does
 * nothing on its own: the crew (Crew) gives it tasks, one after another. It walks with the game's own path
 * finder, breaks and places blocks as a player does (in reach, looking at them) and carries what it picks up.
 * It cannot be hurt and is never saved with the world.
 */
public class Agent extends EntityCreature {
    final Deque<Crew.Task> jobs = new ArrayDeque<Crew.Task>();
    final Deque<Origin> origins = new ArrayDeque<Origin>();   // where each job came from
    Crew.GoTo aside;   // out of someone's way first (a block goes where it stands): before its jobs, without touching them
    Origin origin = Origin.SCRIPT;   // of the job it does now
    final List<ItemStack> bag = new ArrayList<ItemStack>();
    int number, idle, stuck;
    /** what it works on now (for the camera): the block, the kind (1 breaks, 2 crafts, 3 places), when */
    volatile int actX, actY, actZ, actKind, actAt = -1000;

    void act(int x, int y, int z, int kind) {
        actX = x;
        actY = y;
        actZ = z;
        actKind = kind;
        actAt = ticksExisted;
    }
    double lastX, lastZ;

    public Agent(World w) {
        super(w);
        setSize(0.6F, 1.8F);
        getNavigator().setAvoidsWater(true);
        getNavigator().setCanSwim(true);
        getNavigator().setBreakDoors(false);
        getNavigator().setEnterDoors(true);   // through doors as a player goes: opening them, and closing them behind
        this.tasks.addTask(0, new EntityAISwimming(this));
        this.tasks.addTask(1, new net.minecraft.entity.ai.EntityAIOpenDoor(this, true));
    }

    @Override
    protected void applyEntityAttributes() {
        super.applyEntityAttributes();
        getEntityAttribute(SharedMonsterAttributes.movementSpeed).setBaseValue(0.34D);
        getEntityAttribute(SharedMonsterAttributes.followRange).setBaseValue(64D);
        getEntityAttribute(SharedMonsterAttributes.maxHealth).setBaseValue(20D);
    }

    @Override public boolean isAIEnabled() { return true; }
    @Override public boolean attackEntityFrom(DamageSource s, float a) { return false; }
    @Override protected boolean canDespawn() { return false; }
    @Override protected void fall(float d) { }
    @Override public boolean canBreatheUnderwater() { return true; }

    /** the skin: by the number in the name ("Агент 3") */
    int skin() {
        String n = getCustomNameTag();
        for (int i = n.length() - 1; i >= 0; i--) if (Character.isDigit(n.charAt(i))) return n.charAt(i) - '0';
        return 1;
    }
}
