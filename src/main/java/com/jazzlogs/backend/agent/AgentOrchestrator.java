package com.jazzlogs.backend.agent;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.jazzlogs.backend.chat.chat.Chat;

import lombok.extern.slf4j.Slf4j;

/**
 * Where the logic of how agent(s) communicate to fulfill a chat exchange
 * request lives. Today that's trivial and linear: a single {@link Agent}
 * ({@link JazzlogsAgent}, running its own ReAct loop) does all the work, and
 * this class just wraps it in SSE/async plumbing. Depends on the
 * {@code Agent} interface, not on {@code JazzlogsAgent} directly, precisely
 * so this can grow into something less linear later — multiple agents
 * coordinating, a different strategy per request, whatever comes up —
 * without touching the SSE/async wrapping itself: adding or swapping an
 * agent is just a different bean satisfying this same contract.
 * <p>
 * Knows nothing about the Responses API, the tool-calling loop, or
 * persistence — only how to turn "run this agent" into an
 * {@link SseEmitter} the controller can return immediately, streamed to as
 * {@link AgentEvent}s arrive over time. See {@link EventSink} for the
 * callback an {@code Agent} reports those events through.
 */
@Slf4j
@Service
public class AgentOrchestrator {

    private final Agent agent;

    /**
     * Lets {@link #runExchange} return its {@link SseEmitter} immediately while
     * {@code agent.run(...)} keeps going in the background — can't be
     * {@code @Async}, since the emitter has to be created and returned first.
     * Virtual threads because the agent's own work is all blocking I/O, never
     * CPU. A Spring bean ({@code AsyncConfig.agentExecutor}), not owned here,
     * so tests can swap in a synchronous stand-in.
     */
    private final Executor agentExecutor;

    public AgentOrchestrator(Agent agent, Executor agentExecutor) {
        this.agent = agent;
        this.agentExecutor = agentExecutor;
    }

    /**
     * Starts one chat exchange: returns the {@link SseEmitter} immediately
     * while the agent runs on {@link #agentExecutor} in the background,
     * streaming progress through it. The 1-minute timeout is a safety-net
     * ceiling, not the expected duration — a normal turn takes seconds.
     *
     * @param chat        the chat this exchange belongs to
     * @param userMessage the user's new message
     * @param timezone    IANA zone id for runtime context; null falls back to UTC
     * @return an emitter streaming {@link AgentEvent}s as the agent produces them
     */
    public SseEmitter runExchange(Chat chat, String userMessage, String timezone) {

        SseEmitter emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(1));
        
        EventSink sink = EventSink.toSse(emitter);

        CompletableFuture.runAsync(() -> {
            try {
                agent.run(sink, chat, userMessage, timezone);
                emitter.complete();
            } catch (Exception e) {
                handleFailure(sink, emitter, chat, e);
            }
        }, agentExecutor);

        return emitter;
    }

    /**
     * No chat_exchange is ever persisted here — same all-or-nothing contract
     * as {@code final_response} being NOT NULL: if the agent fails, the whole
     * exchange fails, no partial record.
     */
    void handleFailure(EventSink sink, SseEmitter emitter, Chat chat, Exception e) {
        log.error("Agent exchange failed for chat {}: {}", chat.getId(), e.getMessage(), e);
        try {
            sink.emit(new AgentEvent.AgentError("No pude terminar de pensar la respuesta, probá de nuevo."));
            emitter.complete();
        } catch (IOException io) {
            emitter.completeWithError(io);
        }
    }
}
