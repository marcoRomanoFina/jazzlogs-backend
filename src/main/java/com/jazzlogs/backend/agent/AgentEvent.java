package com.jazzlogs.backend.agent;

import java.util.List;
import java.util.UUID;

import com.jazzlogs.backend.chat.chatexchange.dto.WinnerCard;

/**
 * One occasion an {@link Agent} reports over the course of an exchange.
 * Sealed so a new kind forces every {@code permits} case to account for it.
 * Each record is its own payload — carrying only the fields its occasion
 * needs and its own {@link #wireName()}.
 */
public sealed interface AgentEvent {

    /** This event's SSE event-name. */
    String wireName();

    /** Once, at the very start of the exchange. */
    record RunStarted(String label) implements AgentEvent {
        public String wireName() {
            return "run_started";
        }
    }

    /** Once per tool call the model requested this turn, before it runs. */
    record ToolCallStarted(String label) implements AgentEvent {
        public String wireName() {
            return "tool_call_started";
        }
    }

    /** Once per tool call, after it ran or was rejected. */
    record ToolCallFinished(String label, boolean success) implements AgentEvent {
        public String wireName() {
            return "tool_call_finished";
        }
    }

    /**
     * Once, when the model closes the turn with its final answer — carries
     * everything the Frontend needs to render it, already persisted.
     *
     * @param chatId             the exchange's chat — the only way a caller of
     *                           POST /chats (create-new) learns the new chat's id
     * @param text               the conversational reply the user reads
     * @param resultType         whether {@code recommendedItems} has anything in it
     * @param recommendedItems   resolved, display-ready cards; empty for DIRECT_RESPONSE
     * @param suggestedChatTitle the model's proposed title, if any
     */
    record RunFinished(
        UUID chatId,
        String text,
        AgentFinalAnswer.ResultType resultType,
        List<WinnerCard> recommendedItems,
        String suggestedChatTitle
    ) implements AgentEvent {
        public String wireName() {
            return "run_finished";
        }
    }

    /** The exchange failed — no chat_exchange was ever persisted. */
    record AgentError(String message) implements AgentEvent {
        public String wireName() {
            return "error";
        }
    }
}
