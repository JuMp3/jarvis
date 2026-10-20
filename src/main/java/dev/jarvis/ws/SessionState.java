package dev.jarvis.ws;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.web.socket.WebSocketSession;

import dev.jarvis.llm.ProviderId;

/**
 * Per-connection state. The socket is a concurrent decorator, so it is safe to send from any thread.
 */
final class SessionState {

    private final WebSocketSession socket;
    private final String conversationId;
    private final AtomicReference<Turn> currentTurn = new AtomicReference<>();
    private volatile ProviderId provider;

    SessionState(WebSocketSession socket, String conversationId, ProviderId provider) {
        this.socket = socket;
        this.conversationId = conversationId;
        this.provider = provider;
    }

    String id() {
        return socket.getId();
    }

    WebSocketSession socket() {
        return socket;
    }

    String conversationId() {
        return conversationId;
    }

    ProviderId provider() {
        return provider;
    }

    void provider(ProviderId provider) {
        this.provider = provider;
    }

    /** Installs a new turn, cancelling the previous one (barge-in). */
    void replaceTurn(Turn turn) {
        Turn previous = currentTurn.getAndSet(turn);
        if (previous != null) {
            previous.cancel();
        }
    }

    void cancelCurrentTurn() {
        Turn previous = currentTurn.getAndSet(null);
        if (previous != null) {
            previous.cancel();
        }
    }
}
