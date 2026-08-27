package com.jazzlogs.backend.graph;

import java.util.List;

import com.jazzlogs.backend.chat.CatalogItemType;
import com.jazzlogs.backend.vocabulary.ContextVocabulary;
import com.jazzlogs.backend.vocabulary.InstrumentVocabulary;
import com.jazzlogs.backend.vocabulary.MoodVocabulary;
import com.jazzlogs.backend.vocabulary.RhythmVocabulary;
import com.jazzlogs.backend.vocabulary.StyleVocabulary;

/**
 * Already-validated vocabulary codes as real enum values, not raw strings —
 * {@code GraphFilterTool} rejects invalid codes before this is built.
 * {@code entityType} is singular and required: Album/Track/Artist connect to
 * vocabulary through different relationships, so mixing their candidates
 * into one ranked list would compare matches that aren't the same kind of
 * signal. {@code userId} isn't a field here since it's the authenticated
 * user, not something the model controls — see {@link GraphFilterService#filter}.
 */
public record GraphFilterFilters(
    CatalogItemType entityType,
    List<StyleVocabulary> styles,
    List<RhythmVocabulary> rhythms,
    List<MoodVocabulary> moods,
    List<ContextVocabulary> contexts,
    List<InstrumentVocabulary> instruments,
    Boolean excludeListened,
    Boolean excludeAlreadyRated,
    Integer topK
) {
}
