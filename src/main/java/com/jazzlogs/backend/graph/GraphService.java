package com.jazzlogs.backend.graph;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import com.jazzlogs.backend.chat.CatalogItemType;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads and writes to Neo4j on behalf of other domains (User, Album/Track/Artist).
 *
 * Two failure policies, by design:
 *  - Node sync methods (syncAlbumNode/syncTrackNode) mirror Postgres truth into
 *    Neo4j as a side effect; if Neo4j is down they log and swallow, since the
 *    Postgres write they're attached to must not be blocked by a graph outage.
 *  - Everything else (relationships, tag/personnel reads) IS the point of the
 *    request — there's no Postgres fallback to protect — so failures propagate
 *    as GraphWriteException, which the global handler turns into a 502.
 */
@Slf4j
@Service
@AllArgsConstructor
public class GraphService {

    private final Neo4jClient neo4jClient;

    public void syncAlbumNode(UUID albumId, String name) {
        try {
            neo4jClient.query("MERGE (a:Album {id: $id}) SET a.name = $name")
                .bind(albumId.toString()).to("id")
                .bind(name).to("name")
                .run();
        } catch (Exception ex) {
            log.error("Failed to sync Neo4j :Album node for id={}", albumId, ex);
        }
    }

    public void syncTrackNode(UUID trackId, String name) {
        try {
            neo4jClient.query("MERGE (t:Track {id: $id}) SET t.name = $name")
                .bind(trackId.toString()).to("id")
                .bind(name).to("name")
                .run();
        } catch (Exception ex) {
            log.error("Failed to sync Neo4j :Track node for id={}", trackId, ex);
        }
    }

    public void syncArtistNode(UUID artistId, String name) {
        try {
            neo4jClient.query("MERGE (a:Artist {id: $id}) SET a.name = $name")
                .bind(artistId.toString()).to("id")
                .bind(name).to("name")
                .run();
        } catch (Exception ex) {
            log.error("Failed to sync Neo4j :Artist node for id={}", artistId, ex);
        }
    }

    public void syncPlaylistNode(UUID playlistId, String name) {
        try {
            neo4jClient.query("MERGE (p:Playlist {id: $id}) SET p.name = $name")
                .bind(playlistId.toString()).to("id")
                .bind(name).to("name")
                .run();
        } catch (Exception ex) {
            log.error("Failed to sync Neo4j :Playlist node for id={}", playlistId, ex);
        }
    }

    /** Brings a Series into the graph, tags-only — no other relationships (BELONGS_TO/track edges) apply to series. */
    public void syncSeriesNode(UUID seriesId, String name) {
        try {
            neo4jClient.query("MERGE (s:Series {id: $id}) SET s.name = $name")
                .bind(seriesId.toString()).to("id")
                .bind(name).to("name")
                .run();
        } catch (Exception ex) {
            log.error("Failed to sync Neo4j :Series node for id={}", seriesId, ex);
        }
    }

    public void addTrackToAlbum(UUID albumId, UUID trackId, int trackNumber) {
        write("add CONTAINS album=" + albumId + " track=" + trackId, () ->
            neo4jClient.query("""
                    MATCH (al:Album {id: $albumId}), (tr:Track {id: $trackId})
                    MERGE (al)-[c:CONTAINS]->(tr)
                    SET c.trackNumber = $trackNumber
                    """)
                .bind(albumId.toString()).to("albumId")
                .bind(trackId.toString()).to("trackId")
                .bind(trackNumber).to("trackNumber")
                .run());
    }

    /**
     * Every album where this artist appears as a sideman ({@code SIDEMAN_ON}),
     * not as the leading artist — unpaged: a small, cheap-to-read set even
     * for a prolific session musician. The caller (ArtistService.getSidemanAlbums)
     * paginates over these ids in Postgres instead of paging this query.
     *
     * @param artistId the artist
     * @return every album id this artist plays sideman on, unordered
     */
    public List<UUID> getSidemanAlbumIds(UUID artistId) {
        return read("read SIDEMAN_ON album ids for artist=" + artistId, () ->
            neo4jClient.query("""
                    MATCH (ar:Artist {id: $artistId})-[:SIDEMAN_ON]->(al:Album)
                    RETURN al.id AS albumId
                    """)
                .bind(artistId.toString()).to("artistId")
                .fetch()
                .all()
                .stream()
                .map(row -> UUID.fromString((String) row.get("albumId")))
                .toList());
    }

    /**
     * MATCH-then-MERGE, not create-if-missing: if the :User or :Album node isn't
     * there (e.g. it was created while Neo4j was down), this silently touches
     * nothing — same as every other relationship write here. Callers that want
     * this to be non-blocking (see ListenService) catch the GraphWriteException
     * this throws on failure; it's not swallowed in here.
     */
    public void markAlbumListened(UUID userId, UUID albumId, Instant listenedAt) {
        write("add LISTENED user=" + userId + " album=" + albumId, () ->
            neo4jClient.query("""
                    MATCH (u:User {id: $userId})
                    MATCH (al:Album {id: $albumId})
                    MERGE (u)-[l:LISTENED]->(al)
                    SET l.listenedAt = $listenedAt
                    """)
                .bind(userId.toString()).to("userId")
                .bind(albumId.toString()).to("albumId")
                // Instant itself isn't a type the driver's automatic Java ->
                // Cypher conversion understands (see Values.value(Object)) —
                // it needs an offset/zone attached, and Instant is by
                // definition UTC already, so UTC is not a lossy choice here.
                .bind(listenedAt.atOffset(ZoneOffset.UTC)).to("listenedAt")
                .run());
    }

