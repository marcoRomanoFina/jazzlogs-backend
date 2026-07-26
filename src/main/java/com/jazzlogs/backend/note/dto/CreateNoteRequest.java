package com.jazzlogs.backend.note.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateNoteRequest(@NotBlank String text, Integer timestampSeconds) {
}
