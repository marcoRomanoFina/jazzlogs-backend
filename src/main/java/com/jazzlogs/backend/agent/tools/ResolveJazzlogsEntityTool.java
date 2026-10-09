package com.jazzlogs.backend.agent.tools;

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
import com.jazzlogs.backend.character.JazzlogsCharacter;
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

    /**
     * Not exposed to the model. Enough to turn a name into an id — the right
     * match is in the first few or not there at all — while still covering a
     * standard recorded on several albums.
     */
    static final int MAX_CANDIDATES = 5;

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

    private final LogAuthorLookup logAuthorLookup;

    public ResolveJazzlogsEntityTool(
        AlbumRepository albumRepository,
        ArtistRepository artistRepository,
        TrackRepository trackRepository,
        LogAuthorLookup logAuthorLookup,
        JsonMapper objectMapper
    ) {
        super(
            NAME,
            "Resolve an album, track, or artist name the user mentioned into ranked JazzLogs catalog "
                + "candidates. Use this whenever you need a concrete catalog id and don't already have "
                + "one from an earlier tool result in this conversation. query is matched against the "
                + "entity's own name only, so pass just that name (\"So What\", not \"So What by Miles "
                + "Davis\") and tell same-named candidates apart by their artistFullName and album. "
                + "Candidates come best match first, each with its entityId and entityName. A TRACK "
                + "candidate also says which narrator wrote its log (writtenBy) — null means that track "
                + "has no log yet, so EDITORIAL_CONTENT has nothing to return for it.",
            "Identificando el álbum/artista"
        );
        this.objectMapper = objectMapper;
        this.logAuthorLookup = logAuthorLookup;
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

    /** Resolves the model's free-text query into ranked candidates of one entity type. */
    @Override
    public ToolExecutionResult execute(ToolCallRequest call, UUID userId) {
        Args args = parseArgs(call.argumentsJson());
        CatalogItemType entityType = parseRequiredEnum(args.entityType(), CatalogItemType.class, "entityType");
        String query = requireQuery(args.query());
        String normalizedQuery = Album.normalize(query);

        List<CatalogEntityResolver.CandidateRow> rows = resolversByType.get(entityType).search(normalizedQuery, MAX_CANDIDATES);
        List<Candidate> candidates = toCandidates(rows, entityType);

        Output output = new Output(buildContent(entityType, query, candidates), new Metadata(entityType, candidates));
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
     * Keeps the rows in the order the query ranked them — never re-sorts —
     * and attaches each track's log author.
     */
    private List<Candidate> toCandidates(List<CatalogEntityResolver.CandidateRow> rows, CatalogItemType entityType) {
        // Only a track has a log to be the author of — albums/artists skip the lookup entirely.
        Map<UUID, JazzlogsCharacter> authors = entityType == CatalogItemType.TRACK
            ? logAuthorLookup.byTrackIds(rows.stream().map(CatalogEntityResolver.CandidateRow::getId).toList())
            : Map.of();
        return rows.stream().map(row -> toCandidate(row, authors.get(row.getId()))).toList();
    }

    /** Projects one resolver row into the tool's output shape. */
    private Candidate toCandidate(CatalogEntityResolver.CandidateRow row, JazzlogsCharacter writtenBy) {
        return new Candidate(row.getId(), row.getName(), row.getArtistFullName(), row.getAlbumName(), row.getMatchType(), writtenBy);
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

    /**
     * One ranked candidate, named like FIND_TRACKS's so the model sees one
     * vocabulary for ids and names. {@code album} and {@code
     * writtenBy} (who signed its log) are only set for a TRACK.
     */
    private record Candidate(
        UUID entityId, String entityName, String artistFullName, String album, String matchType, JazzlogsCharacter writtenBy
    ) {
    }

    /** The tool's structured payload, alongside {@link #buildContent}'s summary. */
    private record Metadata(CatalogItemType entityType, List<Candidate> candidates) {
    }

    /** The tool's full JSON result shape — conversational summary plus structured metadata. */
    private record Output(String content, Metadata metadata) {
    }
}
