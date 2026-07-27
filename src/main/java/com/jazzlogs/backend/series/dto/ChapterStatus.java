package com.jazzlogs.backend.series.dto;

// Computed per request relative to the current user's listens — never persisted.
// See SeriesService's status-computation helper.
public enum ChapterStatus {
    DONE,
    CURRENT,
    LOCKED
}
