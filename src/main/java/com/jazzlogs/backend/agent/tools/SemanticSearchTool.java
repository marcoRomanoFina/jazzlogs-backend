package com.jazzlogs.backend.agent.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import com.jazzlogs.backend.agent.ToolCallRequest;
import com.jazzlogs.backend.agent.ToolExecutionResult;
import com.jazzlogs.backend.album.Level;
import com.jazzlogs.backend.chat.CatalogItemType;
import com.jazzlogs.backend.editorial.BlockContentCategory;
import com.jazzlogs.backend.semanticsearch.ScoredBlock;
import com.jazzlogs.backend.semanticsearch.SemanticSearchRequest;
import com.jazzlogs.backend.semanticsearch.SemanticSearchResult;
import com.jazzlogs.backend.semanticsearch.SemanticSearchService;

/**
 * Semantic (pgvector) search over editorial_blocks — the second, independent
 * half of a two-tool pipeline with GRAPH_FILTER. Standalone: candidateIds is
 * explicit input, not a shared search/session id, so the model can call this
 * on its own (with a candidate set it built itself) or chain it right after
 * GRAPH_FILTER by copying that tool's entityIds — entityType here should
 * match whichever entityType that GRAPH_FILTER call used, since a candidate
 * set is expected to already be homogeneous. No rerank/fusion step here or
 * anywhere server-side — the model sees similarityScore (and
 * matchedDimensions, if it used GRAPH_FILTER first) side by side and
 * synthesizes the final answer itself.
 */
@Component
public class SemanticSearchTool extends JazzTool {

    public static final String NAME = "SEMANTIC_SEARCH";

    private static final Map<String, Object> SCHEMA = Map.of(
        "type", "object",
        "properties", Map.of(
            "entityType", Map.of("type", "string", "enum", List.of("ALBUM", "TRACK", "ARTIST")),
            "candidateIds", Map.of("type", "array", "items", Map.of("type", "string"), "maxItems", SemanticSearchService.MAX_MATCHES),
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

    private final JsonMapper objectMapper;
    private final SemanticSearchService semanticSearchService;

    public SemanticSearchTool(SemanticSearchService semanticSearchService, JsonMapper objectMapper) {
        super(
            NAME,
            "Semantically rank editorial content blocks against a query, scoped to candidateIds of ONE "
                + "entityType (ALBUM, TRACK, or ARTIST) and ONE content category per call (make separate "
                + "calls for more than one type or category). Use this after GRAPH_FILTER to write from "
                + "real text instead of inventing it, or standalone with a candidate set you already have. "
                + "An empty candidateIds list returns no matches without erroring. energy/accessibility/"
                + "moodIntensity are optional extra filters that only apply when entityType is ALBUM or "
                + "TRACK — they're ignored for ARTIST.",
            "Buscando en las editoriales"
        );
        this.semanticSearchService = semanticSearchService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected Map<String, Object> schema() {
        return SCHEMA;
    }

    /** Ranks candidateIds' editorial blocks of one category by similarity to queryText. */
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

    /** Parses the model's raw JSON args, rejecting malformed JSON. */
    private Args parseArgs(String argumentsJson) {
        try {
            return objectMapper.readValue(argumentsJson, Args.class);
        } catch (JacksonException e) {
            throw new IllegalArgumentException(NAME + " arguments were not valid JSON: " + e.getMessage(), e);
        }
    }

    /** candidateIds must be present (possibly empty) — see {@link SemanticSearchRequest}'s doc on why null is rejected here rather than treated the same as empty. */
    private List<UUID> parseCandidateIds(List<String> raw) {
        if (raw == null) {
            throw new IllegalArgumentException("candidateIds must not be null");
        }
        return raw.stream().map(this::parseUuid).toList();
    }

    /** Rejects a missing/blank/malformed candidateIds entry. */
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

    /** energy/accessibility/moodIntensity are optional — a missing field is a real {@code null}, not an error. */
    private Level parseLevel(String raw, String kind) {
        return raw == null ? null : parseEnumValue(raw, Level.class, kind);
    }

    /** Rejects a missing/blank queryText. */
    private String requireQueryText(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("queryText must not be blank");
        }
        return raw;
    }

    /** The conversational summary line the model reads alongside the structured matches. */
    private String buildContent(List<ScoredBlock> matches) {
        if (matches.isEmpty()) {
            return "No semantically similar blocks found for the given candidates/category.";
        }
        return "Found " + matches.size() + " matching block(s), ranked by similarity.";
    }

    /** Serializes the tool's output — a failure here is our bug, not the model's, hence {@link IllegalStateException}. */
    private String writeJson(Output output) {
        try {
            return objectMapper.writeValueAsString(output);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize " + NAME + " output", e);
        }
    }

    /** Every {@link BlockContentCategory} name — exposed to the model as the {@code category} field's schema enum. */
    private static List<String> categoryNames() {
        return Arrays.stream(BlockContentCategory.values()).map(Enum::name).toList();
    }

    /**
     * Every {@link Level} name plus a trailing {@code null} entry, so the
     * JSON Schema's enum stays valid alongside {@code "type": ["string",
     * "null"]} — {@code List.of(...)} can't hold null, hence the explicit
     * {@code ArrayList} build here instead of reusing a {@code
     * List.of(...)}-based helper like {@link #categoryNames}.
     */
    private static List<String> levelNamesOrNull() {
        List<String> names = new ArrayList<>(Arrays.stream(Level.values()).map(Enum::name).toList());
        names.add(null);
        return names;
    }

    /** The model's raw tool-call arguments, before validation. */
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

    /** The tool's structured payload, alongside {@link #buildContent}'s summary. */
    private record Metadata(List<ScoredBlock> matches) {
    }

    /** The tool's full JSON result shape — conversational summary plus structured metadata. */
    private record Output(String content, Metadata metadata) {
    }
}
