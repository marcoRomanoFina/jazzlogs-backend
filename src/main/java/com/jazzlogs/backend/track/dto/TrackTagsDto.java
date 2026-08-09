package com.jazzlogs.backend.track.dto;

import java.util.List;

import com.jazzlogs.backend.graph.VocabularyTag;

// Lean read for the admin tags tool — lets it preload what's already tagged
// before a PUT (full replace) overwrites it. Track has no general detail
// endpoint (no GET /tracks/{id}), so this is purpose-built rather than a
// slice of a bigger DTO.
public record TrackTagsDto(
    List<VocabularyTag> moods,
    List<VocabularyTag> contexts,
    List<VocabularyTag> rhythms,
    List<VocabularyTag> instruments
) {
}
