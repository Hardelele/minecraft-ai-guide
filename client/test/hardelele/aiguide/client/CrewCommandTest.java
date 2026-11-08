package hardelele.aiguide.client;

/** The crew's commands in the guide's window: which lines are theirs (and not questions for the guide), and what they say. */
public final class CrewCommandTest {
    static void is(String line, int kind, int n) {
        CrewCommand c = CrewCommand.parse(line);
        if (c == null || c.kind != kind || c.n != n) throw new AssertionError(line + " -> " + (c == null ? "null" : c.kind + " " + c.n));
    }

    static void not(String line) {
        if (CrewCommand.parse(line) != null) throw new AssertionError(line + " is not a crew command");
    }

    public static void main(String[] args) {
        is("/агент", CrewCommand.ADD, 1);
        is("  /Агент  3 ", CrewCommand.ADD, 3);
        is("/агенты 2", CrewCommand.ADD, 2);
        is("/crew add", CrewCommand.ADD, 1);
        is("/crew add 2", CrewCommand.ADD, 2);
        is("/crew remove 3", CrewCommand.REMOVE, 3);
        is("/crew remove", CrewCommand.REMOVE, 0);
        is("/агент убрать 4", CrewCommand.REMOVE, 4);
        is("/агент отпустить", CrewCommand.REMOVE, 0);
        is("/crew", CrewCommand.USAGE, 0);
        is("/crew dance", CrewCommand.USAGE, 0);
        is("/агент танцуй", CrewCommand.USAGE, 0);
        not("агент, иди сюда");
        not("/агентство");
        not("/поделиться");
        not("позови 2 агентов");
        not("");
        not(null);
        System.out.println("CrewCommandTest PASS");
    }
}
