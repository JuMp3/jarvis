package dev.jarvis.agent;

/**
 * A pending request for the user to approve an action proposed by a tool.
 *
 * @param id          correlation id the client echoes back
 * @param sessionId   WebSocket session that must answer
 * @param description human-readable description of the action
 */
public record ApprovalRequest(String id, String sessionId, String description) {
}
