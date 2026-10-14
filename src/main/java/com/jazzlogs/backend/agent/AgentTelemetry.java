package com.jazzlogs.backend.agent;

import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import com.jazzlogs.backend.chat.chat.Chat;
import com.jazzlogs.backend.editorial.dto.TrackEditorialCatalogueDto;

/**
 * What an agent exchange leaves behind for someone to look at afterwards: one
 * trace per exchange, with a span for each model call and each tool call
 * inside it, so "what did it do, in what order, with what, and how long did
 * each step take" is something you read off a screen instead of guessing.
 *
 * <p>Everything here goes through Micrometer's Observation API, which turns
 * each observation into both a span and a timer — so the same code also
 * yields metrics (how long tool X takes, how often it fails) without a second
 * set of instrumentation. Low-cardinality values become metric tags and must
 * stay few; anything unbounded (ids, arguments, text) is high-cardinality and
 * only ever lands on the span.
 *
 * <p>Span and attribute names follow OpenTelemetry's GenAI conventions
 * ({@code gen_ai.*}) wherever one exists, so any tool that understands those
 * can read these traces; what has no convention is under {@code jazzlogs.*}.
 *
 * <p>Kept apart from {@link JazzlogsAgent} so the loop reads as the loop: the
 * agent says what happened, this class decides how that is recorded.
 */
@Component
public class AgentTelemetry {

    private static final String PROVIDER = "openai";
    private static final String AGENT_NAME = "jazzlogs";

    private final ObservationRegistry registry;
    private final String model;
    private final boolean captureContent;

    /**
     * @param captureContent whether spans carry what was actually said and
     *                       returned — the user's message, tool arguments and
     *                       results, the answer. That is what makes a trace
     *                       worth reading, and also exactly what should not
     *                       be shipped to a third party without deciding to;
     *                       off, spans keep their structure, timing and
     *                       token counts only
     */
    public AgentTelemetry(
        ObservationRegistry registry,
        @Value("${agent.model.name:gpt-5.6-luna}") String model,
        @Value("${agent.tracing.capture-content:true}") boolean captureContent
    ) {
        this.registry = registry;
        this.model = model;
        this.captureContent = captureContent;
    }

    /** Opens the trace of one exchange. Close it, whatever happens. */
    public Exchange startExchange(Chat chat, String userMessage) {
        Observation observation = Observation.createNotStarted("jazzlogs.agent.exchange", registry)
            .contextualName("invoke_agent " + AGENT_NAME)
            .lowCardinalityKeyValue("gen_ai.operation.name", "invoke_agent")
            .lowCardinalityKeyValue("gen_ai.agent.name", AGENT_NAME);
        // A brand-new chat has no id until its first exchange is saved — see Exchange#finished.
        if (chat.getId() != null) {
            observation.highCardinalityKeyValue("gen_ai.conversation.id", chat.getId().toString());
        }
        content(observation, "jazzlogs.user_message", userMessage);
        return new Exchange(observation.start());
    }

    /** Adds {@code value} to the span only when content capture is on, and only if there is something to add. */
    private void content(Observation observation, String key, String value) {
        if (captureContent && value != null && !value.isEmpty()) {
            observation.highCardinalityKeyValue(key, value);
        }
    }

    /** One exchange's trace — the root span, and the factory for the spans inside it. */
    public final class Exchange implements AutoCloseable {

        private final Observation observation;
        private TokenUsage tokens = TokenUsage.NONE;
        private int modelCalls;
        private int toolCalls;

        private Exchange(Observation observation) {
            this.observation = observation;
        }

        /**
         * @param iteration   which pass of the loop this is, from 1
         * @param forcedFinal whether the model was denied tools to make it answer
         */
        public ModelCall startModelCall(int iteration, boolean forcedFinal) {
            modelCalls++;
            Observation call = Observation.createNotStarted("jazzlogs.agent.model_call", registry)
                .parentObservation(observation)
                .contextualName("chat " + model)
                .lowCardinalityKeyValue("gen_ai.operation.name", "chat")
                .lowCardinalityKeyValue("gen_ai.provider.name", PROVIDER)
                .lowCardinalityKeyValue("gen_ai.request.model", model)
                .highCardinalityKeyValue("jazzlogs.iteration", Integer.toString(iteration))
                .highCardinalityKeyValue("jazzlogs.forced_final", Boolean.toString(forcedFinal));
            return new ModelCall(call.start(), this);
        }

