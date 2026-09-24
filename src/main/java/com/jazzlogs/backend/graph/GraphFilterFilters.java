package com.jazzlogs.backend.graph;

import java.util.List;
import java.util.UUID;

import com.jazzlogs.backend.vocabulary.ContextVocabulary;
import com.jazzlogs.backend.vocabulary.InstrumentVocabulary;
import com.jazzlogs.backend.vocabulary.MoodVocabulary;
import com.jazzlogs.backend.vocabulary.RhythmVocabulary;
import com.jazzlogs.backend.vocabulary.StyleVocabulary;

/**
 * Already-validated vocabulary codes as real enum values, not raw strings —
 * {@code GraphFilterTool} rejects invalid codes before this is built.
 * Always a TRACK search — Album/Artist are no longer independently
 * recommendable, only useful as an optional scope: {@code albumId}/{@code
 * artistId}, when set, narrow the search to one album's/artist's own
 * tracks (see {@code GraphService#findTrackCandidates}). {@code userId}
 * isn't a field here since it's the authenticated user, not something the
 * model controls — see {@link GraphFilterService#filter}.
 */
public record GraphFilterFilters(
    List<StyleVocabulary> styles,
    List<RhythmVocabulary> rhythms,
    List<MoodVocabulary> moods,
    List<ContextVocabulary> contexts,
    List<InstrumentVocabulary> instruments,
    UUID albumId,
    UUID artistId,
    Boolean excludeListened,
    Boolean excludeAlreadyRated,
    Integer topK
) {
}
