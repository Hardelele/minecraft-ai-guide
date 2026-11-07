package hardelele.aiguide.client;

import java.util.Locale;
import java.util.regex.Pattern;

/** Only explicit commands may enter the semantic quick-run path. */
final class RoutineIntent {
    private static final Pattern MANAGEMENT = Pattern.compile(
            "(^|\\W)((?:удал|убер|забуд|измен|поправ|прав|редактир|отредактир|переимен|созда|сохран|покаж|замен|перепиш|помен|очист|отмен|останов|прекрат|выключ)[\\p{L}]*|список|"
                    + "delete|remove|forget|edit|change|update|rename|create|save|list|show|modify|revise|clear|stop|cancel|disable|erase)(\\W|$)", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern RUN = Pattern.compile(
            "(^|\\W)(запусти|запускай|выполни|выполняй|повтори|повторяй|руби|добывай|копай|носи|приноси|собирай|ищи|сажай|посади|строй|крафти|плавь|охоться|"
                    + "run|start|repeat|chop|mine|gather|collect|plant|build|craft|smelt|hunt)(\\W|$)", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern NEGATION = Pattern.compile("(^|\\W)(не|нет|без|don't|not)(\\W|$)|do\\s+not", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern QUESTION = Pattern.compile(
            "(^|\\W)((?:расскаж|объясн|опиш|что|как|почему|зачем|когда|где|сколько|какие)[\\p{L}]*|what|which|how|why|tell|explain|describe)(\\W|$)", Pattern.UNICODE_CHARACTER_CLASS);

    private RoutineIntent() { }

    static boolean quickRun(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        return RUN.matcher(lower).find() && !MANAGEMENT.matcher(lower).find()
                && !NEGATION.matcher(lower).find() && !QUESTION.matcher(lower).find() && !lower.contains("?");
    }
}
