package dev.jarvis.task;

import static dev.jarvis.i18n.Text.text;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import dev.jarvis.agent.ApprovalService;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.task.AgentTask.Status;
import dev.jarvis.ws.ErrorMessages;

/**
 * Runs specialist tasks in the background, at most {@code jarvis.tasks.max-parallel} at a time.
 *
 * <p>Every state change is saved and published as a {@link TaskUpdated} event: the WebSocket layer
 * forwards it to the user's tabs and, when the task ends, lets Jarvis report the outcome.
 */
@Service
public class TaskService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);
    private static final int RECENT_LIMIT = 30;

    /** Published after every change of a task. */
    public record TaskUpdated(AgentTask task) {
    }

    private final Specialists specialists;
    private final TaskRepository repository;
    private final ApplicationEventPublisher events;
    private final ApprovalService approvals;
    private final Semaphore slots;
    private final Duration defaultTimeout;
    private final String language;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService timers =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("task-timeout").factory());
    /** Tasks not yet ended, with the thread running them. */
    private final Map<String, AgentTask> active = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> futures = new ConcurrentHashMap<>();

    public TaskService(Specialists specialists, TaskRepository repository, ApplicationEventPublisher events,
                       ApprovalService approvals, JarvisProperties properties) {
        this.specialists = specialists;
        this.repository = repository;
        this.events = events;
        this.approvals = approvals;
        this.slots = new Semaphore(Math.max(1, properties.tasks().maxParallel()), true);
        this.defaultTimeout = properties.tasks().timeout();
        this.language = properties.language();
        int interrupted = repository.markInterrupted(text("Jarvis è stato riavviato mentre il task era in corso.",
                "Jarvis was restarted while the task was running.").in(language));
        if (interrupted > 0) {
            log.info("Marked {} unfinished tasks as interrupted", interrupted);
        }
    }

    /**
     * Queues a task and returns immediately.
     *
     * @param workspace repository to work on, for coding specialists; may be null
     * @throws IllegalArgumentException if the specialist does not exist or the workspace is not allowed
     */
    public AgentTask start(String conversationId, String agent, String title, String instructions,
                           String expectedOutput, String workspace) {
        specialists.validate(agent, workspace);
        AgentTask task = AgentTask.queued(UUID.randomUUID().toString(), conversationId, agent, title,
                instructions, expectedOutput, workspace == null || workspace.isBlank() ? null : workspace.strip());
        active.put(task.id(), task);
        publish(task);
        futures.put(task.id(), workers.submit(() -> execute(task.id())));
        return task;
    }

    /** Cancels a task of the conversation that has not ended; false if unknown or already ended. */
    public boolean cancel(String conversationId, String taskId) {
        AgentTask task = active.get(taskId);
        if (task == null || !task.conversationId().equals(conversationId)) {
            return false;
        }
        boolean cancelled = end(taskId, current -> current.ended(Status.CANCELLED, "Fermato."));
        Future<?> future = futures.remove(taskId);
        if (future != null) {
            future.cancel(true); // also withdraws a pending approval and stops external processes
        }
        return cancelled;
    }

    /** Cancels every task of the conversation that has not ended. */
    public int cancelAll(String conversationId) {
        List<String> ids = active.values().stream()
                .filter(task -> task.conversationId().equals(conversationId))
                .map(AgentTask::id)
                .toList();
        return (int) ids.stream().filter(id -> cancel(conversationId, id)).count();
    }

    /** Takes the tasks of the conversation that have ended off the panel; the ones still at work stay. */
    public int clearFinished(String conversationId) {
        return repository.hideFinished(conversationId);
    }

    public List<AgentTask> recent(String conversationId) {
        return repository.recent(conversationId, RECENT_LIMIT);
    }

    public Optional<AgentTask> find(String taskId) {
        AgentTask task = active.get(taskId);
        return task != null ? Optional.of(task) : repository.find(taskId);
    }

    public List<Path> repositories() {
        return specialists.repositories();
    }

    private void execute(String taskId) {
        try {
            slots.acquire();
        } catch (InterruptedException e) {
            return; // cancelled while queued
        }
        try {
            AgentTask running = update(taskId, current -> current.status() == Status.QUEUED ? current.running() : null);
            if (running == null) {
                return;
            }
            Clock clock = new Clock(taskId, specialists.timeout(running.agent()).orElse(defaultTimeout));
            try {
                Specialists.Result result = specialists.run(running, new Context(taskId, clock));
                end(taskId, current -> current.completed(result));
            } catch (TaskFailure e) {
                end(taskId, current -> current.ended(e.status(), e.getMessage()));
            } catch (RuntimeException e) {
                if (!Thread.currentThread().isInterrupted()) {
                    log.warn("Task {} ({}) failed", taskId, running.agent(), e);
                    end(taskId, current -> current.ended(Status.FAILED,
                            ErrorMessages.forProvider(language, specialists.provider(running.agent()), e)));
                }
            } finally {
                clock.stop();
            }
        } finally {
            slots.release();
            futures.remove(taskId);
        }
    }

    /** The task's time limit, paused while the user is deciding on an approval. */
    private final class Clock {

        private final String taskId;
        private long remainingMs;
        private long startedAt;
        private ScheduledFuture<?> timer;

        Clock(String taskId, Duration limit) {
            this.taskId = taskId;
            this.remainingMs = limit.toMillis();
            resume();
        }

        synchronized void pause() {
            if (timer != null) {
                timer.cancel(false);
                timer = null;
                remainingMs -= System.currentTimeMillis() - startedAt;
            }
        }

        synchronized void resume() {
            startedAt = System.currentTimeMillis();
            timer = timers.schedule(() -> timeOut(taskId), Math.max(0, remainingMs), TimeUnit.MILLISECONDS);
        }

        synchronized void stop() {
            if (timer != null) {
                timer.cancel(false);
            }
        }
    }

    private final class Context implements TaskContext {

        private final String taskId;
        private final Clock clock;

        Context(String taskId, Clock clock) {
            this.taskId = taskId;
            this.clock = clock;
        }

        @Override
        public void progress(String message) {
            update(taskId, current -> current.status() == Status.RUNNING ? current.withProgress(message) : null);
        }

        @Override
        public boolean requestApproval(String description) {
            AgentTask waiting = update(taskId, current -> current.status() == Status.RUNNING ? current.waitingApproval() : null);
            if (waiting == null) {
                return false;
            }
            clock.pause();
            try {
                return approvals.requestTaskApproval(waiting.conversationId(), waiting.agent(), waiting.title(),
                        description);
            } finally {
                update(taskId, current -> current.status() == Status.WAITING_APPROVAL ? current.running() : null);
                clock.resume();
            }
        }
    }

    private void timeOut(String taskId) {
        AgentTask task = active.get(taskId);
        String reason = task == null ? text("Tempo scaduto.", "Out of time.").in(language)
                : text("Tempo scaduto dopo %s minuti.", "Out of time after %s minutes.")
                        .in(language, specialists.timeout(task.agent()).orElse(defaultTimeout).toMinutes());
        if (end(taskId, current -> current.ended(Status.FAILED, reason))) {
            Future<?> future = futures.remove(taskId);
            if (future != null) {
                future.cancel(true);
            }
        }
    }

    /** Applies a terminal change once; later attempts (cancel vs. completion races) are ignored. */
    private boolean end(String taskId, UnaryOperator<AgentTask> change) {
        AgentTask ended = update(taskId, current -> current.status().terminal() ? null : change.apply(current));
        if (ended == null) {
            return false;
        }
        active.remove(taskId);
        log.atInfo().setMessage("Task ended")
                .addKeyValue("taskId", taskId).addKeyValue("agent", ended.agent())
                .addKeyValue("status", ended.status()).addKeyValue("conversationId", ended.conversationId())
                .addKeyValue("inputTokens", ended.inputTokens()).addKeyValue("outputTokens", ended.outputTokens())
                .addKeyValue("costUsd", ended.costUsd())
                .log();
        return true;
    }

    /** @param change returns the new snapshot, or null to leave the task as it is */
    private synchronized AgentTask update(String taskId, UnaryOperator<AgentTask> change) {
        AgentTask current = active.get(taskId);
        AgentTask next = current == null ? null : change.apply(current);
        if (next == null) {
            return null;
        }
        active.put(taskId, next);
        publish(next);
        return next;
    }

    private void publish(AgentTask task) {
        repository.save(task);
        events.publishEvent(new TaskUpdated(task));
    }

    @Override
    public void destroy() {
        timers.shutdownNow();
        workers.shutdownNow();
    }
}
