package hardelele.aiguide.client;

/** Standalone check: only explicit commands can enter Commander's semantic quick-run path. */
public final class RoutineIntentTest {
    private static void check(boolean expected, String text) {
        if (RoutineIntent.quickRun(text) != expected) throw new AssertionError(text);
    }

    public static void main(String[] args) {
        check(false, "удали сценарий Лесоруб");
        check(false, "Переименуй рутину в Лес");
        check(false, "изменить сценарий добычи");
        check(false, "поправь сценарий добычи");
        check(false, "останови сценарий добычи");
        check(false, "не запускай сценарий сейчас");
        check(false, "не хочу запускать сценарий");
        check(false, "расскажи про сценарий Лесоруб");
        check(false, "как запустить сценарий?");
        check(false, "как мне запусти сценарий?");
        check(false, "делай сценарий для дерева");
        check(false, "create a new routine");
        check(false, "show saved scenarios");
        check(true, "руби деревья");
        check(true, "запусти сценарий Лесоруб");
        check(true, "повтори добычу руды");
        check(true, "run the wood routine");
        System.out.println("RoutineIntentTest PASS");
    }
}
