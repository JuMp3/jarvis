package dev.jarvis.task;

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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.task.AgentTask.Status;
import dev.jarvis.ws.ErrorMessages;

/**
 * Runs specialist tasks in the background, at most {@code jarvis.tasks.max-parallel} at a time.
 *
 * <p>Every state change is saved and published as a {@link TaskUpdated} event: the WebSocket layer
 * forwards it to the user's sessions and, when the task ends, lets Jarvis report the outcome.
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
    private final Semaphore slots;
    private final Duration timeout;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService timers =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("task-timeout").factory());
    /** Tasks not yet ended, with the thread running them. */
    private final Map<String, AgentTask> active = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> futures = new ConcurrentHashMap<>();

    public TaskService(Specialists specialists, TaskRepository repository, ApplicationEventPublisher events,
                       JarvisProperties properties) {
        this.specialists = specialists;
        this.repository = repository;
        this.events = events;
        this.slots = new Semaphore(Math.max(1, properties.tasks().maxParallel()), true);
        this.timeout = properties.tasks().timeout();
        int interrupted = repository.markInterrupted("Jarvis è stato riavviato mentre il task era in corso.");
        if (interrupted > 0) {
            log.info("Marked {} unfinished tasks as interrupted", interrupted);
        }
    }

    /**
     * Queues a task and returns immediately.
     *
     * @throws IllegalArgumentException if the specialist does not exist
     */
    public AgentTask start(String conversationId, String agent, String title, String instructions,
                           String expectedOutput) {
        if (!specialists.exists(agent)) {
            throw new IllegalArgumentException("Specialista sconosciuto: " + agent
                    + ". Disponibili: " + specialists.ids());
        }
        AgentTask task = AgentTask.queued(UUID.randomUUID().toString(), conversationId, agent, title,
                instructions, expectedOutput);
        active.put(task.id(), task);
        publish(task);
        futures.put(task.id(), workers.submit(() -> execute(task.id())));
        return task;
    }

    /** Cancels a queued or running task of the conversation; false if unknown or already ended. */
    public boolean cancel(String conversationId, String taskId) {
        AgentTask task = active.get(taskId);
        if (task == null || !task.conversationId().equals(conversationId)) {
            return false;
        }
        boolean cancelled = end(taskId, current -> current.ended(Status.CANCELLED, "Annullato."));
        Future<?> future = futures.remove(taskId);
        if (future != null) {
            future.cancel(true);
        }
        return cancelled;
    }

    /** Cancels every task of the conversation that is still queued or running. */
    public int cancelAll(String conversationId) {
        List<String> ids = active.values().stream()
                .filter(task -> task.conversationId().equals(conversationId))
                .map(AgentTask::id)
                .toList();
        return (int) ids.stream().filter(id -> cancel(conversationId, id)).count();
    }

    public List<AgentTask> recent(String conversationId) {
        return repository.recent(conversationId, RECENT_LIMIT);
    }

    public Optional<AgentTask> find(String taskId) {
        AgentTask task = active.get(taskId);
        return task != null ? Optional.of(task) : repository.find(taskId);
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
            var timer = timers.schedule(() -> timeOut(taskId), timeout.toMillis(), TimeUnit.MILLISECONDS);
            try {
                Specialists.Result result = specialists.run(running);
                end(taskId, current -> current.completed(result.text(), result.inputTokens(), result.outputTokens()));
            } catch (RuntimeException e) {
                if (!Thread.currentThread().isInterrupted()) {
                    log.warn("Task {} ({}) failed", taskId, running.agent(), e);
                    end(taskId, current -> current.ended(Status.FAILED,
                            ErrorMessages.forProvider(specialists.provider(running.agent()), e)));
                }
            } finally {
                timer.cancel(false);
            }
        } finally {
            slots.release();
            futures.remove(taskId);
        }
    }

    private void timeOut(String taskId) {
        if (end(taskId, current -> current.ended(Status.FAILED,
                "Tempo scaduto dopo " + timeout.toMinutes() + " minuti."))) {
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
