package com.jazzlogs.backend.editorial.dto;

import java.time.Instant;
import java.util.UUID;

import com.jazzlogs.backend.editorial.EditorialOwnerType;
import com.jazzlogs.backend.editorial.EditorialSummary;

/**
 * "The Catalogue"'s own lean shape — a subset of {@link EditorialSummary}'s
 * fields, deliberately missing {@code featurated}/{@code releaseYear}/{@code
 * previewText}: the grid doesn't render any of them (only the {@code
 * /featured} hero shows {@code previewText}). {@code
 * EditorialSummaryRepository}'s JPQL constructor expression only selects
 * these columns, so Postgres never evaluates {@code previewText}'s
 * per-row {@code LATERAL} block lookup for this query.
 *
 * @param likedByCurrentUser computed separately via {@code LikeService},
 *                           since "did I like this" depends on who's asking
 * @param contextName        one hop past the owner: the artist's name for
 *                           an album, the album's name for a track, {@code
 *                           null} for an artist
 * @param contextId          same idea as {@code contextName} but the id —
 *                           for deep-linking a track editorial into its
 *                           album's editorial page
 * @param logNumber          the album's catalog/release identifier — the
 *                           track's own album for a track, {@code null}
 *                           for an artist (no album to point at)
 */
public record CatalogueEditorialDto(
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
    int likeCount,
    boolean likedByCurrentUser
) {
}
