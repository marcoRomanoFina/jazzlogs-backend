package com.jazzlogs.backend.playlist.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

// Metadata only — no tracklist here. Tracks are managed one at a time via
// POST/DELETE/PATCH /playlists/{id}/tracks and PUT /playlists/{id}/tracks/reorder,
// not as part of this upsert. No published field either — every playlist
// starts a draft and publish state only ever changes via POST/DELETE
// /playlists/{id}/publish, same reasoning as featured/cover having their own
// endpoints instead of living on this upsert. styleCodes/moodCodes/contextCodes:
// null/omitted is treated as an empty list (clears that vocabulary), validated
// against StyleVocabulary/MoodVocabulary/ContextVocabulary before anything is
// written — see PlaylistService.replaceTags.
public record PlaylistUpsertRequest(
    @NotBlank String slug,
    @NotBlank String title,
    String tagline,
    String description,
    String coverImageUrl,
    String spotifyUrl,
    List<String> styleCodes,
    List<String> moodCodes,
    List<String> contextCodes
) {
}