    /**
     * Delete-then-recreate, same contract as markAlbumListened — a rating can change,
     * so any stale RATED_TRACK edge must go before the new one lands.
     * TrackRatingService catches the GraphWriteException this throws on failure.
     */
    public void rateTrack(UUID userId, UUID trackId, BigDecimal rating, Instant ratedAt) {
        write("set RATED_TRACK user=" + userId + " track=" + trackId, () ->
            neo4jClient.query("""
                    MATCH (u:User {id: $userId})
                    MATCH (tr:Track {id: $trackId})
                    OPTIONAL MATCH (u)-[old:RATED_TRACK]->(tr)
                    DELETE old
                    MERGE (u)-[r:RATED_TRACK]->(tr)
                    SET r.rating = $rating, r.ratedAt = $ratedAt
                    """)
                .bind(userId.toString()).to("userId")
                .bind(trackId.toString()).to("trackId")
                // See rateAlbum's comment — same BigDecimal/Instant conversion.
                .bind(rating.doubleValue()).to("rating")
                .bind(ratedAt.atOffset(ZoneOffset.UTC)).to("ratedAt")
                .run());
    }

    // --- Track relationships ---

    public void addPerformance(UUID artistId, UUID trackId, String role, String instrumentCode, boolean primaryCredit) {
        Map<String, Object> params = new HashMap<>();
        params.put("artistId", artistId.toString());
        params.put("trackId", trackId.toString());
        params.put("role", role);
        params.put("instrument", instrumentCode);
        params.put("primaryCredit", primaryCredit);

        write("add PERFORMED_ON artist=" + artistId + " track=" + trackId, () ->
            neo4jClient.query("""
                    MATCH (ar:Artist {id: $artistId}), (tr:Track {id: $trackId})
                    MERGE (ar)-[p:PERFORMED_ON]->(tr)
                    SET p.role = $role, p.instrument = $instrument, p.primaryCredit = $primaryCredit
                    """)
                .bindAll(params)
                .run());
    }

    public void replaceTrackStyles(UUID trackId, List<String> styleCodes) {
        replaceTags("Track", trackId, "BELONGS_TO", "Style", styleCodes);
    }

    public void replaceTrackMoods(UUID trackId, List<String> moodCodes) {
        replaceTags("Track", trackId, "EVOKES_MOOD", "Mood", moodCodes);
    }

    public void replaceTrackContexts(UUID trackId, List<String> contextCodes) {
        replaceTags("Track", trackId, "PERFECT_FOR", "Context", contextCodes);
    }

    public void replaceRhythms(UUID trackId, List<String> rhythmCodes) {
        replaceTags("Track", trackId, "HAS_RHYTHM", "Rhythm", rhythmCodes);
    }

    public void replaceFeaturedInstruments(UUID trackId, List<String> instrumentCodes) {
        replaceTags("Track", trackId, "FEATURES_INSTRUMENT", "Instrument", instrumentCodes);
    }

    /** Same MATCH-then-MERGE / non-swallowed-here contract as markAlbumListened. */
    public void markTrackListened(UUID userId, UUID trackId, Instant listenedAt) {
        write("add LISTENED user=" + userId + " track=" + trackId, () ->
            neo4jClient.query("""
                    MATCH (u:User {id: $userId})
                    MATCH (tr:Track {id: $trackId})
                    MERGE (u)-[l:LISTENED]->(tr)
                    SET l.listenedAt = $listenedAt
                    """)
                .bind(userId.toString()).to("userId")
                .bind(trackId.toString()).to("trackId")
                // See markAlbumListened's comment — same Instant conversion.
                .bind(listenedAt.atOffset(ZoneOffset.UTC)).to("listenedAt")
                .run());
    }

    public List<VocabularyTag> getTrackStyles(UUID trackId) {
        return getTags("Track", trackId, "BELONGS_TO", "Style");
    }

    public List<VocabularyTag> getTrackMoods(UUID trackId) {
        return getTags("Track", trackId, "EVOKES_MOOD", "Mood");
    }

    public List<VocabularyTag> getTrackContexts(UUID trackId) {
        return getTags("Track", trackId, "PERFECT_FOR", "Context");
    }

    public List<VocabularyTag> getTrackRhythms(UUID trackId) {
        return getTags("Track", trackId, "HAS_RHYTHM", "Rhythm");
    }

    public List<VocabularyTag> getTrackFeaturedInstruments(UUID trackId) {
        return getTags("Track", trackId, "FEATURES_INSTRUMENT", "Instrument");
    }

