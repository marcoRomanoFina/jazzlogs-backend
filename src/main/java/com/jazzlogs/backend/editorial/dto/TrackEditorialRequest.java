package com.jazzlogs.backend.editorial.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.jazzlogs.backend.character.JazzlogsCharacter;

// byline is required — every log is signed by one of the eight narrators,
// there is no unsigned/house default.
public record TrackEditorialRequest(
    @NotBlank String title,
    @NotBlank String logNumber,
    @NotBlank String dek,
    @NotNull JazzlogsCharacter byline,
    List<BlockRequest> blocks
) {
}
