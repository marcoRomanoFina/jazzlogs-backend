package com.jazzlogs.backend.agent;

import java.util.List;

import com.jazzlogs.backend.chat.chatexchange.CatalogReference;

/**
 * The model's final answer — structured text output, not a tool call. A
 * {@link Step} with no tool calls is, by construction, the model closing
 * with one of these; its raw {@code assistantText} is exactly this record's
 * JSON, parsed straight off by {@link JazzlogsAgent#finalizeExchange}.
 *
 * @param resultType             whether {@code recommendedItems} has anything real in it
 * @param answerText             the conversational reply the user reads
 * @param recommendedItems       raw, unresolved {@link CatalogReference}s — same
 *                                shape {@code ChatExchangeService.persist} expects;
 *                                an invented or malformed id is silently dropped there
 * @param suggestedChatTitle     the model's proposed title, applied only once
 * @param updatedSessionSummary  the model's updated cumulative session summary
 */
public record AgentFinalAnswer(
    ResultType resultType,
    String answerText,
    List<CatalogReference> recommendedItems,
    String suggestedChatTitle,
    String updatedSessionSummary
) {

    /** Whether the turn recommends real catalog items or is just conversational. */
    public enum ResultType {
        DIRECT_RESPONSE,
        CATALOG_RESPONSE
    }
}
