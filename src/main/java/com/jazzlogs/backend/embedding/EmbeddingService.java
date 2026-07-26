package com.jazzlogs.backend.embedding;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates the vector embedding(s) for editorial text. See OpenAiEmbeddingService
 * for the real implementation — provider is OpenAI's text-embedding-3-small.
 */
public interface EmbeddingService {

    float[] embed(String text);

    /**
     * Default falls back to one call per text; implementations that support a real
     * batch API (like OpenAiEmbeddingService) should override this for efficiency.
     */
    default List<float[]> embedBatch(List<String> texts) {
        List<float[]> results = new ArrayList<>(texts.size());
        for (String text : texts) {
            results.add(embed(text));
        }
        return results;
    }
}
