package hardelele.aiguide.client;

/** The underground view's fade, follow and hysteresis maths; runs without Minecraft or OpenGL. */
public final class CaveFadeTest {
    static void check(boolean v, String m) { if (!v) throw new AssertionError(m); }
    static boolean near(double a, double b) { return Math.abs(a - b) < 1e-9; }

    public static void main(String[] args) {
        double R = CaveFade.RADIUS;
        check(R >= 10 && R <= 14, "radius out of the 10..14 range");
        // clarity: fully clear in the core, nothing at the radius, monotone and smooth between
        check(near(CaveFade.clarity(0, R), 1), "agent's own cell is not clear");
        check(near(CaveFade.clarity(CaveFade.CLEAR * R, R), 1), "core edge is not clear");
        check(near(CaveFade.clarity(R, R), 0) && near(CaveFade.clarity(R * 3, R), 0), "rock beyond the radius is not opaque");
        check(near(CaveFade.clarity(R * (CaveFade.CLEAR + 1) / 2, R), 0.5), "smoothstep midpoint is not 0.5");
        double prev = 2, maxStep = 0;
        for (int i = 0; i <= 1000; i++) {
            double c = CaveFade.clarity(R * i / 1000.0, R);
            check(c <= prev + 1e-12, "clarity grows with distance at " + i);
            if (i > 0) maxStep = Math.max(maxStep, prev - c);
            prev = c;
        }
        check(maxStep < 0.005, "gradient has a jump: " + maxStep);
        check(near(CaveFade.clarity(1, 0), 0), "zero radius must show nothing");
        check(near(CaveFade.smoothstep(0, 1, -3), 0) && near(CaveFade.smoothstep(0, 1, 7), 1) && near(CaveFade.smoothstep(2, 2, 1), 0), "smoothstep ends");
        // fade in and out by real time, independent of the frame rate
        double p = 0;
        for (int i = 0; i < 12; i++) p = CaveFade.approach(p, true, 1 / 60.0, CaveFade.FADE_IN, CaveFade.FADE_OUT);
        double q = 0;
        for (int i = 0; i < 6; i++) q = CaveFade.approach(q, true, 1 / 30.0, CaveFade.FADE_IN, CaveFade.FADE_OUT);
        check(near(p, q), "fade depends on the frame rate");
        check(near(p, 0.2 / CaveFade.FADE_IN), "0.2 s is not the fade's share");
        for (int i = 0; i < 200; i++) p = CaveFade.approach(p, true, 1 / 60.0, CaveFade.FADE_IN, CaveFade.FADE_OUT);
        check(near(p, 1), "fade in does not end at 1");
        for (int i = 0; i < 200; i++) p = CaveFade.approach(p, false, 1 / 60.0, CaveFade.FADE_IN, CaveFade.FADE_OUT);
        check(near(p, 0), "fade out does not end at 0");
        check(near(CaveFade.approach(0.4, true, 0, 1, 1), 0.4), "a zero step changed the progress");
        check(near(CaveFade.radius(0, 1), 0) && near(CaveFade.radius(1, 1), R), "radius ends");
        check(CaveFade.radius(1, 0) < R && CaveFade.radius(1, 0) > 0, "a de-emphasised zone must stay visible");
        check(CaveFade.clarityScale(1, 0) < 1 && CaveFade.clarityScale(1, 1) == 1, "emphasis does not weaken the others");
        // follow: the same over 1 s at any step, without overshoot
        double a = 0, b = 0;
        for (int i = 0; i < 60; i++) a += (10 - a) * CaveFade.followWeight(1 / 60.0, CaveFade.FOLLOW);
        for (int i = 0; i < 20; i++) b += (10 - b) * CaveFade.followWeight(1 / 20.0, CaveFade.FOLLOW);
        check(Math.abs(a - b) < 1e-9 && a < 10 && a > 9.99, "follow is not frame-rate independent or overshoots");
        check(near(CaveFade.followWeight(0, 0.1), 1), "no time: snap");
        // hysteresis: no flicker around the boundary
        check(!CaveFade.underground(false, 2.9, 0) && CaveFade.underground(false, 3.0, 0), "enter depth");
        check(!CaveFade.underground(false, 5, 9) && CaveFade.underground(false, 5, 7), "enter sky light");
        check(CaveFade.underground(true, 1.0, 11) && !CaveFade.underground(true, 0.9, 0) && !CaveFade.underground(true, 5, 12), "stay bounds");
        boolean state = false;
        int flips = 0;
        double[] depth = {2.5, 3.2, 2.8, 3.1, 2.2, 1.6, 2.4, 1.2, 1.9, 3.3, 0.7, 2.9, 3.4};   // an agent bobbing on the edge
        for (double d : depth) { boolean n = CaveFade.underground(state, d, 0); if (n != state) flips++; state = n; }
        check(flips == 3, "edge bobbing flipped " + flips + " times");   // down at 3.2, up at 0.7, down at 3.4: depths 1..3 are held
        // the backdrop: gone with the camera in the zone's heart, whole when the camera is far
        check(near(CaveFade.viewerScale(0, R), 0) && near(CaveFade.viewerScale(R * 2, R), 1), "viewer scale ends");
        System.out.println("CaveFadeTest PASS");
    }
}
