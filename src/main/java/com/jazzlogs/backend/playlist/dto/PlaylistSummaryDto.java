package com.jazzlogs.backend.playlist.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.jazzlogs.backend.graph.VocabularyTag;
import com.jazzlogs.backend.playlist.PlaylistType;

// One shape for every playlist listing that isn't a single-item detail page:
// GET /playlists, /playlists/journey, /playlists/journeys, /playlists/standard,
// /playlists/catalogue. No tracklist (see PlaylistDetailDto for that) — every
// one of those reads is a card/list row, not a full playlist page.
public record PlaylistSummaryDto(
    UUID id,
    String title,
    String tagline,
    String description,
    String coverImageUrl,
    String spotifyUrl,
    PlaylistType type,
    boolean published,
    int likeCount,
    boolean likedByCurrentUser,
    int trackCount,
    long durationMs,
    List<VocabularyTag> styleTags,
    List<VocabularyTag> moodTags,
    List<VocabularyTag> contextTags,
    Instant createdAt,
    Instant updatedAt
) {
}
