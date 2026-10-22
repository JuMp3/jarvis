package dev.jarvis.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import dev.jarvis.agent.tools.DocumentText;
import dev.jarvis.agent.tools.DocumentTools;
import jakarta.annotation.PreDestroy;

/**
 * Feeds the long-term memory in the background, without slowing the turns down: the messages of the archive
 * (chat_archive, also those cleared from the screen), the results of the finished tasks (also those hidden from
 * the panel) and the documents of the allowed folders. Where it got to is kept in memory_progress, and for the
 * documents their modification time in memory_document, so nothing is embedded twice.
 */
@Component
public class MemoryIndexer {

    private static final Logger log = LoggerFactory.getLogger(MemoryIndexer.class);
    private static final Duration EVERY = Duration.ofSeconds(20);
    /** Looking at every file costs more than reading two tables: the folders less often. */
    private static final Duration DOCUMENTS_EVERY = Duration.ofMinutes(5);
    private static final int DOCUMENTS_PER_ROUND = 20;
    /** shortcut: the first 200,000 characters of a document (a long book); raise it if whole books are needed */
    private static final int MAX_DOCUMENT_CHARS = 200_000;
    private static final int BATCH = 64;
    /** e5 reads up to 512 tokens: longer texts are split, about 1,200 characters with a little overlap. */
    static final int CHUNK = 1200;
    private static final int OVERLAP = 150;
    /** Automatic task updates are in the memory as tasks already, with their whole result. */
    private static final String TASK_NOTICE = "[Aggiornamento automatico sui task";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.systemDefault());

    private final LongTermMemory memory;
    private final JdbcClient jdbc;
    private final DocumentTools documents;
    private volatile boolean running;
    private Instant documentsDue = Instant.EPOCH;

    public MemoryIndexer(LongTermMemory memory, JdbcClient jdbc, DocumentTools documents) {
        this.memory = memory;
        this.jdbc = jdbc;
        this.documents = documents;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        if (!memory.enabled()) {
            return;
        }
        running = true;
        Thread.ofVirtual().name("memory-indexer").start(() -> {
            while (running) {
                try {
                    int added = indexOnce();
                    if (added > 0) {
                        log.info("Long-term memory: {} new passages", added);
                    }
                    if (added < BATCH) {
                        Thread.sleep(EVERY); // otherwise more is waiting: go on
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    log.warn("Long-term memory: indexing failed, retrying later: {}", e.getMessage());
                    sleepQuietly();
                }
            }
        });
    }

    @PreDestroy
    void stop() {
        running = false;
    }

    /** One round: a batch of messages and of tasks, and the documents when due. @return passages stored */
    int indexOnce() {
        int added = indexMessages() + indexTasks();
        if (Instant.now().isAfter(documentsDue)) {
            int changed = indexDocuments();
            added += changed;
            // a backlog of changed files goes on at the next round, otherwise wait
            documentsDue = changed > 0 ? Instant.EPOCH : Instant.now().plus(DOCUMENTS_EVERY);
        }
        return added;
    }

    /**
     * New or changed files are read and stored again (their old passages replaced), removed files leave the memory.
     * A file that cannot be read is retried only when it changes.
     *
     * @return passages stored
     */
    int indexDocuments() {
        Map<String, long[]> known = new HashMap<>(); // path -> modified, passages
        jdbc.sql("SELECT path, modified, passages FROM memory_document").query((rs, row) ->
                known.put(rs.getString("path"), new long[] {rs.getLong("modified"), rs.getInt("passages")})).list();
        Set<String> present = new HashSet<>();
        int stored = 0;
        int read = 0;
        for (Path file : documents.indexable()) {
            String path = file.toString();
            present.add(path);
            long modified;
            try {
                modified = Files.getLastModifiedTime(file).toMillis();
            } catch (IOException e) {
                continue;
            }
            long[] previous = known.get(path);
            if (previous != null && previous[0] == modified) {
                continue;
            }
            if (read++ == DOCUMENTS_PER_ROUND) {
                break;
            }
            List<Document> passages = List.of();
            try {
                String text = DocumentText.extract(file).strip();
                if (text.length() > MAX_DOCUMENT_CHARS) {
                    text = text.substring(0, MAX_DOCUMENT_CHARS);
                }
                Instant at = Instant.ofEpochMilli(modified);
                passages = passages("doc:" + path, "Documento «" + file.getFileName() + "» (" + path + "), modificato il "
                        + DAY.format(at) + ": ", text, Map.of("source", "document", "path", path, "at", at.toString()));
            } catch (IOException | RuntimeException e) {
                log.debug("Long-term memory: cannot read {}: {}", path, e.getMessage());
            }
            if (previous != null) {
                memory.delete(ids("doc:" + path, (int) previous[1]));
            }
            memory.store(passages);
            stored += passages.size();
            jdbc.sql("""
                            INSERT INTO memory_document (path, modified, passages) VALUES (?, ?, ?)
                            ON CONFLICT (path) DO UPDATE SET modified = EXCLUDED.modified, passages = EXCLUDED.passages""")
                    .params(path, modified, passages.size()).update();
        }
        if (read <= DOCUMENTS_PER_ROUND) { // the whole list was seen: what is missing was removed
            for (Map.Entry<String, long[]> entry : known.entrySet()) {
                if (!present.contains(entry.getKey())) {
                    memory.delete(ids("doc:" + entry.getKey(), (int) entry.getValue()[1]));
                    jdbc.sql("DELETE FROM memory_document WHERE path = ?").param(entry.getKey()).update();
                }
            }
        }
        return stored;
    }

    private int indexMessages() {
        long from = position("chat");
        List<Map<String, Object>> rows = jdbc.sql("""
                        SELECT id, conversation_id, type, content, created_at FROM chat_archive
                        WHERE id > ? ORDER BY id LIMIT ?""")
                .params(from, BATCH).query().listOfRows();
        if (rows.isEmpty()) {
            return 0;
        }
        List<Document> documents = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String type = (String) row.get("type");
            String content = (String) row.get("content");
            if (!("USER".equals(type) || "ASSISTANT".equals(type)) || content == null || content.isBlank()
                    || content.startsWith(TASK_NOTICE)) {
                continue; // tool traffic and automatic notices
            }
            Instant at = ((Timestamp) row.get("created_at")).toInstant();
            String who = "USER".equals(type) ? "L'utente ha detto" : "Jarvis ha risposto";
            documents.addAll(passages("chat:" + row.get("id"), DAY.format(at) + ", " + who + ": ", content.strip(),
                    Map.of("source", "chat", "conversation", row.get("conversation_id"), "at", at.toString())));
        }
        memory.store(documents);
        setPosition("chat", ((Number) rows.getLast().get("id")).longValue());
        return documents.size();
    }

    private int indexTasks() {
        long from = position("task");
        List<Map<String, Object>> rows = jdbc.sql("""
                        SELECT id, conversation_id, agent, title, result, finished_at FROM agent_task
                        WHERE status = 'COMPLETED' AND result IS NOT NULL AND finished_at > ?
                        ORDER BY finished_at LIMIT ?""")
                .params(timestamp(from), BATCH).query().listOfRows();
        if (rows.isEmpty()) {
            return 0;
        }
        List<Document> documents = new ArrayList<>();
        long last = from;
        for (Map<String, Object> row : rows) {
            Instant at = ((Timestamp) row.get("finished_at")).toInstant();
            last = micros(at);
            String head = DAY.format(at) + ", lavoro «" + row.get("title") + "» (" + row.get("agent") + "): ";
            documents.addAll(passages("task:" + row.get("id"), head, ((String) row.get("result")).strip(),
                    Map.of("source", "task", "conversation", row.get("conversation_id"), "at", at.toString())));
        }
        memory.store(documents);
        setPosition("task", last);
        return documents.size();
    }

    /**
     * A text as one or more passages, each starting with when and who, so a passage alone still says where it
     * comes from. Ids derived from the source, so storing again replaces instead of duplicating.
     */
    static List<Document> passages(String source, String head, String text, Map<String, Object> metadata) {
        List<Document> passages = new ArrayList<>();
        int part = 0;
        for (int start = 0; start < text.length(); start += CHUNK - OVERLAP) {
            int end = Math.min(text.length(), start + CHUNK);
            String id = id(source, part++);
            passages.add(Document.builder().id(id).text(head + text.substring(start, end)).metadata(metadata).build());
            if (end == text.length()) {
                break;
            }
        }
        return passages;
    }

    private static String id(String source, int part) {
        return UUID.nameUUIDFromBytes((source + ":" + part).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** The ids of the passages a source had. */
    static List<String> ids(String source, int count) {
        List<String> ids = new ArrayList<>();
        for (int part = 0; part < count; part++) {
            ids.add(id(source, part));
        }
        return ids;
    }

    // Microseconds, as PostgreSQL keeps them: with milliseconds the last task would come back at every round
    static long micros(Instant at) {
        return at.getEpochSecond() * 1_000_000 + at.getNano() / 1_000;
    }

    static Timestamp timestamp(long micros) {
        Timestamp timestamp = new Timestamp(Math.floorDiv(micros, 1_000_000) * 1_000);
        timestamp.setNanos((int) Math.floorMod(micros, 1_000_000) * 1_000);
        return timestamp;
    }

    private long position(String source) {
        return jdbc.sql("SELECT position FROM memory_progress WHERE source = ?").param(source)
                .query(Long.class).optional().orElse(0L);
    }

    private void setPosition(String source, long position) {
        jdbc.sql("""
                        INSERT INTO memory_progress (source, position) VALUES (?, ?)
                        ON CONFLICT (source) DO UPDATE SET position = EXCLUDED.position""")
                .params(source, position).update();
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(EVERY);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
