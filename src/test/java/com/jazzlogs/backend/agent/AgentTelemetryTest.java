package com.jazzlogs.backend.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;

import com.jazzlogs.backend.character.JazzlogsCharacter;
import com.jazzlogs.backend.chat.chat.Chat;
import com.jazzlogs.backend.user.User;

// No tracer here: a handler that keeps every finished observation stands in
// for the exporter, so these assert on exactly what a span would carry — its
// name, its parent, its attributes — without anything leaving the test.
class AgentTelemetryTest {

    private final List<Observation.Context> finished = new ArrayList<>();
    private ObservationRegistry registry;
    private Chat chat;
    private UUID chatId;

    @BeforeEach
    void setUp() {
        registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public void onStop(Observation.Context context) {
                finished.add(context);
            }

            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });

        chatId = UUID.randomUUID();
        chat = new Chat(new User(UUID.randomUUID(), "test@example.com"), null, JazzlogsCharacter.MARK);
        ReflectionTestUtils.setField(chat, "id", chatId);
    }

    @Test
    void anExchange_isOneRootSpan_withItsModelAndToolCallsInside() {
        AgentTelemetry telemetry = new AgentTelemetry(registry, "test-model", true);
        ToolCallRequest request = new ToolCallRequest("call_1", "FIND_TRACKS", "{\"lookingFor\":\"late and quiet\"}");

        try (AgentTelemetry.Exchange exchange = telemetry.startExchange(chat, "something for tonight")) {
            try (AgentTelemetry.ModelCall call = exchange.startModelCall(1, false)) {
                call.completed(new Step("resp_1", "", List.of(request), new TokenUsage(1000, 800, 50, 10)));
            }
            try (AgentTelemetry.ToolCall tool = exchange.startToolCall(request)) {
                tool.completed(new ToolExecutionResult("{\"candidates\":[]}", true));
            }
            try (AgentTelemetry.ModelCall call = exchange.startModelCall(2, false)) {
                call.completed(new Step("resp_2", "{\"answerText\":\"here\"}", List.of(), new TokenUsage(1200, 1000, 80, 0)));
            }
            exchange.finished(
                new AgentFinalAnswer(AgentFinalAnswer.ResultType.DIRECT_RESPONSE, "here", List.of(), null, "summary"), chatId, List.of()
            );
        }

        assertThat(finished).extracting(Observation.Context::getContextualName)
            .containsExactly("chat test-model", "execute_tool FIND_TRACKS", "chat test-model", "invoke_agent jazzlogs");

        Observation.Context root = finished.get(3);
        assertThat(finished.subList(0, 3)).allSatisfy(child ->
            assertThat(child.getParentObservation().getContextView()).isSameAs(root));

        Map<String, String> exchange = attributes(root);
        assertThat(exchange)
            .containsEntry("gen_ai.conversation.id", chatId.toString())
            .containsEntry("jazzlogs.user_message", "something for tonight")
            .containsEntry("jazzlogs.answer", "here")
            .containsEntry("jazzlogs.result_type", "DIRECT_RESPONSE")
            .containsEntry("jazzlogs.model_calls", "2")
            .containsEntry("jazzlogs.tool_calls", "1")
            // Totals across both model calls.
            .containsEntry("gen_ai.usage.input_tokens", "2200")
            .containsEntry("gen_ai.usage.cache_read.input_tokens", "1800")
            .containsEntry("gen_ai.usage.output_tokens", "130");

        assertThat(attributes(finished.get(0)))
            .containsEntry("gen_ai.response.id", "resp_1")
            .containsEntry("gen_ai.usage.input_tokens", "1000")
            .containsEntry("jazzlogs.requested_tools", "FIND_TRACKS")
            .containsEntry("jazzlogs.closes_exchange", "false");

        assertThat(attributes(finished.get(1)))
            .containsEntry("gen_ai.tool.name", "FIND_TRACKS")
            .containsEntry("gen_ai.tool.call.id", "call_1")
            .containsEntry("gen_ai.tool.call.arguments", "{\"lookingFor\":\"late and quiet\"}")
            .containsEntry("gen_ai.tool.call.result", "{\"candidates\":[]}")
            .containsEntry("jazzlogs.tool.success", "true");
    }

    @Test
    void withContentCaptureOff_spansKeepTheirShape_butCarryNoneOfWhatWasSaid() {
        AgentTelemetry telemetry = new AgentTelemetry(registry, "test-model", false);
        ToolCallRequest request = new ToolCallRequest("call_1", "FIND_TRACKS", "{\"lookingFor\":\"a private thing\"}");

        try (AgentTelemetry.Exchange exchange = telemetry.startExchange(chat, "a private message")) {
            try (AgentTelemetry.ToolCall tool = exchange.startToolCall(request)) {
                tool.completed(new ToolExecutionResult("{\"private\":\"result\"}", true));
            }
            try (AgentTelemetry.ModelCall call = exchange.startModelCall(1, false)) {
                call.completed(new Step("resp_1", "a private answer", List.of(), new TokenUsage(10, 0, 5, 0)));
            }
            exchange.finished(
                new AgentFinalAnswer(AgentFinalAnswer.ResultType.DIRECT_RESPONSE, "a private answer", List.of(), null, "a private summary"),
                chatId, List.of()
            );
        }

        assertThat(finished).hasSize(3);
        assertThat(finished).allSatisfy(span -> assertThat(attributes(span).values()).noneMatch(value -> value.contains("private")));
        // Still there: what ran, whether it worked, and what it cost.
        assertThat(attributes(finished.get(0))).containsEntry("gen_ai.tool.name", "FIND_TRACKS").containsEntry("jazzlogs.tool.success", "true");
        assertThat(attributes(finished.get(1))).containsEntry("gen_ai.usage.input_tokens", "10");
    }

    @Test
    void aFailure_isRecordedOnTheSpanItHappenedIn() {
        AgentTelemetry telemetry = new AgentTelemetry(registry, "test-model", true);
        IllegalStateException boom = new IllegalStateException("stream failed");

        try (AgentTelemetry.Exchange exchange = telemetry.startExchange(chat, "hi")) {
            try (AgentTelemetry.ModelCall call = exchange.startModelCall(1, false)) {
                call.failed(boom);
            }
            exchange.failed(boom);
        }

        assertThat(finished).extracting(Observation.Context::getError).containsExactly(boom, boom);
    }

    private static Map<String, String> attributes(Observation.Context context) {
        return context.getAllKeyValues().stream().collect(Collectors.toMap(KeyValue::getKey, KeyValue::getValue));
    }
}
