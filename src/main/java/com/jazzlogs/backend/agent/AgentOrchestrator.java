package com.jazzlogs.backend.agent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.responses.ResponseInputItem;

import com.jazzlogs.backend.agent.tools.EditorialContentTool;
import com.jazzlogs.backend.agent.tools.GraphFilterTool;
import com.jazzlogs.backend.agent.tools.JazzTool;
import com.jazzlogs.backend.agent.tools.ResolveJazzlogsEntityTool;
import com.jazzlogs.backend.agent.tools.SemanticSearchTool;
import com.jazzlogs.backend.agent.tools.SubmitFinalAnswerTool;
import com.jazzlogs.backend.chat.CatalogRef;
import com.jazzlogs.backend.chat.Chat;
import com.jazzlogs.backend.chat.ChatExchangeService;
import com.jazzlogs.backend.chat.dto.ChatExchangeDto;

import lombok.extern.slf4j.Slf4j;

// Drives one exchange's tool-calling loop against the Responses API and
// streams progress to the frontend over SSE: iteration_started,
// tool_call_started/finished, answer_delta, answer_metadata, answer_done,
// error (see the sink.emit(...) calls below for exactly what each carries).
// Chaining via previous_response_id is scoped to THIS exchange only: each new exchange
// starts fresh from ChatContextBuilder.buildInput, never carrying a prior
// exchange's response id forward.
//
// The loop/finalize/failure methods take an EventSink instead of touching
// SseEmitter.send(...) directly — purely so tests can assert on emitted
// (eventName, data) order without an SseEmitter attached to a real HTTP
// response. runExchange is the only place a real SseEmitter is involved.
//
// No in-memory bookkeeping of "what did a tool actually return" during the
// loop (there used to be a candidatePool for that): resolve_jazzlog_entity
// and any other catalog tool only ever return ids that already exist in
// Postgres, so it's simpler to just check submit_final_answer's ids against
// the real catalog once, in ChatExchangeService — this class only ever
// produces the raw (chat, text, refs) inputs for it, never touches the
// catalog or chat_exchanges directly itself.
@Slf4j
@Service
public class AgentOrchestrator {

    @Value("${agent.max-iterations:6}")
    private int maxIterations;

    // Obvious, small cap: nothing about a chat turn needs more than a
    // handful of tool calls at once, and an uncapped model response could
    // otherwise fan out into an unbounded number of concurrent DB/graph
    // lookups. Calls beyond the cap aren't silently dropped — the Responses
    // API requires a function_call_output for every function_call it made,
    // so each gets a synthetic failed result instead (see executeToolCalls).
    @Value("${agent.max-tool-calls-per-turn:8}")
    private int maxToolCallsPerTurn;

    // One entry per JazzTool that actually exists today — add the new
    // label here when a new tool is added, not before.
    private static final Map<String, String> TOOL_DISPLAY_LABELS = Map.of(
        ResolveJazzlogsEntityTool.NAME, "Identificando el álbum/artista",
        EditorialContentTool.NAME, "Leyendo la editorial",
        GraphFilterTool.NAME, "Filtrando por estilo y clima",
        SemanticSearchTool.NAME, "Buscando en las editoriales",
        SubmitFinalAnswerTool.NAME, "Armando la recomendación"
    );
    private static final String DEFAULT_TOOL_LABEL = "Trabajando en tu pedido";

    // Not Spring-managed: this project doesn't autoconfigure a default
    // ObjectMapper bean (spring-boot-starter-webmvc alone doesn't pull that
    // in here), and a plain ObjectMapper is stateless/thread-safe enough to
    // just own directly rather than adding a project-wide Jackson autoconfig
    // dependency for this one use.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // One virtual thread per task, not the shared ForkJoinPool.commonPool()
    // that CompletableFuture.runAsync/supplyAsync fall back to without an
    // explicit executor: everything scheduled here is blocking I/O (the
    // OpenAI HTTP call, eventually per-tool DB/Neo4j/Spotify calls), never
    // CPU-bound work — exactly what virtual threads are for. The common pool
    // is sized for CPU-bound fork/join work and shared process-wide; blocking
    // it here could starve anything else in the JVM relying on it. No
    // lifecycle to manage (same reasoning as OBJECT_MAPPER above), safe to
    // share as a process-lifetime singleton.
    private static final Executor VIRTUAL_THREADS = Executors.newVirtualThreadPerTaskExecutor();

