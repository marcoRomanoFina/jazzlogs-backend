package com.jazzlogs.backend.album.dto;

import java.util.UUID;

// Only tracks that have their own editorial are included — see
// AlbumService.getAlbumSpotlight.
public record SpotlightTrackDto(
    UUID id,
    Integer trackNumber,
    String name,
    String editorialTitle,
    String editorialDek,
    int editorialLikeCount,
    boolean editorialLikedByCurrentUser
) {
}
