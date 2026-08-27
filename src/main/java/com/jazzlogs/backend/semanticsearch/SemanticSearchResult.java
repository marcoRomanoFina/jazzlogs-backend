package com.jazzlogs.backend.semanticsearch;

import java.util.List;

/** {@code matches} is sorted DESC by similarityScore — see {@link SemanticSearchService}. */
public record SemanticSearchResult(List<ScoredBlock> matches) {
}
