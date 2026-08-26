package com.jazzlogs.backend.agent.tools;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;

import com.jazzlogs.backend.agent.ToolCallRequest;
import com.jazzlogs.backend.agent.ToolExecutionResult;

/**
 * Base class for every tool the agent can call. Each concrete tool owns both
 * halves of its own identity — the JSON schema sent to the Responses API AND
 * the execution logic that runs when the model calls it — instead of those
 * two concerns living in separate places. Spring collects every
 * {@code @Component} that extends this into a single {@code List<JazzTool>},
 * injected as-is into both {@code OpenAiResponsesStreamClient} (to build the
 * request's tool list) and {@code JazzlogsAgent} (to dispatch a tool call by
 * name) — adding a new tool is just adding a new subclass, no other wiring
 * changes.
 */
public abstract class JazzTool {

    private final String name;
    private final String description;
    private final String displayLabel;

    /**
     * @param displayLabel what {@code JazzlogsAgent} shows the frontend while
     *                     this tool is running (a ToolCallStarted/Finished
     *                     event's label) — required here, not a
     *                     {@code Map<String, String>} kept separately that
     *                     someone has to remember to update for every new
     *                     tool, same reasoning as name/description already
     *                     being owned by the tool itself
     */
    protected JazzTool(String name, String description, String displayLabel) {
        this.name = name;
        this.description = description;
        this.displayLabel = displayLabel;
    }

    public final String name() {
        return name;
    }

    public final String displayLabel() {
        return displayLabel;
    }

    protected abstract Map<String, Object> schema();

    /**
     * Raw JSON Schema -> {@code FunctionTool.Parameters} via {@code
     * JsonValue.from}, built once here so subclasses only ever declare their
     * {@link #schema()} as a plain Map. Uses {@code strict(false)}: OpenAI's
     * strict mode requires every property to be listed in "required"
     * (optional fields expressed via a nullable type union instead of
     * omission) — none of our {@code schema()}s satisfy that today (e.g.
     * {@code GraphFilterTool}'s vocabulary filters are genuinely optional),
     * so {@code strict(true)} would be rejected by the API. {@code
     * .strict(...)} itself is non-optional on the SDK's builder — omitting
     * it entirely throws at build() time, not request time. Compare {@code
     * OpenAiResponsesStreamClient}'s {@code FINAL_ANSWER_SCHEMA}, which does
     * satisfy strict mode's requirement and uses {@code strict(true)}
     * accordingly.
     */
    public final FunctionTool toFunctionTool() {
        FunctionTool.Parameters.Builder builder = FunctionTool.Parameters.builder();
        schema().forEach((key, value) -> builder.putAdditionalProperty(key, JsonValue.from(value)));
        return FunctionTool.builder().name(name).description(description).parameters(builder.build()).strict(false).build();
    }

    /**
     * @param userId the authenticated user driving this chat exchange (see
     *               {@code JazzlogsAgent.run}, which threads {@code
     *               chat.getUserId()} through) — not something the model
     *               supplies or controls. Most tools ignore it; tools that
     *               need per-user context (e.g. graphFilter's
     *               excludeListened/excludeAlreadyRated) read it here
     *               instead of it living anywhere in {@link ToolCallRequest},
     *               which represents only what the model asked for
     */
    public abstract ToolExecutionResult execute(ToolCallRequest call, UUID userId);

    // --- shared JSON-arg parsing helpers ---
    //
    // Every tool turns the model's raw string args into typed enums the same
    // way; living here once a second tool (GraphFilterTool, SemanticSearchTool)
    // needed the identical logic, instead of each subclass keeping its own copy.

    /**
     * A concrete {@code Class<E>} literal is required at every call site
     * (never a wildcard-typed variable) — {@code Class<? extends Enum<?>>}
     * can't satisfy the {@code <E extends Enum<E>>} bound here due to Java's
     * wildcard capture rules.
     */
    protected static <E extends Enum<E>> E parseEnumValue(String raw, Class<E> enumClass, String kind) {
        try {
            return Enum.valueOf(enumClass, raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Unknown " + kind + " value: " + raw);
        }
    }

    /**
     * For fields the schema marks "required" — rejects null/blank before
     * even trying to resolve it against the enum, so the error names the
     * missing field instead of reading "Unknown entityType value: null".
     */
    protected static <E extends Enum<E>> E parseRequiredEnum(String raw, Class<E> enumClass, String kind) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(kind + " must not be blank");
        }
        return parseEnumValue(raw, enumClass, kind);
    }

    /**
     * For fields the schema marks as an optional array — a missing list
     * means "no filter", not an error; each element that IS present is still
     * validated against enumClass via {@link #parseEnumValue}.
     */
    protected static <E extends Enum<E>> List<E> parseEnumList(List<String> raw, Class<E> enumClass, String kind) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream().map(code -> parseEnumValue(code, enumClass, kind)).toList();
    }
}
