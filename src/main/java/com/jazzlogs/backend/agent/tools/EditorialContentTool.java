package com.jazzlogs.backend.agent.tools;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.jazzlogs.backend.agent.ToolCallRequest;
import com.jazzlogs.backend.agent.ToolExecutionResult;
import com.jazzlogs.backend.editorial.BlockContentCategory;
import com.jazzlogs.backend.editorial.EditorialBlock;
import com.jazzlogs.backend.editorial.EditorialBlockRepository;
import com.jazzlogs.backend.editorial.EditorialBlockType;

// Full/filtered text of one editorial's blocks — what the agent calls once
// it already has a concrete editorialId (from RESOLVE_JAZZLOGS_ENTITY) and
// needs real substance to write from, not just a name. No JOIN to
// album/track/artist_editorials: editorialId already identifies the
// editorials row directly, see EditorialBlockRepository.
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

    // Not Spring-managed — same reasoning as AgentOrchestrator.OBJECT_MAPPER.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final EditorialBlockRepository editorialBlockRepository;

    public EditorialContentTool(EditorialBlockRepository editorialBlockRepository) {
        super(
            NAME,
            "Fetch the full or filtered text content of an editorial's blocks, given an editorialId. "
                + "editorialId is NOT the same id as the album/track/artist itself — an entityId from "
                + "GRAPH_FILTER or SEMANTIC_SEARCH is never a valid editorialId, do not reuse one here. "
                + "The only source for a real editorialId is RESOLVE_JAZZLOGS_ENTITY's editorialId field "
                + "— call that tool with the entity's name if you don't already have one from earlier in "
                + "this conversation. Use this to get real substance to write from before answering — "
                + "never invent editorial content, and never guess or reuse an unrelated id here."
        );
        this.editorialBlockRepository = editorialBlockRepository;
    }

    @Override
    protected Map<String, Object> schema() {
        return SCHEMA;
    }

    @Override
    public ToolExecutionResult execute(ToolCallRequest call, UUID userId) {
        Args args = parseArgs(call.argumentsJson());
        UUID editorialId = requireEditorialId(args.editorialId());
        List<BlockContentCategory> categories = parseCategories(args.categories());

        List<EditorialBlock> blocks = categories.isEmpty()
            ? editorialBlockRepository.findByEditorialIdOrderByPositionAsc(editorialId)
            : editorialBlockRepository.findByEditorialIdAndContentCategoryInOrderByPositionAsc(editorialId, categories);

        List<Block> blockDtos = blocks.stream().map(EditorialContentTool::toBlock).toList();
        Output output = new Output(buildContent(editorialId, blockDtos), new Metadata(editorialId, blockDtos));
        return new ToolExecutionResult(writeJson(output), true);
    }

    private Args parseArgs(String argumentsJson) {
        try {
            return OBJECT_MAPPER.readValue(argumentsJson, Args.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(NAME + " arguments were not valid JSON: " + e.getMessage(), e);
        }
    }

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

    private List<BlockContentCategory> parseCategories(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream().map(this::parseCategory).toList();
    }

    private BlockContentCategory parseCategory(String raw) {
        try {
            return BlockContentCategory.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Unknown BlockContentCategory: " + raw);
        }
    }

    private static Block toBlock(EditorialBlock block) {
        return new Block(block.getId(), block.getType(), block.getContentCategory(), block.getSubhead(), block.getText(), block.getPosition());
    }

    private String buildContent(UUID editorialId, List<Block> blocks) {
        if (blocks.isEmpty()) {
            return "No blocks found for editorial " + editorialId + ".";
        }
        return "Retrieved " + blocks.size() + " block(s) for editorial " + editorialId + ".";
    }

    private String writeJson(Output output) {
        try {
            return OBJECT_MAPPER.writeValueAsString(output);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize " + NAME + " output", e);
        }
    }

    private static List<String> categoryNames() {
        return Arrays.stream(BlockContentCategory.values()).map(Enum::name).toList();
    }

    private record Args(String editorialId, List<String> categories) {
    }

    private record Block(UUID id, EditorialBlockType type, BlockContentCategory contentCategory, String subhead, String text, int position) {
    }

    private record Metadata(UUID editorialId, List<Block> blocks) {
    }

    private record Output(String content, Metadata metadata) {
    }
}
