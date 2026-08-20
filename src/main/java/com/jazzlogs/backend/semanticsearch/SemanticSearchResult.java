package com.jazzlogs.backend.semanticsearch;

import java.util.List;

// matches is sorted DESC by similarityScore — see SemanticSearchService.
public record SemanticSearchResult(List<ScoredBlock> matches) {
}
