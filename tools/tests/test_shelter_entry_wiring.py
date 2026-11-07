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
        and "a.body.activate(w, x, y, z)" in day2   # the agent's own hands at the door it sees (Body)
        and "setBlock(" not in day2[day2.index("static class ShelterClose"):day2.index("static class ShelterWait")]
    )


def blocked_interior_does_not_pre_fail_shelter(day2: str, board: str) -> bool:
    # TaskBoard checks .at before dependency readiness. Agent 2 starts outside
    # the fence, and the wait card is posted before the entry has opened a path.
    entry = day2.split('TaskBoard.Card entry = c.board.post("inside entry",', 1)[1].split(';', 1)[0]
    waiting = day2.split('c.board.post("inside at night",', 1)[1].split(';', 1)[0]
    return (
        "k.at != null && here != null && here.near(" in board
        and "k.state == State.OPEN && !k.placeChecked && k.at != null" in board
        and ".pin(a)" in entry
        and ".work(" in entry
        and ".at(" not in entry
        and ".after(entry)" in waiting
        and ".at(" not in waiting
    )


def existing_guard_stays_outside(day2: str) -> bool:
    return (
        'if (!card.kind.equals("guard") || !card.open()) continue;' in day2
        and "Agent assigned = card.holder != null ? card.holder : card.only;" in day2
        and "outsideGuard = assigned;" in day2
        and "existingGuard = true;" in day2
        and "a != outsideGuard" in day2
        and "outsideGuard != null && !existingGuard" in day2
    )


def main() -> None:
    crew = (ROOT / "client/src/hardelele/aiguide/client/Crew.java").read_text(encoding="utf-8")
    day2 = (ROOT / "client/src/hardelele/aiguide/client/Day2.java").read_text(encoding="utf-8")
    board = (ROOT / "client/src/hardelele/aiguide/client/TaskBoard.java").read_text(encoding="utf-8")
    assert wired(crew, day2), "shelter route/closure wiring missing"
    assert blocked_interior_does_not_pre_fail_shelter(day2, board), "blocked interior pre-fails entry/wait cards"
    assert existing_guard_stays_outside(day2), "already leased guard included as an entrant"
    assert not existing_guard_stays_outside(day2.replace("outsideGuard = assigned;", "outsideGuard = null;")), "leased-guard exclusion mutant survived"
    assert not existing_guard_stays_outside(day2.replace("outsideGuard != null && !existingGuard", "outsideGuard != null")), "duplicate-guard-card mutant survived"
    interior_prefilter_mutant = day2.replace(
        '.pin(a).prio(3).work(60, 1, 0, 0, 0, 0)',
        '.pin(a).prio(3).at(sp[0], sp[1], sp[2]).work(60, 1, 0, 0, 0, 0)',
    )
    assert not blocked_interior_does_not_pre_fail_shelter(interior_prefilter_mutant, board), "outside-fence prefilter mutant survived"
    premature_wait_mutant = day2.replace(
        '.pin(a).after(entry).prio(2).lease(max)',
        '.pin(a).after(entry).prio(2).at(sp[0], sp[1], sp[2]).lease(max)',
    )
    assert not blocked_interior_does_not_pre_fail_shelter(premature_wait_mutant, board), "premature wait NO_PATH mutant survived"
    assert not wired(crew.replace("GoTo shelterEntry(int[] door)", "GoTo shelterEntryX(int[] door)"), day2), "entry mutant survived"
    assert not wired(crew.replace("ShelterRoute.oneOpening(bx, by, bz, shelterDoor)", "true"), day2), "extra-dig mutant survived"
    assert not wired(crew, day2.replace("ShelterRoute.atDoor(a.posX, a.boundingBox.minY, a.posZ, door)", "true")), "fake-door-crossing mutant survived"
    assert not wired(crew, day2.replace("entry.after(previousEntry)", "entry.prio(3)")), "crowded-door mutant survived"
    assert not wired(crew, day2.replace("doorState.closedAfterEntry = true", "doorState.closedAfterEntry = false")), "early-wait mutant survived"
    assert not wired(crew, day2.replace("a.body.activate(w, x, y, z)", "w.setBlock(x, y, z, Blocks.air)")), "world-edit mutant survived"
    print("test_shelter_entry_wiring PASS: exterior lease, dependent wait, pre-leased guard, route, single opening, serial entry, real door activation mutants")


if __name__ == "__main__":
    main()
