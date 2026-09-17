package com.jazzlogs.backend.playlist.dto;

import java.math.BigDecimal;
import java.util.UUID;

// Leaner than PlaylistTrackDetailDto — the featured section only needs
// enough per track to link out (trackId, the album's editorial, if any) and
// render a row (imageUrl, curatorNote, position, avg rating). avgRating is
// null when no one has rated this track yet.
public record FeaturedPlaylistTrackDto(
    UUID trackId,
    UUID albumEditorialId,
    BigDecimal avgRating,
    String curatorNote,
    int position,
    String imageUrl
) {
}
