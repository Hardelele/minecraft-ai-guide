"""Source wiring control for explicit dig designations (no Minecraft runtime)."""

from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOURCE = (ROOT / "client/src/hardelele/aiguide/client/Orders.java").read_text(encoding="utf-8")
GUARD = '''if (agents.length > 0 && who.isEmpty()) {
                    Events.log("order: designation rejected; selected agents unavailable");
                    return;'''


def rejects_missing_selection(source: str) -> bool:
    method = source.split("static void designate(", 1)[1].split("static void erase(", 1)[0]
    resolved = method.find("for (int n : agents) for (Agent g : c.agents)")
    guard = method.find(GUARD)
    post = method.find("c.board.post(")
    return 0 <= resolved < guard < post


def test_missing_selected_agent_cannot_fall_back_to_free_card():
    assert rejects_missing_selection(SOURCE)
    # Removing the guard must fail this control before a free-for-anyone card can be posted.
    mutant = SOURCE.replace(GUARD, "", 1)
    assert mutant != SOURCE
    assert not rejects_missing_selection(mutant)


if __name__ == "__main__":
    test_missing_selected_agent_cannot_fall_back_to_free_card()
    print("Orders designation guard source/mutant PASS")
