package com.jazzlogs.backend.vocabulary;

// Which controlled-vocabulary graph relationship EDITORIAL_SEARCH's
// vocabularyFilter prefilter targets — see
// com.jazzlogs.backend.agent.tools.VocabularyEditorialResolver for how each
// value maps to a Neo4j relationship per entity type (not every type applies
// to every CatalogItemType — e.g. RHYTHM is Track-only).
public enum VocabularyFilterType {
    STYLE,
    RHYTHM,
    MOOD,
    CONTEXT,
    INSTRUMENT
}
