package hardelele.aiguide.client;

/** Geometry decisions for entering the crew's own house at night. No world state is changed here. */
final class ShelterRoute {
    private ShelterRoute() { }

    /** The full base has a fence with a three-cell gap centered ahead of the house door. */
    static int[] gate(int[] site, int size, int[] doorDir) {
        if (site == null || size < 2 || doorDir == null) return null;
        int half = 3, x0 = site[0] + 2 - half, z0 = site[2] + 2 - half;
        int centerX = x0 + half, centerZ = z0 + half;
        return new int[] { doorDir[0] < 0 ? x0 - 5 : doorDir[0] > 0 ? x0 + 2 * half + 5 : centerX,
                site[1], doorDir[1] < 0 ? z0 - 5 : doorDir[1] > 0 ? z0 + 2 * half + 5 : centerZ };
    }

    static boolean outsideFence(double x, double z, int[] site, int size) {
        if (site == null || size < 2) return false;
        int x0 = site[0] - 6, z0 = site[2] - 6;
        int x1 = x0 + 16, z1 = z0 + 16;
        return x < x0 + 1 || x >= x1 || z < z0 + 1 || z >= z1;
    }

    /** Count a body only when its feet are on the house floor, inside the wall, not on the roof or in the doorway. */
    static boolean inside(double x, double y, double z, int[] site, int size) {
        if (site == null) return false;
        int half = size == 0 ? 2 : 3;
        int x0 = site[0] + 2 - half, z0 = site[2] + 2 - half;
        int fx = (int) Math.floor(x), fz = (int) Math.floor(z);
        return fx > x0 && fx < x0 + 2 * half && fz > z0 && fz < z0 + 2 * half
                && y >= site[1] - 0.2 && y < site[1] + 0.9;
    }

    static boolean atDoor(double x, double y, double z, int[] door) {
        return door != null && (int) Math.floor(x) == door[0] && (int) Math.floor(z) == door[2]
                && y >= door[1] - 0.2 && y < door[1] + 0.9;
    }

    /** A lower doorway step needs a drop from the furnace, not another jump into its low roof. */
    static boolean descendThroughDoor(boolean shelterEntry, boolean door, int waypointY, double feetY) {
        return shelterEntry && door && waypointY < feetY - 0.2;
    }

    /** The staged doorway may lose only its single headroom plank, never the door, roof or another wall. */
    static boolean oneOpening(int x, int y, int z, int[] door) {
        return door != null && x == door[0] && y == door[1] + 2 && z == door[2];
    }
}
