package dev.jarvis.task;

import dev.jarvis.llm.ProviderId;

/** Runs the tasks of one specialist, to the end, on the calling (virtual) thread. */
interface SpecialistRunner {

    Specialists.Result run(AgentTask task, TaskContext context);

    /** Provider used, to describe its failures. */
    ProviderId provider();

    /**
     * Checks the workspace before the task is queued.
     *
     * @throws IllegalArgumentException with a message for the orchestrator
     */
    default void validate(String workspace) {
    }
}