    public List<TrackPerformerEntry> getTrackPerformers(UUID trackId) {
        return read("read performers for track=" + trackId, () ->
            neo4jClient.query("""
                    MATCH (ar:Artist)-[p:PERFORMED_ON]->(tr:Track {id: $trackId})
                    RETURN ar.id AS artistId, ar.name AS artistName, p.role AS role,
                           p.instrument AS instrument, p.primaryCredit AS primaryCredit
                    """)
                .bind(trackId.toString()).to("trackId")
                .fetch()
                .all()
                .stream()
                .map(row -> new TrackPerformerEntry(
                    UUID.fromString((String) row.get("artistId")),
                    (String) row.get("artistName"),
                    (String) row.get("role"),
                    (String) row.get("instrument"),
                    Boolean.TRUE.equals(row.get("primaryCredit"))
                ))
                .toList());
    }

    /**
     * Every track's placement within its album, keyed by trackId — used to
     * assign the next upload-order position (see {@code TrackService#createOrUpdateTrack}).
     */
    public List<TrackPlacement> getTrackPlacements(UUID albumId) {
        return read("read track placements for album=" + albumId, () ->
            neo4jClient.query("""
                    MATCH (:Album {id: $albumId})-[c:CONTAINS]->(tr:Track)
                    RETURN tr.id AS trackId, c.trackNumber AS trackNumber
                    """)
                .bind(albumId.toString()).to("albumId")
                .fetch()
                .all()
                .stream()
                .map(row -> new TrackPlacement(
                    UUID.fromString((String) row.get("trackId")),
                    row.get("trackNumber") == null ? null : ((Number) row.get("trackNumber")).intValue()
                ))
                .toList());
    }

    /** Single-track placement lookup — for building a TrackDto right after creation. */
    public TrackPlacement getTrackPlacement(UUID trackId) {
        return read("read placement for track=" + trackId, () ->
            neo4jClient.query("""
                    MATCH (:Album)-[c:CONTAINS]->(tr:Track {id: $trackId})
                    RETURN c.trackNumber AS trackNumber
                    """)
                .bind(trackId.toString()).to("trackId")
                .fetch()
                .one()
                .map(row -> new TrackPlacement(
                    trackId,
                    row.get("trackNumber") == null ? null : ((Number) row.get("trackNumber")).intValue()
                ))
                .orElse(new TrackPlacement(trackId, null)));
    }

    // --- Artist relationships ---

    public void setPrimaryInstrument(UUID artistId, String instrumentCode) {
        write("add PLAYS_INSTRUMENT artist=" + artistId + " instrument=" + instrumentCode, () ->
            neo4jClient.query("""
                    MATCH (ar:Artist {id: $artistId}), (i:Instrument {code: $instrumentCode})
                    MERGE (ar)-[:PLAYS_INSTRUMENT]->(i)
                    """)
                .bind(artistId.toString()).to("artistId")
                .bind(instrumentCode).to("instrumentCode")
                .run());
    }

    public void replaceArtistStyles(UUID artistId, List<String> styleCodes) {
        replaceTags("Artist", artistId, "HAS_STYLE", "Style", styleCodes);
    }

    public void replaceArtistContexts(UUID artistId, List<String> contextCodes) {
        replaceTags("Artist", artistId, "PERFECT_FOR", "Context", contextCodes);
    }

    /**
     * Creates/updates the {@code SIMILAR_TO} edge from one artist to
     * another, with a curated {@code reason} — see {@link
     * #getSimilarArtists}, which reads it back. Unidirectional by default
     * ({@code artistId -> similarArtistId} only); {@code bidirectional=true}
     * also creates the reverse edge with the same {@code reason}.
     *
     * @param artistId        the artist
     * @param similarArtistId the similar artist
     * @param reason          the curated reason, shown as-is on both edges if bidirectional
     * @param bidirectional   whether to also create the reverse edge
     */
    public void addSimilarArtist(UUID artistId, UUID similarArtistId, String reason, boolean bidirectional) {
        write("add SIMILAR_TO artist=" + artistId + " similar=" + similarArtistId, () -> {
            neo4jClient.query("""
                    MATCH (a1:Artist {id: $artistId}), (a2:Artist {id: $similarArtistId})
                    MERGE (a1)-[r:SIMILAR_TO]->(a2)
                    SET r.reason = $reason
                    """)
                .bind(artistId.toString()).to("artistId")
                .bind(similarArtistId.toString()).to("similarArtistId")
                .bind(reason).to("reason")
                .run();

            if (bidirectional) {
                neo4jClient.query("""
                        MATCH (a1:Artist {id: $artistId}), (a2:Artist {id: $similarArtistId})
                        MERGE (a2)-[r:SIMILAR_TO]->(a1)
                        SET r.reason = $reason
                        """)
                    .bind(artistId.toString()).to("artistId")
                    .bind(similarArtistId.toString()).to("similarArtistId")
                    .bind(reason).to("reason")
                    .run();
            }
        });
    }

