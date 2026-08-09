package com.jazzlogs.backend.album.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Purpose-built for the archive page's "last log" spotlight — unlike
// AlbumDetailDto, this pulls no Neo4j data at all except track ordering
// (performers/moods/contexts/rhythms/instruments/personnel/tags aren't shown
// there) and no editorial blocks (dek/byline is all the teaser needs).
public record AlbumSpotlightDto(
    UUID id,
    String artistName,
    String name,
    String imageUrl,
    Integer releaseYear,
    Instant postedAt,
    String editorialTitle,
    String editorialDek,
    String editorialByline,
    int editorialLikeCount,
    boolean editorialLikedByCurrentUser,
    List<SpotlightTrackDto> tracks
) {
}
