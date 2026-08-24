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
import java.util.concurrent.Executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import tools.jackson.databind.json.JsonMapper;

import com.jazzlogs.backend.agent.tools.JazzTool;
import com.jazzlogs.backend.chat.chat.Chat;
import com.jazzlogs.backend.chat.chatexchange.ChatExchangeService;
import com.jazzlogs.backend.chat.chatexchange.dto.ChatExchangeDto;
import com.jazzlogs.backend.user.User;

// Pure Mockito unit tests, no Spring context and no SseEmitter attached to a
// real HTTP response — run() takes an EventSink precisely so tests can
// assert on the emitted AgentEvents (via a simple recording fake) instead of
// intercepting SseEmitter's internal, hard-to-introspect chunk format. The
// AgentEvent -> wire event-name mapping lives only in AgentOrchestrator.runExchange,
// not exercised here — see AgentOrchestratorTest for that (and for the
// "a failure never persists" contract, which is that class's, not this one's:
// JazzlogsAgent.run() either finishes having persisted, or throws — nothing
// downstream of the throw is this class's concern).
//
// A Step with empty toolCalls is the model's final answer — its
// assistantText is the raw JSON a real call would get from
// OpenAiResponsesStreamClient's text.format (see AgentFinalAnswer). There's
// no separate "tool call" standing in for it.
//
// Winners resolution (hallucinated ids dropped, DIRECT_RESPONSE has null
// winners) isn't this class's job — that's ChatExchangeService, tested in
// ChatExchangeServiceTest. Here ChatExchangeService is just a mock: these
// tests only care that JazzlogsAgent calls it with the right arguments and
// reacts correctly to what it returns.
@ExtendWith(MockitoExtension.class)
class JazzlogsAgentTest {

    @Mock
    private ChatContextBuilder contextBuilder;

    @Mock
    private OpenAiResponsesStreamClient streamClient;

    @Mock
    private ChatExchangeService chatExchangeService;

    @Mock
    private JazzTool searchTool;

    private JazzlogsAgent agent;
    private Chat chat;
    private RecordingSink sink;

    @BeforeEach
    void setUp() {
        when(searchTool.name()).thenReturn("SEMANTIC_CATALOG_SEARCH");

        // Runs the submitted task on the calling thread instead of a real
        // executor — these tests only assert on outcome/ordering, never on
        // actual concurrency, so a synchronous stand-in keeps them fast and
        // deterministic. See AsyncConfig.agentExecutor for the real one.
        Executor synchronousExecutor = Runnable::run;
        agent = new JazzlogsAgent(contextBuilder, streamClient, chatExchangeService, new JsonMapper(), synchronousExecutor, List.of(searchTool));
        ReflectionTestUtils.setField(agent, "maxIterations", 6);
        ReflectionTestUtils.setField(agent, "maxToolCallsPerTurn", 4);

        User user = new User(UUID.randomUUID(), "test@example.com");
        chat = new Chat(user, null);
        sink = new RecordingSink();
    }

    @Test
    void normalToolCall_thenFinalAnswer_persistsAndEmitsInOrder() throws Exception {
        ToolCallRequest searchCall = new ToolCallRequest("call_1", "SEMANTIC_CATALOG_SEARCH", "{}");
        Step toolTurn = new Step("resp_1", "", List.of(searchCall));

        String finalJson = "{\"resultType\":\"DIRECT_RESPONSE\",\"answerText\":\"Here's a mellow one for you.\","
            + "\"recommendedItems\":[],\"suggestedChatTitle\":null,\"updatedSessionSummary\":null}";
        Step finalTurn = new Step("resp_2", finalJson, List.of());

        when(streamClient.streamTurn(any(), isNull(), anyBoolean())).thenReturn(toolTurn);
        when(streamClient.streamTurn(any(), eq("resp_1"), anyBoolean())).thenReturn(finalTurn);
        when(searchTool.execute(eq(searchCall), any())).thenReturn(new ToolExecutionResult("{}", true));
        when(chatExchangeService.persist(eq(chat), eq("recommend something mellow"), any(), isNull(), isNull(), isNull()))
            .thenReturn(stubDto());

        agent.run(sink, chat, "recommend something mellow", null);

        verify(chatExchangeService).persist(eq(chat), eq("recommend something mellow"), any(), isNull(), isNull(), isNull());
        assertThat(sink.eventTypes()).containsExactly(
            AgentEvent.RunStarted.class,
            AgentEvent.ToolCallStarted.class, AgentEvent.ToolCallFinished.class,
            AgentEvent.RunFinished.class
        );
    }

