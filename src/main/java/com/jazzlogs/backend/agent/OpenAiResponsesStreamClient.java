package com.jazzlogs.backend.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFormatTextConfig;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseTextConfig;
import com.openai.models.responses.ToolChoiceOptions;

import com.jazzlogs.backend.agent.tools.JazzTool;

import lombok.extern.slf4j.Slf4j;

/**
 * Thin wrapper over the OpenAI Java SDK's Responses API (pinned at 4.45.0 in
 * pom.xml) — one {@link #streamTurn} call is one turn. Builds the request
 * (reasoning config, tools, the final-answer schema, {@code previousResponseId}
 * chaining so the API reconstructs prior turns server-side), then waits for
 * the stream's completed event and reads the turn's text/tool calls off the
 * final {@link Response}.
 */
@Slf4j
@Service
public class OpenAiResponsesStreamClient {

    /**
     * JSON Schema for {@link AgentFinalAnswer}
     */
    private static final Map<String, Object> FINAL_ANSWER_SCHEMA = Map.of(
        "type", "object",
        "properties", Map.of(
            "resultType", Map.of("type", "string", "enum", List.of("DIRECT_RESPONSE", "CATALOG_RESPONSE")),
            "answerText", Map.of("type", "string"),
            "recommendedItems", Map.of(
                "type", "array",
                "items", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "type", Map.of("type", "string", "enum", List.of("ALBUM", "TRACK", "ARTIST")),
                        "id", Map.of("type", "string")
                    ),
                    "required", List.of("type", "id"),
                    "additionalProperties", false
                )
            ),
            "suggestedChatTitle", Map.of("type", List.of("string", "null")),
            "updatedSessionSummary", Map.of("type", List.of("string", "null"))
        ),
        "required", List.of("resultType", "answerText", "recommendedItems", "suggestedChatTitle", "updatedSessionSummary"),
        "additionalProperties", false
    );

    /** Built once at class load — {@link #FINAL_ANSWER_SCHEMA} never varies per call. */
    private static final ResponseTextConfig FINAL_ANSWER_TEXT_CONFIG = buildFinalAnswerTextConfig();

    private final String apiKey;
    private final String model;
    private final List<JazzTool> tools;

    private volatile OpenAIClient client;

    public OpenAiResponsesStreamClient(
        @Value("${openai.api-key:}") String apiKey,
        @Value("${agent.model.name:gpt-5.6-luna}") String model,
        List<JazzTool> tools
    ) {
        this.apiKey = apiKey;
        this.model = model;
        this.tools = tools;
    }

    /**
     * Runs one turn against the Responses API: builds the request, streams
     * it, blocks until the completed event arrives, and reduces the result
     * into a {@link Step}.
     *
     * @param input             this turn's input items — the initial context on
     *                          the first call, or the prior turn's tool outputs
     * @param previousResponseId the prior turn's response id to chain from, or
     *                            {@code null} on the first call in an exchange
     * @param forceFinalAnswer  true on the last allowed iteration — forces
     *                          {@code toolChoice(NONE)} so the model must close
     *                          with text instead of requesting another tool call
     * @return the turn's response id, assistant text, and any tool calls requested
     * @throws IllegalStateException if the stream reports a failure, or ends
     *                                without ever completing
     */
    public Step streamTurn(List<ResponseInputItem> input, String previousResponseId, boolean forceFinalAnswer) {
        ResponseCreateParams.Builder builder = ResponseCreateParams.builder()
            .model(model)
            .inputOfResponse(input)
            .reasoning(Reasoning.builder().effort(ReasoningEffort.LOW).context(Reasoning.Context.ALL_TURNS).build())
            // Lets the model return more than one function_call in the same
            // turn — JazzlogsAgent dispatches them concurrently, up to its cap.
            .parallelToolCalls(true)
            .text(FINAL_ANSWER_TEXT_CONFIG);
        tools.forEach(tool -> builder.addTool(tool.toFunctionTool()));

        if (previousResponseId != null) {
            builder.previousResponseId(previousResponseId);
        }
        // NONE forces a text-only close (schema-constrained by FINAL_ANSWER_TEXT_CONFIG);
        // AUTO lets the model still choose to call a tool instead of answering.
        builder.toolChoice(forceFinalAnswer ? ToolChoiceOptions.NONE : ToolChoiceOptions.AUTO);

        Response[] finalResponseHolder = new Response[1];
        List<String> failures = new ArrayList<>();

        try (StreamResponse<ResponseStreamEvent> stream = client().responses().createStreaming(builder.build())) {
            stream.stream().forEach(event -> {
                event.completed().ifPresent(completed -> finalResponseHolder[0] = completed.response());
                event.failed().ifPresent(failed -> failures.add(describeFailure(failed.response())));
                event.error().ifPresent(err -> failures.add(err.message()));
            });
        }

        if (!failures.isEmpty()) {
            throw new IllegalStateException("Responses API stream failed: " + String.join("; ", failures));
        }
        if (finalResponseHolder[0] == null) {
            throw new IllegalStateException("Responses API stream ended without a completed event");
        }

        logUsage(finalResponseHolder[0]);
        return toStep(finalResponseHolder[0]);
    }

    /**
     * Adapts {@link #FINAL_ANSWER_SCHEMA}'s plain {@code Map} into the SDK's
     * builder chain: each entry becomes a {@link JsonValue} on a
     * {@code Schema.Builder}, that schema gets named and marked strict, and
     * the result is wrapped into the {@link ResponseTextConfig} the request
     * builder's {@code .text(...)} actually expects. Pure plumbing, no logic.
     */
    private static ResponseTextConfig buildFinalAnswerTextConfig() {
        ResponseFormatTextJsonSchemaConfig.Schema.Builder schemaBuilder = ResponseFormatTextJsonSchemaConfig.Schema.builder();
        FINAL_ANSWER_SCHEMA.forEach((key, value) -> schemaBuilder.putAdditionalProperty(key, JsonValue.from(value)));

        ResponseFormatTextJsonSchemaConfig jsonSchemaConfig = ResponseFormatTextJsonSchemaConfig.builder()
            .name("agent_final_answer")
            .schema(schemaBuilder.build())
            .strict(true)
            .build();

        return ResponseTextConfig.builder().format(ResponseFormatTextConfig.ofJsonSchema(jsonSchemaConfig)).build();
    }

    private String describeFailure(Response response) {
        return response.error().map(Object::toString).orElse("response " + response.id() + " failed with no error detail");
    }

    /** Token accounting, log-only. {@code usage} can legitimately be absent. */
    private void logUsage(Response response) {
        response.usage().ifPresentOrElse(
            usage -> log.info(
                "[response={}] tokens: input={} (cached={}), output={} (reasoning={}), total={}",
                response.id(),
                usage.inputTokens(), usage.inputTokensDetails().cachedTokens(),
                usage.outputTokens(), usage.outputTokensDetails().reasoningTokens(),
                usage.totalTokens()
            ),
            () -> log.info("[response={}] no token usage reported", response.id())
        );
    }

    /** Reduces a completed {@link Response}'s output items into assistant text plus any tool calls. */
    private Step toStep(Response response) {
        StringBuilder text = new StringBuilder();
        List<ToolCallRequest> toolCalls = new ArrayList<>();

        for (ResponseOutputItem item : response.output()) {
            item.message().ifPresent(message -> message.content().forEach(content ->
                content.outputText().ifPresent(outputText -> text.append(outputText.text()))
            ));
            item.functionCall().ifPresent(call -> toolCalls.add(new ToolCallRequest(call.callId(), call.name(), call.arguments())));
        }

        return new Step(response.id(), text.toString(), toolCalls);
    }

    /**
     * Lazy, not constructor-time — same reasoning as {@code OpenAiEmbeddingService}:
     * {@code OpenAIOkHttpClient.build()} validates eagerly, and this bean must
     * not block application startup just because {@code OPENAI_API_KEY} isn't set yet.
     */
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
