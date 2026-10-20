package dev.jarvis.agent;

/**
 * Delivers approval requests to the user (implemented by the WebSocket layer).
 */
@FunctionalInterface
public interface ApprovalChannel {

    /**
     * @throws RuntimeException if the request cannot be delivered (e.g. the session is closed)
     */
    void send(ApprovalRequest request);
}
