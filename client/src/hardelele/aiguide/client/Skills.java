package hardelele.aiguide.client;

import com.mojang.authlib.GameProfile;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.ShapedRecipes;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraft.util.MathHelper;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.oredict.ShapedOreRecipe;
import net.minecraftforge.oredict.ShapelessOreRecipe;

/**
 * The crew's honest economy: an agent's bag is booked (Ledger), its hands are a fake player of its own (the game's
 * own break speed, canHarvestBlock, BreakEvent, drops and tool wear; placing with the item's own onItemUse, so logs,
 * doors and torches face as they should), and things are made only by the game's own recipes from what is in the
 * bag, at a real crafting table when they need the 3x3 grid, and smelted in a real furnace with real fuel.
 */
class Skills {

    // ---- the bag ---------------------------------------------------------------------------------------

    static boolean same(ItemStack a, Item item, int meta) {
        return a != null && a.getItem() == item && (meta < 0 || meta == 32767 || a.getMetadata() == meta);
    }

    static int count(List<ItemStack> bag, Item item, int meta) {
        int n = 0;
        for (ItemStack s : bag) if (same(s, item, meta)) n += s.stackSize;
        return n;
    }

    /** into the bag, booked by how it came */
    static void put(Agent a, ItemStack s, Ledger.Way how) {
        if (s == null || s.stackSize <= 0) return;
        Ledger.flow(how, s);
        Crew.give(a.bag, s);
    }

    /** out of the bag (up to n), booked by where it went; returns how many */
    static int take(Agent a, Item item, int meta, int n, Ledger.Way how) {
        int got = 0;
        for (Iterator<ItemStack> it = a.bag.iterator(); it.hasNext() && got < n; ) {
            ItemStack s = it.next();
            if (!same(s, item, meta)) continue;
            int k = Math.min(n - got, s.stackSize);
            s.stackSize -= k;
            got += k;
            if (s.stackSize <= 0) it.remove();
        }
        if (got > 0) Ledger.flow(how, new ItemStack(item, got, meta < 0 ? 0 : meta));
        return got;
    }

    /** the best tool in the bag for a block (or null): the one the block breaks fastest with, that can harvest it */
    static ItemStack bestTool(Agent a, Block b, int meta) {
        ItemStack p = a.preferred;   // the player's choice, when it is in the bag and does this block's work
        if (p != null && a.bag.contains(p) && p.getStrVsBlock(b) > 1.0F && (b.getMaterial().isToolNotRequired() || p.canItemHarvestBlock(b))) return p;
        ItemStack best = null;
        float bs = 1.0F;
        for (ItemStack s : a.bag) {
            if (s == null || s.getItem() == null || s.isStackable()) continue;
            float v = s.getStrVsBlock(b);
            if (v > bs && (b.getMaterial().isToolNotRequired() || s.canItemHarvestBlock(b))) { bs = v; best = s; }
        }
        return best;
    }

    // ---- the hands: a fake player of the agent's own ----------------------------------------------------

    static final Map<Integer, FakePlayer> hands = new HashMap<Integer, FakePlayer>();

    /** the agent's hands where the agent is, looking where it looks, holding what it holds */
    static FakePlayer hands(Agent a, WorldServer w) {
        FakePlayer p = hands.get(a.getEntityId());
        if (p == null || p.worldObj != w) {
            p = FakePlayerFactory.get(w, new GameProfile(UUID.nameUUIDFromBytes(("aiguide-agent-" + a.skin()).getBytes()), "[Agent " + a.skin() + "]"));
            hands.put(a.getEntityId(), p);
        }
        // the game's hooks send a fake player packets (ForgeHooks.onBlockBreakEvent: the block back; a slot): to nowhere.
        // Looked at every time, not only when the hands are made: FakePlayerFactory hands out its cached player, whose
        // handler something else may have dropped (the NPE at ForgeHooks:459 under tryHarvestBlock in the runs before this)
        if (p.playerNetServerHandler == null) {
            final net.minecraft.server.MinecraftServer srv = net.minecraft.server.MinecraftServer.getServer();
            new net.minecraft.network.NetHandlerPlayServer(srv, new net.minecraft.network.NetworkManager(false), p) {
                @Override public void sendPacket(net.minecraft.network.Packet pk) { }
            };
        }
        p.setLocationAndAngles(a.posX, a.posY, a.posZ, a.rotationYawHead, a.rotationPitch);
        p.onGround = true;
        p.inventory.currentItem = 0;
        p.inventory.mainInventory[0] = a.getHeldItem();
        return p;
    }

    /** ticks it takes the agent to break the block with what it holds; -1 when it cannot (or the block gives nothing without a tool) */
    static int breakTicks(Agent a, WorldServer w, int x, int y, int z) {
        Block b = w.getBlock(x, y, z);
        int meta = w.getBlockMetadata(x, y, z);
        FakePlayer p = hands(a, w);
        if (!ForgeHooks.canHarvestBlock(b, p, meta) && !(b.getMaterial() == Material.leaves || b.getMaterial() == Material.plants || b.getMaterial() == Material.vine))
            return -1;
        float per = b.getPlayerRelativeBlockHardness(p, w, x, y, z);
        if (per <= 0) return -1;
        return Math.max(1, (int) Math.ceil(1.0 / per));
    }

