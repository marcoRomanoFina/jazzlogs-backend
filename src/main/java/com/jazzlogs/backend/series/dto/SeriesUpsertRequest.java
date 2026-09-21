package com.jazzlogs.backend.series.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.jazzlogs.backend.series.SeriesVoice;

// Metadata only — chapters are managed via their own granular endpoints
// (addChapter/removeChapter/updateChapter/reorderChapters), same shape as Playlist.
// No status here on purpose — every series starts DRAFT, changed only via
// POST/DELETE /series/{id}/publish, same pattern as Playlist.published.
// No coverImageUrl either — the cover is an uploaded file, set via
// PUT /series/{id}/cover (SeriesService.setCoverImage), never a raw URL the
// client hands us. voice, unlike status, IS part of this upsert — it's a
// plain classification, not a guarded state transition (same reasoning as
// Playlist.type).
public record SeriesUpsertRequest(
    @NotBlank String title,
    String dek,
    String description,
    @NotNull SeriesVoice voice
) {
}
