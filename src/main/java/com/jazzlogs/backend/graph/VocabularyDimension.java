package com.jazzlogs.backend.graph;

/**
 * The five controlled vocabularies a track can be tagged with in the graph —
 * what {@link GraphService#findTracksByTags} searches by and reports its
 * matches under.
 */
public enum VocabularyDimension {
    STYLE, RHYTHM, MOOD, CONTEXT, INSTRUMENT
}
