package com.jazzlogs.backend.agent;

/**
 * What a {@code JazzTool} hands back to {@link JazzlogsAgent} after running.
 *
 * @param payload the raw JSON sent back to the model as this call's {@code
 *                function_call_output} — always JSON, whether the tool
 *                succeeded or failed (a failure's payload is an {@code
 *                {"error": "..."}} object, not an exception)
 * @param success only drives the frontend's {@code ToolCallFinished} event —
 *                the model reads {@code payload} either way, it never sees
 *                this flag directly
 */
public record ToolExecutionResult(String payload, boolean success) {
}
