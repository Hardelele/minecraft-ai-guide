package hardelele.aiguide.client;

/**
 * The numbers behind the underground view (CaveView): how clear the rock is at a distance from an agent, how fast the
 * view comes and goes, and when an agent counts as being underground. No game and no GL here, so it is tested alone.
 */
final class CaveFade {
    /** blocks: beyond this distance from the agent the world is drawn as usual */
    static final double RADIUS = 12.0;
    /** share of RADIUS inside which the rock is fully transparent; the fade to opaque rock runs from here to RADIUS */
    static final double CLEAR = 0.30;
    /** seconds for the view to come when an agent goes down, and to go when it comes up (by time, not by ticks) */
    static final double FADE_IN = 0.45, FADE_OUT = 0.60;
    /** seconds: the zone follows the agent as a low-pass filter with this time constant, so it does not step by blocks */
    static final double FOLLOW = 0.12;
    /** blocks below the surface (and the sky light at the head) to go underground, and to stay: the gap is the hysteresis */
    static final double ENTER_DEPTH = 3.0, STAY_DEPTH = 1.0;
    static final int ENTER_SKY = 7, STAY_SKY = 11;
    /** the darkest the void behind the cave's faces gets (0..1); under it the real terrain still shows faintly */
    static final double BACKDROP = 0.88;
    /** an agent that is not the selected one (when one is selected): its share of the radius and of the clarity */
    static final double OTHER_RADIUS = 0.78, OTHER_CLARITY = 0.7;
    /** the camera's distance from the zone's centre, in shares of the radius, over which the dark backdrop fades out */
    static final double CAMERA_NEAR = 0.35, CAMERA_FAR = 0.9;

    private CaveFade() {}

    static double clamp01(double x) { return x < 0 ? 0 : x > 1 ? 1 : x; }

    /** 0 below e0, 1 above e1, smooth (zero slope at both ends) between */
    static double smoothstep(double e0, double e1, double x) {
        if (e1 <= e0) return x < e0 ? 0 : 1;
        double t = clamp01((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    /** how see-through the rock is at this distance from the agent: 1 in the core, 0 at the radius and beyond */
    static double clarity(double distance, double radius) {
        if (radius <= 0) return 0;
        return 1 - smoothstep(CLEAR * radius, radius, distance);
    }

    /** the zone's radius for a fade progress 0..1 and its emphasis (1 selected or alone, less for the others) */
    static double radius(double progress, double emphasis) {
        double e = clamp01(emphasis);
        return RADIUS * smoothstep(0, 1, progress) * (OTHER_RADIUS + (1 - OTHER_RADIUS) * e);
    }

    /** the whole view's opacity for a fade progress and an emphasis */
    static double clarityScale(double progress, double emphasis) {
        double e = clamp01(emphasis);
        return smoothstep(0, 1, progress) * (OTHER_CLARITY + (1 - OTHER_CLARITY) * e);
    }

    /** moves a progress to its target at a fixed speed in real time: from 0 to 1 in `up` seconds, from 1 to 0 in `down` */
    static double approach(double current, boolean toward, double dt, double up, double down) {
        double step = dt <= 0 ? 0 : toward ? dt / up : dt / down;
        return toward ? Math.min(1, current + step) : Math.max(0, current - step);
    }

    /** the weight of one smoothing step over dt seconds: the same follow at any frame rate */
    static double followWeight(double dt, double tau) {
        return dt <= 0 ? 1 : 1 - Math.exp(-dt / tau);
    }

    /** underground: no open sky over the head. A bound at depth (blocks below the surface) and at the sky light, with hysteresis */
    static boolean underground(boolean was, double depth, int skyLight) {
        return was ? depth >= STAY_DEPTH && skyLight <= STAY_SKY : depth >= ENTER_DEPTH && skyLight <= ENTER_SKY;
    }

    /** the dark backdrop thins out when the camera itself is inside the zone, so a view from within the cave is not veiled */
    static double viewerScale(double cameraDistance, double radius) {
        return smoothstep(CAMERA_NEAR * radius, CAMERA_FAR * radius, cameraDistance);
    }
}
