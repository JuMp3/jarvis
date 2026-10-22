package dev.jarvis.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.jarvis.BaseTest;
import dev.jarvis.config.JarvisProperties;

class MemoryTest extends BaseTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JdbcClient jdbc;

    /** Deterministic stand-in for e5: one dimension per word, so texts sharing words are close. Records its input. */
    static final class WordEmbeddings implements EmbeddingModel {
        final List<String> seen = new ArrayList<>();

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> embeddings = new ArrayList<>();
            for (String text : request.getInstructions()) {
                seen.add(text);
                float[] vector = new float[LongTermMemory.DIMENSIONS];
                for (String word : text.toLowerCase().split("[^\\p{L}]+")) {
                    if (word.length() > 3) {
                        vector[Math.floorMod(word.hashCode(), vector.length)] += 1;
                    }
                }
                embeddings.add(new Embedding(vector, embeddings.size()));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return embed(document.getText());
        }
    }

    @Test
    void e5GetsItsPrefixes() {
        WordEmbeddings words = new WordEmbeddings();
        E5Embeddings e5 = new E5Embeddings(words);
        e5.embed("vacanze");
        e5.embed(List.of(new Document("Ad agosto in Sardegna")), null, null);
        assertThat(words.seen).containsExactly("query: vacanze", "passage: Ad agosto in Sardegna");
    }

    @Test
    void longTextsBecomeOverlappingPassagesWithStableIds() {
        String text = "x".repeat(MemoryIndexer.CHUNK * 2);
        List<Document> first = MemoryIndexer.passages("chat:7", "22/10/2026, L'utente ha detto: ", text, Map.of());
        List<Document> again = MemoryIndexer.passages("chat:7", "22/10/2026, L'utente ha detto: ", text, Map.of());
        assertThat(first).hasSize(3);
        assertThat(first).allMatch(passage -> passage.getText().startsWith("22/10/2026, L'utente ha detto: "));
        assertThat(first).extracting(Document::getId).containsExactlyElementsOf(again.stream().map(Document::getId).toList());
    }

    @Test
    void messagesAndTaskResultsAreIndexedOnceAndFound() {
        LongTermMemory memory = new LongTermMemory(jdbcTemplate, new WordEmbeddings(), 0.1);
        MemoryIndexer indexer = new MemoryIndexer(memory, jdbc);
        String conversation = "memtest-" + UUID.randomUUID();
        jdbc.sql("INSERT INTO chat_archive (conversation_id, type, content) VALUES (?, 'USER', ?), (?, 'TOOL', ?)")
                .params(conversation, "Ad agosto andrò in vacanza in Sardegna, a Cagliari", conversation, "tool traffic")
                .update();
        jdbc.sql("""
                        INSERT INTO agent_task (id, conversation_id, agent, title, instructions, status, result, created_at,
                                                finished_at)
                        VALUES (?, ?, 'researcher', 'Treni', 'cerca', 'COMPLETED', ?, now(), now())""")
                .params(UUID.randomUUID().toString(), conversation, "Il treno Frecciarossa per Napoli parte alle 8:15")
                .update();

        assertThat(indexer.indexOnce()).isGreaterThanOrEqualTo(2);
        while (indexer.indexOnce() > 0) {
            // other tests may have left archive rows: drain them
        }
        assertThat(indexer.indexOnce()).isZero();

        assertThat(memory.search("vacanza Sardegna Cagliari", 3)).extracting(Document::getText)
                .anyMatch(text -> text.contains("L'utente ha detto: Ad agosto andrò in vacanza in Sardegna"));
        assertThat(memory.search("treno Frecciarossa Napoli", 3)).extracting(Document::getText)
                .anyMatch(text -> text.contains("lavoro «Treni» (researcher): Il treno Frecciarossa"));
        assertThat(memory.search("tool traffic", 3)).extracting(Document::getText)
                .noneMatch(text -> text.contains("tool traffic"));
    }

    /** The real model: runs only where it is prepared (desktop/prepare-memory.ps1). */
    @Test
    void theRealModelFindsByMeaningAndNotNoise() {
        Path home = Path.of(System.getenv().getOrDefault("JARVIS_MEMORY_HOME", "desktop/src-tauri/resources/memory"));
        assumeTrue(Files.isRegularFile(home.resolve("model.onnx")), "memory model not prepared in " + home.toAbsolutePath());
        LongTermMemory memory = new LongTermMemory(new JarvisProperties(null, null, null, null, null, null, null, null,
                null, new JarvisProperties.Memory(home.toAbsolutePath().toString())), jdbcTemplate);
        String marker = UUID.randomUUID().toString().substring(0, 8);
        memory.store(List.of(
                passage(marker, "L'utente ha detto: ad agosto vado al mare in Sardegna con la famiglia"),
                passage(marker, "L'utente ha detto: domani alle 10 ho la riunione con il cliente sul budget"),
                passage(marker, "Jarvis ha risposto: ho mandato l'email a Marco con il preventivo"),
                passage(marker, "lavoro «Meteo Milano»: domani a Milano pioggia, minima 9 e massima 14 gradi")));

        List<Document> holidays = memory.search("dove andrò in vacanza quest'estate?", 3);
        assertThat(holidays).isNotEmpty();
        assertThat(holidays.getFirst().getText()).contains("Sardegna");
        assertThat(memory.search("che tempo farà a Milano?", 3).getFirst().getText()).contains("Meteo Milano");
        assertThat(memory.search("ricetta della carbonara con il guanciale", 3))
                .noneMatch(found -> found.getText().contains(marker));
    }

    private static Document passage(String marker, String text) {
        return Document.builder().id(UUID.randomUUID().toString()).text("22/10/2026, " + text + " [" + marker + "]")
                .metadata(Map.of("source", "test")).build();
    }
}
