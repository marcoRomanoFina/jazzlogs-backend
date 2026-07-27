package com.jazzlogs.backend.playlist.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

// Metadata only — no tracklist here. Tracks are managed one at a time via
// POST/DELETE/PATCH /playlists/{id}/tracks and PUT /playlists/{id}/tracks/reorder,
// not as part of this upsert. styleCodes/moodCodes/contextCodes: null/omitted is
// treated as an empty list (clears that vocabulary), validated against
// StyleVocabulary/MoodVocabulary/ContextVocabulary before anything is written —
// see PlaylistService.replaceTags.
public record PlaylistUpsertRequest(
    @NotBlank String slug,
    @NotBlank String title,
    String tagline,
    String description,
    String coverImageUrl,
    String spotifyUrl,
    boolean published,
    List<String> styleCodes,
    List<String> moodCodes,
    List<String> contextCodes
) {
}
