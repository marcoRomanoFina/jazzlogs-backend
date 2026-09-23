package com.jazzlogs.backend.playlist.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.jazzlogs.backend.playlist.PlaylistType;
import com.jazzlogs.backend.series.SeriesVoice;

// Metadata only — no tracklist here. Tracks are managed one at a time via
// POST/DELETE/PATCH /playlists/{id}/tracks and PUT /playlists/{id}/tracks/reorder,
// not as part of this upsert. No published field either — every playlist
// starts a draft and publish state only ever changes via POST/DELETE
// /playlists/{id}/publish, same reasoning as featured/cover having their own
// endpoints instead of living on this upsert. type, unlike published, IS
// part of this upsert — it's a plain classification, not a guarded state
// transition, so it doesn't need its own endpoint. styleCodes/moodCodes/contextCodes/
// instrumentCodes: null/omitted is treated as an empty list (clears that
// vocabulary), validated against StyleVocabulary/MoodVocabulary/ContextVocabulary/
// InstrumentVocabulary before anything is written — see PlaylistService.replaceTags.
public record PlaylistUpsertRequest(
    @NotBlank String title,
    String tagline,
    String description,
    String coverImageUrl,
    String spotifyUrl,
    @NotNull PlaylistType type,
    @NotNull SeriesVoice byline,
    List<String> styleCodes,
    List<String> moodCodes,
    List<String> contextCodes,
    List<String> instrumentCodes
) {
}
