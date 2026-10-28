package com.jazzlogs.backend.agent;

import java.util.List;

/**
 * One fully-resolved turn of the Responses API stream — never partial state.
 * Empty {@code toolCalls} means the model closed with its final answer
 * instead of calling anything.
 *
 * @param responseId    chains the next turn via {@code previousResponseId}
 * @param assistantText the model's text; under structured output, this IS
 *                      the final answer's JSON (see {@link AgentFinalAnswer})
 *                      whenever {@code toolCalls} is empty
 * @param toolCalls     the tool calls the model requested this turn, if any
 * @param usage         what the call cost in tokens; {@link TokenUsage#NONE} if not reported
 */
public record Step(String responseId, String assistantText, List<ToolCallRequest> toolCalls, TokenUsage usage) {

    /** A step whose token usage is not known. */
    public Step(String responseId, String assistantText, List<ToolCallRequest> toolCalls) {
        this(responseId, assistantText, toolCalls, TokenUsage.NONE);
    }
}
