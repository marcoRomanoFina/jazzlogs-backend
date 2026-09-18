package com.jazzlogs.backend.playlist.dto;

import java.math.BigDecimal;
import java.util.UUID;

// Leaner than PlaylistTrackDetailDto — no albumId/artistId/durationMs/title,
// just enough to render a row and link out (trackId, the album's editorial,
// if any). avgRating is null when no one has rated this track yet.
public record FeaturedPlaylistTrackDto(
    UUID trackId,
    String trackName,
    UUID albumEditorialId,
    String albumName,
    String artistName,
    String imageUrl,
    int position,
    BigDecimal avgRating
) {
}
