package hardelele.aiguide.client;

/** Tiny geometry controls from the saved 2026-11-07 house; no game or world mutation. */
public final class ShelterRouteTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    public static void main(String[] args) {
        int[] site = {-146, 69, 179}, door = {-144, 69, 178};
        int[] gate = ShelterRoute.gate(site, 2, new int[] {0, -1});
        check(gate[0] == -144 && gate[1] == 69 && gate[2] == 173, "existing north fence gap shifted");
        check(ShelterRoute.outsideFence(-135.07, 178.5, site, 2), "Agent 2 must approach the gate");
        check(!ShelterRoute.outsideFence(-144.05, 177.69, site, 2), "Agent 4 is already within the fence");
        check(ShelterRoute.gate(site, 1, new int[] {0, -1}) == null, "house-only plan has no fence");

        check(!ShelterRoute.inside(-144.05, 70.0, 177.69, site, 2), "outside furnace is not shelter");
        check(!ShelterRoute.inside(-144.5, 73.0, 180.5, site, 2), "roof is not shelter");
        check(ShelterRoute.inside(-144.5, 69.0, 180.5, site, 2), "interior floor must count");
        check(!ShelterRoute.atDoor(-144.05, 70.0, 177.69, door), "furnace edge is not a door crossing");
        check(ShelterRoute.atDoor(-143.5, 69.0, 178.5, door), "body in original door cell must count");
        check(ShelterRoute.oneOpening(-144, 71, 178, door), "single plank above door must be eligible");
        check(!ShelterRoute.oneOpening(-144, 72, 178, door), "roof plank must stay");
        check(!ShelterRoute.oneOpening(-144, 69, 178, door), "door itself must stay");
        check(!ShelterRoute.oneOpening(-143, 71, 178, door), "side wall must stay");

        check(ShelterRoute.descendThroughDoor(true, true, 69, 70.0), "furnace-to-door step must not jump");
        check(!ShelterRoute.descendThroughDoor(false, true, 69, 70.0), "ordinary orders keep old jumping");
        check(!ShelterRoute.descendThroughDoor(true, false, 69, 70.0), "ordinary obstacle keeps jumping");
        check(!ShelterRoute.descendThroughDoor(true, true, 70, 70.0), "level door step keeps normal movement");
        check(ShelterRoute.holdTicks(11000, true, 1200) == 12500, "dusk entry stays until morning");
        check(ShelterRoute.holdTicks(13000 + 24000 * 5L, true, 1200) == 10500, "a later day counts by the time of day");
        check(ShelterRoute.holdTicks(1000, true, 1200) == 1200, "daytime entry keeps the short wait");
        check(ShelterRoute.holdTicks(23600, true, 1200) == 1200, "after the morning there is no night to wait for");
        check(ShelterRoute.holdTicks(13000, false, 1200) == 1200, "a frozen clock must not hold forever");
        System.out.println("ShelterRouteTest PASS: gate, interior, single opening, door descent negatives");
    }
}
