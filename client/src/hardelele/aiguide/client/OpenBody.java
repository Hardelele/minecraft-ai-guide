package hardelele.aiguide.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.block.BlockButton;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockLever;
import net.minecraft.block.BlockTrapDoor;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;

/**
 * The open world's hands, legs and head (docs/rl/OPEN-WORLD.md section 2): an agent driven by a player's keys and
 * mouse a step at a time, with the body's limits (Body: the head's speed, the eyes' ray, the reach), nothing of the
 * crew's skills in between. The legs act in the game's own movement (Agent.updateAITasks: moveForward, moveStrafing,
 * the jump, at a player's walking speed); the head, the hands and the bag at the end of each server tick (Crew.tick,
 * instead of the reflexes, the mind and the jobs). The step's action: OpenSpec's heads.
 */
final class OpenBody {
    /** a player's: the land movement factor, the entity reach of a survival player, the delay between two blocks broken */
    static final float WALK = 0.1F, SNEAK = 0.3F;
    static final double HIT_REACH = 3.0;
    static final int BREAK_DELAY = 5, EAT_TICKS = 32;

    final int skin;
    int move, jump, act;               // the step's heads (the turns are spent at its start)
    float yawGoal, pitchGoal;
    boolean aimed;                     // the head's goal set (else the first step starts from where it looks)
    int selected;                      // the hotbar's slot in the hand
    int digX, digY, digZ = Integer.MIN_VALUE, digTicks, digDelay;
    double digNeed;
    boolean hitThisStep;
    int eatTicks;
    String last = "";                  // what the hands did last step (for the logs and the checks)
    int steps, crafted, placed, broken, hits;

    OpenBody(int skin) { this.skin = skin; }

    /** the step's action: the turns and the one-shot hands now, the held ones (legs, left button, eating) each tick after */
    void begin(Agent a, WorldServer w, int[] heads) {
        steps++;
        move = clamp(heads[0], OpenSpec.MOVES.length);
        jump = clamp(heads[1], OpenSpec.JUMPS.length);
        int yaw = clamp(heads[2], OpenSpec.YAWS.length), pitch = clamp(heads[3], OpenSpec.PITCHES.length);
        int prev = act;
        act = clamp(heads[4], OpenSpec.ACTS);
        if (!aimed) { yawGoal = a.body.yawNow(); pitchGoal = a.body.pitchNow(); aimed = true; }
        yawGoal += OpenSpec.YAWS[yaw];
        pitchGoal = OpenSpec.clampPitch(pitchGoal + OpenSpec.PITCHES[pitch]);
        hitThisStep = false;
        if (act != OpenSpec.ACT_USE || prev != OpenSpec.ACT_USE) eatTicks = 0;
        if (act != OpenSpec.ACT_ATTACK) resetDig(w);
        a.setSneaking(jump == 2);
        last = "";
        if (act >= OpenSpec.ACT_SLOT && act < OpenSpec.ACT_SLOT + OpenSpec.HOTBAR) { selected = act - OpenSpec.ACT_SLOT; holdSlot(a); last = "slot " + (selected + 1); }
        else if (act == OpenSpec.ACT_DROP) last = drop(a, w);
        else if (act == OpenSpec.ACT_LOAD) last = load(a, w);
        else if (act == OpenSpec.ACT_TAKE) last = take(a, w);
        else if (act >= OpenSpec.ACT_CRAFT) last = craft(a, w, OpenSpec.RECIPES[act - OpenSpec.ACT_CRAFT]);
        else if (act == OpenSpec.ACT_USE) last = use(a, w);
    }

    static int clamp(int v, int n) { return Math.max(0, Math.min(n - 1, v)); }

    // ---- the legs (in the entity's own update, just before it moves) ------------------------------------------

    void legs(Agent a) {
        int[] m = OpenSpec.MOVES[move];
        float k = 0.98F * (jump == 2 ? SNEAK : 1F);
        a.rotationYaw = a.body.yawNow();   // a player walks where the camera looks
        a.setAIMoveSpeed(WALK);
        a.moveForward = m[0] * k;
        a.moveStrafing = m[1] * k;
        a.setJumping(jump == 1);
    }

