package hardelele.aiguide.client;

/** what a crew task's tick ended with: still at it, or done, or why it could not be done */
public enum Result {
    RUNNING, OK,
    /** no way there on foot */
    NO_PATH,
    /** not with what is in hand (stone without a pickaxe) */
    NO_TOOL,
    /** something needed is not in the bag, the chest or anywhere known */
    NO_ITEMS,
    /** what it was about is not there any more (a tree chopped by another, a block already placed) */
    GONE,
    /** took longer than it may */
    TIMEOUT,
    /** in the way of something for good (nothing to put the block against, a station in use) */
    BLOCKED,
    /** not now: someone stands where the block goes (they are asked to step aside); try again shortly */
    BUSY;

    boolean done() {
        return this != RUNNING;
    }

    boolean failed() {
        return this != RUNNING && this != OK;
    }
}
