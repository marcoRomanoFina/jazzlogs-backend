package com.jazzlogs.backend.editorial.dto;

import java.time.Instant;
import java.util.UUID;

import com.jazzlogs.backend.editorial.EditorialOwnerType;
import com.jazzlogs.backend.editorial.EditorialSummary;

/**
 * Same shape as {@link EditorialSummary} (its fields map over 1:1 — see
 * that class for what each one means), plus {@code likedByCurrentUser}:
 * computed per-request via {@code LikeService}, since "did I like this"
 * depends on who's asking and can't live in the shared view.
 */
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
