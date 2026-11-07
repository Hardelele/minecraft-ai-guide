"""Source-level guard for the shelter-only wiring; Minecraft behavior still needs a copy-world run."""
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def wired(crew: str, day2: str) -> bool:
    return (
        crew.count("ShelterRoute.descendThroughDoor(shelterEntry,") == 2
        and "GoTo shelterEntry(int[] door)" in crew
        and "if (shelterEntry && !ShelterRoute.oneOpening(c[0], c[1], c[2], shelterDoor)) return Result.NO_PATH;" in crew
        and "if (ShelterRoute.oneOpening(bx, by, bz, shelterDoor)" in crew
        and ".shelterEntry(door)" in day2
        and "ShelterRoute.atDoor(a.posX, a.boundingBox.minY, a.posZ, door)" in day2
        and "entry.after(previousEntry)" in day2
        and "a != outsideGuard" in day2
        and "for (Agent member : entering)" in day2
        and "doorState.closedAfterEntry = true" in day2
        and ".onBlockActivated(w, x, y, z, Skills.hands(a, w)" in day2
        and "setBlock(" not in day2[day2.index("static class ShelterClose"):day2.index("static class ShelterWait")]
    )


def main() -> None:
    crew = (ROOT / "client/src/hardelele/aiguide/client/Crew.java").read_text(encoding="utf-8")
    day2 = (ROOT / "client/src/hardelele/aiguide/client/Day2.java").read_text(encoding="utf-8")
    assert wired(crew, day2), "shelter route/closure wiring missing"
    assert not wired(crew.replace("GoTo shelterEntry(int[] door)", "GoTo shelterEntryX(int[] door)"), day2), "entry mutant survived"
    assert not wired(crew.replace("ShelterRoute.oneOpening(bx, by, bz, shelterDoor)", "true"), day2), "extra-dig mutant survived"
    assert not wired(crew, day2.replace("ShelterRoute.atDoor(a.posX, a.boundingBox.minY, a.posZ, door)", "true")), "fake-door-crossing mutant survived"
    assert not wired(crew, day2.replace("entry.after(previousEntry)", "entry.prio(3)")), "crowded-door mutant survived"
    assert not wired(crew, day2.replace("doorState.closedAfterEntry = true", "doorState.closedAfterEntry = false")), "early-wait mutant survived"
    assert not wired(crew, day2.replace(".onBlockActivated(w, x, y, z, Skills.hands(a, w)", ".setBlock(x, y, z, Blocks.air)")), "world-edit mutant survived"
    print("test_shelter_entry_wiring PASS: route, single opening, serial entry, real door activation mutants")


if __name__ == "__main__":
    main()
