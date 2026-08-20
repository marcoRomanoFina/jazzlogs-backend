package com.jazzlogs.backend.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.jazzlogs.backend.agent.tools.JazzTool;
import com.jazzlogs.backend.agent.tools.SubmitFinalAnswerTool;
import com.jazzlogs.backend.chat.Chat;
import com.jazzlogs.backend.chat.ChatExchangeService;
import com.jazzlogs.backend.chat.dto.ChatExchangeDto;
import com.jazzlogs.backend.user.User;

// Pure Mockito unit tests, no Spring context and no SseEmitter attached to a
// real HTTP response — runLoop/handleFailure take an EventSink (a nested
// package-private interface on AgentOrchestrator) precisely so tests can
// assert on emitted (eventName) order via a simple recording fake, instead of
// intercepting SseEmitter's internal, hard-to-introspect chunk format.
//
// Winners resolution (hallucinated ids dropped, DIRECT_RESPONSE has null
// winners) isn't this class's job — that's ChatExchangeService, tested in
// ChatExchangeServiceTest. Here ChatExchangeService is just a mock: these
// tests only care that AgentOrchestrator calls it with the right arguments
// and reacts correctly to what it returns.
@ExtendWith(MockitoExtension.class)
class AgentOrchestratorTest {

    @Mock
    private ChatContextBuilder contextBuilder;

    @Mock
    private OpenAiResponsesStreamClient streamClient;

    @Mock
    private ChatExchangeService chatExchangeService;

    @Mock
    private JazzTool searchTool;

    private AgentOrchestrator orchestrator;
    private Chat chat;
    private RecordingSink sink;

    @BeforeEach
    void setUp() {
        when(searchTool.name()).thenReturn("SEMANTIC_CATALOG_SEARCH");

        orchestrator = new AgentOrchestrator(contextBuilder, streamClient, chatExchangeService, List.of(searchTool));
        ReflectionTestUtils.setField(orchestrator, "maxIterations", 6);
        ReflectionTestUtils.setField(orchestrator, "maxToolCallsPerTurn", 4);

        User user = new User(UUID.randomUUID(), "test@example.com");
        chat = new Chat(user, null);
        sink = new RecordingSink();
    }

    @Test
    void normalToolCall_thenSubmitFinalAnswer_persistsAndEmitsInOrder() throws Exception {
        ToolCallRequest searchCall = new ToolCallRequest("call_1", "SEMANTIC_CATALOG_SEARCH", "{}");
        StreamedTurn toolTurn = new StreamedTurn("resp_1", "", List.of(searchCall));

        String finalArgs = "{\"resultType\":\"DIRECT_RESPONSE\",\"recommendedItems\":[],"
            + "\"suggestedChatTitle\":null,\"updatedSessionSummary\":null}";
        ToolCallRequest submitCall = new ToolCallRequest("call_2", SubmitFinalAnswerTool.NAME, finalArgs);
        StreamedTurn finalTurn = new StreamedTurn("resp_2", "Here's a mellow one for you.", List.of(submitCall));

        when(streamClient.streamTurn(any(), isNull(), anyBoolean())).thenReturn(toolTurn);
        when(streamClient.streamTurn(any(), eq("resp_1"), anyBoolean())).thenReturn(finalTurn);
        when(searchTool.execute(eq(searchCall), any())).thenReturn(new ToolExecutionResult("{}", true));
        when(chatExchangeService.persist(eq(chat), eq("recommend something mellow"), any(), isNull(), isNull(), isNull()))
            .thenReturn(stubDto());

        orchestrator.runLoop(sink, chat, "recommend something mellow", null);

        verify(chatExchangeService).persist(eq(chat), eq("recommend something mellow"), any(), isNull(), isNull(), isNull());
        assertThat(sink.eventNames()).containsExactly(
            "iteration_started",
            "tool_call_started", "tool_call_finished",
            "iteration_started",
            "tool_call_started", "tool_call_finished",
            "answer_delta", "answer_metadata", "answer_done"
        );
    }

    // A tool call can (and often does) come back with zero accompanying
    // message text — submit_final_answer's own answerText argument is the
    // one place the real answer is guaranteed to be, so it must win even
    // when the turn's separate assistant text is blank or different.
    @Test
    void submitFinalAnswersAnswerText_isUsedOverTheTurnsOwnAssistantText() throws Exception {
        String finalArgs = "{\"resultType\":\"DIRECT_RESPONSE\",\"answerText\":\"The real answer lives here.\","
            + "\"recommendedItems\":[]}";
        StreamedTurn finalTurn = new StreamedTurn(
            "resp_1", "", List.of(new ToolCallRequest("call_1", SubmitFinalAnswerTool.NAME, finalArgs))
        );

        when(streamClient.streamTurn(any(), isNull(), anyBoolean())).thenReturn(finalTurn);
        when(chatExchangeService.persist(any(), any(), any(), any(), any(), any())).thenReturn(stubDto());

        orchestrator.runLoop(sink, chat, "hola", null);

        verify(chatExchangeService).persist(eq(chat), eq("hola"), eq("The real answer lives here."), isNull(), isNull(), isNull());
    }

