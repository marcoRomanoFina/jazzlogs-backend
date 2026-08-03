package com.jazzlogs.backend.agent.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;

import com.jazzlogs.backend.agent.ToolCallRequest;
import com.jazzlogs.backend.agent.ToolExecutionResult;
import com.jazzlogs.backend.chat.CatalogItemType;
import com.jazzlogs.backend.editorial.BlockContentCategory;
import com.jazzlogs.backend.editorial.EditorialBlockRepository;
import com.jazzlogs.backend.editorial.TrackEditorialRepository;
import com.jazzlogs.backend.embedding.EmbeddingService;
import com.jazzlogs.backend.vocabulary.ContextVocabulary;
import com.jazzlogs.backend.vocabulary.InstrumentVocabulary;
import com.jazzlogs.backend.vocabulary.MoodVocabulary;
import com.jazzlogs.backend.vocabulary.RhythmVocabulary;
import com.jazzlogs.backend.vocabulary.StyleVocabulary;
import com.jazzlogs.backend.vocabulary.VocabularyFilterType;

// Semantic RAG over editorial_blocks.embedding — for open-ended questions
// that don't already point at a resolved entity (e.g. "tell me a great jazz
// story"), as opposed to EDITORIAL_CONTENT, which needs a concrete
// editorialId up front. editorialIds/category/vocabularyFilter here are
// optional prefilters, not a replacement for RESOLVE_JAZZLOGS_ENTITY +
// EDITORIAL_CONTENT when the entity is already known.
//
// vocabularyFilter (e.g. "something with a lot of groove") is resolved in two
// hidden steps before the actual search — see VocabularyEditorialResolver —
// never exposed to the model as separate tool calls.
@Component
public class EditorialSearchTool extends JazzTool {

    public static final String NAME = "EDITORIAL_SEARCH";

    // Not exposed to the model — see class doc on ResolveJazzlogsEntityTool
    // for the same reasoning (fixed internally, tune here if it needs
    // adjusting rather than letting the model pick an unbounded value).
    private static final int LIMIT = 5;

    private static final Map<String, Object> SCHEMA = Map.of(
        "type", "object",
        "properties", Map.of(
            "queryText", Map.of("type", "string"),
            "editorialIds", Map.of("type", "array", "items", Map.of("type", "string")),
            "category", Map.of("type", "string", "enum", categoryNames()),
            "vocabularyFilter", Map.of(
                "type", "object",
                "properties", Map.of(
                    "type", Map.of("type", "string", "enum", vocabularyFilterTypeNames()),
                    "code", Map.of("type", "string")
                ),
                "required", List.of("type", "code")
            ),
            "entityType", Map.of("type", "string", "enum", List.of("ALBUM", "TRACK", "ARTIST"))
        ),
        "required", List.of("queryText")
    );

    // Not Spring-managed — same reasoning as AgentOrchestrator.OBJECT_MAPPER.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final EditorialBlockRepository editorialBlockRepository;
    private final TrackEditorialRepository trackEditorialRepository;
    private final EmbeddingService embeddingService;
    private final VocabularyEditorialResolver vocabularyResolver;

    public EditorialSearchTool(
        EditorialBlockRepository editorialBlockRepository,
        TrackEditorialRepository trackEditorialRepository,
        EmbeddingService embeddingService,
        VocabularyEditorialResolver vocabularyResolver
    ) {
        super(
            NAME,
            "Semantic search over JazzLogs editorial content for open-ended questions that don't already "
                + "point at a resolved entity (e.g. \"tell me a great jazz story\"). Optionally scoped to "
                + "specific editorialIds, a content category, or a vocabularyFilter (style/rhythm/mood/"
                + "context/instrument, e.g. \"something with a lot of groove\") to narrow the search to "
                + "entities tagged with that trait — entityType further narrows which kind of entity the "
                + "vocabularyFilter applies to, when relevant."
        );
        this.editorialBlockRepository = editorialBlockRepository;
        this.trackEditorialRepository = trackEditorialRepository;
        this.embeddingService = embeddingService;
        this.vocabularyResolver = vocabularyResolver;
    }

    @Override
    protected Map<String, Object> schema() {
        return SCHEMA;
    }

