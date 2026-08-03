package com.jazzlogs.backend.editorial;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ArtistEditorialRepository extends JpaRepository<ArtistEditorial, UUID> {

    Optional<ArtistEditorial> findByArtistId(UUID artistId);

    // Step 2 of EDITORIAL_SEARCH's vocabularyFilter — see
    // VocabularyEditorialResolver. ae.id is the editorial_id (see
    // @PrimaryKeyJoinColumn on ArtistEditorial), same id editorial_blocks uses.
    @Query("SELECT ae.id FROM ArtistEditorial ae WHERE ae.artist.id IN :artistIds")
    List<UUID> findEditorialIdsByArtistIdIn(@Param("artistIds") List<UUID> artistIds);
}
