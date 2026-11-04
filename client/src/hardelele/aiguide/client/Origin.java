package hardelele.aiguide.client;

/** where a crew task (and every action done in it) came from: the measure of how autonomous the crew is */
public enum Origin {
    /** from the plan the guide's model made for the player's goal */
    LLM_PLAN,
    /** from the model changing that plan after an event */
    REPLAN,
    /** made by the deterministic compiler out of the model's goals */
    COMPILER,
    /** the board itself: a default task for an idle agent, a haul for a full bag, a hand-off for a need */
    AUTO_DEFAULT,
    /** the player asked for it in so many words (not through the plan) */
    PLAYER,
    /** the director's script gave it */
    SCRIPT,
    /** the script gave it because the model did not (crewfallback) */
    FALLBACK
}
