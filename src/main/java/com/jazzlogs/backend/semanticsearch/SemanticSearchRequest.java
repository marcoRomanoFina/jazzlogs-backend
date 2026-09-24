package com.jazzlogs.backend.semanticsearch;

import java.util.List;
import java.util.UUID;

import com.jazzlogs.backend.album.Level;
import com.jazzlogs.backend.chat.CatalogItemType;
import com.jazzlogs.backend.editorial.BlockContentCategory;

/**
 * {@code entityType} is singular and required, not per-candidate: {@code
 * candidateIds} is meant to already be homogeneous (typically copied
 * straight from one graphFilter call, which itself only ever returns Track
 * candidates now). Forcing one type per semanticSearch call keeps the
 * model's candidate set consistent even when it builds one by hand without
 * graphFilter.
 *
 * <p>{@code candidateIds} is required and never null (an empty list is
 * valid — it just means "nothing to search", see {@link
 * SemanticSearchService}'s fast-empty path) — {@code SemanticSearchTool}
 * owns rejecting a genuinely missing/null list before this is ever
 * constructed.
 *
 * <p>{@code category} is deliberately singular and required: comparing
 * blocks from different categories within the same similarity ranking
 * would mix semantically unrelated content, so the model makes separate
 * calls per category instead. {@code entityType} must be TRACK — only
 * tracks have an editorial to search — and {@code energy}/{@code
 * accessibility}/{@code moodIntensity} are optional extra filters on the
 * track. {@code albumId}/{@code artistId} optionally narrow the search to
 * one album's/artist's own tracks, same scoping idea as {@code
 * GraphFilterFilters}. See {@link SemanticSearchService}.
 */
public record SemanticSearchRequest(
    CatalogItemType entityType,
    List<UUID> candidateIds,
    BlockContentCategory category,
    Level energy,
    Level accessibility,
    Level moodIntensity,
    UUID albumId,
    UUID artistId,
    String queryText
) {
}
