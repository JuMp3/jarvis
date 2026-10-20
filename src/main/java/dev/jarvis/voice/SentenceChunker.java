package dev.jarvis.voice;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a stream of LLM tokens into speakable sentences, so text-to-speech can start on the first
 * sentence instead of waiting for the whole answer. Not thread-safe: use one instance per turn.
 */
public final class SentenceChunker {

    private static final int DEFAULT_MIN_CHARS = 12;
    private static final int DEFAULT_MAX_CHARS = 280;

    private final StringBuilder buffer = new StringBuilder();
    private final int minChars;
    private final int maxChars;

    public SentenceChunker() {
        this(DEFAULT_MIN_CHARS, DEFAULT_MAX_CHARS);
    }

    /**
     * @param minChars sentences shorter than this are merged with the next one ("Sì." alone sounds choppy)
     * @param maxChars text without punctuation is split at the last space once it gets this long
     */
    public SentenceChunker(int minChars, int maxChars) {
        this.minChars = minChars;
        this.maxChars = maxChars;
    }

    /** Adds a token and returns the sentences it completed (possibly none). */
    public List<String> append(String token) {
        if (token != null) {
            buffer.append(token);
        }
        return drain();
    }

    /** Returns whatever is left at the end of the stream. */
    public List<String> flush() {
        List<String> sentences = drain();
        String rest = forSpeech(buffer.toString());
        buffer.setLength(0);
        if (!rest.isEmpty()) {
            sentences = new ArrayList<>(sentences);
            sentences.add(rest);
        }
        return sentences;
    }

    private List<String> drain() {
        List<String> sentences = new ArrayList<>();
        int from = 0;
        int i = 0;
        while (i < buffer.length()) {
            if (isBoundary(i)) {
                String candidate = forSpeech(buffer.substring(from, i + 1));
                if (candidate.length() >= minChars) {
                    sentences.add(candidate);
                    from = i + 1;
                }
            }
            i++;
        }
        buffer.delete(0, from);

        while (buffer.length() > maxChars) {
            int cut = buffer.lastIndexOf(" ", maxChars);
            if (cut <= 0) {
                cut = maxChars;
            }
            String piece = forSpeech(buffer.substring(0, cut));
            buffer.delete(0, cut);
            if (!piece.isEmpty()) {
                sentences.add(piece);
            }
        }
        return sentences;
    }

    /** A terminator followed by whitespace, or a line break. Skips decimals like "3.5". */
    private boolean isBoundary(int index) {
        char c = buffer.charAt(index);
        if (c == '\n') {
            return true;
        }
        boolean terminator = c == '.' || c == '!' || c == '?' || c == '…' || c == ';' || c == ':';
        return terminator && index + 1 < buffer.length() && Character.isWhitespace(buffer.charAt(index + 1));
    }

    /** Strips markdown that would be read aloud literally and collapses whitespace. */
    public static String forSpeech(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replaceAll("```[\\s\\S]*?```", " ")
                .replaceAll("[*_`#>|]", "")
                .replaceAll("^\\s*[-•]\\s+", "")
                .replaceAll("\\[([^]]*)]\\([^)]*\\)", "$1")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
