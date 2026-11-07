"""Small source-wiring gate for the persistent cave-map seed.

The geometry test covers disconnected air; this catches a regression where the
viewer calls that geometry from the moving agent's feet again.
"""

from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CAVE = ROOT / "client/src/hardelele/aiguide/client/CaveView.java"
COMMANDER = ROOT / "client/src/hardelele/aiguide/client/Commander.java"


def wired(cave: str, commander: str) -> bool:
    return (
        "scanner = scan(mc.theWorld, sampledX, sampledY, sampledZ);" in cave
        and "if (newMap) {\n                sampledX = ax; sampledY = ay; sampledZ = az;" in cave
        and "scanner.done && (changed || tick - completedTick >= 40)" in cave
        and "px = CaveView.anchorX();" in commander
        and "pz = CaveView.anchorZ();" in commander
        and "CaveView.agentOutside()" in commander
        and "CaveGeometry.nearestFace(volume.faces, liveIronFaces, ax, ay, az, 3.0)" in cave
        and "w.getBlock(f.x, f.y, f.z) != Blocks.iron_ore" in cave
        and "w.blockExists(nx, ny, nz) && volume.contains(nx, ny, nz) && isOpen(w, nx, ny, nz)" in cave
        and "w.blockExists(x, y, z) && isOpen(w, x, y, z) && y >= w.getHeightValue(x, z)" in cave
        and "if (code == Keyboard.KEY_F && CaveView.active())" in commander
        and commander.find("if (code == Keyboard.KEY_F && CaveView.active())")
        < commander.find("if (code == Keyboard.KEY_K || code == Keyboard.KEY_P || code == Keyboard.KEY_F")
        and "if (!w.blockExists(x, ey, z)) continue;" in cave
        and "if (!liveExit()) return;" in cave
        and "if (liveExit()) {" in cave
        and "if (CaveView.active() && !CaveView.focused() && CaveView.liveExit())" in commander
        and cave.find("GL11.glDepthMask(true);\n            if (!focusOn) drawMouth") >= 0
        and cave.find("GL11.glDepthMask(true);\n            if (!focusOn) drawMouth")
        < cave.find("GL11.glDepthMask(false); // each subterranean level")
        and "if (CaveView.active() && !CaveView.containsEntity(a)) continue;" in commander
    )


def main() -> None:
    cave = CAVE.read_text(encoding="utf-8")
    commander = COMMANDER.read_text(encoding="utf-8")
    assert wired(cave, commander), "mapped seed/camera wiring missing"
    moving_seed = cave.replace(
        "scanner = scan(mc.theWorld, sampledX, sampledY, sampledZ);",
        "scanner = scan(mc.theWorld, ax, ay, az);",
    )
    assert not wired(moving_seed, commander), "moving-seed mutant passed"
    moving_camera = commander.replace("px = CaveView.anchorX();", "px = viewed.posX;")
    assert not wired(cave, moving_camera), "moving-camera mutant passed"
    hidden_ore = cave.replace(
        "CaveGeometry.nearestFace(volume.faces, liveIronFaces, ax, ay, az, 3.0)",
        "CaveGeometry.nearestFace(volume.faces, ironFaces, ax, ay, az, 3.0)",
    )
    assert not wired(hidden_ore, commander), "ore-face wiring mutant passed"
    stale_ore = cave.replace("w.getBlock(f.x, f.y, f.z) != Blocks.iron_ore", "false")
    assert not wired(stale_ore, commander), "mined-ore mutant passed"
    stale_exit = cave.replace("w.blockExists(x, y, z) && isOpen(w, x, y, z) && y >= w.getHeightValue(x, z)", "true")
    assert not wired(stale_exit, commander), "sealed-exit mutant passed"
    unloaded_rim = cave.replace("if (!w.blockExists(x, ey, z)) continue;", "if (false) continue;")
    assert not wired(unloaded_rim, commander), "unloaded-rim mutant passed"
    rim_overlay = cave.replace("GL11.glDepthMask(true);\n            if (!focusOn) drawMouth",
                               "GL11.glDepthMask(false);\n            if (!focusOn) drawMouth")
    assert not wired(rim_overlay, commander), "rim-depth mutant passed"
    print("test_map_anchor_wiring PASS; seed/camera/stale-ore/sealed-exit/rim-depth mutants rejected")


if __name__ == "__main__":
    main()
