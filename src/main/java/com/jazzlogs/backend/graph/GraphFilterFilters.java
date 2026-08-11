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
// rejecting invalid codes before they ever reach here). entityTypes
// null/empty means every type (see GraphFilterService.DEFAULT_ENTITY_TYPES).
// userId deliberately isn't a field here — it's the authenticated user, not
// something the model controls, so it's a separate parameter on
// GraphFilterService.filter instead.
public record GraphFilterFilters(
    List<CatalogItemType> entityTypes,
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
