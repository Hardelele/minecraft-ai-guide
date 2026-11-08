package hardelele.aiguide.client;

/**
 * A place the places model recognised, as much of it as the rating layer needs (places/CONTRACT.md: the classes, the
 * 32^3 cube). A stand-in for the inference branch's own place: its known places are mapped onto this by a PlaceSource,
 * so this side does not depend on that code. No game classes here.
 */
public interface PlaceView {
    /** the classes, by the contract's output index */
    String[] CLASSES = { "solid", "open_outside", "passage", "room", "cave_hall", "shaft", "junction", "built_structure" };

    /** stable while the place is known (a click and its record refer to it) */
    String id();

    /** the class, an index into CLASSES */
    int cls();

    /** the box in world blocks, inclusive: x0 y0 z0 x1 y1 z1 */
    int[] box();

    /** 0..1 */
    double confidence();

    /** the world origin x0 y0 z0 of the 32^3 cube the place was found in; null: not known (the box's corner is used) */
    int[] cube();
}
