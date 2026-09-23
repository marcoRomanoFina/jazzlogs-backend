package com.jazzlogs.backend.series.dto;

import java.util.UUID;

import com.jazzlogs.backend.series.ChapterType;

// track is null for OUTRO chapters (see SeriesService.resolveTrackForType).
// status is computed relative to the current user's listens — see
// SeriesService. audioUrl is a presigned, short-lived playback URL — only
// populated by SeriesService.getChapter (never in a chapter list, to avoid
// a presign call per row per page load).
public record SeriesChapterDetailDto(
    UUID id,
    int position,
    ChapterType type,
    SeriesChapterTrackDto track,
    String title,
    String note,
    String audioObjectKey,
    String audioUrl,
    Integer audioDurationSeconds,
    String audioContentType,
    Long audioFileSizeBytes,
    String imageUrl,
    String landscapeImageUrl,
    ChapterStatus status
) {
}
