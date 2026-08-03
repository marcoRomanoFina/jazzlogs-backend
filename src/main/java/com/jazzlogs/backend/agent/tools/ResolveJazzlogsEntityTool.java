package com.jazzlogs.backend.agent.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.jazzlogs.backend.agent.CatalogEntityResolver;
import com.jazzlogs.backend.agent.ToolCallRequest;
import com.jazzlogs.backend.agent.ToolExecutionResult;
import com.jazzlogs.backend.album.Album;
import com.jazzlogs.backend.album.AlbumRepository;
import com.jazzlogs.backend.artist.ArtistRepository;
import com.jazzlogs.backend.chat.CatalogItemType;
import com.jazzlogs.backend.track.TrackRepository;

// Text -> id translator: the agent calls this when the user names an album,
// track, or artist in free text and doesn't have its stable catalog id yet.
// Deliberately thin — a ranked list of candidates, nothing else. Richer
// context (tracklist, personnel, mood) is a job for deeper tools later
// (CATALOG_CONTEXT, ALBUM_TRACKS), not this one.
//
// Resolves against Postgres, not Neo4j: the graph's nodes are intentionally
// light (id + name only) and were never the source of truth for text —
// that's artists/albums/tracks.normalized_name, which pg_trgm searches
// directly with one indexed query per entity type (see CatalogEntityResolver
// and Album/Artist/TrackRepository.search) instead of several sequential
// passes with hand-rolled fuzzy matching in Java.
@Component
public class ResolveJazzlogsEntityTool extends JazzTool {

    public static final String NAME = "RESOLVE_JAZZLOGS_ENTITY";

    // Not exposed to the model — see class doc. 20 is the fuzzy shortlist
    // CatalogEntityResolver.search returns; this is what actually goes back
    // in the tool result after dedupe.
    private static final int MAX_CANDIDATES = 7;

    private static final Map<String, Object> SCHEMA = Map.of(
        "type", "object",
        "properties", Map.of(
            "entityType", Map.of("type", "string", "enum", List.of("ALBUM", "TRACK", "ARTIST")),
            "query", Map.of("type", "string")
        ),
        "required", List.of("entityType", "query")
    );

    // Not Spring-managed — same reasoning as AgentOrchestrator.OBJECT_MAPPER.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Map<CatalogItemType, CatalogEntityResolver> resolversByType;

    public ResolveJazzlogsEntityTool(AlbumRepository albumRepository, ArtistRepository artistRepository, TrackRepository trackRepository) {
        super(
            NAME,
            "Resolve a free-text album, track, or artist name the user mentioned into ranked JazzLogs "
                + "catalog id candidates. Use this whenever you need a concrete catalog id and don't "
                + "already have one from an earlier tool result in this conversation."
        );
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

    @Override
    public ToolExecutionResult execute(ToolCallRequest call) {
        Args args = parseArgs(call.argumentsJson());
        CatalogItemType entityType = parseEntityType(args.entityType());
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

    private Args parseArgs(String argumentsJson) {
        try {
            return OBJECT_MAPPER.readValue(argumentsJson, Args.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(NAME + " arguments were not valid JSON: " + e.getMessage(), e);
        }
    }

    private CatalogItemType parseEntityType(String raw) {
        try {
            return CatalogItemType.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("entityType must be one of ALBUM, TRACK, ARTIST, got: " + raw);
        }
    }

    private String requireQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        return query;
    }

    // Safety net, not the primary defense: the query is already scoped to
    // one table per entityType and shouldn't return duplicate ids, but
    // dedupe by id anyway. Preserves first-seen order — the rows are already
    // matchType/score-sorted by the query itself, this never re-sorts.
    private List<Candidate> dedupeAndTruncate(List<CatalogEntityResolver.CandidateRow> rows, CatalogItemType entityType) {
        Map<UUID, Candidate> byId = new LinkedHashMap<>();
        for (CatalogEntityResolver.CandidateRow row : rows) {
            byId.putIfAbsent(row.getId(), toCandidate(row, entityType));
        }
        return byId.values().stream().limit(MAX_CANDIDATES).toList();
    }

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

    private String buildContent(CatalogItemType entityType, String query, List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            return "No JazzLogs entity candidates found for \"" + query + "\".";
        }
        return "Resolved " + candidates.size() + " candidate(s) for " + entityType + " query \"" + query + "\".";
    }

    private String writeJson(Output output) {
        try {
            return OBJECT_MAPPER.writeValueAsString(output);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize " + NAME + " output", e);
        }
    }

    private record Args(String entityType, String query) {
    }

    private record Candidate(
        UUID id, CatalogItemType type, String name, String artistFullName, String album, Double score, String matchType, UUID editorialId
    ) {
    }

    private record Metadata(boolean found, CatalogItemType entityType, String query, List<Candidate> candidates) {
    }

    private record Output(String content, Metadata metadata) {
    }
}
