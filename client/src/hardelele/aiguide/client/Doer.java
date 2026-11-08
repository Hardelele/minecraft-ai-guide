package hardelele.aiguide.client;

/**
 * Who takes a card from the crew's board: an agent's body (Agent), or the player's own character (PlayerAgent, number
 * 1), which works through the autopilot (Pilot) with the player's real keys. The board deals with both the same way:
 * whether it may have the card at all (number, orders, can), the lease, giving the card back (cancel). An agent works
 * a card through its jobs (Crew's tick, the reflexes before them); the player's character through its own runner.
 */
interface Doer {
    /** its number in the crew: 1 the player, 2.. the agents (1.. under a video script or the bench) */
    int num();

    /** its name as the crew's lines say it */
    String who();

    boolean alive();

    /** under the player's orders: takes the player's cards only */
    boolean ordered();

    void ordered(boolean on);

    /** held by the player's Stop: stays the player's with no orders left (not back to the AI by itself) */
    boolean holding();

    void holding(boolean on);

    double px();

    double py();

    double pz();

    /** the card's work is something it can honestly do (asked before the board gives it) */
    boolean can(TaskBoard.Card k);

    /** its body is in a reaction now: the card's lease waits */
    boolean paused();

    /** the card is leased to it: its work starts */
    void begin(TaskBoard b, TaskBoard.Card k);

    /** the card's work stops at once (the board took it back, the player took the controls) */
    void cancel(String why);
}
