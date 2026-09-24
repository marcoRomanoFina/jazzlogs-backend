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
import com.jazzlogs.backend.graph.GraphCandidate;
import com.jazzlogs.backend.graph.GraphFilterFilters;
import com.jazzlogs.backend.graph.GraphFilterResult;
import com.jazzlogs.backend.graph.GraphFilterService;
import com.jazzlogs.backend.vocabulary.ContextVocabulary;
import com.jazzlogs.backend.vocabulary.InstrumentVocabulary;
import com.jazzlogs.backend.vocabulary.MoodVocabulary;
import com.jazzlogs.backend.vocabulary.RhythmVocabulary;
import com.jazzlogs.backend.vocabulary.StyleVocabulary;

/**
 * Structural (graph-topology) prefilter: given vocabulary filters, ranks
 * Track candidates by which requested dimensions they match in Neo4j
 * (matchedDimensions — the specific (dimension, code) pairs, not just a
 * count), excluding what the current user already listened to / rated by
 * default. Matching a single requested dimension is enough to be eligible
 * (OR, not AND) — candidates with no matches at all are already excluded in
 * Cypher, never returned here. Optionally scoped to one album's/artist's
 * own tracks via albumId/artistId (resolve the id first with
 * RESOLVE_JAZZLOGS_ENTITY) — Album/Artist are not recommendable outcomes
 * themselves anymore, only useful as search scope. Standalone — the model
 * can synthesize an answer from matchedDimensions alone, or chain the
 * returned candidates into semanticSearch itself; this tool holds no memory
 * between calls. All the actual filtering logic lives in {@link
 * GraphFilterService} — this class only translates the model's JSON args
 * into strongly-typed {@link GraphFilterFilters} (rejecting invalid codes)
 * and serializes the result back out.
 */
@Component
public class GraphFilterTool extends JazzTool {

    public static final String NAME = "GRAPH_FILTER";

    private static final Map<String, Object> SCHEMA = Map.of(
        "type", "object",
        "properties", Map.ofEntries(
            Map.entry("styles", Map.of(
                "type", "array",
                "items", Map.of("type", "string", "enum", namesOf(StyleVocabulary.class))
            )),
            Map.entry("rhythms", Map.of(
                "type", "array",
                "items", Map.of("type", "string", "enum", namesOf(RhythmVocabulary.class))
            )),
            Map.entry("moods", Map.of(
                "type", "array",
                "items", Map.of("type", "string", "enum", namesOf(MoodVocabulary.class))
            )),
            Map.entry("contexts", Map.of(
                "type", "array",
                "items", Map.of("type", "string", "enum", namesOf(ContextVocabulary.class))
            )),
            Map.entry("instruments", Map.of(
                "type", "array",
                "items", Map.of("type", "string", "enum", namesOf(InstrumentVocabulary.class))
            )),
            Map.entry("albumId", Map.of("type", List.of("string", "null"))),
            Map.entry("artistId", Map.of("type", List.of("string", "null"))),
            Map.entry("excludeListened", Map.of("type", List.of("boolean", "null"))),
            Map.entry("excludeAlreadyRated", Map.of("type", List.of("boolean", "null"))),
            Map.entry("topK", Map.of("type", List.of("integer", "null")))
        ),
        "required", List.of()
    );

    private final JsonMapper objectMapper;
    private final GraphFilterService graphFilterService;