    @Test
    void catalogResponse_passesRecommendedItemsThrough_directResponsePassesNull() throws Exception {
        String finalJson = "{\"resultType\":\"CATALOG_RESPONSE\",\"answerText\":\"here you go\",\"recommendedItems\":["
            + "{\"type\":\"ALBUM\",\"id\":\"" + UUID.randomUUID() + "\"}"
            + "],\"suggestedChatTitle\":null,\"updatedSessionSummary\":null}";
        Step finalTurn = new Step("resp_1", finalJson, List.of());

        when(streamClient.streamTurn(any(), isNull(), anyBoolean())).thenReturn(finalTurn);
        when(chatExchangeService.persist(any(), any(), any(), any(), any(), any())).thenReturn(stubDto());

        agent.run(sink, chat, "recommend something mellow", null);

        verify(chatExchangeService).persist(
            eq(chat), any(), any(), org.mockito.ArgumentMatchers.argThat(items -> items != null && items.size() == 1), isNull(), isNull()
        );
    }

    @Test
    void multipleToolCallsInOneTurn_areAllDispatchedConcurrentlyAndAwaited() throws Exception {
        ToolCallRequest call1 = new ToolCallRequest("call_1", "SEMANTIC_CATALOG_SEARCH", "{}");
        ToolCallRequest call2 = new ToolCallRequest("call_2", "SEMANTIC_CATALOG_SEARCH", "{}");
        Step toolTurn = new Step("resp_1", "", List.of(call1, call2));

        String finalJson = "{\"resultType\":\"DIRECT_RESPONSE\",\"answerText\":\"done\",\"recommendedItems\":[],"
            + "\"suggestedChatTitle\":null,\"updatedSessionSummary\":null}";
        Step finalTurn = new Step("resp_2", finalJson, List.of());

        when(streamClient.streamTurn(any(), isNull(), anyBoolean())).thenReturn(toolTurn);
        when(streamClient.streamTurn(any(), eq("resp_1"), anyBoolean())).thenReturn(finalTurn);
        when(searchTool.execute(any(), any())).thenReturn(new ToolExecutionResult("{}", true));
        when(chatExchangeService.persist(any(), any(), any(), any(), any(), any())).thenReturn(stubDto());

        agent.run(sink, chat, "find me something", null);

        verify(searchTool, times(2)).execute(any(), any());
        assertThat(sink.eventTypes()).containsExactly(
            AgentEvent.RunStarted.class,
            AgentEvent.ToolCallStarted.class, AgentEvent.ToolCallStarted.class,
            AgentEvent.ToolCallFinished.class, AgentEvent.ToolCallFinished.class,
            AgentEvent.RunFinished.class
        );
    }

    @Test
    void toolCallsBeyondTheCap_areRejectedWithoutDispatching() throws Exception {
        ReflectionTestUtils.setField(agent, "maxToolCallsPerTurn", 2);

        ToolCallRequest call1 = new ToolCallRequest("call_1", "SEMANTIC_CATALOG_SEARCH", "{}");
        ToolCallRequest call2 = new ToolCallRequest("call_2", "SEMANTIC_CATALOG_SEARCH", "{}");
        ToolCallRequest call3 = new ToolCallRequest("call_3", "SEMANTIC_CATALOG_SEARCH", "{}");
        Step toolTurn = new Step("resp_1", "", List.of(call1, call2, call3));

        String finalJson = "{\"resultType\":\"DIRECT_RESPONSE\",\"answerText\":\"done\",\"recommendedItems\":[],"
            + "\"suggestedChatTitle\":null,\"updatedSessionSummary\":null}";
        Step finalTurn = new Step("resp_2", finalJson, List.of());

        when(streamClient.streamTurn(any(), isNull(), anyBoolean())).thenReturn(toolTurn);
        when(streamClient.streamTurn(any(), eq("resp_1"), anyBoolean())).thenReturn(finalTurn);
        when(searchTool.execute(any(), any())).thenReturn(new ToolExecutionResult("{}", true));
        when(chatExchangeService.persist(any(), any(), any(), any(), any(), any())).thenReturn(stubDto());

        agent.run(sink, chat, "find me something", null);

        // Only the first 2 (the cap) ever reach the tool — the 3rd still gets
        // its own started/finished pair (the API needs an output for every
        // function_call it made) but never touches searchTool.execute: 3 total.
        // The closing turn has no tool calls at all, so it contributes none.
        verify(searchTool, times(2)).execute(any(), any());
        assertThat(sink.eventTypes().stream().filter(AgentEvent.ToolCallStarted.class::equals).count()).isEqualTo(3);
        assertThat(sink.eventTypes().stream().filter(AgentEvent.ToolCallFinished.class::equals).count()).isEqualTo(3);
    }

