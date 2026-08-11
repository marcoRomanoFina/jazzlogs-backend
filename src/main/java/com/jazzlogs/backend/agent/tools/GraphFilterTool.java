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
// Album/Track/Artist candidates by which of the requested dimensions they
// match in Neo4j (matchedDimensions — the specific (dimension, code) pairs,
// not just a count), excluding what the current user already listened to /
// rated by default. Matching a single requested dimension is enough to be
// eligible (OR, not AND) — candidates with no matches at all are already
// excluded by GraphService's Cypher, never returned here. Standalone — the
// model can synthesize an answer from matchedDimensions alone (e.g. "this
// has the mood you wanted, though not the instrument"), or chain the
// returned candidates into a later semanticSearch tool itself; this tool
// holds no memory between calls. All the actual defaulting/short-circuit/
// clamp/ranking logic lives in GraphFilterService — this class only
// translates the model's JSON args into strongly-typed GraphFilterFilters
// (and rejects invalid vocabulary codes) and serializes the result back out.
@Component
public class GraphFilterTool extends JazzTool {

    public static final String NAME = "GRAPH_FILTER";

    private static final Map<String, Object> SCHEMA = Map.of(
        "type", "object",
        "properties", Map.ofEntries(
            Map.entry("entityTypes", Map.of(
                "type", "array",
                "items", Map.of("type", "string", "enum", List.of("ALBUM", "TRACK", "ARTIST"))
            )),
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
        "required", List.of()
    );

    // Not Spring-managed — same reasoning as AgentOrchestrator.OBJECT_MAPPER.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final GraphFilterService graphFilterService;

    public GraphFilterTool(GraphFilterService graphFilterService) {
        super(
            NAME,
            "Rank Album/Track/Artist candidates by graph-topology overlap with the given style/rhythm/"
                + "mood/context/instrument vocabulary filters. Returns candidate ids and exactly which "
                + "filters each one matched (matchedDimensions) — no descriptions or text. A candidate "
                + "only needs to match one of the requested filters to be included; matchedDimensions "
                + "tells you which ones so you can explain the recommendation with real specifics, not "
                + "just a score. Use this to narrow down the catalog before writing an answer, or on its "
                + "own when structural overlap alone is enough. By default excludes items the current "
                + "user already listened to or rated. Omit every vocabulary filter and this returns no "
                + "candidates — at least one of styles/rhythms/moods/contexts/instruments is required for "
                + "a useful result."
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
            parseEnumList(args.entityTypes(), CatalogItemType.class, "entityTypes"),
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

    // A concrete Class<E> literal is required at every call site (never a
    // wildcard-typed variable) — Class<? extends Enum<?>> can't satisfy the
    // <E extends Enum<E>> bound here due to Java's wildcard capture rules.
    private <E extends Enum<E>> List<E> parseEnumList(List<String> raw, Class<E> enumClass, String kind) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream().map(code -> parseEnumValue(code, enumClass, kind)).toList();
    }

    private <E extends Enum<E>> E parseEnumValue(String raw, Class<E> enumClass, String kind) {
        try {
            return Enum.valueOf(enumClass, raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Unknown " + kind + " value: " + raw);
        }
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
        List<String> entityTypes,
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
