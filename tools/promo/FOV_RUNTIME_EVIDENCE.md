# Minecraft 1.7.10 FOV contract for this build

The rejected one-camera diagnostic at `verification/captures/fb790624_diag_h3_1s_20261107` rendered a flat framebuffer. `fov 70` had written `0` into `GameSettings.fovSetting` through the mistaken normalized-slider conversion.

Primary bytecode (`lib/mc-forge-mcp.jar`, inspected with `javap -c -private`):

- `GameSettings` initializes `fovSetting` to `70.0f`.
- `EntityRenderer.getFOVModifier` reads `GameSettings.fovSetting` directly into the field-of-view value.
- The options file converts only on serialization: loading `fov` multiplies by `40` and adds `70`; saving subtracts `70` and divides by `40`.

The old working single-camera source `f027a6c0` assigned `fov 70` directly to the field. The one-camera diagnostic's main framebuffer and MP4 were gray, with 121–149 of 123–151 renderers clipped. The saved camera chunk and pose matched the working source, and the camera column was air. The zero-degree field is the source-level explanation; a post-fix runtime frame is still required.

`tools/tests/test_fov_runtime_contract.py` checks the bytecode and real Director, Cam-state, and Commander wiring. `CaptureFovProbe` would reject the old `70 -> 0` conversion.
