package com.jazzlogs.backend.graph;

/**
 * One controlled-vocabulary tag (style, mood, context, rhythm, ...) as read
 * back from Neo4j.
 *
 * @param code  the vocabulary's own stable code (see {@code VocabularyCodes})
 * @param label the human-readable display text for {@code code}
 */
public record VocabularyTag(String code, String label) {
}
