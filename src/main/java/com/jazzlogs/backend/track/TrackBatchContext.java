package com.jazzlogs.backend.track;

import java.math.BigDecimal;
import java.util.List;

import com.jazzlogs.backend.editorial.dto.TrackEditorialDto;
import com.jazzlogs.backend.graph.TrackPerformerEntry;
import com.jazzlogs.backend.graph.TrackPlacement;
import com.jazzlogs.backend.graph.VocabularyTag;

/**
 * Everything TrackService.toDto needs for one track, pre-fetched in bulk for
 * a whole album — see AlbumService.getAlbumTracks, which batches each of
 * these (placement, editorial, performers, moods, contexts, rhythms,
 * featured instruments, rating stats, my rating) in one query per album
 * instead of N per track. Notes are NOT here — they're their own paginated
 * per-track endpoint (GET /tracks/{id}/notes), not something this bulk
 * album-tracks load batches.
 */
public record TrackBatchContext(
    TrackPlacement placement,
    TrackEditorialDto editorial,
    List<TrackPerformerEntry> performers,
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
