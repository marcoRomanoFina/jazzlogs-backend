package com.jazzlogs.backend.agent;

/**
 * One function/tool call the model made in a turn.
 *
 * @param callId        what the Responses API needs back on {@code
 *                      ResponseInputItem.FunctionCallOutput} to report this
 *                      call's result next turn — not the item's own id
 * @param name          the tool's registered name
 * @param argumentsJson the model's raw, unvalidated arguments
 */
public record ToolCallRequest(String callId, String name, String argumentsJson) {
}
