package com.jazzlogs.backend.series.dto;

import java.time.Instant;
import java.util.UUID;

import com.jazzlogs.backend.series.SeriesStatus;

public record SeriesSummaryDto(
    UUID id,
    String title,
    String dek,
    String coverImageUrl,
    SeriesStatus status,
    int likeCount,
    Instant createdAt
) {
}
