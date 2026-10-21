package dev.jarvis.ws;

import java.util.concurrent.ExecutorService;

import reactor.core.Disposable;

/**
 * A running answer: the LLM stream plus its speech queue. Cancelling stops both and denies any
 * approval the turn is waiting for.
 */
final class Turn {

    private final ExecutorService speechQueue;
    private final Runnable onCancel;
    private volatile Disposable subscription;
    private volatile boolean cancelled;
    private volatile boolean speechFailed;

    Turn(ExecutorService speechQueue, Runnable onCancel) {
        this.speechQueue = speechQueue;
        this.onCancel = onCancel;
    }

    void attach(Disposable subscription) {
        this.subscription = subscription;
        if (cancelled) {
            subscription.dispose();
        }
    }

    boolean cancelled() {
        return cancelled;
    }

    /** After the first synthesis error the turn goes on as text only, with a single error message. */
    boolean speechFailed() {
        return speechFailed;
    }

    void markSpeechFailed() {
        speechFailed = true;
    }

    synchronized void cancel() {
        if (cancelled) {
            return;
        }
        cancelled = true;
        Disposable current = subscription;
        if (current != null) {
            current.dispose();
        }
        if (speechQueue != null) {
            speechQueue.shutdownNow();
        }
        onCancel.run();
    }
}
