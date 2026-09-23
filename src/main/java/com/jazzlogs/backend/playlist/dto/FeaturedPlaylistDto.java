package com.jazzlogs.backend.playlist.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.jazzlogs.backend.graph.VocabularyTag;
import com.jazzlogs.backend.playlist.PlaylistType;
import com.jazzlogs.backend.series.SeriesVoice;

// Same fields as PlaylistDetailDto, full track list included (unlike the
// lean PlaylistSummaryDto every other playlist listing uses) — see
// FeaturedPlaylistTrackDto for how each track differs from the ordinary
// detail's PlaylistTrackDetailDto (no albumImageUrl).
public record FeaturedPlaylistDto(
    UUID id,
    String title,
    String tagline,
    String description,
    String coverImageUrl,
    String spotifyUrl,
    PlaylistType type,
    SeriesVoice byline,
    boolean published,
    int likeCount,
    boolean likedByCurrentUser,
    boolean savedByCurrentUser,
    int trackCount,
    long durationMs,
    List<FeaturedPlaylistTrackDto> tracks,
    List<VocabularyTag> styleTags,
    List<VocabularyTag> moodTags,
    List<VocabularyTag> contextTags,
    List<VocabularyTag> featuredInstruments,
    Instant createdAt,
    Instant updatedAt
) {
}
