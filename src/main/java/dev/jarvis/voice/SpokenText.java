package dev.jarvis.voice;

/**
 * What of an answer is read aloud, as it streams: the screen shows everything, the voice skips fenced blocks (code,
 * cards) and table rows, which make no sense spoken. Lines are judged by how they start, so a token may be held back
 * until its line is known. Inline markup is left to SentenceChunker.forSpeech. Pure logic, one instance per turn.
 */
public final class SpokenText {

    private enum Line { UNKNOWN, SPEAK, SKIP }

    private static final String FENCE = "```";

    private final StringBuilder head = new StringBuilder();
    private Line line = Line.UNKNOWN;
    private boolean fence;

    /** @return the part of the token (and of the ones held back) to speak; possibly empty */
    public String append(String token) {
        StringBuilder spoken = new StringBuilder();
        for (int i = 0; token != null && i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '\n') {
                if (line == Line.UNKNOWN) {
                    decide(true);
                }
                if (line == Line.SPEAK) {
                    spoken.append(head).append('\n');
                }
                head.setLength(0);
                line = Line.UNKNOWN;
            } else if (line == Line.SPEAK) {
                spoken.append(c);
            } else if (line == Line.UNKNOWN) {
                head.append(c);
                decide(false);
                if (line == Line.SPEAK) {
                    spoken.append(head);
                    head.setLength(0);
                }
            }
        }
        return spoken.toString();
    }

    /** The end of the answer: a last line held back, if it is to be spoken. */
    public String flush() {
        if (line == Line.UNKNOWN) {
            decide(true);
        }
        String rest = line == Line.SPEAK ? head.toString() : "";
        head.setLength(0);
        line = Line.UNKNOWN;
        return rest;
    }

    /** @param complete the line ended: decide even on what could still become a fence */
    private void decide(boolean complete) {
        String start = head.toString().stripLeading();
        if (start.isEmpty() && !complete) {
            return;
        }
        if (start.startsWith(FENCE)) {
            fence = !fence;
            line = Line.SKIP;
        } else if (!complete && FENCE.startsWith(start)) {
            line = Line.UNKNOWN; // "`" or "``": may still open or close a fence
        } else if (fence || start.startsWith("|")) {
            line = Line.SKIP;
        } else {
            line = Line.SPEAK;
        }
    }
}