    /** break the block as the agent's hands would: the event, the drops, the tool worn */
    static boolean harvest(Agent a, WorldServer w, int x, int y, int z) {
        FakePlayer p = hands(a, w);
        ItemStack held = a.getHeldItem();
        boolean ok = p.theItemInWorldManager.tryHarvestBlock(x, y, z);
        ItemStack after = p.inventory.mainInventory[0];
        if (held != null && (after == null || after.stackSize <= 0)) {   // the tool broke
            Ledger.flow(Ledger.Way.INGREDIENT, held, "worn out");
            a.bag.remove(held);
            a.body.hold(null);
        }
        p.inventory.mainInventory[0] = null;
        return ok;
    }

    static final int[][] SIDE = { { 0, -1, 0 }, { 0, 1, 0 }, { 0, 0, -1 }, { 0, 0, 1 }, { -1, 0, 0 }, { 1, 0, 0 } };

    /**
     * What a block put at x y z would be put against: the faces of firm neighbours to click (the block below first,
     * then the sides, then above); a door only on the floor under it. The one rule for every placement decision: the
     * placing itself and the choosing of what can be put next.
     */
    static List<int[]> supports(WorldServer w, int x, int y, int z, boolean door) {
        List<int[]> clicks = new ArrayList<int[]>();
        if (door) {
            if (Crew.solid(w, x, y - 1, z)) clicks.add(new int[] { x, y - 1, z, 1 });
            return clicks;
        }
        for (int i : new int[] { 0, 2, 3, 4, 5, 1 }) {
            int nx = x + SIDE[i][0], ny = y + SIDE[i][1], nz = z + SIDE[i][2];
            if (Crew.solid(w, nx, ny, nz)) clicks.add(new int[] { nx, ny, nz, i ^ 1 });   // the neighbour's side that looks at the place
        }
        return clicks;
    }

    /** how light it is where the agent stands (blocks' light and the sky's, as the game counts it) */
    static int light(Agent a, WorldServer w) {
        int x = MathHelper.floor_double(a.posX), y = MathHelper.floor_double(a.posY + 0.5), z = MathHelper.floor_double(a.posZ);
        return w.getBlockLightValue(x, y, z);
    }

    /**
     * Where it is dark (below 7: where mobs would spawn), an agent with torches puts one up: on a wall beside it at head
     * height, or on the floor, where its eyes see a face to put it against from where it stands. The same for every
     * skill that goes into the dark (digging, tunnels, the mine). The torch is put as a short job before its others
     * (the body turns to it first); true when one is to be put.
     */
    static boolean lightUp(Agent a, WorldServer w) {
        if (a.slot.busy() || light(a, w) >= 7 || count(a.bag, Day1.TORCH, -1) == 0) return false;
        int x = MathHelper.floor_double(a.posX), y = MathHelper.floor_double(a.posY + 0.01), z = MathHelper.floor_double(a.posZ);
        int[][] spots = { { x + 1, y + 1, z }, { x - 1, y + 1, z }, { x, y + 1, z + 1 }, { x, y + 1, z - 1 }, { x + 1, y, z }, { x - 1, y, z }, { x, y, z + 1 }, { x, y, z - 1 } };
        for (int[] s : spots) {
            if (!Crew.soft(w, s[0], s[1], s[2])) continue;
            List<int[]> sup = supports(w, s[0], s[1], s[2], false);   // the placing's own rule
            if (sup.isEmpty() || a.body.sees(w, sup) == null) continue;
            Events.log("crew: " + a.getCustomNameTag() + " puts up a torch at " + s[0] + " " + s[1] + " " + s[2] + " (dark)");
            return a.interject(new PlaceFrom(s[0], s[1], s[2], Day1.TORCH, -1).here(), Reflexes.TORCH, "torch", true);
        }
        return false;
    }

    /**
     * Is the cell free for a block now: items lying there are picked up by the placer (they are within its reach),
     * another agent standing there is asked to step aside, the placer itself or the player there means not now (BUSY).
     * Every placement of the crew goes through this.
     */
    @SuppressWarnings("unchecked")
    static Result clearCell(Agent a, WorldServer w, int x, int y, int z, int height) {
        net.minecraft.util.AxisAlignedBB box = net.minecraft.util.AxisAlignedBB.getBoundingBox(x, y, z, x + 1, y + height, z + 1);
        Result r = Result.OK;
        for (Object o : w.getEntitiesWithinAABBExcludingEntity(null, box)) {
            net.minecraft.entity.Entity e = (net.minecraft.entity.Entity) o;
            if (e.isDead) continue;
            if (e instanceof net.minecraft.entity.item.EntityItem) {   // a thing lying there: into the placer's bag
                net.minecraft.entity.item.EntityItem it = (net.minecraft.entity.item.EntityItem) e;
                ItemStack s = it.getEntityItem();
                a.onItemPickup(it, s.stackSize);
                put(a, s, Day1.HANDED.containsKey(it) ? Ledger.Way.HANDED_IN : Ledger.Way.DROP);
                it.setDead();
                continue;
            }
            if (e instanceof net.minecraft.entity.item.EntityXPOrb) continue;
            if (e instanceof Agent) Crew.stepAside((Agent) e, x, y, z);   // an agent, the placer itself too: out of the way
            r = Result.BUSY;   // the placer, another agent (asked to move), the player or an animal: not now
        }
        return r;
    }

