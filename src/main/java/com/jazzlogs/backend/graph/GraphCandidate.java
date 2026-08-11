package com.jazzlogs.backend.graph;

import java.util.List;
import java.util.UUID;

import com.jazzlogs.backend.chat.CatalogItemType;

// entityId is the bridge `id` property on the Neo4j node (points back to the
// real Postgres row) — never Neo4j's own internal id(). matchedDimensions
// lists exactly which (dimension, code) pairs matched — never empty: rows
// with no matches are excluded in Cypher (see GraphService's per-label
// candidate finders), matching graphFilter's permissive-matching rule
// (matching at least one requested dimension is enough to be eligible; the
// count only affects ranking, via matchedDimensions.size(), computed at sort
// time in GraphFilterService rather than stored as a separate field).
public record GraphCandidate(CatalogItemType entityType, UUID entityId, List<MatchedDimension> matchedDimensions) {
}
