package com.jazzlogs.backend.graph;

import java.util.List;

/**
 * {@code candidates} is already sorted DESC by {@code
 * matchedDimensions.size()} and clamped to topK — both done in Cypher (see
 * {@code GraphService}'s per-type finders), {@link GraphFilterService} just
 * passes the result through. Shaped so the semanticSearch tool can take
 * entityType + entityIds straight from this as its own candidate-set input
 * (see {@code SemanticSearchRequest}).
 */
public record GraphFilterResult(List<GraphCandidate> candidates) {
}
