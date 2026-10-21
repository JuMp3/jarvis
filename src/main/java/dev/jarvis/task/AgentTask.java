package dev.jarvis.task;

import java.time.Instant;

/**
 * Snapshot of a background task delegated to a specialist. Immutable: every change makes a new copy.
 *
 * @param expectedOutput what the orchestrator asked to get back; may be null
 * @param result         the specialist's answer, set when completed
 * @param error          user-facing reason, set when failed, cancelled or interrupted
 */
public record AgentTask(String id, String conversationId, String agent, String title, String instructions,
                        String expectedOutput, Status status, String result, String error,
                        Integer inputTokens, Integer outputTokens,
                        Instant createdAt, Instant startedAt, Instant finishedAt) {

    public enum Status {
        QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED, INTERRUPTED;

        public boolean terminal() {
            return this != QUEUED && this != RUNNING;
        }
    }

    static AgentTask queued(String id, String conversationId, String agent, String title, String instructions,
                            String expectedOutput) {
        return new AgentTask(id, conversationId, agent, title, instructions, expectedOutput, Status.QUEUED,
                null, null, null, null, Instant.now(), null, null);
    }

    AgentTask running() {
        return new AgentTask(id, conversationId, agent, title, instructions, expectedOutput, Status.RUNNING,
                null, null, null, null, createdAt, Instant.now(), null);
    }

    AgentTask completed(String result, Integer inputTokens, Integer outputTokens) {
        return new AgentTask(id, conversationId, agent, title, instructions, expectedOutput, Status.COMPLETED,
                result, null, inputTokens, outputTokens, createdAt, startedAt, Instant.now());
    }

    AgentTask ended(Status status, String error) {
        return new AgentTask(id, conversationId, agent, title, instructions, expectedOutput, status,
                null, error, inputTokens, outputTokens, createdAt, startedAt, Instant.now());
    }
}
