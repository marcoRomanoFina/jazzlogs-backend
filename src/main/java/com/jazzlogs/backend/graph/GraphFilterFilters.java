package com.jazzlogs.backend.graph;

import java.util.List;

import com.jazzlogs.backend.chat.CatalogItemType;
import com.jazzlogs.backend.vocabulary.ContextVocabulary;
import com.jazzlogs.backend.vocabulary.InstrumentVocabulary;
import com.jazzlogs.backend.vocabulary.MoodVocabulary;
import com.jazzlogs.backend.vocabulary.RhythmVocabulary;
import com.jazzlogs.backend.vocabulary.StyleVocabulary;

// Already-validated vocabulary codes as real enum values, not raw strings —
// GraphFilterTool owns turning the model's JSON args into this shape (and
// rejecting invalid codes before they ever reach here). entityType is
// singular and required, not a list with a "search everything" default:
// Album/Track/Artist connect to vocabulary through different relationships
// (BELONGS_TO vs HAS_STYLE, etc.), so mixing their candidates into one
// topoScore-ranked list would compare matches that aren't really the same
// kind of signal. The model makes one graphFilter call per entity type it
// cares about instead — same reasoning already applied to semanticSearch's
// single-category-per-call rule. userId deliberately isn't a field here —
// it's the authenticated user, not something the model controls, so it's a
// separate parameter on GraphFilterService.filter instead.
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
