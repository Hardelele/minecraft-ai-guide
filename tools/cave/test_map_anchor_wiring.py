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
    print("test_map_anchor_wiring PASS; moving-seed and moving-camera mutants rejected")


if __name__ == "__main__":
    main()
