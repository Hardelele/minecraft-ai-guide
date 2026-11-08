package hardelele.aiguide.client;

import java.util.List;

/** The places' input channels by block names (as the training's) and a cube's split into places, with no game. */
public class PlacesSplitTest {
    static int bad;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) bad++;
    }

    public static void main(String[] args) {
        classes();
        split();
        System.exit(bad == 0 ? 0 : 1);
    }

    static void classes() {
        String[][] cases = {
            { "minecraft:air", "air" }, { "minecraft:stone", "stone" }, { "minecraft:cobblestone", "built_solid" }, { "minecraft:mossy_cobblestone", "built_solid" },
            { "minecraft:dirt", "ground" }, { "minecraft:grass", "ground" }, { "minecraft:tallgrass", "plant" }, { "minecraft:iron_ore", "ore" },
            { "minecraft:log", "wood_natural" }, { "minecraft:leaves2", "wood_natural" }, { "minecraft:water", "water" }, { "minecraft:flowing_lava", "lava" },
            { "minecraft:fire", "lava" }, { "minecraft:wooden_door", "door" }, { "minecraft:fence_gate", "door" }, { "minecraft:fence", "fence" },
            { "minecraft:cobblestone_wall", "fence" }, { "minecraft:ladder", "climb" }, { "minecraft:vine", "climb" }, { "minecraft:torch", "light" },
            { "minecraft:chest", "storage" }, { "minecraft:furnace", "storage" }, { "minecraft:crafting_table", "storage" }, { "minecraft:rail", "air" },
            { "minecraft:carpet", "air" }, { "minecraft:snow_layer", "air" }, { "minecraft:snow", "ground" }, { "minecraft:mob_spawner", "other_solid" },
            { "minecraft:something_new", "other_solid" }, { "minecraft:planks", "built_solid" }, { "minecraft:glass_pane", "built_solid" },
            // modded, by the name rules
            { "IC2:blockOreCopper", "ore" }, { "Railcraft:residual.heat", "air" }, { "appliedenergistics2:tile.BlockSkyStone", "stone" },
            { "BiomesOPlenty:flowers", "plant" }, { "chisel:marble", "stone" }, { "IC2:blockRubWood", "wood_natural" }, { "Forestry:fluid.honey", "water" },
            { "IronChest:BlockIronChest", "storage" }, { "BuildCraft|Transport:pipeBlock", "built_solid" }, { "somemod:weird_thing", "other_solid" },
            { "BiomesOPlenty:mud", "ground" }, { "Thaumcraft:blockCustomPlant", "plant" }, { "Natura:Dark Tree", "other_solid" },
        };
        int wrong = 0;
        for (String[] c : cases) {
            String got = PlacesClasses.name(PlacesClasses.byName(c[0]));
            if (!got.equals(c[1])) { wrong++; System.out.println("  " + c[0] + ": " + got + ", not " + c[1]); }
        }
        check(wrong == 0, "channels by block names (" + cases.length + " blocks)");
        check(PlacesClasses.channel("somemod:goo", true, false, false, false) == PlacesClasses.WATER, "an unknown modded liquid is water");
        check(PlacesClasses.channel("somemod:goo", true, true, false, false) == PlacesClasses.LAVA, "a hot one is lava");
        check(PlacesClasses.channel("somemod:thing", false, false, false, false) == PlacesClasses.AIR, "an unknown modded block nothing collides with is air");
        check(PlacesClasses.channel("somemod:thing", false, false, true, true) == PlacesClasses.ORE, "an unknown modded ore of the ore dictionary is ore");
        check(PlacesClasses.channel("somemod:thing", false, false, true, false) == PlacesClasses.OTHER, "else other_solid");
        check(PlacesClasses.channel("minecraft:something_new", true, false, false, false) == PlacesClasses.OTHER, "vanilla stays by its table (as the training's)");
        check(PlacesClasses.channel("IC2:blockOreTin", false, false, true, false) == PlacesClasses.ORE, "the name rules first");
        boolean see = PlacesClasses.SEE_THROUGH[PlacesClasses.AIR] && PlacesClasses.SEE_THROUGH[PlacesClasses.FENCE] && !PlacesClasses.SEE_THROUGH[PlacesClasses.BUILT]
                && !PlacesClasses.SEE_THROUGH[PlacesClasses.UNSEEN] && !PlacesClasses.SEE_THROUGH[PlacesClasses.WOOD];
        check(see, "sight through air and fences, not through walls, leaves or the unseen");
    }

    static int at(int x, int y, int z) { return (x * 32 + y) * 32 + z; }

    static void split() {
        int n = PlacesNet.CELLS;
        byte[] cls = new byte[n], input = new byte[n];
        float[] prob = new float[n], bott = new float[PlacesNet.EMB * 512];
        java.util.Arrays.fill(prob, 0.5f);
        java.util.Arrays.fill(input, (byte) PlacesClasses.AIR);
        // a room 4x3x5 at 2,2,2; a passage 1x2x10 at 20,5,0..9; a junction of 2 cells (too small); a room 3x3x3 at 10,10,10 half unseen
        for (int x = 2; x < 6; x++) for (int y = 2; y < 5; y++) for (int z = 2; z < 7; z++) { cls[at(x, y, z)] = 3; prob[at(x, y, z)] = 0.9f; }
        for (int y = 5; y < 7; y++) for (int z = 0; z < 10; z++) cls[at(20, y, z)] = 2;
        cls[at(25, 25, 25)] = 6; cls[at(25, 25, 26)] = 6;
        for (int x = 10; x < 13; x++) for (int y = 10; y < 13; y++) for (int z = 10; z < 13; z++) { cls[at(x, y, z)] = 3; if (x == 12) input[at(x, y, z)] = PlacesClasses.UNSEEN; }
        // a second passage touching the first only by an edge (not a face): a place of its own
        for (int x = 21; x < 24; x++) for (int y = 7; y < 10; y++) for (int z = 0; z < 3; z++) cls[at(x, y, z)] = 2;
        // the bottleneck: channel 0 is 1 in the coarse cell of the first room, channel 1 elsewhere
        for (int b = 0; b < 512; b++) bott[512 + b] = 1;
        bott[0 * 512 + ((0 * 8 + 0) * 8 + 0)] = 5;
        List<PlacesSplit.Place> places = PlacesSplit.split(cls, prob, input, bott, new int[] { 64, -32, 96 });
        check(places.size() == 4, "four places (the junction too small, solid no place): " + places);
        PlacesSplit.Place room = null, half = null, passage = null;
        for (PlacesSplit.Place p : places) {
            if (p.cls == 3 && p.volume == 60) room = p;
            if (p.cls == 3 && p.volume == 18) half = p;
            if (p.cls == 2 && p.volume == 20) passage = p;
        }
        check(room != null && half != null && passage != null, "the room of 60, the half seen room of 18 (the unseen cells not in it), the passage of 20");
        if (room == null || passage == null) return;
        check(room.min[0] == 66 && room.min[1] == -30 && room.min[2] == 98 && room.max[0] == 69 && room.max[1] == -28 && room.max[2] == 102, "the room's box in world cells");
        check(Math.abs(room.center[0] - 68.0) < 1e-9 && Math.abs(room.center[1] - (-28.5)) < 1e-9 && Math.abs(room.center[2] - 100.5) < 1e-9, "its centre");
        check(Math.abs(room.confidence - 0.9f) < 1e-6 && Math.abs(passage.confidence - 0.5f) < 1e-6, "the mean softmax");
        double norm = 0;
        for (float v : room.embedding) norm += v * v;
        check(Math.abs(norm - 1) < 1e-5, "the embedding L2-normed");
        // the room's cells x 2..5, y 2..4, z 2..6: in coarse cells x 0..1, y 0..1, z 0..1; of its 60 cells 2*2*2=8 in the coarse cell 0,0,0
        float c0 = (8 * 5 + 52 * 0) / 60f, c1 = 1;
        double len = Math.sqrt(c0 * c0 + c1 * c1);
        check(Math.abs(room.embedding[0] - c0 / len) < 1e-5 && Math.abs(room.embedding[1] - c1 / len) < 1e-5, "the embedding: the mean of the bottleneck over the cells");
        check(PlacesSplit.summary(places).equals("passage 2, room 2"), "the summary by class: " + PlacesSplit.summary(places));
        byte[] none = new byte[n];
        check(PlacesSplit.split(none, null, null, null, new int[3]).isEmpty(), "all solid: no places");
        byte[] full = new byte[n];
        java.util.Arrays.fill(full, (byte) 1);
        List<PlacesSplit.Place> one = PlacesSplit.split(full, null, null, null, new int[3]);
        check(one.size() == 1 && one.get(0).volume == n, "a cube all open: one place of the whole cube");
    }
}
