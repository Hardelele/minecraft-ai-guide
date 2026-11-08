package hardelele.aiguide.client;

/** What the player sees of the agents' minds (the client's): the label over an agent's head, its line on the commander's panel */
final class MindUi {
    private MindUi() { }

    /** what the agent of this number does by its mind now, in the player's words; null when it has no goal */
    static String doing(int num) {
        String[] s = AgentMind.shown.get(num);
        if (s == null) return null;
        switch (s[0]) {
            case "thinking": return CrewKeys.tr("mind.thinking");
            case "orders": return CrewKeys.tr("mind.do.orders");
            case "look_around": return CrewKeys.tr("mind.do.look_around");
            case "go_to": return CrewKeys.tr("mind.do.go_to");
            case "chop_tree": return CrewKeys.tr("mind.do.chop_tree");
            case "mine": return CrewKeys.tr("mind.do.mine");
            case "craft": return CrewKeys.tr("mind.do.craft");
            case "smelt": return CrewKeys.tr("mind.do.smelt");
            case "place": return CrewKeys.tr("mind.do.place");
            case "take": return CrewKeys.tr("mind.do.take");
            case "put": return CrewKeys.tr("mind.do.put");
            case "give": return CrewKeys.tr("mind.do.give");
            case "wait": return CrewKeys.tr("mind.do.wait");
            default: return s[0];
        }
    }

    /** the label over its head: grey, under the name */
    static String label(int num) {
        String d = doing(num);
        return d == null ? null : "§7" + d;
    }
}
