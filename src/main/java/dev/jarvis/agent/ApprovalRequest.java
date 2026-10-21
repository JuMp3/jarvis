package dev.jarvis.agent;

/**
 * A pending request for the user to approve an action.
 *
 * @param id             correlation id the client echoes back
 * @param conversationId any open tab of this conversation may answer
 * @param sessionId      for an action of Jarvis' own turn, the session running it: the request is denied
 *                       when that turn ends. Null for background tasks, whose requests survive reconnections
 * @param agent          specialist asking, null for Jarvis itself
 * @param taskTitle      task the request belongs to, null for Jarvis itself
 * @param description    human-readable description of the action
 */
public record ApprovalRequest(String id, String conversationId, String sessionId, String agent, String taskTitle,
                              String description) {

    public boolean fromTask() {
        return sessionId == null;
    }
}
