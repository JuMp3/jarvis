package dev.jarvis.task;

import java.time.Instant;

/**
 * Snapshot of a background task delegated to a specialist. Immutable: every change makes a new copy.
 *
 * @param expectedOutput what the orchestrator asked to get back; may be null
 * @param workspace      repository the task works on (coder only); may be null
 * @param progress       latest activity while running, e.g. "Modifica App.java"; may be null
 * @param result         the specialist's answer, set when completed
 * @param error          user-facing reason, set when failed, cancelled or interrupted
 * @param costUsd        cost reported by the specialist (Claude Code); null when unknown
 */
public record AgentTask(String id, String conversationId, String agent, String title, String instructions,
                        String expectedOutput, String workspace, Status status, String progress, String result,
                        String error, Integer inputTokens, Integer outputTokens, Double costUsd,
                        Instant createdAt, Instant startedAt, Instant finishedAt) {

    public enum Status {
        QUEUED, RUNNING, WAITING_APPROVAL, COMPLETED, FAILED, CANCELLED, INTERRUPTED;

        public boolean terminal() {
            return this != QUEUED && this != RUNNING && this != WAITING_APPROVAL;
        }
    }

    static AgentTask queued(String id, String conversationId, String agent, String title, String instructions,
                            String expectedOutput, String workspace) {
        return new AgentTask(id, conversationId, agent, title, instructions, expectedOutput, workspace,
                Status.QUEUED, null, null, null, null, null, null, Instant.now(), null, null);
    }

    AgentTask running() {
        return with(Status.RUNNING, progress, startedAt == null ? Instant.now() : startedAt);
    }

    AgentTask waitingApproval() {
        return with(Status.WAITING_APPROVAL, "Aspetta la tua approvazione", startedAt);
    }

    AgentTask withProgress(String progress) {
        return with(status, progress, startedAt);
    }

    AgentTask completed(Specialists.Result result) {
        return new AgentTask(id, conversationId, agent, title, instructions, expectedOutput, workspace,
                Status.COMPLETED, null, result.text(), null, result.inputTokens(), result.outputTokens(),
                result.costUsd(), createdAt, startedAt, Instant.now());
    }

    AgentTask ended(Status status, String error) {
        return new AgentTask(id, conversationId, agent, title, instructions, expectedOutput, workspace, status,
                null, null, error, inputTokens, outputTokens, costUsd, createdAt, startedAt, Instant.now());
    }

    private AgentTask with(Status status, String progress, Instant startedAt) {
        return new AgentTask(id, conversationId, agent, title, instructions, expectedOutput, workspace, status,
                progress, result, error, inputTokens, outputTokens, costUsd, createdAt, startedAt, finishedAt);
    }
}
