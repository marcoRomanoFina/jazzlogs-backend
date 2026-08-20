package com.jazzlogs.backend.graph;

import java.util.List;
import java.util.UUID;

import com.jazzlogs.backend.chat.CatalogItemType;

// entityId is the bridge `id` property on the Neo4j node (points back to the
// real Postgres row) — never Neo4j's own internal id(). entityName is that
// same node's `name` property — every graph node carries one for exactly
// this reason (readability), so graphFilter can be used standalone (per its
// own tool description) without forcing a semanticSearch follow-up just to
// find out what a candidate is actually called. matchedDimensions lists
// exactly which (dimension, code) pairs matched — never empty: rows with no
// matches are excluded in Cypher (see GraphService's per-type candidate
// finders), matching graphFilter's permissive-matching rule (matching at
// least one requested dimension is enough to be eligible; the count only
// affects ranking, via matchedDimensions.size(), used directly in each
// finder's own "ORDER BY matchCount DESC" — GraphFilterService never
// recomputes or re-sorts it).
public record GraphCandidate(CatalogItemType entityType, UUID entityId, String entityName, List<MatchedDimension> matchedDimensions) {
}