    /**
     * Removes the {@code SIMILAR_TO} edge from one artist to another, if it
     * exists — a no-op otherwise. {@code bidirectional=true} also removes
     * the reverse edge, mirroring {@link #addSimilarArtist}; it doesn't
     * check whether the reverse edge was actually created bidirectionally
     * in the first place, just removes it if present.
     *
     * @param artistId        the artist
     * @param similarArtistId the similar artist
     * @param bidirectional   whether to also remove the reverse edge
     */
    public void removeSimilarArtist(UUID artistId, UUID similarArtistId, boolean bidirectional) {
        write("remove SIMILAR_TO artist=" + artistId + " similar=" + similarArtistId, () -> {
            neo4jClient.query("""
                    MATCH (a1:Artist {id: $artistId})-[r:SIMILAR_TO]->(a2:Artist {id: $similarArtistId})
                    DELETE r
                    """)
                .bind(artistId.toString()).to("artistId")
                .bind(similarArtistId.toString()).to("similarArtistId")
                .run();

            if (bidirectional) {
                neo4jClient.query("""
                        MATCH (a2:Artist {id: $similarArtistId})-[r:SIMILAR_TO]->(a1:Artist {id: $artistId})
                        DELETE r
                        """)
                    .bind(artistId.toString()).to("artistId")
                    .bind(similarArtistId.toString()).to("similarArtistId")
                    .run();
            }
        });
    }

    public List<VocabularyTag> getArtistInstruments(UUID artistId) {
        return getTags("Artist", artistId, "PLAYS_INSTRUMENT", "Instrument");
    }

    public List<VocabularyTag> getArtistStyles(UUID artistId) {
        return getTags("Artist", artistId, "HAS_STYLE", "Style");
    }

    public List<VocabularyTag> getArtistContexts(UUID artistId) {
        return getTags("Artist", artistId, "PERFECT_FOR", "Context");
    }

    public List<SimilarArtistEntry> getSimilarArtists(UUID artistId) {
        return read("read SIMILAR_TO for artist=" + artistId, () ->
            neo4jClient.query("""
                    MATCH (ar:Artist {id: $artistId})-[r:SIMILAR_TO]->(other:Artist)
                    RETURN other.id AS artistId, other.name AS name, r.reason AS reason
                    """)
                .bind(artistId.toString()).to("artistId")
                .fetch()
                .all()
                .stream()
                .map(row -> new SimilarArtistEntry(
                    UUID.fromString((String) row.get("artistId")),
                    (String) row.get("name"),
                    (String) row.get("reason")
                ))
                .toList());
    }

    @SuppressWarnings("unchecked")
    public List<ArtistAlbumAppearance> getArtistAlbumAppearances(UUID artistId) {
        return read("read album appearances for artist=" + artistId, () ->
            neo4jClient.query("""
                    MATCH (ar:Artist {id: $artistId})-[r:LEADER_OF|SIDEMAN_ON]->(al:Album)
                    RETURN al.id AS albumId, al.name AS albumName, type(r) AS relType, r.instruments AS instruments
                    """)
                .bind(artistId.toString()).to("artistId")
                .fetch()
                .all()
                .stream()
                .map(row -> new ArtistAlbumAppearance(
                    UUID.fromString((String) row.get("albumId")),
                    (String) row.get("albumName"),
                    "LEADER_OF".equals(row.get("relType")) ? "LEADER" : "SIDEMAN",
                    row.get("instruments") == null ? List.of() : (List<String>) row.get("instruments")
                ))
                .toList());
    }

    public List<ArtistTrackAppearance> getArtistTrackAppearances(UUID artistId) {
        return read("read track appearances for artist=" + artistId, () ->
            neo4jClient.query("""
                    MATCH (ar:Artist {id: $artistId})-[p:PERFORMED_ON]->(tr:Track)
                    RETURN tr.id AS trackId, tr.name AS trackName, p.role AS role,
                           p.instrument AS instrument, p.primaryCredit AS primaryCredit
                    """)
                .bind(artistId.toString()).to("artistId")
                .fetch()
                .all()
                .stream()
                .map(row -> new ArtistTrackAppearance(
                    UUID.fromString((String) row.get("trackId")),
                    (String) row.get("trackName"),
                    (String) row.get("role"),
                    (String) row.get("instrument"),
                    Boolean.TRUE.equals(row.get("primaryCredit"))
                ))
                .toList());
    }

    // --- Playlist relationships ---

    /** Same MATCH-then-MERGE / non-swallowed-here contract as markAlbumListened/markTrackListened. */
    public void markPlaylistListened(UUID userId, UUID playlistId, Instant listenedAt) {
        write("add LISTENED user=" + userId + " playlist=" + playlistId, () ->
            neo4jClient.query("""
                    MATCH (u:User {id: $userId})
                    MATCH (p:Playlist {id: $playlistId})
                    MERGE (u)-[l:LISTENED]->(p)
                    SET l.listenedAt = $listenedAt
                    """)
                .bind(userId.toString()).to("userId")
                .bind(playlistId.toString()).to("playlistId")
                // See markAlbumListened's comment — same Instant conversion.
                .bind(listenedAt.atOffset(ZoneOffset.UTC)).to("listenedAt")
                .run());
    }

    /** MATCH-then-MERGE, single edge — PlaylistService.addTrack's sync target. */
    public void addPlaylistTrack(UUID playlistId, UUID trackId, int position) {
        write("add BELONGS_TO track=" + trackId + " playlist=" + playlistId, () ->
            neo4jClient.query("""
                    MATCH (tr:Track {id: $trackId}), (p:Playlist {id: $playlistId})
                    MERGE (tr)-[b:BELONGS_TO]->(p)
                    SET b.position = $position
                    """)
                .bind(trackId.toString()).to("trackId")
                .bind(playlistId.toString()).to("playlistId")
                .bind(position).to("position")
                .run());
    }

