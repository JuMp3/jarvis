package dev.jarvis.memory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import jakarta.annotation.PreDestroy;

/**
 * Long-term memory: what was said and done, as vectors in pgvector (table {@code memory_vector}, see schema.sql),
 * searched by meaning. The embeddings are computed here, with multilingual-e5-small (ONNX) from
 * {@code jarvis.memory.home} (desktop/prepare-memory.ps1). Without the model the memory is off and Jarvis works
 * as before.
 */
@Component
public class LongTermMemory {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemory.class);
    static final int DIMENSIONS = 384;
    /** Below this cosine similarity e5 matches are mostly noise. */
    static final double MIN_SIMILARITY = 0.78;

    private final VectorStore store;
    private final TransformersEmbeddingModel model;
    private final double minSimilarity;

    @Autowired
    public LongTermMemory(JarvisProperties properties, JdbcTemplate jdbc) {
        TransformersEmbeddingModel loaded = load(properties.memory() == null ? null : properties.memory().home());
        this.model = loaded;
        this.store = loaded == null ? null : store(jdbc, new E5Embeddings(loaded));
        this.minSimilarity = MIN_SIMILARITY;
    }

    /** With any embedding model and threshold, for tests. */
    LongTermMemory(JdbcTemplate jdbc, EmbeddingModel embeddings, double minSimilarity) {
        this.model = null;
        this.store = store(jdbc, embeddings);
        this.minSimilarity = minSimilarity;
    }

    public boolean enabled() {
        return store != null;
    }

    void store(List<Document> documents) {
        if (store != null && !documents.isEmpty()) {
            store.add(documents);
        }
    }

    /** The memories closest in meaning to the query, most similar first. */
    public List<Document> search(String query, int max) {
        if (store == null || query == null || query.isBlank()) {
            return List.of();
        }
        return store.similaritySearch(SearchRequest.builder().query(query).topK(max)
                .similarityThreshold(minSimilarity).build());
    }

    private static VectorStore store(JdbcTemplate jdbc, EmbeddingModel embeddings) {
        // The table is created by schema.sql: initializing it here would also install hstore and uuid-ossp
        return PgVectorStore.builder(jdbc, embeddings)
                .vectorTableName("memory_vector")
                .dimensions(DIMENSIONS)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .initializeSchema(false)
                .build();
    }

    private static TransformersEmbeddingModel load(String home) {
        Path modelFile = home == null ? null : Path.of(home, "model.onnx");
        Path tokenizerFile = home == null ? null : Path.of(home, "tokenizer.json");
        if (modelFile == null || !Files.isRegularFile(modelFile) || !Files.isRegularFile(tokenizerFile)) {
            log.warn("Long-term memory off: no model in {} (desktop/prepare-memory.ps1, or set jarvis.memory.home)", home);
            return null;
        }
        try {
            TransformersEmbeddingModel model = new TransformersEmbeddingModel();
            model.setModelResource(new FileSystemResource(modelFile));
            model.setTokenizerResource(new FileSystemResource(tokenizerFile));
            model.setDisableCaching(true); // a local file: no copy in a cache folder
            // Texts of a batch padded to the same length (the model takes a rectangular tensor), longer ones cut
            model.setTokenizerOptions(Map.of("padding", "true", "truncation", "true", "maxLength", "512"));
            model.afterPropertiesSet();
            log.info("Long-term memory on: multilingual-e5-small from {}", home);
            return model;
        } catch (Exception e) {
            log.warn("Long-term memory off: cannot load the model from {}: {}", home, e.getMessage());
            return null;
        }
    }

    @PreDestroy
    void close() throws Exception {
        if (model != null) {
            model.close();
        }
    }
}