    /**
     * The item's own use on a face its eyes are on (only the body calls this: Body.place, after the aim is checked):
     * one of the stack goes into the world by onItemUse at the point the aim meets, booked as placed.
     */
    static Result use(Agent a, WorldServer w, int x, int y, int z, ItemStack in, int[] c, float hx, float hy, float hz) {
        FakePlayer p = hands(a, w);
        ItemStack one = in.copy();
        one.stackSize = 1;
        boolean ok = one.tryPlaceItemIntoWorld(p, w, c[0], c[1], c[2], c[3], hx, hy, hz);
        if (!ok || one.stackSize != 0) return Result.BLOCKED;
        take(a, in.getItem(), in.getMetadata(), 1, Ledger.Way.PLACED);
        Ground.changed();
        Block placed = w.getBlock(x, y, z);
        w.playSoundEffect(x + 0.5, y + 0.5, z + 0.5, placed.stepSound.getPlaceSound(), 1.0F, 0.8F);
        a.swingItem();
        Ledger.act(a, "place");
        return Result.OK;
    }
    // ---- recipes: the game's own ---------------------------------------------------------------------------

    /** a recipe laid out: the ingredient of each grid cell (a list of stacks it may be), the grid's width */
    static final class Laid {
        IRecipe recipe;
        List<List<ItemStack>> cells = new ArrayList<List<ItemStack>>();   // 9 (3x3) or fewer; null cell = empty
        int width = 3, height = 3;
        boolean shapeless;
        ItemStack out;
        boolean small() {
            if (shapeless) { int n = 0; for (List<ItemStack> c : cells) if (c != null) n++; return n <= 4; }
            return width <= 2 && height <= 2;
        }
    }

    @SuppressWarnings("unchecked")
    static List<ItemStack> ingredient(Object o) {
        if (o == null) return null;
        List<ItemStack> l = new ArrayList<ItemStack>();
        if (o instanceof ItemStack) l.add((ItemStack) o);
        else if (o instanceof Item) l.add(new ItemStack((Item) o, 1, 32767));
        else if (o instanceof Block) l.add(new ItemStack((Block) o, 1, 32767));
        else if (o instanceof List) for (Object x : (List<Object>) o) if (x instanceof ItemStack) l.add((ItemStack) x);
        return l.isEmpty() ? null : l;
    }

