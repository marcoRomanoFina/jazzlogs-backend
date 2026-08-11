package com.jazzlogs.backend.graph;

// Which of the 5 controlled-vocabulary axes a graphFilter match belongs to —
// see MatchedDimension. Not every entity type connects to every dimension
// (e.g. Track has no Style relation, Artist has no Mood/Rhythm relation);
// GraphService's per-label Cypher queries only ever emit the dimensions that
// actually apply to that label.
public enum VocabularyDimension {
    STYLE, RHYTHM, MOOD, CONTEXT, INSTRUMENT
}
