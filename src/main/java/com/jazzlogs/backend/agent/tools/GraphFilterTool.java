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
import com.jazzlogs.backend.chat.CatalogItemType;
import com.jazzlogs.backend.graph.GraphCandidate;
import com.jazzlogs.backend.graph.GraphFilterFilters;
import com.jazzlogs.backend.graph.GraphFilterResult;
import com.jazzlogs.backend.graph.GraphFilterService;
import com.jazzlogs.backend.vocabulary.ContextVocabulary;
import com.jazzlogs.backend.vocabulary.InstrumentVocabulary;
import com.jazzlogs.backend.vocabulary.MoodVocabulary;
import com.jazzlogs.backend.vocabulary.RhythmVocabulary;
import com.jazzlogs.backend.vocabulary.StyleVocabulary;

// Structural (graph-topology) prefilter: given vocabulary filters, ranks
// candidates of ONE entity type (Album, Track, or Artist — call this again
// for another type, same rule already applied to semanticSearch's category)
// by which of the requested dimensions they match in Neo4j
// (matchedDimensions — the specific (dimension, code) pairs, not just a
// count), excluding what the current user already listened to / rated by
// default. Matching a single requested dimension is enough to be eligible
// (OR, not AND) — candidates with no matches at all are already excluded by
// GraphService's Cypher, never returned here. Standalone — the model can
// synthesize an answer from matchedDimensions alone (e.g. "this has the
// mood you wanted, though not the instrument"), or chain the returned
// candidates into semanticSearch itself; this tool holds no memory between
// calls. All the actual short-circuit/dispatch logic lives in
// GraphFilterService — this class only translates the model's JSON args
// into strongly-typed GraphFilterFilters (and rejects invalid codes) and
// serializes the result back out.
@Component
public class GraphFilterTool extends JazzTool {

    public static final String NAME = "GRAPH_FILTER";

    private static final Map<String, Object> SCHEMA = Map.of(
        "type", "object",
        "properties", Map.ofEntries(
            Map.entry("entityType", Map.of("type", "string", "enum", List.of("ALBUM", "TRACK", "ARTIST"))),
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
            Map.entry("excludeListened", Map.of("type", List.of("boolean", "null"))),
            Map.entry("excludeAlreadyRated", Map.of("type", List.of("boolean", "null"))),
            Map.entry("topK", Map.of("type", List.of("integer", "null")))
        ),
        "required", List.of("entityType")
    );

    // Not Spring-managed — same reasoning as AgentOrchestrator.OBJECT_MAPPER.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final GraphFilterService graphFilterService;

    public GraphFilterTool(GraphFilterService graphFilterService) {
        super(
            NAME,
            "Rank candidates of ONE entity type (ALBUM, TRACK, or ARTIST) by graph-topology overlap with "
                + "the given style/rhythm/mood/context/instrument vocabulary filters. Call this again with "
                + "a different entityType to cover more than one. Not every filter applies to every "
                + "entityType — an irrelevant one is silently ignored, not an error, so check first: ALBUM "
                + "only connects to styles/moods/contexts (no rhythms/instruments); TRACK only to "
                + "moods/contexts/rhythms/instruments (no styles); ARTIST only to styles/contexts/"
                + "instruments (no moods/rhythms). Returns each candidate's id, name (entityName — use "
                + "this, never the id, when referring to a candidate in your answer), and exactly which "
                + "filters it matched (matchedDimensions) — no long-form description or editorial text "
                + "(use SEMANTIC_SEARCH for that, required before recommending anything specific — see "
                + "KNOWLEDGE SOURCE RULE). A candidate only needs to match one of the requested filters to "
                + "be included, not all of them — matchedDimensions tells you which ones actually matched, "
                + "so a candidate matching only 1 of 3 requested filters is not necessarily a strong fit, "
                + "check before assuming it's central to the request. Use this to narrow down the catalog "
                + "before writing an answer, or on its own when structural overlap alone is enough. By "
                + "default excludes items the current user already listened to or rated. Omit every "
                + "vocabulary filter and this returns no candidates — at least one of "
                + "styles/rhythms/moods/contexts/instruments (whichever apply to the chosen entityType) is "
                + "required for a useful result."
        );
        this.graphFilterService = graphFilterService;
    }

    @Override
    protected Map<String, Object> schema() {
        return SCHEMA;
    }

    @Override
    public ToolExecutionResult execute(ToolCallRequest call, UUID userId) {
        Args args = parseArgs(call.argumentsJson());
        GraphFilterFilters filters = new GraphFilterFilters(
            parseRequiredEnum(args.entityType(), CatalogItemType.class, "entityType"),
            parseEnumList(args.styles(), StyleVocabulary.class, "styles"),
            parseEnumList(args.rhythms(), RhythmVocabulary.class, "rhythms"),
            parseEnumList(args.moods(), MoodVocabulary.class, "moods"),
            parseEnumList(args.contexts(), ContextVocabulary.class, "contexts"),
            parseEnumList(args.instruments(), InstrumentVocabulary.class, "instruments"),
            args.excludeListened(),
            args.excludeAlreadyRated(),
            args.topK()
        );

        GraphFilterResult result = graphFilterService.filter(filters, userId);

        Output output = new Output(buildContent(result.candidates()), new Metadata(result.candidates()));
        return new ToolExecutionResult(writeJson(output), true);
    }

    private Args parseArgs(String argumentsJson) {
        try {
            return OBJECT_MAPPER.readValue(argumentsJson, Args.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(NAME + " arguments were not valid JSON: " + e.getMessage(), e);
        }
    }

    // parseEnumValue/parseRequiredEnum are inherited from JazzTool — shared
    // with SemanticSearchTool, which needs the identical entityType parsing.
    private <E extends Enum<E>> List<E> parseEnumList(List<String> raw, Class<E> enumClass, String kind) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream().map(code -> parseEnumValue(code, enumClass, kind)).toList();
    }

    private String buildContent(List<GraphCandidate> candidates) {
        if (candidates.isEmpty()) {
            return "No graph candidates matched the given filters.";
        }
        return "Found " + candidates.size() + " graph candidate(s), ranked by number of matched dimensions.";
    }

    private String writeJson(Output output) {
        try {
            return OBJECT_MAPPER.writeValueAsString(output);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize " + NAME + " output", e);
        }
    }

    private static <E extends Enum<E>> List<String> namesOf(Class<E> enumClass) {
        return Arrays.stream(enumClass.getEnumConstants()).map(Enum::name).toList();
    }

    private record Args(
        String entityType,
        List<String> styles,
        List<String> rhythms,
        List<String> moods,
        List<String> contexts,
        List<String> instruments,
        Boolean excludeListened,
        Boolean excludeAlreadyRated,
        Integer topK
    ) {
    }

    private record Metadata(List<GraphCandidate> candidates) {
    }

    private record Output(String content, Metadata metadata) {
    }
}
