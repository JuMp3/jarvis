package dev.jarvis.memory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * A sentence embedding model run with ONNX Runtime: tokens, the model, then the mean of the token vectors weighted by
 * the attention mask and normalized (what e5 expects).
 *
 * <p>Not Spring AI's TransformersEmbeddingModel: for the mean it uses DJL's PyTorch engine, which downloads about
 * 260 MB of PyTorch libraries at the first use (offline or behind a proxy the memory would not work), and fails on
 * the reduced runtime of the desktop app. The tokenizer is DJL's, whose native library is inside its jar.
 */
final class OnnxEmbeddings implements EmbeddingModel, AutoCloseable {

    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    private final int dimensions;

    OnnxEmbeddings(Path model, Path tokenizerFile, int dimensions) throws IOException, OrtException {
        // A batch padded to its longest text (the model takes a rectangular tensor), texts over 512 tokens cut
        this.tokenizer = HuggingFaceTokenizer.newInstance(tokenizerFile,
                Map.of("padding", "true", "truncation", "true", "maxLength", "512"));
        this.session = environment.createSession(model.toString(), new OrtSession.SessionOptions());
        this.dimensions = dimensions;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        Encoding[] encodings = tokenizer.batchEncode(texts);
        long[][] ids = new long[encodings.length][];
        long[][] mask = new long[encodings.length][];
        long[][] types = new long[encodings.length][];
        for (int i = 0; i < encodings.length; i++) {
            ids[i] = encodings[i].getIds();
            mask[i] = encodings[i].getAttentionMask();
            types[i] = encodings[i].getTypeIds();
        }
        Map<String, OnnxTensor> inputs = new HashMap<>();
        try {
            inputs.put("input_ids", OnnxTensor.createTensor(environment, ids));
            inputs.put("attention_mask", OnnxTensor.createTensor(environment, mask));
            if (session.getInputNames().contains("token_type_ids")) {
                inputs.put("token_type_ids", OnnxTensor.createTensor(environment, types));
            }
            try (OrtSession.Result result = session.run(inputs)) {
                float[][][] tokens = (float[][][]) result.get(0).getValue(); // last_hidden_state: text, token, value
                List<float[]> vectors = new ArrayList<>(tokens.length);
                for (int i = 0; i < tokens.length; i++) {
                    vectors.add(meanPooled(tokens[i], mask[i]));
                }
                return vectors;
            }
        } catch (OrtException e) {
            throw new IllegalStateException("Embedding failed: " + e.getMessage(), e);
        } finally {
            inputs.values().forEach(OnnxTensor::close);
        }
    }

    /** The mean of the vectors of the real tokens (mask 1, not the padding), with length 1. */
    static float[] meanPooled(float[][] tokens, long[] mask) {
        float[] mean = new float[tokens[0].length];
        int count = 0;
        for (int t = 0; t < tokens.length; t++) {
            if (mask[t] == 0) {
                continue;
            }
            count++;
            for (int d = 0; d < mean.length; d++) {
                mean[d] += tokens[t][d];
            }
        }
        double norm = 0;
        for (int d = 0; d < mean.length; d++) {
            mean[d] /= Math.max(1, count);
            norm += mean[d] * mean[d];
        }
        float scale = norm == 0 ? 0 : (float) (1 / Math.sqrt(norm));
        for (int d = 0; d < mean.length; d++) {
            mean[d] *= scale;
        }
        return mean;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<float[]> vectors = embed(request.getInstructions());
        List<Embedding> embeddings = new ArrayList<>(vectors.size());
        for (int i = 0; i < vectors.size(); i++) {
            embeddings.add(new Embedding(vectors.get(i), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return embed(List.of(document.getText())).getFirst();
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public void close() throws OrtException {
        session.close();
        tokenizer.close();
    }
}
