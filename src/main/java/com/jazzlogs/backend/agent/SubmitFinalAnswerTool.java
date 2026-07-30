package com.jazzlogs.backend.agent;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

// The one JazzTool defined so far — real catalog tools (SEMANTIC_CATALOG_SEARCH,
// FILTER_CATALOG, etc.) are a later task. Unlike those, this one never goes
// through AgentOrchestrator's normal dispatch: it's how the model closes a
// turn, intercepted via turn.findToolCall(NAME) before any tool call is
// executed (see AgentOrchestrator.runLoop) — its plain-text output IS the
// user-facing answer (see AgentPromptTemplates' FINAL OUTPUT CONTRACT), this
// call only attaches the structured metadata alongside it. execute() is
// unreachable in practice; it throws so a wiring mistake fails loudly instead
// of silently no-opping.
@Component
public class SubmitFinalAnswerTool extends JazzTool {

    public static final String NAME = "submit_final_answer";

    private static final Map<String, Object> SCHEMA = Map.of(
        "type", "object",
        "properties", Map.of(
            "resultType", Map.of("type", "string", "enum", List.of("DIRECT_RESPONSE", "CATALOG_RESPONSE")),
            "recommendedItems", Map.of(
                "type", "array",
                "items", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "type", Map.of("type", "string", "enum", List.of("ALBUM", "TRACK", "ARTIST")),
                        "id", Map.of("type", "string")
                    ),
                    "required", List.of("type", "id")
                )
            ),
            "suggestedChatTitle", Map.of("type", List.of("string", "null")),
            "updatedSessionSummary", Map.of("type", List.of("string", "null"))
        ),
        "required", List.of("resultType", "recommendedItems")
    );

    public SubmitFinalAnswerTool() {
        super(
            NAME,
            "Call this when your conversational answer is complete, to attach the structured "
                + "recommendation metadata. Never call any other tool in the same turn as this one."
        );
    }

    @Override
    protected Map<String, Object> schema() {
        return SCHEMA;
    }

    @Override
    public ToolExecutionResult execute(ToolCallRequest call) {
        throw new UnsupportedOperationException(
            NAME + " is intercepted by AgentOrchestrator before dispatch and is never executed directly"
        );
    }
}
