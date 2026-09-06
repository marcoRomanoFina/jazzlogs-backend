package com.jazzlogs.backend.review.dto;

import java.math.BigDecimal;


public record AlbumRatingStats(BigDecimal avgRating, long count) {
}
