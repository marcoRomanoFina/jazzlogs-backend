package com.jazzlogs.backend.agent;

import java.io.IOException;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * What an {@code Agent} reports progress through, one {@link AgentEvent} at
 * a time — decoupled from any real transport (SSE, a test recorder) so
 * agent implementations never depend on how an event actually gets out.
 */
@FunctionalInterface
public interface EventSink {

    /** Reports one event. */
    void emit(AgentEvent event) throws IOException;

    /** Adapts a real {@link SseEmitter} into an EventSink — the only place an AgentEvent becomes an actual SSE frame. */
    static EventSink toSse(SseEmitter emitter) {
        return event -> emitter.send(SseEmitter.event().name(event.wireName()).data(event));
    }
}
