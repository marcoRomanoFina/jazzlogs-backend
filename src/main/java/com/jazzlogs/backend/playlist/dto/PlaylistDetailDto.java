package com.jazzlogs.backend.playlist.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.jazzlogs.backend.graph.VocabularyTag;

// trackCount/durationMs are denormalized on the Playlist entity, kept in sync
// by PlaylistService.replaceTracklist. Tags are read from Neo4j, not Postgres —
// same VocabularyTag(code, label) shape Album's getStyles/getMoods/getContexts
// already return.
public record PlaylistDetailDto(
    UUID id,
    String slug,
    String title,
    String tagline,
    String description,
    String coverImageUrl,
    String spotifyUrl,
    boolean published,
    int likeCount,
    boolean likedByCurrentUser,
    int trackCount,
    long durationMs,
    List<PlaylistTrackDetailDto> tracks,
    List<VocabularyTag> styleTags,
    List<VocabularyTag> moodTags,
    List<VocabularyTag> contextTags,
    Instant createdAt,
    Instant updatedAt
) {
}
