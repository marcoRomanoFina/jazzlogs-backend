package com.jazzlogs.backend.track;

import java.util.Collection;
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
        return new Resolved(track.getName(), track.getImageUrl());
    }

    /**
     * JOIN FETCH, not the default findAllById — {@code
     * ChatExchangeService.resolveWinners} needs each track's album AND that
     * album's artists ({@code track.album} is {@code FetchType.LAZY}, and so
     * is {@code album.artists}); without this, resolving N recommended
     * tracks means up to 2N extra per-row SELECTs (one for the album, one
     * for its artists) instead of one batched query. {@code artists} is a
     * collection now, not a to-one — fetch-joining it here is still safe
     * because this method isn't paginated (unlike a {@code Pageable} query,
     * where it would silently break LIMIT/OFFSET).
     */
    @Query("SELECT t FROM Track t JOIN FETCH t.album al LEFT JOIN FETCH al.artists WHERE t.id IN :ids")
    List<Track> findAllByIdWithAlbumAndArtist(@Param("ids") List<UUID> ids);

    /**
     * Same matchType/ordering shape as {@code ArtistRepository.search} — see
     * its Javadoc. Two-hop join (track -> album -> artist). {@code
     * artistFullName} is every one of the album's credited artists joined
     * with ", ", in credited order — a scalar subquery, not a join, so an
     * album with more than one artist doesn't fan out into duplicate rows
     * ahead of the {@code LIMIT}.
     */
    @Override
    @Query(value = """
        SELECT
            t.id AS id,
            t.name AS name,
            (
                SELECT string_agg(ar.name, ', ' ORDER BY aa.position)
                FROM album_artists aa
                JOIN artists ar ON ar.id = aa.artist_id
                WHERE aa.album_id = al.id
            ) AS artistFullName,
            al.name AS albumName,
            similarity(t.normalized_name, :normalizedQuery) AS score,
            CASE
                WHEN t.normalized_name = :normalizedQuery THEN 'EXACT'
                WHEN t.normalized_name LIKE :normalizedQuery || '%' THEN 'PREFIX'
                WHEN t.normalized_name LIKE '%' || :normalizedQuery || '%' THEN 'CONTAINS'
                ELSE 'FUZZY'
            END AS matchType
        FROM tracks t
        JOIN albums al ON al.id = t.album_id
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
        LIMIT :limit
        """, nativeQuery = true)
    List<CandidateRow> search(@Param("normalizedQuery") String normalizedQuery, @Param("limit") int limit);

    /**
     * The tracks at the given levels whose log was written by the given
     * narrator; a {@code null} condition is not filtered on. Everything is
     * passed as an enum name — a native query, so that a null one is a plain
     * untyped NULL Postgres can compare against.
     */
    @Query(value = """
        SELECT t.id
        FROM tracks t
        LEFT JOIN track_editorials te ON te.track_id = t.id
        WHERE (:energy IS NULL OR t.energy = :energy)
          AND (:accessibility IS NULL OR t.accessibility = :accessibility)
          AND (:moodIntensity IS NULL OR t.mood_intensity = :moodIntensity)
          AND (:writtenBy IS NULL OR te.byline = :writtenBy)
        """, nativeQuery = true)
    List<UUID> findIdsByLevelsAndAuthor(
        @Param("energy") String energy,
        @Param("accessibility") String accessibility,
        @Param("moodIntensity") String moodIntensity,
        @Param("writtenBy") String writtenBy
    );

    /**
     * What a search result shows of each track without opening its log: its
     * name, album, credited artists (joined with ", " in credited order, same
     * as {@link #search}) and who wrote its log.
     */
    @Query(value = """
        SELECT
            t.id AS id,
            t.name AS name,
            al.name AS albumName,
            (
                SELECT string_agg(ar.name, ', ' ORDER BY aa.position)
                FROM album_artists aa
                JOIN artists ar ON ar.id = aa.artist_id
                WHERE aa.album_id = al.id
            ) AS artistFullName,
            te.byline AS writtenBy
        FROM tracks t
        JOIN albums al ON al.id = t.album_id
        LEFT JOIN track_editorials te ON te.track_id = t.id
        WHERE t.id IN (:trackIds)
        """, nativeQuery = true)
    List<TrackCardRow> findCards(@Param("trackIds") Collection<UUID> trackIds);

    /** One row from {@link #findCards}; {@code writtenBy} is a {@code JazzlogsCharacter} name, null if the track has no log. */
    interface TrackCardRow {
        UUID getId();

        String getName();

        String getAlbumName();

        String getArtistFullName();

        String getWrittenBy();
    }
}
