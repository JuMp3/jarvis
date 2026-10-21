package dev.jarvis.voice;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Hands-free with wake word: only phrases addressed to Jarvis go to the model. The check runs on the
 * transcript, so it must forgive how speech-to-text spells the name ("Giarvis", "Jervis", "Jarvis,").
 * Pure logic, no Spring dependency.
 */
public final class WakeWord {

    private static final int LOOKAHEAD_WORDS = 3;

    private WakeWord() {
    }

    /**
     * @param word the wake word, e.g. "jarvis"
     * @return the request without the wake word (and the greeting before it, as in "Ehi Jarvis, ..."), or empty
     *         if the phrase is not addressed to Jarvis
     */
    public static Optional<String> strip(String transcript, String word) {
        if (transcript == null || word == null || word.isBlank()) {
            return Optional.empty();
        }
        String target = normalize(word);
        String[] words = transcript.strip().split("\\s+");
        for (int i = 0; i < Math.min(LOOKAHEAD_WORDS, words.length); i++) {
            if (close(normalize(words[i]), target)) {
                String rest = String.join(" ", Arrays.copyOfRange(words, i + 1, words.length))
                        .replaceFirst("^[\\p{Punct}\\s]+", "").strip();
                return Optional.of(rest);
            }
        }
        return Optional.empty();
    }

    /** Letters only, lowercase, no accents. */
    static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }

    /** Same word, or one edit away (a letter changed, added or removed), with a common Italian spelling. */
    static boolean close(String heard, String target) {
        if (heard.isEmpty()) {
            return false;
        }
        String italian = heard.replace("gi", "j").replace("ge", "je");
        return distance(heard, target) <= 1 || distance(italian, target) <= 1;
    }

    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
