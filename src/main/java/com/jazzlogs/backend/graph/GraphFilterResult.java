package com.jazzlogs.backend.graph;

import java.util.List;

// candidates is already sorted DESC by topoScore and clamped to topK — see
// GraphFilterService.filter. Shaped so a later semanticSearch tool can take
// this straight as its own candidate-set input.
public record GraphFilterResult(List<GraphCandidate> candidates) {
}