    /**
     * Full node delete — {@code DETACH DELETE} removes every relationship
     * touching this node too (BELONGS_TO, style/mood/context tag edges,
     * LISTENED, ...), regardless of direction, so no separate cleanup per
     * edge type is needed. PlaylistService.delete's sync target. A no-op if
     * the node is already gone (e.g. a retried sync after the first attempt
     * actually succeeded).
     */
    public void deletePlaylistNode(UUID playlistId) {
        write("delete Playlist node id=" + playlistId, () ->
            neo4jClient.query("MATCH (p:Playlist {id: $id}) DETACH DELETE p")
                .bind(playlistId.toString()).to("id")
                .run());
    }

    /** Single edge delete — PlaylistService.removeTrack's sync target. */
    public void removePlaylistTrack(UUID playlistId, UUID trackId) {
        write("remove BELONGS_TO track=" + trackId + " playlist=" + playlistId, () ->
            neo4jClient.query("""
                    MATCH (:Track {id: $trackId})-[r:BELONGS_TO]->(:Playlist {id: $playlistId})
                    DELETE r
                    """)
                .bind(trackId.toString()).to("trackId")
                .bind(playlistId.toString()).to("playlistId")
                .run());
    }

    /**
     * MATCH on the existing edges only — no MERGE, no DELETE. The set of
     * BELONGS_TO edges doesn't change on a reorder, only their position, so
     * unlike addPlaylistTrack/removePlaylistTrack this can't create or remove
     * relationships, only update the ones already there.
     */
    public void reorderPlaylistTracks(UUID playlistId, Map<UUID, Integer> positions) {
        write("reorder BELONGS_TO tracks for playlist=" + playlistId, () -> {
            if (positions.isEmpty()) {
                return;
            }
            List<Map<String, Object>> rows = positions.entrySet().stream()
                .map(entry -> Map.<String, Object>of("trackId", entry.getKey().toString(), "position", entry.getValue()))
                .toList();
            neo4jClient.query("""
                    MATCH (p:Playlist {id: $playlistId})
                    UNWIND $rows AS row
                    MATCH (tr:Track {id: row.trackId})-[b:BELONGS_TO]->(p)
                    SET b.position = row.position
                    """)
                .bind(playlistId.toString()).to("playlistId")
                .bind(rows).to("rows")
                .run();
        });
    }

    /**
     * Clear + recreate all three vocab relationship types together (style/mood/
     * context) — always recreated as one unit from PlaylistService. Neo4j-only,
     * same failure policy as Album's addStyle/addMood/addContext: synchronous,
     * propagates GraphWriteException on failure (no Postgres table backing this,
     * nothing to fall back to). Targets nodes already seeded by VocabularySeeder;
     * never creates vocabulary nodes here.
     */
    public void setPlaylistTags(
        UUID playlistId, List<String> styleCodes, List<String> moodCodes, List<String> contextCodes, List<String> instrumentCodes
    ) {
        write("set tags for playlist=" + playlistId, () -> {
            replaceVocabEdges("Playlist", playlistId, "Style", "BELONGS_TO", styleCodes);
            replaceVocabEdges("Playlist", playlistId, "Mood", "EVOKES_MOOD", moodCodes);
            replaceVocabEdges("Playlist", playlistId, "Context", "PERFECT_FOR", contextCodes);
            replaceVocabEdges("Playlist", playlistId, "Instrument", "FEATURES_INSTRUMENT", instrumentCodes);
        });
    }

    /** Same as {@link #setPlaylistTags} — a Series' style/mood/context/featured instruments, always recreated as one unit from SeriesService. */
    public void setSeriesTags(
        UUID seriesId, List<String> styleCodes, List<String> moodCodes, List<String> contextCodes, List<String> instrumentCodes
    ) {
        write("set tags for series=" + seriesId, () -> {
            replaceVocabEdges("Series", seriesId, "Style", "BELONGS_TO", styleCodes);
            replaceVocabEdges("Series", seriesId, "Mood", "EVOKES_MOOD", moodCodes);
            replaceVocabEdges("Series", seriesId, "Context", "PERFECT_FOR", contextCodes);
            replaceVocabEdges("Series", seriesId, "Instrument", "FEATURES_INSTRUMENT", instrumentCodes);
        });
    }

    public List<VocabularyTag> getPlaylistStyles(UUID playlistId) {
        return getTags("Playlist", playlistId, "BELONGS_TO", "Style");
    }

    public List<VocabularyTag> getPlaylistMoods(UUID playlistId) {
        return getTags("Playlist", playlistId, "EVOKES_MOOD", "Mood");
    }

    public List<VocabularyTag> getPlaylistContexts(UUID playlistId) {
        return getTags("Playlist", playlistId, "PERFECT_FOR", "Context");
    }

    public List<VocabularyTag> getPlaylistFeaturedInstruments(UUID playlistId) {
        return getTags("Playlist", playlistId, "FEATURES_INSTRUMENT", "Instrument");
    }

    public List<VocabularyTag> getSeriesStyles(UUID seriesId) {
        return getTags("Series", seriesId, "BELONGS_TO", "Style");
    }

