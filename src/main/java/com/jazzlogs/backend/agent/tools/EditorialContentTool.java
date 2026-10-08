package com.jazzlogs.backend.agent.tools;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import com.jazzlogs.backend.agent.ToolCallRequest;
import com.jazzlogs.backend.agent.ToolExecutionResult;
import com.jazzlogs.backend.character.JazzlogsCharacter;
import com.jazzlogs.backend.editorial.BlockContentCategory;
import com.jazzlogs.backend.editorial.EditorialBlock;
import com.jazzlogs.backend.editorial.EditorialBlockRepository;
import com.jazzlogs.backend.editorial.EditorialBlockType;

/**
 * Full/filtered text of one track's log — what the agent calls once it has a
 * track id (from any other tool) and needs real substance to write from, not
 * just a name. Keyed by the track, not by the log's own id: a track has at
 * most one log, so the agent only ever has to carry one id per track.
 */
@Component
public class EditorialContentTool extends JazzTool {

    public static final String NAME = "EDITORIAL_CONTENT";

    private static final Map<String, Object> SCHEMA = Map.of(
        "type", "object",
        "properties", Map.of(
            "trackId", Map.of("type", "string"),
            "categories", Map.of(
                "type", "array",
                "items", Map.of("type", "string", "enum", categoryNames())
            )
        ),
        "required", List.of("trackId")
    );

    private final JsonMapper objectMapper;
    private final EditorialBlockRepository editorialBlockRepository;
    private final LogAuthorLookup logAuthorLookup;

    public EditorialContentTool(
        EditorialBlockRepository editorialBlockRepository, LogAuthorLookup logAuthorLookup, JsonMapper objectMapper
    ) {
        super(
            NAME,
            "Fetch the text of a track's log — all of its blocks, or only some categories — given the "
                + "track's id: the entityId of a TRACK from GRAPH_FILTER, SEMANTIC_SEARCH, or "
                + "RESOLVE_JAZZLOGS_ENTITY. Also says which narrator wrote the log (writtenBy). Use this "
                + "to get real substance to write from before answering — never invent editorial "
                + "content. A track with no log yet returns no blocks and a null writtenBy; an album or "
                + "artist id is not valid here.",
            "Leyendo la editorial"
        );
        this.editorialBlockRepository = editorialBlockRepository;
        this.logAuthorLookup = logAuthorLookup;
        this.objectMapper = objectMapper;
    }

    @Override
    protected Map<String, Object> schema() {
        return SCHEMA;
    }

    /** Fetches a track's log blocks, optionally filtered to specific content categories. */
    @Override
    public ToolExecutionResult execute(ToolCallRequest call, UUID userId) {
        Args args = parseArgs(call.argumentsJson());
        UUID trackId = requireTrackId(args.trackId());
        List<BlockContentCategory> categories = parseEnumList(args.categories(), BlockContentCategory.class, "category");

        List<EditorialBlock> blocks = categories.isEmpty()
            ? editorialBlockRepository.findByTrackEditorialTrackIdOrderByPositionAsc(trackId)
            : editorialBlockRepository.findByTrackEditorialTrackIdAndContentCategoryInOrderByPositionAsc(trackId, categories);

        List<Block> blockDtos = blocks.stream().map(EditorialContentTool::toBlock).toList();
        JazzlogsCharacter writtenBy = logAuthorLookup.byTrackIds(List.of(trackId)).get(trackId);
        Output output = new Output(buildContent(trackId, blockDtos), new Metadata(trackId, writtenBy, blockDtos));
        return new ToolExecutionResult(writeJson(output), true);
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

    /** Projects one persistence-layer {@link EditorialBlock} into the tool's output shape. */
    private static Block toBlock(EditorialBlock block) {
        return new Block(block.getId(), block.getType(), block.getContentCategory(), block.getSubhead(), block.getText(), block.getPosition());
    }

    /** The conversational summary line the model reads alongside the structured blocks. */
    private String buildContent(UUID trackId, List<Block> blocks) {
        if (blocks.isEmpty()) {
            return "No log blocks found for track " + trackId + ".";
        }
        return "Retrieved " + blocks.size() + " log block(s) for track " + trackId + ".";
    }

    /** Serializes the tool's output — a failure here is our bug, not the model's, hence {@link IllegalStateException}. */
    private String writeJson(Output output) {
        try {
            return objectMapper.writeValueAsString(output);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize " + NAME + " output", e);
        }
    }

    /** Every {@link BlockContentCategory} name — exposed to the model as the {@code categories} field's schema enum. */
    private static List<String> categoryNames() {
        return Arrays.stream(BlockContentCategory.values()).map(Enum::name).toList();
    }

    /** The model's raw tool-call arguments, before validation. */
    private record Args(String trackId, List<String> categories) {
    }

    /** One editorial block, projected from {@link EditorialBlock}. */
    private record Block(UUID id, EditorialBlockType type, BlockContentCategory contentCategory, String subhead, String text, int position) {
    }

    /** The tool's structured payload, alongside {@link #buildContent}'s summary. */
    private record Metadata(UUID trackId, JazzlogsCharacter writtenBy, List<Block> blocks) {
    }

    /** The tool's full JSON result shape — conversational summary plus structured metadata. */
    private record Output(String content, Metadata metadata) {
    }
}
