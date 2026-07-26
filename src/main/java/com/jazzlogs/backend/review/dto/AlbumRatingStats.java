package com.jazzlogs.backend.review.dto;

import java.math.BigDecimal;

// avgRating is null (not zero) when count == 0 — there's no average of zero reviews.
public record AlbumRatingStats(BigDecimal avgRating, long count) {
}
