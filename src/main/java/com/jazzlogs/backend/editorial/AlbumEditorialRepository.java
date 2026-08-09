package com.jazzlogs.backend.editorial;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlbumEditorialRepository extends JpaRepository<AlbumEditorial, UUID> {

    Optional<AlbumEditorial> findByAlbumId(UUID albumId);

    // Step 2 of EDITORIAL_SEARCH's vocabularyFilter — see
    // VocabularyEditorialResolver. ae.id is the editorial_id (see
    // @PrimaryKeyJoinColumn on AlbumEditorial), same id editorial_blocks uses.
    @Query("SELECT ae.id FROM AlbumEditorial ae WHERE ae.album.id IN :albumIds")
    List<UUID> findEditorialIdsByAlbumIdIn(@Param("albumIds") List<UUID> albumIds);

    // For the archive page's spotlight — title/dek/byline/likeCount/id only,
    // no blocks (which would drag along their embedding vectors for nothing,
    // since the spotlight never shows block content). id is needed to look
    // up likedByCurrentUser via LikeService.
    @Query("SELECT ae.id AS id, ae.title AS title, ae.dek AS dek, ae.byline AS byline, ae.likeCount AS likeCount FROM AlbumEditorial ae WHERE ae.album.id = :albumId")
    Optional<EditorialTeaserRow> findTeaserByAlbumId(@Param("albumId") UUID albumId);

    interface EditorialTeaserRow {
        UUID getId();

        String getTitle();

        String getDek();

        String getByline();

        int getLikeCount();
    }
}
