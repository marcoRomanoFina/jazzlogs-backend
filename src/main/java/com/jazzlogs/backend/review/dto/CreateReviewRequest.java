package com.jazzlogs.backend.review.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param rating           1 to 5, in 0.5 steps — see {@code ReviewService#assertValidRating} for the full validation
 * @param text             optional (a rating alone is a valid review); capped, same as {@code CreateNoteRequest}
 * @param standoutTrackIds optional; every id must exist and belong to the album being reviewed
 */
public record CreateReviewRequest(
    @NotNull BigDecimal rating,
    @Size(max = 5000) String text,
    List<UUID> standoutTrackIds
) {
}
