package dev.jarvis.agent;

/**
 * Delivers approval requests to the user (implemented by the WebSocket layer).
 */
public interface ApprovalChannel {

    /**
     * Shows the request in the open tabs of its conversation. Requests of background tasks may find no tab
     * open: they are shown again when the user reconnects.
     *
     * @throws RuntimeException if a request of Jarvis' own turn cannot be delivered (its session is closed)
     */
    void send(ApprovalRequest request);

    /** The request was answered, expired or withdrawn: tabs still showing it must close it. */
    void closed(ApprovalRequest request);
}
