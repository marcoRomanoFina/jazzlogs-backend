package com.jazzlogs.backend.playlist.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateTrackNoteRequest(@NotBlank String title, @NotBlank String curatorNote) {
}
