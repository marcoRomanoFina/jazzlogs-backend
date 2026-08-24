package com.jazzlogs.backend.agent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import com.openai.models.responses.ResponseInputItem;

import com.jazzlogs.backend.agent.tools.JazzTool;
import com.jazzlogs.backend.chat.chat.Chat;
import com.jazzlogs.backend.chat.chatexchange.CatalogReference;
import com.jazzlogs.backend.chat.chatexchange.ChatExchangeService;
import com.jazzlogs.backend.chat.chatexchange.dto.ChatExchangeDto;

/**
 * The ReAct loop itself: given a chat and the user's new message, repeatedly
 * calls the model, dispatches whatever tool calls it requests, and reports
 * every step through {@link EventSink} until the model closes with a final
 * answer.
 */
@Service
public class JazzlogsAgent implements Agent {

    @Value("${agent.max-iterations:6}")
    private int maxIterations;

    /**
     * Small, deliberate cap — an uncapped turn could fan out into unbounded
     * concurrent tool calls. Calls beyond it aren't dropped; the Responses
     * API requires a {@code function_call_output} for every call it made, so
     * each gets a synthetic failed result instead — see {@link #executeToolCalls}.
     */
    @Value("${agent.max-tool-calls-per-turn:8}")
    private int maxToolCallsPerTurn;

    /** Fallback label for a hallucinated/unregistered tool name — see {@link #labelFor}. */
    private static final String UNKNOWN_TOOL_LABEL = "Trabajando en tu pedido";
    /** RunStarted's label — the agent's own occasion, no JazzTool to ask. */
    private static final String RUN_STARTED_LABEL = "Pensando tu pedido";

    private final ChatContextBuilder contextBuilder;
    private final OpenAiResponsesStreamClient streamClient;
    private final ChatExchangeService chatExchangeService;
    private final JsonMapper objectMapper;
    /** Runs tool dispatch — blocking I/O, never CPU; same virtual-thread executor {@code AgentOrchestrator} uses for this whole class. */
    private final Executor agentExecutor;
    /** Keyed by name for {@link #dispatch} — built once from the same {@code List<JazzTool>} Spring supplies everywhere else. */
    private final Map<String, JazzTool> toolsByName;

    public JazzlogsAgent(
        ChatContextBuilder contextBuilder,
        OpenAiResponsesStreamClient streamClient,
        ChatExchangeService chatExchangeService,
        JsonMapper objectMapper,
        Executor agentExecutor,
        List<JazzTool> tools
    ) {
        this.contextBuilder = contextBuilder;
        this.streamClient = streamClient;
        this.chatExchangeService = chatExchangeService;
        this.objectMapper = objectMapper;
        this.agentExecutor = agentExecutor;
        this.toolsByName = tools.stream().collect(Collectors.toMap(JazzTool::name, tool -> tool));
    }

    /**
     * Runs the ReAct loop to completion: iterates until the model closes
     * with a final answer, forcing that close on the last allowed iteration.
     *
     * @throws IllegalStateException if the model never closes within {@link #maxIterations}
     */
    @Override
    public void run(EventSink sink, Chat chat, String userMessage, String timezone) throws IOException {
        sink.emit(new AgentEvent.RunStarted(RUN_STARTED_LABEL));

        List<ResponseInputItem> nextInput = contextBuilder.buildInput(chat, userMessage, timezone);
        String previousResponseId = null;

        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            boolean forceFinal = iteration == maxIterations;
            Step turn = streamClient.streamTurn(nextInput, previousResponseId, forceFinal);
            previousResponseId = turn.responseId();

            // Empty toolCalls means the model answered with text instead of
            // calling anything — under OpenAiResponsesStreamClient's
            // structured text.format, that text IS the final answer's JSON
            // (see AgentFinalAnswer), never freeform commentary. There's no
            // third case to handle here: a turn either requests tool calls,
            // or it closes.
            if (turn.toolCalls().isEmpty()) {
                finalizeExchange(sink, chat, userMessage, turn);
                return;
            }

            nextInput = executeToolCalls(sink, turn, chat.getUserId());
        }

