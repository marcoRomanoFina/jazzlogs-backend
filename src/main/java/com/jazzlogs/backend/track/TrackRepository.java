package com.jazzlogs.backend.track;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jazzlogs.backend.agent.CatalogEntityResolver;
import com.jazzlogs.backend.saveditem.SavedItemResolver;

public interface TrackRepository extends JpaRepository<Track, UUID>, SavedItemResolver, CatalogEntityResolver {

    /**
     * Spotify identity is what POST /albums/{id}/tracks upserts on — a track
     * with this spotifyTrackId already existing means "update it", not
     * "create a duplicate".
     */
    Optional<Track> findBySpotifyTrackId(String spotifyTrackId);

    /**
     * For {@code ListenService.syncAlbumCompletionState} — needs every track
     * id on the album to check whether the user has now listened to all of
     * them.
     */
    @Query("SELECT t.id FROM Track t WHERE t.album.id = :albumId")
    List<UUID> findIdsByAlbumId(@Param("albumId") UUID albumId);

    /** For {@code TrackService.setFeatured}'s cap check — see {@code Track#featured}. */
    @Query("SELECT COUNT(t) FROM Track t WHERE t.featured = true")
    long countFeatured();

    /**
     * Atomic UPDATE, not read-modify-save — same reasoning as {@code
     * EditorialRepository.markFeaturated}. {@code clearAutomatically = true}:
     * {@code setFeatured} already has this Track loaded ({@code
     * getTrackOrThrow}) when this runs, and a bulk UPDATE doesn't touch that
     * in-memory copy — without this, it'd keep reading as not-featured for
     * the rest of the transaction.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Track t SET t.featured = true WHERE t.id = :id")
    void markFeatured(@Param("id") UUID id);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Track t SET t.featured = false WHERE t.id = :id")
    void unmarkFeatured(@Param("id") UUID id);

    @Override
    default Optional<Resolved> resolve(UUID entityId) {
        return findById(entityId).map(TrackRepository::toResolved);
    }

    @Override
    default Map<UUID, Resolved> resolveBatch(List<UUID> entityIds) {
        return findAllById(entityIds).stream()
            .collect(Collectors.toMap(Track::getId, TrackRepository::toResolved));
    }

    private static Resolved toResolved(Track track) {
        return new Resolved(track.getName(), track.getImageUrl(), track.getSpotifyUrl());
    }

    /**
     * JOIN FETCH, not the default findAllById — {@code
     * ChatExchangeService.resolveWinners} needs each track's album AND that
     * album's artist ({@code track.album} and {@code album.artist} are both
     * {@code FetchType.LAZY}); without this, resolving N recommended tracks
     * means up to 2N extra per-row SELECTs (one for the album, one for the
     * artist) instead of one batched query.
     */
    @Query("SELECT t FROM Track t JOIN FETCH t.album al JOIN FETCH al.artist WHERE t.id IN :ids")
    List<Track> findAllByIdWithAlbumAndArtist(@Param("ids") List<UUID> ids);

    /**
     * Same matchType/ordering shape as {@code ArtistRepository.search} — see
     * its Javadoc. Two-hop join (track -> album -> artist): a track's artist
     * is its album's direct artist_id, no sideman/graph resolution here.
     */
    @Override
    @Query(value = """
        SELECT
            t.id AS id,
            t.name AS name,
            ar.name AS artistFullName,
            al.name AS albumName,
            similarity(t.normalized_name, :normalizedQuery) AS score,
            CASE
                WHEN t.normalized_name = :normalizedQuery THEN 'EXACT'
                WHEN t.normalized_name LIKE :normalizedQuery || '%' THEN 'PREFIX'
                WHEN t.normalized_name LIKE '%' || :normalizedQuery || '%' THEN 'CONTAINS'
                ELSE 'FUZZY'
            END AS matchType,
            ted.editorial_id AS editorialId
        FROM tracks t
        JOIN albums al ON al.id = t.album_id
        JOIN artists ar ON ar.id = al.artist_id
        LEFT JOIN track_editorials ted ON ted.track_id = t.id
        WHERE t.normalized_name = :normalizedQuery
           OR t.normalized_name LIKE :normalizedQuery || '%'
           OR t.normalized_name LIKE '%' || :normalizedQuery || '%'
           OR t.normalized_name % :normalizedQuery
        ORDER BY
            CASE
                WHEN t.normalized_name = :normalizedQuery THEN 0
                WHEN t.normalized_name LIKE :normalizedQuery || '%' THEN 1
                WHEN t.normalized_name LIKE '%' || :normalizedQuery || '%' THEN 2
                ELSE 3
            END,
            similarity(t.normalized_name, :normalizedQuery) DESC
        LIMIT 20
        """, nativeQuery = true)
    List<CandidateRow> search(@Param("normalizedQuery") String normalizedQuery);
}
