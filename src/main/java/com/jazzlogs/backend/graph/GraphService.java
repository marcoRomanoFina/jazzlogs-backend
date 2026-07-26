package com.jazzlogs.backend.graph;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

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
 *
 * TODO: once this talks to Aura in production, evaluate wrapping neo4jClient
 * calls with a circuit breaker (no Resilience4j or equivalent in the repo yet —
 * this would be the first use). Not worth adding speculatively; revisit once
 * real prod failure patterns from Aura are observed.
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

    public void addTrackToAlbum(UUID albumId, UUID trackId, int trackNumber, String trackRole) {
        write("add CONTAINS album=" + albumId + " track=" + trackId, () ->
            neo4jClient.query("""
                    MATCH (al:Album {id: $albumId}), (tr:Track {id: $trackId})
                    MERGE (al)-[c:CONTAINS]->(tr)
                    SET c.trackNumber = $trackNumber, c.trackRole = $trackRole
                    """)
                .bind(albumId.toString()).to("albumId")
                .bind(trackId.toString()).to("trackId")
                .bind(trackNumber).to("trackNumber")
                .bind(trackRole).to("trackRole")
                .run());
    }

    public void setAlbumLeader(UUID artistId, UUID albumId) {
        write("set LEADER_OF artist=" + artistId + " album=" + albumId, () ->
            neo4jClient.query("""
                    MATCH (ar:Artist {id: $artistId}), (al:Album {id: $albumId})
                    MERGE (ar)-[:LEADER_OF]->(al)
                    """)
                .bind(artistId.toString()).to("artistId")
                .bind(albumId.toString()).to("albumId")
                .run());
    }

    public void addSideman(UUID artistId, UUID albumId, List<String> instruments) {
        write("add SIDEMAN_ON artist=" + artistId + " album=" + albumId, () ->
            neo4jClient.query("""
                    MATCH (ar:Artist {id: $artistId}), (al:Album {id: $albumId})
                    MERGE (ar)-[s:SIDEMAN_ON]->(al)
                    SET s.instruments = $instruments
                    """)
                .bind(artistId.toString()).to("artistId")
                .bind(albumId.toString()).to("albumId")
                .bind(instruments).to("instruments")
                .run());
    }

    public void markAsEntryPoint(UUID albumId, UUID artistId) {
        write("add ENTRY_POINT_TO album=" + albumId + " artist=" + artistId, () ->
            neo4jClient.query("""
                    MATCH (al:Album {id: $albumId}), (ar:Artist {id: $artistId})
                    MERGE (al)-[:ENTRY_POINT_TO]->(ar)
                    """)
                .bind(albumId.toString()).to("albumId")
                .bind(artistId.toString()).to("artistId")
                .run());
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
                    MATCH (u:User {id: $userId}), (al:Album {id: $albumId})
                    MERGE (u)-[l:LISTENED]->(al)
                    SET l.listenedAt = $listenedAt
                    """)
                .bind(userId.toString()).to("userId")
                .bind(albumId.toString()).to("albumId")
                .bind(listenedAt).to("listenedAt")
                .run());
    }

    /**
     * Delete-then-recreate, not MERGE+SET — a rating can change, so any stale
     * RATED edge from a previous review must go before the new one lands.
     * Same MATCH-then-MERGE / non-swallowed-here contract as markAlbumListened;
     * ReviewService catches the GraphWriteException this throws on failure.
     */
    public void rateAlbum(UUID userId, UUID albumId, BigDecimal rating, Instant ratedAt) {
        write("set RATED user=" + userId + " album=" + albumId, () ->
            neo4jClient.query("""
                    MATCH (u:User {id: $userId}), (al:Album {id: $albumId})
                    OPTIONAL MATCH (u)-[old:RATED]->(al)
                    DELETE old
                    MERGE (u)-[r:RATED]->(al)
                    SET r.rating = $rating, r.ratedAt = $ratedAt
                    """)
                .bind(userId.toString()).to("userId")
                .bind(albumId.toString()).to("albumId")
                .bind(rating).to("rating")
                .bind(ratedAt).to("ratedAt")
                .run());
    }

    public void addStyle(UUID albumId, String styleCode) {
        write("add BELONGS_TO album=" + albumId + " style=" + styleCode, () ->
            neo4jClient.query("""
                    MATCH (al:Album {id: $albumId}), (s:Style {code: $styleCode})
                    MERGE (al)-[:BELONGS_TO]->(s)
                    """)
                .bind(albumId.toString()).to("albumId")
                .bind(styleCode).to("styleCode")
                .run());
    }

    public void addMood(UUID albumId, String moodCode) {
        write("add EVOKES_MOOD album=" + albumId + " mood=" + moodCode, () ->
            neo4jClient.query("""
                    MATCH (al:Album {id: $albumId}), (m:Mood {code: $moodCode})
                    MERGE (al)-[:EVOKES_MOOD]->(m)
                    """)
                .bind(albumId.toString()).to("albumId")
                .bind(moodCode).to("moodCode")
                .run());
    }

    public void addContext(UUID albumId, String contextCode) {
        write("add PERFECT_FOR album=" + albumId + " context=" + contextCode, () ->
            neo4jClient.query("""
                    MATCH (al:Album {id: $albumId}), (c:Context {code: $contextCode})
                    MERGE (al)-[:PERFECT_FOR]->(c)
                    """)
                .bind(albumId.toString()).to("albumId")
                .bind(contextCode).to("contextCode")
                .run());
    }

    public List<VocabularyTag> getStyles(UUID albumId) {
        return getTags("Album", albumId, "BELONGS_TO", "Style");
    }

    public List<VocabularyTag> getMoods(UUID albumId) {
        return getTags("Album", albumId, "EVOKES_MOOD", "Mood");
    }

    public List<VocabularyTag> getContexts(UUID albumId) {
        return getTags("Album", albumId, "PERFECT_FOR", "Context");
    }

    @SuppressWarnings("unchecked")
    public List<AlbumPersonnelEntry> getPersonnel(UUID albumId) {
        return read("read personnel for album=" + albumId, () ->
            neo4jClient.query("""
                    MATCH (ar:Artist)-[r:LEADER_OF|SIDEMAN_ON]->(al:Album {id: $albumId})
                    RETURN ar.id AS artistId, ar.name AS artistName, type(r) AS relType, r.instruments AS instruments
                    """)
                .bind(albumId.toString()).to("albumId")
                .fetch()
                .all()
                .stream()
                .map(row -> new AlbumPersonnelEntry(
                    UUID.fromString((String) row.get("artistId")),
                    (String) row.get("artistName"),
                    "LEADER_OF".equals(row.get("relType")) ? "LEADER" : "SIDEMAN",
                    row.get("instruments") == null ? List.of() : (List<String>) row.get("instruments")
                ))
                .toList());
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

    public void addTrackMood(UUID trackId, String moodCode) {
        write("add EVOKES_MOOD track=" + trackId + " mood=" + moodCode, () ->
            neo4jClient.query("""
                    MATCH (tr:Track {id: $trackId}), (m:Mood {code: $moodCode})
                    MERGE (tr)-[:EVOKES_MOOD]->(m)
                    """)
                .bind(trackId.toString()).to("trackId")
                .bind(moodCode).to("moodCode")
                .run());
    }

    public void addTrackContext(UUID trackId, String contextCode) {
        write("add PERFECT_FOR track=" + trackId + " context=" + contextCode, () ->
            neo4jClient.query("""
                    MATCH (tr:Track {id: $trackId}), (c:Context {code: $contextCode})
                    MERGE (tr)-[:PERFECT_FOR]->(c)
                    """)
                .bind(trackId.toString()).to("trackId")
                .bind(contextCode).to("contextCode")
                .run());
    }

    public void addRhythm(UUID trackId, String rhythmCode) {
        write("add HAS_RHYTHM track=" + trackId + " rhythm=" + rhythmCode, () ->
            neo4jClient.query("""
                    MATCH (tr:Track {id: $trackId}), (r:Rhythm {code: $rhythmCode})
                    MERGE (tr)-[:HAS_RHYTHM]->(r)
                    """)
                .bind(trackId.toString()).to("trackId")
                .bind(rhythmCode).to("rhythmCode")
                .run());
    }

    public void addFeaturedInstrument(UUID trackId, String instrumentCode) {
        write("add FEATURES_INSTRUMENT track=" + trackId + " instrument=" + instrumentCode, () ->
            neo4jClient.query("""
                    MATCH (tr:Track {id: $trackId}), (i:Instrument {code: $instrumentCode})
                    MERGE (tr)-[:FEATURES_INSTRUMENT]->(i)
                    """)
                .bind(trackId.toString()).to("trackId")
                .bind(instrumentCode).to("instrumentCode")
                .run());
    }

    public void markTrackAsEntryPoint(UUID trackId, UUID artistId) {
        write("add ENTRY_POINT_TO track=" + trackId + " artist=" + artistId, () ->
            neo4jClient.query("""
                    MATCH (tr:Track {id: $trackId}), (ar:Artist {id: $artistId})
                    MERGE (tr)-[:ENTRY_POINT_TO]->(ar)
                    """)
                .bind(trackId.toString()).to("trackId")
                .bind(artistId.toString()).to("artistId")
                .run());
    }

    /** Same MATCH-then-MERGE / non-swallowed-here contract as markAlbumListened. */
    public void markTrackListened(UUID userId, UUID trackId, Instant listenedAt) {
        write("add LISTENED user=" + userId + " track=" + trackId, () ->
            neo4jClient.query("""
                    MATCH (u:User {id: $userId}), (tr:Track {id: $trackId})
                    MERGE (u)-[l:LISTENED]->(tr)
                    SET l.listenedAt = $listenedAt
                    """)
                .bind(userId.toString()).to("userId")
                .bind(trackId.toString()).to("trackId")
                .bind(listenedAt).to("listenedAt")
                .run());
    }

    /**
     * Clear + recreate — replaces every HIGHLIGHTED this user has on tracks of
     * this specific album with exactly trackIds (empty list just clears). Two
     * statements in one write(): delete is scoped to tracks of albumId via
     * CONTAINS, so it never touches HIGHLIGHTED edges on other albums' tracks.
     */
    public void setHighlightedTracks(UUID userId, UUID albumId, List<UUID> trackIds) {
        write("set HIGHLIGHTED user=" + userId + " album=" + albumId, () -> {
            neo4jClient.query("""
                    MATCH (u:User {id: $userId})-[r:HIGHLIGHTED]->(:Track)<-[:CONTAINS]-(al:Album {id: $albumId})
                    DELETE r
                    """)
                .bind(userId.toString()).to("userId")
                .bind(albumId.toString()).to("albumId")
                .run();

            if (!trackIds.isEmpty()) {
                List<String> trackIdStrings = trackIds.stream().map(UUID::toString).toList();
                neo4jClient.query("""
                        MATCH (u:User {id: $userId})
                        UNWIND $trackIds AS trackId
                        MATCH (tr:Track {id: trackId})
                        MERGE (u)-[:HIGHLIGHTED]->(tr)
                        """)
                    .bind(userId.toString()).to("userId")
                    .bind(trackIdStrings).to("trackIds")
                    .run();
            }
        });
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
     * Every track's placement within its album, keyed by trackId — one query for
     * the whole album instead of one per track (see AlbumService.getAlbumDetail).
     */
    public List<TrackPlacement> getTrackPlacements(UUID albumId) {
        return read("read track placements for album=" + albumId, () ->
            neo4jClient.query("""
                    MATCH (:Album {id: $albumId})-[c:CONTAINS]->(tr:Track)
                    RETURN tr.id AS trackId, c.trackNumber AS trackNumber, c.trackRole AS trackRole
                    """)
                .bind(albumId.toString()).to("albumId")
                .fetch()
                .all()
                .stream()
                .map(row -> new TrackPlacement(
                    UUID.fromString((String) row.get("trackId")),
                    row.get("trackNumber") == null ? null : ((Number) row.get("trackNumber")).intValue(),
                    (String) row.get("trackRole")
                ))
                .toList());
    }

    /** Single-track placement lookup — for building a TrackDto right after creation. */
    public TrackPlacement getTrackPlacement(UUID trackId) {
        return read("read placement for track=" + trackId, () ->
            neo4jClient.query("""
                    MATCH (:Album)-[c:CONTAINS]->(tr:Track {id: $trackId})
                    RETURN c.trackNumber AS trackNumber, c.trackRole AS trackRole
                    """)
                .bind(trackId.toString()).to("trackId")
                .fetch()
                .one()
                .map(row -> new TrackPlacement(
                    trackId,
                    row.get("trackNumber") == null ? null : ((Number) row.get("trackNumber")).intValue(),
                    (String) row.get("trackRole")
                ))
                .orElse(new TrackPlacement(trackId, null, null)));
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

    public void addArtistStyle(UUID artistId, String styleCode) {
        write("add HAS_STYLE artist=" + artistId + " style=" + styleCode, () ->
            neo4jClient.query("""
                    MATCH (ar:Artist {id: $artistId}), (s:Style {code: $styleCode})
                    MERGE (ar)-[:HAS_STYLE]->(s)
                    """)
                .bind(artistId.toString()).to("artistId")
                .bind(styleCode).to("styleCode")
                .run());
    }

    public void addArtistContext(UUID artistId, String contextCode) {
        write("add PERFECT_FOR artist=" + artistId + " context=" + contextCode, () ->
            neo4jClient.query("""
                    MATCH (ar:Artist {id: $artistId}), (c:Context {code: $contextCode})
                    MERGE (ar)-[:PERFECT_FOR]->(c)
                    """)
                .bind(artistId.toString()).to("artistId")
                .bind(contextCode).to("contextCode")
                .run());
    }

    /** Unidirectional by default (a1 -> a2 only); pass bidirectional=true to also create a2 -> a1. */
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
