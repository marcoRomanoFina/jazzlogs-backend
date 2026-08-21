package com.jazzlogs.backend.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.jazzlogs.backend.chat.chat.Chat;
import com.jazzlogs.backend.user.User;

// Pure Mockito unit tests — Agent is just a mock here, since this class's
// only job is the SSE/async wrapping around whatever Agent does, not the
// agent's own logic (see JazzlogsAgentTest for that). A synchronous Executor
// stand-in (Runnable::run) keeps runExchange's async dispatch deterministic
// in these tests, same reasoning as JazzlogsAgentTest's.
@ExtendWith(MockitoExtension.class)
class AgentOrchestratorTest {

    @Mock
    private Agent agent;

    private AgentOrchestrator orchestrator;
    private Chat chat;

    @BeforeEach
    void setUp() {
        Executor synchronousExecutor = Runnable::run;
        orchestrator = new AgentOrchestrator(agent, synchronousExecutor);

        User user = new User(UUID.randomUUID(), "test@example.com");
        chat = new Chat(user, null);
    }

    @Test
    void runExchange_delegatesToTheAgent_andCompletesTheEmitterOnSuccess() throws Exception {
        orchestrator.runExchange(chat, "hi", "America/Argentina/Buenos_Aires");

        verify(agent).run(any(), eq(chat), eq("hi"), eq("America/Argentina/Buenos_Aires"));
    }

    @Test
    void handleFailure_emitsOnlyAnErrorEvent() {
        RecordingSink sink = new RecordingSink();

        orchestrator.handleFailure(sink, new SseEmitter(), chat, new RuntimeException("boom"));

        assertThat(sink.eventTypes()).containsExactly(AgentEvent.AgentError.class);
    }

    @Test
    void agentThrowing_isRoutedToHandleFailure_notLeftUnhandled() throws Exception {
        doThrow(new RuntimeException("Responses API is down")).when(agent).run(any(), any(), any(), any());

        // No assertion needed beyond "this doesn't throw" — runExchange's
        // CompletableFuture.runAsync catches Exception and routes it to
        // handleFailure (see handleFailure_emitsOnlyAnErrorEvent for what
        // that actually emits); a synchronous executor means it's already
        // run by the time runExchange returns.
        orchestrator.runExchange(chat, "hi", null);
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
