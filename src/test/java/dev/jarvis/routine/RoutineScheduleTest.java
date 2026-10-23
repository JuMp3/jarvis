package dev.jarvis.routine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class RoutineScheduleTest {

    // Friday 23 October 2026
    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 23);

    private static Routine briefing(Set<DayOfWeek> days, LocalDate lastRun) {
        return new Routine("1", "Briefing", LocalTime.of(8, 0), days, "Meteo e posta", true, lastRun);
    }

    @Test
    void runsOnceADayFromItsTimeOnTheChosenDays() {
        Routine weekdays = briefing(EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), null);
        assertThat(RoutineSchedule.due(weekdays, FRIDAY.atTime(7, 59))).isFalse();
        assertThat(RoutineSchedule.due(weekdays, FRIDAY.atTime(8, 0))).isTrue();
        assertThat(RoutineSchedule.due(weekdays, FRIDAY.atTime(9, 30))).as("Jarvis switched on later: still today").isTrue();
        assertThat(RoutineSchedule.due(briefing(weekdays.days(), FRIDAY), FRIDAY.atTime(9, 30))).as("already run").isFalse();
        assertThat(RoutineSchedule.due(briefing(weekdays.days(), FRIDAY.minusDays(1)), FRIDAY.atTime(9, 30))).isTrue();
        assertThat(RoutineSchedule.due(weekdays, FRIDAY.plusDays(1).atTime(9, 0))).as("Saturday").isFalse();
        Routine off = new Routine("1", "Briefing", LocalTime.of(8, 0), weekdays.days(), "x", false, null);
        assertThat(RoutineSchedule.due(off, FRIDAY.atTime(9, 0))).isFalse();
    }

    @Test
    void aRoutineCreatedAfterItsTimeStartsTomorrow() {
        LocalDateTime afternoon = FRIDAY.atTime(15, 0);
        assertThat(RoutineSchedule.initialLastRun(LocalTime.of(8, 0), afternoon)).isEqualTo(FRIDAY);
        assertThat(RoutineSchedule.initialLastRun(LocalTime.of(18, 0), afternoon)).isNull();
    }

    @Test
    void readsTimesAndDaysAsTheUserSaysThem() {
        assertThat(RoutineSchedule.parseTime("8")).isEqualTo(LocalTime.of(8, 0));
        assertThat(RoutineSchedule.parseTime("07.30")).isEqualTo(LocalTime.of(7, 30));
        assertThatThrownBy(() -> RoutineSchedule.parseTime("25:00")).isInstanceOf(IllegalArgumentException.class);

        assertThat(RoutineSchedule.parseDays("ogni giorno")).hasSize(7);
        assertThat(RoutineSchedule.parseDays("lun-ven")).isEqualTo(EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY));
        assertThat(RoutineSchedule.parseDays("sabato e domenica")).containsExactly(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
        assertThat(RoutineSchedule.parseDays("fri-mon")).containsExactlyInAnyOrder(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY,
                DayOfWeek.SUNDAY, DayOfWeek.MONDAY);
        assertThat(RoutineSchedule.format(RoutineSchedule.parseDays("Monday, wed"))).isEqualTo("mon,wed");
        assertThatThrownBy(() -> RoutineSchedule.parseDays("presto")).isInstanceOf(IllegalArgumentException.class);
    }
}
