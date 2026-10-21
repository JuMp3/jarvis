package dev.jarvis.task;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Task history on PostgreSQL (table {@code agent_task}, see schema.sql), so it survives restarts.
 */
@Repository
public class TaskRepository {

    private final JdbcClient jdbc;

    public TaskRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void save(AgentTask task) {
        jdbc.sql("""
                        INSERT INTO agent_task (id, conversation_id, agent, title, instructions, expected_output,
                                                status, result, error, input_tokens, output_tokens,
                                                created_at, started_at, finished_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status, result = EXCLUDED.result,
                            error = EXCLUDED.error, input_tokens = EXCLUDED.input_tokens,
                            output_tokens = EXCLUDED.output_tokens, started_at = EXCLUDED.started_at,
                            finished_at = EXCLUDED.finished_at""")
                .params(task.id(), task.conversationId(), task.agent(), task.title(), task.instructions(),
                        task.expectedOutput(), task.status().name(), task.result(), task.error(),
                        task.inputTokens(), task.outputTokens(), timestamp(task.createdAt()),
                        timestamp(task.startedAt()), timestamp(task.finishedAt()))
                .update();
    }

    Optional<AgentTask> find(String id) {
        return jdbc.sql("SELECT * FROM agent_task WHERE id = ?").param(id).query(TaskRepository::map).optional();
    }

    /** Most recent tasks of a conversation, oldest first. */
    List<AgentTask> recent(String conversationId, int limit) {
        return jdbc.sql("""
                        SELECT * FROM (SELECT * FROM agent_task WHERE conversation_id = ?
                                       ORDER BY created_at DESC LIMIT ?) recent
                        ORDER BY created_at""")
                .params(conversationId, limit)
                .query(TaskRepository::map)
                .list();
    }

    /** Tasks that were queued or running when the application stopped can no longer finish. */
    int markInterrupted(String reason) {
        return jdbc.sql("""
                        UPDATE agent_task SET status = 'INTERRUPTED', error = ?, finished_at = now()
                        WHERE status IN ('QUEUED', 'RUNNING')""")
                .param(reason)
                .update();
    }

    private static AgentTask map(ResultSet rs, int row) throws SQLException {
        return new AgentTask(rs.getString("id"), rs.getString("conversation_id"), rs.getString("agent"),
                rs.getString("title"), rs.getString("instructions"), rs.getString("expected_output"),
                AgentTask.Status.valueOf(rs.getString("status")), rs.getString("result"), rs.getString("error"),
                (Integer) rs.getObject("input_tokens"), (Integer) rs.getObject("output_tokens"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")));
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
