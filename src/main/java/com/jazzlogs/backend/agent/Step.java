package com.jazzlogs.backend.agent;

import java.util.List;

/**
 * One fully-resolved turn of the Responses API stream — {@link
 * OpenAiResponsesStreamClient} only hands this back once the turn is
 * complete (id, full assistant text, every tool call), never partial/
 * incremental state. Empty {@code toolCalls} means the model answered with
 * text instead of calling anything — under structured {@code text.format},
 * that text is always the final answer's JSON (see {@link AgentFinalAnswer}),
 * so {@link JazzlogsAgent#run} treats {@code toolCalls().isEmpty()} as "the
 * turn closed", nothing more to check.
 */
public record Step(String responseId, String assistantText, List<ToolCallRequest> toolCalls) {
}
