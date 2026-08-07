package com.jazzlogs.backend.editorial;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TrackEditorialRepository extends JpaRepository<TrackEditorial, UUID> {

    Optional<TrackEditorial> findByTrackId(UUID trackId);

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

    // For the archive page's spotlight — title/dek/likeCount only, keyed by
    // trackId, no blocks/embeddings (same reasoning as
    // AlbumEditorialRepository's teaser query). editorialId is needed to
    // look up likedByCurrentUser via LikeService (it's a different id than
    // trackId — the editorial's own PK).
    @Query("SELECT te.id AS editorialId, t.id AS trackId, te.title AS title, te.dek AS dek, te.likeCount AS likeCount FROM TrackEditorial te JOIN te.track t WHERE t.album.id = :albumId")
    List<TrackTeaserRow> findTeasersByAlbumId(@Param("albumId") UUID albumId);

    interface TrackTeaserRow {
        UUID getEditorialId();

        UUID getTrackId();

        String getTitle();

        String getDek();

        int getLikeCount();
    }

    // Fallback for EditorialSearchTool: embedding_metadata never carries a
    // track's own name (see EditorialService.buildBaseMetadata, which only
    // populates albumName/artistName for Album/ArtistEditorial, nothing for
    // TrackEditorial) — this is the one case that gets resolved with a real
    // JOIN instead of reading it straight off the block's metadata.
    @Query("SELECT te.id AS editorialId, t.name AS name FROM TrackEditorial te JOIN te.track t WHERE te.id IN :editorialIds")
    List<NameRow> findNamesByEditorialIdIn(@Param("editorialIds") List<UUID> editorialIds);

    // Step 2 of EDITORIAL_SEARCH's vocabularyFilter — see
    // VocabularyEditorialResolver. te.id is the editorial_id (see
    // @PrimaryKeyJoinColumn on TrackEditorial), same id editorial_blocks uses.
    @Query("SELECT te.id FROM TrackEditorial te WHERE te.track.id IN :trackIds")
    List<UUID> findEditorialIdsByTrackIdIn(@Param("trackIds") List<UUID> trackIds);

    interface NameRow {
        UUID getEditorialId();

        String getName();
    }
}
