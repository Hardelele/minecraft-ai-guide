package hardelele.aiguide.client;

import net.minecraft.world.WorldServer;

/**
 * A task that wants to know it was paused for a reaction (layer 2) and goes on (optional: a task without it simply
 * gets no steps meanwhile). Only the job on top of an agent's queue is told; a task inside another (a walk inside a
 * chop) sees the pause itself by the ticks it missed (Crew.GoTo, Skills.PlaceFrom do).
 */
interface Pausable {
    void paused(Agent a, WorldServer w);

    void resumed(Agent a, WorldServer w);
}
