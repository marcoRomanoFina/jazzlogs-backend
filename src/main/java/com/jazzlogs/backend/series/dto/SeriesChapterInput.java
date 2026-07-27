package com.jazzlogs.backend.series.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

import com.jazzlogs.backend.series.ChapterType;

// Used for both addChapter (POST) and updateChapter (PATCH) — same field set
// either way. trackId is required iff type = TRACK (mirrors the DB CHECK
// constraint; SeriesService.resolveTrackForType gives a 400 before it ever
// reaches Postgres). position isn't sent — addChapter appends at the end,
// reordering is a separate endpoint.
public record SeriesChapterInput(
    @NotNull ChapterType type,
    UUID trackId,
    String title,
    String note,
    String audioObjectKey,
    Integer audioDurationMs,
    String audioContentType,
    Long audioFileSizeBytes
) {
}
