package dev.jarvis.ws;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.UnaryOperator;

/**
 * A phrase transcribed in pieces while the user is still speaking: the page sends each piece at a short pause, and
 * at the end only the last one is left to transcribe. Pieces run one after the other, each with the text so far as
 * context. Pure logic.
 */
final class Utterance {

    private final Executor executor;
    private CompletableFuture<String> text = CompletableFuture.completedFuture("");

    Utterance(Executor executor) {
        this.executor = executor;
    }

    /** @param piece from the text so far to the text with this piece; runs after the previous pieces */
    synchronized void add(UnaryOperator<String> piece) {
        text = text.thenApplyAsync(piece, executor);
    }

    /** The whole phrase, once every piece sent so far is transcribed; failed if one of them failed. */
    synchronized CompletableFuture<String> text() {
        return text;
    }

    static String join(String before, String piece) {
        String next = piece == null ? "" : piece.strip();
        return before.isBlank() ? next : next.isEmpty() ? before : before + " " + next;
    }
}
