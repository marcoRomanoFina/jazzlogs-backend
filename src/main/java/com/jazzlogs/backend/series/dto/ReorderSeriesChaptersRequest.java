package com.jazzlogs.backend.series.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

// chapterIds must contain exactly the chapters already in the series (same
// set, no more, no less, no duplicates) — position is the array index. See
// SeriesService.reorderChapters.
public record ReorderSeriesChaptersRequest(
    @NotNull List<UUID> chapterIds
) {
}
