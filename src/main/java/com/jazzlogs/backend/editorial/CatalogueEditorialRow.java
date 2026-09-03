package com.jazzlogs.backend.editorial;

import java.time.Instant;
import java.util.UUID;

/**
 * What {@link EditorialSummaryRepository#searchLean} actually selects —
 * {@link com.jazzlogs.backend.editorial.dto.CatalogueEditorialDto} minus
 * {@code likedByCurrentUser}, which {@code EditorialService} adds after
 * (via a separate batched {@code LikeService} lookup, not part of this
 * projection).
 */
public record CatalogueEditorialRow(
    UUID id,
    EditorialOwnerType type,
    UUID ownerId,
    String ownerName,
    String ownerImageUrl,
    String contextName,
    UUID contextId,
    String title,
    String dek,
    String byline,
    Instant createdAt,
    String logNumber,
    int likeCount
) {
}
