package com.jazzlogs.backend.series.dto;

import java.util.UUID;

import com.jazzlogs.backend.series.ChapterType;

// trackId/trackName are null for INTRO/OUTRO chapters. status is computed
// relative to the current user's listens — see SeriesService.
public record SeriesChapterDetailDto(
    UUID id,
    int position,
    ChapterType type,
    UUID trackId,
    String trackName,
    String title,
    String note,
    String audioObjectKey,
    Integer audioDurationMs,
    String audioContentType,
    Long audioFileSizeBytes,
    ChapterStatus status
) {
}
