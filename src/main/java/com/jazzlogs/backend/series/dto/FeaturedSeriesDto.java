package com.jazzlogs.backend.series.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.jazzlogs.backend.graph.VocabularyTag;
import com.jazzlogs.backend.series.SeriesStatus;
import com.jazzlogs.backend.series.SeriesVoice;

// Same fields as SeriesSummaryDto, plus a lean chapter list (title/note only)
// — the one place a chapter list shows up outside the full series detail.
public record FeaturedSeriesDto(
    UUID id,
    String title,
    String dek,
    String coverImageUrl,
    SeriesStatus status,
    SeriesVoice voice,
    int likeCount,
    List<FeaturedSeriesChapterDto> chapters,
    List<VocabularyTag> styleTags,
    List<VocabularyTag> moodTags,
    List<VocabularyTag> contextTags,
    List<VocabularyTag> featuredInstruments,
    Instant createdAt
) {
}
