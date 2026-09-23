package com.jazzlogs.backend.series.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.jazzlogs.backend.graph.VocabularyTag;

// No performers/editorial/notes here — same trim as PlaylistTrackDetailDto,
// not the full TrackDto. avgRating/ratingCount are null/0 when no one has
// rated it yet.
public record SeriesChapterTrackDto(
    UUID id,
    String name,
    Integer durationMs,
    String spotifyUrl,
    String imageUrl,
    UUID albumId,
    String albumName,
    UUID artistId,
    String artistName,
    BigDecimal avgRating,
    long ratingCount,
    BigDecimal myRating,
    boolean hasListened,
    List<VocabularyTag> moods,
    List<VocabularyTag> contexts,
    List<VocabularyTag> rhythms,
    List<VocabularyTag> featuredInstruments
) {
}
