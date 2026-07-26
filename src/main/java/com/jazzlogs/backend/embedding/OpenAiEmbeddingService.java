package com.jazzlogs.backend.embedding;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.Embedding;
import com.openai.models.embeddings.EmbeddingCreateParams;
import com.openai.models.embeddings.EmbeddingModel;

/**
 * Real embeddings via OpenAI's official Java SDK. No error handling here on
 * purpose: unlike the Neo4j sync (where a failure must never block Postgres),
 * an embedding failure MUST fail the whole editorial upsert — a block without
 * a real vector is worse than no block at all. Every OpenAIException from the
 * SDK propagates untouched; GlobalExceptionHandler turns it into a controlled
 * 502 at the HTTP boundary.
 *
 * The client is built lazily (not in the constructor): OpenAIOkHttpClient.build()
 * validates eagerly and throws if no credential is set, and this service must not
 * block application startup just because OPENAI_API_KEY isn't configured yet.
 */
@Service
public class OpenAiEmbeddingService implements EmbeddingService {

    private final String apiKey;
    private final String model;
    private final long dimensions;

    private volatile OpenAIClient client;

    public OpenAiEmbeddingService(
        @Value("${openai.api-key:}") String apiKey,
        @Value("${openai.embedding-model:text-embedding-3-small}") String model,
        @Value("${openai.embedding-dimensions:1536}") long dimensions
    ) {
        this.apiKey = apiKey;
        this.model = model;
        this.dimensions = dimensions;
    }

    @Override
    public float[] embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        for (String text : texts) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("Cannot generate an embedding for null/blank text");
            }
        }

        EmbeddingCreateParams params = EmbeddingCreateParams.builder()
            .model(EmbeddingModel.of(model))
            .dimensions(dimensions)
            .inputOfArrayOfStrings(texts)
            .build();

        CreateEmbeddingResponse response = client().embeddings().create(params);

        List<Embedding> data = new ArrayList<>(response.data());
        data.sort(Comparator.comparingLong(Embedding::index));

        List<float[]> vectors = new ArrayList<>(data.size());
        for (Embedding embedding : data) {
            List<Float> values = embedding.embedding();
            float[] vector = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                vector[i] = values.get(i);
            }
            vectors.add(vector);
        }
        return vectors;
    }

    private OpenAIClient client() {
        OpenAIClient current = client;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (client == null) {
                if (apiKey == null || apiKey.isBlank()) {
                    throw new IllegalStateException("OPENAI_API_KEY is not configured");
                }
                client = OpenAIOkHttpClient.builder().apiKey(apiKey).build();
            }
            return client;
        }
    }
}