    @Override
    public ToolExecutionResult execute(ToolCallRequest call) {
        Args args = parseArgs(call.argumentsJson());
        String queryText = requireQueryText(args.queryText());
        List<UUID> inputEditorialIds = parseOptionalEditorialIds(args.editorialIds());
        BlockContentCategory category = parseOptionalCategory(args.category());
        CatalogItemType entityType = parseOptionalEntityType(args.entityType());
        VocabularyFilter vocabularyFilter = parseOptionalVocabularyFilter(args.vocabularyFilter());

        Integer resolvedEntityCount = null;
        List<UUID> editorialIds = inputEditorialIds;

        if (vocabularyFilter != null) {
            List<VocabularyEditorialResolver.EntityRef> entities =
                vocabularyResolver.findEntities(vocabularyFilter.type(), vocabularyFilter.code(), entityType);
            resolvedEntityCount = entities.size();

            // Tag matches nothing in the graph — stop here, no point running
            // steps 2/3 (Postgres editorial lookup + the pgvector query itself).
            if (entities.isEmpty()) {
                return noResults(queryText, inputEditorialIds, category, resolvedEntityCount);
            }

            Set<UUID> vocabularyEditorialIds = vocabularyResolver.resolveEditorialIds(entities);
            editorialIds = intersectOrUse(inputEditorialIds, vocabularyEditorialIds);

            // Entities matched the tag, but either none of them have an
            // editorial yet, or none overlap with the caller's own
            // editorialIds — same "nothing to search" outcome either way.
            if (editorialIds.isEmpty()) {
                return noResults(queryText, editorialIds, category, resolvedEntityCount);
            }
        }

        String queryEmbedding = new PGvector(embeddingService.embed(queryText)).getValue();
        List<EditorialBlockRepository.SemanticSearchRow> rows = editorialIds == null
            ? editorialBlockRepository.semanticSearch(queryEmbedding, category == null ? null : category.name(), LIMIT)
            : editorialBlockRepository.semanticSearchScopedToEditorials(
                queryEmbedding, editorialIds, category == null ? null : category.name(), LIMIT
            );

        List<Result> results = toResults(rows);
        Output output = new Output(
            buildContent(queryText, results),
            new Metadata(queryText, editorialIds, category, resolvedEntityCount, results)
        );
        return new ToolExecutionResult(writeJson(output), true);
    }

