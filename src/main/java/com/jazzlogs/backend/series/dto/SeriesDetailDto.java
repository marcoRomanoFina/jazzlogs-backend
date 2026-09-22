package com.jazzlogs.backend.series.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.jazzlogs.backend.graph.VocabularyTag;
import com.jazzlogs.backend.series.SeriesStatus;
import com.jazzlogs.backend.series.SeriesVoice;

// totalListenings is computed on-demand (COUNT across every chapter's listens,
// all users) — same criterio as Album's avg rating, never denormalized.
public record SeriesDetailDto(
    UUID id,
    String title,
    String dek,
    String description,
    String coverImageUrl,
    SeriesStatus status,
    SeriesVoice voice,
    int likeCount,
    boolean likedByCurrentUser,
    long totalListenings,
    List<SeriesChapterDetailDto> chapters,
    List<VocabularyTag> styleTags,
    List<VocabularyTag> moodTags,
    List<VocabularyTag> contextTags,
    List<VocabularyTag> featuredInstruments,
    Instant createdAt,
    Instant updatedAt
) {
}