    public List<VocabularyTag> getSeriesMoods(UUID seriesId) {
        return getTags("Series", seriesId, "EVOKES_MOOD", "Mood");
    }

    public List<VocabularyTag> getSeriesContexts(UUID seriesId) {
        return getTags("Series", seriesId, "PERFECT_FOR", "Context");
    }

    public List<VocabularyTag> getSeriesFeaturedInstruments(UUID seriesId) {
        return getTags("Series", seriesId, "FEATURES_INSTRUMENT", "Instrument");
    }

    /**
     * Batch versions of getPlaylistStyles/Moods/Contexts/FeaturedInstruments —
     * one query for a whole catalogue page instead of one per playlist (see
     * PlaylistService.toCatalogueDto). Same shape as getTagsForAlbum,
     * grouped by playlistId instead of trackId; a playlist with none of this
     * tag type simply has no entry, callers treat that as an empty list.
     */
    public Map<UUID, List<VocabularyTag>> getPlaylistStylesBatch(List<UUID> playlistIds) {
        return getTagsForPlaylists(playlistIds, "BELONGS_TO", "Style");
    }

    public Map<UUID, List<VocabularyTag>> getPlaylistMoodsBatch(List<UUID> playlistIds) {
        return getTagsForPlaylists(playlistIds, "EVOKES_MOOD", "Mood");
    }

    public Map<UUID, List<VocabularyTag>> getPlaylistContextsBatch(List<UUID> playlistIds) {
        return getTagsForPlaylists(playlistIds, "PERFECT_FOR", "Context");
    }

    public Map<UUID, List<VocabularyTag>> getPlaylistFeaturedInstrumentsBatch(List<UUID> playlistIds) {
        return getTagsForPlaylists(playlistIds, "FEATURES_INSTRUMENT", "Instrument");
    }

    /** Batch versions of getSeriesStyles/Moods/Contexts/FeaturedInstruments — one query for a whole catalogue page instead of one per series. */
    public Map<UUID, List<VocabularyTag>> getSeriesStylesBatch(List<UUID> seriesIds) {
        return getTagsForSeries(seriesIds, "BELONGS_TO", "Style");
    }

    public Map<UUID, List<VocabularyTag>> getSeriesMoodsBatch(List<UUID> seriesIds) {
        return getTagsForSeries(seriesIds, "EVOKES_MOOD", "Mood");
    }

    public Map<UUID, List<VocabularyTag>> getSeriesContextsBatch(List<UUID> seriesIds) {
        return getTagsForSeries(seriesIds, "PERFECT_FOR", "Context");
    }

    public Map<UUID, List<VocabularyTag>> getSeriesFeaturedInstrumentsBatch(List<UUID> seriesIds) {
        return getTagsForSeries(seriesIds, "FEATURES_INSTRUMENT", "Instrument");
    }

    private Map<UUID, List<VocabularyTag>> getTagsForPlaylists(List<UUID> playlistIds, String relationshipType, String targetLabel) {
        if (playlistIds.isEmpty()) {
            return Map.of();
        }
        List<String> ids = playlistIds.stream().map(UUID::toString).toList();
        return read("read " + relationshipType + " for playlists=" + playlistIds.size(), () ->
            neo4jClient.query(
                    "MATCH (p:Playlist)-[:" + relationshipType + "]->(n:" + targetLabel + ") "
                        + "WHERE p.id IN $playlistIds "
                        + "RETURN p.id AS playlistId, n.code AS code, n.label AS label")
                .bind(ids).to("playlistIds")
                .fetch()
                .all()
                .stream()
                .collect(Collectors.groupingBy(
                    row -> UUID.fromString((String) row.get("playlistId")),
                    Collectors.mapping(
                        row -> new VocabularyTag((String) row.get("code"), (String) row.get("label")),
                        Collectors.toList()
                    )
                )));
    }

    private Map<UUID, List<VocabularyTag>> getTagsForSeries(List<UUID> seriesIds, String relationshipType, String targetLabel) {
        if (seriesIds.isEmpty()) {
            return Map.of();
        }
        List<String> ids = seriesIds.stream().map(UUID::toString).toList();
        return read("read " + relationshipType + " for series=" + seriesIds.size(), () ->
            neo4jClient.query(
                    "MATCH (s:Series)-[:" + relationshipType + "]->(n:" + targetLabel + ") "
                        + "WHERE s.id IN $seriesIds "
                        + "RETURN s.id AS seriesId, n.code AS code, n.label AS label")
                .bind(ids).to("seriesIds")
                .fetch()
                .all()
                .stream()
                .collect(Collectors.groupingBy(
                    row -> UUID.fromString((String) row.get("seriesId")),
                    Collectors.mapping(
                        row -> new VocabularyTag((String) row.get("code"), (String) row.get("label")),
                        Collectors.toList()
                    )
                )));
    }

