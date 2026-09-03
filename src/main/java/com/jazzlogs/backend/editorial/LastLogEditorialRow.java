package com.jazzlogs.backend.editorial;

import java.time.Instant;
import java.util.UUID;

/**
 * What {@link EditorialSummaryRepository#findLatestAlbum} actually selects —
 * everything {@link com.jazzlogs.backend.editorial.dto.LastLogDto} needs
 * except {@code likedByCurrentUser} and {@code tracks}, which {@code
 * EditorialService} adds after (a separate {@code TrackEditorialRepository}
 * + {@code GraphService} lookup, keyed off {@code albumId} here).
 */
public record LastLogEditorialRow(
    UUID id,
    UUID albumId,
    String title,
    String artistName,
    String dek,
    String byline,
    Integer releaseYear,
    Instant postedAt,
    String imageUrl,
    int likeCount
) {
}