    // ---- each tick: the head, the hand, the held buttons, what it touches ---------------------------------------

    void tick(Agent a, WorldServer w) {
        double y = Math.toRadians(yawGoal), p = Math.toRadians(pitchGoal);
        a.body.lookAt(a.body.eyeX() - Math.sin(y) * Math.cos(p) * 10, a.body.eyeY() - Math.sin(p) * 10, a.body.eyeZ() + Math.cos(y) * Math.cos(p) * 10);
        holdSlot(a);
        if (digDelay > 0) digDelay--;
        if (act == OpenSpec.ACT_ATTACK) attack(a, w);
        else if (act == OpenSpec.ACT_USE && eatTicks > 0) eat(a, w);
        pickup(a, w);
    }

    /** the hand holds the selected slot's stack (the bag's first nine are the hotbar) */
    void holdSlot(Agent a) {
        ItemStack s = selected < a.bag.size() ? a.bag.get(selected) : null;
        if (a.getHeldItem() != s) a.setCurrentItemOrArmor(0, s);
    }

    static ItemStack slot(Agent a, int i) { return i < a.bag.size() ? a.bag.get(i) : null; }

    /** what lies within a player's pickup box comes into the bag (as a player walks over it) */
    @SuppressWarnings("unchecked")
    static void pickup(Agent a, WorldServer w) {
        for (Object o : w.getEntitiesWithinAABB(EntityItem.class, a.boundingBox.expand(1.0, 0.5, 1.0))) {
            EntityItem it = (EntityItem) o;
            if (it.isDead || it.delayBeforeCanPickup > 0) continue;
            ItemStack s = it.getEntityItem();
            a.onItemPickup(it, s.stackSize);
            Crew.give(a.bag, s);
            Ledger.flow(Ledger.Way.DROP, s);
            it.setDead();
            RlAch.picked(a, MindSkills.id(s.getItem(), 0));
        }
    }

    // ---- the left button ------------------------------------------------------------------------------------------

    void attack(Agent a, WorldServer w) {
        Entity e = OpenBody.entityOnRay(a, HIT_REACH);
        if (e != null) {   // a click on a living thing: one hit a step (holding the button does not hit again in 1.7.10)
            resetDig(w);
            if (!hitThisStep && e instanceof EntityLiving) {
                hitThisStep = true;
                Guard.strike(a, w, (EntityLiving) e);
                hits++;
                last = "hit " + e.getCommandSenderName();
                OpenRec.note(a, OpenRec.ATTACK, 0);
            } else if (!hitThisStep) { a.swingItem(); hitThisStep = true; }
            return;
        }
        MovingObjectPosition m = ray(a, Crew.REACH, false);
        if (!OpenBody.isBlock(m)) {
            resetDig(w);
            if (!hitThisStep) { a.swingItem(); hitThisStep = true; }
            return;
        }
        int x = m.blockX, yy = m.blockY, z = m.blockZ;
        Block b = w.getBlock(x, yy, z);
        if (b.getMaterial() == Material.air) { resetDig(w); return; }
        if (digX != x || digY != yy || digZ != z) {   // another block: the cracks start over
            resetDig(w);
            digX = x; digY = yy; digZ = z;
            digTicks = 0;
            FakePlayer h = Skills.hands(a, w);
            float per = b.getPlayerRelativeBlockHardness(h, w, x, yy, z);
            h.inventory.mainInventory[0] = null;
            digNeed = per <= 0 ? -1 : 1.0 / per;
        }
        if (digNeed < 0 || digDelay > 0) return;
        digTicks++;
        if (digTicks % 4 == 1) { a.swingItem(); w.playSoundEffect(x + 0.5, yy + 0.5, z + 0.5, b.stepSound.getStepSound(), 0.25F, 0.5F); }
        a.act(x, yy, z, 1);
        w.destroyBlockInWorldPartially(a.getEntityId(), x, yy, z, Math.min(9, (int) (digTicks / digNeed * 10)));
        if (digTicks < digNeed) return;
        digZ = Integer.MIN_VALUE;
        w.destroyBlockInWorldPartially(a.getEntityId(), x, yy, z, -1);
        Ledger.act(a, "break " + b.getUnlocalizedName().replace("tile.", ""));
        if (Skills.harvest(a, w, x, yy, z)) {
            if (Agent.hungerOn) a.hunger.exhaust(Hunger.BREAK);
            RlEnv.broken(a, w, x, yy, z);
            Ground.changed();
            broken++;
            last = "broke " + MindSkills.idOf(b);
        }
        digDelay = BREAK_DELAY;
    }

