package com.jazzlogs.backend.track;

import java.math.BigDecimal;
import java.util.List;

import com.jazzlogs.backend.editorial.dto.TrackEditorialDto;
import com.jazzlogs.backend.graph.TrackPerformerEntry;
import com.jazzlogs.backend.graph.TrackPlacement;
import com.jazzlogs.backend.graph.VocabularyTag;

/**
 * Everything {@code TrackService.toTrackDto} needs for one track — placement,
 * editorial, performers, vocabulary tags, rating stats, and the current
 * user's own rating/listen/save state. Notes are NOT here — they're their
 * own paginated per-track endpoint (GET /tracks/{id}/notes).
 */
public record TrackBatchContext(
    TrackPlacement placement,
    TrackEditorialDto editorial,
    List<TrackPerformerEntry> performers,
    List<VocabularyTag> styles,
    List<VocabularyTag> moods,
    List<VocabularyTag> contexts,
    List<VocabularyTag> rhythms,
    List<VocabularyTag> featuredInstruments,
    BigDecimal avgRating,
    long ratingCount,
    BigDecimal myRating,
    boolean hasListened,
    boolean isSaved
) {
}
