package com.jazzlogs.backend.editorial;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlbumEditorialRepository extends JpaRepository<AlbumEditorial, UUID> {

    Optional<AlbumEditorial> findByAlbumId(UUID albumId);

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
