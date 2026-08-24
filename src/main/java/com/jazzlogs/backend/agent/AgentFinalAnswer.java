package com.jazzlogs.backend.agent;

import java.util.List;

import com.jazzlogs.backend.chat.chatexchange.CatalogReference;

// The model's final answer — structured text output (see
// OpenAiResponsesStreamClient's text.format json_schema), not a tool call.
// A turn with no tool calls (see Step) is, by construction, the
// model closing with one of these; its raw assistantText is exactly this
// record's JSON, parsed straight off by AgentOrchestrator.finalizeExchange.
//
// recommendedItems reuses chatexchange.CatalogReference (not its own nested type): it's
// the same raw, unresolved (type, id) shape ChatExchangeService.persist
// expects — an id the model invented, or that isn't a valid UUID, is
// silently dropped there, never trusted as-is.
public record AgentFinalAnswer(
    ResultType resultType,
    String answerText,
    List<CatalogReference> recommendedItems,
    String suggestedChatTitle,
    String updatedSessionSummary
) {

    public enum ResultType {
        DIRECT_RESPONSE,
        CATALOG_RESPONSE
    }
}
