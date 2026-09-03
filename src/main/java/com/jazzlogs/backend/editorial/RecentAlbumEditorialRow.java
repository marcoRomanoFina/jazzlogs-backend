package com.jazzlogs.backend.editorial;

import java.util.UUID;

/**
 * What {@link EditorialSummaryRepository#findRecentAlbums} actually
 * selects — {@link com.jazzlogs.backend.editorial.dto.RecentAlbumEditorialDto}
 * minus {@code likedByCurrentUser}, which {@code EditorialService} adds
 * after (via a separate batched {@code LikeService} lookup, not part of
 * this projection).
 */
public record RecentAlbumEditorialRow(
    UUID id,
    UUID albumId,
    UUID artistId,
    String imageUrl,
    String title,
    String albumName,
    String artistName,
    String dek,
    String byline,
    String logNumber,
    int likeCount
) {
}
