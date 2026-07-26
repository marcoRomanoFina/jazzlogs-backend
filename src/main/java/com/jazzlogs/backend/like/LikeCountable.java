package com.jazzlogs.backend.like;

import java.util.Optional;
import java.util.UUID;

// Implemented by each likeable entity's repository (EditorialRepository, and
// ReviewRepository/PlaylistRepository/NoteRepository/SeriesRepository once
// those entities exist) so LikeService can dispatch increment/decrement/read
// through a Map<LikeableEntityType, LikeableRepository<?>> instead of a switch.
public interface LikeCountable {

    void incrementLikeCount(UUID entityId);

    void decrementLikeCount(UUID entityId);

    // Reads just the counter column, not the whole entity — empty if entityId
    // doesn't exist, callers treat that as 0 rather than an error.
    Optional<Integer> findLikeCount(UUID entityId);
}