    private Args parseArgs(String argumentsJson) {
        try {
            return OBJECT_MAPPER.readValue(argumentsJson, Args.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(NAME + " arguments were not valid JSON: " + e.getMessage(), e);
        }
    }

    private String requireQueryText(String queryText) {
        if (queryText == null || queryText.isBlank()) {
            throw new IllegalArgumentException("queryText must not be blank");
        }
        return queryText;
    }

    // null (not empty) means "no editorialIds filter at all" — an explicit
    // empty array from the model is treated the same way, not as "match
    // nothing", consistent with every other optional field here.
    private List<UUID> parseOptionalEditorialIds(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        return raw.stream().map(this::parseEditorialId).toList();
    }

    private UUID parseEditorialId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("editorialIds contains an invalid id: " + raw);
        }
    }

    private BlockContentCategory parseOptionalCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return BlockContentCategory.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown BlockContentCategory: " + raw);
        }
    }

    private CatalogItemType parseOptionalEntityType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return CatalogItemType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("entityType must be one of ALBUM, TRACK, ARTIST, got: " + raw);
        }
    }

    private VocabularyFilter parseOptionalVocabularyFilter(VocabularyFilterArgs raw) {
        if (raw == null) {
            return null;
        }
        VocabularyFilterType type = parseVocabularyFilterType(raw.type());
        String code = requireValidCode(type, raw.code());
        return new VocabularyFilter(type, code);
    }

    private VocabularyFilterType parseVocabularyFilterType(String raw) {
        try {
            return VocabularyFilterType.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException(
                "vocabularyFilter.type must be one of STYLE, RHYTHM, MOOD, CONTEXT, INSTRUMENT, got: " + raw
            );
        }
    }

    // Same validate-before-hitting-Neo4j principle as VocabularyCodes.validate
    // elsewhere in the project — a MATCH on a nonexistent tag node just fails
    // silently (empty result) instead of raising an error, so a typo'd code
    // needs to be caught here, not left to look like "no matches".
    private String requireValidCode(VocabularyFilterType type, String code) {
        if (!isValidCode(type, code)) {
            throw new IllegalArgumentException("vocabularyFilter.code is not a valid " + type + " code: " + code);
        }
        return code;
    }

    private static boolean isValidCode(VocabularyFilterType type, String code) {
        if (code == null) {
            return false;
        }
        return switch (type) {
            case STYLE -> isEnumValue(StyleVocabulary.class, code);
            case RHYTHM -> isEnumValue(RhythmVocabulary.class, code);
            case MOOD -> isEnumValue(MoodVocabulary.class, code);
            case CONTEXT -> isEnumValue(ContextVocabulary.class, code);
            case INSTRUMENT -> isEnumValue(InstrumentVocabulary.class, code);
        };
    }

    private static <E extends Enum<E>> boolean isEnumValue(Class<E> enumClass, String code) {
        try {
            Enum.valueOf(enumClass, code);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // null input (no editorialIds passed at all) just adopts whatever the
    // vocabularyFilter resolved; otherwise the two are intersected — an
    // editorial only stays in scope if both agree on it.
    private List<UUID> intersectOrUse(List<UUID> inputIds, Set<UUID> vocabularyIds) {
        if (inputIds == null) {
            return new ArrayList<>(vocabularyIds);
        }
        return inputIds.stream().filter(vocabularyIds::contains).toList();
    }

    private ToolExecutionResult noResults(String queryText, List<UUID> editorialIds, BlockContentCategory category, Integer resolvedEntityCount) {
        Output output = new Output(
            buildContent(queryText, List.of()),
            new Metadata(queryText, editorialIds, category, resolvedEntityCount, List.of())
        );
        return new ToolExecutionResult(writeJson(output), true);
    }

    // Entity type/name come off embedding_metadata (editorialType, albumName,
    // artistName — see EditorialService.buildBaseMetadata) whenever it's
    // there, no extra JOIN — except TrackEditorial blocks, whose metadata was
    // never given a track name at write time. Those get backfilled with one
    // batched JOIN query instead of one lookup per row.
    private List<Result> toResults(List<EditorialBlockRepository.SemanticSearchRow> rows) {
        List<PartialResult> partials = rows.stream().map(this::toPartial).toList();

        List<UUID> missingNames = partials.stream()
            .filter(p -> p.entityType() == CatalogItemType.TRACK && p.entityName() == null)
            .map(PartialResult::editorialId)
            .distinct()
            .toList();

        Map<UUID, String> backfilled = missingNames.isEmpty()
            ? Map.of()
            : trackEditorialRepository.findNamesByEditorialIdIn(missingNames).stream()
                .collect(Collectors.toMap(TrackEditorialRepository.NameRow::getEditorialId, TrackEditorialRepository.NameRow::getName));

        return partials.stream()
            .map(p -> new Result(
                p.blockId(), p.editorialId(), p.entityType(),
                p.entityName() != null ? p.entityName() : backfilled.get(p.editorialId()),
                p.contentCategory(), p.text(), p.score()
            ))
            .toList();
    }

    private PartialResult toPartial(EditorialBlockRepository.SemanticSearchRow row) {
        Map<String, Object> metadata = readMetadata(row.getEmbeddingMetadataJson());
        CatalogItemType entityType = toEntityType((String) metadata.get("editorialType"));
        // entityType is null when editorialType is missing/unrecognized (malformed
        // metadata) — a plain switch over a null enum throws, which would fail the
        // whole search over one bad row instead of just leaving it unlabeled.
        String entityName = entityType == null ? null : switch (entityType) {
            case ALBUM -> (String) metadata.get("albumName");
            case ARTIST -> (String) metadata.get("artistName");
            case TRACK -> null; // not in metadata — backfilled in toResults
        };

        return new PartialResult(
            row.getId(), row.getEditorialId(), entityType, entityName,
            BlockContentCategory.valueOf(row.getContentCategory()), row.getText(), row.getScore()
        );
    }

    // Enrichment only, never load-bearing for the search result itself: a
    // block whose metadata is missing or malformed still has valid text and
    // a valid score, it just won't have an entityType/entityName label —
    // degrade quietly rather than failing the whole search over it.
    private Map<String, Object> readMetadata(String json) {
        if (json == null) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private static CatalogItemType toEntityType(String editorialType) {
        if (editorialType == null) {
            return null;
        }
        return switch (editorialType) {
            case "AlbumEditorial" -> CatalogItemType.ALBUM;
            case "TrackEditorial" -> CatalogItemType.TRACK;
            case "ArtistEditorial" -> CatalogItemType.ARTIST;
            default -> null;
        };
    }

    private String buildContent(String queryText, List<Result> results) {
        if (results.isEmpty()) {
            return "No editorial blocks found matching \"" + queryText + "\".";
        }
        return "Found " + results.size() + " editorial block(s) matching \"" + queryText + "\".";
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

    private static List<String> vocabularyFilterTypeNames() {
        return Arrays.stream(VocabularyFilterType.values()).map(Enum::name).toList();
    }

    private record Args(
        String queryText, List<String> editorialIds, String category, VocabularyFilterArgs vocabularyFilter, String entityType
    ) {
    }

    private record VocabularyFilterArgs(String type, String code) {
    }

    private record VocabularyFilter(VocabularyFilterType type, String code) {
    }

    // Pre-backfill shape — entityName is null exactly when it still needs to
    // come from the TrackEditorial fallback query.
    private record PartialResult(
        UUID blockId, UUID editorialId, CatalogItemType entityType, String entityName,
        BlockContentCategory contentCategory, String text, Double score
    ) {
    }

    private record Result(
        UUID blockId, UUID editorialId, CatalogItemType entityType, String entityName,
        BlockContentCategory contentCategory, String text, Double score
    ) {
    }

    private record Metadata(
        String queryText, List<UUID> editorialIds, BlockContentCategory category, Integer resolvedEntityCount, List<Result> results
    ) {
    }

    private record Output(String content, Metadata metadata) {
    }
}
