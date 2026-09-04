package com.jazzlogs.backend.editorial;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TrackEditorialRepository extends JpaRepository<TrackEditorial, UUID> {

    Optional<TrackEditorial> findByTrackId(UUID trackId);

    boolean existsByTrackId(UUID trackId);

    // One query for every track editorial on this album (title/dek/byline +
    // blocks), instead of one per track — see AlbumService.getAlbumDetail,
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

    // For "The Last Log" — title/dek/likeCount only, keyed by trackId, no
    // blocks/embeddings. editorialId is needed to look up likedByCurrentUser
    // via LikeService (it's a different id than trackId — the editorial's
    // own PK).
    @Query("SELECT te.id AS editorialId, t.id AS trackId, te.title AS title, te.dek AS dek, te.likeCount AS likeCount FROM TrackEditorial te JOIN te.track t WHERE t.album.id = :albumId")
    List<TrackTeaserRow> findTeasersByAlbumId(@Param("albumId") UUID albumId);

    interface TrackTeaserRow {
        UUID getEditorialId();

        UUID getTrackId();

        String getTitle();

        String getDek();

        int getLikeCount();
    }

    /**
     * Backs "Featured Tracks" — {@code Track#featured}, not {@code
     * Editorial#featurated} (that one's the single hero slot, this is up
     * to {@code TrackService.MAX_FEATURED_TRACKS}). The inner joins mean a
     * featured track with no editorial yet silently doesn't appear here —
     * an admin-workflow concern, not something this query guards against.
     */
    @Query("""
        SELECT new com.jazzlogs.backend.editorial.FeaturedTrackRow(
            te.id, te.title, te.dek, te.byline, alb.logNumber,
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
