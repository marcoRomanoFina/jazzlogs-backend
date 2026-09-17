package com.jazzlogs.backend.playlist.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.jazzlogs.backend.graph.VocabularyTag;

// Same playlist-level fields as PlaylistDetailDto — only tracks differs,
// using the leaner FeaturedPlaylistTrackDto instead of PlaylistTrackDetailDto.
public record FeaturedPlaylistDto(
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
    List<FeaturedPlaylistTrackDto> tracks,
    List<VocabularyTag> styleTags,
    List<VocabularyTag> moodTags,
    List<VocabularyTag> contextTags,
    Instant createdAt,
    Instant updatedAt
) {
}
