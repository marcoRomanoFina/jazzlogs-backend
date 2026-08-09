package com.jazzlogs.backend.editorial.dto;

import java.time.Instant;
import java.util.UUID;

import com.jazzlogs.backend.editorial.EditorialOwnerType;

public record EditorialSummaryDto(
    UUID id,
    EditorialOwnerType type,
    UUID ownerId,
    String ownerName,
    String ownerImageUrl,
    String title,
    String dek,
    String byline,
    Instant createdAt,
    int likeCount,
    boolean likedByCurrentUser,
    boolean featurated,
    String contextName,
    Integer releaseYear,
    String previewText,
    UUID contextId
) {
}