        throw new IllegalStateException("Agent did not converge to a final answer within max iterations");
    }

    /**
     * Dispatches accepted calls concurrently (independent lookups, no
     * ordering between them); calls over {@link #maxToolCallsPerTurn} get a
     * synthetic failed result instead of running. Every call still gets a
     * ToolCallStarted/Finished pair and a {@code function_call_output}.
     *
     * @return next iteration's input — just these tool outputs, {@code previousResponseId} carries the rest
     */
    private List<ResponseInputItem> executeToolCalls(EventSink sink, Step turn, UUID userId) throws IOException {
        List<ToolCallRequest> calls = turn.toolCalls();
        List<ToolCallRequest> accepted = calls.size() > maxToolCallsPerTurn ? calls.subList(0, maxToolCallsPerTurn) : calls;
        List<ToolCallRequest> rejected = calls.size() > maxToolCallsPerTurn ? calls.subList(maxToolCallsPerTurn, calls.size()) : List.of();

        for (ToolCallRequest call : calls) {
            sink.emit(new AgentEvent.ToolCallStarted(labelFor(call)));
        }

        List<CompletableFuture<ToolExecutionResult>> futures = accepted.stream()
            .map(call -> CompletableFuture.supplyAsync(() -> dispatch(call, userId), agentExecutor))
            .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        List<ResponseInputItem> outputs = new ArrayList<>();
        for (int i = 0; i < accepted.size(); i++) {
            ToolCallRequest call = accepted.get(i);
            ToolExecutionResult result = futures.get(i).join();
            outputs.add(toFunctionCallOutput(call, result));
            sink.emit(new AgentEvent.ToolCallFinished(labelFor(call), result.success()));
        }
        for (ToolCallRequest call : rejected) {
            ToolExecutionResult result = new ToolExecutionResult(
                "{\"error\":\"Too many tool calls in a single turn (limit: " + maxToolCallsPerTurn + ")\"}", false
            );
            outputs.add(toFunctionCallOutput(call, result));
            sink.emit(new AgentEvent.ToolCallFinished(labelFor(call), false));
        }
        return outputs;
    }

    /** The tool's own display label, or {@link #UNKNOWN_TOOL_LABEL} if unregistered. */
    private String labelFor(ToolCallRequest call) {
        JazzTool tool = toolsByName.get(call.name());
        return tool == null ? UNKNOWN_TOOL_LABEL : tool.displayLabel();
    }

    /**
     * Runs one tool call. Never throws: an unregistered tool name or invalid
     * arguments (a JazzTool's own parsing rejects them with {@link
     * IllegalArgumentException}) become a failed result instead — the model
     * sees why in the function_call_output and can retry with corrected
     * arguments next turn, instead of the whole exchange dying.
     */
    private ToolExecutionResult dispatch(ToolCallRequest call, UUID userId) {
        JazzTool tool = toolsByName.get(call.name());
        if (tool == null) {
            return new ToolExecutionResult("{\"error\":\"Unknown tool: " + call.name() + "\"}", false);
        }
        try {
            return tool.execute(call, userId);
        } catch (IllegalArgumentException e) {
            return new ToolExecutionResult(objectMapper.writeValueAsString(Map.of("error", "Invalid arguments: " + e.getMessage())), false);
        }
    }

    /** Wraps a tool's result into the shape the next Responses API call expects. */
    private ResponseInputItem toFunctionCallOutput(ToolCallRequest call, ToolExecutionResult result) {
        return ResponseInputItem.ofFunctionCallOutput(
            ResponseInputItem.FunctionCallOutput.builder()
                .callId(call.callId())
                .output(result.payload())
                .build()
        );
    }

    /** Parses the model's final answer, persists the exchange, and emits RunFinished. */
    private void finalizeExchange(EventSink sink, Chat chat, String userMessage, Step turn) throws IOException {
        AgentFinalAnswer metadata = parseFinalAnswer(turn.assistantText());
        // null (not empty) tells ChatExchangeService "this is a DIRECT_RESPONSE,
        // don't touch the catalog at all".
        List<CatalogReference> recommendedItems = metadata.resultType() == AgentFinalAnswer.ResultType.DIRECT_RESPONSE
            ? null
            : metadata.recommendedItems();

        ChatExchangeDto saved = chatExchangeService.persist(
            chat, userMessage, metadata.answerText(), recommendedItems, metadata.suggestedChatTitle(), metadata.updatedSessionSummary()
        );

        // The only way a caller of POST /chats (create-new) learns the
        // chatId it just got — there's no other field in this SSE stream
        // that carries it, and the response itself is just an event stream,
        // not a JSON body with a Location header or similar.
        sink.emit(new AgentEvent.RunFinished(
            saved.chatId(),
            metadata.answerText(),
            metadata.resultType(),
            saved.winners() == null ? List.of() : saved.winners(),
            metadata.suggestedChatTitle()
        ));
    }

    /** Parses the turn's closing text as {@link AgentFinalAnswer} JSON. */
    private AgentFinalAnswer parseFinalAnswer(String responseText) {
        try {
            return objectMapper.readValue(responseText, AgentFinalAnswer.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("Final answer text was not valid JSON: " + e.getMessage(), e);
        }
    }
}
