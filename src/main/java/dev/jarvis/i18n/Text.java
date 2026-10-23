package dev.jarvis.i18n;

import java.util.Locale;

/**
 * A text the user reads, in every language Jarvis speaks (SettingsCatalog.LANGUAGES). Pure data.
 *
 * <p>shortcut: the translations sit next to the code, pair by pair; with a third language move them to resource
 * bundles.
 */
public record Text(String it, String en) {

    public static Text text(String it, String en) {
        return new Text(it, en);
    }

    /** The user's language as the prompts name it: they are written in Italian, and say which language to answer in. */
    public static String promptName(String language) {
        return "en".equals(language) ? "inglese" : "italiano";
    }

    /** Added to Jarvis' system prompt: the language chosen in Settings, unless the user writes in another one. */
    public static String languageLine(String language) {
        return "\nL'utente parla %1$s: rispondi in %1$s, anche a voce, salvo che ti scriva in un'altra lingua."
                .formatted(promptName(language));
    }

    /** Added to every specialist's system prompt: its result reaches the user. */
    public static String specialistLanguageLine(String language) {
        return "\n\nScrivi il risultato in %s, la lingua dell'utente.".formatted(promptName(language));
    }

    /** For dates and numbers, with the same fallback as {@link #in(String)}. */
    public static Locale locale(String language) {
        return "en".equals(language) ? Locale.ENGLISH : Locale.ITALIAN;
    }

    /** @param language "it" or "en"; anything else, null included, gets Italian */
    public String in(String language) {
        return "en".equals(language) ? en : it;
    }

    /** With the placeholders (%s, %.2f) filled in, numbers written as in that language. */
    public String in(String language, Object... arguments) {
        return String.format(locale(language), in(language), arguments);
    }
}
