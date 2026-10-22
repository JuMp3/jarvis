package dev.jarvis.memory;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * The e5 models were trained with a prefix on every text: "query: " for what is searched, "passage: " for what is
 * stored. Without them the similarity is noticeably worse. The vector store embeds documents to store them and a
 * string to search, so the two calls get the two prefixes.
 */
final class E5Embeddings implements EmbeddingModel {

    static final String QUERY = "query: ";
    static final String PASSAGE = "passage: ";

    private final EmbeddingModel model;

    E5Embeddings(EmbeddingModel model) {
        this.model = model;
    }

    @Override
    public float[] embed(String query) {
        return model.embed(QUERY + query);
    }

    @Override
    public float[] embed(Document document) {
        return model.embed(PASSAGE + document.getText());
    }

    @Override
    public List<float[]> embed(List<Document> documents, EmbeddingOptions options, BatchingStrategy batchingStrategy) {
        return model.embed(documents.stream().map(document -> PASSAGE + document.getText()).toList());
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        return model.call(request);
    }
}
