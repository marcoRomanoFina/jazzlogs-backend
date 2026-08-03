package com.jazzlogs.backend.editorial;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TrackEditorialRepository extends JpaRepository<TrackEditorial, UUID> {

    Optional<TrackEditorial> findByTrackId(UUID trackId);

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
