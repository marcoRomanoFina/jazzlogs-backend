package com.jazzlogs.backend.agent;

import java.util.Map;

import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;

// Base class for every tool the agent can call. Each concrete tool owns both
// halves of its own identity — the JSON schema sent to the Responses API AND
// the execution logic that runs when the model calls it — instead of those
// two concerns living in separate places. Spring collects every @Component
// that extends this into a single List<JazzTool>, injected as-is into both
// OpenAiResponsesStreamClient (to build the request's tool list) and
// AgentOrchestrator (to dispatch a tool call by name) — adding a new tool is
// just adding a new subclass, no other wiring changes.
public abstract class JazzTool {

    private final String name;
    private final String description;

    protected JazzTool(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public final String name() {
        return name;
    }

    protected abstract Map<String, Object> schema();

    // Raw JSON Schema -> FunctionTool.Parameters via JsonValue.from, built once
    // here so subclasses only ever declare their schema() as a plain Map.
    public final FunctionTool toFunctionTool() {
        FunctionTool.Parameters.Builder builder = FunctionTool.Parameters.builder();
        schema().forEach((key, value) -> builder.putAdditionalProperty(key, JsonValue.from(value)));
        return FunctionTool.builder().name(name).description(description).parameters(builder.build()).build();
    }

    public abstract ToolExecutionResult execute(ToolCallRequest call);
}
