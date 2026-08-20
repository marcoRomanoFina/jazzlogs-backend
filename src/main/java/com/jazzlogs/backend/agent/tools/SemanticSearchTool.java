package com.jazzlogs.backend.agent.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.jazzlogs.backend.agent.ToolCallRequest;
import com.jazzlogs.backend.agent.ToolExecutionResult;
import com.jazzlogs.backend.album.Level;
import com.jazzlogs.backend.chat.CatalogItemType;
import com.jazzlogs.backend.editorial.BlockContentCategory;
import com.jazzlogs.backend.semanticsearch.ScoredBlock;
import com.jazzlogs.backend.semanticsearch.SemanticSearchRequest;
import com.jazzlogs.backend.semanticsearch.SemanticSearchResult;
import com.jazzlogs.backend.semanticsearch.SemanticSearchService;

// Semantic (pgvector) search over editorial_blocks — the second, independent
// half of a two-tool pipeline with GRAPH_FILTER. Standalone: candidateIds is
// explicit input, not a shared search/session id, so the model can call
// this on its own (with a candidate set it built itself) or chain it right
// after GRAPH_FILTER by copying that tool's entityIds — entityType here
// should match whichever entityType that GRAPH_FILTER call used, since a
// candidate set is expected to already be homogeneous. No rerank/fusion
// step here or anywhere server-side — the model sees similarityScore (and
// matchedDimensions, if it used GRAPH_FILTER first) side by side and
// synthesizes the final answer itself.
@Component
public class SemanticSearchTool extends JazzTool {

    public static final String NAME = "SEMANTIC_SEARCH";

    private static final Map<String, Object> SCHEMA = Map.of(
        "type", "object",
        "properties", Map.of(
            "entityType", Map.of("type", "string", "enum", List.of("ALBUM", "TRACK", "ARTIST")),
            "candidateIds", Map.of("type", "array", "items", Map.of("type", "string")),
            "category", Map.of("type", "string", "enum", categoryNames()),
            "energy", Map.of("type", List.of("string", "null"), "enum", levelNamesOrNull()),
            "accessibility", Map.of("type", List.of("string", "null"), "enum", levelNamesOrNull()),
            "moodIntensity", Map.of("type", List.of("string", "null"), "enum", levelNamesOrNull()),
            "queryText", Map.of(
                "type", "string",
                "description", "What actually gets embedded and compared against real editorial prose — "
                    + "phrase it like a snippet describing the music itself (mood, atmosphere, character, "
                    + "instrumentation), the way a review would, not like an instruction to a writer. Only "
                    + "include what the user actually indicated (e.g. \"vocal jazz album\" for a bare "
                    + "request with no mood/energy/context mentioned) — do not invent mood, atmosphere, or "
                    + "character details the user never gave you just to make the query feel fuller; that's "
                    + "the same fabrication KNOWLEDGE SOURCE RULE forbids elsewhere, applied here too. Do "
                    + "not pad it with meta-phrasing like \"recommend this as...\" or \"describe the...\" "
                    + "either — both kinds of padding hurt the match, they don't help it."
            )
        ),
        "required", List.of("entityType", "candidateIds", "category", "queryText")
    );

    // Not Spring-managed — same reasoning as AgentOrchestrator.OBJECT_MAPPER.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SemanticSearchService semanticSearchService;

    public SemanticSearchTool(SemanticSearchService semanticSearchService) {
        super(
            NAME,
            "Semantically rank editorial content blocks against a query, scoped to candidateIds of ONE "
                + "entityType (ALBUM, TRACK, or ARTIST) and ONE content category per call (make separate "
                + "calls for more than one type or category). Use this after GRAPH_FILTER to write from "
                + "real text instead of inventing it, or standalone with a candidate set you already have. "
                + "An empty candidateIds list returns no matches without erroring. energy/accessibility/"
                + "moodIntensity are optional extra filters that only apply when entityType is ALBUM or "
                + "TRACK — they're ignored for ARTIST."
        );
        this.semanticSearchService = semanticSearchService;
    }

    @Override
    protected Map<String, Object> schema() {
        return SCHEMA;
    }

    @Override
    public ToolExecutionResult execute(ToolCallRequest call, UUID userId) {
        Args args = parseArgs(call.argumentsJson());
        SemanticSearchRequest request = new SemanticSearchRequest(
            parseRequiredEnum(args.entityType(), CatalogItemType.class, "entityType"),
            parseCandidateIds(args.candidateIds()),
            parseRequiredEnum(args.category(), BlockContentCategory.class, "category"),
            parseLevel(args.energy(), "energy"),
            parseLevel(args.accessibility(), "accessibility"),
            parseLevel(args.moodIntensity(), "moodIntensity"),
            requireQueryText(args.queryText())
        );

        SemanticSearchResult result = semanticSearchService.search(request);

        Output output = new Output(buildContent(result.matches()), new Metadata(result.matches()));
        return new ToolExecutionResult(writeJson(output), true);
    }

    private Args parseArgs(String argumentsJson) {
        try {
            return OBJECT_MAPPER.readValue(argumentsJson, Args.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(NAME + " arguments were not valid JSON: " + e.getMessage(), e);
        }
    }

    // candidateIds must be present (possibly empty) — see
    // SemanticSearchRequest's doc on why null is rejected here rather than
    // treated the same as empty.
    private List<UUID> parseCandidateIds(List<String> raw) {
        if (raw == null) {
            throw new IllegalArgumentException("candidateIds must not be null");
        }
        return raw.stream().map(this::parseUuid).toList();
    }

    private UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("candidateIds entry must not be blank");
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("candidateIds entry is not a valid id: " + raw);
        }
    }

    private Level parseLevel(String raw, String kind) {
        return raw == null ? null : parseEnumValue(raw, Level.class, kind);
    }

    private String requireQueryText(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("queryText must not be blank");
        }
        return raw;
    }

    // parseEnumValue/parseRequiredEnum are inherited from JazzTool — shared
    // with GraphFilterTool, which needs the identical entityType parsing.

    private String buildContent(List<ScoredBlock> matches) {
        if (matches.isEmpty()) {
            return "No semantically similar blocks found for the given candidates/category.";
        }
        return "Found " + matches.size() + " matching block(s), ranked by similarity.";
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

    // Includes a null entry so the JSON Schema's enum stays valid alongside
    // "type": ["string", "null"] — List.of(...) can't hold null, hence the
    // explicit ArrayList build here instead of reusing a List.of(...)-based
    // helper like categoryNames().
    private static List<String> levelNamesOrNull() {
        List<String> names = new ArrayList<>(Arrays.stream(Level.values()).map(Enum::name).toList());
        names.add(null);
        return names;
    }

    private record Args(
        String entityType,
        List<String> candidateIds,
        String category,
        String energy,
        String accessibility,
        String moodIntensity,
        String queryText
    ) {
    }

    private record Metadata(List<ScoredBlock> matches) {
    }

    private record Output(String content, Metadata metadata) {
    }
}
