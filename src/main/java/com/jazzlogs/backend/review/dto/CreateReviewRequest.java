package com.jazzlogs.backend.review.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record CreateReviewRequest(
    @NotNull BigDecimal rating,
    String text,
    List<UUID> standoutTrackIds
) {
}
