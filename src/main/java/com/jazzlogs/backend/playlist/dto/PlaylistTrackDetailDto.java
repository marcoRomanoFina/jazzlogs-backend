package com.jazzlogs.backend.playlist.dto;

import java.math.BigDecimal;
import java.util.UUID;

// avgRating is null / ratingCount is 0 when no one has rated this track yet.
public record PlaylistTrackDetailDto(
    UUID trackId,
    String trackName,
    Integer durationMs,
    UUID albumId,
    String albumName,
    String albumImageUrl,
    UUID artistId,
    String artistName,
    int position,
    String title,
    String curatorNote,
    BigDecimal avgRating,
    long ratingCount
) {
}
