package com.jazzlogs.backend.graph;

/**
 * One concrete match between a graphFilter candidate and a requested
 * vocabulary filter, e.g. (MOOD, "RELAXED") — {@code code} is the specific
 * enum value that matched, not just which dimension. Kept as real substance
 * (not a bare count) so the LLM can explain *why* a candidate was
 * recommended in its final synthesis, not just cite a number.
 */
public record MatchedDimension(VocabularyDimension dimension, String code) {
}
