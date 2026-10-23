package dev.jarvis.routine;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** The routines on PostgreSQL (table {@code routine}, see schema.sql). */
@Repository
public class RoutineRepository {

    private final JdbcClient jdbc;

    public RoutineRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Routine> all() {
        return jdbc.sql("SELECT * FROM routine ORDER BY at_time, name").query(RoutineRepository::routine).list();
    }

    public Optional<Routine> find(String id) {
        return jdbc.sql("SELECT * FROM routine WHERE id = ?").param(id).query(RoutineRepository::routine).optional();
    }

    public void save(Routine routine) {
        jdbc.sql("""
                        INSERT INTO routine (id, name, at_time, days, instructions, enabled, last_run)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, at_time = EXCLUDED.at_time,
                            days = EXCLUDED.days, instructions = EXCLUDED.instructions, enabled = EXCLUDED.enabled,
                            last_run = EXCLUDED.last_run""")
                .params(routine.id(), routine.name(), Time.valueOf(routine.at()), RoutineSchedule.format(routine.days()),
                        routine.instructions(), routine.enabled(),
                        routine.lastRun() == null ? null : Date.valueOf(routine.lastRun()))
                .update();
    }

    public boolean delete(String id) {
        return jdbc.sql("DELETE FROM routine WHERE id = ?").param(id).update() > 0;
    }

    /** The list of the Settings section replaces the routines: the ones not in it are removed. */
    @Transactional
    public void replaceAll(List<Routine> routines) {
        Map<String, Routine> kept = routines.stream().collect(Collectors.toMap(Routine::id, Function.identity()));
        all().stream().filter(routine -> !kept.containsKey(routine.id())).forEach(routine -> delete(routine.id()));
        routines.forEach(this::save);
    }

    /**
     * Records that the routine runs today, unless it already did: with two checks at once (a restart), only one
     * gets true.
     */
    public boolean markRun(String id, LocalDate today) {
        return jdbc.sql("UPDATE routine SET last_run = ? WHERE id = ? AND (last_run IS NULL OR last_run < ?)")
                .params(Date.valueOf(today), id, Date.valueOf(today))
                .update() > 0;
    }

    private static Routine routine(ResultSet row, int rowNumber) throws SQLException {
        Date lastRun = row.getDate("last_run");
        return new Routine(row.getString("id"), row.getString("name"), row.getTime("at_time").toLocalTime(),
                RoutineSchedule.parseDays(row.getString("days")), row.getString("instructions"),
                row.getBoolean("enabled"), lastRun == null ? null : lastRun.toLocalDate());
    }
}
