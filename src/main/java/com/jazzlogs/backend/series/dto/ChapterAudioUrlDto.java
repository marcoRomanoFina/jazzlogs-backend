package com.jazzlogs.backend.series.dto;

// A short-lived, presigned URL — see SeriesService.getChapterAudioUrl. Not
// meant to be cached client-side past its own expiry.
public record ChapterAudioUrlDto(String url) {
}
