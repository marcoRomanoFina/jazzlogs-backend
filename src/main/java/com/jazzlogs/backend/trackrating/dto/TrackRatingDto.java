package com.jazzlogs.backend.trackrating.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TrackRatingDto(
    UUID id,
    UUID trackId,
    UUID userId,
    BigDecimal rating,
    Instant createdAt,
    Instant updatedAt
) {
}