    void resetDig(WorldServer w) {
        if (digZ != Integer.MIN_VALUE && w != null) w.destroyBlockInWorldPartially(0, digX, digY, digZ, -1);
        digZ = Integer.MIN_VALUE;
        digTicks = 0;
    }

    // ---- the right button -------------------------------------------------------------------------------------------

    String use(Agent a, WorldServer w) {
        ItemStack held = a.getHeldItem();
        if (held != null && held.getItem() instanceof ItemFood && canEat(a, held)) { eatTicks = 1; return "eating"; }
        MovingObjectPosition m = ray(a, Crew.REACH, false);
        if (OpenBody.isBlock(m)) {
            Block b = w.getBlock(m.blockX, m.blockY, m.blockZ);
            float hx = (float) (m.hitVec.xCoord - m.blockX), hy = (float) (m.hitVec.yCoord - m.blockY), hz = (float) (m.hitVec.zCoord - m.blockZ);
            if (b instanceof BlockDoor || b instanceof BlockFenceGate || b instanceof BlockTrapDoor || b instanceof BlockLever || b instanceof BlockButton) {
                b.onBlockActivated(w, m.blockX, m.blockY, m.blockZ, Skills.hands(a, w), m.sideHit, hx, hy, hz);
                a.swingItem();
                OpenRec.note(a, OpenRec.USE, 0);
                return "opened " + MindSkills.idOf(b);
            }
            if (held != null && !(held.getItem() instanceof ItemArmor)) {
                FakePlayer h = Skills.hands(a, w);
                Block before = w.getBlock(m.blockX + Skills.SIDE[m.sideHit][0], m.blockY + Skills.SIDE[m.sideHit][1], m.blockZ + Skills.SIDE[m.sideHit][2]);
                boolean ok = held.tryPlaceItemIntoWorld(h, w, m.blockX, m.blockY, m.blockZ, m.sideHit, hx, hy, hz);
                back(a, h, held);
                if (ok) {
                    a.swingItem();
                    Ground.changed();
                    placed++;
                    int px = m.blockX + Skills.SIDE[m.sideHit][0], py = m.blockY + Skills.SIDE[m.sideHit][1], pz = m.blockZ + Skills.SIDE[m.sideHit][2];
                    if (w.getBlock(px, py, pz) != before) RlEnv.placed(a, w, px, py, pz);
                    Ledger.act(a, "place");
                    return "used " + MindSkills.id(held) + " on " + MindSkills.idOf(b);
                }
            }
        }
        if (held == null) return "";
        if (held.getItem() instanceof ItemArmor) {   // a player's right click with armour wears it (its slot empty)
            int slot = 4 - ((ItemArmor) held.getItem()).armorType;
            if (a.getEquipmentInSlot(slot) != null) return "";
            a.bag.remove(held);
            a.setCurrentItemOrArmor(slot, held);
            holdSlot(a);
            return "wore " + MindSkills.id(held);
        }
        if (held.getItem() instanceof ItemFood || held.getItem() instanceof net.minecraft.item.ItemBlock) return "";
        FakePlayer h = Skills.hands(a, w);
        ItemStack res = held.useItemRightClick(w, h);   // in the air: buckets
        if (h.isUsingItem()) h.clearItemInUse();
        if (res != held) {
            int i = a.bag.indexOf(held);
            if (i >= 0) { if (res == null || res.stackSize <= 0) a.bag.remove(i); else a.bag.set(i, res); }
        } else if (held.stackSize <= 0) a.bag.remove(held);
        back(a, h, null);
        a.swingItem();
        return "used " + MindSkills.id(held);
    }

