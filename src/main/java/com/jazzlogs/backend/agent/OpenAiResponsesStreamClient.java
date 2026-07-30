package com.jazzlogs.backend.agent;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ToolChoiceFunction;
import com.openai.models.responses.ToolChoiceOptions;

/**
 * Thin wrapper over the official OpenAI Java SDK's Responses API (pinned at
 * 4.45.0 in pom.xml) — resolves every SDK-specific mechanic AgentOrchestrator
 * shouldn't have to know about:
 * <ul>
 *   <li>reasoning.effort/context: {@code Reasoning.builder().effort(...).context(...)}.
 *       No {@code .summary(...)} call — that's the explicit decision not to
 *       request reasoning.summary (org verification + empty-summary risk at
 *       low effort). effort=LOW/context=ALL_TURNS are fixed, not configurable.</li>
 *   <li>forcing submit_final_answer: {@code ResponseCreateParams.Builder} has a
 *       dedicated {@code toolChoice(ToolChoiceFunction)} overload — equivalent
 *       to the raw API's {@code tool_choice: {"type": "function", "name": "..."}}.</li>
 *   <li>continuing with pending tool outputs: there's no separate "attach"
 *       call. Each loop iteration is a fresh {@code createStreaming(params)}
 *       call with {@code previousResponseId} set and {@code input} = that
 *       iteration's {@code ResponseInputItem.ofFunctionCallOutput(...)} list —
 *       the API reconstructs prior turns server-side from previousResponseId.</li>
 *   <li>reading the turn back out: rather than manually accumulating delta
 *       events, this waits for {@code ResponseCompletedEvent} and reads the
 *       turn's text/tool-calls off the final {@code Response.output()} —
 *       simpler and can't end up in an inconsistent partial state.</li>
 * </ul>
 */
@Service
public class OpenAiResponsesStreamClient {

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

    public StreamedTurn streamTurn(List<ResponseInputItem> input, String previousResponseId, boolean forceFinalAnswer) {
        ResponseCreateParams.Builder builder = ResponseCreateParams.builder()
            .model(model)
            .inputOfResponse(input)
            .reasoning(Reasoning.builder().effort(ReasoningEffort.LOW).context(Reasoning.Context.ALL_TURNS).build())
            // Explicit, not relying on the API's default: lets the model return
            // more than one function_call in the same turn (e.g. resolve_jazzlog_entity
            // + semantic_catalog_search together) instead of one per turn. AgentOrchestrator
            // dispatches whatever comes back concurrently, up to its own per-turn cap.
            .parallelToolCalls(true);
        tools.forEach(tool -> builder.addTool(tool.toFunctionTool()));

        if (previousResponseId != null) {
            builder.previousResponseId(previousResponseId);
        }
        if (forceFinalAnswer) {
            builder.toolChoice(ToolChoiceFunction.builder().name(SubmitFinalAnswerTool.NAME).build());
        } else {
            builder.toolChoice(ToolChoiceOptions.AUTO);
        }

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

        return toStreamedTurn(finalResponseHolder[0]);
    }

    private String describeFailure(Response response) {
        return response.error().map(Object::toString).orElse("response " + response.id() + " failed with no error detail");
    }

    private StreamedTurn toStreamedTurn(Response response) {
        StringBuilder text = new StringBuilder();
        List<ToolCallRequest> toolCalls = new ArrayList<>();

        for (ResponseOutputItem item : response.output()) {
            item.message().ifPresent(message -> message.content().forEach(content ->
                content.outputText().ifPresent(outputText -> text.append(outputText.text()))
            ));
            item.functionCall().ifPresent(call -> toolCalls.add(new ToolCallRequest(call.callId(), call.name(), call.arguments())));
        }

        return new StreamedTurn(response.id(), text.toString(), toolCalls);
    }

    // Lazy, not constructor-time — same reasoning as OpenAiEmbeddingService:
    // OpenAIOkHttpClient.build() validates eagerly, and this bean must not
    // block application startup just because OPENAI_API_KEY isn't set yet.
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
