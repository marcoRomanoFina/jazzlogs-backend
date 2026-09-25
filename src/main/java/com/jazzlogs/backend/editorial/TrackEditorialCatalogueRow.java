package com.jazzlogs.backend.editorial;

import java.time.Instant;
import java.util.UUID;

/** What {@link TrackEditorialRepository#searchCatalogue} selects — everything the catalogue DTO needs except {@code likedByCurrentUser}. */
public record TrackEditorialCatalogueRow(
    UUID id,
    UUID trackId,
    String trackName,
    String editorialCoverUrl,
    String albumName,
    UUID albumId,
    String artistName,
    String title,
    String dek,
    EditorialByline byline,
    Instant createdAt,
    int likeCount
) {
}