    /** what the hands' fake player was given besides (a bucket filled with more in the stack, a bowl): into the bag */
    static void back(Agent a, FakePlayer h, ItemStack held) {
        if (held != null && held.stackSize <= 0) { a.bag.remove(held); a.setCurrentItemOrArmor(0, null); }
        ItemStack[] inv = h.inventory.mainInventory;
        for (int i = 1; i < inv.length; i++) if (inv[i] != null) { if (inv[i].stackSize > 0) Crew.give(a.bag, inv[i]); inv[i] = null; }
        inv[0] = null;
    }

    static boolean canEat(Agent a, ItemStack f) {
        boolean always = ((ItemFood) f.getItem()).getItemUseAction(f) == net.minecraft.item.EnumAction.eat && f.getItem() == Items.golden_apple;
        return Agent.hungerOn ? a.hunger.canEat(always) : a.getHealth() < a.getMaxHealth();
    }

    /** the right button held with food: 32 ticks, then one is eaten (as DefenseSkills' eat, as a player) */
    void eat(Agent a, WorldServer w) {
        ItemStack f = a.getHeldItem();
        if (f == null || !(f.getItem() instanceof ItemFood) || !canEat(a, f)) { eatTicks = 0; return; }
        if (++eatTicks % 4 == 0) { a.swingItem(); w.playSoundEffect(a.posX, a.posY + 1, a.posZ, "random.eat", 0.5F, 1.0F); }
        if (eatTicks < EAT_TICKS) return;
        eatTicks = 0;
        int heal = ((ItemFood) f.getItem()).getHealAmount(f);
        Item it = f.getItem();
        float sat = ((ItemFood) it).getSaturationModifier(f);
        Skills.take(a, it, f.getMetadata(), 1, Ledger.Way.INGREDIENT);
        if (Agent.hungerOn) a.hunger.eat(heal, sat);
        else a.heal(heal);
        RlEnv.ate(a, heal);
        last = "ate " + MindSkills.id(it, 0);
    }

    // ---- one-shot hands: drop, the furnace, a craft --------------------------------------------------------------------

    String drop(Agent a, WorldServer w) {
        ItemStack held = a.getHeldItem();
        if (held == null) return "";
        ItemStack one = held.splitStack(1);
        if (held.stackSize <= 0) { a.bag.remove(held); a.setCurrentItemOrArmor(0, null); }
        double[] v = a.body.look();
        EntityItem e = new EntityItem(w, a.body.eyeX(), a.body.eyeY() - 0.3, a.body.eyeZ(), one);
        e.delayBeforeCanPickup = 40;
        e.motionX = v[0] * 0.3; e.motionY = v[1] * 0.3 + 0.1; e.motionZ = v[2] * 0.3;
        w.spawnEntityInWorld(e);
        Ledger.flow(Ledger.Way.INTO_NOTHING, one);
        OpenRec.note(a, OpenRec.DROP, 0);
        return "dropped " + MindSkills.id(one);
    }

