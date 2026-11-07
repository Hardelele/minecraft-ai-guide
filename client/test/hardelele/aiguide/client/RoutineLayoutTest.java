package hardelele.aiguide.client;

/** Save and cancel remain clickable in the small GUI scale used by the Commander. */
public final class RoutineLayoutTest {
    private static void check(int screenH) {
        int top = Commander.RoutineLayout.top(screenH);
        int bottom = Commander.RoutineLayout.bottomY(screenH);
        int lastField = Commander.RoutineLayout.fieldY(screenH, 4) + (Commander.RoutineLayout.compact(screenH) ? 14 : 16);
        if (top < 0 || bottom + 15 > screenH || bottom <= lastField + 15)
            throw new AssertionError("dialog controls clipped or overlapping at height " + screenH);
    }

    public static void main(String[] args) {
        check(225);
        check(294);
        check(600);
        System.out.println("RoutineLayoutTest PASS");
    }
}
