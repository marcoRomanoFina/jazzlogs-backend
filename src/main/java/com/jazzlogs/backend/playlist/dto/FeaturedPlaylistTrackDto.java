package com.jazzlogs.backend.playlist.dto;

import java.math.BigDecimal;
import java.util.UUID;

// Same as PlaylistTrackDetailDto, minus albumImageUrl and myNotes — the
// featured playlist's track list doesn't need per-track artwork or notes.
public record FeaturedPlaylistTrackDto(
    UUID trackId,
    String trackName,
    Integer durationMs,
    String spotifyUrl,
    UUID albumId,
    String albumName,
    UUID artistId,
    String artistName,
    int position,
    String title,
    String curatorNote,
    BigDecimal avgRating,
    long ratingCount,
    BigDecimal myRating,
    boolean listenedByCurrentUser
) {
}