    @Test
    void toolRejectsInvalidArguments_becomesAFailedResult_insteadOfFailingTheExchange() throws Exception {
        ToolCallRequest badCall = new ToolCallRequest("call_1", "SEMANTIC_CATALOG_SEARCH", "{\"entityType\":\"NOT_REAL\"}");
        Step toolTurn = new Step("resp_1", "", List.of(badCall));

        String finalJson = "{\"resultType\":\"DIRECT_RESPONSE\",\"answerText\":\"done\",\"recommendedItems\":[],"
            + "\"suggestedChatTitle\":null,\"updatedSessionSummary\":null}";
        Step finalTurn = new Step("resp_2", finalJson, List.of());

        when(streamClient.streamTurn(any(), isNull(), anyBoolean())).thenReturn(toolTurn);
        when(streamClient.streamTurn(any(), eq("resp_1"), anyBoolean())).thenReturn(finalTurn);
        when(searchTool.execute(eq(badCall), any())).thenThrow(new IllegalArgumentException("entityType must be one of ALBUM, TRACK, ARTIST"));
        when(chatExchangeService.persist(any(), any(), any(), any(), any(), any())).thenReturn(stubDto());

        agent.run(sink, chat, "find me something", null);

        verify(chatExchangeService).persist(any(), any(), any(), any(), any(), any());
        assertThat(sink.eventTypes()).containsExactly(
            AgentEvent.RunStarted.class,
            AgentEvent.ToolCallStarted.class, AgentEvent.ToolCallFinished.class,
            AgentEvent.RunFinished.class
        );
        AgentEvent.ToolCallFinished finished = (AgentEvent.ToolCallFinished) sink.events.get(2);
        assertThat(finished.success()).isFalse();
    }

    @Test
    void lastIteration_forcesAFinalAnswer_whenModelDoesNotConverge() throws Exception {
        ReflectionTestUtils.setField(agent, "maxIterations", 2);

        ToolCallRequest searchCall = new ToolCallRequest("call_1", "SEMANTIC_CATALOG_SEARCH", "{}");
        Step nonConvergingTurn = new Step("resp_1", "", List.of(searchCall));

        String finalJson = "{\"resultType\":\"DIRECT_RESPONSE\",\"answerText\":\"ok here it is\",\"recommendedItems\":[],"
            + "\"suggestedChatTitle\":null,\"updatedSessionSummary\":null}";
        Step forcedFinalTurn = new Step("resp_2", finalJson, List.of());

        when(streamClient.streamTurn(any(), isNull(), eq(false))).thenReturn(nonConvergingTurn);
        when(streamClient.streamTurn(any(), eq("resp_1"), eq(true))).thenReturn(forcedFinalTurn);
        when(searchTool.execute(any(), any())).thenReturn(new ToolExecutionResult("{}", true));
        when(chatExchangeService.persist(any(), any(), any(), any(), any(), any())).thenReturn(stubDto());

        agent.run(sink, chat, "find me something", null);

        verify(streamClient).streamTurn(any(), eq("resp_1"), eq(true));
        verify(chatExchangeService).persist(any(), any(), any(), any(), any(), any());
    }

    @Test
    void exceptionDuringTheLoop_propagatesAndNeverPersists() {
        when(streamClient.streamTurn(any(), any(), anyBoolean())).thenThrow(new RuntimeException("Responses API is down"));

        assertThatThrownBy(() -> agent.run(sink, chat, "hi", null)).isInstanceOf(RuntimeException.class);

        verifyNoInteractions(chatExchangeService);
    }

    private static ChatExchangeDto stubDto() {
        return new ChatExchangeDto(UUID.randomUUID(), UUID.randomUUID(), "msg", "answer", List.of(), Instant.now());
    }

    private static class RecordingSink implements EventSink {
        private final List<AgentEvent> events = new ArrayList<>();

        @Override
        public void emit(AgentEvent event) {
            events.add(event);
        }

        List<Class<?>> eventTypes() {
            return events.stream().map(Object::getClass).toList();
        }
    }
}
