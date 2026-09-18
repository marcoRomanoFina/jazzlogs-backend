package com.jazzlogs.backend.playlist.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.jazzlogs.backend.graph.VocabularyTag;
import com.jazzlogs.backend.playlist.PlaylistType;

// Same playlist-level fields as FeaturedPlaylistDto, minus tracks — the
// journey section doesn't render a tracklist, so GET /playlists/journey
// skips that whole fan-out (no playlist_tracks/rating/editorial lookups).
public record JourneyPlaylistDto(
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