    private void replaceVocabEdges(String sourceLabel, UUID sourceId, String vocabLabel, String relationshipType, List<String> codes) {
        neo4jClient.query(
                "MATCH (:" + sourceLabel + " {id: $sourceId})-[r:" + relationshipType + "]->(:" + vocabLabel + ") DELETE r")
            .bind(sourceId.toString()).to("sourceId")
            .run();

        if (!codes.isEmpty()) {
            neo4jClient.query(
                    "MATCH (src:" + sourceLabel + " {id: $sourceId}) "
                        + "UNWIND $codes AS code "
                        + "MATCH (v:" + vocabLabel + " {code: code}) "
                        + "MERGE (src)-[:" + relationshipType + "]->(v)")
                .bind(sourceId.toString()).to("sourceId")
                .bind(codes).to("codes")
                .run();
        }
    }

    private List<VocabularyTag> getTags(String sourceLabel, UUID sourceId, String relationshipType, String targetLabel) {
        return read("read " + relationshipType + " for " + sourceLabel + "=" + sourceId, () ->
            neo4jClient.query(
                    "MATCH (src:" + sourceLabel + " {id: $sourceId})-[:" + relationshipType + "]->(n:" + targetLabel + ") "
                        + "RETURN n.code AS code, n.label AS label")
                .bind(sourceId.toString()).to("sourceId")
                .fetch()
                .all()
                .stream()
                .map(row -> new VocabularyTag((String) row.get("code"), (String) row.get("label")))
                .toList());
    }

    // Full replace, not add: clears every existing relationship of this type
    // from the entity first, then recreates one per code — same "DELETE then
    // conditionally UNWIND+MERGE" shape as setHighlightedTracks. Labels/
    // relationshipType are always hardcoded call-site constants (see the
    // replace* methods above), never user input, so string-building the
    // query text is safe — Cypher has no way to parameterize a label or
    // relationship type as a bind variable.
    private void replaceTags(String entityLabel, UUID entityId, String relationshipType, String targetLabel, List<String> codes) {
        write("replace " + relationshipType + " " + entityLabel + "=" + entityId, () -> {
            neo4jClient.query(
                    "MATCH (e:" + entityLabel + " {id: $entityId})-[r:" + relationshipType + "]->(:" + targetLabel + ") "
                        + "DELETE r")
                .bind(entityId.toString()).to("entityId")
                .run();

            if (!codes.isEmpty()) {
                neo4jClient.query(
                        "MATCH (e:" + entityLabel + " {id: $entityId}) "
                            + "UNWIND $codes AS code "
                            + "MATCH (t:" + targetLabel + " {code: code}) "
                            + "MERGE (e)-[:" + relationshipType + "]->(t)")
                    .bind(entityId.toString()).to("entityId")
                    .bind(codes).to("codes")
                    .run();
            }
        });
    }

    // --- graphFilter (agent tool) ---

