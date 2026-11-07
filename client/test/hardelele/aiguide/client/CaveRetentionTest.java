package hardelele.aiguide.client;

public final class CaveRetentionTest {
    public static void main(String[] args) {
        if (!CaveRetention.hold(true, true, 5722, 200, 240)) throw new AssertionError("transient invalid start erased valid mesh");
        if (CaveRetention.hold(true, true, 5722, 200, 281)) throw new AssertionError("stale mesh kept past retry window");
        if (CaveRetention.hold(false, true, 5722, 200, 240)) throw new AssertionError("old world mesh leaked");
        if (CaveRetention.hold(true, false, 5722, 200, 240)) throw new AssertionError("old agent mesh leaked");
        if (CaveRetention.hold(true, true, 0, 200, 240)) throw new AssertionError("empty mesh held");
        if (CaveRetention.hold(true, true, 5722, -1, 240)) throw new AssertionError("unverified mesh held");
        System.out.println("CaveRetentionTest PASS");
    }
}
