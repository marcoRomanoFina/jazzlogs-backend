package com.jazzlogs.backend.review.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateReviewRequest(
    @NotNull BigDecimal rating,
    // Large but controllable, same cap as notes (see CreateNoteRequest) —
    // text is optional (a rating alone is a valid review), so no @NotBlank.
    @Size(max = 5000) String text,
    List<UUID> standoutTrackIds
) {
}
