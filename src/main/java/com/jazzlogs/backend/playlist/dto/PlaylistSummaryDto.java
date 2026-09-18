package com.jazzlogs.backend.playlist.dto;

import java.time.Instant;
import java.util.UUID;

import com.jazzlogs.backend.playlist.PlaylistType;

public record PlaylistSummaryDto(
    UUID id,
    String title,
    String tagline,
    String coverImageUrl,
    PlaylistType type,
    boolean published,
    int likeCount,
    int trackCount,
    long durationMs,
    Instant createdAt
) {
}
