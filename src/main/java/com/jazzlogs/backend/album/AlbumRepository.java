package com.jazzlogs.backend.album;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jazzlogs.backend.agent.CatalogEntityResolver;
import com.jazzlogs.backend.saveditem.SavedItemResolver;

public interface AlbumRepository extends JpaRepository<Album, UUID>, SavedItemResolver, CatalogEntityResolver {

    /**
     * Spotify identity is what track-first ingestion resolves/creates on
     * (see {@code TrackService#resolveOrCreateAlbum}) — an album with this
     * spotifyAlbumId already existing means "reuse it", not "create a duplicate".
     */
    Optional<Album> findBySpotifyAlbumId(String spotifyAlbumId);

    @Override
    default Optional<Resolved> resolve(UUID entityId) {
        return findById(entityId).map(AlbumRepository::toResolved);
    }

    @Override
    default Map<UUID, Resolved> resolveBatch(List<UUID> entityIds) {
        return findAllById(entityIds).stream()
            .collect(Collectors.toMap(Album::getId, AlbumRepository::toResolved));
    }

    private static Resolved toResolved(Album album) {
        return new Resolved(album.getName(), album.getImageUrl());
    }

    /**
     * For {@code ArtistService.getSidemanAlbums}, whose candidate album ids
     * come from a single unpaged Neo4j read ({@code
     * GraphService.getSidemanAlbumIds}) and get their real pagination (and
     * {@code Page}'s total count) done here instead. No {@code JOIN FETCH}
     * on {@code artists} here — it's a collection now, and combining a
     * fetch join with {@code Pageable} silently breaks LIMIT/OFFSET; see
     * {@link #findArtistsForAlbums} for how the caller gets each album's
     * artists instead, batched separately.
     */
    @Query(
        value = "SELECT a FROM Album a WHERE a.id IN :ids ORDER BY a.releaseYear ASC",
        countQuery = "SELECT count(a) FROM Album a WHERE a.id IN :ids"
    )
    Page<Album> findByIdInOrderByReleaseYearAsc(@Param("ids") List<UUID> ids, Pageable pageable);

    /**
     * Every artist credited on any of {@code albumIds}, in credited order —
     * one query for a whole page of albums instead of one per album. Native
     * (not JPQL): {@code album_artists.position} is what the ordering needs,
     * and a JPQL join can't surface an {@code @OrderColumn}'s backing column
     * directly in a projection.
     */
    @Query(
        value = """
            SELECT aa.album_id AS albumId, ar.id AS artistId, ar.name AS name,
                   ar.image_url AS imageUrl, ar.spotify_url AS spotifyUrl
            FROM album_artists aa
            JOIN artists ar ON ar.id = aa.artist_id
            WHERE aa.album_id IN :albumIds
            ORDER BY aa.album_id, aa.position
            """,
        nativeQuery = true
    )
    List<AlbumArtistRow> findArtistsForAlbums(@Param("albumIds") List<UUID> albumIds);

    /** One row from {@link #findArtistsForAlbums}. */
    interface AlbumArtistRow {
        UUID getAlbumId();

        UUID getArtistId();

        String getName();

        String getImageUrl();

        String getSpotifyUrl();
    }

    /**
     * Same matchType/ordering shape as {@code ArtistRepository.search} — see
     * its Javadoc. {@code artistFullName} is every one of the album's
     * credited artists joined with ", ", in credited order — a scalar
     * subquery, not a join, so an album with more than one artist doesn't
     * fan out into duplicate rows ahead of the {@code LIMIT}.
     */
    @Override
    @Query(value = """
        SELECT
            al.id AS id,
            al.name AS name,
            (
                SELECT string_agg(ar.name, ', ' ORDER BY aa.position)
                FROM album_artists aa
                JOIN artists ar ON ar.id = aa.artist_id
                WHERE aa.album_id = al.id
            ) AS artistFullName,
            NULL::text AS albumName,
            similarity(al.normalized_name, :normalizedQuery) AS score,
            CASE
                WHEN al.normalized_name = :normalizedQuery THEN 'EXACT'
                WHEN al.normalized_name LIKE :normalizedQuery || '%' THEN 'PREFIX'
                WHEN al.normalized_name LIKE '%' || :normalizedQuery || '%' THEN 'CONTAINS'
                ELSE 'FUZZY'
            END AS matchType
        FROM albums al
        WHERE al.normalized_name = :normalizedQuery
           OR al.normalized_name LIKE :normalizedQuery || '%'
           OR al.normalized_name LIKE '%' || :normalizedQuery || '%'
           OR al.normalized_name % :normalizedQuery
        ORDER BY
            CASE
                WHEN al.normalized_name = :normalizedQuery THEN 0
                WHEN al.normalized_name LIKE :normalizedQuery || '%' THEN 1
                WHEN al.normalized_name LIKE '%' || :normalizedQuery || '%' THEN 2
                ELSE 3
            END,
            similarity(al.normalized_name, :normalizedQuery) DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<CandidateRow> search(@Param("normalizedQuery") String normalizedQuery, @Param("limit") int limit);
}
