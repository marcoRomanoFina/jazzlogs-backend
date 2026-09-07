package com.jazzlogs.backend.like;

import java.util.Optional;
import java.util.UUID;

/**
 * Implemented by each likeable entity's repository (EditorialRepository,
 * ReviewRepository, PlaylistRepository, NoteRepository, SeriesRepository) so
 * {@link LikeService} can dispatch increment/decrement/read through a
 * {@code Map<LikeableEntityType, LikeableRepository<?>>} instead of a switch.
 */
public interface LikeCountable {

    /** Atomic {@code likeCount + 1} — not read-modify-save. */
    void incrementLikeCount(UUID entityId);

    /** Atomic {@code GREATEST(likeCount - 1, 0)} — not read-modify-save, and never goes negative. */
    void decrementLikeCount(UUID entityId);

    /**
     * Reads just the counter column, not the whole entity.
     *
     * @param entityId the entity to check
     * @return its like count, empty if no entity with that id exists — callers treat that as 0, not an error
     */
    Optional<Integer> findLikeCount(UUID entityId);
}
