package com.jazzlogs.backend.series.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.jazzlogs.backend.series.SeriesStatus;

// Metadata only — chapters are managed via their own granular endpoints
// (addChapter/removeChapter/updateChapter/reorderChapters), same shape as Playlist.
public record SeriesUpsertRequest(
    @NotBlank String title,
    String dek,
    String description,
    String coverImageUrl,
    @NotNull SeriesStatus status
) {
}