    private final ChatContextBuilder contextBuilder;
    private final OpenAiResponsesStreamClient streamClient;
    private final ChatExchangeService chatExchangeService;
    // Keyed by name for dispatch in executeToolCalls — see JazzTool. Built once
    // here rather than injecting a Map directly: Spring supplies every JazzTool
    // @Component as a plain List<JazzTool>, same list OpenAiResponsesStreamClient
    // uses to build the request's tool array. submit_final_answer is in here too,
    // but its entry is never looked up: runLoop intercepts that call by name
    // before executeToolCalls/dispatch ever runs.
    private final Map<String, JazzTool> toolsByName;

    public AgentOrchestrator(
        ChatContextBuilder contextBuilder,
        OpenAiResponsesStreamClient streamClient,
        ChatExchangeService chatExchangeService,
        List<JazzTool> tools
    ) {
        this.contextBuilder = contextBuilder;
        this.streamClient = streamClient;
        this.chatExchangeService = chatExchangeService;
        this.toolsByName = tools.stream().collect(Collectors.toMap(JazzTool::name, tool -> tool));
    }

    @FunctionalInterface
    interface EventSink {
        void emit(String eventName, Object data) throws IOException;
    }

    public SseEmitter runExchange(Chat chat, String userMessage, String timezone) {
        SseEmitter emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(3));
        EventSink sink = (eventName, data) -> emitter.send(SseEmitter.event().name(eventName).data(data));

        CompletableFuture.runAsync(() -> {
            try {
                runLoop(sink, chat, userMessage, timezone);
                emitter.complete();
            } catch (Exception e) {
                handleFailure(sink, emitter, chat, e);
            }
        }, VIRTUAL_THREADS);

