package com.jazzlogs.backend.series.dto;

import jakarta.validation.constraints.NotBlank;

// Metadata only — chapters are managed via their own granular endpoints
// (addChapter/removeChapter/updateChapter/reorderChapters), same shape as Playlist.
// No status here on purpose — every series starts DRAFT, changed only via
// POST/DELETE /series/{id}/publish, same pattern as Playlist.published.
public record SeriesUpsertRequest(
    @NotBlank String title,
    String dek,
    String description,
    String coverImageUrl
) {
}
