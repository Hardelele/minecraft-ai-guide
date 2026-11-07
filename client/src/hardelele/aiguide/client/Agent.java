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
    /**
     * Layer 2: the reaction that has the body in front of its jobs, without touching them (a reflex, or what a task
     * asked for itself: out of someone's way, a torch in the dark); the job on top waits meanwhile and goes on after.
     */
    final Reflexes.Slot<Crew.Task> slot = new Reflexes.Slot<Crew.Task>();
    Reacts.Run run;
    /** the priority under which its task is not interrupted (30; a task raises it each tick on a critical part: a pillar 70, a fight 85) */
    int guard = 30;
    long guardAt = -10;
    /** what its tasks changed for a while and owe back (a pillar, a hole in its wall) */
    final Undo.Book undo = new Undo.Book();
    /** the walks that failed (an unreachable goal is not tried again and again) and the walk its task is on now */
    final Reflexes.Walks walks = new Reflexes.Walks();
    Crew.GoTo walking;
    long walkingAt = -10;

    /** this tick its task is at a part not to be interrupted below this priority */
    void guarded(int priority) {
        guard = priority;
        guardAt = worldObj.getTotalWorldTime();
    }

    /** a short job before its jobs (the slot): own, asked by its own task (always taken), else only over its task's guard */
    boolean interject(Crew.Task t, int priority, String why, boolean own) {
        return slot.interject(t, priority, why, own, guardAt >= worldObj.getTotalWorldTime() - 1 ? guard : 30);
    }

    /** a change made for a while, owed back to the job on now (the reaction's, when there is no job) */
    void owe(Undo.Kind kind, int x, int y, int z, int block, int meta, String why) {
        Object owner = jobs.peek() != null ? jobs.peek() : slot.task;
        undo.owe(new Undo(kind, x, y, z, block, meta, why, owner, worldObj.getTotalWorldTime()));
    }

    /** given back by the task itself */
    void paid(int x, int y, int z) {
        undo.paid(x, y, z);
    }
    /** its body: the one way it acts on the world (the head, the hands, the legs) and what it senses */
    final Body body = new Body(this);
    Origin origin = Origin.SCRIPT;   // of the job it does now
    final List<ItemStack> bag = new ArrayList<ItemStack>();
    int number, idle, stuck;
    /** what it works on now (for the camera): the block, the kind (1 breaks, 2 crafts, 3 places, 4 fights, 5 hands over), when */
    volatile int actX, actY, actZ, actKind, actAt = -1000;
    volatile Act actWhat = Act.IDLE;

    /**
     * What an agent is doing, ranked by how much of a work action it is to look at (the camera's choice, the cut's
     * measure): a fight first, then ore, chopping, digging, building, a station (furnace, table), handing over and
     * carrying, walking; standing (or jumping about) is nothing.
     */
    enum Act {
        IDLE(0), WALK(1), CARRY(2), STATION(3), BUILD(4), DIG(5), CHOP(6), ORE(7), FIGHT(9);
        final int rank;
        Act(int r) { rank = r; }
        /** a work action: something is being done to the world or with things (not walking, not carrying) */
        boolean work() { return rank >= STATION.rank; }
    }

    void act(int x, int y, int z, int kind) {
        actX = x;
        actY = y;
        actZ = z;
        actKind = kind;
        actAt = ticksExisted;
        Act w = Act.STATION;
        if (kind == 1) {   // what is broken says which work it is
            net.minecraft.block.Block b = worldObj.getBlock(x, y, z);
            String n = net.minecraft.block.Block.blockRegistry.getNameForObject(b);
            boolean ore = n != null && n.contains("_ore");
            if (!ore && net.minecraft.item.Item.getItemFromBlock(b) != null) for (int id : net.minecraftforge.oredict.OreDictionary.getOreIDs(new ItemStack(b))) if (net.minecraftforge.oredict.OreDictionary.getOreName(id).startsWith("ore")) ore = true;
            w = ore ? Act.ORE : b.getMaterial() == net.minecraft.block.material.Material.wood || b.getMaterial() == net.minecraft.block.material.Material.leaves ? Act.CHOP : Act.DIG;
        } else if (kind == 3) w = Act.BUILD;
        else if (kind == 4) w = Act.FIGHT;
        else if (kind == 5) w = Act.CARRY;
        actWhat = w;
    }

    /** what it does this moment: the last act while it lasts (half a second), else walking or carrying while it moves, else nothing */
    /** in a replay: what the recording says it did (a puppet has no jobs of its own) */
    volatile Act replayAct;

    Act activity() {
        if (replayAct != null) return replayAct;
        if (isDead) return Act.IDLE;
        if (ticksExisted - actAt <= 10) return actWhat;
        double v = Math.hypot(posX - prevPosX, posZ - prevPosZ);
        if (v > 0.04 && !jobs.isEmpty()) return TaskBoard.stackables(this) > 0 ? Act.CARRY : Act.WALK;
        return Act.IDLE;
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
    @Override protected boolean canDespawn() { return false; }

    /**
     * Hurt as a player is: by mobs, falls, drowning, fire (nothing spared). It heals as a fed player does (a point every
     * 4 seconds: the agents have no hunger, so they count as fed). It dies as a player does: what it carried falls where
     * it died (items in the world: picked up again or gone in 5 minutes), and it comes back at the crew's spawn point
     * with full health and an empty bag (the crew does that: Crew.respawn).
     */
    @Override
    public boolean attackEntityFrom(DamageSource s, float amount) {
        float before = getHealth();
        boolean hit = super.attackEntityFrom(s, amount);
        if (hit && !worldObj.isRemote) {
            hurtAt = ticksExisted;
            hurtBy = s.getEntity() != null ? s.getEntity().getCommandSenderName() : s.getDamageType();
            Events.log("crew: " + getCustomNameTag() + " hurt by " + (s.getEntity() != null ? s.getEntity().getCommandSenderName() : s.getDamageType())
                    + ", health " + Math.round(before) + " -> " + Math.round(getHealth()));
        }
        return hit;
    }

    /** under the player's command (Commander): takes only the player's order cards, until given back to the AI */
    volatile boolean commanded;
    /** what the player chose it to work with (a tool, a block to build with): its skills take it first when it fits */
    volatile ItemStack preferred;
    int hurtAt = -1000;
    String hurtBy;
    int[] diedAt;

    /** the eyes where a player's are (1.62 over the feet), not a creature's (0.85 of its height) */
    @Override
    public float getEyeHeight() { return 1.62F; }

    @Override
    public void onLivingUpdate() {
        super.onLivingUpdate();
        if (!worldObj.isRemote && !isDead && getHealth() > 0 && getHealth() < getMaxHealth() && ticksExisted % 80 == 0) heal(1.0F);
    }

    @Override
    public void onDeath(DamageSource s) {
        if (!worldObj.isRemote && slot.busy()) Cases.drop(this, "DIED");   // a reaction open: its case ends so
        if (!worldObj.isRemote) setCurrentItemOrArmor(0, null);   // the held thing is in the bag: it falls once, with the bag
        super.onDeath(s);
        if (worldObj.isRemote) return;
        for (ItemStack it : bag) {   // the bag falls where it died, as a player's inventory does
            Ledger.flow(Ledger.Way.DROPPED_AT_DEATH, it);
            net.minecraft.entity.item.EntityItem e = new net.minecraft.entity.item.EntityItem(worldObj, posX, posY + 0.5, posZ, it.copy());
            e.delayBeforeCanPickup = 40;
            e.motionX = (rand.nextDouble() - 0.5) * 0.3; e.motionZ = (rand.nextDouble() - 0.5) * 0.3; e.motionY = 0.2;
            worldObj.spawnEntityInWorld(e);
        }
        bag.clear();
        setCurrentItemOrArmor(0, null);
        Ledger.act(this, "died");
        diedAt = new int[] { (int) Math.floor(posX), (int) Math.floor(posY), (int) Math.floor(posZ) };
        Events.log("crew: " + getCustomNameTag() + " died (" + s.getDamageType() + (s.getEntity() != null ? ", " + s.getEntity().getCommandSenderName() : "") + ") at "
                + (int) Math.floor(posX) + " " + (int) Math.floor(posY) + " " + (int) Math.floor(posZ));
    }

    /** the weapon worth most in its bag: by the attack damage the item gives (a sword over an axe over a pickaxe), 1 bare-handed */
    double weapon() {
        double best = 1;
        for (ItemStack s : bag) best = Math.max(best, damageOf(s));
        return best;
    }

    static double damageOf(ItemStack s) {
        double d = 1;
        if (s == null) return d;
        @SuppressWarnings("unchecked") com.google.common.collect.Multimap<String, Object> m = (com.google.common.collect.Multimap<String, Object>) (Object) s.getAttributeModifiers();
        for (Object o : m.get(SharedMonsterAttributes.attackDamage.getAttributeUnlocalizedName()))
            d += ((net.minecraft.entity.ai.attributes.AttributeModifier) o).getAmount();
        return d;
    }

    ItemStack bestWeapon() {
        ItemStack best = null;
        for (ItemStack s : bag) if (damageOf(s) > damageOf(best) || best == null && damageOf(s) > 1) best = s;
        return best;
    }

    /** the bag goes with the agent into the world's save and comes back with it (nothing is lost between days) */
    @Override
    public void writeEntityToNBT(NBTTagCompound t) {
        super.writeEntityToNBT(t);
        net.minecraft.nbt.NBTTagList l = new net.minecraft.nbt.NBTTagList();
        for (ItemStack s : bag) {
            NBTTagCompound c = new NBTTagCompound();
            s.writeToNBT(c);
            l.appendTag(c);
        }
        t.setTag("AIGuideBag", l);
    }

    @Override
    public void readEntityFromNBT(NBTTagCompound t) {
        super.readEntityFromNBT(t);
        bag.clear();
        net.minecraft.nbt.NBTTagList l = t.getTagList("AIGuideBag", 10);
        for (int i = 0; i < l.tagCount(); i++) {
            ItemStack s = ItemStack.loadItemStackFromNBT(l.getCompoundTagAt(i));
            if (s != null) bag.add(s);
        }
        if (getHeldItem() == null) for (ItemStack s : bag) if (s.getItem() instanceof net.minecraft.item.ItemTool) { setCurrentItemOrArmor(0, s); break; }
    }

    /** the skin: by the number in the name ("Агент 3") */
    int skin() {
        String n = getCustomNameTag();
        for (int i = n.length() - 1; i >= 0; i--) if (Character.isDigit(n.charAt(i))) return n.charAt(i) - '0';
        return 1;
    }
}
