package com.jazzlogs.backend.track;

import java.math.BigDecimal;
import java.util.List;

import com.jazzlogs.backend.editorial.dto.TrackEditorialDto;
import com.jazzlogs.backend.graph.TrackPerformerEntry;
import com.jazzlogs.backend.graph.TrackPlacement;
import com.jazzlogs.backend.graph.VocabularyTag;
import com.jazzlogs.backend.note.dto.NoteDto;

/**
 * Everything TrackService.toDto needs for one track, pre-fetched in bulk for
 * a whole album — see AlbumService.getAlbumDetail, which batches each of
 * these (placement, notes, editorial, performers, moods, contexts, rhythms,
 * featured instruments, rating stats, my rating) in one query per album
 * instead of N per track.
 */
public record TrackBatchContext(
    TrackPlacement placement,
    List<NoteDto> myNotes,
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
