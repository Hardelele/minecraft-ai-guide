# Shelter door entry candidate — source only

Base: `0049b85542e651d079f1eccafa1da43f3805dfe5`. The original world and integration worktree were not changed.

## Primary fixture

Read-only WorldRec `door_agent3_stage2_live_20261107/session.bin.gz` and its copied Anvil region show the existing wooden door at `(-144,69–70,178)` open (lower metadata 5), a furnace at `(-144,69,177)`, plank `(-144,71,178)` removed at tick 444, and roof plank at Y72 retained. Agents 3/4 remain outside at Z≈177.7 through tick 1102, bouncing at Y70–71. Agent 2 remains outside the east fence at X≈-135.07. The full base's north fence gap from its plan is centered at `(-144,69,173)`.

## Candidate behavior

- The night phase selects the outside guard before posting inside cards. The other agents enter one at a time. An agent outside the fence first walks to the existing gap, without digging.
- The agent must physically reach the original door cell, then an interior floor cell. Doorway pathing may break only the one existing house plank at `(doorX,doorY+2,doorZ)` through the normal `Crew.Break`; it cannot authorize another wall, roof, station or door block. The downward door step suppresses the collision-triggered jump only for this shelter route.
- The last entrant checks all participating agents' actual interior floor positions. It closes the existing wooden door with `onBlockActivated` using its own agent hands. Early entrants hold position until that closure is observed; their night wait then starts. Failed entry cannot report closure.

## Lightweight evidence

`javac --release 8 -Xlint:-options -d build/test-door client/src/hardelele/aiguide/client/ShelterRoute.java client/test/hardelele/aiguide/client/ShelterRouteTest.java` and `java -cp build/test-door hardelele.aiguide.client.ShelterRouteTest` pass. The test includes negative fixtures for the furnace edge, roof, door and side wall. `python tools/tests/test_shelter_entry_wiring.py` passes and rejects source mutants removing serial entry, exact door crossing, single-block restriction and agent door activation. `git diff --check` passes. These checks do **not** compile the Minecraft-dependent classes or prove actual pathing.

## Runtime gate still required

On a fresh isolated copy only, pin both JARs and run real WorldRec plus UI. Require one Y71 plank ID5→0 break attributed to an agent; no second house/roof/station break; each sheltered body moves through the door cell and reaches an interior floor cell; the original door remains ID64 and changes from open to closed metadata after the last entrant; normal game UI shows the entry and closure. If Agent 2 cannot reach the fence gap, the physical door collision persists, or the close action fails, report failure and keep this candidate out of integration.
