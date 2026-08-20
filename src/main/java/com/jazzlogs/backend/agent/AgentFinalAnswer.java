package com.jazzlogs.backend.agent;

import java.util.List;

import com.jazzlogs.backend.chat.chatexchange.CatalogRef;

// Structured argument shape of the submit_final_answer tool call — see
// SubmitFinalAnswerTool for the JSON schema sent to the model. Parsed
// straight from that call's arguments JSON (see AgentOrchestrator).
// recommendedItems reuses chatexchange.CatalogRef (not its own nested type): it's
// the same raw, unresolved (type, id) shape ChatExchangeService.persist
// expects — an id the model invented, or that isn't a valid UUID, is
// silently dropped there, never trusted as-is.
//
// answerText carries the actual conversational reply as a required tool
// argument, not as separate message-level text alongside the call — a model
// can call a function with zero accompanying message content (common
// tool-calling behavior, not a violation of anything), which used to mean
// submit_final_answer could close a turn with no visible answer at all. See
// AgentOrchestrator.finalizeExchange for the fallback chain this still sits
// behind (turn.assistantText(), then the last non-blank text from an
// earlier iteration) for the rare case a model still leaves this blank.
public record AgentFinalAnswer(
    ResultType resultType,
    String answerText,
    List<CatalogRef> recommendedItems,
    String suggestedChatTitle,
    String updatedSessionSummary
) {

    public enum ResultType {
        DIRECT_RESPONSE,
        CATALOG_RESPONSE
    }
}
