package com.jazzlogs.backend.agent;

// One function/tool call the model made in a turn. callId (not the item's own
// id) is what the Responses API needs back on ResponseInputItem.FunctionCallOutput
// to report this call's result on the next turn.
public record ToolCallRequest(String callId, String name, String argumentsJson) {
}
