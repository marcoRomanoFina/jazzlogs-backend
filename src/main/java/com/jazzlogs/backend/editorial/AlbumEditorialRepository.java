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
}
