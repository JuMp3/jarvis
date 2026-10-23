package dev.jarvis.routine;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.jarvis.agent.PostgresChatMemoryRepository;
import dev.jarvis.i18n.Text;
import dev.jarvis.task.TaskService;

/**
 * Starts the routines at their time, as tasks of the routine specialist in the user's ongoing conversation: they
 * show in the Squadra panel, Jarvis reports their result, and their approvals wait for the user like any task's.
 * A routine missed while Jarvis was off runs as soon as he is on again, the same day only.
 */
@Service
public class RoutineService {

    private static final Logger log = LoggerFactory.getLogger(RoutineService.class);
    /** After a start the MCP servers (mail, calendar) need up to a minute to connect: routines wait for them. */
    private static final long FIRST_CHECK_SECONDS = 60;
    private static final long CHECK_SECONDS = 30;

    private final RoutineRepository repository;
    private final TaskService tasks;
    private final PostgresChatMemoryRepository archive;
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("routines").factory());

    public RoutineService(RoutineRepository repository, TaskService tasks, PostgresChatMemoryRepository archive) {
        this.repository = repository;
        this.tasks = tasks;
        this.archive = archive;
        timer.scheduleWithFixedDelay(this::check, FIRST_CHECK_SECONDS, CHECK_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        timer.shutdownNow();
    }

    void check() {
        try {
            LocalDateTime now = LocalDateTime.now();
            for (Routine routine : repository.all()) {
                if (RoutineSchedule.due(routine, now) && repository.markRun(routine.id(), now.toLocalDate())) {
                    run(routine);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Cannot check the routines", e); // the next check tries again
        }
    }

    private void run(Routine routine) {
        // One user, one ongoing conversation: the one the page resumes
        String conversationId = archive.latestConversationId().orElseGet(() -> UUID.randomUUID().toString());
        tasks.startRoutine(conversationId, routine.name(), routine.instructions()).ifPresentOrElse(
                task -> log.info("Routine {} started as task {}", routine.name(), task.id()),
                () -> log.warn("Routine {} not run: no routine specialist (jarvis.agents.*.routine)", routine.name()));
    }

    /**
     * A new routine, not saved yet.
     *
     * @throws IllegalArgumentException with a message for the model
     */
    public Routine prepare(String name, String time, String days, String instructions) {
        if (name == null || name.isBlank() || instructions == null || instructions.isBlank()) {
            throw new IllegalArgumentException("Servono un nome e le istruzioni della routine.");
        }
        LocalTime at = RoutineSchedule.parseTime(time);
        return new Routine(UUID.randomUUID().toString(), name.strip(), at, RoutineSchedule.parseDays(days),
                instructions.strip(), true, RoutineSchedule.initialLastRun(at, LocalDateTime.now()));
    }

    /** "lun, mer, ven" or "Mon, Wed, Fri"; "ogni giorno" / "every day" for all of them. */
    public static String describeDays(Set<DayOfWeek> days, String language) {
        if (days.size() == DayOfWeek.values().length) {
            return Text.text("ogni giorno", "every day").in(language);
        }
        return days.stream().sorted().map(day -> day.getDisplayName(TextStyle.SHORT, Text.locale(language)))
                .collect(Collectors.joining(", "));
    }
}
