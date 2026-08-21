package com.jazzlogs.backend.agent;

import java.io.IOException;

import com.jazzlogs.backend.chat.chat.Chat;

/**
 * Runs one chat exchange's turn-by-turn logic, reporting progress via
 * {@link EventSink} as it goes. {@code AgentOrchestrator} depends on this
 * interface, not on {@code JazzlogsAgent} directly, so a different agent is
 * just a different bean satisfying this contract — no change to the
 * SSE/async wrapper around it.
 */
public interface Agent {

    /** Runs the exchange to completion, or throws on failure. */
    void run(EventSink sink, Chat chat, String userMessage, String timezone) throws IOException;
}
