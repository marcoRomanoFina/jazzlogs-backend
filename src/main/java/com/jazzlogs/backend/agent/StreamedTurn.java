package com.jazzlogs.backend.agent;

import java.util.List;

import com.jazzlogs.backend.agent.tools.SubmitFinalAnswerTool;

// One fully-resolved turn of the Responses API stream — OpenAiResponsesStreamClient
// only hands this back once the turn is complete (id, full assistant text,
// every tool call), never partial/incremental state.
public record StreamedTurn(String responseId, String assistantText, List<ToolCallRequest> toolCalls) {

    public ToolCallRequest findToolCall(String name) {
        return toolCalls.stream().filter(call -> call.name().equals(name)).findFirst().orElse(null);
    }

    public List<ToolCallRequest> otherToolCalls() {
        return toolCalls.stream().filter(call -> !call.name().equals(SubmitFinalAnswerTool.NAME)).toList();
    }
}
