package com.jazzlogs.backend.playlist.dto;

import java.time.Instant;
import java.util.UUID;

public record PlaylistSummaryDto(
    UUID id,
    String slug,
    String title,
    String tagline,
    String coverImageUrl,
    boolean published,
    int likeCount,
    int trackCount,
    long durationMs,
    Instant createdAt
) {
}
