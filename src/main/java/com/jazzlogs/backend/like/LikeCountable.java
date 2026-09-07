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

    void decrementLikeCount(UUID entityId);

    // Reads just the counter column, not the whole entity — empty if entityId
    // doesn't exist, callers treat that as 0 rather than an error.
    Optional<Integer> findLikeCount(UUID entityId);
}