        return emitter;
    }

    void runLoop(EventSink sink, Chat chat, String userMessage, String timezone) throws IOException {
        log.info("[chat={}] exchange started, userMessage=\"{}\"", chat.getId(), userMessage);
        List<ResponseInputItem> nextInput = contextBuilder.buildInput(chat, userMessage, timezone);
        String previousResponseId = null;
        // Carried across iterations as a fallback: the prompt requires the
        // model to give its conversational reply in the SAME turn as
        // submit_final_answer, but that's a prompt instruction, not
        // something the API enforces — if the model splits them (answers in
        // an earlier turn, then closes with blank text later), that earlier
        // text would otherwise be silently discarded. See finalizeExchange.
        String lastNonBlankAssistantText = null;

        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            sink.emit("iteration_started", Map.of("iteration", iteration));

            boolean forceFinal = iteration == maxIterations;
            log.info("[chat={}] iteration {} — calling Responses API (forceFinal={})", chat.getId(), iteration, forceFinal);
            StreamedTurn turn = streamClient.streamTurn(nextInput, previousResponseId, forceFinal);
            previousResponseId = turn.responseId();
            if (turn.assistantText() != null && !turn.assistantText().isBlank()) {
                lastNonBlankAssistantText = turn.assistantText();
            }

            ToolCallRequest submitCall = turn.findToolCall(SubmitFinalAnswerTool.NAME);
            if (submitCall != null) {
                log.info("[chat={}] iteration {} — model closed with submit_final_answer", chat.getId(), iteration);
                finalizeExchange(sink, chat, userMessage, turn, submitCall, iteration, lastNonBlankAssistantText);
                return;
            }

            List<ToolCallRequest> requested = turn.otherToolCalls();
            log.info(
                "[chat={}] iteration {} — model requested {} tool call(s): {}",
                chat.getId(), iteration, requested.size(), requested.stream().map(ToolCallRequest::name).toList()
            );

            nextInput = executeToolCalls(sink, turn, iteration, chat.getUserId());
        }

        log.warn("[chat={}] did not converge to submit_final_answer within {} iterations", chat.getId(), maxIterations);
        throw new IllegalStateException("Agent did not converge to submit_final_answer within max iterations");
    }

    // Next iteration's input is ONLY these tool outputs (plus previousResponseId
    // carrying the rest of the context server-side) — not the whole conversation
    // again, see OpenAiResponsesStreamClient's class doc.
    //
    // Calls within the cap are dispatched concurrently (independent lookups,
    // no ordering dependency between them) — every call still gets a
    // tool_call_started/finished pair and a function_call_output, whether it
    // actually ran or was rejected for being over maxToolCallsPerTurn.
    private List<ResponseInputItem> executeToolCalls(EventSink sink, StreamedTurn turn, int iteration, UUID userId) throws IOException {
        List<ToolCallRequest> calls = turn.otherToolCalls();
        List<ToolCallRequest> accepted = calls.size() > maxToolCallsPerTurn ? calls.subList(0, maxToolCallsPerTurn) : calls;
        List<ToolCallRequest> rejected = calls.size() > maxToolCallsPerTurn ? calls.subList(maxToolCallsPerTurn, calls.size()) : List.of();

        for (ToolCallRequest call : calls) {
            sink.emit("tool_call_started", Map.of("label", labelFor(call), "iteration", iteration));
        }

        List<CompletableFuture<ToolExecutionResult>> futures = accepted.stream()
            .map(call -> CompletableFuture.supplyAsync(() -> dispatch(call, userId), VIRTUAL_THREADS))
            .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        List<ResponseInputItem> outputs = new ArrayList<>();
        for (int i = 0; i < accepted.size(); i++) {
            ToolCallRequest call = accepted.get(i);
            ToolExecutionResult result = futures.get(i).join();
            outputs.add(toFunctionCallOutput(call, result));
            sink.emit("tool_call_finished", Map.of("label", labelFor(call), "success", result.success()));
        }
        for (ToolCallRequest call : rejected) {
            log.warn("{} (callId={}) rejected: over the {}-call-per-turn cap", call.name(), call.callId(), maxToolCallsPerTurn);
            ToolExecutionResult result = new ToolExecutionResult(
                "{\"error\":\"Too many tool calls in a single turn (limit: " + maxToolCallsPerTurn + ")\"}", false
            );
            outputs.add(toFunctionCallOutput(call, result));
            sink.emit("tool_call_finished", Map.of("label", labelFor(call), "success", false));
        }
        return outputs;
    }

    private String labelFor(ToolCallRequest call) {
        return TOOL_DISPLAY_LABELS.getOrDefault(call.name(), DEFAULT_TOOL_LABEL);
    }

    private ToolExecutionResult dispatch(ToolCallRequest call, UUID userId) {
        JazzTool tool = toolsByName.get(call.name());
        if (tool == null) {
            log.warn("No JazzTool registered for {}", call.name());
            return new ToolExecutionResult("{\"error\":\"Unknown tool: " + call.name() + "\"}", false);
        }
        log.info("--> {} (callId={})\nargs: {}", call.name(), call.callId(), prettyJson(call.argumentsJson()));
        ToolExecutionResult result = tool.execute(call, userId);
        log.info(
            "<-- {} (callId={}) success={}\nresult: {}",
            call.name(), call.callId(), result.success(), prettyJson(result.payload())
        );
        return result;
    }

    // Logging only — reformats a tool's raw single-line JSON (args or
    // payload) into indented, human-readable JSON so a console reader can
    // actually see the structure instead of one long unbroken line. Falls
    // back to the raw string if it's ever not valid JSON, since this must
    // never be the reason a tool call fails.
    private static String prettyJson(String rawJson) {
        try {
            Object parsed = OBJECT_MAPPER.readValue(rawJson, Object.class);
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(parsed);
        } catch (JsonProcessingException e) {
            return rawJson;
        }
    }

    // First non-null/non-blank candidate, in order; "" if none qualify —
    // never returns null, since assistantText ends up persisted to a
    // NOT NULL column and shown as-is in the answer_delta SSE payload.
    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "";
    }

    private ResponseInputItem toFunctionCallOutput(ToolCallRequest call, ToolExecutionResult result) {
        return ResponseInputItem.ofFunctionCallOutput(
            ResponseInputItem.FunctionCallOutput.builder()
                .callId(call.callId())
                .output(result.payload())
                .build()
        );
    }

    private void finalizeExchange(
        EventSink sink, Chat chat, String userMessage, StreamedTurn turn, ToolCallRequest submitCall, int iteration,
        String fallbackAssistantText
    ) throws IOException {
        String label = TOOL_DISPLAY_LABELS.get(SubmitFinalAnswerTool.NAME);
        sink.emit("tool_call_started", Map.of("label", label, "iteration", iteration));

        AgentFinalAnswer metadata = parseFinalAnswer(submitCall.argumentsJson());
        // null (not empty) tells ChatExchangeService "this is a DIRECT_RESPONSE,
        // don't touch the catalog at all".
        List<CatalogRef> recommendedItems = metadata.resultType() == AgentFinalAnswer.ResultType.DIRECT_RESPONSE
            ? null
            : metadata.recommendedItems();

        // Fallback chain, in order of trust: submit_final_answer's own
        // answerText argument (see AgentFinalAnswer's doc — the only place
        // guaranteed to carry the real answer), then this turn's separate
        // message text (a model can still emit both), then the last
        // non-blank text from an earlier iteration (see runLoop's comment
        // on lastNonBlankAssistantText), then "" as the absolute last
        // resort rather than ever persisting/showing null.
        String assistantText = firstNonBlank(metadata.answerText(), turn.assistantText(), fallbackAssistantText);

        ChatExchangeDto saved = chatExchangeService.persist(
            chat, userMessage, assistantText, recommendedItems, metadata.suggestedChatTitle(), metadata.updatedSessionSummary()
        );

        log.info(
            "[chat={}] exchange finished: resultType={}, recommendedItems={}, suggestedChatTitle={}",
            chat.getId(), metadata.resultType(), recommendedItems == null ? 0 : recommendedItems.size(), metadata.suggestedChatTitle()
        );

        sink.emit("tool_call_finished", Map.of("label", label, "success", true));
        sink.emit("answer_delta", Map.of("text", assistantText));
        sink.emit("answer_metadata", toMetadataPayload(metadata, saved));
        sink.emit("answer_done", Map.of());
    }

    private AgentFinalAnswer parseFinalAnswer(String argumentsJson) {
        try {
            return OBJECT_MAPPER.readValue(argumentsJson, AgentFinalAnswer.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("submit_final_answer arguments were not valid JSON: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> toMetadataPayload(AgentFinalAnswer metadata, ChatExchangeDto saved) {
        Map<String, Object> payload = new LinkedHashMap<>();
        // The only way a caller of POST /chats (create-new) learns the
        // chatId it just got — there's no other field in this SSE stream
        // that carries it, and the response itself is just an event stream,
        // not a JSON body with a Location header or similar.
        payload.put("chatId", saved.chatId());
        payload.put("resultType", metadata.resultType());
        payload.put("recommendedItems", saved.winners() == null ? List.of() : saved.winners());
        payload.put("suggestedChatTitle", metadata.suggestedChatTitle());
        return payload;
    }

    // No chat_exchange is persisted here, ever — same all-or-nothing contract
    // as final_response being NOT NULL: if the agent fails, the whole exchange
    // fails, no partial record. emitter.complete()/completeWithError() close
    // the SSE stream — that's still the raw SseEmitter, not the EventSink.
    void handleFailure(EventSink sink, SseEmitter emitter, Chat chat, Exception e) {
        log.error("Agent exchange failed for chat {}: {}", chat.getId(), e.getMessage(), e);
        try {
            sink.emit("error", Map.of("message", "No pude terminar de pensar la respuesta, probá de nuevo."));
            emitter.complete();
        } catch (IOException io) {
            emitter.completeWithError(io);
        }
    }
}
