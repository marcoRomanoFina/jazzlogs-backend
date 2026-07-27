package com.jazzlogs.backend.series.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.jazzlogs.backend.series.SeriesStatus;

// totalListenings is computed on-demand (COUNT across every chapter's listens,
// all users) — same criterio as Album's avg rating, never denormalized.
public record SeriesDetailDto(
    UUID id,
    String title,
    String dek,
    String description,
    String coverImageUrl,
    SeriesStatus status,
    int likeCount,
    boolean likedByCurrentUser,
    long totalListenings,
    List<SeriesChapterDetailDto> chapters,
    Instant createdAt,
    Instant updatedAt
) {
}
