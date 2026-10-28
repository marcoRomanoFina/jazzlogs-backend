package com.jazzlogs.backend.trackrating.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One user's rating of one track — see {@code TrackRatingService#upsertRating}.
 *
 * @param id        the rating row's own id
 * @param trackId   the rated track
 * @param userId    who rated it
 * @param rating    1 to 5, in 0.5 steps
 * @param createdAt when this rating was first given
 * @param updatedAt when it was last changed
 */
public record TrackRatingDto(
    UUID id,
    UUID trackId,
    UUID userId,
    BigDecimal rating,
    Instant createdAt,
    Instant updatedAt
) {
}
