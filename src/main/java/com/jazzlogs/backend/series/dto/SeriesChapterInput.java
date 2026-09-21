package com.jazzlogs.backend.series.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

import com.jazzlogs.backend.series.ChapterType;

// Used for both addChapter (POST) and updateChapter (PATCH) — same field set
// either way. trackId is required for INTRO/TRACK chapters, forbidden for
// OUTRO ones (SeriesService.resolveTrackForType gives a clean 400 for
// either violation). position isn't sent — addChapter appends at the end,
// reordering is a separate endpoint. No audioObjectKey/audioContentType/
// audioFileSizeBytes here — those only ever come from a real upload via
// PUT /series/{id}/chapters/{chapterId}/audio (SeriesService.setChapterAudio),
// same reasoning as the cover/landscape images not living on this input.
// audioDurationSeconds stays here — nothing in this codebase decodes audio to
// derive it from the uploaded file, so it's still admin-supplied metadata.
public record SeriesChapterInput(
    @NotNull ChapterType type,
    UUID trackId,
    String title,
    String note,
    Integer audioDurationSeconds
) {
}
