package dev.jarvis.task;

/**
 * A specialist stopping for a reason the user should read as is ("il repository ha modifiche non salvate").
 * Any other exception is turned into a generic message by {@code ErrorMessages}.
 */
public class TaskFailure extends RuntimeException {

    private final AgentTask.Status status;

    public TaskFailure(String userMessage) {
        this(AgentTask.Status.FAILED, userMessage);
    }

    /** @param status FAILED, or CANCELLED when the user declined (e.g. a plan not approved) */
    public TaskFailure(AgentTask.Status status, String userMessage) {
        super(userMessage);
        this.status = status;
    }

    public AgentTask.Status status() {
        return status;
    }
}
