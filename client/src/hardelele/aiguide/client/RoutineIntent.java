package hardelele.aiguide.client;

import java.util.Locale;
import java.util.regex.Pattern;

/** Management requests must reach the guide instead of the semantic quick-run path. */
final class RoutineIntent {
    private static final Pattern MANAGEMENT = Pattern.compile(
            "(^|\\W)((?:удал|убер|забуд|измен|поправ|прав|редактир|отредактир|переимен|созда|сохран|покаж|замен|перепиш|помен|очист|отмен|останов|прекрат|выключ)[\\p{L}]*|список|"
                    + "delete|remove|forget|edit|change|update|rename|create|save|list|show|modify|revise|clear|stop|cancel|disable|erase)(\\W|$)", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern NEGATED_RUN = Pattern.compile("(^|\\W)(не\\s+(запускай|запустить|выполняй)|do\\s+not\\s+run|don't\\s+run)(\\W|$)", Pattern.UNICODE_CHARACTER_CLASS);

    private RoutineIntent() { }

    static boolean management(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        return MANAGEMENT.matcher(lower).find() || NEGATED_RUN.matcher(lower).find();
    }
}
