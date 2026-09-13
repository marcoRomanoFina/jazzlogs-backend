package com.jazzlogs.backend.album;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jazzlogs.backend.agent.CatalogEntityResolver;
import com.jazzlogs.backend.saveditem.SavedItemResolver;

public interface AlbumRepository extends JpaRepository<Album, UUID>, SavedItemResolver, CatalogEntityResolver {

    /**
     * Spotify identity is what POST /albums upserts on — an album with this
     * spotifyAlbumId already existing means "update it", not "create a duplicate".
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
     * JOIN FETCH, not the default findAllById — {@code
     * ChatExchangeService.resolveWinners} needs each album's artist ({@code
     * album.artist} is {@code FetchType.LAZY}); without this, resolving N
     * recommended albums means N extra per-row SELECTs (one per artist)
     * instead of one batched query.
     */
    @Query("SELECT a FROM Album a JOIN FETCH a.artist WHERE a.id IN :ids")
    List<Album> findAllByIdWithArtist(@Param("ids") List<UUID> ids);

    /**
     * Same {@code JOIN FETCH a.artist} as {@link #findAllByIdWithArtist},
     * paginated — for {@code ArtistService.getEssentialListening}, whose
     * candidate album ids come from a single unpaged Neo4j read
     * ({@code GraphService.getEntryPointAlbumIds}) and get their real
     * pagination (and {@code Page}'s total count) done here instead.
     * {@code artist} is a to-one association, not a collection — combining
     * a fetch join with {@code Pageable} is safe here, unlike fetch-joining
     * a collection (see {@code ReviewRepository}'s comment on that).
     */
    @Query(
        value = "SELECT a FROM Album a JOIN FETCH a.artist WHERE a.id IN :ids ORDER BY a.releaseYear ASC",
        countQuery = "SELECT count(a) FROM Album a WHERE a.id IN :ids"
    )
    Page<Album> findByIdInOrderByReleaseYearAsc(@Param("ids") List<UUID> ids, Pageable pageable);

    /**
     * Same matchType/ordering shape as {@code ArtistRepository.search} — see
     * its Javadoc. {@code artistFullName} comes from the joined artists row
     * (an album's artist is just its direct artist_id, no sideman/graph
     * resolution here).
     */
    @Override
    @Query(value = """
        SELECT
            al.id AS id,
            al.name AS name,
            ar.name AS artistFullName,
            NULL::text AS albumName,
            similarity(al.normalized_name, :normalizedQuery) AS score,
            CASE
                WHEN al.normalized_name = :normalizedQuery THEN 'EXACT'
                WHEN al.normalized_name LIKE :normalizedQuery || '%' THEN 'PREFIX'
                WHEN al.normalized_name LIKE '%' || :normalizedQuery || '%' THEN 'CONTAINS'
                ELSE 'FUZZY'
            END AS matchType,
            aled.editorial_id AS editorialId
        FROM albums al
        JOIN artists ar ON ar.id = al.artist_id
        LEFT JOIN album_editorials aled ON aled.album_id = al.id
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
        LIMIT 20
        """, nativeQuery = true)
    List<CandidateRow> search(@Param("normalizedQuery") String normalizedQuery);

    /** For {@code EditorialService.getFeatured} — the archive hero's source album, if any is currently featured. */
    Optional<Album> findByFeaturedTrue();

    /**
     * The normal-path way to clear the previous featured row before marking
     * a new one — {@code AlbumService.setFeatured} calls this before {@link
     * #markFeatured}, both as atomic UPDATEs rather than read-modify-save.
     * This alone doesn't guarantee at most one stays featured under
     * concurrent calls; {@code idx_albums_only_one_featured} (see V24) is
     * what actually enforces that.
     */
    @Modifying
    @Query("UPDATE Album a SET a.featured = false WHERE a.featured = true")
    void clearFeatured();

    /**
     * See {@link #clearFeatured} — always called right after it, never on its own.
     *
     * @param id the album to feature; caller is responsible for validating it exists
     */
    @Modifying
    @Query("UPDATE Album a SET a.featured = true WHERE a.id = :id")
    void markFeatured(@Param("id") UUID id);

    /** A no-op if {@code id} wasn't featured to begin with. */
    @Modifying
    @Query("UPDATE Album a SET a.featured = false WHERE a.id = :id")
    void unmarkFeatured(@Param("id") UUID id);
}