    @Test
    void catalogResponse_passesRecommendedItemsThrough_directResponsePassesNull() throws Exception {
        String finalArgs = "{\"resultType\":\"CATALOG_RESPONSE\",\"recommendedItems\":["
            + "{\"type\":\"ALBUM\",\"id\":\"" + UUID.randomUUID() + "\"}"
            + "],\"suggestedChatTitle\":null,\"updatedSessionSummary\":null}";
        StreamedTurn finalTurn = new StreamedTurn(
            "resp_1", "here you go", List.of(new ToolCallRequest("call_1", SubmitFinalAnswerTool.NAME, finalArgs))
        );

        when(streamClient.streamTurn(any(), isNull(), anyBoolean())).thenReturn(finalTurn);
        when(chatExchangeService.persist(any(), any(), any(), any(), any(), any())).thenReturn(stubDto());

        orchestrator.runLoop(sink, chat, "recommend something mellow", null);

        verify(chatExchangeService).persist(
            eq(chat), any(), any(), org.mockito.ArgumentMatchers.argThat(items -> items != null && items.size() == 1), isNull(), isNull()
        );
    }

    @Test
    void multipleToolCallsInOneTurn_areAllDispatchedConcurrentlyAndAwaited() throws Exception {
        ToolCallRequest call1 = new ToolCallRequest("call_1", "SEMANTIC_CATALOG_SEARCH", "{}");
        ToolCallRequest call2 = new ToolCallRequest("call_2", "SEMANTIC_CATALOG_SEARCH", "{}");
        StreamedTurn toolTurn = new StreamedTurn("resp_1", "", List.of(call1, call2));

        String finalArgs = "{\"resultType\":\"DIRECT_RESPONSE\",\"recommendedItems\":[]}";
        StreamedTurn finalTurn = new StreamedTurn(
            "resp_2", "done", List.of(new ToolCallRequest("call_3", SubmitFinalAnswerTool.NAME, finalArgs))
        );

        when(streamClient.streamTurn(any(), isNull(), anyBoolean())).thenReturn(toolTurn);
        when(streamClient.streamTurn(any(), eq("resp_1"), anyBoolean())).thenReturn(finalTurn);
        when(searchTool.execute(any(), any())).thenReturn(new ToolExecutionResult("{}", true));
        when(chatExchangeService.persist(any(), any(), any(), any(), any(), any())).thenReturn(stubDto());

        orchestrator.runLoop(sink, chat, "find me something", null);

        verify(searchTool, times(2)).execute(any(), any());
        assertThat(sink.eventNames()).containsExactly(
            "iteration_started",
            "tool_call_started", "tool_call_started",
            "tool_call_finished", "tool_call_finished",
            "iteration_started",
            "tool_call_started", "tool_call_finished",
            "answer_delta", "answer_metadata", "answer_done"
        );
    }

    @Test
    void toolCallsBeyondTheCap_areRejectedWithoutDispatching() throws Exception {
        ReflectionTestUtils.setField(orchestrator, "maxToolCallsPerTurn", 2);

        ToolCallRequest call1 = new ToolCallRequest("call_1", "SEMANTIC_CATALOG_SEARCH", "{}");
        ToolCallRequest call2 = new ToolCallRequest("call_2", "SEMANTIC_CATALOG_SEARCH", "{}");
        ToolCallRequest call3 = new ToolCallRequest("call_3", "SEMANTIC_CATALOG_SEARCH", "{}");
        StreamedTurn toolTurn = new StreamedTurn("resp_1", "", List.of(call1, call2, call3));

        String finalArgs = "{\"resultType\":\"DIRECT_RESPONSE\",\"recommendedItems\":[]}";
        StreamedTurn finalTurn = new StreamedTurn(
            "resp_2", "done", List.of(new ToolCallRequest("call_4", SubmitFinalAnswerTool.NAME, finalArgs))
        );

        when(streamClient.streamTurn(any(), isNull(), anyBoolean())).thenReturn(toolTurn);
        when(streamClient.streamTurn(any(), eq("resp_1"), anyBoolean())).thenReturn(finalTurn);
        when(searchTool.execute(any(), any())).thenReturn(new ToolExecutionResult("{}", true));
        when(chatExchangeService.persist(any(), any(), any(), any(), any(), any())).thenReturn(stubDto());

        orchestrator.runLoop(sink, chat, "find me something", null);

        // Only the first 2 (the cap) ever reach the tool — the 3rd still gets
        // its own started/finished pair (the API needs an output for every
        // function_call it made) but never touches searchTool.execute. Plus
        // one more started/finished pair for submit_final_answer itself in
        // the next iteration: 3 + 1 = 4.
        verify(searchTool, times(2)).execute(any(), any());
        assertThat(sink.eventNames().stream().filter("tool_call_started"::equals).count()).isEqualTo(4);
        assertThat(sink.eventNames().stream().filter("tool_call_finished"::equals).count()).isEqualTo(4);
    }

