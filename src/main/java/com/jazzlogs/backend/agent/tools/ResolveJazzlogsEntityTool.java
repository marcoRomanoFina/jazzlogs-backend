package com.jazzlogs.backend.agent.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import com.jazzlogs.backend.agent.CatalogEntityResolver;
import com.jazzlogs.backend.agent.ToolCallRequest;
import com.jazzlogs.backend.agent.ToolExecutionResult;
import com.jazzlogs.backend.album.Album;
import com.jazzlogs.backend.album.AlbumRepository;
import com.jazzlogs.backend.artist.ArtistRepository;
import com.jazzlogs.backend.chat.CatalogItemType;
import com.jazzlogs.backend.track.TrackRepository;

/**
 * Text-to-id translator: the agent calls this when the user names an album,
 * track, or artist in free text and doesn't have its stable catalog id yet.
 * Deliberately thin — a ranked list of candidates, nothing else. Resolves
 * against Postgres via pg_trgm ({@link CatalogEntityResolver}), not Neo4j —
 * the graph's nodes are id+name only and were never the source of truth for
 * text search.
 */
@Component
public class ResolveJazzlogsEntityTool extends JazzTool {

    public static final String NAME = "RESOLVE_JAZZLOGS_ENTITY";

    /** Not exposed to the model — the cap on candidates returned after dedupe, not on {@link CatalogEntityResolver}'s own shortlist. */
    private static final int MAX_CANDIDATES = 7;

    private static final Map<String, Object> SCHEMA = Map.of(
        "type", "object",
        "properties", Map.of(
            "entityType", Map.of("type", "string", "enum", List.of("ALBUM", "TRACK", "ARTIST")),
            "query", Map.of("type", "string")
        ),
        "required", List.of("entityType", "query")
    );

    private final JsonMapper objectMapper;
    /** Each repository implements {@link CatalogEntityResolver} itself — no separate resolver classes needed. */
    private final Map<CatalogItemType, CatalogEntityResolver> resolversByType;

    public ResolveJazzlogsEntityTool(
        AlbumRepository albumRepository, ArtistRepository artistRepository, TrackRepository trackRepository, JsonMapper objectMapper
    ) {
        super(
            NAME,
            "Resolve a free-text album, track, or artist name the user mentioned into ranked JazzLogs "
                + "catalog id candidates. Use this whenever you need a concrete catalog id and don't "
                + "already have one from an earlier tool result in this conversation.",
            "Identificando el álbum/artista"
        );
        this.objectMapper = objectMapper;
        this.resolversByType = Map.of(
            CatalogItemType.ALBUM, albumRepository,
            CatalogItemType.ARTIST, artistRepository,
            CatalogItemType.TRACK, trackRepository
        );
    }

    @Override
    protected Map<String, Object> schema() {
        return SCHEMA;
    }

    /** Resolves the model's free-text query into ranked, deduped candidates of one entity type. */
    @Override
    public ToolExecutionResult execute(ToolCallRequest call, UUID userId) {
        Args args = parseArgs(call.argumentsJson());
        CatalogItemType entityType = parseRequiredEnum(args.entityType(), CatalogItemType.class, "entityType");
        String query = requireQuery(args.query());
        String normalizedQuery = Album.normalize(query);

        List<CatalogEntityResolver.CandidateRow> rows = resolversByType.get(entityType).search(normalizedQuery);
        List<Candidate> candidates = dedupeAndTruncate(rows, entityType);

        Output output = new Output(
            buildContent(entityType, query, candidates),
            new Metadata(!candidates.isEmpty(), entityType, query, candidates)
        );
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

    /** Rejects a missing/blank query. */
    private String requireQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        return query;
    }

    /**
     * Safety net, not the primary defense: the query is already scoped to
     * one table per entityType and shouldn't return duplicate ids, but
     * dedupe by id anyway. Preserves first-seen order — the rows are already
     * matchType/score-sorted by the query itself, this never re-sorts.
     */
    private List<Candidate> dedupeAndTruncate(List<CatalogEntityResolver.CandidateRow> rows, CatalogItemType entityType) {
        Map<UUID, Candidate> byId = new LinkedHashMap<>();
        for (CatalogEntityResolver.CandidateRow row : rows) {
            byId.putIfAbsent(row.getId(), toCandidate(row, entityType));
        }
        return byId.values().stream().limit(MAX_CANDIDATES).toList();
    }

    /** Projects one resolver row into the tool's output shape. */
    private Candidate toCandidate(CatalogEntityResolver.CandidateRow row, CatalogItemType entityType) {
        return new Candidate(
            row.getId(),
            entityType,
            row.getName(),
            row.getArtistFullName(),
            entityType == CatalogItemType.TRACK ? row.getAlbumName() : null,
            row.getScore(),
            row.getMatchType(),
            row.getEditorialId()
        );
    }

    /** The conversational summary line the model reads alongside the structured candidates. */
    private String buildContent(CatalogItemType entityType, String query, List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            return "No JazzLogs entity candidates found for \"" + query + "\".";
        }
        return "Resolved " + candidates.size() + " candidate(s) for " + entityType + " query \"" + query + "\".";
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
    private record Args(String entityType, String query) {
    }

    /** One ranked candidate; {@code album} is only set for a TRACK. */
    private record Candidate(
        UUID id, CatalogItemType type, String name, String artistFullName, String album, Double score, String matchType, UUID editorialId
    ) {
    }

    /** The tool's structured payload, alongside {@link #buildContent}'s summary. */
    private record Metadata(boolean found, CatalogItemType entityType, String query, List<Candidate> candidates) {
    }

    /** The tool's full JSON result shape — conversational summary plus structured metadata. */
    private record Output(String content, Metadata metadata) {
    }
}
