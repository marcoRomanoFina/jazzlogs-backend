package com.jazzlogs.backend.semanticsearch;

import java.util.List;
import java.util.UUID;

import com.jazzlogs.backend.album.Level;
import com.jazzlogs.backend.chat.CatalogItemType;
import com.jazzlogs.backend.editorial.BlockContentCategory;

// entityType is singular and required, not per-candidate: candidateIds is
// meant to already be homogeneous (typically copied straight from one
// graphFilter call, which itself only ever returns one entity type — see
// GraphFilterFilters). Forcing one type per semanticSearch call too avoids
// resolving a mixed Album/Track/Artist candidate set through three
// different joins in the same query, and keeps the model's candidate set
// consistent even when it builds one by hand without graphFilter.
// candidateIds is required and never null (an empty list is valid — it
// just means "nothing to search", see SemanticSearchService's fast-empty
// path) — SemanticSearchTool owns rejecting a genuinely missing/null list
// before this is ever constructed. category is deliberately singular and
// required: comparing blocks from different categories within the same
// similarity ranking would mix semantically unrelated content, so the
// model makes separate calls per category instead. energy/accessibility/
// moodIntensity only apply when entityType is ALBUM or TRACK (Artist has
// none of these columns) — see SemanticSearchService.
public record SemanticSearchRequest(
    CatalogItemType entityType,
    List<UUID> candidateIds,
    BlockContentCategory category,
    Level energy,
    Level accessibility,
    Level moodIntensity,
    String queryText
) {
}
