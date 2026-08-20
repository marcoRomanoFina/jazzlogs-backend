package com.jazzlogs.backend.note.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// Caps large but controllable — matches the frontend's write-note modal
// (title/text maxLength on the inputs), enforced here too since the API
// isn't only reachable through that modal.
public record CreateNoteRequest(
    @NotBlank @Size(max = 120) String title,
    @NotBlank @Size(max = 5000) String text,
    Integer timestampSeconds
) {
}