    @Test
    void lastIteration_forcesSubmitFinalAnswer_whenModelDoesNotConverge() throws Exception {
        ReflectionTestUtils.setField(orchestrator, "maxIterations", 2);

        ToolCallRequest searchCall = new ToolCallRequest("call_1", "SEMANTIC_CATALOG_SEARCH", "{}");
        StreamedTurn nonConvergingTurn = new StreamedTurn("resp_1", "", List.of(searchCall));

        String finalArgs = "{\"resultType\":\"DIRECT_RESPONSE\",\"recommendedItems\":[]}";
        StreamedTurn forcedFinalTurn = new StreamedTurn(
            "resp_2", "ok here it is", List.of(new ToolCallRequest("call_2", SubmitFinalAnswerTool.NAME, finalArgs))
        );

        when(streamClient.streamTurn(any(), isNull(), eq(false))).thenReturn(nonConvergingTurn);
        when(streamClient.streamTurn(any(), eq("resp_1"), eq(true))).thenReturn(forcedFinalTurn);
        when(searchTool.execute(any(), any())).thenReturn(new ToolExecutionResult("{}", true));
        when(chatExchangeService.persist(any(), any(), any(), any(), any(), any())).thenReturn(stubDto());

        orchestrator.runLoop(sink, chat, "find me something", null);

        verify(streamClient).streamTurn(any(), eq("resp_1"), eq(true));
        verify(chatExchangeService).persist(any(), any(), any(), any(), any(), any());
    }

    // Guards against a real bug: the model is instructed to always call
    // submit_final_answer in the same turn as its conversational text, but
    // that's a prompt instruction, not something the API enforces. If the
    // model answers in plain text without calling any tool at all (not even
    // submit_final_answer — as happened for a casual "hola" greeting), then
    // only closes on a later, forced-continuation turn with blank text, the
    // earlier turn's real answer must still make it to the user instead of
    // being silently dropped.
    @Test
    void modelAnswersWithoutClosing_thenClosesWithBlankText_fallsBackToEarlierAnswer() throws Exception {
        StreamedTurn textOnlyTurn = new StreamedTurn("resp_1", "¡Hola! Todo bien, ¿y vos?", List.of());

        String finalArgs = "{\"resultType\":\"DIRECT_RESPONSE\",\"recommendedItems\":[]}";
        StreamedTurn blankFinalTurn = new StreamedTurn(
            "resp_2", "", List.of(new ToolCallRequest("call_1", SubmitFinalAnswerTool.NAME, finalArgs))
        );

        when(streamClient.streamTurn(any(), isNull(), anyBoolean())).thenReturn(textOnlyTurn);
        when(streamClient.streamTurn(any(), eq("resp_1"), anyBoolean())).thenReturn(blankFinalTurn);
        when(chatExchangeService.persist(any(), any(), any(), any(), any(), any())).thenReturn(stubDto());

        orchestrator.runLoop(sink, chat, "hola como estas", null);

        verify(chatExchangeService).persist(
            eq(chat), eq("hola como estas"), eq("¡Hola! Todo bien, ¿y vos?"), isNull(), isNull(), isNull()
        );
    }

    @Test
    void exceptionDuringTheLoop_propagatesAndNeverPersists() {
        when(streamClient.streamTurn(any(), any(), anyBoolean())).thenThrow(new RuntimeException("Responses API is down"));

        assertThatThrownBy(() -> orchestrator.runLoop(sink, chat, "hi", null)).isInstanceOf(RuntimeException.class);

        verifyNoInteractions(chatExchangeService);
    }

    @Test
    void handleFailure_emitsOnlyAnErrorEvent_andNeverPersists() {
        orchestrator.handleFailure(sink, new SseEmitter(), chat, new RuntimeException("boom"));

        assertThat(sink.eventNames()).containsExactly("error");
        verifyNoInteractions(chatExchangeService);
    }

    private static ChatExchangeDto stubDto() {
        return new ChatExchangeDto(UUID.randomUUID(), UUID.randomUUID(), "msg", "answer", List.of(), Instant.now());
    }

    private static class RecordingSink implements AgentOrchestrator.EventSink {
        private final List<String> eventNames = new ArrayList<>();

        @Override
        public void emit(String eventName, Object data) {
            eventNames.add(eventName);
        }

        List<String> eventNames() {
            return eventNames;
        }
    }
}
