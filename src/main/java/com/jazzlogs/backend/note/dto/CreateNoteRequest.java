package com.jazzlogs.backend.note.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body to create a note on a track.
 *
 * <p>Caps large but controllable — matches the frontend's write-note modal
 * (title/text maxLength on the inputs), enforced here too since the API
 * isn't only reachable through that modal.
 *
 * @param title            required, up to 120 chars
 * @param text             required, up to 5000 chars
 * @param timestampSeconds optional — a specific moment in the track this note is about
 */
public record CreateNoteRequest(
    @NotBlank @Size(max = 120) String title,
    @NotBlank @Size(max = 5000) String text,
    Integer timestampSeconds
) {
}
