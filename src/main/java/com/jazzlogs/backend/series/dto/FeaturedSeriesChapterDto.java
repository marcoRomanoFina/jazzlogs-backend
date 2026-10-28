package com.jazzlogs.backend.series.dto;

// Only what the featured section renders per chapter — not the full
// SeriesChapterDetailDto fan-out (no audio, images, status, etc.).
public record FeaturedSeriesChapterDto(String title, String note) {
}
