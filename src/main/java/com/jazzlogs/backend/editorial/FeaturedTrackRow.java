package com.jazzlogs.backend.editorial;

import java.time.Instant;
import java.util.UUID;

/**
 * What {@link TrackEditorialRepository#findFeatured} actually selects —
 * everything {@link com.jazzlogs.backend.editorial.dto.FeaturedTrackDto}
 * needs except {@code likedByCurrentUser}, which {@code EditorialService}
 * adds after (via a separate batched {@code LikeService} lookup).
 */
public record FeaturedTrackRow(
    UUID id,
    String title,
    String dek,
    String byline,
    String logNumber,
    String trackName,
    String imageUrl,
    String albumName,
    UUID albumId,
    Instant createdAt,
    int likeCount
) {
}
