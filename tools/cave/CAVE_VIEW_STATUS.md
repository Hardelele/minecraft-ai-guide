# Connected cave view prototype — UNVERIFIED_RUNTIME

This is an isolated Commander prototype. It has not passed a real client visual or load gate.

- Select one underground agent in Commander. `C` switches between the existing one-level `U` view and a connected 3D air-volume view. `Q/E` orbit; the wheel zooms. The view follows the selected agent and shows only currently loaded chunks.
- The 3D view traverses 6-connected open cells in XYZ. Walls, floors and faint ceilings use actual exposed block faces; sealed ore has no face in the connected component. It re-renders entities in that component and blocks world clicks because vanilla picking still sees the uncut terrain. It never passes geometry or ore to the planner.
- Work is limited to radius 40, at most 12,000 air cells and 8,000 faces, scanning 400 cells per game tick. The UI marks a partial result. “Open top” means an air connection upward, not a walkable agent route. A changed block near the selected agent triggers an update; farther edits may take up to 40 ticks to appear.

Source-only checks before this commit: a client build passed before the incremental/render revisions; `CaveGeometryTest` passed before those revisions. Independent source reviews resolved their identified P1 issues. **The current source still requires a fresh client build, synthetic test, actual client recording, orbit/zoom visual review, source-to-JAR hash pin, and load measurement.** Until then it is neither a completed cave viewer nor film-ready.

Runtime gate after the laptop filming slot is free: build this exact commit on the laptop in an isolated source copy, copy the `cmdr-slice-ore2` world into a new game directory, pin the new JAR pair, then record one continuous Commander clip with Agent 1: `C` volume, at least three distinct heights, visible upper air connection if present, exposed iron and actor, orbit both ways, zoom in/out, return to `U` and normal view. Capture frame times and scan logs. A bounded result or no actual top connection is a documented failure of the requested full view.