    /** a block of these kinds in reach that the eyes see (a player would click on it): its cell or null */
    static int[] seen(Agent a, WorldServer w, Block... kinds) {
        int ex = MathHelper.floor_double(a.body.eyeX()), ey = MathHelper.floor_double(a.body.eyeY()), ez = MathHelper.floor_double(a.body.eyeZ());
        int r = (int) Math.ceil(Crew.REACH);
        int[] best = null;
        double bd = 1e9;
        for (int dy = -r; dy <= r; dy++)
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    Block b = w.getBlock(ex + dx, ey + dy, ez + dz);
                    boolean is = false;
                    for (Block k : kinds) if (b == k) is = true;
                    if (!is) continue;
                    double d = Sight.faceDist(a.body.eyeX(), a.body.eyeY(), a.body.eyeZ(), ex + dx, ey + dy, ez + dz);
                    if (d > Crew.REACH || d >= bd || a.body.sightPoint(w, ex + dx, ey + dy, ez + dz) == null) continue;
                    bd = d;
                    best = new int[] { ex + dx, ey + dy, ez + dz };
                }
        return best;
    }

    static TileEntityFurnace furnace(Agent a, WorldServer w) {
        int[] at = seen(a, w, Blocks.furnace, Blocks.lit_furnace);
        if (at == null) return null;
        TileEntity te = w.getTileEntity(at[0], at[1], at[2]);
        return te instanceof TileEntityFurnace ? (TileEntityFurnace) te : null;
    }

    /** what is in the hand goes in to be smelted (if it smelts), and fuel from the bag (if the fuel slot takes it) */
    String load(Agent a, WorldServer w) {
        TileEntityFurnace f = furnace(a, w);
        if (f == null) return "";
        StringBuilder out = new StringBuilder();
        ItemStack held = a.getHeldItem();
        if (held != null && FurnaceRecipes.instance().getSmeltingResult(held) != null) {
            int n = into(f, 0, held);
            if (held.stackSize <= 0) { a.bag.remove(held); a.setCurrentItemOrArmor(0, null); }
            if (n > 0) out.append("in ").append(n).append(' ').append(MindSkills.id(held));
        }
        ItemStack fuel = null;
        for (ItemStack s : a.bag) if (s != held && TileEntityFurnace.getItemBurnTime(s) > 0 && (fuel == null || s.getItem() == Items.coal)) fuel = s;
        if (fuel == null && held != null && held.stackSize > 0 && TileEntityFurnace.getItemBurnTime(held) > 0) fuel = held;
        if (fuel != null) {
            int n = into(f, 1, fuel);
            if (fuel.stackSize <= 0) { a.bag.remove(fuel); if (a.getHeldItem() == fuel) a.setCurrentItemOrArmor(0, null); }
            if (n > 0) out.append(out.length() > 0 ? ", " : "").append("fuel ").append(n).append(' ').append(MindSkills.id(fuel));
        }
        if (out.length() > 0) { a.swingItem(); f.markDirty(); OpenRec.note(a, OpenRec.FURNACE, OpenSpec.ACT_LOAD); }
        return out.toString();
    }

    /** a stack into a slot of the furnace, as much as fits; how many went */
    static int into(TileEntityFurnace f, int slot, ItemStack s) {
        ItemStack in = f.getStackInSlot(slot);
        int room;
        if (in == null) room = s.getMaxStackSize();
        else if (in.getItem() == s.getItem() && in.getMetadata() == s.getMetadata()) room = in.getMaxStackSize() - in.stackSize;
        else return 0;
        int n = Math.min(room, s.stackSize);
        if (n <= 0) return 0;
        if (in == null) f.setInventorySlotContents(slot, new ItemStack(s.getItem(), n, s.getMetadata()));
        else in.stackSize += n;
        s.stackSize -= n;
        return n;
    }

    String take(Agent a, WorldServer w) {
        TileEntityFurnace f = furnace(a, w);
        if (f == null) return "";
        ItemStack out = f.getStackInSlot(2);
        if (out == null) return "";
        f.setInventorySlotContents(2, null);
        Crew.give(a.bag, out);
        Ledger.flow(Ledger.Way.SMELTED, out);
        String id = MindSkills.id(out.getItem(), 0);
        if (id.equals("iron_ingot")) RlAch.grant(a, "acquireIron");
        RlAch.picked(a, id);
        a.swingItem();
        OpenRec.note(a, OpenRec.FURNACE, OpenSpec.ACT_TAKE);
        return "took " + out.stackSize + " " + id;
    }

    static final Map<String, List<Skills.Laid>> RECIPES = new HashMap<String, List<Skills.Laid>>();

    static List<Skills.Laid> recipes(String name) {
        List<Skills.Laid> l = RECIPES.get(name);
        if (l == null) {
            Object[] it = MindSkills.item(name);
            l = it == null ? new java.util.ArrayList<Skills.Laid>() : Skills.recipesFor((Item) it[0], (Integer) it[1]);
            RECIPES.put(name, l);
        }
        return l;
    }

    /** the recipe of the item the bag can do now (a 3x3 one only with a table seen in reach), or null */
    static Skills.Laid doable(Agent a, WorldServer w, String name, boolean[] table) {
        Skills.Laid l = MindSkills.doable(a, recipes(name));
        if (l == null) return null;
        if (!l.small()) {
            if (table[0] && !table[1]) { table[1] = seen(a, w, Blocks.crafting_table) != null; table[0] = false; }
            if (!table[1]) return null;
        }
        return l;
    }

    String craft(Agent a, WorldServer w, String name) {
        Skills.Laid l = doable(a, w, name, new boolean[] { true, false });
        if (l == null) return "";
        ItemStack r = Skills.craftOnce(a, w, l);
        if (r == null) return "";
        crafted++;
        a.swingItem();
        RlAch.crafted(a, MindSkills.id(r.getItem(), 0));
        return "crafted " + r.stackSize + " " + MindSkills.id(r);
    }

    /** the hands' mask (OpenSpec's act head): the crafts the bag and a table in sight allow, the furnace in sight */
    static int[] mask(Agent a, WorldServer w) {
        int[] m = new int[OpenSpec.ACTS];
        for (int i = 0; i < OpenSpec.ACT_LOAD; i++) m[i] = 1;
        boolean[] table = { true, false };
        for (int k = 0; k < OpenSpec.RECIPES.length; k++) m[OpenSpec.ACT_CRAFT + k] = doable(a, w, OpenSpec.RECIPES[k], table) != null ? 1 : 0;
        TileEntityFurnace f = furnace(a, w);
        m[OpenSpec.ACT_LOAD] = f != null ? 1 : 0;
        m[OpenSpec.ACT_TAKE] = f != null && f.getStackInSlot(2) != null ? 1 : 0;
        return m;
    }

    /** a block hit (the enum's class is not visible in the compile library: by its name) */
    static boolean isBlock(MovingObjectPosition m) { return m != null && "BLOCK".equals(String.valueOf(m.typeOfHit)); }

    // ---- the eyes' rays ---------------------------------------------------------------------------------------------------

    /** the block the eyes' ray meets in reach (boxless: plants and torches stop it too, as a player's click) */
    static MovingObjectPosition ray(Agent a, double len, boolean throughPlants) {
        double[] v = a.body.look();
        Vec3 from = Vec3.createVectorHelper(a.body.eyeX(), a.body.eyeY(), a.body.eyeZ());
        Vec3 to = Vec3.createVectorHelper(a.body.eyeX() + v[0] * len, a.body.eyeY() + v[1] * len, a.body.eyeZ() + v[2] * len);
        return a.worldObj.rayTraceBlocks(from, to, false, throughPlants, false);
    }

    /** the living thing the eyes' ray meets first within the reach, before any block (as the game's pointed entity) */
    @SuppressWarnings("unchecked")
    static Entity entityOnRay(Agent a, double reach) {
        double[] v = a.body.look();
        double ex = a.body.eyeX(), ey = a.body.eyeY(), ez = a.body.eyeZ();
        MovingObjectPosition blk = ray(a, reach, false);
        double limit = blk == null ? reach : blk.hitVec.distanceTo(Vec3.createVectorHelper(ex, ey, ez));
        Entity best = null;
        List<Entity> l = a.worldObj.getEntitiesWithinAABBExcludingEntity(a, a.boundingBox.addCoord(v[0] * reach, v[1] * reach, v[2] * reach).expand(1, 1, 1));
        for (Entity e : l) {
            if (!e.canBeCollidedWith() || e.isDead || e instanceof FakePlayer || !(e instanceof EntityLivingBase)) continue;
            double b = e.getCollisionBorderSize();
            AxisAlignedBB bb = e.boundingBox.expand(b, b, b);
            Vec3 from = Vec3.createVectorHelper(ex, ey, ez), to = Vec3.createVectorHelper(ex + v[0] * reach, ey + v[1] * reach, ez + v[2] * reach);
            double d;
            if (bb.isVecInside(from)) d = 0;
            else {
                MovingObjectPosition hit = bb.calculateIntercept(from, to);
                if (hit == null) continue;
                d = hit.hitVec.distanceTo(Vec3.createVectorHelper(ex, ey, ez));
            }
            if (d < limit) { limit = d; best = e; }
        }
        return best;
    }
}
