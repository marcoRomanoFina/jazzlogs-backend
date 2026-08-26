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
import com.jazzlogs.backend.editorial.BlockContentCategory;
import com.jazzlogs.backend.editorial.EditorialBlock;
import com.jazzlogs.backend.editorial.EditorialBlockRepository;
import com.jazzlogs.backend.editorial.EditorialBlockType;

/**
 * Full/filtered text of one editorial's blocks — what the agent calls once
 * it already has a concrete editorialId (from RESOLVE_JAZZLOGS_ENTITY) and
 * needs real substance to write from, not just a name. No JOIN to
 * album/track/artist_editorials: editorialId already identifies the
 * editorials row directly, see {@link EditorialBlockRepository}.
 */
@Component
public class EditorialContentTool extends JazzTool {

    public static final String NAME = "EDITORIAL_CONTENT";

    private static final Map<String, Object> SCHEMA = Map.of(
        "type", "object",
        "properties", Map.of(
            "editorialId", Map.of("type", "string"),
            "categories", Map.of(
                "type", "array",
                "items", Map.of("type", "string", "enum", categoryNames())
            )
        ),
        "required", List.of("editorialId")
    );

    private final JsonMapper objectMapper;
    private final EditorialBlockRepository editorialBlockRepository;

    public EditorialContentTool(EditorialBlockRepository editorialBlockRepository, JsonMapper objectMapper) {
        super(
            NAME,
            "Fetch the full or filtered text content of an editorial's blocks, given an editorialId. "
                + "editorialId is NOT the same id as the album/track/artist itself — an entityId from "
                + "GRAPH_FILTER or SEMANTIC_SEARCH is never a valid editorialId, do not reuse one here. "
                + "The only source for a real editorialId is RESOLVE_JAZZLOGS_ENTITY's editorialId field "
                + "— call that tool with the entity's name if you don't already have one from earlier in "
                + "this conversation. Use this to get real substance to write from before answering — "
                + "never invent editorial content, and never guess or reuse an unrelated id here.",
            "Leyendo la editorial"
        );
        this.editorialBlockRepository = editorialBlockRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    protected Map<String, Object> schema() {
        return SCHEMA;
    }

    /** Fetches an editorial's blocks, optionally filtered to specific content categories. */
    @Override
    public ToolExecutionResult execute(ToolCallRequest call, UUID userId) {
        Args args = parseArgs(call.argumentsJson());
        UUID editorialId = requireEditorialId(args.editorialId());
        List<BlockContentCategory> categories = parseEnumList(args.categories(), BlockContentCategory.class, "category");

        List<EditorialBlock> blocks = categories.isEmpty()
            ? editorialBlockRepository.findByEditorialIdOrderByPositionAsc(editorialId)
            : editorialBlockRepository.findByEditorialIdAndContentCategoryInOrderByPositionAsc(editorialId, categories);

        List<Block> blockDtos = blocks.stream().map(EditorialContentTool::toBlock).toList();
        Output output = new Output(buildContent(editorialId, blockDtos), new Metadata(editorialId, blockDtos));
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

    /** Rejects a missing/blank/malformed editorialId. */
    private UUID requireEditorialId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("editorialId must not be blank");
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("editorialId is not a valid id: " + raw);
        }
    }

    /** Projects one persistence-layer {@link EditorialBlock} into the tool's output shape. */
    private static Block toBlock(EditorialBlock block) {
        return new Block(block.getId(), block.getType(), block.getContentCategory(), block.getSubhead(), block.getText(), block.getPosition());
    }

    /** The conversational summary line the model reads alongside the structured blocks. */
    private String buildContent(UUID editorialId, List<Block> blocks) {
        if (blocks.isEmpty()) {
            return "No blocks found for editorial " + editorialId + ".";
        }
        return "Retrieved " + blocks.size() + " block(s) for editorial " + editorialId + ".";
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
    private record Args(String editorialId, List<String> categories) {
    }

    /** One editorial block, projected from {@link EditorialBlock}. */
    private record Block(UUID id, EditorialBlockType type, BlockContentCategory contentCategory, String subhead, String text, int position) {
    }

    /** The tool's structured payload, alongside {@link #buildContent}'s summary. */
    private record Metadata(UUID editorialId, List<Block> blocks) {
    }

    /** The tool's full JSON result shape — conversational summary plus structured metadata. */
    private record Output(String content, Metadata metadata) {
    }
}