    /**
     * Ranks Track candidates by graph-topology overlap with the given
     * vocabulary filters, optionally narrowed to one album's/artist's own
     * tracks via {@code albumId}/{@code artistId} (either or both may be
     * {@code null} — a pure vocabulary search with no scope). TRACK is the
     * only entity type this searches: Album/Artist are no longer
     * independently recommendable, only resolvable as scope (see {@code
     * ResolveJazzlogsEntityTool}) — this method IS the "search this
     * album's/artist's tracks" capability that scope feeds into.
     *
     * <p>Matching is permissive by design (OR, not AND): a candidate doesn't
     * need to match every requested dimension, matching at least one is
     * enough — that's what {@code matchCount > 0} enforces below, nothing
     * stronger. When no vocabulary was requested at all (every code list
     * empty) but a scope was, {@code requireVocabMatch} relaxes that so a
     * pure "tracks from this album" query still returns candidates. Ranking
     * (by matchCount, i.e. {@code matchedDimensions.size()}) is a separate
     * concern from eligibility, done right here via {@code ORDER BY
     * matchCount DESC LIMIT $limit} — {@code GraphFilterService} never
     * re-sorts or re-clamps what comes back.
     *
     * <p>Each dimension is a pattern comprehension — e.g. {@code
     * [(tr)-[:BELONGS_TO]->(s:Style) WHERE s.code IN $styleCodes | s.code]}
     * — collecting the actual codes that matched, not just a 0/1 flag: the
     * LLM gets to see e.g. "matched Mood=RELAXED" instead of a bare count.
     * An empty codes list for a dimension naturally yields an empty match
     * list ({@code s.code IN []} is never true), so "not requested" and
     * "requested but nothing matched" both fall out without a separate
     * guard. {@code excludeAlreadyRated} checks RATED_TRACK, not RATED —
     * that's the Track-level rating relationship (see {@link #rateTrack}).
     */
    public List<GraphCandidate> findTrackCandidates(
        List<String> styleCodes, List<String> moodCodes, List<String> contextCodes, List<String> rhythmCodes, List<String> instrumentCodes,
        UUID albumId, UUID artistId, UUID userId, boolean excludeListened, boolean excludeAlreadyRated, int limit
    ) {
        boolean requireVocabMatch = !styleCodes.isEmpty() || !moodCodes.isEmpty() || !contextCodes.isEmpty()
            || !rhythmCodes.isEmpty() || !instrumentCodes.isEmpty();

        return read("find Track candidates for graphFilter", () ->
            neo4jClient.query("""
                    MATCH (tr:Track)
                    WHERE ($excludeListened = false OR NOT EXISTS { (u:User {id: $userId})-[:LISTENED]->(tr) })
                      AND ($excludeRated = false OR NOT EXISTS { (u:User {id: $userId})-[:RATED_TRACK]->(tr) })
                      AND ($albumId IS NULL OR EXISTS { (:Album {id: $albumId})-[:CONTAINS]->(tr) })
                      AND ($artistId IS NULL OR EXISTS { (:Artist {id: $artistId})-[:PERFORMED_ON]->(tr) })
                    WITH tr,
                        [(tr)-[:BELONGS_TO]->(s:Style) WHERE s.code IN $styleCodes | s.code] AS styleMatches,
                        [(tr)-[:EVOKES_MOOD]->(m:Mood) WHERE m.code IN $moodCodes | m.code] AS moodMatches,
                        [(tr)-[:PERFECT_FOR]->(c:Context) WHERE c.code IN $contextCodes | c.code] AS contextMatches,
                        [(tr)-[:HAS_RHYTHM]->(r:Rhythm) WHERE r.code IN $rhythmCodes | r.code] AS rhythmMatches,
                        [(tr)-[:FEATURES_INSTRUMENT]->(i:Instrument) WHERE i.code IN $instrumentCodes | i.code] AS instrumentMatches
                    WITH tr, styleMatches, moodMatches, contextMatches, rhythmMatches, instrumentMatches,
                        (size(styleMatches) + size(moodMatches) + size(contextMatches) + size(rhythmMatches) + size(instrumentMatches)) AS matchCount
                    WHERE $requireVocabMatch = false OR matchCount > 0
                    RETURN tr.id AS entityId, tr.name AS entityName, styleMatches, moodMatches, contextMatches, rhythmMatches, instrumentMatches
                    ORDER BY matchCount DESC
                    LIMIT $limit
                    """)
                .bind(userId.toString()).to("userId")
                .bind(excludeListened).to("excludeListened")
                .bind(excludeAlreadyRated).to("excludeRated")
                .bind(albumId == null ? null : albumId.toString()).to("albumId")
                .bind(artistId == null ? null : artistId.toString()).to("artistId")
                .bind(styleCodes).to("styleCodes")
                .bind(moodCodes).to("moodCodes")
                .bind(contextCodes).to("contextCodes")
                .bind(rhythmCodes).to("rhythmCodes")
                .bind(instrumentCodes).to("instrumentCodes")
                .bind(requireVocabMatch).to("requireVocabMatch")
                .bind(limit).to("limit")
                .fetch()
                .all()
                .stream()
                .map(row -> new GraphCandidate(
                    CatalogItemType.TRACK,
                    UUID.fromString((String) row.get("entityId")),
                    (String) row.get("entityName"),
                    concatMatches(List.of(
                        dimensionMatches(VocabularyDimension.STYLE, row.get("styleMatches")),
                        dimensionMatches(VocabularyDimension.MOOD, row.get("moodMatches")),
                        dimensionMatches(VocabularyDimension.CONTEXT, row.get("contextMatches")),
                        dimensionMatches(VocabularyDimension.RHYTHM, row.get("rhythmMatches")),
                        dimensionMatches(VocabularyDimension.INSTRUMENT, row.get("instrumentMatches"))
                    ))
                ))
                .toList());
    }

    // One dimension's raw match-code list off a candidate row, already cast
    // and paired with which dimension it came from — the only place the
    // unchecked Object -> List<String> cast happens, so concatMatches below
    // stays fully typed. rawCodes is always a List<String> in practice: it
    // comes straight off a Neo4jClient row column bound from a Cypher
    // list-comprehension (see e.g. findAlbumCandidates' styleMatches).
    @SuppressWarnings("unchecked")
    private static DimensionMatches dimensionMatches(VocabularyDimension dimension, Object rawCodes) {
        return new DimensionMatches(dimension, (List<String>) rawCodes);
    }

    private record DimensionMatches(VocabularyDimension dimension, List<String> codes) {
    }

    // Flattens however many dimensions a candidate row has into one
    // matchedDimensions list — a plain List rather than varargs so each
    // finder above can pass exactly the dimensions that apply to its label
    // (3 for Album/Artist, 4 for Track) without a shared column count.
    private static List<MatchedDimension> concatMatches(List<DimensionMatches> perDimension) {
        List<MatchedDimension> matches = new ArrayList<>();
        for (DimensionMatches entry : perDimension) {
            entry.codes().forEach(code -> matches.add(new MatchedDimension(entry.dimension(), code)));
        }
        return matches;
    }

    private void write(String description, Runnable action) {
        try {
            action.run();
        } catch (Exception ex) {
            log.error("Neo4j write failed: {}", description, ex);
            throw new GraphWriteException("Neo4j write failed: " + description, ex);
        }
    }

    private <T> T read(String description, Supplier<T> action) {
        try {
            return action.get();
        } catch (Exception ex) {
            log.error("Neo4j read failed: {}", description, ex);
            throw new GraphWriteException("Neo4j read failed: " + description, ex);
        }
    }

    public void createUserNode(UUID userId) {
        write("create User node id=" + userId, () ->
            neo4jClient.query("MERGE (u:User {id: $id})")
                .bind(userId.toString()).to("id")
                .run());
    }
}