        /**
         * Call this from the thread running the exchange, then hand the result
         * to whichever thread runs the tool: the span's parent is set
         * explicitly here, so it does not depend on what that thread has in scope.
         */
        public ToolCall startToolCall(ToolCallRequest request) {
            toolCalls++;
            Observation call = Observation.createNotStarted("jazzlogs.agent.tool_call", registry)
                .parentObservation(observation)
                .contextualName("execute_tool " + request.name())
                .lowCardinalityKeyValue("gen_ai.operation.name", "execute_tool")
                .lowCardinalityKeyValue("gen_ai.tool.name", request.name())
                .highCardinalityKeyValue("gen_ai.tool.call.id", request.callId());
            content(call, "gen_ai.tool.call.arguments", request.argumentsJson());
            return new ToolCall(call.start());
        }

        /** The exchange closed with an answer. */
        public void finished(AgentFinalAnswer answer, UUID chatId, List<TrackEditorialCatalogueDto> recommended) {
            observation.highCardinalityKeyValue("gen_ai.conversation.id", chatId.toString());
            observation.lowCardinalityKeyValue("jazzlogs.result_type", answer.resultType().name());
            observation.highCardinalityKeyValue("jazzlogs.recommended_tracks.count", Integer.toString(recommended.size()));
            if (!recommended.isEmpty()) {
                observation.highCardinalityKeyValue(
                    "jazzlogs.recommended_tracks",
                    String.join(" | ", recommended.stream().map(TrackEditorialCatalogueDto::trackName).toList())
                );
            }
            content(observation, "jazzlogs.answer", answer.answerText());
            content(observation, "jazzlogs.session_summary", answer.updatedSessionSummary());
        }

        public void failed(Throwable error) {
            observation.error(error);
        }

        private void add(TokenUsage usage) {
            tokens = tokens.plus(usage);
        }

        /** Totals go on the root span at the very end, so they are there whether the exchange answered or failed. */
        @Override
        public void close() {
            observation.highCardinalityKeyValue("jazzlogs.model_calls", Integer.toString(modelCalls));
            observation.highCardinalityKeyValue("jazzlogs.tool_calls", Integer.toString(toolCalls));
            tokenAttributes(observation, tokens);
            observation.stop();
        }
    }

    /** One request to the model. */
    public final class ModelCall implements AutoCloseable {

        private final Observation observation;
        private final Exchange exchange;

        private ModelCall(Observation observation, Exchange exchange) {
            this.observation = observation;
            this.exchange = exchange;
        }

        /** What came back: either a request for tools or the closing answer. */
        public void completed(Step step) {
            exchange.add(step.usage());
            observation.highCardinalityKeyValue("gen_ai.response.id", step.responseId());
            tokenAttributes(observation, step.usage());
            boolean closes = step.toolCalls().isEmpty();
            observation.highCardinalityKeyValue("jazzlogs.closes_exchange", Boolean.toString(closes));
            if (closes) {
                content(observation, "jazzlogs.output", step.assistantText());
            } else {
                observation.highCardinalityKeyValue(
                    "jazzlogs.requested_tools", String.join(", ", step.toolCalls().stream().map(ToolCallRequest::name).toList())
                );
            }
        }

        public void failed(Throwable error) {
            observation.error(error);
        }

        @Override
        public void close() {
            observation.stop();
        }
    }

    /** One tool execution. */
    public final class ToolCall implements AutoCloseable {

        private final Observation observation;

        private ToolCall(Observation observation) {
            this.observation = observation;
        }

        /** A tool that ran and reported failure (bad arguments, unknown id) is a completed call with {@code success=false}, not an error. */
        public void completed(ToolExecutionResult result) {
            observation.lowCardinalityKeyValue("jazzlogs.tool.success", Boolean.toString(result.success()));
            observation.highCardinalityKeyValue("jazzlogs.tool.result.chars", Integer.toString(result.payload().length()));
            content(observation, "gen_ai.tool.call.result", result.payload());
        }

        public void failed(Throwable error) {
            observation.error(error);
        }

        @Override
        public void close() {
            observation.stop();
        }
    }

    private static void tokenAttributes(Observation observation, TokenUsage usage) {
        observation.highCardinalityKeyValue("gen_ai.usage.input_tokens", Long.toString(usage.input()));
        observation.highCardinalityKeyValue("gen_ai.usage.cache_read.input_tokens", Long.toString(usage.cached()));
        observation.highCardinalityKeyValue("gen_ai.usage.output_tokens", Long.toString(usage.output()));
        observation.highCardinalityKeyValue("jazzlogs.usage.reasoning_tokens", Long.toString(usage.reasoning()));
    }
}
