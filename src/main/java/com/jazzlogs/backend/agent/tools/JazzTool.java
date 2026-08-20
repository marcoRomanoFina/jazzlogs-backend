package com.jazzlogs.backend.agent.tools;

import java.util.Map;
import java.util.UUID;

import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;

import com.jazzlogs.backend.agent.ToolCallRequest;
import com.jazzlogs.backend.agent.ToolExecutionResult;

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
    // strict(false): OpenAI's strict mode requires every property to be
    // listed in "required" (optional fields expressed via a nullable type
    // union instead of omission) — none of our schema()s satisfy that today
    // (see e.g. SubmitFinalAnswerTool, where suggestedChatTitle/
    // updatedSessionSummary are genuinely optional), so strict(true) would be
    // rejected by the API. .strict(...) itself is non-optional on the SDK's
    // builder — omitting it entirely throws at build() time, not request time.
    public final FunctionTool toFunctionTool() {
        FunctionTool.Parameters.Builder builder = FunctionTool.Parameters.builder();
        schema().forEach((key, value) -> builder.putAdditionalProperty(key, JsonValue.from(value)));
        return FunctionTool.builder().name(name).description(description).parameters(builder.build()).strict(false).build();
    }

    // userId is the authenticated user driving this chat exchange (see
    // AgentOrchestrator.runLoop, which threads chat.getUserId() through) —
    // not something the model supplies or controls. Most tools ignore it;
    // tools that need per-user context (e.g. graphFilter's excludeListened/
    // excludeAlreadyRated) read it here instead of it living anywhere in
    // ToolCallRequest, which represents only what the model asked for.
    public abstract ToolExecutionResult execute(ToolCallRequest call, UUID userId);

    // --- shared JSON-arg parsing helpers ---
    //
    // Every tool turns the model's raw string args into typed enums the same
    // way; living here once a second tool (GraphFilterTool, SemanticSearchTool)
    // needed the identical logic, instead of each subclass keeping its own copy.

    // A concrete Class<E> literal is required at every call site (never a
    // wildcard-typed variable) — Class<? extends Enum<?>> can't satisfy the
    // <E extends Enum<E>> bound here due to Java's wildcard capture rules.
    protected static <E extends Enum<E>> E parseEnumValue(String raw, Class<E> enumClass, String kind) {
        try {
            return Enum.valueOf(enumClass, raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Unknown " + kind + " value: " + raw);
        }
    }

    // For fields the schema marks "required" — rejects null/blank before
    // even trying to resolve it against the enum, so the error names the
    // missing field instead of reading "Unknown entityType value: null".
    protected static <E extends Enum<E>> E parseRequiredEnum(String raw, Class<E> enumClass, String kind) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(kind + " must not be blank");
        }
        return parseEnumValue(raw, enumClass, kind);
    }
}
