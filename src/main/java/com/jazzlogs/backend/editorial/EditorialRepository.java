package com.jazzlogs.backend.editorial;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jazzlogs.backend.like.LikeableRepository;

/**
 * Repository on the JOINED-inheritance base class — every AlbumEditorial/
 * TrackEditorial/ArtistEditorial row has a corresponding row in {@code
 * editorials}, so {@code existsById} here is enough to validate a LIKE's
 * entityId regardless of which concrete editorial subtype it points to.
 */
public interface EditorialRepository extends LikeableRepository<Editorial> {

    // Atomic UPDATE, not read-modify-save — two concurrent likes must not race
    // and lose an increment.
    @Modifying
    @Query("UPDATE Editorial e SET e.likeCount = e.likeCount + 1 WHERE e.id = :id")
    void incrementLikeCount(@Param("id") UUID entityId);

    @Modifying
    @Query("UPDATE Editorial e SET e.likeCount = GREATEST(e.likeCount - 1, 0) WHERE e.id = :id")
    void decrementLikeCount(@Param("id") UUID entityId);

    @Query("SELECT e.likeCount FROM Editorial e WHERE e.id = :id")
    Optional<Integer> findLikeCount(@Param("id") UUID entityId);

    /**
     * The normal-path way to clear the previous featurated row, across
     * every subtype (Album/Track/ArtistEditorial alike) — {@link
     * EditorialService#setFeaturated} calls this before {@link
     * #markFeaturated}, both as atomic UPDATEs rather than
     * read-modify-save. This alone doesn't guarantee at most one stays
     * featured under concurrent calls; {@code idx_editorials_only_one_featured}
     * (see V18) is what actually enforces that.
     */
    @Modifying
    @Query("UPDATE Editorial e SET e.featurated = false WHERE e.featurated = true")
    void clearFeaturated();

    /**
     * See {@link #clearFeaturated} — always called right after it, never on its own.
     *
     * @param id the editorial to feature; caller is responsible for validating it exists
     */
    @Modifying
    @Query("UPDATE Editorial e SET e.featurated = true WHERE e.id = :id")
    void markFeaturated(@Param("id") UUID id);
}
