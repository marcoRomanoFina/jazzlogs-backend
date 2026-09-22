package com.jazzlogs.backend.series.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.jazzlogs.backend.graph.VocabularyTag;
import com.jazzlogs.backend.series.SeriesStatus;
import com.jazzlogs.backend.series.SeriesVoice;

public record SeriesSummaryDto(
    UUID id,
    String title,
    String dek,
    String coverImageUrl,
    SeriesStatus status,
    SeriesVoice voice,
    int likeCount,
    List<VocabularyTag> styleTags,
    List<VocabularyTag> moodTags,
    List<VocabularyTag> contextTags,
    List<VocabularyTag> featuredInstruments,
    Instant createdAt
) {
}
