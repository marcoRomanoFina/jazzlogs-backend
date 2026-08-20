package com.jazzlogs.backend.graph;

import java.util.List;

// candidates is already sorted DESC by matchedDimensions.size() and clamped
// to topK — both done in Cypher (see GraphService's per-type finders),
// GraphFilterService just passes the result through. Shaped so the
// semanticSearch tool can take entityType + entityIds straight from this as
// its own candidate-set input (see SemanticSearchRequest).
public record GraphFilterResult(List<GraphCandidate> candidates) {
}