    public GraphFilterTool(GraphFilterService graphFilterService, JsonMapper objectMapper) {
        super(
            NAME,
            "Rank Track candidates by graph-topology overlap with the given style/rhythm/mood/context/"
                + "instrument vocabulary filters. Returns each candidate's id, name (entityName — use this, "
                + "never the id, when referring to a candidate in your answer), and exactly which filters "
                + "it matched (matchedDimensions) — no long-form description or editorial text (use "
                + "SEMANTIC_SEARCH for that, required before recommending anything specific — see KNOWLEDGE "
                + "SOURCE RULE). A candidate only needs to match one of the requested filters to be "
                + "included, not all of them — matchedDimensions tells you which ones actually matched, so "
                + "a candidate matching only 1 of 3 requested filters is not necessarily a strong fit, check "
                + "before assuming it's central to the request. Use this to narrow down the catalog before "
                + "writing an answer, or on its own when structural overlap alone is enough. By default "
                + "excludes items the current user already listened to or rated. Optionally set albumId "
                + "and/or artistId (resolve the id first with RESOLVE_JAZZLOGS_ENTITY) to scope the search "
                + "to one album's or artist's own tracks — e.g. for \"something from this album\", resolve "
                + "the album then call this with just albumId set and no vocabulary filters. At least one "
                + "of styles/rhythms/moods/contexts/instruments/albumId/artistId is required for a useful "
                + "result — omitting all of them returns no candidates.",
            "Filtrando por estilo y clima"
        );
        this.graphFilterService = graphFilterService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected Map<String, Object> schema() {
        return SCHEMA;
    }

    /** Ranks Track candidates by graph-topology overlap with the given vocabulary filters, optionally scoped to an album/artist. */
    @Override
    public ToolExecutionResult execute(ToolCallRequest call, UUID userId) {
        Args args = parseArgs(call.argumentsJson());
        GraphFilterFilters filters = new GraphFilterFilters(
            parseEnumList(args.styles(), StyleVocabulary.class, "styles"),
            parseEnumList(args.rhythms(), RhythmVocabulary.class, "rhythms"),
            parseEnumList(args.moods(), MoodVocabulary.class, "moods"),
            parseEnumList(args.contexts(), ContextVocabulary.class, "contexts"),
            parseEnumList(args.instruments(), InstrumentVocabulary.class, "instruments"),
            parseOptionalUuid(args.albumId(), "albumId"),
            parseOptionalUuid(args.artistId(), "artistId"),
            args.excludeListened(),
            args.excludeAlreadyRated(),
            args.topK()
        );

        GraphFilterResult result = graphFilterService.filter(filters, userId);

        Output output = new Output(buildContent(result.candidates()), new Metadata(result.candidates()));
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

    /** albumId/artistId are optional scope — a missing/null value means "no scope", not an error; a present one must be a real UUID. */
    private UUID parseOptionalUuid(String raw, String kind) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(kind + " is not a valid id: " + raw);
        }
    }

    /** The conversational summary line the model reads alongside the structured candidates. */
    private String buildContent(List<GraphCandidate> candidates) {
        if (candidates.isEmpty()) {
            return "No graph candidates matched the given filters.";
        }
        return "Found " + candidates.size() + " graph candidate(s), ranked by number of matched dimensions.";
    }

    /** Serializes the tool's output — a failure here is our bug, not the model's, hence {@link IllegalStateException}. */
    private String writeJson(Output output) {
        try {
            return objectMapper.writeValueAsString(output);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize " + NAME + " output", e);
        }
    }

    /** Every {@code enumClass} constant's name — exposed to the model as one vocabulary field's schema enum. */
    private static <E extends Enum<E>> List<String> namesOf(Class<E> enumClass) {
        return Arrays.stream(enumClass.getEnumConstants()).map(Enum::name).toList();
    }

    /** The model's raw tool-call arguments, before validation. */
    private record Args(
        List<String> styles,
        List<String> rhythms,
        List<String> moods,
        List<String> contexts,
        List<String> instruments,
        String albumId,
        String artistId,
        Boolean excludeListened,
        Boolean excludeAlreadyRated,
        Integer topK
    ) {
    }

    /** The tool's structured payload, alongside {@link #buildContent}'s summary. */
    private record Metadata(List<GraphCandidate> candidates) {
    }

    /** The tool's full JSON result shape — conversational summary plus structured metadata. */
    private record Output(String content, Metadata metadata) {
    }
}
