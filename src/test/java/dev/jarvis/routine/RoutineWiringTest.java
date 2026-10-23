package dev.jarvis.routine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import dev.jarvis.BaseTest;
import dev.jarvis.llm.ProviderRouter;
import dev.jarvis.task.Specialists;
import dev.jarvis.task.TaskService;

/** The routine specialist runs the routines only: Jarvis neither sees it nor delegates to it. */
class RoutineWiringTest extends BaseTest {

    @Autowired
    Specialists specialists;
    @Autowired
    TaskService tasks;
    @Autowired
    ProviderRouter router;
    @Autowired
    RoutineRepository repository;

    @Test
    void theRoutineSpecialistIsNotJarvisToDelegateTo() {
        assertThat(specialists.routineRunner()).contains("routine");
        assertThat(specialists.ids()).doesNotContain("routine").contains("researcher");
        assertThat(router.systemPrompt()).doesNotContain("- routine:").contains("createRoutine");
        assertThatThrownBy(() -> tasks.start("conversation", "routine", "x", "y", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRoutineRunsOnceADay() {
        Routine routine = new RoutineService(repository, tasks, null).prepare("Briefing", "00:00", "every day", "Meteo");
        repository.save(new Routine(routine.id(), routine.name(), routine.at(), routine.days(), routine.instructions(),
                true, null));
        LocalDate today = LocalDate.now();
        assertThat(repository.markRun(routine.id(), today)).isTrue();
        assertThat(repository.markRun(routine.id(), today)).as("a second check the same day").isFalse();
        assertThat(repository.find(routine.id()).orElseThrow().lastRun()).isEqualTo(today);
        repository.delete(routine.id());
    }
}
