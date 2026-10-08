package com.jazzlogs.backend.agent.tools;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import com.jazzlogs.backend.agent.ToolCallRequest;
import com.jazzlogs.backend.agent.ToolExecutionResult;
import com.jazzlogs.backend.agent.tools.TrackLogReader.TrackLog;

/**
 * One track, whole: its log from first block to last plus everything else
 * JazzLogs knows about it (see {@link TrackLogReader}). The agent's last step
 * before recommending — it chooses a track with the other tools, then reads
 * it here and writes its answer from that. Deliberately unfiltered: a
 * narrator who has read the entire entry talks about a track better than one
 * who asked for a single paragraph.
 * Keyed by the track, not by the log's own id: a track has exactly one log,
 * so the agent only ever has to carry one id per track.
 */
@Component
public class EditorialContentTool extends JazzTool {

    public static final String NAME = "EDITORIAL_CONTENT";

    private static final Map<String, Object> SCHEMA = Map.of(
        "type", "object",
        "properties", Map.of("trackId", Map.of("type", "string")),
        "required", List.of("trackId")
    );

    private final JsonMapper objectMapper;
    private final TrackLogReader trackLogReader;

    public EditorialContentTool(TrackLogReader trackLogReader, JsonMapper objectMapper) {
        super(
            NAME,
            "Read everything JazzLogs has on one track, given its id — the entityId of a TRACK from "
                + "GRAPH_FILTER, SEMANTIC_SEARCH, or RESOLVE_JAZZLOGS_ENTITY. Returns the track itself "
                + "(name, artists, album and year, length, vocal profile, energy, accessibility, mood "
                + "intensity, tempo feel, composition type, its style/mood/context/rhythm/instrument "
                + "tags, and who plays what on it) and its complete log: title, log number, dek, the "
                + "narrator who wrote it (writtenBy), and every block of its text in reading order, "
                + "each labelled with what it covers (contentCategory). This is the last step before "
                + "recommending: call it once you have decided which track you are recommending, and "
                + "write your answer from what it returns — never invent editorial content. Do not use "
                + "it to compare candidates (that is SEMANTIC_SEARCH's job). Only a track id is valid "
                + "here: an album or artist id is rejected.",
            "Leyendo la editorial"
        );
        this.trackLogReader = trackLogReader;
        this.objectMapper = objectMapper;
    }

    @Override
    protected Map<String, Object> schema() {
        return SCHEMA;
    }

    /** Reads a track and its whole log, telling the model apart an id that isn't a track from one that is. */
    @Override
    public ToolExecutionResult execute(ToolCallRequest call, UUID userId) {
        UUID trackId = requireTrackId(parseArgs(call.argumentsJson()).trackId());

        TrackLog trackLog = trackLogReader.read(trackId).orElseThrow(() -> new IllegalArgumentException(
            "trackId " + trackId + " is not a track in the JazzLogs catalog. An album or artist id is not valid "
                + "here — pass the entityId of a TRACK candidate, or resolve the track by name with "
                + ResolveJazzlogsEntityTool.NAME + "."
        ));

        return new ToolExecutionResult(writeJson(new Output(buildContent(trackLog), trackLog)), true);
    }

    /** Parses the model's raw JSON args, rejecting malformed JSON. */
    private Args parseArgs(String argumentsJson) {
        try {
            return objectMapper.readValue(argumentsJson, Args.class);
        } catch (JacksonException e) {
            throw new IllegalArgumentException(NAME + " arguments were not valid JSON: " + e.getMessage(), e);
        }
    }

    /** Rejects a missing/blank/malformed trackId. */
    private UUID requireTrackId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("trackId must not be blank");
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("trackId is not a valid id: " + raw);
        }
    }

    /** The conversational summary line the model reads alongside the structured payload. */
    private String buildContent(TrackLog trackLog) {
        return "Log #" + trackLog.log().logNumber() + " \"" + trackLog.log().title() + "\" on the track \""
            + trackLog.track().entityName() + "\", written by " + trackLog.log().writtenBy() + " — "
            + trackLog.log().blocks().size() + " block(s).";
    }

    /** Serializes the tool's output — a failure here is our bug, not the model's, hence {@link IllegalStateException}. */
    private String writeJson(Output output) {
        try {
            return objectMapper.writeValueAsString(output);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize " + NAME + " output", e);
        }
    }

    /** The model's raw tool-call arguments, before validation. */
    private record Args(String trackId) {
    }

    /** The tool's full JSON result shape — conversational summary plus the track and its log. */
    private record Output(String content, TrackLog metadata) {
    }
}
