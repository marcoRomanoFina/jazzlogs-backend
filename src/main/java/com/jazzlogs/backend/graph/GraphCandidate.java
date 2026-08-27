package com.jazzlogs.backend.graph;

import java.util.List;
import java.util.UUID;

import com.jazzlogs.backend.chat.CatalogItemType;

/**
 * {@code entityId} is the bridge {@code id} property on the Neo4j node
 * (points back to the real Postgres row) — never Neo4j's own internal
 * {@code id()}. {@code entityName} is that same node's {@code name}
 * property — every graph node carries one so graphFilter can be used
 * standalone, without forcing a semanticSearch follow-up just to find out
 * what a candidate is called. {@code matchedDimensions} lists exactly which
 * (dimension, code) pairs matched — never empty: rows with no matches are
 * excluded in Cypher (see {@code GraphService}'s per-type candidate
 * finders), matching graphFilter's permissive-matching rule (one matched
 * dimension is enough to be eligible; the count only affects ranking, via
 * {@code matchedDimensions.size()}, used directly in each finder's own
 * "ORDER BY matchCount DESC" — {@link GraphFilterService} never recomputes
 * or re-sorts it).
 */
public record GraphCandidate(CatalogItemType entityType, UUID entityId, String entityName, List<MatchedDimension> matchedDimensions) {
}
