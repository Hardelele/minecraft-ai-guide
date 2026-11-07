package hardelele.aiguide.client;

/** Standalone check: management words cannot enter Commander's semantic quick-run path. */
public final class RoutineIntentTest {
    private static void check(boolean expected, String text) {
        if (RoutineIntent.management(text) != expected) throw new AssertionError(text);
    }

    public static void main(String[] args) {
        check(true, "удали сценарий Лесоруб");
        check(true, "Переименуй рутину в Лес");
        check(true, "изменить сценарий добычи");
        check(true, "поправь сценарий добычи");
        check(true, "останови сценарий добычи");
        check(true, "не запускай сценарий сейчас");
        check(true, "create a new routine");
        check(true, "show saved scenarios");
        check(false, "руби деревья");
        check(false, "запусти сценарий Лесоруб");
        check(false, "добывай руду как обычно");
        System.out.println("RoutineIntentTest PASS");
    }
}
