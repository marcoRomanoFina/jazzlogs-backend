package com.jazzlogs.backend.editorial;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlbumEditorialRepository extends JpaRepository<AlbumEditorial, UUID> {

    Optional<AlbumEditorial> findByAlbumId(UUID albumId);

    /**
     * Batch — one query for a whole page of albums (e.g.
     * {@code ArtistService.getEssentialListening}), which only wants
     * {@code dek}, not one {@link #findByAlbumId} (and a whole entity,
     * blocks included) per row.
     *
     * @param albumIds the albums to look up
     * @return one row per album that has an editorial — an album with none
     *         simply has no row, callers treat that as no dek
     */
    @Query("SELECT ae.album.id AS albumId, ae.dek AS dek FROM AlbumEditorial ae WHERE ae.album.id IN :albumIds")
    List<AlbumEditorialDekRow> findDeksByAlbumIds(@Param("albumIds") List<UUID> albumIds);

    interface AlbumEditorialDekRow {
        UUID getAlbumId();
        String getDek();
    }
}
