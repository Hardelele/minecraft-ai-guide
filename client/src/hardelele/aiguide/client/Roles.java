package hardelele.aiguide.client;

import net.minecraft.block.Block;
import net.minecraft.block.BlockChest;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockFurnace;
import net.minecraft.block.BlockWorkbench;
import net.minecraft.world.WorldServer;

/**
 * What a block is for the crew, one answer for every check: a furnace (burning or cold, any block of the furnace's
 * kind), a crafting table, a chest, a door. Every "is the station there" in the crew's code asks this, never a
 * particular block.
 */
class Roles {
    enum Role { NONE, FURNACE, TABLE, CHEST, DOOR }

    static Role of(Block b) {
        if (b instanceof BlockFurnace) return Role.FURNACE;
        if (b instanceof BlockWorkbench) return Role.TABLE;
        if (b instanceof BlockChest) return Role.CHEST;
        if (b instanceof BlockDoor) return Role.DOOR;
        return Role.NONE;
    }

    static boolean is(WorldServer w, int[] at, Role r) {
        return at != null && of(w.getBlock(at[0], at[1], at[2])) == r;
    }

    static boolean is(WorldServer w, int x, int y, int z, Role r) {
        return of(w.getBlock(x, y, z)) == r;
    }
}
