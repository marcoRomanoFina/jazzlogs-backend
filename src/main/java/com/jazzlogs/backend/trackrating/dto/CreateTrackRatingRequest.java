package com.jazzlogs.backend.trackrating.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;

/**
 * @param rating 1 to 5, in 0.5 steps — see {@code TrackRatingService#upsertRating}
 *               for the full validation
 */
public record CreateTrackRatingRequest(
    @NotNull BigDecimal rating
) {
}
