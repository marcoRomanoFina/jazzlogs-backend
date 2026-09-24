package com.jazzlogs.backend.editorial;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jazzlogs.backend.like.LikeableRepository;

public interface TrackEditorialRepository extends LikeableRepository<TrackEditorial> {

    Optional<TrackEditorial> findByTrackId(UUID trackId);

    boolean existsByTrackId(UUID trackId);

    // Atomic UPDATE, not read-modify-save — two concurrent likes must not race
    // and lose an increment.
    @Modifying
    @Query("UPDATE TrackEditorial te SET te.likeCount = te.likeCount + 1 WHERE te.id = :id")
    void incrementLikeCount(@Param("id") UUID entityId);

    @Modifying
    @Query("UPDATE TrackEditorial te SET te.likeCount = GREATEST(te.likeCount - 1, 0) WHERE te.id = :id")
    void decrementLikeCount(@Param("id") UUID entityId);

    @Query("SELECT te.likeCount FROM TrackEditorial te WHERE te.id = :id")
    Optional<Integer> findLikeCount(@Param("id") UUID entityId);

    // One query for every track editorial on this album (title/dek/byline +
    // blocks), instead of one per track — see AlbumService.getAlbumTracks,
    // which used to call getTrackEditorialDto(trackId) once per track.
    // DISTINCT is needed because the blocks fetch join otherwise duplicates
    // each TrackEditorial row once per block.
    @Query("""
        SELECT DISTINCT te FROM TrackEditorial te
        JOIN FETCH te.track t
        LEFT JOIN FETCH te.blocks
        WHERE t.album.id = :albumId
        """)
    List<TrackEditorial> findByTrackAlbumId(@Param("albumId") UUID albumId);

    /**
     * The archive's free-form search/browse listing — {@code pattern} is a
     * lowercased {@code "%...%"} substring, matched against either the
     * editorial's own title or its track's name; {@code null} means no
     * filter. No {@code type} filter anymore — track is the only kind of
     * editorial left.
     */
    @Query(
        value = """
            SELECT new com.jazzlogs.backend.editorial.TrackEditorialCatalogueRow(
                te.id, t.id, t.name, t.imageUrl, alb.name, alb.id, te.title, te.dek, te.byline, te.createdAt, te.likeCount
            )
            FROM TrackEditorial te
            JOIN te.track t
            JOIN t.album alb
            WHERE :pattern IS NULL OR LOWER(te.title) LIKE :pattern OR LOWER(t.name) LIKE :pattern
            """,
        countQuery = """
            SELECT COUNT(te)
            FROM TrackEditorial te
            JOIN te.track t
            WHERE :pattern IS NULL OR LOWER(te.title) LIKE :pattern OR LOWER(t.name) LIKE :pattern
            """
    )
    Page<TrackEditorialCatalogueRow> searchCatalogue(@Param("pattern") String pattern, Pageable pageable);

    /**
     * Backs "Featured Tracks" — {@code Track#featured}, not {@code
     * Editorial#featurated} (that one's the single hero slot, this is up
     * to {@code TrackService.MAX_FEATURED_TRACKS}). The inner joins mean a
     * featured track with no editorial yet silently doesn't appear here —
     * an admin-workflow concern, not something this query guards against.
     */
    @Query("""
        SELECT new com.jazzlogs.backend.editorial.FeaturedTrackRow(
            te.id, te.title, te.dek, te.byline,
            t.name, t.imageUrl, alb.name, alb.id, te.createdAt, te.likeCount
        )
        FROM TrackEditorial te
        JOIN te.track t
        JOIN t.album alb
        WHERE t.featured = true
        ORDER BY te.createdAt DESC
        """)
    List<FeaturedTrackRow> findFeatured();
}
