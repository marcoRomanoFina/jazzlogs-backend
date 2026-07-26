package com.jazzlogs.backend.trackrating.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;

public record CreateTrackRatingRequest(
    @NotNull BigDecimal rating
) {
}
