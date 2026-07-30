package com.jazzlogs.backend.agent;

import java.util.List;

import com.jazzlogs.backend.chat.CatalogRef;

// Structured argument shape of the submit_final_answer tool call — see
// SubmitFinalAnswerTool for the JSON schema sent to the model. Parsed
// straight from that call's arguments JSON (see AgentOrchestrator).
// recommendedItems reuses chat.CatalogRef (not its own nested type): it's
// the same raw, unresolved (type, id) shape ChatExchangeService.persist
// expects — an id the model invented, or that isn't a valid UUID, is
// silently dropped there, never trusted as-is.
public record AgentFinalAnswer(
    ResultType resultType,
    List<CatalogRef> recommendedItems,
    String suggestedChatTitle,
    String updatedSessionSummary
) {

    public enum ResultType {
        DIRECT_RESPONSE,
        CATALOG_RESPONSE
    }
}