    static Object field(Object o, Class<?> type, int nth) {
        int k = 0;
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass())
            for (Field f : c.getDeclaredFields())
                if (f.getType() == type && !java.lang.reflect.Modifier.isStatic(f.getModifiers()) && k++ == nth)
                    try {
                        f.setAccessible(true);
                        return f.get(o);
                    } catch (Exception e) {
                        return null;
                    }
        return null;
    }

    static Laid lay(IRecipe r) {
        Laid l = new Laid();
        l.recipe = r;
        l.out = r.getRecipeOutput();
        try {
            if (r instanceof ShapedRecipes) {
                l.width = (Integer) field(r, int.class, 0);
                l.height = (Integer) field(r, int.class, 1);
                for (ItemStack s : (ItemStack[]) field(r, ItemStack[].class, 0)) l.cells.add(ingredient(s));
            } else if (r instanceof ShapedOreRecipe) {
                Field fw = ShapedOreRecipe.class.getDeclaredField("width"), fh = ShapedOreRecipe.class.getDeclaredField("height");
                fw.setAccessible(true);
                fh.setAccessible(true);
                l.width = fw.getInt(r);
                l.height = fh.getInt(r);
                for (Object o : ((ShapedOreRecipe) r).getInput()) l.cells.add(ingredient(o));
            } else if (r instanceof ShapelessRecipes) {
                l.shapeless = true;
                for (Object o : (List<?>) field(r, List.class, 0)) l.cells.add(ingredient(o));
            } else if (r instanceof ShapelessOreRecipe) {
                l.shapeless = true;
                for (Object o : ((ShapelessOreRecipe) r).getInput()) l.cells.add(ingredient(o));
            } else return null;
        } catch (Exception e) {
            return null;
        }
        return l;
    }

    /** the recipes that make the item (the game's crafting list), simplest first */
    @SuppressWarnings("unchecked")
    static List<Laid> recipesFor(Item item, int meta) {
        List<Laid> out = new ArrayList<Laid>();
        for (Object o : CraftingManager.getInstance().getRecipeList()) {
            IRecipe r = (IRecipe) o;
            ItemStack res = r.getRecipeOutput();
            if (!same(res, item, meta)) continue;
            Laid l = lay(r);
            if (l != null) out.add(l);
        }
        return out;
    }

    static final Container NO_CONTAINER = new Container() {
        @Override public boolean canInteractWith(EntityPlayer p) { return true; }
    };

    /** what one craft of the recipe would take from the bag: bag index per grid cell, or null if the bag lacks something */
    static int[] pick(List<ItemStack> bag, Laid l) {
        int[] left = new int[bag.size()];
        for (int i = 0; i < bag.size(); i++) left[i] = bag.get(i).stackSize;
        int[] at = new int[l.cells.size()];
        for (int c = 0; c < l.cells.size(); c++) {
            at[c] = -1;
            List<ItemStack> ing = l.cells.get(c);
            if (ing == null) continue;
            for (int i = 0; i < bag.size() && at[c] < 0; i++)
                if (left[i] > 0) for (ItemStack opt : ing) if (same(bag.get(i), opt.getItem(), opt.getMetadata())) { at[c] = i; left[i]--; break; }
            if (at[c] < 0) return null;
        }
        return at;
    }

    /** one craft by the game's own rules, from the agent's bag into it; null when it does not come out */
    static ItemStack craftOnce(Agent a, WorldServer w, Laid l) {
        int[] at = pick(a.bag, l);
        if (at == null) return null;
        InventoryCrafting grid = new InventoryCrafting(NO_CONTAINER, 3, 3);
        for (int c = 0; c < at.length; c++) {
            if (at[c] < 0) continue;
            int slot = l.shapeless ? c : (c / l.width) * 3 + (c % l.width);
            ItemStack one = a.bag.get(at[c]).copy();
            one.stackSize = 1;
            grid.setInventorySlotContents(slot, one);
        }
        ItemStack res = CraftingManager.getInstance().findMatchingRecipe(grid, w);
        if (res == null || res.getItem() != l.out.getItem()) return null;
        FakePlayer p = hands(a, w);
        cpw.mods.fml.common.FMLCommonHandler.instance().firePlayerCraftingEvent(p, res, grid);
        for (int s = 0; s < 9; s++) {   // the ingredients are used up (a bucket and the like come back)
            ItemStack in = grid.getStackInSlot(s);
            if (in == null) continue;
            take(a, in.getItem(), in.getMetadata(), 1, Ledger.Way.INGREDIENT);
            if (in.getItem().hasContainerItem()) put(a, new ItemStack(in.getItem().getContainerItem()), Ledger.Way.CRAFTED);
        }
        put(a, res.copy(), Ledger.Way.CRAFTED);
        Ledger.act(a, "craft " + Ledger.name(res));
        return res;
    }

    static boolean nearRole(Agent a, WorldServer w, int[] at, Roles.Role role, double r) {
        return Roles.is(w, at, role) && Crew.dist(a, at[0] + 0.5, at[1], at[2] + 0.5) <= r;
    }

    // ---- tasks --------------------------------------------------------------------------------------------

    /** make n of an item (at least): by the 2x2 grid in the hands, or at the crafting table for the 3x3 ones */
    static class Craft implements Crew.Task {
        final Item item;
        final int meta, n;
        int made, ticks, state;
        Crew.GoTo walk;
        Craft(Item item, int meta, int n) { this.item = item; this.meta = meta; this.n = n; }
        public Result step(Agent a, WorldServer w) {
            List<Laid> rs = recipesFor(item, meta);
            if (rs.isEmpty()) return Result.NO_ITEMS;
            Laid l = null;
            for (Laid x : rs) if (pick(a.bag, x) != null) { l = x; break; }
            if (l == null) return made >= n ? Result.OK : made > 0 ? Result.OK : Result.NO_ITEMS;
            int[] t = Crew.instance == null ? null : Crew.instance.table;
            if (!l.small()) {   // the 3x3 grid: at the table
                if (!Roles.is(w, t, Roles.Role.TABLE)) return Result.NO_ITEMS;   // a station is missing
                if (!nearRole(a, w, t, Roles.Role.TABLE, 2.8)) {   // a table is everyone's at once: each stands at its own side
                    double ang = Math.toRadians(a.skin() * 72 + 20);
                    if (walk == null) walk = new Crew.GoTo(t[0] + 0.5 + Math.sin(ang) * 1.6, t[1], t[2] + 0.5 + Math.cos(ang) * 1.6, 0.6);
                    Result r = walk.step(a, w);
                    if (r == Result.RUNNING) return r;
                    walk = null;
                    if (r != Result.OK && !nearRole(a, w, t, Roles.Role.TABLE, 3.2)) return r;
                }
                Crew.face(a, t[0] + 0.5, t[1] + 0.9, t[2] + 0.5);
                a.act(t[0], t[1], t[2], 2);
            } else a.act((int) a.posX, (int) a.posY, (int) a.posZ, 2);
            if (++ticks % 6 == 0) a.swingItem();
            if (ticks < 14) return Result.RUNNING;   // a craft takes a moment
            ticks = 0;
            ItemStack r = craftOnce(a, w, l);
            if (r == null) return made > 0 ? Result.OK : Result.NO_ITEMS;
            made += r.stackSize;
            w.playSoundEffect(a.posX, a.posY + 1, a.posZ, "random.wood_click", 0.4F, 1.2F);
            if (!r.isStackable()) a.body.hold(lastOf(a, r.getItem()));
            return made >= n ? Result.OK : Result.RUNNING;
        }
    }

    /** what to hold when the hand is empty: a tool if there is one */
    static ItemStack bestHeld(Agent a) {
        for (ItemStack s : a.bag) if (s.getItem() instanceof net.minecraft.item.ItemTool) return s;
        return null;
    }

    static ItemStack lastOf(Agent a, Item item) {
        ItemStack l = null;
        for (ItemStack s : a.bag) if (s.getItem() == item) l = s;
        return l;
    }

    /** smelt n of an input in the crew's furnace: the input and enough fuel go in, the output comes out when done */
    static class Smelt implements Crew.Task {
        final Item in, fuel;
        final int inMeta, n;
        int state, ticks;
        Crew.GoTo walk;
        Smelt(Item in, int inMeta, int n, Item fuel) { this.in = in; this.inMeta = inMeta; this.n = n; this.fuel = fuel; }
        public Result step(Agent a, WorldServer w) {
            int[] f = Day1.furnace;
            if (f == null) return Result.NO_ITEMS;
            TileEntity te = w.getTileEntity(f[0], f[1], f[2]);
            if (!(te instanceof TileEntityFurnace)) return Result.GONE;
            TileEntityFurnace fu = (TileEntityFurnace) te;
            if (Crew.dist(a, f[0] + 0.5, f[1], f[2] + 0.5) > 2.8) {
                if (walk == null) walk = new Crew.GoTo(f[0] + 0.5, f[1], f[2] + 0.5, 2.3);
                Result r = walk.step(a, w);
                if (r == Result.RUNNING) return r;
                walk = null;
                if (r != Result.OK && Crew.dist(a, f[0] + 0.5, f[1], f[2] + 0.5) > 3.2) return r;
            }
            Crew.face(a, f[0] + 0.5, f[1] + 0.5, f[2] + 0.5);
            a.act(f[0], f[1], f[2], 2);
            ticks++;
            if (state == 0) {   // in go the input and the fuel for all of it
                if (!Crew.instance.board.station("furnace", a, 1)) return ticks > 20 * 60 ? Result.BLOCKED : Result.RUNNING;   // one inventory: one at a time
                if (fu.getStackInSlot(0) != null) { Crew.instance.board.leave("furnace", a); return Result.BLOCKED; }   // something else smelts
                int put = take(a, in, inMeta, n, Ledger.Way.INGREDIENT);
                if (put == 0) return Result.NO_ITEMS;
                fu.setInventorySlotContents(0, new ItemStack(in, put, Math.max(0, inMeta)));
                int burn = TileEntityFurnace.getItemBurnTime(new ItemStack(fuel));
                if (burn <= 0) return Result.NO_ITEMS;
                int fuelN = (int) Math.ceil(200.0 * put / burn);
                int gotFuel = take(a, fuel, -1, fuelN, Ledger.Way.FUEL);
                if (gotFuel == 0) { put(a, fu.getStackInSlot(0), Ledger.Way.CHEST_OUT); fu.setInventorySlotContents(0, null); return Result.NO_ITEMS; }
                fu.setInventorySlotContents(1, new ItemStack(fuel, gotFuel));
                fu.markDirty();
                a.swingItem();
                Ledger.act(a, "fill the furnace");
                state = 1;
                ticks = 0;
                return Result.RUNNING;
            }
            ItemStack out = fu.getStackInSlot(2);
            boolean doneIn = fu.getStackInSlot(0) == null;
            if (doneIn || ticks > 200 * n + 400) {   // all through (or the fuel ran out): what came out is taken
                if (out != null) {
                    fu.setInventorySlotContents(2, null);
                    put(a, out, Ledger.Way.SMELTED);
                    a.swingItem();
                    Ledger.act(a, "take from the furnace");
                }
                fu.markDirty();
                Crew.instance.board.leave("furnace", a);
                return out == null ? Result.TIMEOUT : Result.OK;
            }
            return Result.RUNNING;
        }
    }

    /**
     * Place one block from the bag as a player does: from where it stands when its eyes see a face to put it against
     * in reach; else it goes where they do (Sight finds the place: a cell it walks to, the top of a pillar of its own
     * blocks, or a few steps off that top, onto what is up there), puts it from there and takes its pillar down after.
     * With no such place it fails with the reason (NOT_VISIBLE, OUT_OF_REACH), never puts it blind.
     */
    static class PlaceFrom implements Crew.Task {
        int x, y, z;   // where it goes (a station's cell may move: station)
        final int meta;
        final Item item;
        int aside, tries, maxPillar = 4;
        /** a station (a furnace, a table, a chest) told where it went when its own cell would not do */
        interface Moved { void to(int[] cell); }
        Moved moved;
        int moves;
        int[] origin;
        final java.util.Set<Long> tried = new java.util.HashSet<Long>();

        /** a station's block: if it cannot go into its cell, into a cell beside it where it is seen to go (Moved says where) */
        PlaceFrom station(Moved m) {
            moved = m;
            origin = new int[] { x, y, z };
            tried.add(Crew.key(x, y, z));
            return this;
        }

        /** a failure, with why and where, said and kept for the crew's line; a station tries a cell beside its own first */
        Result fail(Agent a, WorldServer w, Result r, String reason) {
            String what = Ledger.name(new ItemStack(item));
            Events.log("crew: " + a.getCustomNameTag() + " cannot put " + what + " at " + x + " " + y + " " + z + ": " + reason + " (" + r + ")");
            a.why(r + ", " + reason + ", " + x + " " + y + " " + z);
            if (moved == null || moves >= 3) return r;
            int[] c = besideCell(a, w);
            if (c == null) { Events.log("crew: no other cell for " + what + " near " + origin[0] + " " + origin[1] + " " + origin[2]); return r; }
            moves++;
            x = c[0]; y = c[1]; z = c[2];
            moved.to(c);
            view = null; went = null; go = null; bad.clear(); tries = 0; aside = 0;
            a.why = null;
            Events.log("crew: " + a.getCustomNameTag() + " puts " + what + " at " + x + " " + y + " " + z + " instead (beside its planned cell)");
            return Result.RUNNING;
        }

        /** a free cell on firm ground beside the station's planned one, not the crew's other stations', that the eyes are seen to reach (Sight) */
        int[] besideCell(Agent a, WorldServer w) {
            List<int[]> cells = new ArrayList<int[]>();
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++)
                    for (int dy = -1; dy <= 1; dy++) {
                        int cx = origin[0] + dx, cy = origin[1] + dy, cz = origin[2] + dz;
                        if (tried.contains(Crew.key(cx, cy, cz)) || !Crew.soft(w, cx, cy, cz) || !Crew.solid(w, cx, cy - 1, cz)) continue;
                        if (w.getBlock(cx, cy, cz).getMaterial().isLiquid() || station(cx, cy, cz)) continue;
                        if (!w.getEntitiesWithinAABBExcludingEntity(null, net.minecraft.util.AxisAlignedBB.getBoundingBox(cx, cy, cz, cx + 1, cy + 1, cz + 1)).isEmpty()) continue;
                        cells.add(new int[] { cx, cy, cz });
                    }
            final int[] o = origin;
            java.util.Collections.sort(cells, (p, q) -> (Math.abs(p[0] - o[0]) + Math.abs(p[2] - o[2]) + Math.abs(p[1] - o[1]) * 2) - (Math.abs(q[0] - o[0]) + Math.abs(q[2] - o[2]) + Math.abs(q[1] - o[1]) * 2));
            int sx = x, sy = y, sz = z, checks = 0;
            for (int[] c : cells) {
                if (checks++ >= 10) break;   // (each a sight search)
                tried.add(Crew.key(c[0], c[1], c[2]));
                x = c[0]; y = c[1]; z = c[2];
                view = null;
                if (plan(a, w) != null) { x = sx; y = sy; z = sz; view = null; return c; }
            }
            x = sx; y = sy; z = sz;
            view = null;
            return null;
        }

        /** who stands in a cell (for the line that says why a block was not put) */
        static String occupants(WorldServer w, int x, int y, int z) {
            StringBuilder b = new StringBuilder();
            for (Object o : w.getEntitiesWithinAABBExcludingEntity(null, net.minecraft.util.AxisAlignedBB.getBoundingBox(x, y, z, x + 1, y + 2, z + 1))) {
                net.minecraft.entity.Entity e = (net.minecraft.entity.Entity) o;
                if (e.isDead || e instanceof net.minecraft.entity.item.EntityItem || e instanceof net.minecraft.entity.item.EntityXPOrb) continue;
                b.append(b.length() > 0 ? ", " : "").append(e.getCommandSenderName());
            }
            return b.length() == 0 ? "someone (gone now)" : b.toString();
        }

        /** one of the crew's stations stands (or is to stand) there */
        static boolean station(int x, int y, int z) {
            Crew c = Crew.instance;
            for (int[] s : new int[][] { c == null ? null : c.chest, c == null ? null : c.table, Day1.furnace })
                if (s != null && s[0] == x && s[1] == y && s[2] == z) return true;
            return false;
        }
        boolean here;              // from where it stands only (no walking, no pillar)
        Sight.Walk penalty;        // what standing somewhere costs besides the walk (a builder outside its walls)
        java.util.Set<Long> avoid; // cells not to stand in nor build a pillar in (the plan's own)
        final java.util.Set<Long> bad = new java.util.HashSet<Long>();   // places it stood in and still did not see it from
        Sight.View view, went;
        Crew.Task go;
        Result why = Result.NOT_VISIBLE;
        PlaceFrom(int x, int y, int z, Item item, int meta) { this.x = x; this.y = y; this.z = z; this.item = item; this.meta = meta; }
        PlaceFrom here() { here = true; maxPillar = 0; return this; }
        PlaceFrom pillar(int n) { maxPillar = n; return this; }
        PlaceFrom prefer(Sight.Walk p) { penalty = p; return this; }
        PlaceFrom avoid(java.util.Set<Long> cells) { avoid = cells; return this; }

        /** where to stand to put it (null: nowhere it can get to); worked out once, again after a place that did not do */
        Sight.View plan(Agent a, final WorldServer w) {
            if (view != null) return view;
            final int[] f = Ground.feet(w, a.posX, a.posY, a.posZ);
            final Ground.Flood fl = Ground.flood(w, f, 32, null);
            final Sight.Walk extra = penalty;
            Sight.Walk walk = (cx, cy, cz) -> {
                long k = Crew.key(cx, cy, cz);
                if (!fl.has(cx, cy, cz) || bad.contains(k) || avoid != null && (avoid.contains(k) || avoid.contains(Crew.key(cx, cy + 1, cz)))) return -1;
                double c = Math.abs(cx - f[0]) + Math.abs(cz - f[2]) + Math.abs(cy - f[1]) * 2;
                return extra == null ? c : c + Math.max(0, extra.cost(cx, cy, cz));
            };
            Sight.Space s = Body.space(w);
            if (avoid != null && maxPillar > 0) {   // a pillar is not built in the plan's cells
                Sight.Overlay o = new Sight.Overlay(s);
                o.banned.addAll(avoid);
                s = o;
            }
            int pillar = Math.min(maxPillar, Scaffold.blocks(a, item));
            view = Sight.find(s, walk, x, y, z, item instanceof net.minecraft.item.ItemDoor ? 2 : 1, supports(w, x, y, z, item instanceof net.minecraft.item.ItemDoor), Crew.REACH, pillar);
            return view;
        }

        long last = -1;

        public Result step(Agent a, WorldServer w) {
            long now = w.getTotalWorldTime();
            if (last >= 0 && now - last > 1 && go == null) { view = null; went = null; }   // it waited for a reaction and may be elsewhere now: the place worked out again
            last = now;
            if (go != null) {   // on the way to the place it sees it from (and, on a pillar, done there)
                Result r = go.step(a, w);
                if (r == Result.RUNNING) return r;
                Crew.Task was = go;
                go = null;
                if (was instanceof Scaffold) {   // put up there (or not), the pillar taken down
                    if (r.failed() && r != Result.NO_ITEMS) return fail(a, w, r, "not put from the pillar");
                    return r;
                }
                if (r.failed() && ++tries > 3) return fail(a, w, r, "no way to the place it would see it from");
                went = view;
            }
            Result r = a.body.place(w, x, y, z, item, meta);
            if (r == Result.RUNNING || r == Result.OK || r == Result.GONE || r == Result.NO_ITEMS) { if (r != Result.RUNNING) aside = 0; return r; }
            if (r == Result.BUSY) return ++aside > 20 * 15 ? fail(a, w, Result.BLOCKED, "the cell is taken by " + occupants(w, x, y, z)) : Result.RUNNING;   // someone is moving out of the way
            aside = 0;
            if (r == Result.BLOCKED) return fail(a, w, r, "nothing to put it against");
            why = r;   // not seen from here: go where it is
            if (here) return r;
            if (went != null) bad.add(Crew.key(went.stand[0], went.stand[1], went.stand[2]));   // stood there and did not see it after all
            went = null;
            view = null;
            if (++tries > 3 || plan(a, w) == null)
                return fail(a, w, why, why == Result.OUT_OF_REACH ? "no place it gets to has it in reach" : "no place it gets to sees a face to put it against");
            Sight.View v = view;
            if (v.pillar == 0) go = new Crew.GoTo(v.stand[0] + 0.5, v.stand[1], v.stand[2] + 0.5, 0.4);
            else {
                Events.log("crew: " + a.getCustomNameTag() + " goes up to put " + Ledger.name(new ItemStack(item)) + " at " + x + " " + y + " " + z + ": " + v);
                Crew.Task up = v.steps > 0 ? new TaskBoard.Seq(new Crew.GoTo(v.stand[0] + 0.5, v.stand[1], v.stand[2] + 0.5, 0.4).walkOnly(), new PlaceFrom(x, y, z, item, meta).here())
                        : new PlaceFrom(x, y, z, item, meta).here();
                go = new Scaffold(v.foot, v.pillar, null, up, item);
            }
            return Result.RUNNING;
        }
    }

    /**
     * Up on a pillar as a player goes up: at its foot it looks down, jumps and puts a block from the bag under its feet,
     * again, as high as asked (or until the condition holds); does its work up there; comes back onto the pillar's top
     * and digs it down under itself, picking the blocks up. The pillar taken down is the work's undo: it is done
     * whatever the work came to; one it could not take down is told.
     */
    static class Scaffold implements Crew.Task {
        interface Until { boolean ok(Agent a); }
        final int[] foot;
        final int height;
        final Until until;
        final Crew.Task work;
        final Item keep;     // the item the work puts: not used for the pillar while there is something else
        int state, built, ticks, landed;
        Crew.Task walk;
        Result result;
        boolean stay;        // left standing when up (a reflex's climb): owed to the job, taken down by the undo reflex
        Scaffold(int[] foot, int height, Until until, Crew.Task work, Item keep) { this.foot = foot; this.height = height; this.until = until; this.work = work; this.keep = keep; }
        Scaffold keep() { stay = true; return this; }

        /** something solid to stand on (dirt and stone first), keeping one of the item that is to be put */
        static ItemStack block(Agent a, Item keep) {
            ItemStack best = null;
            for (ItemStack s : a.bag) {
                if (!(s.getItem() instanceof ItemBlock) || s.getItem() == keep && s.stackSize <= 1) continue;
                Block b = Block.getBlockFromItem(s.getItem());
                if (!b.isOpaqueCube() || !b.getMaterial().isSolid() || b instanceof net.minecraft.block.ITileEntityProvider || b == Blocks.gravel || b == Blocks.sand) continue;
                if (best == null || b == Blocks.dirt || b == Blocks.cobblestone || best.getItem() == keep) best = s;
            }
            return best;
        }

        /** how many pillar blocks the bag has (one of the item to be put kept back) */
        static int blocks(Agent a, Item keep) {
            int n = 0;
            for (ItemStack s : a.bag) {
                if (!(s.getItem() instanceof ItemBlock)) continue;
                Block b = Block.getBlockFromItem(s.getItem());
                if (!b.isOpaqueCube() || !b.getMaterial().isSolid() || b instanceof net.minecraft.block.ITileEntityProvider || b == Blocks.gravel || b == Blocks.sand) continue;
                n += s.stackSize - (s.getItem() == keep ? 1 : 0);
            }
            return Math.max(0, n);
        }

        boolean onTop(Agent a) {
            return Math.abs(a.posX - (foot[0] + 0.5)) < 0.45 && Math.abs(a.posZ - (foot[2] + 0.5)) < 0.45 && Math.abs(a.posY - (foot[1] + built)) < 0.6;
        }

        public Result step(Agent a, WorldServer w) {
            ticks++;
            if (state >= 1) a.guarded(70);   // up there only survival and a fall interrupt it (not a torch, not someone's block)
            if (state < 3 && ticks > 20 * 120) { result = Result.TIMEOUT; state = 3; }
            if (state == 0) {   // to its foot
                if (walk == null) walk = new Crew.GoTo(foot[0] + 0.5, foot[1], foot[2] + 0.5, 0.3);
                Result r = walk.step(a, w);
                if (r == Result.RUNNING) return r;
                walk = null;
                if (r.failed()) return r;
                state = 1;
            }
            if (state == 1) {   // up: a block under its feet at the top of each jump
                if (built >= height || until != null && until.ok(a)) { state = 2; return Result.RUNNING; }
                int cell = foot[1] + built;
                if (!Ground.passable(w, foot[0], cell + 2, foot[2], null)) { result = Result.BLOCKED; state = 3; return Result.RUNNING; }   // its head would hit something
                ItemStack s = block(a, keep);
                if (s == null) { result = Result.NO_ITEMS; state = 3; return Result.RUNNING; }   // out of blocks: down again
                a.body.stepTo(foot[0] + 0.5, a.posY, foot[2] + 0.5, 0.6);
                a.body.lookAt(foot[0] + 0.5, cell - 0.01, foot[2] + 0.5);   // down at what it stands on, before the jump
                if (a.onGround && a.posY < cell + 0.5) a.body.jump();
                if (a.posY >= cell + 1.05) {   // over the cell: the block under its feet
                    Result r = a.body.place(w, foot[0], cell, foot[2], s.getItem(), s.getMetadata());
                    if (r == Result.OK) { built++; Ledger.act(a, "pillar up"); a.owe(Undo.Kind.REMOVE, foot[0], cell, foot[2], 0, 0, "pillar"); }   // owed back until taken down
                }
                return Result.RUNNING;
            }
            if (state == 2) {   // the work up there
                Result r = work == null ? Result.OK : work.step(a, w);
                if (r == Result.RUNNING) return r;
                result = r;
                if (stay) return r;   // up, and stays up: the pillar is owed
                state = 3;
            }
            if (state == 3) {   // back onto the pillar's top (it may have stepped off it)
                if (built <= 0) return result;
                if (!onTop(a)) {
                    if (walk == null) walk = new Crew.GoTo(foot[0] + 0.5, foot[1] + built, foot[2] + 0.5, 0.3).walkOnly();
                    Result r = walk.step(a, w);
                    if (r == Result.RUNNING) return r;
                    walk = null;
                    if (!onTop(a)) {
                        Events.log("crew: " + a.getCustomNameTag() + " cannot get back onto its pillar at " + foot[0] + " " + foot[1] + " " + foot[2] + ": " + built + " blocks of it left standing");
                        return result;
                    }
                }
                state = 4;
            }
            // down: the block under its feet, one by one, the blocks picked up
            Day1.catchNear(a, w, 1.5);
            if (built <= 0) return ++landed > 12 ? result : Result.RUNNING;   // the last one's drop lands first
            int under = foot[1] + built - 1;
            if (!a.onGround) return Result.RUNNING;
            Result r = a.body.dig(w, foot[0], under, foot[2]);
            if (r == Result.RUNNING) return r;
            if (r == Result.OK) { built--; Ledger.act(a, "pillar down"); a.paid(foot[0], under, foot[2]); return Result.RUNNING; }
            Events.log("crew: " + a.getCustomNameTag() + " cannot take its pillar down at " + foot[0] + " " + under + " " + foot[2] + " (" + r + "): " + built + " blocks of it left standing");
            return result;
        }
    }

    /** hand things over: to another agent (its bag) or the player (thrown at them, they pick it up) */
    static class Give implements Crew.Task {
        final Agent to;
        final boolean player;
        final Item item;
        final int n;
        int ticks, caught = -1;
        Crew.GoTo walk;
        Crew.Task fetch;
        boolean fetched;
        Give(Agent to, Item item, int n) { this.to = to; this.player = false; this.item = item; this.n = n; }
        Give(Item item, int n) { this.to = null; this.player = true; this.item = item; this.n = n; }
        public Result step(Agent a, WorldServer w) {
            net.minecraft.entity.Entity t = player ? Crew.instance.playerIn(w) : to;
            if (t == null || t.isDead) return Result.GONE;
            if (caught >= 0) {   // thrown to an agent: it turns, the things land, it picks them up
                to.body.stop();
                Crew.face(to, a.posX, a.posY + 1.4, a.posZ);
                if (++caught < 26) return Result.RUNNING;
                Day1.catchNear(to, w, 3.0);
                return Result.OK;
            }
            if (fetch != null) {   // what the bag lacks, from the chest first
                Result r = fetch.step(a, w);
                if (r == Result.RUNNING) return r;
                fetch = null;
                fetched = true;
            }
            if (!fetched && count(a.bag, item, -1) < n && Crew.instance != null && Crew.instance.chestInv(w) != null) {
                fetch = new Day1.Take(Crew.instance, item, n - count(a.bag, item, -1));
                return Result.RUNNING;
            }
            if (count(a.bag, item, -1) == 0) return Result.NO_ITEMS;
            if (Crew.dist(a, t.posX, t.boundingBox.minY, t.posZ) > 2.8) {
                if (walk == null || ticks % 30 == 0) walk = new Crew.GoTo(t.posX, t.boundingBox.minY, t.posZ, 2.3);
                ticks++;
                Result r = walk.step(a, w);
                if (r == Result.RUNNING || r == Result.OK) return Result.RUNNING;
                return r;
            }
            a.body.stop();
            Crew.face(a, t.posX, t.posY + 1.4, t.posZ);
            if (++ticks < 10) return Result.RUNNING;
            List<ItemStack> keep = new ArrayList<ItemStack>(a.bag);   // throw just this item
            a.bag.clear();
            int left = n;
            for (ItemStack s : keep) {
                if (left > 0 && same(s, item, -1)) {
                    int k = Math.min(left, s.stackSize);
                    ItemStack g = s.copy();
                    g.stackSize = k;
                    a.bag.add(g);
                    left -= k;
                    s.stackSize -= k;
                }
            }
            Day1.toss(a, t.posX, t.posY + 1.0, t.posZ, w, null);
            for (ItemStack s : keep) if (s.stackSize > 0) a.bag.add(s);
            if (a.getHeldItem() == null) a.body.hold(bestHeld(a));
            if (player) return Result.OK;   // the player picks it up himself
            caught = 0;
            return Result.RUNNING;
        }
    }
}
