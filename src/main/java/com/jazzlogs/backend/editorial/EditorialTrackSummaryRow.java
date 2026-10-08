package com.jazzlogs.backend.editorial;

import java.time.Instant;
import java.util.UUID;

import com.jazzlogs.backend.character.JazzlogsCharacter;

/**
 * What {@link TrackEditorialRepository#findRecentByByline} actually selects
 * — everything {@link com.jazzlogs.backend.editorial.dto.EditorialTrackSummaryDto}
 * needs except {@code likedByCurrentUser}, which {@code EditorialService}
 * adds after (via a separate batched {@code LikeService} lookup).
 */
public record EditorialTrackSummaryRow(
    UUID id,
    String title,
    String logNumber,
    String dek,
    JazzlogsCharacter byline,
    UUID trackId,
    String trackName,
    String coverImageUrl,
    String albumName,
    String artistName,
    Instant createdAt,
    int likeCount
) {
}
